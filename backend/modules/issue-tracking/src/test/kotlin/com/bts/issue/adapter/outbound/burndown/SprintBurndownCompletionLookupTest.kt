// 번다운 포트가 「이슈 몇 개이고 언제 완료됐는가」를 나르는지 재는 Testcontainers 테스트 (부채 177 task-35)

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
import com.bts.shared.burndown.BurndownSource
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateView
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

private const val TEST_PROJECT_KEY = "TPRJ"

/** V003 seed 의 표준 이슈 타입 키 — 이 파일의 이슈는 전부 이 타입이다. */
private const val TASK_TYPE_KEY = "task"

private const val STATE_OPEN = "open"
private const val STATE_IN_PROGRESS = "in_progress"
private const val STATE_DONE = "done"

/** 첫 완료 시각. 되돌렸다 다시 완료하면 이 시각은 **정본이 아니다**. */
private val FIRST_DONE_AT: Instant = Instant.parse("2026-07-02T09:00:00Z")

/** 되돌린 시각. */
private val REOPENED_AT: Instant = Instant.parse("2026-07-03T09:00:00Z")

/** 마지막 완료 시각 — 개수 축이 칸에 놓아야 하는 시각이다. */
private val LAST_DONE_AT: Instant = Instant.parse("2026-07-04T09:00:00Z")

/**
 * [SprintBurndownLookupAdapter] 가 **개수 축의 입력**을 나르는지 재는 Testcontainers 테스트 (부채 177 task-35).
 *
 * ## 왜 필요한가
 *
 * 개수 기반 번다운은 「스프린트에 이슈가 몇 개이고 언제 완료됐는가」가 있어야 그려진다.
 * 기존 포트는 추정 시간과 worklog 만 날랐다 — 그 둘로는 개수 축을 원리적으로 만들 수 없다.
 * 이 파일은 넓힌 계약([BurndownSource.visibleIssueCount] · [BurndownSource.issueCompletions])이
 * 실 DB 위에서 실제로 채워지는지 잰다.
 *
 * ## 완료의 정의 (task-35)
 *
 * **지금 DONE 카테고리인 이슈**를 완료로 세고, 그 시각은 **마지막 DONE 전환**의 시각이다.
 *
 * - 「지금」을 보는 이유. 완료 후 되돌린 이슈를 완료로 세면 차트의 마지막 점이 보드의 실제 잔여와
 *   어긋난다. 사람들이 번다운에서 읽는 값이 바로 그 마지막 점이다.
 * - 「마지막 전환」인 이유. 되돌렸다 다시 완료한 이슈의 완료 시각은 첫 완료가 아니다.
 * - 전환 이력이 없는 DONE 이슈는 **완료 시각을 알 수 없어** 완료 목록에 넣지 않는다(개수에는 남는다).
 *   `CycleTimeService` 가 같은 규율(EC8 — 전환 기반 신뢰)을 이미 쓴다. 그 이슈는 잔여가 줄지 않는
 *   것으로 그려지며, 이것이 이 계약의 문서화된 한계다.
 *
 * ## 공허 방지
 * 세 축을 **한 픽스처**에 함께 세운다 — 「완료를 전부 센다」와 「현재 상태만 본다」와
 * 「첫 전환을 쓴다」가 서로 다른 이슈에서 갈리므로, 느슨한 구현은 어느 하나에서 반드시 걸린다.
 *
 * ## 설정 공유
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다
 * (형제 [SprintBurndownLookupAdapterTest] 와 같은 관용구).
 * 워크플로우 카탈로그는 [IsolatedWorkflowStateLookup] mock 으로 세운다 — 실 카탈로그 조립의 판정은
 * `SprintVelocityLookupAdapterTest` 가 이미 진다(같은 헬퍼 Bean 을 공유한다).
 */
class SprintBurndownCompletionLookupTest : IssueTestcontainersBase() {
    private var taskTypeId: IssueTypeId? = null

    private lateinit var adapter: SprintBurndownLookupAdapter

    /** 이 클래스는 가시성 축을 재지 않는다 — 항상 unrestricted. */
    private val viewerId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setupAdapter() {
        val stateLookup =
            mockk<IsolatedWorkflowStateLookup>().also {
                // ★`any()` 를 쓰지 않는다 — ProjectKey/IssueTypeKey 는 검증하는 value class 라
                //   MockK 의 임의 서명값이 생성자 require 에 걸린다(CycleTimeServiceTest 와 같은 관용구).
                every { it.listStates(ProjectKey.of(TEST_PROJECT_KEY), IssueTypeKey(TASK_TYPE_KEY)) } returns
                    listOf(
                        WorkflowStateView(key = STATE_OPEN, name = "할 일", category = "TODO"),
                        WorkflowStateView(key = STATE_IN_PROGRESS, name = "진행 중", category = "IN_PROGRESS"),
                        WorkflowStateView(key = STATE_DONE, name = "완료", isDone = true, category = "DONE"),
                    )
            }
        adapter =
            SprintBurndownLookupAdapter(
                SprintBurndownQueryRepository(dsl),
                AllVisibleSecurityDirectory,
                repository,
                IssueTypeRepository(dsl),
                StatusHistoryRepository(dsl),
                stateLookup,
            )
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    @BeforeEach
    fun cleanIssuesAndHistory() {
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
                stmt.execute("DELETE FROM worklogs")
                stmt.execute("DELETE FROM issues")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$TEST_PROJECT_KEY'")
            }
        }
    }

    @Test
    fun `지금 DONE 인 이슈만 완료로 세고 그 시각은 마지막 DONE 전환이다`() {
        // ① 되돌렸다 다시 완료 — 완료다. 시각은 마지막 전환(LAST_DONE_AT)이다.
        val redone = insertIssue(seqNum = 1L, currentStateKey = STATE_DONE)
        recordStatusChange(redone, STATE_IN_PROGRESS, STATE_DONE, FIRST_DONE_AT)
        recordStatusChange(redone, STATE_DONE, STATE_OPEN, REOPENED_AT)
        recordStatusChange(redone, STATE_OPEN, STATE_DONE, LAST_DONE_AT)
        // ② 진행 중 — 완료가 아니다.
        val inProgress = insertIssue(seqNum = 2L, currentStateKey = STATE_IN_PROGRESS)
        recordStatusChange(inProgress, STATE_OPEN, STATE_IN_PROGRESS, FIRST_DONE_AT)
        // ③ DONE 인데 전환 이력이 없다 — 개수에는 있고 완료 목록에는 없다(EC8).
        insertIssue(seqNum = 3L, currentStateKey = STATE_DONE)

        val source = fetch("$TEST_PROJECT_KEY-1", "$TEST_PROJECT_KEY-2", "$TEST_PROJECT_KEY-3")

        assertSoftly { softly ->
            softly.assertThat(source.visibleIssueCount)
                .describedAs("개수 축의 스코프는 가시 이슈 전부다 — 완료 여부와 무관하다")
                .isEqualTo(3L)
            softly.assertThat(source.issueCompletions.map { it.issueKey })
                .describedAs("진행 중 이슈와 이력 없는 이슈는 완료 목록에 없다")
                .containsExactly("$TEST_PROJECT_KEY-1")
            softly.assertThat(source.issueCompletions.single().completedAt)
                .describedAs("첫 완료가 아니라 마지막 완료 시각이다")
                .isEqualTo(LAST_DONE_AT)
        }
    }

    @Test
    fun `완료했다가 되돌린 이슈는 완료로 세지 않는다`() {
        val reopened = insertIssue(seqNum = 1L, currentStateKey = STATE_OPEN)
        recordStatusChange(reopened, STATE_IN_PROGRESS, STATE_DONE, FIRST_DONE_AT)
        recordStatusChange(reopened, STATE_DONE, STATE_OPEN, REOPENED_AT)

        val source = fetch("$TEST_PROJECT_KEY-1")

        assertSoftly { softly ->
            softly.assertThat(source.visibleIssueCount).isEqualTo(1L)
            softly.assertThat(source.issueCompletions)
                .describedAs("전환 이력만 보면 완료로 세어진다 — 지금 상태를 함께 봐야 갈린다")
                .isEmpty()
        }
    }

    @Test
    fun `soft-deleted 이슈는 개수에도 완료에도 들어가지 않는다`() {
        val alive = insertIssue(seqNum = 1L, currentStateKey = STATE_DONE)
        recordStatusChange(alive, STATE_OPEN, STATE_DONE, LAST_DONE_AT)
        val deleted = insertIssue(seqNum = 2L, currentStateKey = STATE_DONE)
        recordStatusChange(deleted, STATE_OPEN, STATE_DONE, LAST_DONE_AT)
        softDeleteIssue(deleted.id.value)

        val source = fetch("$TEST_PROJECT_KEY-1", "$TEST_PROJECT_KEY-2")

        assertSoftly { softly ->
            softly.assertThat(source.visibleIssueCount).isEqualTo(1L)
            softly.assertThat(source.issueCompletions.map { it.issueKey })
                .containsExactly("$TEST_PROJECT_KEY-1")
        }
    }

    @Test
    fun `이슈 키가 비면 개수 0 · 완료 없음으로 조기 반환한다`() {
        val source = adapter.fetchBurndownSource(emptySet(), TEST_PROJECT_KEY, viewerId)

        assertSoftly { softly ->
            softly.assertThat(source.visibleIssueCount).isZero()
            softly.assertThat(source.issueCompletions).isEmpty()
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun fetch(vararg issueKeys: String): BurndownSource =
        adapter.fetchBurndownSource(
            issueKeys = issueKeys.toSet(),
            projectKey = TEST_PROJECT_KEY,
            viewerUserId = viewerId,
        )

    private fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
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

    private fun insertIssue(
        seqNum: Long,
        currentStateKey: String,
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of(TEST_PROJECT_KEY, seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "개수 축 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = currentStateKey,
                securityLevelId = null,
            ),
        )

    private fun softDeleteIssue(issueId: UUID) {
        withConnection { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE id = ?::uuid").use { stmt ->
                stmt.setString(1, issueId.toString())
                stmt.executeUpdate()
            }
        }
    }

    /** status 전환 이력 1건(그룹 + 항목)을 기록한다 — 실제 전환 API 가 남기는 것과 같은 모양이다. */
    private fun recordStatusChange(
        issue: Issue,
        fromValue: String,
        toValue: String,
        changedAt: Instant,
    ) {
        withConnection { conn ->
            val groupId =
                conn.prepareStatement(
                    """
                    INSERT INTO issue_change_group (issue_id, issue_key, actor_id, created_at)
                    VALUES (?::uuid, ?, NULL, ?::timestamptz)
                    RETURNING id
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, issue.id.value.toString())
                    stmt.setString(2, issue.key.value)
                    stmt.setString(3, changedAt.toString())
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "issue_change_group 삽입 실패" }
                        rs.getLong(1)
                    }
                }
            conn.prepareStatement(
                "INSERT INTO issue_change_item (group_id, field, from_value, to_value) VALUES (?, 'status', ?, ?)",
            ).use { stmt ->
                stmt.setLong(1, groupId)
                stmt.setString(2, fromValue)
                stmt.setString(3, toValue)
                stmt.executeUpdate()
            }
        }
    }
}

/** 가시성 필터를 항상 통과시키는 [IssueSecurityDirectory] — 이 클래스는 보안 축을 재지 않는다. */
private object AllVisibleSecurityDirectory : IssueSecurityDirectory {
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
