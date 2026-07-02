// WorklogService.createImported 통합 테스트 — Import 전용 워크로그 생성(author 주입·이력 생략) 검증 (FR-IM-01 PR3 Task 5)

package com.bts.issue.worklog.application

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.history.IssueChangeHistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * WorklogService.createImported Testcontainers 통합 테스트 (FR-IM-01 PR3 Task 5).
 *
 * [TestConfig] singleton Testcontainers PostgreSQL + Flyway + 기본 빈과
 * [WorklogServiceIntegrationTest.WorklogServiceTestConfig] (실 [com.bts.issue.history.IssueHistoryRecorder]
 * + `denyablePermissionResolver`)를 그대로 재사용한다 — 새 컨텍스트/새 mock 빈을 만들지 않는다
 * (기존 `create` 통합 테스트 base 재사용 원칙).
 *
 * ## 검증 시나리오
 * - (1) authorId 주입 — 저장된 worklog.authorId == 주입값, actor 와 다름.
 * - (2) UPDATE 권한 게이트 — 권한 없는 actor 는 403.
 * - (3) issues.version 불변(no-bump) + remaining auto-decrement 정상 동작.
 * - (4) 이력 미기록 — `issue_change_group` 무변화 (실 [IssueHistoryRecorder] 사용 중에도 기록 없음).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [
        TestConfig::class,
        WorklogServiceIntegrationTest.WorklogServiceTestConfig::class,
    ],
)
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorklogServiceImportTest {
    @Autowired
    lateinit var worklogService: WorklogService

    @Autowired
    lateinit var issueChangeHistoryRepository: IssueChangeHistoryRepository

    companion object {
        private const val PROJECT_KEY = "WLIMP"

        val ACTOR_UUID: UUID = UUID.fromString("66666666-6666-4666-8666-666666666666")
        val IMPORT_AUTHOR_UUID: UUID = UUID.fromString("77777777-7777-4777-8777-777777777777")

        val ACTOR: ActorId = ActorId(ACTOR_UUID)
        val IMPORT_AUTHOR: ActorId = ActorId(IMPORT_AUTHOR_UUID)

        // denyablePermissionResolver(WorklogServiceTestConfig)가 판정 대상으로 삼는 상수를 그대로 재사용.
        val DENY_ACTOR: ActorId = ActorId(WorklogServiceIntegrationTest.DENY_ACTOR_UUID)

        private const val H8 = 8 * 3600
        private const val H2 = 2 * 3600
        private const val H6 = 6 * 3600

        private var migrated = false
        private var seeded = false
    }

    // ── 초기화 ────────────────────────────────────────────────────────────────────

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProject()
            seeded = true
        }
    }

    @BeforeEach
    fun cleanBetweenTests() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                // 이력 삭제 (issue_change_item → issue_change_group 순서 — FK)
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
                // worklogs → issues 순서
                stmt.execute("DELETE FROM worklogs")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    // ── (1) authorId 주입 ─────────────────────────────────────────────────────────

    /**
     * (1) authorId 주입 — 저장된 worklog.authorId 는 actor 가 아닌 주입값이다.
     */
    @Test
    fun `(1) createImported — 저장된 worklog authorId는 주입값(actor와 다름)`() {
        val issueKey = insertIssue(remainingSeconds = H8)

        val worklog =
            worklogService.createImported(
                actor = ACTOR,
                issueKey = IssueKey(issueKey),
                authorId = IMPORT_AUTHOR,
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = "import 워크로그",
            )

        assertThat(worklog.authorId).isEqualTo(IMPORT_AUTHOR_UUID)
        assertThat(worklog.authorId).isNotEqualTo(ACTOR_UUID)
    }

    // ── (2) UPDATE 권한 게이트 ─────────────────────────────────────────────────────

    /**
     * (2) UPDATE 권한 게이트 — 권한 없는 actor 는 403.
     */
    @Test
    fun `(2) createImported — UPDATE 권한 없는 actor는 403`() {
        val issueKey = insertIssue(remainingSeconds = null)

        assertThatThrownBy {
            worklogService.createImported(
                actor = DENY_ACTOR,
                issueKey = IssueKey(issueKey),
                authorId = IMPORT_AUTHOR,
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = null,
            )
        }.isInstanceOf(IssueAccessDeniedException::class.java)
    }

    // ── (3) version 불변(no-bump) ─────────────────────────────────────────────────

    /**
     * (3) issues.version 불변(no-bump) — worklog 는 사이드카라 version 을 증가시키지 않는다.
     * remaining auto-decrement 도 `create` 와 동형으로 동작함을 함께 확인한다(로직 재사용 근거).
     */
    @Test
    fun `(3) createImported — issues version 불변(no-bump), remaining auto-decrement`() {
        val issueKey = insertIssue(remainingSeconds = H8)
        val versionBefore = fetchVersion(issueKey)

        worklogService.createImported(
            actor = ACTOR,
            issueKey = IssueKey(issueKey),
            authorId = IMPORT_AUTHOR,
            timeSpentSeconds = H2,
            startedAt = Instant.now(),
            comment = null,
        )

        val versionAfter = fetchVersion(issueKey)
        assertThat(versionAfter).isEqualTo(versionBefore)

        val list = worklogService.listForIssue(ACTOR, IssueKey(issueKey))
        assertThat(list.timeSpentSeconds).isEqualTo(H2)
        assertThat(list.remainingEstimateSeconds).isEqualTo(H6)
    }

    // ── (4) 이력 미기록 ───────────────────────────────────────────────────────────

    /**
     * (4) 이력 미기록 — `IssueHistoryRecorder` 는 실 빈이지만 `createImported` 는 호출하지 않으므로
     * `issue_change_group` 에 아무 행도 남지 않는다. [WorklogServiceIntegrationTest] (9)changelog 와 대조되는
     * 회귀 가드 — create 는 이력을 남기고 createImported 는 남기지 않아야 한다.
     */
    @Test
    fun `(4) createImported — 이력 미기록(issue_change_group 무변화)`() {
        val issueKey = insertIssue(remainingSeconds = H8)
        val issueId = fetchIssueId(issueKey)

        worklogService.createImported(
            actor = ACTOR,
            issueKey = IssueKey(issueKey),
            authorId = IMPORT_AUTHOR,
            timeSpentSeconds = H2,
            startedAt = Instant.now(),
            comment = null,
        )

        val groups = issueChangeHistoryRepository.findByIssue(issueId)
        assertThat(groups).isEmpty()
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(
                TestConfig.postgres.jdbcUrl,
                TestConfig.postgres.username,
                TestConfig.postgres.password,
            )
            .placeholderReplacement(false)
            .locations(
                "classpath:db/migration/issue-tracking",
                "classpath:db/migration/project-workflow",
            )
            .load()
            .migrate()
    }

    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Worklog Import Test Project")
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 이슈를 직접 삽입하고 이슈 키를 반환한다.
     *
     * @param remainingSeconds remaining_estimate_seconds. null 이면 미추정.
     */
    @Suppress("NestedBlockDepth", "LongMethod")
    private fun insertIssue(remainingSeconds: Int?): String =
        conn().use { c ->
            c.autoCommit = false

            val seq =
                c.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            val issueKey = "$PROJECT_KEY-$seq"
            val projectId =
                c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            val taskTypeId =
                c.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            if (remainingSeconds != null) {
                c.prepareStatement(
                    "INSERT INTO issues " +
                        "(key, project_id, summary, reporter_id, current_state_key, version, type_id, " +
                        "remaining_estimate_seconds) VALUES (?, ?, ?, ?, 'open', 1, ?, ?)",
                ).use { stmt ->
                    stmt.setString(1, issueKey)
                    stmt.setObject(2, projectId)
                    stmt.setString(3, "워크로그 import 테스트 이슈")
                    stmt.setObject(4, ACTOR_UUID)
                    stmt.setLong(5, taskTypeId)
                    stmt.setInt(6, remainingSeconds)
                    stmt.executeUpdate()
                }
            } else {
                c.prepareStatement(
                    "INSERT INTO issues " +
                        "(key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                        "VALUES (?, ?, ?, ?, 'open', 1, ?)",
                ).use { stmt ->
                    stmt.setString(1, issueKey)
                    stmt.setObject(2, projectId)
                    stmt.setString(3, "워크로그 import 테스트 이슈")
                    stmt.setObject(4, ACTOR_UUID)
                    stmt.setLong(5, taskTypeId)
                    stmt.executeUpdate()
                }
            }

            c.commit()
            issueKey
        }

    /** 이슈 키로 issues.id 를 조회한다 (이력 검증용). */
    private fun fetchIssueId(issueKey: String): UUID =
        conn().use { c ->
            c.prepareStatement("SELECT id FROM issues WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "이슈 미존재: $issueKey" }
                    rs.getObject(1) as UUID
                }
            }
        }

    /** 이슈 키로 issues.version 을 조회한다 (no-bump 검증용). */
    private fun fetchVersion(issueKey: String): Long =
        conn().use { c ->
            c.prepareStatement("SELECT version FROM issues WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "이슈 미존재: $issueKey" }
                    rs.getLong(1)
                }
            }
        }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
