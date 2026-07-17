// Git 웹훅 PR_MERGED 파이프라인 — 이벤트 판정 + 이슈키 추출 + 팬아웃 3중 상한 + dedup + 단일 트랜잭션 enqueue (FR-AT-07 PR-C Task 9)

package com.bts.automation.application

import com.bts.automation.adapter.AutomationExecutionEnqueuer
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.adapter.GitWebhookRepository
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.GitProvider
import com.bts.automation.domain.GitWebhook
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * GitHub/GitLab PR 머지 웹훅의 `PR_MERGED` 트리거 파이프라인 (FR-AT-07 PR-C Task 9, spec §3.5).
 *
 * ## ★★ 제3의 경로 — `TriggerMatcher`/`AutomationEventWorker`/`q_automation_events` 를 쓰지 않는다
 * `q_automation_events` 는 issue-tracking 이 발행하는 **이슈 이벤트 전용** fan-out 큐다
 * (`AutomationEventWorker` KDoc). issue-tracking 은 GitHub PR 머지를 알 수 없으므로 Git 웹훅은 그 큐에
 * 올라갈 수 없다. 이 서비스가 **룰을 직접 조회해 동기적으로 enqueue** 한다 — 하류(`AutomationExecutionWorker`
 * → [ActionExecutor])는 FR-AT-01 WEBHOOK 트리거와 동일하게 재사용한다.
 *
 * ## 호출 전제 — 서명 검증은 호출자(컨트롤러, Task 10)가 이미 끝냈다
 * 이 서비스는 서명을 재검증하지 않는다. **서명 미검증 요청은 이 서비스를 호출해서는 안 된다** —
 * `git_webhook_deliveries`/`q_automation_execution` 에 어떤 쓰기도 서명 검증 전에 남기지 않는다는
 * 불변식(spec §3.8)은 호출자가 지킨다. [webhook] 은 이미 토큰 조회로 확정된 등록행이어야 한다.
 *
 * ## 파이프라인 (spec §3.5, 이 클래스 기준 로컬 번호는 KDoc 각 단계 참조)
 * 1. 이벤트 판정 — GITHUB `pull_request`+`action=closed`+`merged=true` / GITLAB
 *    `Merge Request Hook`+`action=merge`(FR-C4). 아니면 조용히 종료
 * 2. 이슈키 추출([PrIssueKeyExtractor]) + 프로젝트 스코프 필터(FR-C7, `"${projectKey}-"` 접두 — 하이픈
 *    누락 시 `PROJ2-1` 이 `PROJ` 룰을 통과하는 사고 방지) + distinct 키 20 상한(FR-C8①)
 * 3. **단일 `@Transactional`**(DEC-23) 안에서 — dedup INSERT → 룰 조회 → 룰×키 100 상한(FR-C8③) →
 *    targetBranch 필터(FR-C5) → 이슈키 × 매칭룰 N×M enqueue
 *
 * ## 단일 트랜잭션과 EC10 롤백 (DEC-23)
 * [handleInboundEvent] 전체가 `@Transactional`(REQUIRED)이다. [AutomationExecutionEnqueuer.enqueue] 가
 * 던지는 예외를 이 메서드는 catch 하지 않고 그대로 전파한다 — Spring 프록시가 그 예외를 보고 트랜잭션
 * 전체(이미 실행된 [GitWebhookRepository.insertDelivery] 포함)를 롤백한다. 부분 enqueue 후 실패해도 dedup
 * 이 남지 않으므로 GitHub/GitLab 의 정직한 재전송이 정상적으로 재처리를 유발한다(1회차의 "영구 유실"
 * 트레이드오프는 근거 없이 수용됐던 것이라 폐기, EC10). self-invocation 은 무관하다 — 컨트롤러가 이
 * 서비스 빈을 외부에서 호출하므로 Spring AOP 프록시를 정상적으로 탄다.
 *
 * ## 팬아웃 3중 상한 (FR-C8, spec §3.7)
 * ① distinct 이슈키 > [MAX_ISSUE_KEYS] 또는 ③ 룰 수 × distinct 키 수 > [MAX_FANOUT_PRODUCT] 면
 * fail-closed(WARN + 처리 0건) — 일부만 처리하면 어느 조합이 처리됐는지 비결정적이라 전량 스킵한다.
 * ② `pr.title`/`pr.body` 는 [MAX_PR_TEXT_LENGTH] 로 절단한다 — `AutomationRuleRepository` 가 프로젝트당
 * 룰 수 상한을 강제하지 않고, `AutomationExecutionEnqueuer`/`AutomationExecutionWorker` 가
 * triggerEvent 를 pgmq 아카이브·`rule_executions` 에 **영구** 보존하므로(정리 배치 부재), 절단 없이는
 * 재전송 1회가 20키 × N룰 × 대용량 payload 를 영구 적재해 디스크 고갈로 이어진다.
 *
 * ## triggerEvent 스키마 — 최상위에 `title`/`body` 를 두지 않는다 (spec §3.10, 의도적 설계)
 * `ActionExecutor.buildContext` 는 triggerEvent 에 `issue` 키가 없으면 **triggerEvent 전체**를 이슈
 * 필드로 취급한다. 최상위에 `title` 을 두면 `{{issue.title}}` 이 PR 제목으로 오염된다. 이 서비스는 PR
 * 필드를 전부 `pr` 하위에 넣어 `{{issue.title}}` 은 항상 빈 값이고 `{{issue.pr.title}}` 로만 접근하게 해
 * 이 오염 경로를 설계로 차단한다. `actorId` 는 두지 않는다 — GitHub/GitLab 사용자는 BTS user 가 아니므로
 * 위조된 actor 를 신뢰하지 않는다([ActionExecutor] 는 actorId 부재 시 actor 컨텍스트를 빈 맵으로 만든다).
 *
 * ## FR-C13 방어심층은 이 클래스의 책임이 아니다 (DEC-24)
 * 이 서비스가 만든 `issueKey` 는 이미 [webhook].projectKey 스코프로 필터링됐지만, "같은 프로젝트 안의
 * 임의 이슈 조작"까지는 막지 않는다(잔여위험, ADR 참조). 그 심층 방어는 [ActionExecutor.execute] 내부
 * 단일 choke point 에 있다(워커·replay 모두 통과) — 이 서비스가 중복 구현하지 않는다.
 *
 * @param gitWebhookRepository 배달 dedup 등록([GitWebhookRepository.insertDelivery], Task 6).
 * @param automationRuleRepository `PR_MERGED` 활성 룰 조회([AutomationRuleRepository.findEnabledByProjectAndTriggerType]).
 * @param executionEnqueuer 매칭 룰을 `q_automation_execution` 에 적재하는 아웃바운드 어댑터.
 * @param objectMapper triggerConfig/payload JSON 파싱 + triggerEvent 조립용 Jackson [ObjectMapper]
 *   (Spring Boot 기본 자동 구성 빈).
 * @param clock 배달 수신 시각(`receivedAt`) 계산용 [Clock]. automation 모듈에는 중앙 Clock 빈이 없으므로
 *   [Clock.systemUTC] 를 기본값으로 둔다([AutomationRuleService] 선례 — 컴포넌트 스캔 시
 *   `NoSuchBeanDefinitionException` 방지). 테스트는 고정 인스턴스를 주입한다.
 */
@Service
class GitWebhookService(
    private val gitWebhookRepository: GitWebhookRepository,
    private val automationRuleRepository: AutomationRuleRepository,
    private val executionEnqueuer: AutomationExecutionEnqueuer,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 인바운드 Git 웹훅 이벤트 1건을 처리한다. 컨트롤러(Task 10)가 토큰 조회 + 서명 검증까지 끝낸
     * 뒤에만 호출해야 한다(클래스 KDoc "호출 전제" 참조). 어떤 경로로 끝나든 컨트롤러는 202 로
     * 응답한다 — 이 메서드의 반환값은 없다(처리 여부는 구조화 로그로만 구분, `git_webhook_*` 이벤트명).
     *
     * @param webhook 토큰 조회로 확정된 등록행.
     * @param eventTypeHeader provider 별 이벤트 종류 헤더 값(`X-GitHub-Event`/`X-Gitlab-Event`).
     * @param deliveryId provider 별 배달 식별자(`X-GitHub-Delivery`/`X-Gitlab-Event-UUID`). 헤더가
     *   없으면 dedup 을 건너뛰고 처리를 진행한다(fail-open, spec §3.8 EC11 — dedup 은 보안 통제가 아니라
     *   재시도 완화책).
     * @param payload 파싱된 요청 본문(JSON 파싱 실패는 컨트롤러가 400 으로 먼저 걸러낸다).
     */
    @Suppress("ReturnCount") // 판정→추출→상한→dedup→룰조회→상한→분기 7단계 fail-closed guard clause — 선례(WebhookActionClient) 동형
    @Transactional
    fun handleInboundEvent(
        webhook: GitWebhook,
        eventTypeHeader: String?,
        deliveryId: String?,
        payload: JsonNode,
    ) {
        if (!isMergeEvent(webhook.provider, eventTypeHeader, payload)) {
            log.info(LOG_IGNORED_NOT_MERGE, webhook.id, webhook.projectKey)
            return
        }

        val prFields = extractPrFields(webhook.provider, payload)
        val rawKeys = PrIssueKeyExtractor.extract(prFields.title, prFields.body)
        val scopedKeys = rawKeys.filter { it.startsWith(webhook.projectKey + ISSUE_KEY_SEPARATOR) }

        if (scopedKeys.size > MAX_ISSUE_KEYS) {
            log.warn(LOG_KEY_LIMIT_EXCEEDED, webhook.id, webhook.projectKey, scopedKeys.size)
            return
        }
        if (scopedKeys.isEmpty()) {
            if (rawKeys.isNotEmpty()) {
                log.warn(LOG_SCOPE_VIOLATION, webhook.id, webhook.projectKey)
            }
            return
        }

        if (!registerDelivery(webhook, deliveryId)) return

        val rules =
            automationRuleRepository.findEnabledByProjectAndTriggerType(webhook.projectKey, TriggerType.PR_MERGED)
        if (rules.isEmpty()) {
            log.info(LOG_NO_MATCHING_RULE, webhook.id, webhook.projectKey)
            return
        }
        if (rules.size.toLong() * scopedKeys.size.toLong() > MAX_FANOUT_PRODUCT) {
            log.warn(LOG_PRODUCT_LIMIT_EXCEEDED, webhook.id, webhook.projectKey, rules.size, scopedKeys.size)
            return
        }

        val matchingRules = rules.filter { targetBranchMatches(objectMapper, it.triggerConfig, prFields.targetBranch) }
        if (matchingRules.isEmpty()) {
            log.info(LOG_NO_BRANCH_MATCH, webhook.id, webhook.projectKey)
            return
        }

        enqueueFanout(webhook, prFields, scopedKeys, matchingRules)
    }

    /**
     * 배달 dedup 을 등록한다(spec §3.8). [deliveryId] 가 없으면 등록을 건너뛰고 처리를 계속 진행한다
     * (fail-open — EC11). 신규 배달(계속 진행)이면 `true`, 이미 기록된 재전송(종료)이면 `false`.
     */
    private fun registerDelivery(
        webhook: GitWebhook,
        deliveryId: String?,
    ): Boolean {
        if (deliveryId.isNullOrBlank()) {
            log.warn(LOG_DELIVERY_ID_MISSING, webhook.id, webhook.projectKey)
            return true
        }
        val inserted = gitWebhookRepository.insertDelivery(webhook.id, deliveryId, Instant.now(clock))
        if (!inserted) {
            log.info(LOG_DUPLICATE_DELIVERY, webhook.id, webhook.projectKey)
        }
        return inserted
    }

    /**
     * [issueKeys] × [rules] N×M 조합을 `q_automation_execution` 에 적재한다(spec §3.5 ⑨). `pr.title`/
     * `pr.body` 는 여기서 [MAX_PR_TEXT_LENGTH] 로 절단한다(FR-C8②) — 이슈키마다 별도 이벤트를 만들되
     * (이슈키 1개당 1 이벤트, §3.10), 절단은 이슈키 전체에 걸쳐 한 번만 계산해 재사용한다.
     */
    private fun enqueueFanout(
        webhook: GitWebhook,
        prFields: PrFields,
        issueKeys: List<String>,
        rules: List<AutomationRule>,
    ) {
        val truncated =
            prFields.copy(
                title = prFields.title?.take(MAX_PR_TEXT_LENGTH),
                body = prFields.body?.take(MAX_PR_TEXT_LENGTH),
            )
        var enqueuedCount = 0
        issueKeys.forEach { issueKey ->
            val triggerEvent = buildTriggerEvent(objectMapper, issueKey, webhook.provider, truncated)
            rules.forEach { rule ->
                executionEnqueuer.enqueue(rule.id, TriggerType.PR_MERGED, triggerEvent)
                enqueuedCount++
            }
        }
        log.info(LOG_FIRED, webhook.id, webhook.projectKey, issueKeys.size, rules.size, enqueuedCount)
    }

    private companion object {
        /** 팬아웃 상한 ① — distinct 이슈키 개수(FR-C8, 초과 시 fail-closed 0건). */
        const val MAX_ISSUE_KEYS = 20

        /** 팬아웃 상한 ③ — 룰 수 × distinct 키 수(FR-C8, 초과 시 fail-closed 0건). */
        const val MAX_FANOUT_PRODUCT = 100

        /**
         * 팬아웃 상한 ② — `pr.title`/`pr.body` 절단 길이(FR-C8, 문자수 근사). "2KB" 를 UTF-8 바이트 정확히
         * 일치시키려면 멀티바이트 문자 경계에서 서로게이트 쌍이 잘릴 위험이 있다 — 저장 크기 상한이라는
         * 목적에는 문자수 근사로 충분하다는 의식적 선택이다.
         */
        const val MAX_PR_TEXT_LENGTH = 2 * 1024

        /** 이슈 키의 `프로젝트키-번호` 구분자(FR-C7 스코프 필터 접두 비교, [ActionExecutor] 의 동명 상수와 동일 근거). */
        const val ISSUE_KEY_SEPARATOR = "-"

        const val LOG_IGNORED_NOT_MERGE = "git_webhook_ignored_not_merge webhookId={} projectKey={}"
        const val LOG_KEY_LIMIT_EXCEEDED = "git_webhook_fanout_key_limit_exceeded webhookId={} projectKey={} count={}"
        const val LOG_SCOPE_VIOLATION = "git_webhook_scope_violation webhookId={} projectKey={}"
        const val LOG_DELIVERY_ID_MISSING = "git_webhook_delivery_id_missing webhookId={} projectKey={}"
        const val LOG_DUPLICATE_DELIVERY = "git_webhook_duplicate_delivery webhookId={} projectKey={}"
        const val LOG_NO_MATCHING_RULE = "git_webhook_no_matching_rule webhookId={} projectKey={}"
        const val LOG_PRODUCT_LIMIT_EXCEEDED =
            "git_webhook_fanout_product_limit_exceeded webhookId={} projectKey={} rules={} keys={}"
        const val LOG_NO_BRANCH_MATCH = "git_webhook_no_target_branch_match webhookId={} projectKey={}"
        const val LOG_FIRED = "git_webhook_fired webhookId={} projectKey={} issueKeys={} rules={} enqueued={}"
    }
}

/** GITHUB `pull_request` 페이로드 필드명. */
private const val FIELD_ACTION = "action"
private const val FIELD_PULL_REQUEST = "pull_request"
private const val FIELD_MERGED = "merged"
private const val FIELD_NUMBER = "number"
private const val FIELD_TITLE = "title"
private const val FIELD_BODY = "body"
private const val FIELD_BASE = "base"
private const val FIELD_REF = "ref"
private const val FIELD_MERGED_AT = "merged_at"
private const val FIELD_HTML_URL = "html_url"

/** GITLAB `Merge Request Hook` 페이로드 필드명 — `object_attributes` 하위. */
private const val FIELD_OBJECT_ATTRIBUTES = "object_attributes"
private const val FIELD_IID = "iid"
private const val FIELD_DESCRIPTION = "description"
private const val FIELD_TARGET_BRANCH_SRC = "target_branch"
private const val FIELD_UPDATED_AT = "updated_at"
private const val FIELD_URL = "url"

/** `automation_rules.trigger_config` 의 targetBranch 키([com.bts.automation.domain.TriggerConfig] 동일 키). */
private const val FIELD_TRIGGER_TARGET_BRANCH = "targetBranch"

/** triggerEvent 출력 스키마 필드명(spec §3.10). */
private const val OUT_ISSUE_KEY = "issueKey"
private const val OUT_PROVIDER = "provider"
private const val OUT_PR = "pr"
private const val OUT_NUMBER = "number"
private const val OUT_TITLE = "title"
private const val OUT_BODY = "body"
private const val OUT_TARGET_BRANCH = "targetBranch"
private const val OUT_MERGED_AT = "mergedAt"
private const val OUT_URL = "url"

private const val GITHUB_EVENT_TYPE = "pull_request"
private const val GITHUB_ACTION_CLOSED = "closed"
private const val GITLAB_EVENT_TYPE = "Merge Request Hook"
private const val GITLAB_ACTION_MERGE = "merge"

/** provider 별 공통 PR 필드 표현(§3.10 트리거이벤트 스키마의 `pr` 하위 필드에 대응). */
private data class PrFields(
    val number: Int?,
    val title: String?,
    val body: String?,
    val targetBranch: String?,
    val mergedAt: String?,
    val url: String?,
)

/**
 * [payload] 가 [provider] 기준으로 PR 머지 이벤트인지 판정한다(FR-C4).
 * GITHUB — `eventType=pull_request` + `action=closed` + `pull_request.merged=true`.
 * GITLAB — `eventType=Merge Request Hook` + `object_attributes.action=merge`.
 */
private fun isMergeEvent(
    provider: GitProvider,
    eventType: String?,
    payload: JsonNode,
): Boolean =
    when (provider) {
        GitProvider.GITHUB ->
            eventType == GITHUB_EVENT_TYPE &&
                payload.path(FIELD_ACTION).asText(null) == GITHUB_ACTION_CLOSED &&
                payload.path(FIELD_PULL_REQUEST).path(FIELD_MERGED).asBoolean(false)
        GitProvider.GITLAB ->
            eventType == GITLAB_EVENT_TYPE &&
                payload.path(FIELD_OBJECT_ATTRIBUTES).path(FIELD_ACTION).asText(null) == GITLAB_ACTION_MERGE
    }

/** [payload] 에서 [provider] 별 실제 필드 경로로 [PrFields] 를 추출한다. 누락 필드는 안전하게 `null`. */
private fun extractPrFields(
    provider: GitProvider,
    payload: JsonNode,
): PrFields =
    when (provider) {
        GitProvider.GITHUB -> {
            val pr = payload.path(FIELD_PULL_REQUEST)
            PrFields(
                number = pr.path(FIELD_NUMBER).takeIf { it.isNumber }?.asInt(),
                title = pr.path(FIELD_TITLE).asText(null),
                body = pr.path(FIELD_BODY).asText(null),
                targetBranch = pr.path(FIELD_BASE).path(FIELD_REF).asText(null),
                mergedAt = pr.path(FIELD_MERGED_AT).asText(null),
                url = pr.path(FIELD_HTML_URL).asText(null),
            )
        }
        GitProvider.GITLAB -> {
            val attrs = payload.path(FIELD_OBJECT_ATTRIBUTES)
            PrFields(
                number = attrs.path(FIELD_IID).takeIf { it.isNumber }?.asInt(),
                title = attrs.path(FIELD_TITLE).asText(null),
                body = attrs.path(FIELD_DESCRIPTION).asText(null),
                targetBranch = attrs.path(FIELD_TARGET_BRANCH_SRC).asText(null),
                mergedAt = attrs.path(FIELD_UPDATED_AT).asText(null),
                url = attrs.path(FIELD_URL).asText(null),
            )
        }
    }

/**
 * [triggerConfig] JSON(`{"targetBranch":"..."}`) 의 targetBranch 값을 파싱한다. 미지정/빈 문자열이면
 * `null`(전체 브랜치 발화 신호). [RuleConflictAnalyzer] 의 동명 private 파싱과 형식이 겹치지만 두 파일
 * 모두 모듈 비공개 구현이라 재사용할 공개 API 가 없다(그 파일 KDoc 과 동일 근거로 값만 복제).
 */
private fun targetBranchOf(
    objectMapper: ObjectMapper,
    triggerConfig: String,
): String? {
    val branch = objectMapper.readTree(triggerConfig).path(FIELD_TRIGGER_TARGET_BRANCH).asText(null)
    return branch?.takeIf(String::isNotBlank)
}

/** [triggerConfig] 의 targetBranch 가 [actualBranch] 와 일치하면 `true`. 미지정이면 전 브랜치 발화(`true`). */
private fun targetBranchMatches(
    objectMapper: ObjectMapper,
    triggerConfig: String,
    actualBranch: String?,
): Boolean {
    val configured = targetBranchOf(objectMapper, triggerConfig) ?: return true
    return configured == actualBranch
}

/**
 * [issueKey] 1건에 대한 triggerEvent JSON 을 구성한다(spec §3.10). 최상위에 PR 제목/본문을 두지 않고
 * `pr` 하위로만 노출한다 — [GitWebhookService] 클래스 KDoc "triggerEvent 스키마" 참조.
 *
 * @param prFields 이미 [GitWebhookService.enqueueFanout] 에서 절단된 title/body 를 담은 값.
 */
private fun buildTriggerEvent(
    objectMapper: ObjectMapper,
    issueKey: String,
    provider: GitProvider,
    prFields: PrFields,
): JsonNode {
    val prNode =
        objectMapper.createObjectNode().apply {
            put(OUT_NUMBER, prFields.number)
            put(OUT_TITLE, prFields.title)
            put(OUT_BODY, prFields.body)
            put(OUT_TARGET_BRANCH, prFields.targetBranch)
            put(OUT_MERGED_AT, prFields.mergedAt)
            put(OUT_URL, prFields.url)
        }
    return objectMapper.createObjectNode().apply {
        put(OUT_ISSUE_KEY, issueKey)
        put(OUT_PROVIDER, provider.name)
        set<JsonNode>(OUT_PR, prNode)
    }
}
