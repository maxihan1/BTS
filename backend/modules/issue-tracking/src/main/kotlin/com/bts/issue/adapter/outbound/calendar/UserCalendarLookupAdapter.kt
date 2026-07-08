// 개인 캘린더 cross-BC 조회 adapter — 프로젝트별 visibility 필터 (FR-CA-01)

package com.bts.issue.adapter.outbound.calendar

import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.ISSUE_TYPES
import com.bts.issue.jooq.tables.references.PROJECTS
import com.bts.issue.jooq.tables.references.WORKLOGS
import com.bts.shared.calendar.CalendarIssuePage
import com.bts.shared.calendar.CalendarIssueView
import com.bts.shared.calendar.CalendarWorklogPage
import com.bts.shared.calendar.CalendarWorklogView
import com.bts.shared.calendar.UserCalendarLookupPort
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
 * 담당 이슈는 여러 프로젝트에 걸칠 수 있으므로, 이 adapter 는 다음 절차로 직접 조립한다.
 *
 * 1. 담당 이슈가 있는 프로젝트 id 집합을 열거한다.
 * 2. 프로젝트마다 [IssueSecurityDirectory.accessibleLevels] 로 접근 가능 등급 집합을 조회한다.
 * 3. **`project_id = Pn AND ...` 로 격리한 뒤** OR 로 조립한다([buildIsolatedProjectVisibilityCondition]).
 *    한 프로젝트의 등급 집합을 다른 프로젝트 이슈에 적용하면 fail-open(고보안 이슈 노출) 사고로
 *    이어지므로, 격리 없이 등급 집합을 합집합으로 두는 구현은 절대 금지한다.
 *
 * ### worklog 참조 이슈 마스킹
 *
 * [listWorklogs] 는 worklog 자체(작성자=본인)는 항상 반환하되, 참조 이슈가 조회자에게 비가시면
 * [CalendarWorklogView.issueSummary] 를 null 로 마스킹한다([toCalendarWorklogView]).
 * [CalendarWorklogView.issueKey] 는 마스킹 대상이 아니다(포트 계약).
 *
 * @see UserCalendarLookupPort
 * @see IssueSecurityDirectory.accessibleLevels
 */
@Component
class UserCalendarLookupAdapter(
    private val dsl: DSLContext,
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
        val projectIds = fetchAssignedScheduledProjectIds(userId)
        if (projectIds.isEmpty()) return CalendarIssuePage(items = emptyList(), truncated = false)

        val projectKeysById = fetchProjectKeysById(projectIds)
        val visibilityCondition = buildIsolatedProjectVisibilityCondition(userId, projectIds, projectKeysById)
        val fetched = fetchAssignedScheduledIssueRows(userId, from, to, visibilityCondition)

        val truncated = fetched.size > CALENDAR_ISSUE_FETCH_LIMIT
        val kept = if (truncated) fetched.take(CALENDAR_ISSUE_FETCH_LIMIT) else fetched
        return CalendarIssuePage(items = kept.map { it.toCalendarIssueView() }, truncated = truncated)
    }

    /**
     * 작성자가 [userId] 인 활성 worklog 중 [fromInstant](포함)~[toInstant](배타) 범위인 worklog 를 반환한다.
     *
     * 참조 이슈가 조회자에게 비가시면 [CalendarWorklogView.issueSummary] 를 null 로 마스킹한다
     * ([toCalendarWorklogView] 참조). [CalendarWorklogPage.truncated] 는 [CALENDAR_WORKLOG_FETCH_LIMIT] 초과 여부.
     */
    @Transactional(readOnly = true)
    override fun listWorklogs(
        userId: UUID,
        fromInstant: Instant,
        toInstant: Instant,
    ): CalendarWorklogPage {
        val fetched = fetchWorklogRows(userId, fromInstant, toInstant)
        val truncated = fetched.size > CALENDAR_WORKLOG_FETCH_LIMIT
        val kept = if (truncated) fetched.take(CALENDAR_WORKLOG_FETCH_LIMIT) else fetched

        val accessByProjectId = resolveAccessByProjectId(userId, kept)
        val items = kept.map { it.toCalendarWorklogView(userId, accessByProjectId) }
        return CalendarWorklogPage(items = items, truncated = truncated)
    }

    // ── private — listAssignedScheduledIssues 조회 ───────────────────────────

    /**
     * [userId] 가 담당자이고 기간(시작일/마감일 중 하나)이 설정된 활성 이슈가 존재하는 프로젝트 id 집합.
     *
     * ### EXPLAIN ANALYZE 실측 (D2, 20,000행 시드, 2프로젝트 분산)
     *
     * 이 술어는 `project_id` 조건이 없어 V029 복합 부분 인덱스(`project_id, assignee_id`)의
     * 선행 컬럼을 활용하지 못한다 — 실측 결과 `Seq Scan on issues`(assignee_id 는 Filter 로만 적용,
     * 19,960/20,000 행 제거) 로 20,000행에서 2.5ms 소요. 개인 캘린더는 저빈도·저동시성 조회이므로
     * 이 정도 스캔 비용은 현재 유예 가능하다고 판단한다(D2). 향후 다량 데이터에서 병목 확인 시
     * `assignee_id` 단독(또는 `assignee_id, project_id` 순) 인덱스를 후속 PR 로 권고하며,
     * 이번 PR 에서는 신규 마이그레이션을 추가하지 않는다.
     */
    private fun fetchAssignedScheduledProjectIds(userId: UUID): List<UUID> =
        dsl.selectDistinct(ISSUES.PROJECT_ID)
            .from(ISSUES)
            .where(buildAssignedScheduledActiveCondition(userId))
            .fetch(ISSUES.PROJECT_ID)
            .filterNotNull()

    /** [projectIds] 에 대응하는 project_id → project_key 맵. 미존재 id 는 결과에서 제외된다. */
    private fun fetchProjectKeysById(projectIds: List<UUID>): Map<UUID, String> =
        dsl.select(PROJECTS.ID, PROJECTS.KEY)
            .from(PROJECTS)
            .where(PROJECTS.ID.`in`(projectIds))
            .fetch()
            .mapNotNull { record ->
                val id = record.get(PROJECTS.ID) ?: return@mapNotNull null
                val key = record.get(PROJECTS.KEY) ?: return@mapNotNull null
                id to key
            }
            .toMap()

    /**
     * 담당 이슈 본 조회 — [visibilityCondition] 의 프로젝트별 격리 `AND` 절이 V029 인덱스를 태운다.
     *
     * ### EXPLAIN ANALYZE 실측 (D2, 20,000행 시드, 2프로젝트 분산)
     *
     * `WHERE assignee_id=? AND deleted_at IS NULL AND ((project_id=P1 AND assignee_id=?) OR
     * (project_id=P2 AND assignee_id=?))` 형태는 프로젝트별 `Bitmap Index Scan on
     * idx_issues_project_assignee_active` 2회 + `BitmapOr` 로 실행됐다(실행시간 0.054ms,
     * [fetchAssignedScheduledProjectIds] 의 seq scan 2.5ms 대비 대폭 개선). [buildIsolatedProjectVisibilityCondition]
     * 이 `project_id = Pn AND ...` 로 프로젝트별 격리한 설계가 V029(`project_id, assignee_id`)
     * 인덱스와 정확히 일치하는 컬럼 순서임을 실측으로 확인했다 — 신규 인덱스 불필요.
     */
    private fun fetchAssignedScheduledIssueRows(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
        visibilityCondition: Condition,
    ): List<Record> {
        val where =
            buildAssignedScheduledActiveCondition(userId)
                .and(buildDateOverlapCondition(from, to))
                .and(visibilityCondition)

        return dsl.select(
            ISSUES.KEY,
            ISSUES.SUMMARY,
            ISSUE_TYPES.KEY,
            ISSUES.CURRENT_STATE_KEY,
            ISSUES.START_DATE,
            ISSUES.DUE_DATE,
        )
            .from(ISSUES)
            .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
            .where(where)
            // created_at 대체 — start_date 순, key 로 tie-break (M1 truncation 결정성)
            .orderBy(ISSUES.START_DATE.asc(), ISSUES.KEY.asc())
            .limit(CALENDAR_ISSUE_FETCH_LIMIT + 1)
            .fetch()
    }

    /**
     * 프로젝트별로 격리된 보안조건을 OR 로 조립한다 — D1 fail-open 방지 핵심.
     *
     * 각 프로젝트의 [IssueSecurityDirectory.accessibleLevels] 결과는 반드시
     * `project_id = Pn AND ...` 로 그 프로젝트의 이슈에만 적용되도록 격리한다.
     * 프로젝트 키를 찾지 못한 id(데이터 정합성 이상)는 조건에서 제외된다(fail-closed —
     * 조건 미생성 시 해당 프로젝트 이슈는 결과에 포함되지 않는다).
     */
    private fun buildIsolatedProjectVisibilityCondition(
        actor: UUID,
        projectIds: List<UUID>,
        projectKeysById: Map<UUID, String>,
    ): Condition {
        val perProjectConditions =
            projectIds.mapNotNull { projectId ->
                val projectKey = projectKeysById[projectId] ?: return@mapNotNull null
                val access = securityDirectory.accessibleLevels(actor, projectKey)
                ISSUES.PROJECT_ID.eq(projectId).and(buildSecurityLevelCondition(actor, access))
            }
        return perProjectConditions.fold(DSL.falseCondition() as Condition) { acc, condition -> acc.or(condition) }
    }

    // ── private — listWorklogs 조회 ───────────────────────────────────────────

    private fun fetchWorklogRows(
        userId: UUID,
        fromInstant: Instant,
        toInstant: Instant,
    ): List<Record> {
        val fromOdt = fromInstant.atOffset(ZoneOffset.UTC)
        val toOdt = toInstant.atOffset(ZoneOffset.UTC)

        return dsl.select(
            WORKLOGS.ID,
            WORKLOGS.STARTED_AT,
            WORKLOGS.TIME_SPENT_SECONDS,
            ISSUES.KEY,
            ISSUES.SUMMARY,
            ISSUES.PROJECT_ID,
            ISSUES.SECURITY_LEVEL_ID,
            ISSUES.ASSIGNEE_ID,
            ISSUES.REPORTER_ID,
            ISSUES.DELETED_AT,
            PROJECTS.KEY,
        )
            .from(WORKLOGS)
            .join(ISSUES).on(WORKLOGS.ISSUE_ID.eq(ISSUES.ID))
            .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
            .where(
                WORKLOGS.AUTHOR_ID.eq(userId)
                    .and(WORKLOGS.DELETED_AT.isNull)
                    .and(WORKLOGS.STARTED_AT.greaterOrEqual(fromOdt))
                    .and(WORKLOGS.STARTED_AT.lessThan(toOdt)),
            )
            .orderBy(WORKLOGS.STARTED_AT.desc(), WORKLOGS.ID.asc())
            .limit(CALENDAR_WORKLOG_FETCH_LIMIT + 1)
            .fetch()
    }

    /**
     * 조회 결과에 포함된 이슈들의 project_id → [IssueSecurityAccess] 맵.
     *
     * 프로젝트당 [IssueSecurityDirectory.accessibleLevels] 를 1회만 호출하도록 distinct 후 조회한다.
     */
    private fun resolveAccessByProjectId(
        actor: UUID,
        rows: List<Record>,
    ): Map<UUID, IssueSecurityAccess> {
        val distinctProjects =
            rows.mapNotNull { record ->
                val projectId = record.get(ISSUES.PROJECT_ID) ?: return@mapNotNull null
                val projectKey = record.get(PROJECTS.KEY) ?: return@mapNotNull null
                projectId to projectKey
            }.distinct()
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

// ── file-scope private helpers — dsl/securityDirectory 인스턴스 상태에 의존하지 않는 순수 조건/매핑 ──

/** [userId] 담당 + 미삭제 + 기간(시작일/마감일 중 하나) 설정 조건. 이슈 열거·본 조회 양쪽에서 공유. */
private fun buildAssignedScheduledActiveCondition(userId: UUID): Condition =
    ISSUES.ASSIGNEE_ID.eq(userId)
        .and(ISSUES.DELETED_AT.isNull)
        .and(ISSUES.START_DATE.isNotNull.or(ISSUES.DUE_DATE.isNotNull))

/**
 * [IssueSecurityAccess] 등급 판정 규칙을 SQL [Condition] 으로 표현한다.
 *
 * [IssueRepository][com.bts.issue.repository.IssueRepository] 의 (동명) 보안 조건 빌더는
 * private 이라 재사용 불가하므로 동일 규칙을 재구현한다(D1).
 * NULL 등급은 항상 공개, static 등급은 항상 노출, reporter/assignee 조건부 등급은
 * actor 가 해당 역할(REPORTER_ID/ASSIGNEE_ID = actor)일 때만 노출.
 */
private fun buildSecurityLevelCondition(
    actor: UUID,
    access: IssueSecurityAccess,
): Condition {
    if (access.unrestricted) return DSL.trueCondition()

    var condition: Condition = ISSUES.SECURITY_LEVEL_ID.isNull
    if (access.staticLevelIds.isNotEmpty()) {
        condition = condition.or(ISSUES.SECURITY_LEVEL_ID.`in`(access.staticLevelIds))
    }
    if (access.reporterLevelIds.isNotEmpty()) {
        condition =
            condition.or(
                ISSUES.SECURITY_LEVEL_ID.`in`(access.reporterLevelIds).and(ISSUES.REPORTER_ID.eq(actor)),
            )
    }
    if (access.assigneeLevelIds.isNotEmpty()) {
        condition =
            condition.or(
                ISSUES.SECURITY_LEVEL_ID.`in`(access.assigneeLevelIds).and(ISSUES.ASSIGNEE_ID.eq(actor)),
            )
    }
    return condition
}

/**
 * `[from, to]` 구간과 이슈 기간(start_date/due_date)의 교차 여부.
 *
 * start_date 또는 due_date 하나만 설정된 이슈는 `COALESCE` 로 단일 시점 이슈처럼 취급한다
 * (예: due_date 만 있으면 effectiveStart = effectiveEnd = due_date).
 */
private fun buildDateOverlapCondition(
    from: LocalDate,
    to: LocalDate,
): Condition {
    val effectiveStart = DSL.coalesce(ISSUES.START_DATE, ISSUES.DUE_DATE)
    val effectiveEnd = DSL.coalesce(ISSUES.DUE_DATE, ISSUES.START_DATE)
    return effectiveStart.lessOrEqual(to).and(effectiveEnd.greaterOrEqual(from))
}

private fun Record.toCalendarIssueView(): CalendarIssueView =
    CalendarIssueView(
        key = get(ISSUES.KEY) ?: error("issues.key must not be null after DB read"),
        summary = get(ISSUES.SUMMARY) ?: error("issues.summary must not be null after DB read"),
        issueType = get(ISSUE_TYPES.KEY) ?: error("issue_types.key must not be null after DB read"),
        currentStateKey =
            get(ISSUES.CURRENT_STATE_KEY)
                ?: error("issues.current_state_key must not be null after DB read"),
        startDate = get(ISSUES.START_DATE),
        dueDate = get(ISSUES.DUE_DATE),
    )

/**
 * worklog 행을 [CalendarWorklogView] 로 매핑한다.
 *
 * 참조 이슈가 [isIssueVisibleToActor] 기준으로 비가시면 [CalendarWorklogView.issueSummary] 를
 * null 로 마스킹한다. [CalendarWorklogView.issueKey] 는 마스킹 대상이 아니다(포트 계약).
 */
private fun Record.toCalendarWorklogView(
    actor: UUID,
    accessByProjectId: Map<UUID, IssueSecurityAccess>,
): CalendarWorklogView {
    val issueKey = get(ISSUES.KEY) ?: error("issues.key must not be null after DB read")
    val projectId = get(ISSUES.PROJECT_ID) ?: error("issues.project_id must not be null after DB read")
    val access = accessByProjectId[projectId]
    val visible = access != null && isIssueVisibleToActor(actor, access)
    return CalendarWorklogView(
        id = get(WORKLOGS.ID) ?: error("worklogs.id must not be null after DB read"),
        issueKey = issueKey,
        issueSummary = if (visible) get(ISSUES.SUMMARY) else null,
        startedAt =
            (get(WORKLOGS.STARTED_AT) ?: error("worklogs.started_at must not be null after DB read")).toInstant(),
        timeSpentSeconds =
            get(WORKLOGS.TIME_SPENT_SECONDS)
                ?: error("worklogs.time_spent_seconds must not be null after DB read"),
    )
}

/**
 * 참조 이슈가 [actor] 에게 가시인지 판정한다(worklog 마스킹 전용, [buildSecurityLevelCondition] 과 동일 규칙).
 *
 * soft-deleted 이슈는 비가시로 취급한다. [access] 는 호출측이 프로젝트 키로 미리 조회해 전달한다.
 */
private fun Record.isIssueVisibleToActor(
    actor: UUID,
    access: IssueSecurityAccess,
): Boolean {
    val deleted = get(ISSUES.DELETED_AT) != null
    val securityLevelId = get(ISSUES.SECURITY_LEVEL_ID)
    val reporterId = get(ISSUES.REPORTER_ID)
    val assigneeId = get(ISSUES.ASSIGNEE_ID)

    return !deleted &&
        (
            access.unrestricted ||
                securityLevelId == null ||
                securityLevelId in access.staticLevelIds ||
                (securityLevelId in access.reporterLevelIds && reporterId == actor) ||
                (securityLevelId in access.assigneeLevelIds && assigneeId == actor)
        )
}
