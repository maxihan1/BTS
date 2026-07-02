// SprintBurndownLookupAdapter Testcontainers 통합 테스트 — 추정시간 합계 + UTC 날짜별 worklog 집계, 삭제 제외 (FR-RP-01 Task 3)

package com.bts.issue.adapter.outbound.burndown

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [SprintBurndownLookupAdapter] Testcontainers 통합 테스트 (FR-RP-01 Task 3).
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다 (WorklogAggregateRepositoryTest 선례).
 */
class SprintBurndownLookupAdapterIntegrationTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id — value class 는 lateinit 불가, nullable var 사용 */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var adapter: SprintBurndownLookupAdapter

    // ── setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setupAdapter() {
        adapter = SprintBurndownLookupAdapter(dsl)
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    /** 각 테스트 전 worklogs + issues 전체 삭제 */
    @BeforeEach
    fun cleanWorklogsAndIssues() {
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM worklogs")
                stmt.execute("DELETE FROM issues")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = 'TPRJ'")
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    private fun loadTaskTypeId(): IssueTypeId =
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    IssueTypeId(rs.getLong(1))
                }
            }
        }

    /** 이슈 1건을 TPRJ 프로젝트에 삽입한다. [originalEstimateSeconds] 가 null 이 아니면 추정 시간을 함께 설정한다. */
    private fun insertIssue(
        seqNum: Long,
        originalEstimateSeconds: Int? = null,
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "번다운 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ).copy(originalEstimateSeconds = originalEstimateSeconds),
        )

    /** 이슈를 소프트 삭제한다 (deleted_at 설정). */
    private fun softDeleteIssue(issueId: UUID) {
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.prepareStatement(
                "UPDATE issues SET deleted_at = NOW() WHERE id = ?::uuid",
            ).use { stmt ->
                stmt.setString(1, issueId.toString())
                stmt.executeUpdate()
            }
        }
    }

    /** 워크로그 1건을 worklogs 테이블에 직접 삽입한다. */
    private fun insertWorklog(
        issueId: UUID,
        timeSpentSeconds: Int,
        startedAt: Instant,
        deletedAt: Instant? = null,
    ) {
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO worklogs (id, issue_id, author_id, time_spent_seconds, started_at, deleted_at)
                VALUES (gen_random_uuid(), ?::uuid, gen_random_uuid(), ?, ?::timestamptz, ?::timestamptz)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, issueId.toString())
                stmt.setInt(2, timeSpentSeconds)
                stmt.setString(3, startedAt.toString())
                stmt.setString(4, deletedAt?.toString())
                stmt.executeUpdate()
            }
        }
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Given  이슈 3건(추정 8h/4h/4h) — 3번째는 소프트 삭제.
     *        1번 이슈에 worklog 3건(같은 UTC 날짜 2건 + 다른 날짜 1건) + 소프트 삭제된 worklog 1건.
     *        소프트 삭제된 3번 이슈에도 worklog 1건(제외 대상).
     * When   fetchBurndownSource 를 3개 키 전부로 호출.
     * Then   추정 합계는 미삭제 이슈만(8h+4h=12h). worklog 는 UTC 날짜별로 사전 집계되고
     *        소프트 삭제된 worklog · 소프트 삭제된 이슈 소속 worklog 는 결과에서 제외된다.
     */
    @Test
    fun `sums original_estimate and aggregates worklog by UTC date, excludes deleted`() {
        val hourInSeconds = 3600
        val issue1 = insertIssue(seqNum = 1L, originalEstimateSeconds = 8 * hourInSeconds)
        val issue2 = insertIssue(seqNum = 2L, originalEstimateSeconds = 4 * hourInSeconds)
        val issue3 = insertIssue(seqNum = 3L, originalEstimateSeconds = 4 * hourInSeconds)
        softDeleteIssue(issue3.id.value)

        // issue1: 2024-06-01 에 2h+1h=3h, 2024-06-02 에 3h
        insertWorklog(issue1.id.value, 2 * hourInSeconds, Instant.parse("2024-06-01T09:00:00Z"))
        insertWorklog(issue1.id.value, hourInSeconds, Instant.parse("2024-06-01T15:00:00Z"))
        insertWorklog(issue1.id.value, 3 * hourInSeconds, Instant.parse("2024-06-02T09:00:00Z"))
        // 소프트 삭제된 worklog — 집계 제외되어야 함
        insertWorklog(
            issueId = issue1.id.value,
            timeSpentSeconds = 99 * hourInSeconds,
            startedAt = Instant.parse("2024-06-01T10:00:00Z"),
            deletedAt = Instant.parse("2024-06-03T00:00:00Z"),
        )
        // 소프트 삭제된 이슈(issue3) 소속 worklog — 집계 제외되어야 함
        insertWorklog(issue3.id.value, 5 * hourInSeconds, Instant.parse("2024-06-01T09:00:00Z"))

        val source =
            adapter.fetchBurndownSource(
                issueKeys = setOf(issue1.key.value, issue2.key.value, issue3.key.value),
            )

        assertThat(source.totalOriginalEstimateSeconds).isEqualTo(((8 + 4) * hourInSeconds).toLong())
        assertThat(source.worklogEntries).hasSize(2)
        val byDate = source.worklogEntries.associateBy({ it.startedOnUtcDate }, { it.timeSpentSeconds })
        assertThat(byDate[LocalDate.parse("2024-06-01")]).isEqualTo((3 * hourInSeconds).toLong())
        assertThat(byDate[LocalDate.parse("2024-06-02")]).isEqualTo((3 * hourInSeconds).toLong())
    }

    /**
     * Given  빈 이슈 키 집합.
     * When   fetchBurndownSource(emptySet()).
     * Then   조기 반환 — 추정 합계 0, worklog 목록 빈 리스트 (jOOQ 빈 IN 절 조회 회피).
     */
    @Test
    fun `empty issueKeys returns zero scope and empty worklogs`() {
        val source = adapter.fetchBurndownSource(issueKeys = emptySet())

        assertThat(source.totalOriginalEstimateSeconds).isZero()
        assertThat(source.worklogEntries).isEmpty()
    }
}
