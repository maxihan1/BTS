// 백로그 조회 REST API 응답 DTO — agile-planning BC (FR-BL-01/02 Task 3)

package com.bts.agileplanning.web.dto

import com.bts.shared.board.BoardIssueView
import java.time.LocalDate
import java.util.UUID

/**
 * 백로그 이슈 응답 DTO.
 *
 * [BoardIssueView] 를 그대로 미러한다. rank 를 포함해 클라이언트가 드래그 앤 드롭 정렬에 활용할 수 있도록 한다.
 *
 * @property key 이슈 키. 예: `"BTS-1"`.
 * @property summary 이슈 제목.
 * @property currentStateKey 현재 워크플로우 상태 키.
 * @property assigneeId 담당자 UUID. null 이면 미배정.
 * @property priority 우선순위 값. 숫자 작을수록 높다.
 * @property rank LexoRank 정렬 키. null 이면 미부여.
 * @property version 낙관적 락(OCC) 버전.
 * @property epicKey 소속 에픽 키. null 이면 에픽 없음.
 * @property typeKey 이슈 유형 키(`issue_types.key`). 소문자. 예: `"bug"` (FR-UX-14 B2).
 *   유형 이름·아이콘은 담지 않는다 — 클라이언트가 기존 타입 목록 API 에서 얻는다(ADR §D-1).
 * @property labels 이슈 라벨 목록. 라벨이 없으면 **빈 배열**로 직렬화된다(null 아님) (FR-UX-14 B2).
 * @property originalEstimateSeconds 최초 추정 작업 시간(초). 미추정이면 null (FR-UX-14 B2).
 */
data class BacklogIssueResponse(
    val key: String,
    val summary: String,
    val currentStateKey: String,
    val assigneeId: UUID?,
    val priority: Int,
    val rank: String?,
    val version: Long,
    val epicKey: String?,
    val typeKey: String,
    val labels: List<String> = emptyList(),
    val originalEstimateSeconds: Int? = null,
) {
    companion object {
        /**
         * cross-BC [BoardIssueView] 를 [BacklogIssueResponse] 로 변환한다.
         *
         * @param view 변환할 이슈 뷰 VO.
         * @return 응답 DTO 인스턴스.
         */
        fun from(view: BoardIssueView): BacklogIssueResponse =
            BacklogIssueResponse(
                key = view.key,
                summary = view.summary,
                currentStateKey = view.currentStateKey,
                assigneeId = view.assigneeId,
                priority = view.priority,
                rank = view.rank,
                version = view.version,
                epicKey = view.epicKey,
                typeKey = view.typeKey,
                labels = view.labels,
                originalEstimateSeconds = view.originalEstimateSeconds,
            )
    }
}

/**
 * 스프린트 메타 응답 DTO — 백로그 응답 전용 경량 버전.
 *
 * [SprintResponse] 를 그대로 미러한다. 별도 타입으로 분리해 백로그 맥락에서 명시적으로 사용한다.
 *
 * @property sprintId 스프린트 UUID.
 * @property name 스프린트 이름.
 * @property goal 스프린트 목표. null 이면 미설정.
 * @property status 스프린트 상태. `"PLANNED"` · `"ACTIVE"` · `"COMPLETED"`.
 * @property startDate 시작일. null 이면 미지정.
 * @property endDate 종료일. null 이면 미지정.
 * @property version 낙관적 락(OCC) 버전.
 */
data class SprintMetaResponse(
    val sprintId: UUID,
    val name: String,
    val goal: String?,
    val status: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val version: Long,
)

/**
 * 스프린트 + 소속 이슈 묶음 응답 DTO.
 *
 * @property sprint 스프린트 메타.
 * @property issues 해당 스프린트에 할당된 이슈 목록. rank ASC NULLS LAST → key ASC 정렬.
 */
data class SprintIssuesResponse(
    val sprint: SprintMetaResponse,
    val issues: List<BacklogIssueResponse>,
)

/**
 * 백로그 조회 최상위 응답 DTO.
 *
 * @property backlog 미할당(백로그) 이슈 목록. rank ASC NULLS LAST → key ASC 정렬.
 * @property sprints 스프린트별 이슈 묶음. ACTIVE → PLANNED → COMPLETED 그다음 startDate ASC NULLS LAST.
 * @property truncated BOARD_CARD_FETCH_LIMIT 초과로 이슈 일부 누락 시 true.
 */
data class BacklogResponse(
    val backlog: List<BacklogIssueResponse>,
    val sprints: List<SprintIssuesResponse>,
    val truncated: Boolean,
)
