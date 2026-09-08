// 전환 resolution 영속 + clear + 존재성 검증 통합 테스트 — FR-IS-07 Task B6

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueController
import com.bts.issue.adapter.inbound.rest.IssueExceptionHandler
import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.testsupport.insertWorkflowStatus
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.user.UserLookupPort
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.engine.WorkflowDefinitionRepository
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.engine.WorkflowPostActionFactory
import com.bts.workflow.engine.WorkflowValidatorFactory
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import com.bts.workflow.scheme.adapter.inbound.WorkflowResolverImpl
import com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver
import com.bts.workflow.scheme.adapter.outbound.JdbcProjectLookupAdapter
import com.bts.workflow.scheme.adapter.outbound.WorkflowSchemeEventPublisher
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.application.port.IssueTypeLookupPort
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * 이슈 전환 시 resolution 영속 · clear · 존재성 검증 통합 테스트.
 *
 * Testcontainers PostgreSQL + 두 BC(issue-tracking, project-workflow) 전체 스택 wire.
 * 단위 mock 으로 검출 불가한 DB 영속/clear/존재성을 실제 Postgres 에서 검증한다.
 *
 * ## 검증 시나리오 (B6 명세)
 *
 * ### S1. DONE 전환 + resolutionId → resolution_id 영속
 * Given  이슈 currentStateKey = "open" (in_progress 거쳐 done 까지 2단계)
 * When   POST /api/v1/issues/{key}/transition { toStateKey: "done", resolutionId: <고정 UUID> }
 * Then   200 OK + DB issues.resolution_id == 제공한 UUID
 *
 * ### S3. done → open 재전환 (resolutionId=null) → resolution_id null clear
 * Given  이슈 currentStateKey = "done", resolution_id = 고정 UUID
 * When   POST /api/v1/issues/{key}/transition { toStateKey: "open" } (resolutionId 미제공)
 * Then   200 OK + DB issues.resolution_id IS NULL
 *
 * ### S4. 존재하지 않는 resolutionId → 404 RESOLUTION_NOT_FOUND
 * Given  이슈 currentStateKey = "open"
 * When   POST /api/v1/issues/{key}/transition { toStateKey: "done", resolutionId: <위조 UUID> }
 * Then   404 + errorCode == "RESOLUTION_NOT_FOUND"
 *
 * ## 워크플로우 시드
 * open → in_progress → done (3-상태 소프트웨어 단순 흐름).
 * done → open 역방향 전환 추가 (S3 재전환 검증).
 * B7(DONE 진입 시 resolution 필수 validator) 은 이 테스트에서 시드하지 않는다.
 * 따라서 S1 에서 DONE 전환이 resolutionId 없이도 통과하는 것은 정상 (B7 이전 상태).
 *
 * ## 마이그레이션
 * issue-tracking V001~V011 + project-workflow V200~V202 순차 적용.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueTransitionResolutionIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueTransitionResolutionIntegrationTest {
    // ── Spring Bean 구성 ────────────────────────────────────────────────────────

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            /** JVM 단위 singleton Testcontainers — singleton pattern */
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_res_it_test")
                    .withUsername("bts")
                    .withPassword("bts_res_it_test")
                    .apply { start() }
        }

        @Bean
        open fun dataSource(): DriverManagerDataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )

        @Bean
        open fun transactionManager(dataSource: DriverManagerDataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        @Suppress("MaxLineLength")
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())

        // ── issue-tracking 빈 ──────────────────────────────────────────────────

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

        @Bean
        open fun issueTypeRepository(dsl: DSLContext): IssueTypeRepository = IssueTypeRepository(dsl)

        @Bean
        open fun resolutionRepository(dsl: DSLContext): ResolutionRepository = ResolutionRepository(dsl)

        @Bean
        open fun issueEventPublisher(
            dsl: DSLContext,
            objectMapper: ObjectMapper,
        ): IssueEventPublisher = IssueEventPublisher(dsl, objectMapper)

        @Bean
        @Profile("test")
        open fun alwaysAllowIssuePermissionResolver() = AlwaysAllowIssuePermissionResolver()

        // ── project-workflow 빈 ───────────────────────────────────────────────

        @Bean
        open fun workflowRepository(dsl: DSLContext): WorkflowRepository = WorkflowRepository(dsl)

        @Bean
        open fun workflowCache(
            workflowRepo: WorkflowRepository,
            dsl: DSLContext,
        ): WorkflowCache = WorkflowCache(workflowRepo, dsl)

        @Bean
        open fun workflowDefinitionRepository(): WorkflowDefinitionRepository =
            mockk<WorkflowDefinitionRepository> {
                every { findValidators(any(), any()) } returns emptyList()
                every { findPostActions(any(), any()) } returns emptyList()
            }

        @Bean
        open fun workflowValidatorFactory(): WorkflowValidatorFactory = mockk(relaxed = true)

        @Bean
        open fun workflowPostActionFactory(): WorkflowPostActionFactory = mockk(relaxed = true)

        @Bean
        open fun workflowEngine(
            cache: WorkflowCache,
            validatorFactory: WorkflowValidatorFactory,
            postActionFactory: WorkflowPostActionFactory,
            definitionRepo: WorkflowDefinitionRepository,
        ): WorkflowEngine = WorkflowEngine(cache, validatorFactory, postActionFactory, definitionRepo)

        @Bean
        @Suppress("MaxLineLength")
        open fun workflowTransitionAdapter(engine: WorkflowEngine): WorkflowTransitionAdapter = WorkflowTransitionAdapter(engine)

        @Bean
        open fun workflowSchemeRepository(dsl: DSLContext): WorkflowSchemeRepository = WorkflowSchemeRepository(dsl)

        @Bean
        open fun projectWorkflowSchemeAssignmentRepository(dsl: DSLContext): ProjectWorkflowSchemeAssignmentRepository =
            ProjectWorkflowSchemeAssignmentRepository(dsl)

        @Bean
        @Suppress("MaxLineLength")
        open fun schemeIssueTypeMappingRepository(dsl: DSLContext): SchemeIssueTypeMappingRepository = SchemeIssueTypeMappingRepository(dsl)

        @Bean
        open fun workflowSchemeEventPublisher(
            dsl: DSLContext,
            objectMapper: ObjectMapper,
        ): WorkflowSchemeEventPublisher = WorkflowSchemeEventPublisher(dsl, objectMapper)

        @Bean
        open fun alwaysAllowWorkflowSchemePermissionResolver(): AlwaysAllowWorkflowSchemePermissionResolver =
            AlwaysAllowWorkflowSchemePermissionResolver()

        @Bean
        open fun jdbcProjectLookupAdapter(dsl: DSLContext): JdbcProjectLookupAdapter = JdbcProjectLookupAdapter(dsl)

        @Bean
        open fun issueTypeLookupPort(): IssueTypeLookupPort = mockk(relaxed = true)

        @Bean
        @Suppress("LongParameterList")
        open fun workflowSchemeApplicationService(
            schemeRepo: WorkflowSchemeRepository,
            assignmentRepo: ProjectWorkflowSchemeAssignmentRepository,
            mappingRepo: SchemeIssueTypeMappingRepository,
            eventPublisher: WorkflowSchemeEventPublisher,
            permissionResolver: AlwaysAllowWorkflowSchemePermissionResolver,
            workflowRepo: WorkflowRepository,
            issueTypeLookupPort: IssueTypeLookupPort,
        ): WorkflowSchemeApplicationService =
            WorkflowSchemeApplicationService(
                schemeRepo = schemeRepo,
                assignmentRepo = assignmentRepo,
                mappingRepo = mappingRepo,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowRepo = workflowRepo,
                issueTypeLookupPort = issueTypeLookupPort,
            )

        @Bean
        open fun workflowResolverImpl(
            projectLookup: JdbcProjectLookupAdapter,
            assignmentRepo: ProjectWorkflowSchemeAssignmentRepository,
            mappingRepo: SchemeIssueTypeMappingRepository,
            workflowRepo: WorkflowRepository,
            schemeAS: WorkflowSchemeApplicationService,
        ): WorkflowResolverImpl =
            WorkflowResolverImpl(
                projectLookup = projectLookup,
                assignmentRepo = assignmentRepo,
                mappingRepo = mappingRepo,
                workflowRepo = workflowRepo,
                schemeAS = schemeAS,
            )

        @Bean
        open fun workflowKeyResolverImpl(workflowResolver: WorkflowResolverImpl): WorkflowKeyResolverImpl =
            WorkflowKeyResolverImpl(workflowResolver = workflowResolver)

        // ── Application Service + Controller ──────────────────────────────────

        @Bean
        open fun clock(): Clock = Clock.systemUTC()

        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                override fun exists(userId: UUID): Boolean = true
            }

        /**
         * IssueApplicationService 에 ResolutionRepository 주입 포함.
         * B6 GREEN 이후 resolutionRepository 파라미터가 추가된다.
         * RED 단계에서 IssueApplicationService 생성자에 resolutionRepository 가 없으므로 컴파일 실패.
         */
        @Bean
        @Suppress("LongParameterList")
        open fun issueApplicationService(
            repo: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            eventPublisher: IssueEventPublisher,
            permissionResolver: AlwaysAllowIssuePermissionResolver,
            workflowTransitionAdapter: WorkflowTransitionAdapter,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            userLookupPort: UserLookupPort,
            clock: Clock,
        ): IssueApplicationService =
            IssueApplicationService(
                repo = repo,
                issueTypeRepository = issueTypeRepository,
                resolutionRepository = resolutionRepository,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowPort = workflowTransitionAdapter,
                workflowKeyResolver = workflowKeyResolver,
                userLookupPort = userLookupPort,
                componentRepository = mockk(relaxed = true),
                projectLeadRepository = mockk(relaxed = true),
                versionRepository = mockk(relaxed = true),
                clock = clock,
                historyRecorder = io.mockk.mockk(relaxed = true),
            )

        @Bean
        open fun issueController(service: IssueApplicationService): IssueController = IssueController(service)

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    // ── 테스트 인프라 ────────────────────────────────────────────────────────────

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dataSource: DriverManagerDataSource

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        private var migrated = false
        private var seeded = false

        private const val PROJECT_KEY = "RESOLUTION"

        /**
         * V011 seed 의 'fixed' resolution 고정 UUID.
         * Zod v4 형식 (메모리 zod-v4-uuid-fixture-strictness) — 4번째 그룹 8 시작.
         */
        private val FIXED_RESOLUTION_ID: UUID =
            UUID.fromString("00000000-0000-4000-8000-000000000001")

        /** 존재하지 않는 위조 UUID — Q3 검증용. Zod v4 형식. */
        private val NONEXISTENT_RESOLUTION_ID: UUID =
            UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff")
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedWorkflowsAndProject()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── S1. DONE 전환 + resolutionId → resolution_id 영속 ────────────────────────

    /**
     * S1 — DONE 전환 시 resolutionId 가 issues.resolution_id 에 영속됨을 검증한다.
     *
     * Given  이슈 currentStateKey = "open", version = 1
     * When   open → in_progress (1단계)
     * And    in_progress → done (resolutionId=FIXED_RESOLUTION_ID, 2단계)
     * Then   200 OK
     * And    DB issues.resolution_id == FIXED_RESOLUTION_ID
     */
    @Test
    fun `DONE 전환 시 resolutionId 가 issues resolution_id 에 영속된다`() {
        val issueKey = insertIssue(PROJECT_KEY, "resolution 영속 검증 이슈", "open")

        // 1단계: open → in_progress
        val step1Body = mapOf("toStatusKey" to "in_progress", "expectedVersion" to 1)
        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(step1Body)),
        ).andExpect(status().isOk)

        // 2단계: in_progress → done (resolutionId 제공)
        val step2Body =
            mapOf(
                "toStatusKey" to "done",
                "expectedVersion" to 2,
                "resolutionId" to FIXED_RESOLUTION_ID.toString(),
            )
        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(step2Body)),
        ).andExpect(status().isOk)

        // DB 직접 조회 — resolution_id 영속 검증
        val resolutionId = queryResolutionId(issueKey)
        check(resolutionId == FIXED_RESOLUTION_ID) {
            "resolution_id 영속 실패: expected=$FIXED_RESOLUTION_ID, actual=$resolutionId"
        }
    }

    // ── S3. done → open 재전환(resolutionId=null) → resolution_id null clear ────

    /**
     * S3 — 비DONE 재전환 시 issues.resolution_id 가 null 로 clear 됨을 검증한다.
     *
     * Given  이슈 currentStateKey = "done", resolution_id = FIXED_RESOLUTION_ID
     * When   POST /api/v1/issues/{key}/transition { toStateKey: "open" } (resolutionId 미제공)
     * Then   200 OK
     * And    DB issues.resolution_id IS NULL
     */
    @Test
    fun `비DONE 재전환 시 resolution_id 가 null 로 clear 된다`() {
        // done 상태 + resolution_id 세팅 이슈 직접 삽입
        val issueKey = insertIssueWithResolution(PROJECT_KEY, "resolution clear 검증 이슈", "done", FIXED_RESOLUTION_ID)

        // done → open 역방향 전환 (resolutionId 미제공)
        val body = mapOf("toStatusKey" to "open", "expectedVersion" to 1)
        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        ).andExpect(status().isOk)

        // DB 직접 조회 — resolution_id null 확인
        val resolutionId = queryResolutionId(issueKey)
        check(resolutionId == null) {
            "resolution_id clear 실패: expected=null, actual=$resolutionId"
        }
    }

    // ── S4. 존재하지 않는 resolutionId → 404 RESOLUTION_NOT_FOUND ─────────────────

    /**
     * S4 — 위조 resolutionId 제공 시 영속 전에 거부됨을 검증한다.
     *
     * Given  이슈 currentStateKey = "open"
     * When   POST /api/v1/issues/{key}/transition { toStateKey: "in_progress", resolutionId: <위조 UUID> }
     * Then   404 + errorCode == "RESOLUTION_NOT_FOUND"
     * And    issues.resolution_id 는 변경되지 않음 (영속 전 검증)
     */
    @Test
    fun `존재하지 않는 resolutionId 제공 시 404 RESOLUTION_NOT_FOUND 반환`() {
        val issueKey = insertIssue(PROJECT_KEY, "존재성 검증 이슈", "open")

        val body =
            mapOf(
                "toStatusKey" to "in_progress",
                "expectedVersion" to 1,
                "resolutionId" to NONEXISTENT_RESOLUTION_ID.toString(),
            )
        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("RESOLUTION_NOT_FOUND"))

        // 영속되지 않았음을 확인 — resolution_id 변경 없음
        val resolutionId = queryResolutionId(issueKey)
        check(resolutionId == null) {
            "거부됐음에도 resolution_id 가 영속됐다: actual=$resolutionId"
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — issue-tracking V001~V011 + project-workflow V200~V202.
     *
     * V011 이 resolutions 테이블 + seed 5종 + issues.resolution_id 컬럼을 생성한다.
     */
    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(
                TestConfig.postgres.jdbcUrl,
                TestConfig.postgres.username,
                TestConfig.postgres.password,
            )
            .placeholderReplacement(false)
            .locations(
                "classpath:db/migration/issue-tracking",
                "classpath:db/migration/project-workflow",
            )
            .load()
            .migrate()
    }

    /**
     * 테스트용 프로젝트 + 워크플로우 시드.
     *
     * 워크플로우: open → in_progress → done, done → open (역방향 추가).
     * B7 validator seed 는 포함하지 않는다 (B6 명세 범위).
     */
    @Suppress("LongMethod")
    private fun seedWorkflowsAndProject() {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false

            insertProject(conn, PROJECT_KEY, "Resolution Integration Test Project")

            // 워크플로우
            val wfId =
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('resolution-test-wf', 'resolution 테스트 워크플로우') " +
                        "ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL" +
                        " DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val openId = insertState(conn, wfId, "open", "Open", "TODO", 0)
            val inProgressId = insertState(conn, wfId, "in_progress", "In Progress", "IN_PROGRESS", 1)
            val doneId = insertState(conn, wfId, "done", "Done", "DONE", 2)

            insertTransition(conn, wfId, openId, inProgressId, "Start Work")
            insertTransition(conn, wfId, inProgressId, doneId, "Complete")
            // S3 검증을 위한 done → open 역방향 전환
            insertTransition(conn, wfId, doneId, openId, "Reopen")

            // workflow_scheme + default mapping
            val schemeId: Long =
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name, is_default) " +
                        "VALUES ('resolution-test-scheme', 'resolution 테스트 스킴', false) " +
                        "ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL" +
                        " DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            conn.prepareStatement(
                "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id) " +
                    "VALUES (?, NULL, ?) ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING",
            ).use { stmt ->
                stmt.setLong(1, schemeId)
                stmt.setObject(2, wfId)
                stmt.executeUpdate()
            }

            // 프로젝트에 스킴 배정
            conn.prepareStatement(
                "INSERT INTO project_workflow_scheme_assignments " +
                    "(project_id, workflow_scheme_id, assigned_at, assigned_by) " +
                    "SELECT p.id, ?, NOW(), '00000000-0000-4000-8000-000000000000'::uuid " +
                    "FROM projects p WHERE p.key = ? " +
                    "ON CONFLICT (project_id) DO NOTHING",
            ).use { stmt ->
                stmt.setLong(1, schemeId)
                stmt.setString(2, PROJECT_KEY)
                stmt.executeUpdate()
            }

            conn.commit()
        }
    }

    private fun insertProject(
        conn: Connection,
        key: String,
        name: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
        ).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, name)
            stmt.executeUpdate()
        }
    }

    @Suppress("LongParameterList") // 테스트 헬퍼 — DB 직접 삽입에 필요한 최소 파라미터
    private fun insertState(
        conn: Connection,
        wfId: UUID,
        key: String,
        name: String,
        category: String,
        displayOrder: Int,
    ): UUID =
        insertWorkflowStatus(conn, wfId, key, name, category, displayOrder)

    private fun insertTransition(
        conn: Connection,
        wfId: UUID,
        fromId: UUID,
        toId: UUID,
        name: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) " +
                "SELECT ?, ?, ?, ? WHERE NOT EXISTS (" +
                "SELECT 1 FROM workflow_transitions " +
                "WHERE workflow_id = ? AND from_state_id = ? AND to_state_id = ?)",
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setObject(2, fromId)
            stmt.setObject(3, toId)
            stmt.setString(4, name)
            stmt.setObject(5, wfId)
            stmt.setObject(6, fromId)
            stmt.setObject(7, toId)
            stmt.executeUpdate()
        }
    }

    /**
     * 테스트용 이슈를 DB 에 직접 삽입하고 이슈 키를 반환한다.
     *
     * @param projectKey 프로젝트 키.
     * @param summary 이슈 제목.
     * @param currentStateKey 초기 상태 키.
     * @return 삽입된 이슈 키 (예: "RESOLUTION-1").
     */
    private fun insertIssue(
        projectKey: String,
        summary: String,
        currentStateKey: String,
    ): String {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false

            val seq =
                conn.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            val issueKey = "$projectKey-$seq"

            val projectId =
                conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val taskTypeId =
                conn.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음. V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            conn.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "VALUES (?, ?, ?, ?, ?, 1, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, UUID.fromString("00000000-0000-4000-8000-000000000001"))
                stmt.setString(5, currentStateKey)
                stmt.setLong(6, taskTypeId)
                stmt.executeUpdate()
            }

            conn.commit()
            return issueKey
        }
    }

    /**
     * resolution_id 가 이미 설정된 이슈를 DB 에 직접 삽입한다.
     *
     * S3(clear 검증)에서 done 상태 + resolution_id 세팅 이슈를 만들기 위해 사용한다.
     *
     * @param projectKey 프로젝트 키.
     * @param summary 이슈 제목.
     * @param currentStateKey 초기 상태 키.
     * @param resolutionId 설정할 resolution UUID.
     * @return 삽입된 이슈 키.
     */
    private fun insertIssueWithResolution(
        projectKey: String,
        summary: String,
        currentStateKey: String,
        resolutionId: UUID,
    ): String {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false

            val seq =
                conn.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            val issueKey = "$projectKey-$seq"

            val projectId =
                conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val taskTypeId =
                conn.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음. V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            conn.prepareStatement(
                "INSERT INTO issues " +
                    "(key, project_id, summary, reporter_id, current_state_key, version, type_id, resolution_id) " +
                    "VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, UUID.fromString("00000000-0000-4000-8000-000000000001"))
                stmt.setString(5, currentStateKey)
                stmt.setLong(6, taskTypeId)
                stmt.setObject(7, resolutionId)
                stmt.executeUpdate()
            }

            conn.commit()
            return issueKey
        }
    }

    /**
     * DB 에서 이슈의 resolution_id 를 직접 조회한다.
     *
     * @param issueKey 이슈 키.
     * @return issues.resolution_id UUID, null 이면 null.
     */
    private fun queryResolutionId(issueKey: String): UUID? =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn -> fetchResolutionId(conn, issueKey) }

    private fun fetchResolutionId(
        conn: java.sql.Connection,
        issueKey: String,
    ): UUID? =
        conn.prepareStatement("SELECT resolution_id FROM issues WHERE key = ?").use { stmt ->
            stmt.setString(1, issueKey)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getObject(1) as UUID? else null
            }
        }
}
