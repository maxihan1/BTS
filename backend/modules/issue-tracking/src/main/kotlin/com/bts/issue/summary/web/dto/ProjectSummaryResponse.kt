// 프로젝트 요약 REST 응답 DTO — ProjectSummary 를 JSON 으로 직렬화

package com.bts.issue.summary.web.dto

import com.bts.issue.domain.IssuePriority
import com.bts.issue.statushistory.StatusCategory
import com.bts.issue.summary.domain.AssigneeSlice
import com.bts.issue.summary.domain.PrioritySlice
import com.bts.issue.summary.domain.ProjectSummary
import com.bts.issue.summary.domain.RecentCounts
import com.bts.issue.summary.domain.StatusSlice
import com.bts.issue.summary.domain.TypeSlice
import com.bts.issue.summary.domain.UpcomingCounts
import com.bts.issue.summary.domain.WindowCount
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * 프로젝트 요약 화면 응답 DTO.
 *
 * null 필드는 [JsonInclude.Include.NON_NULL] 로 제외한다 — 프론트의 Zod `.nullish()` 스키마와
 * 정합하도록 키 자체를 응답에 포함하지 않는다([com.bts.issue.adapter.inbound.rest.IssueChangelogResponse] 선례).
 *
 * @property projectKey 집계 대상 프로젝트 키.
 * @property recent 최근 7일 카드 3종.
 * @property upcoming 마감 예정·지연 카드.
 * @property statusOverview 상태 개요. DONE 카테고리에만 최근 2주 특례가 걸려 있다.
 * @property priorityBreakdown 우선순위 분포.
 * @property typesOfWork 작업 유형 분포.
 * @property teamWorkload 담당자 분포.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProjectSummaryResponse(
    val projectKey: String,
    val recent: RecentCountsResponse,
    val upcoming: UpcomingCountsResponse,
    val statusOverview: List<StatusSliceResponse>,
    val priorityBreakdown: List<PrioritySliceResponse>,
    val typesOfWork: List<TypeSliceResponse>,
    val teamWorkload: List<AssigneeSliceResponse>,
) {
    companion object {
        /**
         * 도메인 [ProjectSummary] 를 응답 DTO 로 변환한다.
         *
         * @param summary 서비스가 조립한 요약.
         * @return JSON 직렬화 가능한 응답 DTO.
         */
        fun from(summary: ProjectSummary): ProjectSummaryResponse =
            ProjectSummaryResponse(
                projectKey = summary.projectKey,
                recent = RecentCountsResponse.from(summary.recent),
                upcoming = UpcomingCountsResponse.from(summary.upcoming),
                statusOverview = summary.statusOverview.map(StatusSliceResponse::from),
                priorityBreakdown = summary.priorityBreakdown.map(PrioritySliceResponse::from),
                typesOfWork = summary.typesOfWork.map(TypeSliceResponse::from),
                teamWorkload = summary.teamWorkload.map(AssigneeSliceResponse::from),
            )
    }
}

/**
 * 현재 창과 직전 창의 값 쌍 — 화면이 델타를 그리는 재료.
 *
 * @property current 최근 7일 값.
 * @property previous 직전 7일(8~14일 전) 값.
 */
data class WindowCountResponse(
    val current: Long,
    val previous: Long,
) {
    companion object {
        fun from(count: WindowCount): WindowCountResponse = WindowCountResponse(count.current, count.previous)
    }
}

/**
 * 최근 7일 카드 3종.
 *
 * @property windowDays 창 길이(일).
 * @property completed 완료 — 상태 이력의 DONE 진입 시각 기준.
 * @property updated 업데이트.
 * @property created 생성.
 */
data class RecentCountsResponse(
    val windowDays: Int,
    val completed: WindowCountResponse,
    val updated: WindowCountResponse,
    val created: WindowCountResponse,
) {
    companion object {
        fun from(counts: RecentCounts): RecentCountsResponse =
            RecentCountsResponse(
                windowDays = counts.windowDays,
                completed = WindowCountResponse.from(counts.completed),
                updated = WindowCountResponse.from(counts.updated),
                created = WindowCountResponse.from(counts.created),
            )
    }
}

/**
 * 마감 예정·지연 카드. 둘 다 미완료 이슈만 센다.
 *
 * @property windowDays 창 길이(일).
 * @property due 향후 7일 안에 마감인 미완료 이슈 수.
 * @property overdue 마감일이 지난 미완료 이슈 수.
 */
data class UpcomingCountsResponse(
    val windowDays: Int,
    val due: Long,
    val overdue: Long,
) {
    companion object {
        fun from(counts: UpcomingCounts): UpcomingCountsResponse =
            UpcomingCountsResponse(
                windowDays = counts.windowDays,
                due = counts.due,
                overdue = counts.overdue,
            )
    }
}

/**
 * 상태 개요 한 조각.
 *
 * @property statusKey 워크플로우 상태 키.
 * @property statusName 표시명. 워크플로우 스킴 해석 실패 시 키 자체를 쓰도록 null 로 온다.
 * @property category 상태 카테고리(`TODO`/`IN_PROGRESS`/`DONE`).
 * @property count 이슈 수.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class StatusSliceResponse(
    val statusKey: String,
    val statusName: String?,
    val category: StatusCategory,
    val count: Long,
) {
    companion object {
        fun from(slice: StatusSlice): StatusSliceResponse =
            StatusSliceResponse(slice.statusKey, slice.statusName, slice.category, slice.count)
    }
}

/**
 * 우선순위 분포 한 조각.
 *
 * 형제 분포 3종([StatusSliceResponse]·[TypeSliceResponse]·[AssigneeSliceResponse])과 마찬가지로
 * 표시명을 함께 싣는다. 정수만 주면 화면이 1~5 라벨 맵을 따로 만들게 되고, 그 맵은
 * [IssuePriority] 와 서로를 확인하지 않아 한쪽만 바뀌어도 아무도 모른다.
 *
 * @property priority 우선순위 값(1~5).
 * @property priorityName [IssuePriority.displayName]. 범위 밖 값이면 키가 응답에서 빠진다.
 * @property count 이슈 수.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PrioritySliceResponse(
    val priority: Int,
    val priorityName: String?,
    val count: Long,
) {
    companion object {
        /**
         * 도메인 조각을 응답 DTO 로 변환한다.
         *
         * 표시명은 [IssuePriority.fromNumberOrNull] 로 해석한다 — `fromNumber` 는 범위 밖에서
         * 던지므로, `priority` 가 SMALLINT 제약을 벗어난 값이면 요약 화면 전체가 500 이 된다.
         * 라벨 하나가 비는 것과 화면이 죽는 것은 다른 사고다.
         */
        fun from(slice: PrioritySlice): PrioritySliceResponse =
            PrioritySliceResponse(
                priority = slice.priority,
                priorityName = IssuePriority.fromNumberOrNull(slice.priority)?.displayName,
                count = slice.count,
            )
    }
}

/**
 * 작업 유형 분포 한 조각.
 *
 * @property typeKey 이슈 유형 키.
 * @property typeName 이슈 유형 표시명.
 * @property count 이슈 수.
 */
data class TypeSliceResponse(
    val typeKey: String,
    val typeName: String,
    val count: Long,
) {
    companion object {
        fun from(slice: TypeSlice): TypeSliceResponse = TypeSliceResponse(slice.typeKey, slice.typeName, slice.count)
    }
}

/**
 * 담당자 분포 한 조각.
 *
 * @property assigneeId 담당자 UUID. 미할당이면 키가 응답에서 빠진다.
 * @property assigneeName 표시명. 조회 실패 시 키가 빠진다.
 * @property count 이슈 수.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AssigneeSliceResponse(
    val assigneeId: UUID?,
    val assigneeName: String?,
    val count: Long,
) {
    companion object {
        fun from(slice: AssigneeSlice): AssigneeSliceResponse =
            AssigneeSliceResponse(
                assigneeId = slice.assigneeId,
                assigneeName = slice.assigneeName,
                count = slice.count,
            )
    }
}
