// 보드 카드 cross-BC 조회 adapter — issue-tracking 이 shared-kernel BoardIssueLookupPort 를 구현.

package com.bts.issue.adapter.outbound.board

import com.bts.issue.domain.Issue
import com.bts.issue.repository.IssueRepository
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.board.BoardIssuePage
import com.bts.shared.board.BoardIssueView
import com.bts.shared.permission.IssueSecurityDirectory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [BoardIssueLookupPort] 의 issue-tracking BC 구현 (FR-BD-01 Task 4).
 *
 * agile-planning BC 가 보드 조회 시 이 adapter 를 통해 프로젝트의 가시 이슈 목록을 받는다.
 * 두 BC 는 shared-kernel 의 [BoardIssueLookupPort] 만 공유하며 서로를 직접 gradle 의존하지 않는다.
 *
 * ### visibility 경로 — 목록 보안필터 정석 재사용 (FR-NT-03 BLOCKER 재발 방지)
 *
 * 보드 카드는 200건 규모이므로 수신자용 단건 위임(IssueVisibilityPort / IssueSecurityDecider)을
 * 쓰면 N+1 이 되고, 멤버 타입 누락 시 제목 누출 위험이 있다. 따라서 이슈 목록 조회와 **동일한**
 * 정석 2단 게이트를 그대로 재사용한다.
 *
 * 1. [IssueSecurityDirectory.accessibleLevels] 로 viewer 의 접근 가능 보안 등급 집합을 1회 조회.
 * 2. [IssueRepository.listVisibleForBoard] 가 그 집합으로 SQL WHERE 술어를 푸시다운해
 *    비가시 행을 단일 쿼리에서 제외한다 (새 보안 판정 경로를 만들지 않는다).
 *
 * ### fail-safe 방향
 *
 * 조회는 보안 "판정"이 아니라 데이터 "조회"이며, 권한 게이트(BROWSE)는 보드 컨트롤러(Task 9)가
 * 행위자 단위로 별도 강제한다. 행단위 보안필터는 항상 적용되므로, 이 adapter 가 반환하는 목록에는
 * viewer 가 볼 수 없는 등급의 이슈가 절대 포함되지 않는다.
 *
 * @see BoardIssueLookupPort
 * @see IssueRepository.listVisibleForBoard
 */
@Component
class BoardIssueLookupAdapter(
    private val issueRepository: IssueRepository,
    private val securityDirectory: IssueSecurityDirectory,
) : BoardIssueLookupPort {
    /**
     * 프로젝트의 가시 이슈 목록을 [BoardIssuePage] 로 반환한다.
     *
     * viewer 가 볼 수 없는 보안 등급 이슈와 soft-deleted 이슈는 SQL 수준에서 제외된다.
     * 정렬(컬럼 내 priority 등)은 소비측(agile-planning) 도메인 배치 로직이 담당한다.
     * [BoardIssuePage.truncated] 가 true 이면 BOARD_CARD_FETCH_LIMIT 초과로 일부 이슈가 누락됐음을 의미한다.
     *
     * @param projectKey 조회할 프로젝트 키. 예: `"ATLAS"`.
     * @param viewerUserId 보드를 조회하는 사용자 UUID. visibility 필터 기준.
     * @return 가시 이슈를 매핑한 [BoardIssuePage].
     */
    @Transactional(readOnly = true)
    override fun listVisibleIssuesByProject(
        projectKey: String,
        viewerUserId: UUID,
    ): BoardIssuePage = listVisibleIssuesByProject(projectKey, viewerUserId, BoardCardFilter.EMPTY)

    /**
     * 프로젝트의 가시 이슈 목록을 [BoardCardFilter] 를 적용해 [BoardIssuePage] 로 반환한다.
     *
     * [listVisibleIssuesByProject] 와 동일한 보안 필터 경로를 재사용하며,
     * [BoardCardFilter] 의 담당자·라벨·컴포넌트 조건을 SQL WHERE 술어로 푸시다운해 단일 쿼리로 처리한다.
     * [filter] 가 비어 있으면 필터 Condition 을 추가하지 않아 2-인자 호출과 동일하다(EC2 회귀 보존).
     *
     * @param projectKey 조회할 프로젝트 키. 예: `"ATLAS"`.
     * @param viewerUserId 보드를 조회하는 사용자 UUID. visibility 필터 기준.
     * @param filter 보드 카드 필터 조건. [BoardCardFilter.EMPTY] 이면 무필터와 동일.
     * @return 필터와 가시성 술어를 모두 적용한 [BoardIssuePage].
     */
    @Transactional(readOnly = true)
    override fun listVisibleIssuesByProject(
        projectKey: String,
        viewerUserId: UUID,
        filter: BoardCardFilter,
    ): BoardIssuePage {
        // 목록당 1회 cross-BC 호출 — N+1 없음. unrestricted=true 이면 WHERE 술어 미적용(빠른경로).
        val access = securityDirectory.accessibleLevels(viewerUserId, projectKey)
        val fetchResult = issueRepository.listVisibleForBoard(projectKey, viewerUserId, access, filter)
        return BoardIssuePage(
            issues = fetchResult.entries.map { it.toBoardIssueView() },
            truncated = fetchResult.truncated,
        )
    }

    /**
     * 지정 이슈가 뷰어에게 가시적인 프로젝트 내 활성 이슈인지 단건으로 확인한다 (FR-BL-02 Task 8).
     *
     * listVisibleIssuesByProject 와 동일한 보안 등급 필터 경로를 재사용한다.
     * BOARD_CARD_FETCH_LIMIT 과 무관하게 단건 EXISTS 쿼리를 수행하므로,
     * 대규모 프로젝트에서 truncated 로 인한 오거부가 발생하지 않는다.
     *
     * @param projectKey 이슈가 속해야 하는 프로젝트 키. 예: "ATLAS".
     * @param issueKey 확인할 이슈 키. 예: "ATLAS-42".
     * @param viewerUserId 가시성을 판단할 사용자 UUID.
     * @return 가시 활성 이슈이면 true, 그 외 false.
     */
    @Transactional(readOnly = true)
    override fun isVisibleIssue(
        projectKey: String,
        issueKey: String,
        viewerUserId: UUID,
    ): Boolean {
        val access = securityDirectory.accessibleLevels(viewerUserId, projectKey)
        return issueRepository.existsVisibleIssue(projectKey, issueKey, viewerUserId, access)
    }
}

/**
 * [IssueRepository.BoardIssueEntry] 를 보드 카드 뷰 [BoardIssueView] 로 매핑한다.
 *
 * [Issue] 도메인 객체에는 epicKey 필드가 없으므로 [IssueRepository.BoardIssueEntry] 쌍에서
 * epicKey 를 직접 전달한다 (CONCERN C1 반영 — Issue 도메인 우회).
 * 동일 프로젝트 에픽만 포함되며 cross-project 에픽은 null 이다 (P1-A 회귀방지).
 * 보드 카드 배치/정렬 + **표시**에 필요한 필드를 추출한다 (FR-UX-14 B2).
 * `typeKey` 는 조인 결과라 entry 에서, `labels`·`originalEstimateSeconds` 는 `ISSUES.fields()` 로
 * 이미 채워진 [Issue] 도메인에서 가져온다. 본문(`description`) 은 카드에 안 그려지므로 여전히 제외한다.
 */
private fun IssueRepository.BoardIssueEntry.toBoardIssueView(): BoardIssueView =
    BoardIssueView(
        key = issue.key.value,
        summary = issue.summary,
        currentStateKey = issue.currentStateKey,
        assigneeId = issue.assigneeId?.value,
        priority = issue.priority,
        version = issue.version,
        typeKey = typeKey,
        epicKey = epicKey,
        rank = issue.rank,
        labels = issue.labels,
        originalEstimateSeconds = issue.originalEstimateSeconds,
    )
