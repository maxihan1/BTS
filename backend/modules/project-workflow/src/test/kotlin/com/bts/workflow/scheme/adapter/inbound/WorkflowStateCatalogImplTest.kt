// WorkflowStateCatalogImpl 통합 테스트 — 스킴 할당·타입 매핑·미할당 세 시나리오

package com.bts.workflow.scheme.adapter.inbound

import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver
import com.bts.workflow.scheme.adapter.outbound.JdbcProjectLookupAdapter
import com.bts.workflow.scheme.adapter.outbound.WorkflowSchemeEventPublisher
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import com.bts.workflow.testsupport.insertWorkflowStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * [WorkflowStateCatalogImpl] 통합 테스트.
 *
 * Testcontainers PostgreSQL (tembo pgmq 이미지) 위에서 Flyway 마이그레이션을 실행한 뒤
 * 실제 DB 접근으로 [WorkflowStateCatalog.listStates] 의 세 시나리오를 검증한다.
 *
 * ## 검증 시나리오
 * - **S1 default mapping**: issueTypeKey null → default mapping 워크플로우의 전체 states 반환.
 * - **S2 explicit type mapping**: issueTypeKey 명시, 매칭 존재 → 해당 워크플로우 states 반환.
 * - **S3 type fallback to default**: issueTypeKey 명시, 명시적 매핑 없음 → default mapping 워크플로우 states 반환.
 * - **S4 unassigned project**: 스킴 미할당 프로젝트 → 빈 리스트 반환.
 * - **S5 no-default scheme**: 스킴은 있지만 default mapping 없음 → [WorkflowSchemeNoDefaultException] 전파.
 *
 * ## 트랜잭션 정책
 * [WorkflowStateCatalog.listStates] 는 Propagation.MANDATORY 이므로
 * [DataSourceTransactionManager] + [TransactionTemplate] 으로 감싼다.
 */
@Testcontainers
class WorkflowStateCatalogImplTest {
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

        private lateinit var catalog: WorkflowStateCatalog
        private lateinit var txTemplate: TransactionTemplate

        // S1/S2/S3 — software-scheme 이 배정된 프로젝트
        private lateinit var projectAssignedId: UUID
        private const val PROJECT_ASSIGNED_KEY = "CTLGASN"

        // S4 — 스킴 미할당 프로젝트
        private lateinit var projectUnassignedId: UUID
        private const val PROJECT_UNASSIGNED_KEY = "CTLGNOASN"

        // S5 — default mapping 없는 스킴이 배정된 프로젝트
        private lateinit var projectNoDefaultId: UUID
        private const val PROJECT_NO_DEFAULT_KEY = "CTLGNODFT"

        private const val ISSUE_TYPE_BUG_KEY = "bug"
        private const val ISSUE_TYPE_TASK_KEY = "task"

        @BeforeAll
        @JvmStatic
        fun setup() {
            applyMigrations()

            val dataSource =
                DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

            val txManager = DataSourceTransactionManager(dataSource)
            txTemplate = TransactionTemplate(txManager)

            val schemeRepo = WorkflowSchemeRepository(dsl)
            val assignmentRepo = ProjectWorkflowSchemeAssignmentRepository(dsl)
            val mappingRepo = SchemeIssueTypeMappingRepository(dsl)
            val eventPublisher = WorkflowSchemeEventPublisher(dsl, ObjectMapper().registerModule(JavaTimeModule()))
            val permissionResolver = AlwaysAllowWorkflowSchemePermissionResolver()
            val workflowRepo = WorkflowRepository(dsl)
            val projectLookup: ProjectLookupPort = JdbcProjectLookupAdapter(dsl)

            val schemeAppService =
                WorkflowSchemeApplicationService(
                    schemeRepo,
                    assignmentRepo,
                    mappingRepo,
                    eventPublisher,
                    permissionResolver,
                    workflowRepo,
                    issueTypeLookupPort = mockk(relaxed = true),
                    // 이 테스트는 키 해석만 잰다 — 스코프 판정은 전역으로 고정한다.
                    scopeResolver =
                        mockk {
                            every { ofScheme(any()) } returns WorkflowSchemeScope.Global
                            every { ofProjectId(any()) } returns WorkflowSchemeScope.Global
                        },
                )

            val workflowResolver =
                WorkflowResolverImpl(
                    projectLookup = projectLookup,
                    assignmentRepo = assignmentRepo,
                    mappingRepo = mappingRepo,
                    workflowRepo = workflowRepo,
                    schemeAS = schemeAppService,
                )

            catalog = WorkflowStateCatalogImpl(workflowResolver = workflowResolver)

            seedWorkflowsAndMappings()
            insertProjectFixtures()
            setupAssignments()
        }

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
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS issue_types (" +
                            "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                            "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                            "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                            "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                    )
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

        private fun seedWorkflowsAndMappings() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                // 워크플로우 seed
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflows (key, name, description) VALUES
                            ('software-default', '소프트웨어 개발 기본 워크플로우', NULL),
                            ('bug-tracking',     '버그 추적 워크플로우',            NULL),
                            ('simple',           '단순 워크플로우',                 NULL),
                            ('kanban-basic',     '칸반 기본 워크플로우',            NULL)
                        ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL DO NOTHING
                        """.trimIndent(),
                    )
                }

                // software-default states seed — open(0) / in-progress(1) / done(2)
                conn.prepareStatement("SELECT id FROM workflows WHERE key = ?").use { stmt ->
                    stmt.setString(1, "software-default")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        val wfId = rs.getObject(1) as UUID
                        insertWorkflowStatus(conn, wfId, "open", "열림", "TODO", 0)
                        insertWorkflowStatus(conn, wfId, "in-progress", "진행 중", "IN_PROGRESS", 1)
                        insertWorkflowStatus(conn, wfId, "done", "완료", "DONE", 2)
                    }
                }

                // bug-tracking states seed — 명시 타입 매핑 검증용
                conn.prepareStatement("SELECT id FROM workflows WHERE key = ?").use { stmt ->
                    stmt.setString(1, "bug-tracking")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        val wfId = rs.getObject(1) as UUID
                        insertWorkflowStatus(conn, wfId, "new", "신규", "TODO", 0)
                        insertWorkflowStatus(conn, wfId, "resolved", "해결됨", "DONE", 1)
                    }
                }

                // 표준 4 스킴 default mapping seed
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                        SELECT s.id, NULL, w.id
                        FROM workflow_schemes s
                        JOIN workflows w ON w.key = CASE s.key
                            WHEN 'software-scheme'     THEN 'software-default'
                            WHEN 'bug-tracking-scheme' THEN 'bug-tracking'
                            WHEN 'simple-scheme'       THEN 'simple'
                            WHEN 'kanban-scheme'       THEN 'kanban-basic'
                        END
                        ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING
                        """.trimIndent(),
                    )
                }

                // bug 이슈 타입은 Flyway V003 표준 seed 에 이미 존재하므로 별도 INSERT 생략.
                // software-scheme 에 bug → bug-tracking 명시 매핑 추가
                conn.prepareStatement("SELECT id FROM issue_types WHERE key = ?").use { stmt ->
                    stmt.setString(1, ISSUE_TYPE_BUG_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        val bugTypeId = rs.getLong(1)
                        conn.createStatement().use { s ->
                            s.execute(
                                """
                                INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                                SELECT s.id, $bugTypeId, w.id
                                FROM workflow_schemes s
                                JOIN workflows w ON w.key = 'bug-tracking'
                                WHERE s.key = 'software-scheme'
                                ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING
                                """.trimIndent(),
                            )
                        }
                    }
                }
            }
        }

        private fun insertProjectFixtures() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                fun insertProject(
                    uuid: String,
                    key: String,
                    name: String,
                ): UUID {
                    conn.prepareStatement(
                        "INSERT INTO projects (id, key, name) VALUES (?::uuid, ?, ?) " +
                            "ON CONFLICT DO NOTHING RETURNING id",
                    ).use { stmt ->
                        stmt.setString(1, uuid)
                        stmt.setString(2, key)
                        stmt.setString(3, name)
                        stmt.executeQuery().use { rs ->
                            return if (rs.next()) rs.getObject(1) as UUID else UUID.fromString(uuid)
                        }
                    }
                }

                projectAssignedId =
                    insertProject("00000000-0000-0000-CCCC-000000000101", PROJECT_ASSIGNED_KEY, "Catalog Assigned")
                projectUnassignedId =
                    insertProject("00000000-0000-0000-CCCC-000000000102", PROJECT_UNASSIGNED_KEY, "Catalog Unassigned")
                projectNoDefaultId =
                    insertProject("00000000-0000-0000-CCCC-000000000103", PROJECT_NO_DEFAULT_KEY, "Catalog No Default")
            }
        }

        private fun setupAssignments() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                // S1/S2/S3 — software-scheme 배정
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO project_workflow_scheme_assignments
                            (project_id, workflow_scheme_id, assigned_at, assigned_by)
                        SELECT '$projectAssignedId', s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                        FROM workflow_schemes s WHERE s.key = 'software-scheme'
                        ON CONFLICT (project_id) DO NOTHING
                        """.trimIndent(),
                    )
                }

                // S5 — default mapping 없는 커스텀 스킴 생성 후 배정
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflow_schemes (key, name, is_default)
                        VALUES ('no-default-catalog', 'No Default Catalog Scheme', false)
                        ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL DO NOTHING
                        """.trimIndent(),
                    )
                }
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO project_workflow_scheme_assignments
                            (project_id, workflow_scheme_id, assigned_at, assigned_by)
                        SELECT '$projectNoDefaultId', s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                        FROM workflow_schemes s WHERE s.key = 'no-default-catalog'
                        ON CONFLICT (project_id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    // ── S1 — issueTypeKey null → default mapping 워크플로우 states 반환 ──────────

    @Test
    fun `listStates returns all states from default mapping when issueTypeKey is null`() {
        val result: List<WorkflowStateView> =
            txTemplate.execute {
                catalog.listStates(ProjectKey(PROJECT_ASSIGNED_KEY), issueTypeKey = null)
            }!!

        // software-default 워크플로우는 open / in-progress / done 3개 상태를 가진다
        assertThat(result).hasSize(3)
        assertThat(result.map { it.key }).containsExactlyInAnyOrder("open", "in-progress", "done")
        assertThat(result.map { it.name }).containsExactlyInAnyOrder("열림", "진행 중", "완료")
    }

    // ── S2 — 명시 타입 매핑 존재 → 해당 워크플로우 states 반환 ─────────────────────

    @Test
    fun `listStates returns states from explicit type mapping when issueTypeKey matches`() {
        val result: List<WorkflowStateView> =
            txTemplate.execute {
                catalog.listStates(
                    ProjectKey(PROJECT_ASSIGNED_KEY),
                    issueTypeKey = IssueTypeKey(ISSUE_TYPE_BUG_KEY),
                )
            }!!

        // bug → bug-tracking 워크플로우: new / resolved 2개 상태
        assertThat(result).hasSize(2)
        assertThat(result.map { it.key }).containsExactlyInAnyOrder("new", "resolved")
    }

    // ── S3 — 명시 타입 매핑 없음 → default mapping fallback ─────────────────────────

    @Test
    fun `listStates falls back to default mapping when no explicit mapping for issueTypeKey`() {
        val result: List<WorkflowStateView> =
            txTemplate.execute {
                // task 타입은 software-scheme 에 명시 매핑 없음 → default(software-default) fallback
                catalog.listStates(
                    ProjectKey(PROJECT_ASSIGNED_KEY),
                    issueTypeKey = IssueTypeKey(ISSUE_TYPE_TASK_KEY),
                )
            }!!

        // software-default 상태 3개 반환
        assertThat(result).hasSize(3)
        assertThat(result.map { it.key }).containsExactlyInAnyOrder("open", "in-progress", "done")
    }

    // ── S4 — 스킴 미할당 프로젝트 → 빈 리스트 반환 ──────────────────────────────────

    @Test
    fun `listStates returns empty list when project has no scheme assignment`() {
        val result: List<WorkflowStateView> =
            txTemplate.execute {
                catalog.listStates(ProjectKey(PROJECT_UNASSIGNED_KEY), issueTypeKey = null)
            }!!

        assertThat(result).isEmpty()
    }

    // ── S5 — default mapping 없는 스킴 → WorkflowSchemeNoDefaultException 전파 ────

    @Test
    fun `listStates propagates WorkflowSchemeNoDefaultException when scheme has no default mapping`() {
        assertThatThrownBy {
            txTemplate.execute {
                catalog.listStates(ProjectKey(PROJECT_NO_DEFAULT_KEY), issueTypeKey = null)
            }
        }.isInstanceOf(WorkflowSchemeNoDefaultException::class.java)
    }

    // ── G1 — WorkflowStateView 불변 계약 — key/name 모두 비어있지 않아야 한다 ───────

    @Test
    fun `listStates returns WorkflowStateView items with non-blank key and name`() {
        val result: List<WorkflowStateView> =
            txTemplate.execute {
                catalog.listStates(ProjectKey(PROJECT_ASSIGNED_KEY), issueTypeKey = null)
            }!!

        result.forEach { view ->
            assertThat(view.key).isNotBlank()
            assertThat(view.name).isNotBlank()
        }
    }

    // ── isDone — DONE 카테고리 상태는 isDone=true, 그 외는 false ─────────────────

    @Test
    fun `listStates sets isDone=true for DONE category state and false for others`() {
        val result: List<WorkflowStateView> =
            txTemplate.execute {
                // software-default 워크플로우: open(TODO), in-progress(IN_PROGRESS), done(DONE)
                catalog.listStates(ProjectKey(PROJECT_ASSIGNED_KEY), issueTypeKey = null)
            }!!

        // done 상태는 isDone=true
        val doneState = result.first { it.key == "done" }
        assertThat(doneState.isDone).isTrue()

        // open / in-progress 상태는 isDone=false
        val nonDoneStates = result.filter { it.key != "done" }
        nonDoneStates.forEach { view ->
            assertThat(view.isDone)
                .describedAs("${view.key} 는 DONE 카테고리가 아니므로 isDone=false 이어야 한다")
                .isFalse()
        }
    }

    @Test
    fun `listStates sets isDone=true for all DONE category states in bug-tracking workflow`() {
        val result: List<WorkflowStateView> =
            txTemplate.execute {
                // bug-tracking 워크플로우: new(TODO), resolved(DONE)
                catalog.listStates(
                    ProjectKey(PROJECT_ASSIGNED_KEY),
                    issueTypeKey = IssueTypeKey(ISSUE_TYPE_BUG_KEY),
                )
            }!!

        val resolvedState = result.first { it.key == "resolved" }
        assertThat(resolvedState.isDone).isTrue()

        val newState = result.first { it.key == "new" }
        assertThat(newState.isDone).isFalse()
    }

    // ── category — 실제 StateCategory 값이 매핑되어야 한다 ───────────────────────

    @Test
    fun `listStates maps category from domain StateCategory — open=TODO, in-progress=IN_PROGRESS, done=DONE`() {
        val result: List<WorkflowStateView> =
            txTemplate.execute {
                // software-default 워크플로우: open(TODO), in-progress(IN_PROGRESS), done(DONE)
                catalog.listStates(ProjectKey(PROJECT_ASSIGNED_KEY), issueTypeKey = null)
            }!!

        assertThat(result.first { it.key == "open" }.category)
            .describedAs("open 상태는 TODO 카테고리이어야 한다")
            .isEqualTo("TODO")
        assertThat(result.first { it.key == "in-progress" }.category)
            .describedAs("in-progress 상태는 IN_PROGRESS 카테고리이어야 한다")
            .isEqualTo("IN_PROGRESS")
        assertThat(result.first { it.key == "done" }.category)
            .describedAs("done 상태는 DONE 카테고리이어야 한다 — default 값 'TODO'로 통과하면 실패")
            .isEqualTo("DONE")
    }

    // ── displayOrder — 실제 display_order 값이 매핑되어야 한다 ────────────────────

    @Test
    fun `listStates maps displayOrder from domain WorkflowState — open=0, in-progress=1, done=2`() {
        val result: List<WorkflowStateView> =
            txTemplate.execute {
                // software-default 워크플로우: open(0), in-progress(1), done(2)
                catalog.listStates(ProjectKey(PROJECT_ASSIGNED_KEY), issueTypeKey = null)
            }!!

        assertThat(result.first { it.key == "open" }.displayOrder)
            .describedAs("open 상태의 displayOrder 는 0 이어야 한다")
            .isEqualTo(0)
        assertThat(result.first { it.key == "in-progress" }.displayOrder)
            .describedAs("in-progress 상태의 displayOrder 는 1 이어야 한다")
            .isEqualTo(1)
        assertThat(result.first { it.key == "done" }.displayOrder)
            .describedAs("done 상태의 displayOrder 는 2 이어야 한다 — default 값 0 으로 통과하면 실패")
            .isEqualTo(2)
    }
}
