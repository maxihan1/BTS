// transitionIssue → plan.emitEvents → q_transition_events enqueue 전 구간 통합 테스트 (FR-NT-05 Task 2)
@file:Suppress("MaxLineLength")

package com.bts.issue.integration

import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.TransitionIssueRequest
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.TransitionEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.testsupport.insertWorkflowStatus
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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executors

/**
 * transitionIssue → plan.emitEvents → q_transition_events pgmq enqueue 전 구간 통합 테스트 (FR-NT-05 Task 2).
 *
 * ## 검증 목적
 * [IssueApplicationService.transitionIssue] 가 [TransitionEventPublisher] 로 plan.emitEvents 를
 * q_transition_events 큐에 enqueue 하는지 실 DB 트랜잭션 경로로 검증한다.
 *
 * ## 시드 전략
 * - workflow_post_actions 에 CALL_WEBHOOK(type='CALL_WEBHOOK', config JSONB) 을 시드하여
 *   전환 plan 의 emitEvents 에 WebhookRequested 1건이 포함되도록 한다.
 * - YamlSeedService 없이 SQL 직접 시드 — RequiredField validator 제외 단순 전환만 필요.
 *
 * ## 시나리오
 *
 * ### S1: CallWebhook 시드된 전환 실행 → q_transition_events 에 WebhookRequested 1건 확인
 * ### S2 롤백: 버전 충돌 예외 → 큐 0건 (트랜잭션 롤백으로 enqueue 취소)
 * ### S3 dry-run: transitionIssue 미호출(availableTransitions 만) → 큐 0건
 * ### S5 회귀: post-action 미시드 전환 → q_transition_events 0건 + q_issue_events IssueTransitioned 정상 발행
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TransitionEmitEventsPublishIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransitionEmitEventsPublishIntegrationTest {
    // ── Spring Bean 구성 ──────────────────────────────────────────────────────

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
                    .withDatabaseName("bts_emit_events_it_test")
                    .withUsername("bts")
                    .withPassword("bts_emit_events_it_test")
                    .apply { start() }

            private val spelExecutor = Executors.newCachedThreadPool()

            /** 전환 실행 actor UUID (Zod v4 형식) */
            val ACTOR_ID: UUID = UUID.fromString("aa000000-0000-4000-8000-000000000001")

            /** 웹훅 URL 고정값 */
            const val WEBHOOK_URL = "https://example.test/hook"

            /** 웹훅 HTTP 메서드 */
            const val WEBHOOK_METHOD = "POST"
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

        /**
         * [TransitionEventPublisher] — plan.emitEvents 를 q_transition_events 큐에 enqueue.
         * 이 테스트의 핵심 배선 대상이다.
         */
        @Bean
        open fun transitionEventPublisher(
            dsl: DSLContext,
            objectMapper: ObjectMapper,
        ): TransitionEventPublisher = TransitionEventPublisher(dsl, objectMapper)

        @Bean
        @Profile("test")
        open fun alwaysAllowIssuePermissionResolver() = AlwaysAllowIssuePermissionResolver()

        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                override fun exists(userId: UUID): Boolean = true
            }

        // ── project-workflow 빈 (실 구현체) ───────────────────────────────────

        @Bean
        open fun workflowRepository(dsl: DSLContext): WorkflowRepository = WorkflowRepository(dsl)

        @Bean
        open fun workflowCache(
            workflowRepo: WorkflowRepository,
            dsl: DSLContext,
        ): WorkflowCache = WorkflowCache(workflowRepo, dsl)

        @Bean
        open fun workflowDefinitionRepository(dsl: DSLContext): DefaultWorkflowDefinitionRepository =
            DefaultWorkflowDefinitionRepository(dsl)

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
        open fun workflowTransitionAdapter(engine: WorkflowEngine): WorkflowTransitionAdapter = WorkflowTransitionAdapter(engine)

        @Bean
        open fun workflowSchemeRepository(dsl: DSLContext): WorkflowSchemeRepository = WorkflowSchemeRepository(dsl)

        @Bean
        open fun projectWorkflowSchemeAssignmentRepository(dsl: DSLContext): ProjectWorkflowSchemeAssignmentRepository =
            ProjectWorkflowSchemeAssignmentRepository(dsl)

        @Bean
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

        // ── Application Service ───────────────────────────────────────────────

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
            transitionEventPublisher: TransitionEventPublisher,
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
                historyRecorder = mockk(relaxed = true),
                transitionEventPublisher = transitionEventPublisher,
            )
    }

    // ── 테스트 인프라 ────────────────────────────────────────────────────────────

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    companion object {
        private val log = LoggerFactory.getLogger(TransitionEmitEventsPublishIntegrationTest::class.java)

        private var migrated = false
        private var seeded = false

        /**
         * 테스트 전용 프로젝트 키 — CallWebhook post-action 이 시드된 전환을 보유한다.
         * IssueTransitionValidatorEndToEndIntegrationTest("VALIDE2E")와 겹치지 않는 이름 사용.
         */
        private const val PROJECT_KEY = "EMITEV"

        /** CallWebhook post-action 이 없는 단순 전환 검증 프로젝트 */
        private const val PROJECT_KEY_NO_ACTION = "EMITEVNA"
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProjectsAndWorkflows()
            seeded = true
        }
    }

    @BeforeEach
    fun cleanQueuesAndIssues() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("SELECT pgmq.purge_queue('q_transition_events')")
                stmt.execute("SELECT pgmq.purge_queue('q_issue_events')")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY_NO_ACTION-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key IN ('$PROJECT_KEY', '$PROJECT_KEY_NO_ACTION')")
            }
        }
    }

    // ── S1: CallWebhook 시드된 전환 실행 → q_transition_events WebhookRequested 1건 ──

    /**
     * S1 — CallWebhook post-action 이 시드된 전환 실행 시 q_transition_events 에 WebhookRequested 이벤트 1건이 enqueue 된다.
     *
     * Given  이슈 currentStateKey = "open", workflow_post_actions 에 CALL_WEBHOOK 시드됨
     * When   transitionIssue(toStateKey = "done")
     * Then   q_transition_events 에서 WebhookRequested 메시지 1건 확인
     *         payload.issueKey, payload.url, payload.method 검증
     */
    @Test
    fun `S1 — CallWebhook 시드 전환 실행 후 q_transition_events 에 WebhookRequested 가 enqueue 된다`() {
        val issueKey = insertIssue(PROJECT_KEY, "emit events 검증 이슈 S1", "open")
        val actor = ActorId(TestConfig.ACTOR_ID)
        val request = TransitionIssueRequest(toStateKey = "done", expectedVersion = 1L)

        issueApplicationService.transitionIssue(actor, IssueKey(issueKey), request)

        val messages = readTransitionQueueMessages(maxQty = 5)
        val webhookMsg = messages.firstOrNull { it.get("type")?.asText() == "WebhookRequested" }

        check(webhookMsg != null) {
            "transitionIssue 후 q_transition_events 에 WebhookRequested 이벤트가 없습니다. " +
                "plan.emitEvents 가 버려지고 있을 가능성이 있습니다. 전체 메시지: ${messages.size}건"
        }

        val payload = webhookMsg.get("payload")
        check(payload != null) { "WebhookRequested 메시지에 payload 필드가 없습니다." }
        check(payload.get("issueKey")?.asText() == issueKey) {
            "payload.issueKey 가 '$issueKey' 여야 하지만 '${payload.get("issueKey")?.asText()}' 입니다."
        }
        check(payload.get("url")?.asText() == TestConfig.WEBHOOK_URL) {
            "payload.url 이 '${TestConfig.WEBHOOK_URL}' 여야 하지만 '${payload.get("url")?.asText()}' 입니다."
        }
        check(payload.get("method")?.asText() == TestConfig.WEBHOOK_METHOD) {
            "payload.method 가 '${TestConfig.WEBHOOK_METHOD}' 여야 하지만 '${payload.get("method")?.asText()}' 입니다."
        }

        log.info("S1 통과 — q_transition_events WebhookRequested 1건 확인 issueKey={}", issueKey)
    }

    // ── S2 롤백: 버전 충돌 예외 → 큐 0건 ────────────────────────────────────────

    /**
     * S2 — 버전 충돌(expectedVersion 불일치)로 전환이 실패하면 트랜잭션이 롤백되어 q_transition_events 에 메시지가 없다.
     *
     * Given  이슈 currentStateKey = "open", version = 1
     * When   transitionIssue(toStateKey = "done", expectedVersion = 99) — 존재하지 않는 버전
     * Then   IssueVersionConflictException 발생 + q_transition_events 0건
     */
    @Test
    fun `S2 — 버전 충돌로 전환 실패 시 q_transition_events 에 메시지가 없다`() {
        val issueKey = insertIssue(PROJECT_KEY, "emit events 검증 이슈 S2", "open")
        val actor = ActorId(TestConfig.ACTOR_ID)
        // expectedVersion = 99 는 DB version = 1 과 충돌하여 applyTransition 이 0 row 반환 → 예외
        val request = TransitionIssueRequest(toStateKey = "done", expectedVersion = 99L)

        runCatching {
            issueApplicationService.transitionIssue(actor, IssueKey(issueKey), request)
        }

        val messages = readTransitionQueueMessages(maxQty = 5)
        check(messages.isEmpty()) {
            "버전 충돌 롤백 후에도 q_transition_events 에 메시지 ${messages.size}건이 남아있습니다. " +
                "enqueue 와 상태변경이 같은 트랜잭션에 묶여있지 않을 가능성이 있습니다."
        }

        log.info("S2 통과 — 버전 충돌 롤백 후 q_transition_events 0건 확인")
    }

    // ── S3 dry-run: transitionIssue 미호출 → 큐 0건 ──────────────────────────────

    /**
     * S3 — transitionIssue 를 호출하지 않으면(dry-run 또는 availableTransitions 만 호출) q_transition_events 에 메시지가 없다.
     *
     * Given  이슈 시드됨
     * When   issueApplicationService.availableTransitions 만 호출 (transitionIssue 미호출)
     * Then   q_transition_events 0건
     */
    @Test
    fun `S3 — transitionIssue 미호출(dry-run) 시 q_transition_events 에 메시지가 없다`() {
        insertIssue(PROJECT_KEY, "emit events 검증 이슈 S3", "open")
        // transitionIssue 를 호출하지 않음 — availableTransitions 조회만

        val messages = readTransitionQueueMessages(maxQty = 5)
        check(messages.isEmpty()) {
            "transitionIssue 미호출인데 q_transition_events 에 메시지 ${messages.size}건이 있습니다."
        }

        log.info("S3 통과 — dry-run(transitionIssue 미호출) 후 q_transition_events 0건 확인")
    }

    // ── S5 회귀: post-action 미시드 전환 → q_transition_events 0건 + q_issue_events 정상 ──

    /**
     * S5 — post-action 이 시드되지 않은 전환 실행 시 q_transition_events 에 메시지가 없고
     *       q_issue_events 에 IssueTransitioned 이벤트가 정상 발행된다.
     *
     * Given  이슈 currentStateKey = "open", 전환에 workflow_post_actions 미시드
     * When   transitionIssue(toStateKey = "done")
     * Then   q_transition_events 0건
     * And    q_issue_events 에 issue.transitioned 이벤트 1건 (기존 IssueTransitioned 정상 발행)
     */
    @Test
    fun `S5 — post-action 미시드 전환 실행 시 q_transition_events 0건 + q_issue_events IssueTransitioned 정상 발행`() {
        val issueKey = insertIssue(PROJECT_KEY_NO_ACTION, "emit events 회귀 이슈 S5", "open")
        val actor = ActorId(TestConfig.ACTOR_ID)
        val request = TransitionIssueRequest(toStateKey = "done", expectedVersion = 1L)

        issueApplicationService.transitionIssue(actor, IssueKey(issueKey), request)

        val transitionQueueMessages = readTransitionQueueMessages(maxQty = 5)
        check(transitionQueueMessages.isEmpty()) {
            "post-action 미시드 전환인데 q_transition_events 에 메시지 ${transitionQueueMessages.size}건이 있습니다."
        }

        val issueQueueMessages = readIssueQueueMessages(maxQty = 5)
        val transitionedEvent = issueQueueMessages.firstOrNull { it.get("type")?.asText() == "issue.transitioned" }
        check(transitionedEvent != null) {
            "post-action 미시드 전환 후 q_issue_events 에 issue.transitioned 이벤트가 없습니다. " +
                "기존 IssueTransitioned 발행 경로가 회귀됐을 수 있습니다. 전체 메시지: ${issueQueueMessages.size}건"
        }

        log.info("S5 통과 — q_transition_events 0건 + q_issue_events issue.transitioned 1건 확인 issueKey={}", issueKey)
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
     * 테스트 프로젝트, 워크플로우(open→done 전환), post-action 시드.
     *
     * PROJECT_KEY: CALL_WEBHOOK post-action 이 시드된 open→done 전환 보유.
     * PROJECT_KEY_NO_ACTION: post-action 이 없는 open→done 전환만 보유 (S5 회귀 검증용).
     */
    @Suppress("LongMethod")
    private fun seedProjectsAndWorkflows() {
        conn().use { c ->
            c.autoCommit = false

            // ── PROJECT_KEY 프로젝트 + 워크플로우 시드 ─────────────────────────
            insertProject(c, PROJECT_KEY, "Emit Events Integration Test Project")
            val wfId = insertWorkflow(c, "emit-events-test-wf", "Emit Events Test Workflow")
            insertWorkflowState(c, wfId, "open", "Open", "TODO", 0)
            insertWorkflowState(c, wfId, "done", "Done", "DONE", 1)
            val transitionId = insertWorkflowTransition(c, wfId, "open", "done", "To Done")

            // workflow_post_actions: CALL_WEBHOOK 시드 — open→done 전환 실행 시 WebhookRequested 발행
            c.prepareStatement(
                "INSERT INTO workflow_post_actions (transition_id, type, config) " +
                    "VALUES (?, 'CALL_WEBHOOK', ?::jsonb)",
            ).use { stmt ->
                stmt.setObject(1, transitionId)
                stmt.setString(
                    2,
                    """{"url":"${TestConfig.WEBHOOK_URL}","method":"${TestConfig.WEBHOOK_METHOD}"}""",
                )
                stmt.executeUpdate()
            }

            val schemeId = insertSchemeWithDefaultMapping(c, "emit-events-test-scheme", "Emit Events Test Scheme", wfId)
            assignSchemeToProject(c, PROJECT_KEY, schemeId)

            // ── PROJECT_KEY_NO_ACTION 프로젝트 + 워크플로우 시드 (post-action 미시드) ──
            insertProject(c, PROJECT_KEY_NO_ACTION, "Emit Events No Action Test Project")
            val wfIdNoAction = insertWorkflow(c, "emit-events-no-action-wf", "Emit Events No Action Workflow")
            insertWorkflowState(c, wfIdNoAction, "open", "Open", "TODO", 0)
            insertWorkflowState(c, wfIdNoAction, "done", "Done", "DONE", 1)
            insertWorkflowTransition(c, wfIdNoAction, "open", "done", "To Done")
            // workflow_post_actions 미시드

            val schemeIdNoAction =
                insertSchemeWithDefaultMapping(c, "emit-events-no-action-scheme", "Emit Events No Action Scheme", wfIdNoAction)
            assignSchemeToProject(c, PROJECT_KEY_NO_ACTION, schemeIdNoAction)

            c.commit()
            log.info("seedProjectsAndWorkflows 완료 — projectKey={}, noActionKey={}", PROJECT_KEY, PROJECT_KEY_NO_ACTION)
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

    private fun insertWorkflow(
        conn: Connection,
        key: String,
        name: String,
    ): UUID =
        conn.prepareStatement(
            "INSERT INTO workflows (key, name) VALUES (?, ?) " +
                "ON CONFLICT (key) WHERE deleted_at IS NULL DO UPDATE SET name = EXCLUDED.name RETURNING id",
        ).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, name)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
            }
        }

    // workflow_states 컬럼과 1:1 대응하는 테스트 시드 헬퍼라 파라미터 수가 컬럼 수를 따른다.
    @Suppress("LongParameterList")
    private fun insertWorkflowState(
        conn: Connection,
        workflowId: UUID,
        key: String,
        name: String,
        category: String,
        displayOrder: Int,
    ) {
        insertWorkflowStatus(conn, workflowId, key, name, category, displayOrder)
    }

    /**
     * workflow_transitions 를 삽입하고 생성된 transition UUID 를 반환한다.
     *
     * workflow_transitions 는 from_state_id, to_state_id(UUID FK)를 사용하므로
     * 먼저 workflow_states 에서 상태 UUID 를 조회한 뒤 INSERT 한다.
     *
     * @param conn DB 커넥션.
     * @param workflowId 워크플로우 UUID.
     * @param fromStateKey 전환 출발 상태 키 문자열.
     * @param toStateKey 전환 도착 상태 키 문자열.
     * @param name 전환 이름.
     * @return 삽입된 transition UUID.
     */
    private fun insertWorkflowTransition(
        conn: Connection,
        workflowId: UUID,
        fromStateKey: String,
        toStateKey: String,
        name: String,
    ): UUID {
        val fromStateId = findWorkflowStateId(conn, workflowId, fromStateKey)
        val toStateId = findWorkflowStateId(conn, workflowId, toStateKey)

        conn.prepareStatement(
            "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) " +
                "SELECT ?, ?, ?, ? WHERE NOT EXISTS (" +
                "SELECT 1 FROM workflow_transitions " +
                "WHERE workflow_id = ? AND from_state_id = ? AND to_state_id = ?)",
        ).use { stmt ->
            stmt.setObject(1, workflowId)
            stmt.setObject(2, fromStateId)
            stmt.setObject(3, toStateId)
            stmt.setString(4, name)
            stmt.setObject(5, workflowId)
            stmt.setObject(6, fromStateId)
            stmt.setObject(7, toStateId)
            stmt.executeUpdate()
        }

        return findWorkflowTransitionId(conn, workflowId, fromStateId, toStateId)
    }

    /**
     * workflow_transitions 에서 (workflowId, fromStateId, toStateId) 로 전환 UUID 를 조회한다.
     *
     * V207 이 상태쌍 UNIQUE 를 제거해 같은 쌍에 복수 전환이 가능하므로 가장 먼저 심긴 행을 취한다.
     *
     * @param conn DB 커넥션.
     * @param workflowId 워크플로우 UUID.
     * @param fromStateId 출발 상태 UUID.
     * @param toStateId 도착 상태 UUID.
     * @return 전환 UUID.
     */
    private fun findWorkflowTransitionId(
        conn: Connection,
        workflowId: UUID,
        fromStateId: UUID,
        toStateId: UUID,
    ): UUID =
        conn.prepareStatement(
            "SELECT id FROM workflow_transitions " +
                "WHERE workflow_id = ? AND from_state_id = ? AND to_state_id = ? " +
                "ORDER BY created_at, id LIMIT 1",
        ).use { stmt ->
            stmt.setObject(1, workflowId)
            stmt.setObject(2, fromStateId)
            stmt.setObject(3, toStateId)
            stmt.executeQuery().use { rs ->
                check(rs.next()) {
                    "workflow_transitions 시드 실패 — workflowId=$workflowId, $fromStateId->$toStateId"
                }
                rs.getObject(1) as UUID
            }
        }

    /**
     * workflow_states 에서 (workflowId, key) 로 상태 UUID 를 조회한다.
     *
     * @param conn DB 커넥션.
     * @param workflowId 워크플로우 UUID.
     * @param stateKey 상태 키 문자열.
     * @return 상태 UUID.
     */
    private fun findWorkflowStateId(
        conn: Connection,
        workflowId: UUID,
        stateKey: String,
    ): UUID =
        conn.prepareStatement(
            "SELECT id FROM workflow_states WHERE workflow_id = ? AND key = ?",
        ).use { stmt ->
            stmt.setObject(1, workflowId)
            stmt.setString(2, stateKey)
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "workflow_states 에 workflowId=$workflowId, key=$stateKey 가 없습니다." }
                rs.getObject(1) as UUID
            }
        }

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

    private fun assignSchemeToProject(
        conn: Connection,
        projectKey: String,
        schemeId: Long,
    ) {
        conn.prepareStatement(
            "INSERT INTO project_workflow_scheme_assignments " +
                "(project_id, workflow_scheme_id, assigned_at, assigned_by) " +
                "SELECT p.id, ?, NOW(), '00000000-0000-4000-8000-000000000000'::uuid " +
                "FROM projects p WHERE p.key = ? " +
                "ON CONFLICT (project_id) DO NOTHING",
        ).use { stmt ->
            stmt.setLong(1, schemeId)
            stmt.setString(2, projectKey)
            stmt.executeUpdate()
        }
    }

    /**
     * 테스트용 이슈를 DB 에 직접 삽입하고 이슈 키를 반환한다.
     *
     * @param projectKey 프로젝트 키.
     * @param summary 이슈 제목.
     * @param currentStateKey 초기 상태 키.
     * @return 삽입된 이슈 키 (예: "EMITEV-1").
     */
    @Suppress("LongMethod")
    private fun insertIssue(
        projectKey: String,
        summary: String,
        currentStateKey: String,
    ): String {
        conn().use { c ->
            c.autoCommit = false

            val seq =
                c.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            val issueKey = "$projectKey-$seq"

            val projectId: UUID =
                c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "$projectKey 프로젝트가 없습니다." }
                        rs.getObject(1) as UUID
                    }
                }

            val taskTypeId: Long =
                c.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음. V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            c.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "VALUES (?, ?, ?, ?, ?, 1, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, TestConfig.ACTOR_ID)
                stmt.setString(5, currentStateKey)
                stmt.setLong(6, taskTypeId)
                stmt.executeUpdate()
            }

            c.commit()
            return issueKey
        }
    }

    /**
     * q_transition_events 큐에서 최대 [maxQty] 건을 읽어 JSON 노드 목록으로 반환한다.
     *
     * visibility_timeout = 1초로 읽어 다른 소비자 간섭을 최소화한다.
     */
    private fun readTransitionQueueMessages(maxQty: Int): List<com.fasterxml.jackson.databind.JsonNode> {
        val dsl =
            DSL.using(
                conn(),
                SQLDialect.POSTGRES,
            )
        return dsl.fetch("SELECT * FROM pgmq.read('${TransitionEventPublisher.QUEUE_NAME}', 1, $maxQty)")
            .map { record ->
                val body = record.get("message", String::class.java)
                objectMapper.readTree(body)
            }
    }

    /**
     * q_issue_events 큐에서 최대 [maxQty] 건을 읽어 JSON 노드 목록으로 반환한다.
     */
    private fun readIssueQueueMessages(maxQty: Int): List<com.fasterxml.jackson.databind.JsonNode> {
        val dsl =
            DSL.using(
                conn(),
                SQLDialect.POSTGRES,
            )
        return dsl.fetch("SELECT * FROM pgmq.read('q_issue_events', 1, $maxQty)")
            .map { record ->
                val body = record.get("message", String::class.java)
                objectMapper.readTree(body)
            }
    }

    private fun conn(): Connection =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
