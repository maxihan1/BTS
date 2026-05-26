// ProjectWorkflowSchemeAssignmentRepository 통합 테스트 — UPSERT(saveAssignment) + findByProjectId + deleteByProjectId

package com.bts.workflow.scheme.repository

import com.bts.workflow.scheme.domain.ProjectWorkflowSchemeAssignment
import com.bts.workflow.scheme.domain.WorkflowSchemeId
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
import java.time.Instant
import java.util.UUID

/**
 * ProjectWorkflowSchemeAssignmentRepository 통합 테스트.
 *
 * 검증 범위.
 * - saveAssignment: 신규 할당 INSERT (project_id PK, workflow_scheme_id FK)
 * - saveAssignment: 동일 project_id 재호출 시 UPSERT → workflow_scheme_id 교체 (ON CONFLICT DO UPDATE)
 * - findByProjectId: 존재하는 project_id 조회 시 Assignment 반환
 * - findByProjectId: 존재하지 않는 project_id 조회 시 null 반환
 * - deleteByProjectId: 삭제 후 findByProjectId null 반환
 * - deleteByProjectId: 존재하지 않는 project_id 삭제 시 예외 없음 (no-op)
 *
 * Cross-table UPSERT 금지 (PR #14 learning) — projects 테이블 미터치.
 * projects 는 fixture INSERT 로 FK 검증 없이 BIGINT project_id 만 사용 (V004 FK 제약 없음).
 *
 * Testcontainers 이미지: quay.io/tembo/pg16-pgmq:latest (V004 pgmq 확장 요구).
 * Flyway 2단계 패턴: target="1" → issue_types 스텁 → LATEST (T7 WorkflowSchemesMigrationIntegrationTest 패턴).
 */
@Testcontainers
class ProjectWorkflowSchemeAssignmentRepositoryIntegrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V004 pgmq 확장 + pgmq.create() 요구로 인해 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
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

        lateinit var repository: ProjectWorkflowSchemeAssignmentRepository

        // 테스트에서 공유하는 scheme_id fixture — workflow_schemes 테이블에 사전 삽입
        var schemeId1: Long = 0L
        var schemeId2: Long = 0L

        @BeforeAll
        @JvmStatic
        fun setup() {
            // 2단계 Flyway: V001 먼저 → issue_types 스텁 생성 → V002~V004 실행
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .target("1")
                .load()
                .migrate()

            // issue_types 스텁 — workflow_scheme_issue_type_mappings.issue_type_id cross-BC FK 통과용.
            // 프로덕션: issue-tracking V003 이 먼저 실행. 테스트: project-workflow 모듈 Flyway 만 실행.
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

            // V002~V004 실행 (LATEST)
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            // workflow_schemes fixture 삽입 — UPSERT FK 검증용
            // V004 seed 로 이미 4 row 존재. 추가 삽입 없이 기존 id 를 읽어 사용한다.
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT id FROM workflow_schemes WHERE deleted_at IS NULL ORDER BY id LIMIT 2",
                    ).use { rs ->
                        rs.next()
                        schemeId1 = rs.getLong(1)
                        rs.next()
                        schemeId2 = rs.getLong(1)
                    }
                }
            }

            val dataSource =
                org.springframework.jdbc.datasource.DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )
            repository = ProjectWorkflowSchemeAssignmentRepository(DSL.using(dataSource, SQLDialect.POSTGRES))
        }
    }

    // ── saveAssignment: 신규 INSERT ────────────────────────────────────────────

    @Test
    fun `saveAssignment - 신규 project_id 저장 시 row 가 삽입된다`() {
        val projectId = 1001L
        val actorId = UUID.randomUUID()
        val assignment =
            ProjectWorkflowSchemeAssignment(
                projectId = projectId,
                workflowSchemeId = WorkflowSchemeId(schemeId1),
                assignedAt = Instant.now(),
                assignedBy = actorId,
            )

        repository.saveAssignment(assignment)

        val found = repository.findByProjectId(projectId)
        assertThat(found).isNotNull
        assertThat(found!!.projectId).isEqualTo(projectId)
        assertThat(found.workflowSchemeId).isEqualTo(WorkflowSchemeId(schemeId1))
        assertThat(found.assignedBy).isEqualTo(actorId)
    }

    // ── saveAssignment: UPSERT — 동일 project_id 재호출 시 교체 ───────────────

    @Test
    fun `saveAssignment - 동일 project_id 재호출 시 workflow_scheme_id 가 교체된다`() {
        val projectId = 1002L
        val actorId = UUID.randomUUID()

        // 1차 저장 — schemeId1
        repository.saveAssignment(
            ProjectWorkflowSchemeAssignment(
                projectId = projectId,
                workflowSchemeId = WorkflowSchemeId(schemeId1),
                assignedAt = Instant.now(),
                assignedBy = actorId,
            ),
        )

        // 2차 저장 — schemeId2 (UPSERT)
        val newActorId = UUID.randomUUID()
        repository.saveAssignment(
            ProjectWorkflowSchemeAssignment(
                projectId = projectId,
                workflowSchemeId = WorkflowSchemeId(schemeId2),
                assignedAt = Instant.now(),
                assignedBy = newActorId,
            ),
        )

        val found = repository.findByProjectId(projectId)
        assertThat(found).isNotNull
        assertThat(found!!.workflowSchemeId).isEqualTo(WorkflowSchemeId(schemeId2))
        assertThat(found.assignedBy).isEqualTo(newActorId)
    }

    // ── findByProjectId: 존재 ──────────────────────────────────────────────────

    @Test
    fun `findByProjectId - 존재하는 project_id 조회 시 Assignment 반환`() {
        val projectId = 1003L
        val actorId = UUID.randomUUID()
        val assignedAt = Instant.parse("2026-05-26T10:00:00Z")

        repository.saveAssignment(
            ProjectWorkflowSchemeAssignment(
                projectId = projectId,
                workflowSchemeId = WorkflowSchemeId(schemeId1),
                assignedAt = assignedAt,
                assignedBy = actorId,
            ),
        )

        val found = repository.findByProjectId(projectId)

        assertThat(found).isNotNull
        assertThat(found!!.projectId).isEqualTo(projectId)
        assertThat(found.workflowSchemeId).isEqualTo(WorkflowSchemeId(schemeId1))
        assertThat(found.assignedBy).isEqualTo(actorId)
        assertThat(found.assignedAt).isNotNull
    }

    // ── findByProjectId: 부재 ──────────────────────────────────────────────────

    @Test
    fun `findByProjectId - 존재하지 않는 project_id 조회 시 null 반환`() {
        val found = repository.findByProjectId(99999L)

        assertThat(found).isNull()
    }

    // ── deleteByProjectId: 삭제 후 부재 ───────────────────────────────────────

    @Test
    fun `deleteByProjectId - 삭제 후 findByProjectId 가 null 반환`() {
        val projectId = 1004L

        repository.saveAssignment(
            ProjectWorkflowSchemeAssignment(
                projectId = projectId,
                workflowSchemeId = WorkflowSchemeId(schemeId1),
                assignedAt = Instant.now(),
                assignedBy = UUID.randomUUID(),
            ),
        )

        repository.deleteByProjectId(projectId)

        assertThat(repository.findByProjectId(projectId)).isNull()
    }

    // ── deleteByProjectId: 존재하지 않는 row 삭제 시 no-op ────────────────────

    @Test
    fun `deleteByProjectId - 존재하지 않는 project_id 삭제 시 예외 없이 no-op`() {
        // 예외 없이 정상 완료되어야 한다
        repository.deleteByProjectId(99998L)
    }
}
