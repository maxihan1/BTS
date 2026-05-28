// WorkflowKeyResolverImpl 통합 테스트 — EC-1 auto-assign / S5 explicit / EC-2 no-default / EC-8 concurrent race

package com.bts.workflow.scheme.adapter.inbound

import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * [WorkflowKeyResolverImpl] 통합 테스트.
 *
 * Testcontainers PostgreSQL (tembo pgmq 이미지) 위에서 Flyway 마이그레이션을 실행한 뒤
 * 실제 DB 접근으로 [WorkflowKeyResolver.resolveStart] 의 4 시나리오를 검증한다.
 *
 * ## 검증 시나리오
 * - **S5 explicit**: assignment 있음 + issueTypeKey 매치 → 해당 workflow + 시작 상태 반환
 * - **EC-2 no-default**: default mapping 부재 시 [WorkflowSchemeNoDefaultException] 발생
 * - **EC-1 auto-assign**: assignment 없는 신규 프로젝트 → software-scheme 자동 배정 후 시작 상태 반환
 * - **EC-8 race**: 동시 2 스레드 auto-assign → idempotent (1회 배정, 예외 없음)
 *
 * ## 트랜잭션 정책
 * [WorkflowKeyResolver.resolveStart] 는 Propagation.MANDATORY 이므로
 * [DataSourceTransactionManager] + [TransactionTemplate] 으로 감싼다.
 *
 * ## 마이그레이션 순서 (WorkflowResolverImplIntegrationTest 패턴 동일)
 * 1. Flyway target=200 → issue_types/projects 스텁 → LATEST
 * 2. workflows / workflow_states / scheme 매핑 seed 직접 INSERT
 */
@Testcontainers
class WorkflowKeyResolverImplIntegrationTest {
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

        private lateinit var resolver: WorkflowKeyResolver
        private lateinit var txTemplate: TransactionTemplate

        // S5 시나리오 — assignment 있음 + story issueType 명시적 매핑
        private lateinit var projectS5Id: UUID
        private const val PROJECT_S5_KEY = "KEYRSLVS5"

        // EC-1 시나리오 — assignment 없는 신규 프로젝트 (auto-assign 대상)
        private lateinit var projectEc1Id: UUID
        private const val PROJECT_EC1_KEY = "KEYRSLVEC1"

        // EC-8 시나리오 — concurrent auto-assign race (별도 프로젝트)
        private lateinit var projectEc8Id: UUID
        private const val PROJECT_EC8_KEY = "KEYRSLVEC8"

        // EC-2 시나리오용 — default mapping 이 없는 커스텀 스킴에 배정된 프로젝트
        private lateinit var projectEc2Id: UUID
        private const val PROJECT_EC2_KEY = "KEYRSLVEC2"

        private const val ISSUE_TYPE_STORY_KEY = "story"
        private const val ISSUE_TYPE_UNKNOWN_KEY = "unknown-key-type"

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
                )

            val workflowResolver =
                WorkflowResolverImpl(
                    projectLookup = projectLookup,
                    assignmentRepo = assignmentRepo,
                    mappingRepo = mappingRepo,
                    workflowRepo = workflowRepo,
                    schemeAS = schemeAppService,
                )

            resolver = WorkflowKeyResolverImpl(workflowResolver = workflowResolver)

            seedWorkflowsAndMappings()
            insertProjectFixtures()
            setupS5Assignment()
            setupEc2SchemeAndAssignment()
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
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflows (key, name, description) VALUES
                            ('software-default', '소프트웨어 개발 기본 워크플로우', NULL),
                            ('bug-tracking',     '버그 추적 워크플로우',            NULL),
                            ('simple',           '단순 워크플로우',                 NULL),
                            ('kanban-basic',     '칸반 기본 워크플로우',            NULL)
                        ON CONFLICT (key) DO NOTHING
                        """.trimIndent(),
                    )
                }

                // software-default workflow states seed (displayOrder 0 = open)
                conn.prepareStatement("SELECT id FROM workflows WHERE key = ?").use { stmt ->
                    stmt.setString(1, "software-default")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        val wfId = rs.getObject(1) as UUID
                        conn.createStatement().use { s ->
                            s.execute(
                                """
                                INSERT INTO workflow_states (workflow_id, key, name, category, display_order) VALUES
                                    ('$wfId', 'open',        '열림',   'TODO',        0),
                                    ('$wfId', 'in-progress', '진행 중', 'IN_PROGRESS', 1),
                                    ('$wfId', 'done',        '완료',   'DONE',        2)
                                ON CONFLICT DO NOTHING
                                """.trimIndent(),
                            )
                        }
                    }
                }

                // 표준 4 스킴의 default mapping (issue_type_id IS NULL) seed
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

                // S5: story issueType 에 대한 명시적 매핑 추가 (software-scheme + story)
                val storyId =
                    conn.prepareStatement("SELECT id FROM issue_types WHERE key = ?").use { stmt ->
                        stmt.setString(1, ISSUE_TYPE_STORY_KEY)
                        stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                    }

                if (storyId != null) {
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
                fun insertProject(
                    uuid: String,
                    key: String,
                    name: String,
                ): UUID {
                    conn.prepareStatement(
                        "INSERT INTO projects (id, key, name) VALUES (?::uuid, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                    ).use { stmt ->
                        stmt.setString(1, uuid)
                        stmt.setString(2, key)
                        stmt.setString(3, name)
                        stmt.executeQuery().use { rs ->
                            return if (rs.next()) rs.getObject(1) as UUID else UUID.fromString(uuid)
                        }
                    }
                }

                projectS5Id = insertProject("00000000-0000-0000-0055-000000005501", PROJECT_S5_KEY, "KeyResolver S5")
                projectEc1Id = insertProject("00000000-0000-0000-0011-000000001101", PROJECT_EC1_KEY, "KeyResolver EC1")
                projectEc8Id = insertProject("00000000-0000-0000-0088-000000008801", PROJECT_EC8_KEY, "KeyResolver EC8")
                projectEc2Id = insertProject("00000000-0000-0000-0022-000000002201", PROJECT_EC2_KEY, "KeyResolver EC2")
            }
        }

        private fun setupS5Assignment() {
            // S5 프로젝트에 software-scheme 미리 배정
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                        SELECT '$projectS5Id', s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                        FROM workflow_schemes s WHERE s.key = 'software-scheme'
                        ON CONFLICT (project_id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
        }

        private fun setupEc2SchemeAndAssignment() {
            // EC-2: default mapping 이 없는 커스텀 스킴 생성 후 EC-2 프로젝트에 배정
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                // 커스텀 스킴 생성 (default mapping 없음)
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflow_schemes (key, name, is_default)
                        VALUES ('no-default-scheme', 'No Default Scheme', false)
                        ON CONFLICT (key) DO NOTHING
                        """.trimIndent(),
                    )
                }

                // EC-2 프로젝트에 no-default-scheme 배정
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                        SELECT '$projectEc2Id', s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                        FROM workflow_schemes s WHERE s.key = 'no-default-scheme'
                        ON CONFLICT (project_id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    // ── S5 — explicit assignment + issueTypeKey match → WorkflowStartState 반환 ──

    @Test
    fun `resolveStart returns mapped workflow + start state for explicit scheme assignment`() {
        val result: WorkflowStartState =
            txTemplate.execute {
                resolver.resolveStart(ProjectKey(PROJECT_S5_KEY), IssueTypeKey(ISSUE_TYPE_STORY_KEY))
            }!!

        assertThat(result.workflowKey).isEqualTo("software-default")
        assertThat(result.startStateKey).isNotBlank()
    }

    // ── EC-2 — default mapping 부재 → WorkflowSchemeNoDefaultException ───────────

    @Test
    fun `resolveStart throws WorkflowSchemeNoDefaultException when default mapping absent (EC-2)`() {
        assertThatThrownBy {
            txTemplate.execute {
                resolver.resolveStart(ProjectKey(PROJECT_EC2_KEY), IssueTypeKey(ISSUE_TYPE_UNKNOWN_KEY))
            }
        }.isInstanceOf(WorkflowSchemeNoDefaultException::class.java)
    }

    // ── EC-1 auto-assign — assignment 없는 신규 프로젝트 → software-default 반환 ──

    @Test
    fun `resolveStart returns software-default + open for new project (EC-1 auto-assign)`() {
        val result: WorkflowStartState =
            txTemplate.execute {
                resolver.resolveStart(ProjectKey(PROJECT_EC1_KEY), null)
            }!!

        assertThat(result.workflowKey).isEqualTo("software-default")
        assertThat(result.startStateKey).isEqualTo("open")
    }

    // ── EC-8 — 동시 2 스레드 auto-assign race → idempotent ───────────────────────

    @Test
    fun `resolveStart is idempotent under concurrent auto-assign (EC-8 — 2 thread race)`() {
        val threadCount = 2
        val latch = CountDownLatch(1)
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                try {
                    latch.await()
                    txTemplate.execute {
                        resolver.resolveStart(ProjectKey(PROJECT_EC8_KEY), null)
                    }
                    successCount.incrementAndGet()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    errorCount.incrementAndGet()
                }
            }
        }

        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)

        // 두 스레드 모두 정상 응답해야 한다 (idempotent — 중복 insert는 ON CONFLICT 처리)
        assertThat(successCount.get()).isEqualTo(threadCount)
        assertThat(errorCount.get()).isEqualTo(0)
    }
}
