// 보드 카드 cross-BC 조회 adapter — issue-tracking 이 shared-kernel BoardIssueLookupPort 를 구현.

package com.bts.issue.adapter.outbound.board

import com.bts.issue.domain.Issue
import com.bts.issue.fieldpermission.adapter.AlwaysAllowFieldPermissionResolver
import com.bts.issue.repository.IssueRepository
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.board.BoardIssuePage
import com.bts.shared.board.BoardIssueView
import com.bts.shared.permission.FieldKind
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
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
 * ### 포트 계약 — 나가는 값은 **이미 마스킹된 것**이다 (FR-PM-07 · 스펙 C-6)
 *
 * [BoardIssueView.customFields] 는 이 adapter 가 [FieldPermissionResolver.visibleFields] 로
 * 이미 걸러 낸 값이다. **소비자(agile-planning)는 받은 것을 다시 거르지 않는다** — 그대로 미러한다.
 *
 * 마스킹 주체를 여기 하나로 둔 이유는 두 가지다.
 * 1. 권한 판정은 issue-tracking 이 소유한 지식이다. 소비측이 마스킹하려면 권한 모델을 복사해야 하고,
 *    그 순간 서로를 검사하지 않는 **두 번째 진실**이 생긴다.
 * 2. 판정 규칙이 REST 경로(`IssueApplicationService.maskFieldsForPage` +
 *    `IssueResponse.maskInvisible`)와 **한 곳에서 갈라지지 않게** 하려면 같은 포트를 같은 규칙으로
 *    호출하는 곳이 하나여야 한다. 규칙이 갈라지면 같은 사용자가 화면에 따라 다른 것을 보게 된다.
 *
 * 코어 필드(assigneeId/labels 등)의 필드 수준 마스킹은 이 경로의 범위가 **아니다** —
 * 보드 카드는 REST 응답과 필드 집합이 다르므로 별도 판정이 필요하다(부채 177 Task 25 범위 밖).
 *
 * @see BoardIssueLookupPort
 * @see IssueRepository.listVisibleForBoard
 */
@Component
class BoardIssueLookupAdapter(
    private val issueRepository: IssueRepository,
    private val securityDirectory: IssueSecurityDirectory,
    // 기본값은 Spring 이 관리하지 않는 단위 테스트 컨텍스트 호환용 fallback 이다 ([IssueApplicationService] 동형).
    // prod 컨텍스트에서는 IdentityAccessFieldPermissionResolver(@Profile("prod")) 또는
    // AlwaysAllowFieldPermissionResolver(@Profile("!prod")) Bean 이 타입으로 주입돼 이 기본값을 대체한다.
    private val fieldPermissionResolver: FieldPermissionResolver = AlwaysAllowFieldPermissionResolver(),
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
     * @return 가시 이슈를 매핑한 [BoardIssuePage]. `customFields` 는 이미 마스킹된 값이다(클래스 KDoc 포트 계약).
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
     *   `customFields` 는 열람 권한으로 이미 마스킹돼 있다 — 소비자는 다시 거르지 않는다(포트 계약).
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
        val visibleCustomFields = resolveVisibleCustomFields(fetchResult.entries, viewerUserId)
        return BoardIssuePage(
            issues =
                fetchResult.entries.map {
                    it.toBoardIssueView(visibleCustomFields[it.issue.projectId].orEmpty())
                },
            truncated = fetchResult.truncated,
        )
    }

    /**
     * 페이지 전체의 커스텀 필드 열람 판정을 **프로젝트당 1회** 로 모아 수행한다 (FR-PM-07).
     *
     * REST 목록 경로(`IssueApplicationService.maskFieldsForPage`)와 **같은 판정 규칙**이다 —
     * 같은 [FieldPermissionResolver.visibleFields] 에 페이지 내 커스텀 필드 키의 합집합을
     * candidates 로 넘기고, 결과 집합에 없는 키를 [toBoardIssueView] 가 제거한다.
     * 규칙이 갈라지면 같은 사용자가 화면에 따라 다른 것을 보게 된다.
     *
     * ### 카드마다 부르지 않는다
     *
     * 판정은 페이지당 1회다. 카드마다 부르면 보드 한 번에 수백 회 판정이 된다
     * (`BoardIssueLookupMaskingTest` M4/M5 가 호출 수를 1로 고정한다).
     * projectId 는 조회 결과의 [Issue.projectId] 에서 얻으므로 **추가 쿼리가 없다** —
     * `findProjectIdByKey` 를 부르면 `BoardIssueLookupCustomFieldsTest` C4/C5 의 SQL 문 수 가드가 깨진다.
     *
     * ### 호출부의 `orEmpty()` 는 **도달하지 않는 자리**다 — 살아 있는 가드가 아니다
     *
     * 이 맵의 키 집합은 같은 [entries] 를 `groupBy { it.issue.projectId }` 한 결과이므로
     * 호출부가 찾는 `it.issue.projectId` 집합과 **정확히 같다**. 따라서 조회는 null 이 될 수 없고
     * `orEmpty()` 는 한 번도 실행되지 않는다. 「판정을 못 받은 프로젝트를 막는 fail-closed 방어선」이
     * 여기 서 있다고 읽으면 **틀린다** — 어떤 테스트도 그 분기를 재지 못한다.
     * `groupBy` 키 구성이 바뀌면 그때 살아나는 자리이므로 방어적 코딩으로 남겨 둘 뿐이다.
     *
     * 실제 안전은 두 곳에서 온다.
     * 1. [FieldPermissionResolver.visibleFields] 가 던지면 예외가 그대로 전파돼 페이지 전체가 나가지 않는다.
     * 2. 판정이 한 키도 통과시키지 않으면 빈 집합이 내려가 그 프로젝트 카드의 커스텀 필드가 전부 사라진다
     *    (`BoardIssueLookupMaskingTest` M2 가 그 반대 방향 — 통과시킨 키가 남는 것 — 을 잰다).
     *
     * 커스텀 필드가 한 건도 없으면 물을 것이 없으므로 판정을 건너뛴다(빈 집합 반환).
     *
     * @param entries 보드 조회 결과 행.
     * @param viewerUserId 보드를 조회하는 사용자 UUID.
     * @return `projectId -> 열람 가능한 커스텀 필드 [FieldRef] 집합`.
     */
    private fun resolveVisibleCustomFields(
        entries: List<IssueRepository.BoardIssueEntry>,
        viewerUserId: UUID,
    ): Map<UUID, Set<FieldRef>> =
        entries
            .groupBy { it.issue.projectId }
            .mapValues { (projectId, group) ->
                val candidates =
                    group.flatMapTo(mutableSetOf()) { entry ->
                        entry.issue.customFields.keys.map { FieldRef(FieldKind.CUSTOM, it) }
                    }
                if (candidates.isEmpty()) {
                    emptySet()
                } else {
                    fieldPermissionResolver.visibleFields(viewerUserId, projectId, candidates)
                }
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
 * `typeKey` 는 조인 결과라 entry 에서, `labels`·`originalEstimateSeconds`·`customFields` 는
 * `ISSUES.fields()` 로 이미 채워진 [Issue] 도메인에서 가져온다.
 * 본문(`description`) 은 카드에 안 그려지므로 여전히 제외한다.
 *
 * ### customFields 는 추가 조회 없이 실린다 (FR-IS-10)
 *
 * `issues.custom_fields` 는 JSONB **컬럼**이라 [IssueRepository.listVisibleForBoard] 의
 * `ISSUES.fields()` 에 이미 포함돼 있고, `toIssue()` 가 맵으로 역직렬화해 도메인에 담아 준다.
 * 따라서 조인도 카드당 조회도 늘지 않는다 — `BoardIssueLookupCustomFieldsTest` C4/C5 가
 * 보드·백로그 두 경로에서 SQL 문 수 1회를 고정한다.
 * 값의 **소유는 issue-tracking BC** 이며 소비측(agile-planning)은 미러 노출만 한다.
 *
 * ### 나가는 맵은 마스킹을 통과한 것만 담는다 (FR-PM-07)
 *
 * [visibleCustomFields] 에 없는 키는 여기서 **제거**된다. `IssueResponse.maskInvisible` 의
 * 커스텀 필드 절과 같은 판정식(`FieldRef(CUSTOM, key) in visible`)을 쓴다 — 규칙을 새로 만들지 않는다.
 * 빈 집합이 들어오면 남는 키가 없다 — 판정이 한 키도 통과시키지 않은 경우가 그렇다.
 *
 * @param visibleCustomFields 이 카드의 프로젝트에서 viewer 가 열람 가능한 커스텀 필드 [FieldRef] 집합.
 */
private fun IssueRepository.BoardIssueEntry.toBoardIssueView(visibleCustomFields: Set<FieldRef>): BoardIssueView =
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
        customFields = issue.customFields.filterKeys { FieldRef(FieldKind.CUSTOM, it) in visibleCustomFields },
    )
