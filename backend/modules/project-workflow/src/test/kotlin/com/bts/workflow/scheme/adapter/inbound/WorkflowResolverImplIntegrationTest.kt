// WorkflowResolverImpl 통합 테스트 — S5 matching mapping / EC-2 default fallback / EC-1 D10 auto-assign / EC-7 ProjectNotFoundException

package com.bts.workflow.scheme.adapter.inbound

import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.domain.Workflow
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver
import com.bts.workflow.scheme.adapter.outbound.JdbcProjectLookupAdapter
import com.bts.workflow.scheme.adapter.outbound.WorkflowSchemeEventPublisher
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.exception.ProjectNotFoundException
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import com.bts.workflow.scheme.port.outbound.WorkflowResolver
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
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * WorkflowResolverImpl 통합 테스트.
 *
 * Testcontainers PostgreSQL (tembo pgmq 이미지) 위에서 Flyway 마이그레이션을 실행한 뒤
 * 실제 DB 접근으로 WorkflowResolverImpl 의 4 시나리오를 검증한다.
 *
 * ## 검증 시나리오
 * - **S5**: assignment 있음 + issueTypeKey 매치 → 해당 workflow 반환
 * - **EC-2**: assignment 있음 + issueTypeKey 매치 없음 → default mapping 의 workflow 반환
 * - **EC-1 D10**: assignment 없음 → software-scheme 자동 배정 후 default mapping workflow 반환
 * - **EC-7**: 무효 projectKey → [ProjectNotFoundException] 발생
 *
 * ## 트랜잭션 정책
 * [WorkflowResolver.resolveFor] 는 Propagation.MANDATORY 이므로,
 * 테스트에서 [org.springframework.transaction.PlatformTransactionManager] 없이
 * plain [org.springframework.jdbc.datasource.DataSourceTransactionManager] +
 * [TransactionTemplate] 으로 감싼다.
 *
 * ## 마이그레이션 순서 (2단계)
 * 1. Flyway target=200 → issue_types 스텁 → LATEST (ProjectWorkflowSchemeAssignmentRepositoryIntegrationTest 패턴 동일)
 * 2. workflows / issue_types seed 직접 INSERT (YamlSeedService 는 Spring 없이 미동작)
 */
@Testcontainers
class WorkflowResolverImplIntegrationTest {
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

        private lateinit var resolver: WorkflowResolver
        private lateinit var txTemplate: TransactionTemplate

        // fixture — 시나리오별 프로젝트 UUID + key
        private lateinit var projectS5Id: UUID
        private const val PROJECT_S5_KEY = "PROJFIVE"

        private lateinit var projectEc2Id: UUID
        private const val PROJECT_EC2_KEY = "PROJTWO"

        private lateinit var projectEc1Id: UUID
        private const val PROJECT_EC1_KEY = "PROJONE"

        // EC-7 용 — DB 에 존재하지 않는 프로젝트 키
        private const val PROJECT_EC7_KEY = "NOEXIST"

        // 시나리오에 사용할 issue type key fixture
        private const val ISSUE_TYPE_STORY_KEY = "story"
        private const val ISSUE_TYPE_UNKNOWN_KEY = "unknown-type"

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

            val txManager = org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)
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

            resolver =
                WorkflowResolverImpl(
                    projectLookup = projectLookup,
                    assignmentRepo = assignmentRepo,
                    mappingRepo = mappingRepo,
                    workflowRepo = workflowRepo,
                    schemeAS = schemeAppService,
                )

            seedWorkflowsAndMappings()
            insertProjectFixtures()
            setupS5Assignment()
            setupEc2Assignment()
        }

        private fun applyMigrations() {
            // 1단계: V200 까지 적용 (issue-tracking cross-BC dep 으로 V001~V003 도 함께)
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

            // issue_types / projects 스텁 — FK 통과용 (이미 V003/V001 로 생성됐으면 no-op)
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

            // 2단계: V201+ LATEST 적용
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
                // 4 표준 workflow seed
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

                // workflow_states — software-default 워크플로우에 최소 1개 state 필요
                conn.prepareStatement(
                    "SELECT id FROM workflows WHERE key = ?",
                ).use { stmt ->
                    stmt.setString(1, "software-default")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        val wfId = rs.getObject(1) as UUID
                        insertWorkflowStatus(conn, wfId, "open", "열림", "TODO", 0)
                        insertWorkflowStatus(conn, wfId, "in-progress", "진행 중", "IN_PROGRESS", 1)
                        insertWorkflowStatus(conn, wfId, "done", "완료", "DONE", 2)
                    }
                }

                // 4 default mapping seed
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

                // S5: story issueType 전용 매핑 추가 (software-scheme + story issue_type)
                // issue_types 에서 story id 조회
                val storyId =
                    conn.prepareStatement(
                        "SELECT id FROM issue_types WHERE key = ?",
                    ).use { stmt ->
                        stmt.setString(1, ISSUE_TYPE_STORY_KEY)
                        stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                    }

                if (storyId != null) {
                    // software-scheme + story 조합의 명시적 매핑 추가 (software-default 워크플로우 사용)
                    conn.createStatement().use { stmt ->
                        stmt.execute(
                            """
                            INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                            SELECT s.id, $storyId, w.id
                            FROM workflow_schemes s
                            JOIN workflows w ON w.key = 'software-default'
                            WHERE s.key = 'software-scheme'
                            ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING
                            """.trimIndent(),
                        )
                    }
                }
            }
        }

        private fun insertProjectFixtures() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO projects (id, key, name) VALUES (?::uuid, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                ).use { stmt ->
                    val s5Uuid = "00000000-0000-0000-0005-000000005001"
                    stmt.setString(1, s5Uuid)
                    stmt.setString(2, PROJECT_S5_KEY)
                    stmt.setString(3, "Project S5")
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            projectS5Id = rs.getObject(1) as UUID
                        } else {
                            projectS5Id = UUID.fromString(s5Uuid)
                        }
                    }
                }

                conn.prepareStatement(
                    "INSERT INTO projects (id, key, name) VALUES (?::uuid, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                ).use { stmt ->
                    val ec2Uuid = "00000000-0000-0000-0002-000000002001"
                    stmt.setString(1, ec2Uuid)
                    stmt.setString(2, PROJECT_EC2_KEY)
                    stmt.setString(3, "Project EC2")
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            projectEc2Id = rs.getObject(1) as UUID
                        } else {
                            projectEc2Id = UUID.fromString(ec2Uuid)
                        }
                    }
                }

                conn.prepareStatement(
                    "INSERT INTO projects (id, key, name) VALUES (?::uuid, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                ).use { stmt ->
                    val ec1Uuid = "00000000-0000-0000-0001-000000001001"
                    stmt.setString(1, ec1Uuid)
                    stmt.setString(2, PROJECT_EC1_KEY)
                    stmt.setString(3, "Project EC1")
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            projectEc1Id = rs.getObject(1) as UUID
                        } else {
                            projectEc1Id = UUID.fromString(ec1Uuid)
                        }
                    }
                }
            }
        }

        private fun setupS5Assignment() {
            // S5 프로젝트에 software-scheme 할당 (이미 assignment 있는 상태)
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                        SELECT '$projectS5Id', s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                        FROM workflow_schemes s
                        WHERE s.key = 'software-scheme'
                        ON CONFLICT (project_id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
        }

        private fun setupEc2Assignment() {
            // EC-2 프로젝트에도 software-scheme 할당 (assignment 있지만 issueTypeKey 매치 안 되는 시나리오)
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                        SELECT '$projectEc2Id', s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                        FROM workflow_schemes s
                        WHERE s.key = 'software-scheme'
                        ON CONFLICT (project_id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    // ── S5 — assignment 있음 + issueTypeKey 매치 → 해당 workflow 반환 ────────────

    @Test
    fun `S5 - assignment 있고 issueTypeKey 가 story 매핑에 매치되면 해당 workflow 를 반환한다`() {
        val result: Workflow =
            txTemplate.execute {
                resolver.resolveFor(ProjectKey(PROJECT_S5_KEY), IssueTypeKey(ISSUE_TYPE_STORY_KEY))
            }!!

        assertThat(result).isNotNull
        assertThat(result.key).isEqualTo("software-default")
    }

    // ── EC-2 — issueTypeKey 매치 없음 → default mapping fallback ────────────────

    @Test
    fun `EC-2 - assignment 있고 issueTypeKey 매핑 없으면 default mapping workflow 를 반환한다`() {
        val result: Workflow =
            txTemplate.execute {
                resolver.resolveFor(ProjectKey(PROJECT_EC2_KEY), IssueTypeKey(ISSUE_TYPE_UNKNOWN_KEY))
            }!!

        assertThat(result).isNotNull
        assertThat(result.key).isEqualTo("software-default")
    }

    // ── EC-1 D10 — assignment 없음 → software-scheme 자동 배정 후 workflow 반환 ──

    @Test
    fun `EC-1 D10 - assignment 없는 프로젝트는 software-scheme 자동 배정 후 default workflow 를 반환한다`() {
        val result: Workflow =
            txTemplate.execute {
                resolver.resolveFor(ProjectKey(PROJECT_EC1_KEY), null)
            }!!

        assertThat(result).isNotNull
        assertThat(result.key).isEqualTo("software-default")
    }

    // ── EC-7 — 무효 projectKey → ProjectNotFoundException ────────────────────────

    @Test
    fun `EC-7 - 존재하지 않는 projectKey 는 ProjectNotFoundException 을 던진다`() {
        assertThatThrownBy {
            txTemplate.execute {
                resolver.resolveFor(ProjectKey(PROJECT_EC7_KEY), null)
            }
        }.isInstanceOf(ProjectNotFoundException::class.java)
    }
}
