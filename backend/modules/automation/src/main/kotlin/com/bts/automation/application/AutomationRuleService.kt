// AutomationRule CRUD 유스케이스 서비스 — MANAGE_AUTOMATION 가드 + 웹훅 토큰 발급 + SCHEDULED nextFireAt 계산 (FR-AT-01 Task 6)

package com.bts.automation.application

import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import com.bts.shared.permission.AutomationPermissionResolver
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * 자동화 룰(AutomationRule) CRUD 유스케이스 서비스 (FR-AT-01 Task 6).
 *
 * 모든 public 메서드는 [assertManageAutomation] 을 **가장 먼저** 호출한다(Guard 패턴, 리소스 조회보다
 * 먼저 — [[auth-extraction-before-resource-lookup]]). 이렇게 하면 권한 없는 호출자는 대상 룰의
 * 존재 여부조차 알 수 없다(존재 probe 차단).
 *
 * ## 도메인 정규화 경유 (PATCH 우회 방지, [[patch-merge-domain-bypass]])
 * [patch] 는 repository 를 직행하지 않고 항상 [AutomationRule.rename]/[AutomationRule.enable]/
 * [AutomationRule.disable]/[AutomationRule.updateConfig] 도메인 동작을 거친다 — DTO 검증
 * ([com.bts.automation.adapter.web.dto.PatchAutomationRuleRequest])은 1차 방어일 뿐, 실제 불변식
 * (name 길이, triggerConfig 형식)은 도메인이 강제한다.
 *
 * ## 웹훅 토큰 위생 (DEVELOPMENT.md §1 — 평문 저장 금지)
 * WEBHOOK 트리거 생성 시 [SecureRandom] 원문을 발급해 SHA-256 hex 해시만 [AutomationRule.webhookTokenHash]
 * 에 저장한다(notification `ShareTokenMinter`/identity-access PAT 발급 패턴 미러 — BC 격리상 직접
 * import 하지 않고 패턴만 재현, plan Task 6 GREEN 메모). 원문은 [CreatedAutomationRule.webhookToken] 으로
 * 생성 응답에만 1회 담기고 서비스 밖으로는 다시 노출되지 않는다.
 *
 * ## SCHEDULED nextFireAt 계산 (spec G3)
 * "룰 생성/활성화 시 cron 으로 nextFireAt 즉시 계산" — [create] 시 SCHEDULED 트리거면 최초 nextFireAt 을
 * 계산하고, [patch] 로 triggerConfig(cron) 가 바뀌거나 비활성→활성으로 전환될 때도 재계산한다. cron
 * 평가 타임존은 UTC 고정(v1). 이후 발화마다의 재계산은 워커(Task 8, 이 서비스 범위 밖)가 담당한다.
 *
 * ## 권한 판정 실패 메시지 (교훈 fr-pm-04-guard-exception-message-http-leak)
 * [AutomationForbiddenException] 은 왜 거부됐는지(비멤버/권한 미보유/미해석 프로젝트 키 등) 내부 사정을
 * 담지 않는 고정 일반 메시지만 갖는다.
 *
 * @param repository [AutomationRule] 영속 어댑터.
 * @param permissionResolver MANAGE_AUTOMATION 권한 평가 cross-BC 포트(fail-closed, non-null 주입 —
 *   [[crossbc-resolver-nullable-fail-open]] 회귀 방지).
 * @param objectMapper triggerConfig JSON 에서 cron 필드를 읽기 위한 Jackson [ObjectMapper](Spring Boot
 *   기본 자동 구성 빈).
 * @param clock 시각 계산용 [Clock]. automation 모듈에는 중앙 Clock 빈이 없으므로 [Clock.systemUTC] 를
 *   기본값으로 둔다(search-export-import `ExportService` 선례 — 컴포넌트 스캔 시
 *   `NoSuchBeanDefinitionException` 방지). 테스트는 고정 인스턴스를 주입한다.
 */
@Service
class AutomationRuleService(
    private val repository: AutomationRuleRepository,
    private val permissionResolver: AutomationPermissionResolver,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

    /**
     * 신규 자동화 룰을 생성한다.
     *
     * WEBHOOK 트리거면 원문 토큰을 1회 발급하고 해시만 저장한다. SCHEDULED 트리거면 cron 으로 최초
     * nextFireAt 을 계산한다(spec G3).
     *
     * @param actorId 생성을 요청하는 행위자.
     * @param projectKey 룰이 속할 프로젝트 키.
     * @param name 룰 표시 이름.
     * @param triggerType 트리거 타입.
     * @param triggerConfig 트리거별 설정 JSON 문자열.
     * @return 저장된 룰 + (WEBHOOK 이면) 발급된 원문 토큰.
     * @throws AutomationForbiddenException [actorId] 가 [projectKey] 에서 MANAGE_AUTOMATION 권한이 없을 때.
     * @throws com.bts.automation.domain.TriggerConfigInvalidException triggerConfig 가 triggerType 형식을
     *   위반할 때.
     * @throws com.bts.automation.domain.AutomationRuleInvalidException projectKey·name 이 불변식을 위반할 때.
     */
    @Transactional
    fun create(
        actorId: UUID,
        projectKey: String,
        name: String,
        triggerType: TriggerType,
        triggerConfig: String,
    ): CreatedAutomationRule {
        assertManageAutomation(actorId, projectKey)
        // 토큰 발급/nextFireAt 계산 같은 부수 작업 이전에 형식을 먼저 검증한다 — AutomationRule.create()
        // 도 내부적으로 동일 검증을 반복하지만(순수 함수, 부작용 없음), 여기서 먼저 걸러야 잘못된 cron
        // 문자열을 initialNextFireAt() 에 전달하는 사고를 막는다.
        TriggerConfig.validate(triggerType, triggerConfig)

        val now = Instant.now(clock)
        val webhookToken = if (triggerType == TriggerType.WEBHOOK) mintWebhookToken() else null
        val nextFireAt = if (triggerType == TriggerType.SCHEDULED) initialNextFireAt(triggerConfig, now) else null

        val rule =
            AutomationRule.create(
                projectKey = projectKey,
                name = name,
                triggerType = triggerType,
                triggerConfig = triggerConfig,
                createdBy = actorId,
                webhookTokenHash = webhookToken?.hash,
                nextFireAt = nextFireAt,
                now = now,
            )
        repository.save(rule)
        log.info("automation_rule_created id={} projectKey={} triggerType={}", rule.id, projectKey, triggerType)
        return CreatedAutomationRule(rule, webhookToken?.plaintext)
    }

    /**
     * [projectKey] 의 활성 룰 목록을 반환한다.
     *
     * @param actorId 조회를 요청하는 행위자.
     * @param projectKey 조회할 프로젝트 키.
     * @return 생성순 활성 룰 목록.
     * @throws AutomationForbiddenException 권한이 없을 때.
     */
    @Transactional(readOnly = true)
    fun list(
        actorId: UUID,
        projectKey: String,
    ): List<AutomationRule> {
        assertManageAutomation(actorId, projectKey)
        return repository.findByProject(projectKey)
    }

    /**
     * [projectKey] 소속 [id] 룰을 단건 조회한다.
     *
     * @param actorId 조회를 요청하는 행위자.
     * @param projectKey 룰이 속해야 하는 프로젝트 키(경로 스코프).
     * @param id 조회할 룰 id.
     * @return 조회된 룰.
     * @throws AutomationForbiddenException 권한이 없을 때.
     * @throws AutomationRuleNotFoundException 룰이 없거나 [projectKey] 소속이 아닐 때(존재 숨김).
     */
    @Transactional(readOnly = true)
    fun get(
        actorId: UUID,
        projectKey: String,
        id: UUID,
    ): AutomationRule {
        assertManageAutomation(actorId, projectKey)
        return findInProject(projectKey, id)
    }

    /**
     * [id] 룰을 부분 수정한다(name·enabled·triggerConfig, OCC).
     *
     * @param actorId 수정을 요청하는 행위자.
     * @param projectKey 룰이 속해야 하는 프로젝트 키(경로 스코프).
     * @param id 수정할 룰 id.
     * @param expectedVersion 클라이언트가 마지막으로 읽은 version. 서버 현재 version 과 다르면 충돌.
     * @param name 변경할 이름. null 이면 미변경.
     * @param enabled 변경할 활성화 여부. null 이면 미변경.
     * @param triggerConfig 변경할 triggerConfig JSON 문자열. null 이면 미변경.
     * @return 변경된 룰.
     * @throws AutomationForbiddenException 권한이 없을 때.
     * @throws AutomationRuleNotFoundException 룰이 없거나 [projectKey] 소속이 아닐 때.
     * @throws AutomationRuleVersionConflictException [expectedVersion] 이 서버 현재 version 과 다르거나,
     *   조회와 저장 사이 다른 트랜잭션이 먼저 갱신했을 때(TOCTOU 안전망).
     * @throws com.bts.automation.domain.TriggerConfigInvalidException 새 triggerConfig 형식 위반 시.
     * @throws com.bts.automation.domain.AutomationRuleInvalidException 새 name 이 불변식을 위반할 때.
     */
    @Suppress("LongParameterList")
    @Transactional
    fun patch(
        actorId: UUID,
        projectKey: String,
        id: UUID,
        expectedVersion: Long,
        name: String?,
        enabled: Boolean?,
        triggerConfig: String?,
    ): AutomationRule {
        assertManageAutomation(actorId, projectKey)
        val existing = findInProject(projectKey, id)
        if (existing.version != expectedVersion) {
            throw AutomationRuleVersionConflictException(id)
        }

        val now = Instant.now(clock)
        var updated = existing
        val wasDisabled = !existing.enabled
        if (name != null) {
            updated = updated.rename(name, now)
        }
        if (triggerConfig != null) {
            updated = updated.updateConfig(triggerConfig, now)
        }
        if (enabled != null && enabled != updated.enabled) {
            updated = if (enabled) updated.enable(now) else updated.disable(now)
        }
        // spec G3 — triggerConfig(cron) 변경 또는 비활성→활성 전환 시 nextFireAt 재계산.
        val reactivated = enabled == true && wasDisabled
        if (updated.triggerType == TriggerType.SCHEDULED && (triggerConfig != null || reactivated)) {
            updated = updated.copy(nextFireAt = initialNextFireAt(updated.triggerConfig, now))
        }

        // 무변경(no-op) PATCH — 어떤 도메인 동작(rename/updateConfig/enable/disable)도 적용되지 않으면
        // version 이 그대로다. 이때 repository.update 를 호출하면 기대 version(version-1) 행이 없어
        // OptimisticLockingFailureException → 잘못된 409 가 되고 재시도해도 상태가 같아 비수렴한다.
        // 버전이 일치했으므로 변경 없이 200 으로 현재 룰을 그대로 반환한다.
        if (updated.version == existing.version) {
            log.info("automation_rule_patch_noop id={} projectKey={}", id, projectKey)
            return existing
        }

        try {
            repository.update(updated)
        } catch (e: OptimisticLockingFailureException) {
            // 서비스 레벨 버전 비교(위)는 통과했으나 그 사이 다른 트랜잭션이 먼저 갱신한 TOCTOU 레이스.
            throw AutomationRuleVersionConflictException(id, e)
        }
        log.info("automation_rule_updated id={} projectKey={}", id, projectKey)
        return updated
    }

    /**
     * [id] 룰을 소프트 삭제한다.
     *
     * @param actorId 삭제를 요청하는 행위자.
     * @param projectKey 룰이 속해야 하는 프로젝트 키(경로 스코프).
     * @param id 삭제할 룰 id.
     * @throws AutomationForbiddenException 권한이 없을 때.
     * @throws AutomationRuleNotFoundException 룰이 없거나 [projectKey] 소속이 아닐 때.
     */
    @Transactional
    fun delete(
        actorId: UUID,
        projectKey: String,
        id: UUID,
    ) {
        assertManageAutomation(actorId, projectKey)
        findInProject(projectKey, id)
        repository.softDelete(id, Instant.now(clock))
        log.info("automation_rule_deleted id={} projectKey={}", id, projectKey)
    }

    // ── private helpers ──────────────────────────────────────────────────────────

    /** MANAGE_AUTOMATION 판정 — 거부 시 일반 메시지 [AutomationForbiddenException](교훈 fr-pm-04). */
    private fun assertManageAutomation(
        actorId: UUID,
        projectKey: String,
    ) {
        if (!permissionResolver.hasManageAutomation(actorId, projectKey)) {
            throw AutomationForbiddenException()
        }
    }

    /** [projectKey] 소속 활성 룰을 조회한다. 없거나 다른 프로젝트 소속이면 존재를 숨기고 404 로 수렴시킨다. */
    private fun findInProject(
        projectKey: String,
        id: UUID,
    ): AutomationRule {
        val rule = repository.findById(id) ?: throw AutomationRuleNotFoundException(id)
        if (rule.projectKey != projectKey) {
            throw AutomationRuleNotFoundException(id)
        }
        return rule
    }

    /**
     * SCHEDULED triggerConfig 의 cron 필드로 다음 발화 시각(UTC)을 계산한다.
     *
     * 호출 시점에는 [TriggerConfig.validate] 또는 [AutomationRule.updateConfig] 가 이미 cron 파싱
     * 가능성을 검증했다고 전제한다 — 그럼에도 방어적으로 null 체크를 명시한다(`!!` 금지).
     */
    private fun initialNextFireAt(
        triggerConfig: String,
        now: Instant,
    ): Instant {
        val cronNode = objectMapper.readTree(triggerConfig).get(FIELD_CRON)
        val cron = requireNotNull(cronNode?.asText()?.takeIf { it.isNotBlank() }) { "cron 필드가 없습니다." }
        val next = CronExpression.parse(cron).next(now.atZone(ZoneOffset.UTC))
        return requireNotNull(next) { "cron 표현식 '$cron' 에서 다음 발화 시각을 계산할 수 없습니다." }.toInstant()
    }

    /** [SecureRandom] 256bit 원문 + SHA-256 hex 해시를 발급한다(notification `ShareTokenMinter` 패턴 미러). */
    private fun mintWebhookToken(): WebhookToken {
        val rawBytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)
        return WebhookToken(plaintext, sha256Hex(plaintext))
    }

    /** 원문을 SHA-256 hex(소문자 64자) 해시로 변환한다. [MessageDigest] 는 non-thread-safe 라 호출마다 생성한다. */
    private fun sha256Hex(plaintext: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(plaintext.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FIELD_CRON = "cron"
        const val TOKEN_BYTES = 32
        const val SHA_256 = "SHA-256"
    }
}

/** [SecureRandom] 원문 + SHA-256 해시 쌍(내부 전용 — 원문은 [CreatedAutomationRule] 경계 밖으로 나가지 않는다). */
private data class WebhookToken(val plaintext: String, val hash: String)

/**
 * [AutomationRuleService.create] 의 결과 — 저장된 룰 + (WEBHOOK 이면) 1회 노출용 원문 토큰.
 *
 * @property rule 저장된 [AutomationRule] 애그리거트(해시만 보유, 원문 없음).
 * @property webhookToken WEBHOOK 트리거 생성 시 발급된 원문 토큰. 그 외에는 null.
 */
data class CreatedAutomationRule(
    val rule: AutomationRule,
    val webhookToken: String?,
)

/**
 * MANAGE_AUTOMATION 권한이 없는 행위자의 요청을 나타낸다.
 *
 * 왜 거부됐는지(비멤버/권한 미보유/미해석 프로젝트 키)는 담지 않는 고정 일반 메시지만 갖는다
 * (교훈 fr-pm-04-guard-exception-message-http-leak). 웹 레이어에서 403 으로 매핑된다.
 */
class AutomationForbiddenException : RuntimeException("자동화 규칙 관리 권한이 없습니다.")

/**
 * 요청한 [ruleId] 에 해당하는 자동화 룰이 없거나(또는 경로의 프로젝트 키 소속이 아니어서 존재를 숨겨야)
 * 함을 나타낸다. 웹 레이어에서 404 로 매핑된다.
 *
 * @property ruleId 조회를 시도한 룰 id(호출자가 이미 URL 로 알고 있는 값이라 응답에 노출해도 누출이 아니다).
 */
class AutomationRuleNotFoundException(
    val ruleId: UUID,
) : RuntimeException("자동화 룰을 찾을 수 없습니다: $ruleId")

/**
 * OCC(낙관적 동시성 제어) 버전 충돌을 나타낸다 — 클라이언트가 보낸 [ruleId] 룰의 기대 버전이 서버 현재
 * 버전과 다르거나, [AutomationRuleService.patch] 의 조회와 저장 사이 다른 트랜잭션이 먼저 갱신했을 때
 * (TOCTOU). 웹 레이어에서 409 로 매핑된다.
 *
 * @property ruleId 충돌이 발생한 룰 id.
 * @property cause TOCTOU 레이스로 감지된 경우 원인이 된 [OptimisticLockingFailureException]. 그 외에는 null.
 */
class AutomationRuleVersionConflictException(
    val ruleId: UUID,
    cause: Throwable? = null,
) : RuntimeException("자동화 룰이 다른 변경으로 이미 갱신되었습니다: $ruleId", cause)
