// 타임라인 blocks 의존 엣지 cross-BC 조회 adapter 통합 테스트 — listBlocksDepsByProject S1~S6 + truncated 전파 검증 (FR-TL-02 Task 3).

package com.bts.issue.adapter.outbound.timeline

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.link.repository.IssueLinkRepository
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
 * [TimelineLookupAdapter.listBlocksDepsByProject] 통합 테스트 (FR-TL-02 Task 3).
 *
 * `listBlocksDepsByProject` 는 **가시 이슈 집합 안에서** BLOCKS 엣지를 조회한다.
 * 집합 바깥 이슈(비가시 보안 등급, 날짜 없음, 다른 프로젝트)를 끝점으로 하는 엣지는 자동 제거된다.
 * 이 테스트는 긍정 예시(positive control)와 부정 예시를 동일 시드에서 함께 검증해 vacuous 통과를 방지한다.
 *
 * 공유 Testcontainers 인스턴스: [IssueTestcontainersBase.postgres] JVM singleton 재사용.
 *
 * ## 테스트 시나리오
 * - S1. happy path — 날짜 있는 가시 이슈 사이 blocks 엣지 반환.
 * - S2. relates / duplicates 링크는 blocks 의존 엣지에 미포함.
 * - S3. 날짜 없는 이슈를 끝점으로 하는 엣지는 미반환 (타임라인 집합 미포함).
 * - S4. C1 positive control — 가시 이슈 엣지 존재 + 비가시 보안등급 이슈 엣지 부재(같은 시드).
 * - S5. C1 positive control — 동일 프로젝트 엣지 존재 + 다른 프로젝트 이슈 엣지 부재(같은 시드).
 * - S6. 상호 blocks(양방향) — 두 방향 엣지 모두 반환.
 * - truncated — 타임라인 LIMIT 초과 시 result.truncated=true 전파.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TimelineLookupAdapterDepsTest : IssueTestcontainersBase() {
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

    /** 실 [IssueLinkRepository] — 공유 Testcontainers DSL 사용. */
    private fun linkRepository(): IssueLinkRepository = IssueLinkRepository(dsl)

    /** stub directory + 실 repository + 실 link repository 로 adapter 구성. */
    private fun adapterWith(access: IssueSecurityAccess): TimelineLookupAdapter =
        TimelineLookupAdapter(repository, StubSecurityDirectory(access), linkRepository())

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
     * 테스트용 이슈 생성 helper.
     *
     * start_date / due_date 는 [setDates] 로 별도 설정해야 타임라인에 포함된다.
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
                summary = "deps issue $seq",
                reporterId = ActorId(reporterId),
                currentStateKey = currentStateKey,
                priority = priority,
                assigneeId = assigneeId?.let { ActorId(it) },
                securityLevelId = securityLevelId,
            ),
        )

    /** issues.start_date / due_date 를 직접 UPDATE 한다. null 이면 NULL 로 기록. */
    private fun setDates(
        issueKey: String,
        startDate: LocalDate?,
        dueDate: LocalDate?,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET start_date = ?, due_date = ? WHERE key = ?")
                .use { stmt ->
                    stmt.setObject(1, startDate)
                    stmt.setObject(2, dueDate)
                    stmt.setString(3, issueKey)
                    stmt.executeUpdate()
                }
        }
    }

    /**
     * issue_links 테이블에 링크를 직접 삽입한다.
     *
     * issue_links.id 는 IDENTITY 자동 생성이므로 명시하지 않는다.
     * ON DELETE CASCADE FK 가 있어 `cleanIssues()` 의 DELETE FROM issues 시 자동 정리된다.
     */
    private fun insertLink(
        sourceId: UUID,
        targetId: UUID,
        linkType: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO issue_links (source_id, target_id, link_type) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, sourceId)
                stmt.setObject(2, targetId)
                stmt.setString(3, linkType)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 'BETA' 프로젝트를 생성(없으면)하고 UUID 를 반환한다.
     *
     * S5 에서 cross-project 이슈를 삽입하기 위해 사용한다.
     * ON CONFLICT DO NOTHING 이라 다른 테스트가 먼저 생성해도 안전하다.
     */
    private fun ensureBetaProject(): UUID =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('BETA', 'Beta Project') ON CONFLICT (key) DO NOTHING",
            ).use { it.executeUpdate() }
            conn.prepareStatement("SELECT id FROM projects WHERE key = 'BETA'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }
        }

    // ── S1. happy path ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `S1 - 날짜 있는 가시 이슈 사이의 blocks 링크는 엣지로 반환된다`() {
        val viewer = UUID.randomUUID()
        val i1 = insertIssue(seq = 1)
        val i2 = insertIssue(seq = 2)
        setDates("TPRJ-1", LocalDate.of(2024, 1, 1), null)
        setDates("TPRJ-2", LocalDate.of(2024, 2, 1), null)
        insertLink(i1.id.value, i2.id.value, "blocks")

        val result = adapterWith(unrestricted()).listBlocksDepsByProject("TPRJ", viewer)

        assertThat(result.edges).hasSize(1)
        assertThat(result.edges.first().blockerKey).isEqualTo("TPRJ-1")
        assertThat(result.edges.first().blockedKey).isEqualTo("TPRJ-2")
        assertThat(result.truncated).isFalse()
    }

    // ── S2. relates / duplicates 링크는 제외 ─────────────────────────────────────

    @Test
    @Order(2)
    fun `S2 - relates 와 duplicates 링크는 blocks 의존 엣지에 포함되지 않는다`() {
        val viewer = UUID.randomUUID()
        val i1 = insertIssue(seq = 1)
        val i2 = insertIssue(seq = 2)
        val i3 = insertIssue(seq = 3)
        val i4 = insertIssue(seq = 4)
        setDates("TPRJ-1", LocalDate.of(2024, 1, 1), null)
        setDates("TPRJ-2", LocalDate.of(2024, 2, 1), null)
        setDates("TPRJ-3", LocalDate.of(2024, 3, 1), null)
        setDates("TPRJ-4", LocalDate.of(2024, 4, 1), null)
        insertLink(i1.id.value, i2.id.value, "relates")
        insertLink(i3.id.value, i4.id.value, "duplicates")

        val result = adapterWith(unrestricted()).listBlocksDepsByProject("TPRJ", viewer)

        assertThat(result.edges).isEmpty()
    }

    // ── S3. 날짜 없는 이슈 끝점 엣지는 부재 ─────────────────────────────────────

    @Test
    @Order(3)
    fun `S3 - start 와 due 가 모두 null 인 이슈를 끝점으로 하는 blocks 엣지는 반환되지 않는다`() {
        val viewer = UUID.randomUUID()
        val i1 = insertIssue(seq = 1)
        val i9 = insertIssue(seq = 9) // 날짜 없음 — 타임라인 미포함
        setDates("TPRJ-1", LocalDate.of(2024, 1, 1), null)
        // TPRJ-9 날짜 미설정 (start_date = null, due_date = null)
        insertLink(i1.id.value, i9.id.value, "blocks")

        val result = adapterWith(unrestricted()).listBlocksDepsByProject("TPRJ", viewer)

        assertThat(result.edges).isEmpty()
    }

    // ── S4. C1 positive control — 보안 비가시 이슈 끝점 엣지 제외 ────────────────

    @Test
    @Order(4)
    @Suppress("LongMethod")
    fun `S4 - 가시 이슈 사이 blocks 는 존재하고 비가시 보안등급 이슈 끝점 엣지는 제외된다`() {
        val viewer = UUID.randomUUID()
        val secretLevel = UUID.randomUUID()

        // (a) TPRJ-1 blocks TPRJ-2 — 양쪽 가시(null 등급) → 존재 (positive control)
        val i1 = insertIssue(seq = 1, securityLevelId = null)
        val i2 = insertIssue(seq = 2, securityLevelId = null)
        // (b) TPRJ-7 — 날짜 있지만 viewer 가 접근 불가한 보안 등급
        val i7 = insertIssue(seq = 7, securityLevelId = secretLevel)

        setDates("TPRJ-1", LocalDate.of(2024, 1, 1), null)
        setDates("TPRJ-2", LocalDate.of(2024, 2, 1), null)
        setDates("TPRJ-7", LocalDate.of(2024, 7, 1), null)

        insertLink(i1.id.value, i2.id.value, "blocks")
        insertLink(i1.id.value, i7.id.value, "blocks")

        // restricted() — secretLevel 포함 안 함 → TPRJ-7 비가시
        val result = adapterWith(restricted()).listBlocksDepsByProject("TPRJ", viewer)

        // (a) positive control: TPRJ-1 → TPRJ-2 엣지 존재 (필터가 실제로 "통과"시킴을 증명)
        assertThat(result.edges).anyMatch { it.blockerKey == "TPRJ-1" && it.blockedKey == "TPRJ-2" }
        // (b) TPRJ-7 키는 어떤 엣지에도 등장하지 않는다 (비가시 끝점 제거를 증명)
        assertThat(result.edges).noneMatch { it.blockerKey == "TPRJ-7" || it.blockedKey == "TPRJ-7" }
    }

    // ── S5. C1 positive control — 동일 프로젝트 × 다른 프로젝트 ────────────────

    @Test
    @Order(5)
    @Suppress("LongMethod")
    fun `S5 - 동일 프로젝트 blocks 는 존재하고 다른 프로젝트 이슈 끝점 엣지는 반환되지 않는다`() {
        val viewer = UUID.randomUUID()

        // (a) TPRJ-1 blocks TPRJ-2 — 동일 프로젝트, 날짜 있음 → 존재 (positive control)
        val i1 = insertIssue(seq = 1)
        val i2 = insertIssue(seq = 2)
        setDates("TPRJ-1", LocalDate.of(2024, 1, 1), null)
        setDates("TPRJ-2", LocalDate.of(2024, 2, 1), null)
        insertLink(i1.id.value, i2.id.value, "blocks")

        // (b) BETA 프로젝트 이슈 직접 삽입 — 날짜 있음, 다른 프로젝트
        val betaProjectId = ensureBetaProject()
        val betaIssueId = UUID.randomUUID()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO issues (id, key, project_id, type_id, summary, reporter_id, " +
                    "current_state_key, priority, start_date) " +
                    "VALUES (?, 'BETA-2', ?, ?, 'beta issue', gen_random_uuid(), 'open', 3, '2024-01-01')",
            ).use { stmt ->
                stmt.setObject(1, betaIssueId)
                stmt.setObject(2, betaProjectId)
                stmt.setLong(3, requireTaskTypeId().value)
                stmt.executeUpdate()
            }
        }
        insertLink(i1.id.value, betaIssueId, "blocks")

        val result = adapterWith(unrestricted()).listBlocksDepsByProject("TPRJ", viewer)

        // (a) positive control: TPRJ-1 → TPRJ-2 존재 (동일 프로젝트 필터 통과를 증명)
        assertThat(result.edges).anyMatch { it.blockerKey == "TPRJ-1" && it.blockedKey == "TPRJ-2" }
        // (b) BETA-2 키는 어떤 엣지에도 등장하지 않는다 (다른 프로젝트 끝점 제거를 증명)
        assertThat(result.edges).noneMatch { it.blockerKey == "BETA-2" || it.blockedKey == "BETA-2" }
    }

    // ── S6. 상호 blocks ──────────────────────────────────────────────────────────

    @Test
    @Order(6)
    fun `S6 - 상호 blocks 링크는 두 방향 엣지가 모두 반환된다`() {
        val viewer = UUID.randomUUID()
        val i1 = insertIssue(seq = 1)
        val i2 = insertIssue(seq = 2)
        setDates("TPRJ-1", LocalDate.of(2024, 1, 1), null)
        setDates("TPRJ-2", LocalDate.of(2024, 2, 1), null)
        insertLink(i1.id.value, i2.id.value, "blocks")
        insertLink(i2.id.value, i1.id.value, "blocks")

        val result = adapterWith(unrestricted()).listBlocksDepsByProject("TPRJ", viewer)

        assertThat(result.edges).hasSize(2)
        assertThat(result.edges).anyMatch { it.blockerKey == "TPRJ-1" && it.blockedKey == "TPRJ-2" }
        assertThat(result.edges).anyMatch { it.blockerKey == "TPRJ-2" && it.blockedKey == "TPRJ-1" }
    }

    // ── truncated 전파: timelinePage.truncated=true 시 result.truncated=true ──────

    @Test
    @Order(7)
    @Suppress("NestedBlockDepth")
    fun `truncated - 타임라인 LIMIT 초과 시 result-truncated 가 true 로 전파된다`() {
        val viewer = UUID.randomUUID()
        val typeId = requireTaskTypeId().value
        val insertCount = IssueRepository.TIMELINE_FETCH_LIMIT + 1
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                "INSERT INTO issues (id, key, project_id, type_id, summary, reporter_id, " +
                    "current_state_key, priority, start_date) " +
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

        val result = adapterWith(unrestricted()).listBlocksDepsByProject("TPRJ", viewer)

        assertThat(result.truncated).isTrue()
    }
}
