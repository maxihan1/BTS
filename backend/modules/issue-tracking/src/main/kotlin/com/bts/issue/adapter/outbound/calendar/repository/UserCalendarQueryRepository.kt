// 개인 캘린더 jOOQ 조회 리포지토리 — ISSUES/WORKLOGS/PROJECTS 쿼리 + visibility Condition (FR-AT-01 hot-fix, ArchUnit 룰2 준수)

package com.bts.issue.adapter.outbound.calendar.repository

import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.ISSUE_TYPES
import com.bts.issue.jooq.tables.references.PROJECTS
import com.bts.issue.jooq.tables.references.WORKLOGS
import com.bts.shared.calendar.CalendarIssueView
import com.bts.shared.permission.IssueSecurityAccess
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * [com.bts.issue.adapter.outbound.calendar.UserCalendarLookupAdapter] 전용 jOOQ 조회 리포지토리 (FR-AT-01 hot-fix).
 *
 * ArchUnit 룰 2([com.bts.issue.architecture.IssueBcArchTest.jooqGeneratedMustOnlyBeUsedInRepositoryLayer]) —
 * jOOQ 생성 코드(`com.bts.issue.jooq..`)는 `..repository..` 패키지에서만 접촉 가능해야 하므로, 어댑터에
 * 있던 jOOQ 쿼리·visibility Condition 빌드·Record 매핑을 이 클래스로 추출했다(동작 변경 없음, 순수 이동 —
 * velocity/burndown 선례와 동일 패턴). cross-BC 오케스트레이션(`IssueSecurityDirectory` 호출)은 어댑터가
 * 그대로 담당하고, 이 리포지토리는 어댑터가 미리 조회해 넘긴 [IssueSecurityAccess] 맵(plain 데이터)만
 * 입력으로 받는다 — securityDirectory 는 이 클래스에 주입하지 않는다.
 *
 * @param dsl jOOQ DSLContext.
 */
@Repository
class UserCalendarQueryRepository(
    private val dsl: DSLContext,
) {
    // ── listAssignedScheduledIssues 조회 ──────────────────────────────────────

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
    @Transactional(readOnly = true)
    fun fetchAssignedScheduledProjectIds(userId: UUID): List<UUID> =
        dsl.selectDistinct(ISSUES.PROJECT_ID)
            .from(ISSUES)
            .where(buildAssignedScheduledActiveCondition(userId))
            .fetch(ISSUES.PROJECT_ID)
            .filterNotNull()

    /** [projectIds] 에 대응하는 project_id → project_key 맵. 미존재 id 는 결과에서 제외된다. */
    @Transactional(readOnly = true)
    fun fetchProjectKeysById(projectIds: List<UUID>): Map<UUID, String> =
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
     * 담당 이슈 본 조회 — [accessByProjectId] 로 조립한 visibility Condition 이 V029 인덱스를 태운다
     * ([buildIsolatedProjectVisibilityCondition] 참조, D1 fail-open 방지 핵심).
     *
     * ### EXPLAIN ANALYZE 실측 (D2, 20,000행 시드, 2프로젝트 분산)
     *
     * `WHERE assignee_id=? AND deleted_at IS NULL AND ((project_id=P1 AND assignee_id=?) OR
     * (project_id=P2 AND assignee_id=?))` 형태는 프로젝트별 `Bitmap Index Scan on
     * idx_issues_project_assignee_active` 2회 + `BitmapOr` 로 실행됐다(실행시간 0.054ms,
     * [fetchAssignedScheduledProjectIds] 의 seq scan 2.5ms 대비 대폭 개선). [buildIsolatedProjectVisibilityCondition]
     * 이 `project_id = Pn AND ...` 로 프로젝트별 격리한 설계가 V029(`project_id, assignee_id`)
     * 인덱스와 정확히 일치하는 컬럼 순서임을 실측으로 확인했다 — 신규 인덱스 불필요.
     *
     * @param accessByProjectId 어댑터가 프로젝트별로 미리 조회한 [IssueSecurityAccess] 맵. 프로젝트 키를
     *   찾지 못해 어댑터 단계에서 제외된 프로젝트는 이 맵에 없다(fail-closed — 결과에서 제외됨).
     * @param limit 어댑터가 truncation 판정을 위해 넘기는 fetch 상한(어댑터의 `CALENDAR_ISSUE_FETCH_LIMIT + 1`).
     */
    @Transactional(readOnly = true)
    fun fetchAssignedScheduledIssues(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
        accessByProjectId: Map<UUID, IssueSecurityAccess>,
        limit: Int,
    ): List<CalendarIssueView> {
        val where =
            buildAssignedScheduledActiveCondition(userId)
                .and(buildDateOverlapCondition(from, to))
                .and(buildIsolatedProjectVisibilityCondition(userId, accessByProjectId))

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
            .limit(limit)
            .fetch { it.toCalendarIssueView() }
    }

    /**
     * 프로젝트별로 격리된 보안조건을 OR 로 조립한다 — D1 fail-open 방지 핵심.
     *
     * [accessByProjectId] 의 각 항목은 어댑터가 프로젝트별로 `IssueSecurityDirectory.accessibleLevels` 를
     * 조회해 구성한 것이며, 이 메서드는 그 결과를 반드시 `project_id = Pn AND ...` 로 그 프로젝트의
     * 이슈에만 적용되도록 격리한다. 프로젝트 키를 찾지 못한 id(데이터 정합성 이상)는 어댑터 단계에서
     * 이미 맵에서 제외되므로 이 메서드는 맵에 있는 항목만 조건화한다(fail-closed — 조건 미생성 시
     * 해당 프로젝트 이슈는 결과에 포함되지 않는다).
     */
    private fun buildIsolatedProjectVisibilityCondition(
        actor: UUID,
        accessByProjectId: Map<UUID, IssueSecurityAccess>,
    ): Condition {
        val perProjectConditions =
            accessByProjectId.map { (projectId, access) ->
                ISSUES.PROJECT_ID.eq(projectId).and(buildSecurityLevelCondition(actor, access))
            }
        return perProjectConditions.fold(DSL.falseCondition() as Condition) { acc, condition -> acc.or(condition) }
    }

    // ── listWorklogs 조회 ──────────────────────────────────────────────────────

    /**
     * [userId] 가 작성한 활성 worklog 를 [fromInstant](포함)~[toInstant](배타) 범위로 [limit] 건까지 조회해
     * [CalendarWorklogRow] 로 반환한다(마스킹 미적용 — 어댑터가 [CalendarWorklogRow.projectId] 로 접근 등급을
     * 조회한 뒤 마스킹을 판정한다).
     */
    @Transactional(readOnly = true)
    fun fetchWorklogRows(
        userId: UUID,
        fromInstant: Instant,
        toInstant: Instant,
        limit: Int,
    ): List<CalendarWorklogRow> {
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
            .limit(limit)
            .fetch { it.toCalendarWorklogRow() }
    }
}

// ── file-scope private helpers — dsl 인스턴스 상태에 의존하지 않는 순수 조건/매핑 (jOOQ 접촉, repository 전용) ──

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

private fun Record.toCalendarWorklogRow(): CalendarWorklogRow =
    CalendarWorklogRow(
        id = get(WORKLOGS.ID) ?: error("worklogs.id must not be null after DB read"),
        issueKey = get(ISSUES.KEY) ?: error("issues.key must not be null after DB read"),
        issueSummary = get(ISSUES.SUMMARY),
        projectId = get(ISSUES.PROJECT_ID) ?: error("issues.project_id must not be null after DB read"),
        projectKey = get(PROJECTS.KEY) ?: error("projects.key must not be null after DB read"),
        securityLevelId = get(ISSUES.SECURITY_LEVEL_ID),
        reporterId = get(ISSUES.REPORTER_ID),
        assigneeId = get(ISSUES.ASSIGNEE_ID),
        deleted = get(ISSUES.DELETED_AT) != null,
        startedAt =
            (get(WORKLOGS.STARTED_AT) ?: error("worklogs.started_at must not be null after DB read")).toInstant(),
        timeSpentSeconds =
            get(WORKLOGS.TIME_SPENT_SECONDS)
                ?: error("worklogs.time_spent_seconds must not be null after DB read"),
    )

/**
 * [UserCalendarQueryRepository.fetchWorklogRows] 조회 결과 1행 — jOOQ [Record] 를 순수 데이터로 변환한 것.
 *
 * [com.bts.issue.adapter.outbound.calendar.UserCalendarLookupAdapter] 가 이 값을 받아 프로젝트별
 * [IssueSecurityAccess] 를 조회(cross-BC)한 뒤 마스킹 여부를 판정한다 — jOOQ 타입에 의존하지 않는
 * plain 데이터이므로 어댑터가 jOOQ 를 import 하지 않고도 사용할 수 있다.
 *
 * @property issueSummary 마스킹 적용 전 원본 이슈 제목. 마스킹 판정은 소비측(어댑터) 책임.
 * @property deleted 참조 이슈의 soft-delete 여부(`issues.deleted_at IS NOT NULL`).
 */
data class CalendarWorklogRow(
    val id: UUID,
    val issueKey: String,
    val issueSummary: String?,
    val projectId: UUID,
    val projectKey: String,
    val securityLevelId: UUID?,
    val reporterId: UUID?,
    val assigneeId: UUID?,
    val deleted: Boolean,
    val startedAt: Instant,
    val timeSpentSeconds: Int,
)
