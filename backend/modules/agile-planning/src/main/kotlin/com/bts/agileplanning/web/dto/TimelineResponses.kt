// 타임라인 조회 REST API 응답 DTO — agile-planning BC (FR-TL-01 Task 5)

package com.bts.agileplanning.web.dto

import com.bts.shared.timeline.TimelineItemView
import java.time.LocalDate
import java.util.UUID

/**
 * 타임라인 아이템 단위 응답 DTO.
 *
 * [TimelineItemView] cross-BC VO 를 컨트롤러 레이어에서 HTTP 응답 형태로 변환한다.
 * 간트 바 렌더링([startDate], [dueDate])과 행 메타(담당자·상태·에픽 그룹화)에
 * 필요한 최소 필드만 포함한다.
 *
 * @property key 이슈 키. 예: `"PROJ-1"`.
 * @property summary 이슈 제목. 간트 행 레이블에 표시.
 * @property issueType 이슈 타입 키(issue_types.key). 소문자. 예: `"epic"`, `"story"`, `"task"`, `"bug"`.
 * @property currentStateKey 이슈의 현재 워크플로우 상태 키.
 * @property assigneeId 담당자 UUID. 미배정이면 null.
 * @property startDate 이슈 시작일. 미설정이면 null.
 * @property dueDate 이슈 마감일. 미설정이면 null.
 * @property targetDate 이슈 목표일(로드맵 마일스톤). 미설정이면 null.
 * @property epicKey 소속 에픽 키. 에픽 없는 이슈는 null.
 */
data class TimelineItemResponse(
    val key: String,
    val summary: String,
    val issueType: String,
    val currentStateKey: String,
    val assigneeId: UUID?,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
    val targetDate: LocalDate?,
    val epicKey: String?,
) {
    companion object {
        /**
         * cross-BC [TimelineItemView] VO 를 [TimelineItemResponse] 로 변환한다.
         *
         * @param view 변환할 타임라인 아이템 뷰 VO.
         * @return 응답 DTO 인스턴스.
         */
        fun from(view: TimelineItemView): TimelineItemResponse =
            TimelineItemResponse(
                key = view.key,
                summary = view.summary,
                issueType = view.issueType,
                currentStateKey = view.currentStateKey,
                assigneeId = view.assigneeId,
                startDate = view.startDate,
                dueDate = view.dueDate,
                targetDate = view.targetDate,
                epicKey = view.epicKey,
            )
    }
}

/**
 * 타임라인 조회 최상위 응답 DTO.
 *
 * [DataResponse] 봉투로 감싸 클라이언트에 전달된다.
 *
 * @property items 날짜 정렬된 타임라인 아이템 목록.
 *   startDate ASC NULLS LAST → dueDate ASC NULLS LAST → key ASC.
 * @property truncated TIMELINE_FETCH_LIMIT 초과로 이슈 일부 누락 시 true.
 */
data class TimelineResponse(
    val items: List<TimelineItemResponse>,
    val truncated: Boolean,
)
