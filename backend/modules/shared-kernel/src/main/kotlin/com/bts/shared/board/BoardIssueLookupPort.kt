// 칸반 보드 카드 목록 cross-BC 조회 포트 (agile-planning → issue-tracking 위임)

package com.bts.shared.board

import java.util.UUID

/**
 * 보드 카드(이슈) 목록 cross-BC 조회 포트 — agile-planning BC 용 (FR-BD-01).
 *
 * agile-planning BC 가 보드 조회 시 프로젝트 내 가시 이슈 목록을 얻기 위해 이 포트를 호출한다.
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
 * 빈 페이지를 반환해 보드를 안전하게 표시한다.
 * 데이터 조회 실패는 보안 판단이 아니므로 fail-safe 방향이 적절하다
 * (권한 resolver 의 fail-closed 와 다른 방향 — IssuePermissionResolver 참조).
 *
 * ### visibility 필터 책임
 *
 * viewer 가 볼 수 없는 보안 등급 이슈는 구현체가 SQL 수준에서 필터해야 한다.
 * 이 포트를 소비하는 agile-planning 은 필터 여부를 알지 못한다.
 * 필터 미적용 시 보안 등급 이슈 데이터 누출로 이어지므로 구현체 책임이 중요하다.
 *
 * @see BoardIssueView
 * @see BoardIssuePage
 */
interface BoardIssueLookupPort {
    /**
     * 프로젝트의 가시 이슈 목록을 [BoardIssuePage] 로 반환한다.
     *
     * viewer 가 볼 수 없는 보안 등급 이슈는 결과에서 제외된다.
     * soft-deleted 이슈는 포함하지 않는다.
     * 이 메서드는 읽기 전용이며 부수 효과가 없다.
     *
     * [BoardIssuePage.truncated] 가 true 이면 [IssueRepository.BOARD_CARD_FETCH_LIMIT] 를 초과한
     * 이슈가 존재하며 일부가 누락됐음을 의미한다. 소비측([BoardApplicationService])은 이 플래그를
     * 응답에 포함해 클라이언트가 인지할 수 있도록 해야 한다.
     *
     * @param projectKey 조회할 프로젝트 키. 예: `"ATLAS"`.
     * @param viewerUserId 보드를 조회하는 사용자 UUID. visibility 필터 기준.
     * @return [BoardIssuePage]. adapter 부재 또는 조회 불가 시 빈 페이지(truncated=false).
     */
    fun listVisibleIssuesByProject(
        projectKey: String,
        viewerUserId: UUID,
    ): BoardIssuePage = BoardIssuePage(issues = emptyList(), truncated = false)

    /**
     * 프로젝트의 가시 이슈 목록을 [BoardCardFilter] 를 적용해 [BoardIssuePage] 로 반환한다.
     *
     * ### filter 의미
     *
     * [BoardCardFilter] 는 담당자·미할당·라벨·컴포넌트 조건을 담는 불변 VO 다.
     * 동일 필드 내 값은 OR, 필드 간은 AND 로 해석한다(자세한 규칙은 [BoardCardFilter] 참조).
     *
     * ### fail-safe default 위임
     *
     * 이 default 구현은 [filter] 를 무시하고 [listVisibleIssuesByProject(projectKey, viewerUserId)]
     * 2-인자 메서드로 위임한다. 구현체가 이 3-인자 메서드를 override 하지 않아도
     * 안전하게 전체 목록을 반환해 보드가 정상 표시된다.
     *
     * ### 필터 술어 적용 책임
     *
     * 실제 필터 술어는 구현체(issue-tracking SQL adapter)가 SQL 수준에서 적용해야 한다.
     * visibility 필터를 SQL 에서 적용하는 방식과 동일하다.
     * 구현체가 3-인자를 override 하지 않으면 filter 가 드롭되므로, filter-aware 구현을
     * 원하는 경우 반드시 이 메서드를 override 해야 한다(CONCERN-1).
     *
     * ### 빈 필터 동작
     *
     * [filter] 가 [BoardCardFilter.EMPTY] 이거나 [BoardCardFilter.isEmpty] 가 true 이면
     * 무필터 호출과 결과가 동일해야 한다. 구현체는 이를 최적화 힌트로 활용할 수 있다.
     *
     * @param projectKey 조회할 프로젝트 키. 예: `"ATLAS"`.
     * @param viewerUserId 보드를 조회하는 사용자 UUID. visibility 필터 기준.
     * @param filter 보드 카드 필터 조건. [BoardCardFilter.EMPTY] 이면 무필터와 동일.
     * @return [BoardIssuePage]. adapter 부재 또는 조회 불가 시 빈 페이지(truncated=false).
     */
    fun listVisibleIssuesByProject(
        projectKey: String,
        viewerUserId: UUID,
        filter: BoardCardFilter,
    ): BoardIssuePage = listVisibleIssuesByProject(projectKey, viewerUserId)

    /**
     * 지정 이슈가 뷰어에게 가시적인 프로젝트 내 활성 이슈인지 단건으로 확인한다 (FR-BL-02).
     *
     * 스프린트에 이슈를 할당할 때 "그 이슈가 같은 프로젝트의 가시 이슈인가"를 단건 검증하기 위해 사용한다.
     * listVisibleIssuesByProject 는 BOARD_CARD_FETCH_LIMIT 상한이 있어 대규모 프로젝트에서
     * 정당 이슈를 오거부(truncated)할 수 있으므로, 이 메서드는 LIMIT 없이 단건 직접 조회한다.
     *
     * soft-deleted 이슈 및 뷰어가 볼 수 없는 보안 등급 이슈는 false 를 반환한다.
     * 타 프로젝트 이슈는 false 를 반환한다.
     * 이 메서드는 읽기 전용이며 부수 효과가 없다.
     *
     * default 는 fail-safe false 를 반환한다. adapter 가 미override 시 할당을 거부해 안전하다.
     * 목록 조회 fail-safe(빈 목록)와 방향이 다른 것은 의도적이다. 할당은 보안 판정이므로
     * 데이터 부재를 허용(truthy)하면 잘못된 이슈가 스프린트에 들어갈 수 있다.
     *
     * @param projectKey 이슈가 속해야 하는 프로젝트 키. 예: "ATLAS".
     * @param issueKey 확인할 이슈 키. 예: "ATLAS-42".
     * @param viewerUserId 가시성을 판단할 사용자 UUID.
     * @return 가시 활성 이슈이면 true, 그 외 false.
     */
    fun isVisibleIssue(
        projectKey: String,
        issueKey: String,
        viewerUserId: UUID,
    ): Boolean = false
}

/**
 * 보드 카드 목록 조회 결과 페이지 VO.
 *
 * [BoardIssueLookupPort.listVisibleIssuesByProject] 가 반환하는 읽기 전용 값 객체.
 *
 * @property issues 조회된 가시 이슈 목록. 최대 [com.bts.issue.repository.IssueRepository.BOARD_CARD_FETCH_LIMIT] 건.
 * @property truncated 조회 건수가 LIMIT 를 초과해 이슈 일부가 누락됐으면 true. 정상 조회면 false.
 */
data class BoardIssuePage(
    val issues: List<BoardIssueView>,
    val truncated: Boolean,
)

/**
 * 보드 카드 단위 이슈 뷰 VO.
 *
 * [BoardIssueLookupPort.listVisibleIssuesByProject] 가 반환하는 읽기 전용 값 객체.
 * 보드 컬럼 배치([currentStateKey])와 카드 정렬([priority])에 필요한 최소 필드만 포함한다.
 *
 * @property key 이슈 키. 예: `"PROJ-1"`.
 * @property summary 이슈 제목. 카드 UI 에 표시.
 * @property currentStateKey 이슈의 현재 워크플로우 상태 키. 컬럼 배치 기준.
 * @property assigneeId 담당자 UUID. 미배정이면 null.
 * @property priority 우선순위 값. 숫자 작을수록 높은 우선순위. 컬럼 내 정렬 기준.
 * @property version 낙관적 락(OCC) 버전. 카드 이동(전이) 시 expectedVersion 으로 사용.
 * @property epicKey 이슈가 속한 에픽의 이슈 키. EPIC 스윔레인 그룹화 근거 (FR-EP-01 D6/D7).
 *   에픽 없는 이슈 또는 에픽 자신은 null.
 *   동일 프로젝트 에픽만 포함 — cross-project 에픽은 null 처리(P1-A 누출 방지).
 * @property rank LexoRank 정렬 키. null=미부여(정렬 시 NULLS LAST). 정렬은 소비측(agile-planning) 책임.
 */
data class BoardIssueView(
    val key: String,
    val summary: String,
    val currentStateKey: String,
    val assigneeId: UUID?,
    val priority: Int,
    val version: Long,
    val epicKey: String? = null,
    val rank: String? = null,
)
