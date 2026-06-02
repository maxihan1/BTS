// ProjectLookup Testcontainers 통합테스트 — projectKey→id 해석, UUID 직접 수용, 소프트 삭제/미존재 null 반환

package com.bts.issue.component.application

import com.bts.issue.repository.IssueTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * ProjectLookup 통합 테스트.
 *
 * IssueTestcontainersBase 상속으로 Testcontainers PostgreSQL + Flyway 마이그레이션을
 * JVM singleton 라이프사이클로 기동한다.
 *
 * 테스트 시나리오.
 * - T1. projectKey → 활성 프로젝트 id 해석 성공.
 * - T2. UUID 문자열 직접 입력 → 활성 프로젝트 존재 시 해당 UUID 반환.
 * - T3. 존재하지 않는 projectKey → null 반환 (404 신호).
 * - T4. 소프트 삭제된 프로젝트의 key → null 반환.
 * - T5. 소프트 삭제된 프로젝트의 UUID → null 반환.
 * - T6. 올바른 UUID 형식이지만 DB 에 없는 UUID → null 반환.
 * - T7. UUID 도 projectKey 형식도 아닌 잘못된 입력 → null 반환.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ProjectLookupTest : IssueTestcontainersBase() {
    private lateinit var projectLookup: ProjectLookup

    /** 소프트 삭제 검증용 별도 프로젝트 id. */
    private lateinit var deletedProjectId: UUID

    @BeforeEach
    fun setUp() {
        projectLookup = ProjectLookup(dsl)

        // 소프트 삭제 프로젝트를 매 테스트 전에 재생성 + 삭제 (idempotent)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO projects (key, name)
                VALUES ('DEAD', 'Deleted Project')
                ON CONFLICT (key) DO UPDATE SET deleted_at = NULL
                RETURNING id
                """.trimIndent(),
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    deletedProjectId = rs.getObject(1) as UUID
                }
            }
            conn.prepareStatement(
                "UPDATE projects SET deleted_at = NOW() WHERE key = 'DEAD'",
            ).use { it.executeUpdate() }
        }
    }

    // ── T1. projectKey → id ───────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `활성 projectKey 로 프로젝트 id 를 해석한다`() {
        val resolved = projectLookup.resolve("TPRJ")
        assertThat(resolved).isEqualTo(testProjectId)
    }

    // ── T2. UUID 직접 입력 → 해당 UUID 반환 ─────────────────────────────────

    @Test
    @Order(2)
    fun `활성 프로젝트 UUID 문자열 직접 입력 시 해당 UUID 를 반환한다`() {
        val resolved = projectLookup.resolve(testProjectId.toString())
        assertThat(resolved).isEqualTo(testProjectId)
    }

    // ── T3. 미존재 key → null ────────────────────────────────────────────────

    @Test
    @Order(3)
    fun `존재하지 않는 projectKey 입력 시 null 을 반환한다`() {
        val resolved = projectLookup.resolve("NONE")
        assertThat(resolved).isNull()
    }

    // ── T4. 소프트 삭제 key → null ───────────────────────────────────────────

    @Test
    @Order(4)
    fun `소프트 삭제된 프로젝트 key 입력 시 null 을 반환한다`() {
        val resolved = projectLookup.resolve("DEAD")
        assertThat(resolved).isNull()
    }

    // ── T5. 소프트 삭제 UUID → null ──────────────────────────────────────────

    @Test
    @Order(5)
    fun `소프트 삭제된 프로젝트 UUID 입력 시 null 을 반환한다`() {
        val resolved = projectLookup.resolve(deletedProjectId.toString())
        assertThat(resolved).isNull()
    }

    // ── T6. 유효 UUID 형식이나 DB 에 없는 UUID → null ────────────────────────

    @Test
    @Order(6)
    fun `DB 에 없는 UUID 형식 입력 시 null 을 반환한다`() {
        val resolved = projectLookup.resolve(UUID.randomUUID().toString())
        assertThat(resolved).isNull()
    }

    // ── T7. 잘못된 형식 → null ───────────────────────────────────────────────

    @Test
    @Order(7)
    fun `UUID 도 projectKey 형식도 아닌 입력 시 null 을 반환한다`() {
        val resolved = projectLookup.resolve("not-valid-!!!")
        assertThat(resolved).isNull()
    }
}
