// 보드 카드 cross-BC 조회 adapter — issue-tracking 이 shared-kernel BoardIssueLookupPort 를 구현.

package com.bts.issue.adapter.outbound.board

import com.bts.issue.domain.Issue
import com.bts.issue.repository.IssueRepository
import com.bts.shared.board.BoardIssueLookupPort
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
     * 프로젝트의 가시 이슈 목록을 [BoardIssueView] 로 반환한다.
     *
     * viewer 가 볼 수 없는 보안 등급 이슈와 soft-deleted 이슈는 SQL 수준에서 제외된다.
     * 정렬(컬럼 내 priority 등)은 소비측(agile-planning) 도메인 배치 로직이 담당한다.
     *
     * @param projectKey 조회할 프로젝트 키. 예: `"ATLAS"`.
     * @param viewerUserId 보드를 조회하는 사용자 UUID. visibility 필터 기준.
     * @return 가시 이슈를 매핑한 [BoardIssueView] 목록.
     */
    @Transactional(readOnly = true)
    override fun listVisibleIssuesByProject(
        projectKey: String,
        viewerUserId: UUID,
    ): List<BoardIssueView> {
        // 목록당 1회 cross-BC 호출 — N+1 없음. unrestricted=true 이면 WHERE 술어 미적용(빠른경로).
        val access = securityDirectory.accessibleLevels(viewerUserId, projectKey)
        return issueRepository
            .listVisibleForBoard(projectKey, viewerUserId, access)
            .map { it.toBoardIssueView() }
    }
}

/**
 * 도메인 [Issue] 를 보드 카드 뷰 [BoardIssueView] 로 매핑한다.
 *
 * 보드 카드 배치/정렬에 필요한 최소 필드만 추출한다 (type/description 등은 제외).
 */
private fun Issue.toBoardIssueView(): BoardIssueView =
    BoardIssueView(
        key = key.value,
        summary = summary,
        currentStateKey = currentStateKey,
        assigneeId = assigneeId?.value,
        priority = priority,
        version = version,
    )
