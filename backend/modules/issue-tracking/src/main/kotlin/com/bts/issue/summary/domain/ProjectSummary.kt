// 프로젝트 요약 화면 집계 결과 도메인 모델 — Jira 클라우드 Summary view 패리티

package com.bts.issue.summary.domain

import com.bts.issue.statushistory.StatusCategory
import java.util.UUID

/**
 * 프로젝트 요약 화면 한 벌의 집계 결과.
 *
 * Jira 클라우드 Summary view 와 같은 스펙을 목표로 한다.
 *
 * @property projectKey 집계 대상 프로젝트 키.
 * @property recent 상단 카드 3종(완료·업데이트·생성) — 최근 7일과 직전 7일.
 * @property upcoming 상단 카드 「마감 예정」 — 향후 7일 + 지연.
 * @property statusOverview 상태 개요. **DONE 카테고리만 최근 2주 특례가 걸린다**([StatusSlice]).
 * @property priorityBreakdown 우선순위 분포 — 활성·가시 이슈 전량.
 * @property typesOfWork 작업 유형 분포 — 활성·가시 이슈 전량.
 * @property teamWorkload 담당자 분포 — 활성·가시 이슈 전량.
 */
data class ProjectSummary(
    val projectKey: String,
    val recent: RecentCounts,
    val upcoming: UpcomingCounts,
    val statusOverview: List<StatusSlice>,
    val priorityBreakdown: List<PrioritySlice>,
    val typesOfWork: List<TypeSlice>,
    val teamWorkload: List<AssigneeSlice>,
)

/**
 * 현재 창과 직전 창의 값 쌍. 화면이 델타(「+4」·「변동 없음」)를 그리는 재료다.
 *
 * @property current 최근 7일 값.
 * @property previous 직전 7일(8~14일 전) 값.
 */
data class WindowCount(
    val current: Long,
    val previous: Long,
)

/**
 * 최근 7일 활동 카드 3종.
 *
 * @property windowDays 창 길이(일). 항상 7.
 * @property completed 완료 — **상태 이력에서 DONE 카테고리로 진입한 시각** 기준(D1).
 * @property updated 업데이트 — `issues.updated_at` 기준.
 * @property created 생성 — `issues.created_at` 기준.
 */
data class RecentCounts(
    val windowDays: Int,
    val completed: WindowCount,
    val updated: WindowCount,
    val created: WindowCount,
)

/**
 * 마감 관련 카드.
 *
 * 둘 다 **미완료 이슈만** 센다 — 이미 끝난 일은 마감을 앞두고 있지도, 지연되지도 않았다.
 *
 * @property windowDays 창 길이(일). 항상 7.
 * @property due 오늘 포함 향후 7일 안에 마감인 미완료 이슈 수.
 * @property overdue 마감일이 오늘보다 이전인 미완료 이슈 수.
 */
data class UpcomingCounts(
    val windowDays: Int,
    val due: Long,
    val overdue: Long,
)

/**
 * 상태 개요 한 조각.
 *
 * ### DONE 2주 특례
 * [category] 가 [StatusCategory.DONE] 인 조각은 **최근 2주 안에 완료된 이슈만** 센다.
 * Jira 클라우드 원문 — "Only items that have been completed in the last two weeks will appear
 * in Done". 다른 카테고리와 [PrioritySlice]·[TypeSlice]·[AssigneeSlice] 에는 적용되지 않는다.
 *
 * @property statusKey 워크플로우 상태 키.
 * @property statusName 표시명. 워크플로우 스킴을 해석하지 못하면 null — 화면이 키로 폴백한다.
 * @property category 상태 카테고리.
 * @property count 이슈 수.
 */
data class StatusSlice(
    val statusKey: String,
    val statusName: String?,
    val category: StatusCategory,
    val count: Long,
)

/**
 * 우선순위 분포 한 조각.
 *
 * @property priority 우선순위 값(1~5). 표시명은 DTO 매퍼가 `IssuePriority` 로 해석해 싣는다.
 * @property count 이슈 수.
 */
data class PrioritySlice(
    val priority: Int,
    val count: Long,
)

/**
 * 작업 유형 분포 한 조각.
 *
 * @property typeKey 이슈 유형 키(예: `story`).
 * @property typeName 이슈 유형 표시명.
 * @property count 이슈 수.
 */
data class TypeSlice(
    val typeKey: String,
    val typeName: String,
    val count: Long,
)

/**
 * 담당자 분포 한 조각.
 *
 * @property assigneeId 담당자 UUID. null 이면 미할당 버킷.
 * @property assigneeName 표시명. 미할당이거나 identity-access 조회가 실패하면 null(graceful degrade).
 * @property count 이슈 수.
 */
data class AssigneeSlice(
    val assigneeId: UUID?,
    val assigneeName: String?,
    val count: Long,
)
