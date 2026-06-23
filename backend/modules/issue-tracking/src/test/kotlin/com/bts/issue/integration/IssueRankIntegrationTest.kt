// PATCH /api/v1/issues/{key}/rank 엔드포인트 HTTP 통합 테스트 — S1~S5 + 엣지 케이스 (FR-BL-01 Task 6)

package com.bts.issue.integration

import com.bts.issue.adapter.inbound.rest.IssueController
import com.bts.issue.adapter.inbound.rest.IssueExceptionHandler
import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.BacklogRankService
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.pdf.IssuePdfRenderer
import com.bts.issue.pdf.IssuePdfTemplate
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
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
 * PATCH /api/v1/issues/{key}/rank HTTP 통합 테스트 (FR-BL-01 Task 6).
 *
 * 실제 Testcontainers PostgreSQL + 전체 스택(issue-tracking + project-workflow BC) 위에서 동작한다.
 * BacklogRankService 를 실 DB 위에 올려 rank 변경 흐름을 end-to-end 로 검증한다.
 *
 * 커버 시나리오.
 * - S1. 두 이슈 사이로 이동 — 정렬 순서 변경 확인.
 * - S2. 맨 앞으로 이동 (previousIssueKey=null).
 * - S3. 맨 뒤로 이동 (nextIssueKey=null).
 * - S4. 신규 이슈 rank=NULL(옵션 B lazy) — 정렬 시 맨 뒤(NULLS LAST).
 * - S5/E13. 이웃 rank=NULL → rebalance 후 정상 배치(200, 투명).
 * - E2. previousIssueKey == nextIssueKey → 400.
 * - E3. 대상 key == 이웃 key → 400.
 * - E4. 이웃이 타 프로젝트 → 400.
 * - E6. 이웃 순서 역전 (prevRank >= nextRank) → 400.
 * - E7. 둘 다 null → 400.
 * - E5. 이웃 이슈 미존재 → 404.
 * - E9. 대상 이슈 미존재 → 404.
 * - 권한 없음 → AlwaysAllowIssuePermissionResolver 를 사용하므로 403 단위는 슬라이스에서 커버.
 * - E11. 같은 rank tie-break — ORDER BY rank, id 결정적 순서.
 *
 * vacuous 방지: 각 케이스가 실제로 실패하는지 확인하기 위해 잘못된 기대값을 일시 삽입 후 제거한다.
 * 구체적으로 S1 마지막 단언 직전에 의도적으로 다른 값을 기대하는 검증을 두고
 * 해당 assertion 이 실패함을 주석으로 문서화한다(실제 코드에서는 올바른 값으로 교체).
 *
 * TestConfig 를 자체 정의하여 BacklogRankService 빈을 포함시킨다.
 * IssueControllerTransitionIntegrationTest.TestConfig 는 BacklogRankService 가 없으므로 재사용하지 않는다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueRankIntegrationTest.RankTestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueRankIntegrationTest {
    // ── Spring Bean 구성 ──────────────────────────────────────────────────────

    /**
     * rank 통합 테스트 전용 Spring 컨텍스트 구성.
     *
     * IssueControllerTransitionIntegrationTest.TestConfig 와 동일한 구조에
     * BacklogRankService 빈을 추가한 자체 구성이다.
     * BacklogRankService 가 없으면 IssueController.rerank 가 RuntimeException 을 던지므로 필수.
     */
    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    open class RankTestConfig {
        companion object {
            /** JVM 단위 singleton Testcontainers — singleton pattern (concurrent-testcontainers 메모리). */
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_rank_test")
                    .withUsername("bts")
                    .withPassword("bts_rank_test")
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
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext {
            return DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())

        // ── issue-tracking 빈 ─────────────────────────────────────────────────

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
        open fun workflowTransitionAdapter(engine: WorkflowEngine): WorkflowTransitionAdapter {
            return WorkflowTransitionAdapter(engine)
        }

        @Bean
        open fun workflowSchemeRepository(dsl: DSLContext): WorkflowSchemeRepository = WorkflowSchemeRepository(dsl)

        @Bean
        open fun projectWorkflowSchemeAssignmentRepository(dsl: DSLContext): ProjectWorkflowSchemeAssignmentRepository {
            return ProjectWorkflowSchemeAssignmentRepository(dsl)
        }

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

        // ── Application Service + BacklogRankService + Controller ─────────────

        @Bean
        open fun clock(): Clock = Clock.systemUTC()

        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                override fun exists(userId: UUID): Boolean = true
            }

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
                historyRecorder = mockk(relaxed = true),
            )

        @Bean
        open fun backlogRankService(
            repo: IssueRepository,
            permissionResolver: AlwaysAllowIssuePermissionResolver,
            dsl: DSLContext,
        ): BacklogRankService = BacklogRankService(repo = repo, permissionResolver = permissionResolver, dsl = dsl)

        @Bean
        open fun issuePdfTemplate(): IssuePdfTemplate = IssuePdfTemplate()

        @Bean
        open fun issuePdfRenderer(template: IssuePdfTemplate): IssuePdfRenderer = IssuePdfRenderer(template)

        @Bean
        open fun issueController(
            service: IssueApplicationService,
            pdfRenderer: IssuePdfRenderer,
            backlogRankService: BacklogRankService,
        ): IssueController = IssueController(service, pdfRenderer, backlogRankService = backlogRankService)

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    // ── 테스트 상수 ──────────────────────────────────────────────────────────

    companion object {
        /** 메인 테스트 프로젝트 키 (정규식 ^[A-Z][A-Z0-9]{1,9}$). */
        private const val PROJECT_KEY = "RANKTEST"

        /** 타 프로젝트 키 — E4(타 프로젝트 이웃) 검증용. */
        private const val OTHER_PROJECT_KEY = "RANKOTHER"

        /** 테스트 reporter/actor UUID — AlwaysAllow 라 실제 존재 여부 무관. */
        private const val ACTOR_UUID = "11111111-1111-4111-8111-111111111111"

        /**
         * 고정 rank 값 상수.
         *
         * 실제 키는 구현 상태에 따라 달라지므로 DB 직접 삽입 시 사용하는 테스트 픽스처 값.
         * spec 에서 예시 키는 "개념 예시"임을 명시 — FR1(끝문자 != 'a') 준수 값을 사용한다.
         */
        private const val RANK_B = "bb"
        private const val RANK_N = "nn"
        private const val RANK_Z = "zz"

        private var bootstrapped = false
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    // ── 라이프사이클 ─────────────────────────────────────────────────────────

    @BeforeAll
    fun setUpAll() {
        if (!bootstrapped) {
            applyMigrations()
            seedProjects()
            bootstrapped = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                ACTOR_UUID,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        // 테스트 간 격리: 이슈 전체 삭제 + key_sequence 초기화
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key IN ('$PROJECT_KEY', '$OTHER_PROJECT_KEY')")
            }
        }
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── S1. 두 이슈 사이로 이동 ──────────────────────────────────────────────

    /**
     * S1 두 이슈 사이로 이동.
     *
     * Given BTS-1(rank="bb"), BTS-2(rank="nn"), BTS-3(rank="zz") 순서.
     * When  BTS-3 을 BTS-1 과 BTS-2 사이로 이동.
     * Then  BTS-3 rank 가 "bb" < rank < "nn" 범위 내로 변경 → 200 OK.
     *
     * vacuous 확인: 이 케이스에서 의도적으로 status().isBadRequest 를 기대하는
     * 단언을 삽입하여 실패함을 확인한 후 올바른 status().isOk 로 교체하였다.
     * (archunit-vacuous 메모리 가이드에 따른 vacuous 방지 절차)
     */
    @Test
    fun `S1 두 이슈 사이로 이동하면 rank 가 이웃 사이 값으로 변경되고 200 OK 를 반환한다`() {
        val key1 = insertIssueWithRank("S1 첫 이슈", RANK_B)
        val key2 = insertIssueWithRank("S1 둘째 이슈", RANK_N)
        val key3 = insertIssueWithRank("S1 셋째 이슈", RANK_Z)

        val body = mapOf("previousIssueKey" to key1, "nextIssueKey" to key2)

        mockMvc.perform(
            patch("/api/v1/issues/$key3/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value(key3))
            .andExpect(jsonPath("$.data.rank").isNotEmpty)

        // rank 값이 실제로 이웃 사이 범위에 있는지 DB 에서 직접 검증한다.
        val newRank = fetchRank(key3)
        check(newRank != null) { "S1: rerank 후 rank 가 null — DB 저장 실패" }
        check(newRank > RANK_B) { "S1: 새 rank($newRank) 가 이전 이슈 rank($RANK_B) 이하" }
        check(newRank < RANK_N) { "S1: 새 rank($newRank) 가 다음 이슈 rank($RANK_N) 이상" }
    }

    // ── S2. 맨 앞으로 이동 ───────────────────────────────────────────────────

    /**
     * S2 맨 앞으로 이동.
     *
     * Given BTS-1(rank="nn") 이 현재 첫 번째 이슈.
     * When  BTS-2 를 맨 앞으로 이동 (previousIssueKey=null, nextIssueKey=BTS-1).
     * Then  BTS-2 rank < "nn" → 200 OK, BTS-2 가 맨 앞.
     */
    @Test
    fun `S2 맨 앞으로 이동하면 현재 첫 이슈 rank 보다 작은 rank 가 부여된다`() {
        val key1 = insertIssueWithRank("S2 현재 첫 이슈", RANK_N)
        val key2 = insertIssueWithRank("S2 이동 대상", RANK_Z)

        val body = mapOf("previousIssueKey" to null, "nextIssueKey" to key1)

        mockMvc.perform(
            patch("/api/v1/issues/$key2/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value(key2))

        val newRank = fetchRank(key2)
        check(newRank != null) { "S2: rerank 후 rank 가 null" }
        check(newRank < RANK_N) { "S2: 새 rank($newRank) 가 현재 첫 이슈 rank($RANK_N) 이상 — 맨 앞 이동 실패" }
    }

    // ── S3. 맨 뒤로 이동 ─────────────────────────────────────────────────────

    /**
     * S3 맨 뒤로 이동.
     *
     * Given BTS-1(rank="bb") 이 현재 마지막 이슈.
     * When  BTS-2(rank="nn") 를 맨 뒤로 이동 (previousIssueKey=BTS-1, nextIssueKey=null).
     * Then  BTS-2 rank > "bb" → 200 OK.
     */
    @Test
    fun `S3 맨 뒤로 이동하면 현재 마지막 이슈 rank 보다 큰 rank 가 부여된다`() {
        val key1 = insertIssueWithRank("S3 현재 마지막 이슈", RANK_B)
        val key2 = insertIssueWithRank("S3 이동 대상", RANK_N)

        val body = mapOf("previousIssueKey" to key1, "nextIssueKey" to null)

        mockMvc.perform(
            patch("/api/v1/issues/$key2/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value(key2))

        val newRank = fetchRank(key2)
        check(newRank != null) { "S3: rerank 후 rank 가 null" }
        check(newRank > RANK_B) { "S3: 새 rank($newRank) 가 현재 마지막 이슈 rank($RANK_B) 이하 — 맨 뒤 이동 실패" }
    }

    // ── S4. 신규 이슈 rank=NULL(옵션 B lazy) ─────────────────────────────────

    /**
     * S4 옵션 B — 신규 이슈 rank=NULL, 정렬 시 맨 뒤(NULLS LAST).
     *
     * Given 이슈 3건: BTS-1(rank="bb"), BTS-2(rank="nn"), BTS-3(rank=NULL, lazy 미부여).
     * When  DB 에서 ORDER BY rank NULLS LAST, created_at, id 로 조회.
     * Then  BTS-3 이 마지막으로 조회됨.
     */
    @Test
    fun `S4 신규 이슈 rank 가 NULL 이면 정렬 시 맨 뒤에 위치한다`() {
        val key1 = insertIssueWithRank("S4 첫 이슈", RANK_B)
        val key2 = insertIssueWithRank("S4 둘째 이슈", RANK_N)
        val key3 = insertIssueWithoutRank("S4 rank 없는 신규 이슈")

        // rank=NULL 이슈는 NULLS LAST 정렬로 맨 뒤에 위치해야 한다.
        val orderedKeys = fetchIssueKeysOrdered()
        check(orderedKeys.size == 3) { "S4: 이슈 3건 삽입 후 ${orderedKeys.size}건 조회" }
        check(orderedKeys[0] == key1) { "S4: 첫 번째 이슈가 $key1 이어야 하는데 ${orderedKeys[0]}" }
        check(orderedKeys[1] == key2) { "S4: 두 번째 이슈가 $key2 이어야 하는데 ${orderedKeys[1]}" }
        check(orderedKeys[2] == key3) { "S4: NULL rank 이슈($key3)가 맨 뒤여야 하는데 ${orderedKeys[2]}" }
    }

    // ── S5/E13. 이웃 rank=NULL → rebalance 후 정상 배치 ─────────────────────

    /**
     * S5/E13 이웃 rank=NULL 이면 rebalance 트리거 후 정상 200.
     *
     * Given BTS-1(rank=NULL, lazy 미부여), BTS-2(rank=NULL, lazy 미부여).
     * When  BTS-3(rank="nn") 을 BTS-1 앞, BTS-2 뒤 사이로 이동.
     *       이웃 rank 가 NULL 이라 E13 조건 발동 → rebalance → 재조회 → between.
     * Then  200 OK, BTS-3 rank 가 유효한 값.
     */
    @Test
    fun `S5 E13 이웃 rank 가 NULL 이면 rebalance 후 정상 배치되고 200 을 반환한다`() {
        val key1 = insertIssueWithoutRank("E13 이전 이슈 rank=NULL")
        val key2 = insertIssueWithoutRank("E13 다음 이슈 rank=NULL")
        val key3 = insertIssueWithRank("E13 이동 대상", RANK_N)

        val body = mapOf("previousIssueKey" to key1, "nextIssueKey" to key2)

        mockMvc.perform(
            patch("/api/v1/issues/$key3/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value(key3))

        // rebalance 후 BTS-3 rank 가 null 이 아닌 유효한 값이어야 한다.
        val newRank = fetchRank(key3)
        check(newRank != null) { "E13: rebalance + rerank 후 rank 가 여전히 null" }
    }

    // ── E2. previousIssueKey == nextIssueKey → 400 ───────────────────────────

    /**
     * E2 같은 이웃 키를 prev, next 에 모두 전달하면 400.
     */
    @Test
    fun `E2 previousIssueKey 와 nextIssueKey 가 같으면 400 을 반환한다`() {
        val key1 = insertIssueWithRank("E2 이슈 A", RANK_B)
        val key2 = insertIssueWithRank("E2 이동 대상", RANK_N)

        val body = mapOf("previousIssueKey" to key1, "nextIssueKey" to key1)

        mockMvc.perform(
            patch("/api/v1/issues/$key2/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── E3. 대상 key == 이웃 key → 400 ──────────────────────────────────────

    /**
     * E3 대상 이슈와 이웃 이슈가 같으면 400.
     */
    @Test
    fun `E3 대상 이슈와 이웃 이슈가 동일하면 400 을 반환한다`() {
        val key1 = insertIssueWithRank("E3 이슈 A", RANK_B)
        val key2 = insertIssueWithRank("E3 이동 대상", RANK_N)

        // 대상(key2) 과 nextIssueKey(key2) 가 같은 경우
        val body = mapOf("previousIssueKey" to key1, "nextIssueKey" to key2)

        mockMvc.perform(
            patch("/api/v1/issues/$key2/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── E4. 이웃이 타 프로젝트 → 400 ────────────────────────────────────────

    /**
     * E4 이웃 이슈가 다른 프로젝트에 속하면 400.
     */
    @Test
    fun `E4 이웃 이슈가 타 프로젝트이면 400 을 반환한다`() {
        val targetKey = insertIssueWithRank("E4 이동 대상 (메인 프로젝트)", RANK_N)
        val otherKey = insertIssueInOtherProject("E4 타 프로젝트 이슈")

        val body = mapOf("previousIssueKey" to otherKey, "nextIssueKey" to null)

        mockMvc.perform(
            patch("/api/v1/issues/$targetKey/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── E5. 이웃 이슈 미존재 → 404 ───────────────────────────────────────────

    /**
     * E5 이웃 이슈가 없으면 404.
     */
    @Test
    fun `E5 이웃 이슈가 존재하지 않으면 404 를 반환한다`() {
        val targetKey = insertIssueWithRank("E5 이동 대상", RANK_N)

        val body = mapOf("previousIssueKey" to "$PROJECT_KEY-9999", "nextIssueKey" to null)

        mockMvc.perform(
            patch("/api/v1/issues/$targetKey/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
    }

    // ── E6. 이웃 순서 역전 → 400 ─────────────────────────────────────────────

    /**
     * E6 prev rank >= next rank (클라 stale) 이면 400.
     */
    @Test
    fun `E6 이웃 이슈 순서가 역전되면 400 을 반환한다`() {
        // key1(rank="zz") 이 key2(rank="bb") 보다 큰 rank 를 갖도록 역순 삽입
        val key1 = insertIssueWithRank("E6 이슈 순서 역전 — 뒤 이슈", RANK_Z)
        val key2 = insertIssueWithRank("E6 이슈 순서 역전 — 앞 이슈", RANK_B)
        val targetKey = insertIssueWithRank("E6 이동 대상", RANK_N)

        // previousIssueKey=key1(rank=zz), nextIssueKey=key2(rank=bb) — prevRank > nextRank 역전
        val body = mapOf("previousIssueKey" to key1, "nextIssueKey" to key2)

        mockMvc.perform(
            patch("/api/v1/issues/$targetKey/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── E7. 둘 다 null → 400 ─────────────────────────────────────────────────

    /**
     * E7 previousIssueKey 와 nextIssueKey 가 모두 null 이면 400.
     */
    @Test
    fun `E7 이웃 키가 모두 null 이면 400 을 반환한다`() {
        val targetKey = insertIssueWithRank("E7 이동 대상", RANK_N)

        val body = mapOf("previousIssueKey" to null, "nextIssueKey" to null)

        mockMvc.perform(
            patch("/api/v1/issues/$targetKey/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── E9. 대상 이슈 미존재 → 404 ───────────────────────────────────────────

    /**
     * E9 대상 이슈가 없으면 404.
     */
    @Test
    fun `E9 대상 이슈가 존재하지 않으면 404 를 반환한다`() {
        val neighbor = insertIssueWithRank("E9 이웃 이슈", RANK_B)

        val body = mapOf("previousIssueKey" to null, "nextIssueKey" to neighbor)

        mockMvc.perform(
            patch("/api/v1/issues/$PROJECT_KEY-9998/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
    }

    // ── E11. tie-break — 같은 rank 두 이슈 결정적 순서 ─────────────────────

    /**
     * E11 동일 rank 이슈 두 건은 ORDER BY rank, id 로 결정적 순서를 보장한다.
     *
     * Given BTS-1, BTS-2 가 동일 rank("nn") 를 갖는다(동시 삽입 시뮬레이션).
     * When  ORDER BY rank NULLS LAST, created_at, id 로 조회.
     * Then  BTS-1 이 항상 BTS-2 앞에 위치 (created_at, id 오름차순 tie-break).
     */
    @Test
    fun `E11 동일 rank 이슈는 created_at 과 id 로 결정적 순서를 보장한다`() {
        val key1 = insertIssueWithRank("E11 첫 번째 동일 rank 이슈", RANK_N)
        val key2 = insertIssueWithRank("E11 두 번째 동일 rank 이슈", RANK_N)

        val orderedKeys = fetchIssueKeysOrdered()
        check(orderedKeys.size >= 2) { "E11: 이슈 2건 이상이어야 하는데 ${orderedKeys.size}건" }
        val idx1 = orderedKeys.indexOf(key1)
        val idx2 = orderedKeys.indexOf(key2)
        check(idx1 < idx2) { "E11: key1($key1, idx=$idx1) 이 key2($key2, idx=$idx2) 앞이어야 함 — tie-break 실패" }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — issue-tracking + project-workflow 를 단일 pass 로 적용.
     */
    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(
                RankTestConfig.postgres.jdbcUrl,
                RankTestConfig.postgres.username,
                RankTestConfig.postgres.password,
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
     * 테스트 프로젝트 시드 (RANKTEST, RANKOTHER).
     *
     * ON CONFLICT DO NOTHING 으로 멱등하게 삽입한다.
     */
    private fun seedProjects() {
        conn().use { c ->
            c.autoCommit = false
            listOf(
                PROJECT_KEY to "Rank Integration Test Project",
                OTHER_PROJECT_KEY to "Other Project for E4",
            ).forEach { (key, name) ->
                c.prepareStatement(
                    "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                ).use { stmt ->
                    stmt.setString(1, key)
                    stmt.setString(2, name)
                    stmt.executeUpdate()
                }
            }
            c.commit()
        }
    }

    /**
     * 지정 rank 를 가진 이슈를 DB 에 직접 삽입하고 이슈 키를 반환한다.
     *
     * Service layer 를 우회하므로 rank 를 직접 지정한다.
     */
    private fun insertIssueWithRank(
        summary: String,
        rank: String,
    ): String = insertIssueInternal(summary, rank)

    /**
     * rank=NULL(옵션 B lazy) 이슈를 DB 에 직접 삽입하고 이슈 키를 반환한다.
     */
    private fun insertIssueWithoutRank(summary: String): String = insertIssueInternal(summary, null)

    /**
     * 타 프로젝트(OTHER_PROJECT_KEY) 이슈를 삽입하고 이슈 키를 반환한다.
     *
     * E4(타 프로젝트 이웃) 검증용.
     */
    private fun insertIssueInOtherProject(summary: String): String {
        return insertIssueInternal(summary, null, projectKey = OTHER_PROJECT_KEY)
    }

    @Suppress("LongMethod")
    private fun insertIssueInternal(
        summary: String,
        rank: String?,
        projectKey: String = PROJECT_KEY,
    ): String {
        return conn().use { c ->
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

            val projectId =
                c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val taskTypeId =
                c.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            if (rank != null) {
                c.prepareStatement(
                    "INSERT INTO issues " +
                        "(key, project_id, summary, reporter_id, current_state_key, version, type_id, rank) " +
                        "VALUES (?, ?, ?, ?, 'open', 1, ?, ?)",
                ).use { stmt ->
                    stmt.setString(1, issueKey)
                    stmt.setObject(2, projectId)
                    stmt.setString(3, summary)
                    stmt.setObject(4, UUID.fromString(ACTOR_UUID))
                    stmt.setLong(5, taskTypeId)
                    stmt.setString(6, rank)
                    stmt.executeUpdate()
                }
            } else {
                c.prepareStatement(
                    "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                        "VALUES (?, ?, ?, ?, 'open', 1, ?)",
                ).use { stmt ->
                    stmt.setString(1, issueKey)
                    stmt.setObject(2, projectId)
                    stmt.setString(3, summary)
                    stmt.setObject(4, UUID.fromString(ACTOR_UUID))
                    stmt.setLong(5, taskTypeId)
                    stmt.executeUpdate()
                }
            }

            c.commit()
            issueKey
        }
    }

    /**
     * 이슈의 rank 를 DB 에서 단건 조회한다.
     *
     * @return rank 문자열. 미존재 또는 null 이면 null.
     */
    @Suppress("NestedBlockDepth")
    private fun fetchRank(issueKey: String): String? =
        conn().use { c ->
            c.prepareStatement("SELECT rank FROM issues WHERE key = ? AND deleted_at IS NULL").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    /**
     * 현재 프로젝트의 이슈를 ORDER BY rank NULLS LAST, created_at, id 로 조회해 key 목록을 반환한다.
     *
     * E11(tie-break), S4(NULLS LAST 정렬) 검증에 사용된다.
     */
    @Suppress("NestedBlockDepth")
    private fun fetchIssueKeysOrdered(): List<String> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT key FROM issues " +
                    "WHERE deleted_at IS NULL AND key LIKE '$PROJECT_KEY-%' " +
                    "ORDER BY rank NULLS LAST, created_at, id",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val keys = mutableListOf<String>()
                    while (rs.next()) keys.add(rs.getString(1))
                    keys
                }
            }
        }

    /**
     * Testcontainers PostgreSQL 에 직접 JDBC 연결을 반환한다.
     */
    private fun conn() =
        DriverManager.getConnection(
            RankTestConfig.postgres.jdbcUrl,
            RankTestConfig.postgres.username,
            RankTestConfig.postgres.password,
        )
}
