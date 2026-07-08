// cross-BC 개인 캘린더 조회 포트 — 사용자 축 (identity-access → issue-tracking 위임) FR-CA-01

package com.bts.shared.calendar

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 개인 캘린더 cross-BC 조회 포트 — identity-access BC 용 (FR-CA-01).
 *
 * identity-access BC 가 사용자 개인 캘린더 화면을 렌더링할 때 담당 이슈의 일정(시작일·마감일)과
 * 작업 기록(worklog) 시간을 얻기 위해 이 포트를 호출한다.
 * 구현체는 issue-tracking BC 가 제공하며, 두 BC 는 shared-kernel 을 통해 간접 의존한다.
 * identity-access 는 issue-tracking 을 직접 gradle 의존하지 않는다.
 *
 * ### BC 격리 사유 — shared-kernel 배치
 *
 * identity-access 와 issue-tracking 이 shared-kernel 만 공유 의존한다.
 * identity-access 가 issue-tracking 내부를 직접 import 하면 BC 경계가 무너지고
 * 순환 의존 위험이 생긴다. 이 포트를 shared-kernel 에 배치함으로써 두 BC 는 서로를
 * gradle 수준에서 의존하지 않는다 (BC 격리 룰, [com.bts.shared.timeline.TimelineLookupPort] 와
 * 동형 구조 — `SharedKernelBoundaryArchTest` 가 역참조를 빌드 시점에 강제 차단).
 *
 * ### 의존 방향
 * ```
 * identity-access ──(port)──▶ shared-kernel ◀──(impl)──  issue-tracking
 * ```
 *
 * ### fail-safe default 구현
 *
 * issue-tracking adapter 가 등록되지 않은 환경(테스트 stub, 단계적 배포)에서도
 * 빈 페이지를 반환해 캘린더 화면을 안전하게 표시한다.
 * 데이터 조회 실패는 보안 판단이 아니므로 fail-safe 방향이 적절하다
 * ([com.bts.shared.timeline.TimelineLookupPort] 와 동일 방향 — 권한 resolver 의
 * fail-closed 와는 다르다).
 *
 * ### timezone 책임 경계
 *
 * [listWorklogs] 가 반환하는 [CalendarWorklogView.startedAt] 은 UTC 기준 [Instant] 원본이다.
 * 이 포트(및 issue-tracking adapter)는 사용자 timezone 을 알지 못한다.
 * 캘린더 화면에서 특정 로컬 날짜 칸에 worklog 를 배치하는 매핑은 소비측인 identity-access 가
 * 사용자 timezone 설정을 적용해 수행한다.
 *
 * @see CalendarIssueView
 * @see CalendarIssuePage
 * @see CalendarWorklogView
 * @see CalendarWorklogPage
 */
interface UserCalendarLookupPort {
    /**
     * 사용자가 담당자로 배정된, 기간(시작일 또는 마감일)이 설정된 이슈 목록을 [CalendarIssuePage] 로 반환한다.
     *
     * viewer 가 볼 수 없는 보안 등급 이슈를 필터하는 것은 구현체(issue-tracking adapter)의 책임이다.
     * soft-deleted 이슈는 포함하지 않는다.
     * 이 메서드는 읽기 전용이며 부수 효과가 없다.
     *
     * @param userId 캘린더를 조회하는 사용자 UUID. 담당자 필터 기준.
     * @param from 조회 기간 시작일(포함).
     * @param to 조회 기간 종료일(포함).
     * @return [CalendarIssuePage]. adapter 부재 또는 조회 불가 시 빈 페이지(truncated=false).
     */
    fun listAssignedScheduledIssues(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): CalendarIssuePage = CalendarIssuePage(items = emptyList(), truncated = false)

    /**
     * 사용자가 기록한 작업 시간(worklog) 목록을 [CalendarWorklogPage] 로 반환한다.
     *
     * 반환되는 [CalendarWorklogView.startedAt] 은 UTC 기준 [Instant] 원본이며,
     * 로컬 날짜로의 매핑은 소비측(identity-access) 책임이다(클래스 KDoc timezone 책임 경계 참조).
     * viewer 가 볼 수 없는 보안 등급 이슈에 속한 worklog 는 [CalendarWorklogView.issueSummary] 를
     * null 로 마스킹하는 것이 구현체(issue-tracking adapter)의 책임이다.
     * 이 메서드는 읽기 전용이며 부수 효과가 없다.
     *
     * @param userId 캘린더를 조회하는 사용자 UUID. worklog 작성자 필터 기준.
     * @param fromInstant 조회 기간 시작 시각(포함, UTC 기준).
     * @param toInstant 조회 기간 종료 시각(포함, UTC 기준).
     * @return [CalendarWorklogPage]. adapter 부재 또는 조회 불가 시 빈 페이지(truncated=false).
     */
    fun listWorklogs(
        userId: UUID,
        fromInstant: Instant,
        toInstant: Instant,
    ): CalendarWorklogPage = CalendarWorklogPage(items = emptyList(), truncated = false)
}

/**
 * 캘린더에 표시할 담당 이슈 단위 뷰 VO.
 *
 * [UserCalendarLookupPort.listAssignedScheduledIssues] 가 반환하는 읽기 전용 값 객체.
 * 캘린더 칸 렌더링([startDate], [dueDate])과 행 메타(타입, 상태)에 필요한 최소 필드만 포함한다.
 *
 * [startDate] 와 [dueDate] 는 VO 자체로는 둘 다 nullable 이다.
 * 다만 소비측 계약상 이 뷰가 조회 결과에 포함되려면 둘 중 최소 하나는 non-null 이어야 한다
 * (기간 미설정 이슈는 캘린더에 표시할 날짜가 없으므로 애초에 조회 대상에서 제외된다).
 * 이 불변식은 구현체(issue-tracking adapter)가 SQL 조회 조건으로 보장한다.
 *
 * @property key 이슈 키. 예: `"PROJ-1"`.
 * @property summary 이슈 제목. 캘린더 칸 레이블에 표시.
 * @property issueType 이슈 타입 키(issue_types.key). 소문자. 예: `"epic"`, `"story"`, `"task"`, `"bug"`.
 * @property currentStateKey 이슈의 현재 워크플로우 상태 키. 진행 상태 표시 기준.
 * @property startDate 이슈 시작일. 미설정이면 null.
 * @property dueDate 이슈 마감일. 미설정이면 null.
 */
data class CalendarIssueView(
    val key: String,
    val summary: String,
    val issueType: String,
    val currentStateKey: String,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
)

/**
 * 담당 이슈 목록 조회 결과 페이지 VO.
 *
 * [UserCalendarLookupPort.listAssignedScheduledIssues] 가 반환하는 읽기 전용 값 객체.
 *
 * @property items 조회된 담당 이슈 목록.
 * @property truncated 조회 건수가 LIMIT 를 초과해 이슈 일부가 누락됐으면 true. 정상 조회면 false.
 */
data class CalendarIssuePage(
    val items: List<CalendarIssueView>,
    val truncated: Boolean,
)

/**
 * 캘린더에 표시할 작업 기록(worklog) 단위 뷰 VO.
 *
 * [UserCalendarLookupPort.listWorklogs] 가 반환하는 읽기 전용 값 객체.
 *
 * @property id worklog 고유 UUID.
 * @property issueKey worklog 가 속한 이슈 키. 예: `"PROJ-1"`.
 * @property issueSummary 이슈 제목. viewer 가 볼 수 없는 보안 등급 이슈에 속한 worklog 라면
 *   구현체가 이 필드를 null 로 마스킹해 제목 누출을 막는다. [issueKey] 자체는 마스킹 대상이 아니다.
 * @property startedAt worklog 시작 시각. UTC 기준 [Instant] 원본 — 로컬 날짜 매핑은 소비측 책임
 *   (클래스 KDoc timezone 책임 경계 참조).
 * @property timeSpentSeconds 작업 소요 시간(초).
 */
data class CalendarWorklogView(
    val id: UUID,
    val issueKey: String,
    val issueSummary: String?,
    val startedAt: Instant,
    val timeSpentSeconds: Int,
)

/**
 * 작업 기록(worklog) 목록 조회 결과 페이지 VO.
 *
 * [UserCalendarLookupPort.listWorklogs] 가 반환하는 읽기 전용 값 객체.
 *
 * @property items 조회된 worklog 목록.
 * @property truncated 조회 건수가 LIMIT 를 초과해 worklog 일부가 누락됐으면 true. 정상 조회면 false.
 */
data class CalendarWorklogPage(
    val items: List<CalendarWorklogView>,
    val truncated: Boolean,
)
