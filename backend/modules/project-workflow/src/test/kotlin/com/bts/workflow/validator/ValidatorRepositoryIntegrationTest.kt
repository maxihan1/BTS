// ValidatorRepository 통합 테스트 — Testcontainers + Flyway 전량 + workflow/state/transition 시드

package com.bts.workflow.validator

import com.bts.workflow.testsupport.insertWorkflowStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * ValidatorRepository Testcontainers 통합 테스트.
 *
 * `workflow_validators` 는 V200 기존 테이블이고, `transition_id` 는 `workflow_transitions` 로 향하는
 * **FK(ON DELETE CASCADE)** 다. 그래서 전환 행 픽스처를 먼저 심어야 INSERT 자체가 성립한다 —
 * 컨테이너·마이그레이션 배선과 workflow/state 시드는 형제인 `PostActionRepositoryIntegrationTest` 와 같다.
 *
 * 검증 범위.
 * - findByTransitionId: display_order ASC 정렬
 * - insert → findByTransitionId: config JSONB 왕복
 * - update: type / config / displayOrder 갱신 반영
 * - deleteById: 그 행만 빠지고 나머지는 남는다
 *
 * 전환은 **테스트마다 새로 만든다**. 하나를 공유하면 앞선 테스트가 남긴 행이 정렬·삭제 단언을 오염시키고,
 * 그 오염을 `@TestMethodOrder` 로 막으면 실행 순서에 기대는 테스트가 된다.
 * V207 이 `UNIQUE(workflow_id, from_state_id, to_state_id)` 를 풀었으므로 같은 상태쌍에 여러 전환을 둘 수 있다.
 */
@Testcontainers
class ValidatorRepositoryIntegrationTest {
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

        lateinit var repository: ValidatorRepository
        private lateinit var fromStateId: UUID
        private lateinit var toStateId: UUID
        private lateinit var workflowId: UUID

        @BeforeAll
        @JvmStatic
        fun setup() {
            migrateToV200()
            createIssueTypesStub()
            migrateRemaining()
            seedWorkflowAndStates()

            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            repository = ValidatorRepository(DSL.using(dataSource, SQLDialect.POSTGRES), ObjectMapper())
        }

        /** 1단계 — V200 까지 적용 (cross-BC issue-tracking 포함). */
        private fun migrateToV200() {
            flyway().target("200").load().migrate()
        }

        /** 2단계 — 나머지 마이그레이션 전체 적용. */
        private fun migrateRemaining() {
            flyway().load().migrate()
        }

        private fun flyway() =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )

        /** issue_types 스텁 — V201+ FK 통과용. */
        private fun createIssueTypesStub() {
            connection().use { conn ->
                conn.prepareStatement(
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
                ).use { it.execute() }
            }
        }

        /** 워크플로우 1개 + 상태 2개. 전환은 테스트마다 [newTransition] 이 만든다. */
        private fun seedWorkflowAndStates() {
            connection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES (?, ?)" +
                        " ON CONFLICT (key) WHERE deleted_at IS NULL DO NOTHING",
                ).use { stmt ->
                    stmt.setString(1, "validator-test-wf")
                    stmt.setString(2, "validator 테스트 워크플로우")
                    stmt.executeUpdate()
                }

                workflowId =
                    conn.prepareStatement("SELECT id FROM workflows WHERE key = ?").use { stmt ->
                        stmt.setString(1, "validator-test-wf")
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getObject(1) as UUID
                        }
                    }

                fromStateId = insertWorkflowStatus(conn, workflowId, "open", "Open", "TODO", 1)
                toStateId = insertWorkflowStatus(conn, workflowId, "done", "Done", "DONE", 2)
            }
        }

        /** 빈 전환 1개를 새로 심고 그 id 를 준다. 테스트 간 격리의 단위다. */
        fun newTransition(name: String): UUID =
            connection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name)" +
                        " VALUES (?, ?, ?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setObject(1, workflowId)
                    stmt.setObject(2, fromStateId)
                    stmt.setObject(3, toStateId)
                    stmt.setString(4, name)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            }

        private fun connection(): Connection =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    @Test
    fun `findByTransitionId 는 display_order ASC 로 돌려준다`() {
        val transitionId = newTransition("정렬 검증 전환")
        repository.insert(transitionId, "not-status-category", mapOf("category" to "DONE"), 30)
        repository.insert(transitionId, "RequiredField", mapOf("field" to "resolution"), 10)
        repository.insert(transitionId, "permission-check", mapOf("permission" to "EDIT_ISSUE"), 20)

        val result = repository.findByTransitionId(transitionId)

        assertThat(result.map { it.displayOrder }).containsExactly(10, 20, 30)
        assertThat(result.map { it.type })
            .containsExactly("RequiredField", "permission-check", "not-status-category")
    }

    @Test
    fun `insert 한 행을 config JSONB 그대로 읽는다`() {
        val transitionId = newTransition("config 왕복 전환")
        val config =
            mapOf(
                "field" to "resolution",
                "required" to true,
                "threshold" to 3,
                "allowed" to listOf("done", "closed"),
                "nested" to mapOf("scope" to "ISSUE"),
            )

        val inserted = repository.insert(transitionId, "RequiredField", config, 0)

        assertThat(inserted.transitionId).isEqualTo(transitionId)
        assertThat(inserted.type).isEqualTo("RequiredField")

        val found = repository.findByTransitionId(transitionId).single()
        assertThat(found.id).isEqualTo(inserted.id)
        assertThat(found.config).isEqualTo(config)
    }

    @Test
    fun `update 가 type · config · displayOrder 를 바꾼다`() {
        val transitionId = newTransition("수정 검증 전환")
        val row = repository.insert(transitionId, "RequiredField", mapOf("field" to "resolution"), 5)

        val updated =
            repository.update(row.id, "not-status-category", mapOf("category" to "TODO"), 42)

        assertThat(updated.id).isEqualTo(row.id)
        assertThat(updated.transitionId).isEqualTo(transitionId)

        val found = repository.findByTransitionId(transitionId).single()
        assertThat(found.type).isEqualTo("not-status-category")
        assertThat(found.config).isEqualTo(mapOf("category" to "TODO"))
        assertThat(found.displayOrder).isEqualTo(42)
    }

    @Test
    fun `deleteById 후 findByTransitionId 가 그 행을 빼고 돌려준다`() {
        val transitionId = newTransition("삭제 검증 전환")
        val doomed = repository.insert(transitionId, "RequiredField", mapOf("field" to "resolution"), 0)
        val survivor = repository.insert(transitionId, "permission-check", mapOf("permission" to "EDIT_ISSUE"), 1)

        repository.deleteById(doomed.id)

        val result = repository.findByTransitionId(transitionId)
        assertThat(result.map { it.id }).containsExactly(survivor.id)
    }
}
