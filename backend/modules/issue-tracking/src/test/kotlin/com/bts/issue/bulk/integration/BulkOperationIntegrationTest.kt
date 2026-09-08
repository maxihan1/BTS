// 일괄 작업 end-to-end 통합 테스트 — enqueue→consume(워커)→처리→결과기록 전체 흐름을 실 DB로 검증
@file:Suppress("MaxLineLength")

package com.bts.issue.bulk.integration

import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.bulk.application.BulkEditPayload
import com.bts.issue.bulk.application.BulkItemApplier
import com.bts.issue.bulk.application.BulkItemExecutor
import com.bts.issue.bulk.application.BulkItemFailureRecorder
import com.bts.issue.bulk.application.BulkOperationApplicationService
import com.bts.issue.bulk.application.BulkOperationProcessor
import com.bts.issue.bulk.application.BulkTransitionPayload
import com.bts.issue.bulk.application.BulkUpdateRequest
import com.bts.issue.bulk.domain.BULK_OPERATION_MAX_SIZE
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
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.repository.IssueRepository
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 일괄 작업 end-to-end 통합 테스트.
 *
 * Testcontainers PostgreSQL(pgmq 포함) + 전체 Spring 빈 스택을 실제로 기동하여
 * "enqueue → 워커 consume → processor 처리 → 결과 기록" 흐름을 검증한다.
 *
 * ## 검증 시나리오
 * - S1. 일괄 편집(BULK_EDIT) 전체 흐름 — submit→worker→결과 확인
 * - S2. 워커 재전달 멱등 — 같은 메시지를 2회 읽어도 SUCCEEDED 항목 스킵, 카운트 불변
 * - S3. CAS 동시성 — 동시 2워커가 같은 작업을 단일 처리 (claimForRun)
 * - S4. 혼합 from-state 일괄 전환 부분 성공 — 일부 이슈 전환 가능, 일부 불가
 * - S5. 1000건 상한 — BULK_OPERATION_MAX_SIZE 이슈 처리 완료 검증
 * - S6. 완료 이벤트 발행 — q_bulk_operation_events 큐에 메시지 발행 확인
 *
 * ## 설계 원칙
 * - 워커를 직접 호출([BulkOperationWorker.pollAndProcess])하여 스케줄 폴링 대기 없이 제어 흐름 유지
 * - Spring ApplicationContext 공유 — IssueControllerTransitionIntegrationTest.TestConfig 구조 답습
 * - pgmq 큐는 실제 DB 큐를 사용하며 테스트 후 큐 메시지를 정리해 독립성 보장
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BulkOperationIntegrationTest.TestConfig::class])
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkOperationIntegrationTest {
    // ── Spring Bean 구성 ────────────────────────────────────────────────────────

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            /** JVM 단위 singleton Testcontainers — quay.io/tembo/pg16-pgmq:latest (pgmq 확장 포함) */
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_bulk_it_test")
                    .withUsername("bts")
                    .withPassword("bts_bulk_it_test")
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
        open fun resolutionRepository(dsl: DSLContext): com.bts.issue.resolution.repository.ResolutionRepository =
            com.bts.issue.resolution.repository.ResolutionRepository(dsl)

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
            resolutionRepository: com.bts.issue.resolution.repository.ResolutionRepository,
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

        // ── Bulk 빈 ──────────────────────────────────────────────────────────

        @Bean
        open fun bulkOperationRepository(
            dsl: DSLContext,
            objectMapper: ObjectMapper,
            clock: Clock,
        ): BulkOperationRepository = BulkOperationRepository(dsl, objectMapper, clock)

        @Bean
        open fun bulkOperationEnqueuePublisher(dsl: DSLContext): BulkOperationEnqueuePublisher = BulkOperationEnqueuePublisher(dsl)

        @Bean
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
            issueRepository: IssueRepository,
            eventPublisher: IssueEventPublisher,
            dsl: DSLContext,
        ): BulkItemApplier =
            BulkItemApplier(
                issueService = issueService,
                bulkRepo = bulkRepo,
                issueRepository = issueRepository,
                eventPublisher = eventPublisher,
                // 이 컨텍스트에는 IssueHistoryRecorder 빈이 없다 — issueApplicationService 빈도 같은
                // 이유로 relaxed mockk 를 쓴다. 이 테스트들이 보는 것은 이력이 아니라 전환 결과다.
                historyRecorder = io.mockk.mockk(relaxed = true),
                // 가드는 non-null 필수다. 이 클래스는 STATUS_MIGRATION 을 돌리지 않지만 모의를 넘겨
                // 「검사가 생략됐다」를 만들 이유도 없다 — 실물이 dsl 하나로 조립된다.
                projectArchiveGuard = ProjectArchiveGuard(ProjectArchiveStateRepository(dsl)),
            )

        @Bean
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

    // ── 테스트 전역 상수 ───────────────────────────────────────────────────────

    companion object {
        private const val PROJECT_KEY = "BULKIT"
        private val ACTOR_ID = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
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
                // pgmq 큐 메시지 비우기 — 테스트 간 격리 (vt 만료 전 메시지 처리 방지)
                stmt.execute("SELECT pgmq.purge_queue('${BulkOperationEnqueuePublisher.QUEUE_NAME}')")
                stmt.execute("SELECT pgmq.purge_queue('${BulkOperationEventPublisher.QUEUE_NAME}')")
                // bulk 테이블 초기화
                stmt.execute("DELETE FROM bulk_operation_items")
                stmt.execute("DELETE FROM bulk_operations")
                // 이슈 초기화
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    // ── S1. BULK_EDIT 전체 흐름 ────────────────────────────────────────────────

    /**
     * S1 — submit(접수) → worker.pollAndProcess() → 결과 조회 전체 흐름.
     *
     * Given  BULKIT 프로젝트에 이슈 2건 삽입 (open 상태)
     * When   BULK_EDIT(priority=3) 접수 → 워커 폴링
     * Then   BulkOperation COMPLETED, 항목 2건 모두 SUCCEEDED, 카운트 정합
     */
    @Test
    fun `S1 - BULK_EDIT 전체 흐름 - submit 후 워커 실행 시 작업 COMPLETED 및 항목 SUCCEEDED`() {
        val key1 = insertIssue("이슈1", "open")
        val key2 = insertIssue("이슈2", "open")

        val opId =
            bulkAppService.submit(
                ACTOR_ID,
                BulkUpdateRequest(
                    issueKeys = listOf(key1, key2),
                    operationType = BulkOperationType.BULK_EDIT,
                    editPayload = BulkEditPayload(priority = 3, impact = null),
                    transitionPayload = null,
                ),
            )

        worker.pollAndProcess()

        val op = requireNotNull(bulkRepo.findById(opId)) { "작업을 찾을 수 없음" }
        assertThat(op.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(op.succeededCount).isEqualTo(2)
        assertThat(op.failedCount).isEqualTo(0)
        assertThat(op.processedCount).isEqualTo(2)

        val items = bulkRepo.findItemsByOperationId(opId)
        assertThat(items).allMatch { it.status == ItemStatus.SUCCEEDED }
    }

    // ── S2. 워커 재전달 멱등 ───────────────────────────────────────────────────

    /**
     * S2 — 같은 메시지를 2회 read 시 SUCCEEDED 항목 스킵, 카운트 불변.
     *
     * Given  이슈 2건 + BULK_EDIT 접수
     * When   워커 1회 실행 (작업 처리 완료)
     *        pgmq 큐에 동일한 메시지를 수동 재투입(재전달 시뮬레이션)
     *        워커 2회 실행
     * Then   SUCCEEDED 항목 수 여전히 2건 (재처리 없음), BulkOperation COMPLETED 유지
     *
     * 멱등 근거: claimForRun CAS(PENDING→RUNNING) + updateItemResult PENDING 가드.
     * 1차 처리 후 status=COMPLETED이므로 claimForRun이 false를 반환해 재처리 진입 차단.
     */
    @Test
    fun `S2 - 워커 재전달 멱등 - 동일 메시지 2회 처리 시 SUCCEEDED 카운트 불변`() {
        val key1 = insertIssue("멱등 이슈1", "open")
        val key2 = insertIssue("멱등 이슈2", "open")

        val opId =
            bulkAppService.submit(
                ACTOR_ID,
                BulkUpdateRequest(
                    issueKeys = listOf(key1, key2),
                    operationType = BulkOperationType.BULK_EDIT,
                    editPayload = BulkEditPayload(priority = 2, impact = null),
                    transitionPayload = null,
                ),
            )

        // 1차 처리
        worker.pollAndProcess()

        val afterFirst = requireNotNull(bulkRepo.findById(opId))
        assertThat(afterFirst.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(afterFirst.succeededCount).isEqualTo(2)

        // 재전달 시뮬레이션 — 같은 bulkOperationId 를 큐에 재투입
        val payload = """{"bulkOperationId":"${opId.value}"}"""
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", BulkOperationEnqueuePublisher.QUEUE_NAME, payload)

        // 2차 폴링 — claimForRun CAS 실패로 처리 스킵
        worker.pollAndProcess()

        val afterSecond = requireNotNull(bulkRepo.findById(opId))
        assertThat(afterSecond.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(afterSecond.succeededCount).isEqualTo(2) // 불변
        assertThat(afterSecond.failedCount).isEqualTo(0) // 불변
    }

    // ── S3. CAS 동시성 ─────────────────────────────────────────────────────────

    /**
     * S3 — 동시 2워커가 같은 작업을 단일 처리 (claimForRun CAS).
     *
     * Given  이슈 1건 + BULK_EDIT 접수
     * When   CountDownLatch 로 2개 스레드 동시 시작 → 각각 pollAndProcess 호출
     * Then   succeededCount == 1 (단일 처리), 작업 COMPLETED, failedCount == 0
     *
     * CAS 보장: claimForRun WHERE status='PENDING' UPDATE affected rows 1이면 true.
     * 두 워커 중 한 워커만 true를 받아 처리한다.
     */
    @Test
    fun `S3 - CAS 동시성 - 동시 2워커가 같은 작업 단일 처리`() {
        val key1 = insertIssue("동시성 이슈1", "open")

        val opId =
            bulkAppService.submit(
                ACTOR_ID,
                BulkUpdateRequest(
                    issueKeys = listOf(key1),
                    operationType = BulkOperationType.BULK_EDIT,
                    editPayload = BulkEditPayload(priority = 1, impact = null),
                    transitionPayload = null,
                ),
            )

        // 동시 2스레드 실행 — CountDownLatch 로 동시 진입 보장
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures =
            (1..2).map {
                executor.submit {
                    latch.await() // 동시 진입 대기
                    worker.pollAndProcess()
                }
            }
        latch.countDown() // 동시 출발
        futures.forEach { it.get() }
        executor.shutdown()

        val op = requireNotNull(bulkRepo.findById(opId))
        assertThat(op.status).isEqualTo(BulkOperationStatus.COMPLETED)
        // 단일 처리 보장 — 2워커 중 1워커만 claimForRun CAS 성공
        assertThat(op.succeededCount).isEqualTo(1)
        assertThat(op.failedCount).isEqualTo(0)
    }

    // ── S4. 혼합 from-state 일괄 전환 부분 성공 ─────────────────────────────────

    /**
     * S4 — 이슈 상태가 제각각일 때 toStateKey 개별 검증, 부분 성공.
     *
     * Given  이슈 3건 삽입
     *        - key1: open (open→in_progress 전환 가능)
     *        - key2: open (open→in_progress 전환 가능)
     *        - key3: done (done→in_progress 전환 미정의 → TRANSITION_NOT_ALLOWED)
     * When   BULK_TRANSITION toStateKey=in_progress 접수 → 워커 실행
     * Then   key1, key2 → SUCCEEDED (전환 성공)
     *        key3 → FAILED (전환 불가)
     *        작업 COMPLETED, succeededCount=2, failedCount=1
     */
    @Test
    fun `S4 - 혼합 from-state 부분 성공 - 전환 가능 이슈 SUCCEEDED 불가 이슈 FAILED`() {
        val key1 = insertIssue("전환가능1", "open")
        val key2 = insertIssue("전환가능2", "open")
        val key3 = insertIssue("전환불가", "done") // done→in_progress 전환 없음

        val opId =
            bulkAppService.submit(
                ACTOR_ID,
                BulkUpdateRequest(
                    issueKeys = listOf(key1, key2, key3),
                    operationType = BulkOperationType.BULK_TRANSITION,
                    editPayload = null,
                    transitionPayload = BulkTransitionPayload(toStateKey = "in_progress"),
                ),
            )

        worker.pollAndProcess()

        val op = requireNotNull(bulkRepo.findById(opId))
        assertThat(op.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(op.succeededCount).isEqualTo(2)
        assertThat(op.failedCount).isEqualTo(1)
        assertThat(op.processedCount).isEqualTo(3)

        val items = bulkRepo.findItemsByOperationId(opId)
        val succeededKeys = items.filter { it.status == ItemStatus.SUCCEEDED }.map { it.issueKey.value }
        val failedKeys = items.filter { it.status == ItemStatus.FAILED }.map { it.issueKey.value }
        assertThat(succeededKeys).containsExactlyInAnyOrder(key1, key2)
        assertThat(failedKeys).containsExactly(key3)
    }

    // ── S5. 1000건 상한 ────────────────────────────────────────────────────────

    /**
     * S5 — 1000건(BULK_OPERATION_MAX_SIZE) 이슈를 일괄 편집 처리 완료 검증.
     *
     * Given  이슈 1000건 삽입 (open 상태)
     * When   BULK_EDIT 접수 → 워커 실행
     * Then   BulkOperation COMPLETED, succeededCount == 1000
     *
     * 청크(50건) × 20번 처리됨. 처리 시간 허용 범위 내 완료 확인.
     */
    @Test
    fun `S5 - 1000건 상한 - BULK_OPERATION_MAX_SIZE 이슈 처리 완료`() {
        val issueKeys = (1..BULK_OPERATION_MAX_SIZE).map { insertIssue("상한이슈$it", "open") }

        val opId =
            bulkAppService.submit(
                ACTOR_ID,
                BulkUpdateRequest(
                    issueKeys = issueKeys,
                    operationType = BulkOperationType.BULK_EDIT,
                    editPayload = BulkEditPayload(priority = 1, impact = null),
                    transitionPayload = null,
                ),
            )

        worker.pollAndProcess()

        val op = requireNotNull(bulkRepo.findById(opId))
        assertThat(op.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(op.totalCount).isEqualTo(BULK_OPERATION_MAX_SIZE)
        assertThat(op.succeededCount).isEqualTo(BULK_OPERATION_MAX_SIZE)
        assertThat(op.failedCount).isEqualTo(0)
    }

    // ── S2b. 완료 후 메시지 큐에서 삭제 검증 ───────────────────────────────────

    /**
     * S2b — 워커 정상 완료 후 q_bulk_operations 큐에 메시지가 남아 있지 않아야 한다.
     *
     * Given  이슈 1건 + BULK_EDIT 접수 → 큐에 메시지 1건 발행
     * When   워커 실행 → 처리 완료
     * Then   q_bulk_operations 큐에 메시지 0건 (delete 가 호출됐음)
     *
     * F1 결함 수정 검증 — 행복한 경로에서 deleteMessage 가 호출되는지 확인.
     */
    @Test
    fun `S2b - 정상 완료 후 큐에서 메시지 삭제 검증`() {
        val key1 = insertIssue("delete이슈", "open")

        bulkAppService.submit(
            ACTOR_ID,
            BulkUpdateRequest(
                issueKeys = listOf(key1),
                operationType = BulkOperationType.BULK_EDIT,
                editPayload = BulkEditPayload(priority = 2, impact = null),
                transitionPayload = null,
            ),
        )

        // pgmq.purge 후 새로 send 해서 워커가 vt 없이 바로 read 할 수 있도록
        dsl.execute("SELECT pgmq.purge_queue(?)", BulkOperationEnqueuePublisher.QUEUE_NAME)
        val opId2 =
            bulkAppService.submit(
                ACTOR_ID,
                BulkUpdateRequest(
                    issueKeys = listOf(key1),
                    operationType = BulkOperationType.BULK_EDIT,
                    editPayload = BulkEditPayload(priority = 3, impact = null),
                    transitionPayload = null,
                ),
            )

        worker.pollAndProcess()

        // 처리 후 — 큐에 메시지 없음 (delete 됐어야 함)
        val afterMessages =
            dsl.fetch(
                "SELECT * FROM pgmq.read(?, ?, ?)",
                BulkOperationEnqueuePublisher.QUEUE_NAME,
                1,
                10,
            )
        assertThat(afterMessages).isEmpty()

        val op = requireNotNull(bulkRepo.findById(opId2))
        assertThat(op.status).isEqualTo(BulkOperationStatus.COMPLETED)
    }

    // ── S7. stale RUNNING 재청 ─────────────────────────────────────────────────

    /**
     * S7 — stale RUNNING 작업을 두 번째 워커가 재청하여 처리 완료.
     *
     * Given  이슈 1건 + BULK_EDIT 접수
     *        DB에서 직접 status=RUNNING, started_at=과거 조작 (크래시 시뮬레이션)
     * When   워커 실행 (stale 재청 허용)
     * Then   작업 COMPLETED — stale 재청 성공
     *
     * F2 수정 검증.
     */
    @Test
    fun `S7 - stale RUNNING 재청 - 과거 started_at 가진 RUNNING 작업을 워커가 재처리`() {
        val key1 = insertIssue("stale이슈", "open")

        val opId =
            bulkAppService.submit(
                ACTOR_ID,
                BulkUpdateRequest(
                    issueKeys = listOf(key1),
                    operationType = BulkOperationType.BULK_EDIT,
                    editPayload = BulkEditPayload(priority = 1, impact = null),
                    transitionPayload = null,
                ),
            )

        // 크래시 시뮬레이션 — status=RUNNING, started_at=400초 전 (stale threshold 초과)
        dsl.execute(
            "UPDATE bulk_operations SET status='RUNNING', started_at=NOW()-INTERVAL '400 seconds' WHERE id=?",
            opId.value,
        )
        // 메시지는 큐에 그대로 남아 있음 (purge 안 함)

        // 워커 실행 → stale 재청 → 처리
        worker.pollAndProcess()

        val op = requireNotNull(bulkRepo.findById(opId))
        assertThat(op.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(op.succeededCount).isEqualTo(1)
    }

    // ── S6. 완료 이벤트 발행 ───────────────────────────────────────────────────

    /**
     * S6 — 처리 완료 후 q_bulk_operation_events 큐에 완료 이벤트 발행 확인.
     *
     * Given  이슈 1건 + BULK_EDIT 접수
     * When   워커 실행
     * Then   q_bulk_operation_events 큐에 1건 메시지 발행
     *        메시지 JSON에 bulkOperationId 포함 확인
     */
    @Test
    fun `S6 - 완료 이벤트 발행 - q_bulk_operation_events 큐에 bulkOperationId 포함된 메시지 발행`() {
        val key1 = insertIssue("이벤트이슈", "open")

        val opId =
            bulkAppService.submit(
                ACTOR_ID,
                BulkUpdateRequest(
                    issueKeys = listOf(key1),
                    operationType = BulkOperationType.BULK_EDIT,
                    editPayload = BulkEditPayload(priority = 2, impact = null),
                    transitionPayload = null,
                ),
            )

        worker.pollAndProcess()

        // q_bulk_operation_events 에서 메시지 조회 (vt=1초 — 즉시 읽기)
        val messages =
            dsl.fetch(
                "SELECT * FROM pgmq.read(?, ?, ?)",
                BulkOperationEventPublisher.QUEUE_NAME,
                1,
                10,
            )
        assertThat(messages).isNotEmpty
        val firstMessage = messages.first().get("message", String::class.java)
        assertThat(firstMessage).contains(opId.value.toString())
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — issue-tracking + project-workflow 두 BC 를 단일 pass 로 적용.
     *
     * V001~V008 (issue-tracking) + V200~V202 (project-workflow) 순서 적용.
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
     * 1. BULKIT 프로젝트 삽입
     * 2. software-default workflow + states + transitions
     * 3. software-scheme default mapping → software-default workflow
     * 4. BULKIT 프로젝트에 software-scheme 배정
     *
     * LongMethod: 워크플로우·스킴·프로젝트 픽스처 삽입 순서를 한 곳에서 관리해야 하므로 인라인이 적합하다.
     */
    @Suppress("LongMethod")
    private fun seedFixtures() {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false

            // 1. BULKIT 프로젝트 삽입
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Bulk Integration Test Project")
                stmt.executeUpdate()
            }

            // 2. software-default workflow
            val wfId: UUID =
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('software-default', 'Software Default') " +
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
            val inReviewId = insertState(conn, wfId, "in_review", "In Review", "IN_PROGRESS", 2)
            val doneId = insertState(conn, wfId, "done", "Done", "DONE", 3)

            insertTransition(conn, wfId, openId, inProgressId, "Start Work")
            insertTransition(conn, wfId, inProgressId, inReviewId, "Submit for Review")
            insertTransition(conn, wfId, inReviewId, doneId, "Approve")
            // done→open 없음 — S4 부분 실패 시나리오에서 활용

            // 3. software-scheme 삽입
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_schemes (key, name, is_default)
                    VALUES ('software-scheme', 'Software Scheme', true)
                    ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL DO NOTHING
                    """.trimIndent(),
                )
            }

            // software-scheme default mapping → software-default workflow
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                    SELECT s.id, NULL, '$wfId'
                    FROM workflow_schemes s
                    WHERE s.key = 'software-scheme'
                    ON CONFLICT (scheme_id) WHERE issue_type_id IS NULL DO NOTHING
                    """.trimIndent(),
                )
            }

            // 4. BULKIT 프로젝트에 software-scheme 배정
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                    SELECT p.id, s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                    FROM projects p, workflow_schemes s
                    WHERE p.key = '$PROJECT_KEY'
                      AND s.key = 'software-scheme'
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }

            conn.commit()
        }
    }

    /**
     * workflow_states INSERT helper.
     *
     * @param conn DB 연결.
     * @param wfId 워크플로우 ID.
     * @param key 상태 키.
     * @param name 표시 이름.
     * @param category 상태 분류 (TODO / IN_PROGRESS / DONE).
     * @param displayOrder 정렬 순서.
     * @return 삽입된 상태 ID.
     */
    @Suppress("LongParameterList")
    private fun insertState(
        conn: Connection,
        wfId: UUID,
        key: String,
        name: String,
        category: String,
        displayOrder: Int,
    ): UUID = insertWorkflowStatus(conn, wfId, key, name, category, displayOrder)

    private fun insertTransition(
        conn: Connection,
        wfId: UUID,
        fromId: UUID,
        toId: UUID,
        transitionName: String,
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
            stmt.setString(4, transitionName)
            stmt.setObject(5, wfId)
            stmt.setObject(6, fromId)
            stmt.setObject(7, toId)
            stmt.executeUpdate()
        }
    }

    /**
     * 테스트용 이슈를 DB에 직접 삽입하고 이슈 키를 반환한다.
     *
     * Service layer 를 우회하므로 workflowKeyResolver 호출 없이 지정한 currentStateKey 를 그대로 삽입한다.
     *
     * @param summary 이슈 제목.
     * @param currentStateKey 이슈 초기 상태 키 (예: "open", "done").
     * @return 생성된 이슈 키 (예: "BULKIT-1").
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
}
