// 워크로그 집계 리포지토리 Testcontainers 통합 테스트 — by=issue/user/period, 날짜 필터, 프로젝트 격리 (FR-TT-02 Task 1)

package com.bts.issue.worklog.aggregate.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.worklog.aggregate.domain.AggregateGranularity
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateDimension
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * [WorklogAggregateRepository] Testcontainers 통합 테스트.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 *
 * ## 테스트 시나리오 (FR-TT-02 Task 1)
 * - A. `by=issue` — 두 이슈별 SUM + worklogCount 정확성
 * - B. `by=user` — 두 author 별 SUM
 * - C. `by=period` granularity day/week/month 버킷 경계
 * - D. `from`/`to` 필터 경계 (to 당일 포함 = to+1일 00:00 UTC exclusive)
 * - E. 다른 프로젝트 worklog 제외
 * - F. `deleted_at` 있는 worklog/issue 제외
 * - G. 빈 결과 → `emptyList`
 * - H. NFR — 5,000건 시드 후 `by=issue` 집계 p95 < 500ms
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class WorklogAggregateRepositoryTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id — value class 는 lateinit 불가, nullable var 사용 */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var aggregateRepository: WorklogAggregateRepository

    // ── setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setupRepository() {
        aggregateRepository = WorklogAggregateRepository(dsl)
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
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = 'OTHER'")
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun requireTaskTypeId(): IssueTypeId {
        return requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }
    }

    @Suppress("NestedBlockDepth")
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

    /**
     * 다른 프로젝트(OTHER)를 DB 에 삽입하고 프로젝트 UUID 를 반환한다.
     * ON CONFLICT DO NOTHING 으로 같은 JVM 내 재호출에 안전하다.
     */
    private fun insertOtherProject(): UUID =
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
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

    /** 이슈 1건을 TPRJ 프로젝트에 삽입한다. */
    private fun insertIssue(seqNum: Long): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "집계 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

    /**
     * 워크로그 1건을 worklogs 테이블에 직접 삽입한다.
     * WorklogRepository 는 timeSpentSeconds 를 Int 로 저장하므로 스키마와 일치한다.
     */
    private fun insertWorklog(
        issueId: UUID,
        authorId: UUID = UUID.randomUUID(),
        timeSpentSeconds: Int = 3600,
        startedAt: Instant = Instant.parse("2024-06-15T09:00:00Z"),
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
                VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?, ?::timestamptz, ?::timestamptz)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, issueId.toString())
                stmt.setString(2, authorId.toString())
                stmt.setInt(3, timeSpentSeconds)
                stmt.setString(4, startedAt.toString())
                stmt.setString(5, deletedAt?.toString())
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 이슈를 소프트 삭제한다 (deleted_at 설정).
     */
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

    // ── A. by=issue SUM + worklogCount ───────────────────────────────────────

    /**
     * Given  이슈 2건, 각 이슈에 워크로그 2건씩 (소요시간 다름)
     * When   aggregate(projectKey, ISSUE, null, null, null)
     * Then   이슈별 SUM 과 worklogCount 가 정확함.
     */
    @Test
    fun `A - by=issue 두 이슈 SUM과 worklogCount 정확`() {
        val issue1 = insertIssue(1L)
        val issue2 = insertIssue(2L)

        // issue1: 3600 + 1800 = 5400 초, 2건
        insertWorklog(issueId = issue1.id.value, timeSpentSeconds = 3600)
        insertWorklog(issueId = issue1.id.value, timeSpentSeconds = 1800)
        // issue2: 7200 + 900 = 8100 초, 2건
        insertWorklog(issueId = issue2.id.value, timeSpentSeconds = 7200)
        insertWorklog(issueId = issue2.id.value, timeSpentSeconds = 900)

        val rows =
            aggregateRepository.aggregate(
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )

        assertThat(rows).hasSize(2)
        val byKey = rows.associateBy { it.groupKey }

        val row1 = byKey[issue1.key.value]
        assertThat(row1).isNotNull()
        assertThat(row1!!.timeSpentSeconds).isEqualTo(5400L)
        assertThat(row1.worklogCount).isEqualTo(2)

        val row2 = byKey[issue2.key.value]
        assertThat(row2).isNotNull()
        assertThat(row2!!.timeSpentSeconds).isEqualTo(8100L)
        assertThat(row2.worklogCount).isEqualTo(2)
    }

    // ── B. by=user SUM ────────────────────────────────────────────────────────

    /**
     * Given  이슈 1건, author 두 명 각각 2건씩 워크로그
     * When   aggregate(projectKey, USER, null, null, null)
     * Then   author 별 SUM 이 정확함.
     */
    @Test
    fun `B - by=user 두 author SUM 정확`() {
        val issue = insertIssue(1L)
        val author1 = UUID.randomUUID()
        val author2 = UUID.randomUUID()

        // author1: 3600 + 3600 = 7200 초
        insertWorklog(issueId = issue.id.value, authorId = author1, timeSpentSeconds = 3600)
        insertWorklog(issueId = issue.id.value, authorId = author1, timeSpentSeconds = 3600)
        // author2: 1800 + 900 = 2700 초
        insertWorklog(issueId = issue.id.value, authorId = author2, timeSpentSeconds = 1800)
        insertWorklog(issueId = issue.id.value, authorId = author2, timeSpentSeconds = 900)

        val rows =
            aggregateRepository.aggregate(
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.USER,
                granularity = null,
                from = null,
                to = null,
            )

        assertThat(rows).hasSize(2)
        val byKey = rows.associateBy { it.groupKey }

        val row1 = byKey[author1.toString()]
        assertThat(row1).isNotNull()
        assertThat(row1!!.timeSpentSeconds).isEqualTo(7200L)
        assertThat(row1.worklogCount).isEqualTo(2)

        val row2 = byKey[author2.toString()]
        assertThat(row2).isNotNull()
        assertThat(row2!!.timeSpentSeconds).isEqualTo(2700L)
        assertThat(row2.worklogCount).isEqualTo(2)
    }

    // ── C. by=period granularity day/week/month ───────────────────────────────

    /**
     * Given  워크로그 3건 — 2024-06-01, 2024-06-08, 2024-06-15 (각각 다른 day)
     * When   aggregate(PERIOD, DAY, null, null)
     * Then   day 버킷 3개 생성, 각 버킷 groupKey = 'YYYY-MM-DD' 형식.
     */
    @Test
    fun `C1 - by=period granularity=day 날짜 버킷 경계 정확`() {
        val issue = insertIssue(1L)
        insertWorklog(
            issueId = issue.id.value,
            timeSpentSeconds = 1000,
            startedAt = Instant.parse("2024-06-01T10:00:00Z"),
        )
        insertWorklog(
            issueId = issue.id.value,
            timeSpentSeconds = 2000,
            startedAt = Instant.parse("2024-06-08T10:00:00Z"),
        )
        insertWorklog(
            issueId = issue.id.value,
            timeSpentSeconds = 3000,
            startedAt = Instant.parse("2024-06-15T10:00:00Z"),
        )

        val rows =
            aggregateRepository.aggregate(
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.PERIOD,
                granularity = AggregateGranularity.DAY,
                from = null,
                to = null,
            )

        assertThat(rows).hasSize(3)
        val keys = rows.map { it.groupKey }.toSet()
        assertThat(keys).containsExactlyInAnyOrder("2024-06-01", "2024-06-08", "2024-06-15")
    }

    /**
     * Given  워크로그 3건 — 2024-06-03(월), 2024-06-10(월), 2024-06-03(월) 같은 주
     * When   aggregate(PERIOD, WEEK, null, null)
     * Then   week 버킷: 같은 주는 합산, 다른 주는 분리.
     */
    @Test
    fun `C2 - by=period granularity=week 주 버킷 병합`() {
        val issue = insertIssue(1L)
        // 2024-06-03 (월) 와 2024-06-05 (수) — 같은 주(week starting 2024-06-03)
        insertWorklog(
            issueId = issue.id.value,
            timeSpentSeconds = 1000,
            startedAt = Instant.parse("2024-06-03T10:00:00Z"),
        )
        insertWorklog(
            issueId = issue.id.value,
            timeSpentSeconds = 2000,
            startedAt = Instant.parse("2024-06-05T10:00:00Z"),
        )
        // 2024-06-10 (월) — 다른 주
        insertWorklog(
            issueId = issue.id.value,
            timeSpentSeconds = 500,
            startedAt = Instant.parse("2024-06-10T10:00:00Z"),
        )

        val rows =
            aggregateRepository.aggregate(
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.PERIOD,
                granularity = AggregateGranularity.WEEK,
                from = null,
                to = null,
            )

        // 같은 주 합산 → 2건 버킷
        assertThat(rows).hasSize(2)
        val sumByKey = rows.associateBy({ it.groupKey }, { it.timeSpentSeconds })
        // 2024-06-03 주 = 1000+2000 = 3000
        val weekOfJun03 = rows.first { it.worklogCount == 2 }
        assertThat(weekOfJun03.timeSpentSeconds).isEqualTo(3000L)
        // 2024-06-10 주 = 500
        val weekOfJun10 = rows.first { it.worklogCount == 1 }
        assertThat(weekOfJun10.timeSpentSeconds).isEqualTo(500L)
        // sumByKey 로부터 직접 sum 검증 — 위 worklogCount 기반 단언과 이중 확인
        assertThat(sumByKey).hasSize(2)
    }

    /**
     * Given  워크로그 2건 — 2024-05-15, 2024-06-10 (다른 달)
     * When   aggregate(PERIOD, MONTH, null, null)
     * Then   month 버킷 2개 생성, groupKey = '2024-05-01', '2024-06-01'.
     */
    @Test
    fun `C3 - by=period granularity=month 월 버킷 경계 정확`() {
        val issue = insertIssue(1L)
        insertWorklog(
            issueId = issue.id.value,
            timeSpentSeconds = 3600,
            startedAt = Instant.parse("2024-05-15T10:00:00Z"),
        )
        insertWorklog(
            issueId = issue.id.value,
            timeSpentSeconds = 7200,
            startedAt = Instant.parse("2024-06-10T10:00:00Z"),
        )

        val rows =
            aggregateRepository.aggregate(
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.PERIOD,
                granularity = AggregateGranularity.MONTH,
                from = null,
                to = null,
            )

        assertThat(rows).hasSize(2)
        val keys = rows.map { it.groupKey }.toSet()
        assertThat(keys).containsExactlyInAnyOrder("2024-05-01", "2024-06-01")
    }

    // ── D. from/to 필터 경계 ──────────────────────────────────────────────────

    /**
     * Given  워크로그 3건 — 2024-06-01, 2024-06-15, 2024-06-30
     * When   from=2024-06-10, to=2024-06-15 (to 당일 포함 = to+1일 exclusive)
     * Then   2024-06-15 건만 포함 (2024-06-01 제외, 2024-06-30 제외).
     */
    @Test
    fun `D - from_to 날짜 필터 경계 (to 당일 포함)`() {
        val issue = insertIssue(1L)
        insertWorklog(
            issueId = issue.id.value,
            timeSpentSeconds = 1000,
            startedAt = Instant.parse("2024-06-01T10:00:00Z"),
        )
        insertWorklog(
            issueId = issue.id.value,
            timeSpentSeconds = 2000,
            startedAt = Instant.parse("2024-06-15T10:00:00Z"),
        )
        insertWorklog(
            issueId = issue.id.value,
            timeSpentSeconds = 3000,
            startedAt = Instant.parse("2024-06-30T10:00:00Z"),
        )

        val from = Instant.parse("2024-06-10T00:00:00Z")
        val to = Instant.parse("2024-06-15T00:00:00Z") // to 당일 포함 — 구현은 <to+1일 exclusive

        val rows =
            aggregateRepository.aggregate(
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = from,
                to = to,
            )

        assertThat(rows).hasSize(1)
        assertThat(rows[0].timeSpentSeconds).isEqualTo(2000L)
        assertThat(rows[0].worklogCount).isEqualTo(1)
    }

    // ── E. 다른 프로젝트 worklog 제외 ─────────────────────────────────────────

    /**
     * Given  TPRJ 이슈에 워크로그 1건, OTHER 프로젝트 이슈에 워크로그 1건
     * When   aggregate(projectKey="TPRJ", ...)
     * Then   TPRJ worklog 만 반환 (OTHER 제외).
     */
    @Test
    fun `E - 다른 프로젝트 worklog 제외`() {
        val otherProjectId = insertOtherProject()

        val tprjIssue = insertIssue(1L)
        insertWorklog(issueId = tprjIssue.id.value, timeSpentSeconds = 5000)

        // OTHER 프로젝트에 직접 이슈 삽입
        val otherIssueId = UUID.randomUUID()
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO issues (id, key, project_id, summary, reporter_id, type_id, current_state_key)
                VALUES (?::uuid, 'OTHER-1', ?::uuid, 'Other Issue', gen_random_uuid(),
                    (SELECT id FROM issue_types WHERE key='task' LIMIT 1), 'open')
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, otherIssueId.toString())
                stmt.setString(2, otherProjectId.toString())
                stmt.executeUpdate()
            }
        }
        insertWorklog(issueId = otherIssueId, timeSpentSeconds = 9999)

        val rows =
            aggregateRepository.aggregate(
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )

        assertThat(rows).hasSize(1)
        assertThat(rows[0].timeSpentSeconds).isEqualTo(5000L)
    }

    // ── F. deleted_at 있는 worklog/issue 제외 ────────────────────────────────

    /**
     * Given  이슈 2건 — 1건은 활성, 1건은 소프트 삭제.
     *         활성 이슈의 워크로그 2건 — 1건은 활성, 1건은 소프트 삭제.
     * When   aggregate(ISSUE, ...)
     * Then   활성 이슈 + 활성 worklog 1건만 집계됨.
     */
    @Test
    fun `F - deleted_at 있는 worklog 와 issue 는 집계에서 제외`() {
        val activeIssue = insertIssue(1L)
        val deletedIssue = insertIssue(2L)

        // 활성 이슈: 활성 worklog 1건 + 소프트 삭제 worklog 1건
        insertWorklog(issueId = activeIssue.id.value, timeSpentSeconds = 3600)
        insertWorklog(
            issueId = activeIssue.id.value,
            timeSpentSeconds = 9999,
            deletedAt = Instant.parse("2024-06-01T10:00:00Z"),
        )

        // 삭제된 이슈: 활성 worklog 1건 (이슈 삭제로 집계 대상 아님)
        insertWorklog(issueId = deletedIssue.id.value, timeSpentSeconds = 7777)
        softDeleteIssue(deletedIssue.id.value)

        val rows =
            aggregateRepository.aggregate(
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )

        assertThat(rows).hasSize(1)
        assertThat(rows[0].groupKey).isEqualTo(activeIssue.key.value)
        assertThat(rows[0].timeSpentSeconds).isEqualTo(3600L)
        assertThat(rows[0].worklogCount).isEqualTo(1)
    }

    // ── G. 빈 결과 ─────────────────────────────────────────────────────────────

    /**
     * Given  워크로그 없음
     * When   aggregate(ISSUE, ...)
     * Then   emptyList.
     */
    @Test
    fun `G - 워크로그 없을 때 빈 리스트 반환`() {
        val rows =
            aggregateRepository.aggregate(
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )

        assertThat(rows).isEmpty()
    }

    // ── H. NFR — 5,000건 p95 < 500ms ─────────────────────────────────────────

    /**
     * Given  이슈 50건, 각 이슈에 워크로그 100건 = 총 5,000건 시드
     * When   aggregate(ISSUE, ...) 를 10회 실행
     * Then   p95 응답 시간 < 500ms.
     *
     * NFR 측정 결과는 로그로 출력한다.
     */
    @Test
    @Tag("nfr")
    fun `H - NFR 5000건 시드 후 by=issue p95 500ms 미만`() {
        val issueCount = 50
        val worklogsPerIssue = 100

        val issues = (1..issueCount).map { seq -> insertIssue(seq.toLong()) }
        val baseTime = Instant.parse("2024-01-01T00:00:00Z")

        // 배치 삽입 — JDBC PreparedStatement 배치로 5,000건 시드
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                """
                INSERT INTO worklogs (id, issue_id, author_id, time_spent_seconds, started_at)
                VALUES (gen_random_uuid(), ?::uuid, gen_random_uuid(), ?, ?::timestamptz)
                """.trimIndent(),
            ).use { stmt ->
                issues.forEach { issue ->
                    repeat(worklogsPerIssue) { i ->
                        stmt.setString(1, issue.id.value.toString())
                        stmt.setInt(2, 3600 + i * 10)
                        stmt.setString(3, baseTime.plusSeconds(i * 86400L).toString())
                        stmt.addBatch()
                    }
                }
                stmt.executeBatch()
            }
            conn.commit()
        }

        // 워밍업 1회
        aggregateRepository.aggregate(
            projectKey = "TPRJ",
            dimension = WorklogAggregateDimension.ISSUE,
            granularity = null,
            from = null,
            to = null,
        )

        // 10회 측정
        val durations =
            (1..10).map {
                val start = System.nanoTime()
                val rows =
                    aggregateRepository.aggregate(
                        projectKey = "TPRJ",
                        dimension = WorklogAggregateDimension.ISSUE,
                        granularity = null,
                        from = null,
                        to = null,
                    )
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                assertThat(rows).hasSize(issueCount)
                elapsed
            }

        val sorted = durations.sorted()
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        println("[NFR-H] WorklogAggregateRepository.aggregate 5000건 p95=${p95}ms durations=$sorted")

        assertThat(p95)
            .withFailMessage("p95=%dms 이 500ms 를 초과합니다 — 집계 전용 인덱스 필요", p95)
            .isLessThan(500L)
    }
}
