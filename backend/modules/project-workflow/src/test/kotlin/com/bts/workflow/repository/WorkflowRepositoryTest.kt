// WorkflowRepository — Testcontainers postgres + Flyway V001 + 3 case (findByKey hit/miss + findAll)

package com.bts.workflow.repository

import com.bts.workflow.domain.StateCategory
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

/** 테스트 seed 용 상태 삽입 파라미터 묶음. */
private data class RawState(val key: String, val name: String, val category: String, val displayOrder: Int)

/**
 * WorkflowRepository — jOOQ DSLContext 기반 3 테이블 join 조회 통합 테스트.
 *
 * 검증 범위.
 * - findByKey: 적중 시 Workflow aggregate 복원 (states/transitions 포함)
 * - findByKey: 부재 시 null 반환
 * - findAll: 모든 Workflow 반환
 *
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL + Flyway + jOOQ DSL 직접 구성.
 * 참조. FR-WF-01 Task 28.
 */
@Testcontainers
class WorkflowRepositoryTest {
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

        lateinit var repository: WorkflowRepository

        @BeforeAll
        @JvmStatic
        fun setup() {
            // Flyway 2단계 — V004 issue_types cross-BC FK 대응
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .target("1")
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
                .locations("classpath:db/migration/project-workflow")
                .load()
                .migrate()

            // seed 데이터 삽입 — workflows 2건 + states + transitions
            seedData()

            // jOOQ DSLContext — SQL을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
            val dataSource =
                org.springframework.jdbc.datasource.DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = WorkflowRepository(dsl)
        }

        private fun seedData() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.autoCommit = false
                seedSoftwareDefault(conn)
                seedBugTracking(conn)
                conn.commit()
            }
        }

        private fun seedSoftwareDefault(conn: java.sql.Connection) {
            val wfId = insertWorkflowRaw(conn, "software-default", "소프트웨어 기본")
            val openId = insertStateRaw(conn, wfId, RawState("open", "열림", "TODO", 0))
            val inProgressId = insertStateRaw(conn, wfId, RawState("in-progress", "진행 중", "IN_PROGRESS", 1))
            val doneId = insertStateRaw(conn, wfId, RawState("done", "완료", "DONE", 2))
            insertTransitionRaw(conn, wfId, openId, inProgressId, "시작")
            insertTransitionRaw(conn, wfId, inProgressId, doneId, "완료")
        }

        private fun seedBugTracking(conn: java.sql.Connection) {
            val wfId = insertWorkflowRaw(conn, "bug-tracking", "버그 추적")
            val bugOpenId = insertStateRaw(conn, wfId, RawState("bug-open", "버그 등록", "TODO", 0))
            val bugFixedId = insertStateRaw(conn, wfId, RawState("bug-fixed", "수정 완료", "DONE", 1))
            insertTransitionRaw(conn, wfId, bugOpenId, bugFixedId, "수정")
        }

        private fun insertWorkflowRaw(
            conn: java.sql.Connection,
            key: String,
            name: String,
        ): java.util.UUID =
            conn.prepareStatement(
                "INSERT INTO workflows (key, name) VALUES (?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as java.util.UUID
                }
            }

        private fun insertStateRaw(
            conn: java.sql.Connection,
            wfId: java.util.UUID,
            spec: RawState,
        ): java.util.UUID =
            conn.prepareStatement(
                "INSERT INTO workflow_states" +
                    " (workflow_id, key, name, category, display_order)" +
                    " VALUES (?, ?, ?, ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, wfId)
                stmt.setString(2, spec.key)
                stmt.setString(3, spec.name)
                stmt.setString(4, spec.category)
                stmt.setInt(5, spec.displayOrder)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as java.util.UUID
                }
            }

        private fun insertTransitionRaw(
            conn: java.sql.Connection,
            wfId: java.util.UUID,
            fromId: java.util.UUID,
            toId: java.util.UUID,
            name: String,
        ) {
            conn.prepareStatement(
                "INSERT INTO workflow_transitions" +
                    " (workflow_id, from_state_id, to_state_id, name) VALUES (?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, wfId)
                stmt.setObject(2, fromId)
                stmt.setObject(3, toId)
                stmt.setString(4, name)
                stmt.executeUpdate()
            }
        }
    }

    // ── findByKey 적중 ──────────────────────────────────────────────────────────

    @Test
    fun `findByKey - 존재하는 key 조회 시 Workflow aggregate 반환`() {
        val workflow = repository.findByKey("software-default")

        assertThat(workflow).isNotNull
        assertThat(workflow!!.key).isEqualTo("software-default")
        assertThat(workflow.name).isEqualTo("소프트웨어 기본")

        // states 3개 복원 확인
        assertThat(workflow.states).hasSize(3)
        val stateKeys = workflow.states.map { it.key }
        assertThat(stateKeys).containsExactlyInAnyOrder("open", "in-progress", "done")

        // category 복원 확인
        val openState = workflow.states.first { it.key == "open" }
        assertThat(openState.category).isEqualTo(StateCategory.TODO)
        val inProgressState = workflow.states.first { it.key == "in-progress" }
        assertThat(inProgressState.category).isEqualTo(StateCategory.IN_PROGRESS)
        val doneState = workflow.states.first { it.key == "done" }
        assertThat(doneState.category).isEqualTo(StateCategory.DONE)

        // transitions 2개 복원 확인
        assertThat(workflow.transitions).hasSize(2)
        val transitionNames = workflow.transitions.map { it.name }
        assertThat(transitionNames).containsExactlyInAnyOrder("시작", "완료")

        // fromStateKey / toStateKey 복원 확인 (UUID → key 매핑)
        val startTransition = workflow.transitions.first { it.name == "시작" }
        assertThat(startTransition.fromStateKey).isEqualTo("open")
        assertThat(startTransition.toStateKey).isEqualTo("in-progress")
    }

    // ── findByKey 부재 ──────────────────────────────────────────────────────────

    @Test
    fun `findByKey - 존재하지 않는 key 조회 시 null 반환`() {
        val workflow = repository.findByKey("non-existent-workflow")

        assertThat(workflow).isNull()
    }

    // ── findAll ─────────────────────────────────────────────────────────────────

    @Test
    fun `findAll - 모든 Workflow 반환`() {
        val workflows = repository.findAll()

        assertThat(workflows).hasSizeGreaterThanOrEqualTo(2)
        val keys = workflows.map { it.key }
        assertThat(keys).contains("software-default", "bug-tracking")

        // bug-tracking aggregate 복원 확인
        val bugTracking = workflows.first { it.key == "bug-tracking" }
        assertThat(bugTracking.states).hasSize(2)
        assertThat(bugTracking.transitions).hasSize(1)
        assertThat(bugTracking.transitions.first().name).isEqualTo("수정")
    }
}
