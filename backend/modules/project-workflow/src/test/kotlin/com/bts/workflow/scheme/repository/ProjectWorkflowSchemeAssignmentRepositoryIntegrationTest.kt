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
 * - saveAssignment: 신규 할당 INSERT (project_id PK UUID, workflow_scheme_id FK)
 * - saveAssignment: 동일 project_id 재호출 시 UPSERT → workflow_scheme_id 교체 (ON CONFLICT DO UPDATE)
 * - findByProjectId: 존재하는 project_id 조회 시 Assignment 반환
 * - findByProjectId: 존재하지 않는 project_id 조회 시 null 반환
 * - deleteByProjectId: 삭제 후 findByProjectId null 반환
 * - deleteByProjectId: 존재하지 않는 project_id 삭제 시 예외 없음 (no-op)
 *
 * Cross-table UPSERT 금지 (PR #14 learning) — projects 테이블 미터치.
 * V202 FK 제약 (project_id → projects.id ON DELETE CASCADE) 으로 인해
 * 통합 테스트 setUp 에서 projects 테이블에 fixture row 를 SQL 직접 INSERT 하고
 * 생성된 UUID 를 각 시나리오에서 사용한다.
 *
 * Testcontainers 이미지: quay.io/tembo/pg16-pgmq:latest (V201 pgmq 확장 요구).
 * Flyway 2단계 패턴: target="200" → issue_types 스텁 → LATEST (T7 WorkflowSchemesMigrationIntegrationTest 패턴).
 */
@Testcontainers
class ProjectWorkflowSchemeAssignmentRepositoryIntegrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V201 pgmq 확장 + pgmq.create() 요구로 인해 tembo 이미지 사용.
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

        // V202 FK 검증용 — projects 테이블에 setUp 에서 INSERT 한 UUID
        lateinit var fixtureProjectUuid1: UUID
        lateinit var fixtureProjectUuid2: UUID
        lateinit var fixtureProjectUuid3: UUID
        lateinit var fixtureProjectUuid4: UUID

        // deleteByProjectId no-op 테스트용 — projects 에 없는 임의 UUID (FK → CASCADE 테스트용 아님)
        // V202 FK 는 projects.id 참조이므로 존재하지 않는 UUID 로 deleteByProjectId 호출 시 no-op 이어야 한다.
        val nonExistentProjectUuid: UUID = UUID.fromString("00000000-0000-0000-0000-ffffffffffff")

        @BeforeAll
        @JvmStatic
        fun setup() {
            applyMigrations()
            loadSchemeFixtures()
            insertProjectFixtures()

            fixtureProjectUuid1 = UUID.fromString("00000000-0000-0000-0001-000000001001")
            fixtureProjectUuid2 = UUID.fromString("00000000-0000-0000-0002-000000001002")
            fixtureProjectUuid3 = UUID.fromString("00000000-0000-0000-0003-000000001003")
            fixtureProjectUuid4 = UUID.fromString("00000000-0000-0000-0004-000000001004")

            val dataSource =
                org.springframework.jdbc.datasource.DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )
            repository = ProjectWorkflowSchemeAssignmentRepository(DSL.using(dataSource, SQLDialect.POSTGRES))
        }

        /** 2단계 Flyway 마이그레이션 적용. target=200 → cross-BC 스텁 → LATEST. */
        private fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .target("200")
                .load()
                .migrate()

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    // issue_types 스텁 — workflow_scheme_issue_type_mappings FK 통과용 (V003 있으면 no-op)
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS issue_types (" +
                            "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                            "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                            "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                            "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                    )
                    // projects 스텁 — V202 FK (project_id → projects.id) 통과용 (V001 있으면 no-op)
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS projects (" +
                            "id UUID PRIMARY KEY DEFAULT gen_random_uuid(), " +
                            "key VARCHAR(10) NOT NULL UNIQUE, name VARCHAR(255) NOT NULL, " +
                            "key_sequence BIGINT NOT NULL DEFAULT 0, " +
                            "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                            "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                    )
                }
            }

            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }

        /** workflow_schemes 에서 기존 seed id 2개를 읽어 schemeId1/schemeId2 에 저장한다. */
        private fun loadSchemeFixtures() {
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
        }

        /** projects 테이블에 각 시나리오 별 fixture row 를 INSERT 한다 (V202 FK 검증용). */
        private fun insertProjectFixtures() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    listOf(
                        Triple("P001", "Project Alpha", "00000000-0000-0000-0001-000000001001"),
                        Triple("P002", "Project Beta", "00000000-0000-0000-0002-000000001002"),
                        Triple("P003", "Project Gamma", "00000000-0000-0000-0003-000000001003"),
                        Triple("P004", "Project Delta", "00000000-0000-0000-0004-000000001004"),
                    ).forEach { (key, name, uuid) ->
                        stmt.execute(
                            "INSERT INTO projects (id, key, name) VALUES ('$uuid', '$key', '$name') " +
                                "ON CONFLICT DO NOTHING",
                        )
                    }
                }
            }
        }
    }

    // ── saveAssignment: 신규 INSERT ────────────────────────────────────────────

    @Test
    fun `saveAssignment - 신규 project_id 저장 시 row 가 삽입된다`() {
        val projectId = fixtureProjectUuid1
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
        val projectId = fixtureProjectUuid2
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
        val projectId = fixtureProjectUuid3
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
        val found = repository.findByProjectId(UUID.fromString("00000000-0000-0000-0000-999999999999"))

        assertThat(found).isNull()
    }

    // ── deleteByProjectId: 삭제 후 부재 ───────────────────────────────────────

    @Test
    fun `deleteByProjectId - 삭제 후 findByProjectId 가 null 반환`() {
        val projectId = fixtureProjectUuid4

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
        // projects 테이블에도 없는 UUID. FK 삭제는 assignment 가 없으면 no-op.
        repository.deleteByProjectId(nonExistentProjectUuid)
    }
}
