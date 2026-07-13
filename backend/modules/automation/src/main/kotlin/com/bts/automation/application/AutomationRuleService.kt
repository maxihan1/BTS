// AutomationRule CRUD 서비스 — MANAGE_AUTOMATION 가드 + 웹훅토큰 + SCHEDULED nextFireAt + 액션/actor/조건 매핑 (FR-AT-03 Task 8)

package com.bts.automation.application

import com.bts.automation.adapter.AutomationActionRepository
import com.bts.automation.adapter.AutomationConditionRepository
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.domain.Action
import com.bts.automation.domain.ActionConfigInvalidException
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.Condition
import com.bts.automation.domain.InvalidConditionExpressionException
import com.bts.automation.domain.RuleConflict
import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import com.bts.shared.permission.AutomationPermissionResolver
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
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
 * ## 액션/actor 매핑 (FR-AT-02 Task 11)
 * [create] 는 요청의 [AutomationActionInput] 목록을 [toDomainAction] 으로 [Action] 도메인으로 매핑한다
 * (형식 위반은 [ActionConfigInvalidException](400)). `actorUserId` 미지정 시 요청자([actorId])로
 * 폴백한다. [repository] 의 `save` 가 룰+액션을 같은 트랜잭션에서 원자적으로 영속한다(Task 6).
 *
 * [patch] 는 [AutomationRule.changeActor] 를 경유해 actor 변경도 지원한다(FR-AT-02 Task 14, spec FR5 —
 * 변경 UI 는 D6 후속이고 이 서비스는 백엔드 지원만 완결한다). name·triggerConfig·actions 와 마찬가지로
 * [applyFieldPatch] 안에서 같은 `updated` 인스턴스에 체이닝되므로 [repository.update] 호출은 여전히
 * 단 한 번이다.
 *
 * ## 다필드 PATCH 단일 OCC 증가 collapse (코드리뷰 BLOCKER 수정, task-16)
 * 체이닝 중 각 도메인 동작(rename/updateConfig/updateActions/changeActor/enable/disable)은 호출마다
 * `version+1` 하므로, K 개 필드가 바뀌면 `updated.version` 은 인메모리에서 `existing.version+K` 가
 * 된다. 그런데 [AutomationRuleRepository.update] 의 OCC 술어는 `expectedVersion = rule.version - 1`
 * (한 번의 영속 호출 = 단일 bump 전제)이다 — K≥2 인 채로 그대로 저장하면 `expectedVersion` 이
 * `existing.version+K-1` 이 되어 DB 의 실제 원본 버전(`existing.version`)과 어긋나 **다필드 PATCH가
 * 항상 허위 409 로 실패**하고 재시도해도 비수렴한다(단일 필드만 테스트돼 잠복했던 사고). 저장 직전에
 * `updated = updated.copy(version = existing.version + 1)` 로 **몇 필드가 바뀌었든 정확히 1 증가로
 * collapse** 시켜 repository 의 `version-1` 술어(=DB 원본 = `existing.version`)와 일치시킨다. 도메인
 * 메서드의 per-call bump 는 이 collapse 이전까지 "어떤 필드가 실제로 바뀌었는지"(no-op 판별, 아래
 * `updated.version == existing.version` 비교)를 추적하는 인메모리 용도일 뿐이고, 영속되는 값은 이
 * collapse 결과다. sidecar 이중 bump 회귀([[no-bump-sidecar-version-double-bump]])와는 반대
 * 방향(sidecar 는 bump 를 아예 하지 말아야 했던 사고, 이쪽은 여러 bump 를 하나로 모아야 하는 사고)지만
 * "한 논리적 갱신 = DB version 정확히 +1" 원칙은 동일하다.
 *
 * actions 교체는 [AutomationRule.updateActions] 도메인 동작을 거친 뒤 [actionRepository] 로 별도
 * 영속한다 — `repository.update` 는 `automation_rules` 테이블만 갱신하고 `automation_actions` 는
 * 건드리지 않는다(Task 6 설계, [AutomationRuleRepository] 클래스 KDoc §actions 참고).
 *
 * [get]/[list] 는 [AutomationRuleRepository] 의 find 계열이 actions 를 로드하지 않으므로(Task 6 결정)
 * [actionRepository] 로 별도 로드해 채운 뒤 반환한다.
 *
 * ## 조건 게이트 매핑 (FR-AT-03 Task 8)
 * [create]/[patch] 는 요청의 [condition](JSON 문자열, [triggerConfig]/[AutomationActionInput.config] 와
 * 동일하게 원본 텍스트로 받는다)을 [Condition.fromJson] 으로 파싱·검증한다(형식 위반은
 * [InvalidConditionExpressionException], 웹 레이어에서 400 `INVALID_CONDITION_EXPRESSION` 로 매핑 —
 * cross-BC 존재 검증은 하지 않는다, 트리거/액션 선례 동형). [conditionRepository] 가 룰 저장/갱신과
 * **같은 `@Transactional` 경계 안에서** `replace` 를 호출한다(조건 replace 트랜잭션성). [get]/[list] 는
 * [AutomationRuleRepository] 의 find 계열이 [AutomationRule.condition] 을 로드하지 않으므로
 * [conditionRepository.findByRuleId] 로 별도 로드해 채운다(actions 와 동일 설계, [hydrate] 참고).
 *
 * ## 규칙 충돌 lint 통합 (FR-AT-04 Task 5, 저장 트랜잭션 분리 hotfix — 코드리뷰 BLOCKER 수정)
 * [create]/[patch] 자신은 저장(및 OCC/도메인 검증)만 담당하고 `conflicts` 는 항상 빈 리스트 placeholder
 * 를 반환한다 — 실제 규칙 충돌 분석은 [analyzeProjectConflicts] 로 완전히 분리된다.
 * [com.bts.automation.adapter.web.AutomationRuleController] 가 [create]/[patch] 의 `@Transactional` 이
 * **커밋된 후** 별도로 [analyzeProjectConflicts] 를 호출해 conflicts 를 채운 응답을 조립한다.
 *
 * 수정 전에는 [create]/[patch] 자신의 트랜잭션 경계 **안에서** lint(재조회·hydrate·analyze)를 호출했다 —
 * [repository] 의 find 계열은 참여(REQUIRED) 전파라 같은 트랜잭션에 합류하고, 그 안에서 read 예외가 나면
 * Spring 이 `globalRollbackOnParticipationFailure`(기본 true)로 트랜잭션을 rollback-only 로 표시한다.
 * 이 표시는 애플리케이션 코드의 `try/catch` 로 예외를 흡수해도 지워지지 않아, 메서드가 정상 반환해
 * 커밋을 시도하면 `UnexpectedRollbackException` 이 발생해 방금 저장한 규칙까지 롤백됐다
 * ([[workflowstatecatalog-mandatory-rollback-poison]] 동일 패턴). [analyzeProjectConflicts] 를 저장
 * 메서드 밖(다른 호출자가 커밋 후 별도로 호출하는 완전히 독립된 호출)으로 빼면 이 참여가 원천적으로
 * 발생하지 않는다 — self-invocation 함정([[transaction-self-invocation-requires-new]])도 자연히
 * 회피된다: [create]/[patch] 는 같은 클래스의 [analyzeProjectConflicts] 를 전혀 호출하지 않는다(호출자는
 * 항상 별도 빈인 컨트롤러다).
 *
 * [get]/[list] 는 이 분석을 호출하지 않는다 — GET 은 저장 이벤트가 아니라 매 호출마다 프로젝트 전체
 * 규칙을 재분석하는 비용을 들일 이유가 없다(스펙 FR-AT-04 "저장 시점에만 리포트"). 웹 응답 DTO 레이어
 * ([com.bts.automation.adapter.web.dto.AutomationRuleResponse])가 `conflicts` 를 `null`(GET)과
 * 리스트(create/patch)로 구분해 노출한다.
 *
 * @param repository [AutomationRule] 영속 어댑터.
 * @param actionRepository [AutomationRule.actions] 별도 로드/PATCH 시 명시적 영속을 위한 어댑터.
 * @param conditionRepository [AutomationRule.condition] 별도 로드/PATCH 시 명시적 영속을 위한 어댑터
 *   (FR-AT-03 Task 8).
 * @param permissionResolver MANAGE_AUTOMATION 권한 평가 cross-BC 포트(fail-closed, non-null 주입 —
 *   [[crossbc-resolver-nullable-fail-open]] 회귀 방지).
 * @param objectMapper triggerConfig JSON 에서 cron 필드를 읽기 위한 Jackson [ObjectMapper](Spring Boot
 *   기본 자동 구성 빈).
 * @param conflictAnalyzer [analyzeProjectConflicts] 가 저장 트랜잭션 밖에서 규칙 충돌을 정적 분석하는 데
 *   쓰는 [RuleConflictAnalyzer](FR-AT-04 Task 5, 클래스 KDoc §규칙 충돌 lint 통합 참고).
 * @param clock 시각 계산용 [Clock]. automation 모듈에는 중앙 Clock 빈이 없으므로 [Clock.systemUTC] 를
 *   기본값으로 둔다(search-export-import `ExportService` 선례 — 컴포넌트 스캔 시
 *   `NoSuchBeanDefinitionException` 방지). 테스트는 고정 인스턴스를 주입한다.
 */
@Service
@Suppress("LongParameterList") // FR-AT-04 Task 5 에서 conflictAnalyzer 추가로 7개(기존 6개 + 1) — 전부 필수 협력자 주입
class AutomationRuleService(
    private val repository: AutomationRuleRepository,
    private val actionRepository: AutomationActionRepository,
    private val conditionRepository: AutomationConditionRepository,
    private val permissionResolver: AutomationPermissionResolver,
    private val objectMapper: ObjectMapper,
    private val conflictAnalyzer: RuleConflictAnalyzer,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

    /**
     * 신규 자동화 룰을 생성한다.
     *
     * WEBHOOK 트리거면 원문 토큰을 1회 발급하고 해시만 저장한다. SCHEDULED 트리거면 cron 으로 최초
     * nextFireAt 을 계산한다(spec G3). [actions] 는 [toDomainAction] 으로 도메인 [Action] 목록으로
     * 매핑되고, [actorUserId] 가 `null` 이면 [actorId](요청자)로 폴백한다(FR-AT-02). [condition] 은
     * [Condition.fromJson] 으로 파싱·검증되어 [conditionRepository] 에 같은 트랜잭션에서 영속된다
     * (FR-AT-03 Task 8).
     *
     * @param actorId 생성을 요청하는 행위자.
     * @param projectKey 룰이 속할 프로젝트 키.
     * @param name 룰 표시 이름.
     * @param triggerType 트리거 타입.
     * @param triggerConfig 트리거별 설정 JSON 문자열.
     * @param actorUserId 액션 실행 주체. `null` 이면 [actorId] 로 폴백.
     * @param actions 발화 시 실행할 액션 목록(요청 표현). 기본값 빈 리스트.
     * @param condition 조건 게이트 표현식(요청 표현) JSON 문자열. `null` 이면 조건 없이 항상 통과.
     * @return 저장된 룰 + (WEBHOOK 이면) 발급된 원문 토큰. `conflicts` 는 항상 빈 리스트 placeholder 다 —
     *   실제 규칙 충돌은 저장 트랜잭션 밖에서 [analyzeProjectConflicts] 를 호출해야 채워진다(FR-AT-04
     *   Task 5, 코드리뷰 BLOCKER 수정 — 클래스 KDoc §규칙 충돌 lint 통합 참고).
     * @throws AutomationForbiddenException [actorId] 가 [projectKey] 에서 MANAGE_AUTOMATION 권한이 없을 때.
     * @throws com.bts.automation.domain.TriggerConfigInvalidException triggerConfig 가 triggerType 형식을
     *   위반할 때.
     * @throws ActionConfigInvalidException [actions] 중 하나라도 타입 형식을 위반하거나 type 문자열이
     *   알 수 없는 [ActionType] 일 때.
     * @throws InvalidConditionExpressionException [condition] 이 형식/화이트리스트/크기 상한을 위반할 때.
     * @throws com.bts.automation.domain.AutomationRuleInvalidException projectKey·name·actorUserId 가
     *   불변식을 위반할 때.
     */
    @Suppress("LongParameterList")
    @Transactional
    fun create(
        actorId: UUID,
        projectKey: String,
        name: String,
        triggerType: TriggerType,
        triggerConfig: String,
        actorUserId: UUID? = null,
        actions: List<AutomationActionInput> = emptyList(),
        condition: String? = null,
    ): CreatedAutomationRule {
        assertManageAutomation(actorId, projectKey)
        // 토큰 발급/nextFireAt 계산 같은 부수 작업 이전에 형식을 먼저 검증한다 — AutomationRule.create()
        // 도 내부적으로 동일 검증을 반복하지만(순수 함수, 부작용 없음), 여기서 먼저 걸러야 잘못된 cron
        // 문자열을 initialNextFireAt() 에 전달하는 사고를 막는다. 액션/조건 형식 검증도 부수 작업 이전에
        // 끝내 잘못된 요청으로 웹훅 토큰이 낭비 발급되지 않게 한다.
        TriggerConfig.validate(triggerType, triggerConfig)
        val domainActions = actions.map(::toDomainAction)
        val domainCondition = condition?.let(Condition::fromJson)

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
                actorUserId = actorUserId ?: actorId,
                actions = domainActions,
                condition = domainCondition,
                now = now,
            )
        repository.save(rule)
        conditionRepository.replace(rule.id, domainCondition)
        log.info("automation_rule_created id={} projectKey={} triggerType={}", rule.id, projectKey, triggerType)
        // conflicts 는 항상 빈 리스트 placeholder — 실제 분석은 이 트랜잭션 밖에서 analyzeProjectConflicts
        // 가 담당한다(코드리뷰 BLOCKER 수정, 클래스 KDoc §규칙 충돌 lint 통합 참고).
        return CreatedAutomationRule(rule, webhookToken?.plaintext, conflicts = emptyList())
    }

    /**
     * [projectKey] 의 활성 룰 목록을 반환한다. 각 룰의 [AutomationRule.actions]/[AutomationRule.condition]
     * 은 [actionRepository]/[conditionRepository] 로 별도 로드해 채운다([hydrate]).
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
        return repository.findByProject(projectKey).map(::hydrate)
    }

    /**
     * [projectKey] 소속 [id] 룰을 단건 조회한다. [AutomationRule.actions]/[AutomationRule.condition] 은
     * [actionRepository]/[conditionRepository] 로 별도 로드해 채운다([hydrate]).
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
        return hydrate(findInProject(projectKey, id))
    }

    /**
     * [id] 룰을 부분 수정한다(name·enabled·triggerConfig·actions·condition, OCC).
     *
     * [actions] 가 `null` 이 아니면 [AutomationRule.updateActions] 로 전체 교체하고(부분 병합 아님),
     * [repository] 의 `update` 가 `automation_rules` 테이블만 갱신하므로 [actionRepository.replaceForRule]
     * 로 별도 영속한다(Task 6 설계 — 클래스 KDoc §액션/actor 매핑 참고). [actorUserId] 가 `null` 이 아니면
     * [AutomationRule.changeActor] 로 실행 주체를 교체한다(FR-AT-02 Task 14). [condition] 이 `null` 이
     * 아니면 [Condition.fromJson] 으로 파싱·검증 후 [AutomationRule.updateCondition] 으로 교체하고
     * [conditionRepository.replace] 로 별도 영속한다(FR-AT-03 Task 8 — actions 와 동일한 "null=미변경"
     * 부분 PATCH 설계).
     *
     * 몇 개 필드가 동시에 바뀌든 실제로 영속되는 OCC version 은 [expectedVersion] 대비 **정확히 +1**
     * 이다(클래스 KDoc §다필드 PATCH 단일 OCC 증가 collapse 참고).
     *
     * @param actorId 수정을 요청하는 행위자.
     * @param projectKey 룰이 속해야 하는 프로젝트 키(경로 스코프).
     * @param id 수정할 룰 id.
     * @param expectedVersion 클라이언트가 마지막으로 읽은 version. 서버 현재 version 과 다르면 충돌.
     * @param name 변경할 이름. null 이면 미변경.
     * @param enabled 변경할 활성화 여부. null 이면 미변경.
     * @param triggerConfig 변경할 triggerConfig JSON 문자열. null 이면 미변경.
     * @param actions 교체할 액션 목록(요청 표현). null 이면 미변경.
     * @param actorUserId 변경할 액션 실행 주체. null 이면 미변경.
     * @param condition 교체할 조건 게이트 표현식(요청 표현) JSON 문자열. null 이면 미변경.
     * @return 변경된(또는 no-op 이면 기존) 룰(액션·조건 포함). `conflicts` 는 항상 빈 리스트 placeholder
     *   다 — 실제 규칙 충돌은 저장(또는 no-op) 트랜잭션 밖에서 [analyzeProjectConflicts] 를 호출해야
     *   채워진다(FR-AT-04 Task 5, 코드리뷰 BLOCKER 수정 — 무변경(no-op) 응답도 동일하게 처리해 응답
     *   형태를 일관되게 유지한다).
     * @throws AutomationForbiddenException 권한이 없을 때.
     * @throws AutomationRuleNotFoundException 룰이 없거나 [projectKey] 소속이 아닐 때.
     * @throws AutomationRuleVersionConflictException [expectedVersion] 이 서버 현재 version 과 다르거나,
     *   조회와 저장 사이 다른 트랜잭션이 먼저 갱신했을 때(TOCTOU 안전망).
     * @throws com.bts.automation.domain.TriggerConfigInvalidException 새 triggerConfig 형식 위반 시.
     * @throws ActionConfigInvalidException [actions] 중 하나라도 형식을 위반할 때.
     * @throws InvalidConditionExpressionException [condition] 이 형식/화이트리스트/크기 상한을 위반할 때.
     * @throws com.bts.automation.domain.AutomationRuleInvalidException 새 name 또는 [actorUserId] 가
     *   불변식을 위반할 때.
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
        actions: List<AutomationActionInput>? = null,
        actorUserId: UUID? = null,
        condition: String? = null,
    ): PatchedAutomationRule {
        assertManageAutomation(actorId, projectKey)
        val existing = hydrate(findInProject(projectKey, id))
        if (existing.version != expectedVersion) {
            throw AutomationRuleVersionConflictException(id)
        }

        val now = Instant.now(clock)
        val wasDisabled = !existing.enabled
        // name·triggerConfig·actions·actorUserId·condition 적용은 patch() 자체의 순환 복잡도
        // (CyclomaticComplexMethod)를 낮추려고 top-level 함수로 뺐다(TooManyFunctions 예산도 아낀다 —
        // 클래스 멤버가 아니라 패키지 함수라 클래스 함수 개수 집계에서 제외된다).
        var updated = applyFieldPatch(existing, name, triggerConfig, actions, actorUserId, condition, now)
        if (enabled != null && enabled != updated.enabled) {
            updated = if (enabled) updated.enable(now) else updated.disable(now)
        }
        // spec G3 — triggerConfig(cron) 변경 또는 비활성→활성 전환 시 nextFireAt 재계산.
        val reactivated = enabled == true && wasDisabled
        if (updated.triggerType == TriggerType.SCHEDULED && (triggerConfig != null || reactivated)) {
            updated = updated.copy(nextFireAt = initialNextFireAt(updated.triggerConfig, now))
        }

        // 무변경(no-op) PATCH — 어떤 도메인 동작(rename/updateConfig/enable/disable/updateActions)도
        // 적용되지 않으면 version 이 그대로다. 이때 repository.update 를 호출하면 기대 version
        // (version-1) 행이 없어 OptimisticLockingFailureException → 잘못된 409 가 되고 재시도해도 상태가
        // 같아 비수렴한다. 버전이 일치했으므로 변경 없이 200 으로 현재 룰(액션 포함)을 그대로 반환한다.
        if (updated.version == existing.version) {
            log.info("automation_rule_patch_noop id={} projectKey={}", id, projectKey)
            // conflicts 는 항상 빈 리스트 placeholder(코드리뷰 BLOCKER 수정, create() 와 동일 사유).
            return PatchedAutomationRule(existing, conflicts = emptyList())
        }

        // 다필드 단일 OCC 증가 collapse (클래스 KDoc "다필드 PATCH 단일 OCC 증가 collapse" 참조, 코드리뷰
        // BLOCKER 수정) — 위에서 no-op 판별에 쓴 K-bump 상태(`updated.version`)는 여기서 버리고,
        // 몇 필드가 바뀌었든 실제로 영속할 값은 existing.version(=클라이언트 기대 버전=DB 원본)에서
        // 정확히 +1 이다. repository.update 의 expectedVersion(=rule.version-1) 이 DB 원본과 일치해야
        // 다필드 PATCH 도 매칭된다.
        updated = updated.copy(version = existing.version + 1)

        try {
            repository.update(updated)
        } catch (e: OptimisticLockingFailureException) {
            // 서비스 레벨 버전 비교(위)는 통과했으나 그 사이 다른 트랜잭션이 먼저 갱신한 TOCTOU 레이스.
            throw AutomationRuleVersionConflictException(id, e)
        }
        // repository.update 는 automation_rules 테이블만 갱신한다 — actions/condition 변경은 여기서
        // 별도 영속한다(같은 @Transactional 경계 안 — 조건 replace 트랜잭션성).
        if (actions != null) {
            actionRepository.replaceForRule(id, updated.actions)
        }
        if (condition != null) {
            conditionRepository.replace(id, updated.condition)
        }
        log.info("automation_rule_updated id={} projectKey={}", id, projectKey)
        // conflicts 는 항상 빈 리스트 placeholder(코드리뷰 BLOCKER 수정, create() 와 동일 사유).
        return PatchedAutomationRule(updated, conflicts = emptyList())
    }

    /**
     * [projectKey] 소속 규칙 전체를 재조회·hydrate 해 정적 분석한 규칙 충돌 목록을 반환한다(FR-AT-04
     * Task 5, 코드리뷰 BLOCKER 수정).
     *
     * ## 저장 트랜잭션과 완전히 분리된 별도 호출 (클래스 KDoc §규칙 충돌 lint 통합 참고)
     * 이 메서드는 `@Transactional` 이 **아니다** — [repository]/[actionRepository]/[conditionRepository]
     * 의 find 계열([hydrate] 가 호출)은 각각 자신의 `@Transactional(readOnly = true)` 로 독립된 새
     * 트랜잭션을 연다(참여할 상위 트랜잭션이 없으므로). 그래서 이 메서드가 [create]/[patch] **저장이
     * 이미 커밋된 뒤**([com.bts.automation.adapter.web.AutomationRuleController] 가 별도로 호출) 실행돼도
     * 방금 저장한 규칙이 재조회에 포함되고(read-committed), 이 안의 어떤 read 예외도 이미 끝난 저장에
     * 영향을 줄 수 없다.
     *
     * [create]/[patch] 는 이 메서드를 호출하지 않는다 — 호출자는 항상 [AutomationRuleController](다른
     * 빈)뿐이므로 self-invocation 함정([[transaction-self-invocation-requires-new]])이 애초에 발생할
     * 여지가 없다.
     *
     * ## fail-safe
     * 분석 전체(재조회·hydrate·[RuleConflictAnalyzer.analyze])를 try/catch 로 감싸 어떤 예외든 흡수하고,
     * 빈 catch 대신 [log] 에 경고를 남긴 뒤 빈 리스트를 반환한다(빈 catch 금지 원칙 준수).
     *
     * @param projectKey 분석 대상 프로젝트 키.
     * @return 검출된 [RuleConflict] 목록. 분석 실패 시 빈 리스트.
     */
    fun analyzeProjectConflicts(projectKey: String): List<RuleConflict> =
        analyzeConflicts(projectKey, repository, conflictAnalyzer, log, ::hydrate)

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

    /**
     * [rule] 에 [actionRepository]/[conditionRepository] 로 조회한 현재 액션·조건을 채워 반환한다.
     *
     * [AutomationRuleRepository] 의 find 계열은 [AutomationRule.actions]/[AutomationRule.condition] 을
     * 항상 빈 리스트/`null` 로 매핑하므로(Task 6 결정, FR-AT-03 Task 8 동일 적용), CRUD 응답이 실제
     * 액션·조건을 반영하려면 이 헬퍼로 별도 로드해야 한다.
     */
    private fun hydrate(rule: AutomationRule): AutomationRule {
        return rule.copy(
            actions = actionRepository.findByRuleId(rule.id),
            condition = conditionRepository.findByRuleId(rule.id),
        )
    }

    private companion object {
        const val FIELD_CRON = "cron"
        const val TOKEN_BYTES = 32
    }
}

/**
 * [AutomationRuleService.create]/[AutomationRuleService.patch] 가 받는 액션 1건의 애플리케이션 계층
 * 커맨드(FR-AT-02 Task 11).
 *
 * [com.bts.automation.adapter.web.dto.ActionRequest](웹 DTO)를 그대로 재사용하지 않는다 — application
 * 계층이 adapter.web 패키지에 의존하지 않게 하기 위해서다(계층 방향 유지, 이 서비스의 다른 메서드들이
 * 이미 웹 DTO 대신 원시 타입/도메인 타입만 받는 것과 동일한 원칙). 컨트롤러가 `ActionRequest` →
 * [AutomationActionInput] 1:1 매핑을 수행한다.
 *
 * @property type 액션 타입 이름 문자열([com.bts.automation.domain.ActionType] 4종 중 하나).
 * @property config 액션별 설정 JSON 문자열.
 */
data class AutomationActionInput(
    val type: String,
    val config: String,
)

/**
 * [AutomationRuleService.patch] 의 name·triggerConfig·actions·actorUserId·condition 반영을 뺀 top-level
 * 함수(FR-AT-02 Task 11, actorUserId 는 Task 14, condition 은 FR-AT-03 Task 8).
 *
 * `patch()` 자체에 인라인했을 때 순환 복잡도(detekt CyclomaticComplexMethod)와 클래스 함수 개수
 * (detekt TooManyFunctions) 예산을 함께 넘겨서 뺐다 — 클래스 멤버가 아닌 패키지 top-level 함수라 클래스
 * 함수 개수 집계에서 제외된다(`AutomationRuleResponses.kt`/`ActionExecutor.kt` 의 top-level 매핑 함수
 * 선례 동형). enabled 전이는 `patch()` 에 남긴다 — nextFireAt 재계산이 enabled 전이 결과(활성화 전환
 * 여부)에 의존해 분리하면 오히려 상태를 두 번 오가야 한다.
 *
 * 다섯 필드 모두 같은 `updated` 인스턴스에 순차 체이닝된다 — [AutomationRuleService.patch] 가 이 함수의
 * 반환값에 대해 `repository.update` 를 단 한 번만 호출하므로, 여러 필드가 동시에 바뀌어도 저장(영속
 * 호출 자체)은 원자적으로 1회다. 다만 각 도메인 동작이 호출마다 `version+1` 하므로 이 함수가 반환하는
 * `updated.version` 은 바뀐 필드 수만큼 인메모리에서 여러 번 증가한 상태다 — `patch()` 가 이를 그대로
 * 저장하지 않고 `existing.version+1` 로 collapse 한 뒤 영속한다(클래스 KDoc §다필드 PATCH 단일 OCC
 * 증가 collapse 참고, 코드리뷰 BLOCKER 수정).
 *
 * @throws com.bts.automation.domain.TriggerConfigInvalidException 새 triggerConfig 형식 위반 시.
 * @throws ActionConfigInvalidException [actions] 중 하나라도 형식을 위반할 때.
 * @throws InvalidConditionExpressionException [condition] 이 형식/화이트리스트/크기 상한을 위반할 때.
 * @throws com.bts.automation.domain.AutomationRuleInvalidException 새 name 또는 [actorUserId] 가
 *   불변식을 위반할 때.
 */
@Suppress("LongParameterList")
private fun applyFieldPatch(
    rule: AutomationRule,
    name: String?,
    triggerConfig: String?,
    actions: List<AutomationActionInput>?,
    actorUserId: UUID?,
    condition: String?,
    now: Instant,
): AutomationRule {
    var updated = rule
    if (name != null) {
        updated = updated.rename(name, now)
    }
    if (triggerConfig != null) {
        updated = updated.updateConfig(triggerConfig, now)
    }
    if (actions != null) {
        updated = updated.updateActions(actions.map(::toDomainAction), now)
    }
    if (actorUserId != null) {
        updated = updated.changeActor(actorUserId, now)
    }
    if (condition != null) {
        updated = updated.updateCondition(Condition.fromJson(condition), now)
    }
    return updated
}

/**
 * 요청의 [AutomationActionInput] 을 도메인 [Action] 으로 매핑한다(FR-AT-02 Task 11).
 *
 * [ActionType.valueOf] 가 실패하면(알 수 없는 type 문자열) [ActionConfigInvalidException] 으로
 * 변환한다 — Jackson enum 역직렬화 실패에 맡기면 400 malformed-request 로 뭉뚱그려져 원인이 불분명해
 * 지므로, 액션 타입 검증도 다른 액션 형식 위반과 동일한 도메인 예외 경로로 수렴시킨다.
 *
 * @throws ActionConfigInvalidException [input] 의 type 이 알 수 없는 값이거나 config 형식이 [input] 의
 *   타입 요건을 위반할 때.
 */
private fun toDomainAction(input: AutomationActionInput): Action {
    val actionType =
        try {
            ActionType.valueOf(input.type)
        } catch (e: IllegalArgumentException) {
            throw ActionConfigInvalidException("알 수 없는 액션 타입입니다: ${input.type}", e)
        }
    return Action.fromJson(actionType, input.config)
}

private const val SHA_256 = "SHA-256"

/**
 * 원문을 SHA-256 hex(소문자 64자) 해시로 변환한다(FR-AT-02 Task 11 — [TooManyFunctions] 예산 때문에
 * top-level 로 뺐다, [applyFieldPatch] KDoc 참고). [MessageDigest] 는 non-thread-safe 라 호출마다
 * 새로 생성한다.
 */
private fun sha256Hex(plaintext: String): String {
    val digest = MessageDigest.getInstance(SHA_256).digest(plaintext.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/** [SecureRandom] 원문 + SHA-256 해시 쌍(내부 전용 — 원문은 [CreatedAutomationRule] 경계 밖으로 나가지 않는다). */
private data class WebhookToken(val plaintext: String, val hash: String)

/**
 * [projectKey] 소속 규칙 전체를 재조회·hydrate 해 [analyzer] 로 정적 분석한다(FR-AT-04 Task 5,
 * [AutomationRuleService.analyzeProjectConflicts] 전용 구현체, 코드리뷰 BLOCKER 수정).
 *
 * top-level 함수로 둔 이유는 [applyFieldPatch]/[toDomainAction]/[sha256Hex] 와 동일하다 — 클래스 멤버로
 * 두면 [AutomationRuleService] 의 함수 개수([TooManyFunctions]) 예산을 넘긴다. [AutomationRuleService.hydrate]
 * 는 여전히 private 멤버라 직접 호출할 수 없으므로, 호출자가 바운드 콜러블 레퍼런스(`::hydrate`)를
 * [hydrate] 파라미터로 넘긴다.
 *
 * ## fail-safe + 저장 트랜잭션과의 완전한 분리 (호출자 KDoc §규칙 충돌 lint 통합 참고, 코드리뷰 BLOCKER 수정)
 * [AutomationRuleService.analyzeProjectConflicts] 는 `@Transactional` 이 아니므로, 이 함수가 호출하는
 * [repository]/[hydrate] 내부의 각 `@Transactional(readOnly = true)` find 호출은 참여할 상위 트랜잭션이
 * 없어 각자 독립된 새 트랜잭션으로 실행된다. 이전에는 [AutomationRuleService.create]/
 * [AutomationRuleService.patch] 자신의 `@Transactional` 경계 **안에서** 이 함수가 호출됐는데, 그 경우
 * read 예외가 나면 Spring 이 참여 트랜잭션을 rollback-only 로 표시해(`globalRollbackOnParticipationFailure`)
 * 애플리케이션 코드의 `try/catch` 로 예외를 흡수해도 저장 커밋 시점에 `UnexpectedRollbackException` 이
 * 나며 방금 저장한 규칙까지 롤백됐다 — 지금은 저장이 이미 커밋된 뒤(별도 호출자인
 * [com.bts.automation.adapter.web.AutomationRuleController] 가 호출)이므로 이 함수의 read 예외가 저장에
 * 영향을 줄 수 없다. 그럼에도 이 함수 자체는 예외를 그대로 던지지 않는다 — 분석 전체(재조회·hydrate·
 * [RuleConflictAnalyzer.analyze])를 try/catch 로 감싸 어떤 예외든 흡수하고, 빈 catch 대신 [log] 에 경고를
 * 남긴 뒤 빈 리스트를 반환한다(빈 catch 금지 원칙 준수, 호출자 응답을 계속 fail-safe 하게 유지).
 *
 * @param projectKey 분석 대상 프로젝트 키.
 * @param repository [AutomationRule] 재조회용 리포지토리.
 * @param analyzer 정적 분석기.
 * @param log 실패 시 경고를 남길 호출자([AutomationRuleService]) 로거.
 * @param hydrate 재조회된 각 룰에 actions/condition 을 채우는 함수(`::hydrate` 바운드 레퍼런스).
 * @return 검출된 [RuleConflict] 목록. 분석 실패 시 빈 리스트.
 */
@Suppress("TooGenericExceptionCaught")
private fun analyzeConflicts(
    projectKey: String,
    repository: AutomationRuleRepository,
    analyzer: RuleConflictAnalyzer,
    log: Logger,
    hydrate: (AutomationRule) -> AutomationRule,
): List<RuleConflict> =
    try {
        analyzer.analyze(repository.findByProject(projectKey).map(hydrate))
    } catch (e: Exception) {
        log.warn("automation_rule_conflict_analysis_failed projectKey={} error={}", projectKey, e.message, e)
        emptyList()
    }

/**
 * [AutomationRuleService.create] 의 결과 — 저장된 룰 + (WEBHOOK 이면) 1회 노출용 원문 토큰 + 저장 후
 * 검출된 규칙 충돌 목록(FR-AT-04 Task 5).
 *
 * @property rule 저장된 [AutomationRule] 애그리거트(해시만 보유, 원문 없음).
 * @property webhookToken WEBHOOK 트리거 생성 시 발급된 원문 토큰. 그 외에는 null.
 * @property conflicts [analyzeConflicts] 로 검출된 규칙 충돌 목록. 분석 실패 시 빈 리스트(fail-safe).
 */
data class CreatedAutomationRule(
    val rule: AutomationRule,
    val webhookToken: String?,
    val conflicts: List<RuleConflict>,
)

/**
 * [AutomationRuleService.patch] 의 결과 — 변경된(또는 무변경 no-op 인) 룰 + 저장 후 검출된 규칙 충돌
 * 목록(FR-AT-04 Task 5).
 *
 * @property rule 변경된(또는 no-op 이면 기존) [AutomationRule] 애그리거트.
 * @property conflicts [analyzeConflicts] 로 검출된 규칙 충돌 목록. 분석 실패 시 빈 리스트(fail-safe).
 */
data class PatchedAutomationRule(
    val rule: AutomationRule,
    val conflicts: List<RuleConflict>,
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
