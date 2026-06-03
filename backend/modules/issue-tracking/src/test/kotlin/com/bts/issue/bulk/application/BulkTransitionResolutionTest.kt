// 일괄 전이 resolutionId 전달 통합 테스트 — FR-IS-07 Task B13

package com.bts.issue.bulk.application

import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.bulk.domain.BulkOperationStatus
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.event.BulkOperationEnqueuePublisher
import com.bts.issue.bulk.event.BulkOperationEventPublisher
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.bulk.worker.BulkOperationCompleter
import com.bts.issue.bulk.worker.BulkOperationWorker
import com.bts.issue.domain.ActorId
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
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
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * 일괄 전이 payload 에 resolutionId 가 포함·전달됨을 검증하는 통합 테스트.
 *
 * Testcontainers PostgreSQL(pgmq 포함) + 전체 Spring 빈 스택 실기동.
 * B7 에서 시드된 DONE 진입 전이에 RequiredField(resolution) validator 는
 * 이 테스트의 [TestConfig] 에서 `workflowDefinitionRepository` mock 이 emptyList() 를 반환하므로
 * 활성화되지 않는다. resolution 영속 동작 검증에 집중한다.
 *
 * ## 검증 시나리오
 *
 * ### S1. DONE 일괄 전이 + resolutionId 제공 → 각 이슈 resolution_id 영속 + 모든 항목 SUCCEEDED
 * Given  이슈 2건 (in_review 상태, in_review→done 전이 가능)
 * When   BulkTransitionPayload(toStateKey="done", resolutionId=FIXED_RESOLUTION_ID) 일괄 전이
 * Then   두 이슈 모두 issues.resolution_id == FIXED_RESOLUTION_ID
 *        BulkOperation COMPLETED, succeededCount=2, failedCount=0
 *
 * ### S2. DONE 일괄 전이 + resolutionId 누락 → 단건 서비스가 resolution 없음을 허용하므로 SUCCEEDED
 * (B7 validator 는 이 TestConfig 에서 비활성화됨 — validator 를 활성화하는 E2E 는 별도)
 * Given  이슈 1건 (in_review 상태)
 * When   BulkTransitionPayload(toStateKey="done", resolutionId=null)
 * Then   이슈 issues.resolution_id IS NULL
 *        항목 SUCCEEDED (validator 없으므로 통과)
 *
 * ### S3. non-null resolutionId 가 payload 에서 Applier 까지 전달됨 — 부분실패 경로 없음
 * Given  이슈 1건 (in_review 상태) + 존재하지 않는 resolutionId
 * When   BulkTransitionPayload(toStateKey="done", resolutionId=NONEXISTENT_RESOLUTION_ID)
 * Then   해당 이슈 항목 FAILED (ResolutionNotFoundException — 존재성 검증 실패)
 *        BulkOperation COMPLETED, failedCount=1
 *
 * ## 설계 결정
 * - workflowDefinitionRepository 를 mock (emptyList) 으로 두어 B7 RequiredField validator 비활성화.
 *   이는 B13 명세가 "resolutionId 전달 경로" 검증을 목적으로 하기 때문이다.
 * - B7 validator 활성화 경로는 BulkOperationIntegrationTest 에서 DefaultWorkflowDefinitionRepository
 *   + YamlSeedService 를 wire 한 별도 통합 E2E 에서 검증한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BulkTransitionResolutionTest.TestConfig::class])
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkTransitionResolutionTest {
    // ── Spring Bean 구성 ────────────────────────────────────────────────────────

    @Configuration
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
                    .withDatabaseName("bts_bulk_res_it_test")
                    .withUsername("bts")
                    .withPassword("bts_bulk_res_it_test")
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

        // ── IssueApplicationService ───────────────────────────────────────────

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
                clock = clock,
            )

        // ── Bulk 빈 ──────────────────────────────────────────────────────────

        @Bean
        open fun bulkOperationRepository(
            dsl: DSLContext,
            objectMapper: ObjectMapper,
            clock: Clock,
        ): BulkOperationRepository = BulkOperationRepository(dsl, objectMapper, clock)

        @Bean
        @Suppress("MaxLineLength")
        open fun bulkOperationEnqueuePublisher(dsl: DSLContext): BulkOperationEnqueuePublisher = BulkOperationEnqueuePublisher(dsl)

        @Bean
        @Suppress("MaxLineLength")
        open fun bulkOperationEventPublisher(dsl: DSLContext): BulkOperationEventPublisher = BulkOperationEventPublisher(dsl)

        @Bean
        open fun bulkOperationApplicationService(
            repo: BulkOperationRepository,
            enqueuePublisher: BulkOperationEnqueuePublisher,
        ): BulkOperationApplicationService = BulkOperationApplicationService(repo, enqueuePublisher)

        @Bean
        open fun bulkItemApplier(
            issueService: IssueApplicationService,
            bulkRepo: BulkOperationRepository,
        ): BulkItemApplier = BulkItemApplier(issueService, bulkRepo)

        @Bean
        @Suppress("MaxLineLength")
        open fun bulkItemFailureRecorder(bulkRepo: BulkOperationRepository): BulkItemFailureRecorder = BulkItemFailureRecorder(bulkRepo)

        @Bean
        open fun bulkItemExecutor(
            applier: BulkItemApplier,
            failureRecorder: BulkItemFailureRecorder,
        ): BulkItemExecutor = BulkItemExecutor(applier, failureRecorder)

        @Bean
        open fun bulkOperationProcessor(
            bulkRepo: BulkOperationRepository,
            itemExecutor: BulkItemExecutor,
        ): BulkOperationProcessor = BulkOperationProcessor(bulkRepo, itemExecutor)

        @Bean
        open fun bulkOperationCompleter(
            bulkRepo: BulkOperationRepository,
            eventPublisher: BulkOperationEventPublisher,
        ): BulkOperationCompleter = BulkOperationCompleter(bulkRepo, eventPublisher)

        @Bean
        open fun bulkOperationWorker(
            dsl: DSLContext,
            bulkRepo: BulkOperationRepository,
            processor: BulkOperationProcessor,
            completer: BulkOperationCompleter,
        ): BulkOperationWorker = BulkOperationWorker(dsl, bulkRepo, processor, completer)
    }

    // ── 주입 빈 ────────────────────────────────────────────────────────────────

    @Autowired
    lateinit var bulkAppService: BulkOperationApplicationService

    @Autowired
    lateinit var bulkRepo: BulkOperationRepository

    @Autowired
    lateinit var worker: BulkOperationWorker

    @Autowired
    lateinit var dsl: DSLContext

    // ── 전역 상수 ──────────────────────────────────────────────────────────────

    companion object {
        private const val PROJECT_KEY = "BULKRES"
        private val ACTOR_ID = ActorId(UUID.fromString("00000000-0000-4000-8000-000000000001"))

        /**
         * V011 seed 의 'fixed' resolution 고정 UUID.
         * Zod v4 형식 (메모리 zod-v4-uuid-fixture-strictness) — 4번째 그룹 8 시작.
         */
        private val FIXED_RESOLUTION_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")

        /** 존재하지 않는 위조 UUID — S3 검증용. Zod v4 형식. */
        private val NONEXISTENT_RESOLUTION_ID: UUID = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff")

        private var bootstrapped = false
    }

    // ── 생명주기 ───────────────────────────────────────────────────────────────

    @BeforeAll
    fun setUpAll() {
        if (!bootstrapped) {
            applyMigrations()
            seedFixtures()
            bootstrapped = true
        }
    }

    @BeforeEach
    fun cleanBetweenTests() {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("SELECT pgmq.purge_queue('${BulkOperationEnqueuePublisher.QUEUE_NAME}')")
                stmt.execute("SELECT pgmq.purge_queue('${BulkOperationEventPublisher.QUEUE_NAME}')")
                stmt.execute("DELETE FROM bulk_operation_items")
                stmt.execute("DELETE FROM bulk_operations")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    // ── S1. DONE 일괄 전이 + resolutionId → 영속 + SUCCEEDED ──────────────────

    /**
     * S1 — 일괄 전이 payload 에 resolutionId 를 제공하면 각 이슈의 resolution_id 가 영속되고
     * 모든 항목이 SUCCEEDED 로 완료된다.
     *
     * Given  이슈 2건 (in_review 상태 — in_review→done 전이 가능)
     * When   BulkTransitionPayload(toStateKey="done", resolutionId=FIXED_RESOLUTION_ID) 접수 → 워커 실행
     * Then   두 이슈 issues.resolution_id == FIXED_RESOLUTION_ID
     *        BulkOperation COMPLETED, succeededCount=2, failedCount=0
     */
    @Test
    fun `S1 - DONE 일괄 전이 + resolutionId 제공 시 각 이슈 resolution_id 영속 및 모든 항목 SUCCEEDED`() {
        val key1 = insertIssue("DONE 이슈1", "in_review")
        val key2 = insertIssue("DONE 이슈2", "in_review")

        val opId =
            bulkAppService.submit(
                ACTOR_ID,
                BulkUpdateRequest(
                    issueKeys = listOf(key1, key2),
                    operationType = BulkOperationType.BULK_TRANSITION,
                    editPayload = null,
                    transitionPayload =
                        BulkTransitionPayload(
                            toStateKey = "done",
                            resolutionId = FIXED_RESOLUTION_ID,
                        ),
                ),
            )

        worker.pollAndProcess()

        val op = requireNotNull(bulkRepo.findById(opId)) { "작업을 찾을 수 없음" }
        assertThat(op.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(op.succeededCount).isEqualTo(2)
        assertThat(op.failedCount).isEqualTo(0)

        val items = bulkRepo.findItemsByOperationId(opId)
        assertThat(items).allMatch { it.status == ItemStatus.SUCCEEDED }

        // DB 직접 검증 — resolution_id 영속
        assertThat(queryResolutionId(key1)).isEqualTo(FIXED_RESOLUTION_ID)
        assertThat(queryResolutionId(key2)).isEqualTo(FIXED_RESOLUTION_ID)
    }

    // ── S2. DONE 일괄 전이 + resolutionId=null → resolution_id IS NULL + SUCCEEDED ─

    /**
     * S2 — resolutionId=null 일 때 issues.resolution_id IS NULL 이고 항목이 SUCCEEDED 된다.
     *
     * B7 RequiredField(resolution) validator 는 이 TestConfig 에서 비활성화되어 있으므로
     * resolutionId 없이도 DONE 전이가 통과한다.
     *
     * Given  이슈 1건 (in_review 상태)
     * When   BulkTransitionPayload(toStateKey="done", resolutionId=null)
     * Then   issues.resolution_id IS NULL
     *        항목 SUCCEEDED
     */
    @Test
    fun `S2 - resolutionId null 일 때 resolution_id IS NULL 이고 항목 SUCCEEDED`() {
        val key1 = insertIssue("resolutionNull 이슈", "in_review")

        val opId =
            bulkAppService.submit(
                ACTOR_ID,
                BulkUpdateRequest(
                    issueKeys = listOf(key1),
                    operationType = BulkOperationType.BULK_TRANSITION,
                    editPayload = null,
                    transitionPayload =
                        BulkTransitionPayload(
                            toStateKey = "done",
                            resolutionId = null,
                        ),
                ),
            )

        worker.pollAndProcess()

        val op = requireNotNull(bulkRepo.findById(opId)) { "작업을 찾을 수 없음" }
        assertThat(op.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(op.succeededCount).isEqualTo(1)
        assertThat(op.failedCount).isEqualTo(0)

        assertThat(queryResolutionId(key1)).isNull()
    }

    // ── S3. 존재하지 않는 resolutionId → 항목 FAILED ──────────────────────────

    /**
     * S3 — 존재하지 않는 resolutionId 는 Applier 에서 ResolutionNotFoundException 을 유발하고
     * 해당 이슈 항목이 FAILED 로 기록된다.
     *
     * 이는 resolutionId 가 payload 에서 TransitionIssueRequest 까지 실제로 전달되는지를
     * (존재성 검증 경로로) 간접 증명한다.
     *
     * Given  이슈 1건 (in_review 상태) + 존재하지 않는 resolutionId
     * When   BulkTransitionPayload(toStateKey="done", resolutionId=NONEXISTENT_RESOLUTION_ID)
     * Then   항목 FAILED (부분실패 기록)
     *        BulkOperation COMPLETED, failedCount=1
     */
    @Test
    fun `S3 - 존재하지 않는 resolutionId 는 항목 FAILED 로 기록되어 전달 경로가 검증된다`() {
        val key1 = insertIssue("존재안하는 resolution 이슈", "in_review")

        val opId =
            bulkAppService.submit(
                ACTOR_ID,
                BulkUpdateRequest(
                    issueKeys = listOf(key1),
                    operationType = BulkOperationType.BULK_TRANSITION,
                    editPayload = null,
                    transitionPayload =
                        BulkTransitionPayload(
                            toStateKey = "done",
                            resolutionId = NONEXISTENT_RESOLUTION_ID,
                        ),
                ),
            )

        worker.pollAndProcess()

        val op = requireNotNull(bulkRepo.findById(opId)) { "작업을 찾을 수 없음" }
        assertThat(op.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(op.failedCount).isEqualTo(1)
        assertThat(op.succeededCount).isEqualTo(0)

        val items = bulkRepo.findItemsByOperationId(opId)
        assertThat(items).allMatch { it.status == ItemStatus.FAILED }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — issue-tracking V001~V011 + project-workflow V200~V202.
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
     * 테스트 픽스처 seed.
     *
     * 1. BULKRES 프로젝트 삽입
     * 2. resolution-bulk-wf 워크플로우: open→in_review→done
     * 3. resolution-bulk-scheme 스킴 + default mapping
     * 4. BULKRES 프로젝트에 스킴 배정
     */
    @Suppress("LongMethod")
    private fun seedFixtures() {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false

            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Bulk Resolution Integration Test Project")
                stmt.executeUpdate()
            }

            val wfId: UUID =
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('resolution-bulk-wf', 'Resolution Bulk WF') " +
                        "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val openId = insertState(conn, wfId, "open", "Open", "TODO", 0)
            val inReviewId = insertState(conn, wfId, "in_review", "In Review", "IN_PROGRESS", 1)
            val doneId = insertState(conn, wfId, "done", "Done", "DONE", 2)

            insertTransition(conn, wfId, openId, inReviewId, "Start Review")
            insertTransition(conn, wfId, inReviewId, doneId, "Approve")

            conn.prepareStatement(
                "INSERT INTO workflow_schemes (key, name, is_default) " +
                    "VALUES ('resolution-bulk-scheme', 'Resolution Bulk Scheme', false) " +
                    "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
            ).use { stmt ->
                val schemeId =
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }

                conn.prepareStatement(
                    "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id) " +
                        "VALUES (?, NULL, ?) ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING",
                ).use { mappingStmt ->
                    mappingStmt.setLong(1, schemeId)
                    mappingStmt.setObject(2, wfId)
                    mappingStmt.executeUpdate()
                }

                conn.prepareStatement(
                    "INSERT INTO project_workflow_scheme_assignments " +
                        "(project_id, workflow_scheme_id, assigned_at, assigned_by) " +
                        "SELECT p.id, ?, NOW(), '00000000-0000-4000-8000-000000000000'::uuid " +
                        "FROM projects p WHERE p.key = ? " +
                        "ON CONFLICT (project_id) DO NOTHING",
                ).use { assignStmt ->
                    assignStmt.setLong(1, schemeId)
                    assignStmt.setString(2, PROJECT_KEY)
                    assignStmt.executeUpdate()
                }
            }

            conn.commit()
        }
    }

    @Suppress("LongParameterList")
    private fun insertState(
        conn: Connection,
        wfId: UUID,
        key: String,
        name: String,
        category: String,
        displayOrder: Int,
    ): UUID =
        conn.prepareStatement(
            "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (workflow_id, key) " +
                "DO UPDATE SET display_order = EXCLUDED.display_order RETURNING id",
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
        conn: Connection,
        wfId: UUID,
        fromId: UUID,
        toId: UUID,
        transitionName: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT (workflow_id, from_state_id, to_state_id) DO NOTHING",
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setObject(2, fromId)
            stmt.setObject(3, toId)
            stmt.setString(4, transitionName)
            stmt.executeUpdate()
        }
    }

    /**
     * 테스트용 이슈를 DB 에 직접 삽입하고 이슈 키를 반환한다.
     *
     * @param summary 이슈 제목.
     * @param currentStateKey 초기 상태 키.
     * @return 삽입된 이슈 키 (예: "BULKRES-1").
     */
    private fun insertIssue(
        summary: String,
        currentStateKey: String,
    ): String =
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
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            val issueKey = "$PROJECT_KEY-$seq"

            val projectId =
                conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
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
                stmt.setObject(4, ACTOR_ID.value)
                stmt.setString(5, currentStateKey)
                stmt.setLong(6, taskTypeId)
                stmt.executeUpdate()
            }

            conn.commit()
            issueKey
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
