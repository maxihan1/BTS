// 타임라인 아이템 cross-BC 조회 adapter 통합 테스트 — accessibleLevels + listVisibleForTimeline 경로 + 매핑 검증.

package com.bts.issue.adapter.outbound.timeline

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueRepository
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
import java.time.LocalDate
import java.util.UUID

/**
 * [TimelineLookupAdapter] 통합 테스트 (FR-TL-01 Task 3).
 *
 * 타임라인 아이템 cross-BC 조회는 **목록 보안필터 정석**([BoardIssueLookupAdapterTest] 동형)을
 * 재사용해야 한다. accessibleLevels 1회 조회 → SQL WHERE 술어 푸시다운 2단 게이트를 검증한다.
 *
 * 이 테스트는 손수 만든 [IssueSecurityAccess] 를 반환하는 stub [IssueSecurityDirectory] 를 주입해,
 * adapter 가 `accessibleLevels` → `listVisibleForTimeline` → [TimelineItemView] 매핑 경로로
 * 가시 이슈만 반환하는지 검증한다.
 *
 * 공유 Testcontainers 인스턴스: [IssueTestcontainersBase.postgres] JVM singleton 재사용.
 *
 * ## 테스트 시나리오
 * - S1. accessibleLevels + 보안 등급 필터 — 비접근 등급 이슈는 제외.
 * - S2. startDate/dueDate 모두 null 인 이슈는 타임라인에서 제외.
 * - S3. typeKey→issueType, startDate, dueDate, assigneeId, key, summary, currentStateKey, epicKey 매핑 정확.
 * - S4. TIMELINE_FETCH_LIMIT 초과 시 truncated=true 전파.
 * - S5. soft-deleted 이슈는 제외.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TimelineLookupAdapterTest : IssueTestcontainersBase() {
    private var taskTypeId: IssueTypeId? = null

    /**
     * 주입할 [IssueSecurityAccess] 를 테스트마다 교체하는 stub directory.
     *
     * adapter 가 `accessibleLevels(viewerUserId, projectKey)` 를 호출하면 [next] 를 반환한다.
     * 손수 만든 access 로 SQL 술어 동작만 검증하므로 실 멤버십 조회는 하지 않는다.
     */
    private class StubSecurityDirectory(
        var next: IssueSecurityAccess,
    ) : IssueSecurityDirectory {
        override fun levelBelongsToProjectScheme(
            levelId: UUID,
            projectKey: String,
        ): Boolean = true

        override fun accessibleLevels(
            actorId: UUID,
            projectKey: String,
        ): IssueSecurityAccess = next
    }

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

    /** stub directory + 실 repository 로 adapter 구성. */
    private fun adapterWith(access: IssueSecurityAccess): TimelineLookupAdapter =
        TimelineLookupAdapter(repository, StubSecurityDirectory(access))

    /**
     * 테스트용 이슈 생성 helper.
     *
     * startDate/dueDate 는 [setDates] 로 별도 설정해야 타임라인에 포함된다.
     */
    @Suppress("LongParameterList")
    private fun insertIssue(
        seq: Long,
        reporterId: UUID = UUID.randomUUID(),
        assigneeId: UUID? = null,
        securityLevelId: UUID? = null,
        currentStateKey: String = "open",
        priority: Int = 3,
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "timeline issue $seq",
                reporterId = ActorId(reporterId),
                currentStateKey = currentStateKey,
                priority = priority,
                assigneeId = assigneeId?.let { ActorId(it) },
                securityLevelId = securityLevelId,
            ),
        )

    /** issues.start_date / due_date 를 직접 UPDATE 해 날짜를 설정한다. null 이면 NULL 로 기록. */
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

    /** issues.epic_id 를 직접 UPDATE 해 에픽 연결을 설정한다. */
    private fun setEpicId(
        childKey: String,
        epicId: UUID,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET epic_id = ? WHERE key = ?").use { stmt ->
                stmt.setObject(1, epicId)
                stmt.setString(2, childKey)
                stmt.executeUpdate()
            }
        }
    }

    // ── S1. 가시 이슈만 반환 (보안 등급 필터) ──────────────────────────────────

    @Test
    @Order(1)
    fun `S1 - 날짜 있는 가시 이슈만 반환하고 접근 불가 등급 이슈는 제외된다`() {
        val viewer = UUID.randomUUID()
        val excludedLevel = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = null)
        setDates("TPRJ-1", LocalDate.of(2024, 1, 1), null)
        insertIssue(seq = 2, securityLevelId = excludedLevel)
        setDates("TPRJ-2", LocalDate.of(2024, 2, 1), null)

        val result = adapterWith(restricted()).listTimelineItemsByProject("TPRJ", viewer)

        assertThat(result.items).hasSize(1)
        assertThat(result.items.first().key).isEqualTo("TPRJ-1")
    }

    // ── S2. 날짜 없는 이슈는 타임라인에서 제외 ────────────────────────────────

    @Test
    @Order(2)
    fun `S2 - startDate 와 dueDate 가 모두 null 인 이슈는 타임라인에서 제외된다`() {
        val viewer = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = null)
        setDates("TPRJ-1", LocalDate.of(2024, 1, 1), null)
        insertIssue(seq = 2, securityLevelId = null) // 날짜 없음 — 타임라인 미표시

        val result = adapterWith(unrestricted()).listTimelineItemsByProject("TPRJ", viewer)

        assertThat(result.items).hasSize(1)
        assertThat(result.items.first().key).isEqualTo("TPRJ-1")
    }

    // ── S3. 필드 매핑 정확 ──────────────────────────────────────────────────

    @Test
    @Order(3)
    @Suppress("LongMethod")
    fun `S3 - typeKey issueType startDate dueDate assigneeId key summary currentStateKey epicKey 가 정확히 매핑된다`() {
        val viewer = UUID.randomUUID()
        val assignee = UUID.randomUUID()
        val start = LocalDate.of(2024, 3, 1)
        val due = LocalDate.of(2024, 3, 31)

        // 에픽 이슈 삽입 — 날짜(startDate 만) 설정
        val epic = insertIssue(seq = 1, securityLevelId = null)
        setDates("TPRJ-1", start, null)

        // 자식 이슈 삽입 — 양쪽 날짜 + 담당자 + 에픽 연결
        insertIssue(seq = 2, assigneeId = assignee, securityLevelId = null, currentStateKey = "in_progress")
        setDates("TPRJ-2", start, due)
        setEpicId("TPRJ-2", epic.id.value)

        val result = adapterWith(unrestricted()).listTimelineItemsByProject("TPRJ", viewer)

        assertThat(result.items).hasSize(2)

        val child = result.items.first { it.key == "TPRJ-2" }
        assertThat(child.summary).isEqualTo("timeline issue 2")
        assertThat(child.issueType).isEqualTo("task")
        assertThat(child.currentStateKey).isEqualTo("in_progress")
        assertThat(child.assigneeId).isEqualTo(assignee)
        assertThat(child.startDate).isEqualTo(start)
        assertThat(child.dueDate).isEqualTo(due)
        assertThat(child.epicKey).isEqualTo("TPRJ-1")

        // 에픽 자신은 epicKey=null (자기참조 없음)
        val epicItem = result.items.first { it.key == "TPRJ-1" }
        assertThat(epicItem.epicKey).isNull()
        assertThat(epicItem.startDate).isEqualTo(start)
        assertThat(epicItem.dueDate).isNull()
    }

    // ── S4. truncated 전파 ──────────────────────────────────────────────────

    @Test
    @Order(4)
    @Suppress("NestedBlockDepth")
    fun `S4 - TIMELINE_FETCH_LIMIT 초과 시 truncated=true 가 전파되고 items 크기가 LIMIT 과 같다`() {
        val viewer = UUID.randomUUID()
        val typeId = requireTaskTypeId().value
        // TIMELINE_FETCH_LIMIT(500) + 1 = 501 건 직접 배치 삽입
        val insertCount = IssueRepository.TIMELINE_FETCH_LIMIT + 1
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                "INSERT INTO issues " +
                    "(id, key, project_id, type_id, summary, reporter_id, current_state_key, priority, start_date) " +
                    "VALUES (gen_random_uuid(), ?, ?, ?, ?, gen_random_uuid(), 'open', 3, '2024-01-01')",
            ).use { stmt ->
                for (seq in 1..insertCount) {
                    stmt.setString(1, "TPRJ-$seq")
                    stmt.setObject(2, testProjectId)
                    stmt.setLong(3, typeId)
                    stmt.setString(4, "truncated issue $seq")
                    stmt.addBatch()
                    if (seq % 100 == 0) stmt.executeBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        }

        val result = adapterWith(unrestricted()).listTimelineItemsByProject("TPRJ", viewer)

        assertThat(result.truncated).isTrue()
        assertThat(result.items).hasSize(IssueRepository.TIMELINE_FETCH_LIMIT)
    }

    // ── S5. soft-deleted 이슈 제외 ────────────────────────────────────────

    @Test
    @Order(5)
    fun `S5 - soft-deleted 이슈는 타임라인에서 제외된다`() {
        val viewer = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = null)
        setDates("TPRJ-1", LocalDate.of(2024, 1, 1), null)
        insertIssue(seq = 2, securityLevelId = null)
        setDates("TPRJ-2", LocalDate.of(2024, 1, 1), null)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE key = 'TPRJ-2'")
                .use { it.executeUpdate() }
        }

        val result = adapterWith(unrestricted()).listTimelineItemsByProject("TPRJ", viewer)

        assertThat(result.items).hasSize(1)
        assertThat(result.items.first().key).isEqualTo("TPRJ-1")
    }
}
