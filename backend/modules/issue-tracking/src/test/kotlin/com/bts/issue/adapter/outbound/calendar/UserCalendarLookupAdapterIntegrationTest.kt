// UserCalendarLookupAdapter Testcontainers 통합 테스트 — 담당자/기간/삭제 필터, 프로젝트별 visibility, worklog 마스킹 (FR-CA-01 Task 2)

package com.bts.issue.adapter.outbound.calendar

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [UserCalendarLookupAdapter] 통합 테스트 (FR-CA-01 Task 2).
 *
 * D1 결정 — issue-tracking 에는 재사용 가능한 cross-project visibility 술어가 없으므로
 * adapter 는 담당 이슈가 걸친 **프로젝트별로** [IssueSecurityDirectory.accessibleLevels] 를 조회하고,
 * 프로젝트 조건으로 격리한 뒤 OR 조립한다. 이 테스트의 핵심은 그 격리가 실제로 지켜지는지
 * (S4 — 한 프로젝트의 등급 집합이 다른 프로젝트 이슈에 새어 적용되지 않는지) 검증하는 것이다.
 *
 * ## 테스트 시나리오
 * - S1. 담당자/날짜/삭제 필터 — assignee=me + 날짜보유만 포함.
 * - S2. 보안등급 fail-closed — 비접근 등급 제외, 접근 등급은 포함.
 * - S3. 여러 프로젝트(상이 스킴)에 걸친 담당 이슈 모두 반환.
 * - S4. fail-open 방지 — 타 프로젝트 고보안 이슈가 다른 프로젝트 접근등급으로 노출되지 않음.
 * - S5. 기간 교차 경계 — start/due 조합별 포함·제외.
 * - S6. CALENDAR_ISSUE_FETCH_LIMIT 초과 시 truncated=true.
 * - W1. worklog author/기간/삭제 필터.
 * - W2. worklog 참조 이슈 비가시 → issueSummary null 마스킹, issueKey 유지.
 * - W3. CALENDAR_WORKLOG_FETCH_LIMIT 초과 시 truncated=true.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UserCalendarLookupAdapterIntegrationTest : IssueTestcontainersBase() {
    private var taskTypeId: IssueTypeId? = null
    private var otherProjectId: UUID? = null

    /**
     * projectKey 별로 다른 [IssueSecurityAccess] 를 반환하는 stub directory.
     *
     * adapter 가 프로젝트별로 격리된 접근 등급을 올바르게 조회·적용하는지 검증하기 위해
     * projectKey 를 키로 하는 맵을 주입받는다. 맵에 없는 projectKey 는 unrestricted 로 처리한다
     * (테스트 편의 기본값 — production 기본은 fail-closed 이며 이는 실 구현체 책임).
     */
    private class StubSecurityDirectory(
        private val accessByProjectKey: Map<String, IssueSecurityAccess>,
    ) : IssueSecurityDirectory {
        override fun levelBelongsToProjectScheme(
            levelId: UUID,
            projectKey: String,
        ): Boolean = true

        override fun accessibleLevels(
            actorId: UUID,
            projectKey: String,
        ): IssueSecurityAccess = accessByProjectKey[projectKey] ?: unrestricted()
    }

    companion object {
        private fun unrestricted() =
            IssueSecurityAccess(
                unrestricted = true,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        private fun restricted(
            staticLevelIds: Set<UUID> = emptySet(),
            reporterLevelIds: Set<UUID> = emptySet(),
            assigneeLevelIds: Set<UUID> = emptySet(),
        ) = IssueSecurityAccess(
            unrestricted = false,
            staticLevelIds = staticLevelIds,
            reporterLevelIds = reporterLevelIds,
            assigneeLevelIds = assigneeLevelIds,
        )
    }

    /** resolveTaskTypeId — V003 seed 에서 task 타입 id 조회. */
    @BeforeAll
    fun resolveTaskTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                        taskTypeId = IssueTypeId(rs.getLong(1))
                    }
                }
        }
    }

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /**
     * OTHER 프로젝트를 DB 에 삽입하고 프로젝트 UUID 를 반환한다(멱등, 같은 JVM 재호출 안전).
     */
    private fun requireOtherProjectId(): UUID {
        otherProjectId?.let { return it }
        val id =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO projects (key, name) VALUES ('OTHER', 'Other Project') ON CONFLICT (key) DO NOTHING",
                ).use { it.executeUpdate() }
                conn.prepareStatement("SELECT id FROM projects WHERE key = 'OTHER'").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            }
        otherProjectId = id
        return id
    }

    /** stub directory + 실 dsl 로 adapter 구성. */
    private fun adapterWith(access: Map<String, IssueSecurityAccess> = emptyMap()): UserCalendarLookupAdapter =
        UserCalendarLookupAdapter(dsl, StubSecurityDirectory(access))

    @Suppress("LongParameterList")
    private fun insertIssueInProject(
        projectId: UUID,
        projectKeyPrefix: String,
        seq: Long,
        reporterId: UUID = UUID.randomUUID(),
        assigneeId: UUID? = null,
        securityLevelId: UUID? = null,
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of(projectKeyPrefix, seq),
                projectId = projectId,
                typeId = requireTaskTypeId(),
                summary = "calendar issue $projectKeyPrefix-$seq",
                reporterId = ActorId(reporterId),
                currentStateKey = "open",
                assigneeId = assigneeId?.let { ActorId(it) },
                securityLevelId = securityLevelId,
            ),
        )

    private fun insertIssue(
        seq: Long,
        reporterId: UUID = UUID.randomUUID(),
        assigneeId: UUID? = null,
        securityLevelId: UUID? = null,
    ): Issue = insertIssueInProject(testProjectId, "TPRJ", seq, reporterId, assigneeId, securityLevelId)

    /** issues.start_date / due_date 를 직접 UPDATE 한다. null 이면 NULL 로 기록. */
    private fun setDates(
        issueKey: String,
        startDate: LocalDate?,
        dueDate: LocalDate?,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET start_date = ?, due_date = ? WHERE key = ?").use { stmt ->
                stmt.setObject(1, startDate)
                stmt.setObject(2, dueDate)
                stmt.setString(3, issueKey)
                stmt.executeUpdate()
            }
        }
    }

    private fun softDeleteIssue(issueKey: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeUpdate()
            }
        }
    }

    private fun insertWorklog(
        issueId: UUID,
        authorId: UUID,
        timeSpentSeconds: Int = 3600,
        startedAt: Instant = Instant.parse("2024-06-12T09:00:00Z"),
        deletedAt: Instant? = null,
    ): UUID {
        val worklogId = UUID.randomUUID()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO worklogs (id, issue_id, author_id, time_spent_seconds, started_at, deleted_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?::timestamptz, ?::timestamptz)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, worklogId.toString())
                stmt.setString(2, issueId.toString())
                stmt.setString(3, authorId.toString())
                stmt.setInt(4, timeSpentSeconds)
                stmt.setString(5, startedAt.toString())
                stmt.setString(6, deletedAt?.toString())
                stmt.executeUpdate()
            }
        }
        return worklogId
    }

    // ── S1. 담당자/날짜/삭제 필터 + 필드 매핑 ────────────────────────────────

    @Test
    @Order(1)
    fun `S1 - 담당자=me 이면서 날짜 보유한 활성 이슈만 반환되고 필드가 정확히 매핑된다`() {
        val me = UUID.randomUUID()
        val other = UUID.randomUUID()

        insertIssue(seq = 1, assigneeId = me)
        setDates("TPRJ-1", LocalDate.of(2024, 6, 10), null)

        insertIssue(seq = 2, assigneeId = me) // 날짜 없음 — 제외
        insertIssue(seq = 3, assigneeId = other) // 담당자 다름 — 제외
        setDates("TPRJ-3", LocalDate.of(2024, 6, 10), null)
        insertIssue(seq = 4, assigneeId = me) // soft-deleted — 제외
        setDates("TPRJ-4", LocalDate.of(2024, 6, 10), null)
        softDeleteIssue("TPRJ-4")

        val result =
            adapterWith().listAssignedScheduledIssues(me, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30))

        assertThat(result.items).hasSize(1)
        val view = result.items.first()
        assertThat(view.key).isEqualTo("TPRJ-1")
        assertThat(view.summary).isEqualTo("calendar issue TPRJ-1")
        assertThat(view.issueType).isEqualTo("task")
        assertThat(view.currentStateKey).isEqualTo("open")
        assertThat(view.startDate).isEqualTo(LocalDate.of(2024, 6, 10))
        assertThat(view.dueDate).isNull()
        assertThat(result.truncated).isFalse()
    }

    // ── S2. 보안등급 fail-closed / 접근 등급 포함 ─────────────────────────────

    @Test
    @Order(2)
    fun `S2 - 비접근 보안등급 이슈는 fail-closed 로 제외되고 접근 가능 등급이면 포함된다`() {
        val me = UUID.randomUUID()
        val lockedLevel = UUID.randomUUID()

        insertIssue(seq = 1, assigneeId = me, securityLevelId = null)
        setDates("TPRJ-1", LocalDate.of(2024, 6, 10), null)
        insertIssue(seq = 2, assigneeId = me, securityLevelId = lockedLevel)
        setDates("TPRJ-2", LocalDate.of(2024, 6, 11), null)

        val restrictedResult =
            adapterWith(mapOf("TPRJ" to restricted()))
                .listAssignedScheduledIssues(me, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30))
        assertThat(restrictedResult.items.map { it.key }).containsExactly("TPRJ-1")

        val grantedResult =
            adapterWith(mapOf("TPRJ" to restricted(staticLevelIds = setOf(lockedLevel))))
                .listAssignedScheduledIssues(me, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30))
        assertThat(grantedResult.items.map { it.key }).containsExactlyInAnyOrder("TPRJ-1", "TPRJ-2")
    }

    // ── S3. 여러 프로젝트(상이 스킴)에 걸친 담당 이슈 ─────────────────────────

    @Test
    @Order(3)
    fun `S3 - 여러 프로젝트(상이 스킴)에 걸친 담당 이슈를 모두 반환한다`() {
        val me = UUID.randomUUID()
        val otherProjectId = requireOtherProjectId()

        insertIssue(seq = 1, assigneeId = me, securityLevelId = null)
        setDates("TPRJ-1", LocalDate.of(2024, 6, 10), null)
        insertIssueInProject(otherProjectId, "OTHER", seq = 1, assigneeId = me, securityLevelId = null)
        setDates("OTHER-1", LocalDate.of(2024, 6, 11), null)

        val result =
            adapterWith(mapOf("TPRJ" to unrestricted(), "OTHER" to restricted()))
                .listAssignedScheduledIssues(me, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30))

        assertThat(result.items.map { it.key }).containsExactlyInAnyOrder("TPRJ-1", "OTHER-1")
    }

    // ── S4. fail-open 방지 — 프로젝트 격리 ────────────────────────────────────

    @Test
    @Order(4)
    fun `S4 - 타 프로젝트 고보안 이슈가 내 다른 프로젝트 접근등급으로 잘못 노출되지 않는다`() {
        val me = UUID.randomUUID()
        val otherProjectId = requireOtherProjectId()
        // 두 프로젝트 스킴에서 우연히 같은 levelId 값을 재사용 — fail-open 취약점 표면화용.
        val sharedLevelId = UUID.randomUUID()

        // TPRJ: sharedLevelId 접근 가능 (staticLevelIds 에 포함)
        insertIssue(seq = 1, assigneeId = me, securityLevelId = sharedLevelId)
        setDates("TPRJ-1", LocalDate.of(2024, 6, 10), null)

        // OTHER: sharedLevelId 접근 불가 (restricted, 빈 집합) — 격리가 깨지면 이 이슈가 노출된다.
        insertIssueInProject(otherProjectId, "OTHER", seq = 2, assigneeId = me, securityLevelId = sharedLevelId)
        setDates("OTHER-2", LocalDate.of(2024, 6, 11), null)

        val result =
            adapterWith(
                mapOf(
                    "TPRJ" to restricted(staticLevelIds = setOf(sharedLevelId)),
                    "OTHER" to restricted(),
                ),
            ).listAssignedScheduledIssues(me, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30))

        assertThat(result.items.map { it.key }).containsExactly("TPRJ-1")
    }

    // ── S5. 기간 교차 경계 ────────────────────────────────────────────────────

    @Test
    @Order(5)
    fun `S5 - 기간 교차 경계(start만, due만, 양쪽 포함)가 올바르게 판정된다`() {
        val me = UUID.randomUUID()
        val from = LocalDate.of(2024, 6, 10)
        val to = LocalDate.of(2024, 6, 15)

        insertIssue(seq = 1, assigneeId = me) // 범위를 완전히 포함 — 포함
        setDates("TPRJ-1", LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30))
        insertIssue(seq = 2, assigneeId = me) // start 만, 범위 내 — 포함
        setDates("TPRJ-2", LocalDate.of(2024, 6, 12), null)
        insertIssue(seq = 3, assigneeId = me) // start 만, 범위 밖(과거) — 제외
        setDates("TPRJ-3", LocalDate.of(2024, 1, 1), null)
        insertIssue(seq = 4, assigneeId = me) // due 만, 범위 내 — 포함
        setDates("TPRJ-4", null, LocalDate.of(2024, 6, 14))
        insertIssue(seq = 5, assigneeId = me) // due 만, 범위 밖(미래) — 제외
        setDates("TPRJ-5", null, LocalDate.of(2024, 12, 31))

        val result = adapterWith().listAssignedScheduledIssues(me, from, to)

        assertThat(result.items.map { it.key }).containsExactlyInAnyOrder("TPRJ-1", "TPRJ-2", "TPRJ-4")
    }

    // ── S6. truncated 전파 ────────────────────────────────────────────────────

    @Test
    @Order(6)
    @Suppress("NestedBlockDepth")
    fun `S6 - CALENDAR_ISSUE_FETCH_LIMIT 초과 시 truncated=true 이고 items 크기가 LIMIT 과 같다`() {
        val me = UUID.randomUUID()
        val typeId = requireTaskTypeId().value
        val insertCount = UserCalendarLookupAdapter.CALENDAR_ISSUE_FETCH_LIMIT + 1
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                "INSERT INTO issues " +
                    "(id, key, project_id, type_id, summary, reporter_id, " +
                    "current_state_key, assignee_id, start_date) " +
                    "VALUES (gen_random_uuid(), ?, ?, ?, ?, gen_random_uuid(), 'open', ?, '2024-06-10')",
            ).use { stmt ->
                for (seq in 1..insertCount) {
                    stmt.setString(1, "TPRJ-$seq")
                    stmt.setObject(2, testProjectId)
                    stmt.setLong(3, typeId)
                    stmt.setString(4, "truncated issue $seq")
                    stmt.setObject(5, me)
                    stmt.addBatch()
                    if (seq % 100 == 0) stmt.executeBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        }

        val result =
            adapterWith().listAssignedScheduledIssues(me, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30))

        assertThat(result.truncated).isTrue()
        assertThat(result.items).hasSize(UserCalendarLookupAdapter.CALENDAR_ISSUE_FETCH_LIMIT)
    }

    // ── W1. worklog author/기간/삭제 필터 ─────────────────────────────────────

    @Test
    @Order(7)
    fun `W1 - author=me 이면서 기간 내인 활성 worklog 만 반환된다`() {
        val me = UUID.randomUUID()
        val other = UUID.randomUUID()
        val issue = insertIssue(seq = 1, assigneeId = me, securityLevelId = null)
        val from = Instant.parse("2024-06-10T00:00:00Z")
        val to = Instant.parse("2024-06-15T00:00:00Z")

        val inRangeId =
            insertWorklog(
                issue.id.value,
                me,
                timeSpentSeconds = 1800,
                startedAt = Instant.parse("2024-06-12T09:00:00Z"),
            )
        insertWorklog(issue.id.value, me, startedAt = Instant.parse("2024-06-09T23:59:59Z")) // 범위 밖(이전) — 제외
        insertWorklog(issue.id.value, me, startedAt = to) // to 경계 — exclusive 이므로 제외
        insertWorklog(issue.id.value, other, startedAt = Instant.parse("2024-06-12T10:00:00Z")) // author 다름 — 제외
        insertWorklog(
            issue.id.value,
            me,
            startedAt = Instant.parse("2024-06-12T11:00:00Z"),
            deletedAt = Instant.parse("2024-06-12T12:00:00Z"),
        ) // deleted — 제외

        val result = adapterWith().listWorklogs(me, from, to)

        assertThat(result.items).hasSize(1)
        val view = result.items.first()
        assertThat(view.id).isEqualTo(inRangeId)
        assertThat(view.issueKey).isEqualTo("TPRJ-1")
        assertThat(view.issueSummary).isEqualTo("calendar issue TPRJ-1")
        assertThat(view.startedAt).isEqualTo(Instant.parse("2024-06-12T09:00:00Z"))
        assertThat(view.timeSpentSeconds).isEqualTo(1800)
        assertThat(result.truncated).isFalse()
    }

    // ── W2. worklog 참조 이슈 비가시 → issueSummary null 마스킹 ───────────────

    @Test
    @Order(8)
    fun `W2 - 참조 이슈가 비가시면 issueSummary 는 null 로 마스킹되고 issueKey 는 유지된다`() {
        val me = UUID.randomUUID()
        val assignee = UUID.randomUUID()
        val reporter = UUID.randomUUID()
        val lockedLevel = UUID.randomUUID()

        // me 는 assignee 도 reporter 도 아닌, 단지 worklog 를 기록한 이슈 — 비가시 등급
        val hiddenIssue =
            insertIssue(seq = 1, reporterId = reporter, assigneeId = assignee, securityLevelId = lockedLevel)
        val visibleIssue = insertIssue(seq = 2, assigneeId = me, securityLevelId = null)

        val from = Instant.parse("2024-06-10T00:00:00Z")
        val to = Instant.parse("2024-06-15T00:00:00Z")
        insertWorklog(hiddenIssue.id.value, me, startedAt = Instant.parse("2024-06-12T09:00:00Z"))
        insertWorklog(visibleIssue.id.value, me, startedAt = Instant.parse("2024-06-12T10:00:00Z"))

        val result =
            adapterWith(mapOf("TPRJ" to restricted())).listWorklogs(me, from, to)

        assertThat(result.items).hasSize(2)
        val hiddenView = result.items.first { it.issueKey == "TPRJ-1" }
        assertThat(hiddenView.issueSummary).isNull()
        val visibleView = result.items.first { it.issueKey == "TPRJ-2" }
        assertThat(visibleView.issueSummary).isEqualTo("calendar issue TPRJ-2")
    }

    // ── W3. truncated 전파 ────────────────────────────────────────────────────

    @Test
    @Order(9)
    @Suppress("NestedBlockDepth")
    fun `W3 - CALENDAR_WORKLOG_FETCH_LIMIT 초과 시 truncated=true 이고 items 크기가 LIMIT 과 같다`() {
        val me = UUID.randomUUID()
        val issue = insertIssue(seq = 1, assigneeId = me, securityLevelId = null)
        val insertCount = UserCalendarLookupAdapter.CALENDAR_WORKLOG_FETCH_LIMIT + 1
        val baseTime = Instant.parse("2024-06-10T00:00:00Z")

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                """
                INSERT INTO worklogs (id, issue_id, author_id, time_spent_seconds, started_at)
                VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?, ?::timestamptz)
                """.trimIndent(),
            ).use { stmt ->
                repeat(insertCount) { i ->
                    stmt.setString(1, issue.id.value.toString())
                    stmt.setString(2, me.toString())
                    stmt.setInt(3, 3600)
                    stmt.setString(4, baseTime.plusSeconds(i * 60L).toString())
                    stmt.addBatch()
                    if (i % 100 == 0) stmt.executeBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        }

        val result =
            adapterWith().listWorklogs(me, Instant.parse("2024-06-01T00:00:00Z"), Instant.parse("2024-06-20T00:00:00Z"))

        assertThat(result.truncated).isTrue()
        assertThat(result.items).hasSize(UserCalendarLookupAdapter.CALENDAR_WORKLOG_FETCH_LIMIT)
    }

    // ── S7. 보안등급 reporter 스코프(R3) — drift 가드 ──────────────────────────

    /**
     * `buildSecurityLevelCondition` (원본 `IssueRepository.buildSecurityCondition` R3) 의
     * reporter 조건부 등급 규칙 drift 가드.
     *
     * securityLevelId 가 reporterLevelIds 에 속하는 담당(assignee=me) 이슈는, me 가 그 이슈의
     * reporter 일 때만 포함되고 reporter 가 아니면 fail-closed 로 제외된다.
     */
    @Test
    @Order(10)
    fun `S7 - 보안등급이 reporter 스코프(R3)이면 me 가 reporter 일 때만 포함되고 아니면 제외된다`() {
        val me = UUID.randomUUID()
        val other = UUID.randomUUID()
        val reporterLevel = UUID.randomUUID()

        insertIssue(seq = 1, reporterId = me, assigneeId = me, securityLevelId = reporterLevel)
        setDates("TPRJ-1", LocalDate.of(2024, 6, 10), null)
        insertIssue(seq = 2, reporterId = other, assigneeId = me, securityLevelId = reporterLevel)
        setDates("TPRJ-2", LocalDate.of(2024, 6, 11), null)

        val result =
            adapterWith(mapOf("TPRJ" to restricted(reporterLevelIds = setOf(reporterLevel))))
                .listAssignedScheduledIssues(me, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30))

        assertThat(result.items.map { it.key }).containsExactly("TPRJ-1")
    }

    // ── S8. 보안등급 assignee 스코프(R4) — drift 가드 ──────────────────────────

    /**
     * `buildSecurityLevelCondition` (원본 `IssueRepository.buildSecurityCondition` R4) 의
     * assignee 조건부 등급 규칙 drift 가드.
     *
     * securityLevelId 가 assigneeLevelIds 에 속하는 담당(assignee=me) 이슈는 reporter 여부와
     * 무관하게 포함되고, assigneeLevelIds 에 속하지 않는 등급은 fail-closed 로 제외된다
     * (reporterId 를 me 가 아닌 타인으로 두어 R3 분기가 아닌 R4 분기가 매칭을 만드는지 확인한다).
     */
    @Test
    @Order(11)
    fun `S8 - 보안등급이 assignee 스코프(R4)이면 assignee=me 인 이슈가 포함된다`() {
        val me = UUID.randomUUID()
        val other = UUID.randomUUID()
        val assigneeLevel = UUID.randomUUID()
        val otherLevel = UUID.randomUUID()

        insertIssue(seq = 1, reporterId = other, assigneeId = me, securityLevelId = assigneeLevel)
        setDates("TPRJ-1", LocalDate.of(2024, 6, 10), null)
        insertIssue(seq = 2, reporterId = other, assigneeId = me, securityLevelId = otherLevel)
        setDates("TPRJ-2", LocalDate.of(2024, 6, 11), null)

        val result =
            adapterWith(mapOf("TPRJ" to restricted(assigneeLevelIds = setOf(assigneeLevel))))
                .listAssignedScheduledIssues(me, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30))

        assertThat(result.items.map { it.key }).containsExactly("TPRJ-1")
    }

    // ── W4. worklog 언마스킹 — reporter/assignee 스코프 등급 접근 허용 ──────────

    /**
     * `isIssueVisibleToActor` 의 R3/R4 허용 분기 drift 가드.
     *
     * 참조 이슈가 reporter/assignee 조건부 등급이어도 me 가 그 역할(reporter/assignee)로 접근
     * 가능하면 W2 의 마스킹 경로가 아니라 언마스킹(issueSummary 노출) 경로를 탄다.
     */
    @Test
    @Order(12)
    fun `W4 - 참조 이슈가 reporter,assignee 스코프 등급이고 me 가 그 역할이면 issueSummary 가 노출된다`() {
        val me = UUID.randomUUID()
        val other = UUID.randomUUID()
        val reporterLevel = UUID.randomUUID()
        val assigneeLevel = UUID.randomUUID()

        val reporterScopeIssue =
            insertIssue(seq = 1, reporterId = me, assigneeId = other, securityLevelId = reporterLevel)
        val assigneeScopeIssue =
            insertIssue(seq = 2, reporterId = other, assigneeId = me, securityLevelId = assigneeLevel)

        val from = Instant.parse("2024-06-10T00:00:00Z")
        val to = Instant.parse("2024-06-15T00:00:00Z")
        insertWorklog(reporterScopeIssue.id.value, me, startedAt = Instant.parse("2024-06-12T09:00:00Z"))
        insertWorklog(assigneeScopeIssue.id.value, me, startedAt = Instant.parse("2024-06-12T10:00:00Z"))

        val access =
            restricted(reporterLevelIds = setOf(reporterLevel), assigneeLevelIds = setOf(assigneeLevel))
        val result = adapterWith(mapOf("TPRJ" to access)).listWorklogs(me, from, to)

        assertThat(result.items).hasSize(2)
        val reporterView = result.items.first { it.issueKey == "TPRJ-1" }
        assertThat(reporterView.issueSummary).isEqualTo("calendar issue TPRJ-1")
        val assigneeView = result.items.first { it.issueKey == "TPRJ-2" }
        assertThat(assigneeView.issueSummary).isEqualTo("calendar issue TPRJ-2")
    }

    // ── W5. soft-deleted 참조 이슈 — !deleted 가드 drift 가드 ──────────────────

    /**
     * `isIssueVisibleToActor` 의 deleted 가드 drift 가드.
     *
     * 참조 이슈가 unrestricted 등급이라도 soft-delete 되면 deleted 가드에 의해 비가시로 취급되어
     * issueSummary 가 마스킹된다. worklog 자체는 issue 의 soft-delete 와 무관하게 반환되며
     * issueKey 는 유지된다(W1 은 worklog 자체 삭제만 다루고, 이 케이스(참조 이슈 삭제)는 다루지 않는다).
     */
    @Test
    @Order(13)
    fun `W5 - 참조 이슈가 soft-delete 되면 issueSummary 는 마스킹되고 issueKey 는 유지된다`() {
        val me = UUID.randomUUID()
        val issue = insertIssue(seq = 1, assigneeId = me, securityLevelId = null)
        softDeleteIssue("TPRJ-1")

        val from = Instant.parse("2024-06-10T00:00:00Z")
        val to = Instant.parse("2024-06-15T00:00:00Z")
        insertWorklog(issue.id.value, me, startedAt = Instant.parse("2024-06-12T09:00:00Z"))

        val result = adapterWith().listWorklogs(me, from, to)

        assertThat(result.items).hasSize(1)
        val view = result.items.first()
        assertThat(view.issueKey).isEqualTo("TPRJ-1")
        assertThat(view.issueSummary).isNull()
    }
}
