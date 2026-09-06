// cross-BC 번다운 원천 데이터 조회 포트 (agile-planning → issue-tracking 위임) — FR-RP-01

package com.bts.shared.burndown

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 스프린트 번다운/번업 차트 원천 데이터 cross-BC 조회 포트 — agile-planning BC 용 (FR-RP-01).
 *
 * agile-planning BC 가 스프린트 번다운 차트를 계산할 때, 스프린트에 속한 이슈들의
 * 추정 시간 합계와 worklog(작업 로그) 시계열을 얻기 위해 이 포트를 호출한다.
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
 * ### 세로축이 둘이다 — 시간과 개수 (부채 177 task-35)
 *
 * 보드 「추정」 탭의 `time_tracking` 이 번다운 세로축의 단위를 정한다(J36).
 * `REMAINING_AND_SPENT` 면 시간(초)이고 `NONE` 이면 **이슈 개수**다.
 * 그래서 이 포트는 두 축의 원천을 **함께** 나른다.
 *
 * | 축 | 스코프 | 하루치 진행 |
 * |---|---|---|
 * | 시간 | [BurndownSource.totalOriginalEstimateSeconds] | [BurndownSource.worklogEntries] |
 * | 개수 | [BurndownSource.visibleIssueCount] | [BurndownSource.issueCompletions] |
 *
 * ★**어느 축을 쓸지는 이 포트가 정하지 않는다.** 보드 설정은 agile-planning 의 지식이고,
 * 그것을 인자로 받으면 issue-tracking 이 남의 BC 설정을 알아야 한다(timezone 을 넘기지 않은 것과
 * 같은 판단 — 아래 「timezone 책임 경계」). 소비측이 둘 중 하나를 고른다.
 *
 * ### fail-safe default 구현
 *
 * issue-tracking adapter 가 등록되지 않은 환경(테스트 stub, 단계적 배포)에서도
 * 스코프 0 · worklog 없음 · 이슈 0개 · 완료 없음을 반환해 번다운 계산이 예외 없이 안전하게 진행된다.
 * 데이터 조회 실패는 보안 판단이 아니므로 fail-safe 방향이 적절하다
 * (권한 resolver 의 fail-closed 와 다른 방향 — IssuePermissionResolver 참조).
 *
 * ### timezone 책임 경계
 *
 * [fetchBurndownSource] 가 반환하는 [WorklogContribution.startedAt] 은 UTC 기준 [Instant] 원본이다.
 * 이 포트(및 issue-tracking adapter)는 보드 timezone 을 알지 못한다.
 * 번다운 시계열에서 특정 로컬 날짜 칸에 worklog 를 배치하는 매핑은 소비측인 agile-planning 이
 * 보드 timezone 설정을 적용해 수행한다
 * ([com.bts.shared.calendar.UserCalendarLookupPort] 와 같은 방향).
 *
 * 반대 방향(포트가 zone 을 받아 SQL 에서 버킷)은 배제했다 — 일 귀속 규칙이 issue-tracking 에 남고,
 * 그 BC 가 보드 timezone 이라는 agile-planning 의 지식을 알아야 하기 때문이다.
 *
 * ### 합산은 소비측 한 곳에서만
 *
 * 구현체는 합산하지 않는다([WorklogContribution] 은 worklog 1건당 1항목이다).
 * 합산 책임은 소비측(agile-planning)이 단독으로 진다 — 포트와 소비측이 **둘 다** 합산하면
 * 그것이 두 번째 진실이 되고, 어느 쪽 버킷이 정본인지 알 수 없게 된다.
 * 이전 계약이 정확히 그 형태였고(SQL 이 UTC 로 뭉치고 소비측이 다시 뭉쳤다), 그래서
 * 보드 timezone 으로 버킷을 바꾸는 것이 원리적으로 불가능했다.
 *
 * @see BurndownSource
 * @see WorklogContribution
 */
interface SprintBurndownLookupPort {
    /**
     * 이슈 키 집합의 번다운 원천 데이터를 [BurndownSource] 로 반환한다.
     *
     * soft-deleted 이슈와 soft-deleted worklog 는 결과에서 제외된다.
     * 이 메서드는 읽기 전용이며 부수 효과가 없다.
     *
     * ### viewer 가시 이슈만 집계 — 이슈별 보안(security_level) 필터 (리뷰 C1)
     *
     * 프로젝트 BROWSE 권한만으로는 이슈별 보안 등급([com.bts.shared.permission.IssueScope.Issue])으로
     * 차단된 기밀 이슈의 시간값이 집계에 섞여 viewer 가 간접 추론할 수 있다. 따라서 구현체는
     * [viewerUserId] 가 볼 수 없는 이슈의 estimate/worklog 를 집계에서 제외한다. 이 경우 차트는
     * viewer 스코프의 부분값이 될 수 있으며 이는 의도된 동작이다. 가시성 판정은 issue-tracking BC 의
     * 정본 보안 술어를 재사용해야 하며 별도 복제 판정 경로를 만들면 안 된다(보안갭 방지).
     *
     * @param issueKeys 스프린트에 속한 이슈 키 집합. 빈 집합이면 구현체는 조기 반환해야 한다
     *   (jOOQ 빈 `IN` 절 함정 방지) — 이 경우도 스코프 0 · worklog 없음으로 귀결된다.
     * @param projectKey 이슈들이 속한 프로젝트 키. 보안 술어의 프로젝트 스코프 판정에 사용된다.
     * @param viewerUserId 번다운을 조회하는 viewer UUID. 이슈별 가시성 필터 기준.
     * @return [BurndownSource]. adapter 부재 또는 조회 불가 시 두 축 모두 빈 값이다(fail-safe) —
     *   스코프 0 · worklog 없음 · 이슈 0개 · 완료 없음.
     */
    fun fetchBurndownSource(
        issueKeys: Set<String>,
        projectKey: String,
        viewerUserId: UUID,
    ): BurndownSource =
        BurndownSource(
            totalOriginalEstimateSeconds = 0,
            worklogEntries = emptyList(),
            visibleIssueCount = 0,
            issueCompletions = emptyList(),
        )
}

/**
 * 스프린트 번다운 원천 데이터 VO.
 *
 * [SprintBurndownLookupPort.fetchBurndownSource] 가 반환하는 읽기 전용 값 객체.
 * 총 스코프(추정 시간 합계)와 worklog 기여 목록을 담는다.
 *
 * @property totalOriginalEstimateSeconds 이슈들의 `original_estimate_seconds` 합계(초).
 *   NULL 추정치는 0 으로 간주해 합산한다. **시간 축의 스코프**다.
 * @property worklogEntries worklog 1건당 1항목인 기여 목록. 구현체는 사전 집계하지 않는다.
 *   **시간 축의 하루치 진행**이다.
 * @property visibleIssueCount viewer 가 볼 수 있는 미삭제 이슈의 개수. **개수 축의 스코프**다.
 *   ★스프린트의 실제 이슈 수가 아니라 **가시 이슈 수**다 — 이름이 그 사실을 말하게 둔다.
 *   기밀 이슈까지 세면 프로젝트 BROWSE 만 가진 viewer 가 「몇 개가 숨겨져 있는가」를 개수 차로
 *   역산할 수 있고, 그것이 [fetchBurndownSource] 가 estimate/worklog 에서 막는 것과 같은 누출이다.
 * @property issueCompletions 완료된 이슈 1건당 1항목인 완료 시각 목록. **개수 축의 하루치 진행**이다.
 *   구현체는 날짜별로 사전 집계하지 않는다(worklog 와 같은 이유 — [WorklogContribution] KDoc).
 */
data class BurndownSource(
    val totalOriginalEstimateSeconds: Long,
    val worklogEntries: List<WorklogContribution>,
    val visibleIssueCount: Long,
    val issueCompletions: List<IssueCompletion>,
)

/**
 * 완료된 이슈 1건의 번다운 기여 VO — 개수 축(부채 177 task-35).
 *
 * [BurndownSource.issueCompletions] 의 원소. **한 이슈당 최대 한 항목**이다.
 *
 * ### 무엇을 완료로 보는가
 *
 * 「**지금** DONE 카테고리인 이슈」이고, [completedAt] 은 **마지막** DONE 전환 시각이다.
 * - 지금을 보는 이유. 완료 후 되돌린 이슈를 세면 차트의 마지막 점이 보드의 실제 잔여와 어긋난다 —
 *   사람들이 번다운에서 읽는 값이 그 마지막 점이다.
 * - 마지막 전환인 이유. 되돌렸다 다시 완료한 이슈의 완료 시각은 첫 완료가 아니다.
 * - 전환 이력이 없는 DONE 이슈는 **완료 시각을 알 수 없어** 이 목록에 없다(스코프에는 남는다).
 *   `CycleTimeService` 가 같은 규율(전환 기반 신뢰)을 이미 쓴다. 그 이슈는 잔여가 줄지 않는 것으로
 *   그려지며 이것은 문서화된 한계다.
 *
 * ### 날짜가 아니라 시각이다
 *
 * [WorklogContribution.startedAt] 과 같은 이유다 — `23:30Z` 는 UTC 로는 오늘이지만 `Asia/Seoul` 에서는
 * 내일이고, BC 경계 앞에서 날짜로 뭉개면 소비측이 되돌릴 수 없다(비단사). 어느 로컬 날짜 칸에 놓을지는
 * 보드 timezone 을 아는 소비측(agile-planning)이 정한다.
 *
 * @property issueKey 완료된 이슈 키. 같은 이슈가 두 번 나오지 않는다.
 * @property completedAt 마지막 DONE 전환 시각. UTC 기준 [Instant] 원본.
 */
data class IssueCompletion(
    val issueKey: String,
    val completedAt: Instant,
)

/**
 * worklog 1건의 번다운 기여 VO.
 *
 * [BurndownSource.worklogEntries] 의 원소. 미삭제·가시 이슈에 속한 미삭제 worklog **한 건당 한 항목**이며,
 * 구현체(issue-tracking adapter)는 조회 시점에 아무것도 합산하지 않는다.
 * 같은 [startedOnUtcDate] 를 가진 항목이 여러 개일 수 있고, [startedAt] 이 완전히 같은 항목도 여러 개일 수 있다.
 *
 * 이전 계약(「한 [startedOnUtcDate] 당 최대 1개」 pre-aggregate)은 시각을 BC 경계 앞에서 버렸다.
 * `10:00Z` 와 `23:30Z` 는 UTC 로 같은 날이지만 `Asia/Seoul` 에서는 다른 날이라, 한 버킷에 합산되어
 * 도착한 값은 소비측이 무엇을 하든 되돌릴 수 없다(비단사). 그래서 시각 원본을 그대로 나른다.
 *
 * ### 행 수 — 날짜별 1행에서 worklog 별 1행으로
 *
 * 사전 집계를 푼 대가로 항목 수가 늘어난다. 정의에서 나오는 상한이 이렇게 바뀐다.
 * - 이전: worklog 가 있는 **서로 다른 UTC 날짜 수** — 사실상 스프린트 길이로 묶였다(2주면 최대 14).
 * - 지금: 스프린트 가시 이슈의 **미삭제 worklog 총건수** — 스프린트 길이와 무관하다.
 *
 * 배수는 곧 하루 평균 worklog 건수(팀원 수 × 1인당 하루 기록 횟수)다. 8명이 하루 1.5건씩
 * 10 근무일 기록하면 약 10행 → 약 120행(≈12배)이 된다. 프로덕션 실측이 아니라 위 정의에서
 * 나오는 계산이다. 두 경로 모두 LIMIT 가 없다는 점은 바뀌지 않았다.
 *
 * @property startedOnUtcDate 이 worklog 의 `started_at`(TIMESTAMPTZ) 를 UTC 기준 날짜로 변환한 값.
 *   **일 귀속의 정본이 아니다** — UTC 축 소비자용 파생값일 뿐이고, 로컬 날짜 배치의 기준은
 *   [startedAt] + 소비측 timezone 이다(클래스 KDoc timezone 책임 경계 참조).
 * @property timeSpentSeconds 이 worklog 의 `time_spent_seconds`(초). 합계가 아니라 단건 값이다.
 * @property startedAt worklog 시작 시각. UTC 기준 [Instant] 원본 — 로컬 날짜 매핑은 소비측 책임
 *   (클래스 KDoc timezone 책임 경계 참조).
 */
data class WorklogContribution(
    val startedOnUtcDate: LocalDate,
    val timeSpentSeconds: Long,
    val startedAt: Instant,
)
