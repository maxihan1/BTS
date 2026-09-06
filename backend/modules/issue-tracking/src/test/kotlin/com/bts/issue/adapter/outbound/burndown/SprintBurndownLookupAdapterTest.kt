// 번다운 포트가 worklog 시각을 그대로 나르는지 검증하는 Testcontainers 테스트 (부채 177 Task 30)

package com.bts.issue.adapter.outbound.burndown

import com.bts.issue.adapter.outbound.burndown.repository.SprintBurndownQueryRepository
import com.bts.issue.adapter.outbound.velocity.IsolatedWorkflowStateLookup
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.statushistory.repository.StatusHistoryRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.burndown.WorklogContribution
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.workflow.ProjectKey
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 오전 시각. `Asia/Seoul` 로 옮겨도 같은 날(06-01)에 남는다. */
private val MORNING_UTC: Instant = Instant.parse("2024-06-01T10:00:00Z")

/** 심야 시각. `Asia/Seoul` 에서는 06-02 08:30 — [MORNING_UTC] 와 **다른 날**이 된다. */
private val LATE_NIGHT_UTC: Instant = Instant.parse("2024-06-01T23:30:00Z")

/** [MORNING_UTC] 와 [LATE_NIGHT_UTC] 가 공유하는 UTC 날짜 — 날짜만으로는 둘을 가를 수 없다. */
private val SHARED_UTC_DATE: LocalDate = LocalDate.parse("2024-06-01")

/** soft-delete 표시용 시각. */
private val DELETION_UTC: Instant = Instant.parse("2024-06-03T00:00:00Z")

private const val MORNING_SECONDS = 7_200
private const val LATE_NIGHT_SECONDS = 3_600
private const val SHORT_SECONDS = 1_800
private const val LONG_SECONDS = 2_700
private const val DECOY_SECONDS = 356_400
private const val TEST_PROJECT_KEY = "TPRJ"

/**
 * [SprintBurndownLookupAdapter] 가 worklog **시각**을 BC 경계 너머로 그대로 나르는지 재는 테스트 (부채 177 Task 30).
 *
 * ## 왜 이 판정이 필요한가 — 비단사(non-injective) 손실
 *
 * 이전 구현은 `(started_at AT TIME ZONE 'UTC')::date` 로 캐스팅한 뒤 `groupBy` 로 **BC 를 건너기 전에**
 * 시각을 버렸다. `10:00Z` 와 `23:30Z` 는 UTC 로는 같은 날이지만 `Asia/Seoul` 에서는 **다른 날**이다.
 * 두 값이 한 버킷에 합산되어 도착하면 소비측(agile-planning)이 무엇을 하든 되돌릴 수 없다 —
 * 보드 타임존 기준 일 귀속(Task 12)이 원리적으로 불가능해진다.
 * 처방은 [com.bts.shared.calendar.UserCalendarLookupPort] 가 이미 쓴 방향과 같다 —
 * 포트는 UTC [Instant] 원본을 나르고, 로컬 날짜 칸 배치는 소비측이 진다.
 *
 * ## 이 클래스가 지는 판정 (3축)
 *
 * | 축 | 무엇 | 왜 이것만으로는 부족한가 |
 * |---|---|---|
 * | ① 분리 | 같은 UTC 날짜의 **다른 시각** 2건이 두 항목으로, 각자의 시각·시간과 함께 도착한다 | 「따로 온다」만 재면 ②를 통과시킨다 |
 * | ② 대조군 | **완전히 같은 시각** 2건도 여전히 둘이고 **합계가 보존**된다 | 「행이 늘었다」만 재면 값이 틀려도 통과한다 |
 * | ③ 삭제 제외 | 사전집계를 풀어도 soft-deleted worklog·이슈는 여전히 빠진다 | 사전집계 해제가 술어를 같이 날리기 쉬운 자리다 |
 *
 * ★①만 두면 판별력이 없다. 「모든 행을 따로 준다」와 「시각 단위로 뭉친다」가 ①에서는 **둘 다 초록**이고
 * ②에서만 갈린다. 그리고 ①②의 **합계 보존** 단언이 없으면 행 수만 맞추고 값을 뭉갠 구현이 통과한다.
 *
 * ## 뮤테이션 검증 이력 — 재현 가능한 증거 (2026-09-06 실측)
 *
 * 「깨면 red 가 당연한 방향」이 아니라 **느슨한 구현과 올바른 구현이 갈리는 입력**인지를 잰 기록이다.
 * 재현 — [SprintBurndownQueryRepository.findWorklogContributions] 에 아래를 걸고
 * `--tests '*SprintBurndownLookupAdapterTest' --no-build-cache` 로 돌린다.
 *
 * | # | 구현에 건 뮤테이션 | red | 그 뮤테이션이 여전히 통과시키는 것 |
 * |---|---|---|---|
 * | M1 | UTC 날짜 사전집계 복원(`groupBy(utcDate)`+`SUM`, `startedAt`=`MIN`) | ①② | ③ |
 * | M2 | **시각 단위** 집계(`groupBy(started_at)`+`SUM`) | ② | ①③ |
 * | M3 | `timeSpentSeconds` 를 상수 `3_600L` 로 | ①②③ | 세 테스트의 `hasSize` 단언 전부 |
 * | M4 | `WORKLOGS.DELETED_AT.isNull` 제거 | ③ | ①② |
 * | M5 | `ISSUES.DELETED_AT.isNull` 제거 | **없음(생존)** | ①②③ 전부 |
 * | M6 | `startedAt` 을 그 날 자정으로 절단 | ①②③ | 세 테스트의 `hasSize`·합계 단언 전부 |
 *
 * ★**M2 가 이 파일의 존재 이유다.** ① 만 있으면 M2 가 살아남는다 — 시각이 다른 두 건은 시각 단위로
 * 뭉쳐도 어차피 둘로 갈리기 때문이다. 대조군 ②가 있어야 「전혀 안 뭉친다」와 「시각 단위로 뭉친다」가
 * 갈린다.
 *
 * ★**M3·M6 은 「행 수만 재면 통과한다」의 실측 증거다.** 둘 다 세 테스트의 `hasSize` 를 전부
 * 통과시켰고, 값(M3)·시각(M6) 단언에서만 걸렸다.
 *
 * ★**M5 는 생존 뮤턴트다.** [SprintBurndownLookupAdapter] 가 조회 전에
 * `IssueRepository.filterVisibleIssueKeys` 로 키 집합을 좁히는데 그 정본 술어가 이미 soft-deleted
 * 이슈를 제외한다. 그래서 리포지터리의 `ISSUES.DELETED_AT.isNull` 은 **어댑터 경로에서 중복**이고,
 * 어댑터를 통해 재는 한 어떤 입력으로도 죽지 않는다(이 task 의 변경 이전에도 같았다).
 * 리포지터리를 직접 부르는 경로가 생기면 그때는 다른 판정이 필요하다.
 *
 * ## 설정 공유
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다
 * (형제 [SprintBurndownLookupAdapterIntegrationTest] 와 같은 관용구).
 */
class SprintBurndownLookupAdapterTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id — value class 는 lateinit 불가, nullable var 사용. */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var adapter: SprintBurndownLookupAdapter

    /** 번다운을 조회하는 viewer UUID. 이 클래스는 가시성 축을 재지 않으므로 항상 unrestricted 다. */
    private val viewerId: UUID = UUID.randomUUID()

    // ── setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setupAdapter() {
        adapter =
            SprintBurndownLookupAdapter(
                SprintBurndownQueryRepository(dsl),
                UnrestrictedSecurityDirectory,
                repository,
                IssueTypeRepository(dsl),
                StatusHistoryRepository(dsl),
                mockk<IsolatedWorkflowStateLookup>().also {
                    // 이 파일은 개수 축(완료 판정)을 재지 않는다 — 상태 카탈로그가 비면 완료가 0건이다.
                    // ★`any()` 를 쓰지 않는다. ProjectKey/IssueTypeKey 는 검증하는 value class 라
                    //   MockK 의 임의 서명값이 생성자 require 에 걸린다(CycleTimeServiceTest 와 같은 관용구).
                    every { it.listStates(ProjectKey.of("TPRJ"), IssueTypeKey("task")) } returns emptyList()
                },
            )
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    /** 각 테스트 전 worklogs + issues 전체 삭제. */
    @BeforeEach
    fun cleanWorklogsAndIssues() {
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM worklogs")
                stmt.execute("DELETE FROM issues")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$TEST_PROJECT_KEY'")
            }
        }
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * **축 ①.**
     *
     * Given  같은 UTC 날짜(06-01)에 시각이 다른 worklog 2건 — `10:00Z` 2h · `23:30Z` 1h.
     *        `Asia/Seoul` 로 옮기면 앞은 06-01, 뒤는 06-02 다.
     * When   fetchBurndownSource 로 원천 데이터를 조회.
     * Then   두 항목이 **각자의 시각과 각자의 시간**을 달고 도착하고 합계도 보존된다.
     *        두 항목의 UTC 날짜는 같아서 날짜만으로는 가를 수 없다(비단사성의 직접 증거).
     */
    @Test
    fun `같은 UTC 날짜라도 시각이 다르면 두 항목으로 도착한다`() {
        val issue = insertIssue(seqNum = 1L)
        insertWorklog(issue.id.value, MORNING_SECONDS, MORNING_UTC)
        insertWorklog(issue.id.value, LATE_NIGHT_SECONDS, LATE_NIGHT_UTC)

        val entries = fetchEntries(issue.key.value)

        assertThat(entries).hasSize(2)
        assertThat(entries.map { it.startedAt }).containsExactlyInAnyOrder(MORNING_UTC, LATE_NIGHT_UTC)
        assertThat(secondsAt(entries, MORNING_UTC)).isEqualTo(MORNING_SECONDS.toLong())
        assertThat(secondsAt(entries, LATE_NIGHT_UTC)).isEqualTo(LATE_NIGHT_SECONDS.toLong())
        assertThat(entries.sumOf { it.timeSpentSeconds }).isEqualTo((MORNING_SECONDS + LATE_NIGHT_SECONDS).toLong())
        // 두 항목의 UTC 날짜는 동일 — 날짜 축만 남기면 이 둘은 원리적으로 복원 불가다.
        assertThat(entries.map { it.startedOnUtcDate }).containsExactly(SHARED_UTC_DATE, SHARED_UTC_DATE)
    }

    /**
     * **축 ② — ①의 대조군.**
     *
     * Given  **완전히 같은 시각**(`10:00Z`)에 기록된 worklog 2건 — 30분 · 45분.
     * When   fetchBurndownSource 로 원천 데이터를 조회.
     * Then   시각이 같아도 합쳐지지 않고 둘로 오며, 두 값과 합계가 그대로 보존된다.
     *
     * ★①의 대조군이다. 「시각 단위로 뭉치는」 느슨한 구현은 ①을 통과하고 **여기서만** 갈린다.
     * 합계 단언까지 두는 이유는 행 수만 맞추고 값을 뭉갠 구현을 거르기 위해서다.
     */
    @Test
    fun `완전히 같은 시각의 두 worklog 도 합쳐지지 않고 합계가 보존된다`() {
        val issue = insertIssue(seqNum = 1L)
        insertWorklog(issue.id.value, SHORT_SECONDS, MORNING_UTC)
        insertWorklog(issue.id.value, LONG_SECONDS, MORNING_UTC)

        val entries = fetchEntries(issue.key.value)

        assertThat(entries).hasSize(2)
        assertThat(entries.map { it.startedAt }).containsExactly(MORNING_UTC, MORNING_UTC)
        assertThat(entries.map { it.timeSpentSeconds })
            .containsExactlyInAnyOrder(SHORT_SECONDS.toLong(), LONG_SECONDS.toLong())
        assertThat(entries.sumOf { it.timeSpentSeconds }).isEqualTo((SHORT_SECONDS + LONG_SECONDS).toLong())
    }

    /**
     * **축 ③.**
     *
     * Given  살아있는 worklog 1건 + soft-deleted worklog 1건 + soft-deleted 이슈에 속한 worklog 1건.
     * When   두 이슈 키 전부로 fetchBurndownSource 를 호출.
     * Then   살아있는 1건만 남는다 — 사전집계를 푸는 과정에서 삭제 술어가 함께 날아가지 않았다.
     */
    @Test
    fun `사전집계를 풀어도 soft-deleted worklog 와 soft-deleted 이슈는 제외된다`() {
        val issue = insertIssue(seqNum = 1L)
        val deletedIssue = insertIssue(seqNum = 2L)
        softDeleteIssue(deletedIssue.id.value)
        insertWorklog(issue.id.value, MORNING_SECONDS, MORNING_UTC)
        insertWorklog(issue.id.value, DECOY_SECONDS, LATE_NIGHT_UTC, deletedAt = DELETION_UTC)
        insertWorklog(deletedIssue.id.value, DECOY_SECONDS, MORNING_UTC)

        val entries = fetchEntries(issue.key.value, deletedIssue.key.value)

        assertThat(entries).hasSize(1)
        assertThat(entries.single().startedAt).isEqualTo(MORNING_UTC)
        assertThat(entries.single().timeSpentSeconds).isEqualTo(MORNING_SECONDS.toLong())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun fetchEntries(vararg issueKeys: String): List<WorklogContribution> =
        adapter.fetchBurndownSource(
            issueKeys = issueKeys.toSet(),
            projectKey = TEST_PROJECT_KEY,
            viewerUserId = viewerId,
        ).worklogEntries

    /** [at] 시각을 가진 유일한 항목의 기록 시간(초). 항목이 0개거나 2개 이상이면 예외로 실패한다. */
    private fun secondsAt(
        entries: List<WorklogContribution>,
        at: Instant,
    ): Long = entries.single { it.startedAt == at }.timeSpentSeconds

    private fun <T> withConnection(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use(block)

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    private fun loadTaskTypeId(): IssueTypeId =
        withConnection { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    IssueTypeId(rs.getLong(1))
                }
            }
        }

    /** 이슈 1건을 TPRJ 프로젝트에 삽입한다. 추정 시간은 이 클래스의 판정 축이 아니라 설정하지 않는다. */
    private fun insertIssue(seqNum: Long): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of(TEST_PROJECT_KEY, seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "번다운 시각 보존 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                securityLevelId = null,
            ),
        )

    /** 이슈를 소프트 삭제한다 (deleted_at 설정). */
    private fun softDeleteIssue(issueId: UUID) {
        withConnection { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE id = ?::uuid").use { stmt ->
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
        withConnection { conn ->
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
}

/**
 * 가시성 필터를 항상 통과시키는 [IssueSecurityDirectory] — 이 클래스는 보안 축을 재지 않는다.
 *
 * 보안 등급 축은 형제 [SprintBurndownLookupAdapterIntegrationTest] 가 이미 진다.
 */
private object UnrestrictedSecurityDirectory : IssueSecurityDirectory {
    override fun levelBelongsToProjectScheme(
        levelId: UUID,
        projectKey: String,
    ): Boolean = true

    override fun accessibleLevels(
        actorId: UUID,
        projectKey: String,
    ): IssueSecurityAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )
}
