// cross-BC 타임라인 아이템 조회 포트 (agile-planning → issue-tracking 위임) — FR-TL-01

package com.bts.shared.timeline

import java.time.LocalDate
import java.util.UUID

/**
 * 간트 차트 타임라인 아이템 cross-BC 조회 포트 — agile-planning BC 용 (FR-TL-01).
 *
 * agile-planning BC 가 간트 차트를 렌더링할 때 프로젝트 내 가시 이슈의 날짜·상태·담당자 정보를
 * 얻기 위해 이 포트를 호출한다.
 * 구현체는 issue-tracking BC 가 제공하며, 두 BC 는 shared-kernel 을 통해 간접 의존한다.
 * agile-planning 은 issue-tracking 을 직접 gradle 의존하지 않는다.
 *
 * ### BC 격리 사유 — shared-kernel 배치
 *
 * agile-planning 과 issue-tracking 이 shared-kernel 만 공유 의존한다.
 * agile-planning 이 issue-tracking 내부를 직접 import 하면 BC 경계가 무너지고
 * 순환 의존 위험이 생긴다. 이 포트를 shared-kernel 에 배치함으로써 두 BC 는 서로를
 * gradle 수준에서 의존하지 않는다 (BC 격리 룰, ArchUnit 강제).
 *
 * ### 의존 방향
 * ```
 * agile-planning ──(port)──▶ shared-kernel ◀──(impl)──  issue-tracking
 * ```
 *
 * ### fail-safe default 구현
 *
 * issue-tracking adapter 가 등록되지 않은 환경(테스트 stub, 단계적 배포)에서도
 * 빈 페이지를 반환해 간트 차트를 안전하게 표시한다.
 * 데이터 조회 실패는 보안 판단이 아니므로 fail-safe 방향이 적절하다
 * (권한 resolver 의 fail-closed 와 다른 방향 — IssuePermissionResolver 참조).
 *
 * ### visibility 필터 책임
 *
 * viewer 가 볼 수 없는 보안 등급 이슈는 구현체가 SQL 수준에서 필터해야 한다.
 * 이 포트를 소비하는 agile-planning 은 필터 여부를 알지 못한다.
 * 필터 미적용 시 보안 등급 이슈 데이터 누출로 이어지므로 구현체 책임이 중요하다.
 *
 * @see TimelineItemView
 * @see TimelineItemPage
 */
interface TimelineLookupPort {
    /**
     * 프로젝트의 가시 이슈 목록을 [TimelineItemPage] 로 반환한다.
     *
     * viewer 가 볼 수 없는 보안 등급 이슈는 결과에서 제외된다.
     * soft-deleted 이슈는 포함하지 않는다.
     * 이 메서드는 읽기 전용이며 부수 효과가 없다.
     *
     * @param projectKey 조회할 프로젝트 키. 예: `"ATLAS"`.
     * @param viewerUserId 간트 차트를 조회하는 사용자 UUID. visibility 필터 기준.
     * @return [TimelineItemPage]. adapter 부재 또는 조회 불가 시 빈 페이지(truncated=false).
     */
    fun listTimelineItemsByProject(
        projectKey: String,
        viewerUserId: UUID,
    ): TimelineItemPage = TimelineItemPage(items = emptyList(), truncated = false)
}

/**
 * 타임라인 아이템 목록 조회 결과 페이지 VO.
 *
 * [TimelineLookupPort.listTimelineItemsByProject] 가 반환하는 읽기 전용 값 객체.
 *
 * @property items 조회된 가시 이슈 목록.
 * @property truncated 조회 건수가 LIMIT 를 초과해 이슈 일부가 누락됐으면 true. 정상 조회면 false.
 */
data class TimelineItemPage(
    val items: List<TimelineItemView>,
    val truncated: Boolean,
)

/**
 * 간트 차트 타임라인 아이템 단위 뷰 VO.
 *
 * [TimelineLookupPort.listTimelineItemsByProject] 가 반환하는 읽기 전용 값 객체.
 * 간트 바 렌더링([startDate], [dueDate])과 행 메타(담당자, 상태, 에픽 그룹화)에
 * 필요한 최소 필드만 포함한다.
 *
 * ### 날짜 필드 의미
 *
 * - 날짜 0개 ([startDate] = null, [dueDate] = null): 기간 미설정 이슈. 간트 바 미표시.
 * - 날짜 1개 (한쪽만 non-null): 시작일만 또는 마감일만 지정한 이슈.
 *   간트 차트 소비측이 단일 날짜 표시 방식(점 또는 반쪽 바)을 결정한다.
 * - 날짜 2개 (둘 다 non-null): 전체 기간이 확정된 이슈. 간트 바로 완전 렌더링.
 *
 * @property key 이슈 키. 예: `"PROJ-1"`.
 * @property summary 이슈 제목. 간트 행 레이블에 표시.
 * @property issueType 이슈 타입 키(issue_types.key). 소문자. 예: `"epic"`, `"story"`, `"task"`, `"bug"`.
 * @property currentStateKey 이슈의 현재 워크플로우 상태 키. 진행 상태 표시 기준.
 * @property assigneeId 담당자 UUID. 미배정이면 null.
 * @property startDate 이슈 시작일. 미설정이면 null.
 * @property dueDate 이슈 마감일. 미설정이면 null.
 * @property epicKey 이슈가 속한 에픽의 이슈 키. EPIC 행 그룹화 근거 (FR-EP-01).
 *   에픽 없는 이슈 또는 에픽 자신은 null.
 *   동일 프로젝트 에픽만 포함 — cross-project 에픽은 null 처리(누출 방지).
 */
data class TimelineItemView(
    val key: String,
    val summary: String,
    val issueType: String,
    val currentStateKey: String,
    val assigneeId: UUID?,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
    val epicKey: String?,
)
