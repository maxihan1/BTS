// 개인 캘린더 cross-BC 조회 adapter — 프로젝트별 visibility 필터 (FR-CA-01, FR-AT-01 hot-fix로 jOOQ 를 repository 로 분리)

package com.bts.issue.adapter.outbound.calendar

import com.bts.issue.adapter.outbound.calendar.repository.CalendarWorklogRow
import com.bts.issue.adapter.outbound.calendar.repository.UserCalendarQueryRepository
import com.bts.shared.calendar.CalendarIssuePage
import com.bts.shared.calendar.CalendarWorklogPage
import com.bts.shared.calendar.CalendarWorklogView
import com.bts.shared.calendar.UserCalendarLookupPort
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [UserCalendarLookupPort] 의 issue-tracking BC 구현 (FR-CA-01 Task 2).
 *
 * identity-access BC 가 개인 캘린더 화면을 렌더링할 때 이 adapter 를 통해 담당 이슈 일정과
 * worklog 시간을 얻는다. 두 BC 는 shared-kernel 의 [UserCalendarLookupPort] 만 공유한다.
 *
 * ### visibility 경로 — 프로젝트별 격리 (D1, cross-project 재사용 술어 부재)
 *
 * issue-tracking 의 모든 visibility 술어([IssueRepository.buildActiveSecureWhere] 등)는
 * `PROJECTS.KEY.eq(projectKey)` 단일 프로젝트 축으로 하드코딩되어 있어 재사용 불가하다.
 * 담당 이슈는 여러 프로젝트에 걸칠 수 있으므로, 이 adapter 는 다음 절차로 조립한다.
 *
 * 1. 담당 이슈가 있는 프로젝트 id 집합을 열거한다([UserCalendarQueryRepository.fetchAssignedScheduledProjectIds]).
 * 2. 프로젝트마다 [IssueSecurityDirectory.accessibleLevels] 로 접근 가능 등급 집합을 조회해
 *    project_id → [IssueSecurityAccess] 맵을 구성한다([resolveIsolatedAccessByProjectId]).
 * 3. 이 맵을 [UserCalendarQueryRepository.fetchAssignedScheduledIssues] 에 넘기면, repository 가
 *    **`project_id = Pn AND ...` 로 격리한 뒤** OR 로 visibility Condition 을 조립한다(jOOQ `Condition`
 *    빌드이므로 ArchUnit 룰 2 — jOOQ 생성 코드는 repository 레이어만 접촉 — 에 따라 repository 책임이다.
 *    FR-AT-01 hot-fix 로 이 adapter 에서 [UserCalendarQueryRepository] 로 이동, 동작 변경 없음).
 *    한 프로젝트의 등급 집합을 다른 프로젝트 이슈에 적용하면 fail-open(고보안 이슈 노출) 사고로
 *    이어지므로, 격리 없이 등급 집합을 합집합으로 두는 구현은 절대 금지한다.
 *
 * ### worklog 참조 이슈 마스킹
 *
 * [listWorklogs] 는 worklog 자체(작성자=본인)는 항상 반환하되, 참조 이슈가 조회자에게 비가시면
 * [CalendarWorklogView.issueSummary] 를 null 로 마스킹한다(파일 하단 `toCalendarWorklogView` 참조).
 * 이 판정은 jOOQ 를 쓰지 않는 plain [CalendarWorklogRow] 데이터만으로 이뤄지므로 (jOOQ 생성 코드를
 * 참조하지 않아) adapter 레이어에 그대로 둔다. [CalendarWorklogView.issueKey] 는 마스킹 대상이 아니다
 * (포트 계약).
 *
 * @see UserCalendarLookupPort
 * @see IssueSecurityDirectory.accessibleLevels
 */
@Component
class UserCalendarLookupAdapter(
    private val queryRepository: UserCalendarQueryRepository,
    private val securityDirectory: IssueSecurityDirectory,
) : UserCalendarLookupPort {
    /**
     * 담당자가 [userId] 이고 기간이 설정된 활성 이슈 중, [from]~[to] 와 겹치는 이슈를 반환한다.
     *
     * soft-deleted 이슈와 조회자가 접근 불가한 보안 등급 이슈는 SQL 수준에서 제외된다.
     * [CalendarIssuePage.truncated] 가 true 이면 [CALENDAR_ISSUE_FETCH_LIMIT] 초과로 일부가 누락됐음을 의미한다.
     */
    @Transactional(readOnly = true)
    override fun listAssignedScheduledIssues(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): CalendarIssuePage {
        val projectIds = queryRepository.fetchAssignedScheduledProjectIds(userId)
        if (projectIds.isEmpty()) return CalendarIssuePage(items = emptyList(), truncated = false)

        val projectKeysById = queryRepository.fetchProjectKeysById(projectIds)
        val accessByProjectId = resolveIsolatedAccessByProjectId(userId, projectIds, projectKeysById)
        val fetched =
            queryRepository.fetchAssignedScheduledIssues(
                userId,
                from,
                to,
                accessByProjectId,
                CALENDAR_ISSUE_FETCH_LIMIT + 1,
            )

        val truncated = fetched.size > CALENDAR_ISSUE_FETCH_LIMIT
        val kept = if (truncated) fetched.take(CALENDAR_ISSUE_FETCH_LIMIT) else fetched
        return CalendarIssuePage(items = kept, truncated = truncated)
    }

    /**
     * 작성자가 [userId] 인 활성 worklog 중 [fromInstant](포함)~[toInstant](배타) 범위인 worklog 를 반환한다.
     *
     * 참조 이슈가 조회자에게 비가시면 [CalendarWorklogView.issueSummary] 를 null 로 마스킹한다
     * (`toCalendarWorklogView` 참조). [CalendarWorklogPage.truncated] 는 [CALENDAR_WORKLOG_FETCH_LIMIT] 초과 여부.
     */
    @Transactional(readOnly = true)
    override fun listWorklogs(
        userId: UUID,
        fromInstant: Instant,
        toInstant: Instant,
    ): CalendarWorklogPage {
        val fetched =
            queryRepository.fetchWorklogRows(userId, fromInstant, toInstant, CALENDAR_WORKLOG_FETCH_LIMIT + 1)
        val truncated = fetched.size > CALENDAR_WORKLOG_FETCH_LIMIT
        val kept = if (truncated) fetched.take(CALENDAR_WORKLOG_FETCH_LIMIT) else fetched

        val accessByProjectId = resolveAccessByProjectId(userId, kept)
        val items = kept.map { it.toCalendarWorklogView(userId, accessByProjectId) }
        return CalendarWorklogPage(items = items, truncated = truncated)
    }

    // ── private — 프로젝트별 접근 등급 조회 (cross-BC 오케스트레이션, jOOQ 미사용) ────────

    /**
     * 담당 이슈가 걸친 프로젝트별로 [IssueSecurityDirectory.accessibleLevels] 를 조회해
     * project_id → [IssueSecurityAccess] 맵을 구성한다.
     *
     * 프로젝트 키를 찾지 못한 id(데이터 정합성 이상)는 맵에서 제외된다(fail-closed — 이 맵을 소비하는
     * [UserCalendarQueryRepository.fetchAssignedScheduledIssues] 의 visibility Condition 은 맵에 없는
     * 프로젝트의 이슈를 결과에 포함하지 않는다).
     */
    private fun resolveIsolatedAccessByProjectId(
        actor: UUID,
        projectIds: List<UUID>,
        projectKeysById: Map<UUID, String>,
    ): Map<UUID, IssueSecurityAccess> =
        projectIds.mapNotNull { projectId ->
            val projectKey = projectKeysById[projectId] ?: return@mapNotNull null
            projectId to securityDirectory.accessibleLevels(actor, projectKey)
        }.toMap()

    /**
     * 조회 결과에 포함된 이슈들의 project_id → [IssueSecurityAccess] 맵.
     *
     * 프로젝트당 [IssueSecurityDirectory.accessibleLevels] 를 1회만 호출하도록 distinct 후 조회한다.
     */
    private fun resolveAccessByProjectId(
        actor: UUID,
        rows: List<CalendarWorklogRow>,
    ): Map<UUID, IssueSecurityAccess> {
        val distinctProjects = rows.map { it.projectId to it.projectKey }.distinct()
        return distinctProjects.associate { (projectId, projectKey) ->
            projectId to securityDirectory.accessibleLevels(actor, projectKey)
        }
    }

    companion object {
        /** [listAssignedScheduledIssues] 최대 반환 건수. 초과 시 [CalendarIssuePage.truncated]=true. */
        const val CALENDAR_ISSUE_FETCH_LIMIT = 500

        /** [listWorklogs] 최대 반환 건수. 초과 시 [CalendarWorklogPage.truncated]=true. */
        const val CALENDAR_WORKLOG_FETCH_LIMIT = 500
    }
}

// ── file-scope private helpers — worklog 마스킹. plain 데이터([CalendarWorklogRow])만 사용, jOOQ 미접촉 ──

/**
 * worklog 행을 [CalendarWorklogView] 로 매핑한다.
 *
 * 참조 이슈가 [isIssueVisibleToActor] 기준으로 비가시면 [CalendarWorklogView.issueSummary] 를
 * null 로 마스킹한다. [CalendarWorklogView.issueKey] 는 마스킹 대상이 아니다(포트 계약).
 */
private fun CalendarWorklogRow.toCalendarWorklogView(
    actor: UUID,
    accessByProjectId: Map<UUID, IssueSecurityAccess>,
): CalendarWorklogView {
    val access = accessByProjectId[projectId]
    val visible = access != null && isIssueVisibleToActor(actor, access)
    return CalendarWorklogView(
        id = id,
        issueKey = issueKey,
        issueSummary = if (visible) issueSummary else null,
        startedAt = startedAt,
        timeSpentSeconds = timeSpentSeconds,
    )
}

/**
 * 참조 이슈가 [actor] 에게 가시인지 판정한다(worklog 마스킹 전용, repository 의 `buildSecurityLevelCondition` 과
 * 동일 규칙).
 *
 * soft-deleted 이슈는 비가시로 취급한다. [access] 는 호출측이 프로젝트 키로 미리 조회해 전달한다.
 */
private fun CalendarWorklogRow.isIssueVisibleToActor(
    actor: UUID,
    access: IssueSecurityAccess,
): Boolean =
    !deleted &&
        (
            access.unrestricted ||
                securityLevelId == null ||
                securityLevelId in access.staticLevelIds ||
                (securityLevelId in access.reporterLevelIds && reporterId == actor) ||
                (securityLevelId in access.assigneeLevelIds && assigneeId == actor)
        )
