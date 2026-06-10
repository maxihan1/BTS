// 실 RequiredField validator end-to-end 통합 테스트 — resolution 누락 DONE 전이 → 409 검증

package com.bts.issue.integration

import com.bts.issue.adapter.inbound.rest.IssueController
import com.bts.issue.adapter.inbound.rest.IssueExceptionHandler
import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeRef
import com.bts.shared.user.UserLookupPort
import com.bts.workflow.adapter.AlwaysAllowPermissionResolver
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.engine.DefaultWorkflowPostActionFactory
import com.bts.workflow.engine.DefaultWorkflowValidatorFactory
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.repository.DefaultWorkflowDefinitionRepository
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
import com.bts.workflow.seed.YamlSeedService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.io.DefaultResourceLoader
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
import java.util.concurrent.Executors

/**
 * 실 RequiredField validator end-to-end 통합 테스트 (FR-IS-07 Task B6b).
 *
 * ## 검증 목적
 * [com.bts.issue.application.IssueTransitionResolutionIntegrationTest] 가 mock WorkflowDefinitionRepository(emptyList)를
 * 사용해 RequiredField validator 가 비활성화된 상태임을 보완한다.
 *
 * 실 [DefaultWorkflowDefinitionRepository] + [DefaultWorkflowValidatorFactory] + [WorkflowEngine] 을 wire하고,
 * [YamlSeedService.seedAll] 로 production YAML 워크플로우(RequiredField(resolution) validator 포함)를 시드해
 * DONE 진입 전이에 validator 가 실제로 평가되는지 REST → application → engine 전체 경로로 검증한다.
 *
 * ## 시드 전략
 * [YamlSeedService.seedAll] 이 production YAML(software-default 등)을 그대로 시드한다.
 * software-default: in_review → done 전이에 RequiredField(resolution) validator 존재.
 * 이슈를 "in_review" 상태로 직접 삽입해 해당 전이를 직접 검증한다.
 *
 * ## 검증 시나리오
 *
 * ### S1. resolution 누락 DONE 전이 → 409 TRANSITION_NOT_ALLOWED
 * Given  이슈 currentStateKey = "in_review"
 *        software-default 워크플로우 배정 (in_review → done 에 RequiredField(resolution))
 * When   POST /api/v1/issues/{key}/transition { toStatusKey: "done", expectedVersion: 1 } (resolutionId 미제공)
 * Then   409 Conflict + errorCode == "TRANSITION_NOT_ALLOWED"
 *
 * ### S2. resolution 제공 DONE 전이 → 200 성공 + resolution_id 영속
 * Given  이슈 currentStateKey = "in_review"
 * When   POST /api/v1/issues/{key}/transition { toStatusKey: "done", expectedVersion: 1, resolutionId: FIXED_UUID }
 * Then   200 OK + DB issues.resolution_id == FIXED_UUID
 *
 * ### S3. 일괄 경로 — resolution 누락 DONE 전이 → application 레이어 IssueTransitionNotAllowedException
 * Given  이슈 currentStateKey = "in_review"
 * When   service.transitionIssue(resolution=null, toStateKey="done") 직접 호출
 * Then   IssueTransitionNotAllowedException
 *
 * @see com.bts.issue.application.IssueTransitionResolutionIntegrationTest
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueTransitionValidatorEndToEndIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueTransitionValidatorEndToEndIntegrationTest {
    // ── Spring Bean 구성 ─────────────────────────────────────────────────────────

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            /** JVM 단위 singleton Testcontainers — pgmq 확장 포함 */
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_validator_e2e_test")
                    .withUsername("bts")
                    .withPassword("bts_validator_e2e_test")
                    .apply { start() }

            /** SpEL 평가 스레드풀 — DefaultWorkflowValidatorFactory 전달용. */
            private val spelExecutor = Executors.newCachedThreadPool()
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

        @Bean
        open fun clock(): Clock = Clock.systemUTC()

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

        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                override fun exists(userId: UUID): Boolean = true
            }

        // ── project-workflow 빈 (실 구현체) ──────────────────────────────────────

        @Bean
        open fun workflowRepository(dsl: DSLContext): WorkflowRepository = WorkflowRepository(dsl)

        @Bean
        open fun workflowCache(
            workflowRepo: WorkflowRepository,
            dsl: DSLContext,
        ): WorkflowCache = WorkflowCache(workflowRepo, dsl)

        /**
         * 실 [DefaultWorkflowDefinitionRepository] — jOOQ 로 workflow_validators 테이블 조회.
         * 이 테스트의 핵심: mock 대신 실 구현체를 wire해 RequiredField validator 가 실제로 평가된다.
         */
        @Bean
        open fun workflowDefinitionRepository(dsl: DSLContext): DefaultWorkflowDefinitionRepository =
            DefaultWorkflowDefinitionRepository(dsl)

        /**
         * 실 [DefaultWorkflowValidatorFactory] — "RequiredField" type 요청 시 [RequiredFieldValidator] 생성.
         * [AlwaysAllowPermissionResolver] 는 permission-check validator 가 항상 통과하도록 한다.
         */
        @Bean
        open fun workflowValidatorFactory(): DefaultWorkflowValidatorFactory =
            DefaultWorkflowValidatorFactory(
                permissionResolver = AlwaysAllowPermissionResolver(),
                spelEvaluator = SpelEvaluator(executor = spelExecutor, timeoutMillis = 5000L),
            )

        @Bean
        open fun workflowPostActionFactory(): DefaultWorkflowPostActionFactory = DefaultWorkflowPostActionFactory()

        @Bean
        open fun workflowEngine(
            cache: WorkflowCache,
            validatorFactory: DefaultWorkflowValidatorFactory,
            postActionFactory: DefaultWorkflowPostActionFactory,
            definitionRepo: DefaultWorkflowDefinitionRepository,
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
        open fun issueTypeLookupPort(): IssueTypeLookupPort =
            object : IssueTypeLookupPort {
                override fun lookup(ids: List<IssueTypeId>): Map<IssueTypeId, IssueTypeRef> = emptyMap()
            }

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

        /**
         * [YamlSeedService] — production YAML(software-default 등) 을 시드한다.
         * RequiredField(resolution) validator 가 포함된 전이가 시드되어야 S1/S2/S3 검증이 가능하다.
         */
        @Bean
        open fun yamlSeedService(
            workflowRepo: WorkflowRepository,
            dsl: DSLContext,
            validatorFactory: DefaultWorkflowValidatorFactory,
            postActionFactory: DefaultWorkflowPostActionFactory,
        ): YamlSeedService =
            YamlSeedService(
                workflowRepository = workflowRepo,
                dsl = dsl,
                resourceLoader = DefaultResourceLoader(),
                yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule(),
                validatorFactory = validatorFactory,
                postActionFactory = postActionFactory,
            )

        // ── Application Service + Controller ──────────────────────────────────

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

    @Autowired
    lateinit var yamlSeedService: YamlSeedService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        private val log = LoggerFactory.getLogger(IssueTransitionValidatorEndToEndIntegrationTest::class.java)

        private var migrated = false
        private var seeded = false

        /**
         * software-default 워크플로우를 배정할 테스트 전용 프로젝트 키.
         * IssueTransitionResolutionIntegrationTest("RESOLUTION")와 겹치지 않는 이름 사용.
         */
        private const val PROJECT_KEY = "VALIDE2E"

        /**
         * V011 seed 의 'fixed' resolution 고정 UUID (Zod v4 형식).
         * meomry zod-v4-uuid-fixture-strictness — 4번째 그룹이 8로 시작해야 한다.
         */
        private val FIXED_RESOLUTION_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            // production YAML 시드 — RequiredField(resolution) validator 포함
            yamlSeedService.seedAll()
            seedProjectAndScheme()
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

    // ── S1. resolution 누락 DONE 전이 → 409 TRANSITION_NOT_ALLOWED ──────────────

    /**
     * S1 — 실 RequiredField validator 가 resolution 누락 DONE 전이를 409 로 차단한다.
     *
     * Given  이슈 currentStateKey = "in_review"
     *        software-default 워크플로우에 in_review → done 전이 + RequiredField(resolution) validator 시드됨
     * When   POST /api/v1/issues/{key}/transition { toStatusKey: "done", expectedVersion: 1 } (resolutionId 없음)
     * Then   409 Conflict + errorCode == "TRANSITION_NOT_ALLOWED"
     *
     * B6b 핵심 시나리오: REST → IssueApplicationService.transitionIssue
     *   → WorkflowTransitionAdapter.plan → WorkflowEngine.plan
     *   → DefaultWorkflowDefinitionRepository.findValidators (실 DB 조회)
     *   → RequiredFieldValidator.validate (resolution 필드 null) → Fail
     *   → TransitionResult.ValidatorFailure → IssueTransitionNotAllowedException → 409
     */
    @Test
    fun `S1 — resolution 누락 DONE 전이는 실 RequiredField validator 에 의해 409 로 차단된다`() {
        val issueKey = insertIssue(PROJECT_KEY, "validator E2E 검증 이슈 S1", "in_review")

        val body = mapOf("toStatusKey" to "done", "expectedVersion" to 1)
        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("TRANSITION_NOT_ALLOWED"))

        log.info("S1 통과 — resolution 누락 DONE 전이 → 409 TRANSITION_NOT_ALLOWED 확인")
    }

    // ── S2. resolution 제공 DONE 전이 → 200 성공 + resolution_id 영속 ──────────────

    /**
     * S2 — resolution 제공 시 실 validator 가 통과하고 resolution_id 가 영속된다.
     *
     * Given  이슈 currentStateKey = "in_review"
     * When   POST /api/v1/issues/{key}/transition { toStatusKey: "done", expectedVersion: 1, resolutionId: FIXED_UUID }
     * Then   200 OK + DB issues.resolution_id == FIXED_UUID
     */
    @Test
    fun `S2 — resolution 제공 DONE 전이는 실 RequiredField validator 를 통과하고 resolution_id 가 영속된다`() {
        val issueKey = insertIssue(PROJECT_KEY, "validator E2E 검증 이슈 S2", "in_review")

        val body =
            mapOf(
                "toStatusKey" to "done",
                "expectedVersion" to 1,
                "resolutionId" to FIXED_RESOLUTION_ID.toString(),
            )
        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        ).andExpect(status().isOk)

        val resolutionId = queryResolutionId(issueKey)
        check(resolutionId == FIXED_RESOLUTION_ID) {
            "resolution_id 영속 실패: expected=$FIXED_RESOLUTION_ID, actual=$resolutionId"
        }

        log.info("S2 통과 — resolution 제공 DONE 전이 200 + resolution_id={} 영속 확인", resolutionId)
    }

    // ── S3. 일괄 경로 — resolution 누락 DONE 전이 → IssueTransitionNotAllowedException ──

    /**
     * S3 — 일괄 경로(application service 직접 호출)에서도 RequiredField validator 가 동일하게 거부한다.
     *
     * Given  이슈 currentStateKey = "in_review"
     * When   service.transitionIssue(toStateKey="done", resolutionId=null) 직접 호출
     *        (REST 없이 application 레이어만 통과 — 벌크 처리기 경로와 동일)
     * Then   IssueTransitionNotAllowedException
     *
     * 이 시나리오는 BulkTransitionResolutionTest 주석(103-104행)이 참조하는 validator end-to-end 검증이다.
     * 일괄 처리기(BulkItemExecutor)는 IssueApplicationService.transitionIssue 를 호출하므로
     * 이 경로 차단이 곧 bulk DONE 전이 validator 차단을 의미한다.
     *
     * @see com.bts.issue.bulk.application.BulkTransitionResolutionTest
     */
    @Test
    fun `S3 — 일괄 경로에서도 resolution 누락 DONE 전이는 IssueTransitionNotAllowedException 으로 차단된다`() {
        val issueKey = insertIssue(PROJECT_KEY, "validator E2E 검증 이슈 S3", "in_review")

        val service = webApplicationContext.getBean(IssueApplicationService::class.java)
        val actor = com.bts.issue.domain.ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val key = com.bts.issue.domain.IssueKey(issueKey)
        val request =
            com.bts.issue.application.TransitionIssueRequest(
                toStateKey = "done",
                expectedVersion = 1L,
                resolutionId = null,
            )

        try {
            service.transitionIssue(actor, key, request)
            error("IssueTransitionNotAllowedException 이 발생해야 하는데 정상 반환됐습니다. validator 가 누락됐을 가능성이 있습니다.")
        } catch (ex: com.bts.issue.domain.IssueTransitionNotAllowedException) {
            log.info("S3 통과 — 일괄 경로 IssueTransitionNotAllowedException 확인: {}", ex.message)
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — issue-tracking + project-workflow 두 BC 순차 적용.
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
     * 테스트 전용 프로젝트 + software-default 워크플로우 스킴 배정.
     *
     * software-default 워크플로우는 [YamlSeedService.seedAll] 에 의해 이미 시드돼 있다.
     * 이 메서드는 프로젝트와 스킴 배정(PROJECT_KEY → software-default-scheme) 만 추가한다.
     *
     * software-default-scheme 이 존재하지 않을 경우 직접 삽입한다.
     */
    @Suppress("LongMethod", "NestedBlockDepth")
    private fun seedProjectAndScheme() {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false

            insertProject(conn, PROJECT_KEY, "Validator E2E Test Project")

            // software-default 워크플로우 ID 조회
            val workflowId =
                conn.prepareStatement(
                    "SELECT id FROM workflows WHERE key = 'software-default'",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) {
                            "software-default 워크플로우가 없습니다. YamlSeedService.seedAll() 이 정상 실행됐는지 확인하세요."
                        }
                        rs.getObject(1) as UUID
                    }
                }

            // software-default-scheme 조회 또는 생성
            val schemeId =
                conn.prepareStatement(
                    "SELECT id FROM workflow_schemes WHERE key = 'software-default-scheme'",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            rs.getLong(1)
                        } else {
                            // MaxLineLength: 테스트 헬퍼 호출 — 파라미터명이 길어 줄 초과, 의미상 분리 불가
                            @Suppress("MaxLineLength")
                            insertSchemeWithDefaultMapping(conn, "software-default-scheme", "Software Default Scheme", workflowId)
                        }
                    }
                }

            // 프로젝트에 스킴 배정 (이미 있으면 무시)
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
            // MaxLineLength: 구조화 로그 파라미터 3개, 의미상 분리 불가
            @Suppress("MaxLineLength")
            log.info("seedProjectAndScheme 완료 — project={} schemeId={} workflowId={}", PROJECT_KEY, schemeId, workflowId)
        }
    }

    /**
     * workflow_schemes + workflow_scheme_issue_type_mappings 삽입 후 scheme id 반환.
     */
    private fun insertSchemeWithDefaultMapping(
        conn: Connection,
        schemeKey: String,
        schemeName: String,
        workflowId: UUID,
    ): Long {
        val schemeId =
            conn.prepareStatement(
                "INSERT INTO workflow_schemes (key, name, is_default) VALUES (?, ?, false) " +
                    "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
            ).use { stmt ->
                stmt.setString(1, schemeKey)
                stmt.setString(2, schemeName)
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
            stmt.setObject(2, workflowId)
            stmt.executeUpdate()
        }

        return schemeId
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

    /**
     * 테스트용 이슈를 DB 에 직접 삽입하고 이슈 키를 반환한다.
     *
     * @param projectKey 프로젝트 키.
     * @param summary 이슈 제목.
     * @param currentStateKey 초기 상태 키.
     * @return 삽입된 이슈 키 (예: "VALIDE2E-1").
     */
    @Suppress("LongMethod")
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

    /** DB 에서 이슈의 resolution_id 를 직접 조회한다. */
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
