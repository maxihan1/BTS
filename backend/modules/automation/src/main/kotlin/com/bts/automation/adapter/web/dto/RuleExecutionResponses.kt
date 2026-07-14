// AutomationExecutionController 응답 DTO — 룰별 이력 요약 목록 + 단건 trace 상세(outcomes+triggerEvent) (FR-AT-05 Task 4)

package com.bts.automation.adapter.web.dto

import com.bts.automation.application.ActionOutcome
import com.bts.automation.application.RuleExecution
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * 룰별 실행 이력 목록 조회(`GET .../rules/{ruleId}/executions`) 응답 요약 DTO(FR-AT-05 Task 4).
 *
 * 목록은 요약 정보만 노출하고, `outcomes`/`triggerEvent` 원문은 단건 trace 조회
 * ([RuleExecutionDetailResponse])에서만 제공한다 — [actionCount]/[successCount] 는
 * [RuleExecution.outcomes] 를 집계한 값이다.
 *
 * @property id 실행 이력 id.
 * @property ruleId 실행된 룰 id(하드 FK 없음, 소프트/하드 삭제된 룰도 그대로 노출 — NFR-4).
 * @property triggerType fire-time 트리거 타입 이름([com.bts.automation.domain.TriggerType.name]).
 * @property issueKey 대상 이슈 키. 이슈 무관 실행은 `null`.
 * @property status 실행 결과 집계 상태 이름([com.bts.automation.application.ActionExecutionStatus.name]).
 * @property actionCount 이 실행에서 시도된 액션 총 개수([RuleExecution.outcomes] 크기).
 * @property successCount [actionCount] 중 성공한 액션 개수.
 * @property startedAt 실행 시작 시각(UTC).
 * @property finishedAt 실행 종료 시각(UTC).
 * @property replayedFrom replay 로 생성된 실행이면 원본 실행 id. 최초 실행이면 `null`.
 */
data class RuleExecutionSummaryResponse(
    val id: UUID,
    val ruleId: UUID,
    val triggerType: String,
    val issueKey: String?,
    val status: String,
    val actionCount: Int,
    val successCount: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
    val replayedFrom: UUID?,
) {
    companion object {
        /**
         * 도메인 [RuleExecution] 을 목록 요약 응답 DTO 로 변환한다.
         *
         * @param execution 변환할 실행 이력.
         * @return 요약 필드만 담은 응답 DTO(outcomes/triggerEvent 원문 미포함).
         */
        fun from(execution: RuleExecution): RuleExecutionSummaryResponse =
            RuleExecutionSummaryResponse(
                id = execution.id,
                ruleId = execution.ruleId,
                triggerType = execution.triggerType.name,
                issueKey = execution.issueKey,
                status = execution.status.name,
                actionCount = execution.outcomes.size,
                successCount = execution.outcomes.count(ActionOutcome::success),
                startedAt = execution.startedAt,
                finishedAt = execution.finishedAt,
                replayedFrom = execution.replayedFrom,
            )
    }
}

/**
 * 실행 이력 단건 trace 조회(`GET /api/v1/automation/executions/{id}`) 응답 상세 DTO(FR-AT-05 Task 4).
 *
 * [RuleExecutionSummaryResponse] 와 달리 replay 재료인 [triggerEvent] 원문과 액션별 결과 전체
 * ([outcomes])를 담는다.
 *
 * @property id 실행 이력 id.
 * @property ruleId 실행된 룰 id.
 * @property projectKey 룰 소속 프로젝트 키.
 * @property triggerType fire-time 트리거 타입 이름.
 * @property triggerEvent 실행 당시 원본 트리거 payload. BC 격리상 issue-tracking 등 다른 BC 타입을
 *   담지 않고 [JsonNode] 그대로 직렬화한다([RuleExecution.triggerEvent] 클래스 KDoc 동일 원칙).
 * @property issueKey 대상 이슈 키. 이슈 무관 실행은 `null`.
 * @property status 실행 결과 집계 상태 이름.
 * @property outcomes 액션별 실행 결과(position 순).
 * @property replayedFrom replay 로 생성된 실행이면 원본 실행 id. 최초 실행이면 `null`.
 * @property startedAt 실행 시작 시각(UTC).
 * @property finishedAt 실행 종료 시각(UTC).
 */
@Suppress("LongParameterList")
data class RuleExecutionDetailResponse(
    val id: UUID,
    val ruleId: UUID,
    val projectKey: String,
    val triggerType: String,
    val triggerEvent: JsonNode,
    val issueKey: String?,
    val status: String,
    val outcomes: List<ActionOutcomeResponse>,
    val replayedFrom: UUID?,
    val startedAt: Instant,
    val finishedAt: Instant,
) {
    companion object {
        /**
         * 도메인 [RuleExecution] 을 단건 trace 상세 응답 DTO 로 변환한다.
         *
         * @param execution 변환할 실행 이력.
         * @return outcomes/triggerEvent 원문을 포함한 상세 응답 DTO.
         */
        fun from(execution: RuleExecution): RuleExecutionDetailResponse =
            RuleExecutionDetailResponse(
                id = execution.id,
                ruleId = execution.ruleId,
                projectKey = execution.projectKey,
                triggerType = execution.triggerType.name,
                triggerEvent = execution.triggerEvent,
                issueKey = execution.issueKey,
                status = execution.status.name,
                outcomes = execution.outcomes.map(ActionOutcomeResponse::from),
                replayedFrom = execution.replayedFrom,
                startedAt = execution.startedAt,
                finishedAt = execution.finishedAt,
            )
    }
}

/**
 * 액션 1건의 실행 결과 응답 표현([ActionOutcome] 대칭, FR-AT-05 Task 4 —
 * [com.bts.automation.adapter.web.dto.ActionResponse] 선례처럼 도메인/애플리케이션 타입을 응답 DTO 로
 * 직접 노출하지 않고 대칭 DTO 를 둔다).
 *
 * @property position 룰 액션 리스트 내 실행 순서(0-base).
 * @property actionType 실행된 액션 타입 이름([com.bts.automation.domain.ActionType.name]).
 * @property success 성공 여부.
 * @property error 실패 사유 코드. 성공이면 `null`.
 */
data class ActionOutcomeResponse(
    val position: Int,
    val actionType: String,
    val success: Boolean,
    val error: String?,
) {
    companion object {
        /**
         * 도메인 [ActionOutcome] 을 응답 DTO 로 변환한다.
         *
         * @param outcome 변환할 액션 실행 결과.
         * @return 대칭 필드로 구성된 응답 DTO.
         */
        fun from(outcome: ActionOutcome): ActionOutcomeResponse =
            ActionOutcomeResponse(
                position = outcome.position,
                actionType = outcome.actionType.name,
                success = outcome.success,
                error = outcome.error,
            )
    }
}
