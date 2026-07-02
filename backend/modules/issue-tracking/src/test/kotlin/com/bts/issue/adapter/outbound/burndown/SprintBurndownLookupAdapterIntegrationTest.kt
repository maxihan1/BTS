// SprintBurndownLookupAdapter Testcontainers 통합 테스트 — 추정시간 합계 + UTC 날짜별 worklog 집계, 삭제 제외 (FR-RP-01 Task 3)

package com.bts.issue.adapter.outbound.burndown

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
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

    /** 번다운을 조회하는 viewer UUID — 이슈별 가시성 필터 기준. */
    private val viewerId: UUID = UUID.randomUUID()

    /** accessibleLevels 를 테스트별로 제어하는 IssueSecurityDirectory stub. */
    private val securityDirectory = StubSecurityDirectory()

    // ── setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setupAdapter() {
        // 정본 보안 술어(buildActiveSecureWhere)를 재사용하도록 실 IssueRepository 를 주입한다(복제 금지).
        adapter = SprintBurndownLookupAdapter(dsl, securityDirectory, repository)
        // 기본은 unrestricted — 개별 테스트에서 restricted access 로 덮어쓴다.
        securityDirectory.access = StubSecurityDirectory.UNRESTRICTED
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

    /**
     * 이슈 1건을 TPRJ 프로젝트에 삽입한다.
     *
     * [originalEstimateSeconds] 가 null 이 아니면 추정 시간을 함께 설정한다.
     * [securityLevelId] 가 null 이 아니면 해당 보안 등급을 부여한다(null=공개 등급).
     */
    private fun insertIssue(
        seqNum: Long,
        originalEstimateSeconds: Int? = null,
        securityLevelId: UUID? = null,
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
                securityLevelId = securityLevelId,
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
                projectKey = "TPRJ",
                viewerUserId = viewerId,
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
        val source = adapter.fetchBurndownSource(issueKeys = emptySet(), projectKey = "TPRJ", viewerUserId = viewerId)

        assertThat(source.totalOriginalEstimateSeconds).isZero()
        assertThat(source.worklogEntries).isEmpty()
    }

    // ── C1. 이슈별 가시성 필터 (security_level) ──────────────────────────────────

    /**
     * Given  공개 이슈(추정 8h) + viewer 가 접근 불가한 보안등급 이슈(추정 4h). 각각 worklog 보유.
     * When   viewer 가 restrictedLevel 미소속(NULL 등급만 접근)인 access 로 fetchBurndownSource 호출.
     * Then   기밀 이슈의 estimate(4h)·worklog(5h) 는 집계에서 제외되고 공개 이슈만 남는다.
     *        (정본 buildActiveSecureWhere 재사용 — 프로젝트 BROWSE 통과 뷰어의 시간값 간접 추론 차단, 리뷰 C1)
     */
    @Test
    fun `restricted viewer 는 접근 불가 보안등급 이슈의 estimate·worklog 를 집계에서 제외한다`() {
        val hourInSeconds = 3600
        val restrictedLevel = UUID.randomUUID()
        val publicIssue = insertIssue(seqNum = 1L, originalEstimateSeconds = 8 * hourInSeconds, securityLevelId = null)
        val secretIssue =
            insertIssue(seqNum = 2L, originalEstimateSeconds = 4 * hourInSeconds, securityLevelId = restrictedLevel)

        insertWorklog(publicIssue.id.value, 3 * hourInSeconds, Instant.parse("2024-06-01T09:00:00Z"))
        insertWorklog(secretIssue.id.value, 5 * hourInSeconds, Instant.parse("2024-06-01T09:00:00Z"))

        // viewer 는 restrictedLevel 미소속 — NULL 등급 이슈만 접근 가능.
        securityDirectory.access =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        val source =
            adapter.fetchBurndownSource(
                issueKeys = setOf(publicIssue.key.value, secretIssue.key.value),
                projectKey = "TPRJ",
                viewerUserId = viewerId,
            )

        // 기밀 이슈 estimate(4h) 제외 → 공개 이슈 8h 만.
        assertThat(source.totalOriginalEstimateSeconds).isEqualTo((8 * hourInSeconds).toLong())
        // 기밀 이슈 worklog(5h) 제외 → 공개 이슈 worklog 3h 만.
        assertThat(source.worklogEntries).hasSize(1)
        val byDate = source.worklogEntries.associateBy({ it.startedOnUtcDate }, { it.timeSpentSeconds })
        assertThat(byDate[LocalDate.parse("2024-06-01")]).isEqualTo((3 * hourInSeconds).toLong())
    }

    /**
     * Given  공개 이슈(추정 8h) + static 보안등급 이슈(추정 4h). 각각 같은 UTC 날짜에 worklog 보유.
     * When   viewer 가 staticLevel 소속인 access 로 fetchBurndownSource 호출.
     * Then   두 이슈 모두 가시 → estimate 12h·worklog 8h(3h+5h) 전부 집계에 포함된다.
     */
    @Test
    fun `허가된 viewer 는 보안등급 이슈의 estimate·worklog 를 집계에 포함한다`() {
        val hourInSeconds = 3600
        val staticLevel = UUID.randomUUID()
        val publicIssue = insertIssue(seqNum = 1L, originalEstimateSeconds = 8 * hourInSeconds, securityLevelId = null)
        val secretIssue =
            insertIssue(seqNum = 2L, originalEstimateSeconds = 4 * hourInSeconds, securityLevelId = staticLevel)

        insertWorklog(publicIssue.id.value, 3 * hourInSeconds, Instant.parse("2024-06-01T09:00:00Z"))
        insertWorklog(secretIssue.id.value, 5 * hourInSeconds, Instant.parse("2024-06-01T09:00:00Z"))

        // viewer 는 staticLevel 소속 — 두 이슈 모두 접근 가능.
        securityDirectory.access =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = setOf(staticLevel),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        val source =
            adapter.fetchBurndownSource(
                issueKeys = setOf(publicIssue.key.value, secretIssue.key.value),
                projectKey = "TPRJ",
                viewerUserId = viewerId,
            )

        assertThat(source.totalOriginalEstimateSeconds).isEqualTo(((8 + 4) * hourInSeconds).toLong())
        assertThat(source.worklogEntries).hasSize(1)
        val byDate = source.worklogEntries.associateBy({ it.startedOnUtcDate }, { it.timeSpentSeconds })
        assertThat(byDate[LocalDate.parse("2024-06-01")]).isEqualTo(((3 + 5) * hourInSeconds).toLong())
    }
}

/**
 * accessibleLevels 반환값을 테스트별로 제어하는 [IssueSecurityDirectory] stub.
 *
 * 어댑터가 정본 보안 술어(buildActiveSecureWhere)를 이 access 로 푸시다운하는지 검증하기 위한 것으로,
 * 등급 판정 로직 자체는 stub 이 아니라 실 SQL 술어가 담당한다(복제 없음).
 */
private class StubSecurityDirectory : IssueSecurityDirectory {
    var access: IssueSecurityAccess = UNRESTRICTED

    override fun levelBelongsToProjectScheme(
        levelId: UUID,
        projectKey: String,
    ): Boolean = true

    override fun accessibleLevels(
        actorId: UUID,
        projectKey: String,
    ): IssueSecurityAccess = access

    companion object {
        /** 필터 미적용 빠른경로 — 기존 (보안등급 무관) 시나리오 기본값. */
        val UNRESTRICTED =
            IssueSecurityAccess(
                unrestricted = true,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )
    }
}
