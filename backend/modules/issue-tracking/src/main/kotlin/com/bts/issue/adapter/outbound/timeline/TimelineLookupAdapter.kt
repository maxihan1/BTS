// 타임라인 아이템 cross-BC 조회 adapter — issue-tracking 이 shared-kernel TimelineLookupPort 를 구현.

package com.bts.issue.adapter.outbound.timeline

import com.bts.issue.link.repository.IssueLinkRepository
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.timeline.TimelineDepEdge
import com.bts.shared.timeline.TimelineDepsPage
import com.bts.shared.timeline.TimelineItemPage
import com.bts.shared.timeline.TimelineItemView
import com.bts.shared.timeline.TimelineLookupPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [TimelineLookupPort] 의 issue-tracking BC 구현 (FR-TL-01 Task 3).
 *
 * agile-planning BC 가 간트 차트를 렌더링할 때 이 adapter 를 통해 프로젝트의
 * 가시 이슈 날짜·상태·담당자 정보를 받는다.
 * 두 BC 는 shared-kernel 의 [TimelineLookupPort] 만 공유하며 서로를 직접 gradle 의존하지 않는다.
 *
 * ### visibility 경로 — 보드 adapter 와 동일 2단 보안 게이트 재사용
 *
 * 타임라인 아이템은 최대 500건이므로 수신자용 단건 위임(IssueVisibilityPort / IssueSecurityDecider)을
 * 쓰면 N+1 이 되고, 멤버 타입 누락 시 제목 누출 위험이 있다 (FR-NT-03 BLOCKER 재발 방지).
 * 따라서 이슈 목록 조회와 **동일한** 정석 2단 게이트를 재사용한다.
 *
 * 1. [IssueSecurityDirectory.accessibleLevels] 로 viewer 의 접근 가능 보안 등급 집합을 1회 조회.
 * 2. [IssueRepository.listVisibleForTimeline] 이 그 집합으로 SQL WHERE 술어를 푸시다운해
 *    비가시 행을 단일 쿼리에서 제외한다 (새 보안 판정 경로를 만들지 않는다).
 *
 * ### fail-safe 방향
 *
 * 조회는 보안 "판정"이 아니라 데이터 "조회"이며, 권한 게이트(BROWSE)는 간트 컨트롤러가
 * 행위자 단위로 별도 강제한다. 행단위 보안필터는 항상 적용되므로, 이 adapter 가 반환하는 목록에는
 * viewer 가 볼 수 없는 등급의 이슈가 절대 포함되지 않는다.
 * [TimelineLookupPort] 의 default 구현은 빈 페이지를 반환해 adapter 부재 환경에서도 안전하다.
 *
 * @see TimelineLookupPort
 * @see IssueRepository.listVisibleForTimeline
 */
@Component
class TimelineLookupAdapter(
    private val issueRepository: IssueRepository,
    private val securityDirectory: IssueSecurityDirectory,
    private val linkRepository: IssueLinkRepository? = null,
) : TimelineLookupPort {
    /**
     * 프로젝트의 가시 이슈 목록을 [TimelineItemPage] 로 반환한다.
     *
     * viewer 가 볼 수 없는 보안 등급 이슈와 soft-deleted 이슈는 SQL 수준에서 제외된다.
     * startDate / dueDate 가 모두 null 인 이슈도 SQL 수준에서 제외된다(간트 바 표시 불가).
     * [TimelineItemPage.truncated] 가 true 이면 TIMELINE_FETCH_LIMIT(500) 초과로 일부 이슈가
     * 누락됐음을 의미한다.
     *
     * @param projectKey 조회할 프로젝트 키. 예: `"ATLAS"`.
     * @param viewerUserId 간트 차트를 조회하는 사용자 UUID. visibility 필터 기준.
     * @return 가시 이슈를 매핑한 [TimelineItemPage].
     */
    @Transactional(readOnly = true)
    override fun listTimelineItemsByProject(
        projectKey: String,
        viewerUserId: UUID,
    ): TimelineItemPage {
        // 목록당 1회 cross-BC 호출 — N+1 없음. unrestricted=true 이면 WHERE 술어 미적용(빠른경로).
        val access = securityDirectory.accessibleLevels(viewerUserId, projectKey)
        val fetchResult = issueRepository.listVisibleForTimeline(projectKey, viewerUserId, access)
        return TimelineItemPage(
            items = fetchResult.entries.map { it.toTimelineItemView() },
            truncated = fetchResult.truncated,
        )
    }

    /**
     * 프로젝트의 `blocks` 관계 의존 엣지 목록을 [TimelineDepsPage] 로 반환한다.
     *
     * ### 가시성 보장 — 새 보안 판정 경로 신설 없음
     *
     * 가시성 판단은 [listTimelineItemsByProject] 와 **동일한 2단 게이트**를 재사용한다.
     * [IssueSecurityDirectory.accessibleLevels] 로 viewer 의 접근 가능 보안 등급 집합을 1회 조회하고,
     * [IssueRepository.listVisibleForTimeline] 이 그 집합을 SQL WHERE 술어로 푸시다운한다.
     * 이미 가시·동일프로젝트·날짜보유·미삭제로 필터된 이슈 id 집합을 구성한 뒤,
     * [IssueLinkRepository.findBlocksEdgesAmong] 이 그 집합 **내부**의 BLOCKS 엣지만 반환한다.
     * 집합 밖 이슈(비가시 보안 등급, 날짜 없음, 다른 프로젝트, soft-deleted)를 끝점으로 하는
     * 엣지는 집합 내 조회 방식으로 자동 제거되므로 별도 필터가 필요하지 않다.
     *
     * ### truncated 전파
     *
     * [IssueRepository.TimelineFetchResult.truncated] (타임라인 이슈 LIMIT 초과) 또는
     * BLOCKS 엣지가 [IssueLinkRepository.DEPS_FETCH_LIMIT] 를 초과한 경우 모두 truncated=true 를
     * 반환한다. 어느 한 쪽이라도 초과하면 간트 차트가 불완전할 수 있음을 소비자에게 알린다.
     *
     * @param projectKey 조회할 프로젝트 키. 예: `"ATLAS"`.
     * @param viewerUserId 간트 차트를 조회하는 사용자 UUID. visibility 필터 기준.
     * @return [TimelineDepsPage]. linkRepository 미주입 시 또는 가시 이슈가 없으면 빈 페이지.
     */
    @Transactional(readOnly = true)
    override fun listBlocksDepsByProject(
        projectKey: String,
        viewerUserId: UUID,
    ): TimelineDepsPage {
        val repo = linkRepository ?: return TimelineDepsPage(edges = emptyList(), truncated = false)

        val access = securityDirectory.accessibleLevels(viewerUserId, projectKey)
        val timelinePage = issueRepository.listVisibleForTimeline(projectKey, viewerUserId, access)

        // 가시·동일프로젝트·날짜보유·미삭제 이슈 id → key 맵 구성.
        // id 집합이 보안 게이트를 통과했으므로, 집합 내 조회만으로 결과의 가시성이 보장된다.
        // 빈 집합 short-circuit 은 IssueLinkRepository.findBlocksEdgesAmong 이 처리하므로
        // 여기서 별도 isEmpty() 가드가 불필요하다.
        val idToKey = buildIdToKeyMap(timelinePage.entries)

        val rows = repo.findBlocksEdgesAmong(idToKey.keys)
        val blocksTruncated = rows.size > IssueLinkRepository.DEPS_FETCH_LIMIT
        val kept = if (blocksTruncated) rows.take(IssueLinkRepository.DEPS_FETCH_LIMIT) else rows

        val edges =
            kept.map { row ->
                TimelineDepEdge(
                    blockerKey = idToKey.getValue(row.sourceId),
                    blockedKey = idToKey.getValue(row.targetId),
                )
            }

        return TimelineDepsPage(
            edges = edges,
            truncated = timelinePage.truncated || blocksTruncated,
        )
    }
}

/**
 * [IssueRepository.TimelineIssueEntry] 목록에서 이슈 id → 이슈 key 매핑을 구성한다.
 *
 * [listBlocksDepsByProject] 에서 BLOCKS 엣지 조회 후 UUID → String 키 변환에 사용한다.
 * id 집합은 이미 가시성·프로젝트·날짜·삭제 필터를 통과했으므로 별도 보안 검사가 불필요하다.
 *
 * @param entries 가시 이슈 엔트리 목록.
 * @return `issue.id.value(UUID)` → `issue.key.value(String)` 맵.
 */
private fun buildIdToKeyMap(entries: List<IssueRepository.TimelineIssueEntry>): Map<UUID, String> =
    entries.associate { it.issue.id.value to it.issue.key.value }

/**
 * [IssueRepository.TimelineIssueEntry] 를 타임라인 뷰 [TimelineItemView] 로 매핑한다.
 *
 * [com.bts.issue.domain.Issue] 도메인 객체에는 epicKey / typeKey 필드가 없으므로
 * [IssueRepository.TimelineIssueEntry] 쌍에서 직접 전달한다.
 * 동일 프로젝트 에픽만 포함되며 cross-project 에픽은 null 이다 (P1-A 회귀방지).
 * 간트 차트 렌더링에 필요한 최소 필드만 추출한다.
 */
private fun IssueRepository.TimelineIssueEntry.toTimelineItemView(): TimelineItemView =
    TimelineItemView(
        key = issue.key.value,
        summary = issue.summary,
        issueType = typeKey,
        currentStateKey = issue.currentStateKey,
        assigneeId = issue.assigneeId?.value,
        startDate = issue.startDate,
        dueDate = issue.dueDate,
        targetDate = issue.targetDate,
        epicKey = epicKey,
    )
