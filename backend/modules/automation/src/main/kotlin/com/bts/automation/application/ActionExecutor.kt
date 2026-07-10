// 자동화 룰의 액션 리스트를 순서대로 실행하는 디스패처 — 이슈 변경 3종/웹훅 호출 위임 + best-effort 부분실패 집계 (FR-AT-02 Task 9)

package com.bts.automation.application

import com.bts.automation.adapter.AutomationActionRepository
import com.bts.automation.adapter.WebhookActionClient
import com.bts.automation.domain.Action
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.AutomationRule
import com.bts.shared.issue.AddCommentCommand
import com.bts.shared.issue.AssignCommand
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.SetFieldCommand
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 자동화 룰([AutomationRule])의 액션 리스트를 position 순으로 실행하는 디스패처 (FR-AT-02 Task 9).
 *
 * 이슈 변경 3종([Action.SetFieldAction]/[Action.AssignAction]/[Action.AddCommentAction])은
 * [IssueMutationPort] 로, 아웃바운드 웹훅([Action.CallWebhookAction])은 [WebhookActionClient] 로
 * 위임한다. 룰 actor([AutomationRule.actorUserId]) 권한으로 실행되며(각 커맨드의 `actorUserId`),
 * best-effort 로 동작한다 — 한 액션의 실패가 나머지 액션 실행을 막지 않는다.
 *
 * ## best-effort 부분 실패 집계
 * 액션 1건의 실패는 예외로 전파하지 않고 [ActionOutcome] 으로 흡수한다. 전체 결과는
 * [ActionExecutionResult.status] 로 집계된다 — 전부 성공([ActionExecutionStatus.SUCCESS]) /
 * 일부만 성공([ActionExecutionStatus.PARTIAL]) / 전부 실패([ActionExecutionStatus.FAILED]).
 *
 * ## cross-BC 예외 분류 — 클래스명 휴리스틱(BC 격리, 보안 판정 아님)
 * automation 은 issue-tracking 내부 예외 타입을 import 할 수 없다(BC 격리, [IssueMutationPort] 는
 * 계약상 `RuntimeException` 만 노출). [classifyPortFailure] 는 BTS 전 BC 가 공유하는
 * `*AccessDeniedException`/`*Forbidden*` 명명 관례에 기대어 예외 클래스 **이름**만으로
 * `PERMISSION_DENIED`/`FAILED` 를 구분한다 — 이는 로그/집계용 최선 노력(best-effort) 분류일 뿐,
 * 실제 권한 강제는 위임 대상(issue-tracking)이 이미 수행했다.
 *
 * ## 템플릿 컨텍스트 구성
 * `triggerEvent` 로 `{issue, trigger, actor}` 컨텍스트를 만들어 [TemplateRenderer] 에 넘긴다.
 * - `issue` — triggerEvent 에 중첩 `issue` 객체가 있으면 그것을, 없으면(issue-tracking 도메인
 *   이벤트는 이슈 필드가 최상위에 평탄하게 담긴다 — `com.bts.issue.event.IssueDomainEvent` 계약)
 *   triggerEvent 전체를 이슈 필드로 취급한다.
 * - `trigger.type` — 룰의 [AutomationRule.triggerType] 이름.
 * - `actor` — triggerEvent 에 `actorId` 가 있으면 `{id: ...}`, 없으면 빈 맵(SCHEDULED/WEBHOOK 처럼
 *   행위자가 없는 트리거 대응).
 *
 * 대상 이슈 키는 triggerEvent 최상위 `issueKey` 또는 중첩 `issue.key` 에서 추출한다([extractIssueKey]).
 * 이슈가 필요한 액션인데 이슈 키를 찾을 수 없으면(예: SCHEDULED/WEBHOOK 빈 payload) 포트를 호출하지
 * 않고 즉시 [FAILURE_ISSUE_KEY_MISSING] 실패로 기록한다.
 *
 * @param issueMutationPort 이슈 필드 변경/담당자 배정/댓글 추가 cross-BC 포트(fail-closed, non-null
 *   주입 — [[crossbc-resolver-nullable-fail-open]] 회귀 방지). automation 자체 test-boot 컨텍스트는
 *   `StubIssueMutationPort` 를 대신 등록한다(consumer-owns-stub).
 * @param webhookActionClient CALL_WEBHOOK 액션의 아웃바운드 HTTP 호출기(SSRF 검증 내장).
 * @param actionRepository 룰의 액션 리스트를 position 순으로 조회하는 리포지토리.
 * @param objectMapper [Action.SetFieldAction.value] JSON 인코딩 + triggerEvent → Map 변환용 Jackson
 *   [ObjectMapper](Spring Boot 기본 자동 구성 빈).
 * @param templateRenderer 템플릿 치환기. 상태 없는 Kotlin object 싱글턴이라 Spring 빈이 아니며,
 *   기본값으로 싱글턴 자신을 사용한다(automation `AutomationRuleService.clock` 과 동일한 "빈 부재 시
 *   기본값으로 컴포넌트 스캔 통과" 관례).
 */
@Component
class ActionExecutor(
    private val issueMutationPort: IssueMutationPort,
    private val webhookActionClient: WebhookActionClient,
    private val actionRepository: AutomationActionRepository,
    private val objectMapper: ObjectMapper,
    private val templateRenderer: TemplateRenderer = TemplateRenderer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [rule] 의 액션 리스트를 position 순으로 실행한다.
     *
     * @param rule 실행할 자동화 룰(actor·triggerType 포함).
     * @param triggerEvent 발화를 유발한 원본 이벤트 payload(이슈 이벤트/웹훅 본문/빈 객체).
     * @param dryRun true 이면 모든 이슈 변경 커맨드에 dryRun 을 전파한다(실제 커밋·이벤트 발행 없음).
     * @return 액션별 실행 결과와 집계 상태.
     */
    fun execute(
        rule: AutomationRule,
        triggerEvent: JsonNode,
        dryRun: Boolean = false,
    ): ActionExecutionResult {
        val actions = actionRepository.findByRuleId(rule.id)
        if (actions.isEmpty()) {
            log.info("automation_action_executor_noop ruleId={}", rule.id)
            return ActionExecutionResult(ActionExecutionStatus.SUCCESS, emptyList())
        }

        val env = ExecutionEnv(rule.actorUserId, buildContext(rule, triggerEvent), dryRun)
        val issueKey = extractIssueKey(triggerEvent)
        val outcomes = actions.mapIndexed { position, action -> dispatchAction(position, action, issueKey, env) }
        log.info(
            "automation_action_executor_completed ruleId={} total={} success={}",
            rule.id,
            outcomes.size,
            outcomes.count { it.success },
        )
        return ActionExecutionResult(aggregateStatus(outcomes), outcomes)
    }

    /** 액션 1건을 실행하고 실패 사유를 로그로 남긴 뒤 [ActionOutcome] 으로 흡수한다. */
    private fun dispatchAction(
        position: Int,
        action: Action,
        issueKey: String?,
        env: ExecutionEnv,
    ): ActionOutcome {
        val error =
            when (action) {
                is Action.SetFieldAction ->
                    attemptIssueMutation(issueKey) { key ->
                        val value = encodeSetFieldValue(action.value)
                        issueMutationPort.setField(SetFieldCommand(env.actorId, key, action.field, value, env.dryRun))
                    }
                is Action.AssignAction ->
                    attemptIssueMutation(issueKey) { key ->
                        issueMutationPort.assign(AssignCommand(env.actorId, key, action.assigneeId, env.dryRun))
                    }
                is Action.AddCommentAction ->
                    attemptIssueMutation(issueKey) { key ->
                        val body = templateRenderer.render(action.body, env.context)
                        issueMutationPort.addComment(AddCommentCommand(env.actorId, key, body, env.dryRun))
                    }
                is Action.CallWebhookAction -> attemptWebhook(action, env.context)
            }
        if (error != null) {
            log.warn("automation_action_failed position={} type={} reason={}", position, actionTypeOf(action), error)
        }
        return ActionOutcome(position, actionTypeOf(action), success = error == null, error = error)
    }

    /** [issueKey] 가 없으면 즉시 실패, 있으면 [block] 을 시도해 예외를 실패 사유 문자열로 흡수한다. */
    @Suppress("TooGenericExceptionCaught") // IssueMutationPort 계약상 RuntimeException 만 노출(KDoc 참조)
    private fun attemptIssueMutation(
        issueKey: String?,
        block: (String) -> Unit,
    ): String? {
        if (issueKey == null) return FAILURE_ISSUE_KEY_MISSING
        return try {
            block(issueKey)
            null
        } catch (e: RuntimeException) {
            val reason = classifyPortFailure(e)
            log.warn(
                "automation_action_mutation_exception issueKey={} reason={} error={}",
                issueKey,
                reason,
                e.message,
                e,
            )
            reason
        }
    }

    /** url/body 를 템플릿 치환한 뒤 [webhookActionClient] 로 호출한다. 이슈 컨텍스트가 불필요하다. */
    private fun attemptWebhook(
        action: Action.CallWebhookAction,
        context: Map<String, Any?>,
    ): String? {
        val url = templateRenderer.render(action.url, context)
        val body = templateRenderer.render(action.body, context)
        val result = webhookActionClient.call(url, action.method, action.headers, body)
        return if (result.success) null else (result.error ?: FAILURE_GENERIC)
    }

    /** [value] 가 JSON `null` 이면 필드 해제([SetFieldCommand.value] null 계약), 그 외엔 JSON 문자열로 인코딩한다. */
    private fun encodeSetFieldValue(value: JsonNode): String? {
        if (value.isNull) return null
        return objectMapper.writeValueAsString(value)
    }

    /** 클래스 KDoc "cross-BC 예외 분류" 참조 — 이름 패턴 매칭만으로 PERMISSION_DENIED/FAILED 를 구분한다. */
    private fun classifyPortFailure(e: RuntimeException): String {
        val name = e.javaClass.simpleName
        return if (PERMISSION_NAME_MARKERS.any { marker -> name.contains(marker, ignoreCase = true) }) {
            FAILURE_PERMISSION_DENIED
        } else {
            FAILURE_GENERIC
        }
    }

    /** [triggerEvent] 최상위 `issueKey` 또는 중첩 `issue.key` 에서 대상 이슈 키를 추출한다. 둘 다 없으면 `null`. */
    private fun extractIssueKey(triggerEvent: JsonNode): String? {
        val direct = triggerEvent.path(FIELD_ISSUE_KEY).asText(null)
        if (!direct.isNullOrBlank()) return direct
        return triggerEvent.path(FIELD_ISSUE).path(FIELD_KEY).asText(null)?.takeIf { it.isNotBlank() }
    }

    /** 클래스 KDoc "템플릿 컨텍스트 구성" 참조. */
    private fun buildContext(
        rule: AutomationRule,
        triggerEvent: JsonNode,
    ): Map<String, Any?> {
        val issueNode = if (triggerEvent.has(FIELD_ISSUE)) triggerEvent.path(FIELD_ISSUE) else triggerEvent
        val issueMap: Map<String, Any?> =
            if (issueNode.isObject) objectMapper.convertValue(issueNode, MAP_TYPE_REF) else emptyMap()
        val actorId = triggerEvent.path(FIELD_ACTOR_ID).asText(null)?.takeIf { it.isNotBlank() }
        return mapOf(
            FIELD_ISSUE to issueMap,
            "trigger" to mapOf("type" to rule.triggerType.name),
            "actor" to (actorId?.let { mapOf("id" to it) } ?: emptyMap()),
        )
    }

    /** [Action] 서브타입 → [ActionType] 매핑(로그/결과 리포팅 전용 — [AutomationActionRepository] 저장측 매핑과 별도). */
    private fun actionTypeOf(action: Action): ActionType =
        when (action) {
            is Action.SetFieldAction -> ActionType.SET_FIELD
            is Action.AssignAction -> ActionType.ASSIGN
            is Action.AddCommentAction -> ActionType.ADD_COMMENT
            is Action.CallWebhookAction -> ActionType.CALL_WEBHOOK
        }

    /** 성공 0건이면 FAILED, 전부 성공이면 SUCCESS, 그 외엔 PARTIAL. */
    private fun aggregateStatus(outcomes: List<ActionOutcome>): ActionExecutionStatus {
        val successCount = outcomes.count { it.success }
        return when {
            successCount == outcomes.size -> ActionExecutionStatus.SUCCESS
            successCount == 0 -> ActionExecutionStatus.FAILED
            else -> ActionExecutionStatus.PARTIAL
        }
    }

    private companion object {
        const val FIELD_ISSUE_KEY = "issueKey"
        const val FIELD_ISSUE = "issue"
        const val FIELD_KEY = "key"
        const val FIELD_ACTOR_ID = "actorId"

        /** 이슈가 필요한 액션인데 triggerEvent 에서 이슈 키를 찾지 못했을 때의 실패 사유. */
        const val FAILURE_ISSUE_KEY_MISSING = "ISSUE_KEY_MISSING"

        /** [classifyPortFailure] 가 예외 클래스명에서 권한 거부로 분류했을 때의 실패 사유. */
        const val FAILURE_PERMISSION_DENIED = "PERMISSION_DENIED"

        /** [classifyPortFailure] 가 권한 거부로 분류하지 못한 그 외 포트 예외/웹훅 실패의 기본 사유. */
        const val FAILURE_GENERIC = "FAILED"

        val PERMISSION_NAME_MARKERS = listOf("AccessDenied", "Forbidden", "PermissionDenied")

        val MAP_TYPE_REF: TypeReference<Map<String, Any?>> = object : TypeReference<Map<String, Any?>>() {}
    }
}

/** [ActionExecutor.execute] 실행 1회에 걸쳐 모든 액션에 공통으로 전달되는 값(actor·템플릿 컨텍스트·dryRun). */
private data class ExecutionEnv(
    val actorId: UUID,
    val context: Map<String, Any?>,
    val dryRun: Boolean,
)

/**
 * [ActionExecutor.execute] 전체 실행 결과 — 개별 액션 실행 결과([ActionOutcome]) 목록의 집계.
 *
 * @property status 집계 상태.
 * @property outcomes 액션별 실행 결과(position 순).
 */
data class ActionExecutionResult(
    val status: ActionExecutionStatus,
    val outcomes: List<ActionOutcome>,
)

/**
 * [ActionExecutionResult] 전체 집계 상태.
 *
 * - [SUCCESS] — 모든 액션이 성공(빈 액션 리스트도 SUCCESS 로 취급, EC2).
 * - [PARTIAL] — 일부 액션만 성공.
 * - [FAILED] — 액션이 1개 이상 존재하되 전부 실패.
 */
enum class ActionExecutionStatus {
    SUCCESS,
    PARTIAL,
    FAILED,
}

/**
 * 액션 1건의 실행 결과.
 *
 * @property position 룰 액션 리스트 내 실행 순서(0-base).
 * @property actionType 실행된 액션 타입.
 * @property success 성공 여부.
 * @property error 실패 사유 코드. 성공이면 `null`. 실패 시 `"ISSUE_KEY_MISSING"`(이슈 키 없음)/
 *   `"PERMISSION_DENIED"`/`"FAILED"`(그 외 포트 예외) 또는 [com.bts.automation.adapter.WebhookCallResult.error]
 *   (CALL_WEBHOOK — SSRF 차단/non-2xx/요청 오류 사유) 중 하나.
 */
data class ActionOutcome(
    val position: Int,
    val actionType: ActionType,
    val success: Boolean,
    val error: String?,
)
