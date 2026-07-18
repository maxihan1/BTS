// ProjectArchiveGuard 통합 테스트 — archived_at 단독 판정 + deleted_at 오독 판별자 (FR-PJ-04 PR-4 Task 3)

package com.bts.issue.project.archive

import com.bts.issue.domain.IssueKey
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.repository.IssueTestcontainersBase
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * [ProjectArchiveGuard] 통합 테스트.
 *
 * `IssueTestcontainersBase` 상속으로 Testcontainers PostgreSQL + Flyway(V037 archived_at 포함)를
 * JVM singleton 라이프사이클로 기동한다.
 *
 * ## 시딩 (라이프사이클 3분면 — archived_at ⊥ deleted_at)
 * - `GARCH`  — 아카이브됨 (archived_at NOT NULL, deleted_at NULL) → 잠금 대상
 * - `GACTIVE`— 활성 (둘 다 NULL) → 통과
 * - `GDEL`   — 소프트 삭제됨 (deleted_at NOT NULL, **archived_at NULL**) → ★판별자: 아카이브 아님 → 통과
 *
 * ## ★ C2 판별자 (deleted_at 오독 = 이슈 소실 금지)
 * `GDEL 은 아카이브가 아니다` 테스트는 guard 가 `deleted_at` 을 아카이브로 오독하지 않음을 입증한다.
 * `ProjectArchiveStateRepository` 가 `archived_at IS NOT NULL OR deleted_at IS NOT NULL` 같은 잘못된
 * 술어를 쓰면 이 테스트가 fail 한다(RED 에서 실제 주입해 확인).
 */
class ProjectArchiveGuardTest : IssueTestcontainersBase() {
    private lateinit var guard: ProjectArchiveGuard

    private val reporterId: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

    @BeforeEach
    fun setUpGuard() {
        guard = ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            // 프로젝트 3분면 시딩 (idempotent — JVM singleton 컨테이너 공유 안전)
            conn.prepareStatement(
                """
                INSERT INTO projects (key, name)
                VALUES ('GARCH', 'Archived'), ('GACTIVE', 'Active'), ('GDEL', 'Deleted')
                ON CONFLICT (key) DO UPDATE SET deleted_at = NULL, archived_at = NULL
                """.trimIndent(),
            ).use { it.executeUpdate() }
            conn.prepareStatement("UPDATE projects SET archived_at = NOW() WHERE key = 'GARCH'")
                .use { it.executeUpdate() }
            conn.prepareStatement("UPDATE projects SET deleted_at = NOW() WHERE key = 'GDEL'")
                .use { it.executeUpdate() }

            // 이슈 시딩 (checkByIssue 용) — base cleanIssues 가 DELETE FROM issues 후 이 setUp 이 재삽입.
            insertIssue(conn, "GARCH-1", projectIdByKey(conn, "GARCH"))
            insertIssue(conn, "GACTIVE-1", projectIdByKey(conn, "GACTIVE"))
        }
    }

    // ── check(projectId) / check(projectKey) — 아카이브 프로젝트 잠금 ──────────────

    @Test
    fun `check(projectId) 는 아카이브된 프로젝트에 대해 ProjectArchivedException 을 던진다`() {
        val archivedId = projectId("GARCH")

        assertThrows(ProjectArchivedException::class.java) { guard.check(archivedId) }
    }

    @Test
    fun `check(projectKey) 는 아카이브된 프로젝트에 대해 ProjectArchivedException 을 던진다`() {
        assertThrows(ProjectArchivedException::class.java) { guard.check("GARCH") }
    }

    // ── check — 활성 프로젝트 통과 ────────────────────────────────────────────────

    @Test
    fun `check(projectId) 는 활성 프로젝트에 대해 통과한다`() {
        val activeId = projectId("GACTIVE")

        assertDoesNotThrow { guard.check(activeId) }
    }

    @Test
    fun `check(projectKey) 는 활성 프로젝트에 대해 통과한다`() {
        assertDoesNotThrow { guard.check("GACTIVE") }
    }

    // ── ★ C2 판별자 — 소프트 삭제된 프로젝트를 아카이브로 오판하지 않음 ─────────────

    @Test
    fun `check(projectId) 는 소프트 삭제된(archived_at NULL) 프로젝트를 아카이브로 오판하지 않는다`() {
        val deletedId = projectId("GDEL")

        assertDoesNotThrow { guard.check(deletedId) }
    }

    @Test
    fun `check(projectKey) 는 소프트 삭제된(archived_at NULL) 프로젝트를 아카이브로 오판하지 않는다`() {
        assertDoesNotThrow { guard.check("GDEL") }
    }

    // ── check — 미존재 프로젝트는 통과 (fail-closed 아님, 404 는 상위 책임) ──────────

    @Test
    fun `check(projectId) 는 존재하지 않는 프로젝트에 대해 통과한다 (fail-closed 아님)`() {
        assertDoesNotThrow { guard.check(UUID.randomUUID()) }
    }

    // ── checkByIssue — issueKey → 소속 프로젝트로 해석 ────────────────────────────

    @Test
    fun `checkByIssue 는 아카이브된 프로젝트 소속 이슈에 대해 ProjectArchivedException 을 던진다`() {
        assertThrows(ProjectArchivedException::class.java) { guard.checkByIssue(IssueKey("GARCH-1")) }
    }

    @Test
    fun `checkByIssue 는 활성 프로젝트 소속 이슈에 대해 통과한다`() {
        assertDoesNotThrow { guard.checkByIssue(IssueKey("GACTIVE-1")) }
    }

    @Test
    fun `checkByIssue 는 존재하지 않는 이슈에 대해 통과한다 (fail-closed 아님)`() {
        assertDoesNotThrow { guard.checkByIssue(IssueKey("GACTIVE-999")) }
    }

    // ── raw JDBC 헬퍼 ─────────────────────────────────────────────────────────────

    /** 시딩된 프로젝트의 id 를 raw JDBC 로 조회한다(소프트 삭제된 행도 조회 가능해야 함). */
    private fun projectId(key: String): UUID =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            projectIdByKey(conn, key)
        }

    private fun projectIdByKey(
        conn: java.sql.Connection,
        key: String,
    ): UUID =
        conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
            stmt.setString(1, key)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
            }
        }

    private fun insertIssue(
        conn: java.sql.Connection,
        issueKey: String,
        projectId: UUID,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, type_id)
            VALUES (?, ?, 'seed', ?, 'OPEN',
                (SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1))
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, issueKey)
            stmt.setObject(2, projectId)
            stmt.setObject(3, reporterId)
            stmt.executeUpdate()
        }
    }
}
