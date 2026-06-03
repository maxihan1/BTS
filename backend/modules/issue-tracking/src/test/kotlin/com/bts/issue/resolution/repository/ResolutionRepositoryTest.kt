// ResolutionRepository Testcontainers 통합 테스트 — findAllActive + findById (FR-IS-07 Task B3 RED)

package com.bts.issue.resolution.repository

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * ResolutionRepository 통합 테스트.
 *
 * 검증 범위.
 * - findAllActive: 표준 5종을 display_order ASC 로 반환 + deletedAt 있는 건 제외
 * - findById: 존재하는 resolution 반환 + soft-deleted 는 null + 없으면 null
 *
 * Spring 컨텍스트 없이 Testcontainers + Flyway + jOOQ DSLContext 직접 구성.
 * JVM singleton container — IssueTypeRepositoryCrudTest 선례 패턴.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResolutionRepositoryTest {
    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }
    }

    lateinit var dsl: DSLContext
    lateinit var repository: ResolutionRepository

    private var bootstrapped = false

    @BeforeAll
    fun bootstrap() {
        if (bootstrapped) return

        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .placeholderReplacement(false)
            .locations("classpath:db/migration/issue-tracking")
            .load()
            .migrate()

        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        repository = ResolutionRepository(dsl)

        bootstrapped = true
    }

    // ── findAllActive ─────────────────────────────────────────────────────────

    @Test
    fun `findAllActive - 표준 5종을 display_order ASC 로 반환`() {
        val results = repository.findAllActive()

        assertThat(results).hasSize(5)
        val orders = results.map { it.displayOrder }
        assertThat(orders).isSorted
        val keys = results.map { it.key }
        assertThat(keys).containsExactly("fixed", "wontfix", "duplicate", "cannotreproduce", "done")
    }

    @Test
    fun `findAllActive - deletedAt 있는 row 는 제외`() {
        val allBefore = repository.findAllActive()
        val targetId = allBefore.first().id ?: return

        softDeleteResolution(targetId)
        try {
            val results = repository.findAllActive()
            assertThat(results).hasSize(4)
            assertThat(results.map { it.id }).doesNotContain(targetId)
        } finally {
            restoreResolution(targetId)
        }
    }

    // ── findById ──────────────────────────────────────────────────────────────

    @Test
    fun `findById - 존재하는 resolution 반환`() {
        val all = repository.findAllActive()
        val target = all.first()
        val id = target.id ?: error("seed resolution must have id")

        val found = repository.findById(id)

        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(id)
        assertThat(found.key).isEqualTo(target.key)
        assertThat(found.name).isEqualTo(target.name)
        assertThat(found.displayOrder).isEqualTo(target.displayOrder)
        assertThat(found.isStandard).isTrue()
        assertThat(found.deletedAt).isNull()
    }

    @Test
    fun `findById - 존재하지 않는 UUID 는 null 반환`() {
        val nonExistentId = UUID.fromString("00000000-0000-4000-8000-000000000000")

        val found = repository.findById(nonExistentId)

        assertThat(found).isNull()
    }

    @Test
    fun `findById - soft-deleted resolution 은 null 반환`() {
        val all = repository.findAllActive()
        val target = all.last()
        val id = target.id ?: error("seed resolution must have id")

        softDeleteResolution(id)
        try {
            val found = repository.findById(id)
            assertThat(found).isNull()
        } finally {
            restoreResolution(id)
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun softDeleteResolution(id: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE resolutions SET deleted_at = NOW() WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.execute()
            }
        }
    }

    private fun restoreResolution(id: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE resolutions SET deleted_at = NULL WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.execute()
            }
        }
    }
}
