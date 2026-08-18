// DefaultWorkflowDefinitionRepository — Testcontainers postgres + Flyway V200 + jOOQ 기반 validator/post_action 조회 통합 테스트

package com.bts.workflow.repository

import com.bts.workflow.domain.WorkflowTransition
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * DefaultWorkflowDefinitionRepository — Testcontainers PostgreSQL + Flyway V200 적용 후
 * workflow_validators / workflow_post_actions 조회를 검증한다.
 *
 * 검증 범위.
 * - findValidators: display_order ASC 정렬, config JSONB → Map 역직렬화
 * - findPostActions: display_order ASC 정렬, config JSONB → Map 역직렬화
 * - B2 cross-workflow 격리: 같은 (from, to) 상태 key 쌍을 사용하는 두 워크플로우 간 validator 오매칭 차단
 * - 미존재 workflowKey → 빈 리스트 (예외 아님)
 * - transition 미존재 → 빈 리스트 (예외 아님)
 *
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL + Flyway + jOOQ DSL 직접 구성.
 */
@Testcontainers
class DefaultWorkflowDefinitionRepositoryTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V004 pgmq 확장 요구로 인해 tembo 이미지 사용.
        // ADR 2026-05-22-pgmq-postgres-image 와 동일 패턴.
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

        lateinit var repository: DefaultWorkflowDefinitionRepository

        // B2 검증용: workflowAlpha — open→done 에 validator 2건 + post_action 1건
        lateinit var alphaTransition: WorkflowTransition

        // B2 검증용: workflowBeta — 같은 state key (open→done) 를 사용하지만 다른 validator 세트
        lateinit var betaTransition: WorkflowTransition

        @BeforeAll
        @JvmStatic
        fun setup() {
            // Flyway 2단계 — V201 (workflow_schemes) issue_types cross-BC FK 대응
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

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS issue_types (" +
                            "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                            "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                            "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                            "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                    )
                }
            }

            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()

            // jOOQ DSLContext 구성
            val dataSource =
                org.springframework.jdbc.datasource.DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

            // 테스트 데이터 시드 — 두 워크플로우, B2 격리 검증용
            seedData()

            repository = DefaultWorkflowDefinitionRepository(dsl)
            alphaTransition = WorkflowTransition(fromStateKey = "open", toStateKey = "done", name = "완료")
            betaTransition = WorkflowTransition(fromStateKey = "open", toStateKey = "done", name = "완료")
        }

        private fun seedData() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.autoCommit = false

                // ── workflowAlpha: validator 2건(display_order 0,1) + post_action 1건 ──
                val alphaId = insertWorkflow(conn, "alpha-workflow", "알파 워크플로우")
                val alphaOpenId = insertState(conn, alphaId, StateSpec("open", "열림", "TODO", 0))
                val alphaDoneId = insertState(conn, alphaId, StateSpec("done", "완료", "DONE", 1))
                val alphaTransId = insertTransition(conn, alphaId, alphaOpenId, alphaDoneId, "완료")

                insertValidator(conn, alphaTransId, "RequiredField", """{"field":"resolution"}""", 0)
                insertValidator(conn, alphaTransId, "permission-check", """{"role":"DEVELOPER"}""", 1)
                insertPostAction(conn, alphaTransId, "SET_FIELD", """{"field":"assignee","value":"actor"}""", 0)

                // ── workflowBeta: 같은 state key(open→done) 사용, validator 1건(다른 type) ──
                // B2 오매칭 위험: beta의 (open→done) 조회 시 alpha validator 가 섞이면 안 됨
                val betaId = insertWorkflow(conn, "beta-workflow", "베타 워크플로우")
                val betaOpenId = insertState(conn, betaId, StateSpec("open", "열림", "TODO", 0))
                val betaDoneId = insertState(conn, betaId, StateSpec("done", "완료", "DONE", 1))
                val betaTransId = insertTransition(conn, betaId, betaOpenId, betaDoneId, "완료")

                insertValidator(conn, betaTransId, "not-status-category", """{"category":"IN_PROGRESS"}""", 0)
                // post_action 없음 — 빈 리스트 반환 검증

                conn.commit()
            }
        }

        private fun insertWorkflow(
            conn: java.sql.Connection,
            key: String,
            name: String,
        ): UUID =
            conn.prepareStatement(
                "INSERT INTO workflows (key, name) VALUES (?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }

        private data class StateSpec(
            val key: String,
            val name: String,
            val category: String,
            val displayOrder: Int,
        )

        private fun insertState(
            conn: java.sql.Connection,
            workflowId: UUID,
            spec: StateSpec,
        ): UUID =
            conn.prepareStatement(
                "INSERT INTO workflow_states (workflow_id, key, name, category, display_order)" +
                    " VALUES (?, ?, ?, ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, workflowId)
                stmt.setString(2, spec.key)
                stmt.setString(3, spec.name)
                stmt.setString(4, spec.category)
                stmt.setInt(5, spec.displayOrder)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }

        private fun insertTransition(
            conn: java.sql.Connection,
            workflowId: UUID,
            fromStateId: UUID,
            toStateId: UUID,
            name: String,
        ): UUID =
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

        private fun insertValidator(
            conn: java.sql.Connection,
            transitionId: UUID,
            type: String,
            configJson: String,
            displayOrder: Int,
        ) {
            conn.prepareStatement(
                "INSERT INTO workflow_validators (transition_id, type, config, display_order)" +
                    " VALUES (?, ?, ?::jsonb, ?)",
            ).use { stmt ->
                stmt.setObject(1, transitionId)
                stmt.setString(2, type)
                stmt.setString(3, configJson)
                stmt.setInt(4, displayOrder)
                stmt.executeUpdate()
            }
        }

        private fun insertPostAction(
            conn: java.sql.Connection,
            transitionId: UUID,
            type: String,
            configJson: String,
            displayOrder: Int,
        ) {
            conn.prepareStatement(
                "INSERT INTO workflow_post_actions (transition_id, type, config, display_order)" +
                    " VALUES (?, ?, ?::jsonb, ?)",
            ).use { stmt ->
                stmt.setObject(1, transitionId)
                stmt.setString(2, type)
                stmt.setString(3, configJson)
                stmt.setInt(4, displayOrder)
                stmt.executeUpdate()
            }
        }
    }

    // ── findValidators ────────────────────────────────────────────────────────

    @Test
    fun `findValidators - display_order ASC 정렬로 ValidatorConfig 목록 반환`() {
        val result = repository.findValidators("alpha-workflow", alphaTransition)

        assertThat(result).hasSize(2)
        assertThat(result[0].type).isEqualTo("RequiredField")
        assertThat(result[1].type).isEqualTo("permission-check")
    }

    @Test
    fun `findValidators - config JSONB 가 Map 으로 역직렬화`() {
        val result = repository.findValidators("alpha-workflow", alphaTransition)

        assertThat(result[0].config).containsEntry("field", "resolution")
        assertThat(result[1].config).containsEntry("role", "DEVELOPER")
    }

    @Test
    fun `findValidators - 미존재 workflowKey 는 빈 리스트 반환`() {
        val result =
            repository.findValidators(
                "non-existent-workflow",
                WorkflowTransition("open", "done", "완료"),
            )

        assertThat(result).isEmpty()
    }

    @Test
    fun `findValidators - 미존재 transition 은 빈 리스트 반환`() {
        val result =
            repository.findValidators(
                "alpha-workflow",
                WorkflowTransition("open", "nonexistent", "없는 전환"),
            )

        assertThat(result).isEmpty()
    }

    // ── findPostActions ───────────────────────────────────────────────────────

    @Test
    fun `findPostActions - display_order ASC 정렬로 PostActionConfig 목록 반환`() {
        val result = repository.findPostActions("alpha-workflow", alphaTransition)

        assertThat(result).hasSize(1)
        assertThat(result[0].type).isEqualTo("SET_FIELD")
    }

    @Test
    fun `findPostActions - config JSONB 가 Map 으로 역직렬화`() {
        val result = repository.findPostActions("alpha-workflow", alphaTransition)

        assertThat(result[0].config).containsEntry("field", "assignee")
        assertThat(result[0].config).containsEntry("value", "actor")
    }

    @Test
    fun `findPostActions - post_action 없는 workflow 전환은 빈 리스트 반환`() {
        val result = repository.findPostActions("beta-workflow", betaTransition)

        assertThat(result).isEmpty()
    }

    // ── B2 cross-workflow 격리 ────────────────────────────────────────────────

    @Test
    fun `B2 - alpha-workflow 조회 시 beta-workflow validator 가 섞이지 않음`() {
        // alpha 는 RequiredField + Permission 2건
        val alphaResult = repository.findValidators("alpha-workflow", alphaTransition)

        // beta validator(not-status-category) 가 alpha 결과에 포함돼선 안 됨
        assertThat(alphaResult.map { it.type }).doesNotContain("not-status-category")
        assertThat(alphaResult).hasSize(2)
    }

    @Test
    fun `B2 - beta-workflow 조회 시 alpha-workflow validator 가 섞이지 않음`() {
        // beta 는 NotStatusCategory 1건
        val betaResult = repository.findValidators("beta-workflow", betaTransition)

        // alpha validator(RequiredField, permission-check) 가 beta 결과에 포함돼선 안 됨
        assertThat(betaResult.map { it.type }).doesNotContain("RequiredField")
        assertThat(betaResult.map { it.type }).doesNotContain("permission-check")
        assertThat(betaResult).hasSize(1)
        assertThat(betaResult[0].type).isEqualTo("not-status-category")
    }
}
