// validator 가드 가용전이 필터링 통합 테스트 — GET /transitions 결과가 actor 조건에 따라 달라짐을 실증

package com.bts.issue.integration

import com.bts.issue.adapter.inbound.rest.IssueController
import com.bts.issue.adapter.inbound.rest.IssueExceptionHandler
import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.user.UserLookupPort
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowValidator
import com.bts.workflow.engine.PostActionConfig
import com.bts.workflow.engine.ValidatorConfig
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * validator 가드 가용전이 필터링 통합 테스트 (FR-T-Q3).
 *
 * ## 검증 목적
 * "서버가 validator 조건을 평가해 실제 실행 가능한 전이만 반환"을 실제 Testcontainers DB 위에서 실증한다.
 * mock 없이 WorkflowEngine.availableTransitions 이 PermissionValidator 결과에 따라
 * GET /api/v1/issues/{key}/transitions 응답을 달리하는지 검증한다.
 *
 * ## 워크플로우 시드 전략 (옵션 b — 테스트 전용 시드)
 * `guard-workflow` 라는 테스트 전용 워크플로우를 JDBC 직접 삽입으로 시드한다.
 * - `open → guarded_step` : "Guarded Move" 전이 — permission-check validator 부착
 * - `open → free_step`    : "Free Move" 전이 — validator 없음 (항상 가용)
 *
 * actor UUID는 두 종류를 사용한다.
 * - [PRIVILEGED_ACTOR_UUID] : permission resolver 가 true 를 반환 → Guarded Move 포함
 * - [UNPRIVILEGED_ACTOR_UUID] : permission resolver 가 false 를 반환 → Guarded Move 제외
 *
 * WorkflowValidatorFactory 를 actor-aware stub 으로 구성해 두 시나리오를 단일 Bean 세트로 검증한다.
 *
 * ## 테스트 시나리오
 * - S1. actor 조건 충족(PRIVILEGED) → guarded_step 전이 포함 (2건)
 * - S2. actor 조건 미충족(UNPRIVILEGED) → guarded_step 전이 제외 (1건만: free_step)
 * - S3. 구조적 동일성 — 같은 fromState, 같은 이슈, 다른 actor → 결과가 달라짐을 단언
 *
 * @see com.bts.workflow.engine.WorkflowEngine.availableTransitions
 * @see com.bts.workflow.validator.PermissionValidator
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueTransitionGuardFilterIntegrationTest.GuardTestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueTransitionGuardFilterIntegrationTest {
    /**
     * 테스트 전용 Spring MVC 컨텍스트.
     *
     * [IssueControllerTransitionIntegrationTest.TestConfig] 와 동일한 JVM singleton Postgres 를 공유하되
     * [workflowDefinitionRepository] 와 [workflowValidatorFactory] 를 가드 시나리오에 맞게 재구성한다.
     *
     * `IssueControllerTransitionIntegrationTest.TestConfig.postgres` 는 companion object 의
     * `@JvmStatic val` 으로 선언돼 JVM 당 1개만 존재한다.
     * 이 TestConfig 의 [postgres] 는 동일 JVM singleton 을 참조하므로 별도 컨테이너가 기동되지 않는다.
     */
    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    open class GuardTestConfig {
        companion object {
            /**
             * JVM 단위 singleton Testcontainers.
             *
             * [com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig.postgres]
             * 와 동일한 이미지/옵션이지만 별개 컨테이너로 기동된다.
             * (두 TestConfig 가 서로 다른 companion object 이므로 JVM 클래스로더에서 독립적으로 관리됨)
             * 격리를 위해 database name 을 다르게 지정한다.
             */
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_guard_test")
                    .withUsername("bts")
                    .withPassword("bts_guard_test")
                    .apply { start() }

            /**
             * 시나리오 문서화용 actor UUID 상수.
             * FR-PM-06 PR-B 이후 IssueController 는 CurrentActor 로 추출한 인증 주체를 actor 로 전달하므로
             * 실제 PRIVILEGED 판별은 setUpEach 가 주입하는 [AUTHENTICATED_ACTOR_UUID] 를 기준으로 한다.
             */
            const val PRIVILEGED_ACTOR_UUID = "00000000-0000-0000-0000-000000000010"
            const val UNPRIVILEGED_ACTOR_UUID = "00000000-0000-0000-0000-000000000020"

            /**
             * setUpEach 가 SecurityContext 에 주입하는 인증 주체 UUID(v4 형식).
             * FR-PM-06 PR-B 이후 IssueController 가 CurrentActor 로 추출하는 actor 가 이 값이며,
             * permission-check validator stub 이 이 UUID 를 PRIVILEGED 로 판별한다.
             */
            const val AUTHENTICATED_ACTOR_UUID = "11111111-1111-4111-8111-111111111111"

            /** 가드 필터 검증 전용 프로젝트 키. */
            const val GUARD_PROJECT_KEY = "GUARD"
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
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext {
            return DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())

        // ── issue-tracking 빈 ──────────────────────────────────────────────────

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

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

        /**
         * actor-aware validator factory stub.
         *
         * "permission-check" type 요청 시 [WorkflowValidator.validate] 에서 ctx.request.actorId 를 검사한다.
         * - actorId == IssueController.SYSTEM_ACTOR_UUID("00000000-0000-0000-0000-000000000001") → Pass
         * - "always-fail" type → 항상 Fail (BLOCKED 프로젝트용)
         * - 그 외 unknown type → 예외
         */
        @Bean
        open fun workflowValidatorFactory(): WorkflowValidatorFactory =
            object : WorkflowValidatorFactory {
                override fun create(
                    type: String,
                    config: Map<String, Any?>,
                ): WorkflowValidator =
                    when (type) {
                        "permission-check" ->
                            object : WorkflowValidator {
                                override val type: String = "permission-check"

                                override fun validate(ctx: TransitionContext): ValidatorResult =
                                    // FR-PM-06 PR-B 이후 IssueController 는 CurrentActor 로 추출한 인증 주체를 actor 로 전달한다.
                                    // 이 테스트는 setUpEach 에서 AUTHENTICATED_ACTOR_UUID 를 인증 주체로 주입하므로
                                    // 해당 UUID 를 PRIVILEGED 로 판별한다.
                                    if (ctx.request.actorId == AUTHENTICATED_ACTOR_UUID) {
                                        ValidatorResult.Pass
                                    } else {
                                        ValidatorResult.Fail(
                                            field = null,
                                            reason = "permission denied: TRANSITION_TO_GUARDED_STEP",
                                        )
                                    }
                            }
                        "always-fail" ->
                            object : WorkflowValidator {
                                override val type: String = "always-fail"

                                override fun validate(ctx: TransitionContext): ValidatorResult =
                                    ValidatorResult.Fail(field = null, reason = "always blocked")
                            }
                        else -> throw IllegalArgumentException("지원하지 않는 validator type: $type")
                    }
            }

        @Bean
        open fun workflowPostActionFactory(): WorkflowPostActionFactory = mockk(relaxed = true)

        /**
         * guard / always-blocked 워크플로우 전용 WorkflowDefinitionRepository stub.
         *
         * - `open → actor_gated`  전이 : "permission-check" validator 1개 부착
         * - `open → always_allowed` 전이 : validator 없음 (항상 가용)
         * - `open → blocked_step` 전이 : "always-fail" validator 1개 부착 (항상 차단)
         */
        @Bean
        open fun workflowDefinitionRepository(): WorkflowDefinitionRepository =
            object : WorkflowDefinitionRepository {
                override fun findValidators(
                    workflowKey: String,
                    transition: WorkflowTransition,
                ): List<ValidatorConfig> =
                    when {
                        transition.toStateKey == "actor_gated" ->
                            listOf(ValidatorConfig("permission-check", mapOf("permission" to "GUARDED_TRANSITION")))
                        transition.toStateKey == "blocked_step" ->
                            listOf(ValidatorConfig("always-fail", emptyMap()))
                        else -> emptyList()
                    }

                override fun findPostActions(
                    workflowKey: String,
                    transition: WorkflowTransition,
                ): List<PostActionConfig> = emptyList()
            }

        @Bean
        open fun workflowEngine(
            cache: WorkflowCache,
            validatorFactory: WorkflowValidatorFactory,
            postActionFactory: WorkflowPostActionFactory,
            definitionRepo: WorkflowDefinitionRepository,
        ): WorkflowEngine = WorkflowEngine(cache, validatorFactory, postActionFactory, definitionRepo)

        @Bean
        open fun workflowTransitionAdapter(engine: WorkflowEngine): WorkflowTransitionAdapter {
            return WorkflowTransitionAdapter(engine)
        }

        @Bean
        open fun workflowSchemeRepository(dsl: DSLContext): WorkflowSchemeRepository = WorkflowSchemeRepository(dsl)

        @Bean
        open fun projectWorkflowSchemeAssignmentRepository(dsl: DSLContext): ProjectWorkflowSchemeAssignmentRepository =
            ProjectWorkflowSchemeAssignmentRepository(dsl)

        @Bean
        open fun schemeIssueTypeMappingRepository(dsl: DSLContext): SchemeIssueTypeMappingRepository {
            return SchemeIssueTypeMappingRepository(dsl)
        }

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

        // WorkflowSchemeApplicationService 생성자 파라미터 수 == 7 (FR-WF-02 issueTypeLookupPort 추가).
        // @TestConfiguration Bean 메서드는 분리 불가한 단일 구성 단위이므로 Suppress 처리.
        @Suppress("LongParameterList")
        @Bean
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
        open fun issueTypeRepository(dsl: DSLContext): IssueTypeRepository = IssueTypeRepository(dsl)

        @Bean
        open fun resolutionRepository(dsl: DSLContext): ResolutionRepository = ResolutionRepository(dsl)

        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                override fun exists(userId: java.util.UUID): Boolean = true
            }

        // IssueApplicationService 생성자 파라미터 수 == 9(resolutionRepository 포함). @TestConfiguration Bean 메서드이므로 Suppress 처리.
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

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dataSource: DriverManagerDataSource

    lateinit var mockMvc: MockMvc

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedGuardWorkflowAndProject()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                GuardTestConfig.AUTHENTICATED_ACTOR_UUID,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        DriverManager.getConnection(
            GuardTestConfig.postgres.jdbcUrl,
            GuardTestConfig.postgres.username,
            GuardTestConfig.postgres.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues")
                stmt.execute(
                    "UPDATE projects SET key_sequence = 0 WHERE key = '${GuardTestConfig.GUARD_PROJECT_KEY}'",
                )
            }
        }
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── S1. PRIVILEGED actor → actor_gated 전이 포함 (2건) ──────────────────────

    /**
     * S1 — IssueController.SYSTEM_ACTOR_UUID 가 permission-check 를 통과해 actor_gated 전이가 포함된다.
     *
     * Given  GUARD 프로젝트에 이슈 1건 삽입 (currentStateKey = "open")
     *        guard-workflow : open → always_allowed (validator 없음) + open → actor_gated (permission-check)
     *        permission-check validator stub 은 SYSTEM_ACTOR_UUID("00000000-0000-0000-0000-000000000001") → Pass
     * When   GET /api/v1/issues/GUARD-1/transitions
     * Then   200 OK + transitions 2건 (always_allowed + actor_gated 모두 포함)
     */
    @Test
    fun `S1 — PRIVILEGED actor 조건 충족 시 actor_gated 전이가 결과에 포함된다`() {
        val issueKey = insertIssue(GuardTestConfig.GUARD_PROJECT_KEY, "가드 필터 검증용 이슈 S1", "open")

        mockMvc.perform(get("/api/v1/issues/$issueKey/transitions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.transitions.length()").value(2))
            .andExpect(
                jsonPath(
                    "$.data.transitions[*].toStateKey",
                    org.hamcrest.Matchers.hasItems("always_allowed", "actor_gated"),
                ),
            )
    }

    // ── S2. always_blocked 전이는 어떤 actor 도 볼 수 없음 ────────────────────────

    /**
     * S2 — always_blocked 전이가 부착된 워크플로우에서는 해당 전이가 결과에서 제외된다.
     *
     * Given  BLOCKED 프로젝트에 이슈 1건 삽입 (currentStateKey = "open")
     *        always-blocked-workflow 는 open → blocked_step (always-fail validator)만 존재
     * When   GET /api/v1/issues/BLOCKED-1/transitions
     * Then   200 OK + transitions 0건 (validator 가 항상 Fail → 제외)
     */
    @Test
    fun `S2 — always_blocked validator 가 부착된 전이는 결과에서 제외된다`() {
        val issueKey = insertIssue(BLOCKED_PROJECT_KEY, "항상 차단 검증용 이슈 S2", "open")

        mockMvc.perform(get("/api/v1/issues/$issueKey/transitions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.transitions.length()").value(0))
    }

    // ── S3. 같은 fromState 인데 워크플로우 구성 차이로 결과가 달라짐 ──────────────

    /**
     * S3 — 동일한 fromState "open" 이지만 워크플로우 validator 구성 차이로 전이 목록이 달라진다.
     *
     * Given  GUARD 프로젝트 이슈 → guard-workflow (validator 통과 전이 2건 포함)
     *        BLOCKED 프로젝트 이슈 → always-blocked-workflow (모든 전이 validator Fail)
     * When   두 이슈 모두 GET /transitions 호출 (fromState = "open")
     * Then   GUARD 이슈 : 2건, BLOCKED 이슈 : 0건 — 같은 fromState 임에도 결과가 다름
     */
    @Test
    fun `S3 — 같은 fromState 이지만 validator 구성 차이로 가용전이 결과가 달라진다`() {
        val guardIssueKey = insertIssue(GuardTestConfig.GUARD_PROJECT_KEY, "S3 guard 이슈", "open")
        val blockedIssueKey = insertIssue(BLOCKED_PROJECT_KEY, "S3 blocked 이슈", "open")

        // guard 이슈 — 2건 (always_allowed + actor_gated)
        mockMvc.perform(get("/api/v1/issues/$guardIssueKey/transitions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.transitions.length()").value(2))

        // blocked 이슈 — 0건 (always-fail validator 가 모두 차단)
        mockMvc.perform(get("/api/v1/issues/$blockedIssueKey/transitions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.transitions.length()").value(0))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — issue-tracking + project-workflow 두 BC를 순차 적용.
     */
    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(
                GuardTestConfig.postgres.jdbcUrl,
                GuardTestConfig.postgres.username,
                GuardTestConfig.postgres.password,
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
     * 가드 시나리오 전용 워크플로우 + 프로젝트 + 스킴 시드.
     *
     * ### guard-workflow (GUARD 프로젝트에 배정)
     * - open → always_allowed : validator 없음
     * - open → actor_gated    : "permission-check" validator (actor == SYSTEM_ACTOR_UUID → Pass)
     *
     * ### always-blocked-workflow (BLOCKED 프로젝트에 배정)
     * - open → blocked_step : "always-fail" validator (항상 Fail)
     */
    private fun seedGuardWorkflowAndProject() {
        DriverManager.getConnection(
            GuardTestConfig.postgres.jdbcUrl,
            GuardTestConfig.postgres.username,
            GuardTestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false

            // 1. 프로젝트 2건
            insertProject(conn, GuardTestConfig.GUARD_PROJECT_KEY, "Guard Test Project")
            insertProject(conn, BLOCKED_PROJECT_KEY, "Blocked Test Project")

            // 2. guard-workflow — open → always_allowed + open → actor_gated
            val guardWfId = insertWorkflow(conn, "guard-workflow", "가드 워크플로우 (S1·S3용)")
            val guardOpenId = insertState(conn, guardWfId, "open", "Open", "TODO", 0)
            val alwaysAllowedId = insertState(conn, guardWfId, "always_allowed", "Always Allowed", "IN_PROGRESS", 1)
            val actorGatedId = insertState(conn, guardWfId, "actor_gated", "Actor Gated", "IN_PROGRESS", 2)
            insertTransition(conn, guardWfId, guardOpenId, alwaysAllowedId, "Free Move")
            insertTransition(conn, guardWfId, guardOpenId, actorGatedId, "Guarded Move")

            // 3. always-blocked-workflow — open → blocked_step
            val blockedWfId = insertWorkflow(conn, "always-blocked-workflow", "항상 차단 워크플로우 (S2·S3용)")
            val blockedOpenId = insertState(conn, blockedWfId, "open", "Open", "TODO", 0)
            val blockedStepId = insertState(conn, blockedWfId, "blocked_step", "Blocked Step", "IN_PROGRESS", 1)
            insertTransition(conn, blockedWfId, blockedOpenId, blockedStepId, "Blocked Move")

            // 4. guard-scheme — guard-workflow 를 default 매핑으로 배정
            val guardSchemeId = insertScheme(conn, "guard-scheme", "Guard Scheme", isDefault = false)
            conn.prepareStatement(
                "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id) " +
                    "VALUES (?, NULL, ?) ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING",
            ).use { stmt ->
                stmt.setLong(1, guardSchemeId)
                stmt.setObject(2, guardWfId)
                stmt.executeUpdate()
            }
            assignSchemeToProject(conn, GuardTestConfig.GUARD_PROJECT_KEY, "guard-scheme")

            // 5. always-blocked-scheme — always-blocked-workflow 를 default 매핑으로 배정
            val blockedSchemeId =
                insertScheme(conn, "always-blocked-scheme", "Always Blocked Scheme", isDefault = false)
            conn.prepareStatement(
                "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id) " +
                    "VALUES (?, NULL, ?) ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING",
            ).use { stmt ->
                stmt.setLong(1, blockedSchemeId)
                stmt.setObject(2, blockedWfId)
                stmt.executeUpdate()
            }
            assignSchemeToProject(conn, BLOCKED_PROJECT_KEY, "always-blocked-scheme")

            conn.commit()
        }
    }

    private fun insertProject(
        conn: java.sql.Connection,
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

    private fun insertWorkflow(
        conn: java.sql.Connection,
        key: String,
        name: String,
    ): UUID =
        conn.prepareStatement(
            "INSERT INTO workflows (key, name) VALUES (?, ?) " +
                "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
        ).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, name)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
            }
        }

    // 워크플로우 상태 INSERT helper: wfId + key + name + category + displayOrder = 6 파라미터 필수.
    // 테스트 헬퍼 함수이므로 분리보다 인라인 유지가 더 명확하다. Suppress 처리.
    @Suppress("LongParameterList")
    private fun insertState(
        conn: java.sql.Connection,
        wfId: UUID,
        key: String,
        name: String,
        category: String,
        displayOrder: Int,
    ): UUID =
        conn.prepareStatement(
            "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (workflow_id, key) DO UPDATE SET display_order = EXCLUDED.display_order RETURNING id",
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setString(2, key)
            stmt.setString(3, name)
            stmt.setString(4, category)
            stmt.setInt(5, displayOrder)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
            }
        }

    private fun insertTransition(
        conn: java.sql.Connection,
        wfId: UUID,
        fromId: UUID,
        toId: UUID,
        name: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT (workflow_id, from_state_id, to_state_id) DO NOTHING",
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setObject(2, fromId)
            stmt.setObject(3, toId)
            stmt.setString(4, name)
            stmt.executeUpdate()
        }
    }

    private fun insertScheme(
        conn: java.sql.Connection,
        key: String,
        name: String,
        isDefault: Boolean,
    ): Long =
        conn.prepareStatement(
            "INSERT INTO workflow_schemes (key, name, is_default) VALUES (?, ?, ?) " +
                "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
        ).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, name)
            stmt.setBoolean(3, isDefault)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun assignSchemeToProject(
        conn: java.sql.Connection,
        projectKey: String,
        schemeKey: String,
    ) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                SELECT p.id, s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                FROM projects p, workflow_schemes s
                WHERE p.key = '$projectKey'
                  AND s.key = '$schemeKey'
                ON CONFLICT (project_id) DO NOTHING
                """.trimIndent(),
            )
        }
    }

    private fun insertIssue(
        projectKey: String,
        summary: String,
        currentStateKey: String,
    ): String {
        DriverManager.getConnection(
            GuardTestConfig.postgres.jdbcUrl,
            GuardTestConfig.postgres.username,
            GuardTestConfig.postgres.password,
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
                conn.prepareStatement(
                    "SELECT id FROM projects WHERE key = ?",
                ).use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            // task 타입 id 조회 — V003 seed 에 의해 항상 존재, V005에서 type_id NOT NULL 추가됨
            val taskTypeId =
                conn.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입이 없습니다. V003 마이그레이션 확인 필요." }
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
                stmt.setObject(4, UUID.fromString("00000000-0000-0000-0000-000000000001"))
                stmt.setString(5, currentStateKey)
                stmt.setLong(6, taskTypeId)
                stmt.executeUpdate()
            }

            conn.commit()
            return issueKey
        }
    }

    companion object {
        /** BLOCKED 프로젝트 키 — always-blocked-workflow 배정. */
        private const val BLOCKED_PROJECT_KEY = "BLOCKED"
        private var migrated = false
        private var seeded = false
    }
}
