// PostActionRepository 통합 테스트 — Testcontainers + Flyway V200 + workflow/transition 시드

package com.bts.workflow.postaction

import com.bts.workflow.testsupport.insertWorkflowStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * PostActionRepository Testcontainers 통합 테스트.
 *
 * V200 마이그레이션(workflow_post_actions 포함) + 테스트용 workflow/transition 시드 후
 * repository CRUD 를 실 DB 로 검증한다.
 *
 * 검증 범위.
 * - findByTransitionId: 빈 목록 반환 (post-action 없는 전환)
 * - insert → findByTransitionId: 삽입 후 조회
 * - update: type/config/displayOrder 갱신 반영
 * - deleteById: 행 삭제 확인
 * - displayOrder ASC 정렬: 여러 건 삽입 시 순서 보장
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PostActionRepositoryIntegrationTest {
    companion object {
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        lateinit var repository: PostActionRepository
        lateinit var transitionId: UUID
        lateinit var emptyTransitionId: UUID

        @BeforeAll
        @JvmStatic
        @Suppress("LongMethod", "CyclomaticComplexMethod")
        fun setup() {
            // 1단계: V200 까지 적용 (cross-BC issue-tracking 포함)
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .target("200")
                .load()
                .migrate()

            // issue_types 스텁 — V201+ FK 통과용
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS issue_types (
                            id          BIGSERIAL    PRIMARY KEY,
                            key         VARCHAR(30)  NOT NULL UNIQUE,
                            name        VARCHAR(255) NOT NULL,
                            is_standard BOOLEAN      NOT NULL DEFAULT FALSE,
                            created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                            updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                            deleted_at  TIMESTAMPTZ
                        )
                        """.trimIndent(),
                    )
                }
            }

            // 2단계: 나머지 마이그레이션 전체 적용
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()

            // workflow / state / transition 시드
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflows (key, name)
                        VALUES ('test-wf', '테스트 워크플로우')
                        ON CONFLICT (key) WHERE deleted_at IS NULL DO NOTHING
                        """.trimIndent(),
                    )
                }

                val workflowId: String =
                    conn.prepareStatement("SELECT id FROM workflows WHERE key='test-wf'").use { ps ->
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getString("id")
                        }
                    }

                insertWorkflowStatus(conn, java.util.UUID.fromString(workflowId), "open", "Open", "TODO", 1)
                insertWorkflowStatus(conn, java.util.UUID.fromString(workflowId), "in_progress", "In Progress", "IN_PROGRESS", 2)
                insertWorkflowStatus(conn, java.util.UUID.fromString(workflowId), "done", "Done", "DONE", 3)

                val fromStateId: String =
                    conn.prepareStatement(
                        "SELECT id FROM workflow_states WHERE workflow_id='$workflowId'::uuid AND key='open'",
                    ).use { ps ->
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getString("id")
                        }
                    }

                val toStateId: String =
                    conn.prepareStatement(
                        "SELECT id FROM workflow_states WHERE workflow_id='$workflowId'::uuid AND key='in_progress'",
                    ).use { ps ->
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getString("id")
                        }
                    }

                val doneStateId: String =
                    conn.prepareStatement(
                        "SELECT id FROM workflow_states WHERE workflow_id='$workflowId'::uuid AND key='done'",
                    ).use { ps ->
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getString("id")
                        }
                    }

                // post-action 이 있을 전환
                conn.prepareStatement(
                    """
                    INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name)
                    VALUES ('$workflowId'::uuid, '$fromStateId'::uuid, '$toStateId'::uuid, 'Start')
                    RETURNING id
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        transitionId = UUID.fromString(rs.getString("id"))
                    }
                }

                // post-action 이 없는 빈 전환
                conn.prepareStatement(
                    """
                    INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name)
                    VALUES ('$workflowId'::uuid, '$toStateId'::uuid, '$doneStateId'::uuid, 'Complete')
                    RETURNING id
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        emptyTransitionId = UUID.fromString(rs.getString("id"))
                    }
                }
            }

            val dataSource =
                DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = PostActionRepository(dsl, ObjectMapper())
        }
    }

    // ── findByTransitionId ─────────────────────────────────────────────────────

    @Test
    @Order(10)
    fun `findByTransitionId - post-action 없는 전환은 빈 목록 반환`() {
        val result = repository.findByTransitionId(emptyTransitionId)

        assertThat(result).isEmpty()
    }

    // ── insert + findByTransitionId ────────────────────────────────────────────

    @Test
    @Order(20)
    fun `insert 후 findByTransitionId 로 조회되어야 한다`() {
        val config = mapOf("url" to "https://example.com/hook", "method" to "POST")
        val row = repository.insert(transitionId, "CALL_WEBHOOK", config, 0)

        assertThat(row.id).isNotNull()
        assertThat(row.transitionId).isEqualTo(transitionId)
        assertThat(row.type).isEqualTo("CALL_WEBHOOK")
        assertThat(row.config["url"]).isEqualTo("https://example.com/hook")
        assertThat(row.displayOrder).isEqualTo(0)

        val found = repository.findByTransitionId(transitionId)
        assertThat(found).hasSize(1)
        assertThat(found.first().id).isEqualTo(row.id)
    }

    // ── update ─────────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    fun `update 후 변경 사항이 findByTransitionId 에 반영되어야 한다`() {
        val config = mapOf("url" to "https://original.com", "method" to "POST")
        val row = repository.insert(transitionId, "CALL_WEBHOOK", config, 10)

        val updatedConfig = mapOf("url" to "https://updated.com", "method" to "PUT")
        val updated = repository.update(row.id, "CALL_WEBHOOK", updatedConfig, 20)

        assertThat(updated.id).isEqualTo(row.id)
        assertThat(updated.config["url"]).isEqualTo("https://updated.com")
        assertThat(updated.config["method"]).isEqualTo("PUT")
        assertThat(updated.displayOrder).isEqualTo(20)

        val found = repository.findByTransitionId(transitionId)
        val updatedRow = found.firstOrNull { it.id == row.id }
        assertThat(updatedRow).isNotNull()
        assertThat(updatedRow!!.config["url"]).isEqualTo("https://updated.com")
    }

    // ── deleteById ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    fun `deleteById 후 해당 행이 조회되지 않아야 한다`() {
        val config = mapOf("url" to "https://to-delete.com", "method" to "DELETE")
        val row = repository.insert(transitionId, "CALL_WEBHOOK", config, 99)

        val countBefore = repository.findByTransitionId(transitionId).count { it.id == row.id }
        assertThat(countBefore).isEqualTo(1)

        repository.deleteById(row.id)

        val countAfter = repository.findByTransitionId(transitionId).count { it.id == row.id }
        assertThat(countAfter).isEqualTo(0)
    }

    // ── displayOrder ASC 정렬 ──────────────────────────────────────────────────

    @Test
    @Order(50)
    fun `displayOrder ASC 정렬 - 여러 건 삽입 시 순서대로 반환`() {
        // 기존 데이터 정리 후 별도 빈 전환 확보
        val sortTransitionId = emptyTransitionId // 이전에 항목을 추가하지 않은 전환

        repository.insert(sortTransitionId, "SET_FIELD", mapOf("field" to "assignee"), 30)
        repository.insert(sortTransitionId, "ADD_WATCHER", mapOf("watcher" to "actor"), 10)
        repository.insert(sortTransitionId, "CALL_WEBHOOK", mapOf("url" to "https://x.com", "method" to "POST"), 20)

        val result = repository.findByTransitionId(sortTransitionId)

        assertThat(result).hasSizeGreaterThanOrEqualTo(3)
        val orders = result.map { it.displayOrder }
        assertThat(orders).isEqualTo(orders.sorted())
    }
}
