// ProjectQueryRepository 통합 테스트 — key 집합 조회(name 오름차순·소프트삭제 제외)·id 단건 조회 (FR-PJ PR-3 Task 1)

package com.bts.issue.project.query

import com.bts.issue.repository.IssueTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * [ProjectQueryRepository] 통합 테스트.
 *
 * `IssueTestcontainersBase` 상속으로 Testcontainers PostgreSQL(tembo pg16) + Flyway 마이그레이션을
 * JVM singleton 라이프사이클로 기동한다 ([com.bts.issue.project.ProjectLookupTest] 동형 패턴).
 *
 * 테스트마다 `projects` 에 활성 "AAA"/"BBB" + 소프트 삭제 "CCC" 를 idempotent 하게 시딩한다
 * (`ON CONFLICT (key) DO UPDATE` — 다른 테스트 클래스와 JVM singleton 컨테이너를 공유해도 안전).
 * 단일 테이블 조회라 다중 LEFT JOIN 카티전 곱 문제와는 무관하다.
 */
class ProjectQueryRepositoryTest : IssueTestcontainersBase() {
    private lateinit var projectQueryRepository: ProjectQueryRepository

    @BeforeEach
    fun setUp() {
        projectQueryRepository = ProjectQueryRepository(dsl)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO projects (key, name)
                VALUES ('AAA', 'AAA'), ('BBB', 'BBB'), ('CCC', 'CCC')
                ON CONFLICT (key) DO UPDATE SET deleted_at = NULL
                """.trimIndent(),
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                "UPDATE projects SET deleted_at = NOW() WHERE key = 'CCC'",
            ).use { it.executeUpdate() }
        }
    }

    /** 시딩된 프로젝트의 id 를 raw JDBC 로 직접 조회한다(소프트 삭제된 행도 조회 가능해야 함). */
    private fun projectIdByKey(key: String): UUID =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }
        }

    // ── findAccessibleByKeys ─────────────────────────────────────────────────

    @Test
    fun `findAccessibleByKeys 는 활성 프로젝트만 name 오름차순으로 반환한다`() {
        val result = projectQueryRepository.findAccessibleByKeys(setOf("AAA", "BBB", "CCC"))

        assertThat(result.map { it.key }).containsExactly("AAA", "BBB")
    }

    @Test
    fun `findAccessibleByKeys 는 빈 keys 입력 시 빈 리스트를 반환한다`() {
        val result = projectQueryRepository.findAccessibleByKeys(emptySet())

        assertThat(result).isEmpty()
    }

    // ── findByIdOrKey ────────────────────────────────────────────────────────

    @Test
    fun `findByIdOrKey 는 활성 프로젝트 단건을 반환한다`() {
        val activeId = projectIdByKey("AAA")

        val result = projectQueryRepository.findByIdOrKey(activeId)

        assertThat(result?.key).isEqualTo("AAA")
        assertThat(result?.name).isEqualTo("AAA")
    }

    @Test
    fun `findByIdOrKey 는 소프트 삭제된 프로젝트에 대해 null 을 반환한다`() {
        val deletedId = projectIdByKey("CCC")

        val result = projectQueryRepository.findByIdOrKey(deletedId)

        assertThat(result).isNull()
    }

    @Test
    fun `findByIdOrKey 는 존재하지 않는 id 에 대해 null 을 반환한다`() {
        val result = projectQueryRepository.findByIdOrKey(UUID.randomUUID())

        assertThat(result).isNull()
    }
}
