// 상태 이관 실행 통합 테스트 — 워커가 claim 시점에 대상을 다시 긁어 담는다 (FR-WF-07 D4·D5 · plan Task 7)
@file:Suppress("MaxLineLength")

package com.bts.issue.bulk.integration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.adapter.outbound.workflow.WorkflowStatusMigrationAdapter
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.bulk.application.BulkItemApplier
import com.bts.issue.bulk.application.BulkItemExecutor
import com.bts.issue.bulk.application.BulkItemFailureRecorder
import com.bts.issue.bulk.application.BulkOperationProcessor
import com.bts.issue.bulk.domain.BULK_OPERATION_MAX_SIZE
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationStatus
import com.bts.issue.bulk.domain.FailureReasonCode
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.event.BulkOperationEnqueuePublisher
import com.bts.issue.bulk.event.BulkOperationEventPublisher
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.bulk.worker.BulkOperationCompleter
import com.bts.issue.bulk.worker.BulkOperationWorker
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.jooq.tables.references.BULK_OPERATIONS
import com.bts.issue.jooq.tables.references.BULK_OPERATION_ITEMS
import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.PROJECTS
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.testsupport.insertWorkflowStatus
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueStatusMigrationPort
import com.bts.shared.issue.StatusMigrationCommand
import com.bts.shared.issue.StatusMigrationMapping
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
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * 상태 이관(STATUS_MIGRATION) **실행 시점 재해석** 통합 테스트 — plan Task 7.
 *
 * 큐잉은 `bulk_operations` 1건만 만들고 항목을 0건으로 둔다(Task 5). 대상 조회·항목 적재·
 * `total_count` 확정은 **워커가 claim 한 뒤**에 한다(F15 — 「세고 나서 옮긴다」가 아니라
 * **「옮기면서 센다」**). 이 테스트는 그 시점 이동이 실제로 무엇을 바꾸는지를 실 DB 로 확인한다.
 *
 * ## 검증 시나리오
 * - M1. **큐잉 이후** 그 상태로 들어온 이슈도 이관된다 (완료기준 7 · E12) — 이 PR 의 존재 이유
 * - M2. 범위 밖 프로젝트의 같은 상태 이슈는 **무변경**이다 (완료기준 5 · G4). 필터가 실행 시점으로
 *   옮겨졌으므로 판정도 여기서 한다
 * - M3. 실행 시점 대상이 0건이면 `COMPLETED` · `total_count=0` 이다. **실패가 아니다** (완료기준 8 · E3)
 * - M4. 실행 시점 대상이 상한 초과면 `FAILED` 이고 사유에 **분할 재시도 안내**가 들어 있다.
 *   **조용히 자르지 않는다** (완료기준 12 · E4 · J6 · C-2)
 * - M5. 워커가 items 를 채우다 재시작해도 **중복 항목이 0** 이다 (완료기준 9 · E16 · F16)
 * - M6. `failed_count > 0` 으로 끝나면 **로그에 드러난다** (완료기준 13 · C-3)
 *
 * ## ★워커를 테스트가 직접 트리거한다 (게이트 1 C-5)
 * 스케줄 폴링을 켜면 「큐잉 → 이슈 1건 추가 → claim」의 순서가 뒤집혀 M1 이 flaky 해진다.
 * 이 컨텍스트에는 `@EnableScheduling` 이 없고 [BulkOperationWorker.pollAndProcess] 를
 * 테스트가 직접 부른다 — 삽입과 트리거의 순서를 테스트가 통제한다.
 *
 * ## 픽스처가 자기 프로젝트를 직접 만든다
 * 범위 판정(M2)은 선재 행이 있으면 가짜 그린이 된다. 프로젝트 2개(범위 안·밖)와 이슈를 이 클래스가
 * 직접 심고 매 테스트마다 지운다 — 마이그레이션 시드나 공용 DB 에 기대지 않는다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [StatusMigrationMaterializeIntegrationTest.TestConfig::class])
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusMigrationMaterializeIntegrationTest {
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
                    .withDatabaseName("bts_status_migration_mat_it")
                    .withUsername("bts")
                    .withPassword("bts_status_migration_mat_it")
                    .apply { start() }
        }

        /**
         * ★**풀링 DataSource** — `DriverManagerDataSource` 가 아니다.
         *
         * 그쪽은 트랜잭션 밖 질의마다 물리 커넥션을 새로 연다. 항목 1건이 REQUIRES_NEW 트랜잭션
         * 하나를 쓰는 이 경로에서는 그 비용이 항목 수에 그대로 곱해져, 상한 경계(M11)의 1,000건이
         * 커넥션 수립만으로 80초를 넘겼다. 이 클래스가 보려는 것은 커넥션 비용이 아니다.
         */
        @Bean(destroyMethod = "close")
        open fun dataSource(): DataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                },
            )

        @Bean
        open fun transactionManager(ds: DataSource): PlatformTransactionManager = DataSourceTransactionManager(ds)

        @Bean
        open fun dslContext(ds: DataSource): DSLContext = DSL.using(ds, SQLDialect.POSTGRES)

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

        // ── project-workflow 빈 — 이관은 엔진을 타지 않지만 IssueApplicationService 조립에 필요하다 ──

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
                historyRecorder = mockk(relaxed = true),
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
        open fun workflowStatusMigrationAdapter(
            dsl: DSLContext,
            repo: BulkOperationRepository,
            enqueuePublisher: BulkOperationEnqueuePublisher,
        ): WorkflowStatusMigrationAdapter = WorkflowStatusMigrationAdapter(dsl, repo, enqueuePublisher)

        /**
         * ★실물 [ProjectArchiveGuard] — 모의가 아니다 (M7).
         *
         * 이 빈이 없으면 이관은 아카이브 잠금을 통과한다. 「가드가 주입된다」를 판정하려면
         * 판정 술어(`archived_at IS NOT NULL`)가 실 DB 를 읽어야 한다.
         */
        @Bean
        open fun projectArchiveGuard(dsl: DSLContext): ProjectArchiveGuard = ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))

        @Bean
        open fun bulkItemApplier(
            issueService: IssueApplicationService,
            bulkRepo: BulkOperationRepository,
            issueRepository: IssueRepository,
            eventPublisher: IssueEventPublisher,
            projectArchiveGuard: ProjectArchiveGuard,
        ): BulkItemApplier =
            BulkItemApplier(
                issueService = issueService,
                bulkRepo = bulkRepo,
                issueRepository = issueRepository,
                eventPublisher = eventPublisher,
                // 이 컨텍스트에는 IssueHistoryRecorder 빈이 없다 — 이 테스트가 보는 것은 이력이 아니라
                // 「무엇이 대상이 되었는가」다. 이력·이벤트 검증은 Task 6 의 단위 테스트가 맡는다.
                historyRecorder = mockk(relaxed = true),
                projectArchiveGuard = projectArchiveGuard,
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
    lateinit var port: IssueStatusMigrationPort

    @Autowired
    lateinit var bulkRepo: BulkOperationRepository

    @Autowired
    lateinit var worker: BulkOperationWorker

    @Autowired
    lateinit var dsl: DSLContext

    // ── 테스트 전역 상수 ───────────────────────────────────────────────────────

    companion object {
        private val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a7")

        /** 이관 범위 **안** 프로젝트. */
        private const val SCOPE_KEY = "MIGA"

        /** 이관 범위 **밖** 프로젝트 — 같은 상태의 이슈를 두고 무변경을 확인한다(M2). */
        private const val OUT_OF_SCOPE_KEY = "MIGB"

        /** 범위 **안**이지만 **아카이브된** 프로젝트 — 가드가 결선돼 있는지 본다(M7). */
        private const val ARCHIVED_KEY = "MIGC"

        /** 큐잉 뒤 **소프트 삭제**되는 프로젝트 — 범위가 지워진 프로젝트로 되살아나는지 본다(M9). */
        private const val DELETED_SCOPE_KEY = "MIGD"

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
                stmt.execute(
                    "DELETE FROM issues WHERE key LIKE '$SCOPE_KEY-%' OR key LIKE '$OUT_OF_SCOPE_KEY-%' " +
                        "OR key LIKE '$ARCHIVED_KEY-%' OR key LIKE '$DELETED_SCOPE_KEY-%'",
                )
                stmt.execute(
                    "UPDATE projects SET key_sequence = 0 " +
                        "WHERE key IN ('$SCOPE_KEY', '$OUT_OF_SCOPE_KEY', '$ARCHIVED_KEY', '$DELETED_SCOPE_KEY')",
                )
                // M9 가 소프트 삭제한 프로젝트를 되돌린다 — 픽스처가 다음 테스트로 새면 가짜 그린이 된다.
                stmt.execute("UPDATE projects SET deleted_at = NULL WHERE key = '$DELETED_SCOPE_KEY'")
            }
        }
    }

    // ── M1. 큐잉 이후 유입 — 이 PR 의 존재 이유 ────────────────────────────────

    /**
     * M1 (완료기준 7 · E12) — **큐잉 → claim 사이에 그 상태로 들어온 이슈도 이관된다**.
     *
     * 큐잉 시점 스냅샷 설계였다면 늦게 들어온 이슈는 대상에서 빠지고, 정의가 교체된 뒤
     * 사라진 상태를 가리키는 유령이 된다 — 화면은 「이관 완료」인데 실제로는 안 옮겨진 이슈가 남는다.
     * 삽입과 트리거의 순서를 테스트가 직접 통제해 그 창을 재현한다(C-5).
     */
    @Test
    fun `M1 - 큐잉 이후 그 상태로 들어온 이슈도 이관된다`() {
        val early = insertIssue(SCOPE_KEY, "in_review")
        val operationId = enqueue(projectKeys = setOf(SCOPE_KEY))
        // ★큐잉 이후 유입 — 워커가 아직 claim 하지 않은 시점에 같은 상태로 1건 더 들어온다
        val late = insertIssue(SCOPE_KEY, "in_review")

        worker.pollAndProcess()

        assertThat(stateOf(early)).isEqualTo("in_progress")
        assertThat(stateOf(late)).isEqualTo("in_progress")

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(operation.totalCount).isEqualTo(2)
        assertThat(operation.succeededCount).isEqualTo(2)
        assertThat(operation.failedCount).isEqualTo(0)
        assertThat(itemKeys(operationId)).containsExactlyInAnyOrder(early, late)
    }

    // ── M2. 범위 밖은 건드리지 않는다 ──────────────────────────────────────────

    /**
     * M2 (완료기준 5 · G4 · S6) — 상태 키는 **전역**이라 범위가 없으면 남의 프로젝트 이슈까지 옮겨진다.
     *
     * 필터가 큐잉 시점에서 실행 시점으로 옮겨졌으므로(Task 5 → Task 7) 판정도 여기서 한다.
     * 뮤테이션 짝 — `projectKeys` 필터를 지우면 **이 테스트만** red 여야 한다.
     */
    @Test
    fun `M2 - 범위 밖 프로젝트의 같은 상태 이슈는 무변경이다`() {
        val inScope = insertIssue(SCOPE_KEY, "in_review")
        val outOfScope = insertIssue(OUT_OF_SCOPE_KEY, "in_review")

        val operationId = enqueue(projectKeys = setOf(SCOPE_KEY))
        worker.pollAndProcess()

        assertThat(stateOf(inScope)).isEqualTo("in_progress")
        assertThat(stateOf(outOfScope)).isEqualTo("in_review")

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.totalCount).isEqualTo(1)
        assertThat(itemKeys(operationId)).containsExactly(inScope)
    }

    // ── M3. 대상 0건은 실패가 아니다 ───────────────────────────────────────────

    /**
     * M3 (완료기준 8 · E3) — 실행 시점 대상이 0건이면 `COMPLETED` · `total_count=0` 이다.
     *
     * 「할 일이 없었다」이지 실패가 아니다. 매핑 밖 상태의 이슈를 1건 두어 **아무것도 안 옮겼음**을
     * 함께 확인한다 — 0건 판정이 대상이 아닌 이슈를 삼켜서 얻어진 것이 아님을 못 박는다.
     */
    @Test
    fun `M3 - 실행 시점 대상이 0건이면 COMPLETED 이고 total_count 는 0 이다`() {
        val untouched = insertIssue(SCOPE_KEY, "todo")

        val operationId = enqueue(projectKeys = setOf(SCOPE_KEY))
        worker.pollAndProcess()

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(operation.totalCount).isEqualTo(0)
        assertThat(operation.failedCount).isEqualTo(0)
        assertThat(itemKeys(operationId)).isEmpty()
        assertThat(stateOf(untouched)).isEqualTo("todo")
    }

    // ── M4. 상한 초과는 조용히 자르지 않는다 ───────────────────────────────────

    /**
     * M4 (완료기준 12 · E4 · J6 · C-2) — 실행 시점 대상이 상한을 넘으면 `FAILED` 로 두고
     * 사유에 **분할 재시도 안내**를 싣는다.
     *
     * 조용히 [BULK_OPERATION_MAX_SIZE] 건만 자르면 잘린 나머지가 유령이 되는데 화면은 「완료」로 보인다.
     * 안내가 없으면 그대로 재시도해도 같은 결과라 그 상태를 **영영 못 뺀다** — 막다른 길임을 알리지
     * 않는 것 자체가 결함이다. 그래서 사유 문구에 「나눠서 다시 시도」가 실제로 들어 있는지 본다.
     */
    @Test
    fun `M4 - 실행 시점 대상이 상한 초과면 FAILED 이고 사유에 분할 재시도 안내가 있다`() {
        insertIssuesBulk(SCOPE_KEY, "in_review", BULK_OPERATION_MAX_SIZE + 1)

        val operationId = enqueue(projectKeys = setOf(SCOPE_KEY))
        val logs = captureProcessorLogs { worker.pollAndProcess() }

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.status).isEqualTo(BulkOperationStatus.FAILED)
        // 조용히 자르지 않는다 — 부분 적재도 부분 이관도 없다
        assertThat(itemKeys(operationId)).isEmpty()
        assertThat(operation.totalCount).isEqualTo(0)
        assertThat(countIssuesInState(SCOPE_KEY, "in_review")).isEqualTo(BULK_OPERATION_MAX_SIZE + 1)

        val reason =
            logs.filter { it.level.isGreaterOrEqual(Level.WARN) }
                .map { it.formattedMessage }
                .filter { it.contains(operationId.toString()) }
                .joinToString("\n")
        assertThat(reason).isNotBlank()
        assertThat(reason).containsIgnoringCase("split")
        assertThat(reason).containsIgnoringCase("retry")
        assertThat(reason).contains("${BULK_OPERATION_MAX_SIZE + 1}")
        assertThat(reason).contains("$BULK_OPERATION_MAX_SIZE")

        // ★0건 갈래를 여기서 못박는다. 위 네 단언은 두 갈래의 공통 꼬리라
        // `overLimitFailureReason` 의 분기를 통째로 지워도 통과한다 — M10 이 부분 이관 쪽만
        // 지키므로 이것이 없으면 spec 완료 기준 12 의 「두 갈래를 각각 잰다」가 절반만 참이 된다.
        assertThat(reason)
            .describedAs("한 건도 안 옮겼으면 사유가 그렇게 말해야 한다 — 부분 이관 문구가 나가면 거짓이다")
            .containsIgnoringCase("nothing was migrated")
        assertThat(reason)
            .describedAs("정산값이 0 임을 사유가 드러내야 한다 (M10 의 migrated=2 와 짝)")
            .contains("migrated=0")
        assertThat(reason)
            .describedAs("남은 PENDING 이 0 임을 사유가 드러내야 한다 (M10 의 leftPending=1 과 짝)")
            .contains("leftPending=0")
        assertThat(reason)
            .describedAs("0건 이관에 부분 이관 문구가 섞이면 안 된다")
            .doesNotContain("already migrated")
    }

    // ── M5. 적재 도중 재시작해도 중복이 없다 ───────────────────────────────────

    /**
     * M5 (완료기준 9 · E16 · F16) — 워커가 항목을 채우다 죽고 다시 시작해도 **중복 항목이 0** 이다.
     *
     * 크래시 시점을 「1건만 적재된 상태」로 재현한다. 재시작한 워커는 같은 대상을 다시 긁으므로
     * 이미 있는 1건은 `UNIQUE (bulk_operation_id, issue_key)` 가 흡수하고, **못 담은 2건은 새로 담긴다.**
     * 「중복이 없다」만 보면 아무것도 안 담아도 통과하므로 **전량 담겼는지**를 함께 본다.
     */
    @Test
    fun `M5 - 항목 적재 도중 재시작해도 중복 항목이 생기지 않는다`() {
        val keys = (1..3).map { insertIssue(SCOPE_KEY, "in_review") }
        val operationId = enqueue(projectKeys = setOf(SCOPE_KEY))
        // 크래시 재현 — 워커가 3건 중 1건만 적재하고 죽었다
        insertPendingItem(operationId, keys.first())

        worker.pollAndProcess()

        val loaded = allItemKeys(operationId)
        assertThat(loaded).hasSize(3)
        assertThat(loaded.distinct()).hasSize(3)
        assertThat(loaded).containsExactlyInAnyOrderElementsOf(keys)

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.status).isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(operation.totalCount).isEqualTo(3)
        assertThat(operation.succeededCount).isEqualTo(3)
        keys.forEach { assertThat(stateOf(it)).isEqualTo("in_progress") }
    }

    // ── M6. 실패는 조용히 끝나지 않는다 ────────────────────────────────────────

    /**
     * M6 (완료기준 13 · C-3) — `failed_count > 0` 으로 끝나면 **로그에 드러난다**.
     *
     * 작업은 `COMPLETED` 인데 실패한 건은 옛 상태에 남아 유령이 된다. 이 PR 에는 아직 사용자에게
     * 보이는 화면이 없으므로(결선은 PR 7b) 운영자가 그것을 알 수 있는 경로는 로그뿐이다.
     * 실패를 만드는 방법은 E8 그대로 — 항목 적재 뒤 누군가 그 이슈를 매핑 밖 상태로 옮긴다.
     */
    @Test
    fun `M6 - failed_count 가 0 보다 크면 로그에 드러난다`() {
        val succeeds = insertIssue(SCOPE_KEY, "in_review")
        val moved = insertIssue(SCOPE_KEY, "in_review")
        val operationId = enqueue(projectKeys = setOf(SCOPE_KEY))
        insertPendingItem(operationId, succeeds)
        insertPendingItem(operationId, moved)
        // 적재 뒤 누군가 이 이슈를 매핑에 없는 상태로 옮겼다 (E8)
        moveTo(moved, "done")

        val logs = captureProcessorLogs { worker.pollAndProcess() }

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.succeededCount).isEqualTo(1)
        assertThat(operation.failedCount).isEqualTo(1)

        val alert =
            logs.filter { it.level.isGreaterOrEqual(Level.WARN) }
                .map { it.formattedMessage }
                .filter { it.contains(operationId.toString()) }
                .joinToString("\n")
        assertThat(alert).isNotBlank()
        assertThat(alert).contains("failed=1")
        assertThat(alert).containsIgnoringCase("status_migration")
    }

    // ── M7. 아카이브 잠금은 이관에도 걸린다 — 가드가 **결선**되어 있는가 ────────

    /**
     * M7 (E14 · D3 ② · 게이트 2 리뷰 ①②) — 아카이브된 프로젝트의 이슈는 이관되지 않고
     * `PROJECT_ARCHIVED` 로 FAILED 된다.
     *
     * ## 단위 테스트가 못 보는 것
     * `BulkItemApplierStatusMigrationTest` 는 「가드가 **호출된다**」를 mockk 로 본다. 그러나
     * 「가드가 **주입된다**」는 아무도 안 봤다. 실제로 이 PR 의 통합 컨텍스트 3곳이 가드를 생략한 채
     * 이관 경로를 돌렸고, 그때 nullable 기본값의 `?.` 가 검사를 **조용히 건너뛰었다**(fail-open).
     * 이 테스트는 그 결선 자체를 판정한다 — 가드 빈을 빼면 여기가 red 다.
     *
     * ## 비-공허 짝
     * 같은 실행에서 아카이브가 아닌 프로젝트의 이슈는 **정상 이관**된다. 「전부 실패」 구현이나
     * 「이관 자체가 안 도는」 픽스처를 배제한다.
     */
    @Test
    fun `M7 - 아카이브된 프로젝트의 이슈는 이관되지 않고 PROJECT_ARCHIVED 로 FAILED 된다`() {
        val archived = insertIssue(ARCHIVED_KEY, "in_review")
        val active = insertIssue(SCOPE_KEY, "in_review")

        val operationId = enqueue(projectKeys = setOf(SCOPE_KEY, ARCHIVED_KEY))
        worker.pollAndProcess()

        // ① 아카이브 프로젝트의 이슈는 옛 상태 그대로다 — 다른 모든 쓰기가 거부하는 일을 이관만 해내면 안 된다.
        assertThat(stateOf(archived))
            .describedAs("아카이브된 프로젝트의 이슈는 이관되지 않아야 한다 (가드 미결선이면 in_progress 가 된다)")
            .isEqualTo("in_review")
        // ② 비-공허 짝 — 아카이브가 아닌 프로젝트는 같은 실행에서 정상 이관된다.
        assertThat(stateOf(active)).isEqualTo("in_progress")

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.totalCount).isEqualTo(2)
        assertThat(operation.succeededCount).isEqualTo(1)
        assertThat(operation.failedCount).isEqualTo(1)

        val failed =
            bulkRepo.findItemsByOperationId(BulkOperationId(operationId))
                .single { it.issueKey.value == archived }
        assertThat(failed.status).isEqualTo(ItemStatus.FAILED)
        assertThat(failed.failureReasonCode).isEqualTo(FailureReasonCode.PROJECT_ARCHIVED)
    }

    // ── M8 · M9. 소프트 삭제 필터는 수동이다 (DATA.md §3) ──────────────────────

    /**
     * M8 (게이트 2 리뷰 ④-1) — **소프트 삭제된 이슈는 이관 대상이 아니다**.
     *
     * `deleted_at IS NULL` 에는 자동 필터가 없다(DATA.md §3). 대상 조회에서 그 한 줄을 지우면
     * 지워진 이슈가 항목으로 적재된다. 그러면 장부의 `total_count` 가 살아 있는 이슈 수를 넘고,
     * 항목은 `NOT_FOUND` 로 실패해 「실패한 이관」으로 보인다 — 지워진 이슈 때문에.
     *
     * 비-공허 짝 — 같은 프로젝트의 살아 있는 이슈는 정상 이관된다.
     */
    @Test
    fun `M8 - 소프트 삭제된 이슈는 이관 대상이 되지 않는다`() {
        val alive = insertIssue(SCOPE_KEY, "in_review")
        val deleted = insertIssue(SCOPE_KEY, "in_review").also { softDeleteIssue(it) }

        val operationId = enqueue(projectKeys = setOf(SCOPE_KEY))
        worker.pollAndProcess()

        assertThat(itemKeys(operationId))
            .describedAs("소프트 삭제된 이슈는 항목으로 적재되면 안 된다")
            .containsExactly(alive)

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.totalCount).isEqualTo(1)
        assertThat(operation.succeededCount).isEqualTo(1)
        assertThat(operation.failedCount).isEqualTo(0)
        assertThat(stateOf(alive)).isEqualTo("in_progress")
        assertThat(stateOf(deleted)).isEqualTo("in_review")
    }

    /**
     * M9 (게이트 2 리뷰 ④-2) — **큐잉 뒤 소프트 삭제된 프로젝트는 범위에서 빠진다**.
     *
     * 큐잉 시점 가드(T14)는 그때 살아 있던 키만 본다. 큐잉 → 실행 사이에 프로젝트가 지워지면
     * 그 가드는 아무것도 못 한다 — 실행 시점 조회의 `projects.deleted_at IS NULL` 만 남는다.
     * 그 한 줄을 지우면 지워진 프로젝트의 이슈가 범위로 되살아나 조용히 옮겨진다.
     *
     * 비-공허 짝 — 살아 있는 프로젝트의 이슈는 같은 실행에서 정상 이관된다.
     */
    @Test
    fun `M9 - 큐잉 뒤 소프트 삭제된 프로젝트의 이슈는 범위에서 빠진다`() {
        val alive = insertIssue(SCOPE_KEY, "in_review")
        val inDeletedProject = insertIssue(DELETED_SCOPE_KEY, "in_review")

        val operationId = enqueue(projectKeys = setOf(SCOPE_KEY, DELETED_SCOPE_KEY))
        // 큐잉 가드를 통과한 뒤 프로젝트가 지워졌다 — 이제 실행 시점 조회만 남았다.
        softDeleteProject(DELETED_SCOPE_KEY)
        try {
            worker.pollAndProcess()
        } finally {
            restoreProject(DELETED_SCOPE_KEY)
        }

        assertThat(itemKeys(operationId))
            .describedAs("소프트 삭제된 프로젝트의 이슈는 범위 밖이어야 한다")
            .containsExactly(alive)
        assertThat(stateOf(inDeletedProject)).isEqualTo("in_review")
        assertThat(stateOf(alive)).isEqualTo("in_progress")

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.totalCount).isEqualTo(1)
        assertThat(operation.succeededCount).isEqualTo(1)
    }

    // ── M10. 상한 초과 + 크래시 재시작 — 장부가 거짓말하지 않는다 ──────────────

    /**
     * M10 (게이트 2 리뷰 ⑤) — **상한 초과로 끝나도 이미 옮긴 건이 장부와 사유에 반영된다**.
     *
     * ## 재현하는 사고
     * ① 워커가 항목을 적재하고 일부를 적용하다 죽는다. `recomputeAndPersistCounts` 는 [BulkOperationProcessor.process]
     * 맨 끝에만 있으므로 `succeeded_count` 는 **0인 채** 남는다. ② 재전달 사이에 유입이 늘어 재스캔이
     * 상한을 넘는다. ③ 초과 분기가 적재 전에 return 하면서 `markFailed` 만 부르고 `process()` 도
     * 재집계 **앞에서** return 한다. 결과 — `status=FAILED · succeeded_count=0` 인데 실제로는 옮겨진
     * 이슈가 있고, 사유 문구는 문자 그대로 `nothing was migrated` 라 **거짓말**이다.
     *
     * ## 무엇을 단언하나
     * - 장부가 실제와 맞는다 (`succeeded_count` = 실제로 옮겨진 수).
     * - 사유가 **정산된 카운트에서 유도**된다. 이미 옮긴 것이 있으면 0건을 주장하지 않는다 —
     *   운영자가 부분 이관과 0건 이관을 구분할 수 있어야 한다(M4 가 0건 쪽 짝이다).
     * - 남은 PENDING 항목은 **남기되 그 수를 로그로 드러낸다**. 근거는 [BulkOperationProcessor] KDoc.
     */
    @Test
    fun `M10 - 상한 초과로 끝나도 이미 옮긴 건이 장부와 사유에 반영된다`() {
        // 재전달 사이에 유입이 늘어 재스캔이 상한을 넘는다.
        insertIssuesBulk(SCOPE_KEY, "in_review", BULK_OPERATION_MAX_SIZE + 1)
        // 1차 실행이 이미 옮긴 2건 — 지금은 대상 상태가 아니다.
        val alreadyMigrated = (1..2).map { insertIssue(SCOPE_KEY, "in_progress") }
        // 1차 실행이 적재했지만 처리하지 못한 1건.
        val notProcessed = "$SCOPE_KEY-1"

        val operationId = enqueue(projectKeys = setOf(SCOPE_KEY))
        // 크래시 재현 — 항목 3건이 적재됐고 2건은 SUCCEEDED 인데 아직 재집계를 안 해 장부는 0 이다.
        alreadyMigrated.forEach { insertItem(operationId, it, ItemStatus.SUCCEEDED) }
        insertItem(operationId, notProcessed, ItemStatus.PENDING)
        setTotalCount(operationId, 3)

        val logs = captureProcessorLogs { worker.pollAndProcess() }

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.status).isEqualTo(BulkOperationStatus.FAILED)
        assertThat(operation.succeededCount)
            .describedAs("이미 옮겨진 2건이 장부에 반영돼야 한다 — 재집계를 markFailed 앞에서 하지 않으면 0 이다")
            .isEqualTo(2)
        assertThat(operation.processedCount).isEqualTo(2)
        assertThat(operation.totalCount).isEqualTo(3)

        // 아무것도 새로 옮기지 않았다 — 초과 분기는 적재도 적용도 하지 않는다.
        assertThat(countIssuesInState(SCOPE_KEY, "in_review")).isEqualTo(BULK_OPERATION_MAX_SIZE + 1)
        alreadyMigrated.forEach { assertThat(stateOf(it)).isEqualTo("in_progress") }

        // 남은 PENDING 은 남긴다 — 지우거나 없던 실패로 꾸미지 않는다(명시된 선택, KDoc 참조).
        val leftPending =
            bulkRepo.findItemsByOperationId(BulkOperationId(operationId))
                .filter { it.status == ItemStatus.PENDING }
        assertThat(leftPending.map { it.issueKey.value }).containsExactly(notProcessed)

        val reason =
            logs.filter { it.level.isGreaterOrEqual(Level.WARN) }
                .map { it.formattedMessage }
                .filter { it.contains(operationId.toString()) }
                .joinToString("\n")
        assertThat(reason).isNotBlank()
        assertThat(reason)
            .describedAs("이미 옮긴 것이 있는데 「아무것도 안 옮겼다」고 적으면 운영자가 DB 를 잘못 읽는다")
            .doesNotContain("nothing was migrated")
        assertThat(reason).containsIgnoringCase("already migrated")
        assertThat(reason).contains("migrated=2")
        assertThat(reason).contains("leftPending=1")
        // 막다른 길 안내는 그대로 남는다 — 사유가 바뀌어도 분할 재시도는 여전히 유일한 탈출구다.
        assertThat(reason).containsIgnoringCase("split")
        assertThat(reason).containsIgnoringCase("retry")
    }

    // ── M11. 상한 경계 — M4 의 비-공허 짝 ─────────────────────────────────────

    /**
     * M11 (게이트 2 리뷰 ⑥) — **대상이 정확히 [BULK_OPERATION_MAX_SIZE] 면 실패가 아니라 전량 이관된다**.
     *
     * M4 는 상한 **초과**만 본다. 그래서 판정의 `>` 를 `>=` 로 바꾸면 정확히 상한인 이관이 전부
     * FAILED 로 죽는데 전 스위트가 초록이었다 — 「N건은 되고 N+1건은 안 된다」에서 N 을 못 박는 쪽이
     * 없었다. 이 테스트가 그 짝이다. 판정은 두 곳(적재 쿼리·처리기 분기)에 있고 어느 쪽을 바꿔도
     * `COMPLETED` 또는 `total_count` 중 하나가 무너진다.
     */
    @Test
    fun `M11 - 대상이 정확히 상한이면 FAILED 가 아니라 전량 이관된다`() {
        insertIssuesBulk(SCOPE_KEY, "in_review", BULK_OPERATION_MAX_SIZE)

        val operationId = enqueue(projectKeys = setOf(SCOPE_KEY))
        worker.pollAndProcess()

        val operation = requireNotNull(bulkRepo.findById(BulkOperationId(operationId))) { "작업을 찾을 수 없음" }
        assertThat(operation.status)
            .describedAs("정확히 상한인 대상은 초과가 아니다 — FAILED 면 경계 판정이 `>=` 로 밀린 것이다")
            .isEqualTo(BulkOperationStatus.COMPLETED)
        assertThat(operation.totalCount).isEqualTo(BULK_OPERATION_MAX_SIZE)
        assertThat(operation.succeededCount).isEqualTo(BULK_OPERATION_MAX_SIZE)
        assertThat(operation.failedCount).isEqualTo(0)
        assertThat(countIssuesInState(SCOPE_KEY, "in_review")).isEqualTo(0)
        assertThat(countIssuesInState(SCOPE_KEY, "in_progress")).isEqualTo(BULK_OPERATION_MAX_SIZE)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /** 기준 커맨드로 이관을 큐잉한다. 매핑은 2개 — 범위와 시점만 테스트마다 흔든다. */
    private fun enqueue(projectKeys: Set<String>): UUID =
        port.enqueueStatusMigration(
            StatusMigrationCommand(
                actorUserId = ACTOR_ID,
                projectKeys = projectKeys,
                mappings =
                    listOf(
                        StatusMigrationMapping("in_review", "in_progress"),
                        StatusMigrationMapping("blocked", "todo"),
                    ),
            ),
        )

    /** 이슈 1건의 현재 상태 키. */
    private fun stateOf(issueKey: String): String =
        dsl.select(ISSUES.CURRENT_STATE_KEY)
            .from(ISSUES)
            .where(ISSUES.KEY.eq(issueKey))
            .fetchOne(ISSUES.CURRENT_STATE_KEY)
            ?: error("이슈를 찾을 수 없음: $issueKey")

    /** 프로젝트 안에서 특정 상태인 이슈 수. */
    private fun countIssuesInState(
        projectKey: String,
        stateKey: String,
    ): Int =
        dsl.fetchCount(
            ISSUES,
            ISSUES.KEY.like("$projectKey-%").and(ISSUES.CURRENT_STATE_KEY.eq(stateKey)),
        )

    /** 도메인 경유 항목 키 목록. */
    private fun itemKeys(operationId: UUID): List<String> =
        bulkRepo.findItemsByOperationId(BulkOperationId(operationId)).map { it.issueKey.value }

    /** ★DB 원본 그대로의 항목 키 목록 — 중복 판정은 도메인이 아니라 행으로 본다(M5). */
    private fun allItemKeys(operationId: UUID): List<String> =
        dsl.select(BULK_OPERATION_ITEMS.ISSUE_KEY)
            .from(BULK_OPERATION_ITEMS)
            .where(BULK_OPERATION_ITEMS.BULK_OPERATION_ID.eq(operationId))
            .fetch(BULK_OPERATION_ITEMS.ISSUE_KEY)
            .filterNotNull()

    /** 크래시 재현용 — 워커가 적재하다 만 PENDING 항목 1건. */
    private fun insertPendingItem(
        operationId: UUID,
        issueKey: String,
    ) = insertItem(operationId, issueKey, ItemStatus.PENDING)

    /** 크래시 시점의 항목 1건을 원하는 상태로 심는다 — 「적재는 됐고 일부는 이미 끝났다」를 만든다(M10). */
    private fun insertItem(
        operationId: UUID,
        issueKey: String,
        status: ItemStatus,
    ) {
        dsl.insertInto(BULK_OPERATION_ITEMS)
            .set(BULK_OPERATION_ITEMS.ID, UUID.randomUUID())
            .set(BULK_OPERATION_ITEMS.BULK_OPERATION_ID, operationId)
            .set(BULK_OPERATION_ITEMS.ISSUE_KEY, issueKey)
            .set(BULK_OPERATION_ITEMS.STATUS, status.name)
            .execute()
    }

    /** 1차 실행이 확정해 둔 `total_count` 를 재현한다 — 재집계는 이 값을 건드리지 않는다(M10). */
    private fun setTotalCount(
        operationId: UUID,
        totalCount: Int,
    ) {
        dsl.update(BULK_OPERATIONS)
            .set(BULK_OPERATIONS.TOTAL_COUNT, totalCount)
            .where(BULK_OPERATIONS.ID.eq(operationId))
            .execute()
    }

    /** 적재 뒤 제3자가 이슈를 옮긴 상황 재현 (E8). */
    private fun moveTo(
        issueKey: String,
        stateKey: String,
    ) {
        dsl.update(ISSUES)
            .set(ISSUES.CURRENT_STATE_KEY, stateKey)
            .where(ISSUES.KEY.eq(issueKey))
            .execute()
    }

    /** 이슈 1건을 소프트 삭제한다 (M8). 물리 삭제가 아니므로 행은 남고 `deleted_at` 만 채워진다. */
    private fun softDeleteIssue(issueKey: String) {
        dsl.update(ISSUES)
            .set(ISSUES.DELETED_AT, OffsetDateTime.now())
            .where(ISSUES.KEY.eq(issueKey))
            .execute()
    }

    /** 프로젝트 1건을 소프트 삭제한다 (M9). 큐잉 뒤 삭제를 재현한다. */
    private fun softDeleteProject(projectKey: String) {
        dsl.update(PROJECTS)
            .set(PROJECTS.DELETED_AT, OffsetDateTime.now())
            .where(PROJECTS.KEY.eq(projectKey))
            .execute()
    }

    /** [softDeleteProject] 를 되돌린다 — 픽스처가 다음 테스트로 새지 않게 한다. */
    private fun restoreProject(projectKey: String) {
        dsl.update(PROJECTS)
            .setNull(PROJECTS.DELETED_AT)
            .where(PROJECTS.KEY.eq(projectKey))
            .execute()
    }

    /** [BulkOperationProcessor] 로거에 [ListAppender] 를 붙인 채 [block] 을 실행하고 로그를 돌려준다. */
    private fun captureProcessorLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(BulkOperationProcessor::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.toList()
        } finally {
            logger.detachAppender(appender)
        }
    }

    /** Flyway — issue-tracking(bulk_operations·pgmq) + project-workflow(statuses 카탈로그) 단일 pass. */
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
     * 프로젝트 2개(범위 안·밖)와 상태 카탈로그를 이 테스트가 직접 심는다.
     *
     * 워크플로우 스킴은 심지 않는다 — 이관은 엔진을 타지 않으므로 필요 없고,
     * 없는 편이 「엔진을 우회한다」는 사실을 픽스처가 거짓말하지 않는다.
     */
    private fun seedFixtures() {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false
            listOf(
                SCOPE_KEY to "Status Migration In Scope",
                OUT_OF_SCOPE_KEY to "Status Migration Out Of Scope",
                DELETED_SCOPE_KEY to "Status Migration Soft Deleted Scope",
            )
                .forEach { (key, name) ->
                    conn.prepareStatement("INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING").use { stmt ->
                        stmt.setString(1, key)
                        stmt.setString(2, name)
                        stmt.executeUpdate()
                    }
                }

            // 아카이브된 프로젝트(M7) — `archived_at IS NOT NULL` 단독이 가드의 판정 술어다.
            // `deleted_at` 은 건드리지 않는다. 두 축은 직교하며 섞으면 M9 의 판별력이 사라진다.
            conn.prepareStatement(
                "INSERT INTO projects (key, name, archived_at) VALUES (?, ?, NOW()) " +
                    "ON CONFLICT (key) DO UPDATE SET archived_at = NOW()",
            ).use { stmt ->
                stmt.setString(1, ARCHIVED_KEY)
                stmt.setString(2, "Status Migration Archived Scope")
                stmt.executeUpdate()
            }

            val workflowId: UUID =
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('migration-exec-wf', 'Migration Exec') " +
                        "ON CONFLICT (key) WHERE deleted_at IS NULL DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            insertWorkflowStatus(conn, workflowId, "todo", "To Do", "TODO", 0)
            insertWorkflowStatus(conn, workflowId, "in_progress", "In Progress", "IN_PROGRESS", 1)
            insertWorkflowStatus(conn, workflowId, "in_review", "In Review", "IN_PROGRESS", 2)
            insertWorkflowStatus(conn, workflowId, "blocked", "Blocked", "IN_PROGRESS", 3)
            insertWorkflowStatus(conn, workflowId, "done", "Done", "DONE", 4)
            conn.commit()
        }
    }

    /** 이슈 1건을 DB 에 직접 넣고 키를 돌려준다 (서비스 우회 — 지정한 상태를 그대로 심는다). */
    private fun insertIssue(
        projectKey: String,
        stateKey: String,
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
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            val issueKey = "$projectKey-$seq"
            conn.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "SELECT ?::text, p.id, ?::text, ?::uuid, ?::text, 1, t.id FROM projects p, issue_types t " +
                    "WHERE p.key = ?::text AND t.key = 'task' AND t.deleted_at IS NULL",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setString(2, "이관 대상 $issueKey")
                stmt.setObject(3, ACTOR_ID)
                stmt.setString(4, stateKey)
                stmt.setString(5, projectKey)
                check(stmt.executeUpdate() == 1) { "이슈 삽입 실패: $issueKey" }
            }
            conn.commit()
            issueKey
        }

    /** 상한 초과 시나리오용 — 이슈 N 건을 한 문장으로 심는다(M4). */
    private fun insertIssuesBulk(
        projectKey: String,
        stateKey: String,
        count: Int,
    ) {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "SELECT ?::text || '-' || g, p.id, '상한 초과 대상', ?::uuid, ?::text, 1, t.id " +
                    "FROM generate_series(1, ?::int) AS g, projects p, issue_types t " +
                    "WHERE p.key = ?::text AND t.key = 'task' AND t.deleted_at IS NULL",
            ).use { stmt ->
                stmt.setString(1, projectKey)
                stmt.setObject(2, ACTOR_ID)
                stmt.setString(3, stateKey)
                stmt.setInt(4, count)
                stmt.setString(5, projectKey)
                check(stmt.executeUpdate() == count) { "이슈 대량 삽입 실패" }
            }
            conn.prepareStatement("UPDATE projects SET key_sequence = ? WHERE key = ?").use { stmt ->
                stmt.setLong(1, count.toLong())
                stmt.setString(2, projectKey)
                stmt.executeUpdate()
            }
            conn.commit()
        }
    }
}
