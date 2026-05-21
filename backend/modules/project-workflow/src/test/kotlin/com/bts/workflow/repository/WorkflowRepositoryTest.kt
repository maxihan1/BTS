// WorkflowRepository — Testcontainers postgres + Flyway V001 + 3 case (findByKey hit/miss + findAll)

package com.bts.workflow.repository

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
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
import java.sql.DriverManager

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
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        lateinit var repository: WorkflowRepository

        @BeforeAll
        @JvmStatic
        fun setup() {
            // Flyway — DB 스키마 변경을 버전 관리하는 도구. V001 마이그레이션을 적용해 5 테이블 생성
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            // seed 데이터 삽입 — workflows 2건 + states + transitions
            seedData()

            // jOOQ DSLContext — SQL을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
            val dataSource = org.springframework.jdbc.datasource.DriverManagerDataSource(
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

                // 워크플로우 1 — software-default (findByKey 적중 케이스)
                val wfId1 = conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('software-default', '소프트웨어 기본') RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs -> rs.next(); rs.getObject(1) as java.util.UUID }
                }

                // workflow_states 3개 (TODO/IN_PROGRESS/DONE)
                val stateOpenId = conn.prepareStatement(
                    "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) VALUES (?, 'open', '열림', 'TODO', 0) RETURNING id",
                ).use { stmt ->
                    stmt.setObject(1, wfId1)
                    stmt.executeQuery().use { rs -> rs.next(); rs.getObject(1) as java.util.UUID }
                }
                val stateInProgressId = conn.prepareStatement(
                    "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) VALUES (?, 'in-progress', '진행 중', 'IN_PROGRESS', 1) RETURNING id",
                ).use { stmt ->
                    stmt.setObject(1, wfId1)
                    stmt.executeQuery().use { rs -> rs.next(); rs.getObject(1) as java.util.UUID }
                }
                val stateDoneId = conn.prepareStatement(
                    "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) VALUES (?, 'done', '완료', 'DONE', 2) RETURNING id",
                ).use { stmt ->
                    stmt.setObject(1, wfId1)
                    stmt.executeQuery().use { rs -> rs.next(); rs.getObject(1) as java.util.UUID }
                }

                // workflow_transitions 2개
                conn.prepareStatement(
                    "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) VALUES (?, ?, ?, '시작')",
                ).use { stmt ->
                    stmt.setObject(1, wfId1)
                    stmt.setObject(2, stateOpenId)
                    stmt.setObject(3, stateInProgressId)
                    stmt.executeUpdate()
                }
                conn.prepareStatement(
                    "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) VALUES (?, ?, ?, '완료')",
                ).use { stmt ->
                    stmt.setObject(1, wfId1)
                    stmt.setObject(2, stateInProgressId)
                    stmt.setObject(3, stateDoneId)
                    stmt.executeUpdate()
                }

                // 워크플로우 2 — bug-tracking (findAll 케이스용)
                val wfId2 = conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('bug-tracking', '버그 추적') RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs -> rs.next(); rs.getObject(1) as java.util.UUID }
                }

                val stateBugOpenId = conn.prepareStatement(
                    "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) VALUES (?, 'bug-open', '버그 등록', 'TODO', 0) RETURNING id",
                ).use { stmt ->
                    stmt.setObject(1, wfId2)
                    stmt.executeQuery().use { rs -> rs.next(); rs.getObject(1) as java.util.UUID }
                }
                val stateBugFixedId = conn.prepareStatement(
                    "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) VALUES (?, 'bug-fixed', '수정 완료', 'DONE', 1) RETURNING id",
                ).use { stmt ->
                    stmt.setObject(1, wfId2)
                    stmt.executeQuery().use { rs -> rs.next(); rs.getObject(1) as java.util.UUID }
                }

                conn.prepareStatement(
                    "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) VALUES (?, ?, ?, '수정')",
                ).use { stmt ->
                    stmt.setObject(1, wfId2)
                    stmt.setObject(2, stateBugOpenId)
                    stmt.setObject(3, stateBugFixedId)
                    stmt.executeUpdate()
                }

                conn.commit()
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
