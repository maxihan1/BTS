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
 * - findProjectRefsByWorkflowId: workflow → scheme mapping → assignment → projects 3단 역방향 조회
 *   (다른 스킴 제외 · 스킴 미할당 워크플로우 빈 목록 · 소프트 삭제/아카이브 프로젝트 제외)
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

        /** 비-공허 짝이 매핑 행을 직접 넣고 뺄 때 쓴다. */
        lateinit var dsl: org.jooq.DSLContext

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

        // ── findProjectRefsByWorkflowId 전용 fixture ───────────────────────────
        // 기존 시나리오(fixtureProjectUuid1~4 / schemeId1~2)와 완전히 분리한다.
        // 기존 테스트가 UPSERT 로 할당을 갈아치우므로 같은 스킴을 공유하면 실행 순서에 결과가 흔들린다.
        val reverseWorkflowA: UUID = UUID.fromString("00000000-0000-0000-000a-00000000000a")
        val reverseWorkflowB: UUID = UUID.fromString("00000000-0000-0000-000b-00000000000b")

        /** 어떤 스킴 매핑에도 등장하지 않는 워크플로우 — 빈 목록 판정용. */
        val reverseWorkflowOrphan: UUID = UUID.fromString("00000000-0000-0000-000c-00000000000c")

        // reverseWorkflowA 를 가리키는 스킴 / reverseWorkflowB 를 가리키는 스킴
        var reverseSchemeA: Long = 0L
        var reverseSchemeB: Long = 0L

        val reverseProjectAlpha: UUID = UUID.fromString("00000000-0000-0000-0005-000000001005")
        val reverseProjectBeta: UUID = UUID.fromString("00000000-0000-0000-0006-000000001006")
        val reverseProjectOtherScheme: UUID = UUID.fromString("00000000-0000-0000-0007-000000001007")
        val reverseProjectDeleted: UUID = UUID.fromString("00000000-0000-0000-0008-000000001008")
        val reverseProjectArchived: UUID = UUID.fromString("00000000-0000-0000-0009-000000001009")

        /** 소프트 삭제된 스킴. 매핑 행은 그대로 남는다 — CASCADE 는 하드 삭제에만 걸린다. */
        var reverseSchemeDeleted: Long = 0

        /** 활성이지만 어느 프로젝트에도 할당되지 않은 스킴. */
        var reverseSchemeUnassigned: Long = 0

        /**
         * 삭제된 스킴에 **할당된** 프로젝트.
         *
         * ★이것이 없으면 삭제 축을 한 번도 재지 못한다 — 할당이 없는 스킴은 `join(assignments)`
         * 가 먼저 걸러 버려, `deleted_at` 조건을 지워도 전 테스트가 초록이다(뮤테이션 실측).
         * 두 조건을 동시에 만족하는 픽스처는 어느 쪽이 잡는지 구별해 주지 않는다.
         */
        val reverseProjectOnDeletedScheme: UUID = UUID.fromString("00000000-0000-0000-000d-00000000100d")

        @BeforeAll
        @JvmStatic
        fun setup() {
            applyMigrations()
            loadSchemeFixtures()
            insertProjectFixtures()
            insertReverseLookupFixtures()

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
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = ProjectWorkflowSchemeAssignmentRepository(dsl)
        }

        /** 2단계 Flyway 마이그레이션 적용. target=200 → cross-BC 스텁 → LATEST. */
        private fun applyMigrations() {
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
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
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

        /**
         * 역방향 조회 전용 fixture 한 벌.
         *
         * workflows → workflow_schemes → workflow_scheme_issue_type_mappings →
         * project_workflow_scheme_assignments → projects 순으로 FK 선행 조건을 채운다.
         */
        private fun insertReverseLookupFixtures() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                insertReverseWorkflows(conn)
                insertReverseSchemes(conn)
                insertReverseMappings(conn)
                insertReverseProjects(conn)
                insertReverseAssignments(conn)
            }
        }

        /** 대상 워크플로우 A · 다른 스킴 워크플로우 B · 스킴 미할당 orphan 3건. */
        private fun insertReverseWorkflows(conn: java.sql.Connection) {
            conn.prepareStatement("INSERT INTO workflows (id, key, name) VALUES (?, ?, ?)").use { ps ->
                listOf(
                    Triple(reverseWorkflowA, "reverse-lookup-wf-a", "역방향 조회 A"),
                    Triple(reverseWorkflowB, "reverse-lookup-wf-b", "역방향 조회 B"),
                    Triple(reverseWorkflowOrphan, "reverse-lookup-wf-orphan", "역방향 조회 orphan"),
                ).forEach { (id, key, name) ->
                    ps.setObject(1, id)
                    ps.setString(2, key)
                    ps.setString(3, name)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        /** 스킴 2건을 만들고 BIGSERIAL 로 발급된 id 를 companion 에 담는다. */
        private fun insertReverseSchemes(conn: java.sql.Connection) {
            reverseSchemeA = insertScheme(conn, "reverse-lookup-a", "역방향 스킴 A")
            reverseSchemeB = insertScheme(conn, "reverse-lookup-b", "역방향 스킴 B")

            // 형제 판정이 「매핑이 있는 모든 스킴」을 보면 이 둘이 workflowA 의 이관을 영구히 막는다.
            // 둘 다 프로젝트에 붙이지 않으므로 역방향 프로젝트 조회 결과는 달라지지 않는다.
            reverseSchemeDeleted = insertScheme(conn, "reverse-lookup-del", "삭제된 스킴")
            reverseSchemeUnassigned = insertScheme(conn, "reverse-lookup-unassigned", "미할당 스킴")
            conn.prepareStatement("UPDATE workflow_schemes SET deleted_at = NOW() WHERE id = ?").use { ps ->
                ps.setLong(1, reverseSchemeDeleted)
                ps.executeUpdate()
            }
        }

        /** workflow_schemes 1건 INSERT 후 발급된 id 를 돌려준다. */
        private fun insertScheme(
            conn: java.sql.Connection,
            key: String,
            name: String,
        ): Long =
            conn.prepareStatement(
                "INSERT INTO workflow_schemes (key, name, is_default) VALUES (?, ?, FALSE) RETURNING id",
            ).use { ps ->
                ps.setString(1, key)
                ps.setString(2, name)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }

        /**
         * 스킴 → 워크플로우 매핑.
         *
         * 스킴 A 는 default(issue_type_id NULL) 와 특정 이슈 타입 매핑 **둘 다** workflowA 를 가리킨다.
         * 이러면 JOIN 이 프로젝트당 2행을 만들어내므로 DISTINCT 가 빠지면 테스트 1 이 깨진다
         * (DISTINCT 를 장식이 아니라 판정 대상으로 만든다).
         */
        private fun insertReverseMappings(conn: java.sql.Connection) {
            val issueTypeId =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT id FROM issue_types ORDER BY id LIMIT 1").use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            conn.prepareStatement(
                "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)" +
                    " VALUES (?, ?, ?)",
            ).use { ps ->
                listOf(
                    Triple(reverseSchemeA, null, reverseWorkflowA),
                    Triple(reverseSchemeA, issueTypeId, reverseWorkflowA),
                    Triple(reverseSchemeB, null, reverseWorkflowB),
                    // 삭제된 스킴 · 미할당 스킴이 각각 workflowA 와 **형제**를 함께 매핑한다.
                    Triple(reverseSchemeDeleted, null, reverseWorkflowA),
                    Triple(reverseSchemeDeleted, issueTypeId, reverseWorkflowB),
                    Triple(reverseSchemeUnassigned, null, reverseWorkflowA),
                    Triple(reverseSchemeUnassigned, issueTypeId, reverseWorkflowB),
                ).forEach { (schemeId, typeId, workflowId) ->
                    ps.setLong(1, schemeId)
                    if (typeId == null) {
                        ps.setNull(2, java.sql.Types.BIGINT)
                    } else {
                        ps.setLong(2, typeId)
                    }
                    ps.setObject(3, workflowId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        /** 활성 2건 · 다른 스킴 1건 · 소프트 삭제 1건 · 아카이브 1건. */
        private fun insertReverseProjects(conn: java.sql.Connection) {
            conn.prepareStatement(
                "INSERT INTO projects (id, key, name, deleted_at, archived_at) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                val now = java.sql.Timestamp.from(Instant.parse("2026-08-01T00:00:00Z"))
                listOf(
                    ReverseProjectFixture(reverseProjectAlpha, "R001", "역방향 Alpha", null, null),
                    ReverseProjectFixture(reverseProjectBeta, "R002", "역방향 Beta", null, null),
                    ReverseProjectFixture(reverseProjectOtherScheme, "R003", "역방향 Gamma", null, null),
                    ReverseProjectFixture(reverseProjectDeleted, "R004", "역방향 Delta", now, null),
                    ReverseProjectFixture(reverseProjectArchived, "R005", "역방향 Epsilon", null, now),
                    ReverseProjectFixture(reverseProjectOnDeletedScheme, "R006", "역방향 Zeta", null, null),
                ).forEach { fixture ->
                    ps.setObject(1, fixture.id)
                    ps.setString(2, fixture.key)
                    ps.setString(3, fixture.name)
                    ps.setTimestamp(4, fixture.deletedAt)
                    ps.setTimestamp(5, fixture.archivedAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        /** 프로젝트 → 스킴 할당. Gamma 만 스킴 B, 나머지는 전부 스킴 A. */
        private fun insertReverseAssignments(conn: java.sql.Connection) {
            conn.prepareStatement(
                "INSERT INTO project_workflow_scheme_assignments" +
                    " (project_id, workflow_scheme_id, assigned_by) VALUES (?, ?, ?)",
            ).use { ps ->
                listOf(
                    reverseProjectOnDeletedScheme to reverseSchemeDeleted,
                    reverseProjectAlpha to reverseSchemeA,
                    reverseProjectBeta to reverseSchemeA,
                    reverseProjectOtherScheme to reverseSchemeB,
                    reverseProjectDeleted to reverseSchemeA,
                    reverseProjectArchived to reverseSchemeA,
                ).forEach { (projectId, schemeId) ->
                    ps.setObject(1, projectId)
                    ps.setLong(2, schemeId)
                    ps.setObject(3, UUID.fromString("00000000-0000-0000-00ff-0000000000ff"))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /** 역방향 조회 fixture 의 projects row 한 건. */
    private data class ReverseProjectFixture(
        val id: UUID,
        val key: String,
        val name: String,
        val deletedAt: java.sql.Timestamp?,
        val archivedAt: java.sql.Timestamp?,
    )

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

    // ── findProjectRefsByWorkflowId: workflow → scheme → assignment → project 역방향 ──

    @Test
    fun `워크플로우를 쓰는 스킴에 할당된 프로젝트를 전부 돌려준다`() {
        val refs = repository.findProjectRefsByWorkflowId(reverseWorkflowA)

        // 스킴 A 는 workflowA 를 매핑 2건(default + 이슈 타입)으로 가리키므로
        // DISTINCT 가 없으면 프로젝트마다 2행이 나와 이 단언이 깨진다.
        assertThat(refs).containsExactlyInAnyOrder(
            ProjectRef(reverseProjectAlpha, "R001"),
            ProjectRef(reverseProjectBeta, "R002"),
        )
    }

    @Test
    fun `다른 스킴에만 있는 프로젝트는 안 나온다`() {
        val refs = repository.findProjectRefsByWorkflowId(reverseWorkflowA)

        // Gamma 는 스킴 B(→ workflowB) 소속이다.
        assertThat(refs.map { it.id }).doesNotContain(reverseProjectOtherScheme)
    }

    @Test
    fun `스킴에 할당되지 않은 워크플로우는 빈 집합이다`() {
        val refs = repository.findProjectRefsByWorkflowId(reverseWorkflowOrphan)

        assertThat(refs).isEmpty()
    }

    @Test
    fun `소프트 삭제된 프로젝트는 제외한다`() {
        val refs = repository.findProjectRefsByWorkflowId(reverseWorkflowA)

        // Delta 는 스킴 A 에 할당돼 있지만 deleted_at 이 채워져 있다.
        assertThat(refs.map { it.id }).doesNotContain(reverseProjectDeleted)
    }

    @Test
    fun `아카이브된 프로젝트는 제외한다`() {
        val refs = repository.findProjectRefsByWorkflowId(reverseWorkflowA)

        // Epsilon 은 스킴 A 에 할당돼 있지만 archived_at 이 채워져 있다 (스펙 E7).
        assertThat(refs.map { it.id }).doesNotContain(reverseProjectArchived)
    }

    // ── 이관 범위 조회 (E7 제3의 길) ────────────────────────────────────────────

    /**
     * ★ 이관 **범위**는 아카이브를 포함한다 — 카운트와 다른 집합이다 (spec E7 · 게이트 1 I1).
     *
     * ### 왜 범위에는 넣는가
     * 범위에서까지 빼면 워커가 그 프로젝트를 아예 안 보고, 그 이슈들은 **흔적 없이 사라진다.**
     * 넣어 두면 워커의 `BulkItemApplier` 가 아카이브 가드에 걸려 `bulk_operation_items` 에
     * `PROJECT_ARCHIVED` 로 남기므로(`BulkItemExecutor` 의 `ProjectArchivedException` 매핑)
     * 관리자가 「몇 건이 왜 안 옮겨졌는지」를 셀 수 있다.
     *
     * ### 왜 카운트에는 안 넣는가
     * 아카이브 이슈는 영원히 안 옮겨진다. 발행 차단 카운트에 넣으면 발행이 **관리자가 풀 수 없는
     * 상태로** 막힌다. 그래서 [ProjectWorkflowSchemeAssignmentRepository.findProjectRefsByWorkflowId]
     * 는 지금처럼 아카이브를 계속 제외한다 — 두 조회는 일부러 다른 집합이다.
     */
    @Test
    fun `이관 범위 조회는 아카이브된 프로젝트를 포함한다`() {
        val refs = repository.findMigrationScopeRefsByWorkflowId(reverseWorkflowA)

        assertThat(refs.map { it.id })
            .describedAs("범위에서 빠지면 워커가 그 프로젝트를 안 보고 이슈가 흔적 없이 사라진다")
            .contains(reverseProjectArchived)
        assertThat(refs.map { it.id }).contains(reverseProjectAlpha, reverseProjectBeta)
    }

    /**
     * ★ 소프트 삭제된 **스킴**에 붙어 있던 프로젝트는 어느 조회에도 안 나온다.
     *
     * `softDelete` 는 `deleted_at` 만 세우고 할당·매핑 행을 남긴다. 그것을 안 보면 지운 스킴의
     * 프로젝트가 계속 잡혀 **발행 차단 카운트가 과다 집계**되고(그 프로젝트는 이 워크플로우를
     * 더 이상 쓰지 않는다) 이관도 남의 프로젝트를 범위에 싣는다.
     */
    @Test
    fun `소프트 삭제된 스킴에 할당된 프로젝트는 카운트에도 범위에도 안 나온다`() {
        assertThat(repository.findProjectRefsByWorkflowId(reverseWorkflowA).map { it.id })
            .doesNotContain(reverseProjectOnDeletedScheme)
        assertThat(repository.findMigrationScopeRefsByWorkflowId(reverseWorkflowA).map { it.id })
            .describedAs("지운 스킴의 프로젝트를 범위에 실으면 남의 이슈를 옮긴다")
            .doesNotContain(reverseProjectOnDeletedScheme)
    }

    /** 소프트 삭제는 범위에서도 뺀다 — 죽은 프로젝트의 이슈를 옮길 이유가 없다. */
    @Test
    fun `이관 범위 조회도 소프트 삭제된 프로젝트는 제외한다`() {
        val refs = repository.findMigrationScopeRefsByWorkflowId(reverseWorkflowA)

        assertThat(refs.map { it.id }).doesNotContain(reverseProjectDeleted)
    }

    /** 다른 스킴만 쓰는 프로젝트는 범위에도 안 들어간다 — 스코프 판정이 공허해지지 않게 한다. */
    @Test
    fun `이관 범위 조회도 다른 스킴에만 있는 프로젝트는 안 나온다`() {
        val refs = repository.findMigrationScopeRefsByWorkflowId(reverseWorkflowA)

        assertThat(refs.map { it.id }).doesNotContain(reverseProjectOtherScheme)
    }

    // ── 형제 워크플로우 판정 범위 (F11 과차단 방지) ────────────────────────────

    /**
     * ★ 형제 판정은 **살아 있고 실제로 쓰이는 스킴**만 본다.
     *
     * 이 판정이 「매핑이 있는 모든 스킴」을 보면, 예전에 만들었다 지운 스킴 하나가 그 워크플로우의
     * 이관을 **영구히 400 으로** 막는다. `WorkflowSchemeRepository.softDelete` 는 `deleted_at` 만
     * 세우고 매핑 행은 남기며(V201 의 CASCADE 는 하드 삭제에만 걸린다), 관리자가 손댈 수 있는
     * 활성 스킴에는 형제가 없어 **화면에서 원인을 찾을 방법이 없다.**
     *
     * 안전성은 줄지 않는다 — 이관이 옮기는 것은 **범위 프로젝트의 이슈**이고, 그 프로젝트가 붙은
     * 스킴에서만 형제 워크플로우와 섞일 수 있다.
     */
    @Test
    fun `소프트 삭제된 스킴의 잔존 매핑은 형제로 세지 않는다`() {
        assertThat(repository.hasSiblingWorkflowInAssignedSchemes(reverseWorkflowA))
            .describedAs("지운 스킴 하나가 이관을 영구히 막으면 출구가 없다")
            .isFalse()
    }

    /**
     * ★ 프로젝트에 할당되지 않은 스킴도 형제로 세지 않는다.
     *
     * 할당이 없으면 그 스킴을 쓰는 이슈가 하나도 없다 — 이관이 그 워크플로우의 이슈를 건드릴
     * 경로 자체가 없으므로 막을 이유가 없다.
     *
     * 이 판정과 위 판정은 **다른 축**이다(활성 여부 · 할당 여부). 하나만 두면 다른 쪽 구멍이
     * 조용히 남는다.
     */
    @Test
    fun `프로젝트에 할당되지 않은 스킴의 매핑은 형제로 세지 않는다`() {
        assertThat(repository.hasSiblingWorkflowInAssignedSchemes(reverseWorkflowA)).isFalse()
    }

    /**
     * ★비-공허 짝 — 좁히기가 판정을 통째로 죽이지 않았는지 반대 방향으로 확인한다.
     *
     * 이것이 없으면 `hasSiblingWorkflowInAssignedSchemes` 가 **항상 false** 를 돌려줘도 위 두
     * 테스트가 초록이다. F11 fail-closed 가 통째로 사라지는데 아무도 모르는 형태가 된다.
     */
    @Test
    fun `할당된 활성 스킴에 형제가 있으면 참이다`() {
        // Gamma 가 붙은 스킴 B 에 형제를 끼운다 — B 는 활성이고 할당도 있다.
        //
        // ★`issue_type_id` 를 NULL 로 넣으면 안 된다. `ix_scheme_default_mapping` partial UNIQUE 가
        //   스킴당 default 를 1건으로 막아 INSERT 가 조용히 무시되고, 형제가 안 들어간 채 판정만
        //   false 가 된다 — 실제로 이 픽스처가 그렇게 한 번 헛돌았고 비-공허 짝이 그것을 잡았다.
        val siblingTypeId =
            dsl
                .fetchOne("SELECT id FROM issue_types ORDER BY id LIMIT 1")
                ?.get("id", Long::class.java)
                ?: error("issue_types 가 비어 있다 — 형제 픽스처를 심을 수 없다")
        dsl.execute(
            "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)" +
                " VALUES (?, ?, ?)",
            reverseSchemeB,
            siblingTypeId,
            reverseWorkflowOrphan,
        )
        try {
            assertThat(repository.hasSiblingWorkflowInAssignedSchemes(reverseWorkflowB))
                .describedAs("좁히기가 과해 판정이 통째로 죽으면 F11 fail-closed 가 사라진다")
                .isTrue()
        } finally {
            dsl.execute(
                "DELETE FROM workflow_scheme_issue_type_mappings WHERE scheme_id = ? AND workflow_id = ?",
                reverseSchemeB,
                reverseWorkflowOrphan,
            )
        }
    }
}
