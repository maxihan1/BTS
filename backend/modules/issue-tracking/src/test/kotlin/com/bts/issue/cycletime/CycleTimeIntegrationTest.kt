// Cycle/Lead Time HTTP 통합 테스트 — 실 Testcontainers PostgreSQL + 실 컨트롤러/서비스/리포지토리 전체 스택 검증 (FR-RP-04 Task 7)
@file:Suppress("MaxLineLength")

package com.bts.issue.cycletime

import com.bts.issue.adapter.outbound.velocity.IsolatedWorkflowStateLookup
import com.bts.issue.cycletime.application.CycleTimeService
import com.bts.issue.cycletime.web.CycleTimeController
import com.bts.issue.cycletime.web.CycleTimeExceptionHandler
import com.bts.issue.cycletime.web.dto.CycleTimeResponse
import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_GROUP
import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_ITEM
import com.bts.issue.repository.IssueRepository
import com.bts.issue.statushistory.repository.StatusHistoryRepository
import com.bts.issue.testsupport.insertWorkflowStatus
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.adapter.inbound.WorkflowResolverImpl
import com.bts.workflow.scheme.adapter.inbound.WorkflowStateCatalogImpl
import com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver
import com.bts.workflow.scheme.adapter.outbound.JdbcProjectLookupAdapter
import com.bts.workflow.scheme.adapter.outbound.WorkflowSchemeEventPublisher
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.application.port.IssueTypeLookupPort
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import org.springframework.context.annotation.Import
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
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
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * FR-RP-04 Task 7 — GET /api/v1/projects/{projectKey}/cycle-time 실 스택 통합 테스트.
 *
 * 실제 Testcontainers PostgreSQL + 실 컨트롤러→서비스→리포지토리(jOOQ) 경로로 조회한다.
 * [com.bts.issue.cfd.CfdIntegrationTest]를 미러한다 — status 전환 이력은 실 전환 서비스를 거치지
 * 않고 `issue_change_group`/`issue_change_item`에 **직접 jOOQ insert**하되 `created_at`을 명시적
 * [OffsetDateTime]으로 지정해 결정적 duration을 만든다.
 *
 * ## 결정성 — 명시 from/to
 * 모든 요청에 from/to를 명시적으로 지정해 [CycleTimeController]의 [java.time.Clock] 기본값에
 * 의존하지 않는다. Clock 결정성 문제 자체는 [com.bts.issue.cycletime.web.CycleTimeControllerTest]가
 * 이미 검증했다.
 *
 * ## 워크플로우 스킴
 * cycletime-scheme(default mapping → cycletime-workflow: open=TODO, in_progress=IN_PROGRESS,
 * done=DONE)를 CYCP 프로젝트에 배정한다.
 *
 * ## 검증 시나리오
 * - S1. 정상 — IN_PROGRESS→DONE 전환 시각 차이로 cycle/lead 표본·통계가 정확히 계산된다.
 * - S2. 미경유 — TODO→DONE 직행 이슈는 lead 표본에는 남고 cycle 표본에서는 제외된다(비-vacuous).
 * - S4. 창 밖 — 완료일이 to보다 뒤인 이슈는 모집단에서 제외된다.
 * - S5. 빈 결과 — 창 내 완료 이슈 0건이면 count 0·samples []·통계 null.
 * - S6. 기밀 누출 차단 — 뷰어가 접근 불가한 보안등급 이슈는 samples에서 제외된다(비-vacuous).
 * - S7. BROWSE 권한 없음 → 403.
 * - S8. 잘못된 창(from>to / 180일 초과) → 400.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [CycleTimeIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("TooManyFunctions", "LargeClass")
class CycleTimeIntegrationTest {
    /**
     * 기본 BROWSE 허용 resolver. denyBrowseForProject()로 특정 프로젝트 BROWSE 거부를 제어한다.
     * ([com.bts.issue.cfd.CfdIntegrationTest.SwitchablePermissionResolver] 동형)
     */
    class SwitchablePermissionResolver : IssuePermissionResolver {
        private val denyBrowseProject = ThreadLocal<String?>()

        fun denyBrowseForProject(projectKey: String) = denyBrowseProject.set(projectKey)

        fun resetPermissions() = denyBrowseProject.remove()

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            if (permission == IssuePermission.BROWSE && scope is IssueScope.Project) {
                if (scope.key == denyBrowseProject.get()) return false
            }
            return true
        }
    }

    /**
     * 보안 등급 접근을 토글 가능한 스텁.
     *
     * 기본(reset 상태): unrestricted=true — 모든 이슈 열람 가능.
     * restrict(levelId) 호출 후: unrestricted=false, 나머지 집합 전부 비어있음 →
     * securityLevelId가 NULL이 아닌 이슈는 전부 제외된다.
     * ([com.bts.issue.cfd.CfdIntegrationTest.SwitchableSecurityDirectory] 동형)
     */
    internal class SwitchableSecurityDirectory : IssueSecurityDirectory {
        private val restrictedLevelId = ThreadLocal<UUID?>()

        fun restrict(levelId: UUID) = restrictedLevelId.set(levelId)

        fun reset() = restrictedLevelId.remove()

        override fun levelBelongsToProjectScheme(
            levelId: UUID,
            projectKey: String,
        ): Boolean = true

        override fun accessibleLevels(
            actorId: UUID,
            projectKey: String,
        ): IssueSecurityAccess {
            return if (restrictedLevelId.get() == null) {
                IssueSecurityAccess(
                    unrestricted = true,
                    staticLevelIds = emptySet(),
                    reporterLevelIds = emptySet(),
                    assigneeLevelIds = emptySet(),
                )
            } else {
                IssueSecurityAccess(
                    unrestricted = false,
                    staticLevelIds = emptySet(),
                    reporterLevelIds = emptySet(),
                    assigneeLevelIds = emptySet(),
                )
            }
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    // CycleTimeController는 수동 @Bean이 아니라 @Import로 등록해, Spring이 생성자의 optional Clock
    // 파라미터(기본값 Clock.systemUTC())를 실제로 resolve하는 프로덕션 배선 경로를 테스트가 태우게 한다.
    @Import(CycleTimeController::class)
    open class TestConfig : WebMvcConfigurer {
        /**
         * `@EnableWebMvc` 기본 Jackson 컨버터는 [java.time.LocalDate]를 배열로 직렬화한다.
         * 기존 컨버터를 교체하지 않고 매퍼 설정만 보강해 ISO-8601 문자열로 나오게 한다.
         */
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters.filterIsInstance<MappingJackson2HttpMessageConverter>().forEach { converter ->
                converter.objectMapper
                    .registerModule(JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }
        }

        companion object {
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_cycletime_http_test")
                    .withUsername("bts")
                    .withPassword("bts_cycletime_http_test")
                    .apply { start() }

            val permissionResolver = SwitchablePermissionResolver()
            internal val securityDirectory = SwitchableSecurityDirectory()
        }

        @Bean
        open fun dataSource(): DriverManagerDataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

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
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

        @Bean
        open fun issueTypeRepository(dsl: DSLContext): IssueTypeRepository = IssueTypeRepository(dsl)

        @Bean
        open fun statusHistoryRepository(dsl: DSLContext): StatusHistoryRepository = StatusHistoryRepository(dsl)

        @Bean
        open fun permissionResolver(): IssuePermissionResolver = TestConfig.permissionResolver

        @Bean
        open fun securityDirectory(): IssueSecurityDirectory = TestConfig.securityDirectory

        // ── project-workflow 빈 조립 (CfdIntegrationTest.TestConfig 선례) ──

        @Bean
        open fun workflowRepository(dsl: DSLContext): WorkflowRepository = WorkflowRepository(dsl)

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
        open fun workflowStateCatalogImpl(workflowResolver: WorkflowResolverImpl): WorkflowStateCatalogImpl =
            WorkflowStateCatalogImpl(workflowResolver)

        @Bean
        open fun isolatedWorkflowStateLookup(catalog: WorkflowStateCatalogImpl): IsolatedWorkflowStateLookup =
            IsolatedWorkflowStateLookup(catalog)

        // ── Cycle/Lead Time 빈 ───────────────────────────────────────────────

        @Bean
        @Suppress("LongParameterList")
        open fun cycleTimeService(
            permissionResolver: IssuePermissionResolver,
            issueRepository: IssueRepository,
            statusHistoryRepository: StatusHistoryRepository,
            securityDirectory: IssueSecurityDirectory,
            issueTypeRepository: IssueTypeRepository,
            workflowStateLookup: IsolatedWorkflowStateLookup,
        ): CycleTimeService =
            CycleTimeService(
                permissionResolver = permissionResolver,
                issueRepository = issueRepository,
                statusHistoryRepository = statusHistoryRepository,
                securityDirectory = securityDirectory,
                issueTypeRepository = issueTypeRepository,
                workflowStateLookup = workflowStateLookup,
            )

        // cycleTimeController는 @Import(CycleTimeController::class)로 등록 — Spring이 optional Clock 기본값을 resolve.

        @Bean
        open fun cycleTimeExceptionHandler(): CycleTimeExceptionHandler = CycleTimeExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dsl: DSLContext

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private lateinit var mockMvc: MockMvc

    /** GET /cycle-time 응답 외피 — `{"data": {...}}` 역직렬화 전용 로컬 DTO. */
    private data class Envelope(val data: CycleTimeResponse)

    companion object {
        private const val PROJECT_KEY = "CYCP"
        private var migrated = false
        private var seeded = false

        private var testProjectId: UUID = UUID.randomUUID()

        /** V003 seed의 task 타입 id — 모든 시드 이슈가 공유한다. */
        private var taskTypeId: Long = -1L

        private const val OPEN_KEY = "open"
        private const val IN_PROGRESS_KEY = "in_progress"
        private const val DONE_KEY = "done"

        /** S6 — viewer가 접근 불가한 보안 등급 UUID. */
        val restrictedSecurityLevelId: UUID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")

        val actorUuid: UUID = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")

        /** reporter_id 컬럼 NOT NULL 충족용 sentinel(비-nil UUID) — BC 격리로 FK 미적용. */
        private const val REPORTER_ID = "00000000-0000-0000-0000-000000000001"
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedAll()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorUuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        TestConfig.permissionResolver.resetPermissions()
        TestConfig.securityDirectory.reset()
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues WHERE project_id = '$testProjectId'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE id = '$testProjectId'")
            }
        }
    }

    // ── S1. 정상 — 전환 시각 차이로 cycle/lead 표본·통계가 정확히 계산된다 ──────

    /**
     * S1. IN_PROGRESS→DONE 전환 시각 차이로 cycle/lead 표본·통계가 정확히 계산된다.
     *
     * Given  이슈 A(06-01 생성, 06-02 open→in_progress, 06-03 in_progress→done 전환, 창 내)
     * When   GET /api/v1/projects/CYCP/cycle-time?from=2026-06-01&to=2026-06-05
     * Then   200, cycleTime 표본 seconds=(06-03−06-02), leadTime 표본 seconds=(06-03−06-01),
     *          count=1이라 min=max=avg=p25~p90 모두 표본값과 동일.
     */
    @Test
    fun `S1 정상 - 전환 시각 차이로 cycle,lead 표본·통계가 정확히 계산된다`() {
        val created = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val inProgressAt = OffsetDateTime.of(2026, 6, 2, 0, 0, 0, 0, ZoneOffset.UTC)
        val doneAt = OffsetDateTime.of(2026, 6, 3, 0, 0, 0, 0, ZoneOffset.UTC)
        val (key, id) = createIssue(testProjectId, PROJECT_KEY, "이슈 A", DONE_KEY, taskTypeId, created)
        seedStatusChange(id, key, inProgressAt, OPEN_KEY, IN_PROGRESS_KEY)
        seedStatusChange(id, key, doneAt, IN_PROGRESS_KEY, DONE_KEY)

        val expectedCycleSeconds = Duration.between(inProgressAt.toInstant(), doneAt.toInstant()).seconds
        val expectedLeadSeconds = Duration.between(created.toInstant(), doneAt.toInstant()).seconds

        val response = fetchCycleTime("2026-06-01", "2026-06-05")

        assertThat(response.projectKey).isEqualTo(PROJECT_KEY)

        assertThat(response.cycleTime.count).isEqualTo(1)
        assertThat(response.cycleTime.samples).hasSize(1)
        assertThat(response.cycleTime.samples[0].issueKey).isEqualTo(key)
        assertThat(response.cycleTime.samples[0].seconds).isEqualTo(expectedCycleSeconds)
        assertThat(response.cycleTime.min).isEqualTo(expectedCycleSeconds)
        assertThat(response.cycleTime.max).isEqualTo(expectedCycleSeconds)
        assertThat(response.cycleTime.avg).isEqualTo(expectedCycleSeconds)
        assertThat(response.cycleTime.p25).isEqualTo(expectedCycleSeconds)
        assertThat(response.cycleTime.p50).isEqualTo(expectedCycleSeconds)
        assertThat(response.cycleTime.p75).isEqualTo(expectedCycleSeconds)
        assertThat(response.cycleTime.p90).isEqualTo(expectedCycleSeconds)

        assertThat(response.leadTime.count).isEqualTo(1)
        assertThat(response.leadTime.samples).hasSize(1)
        assertThat(response.leadTime.samples[0].issueKey).isEqualTo(key)
        assertThat(response.leadTime.samples[0].seconds).isEqualTo(expectedLeadSeconds)
        assertThat(response.leadTime.min).isEqualTo(expectedLeadSeconds)
        assertThat(response.leadTime.max).isEqualTo(expectedLeadSeconds)
        assertThat(response.leadTime.avg).isEqualTo(expectedLeadSeconds)
    }

    // ── S2. 미경유 — TODO→DONE 직행 이슈는 lead에는 남고 cycle에서는 제외된다 ──

    /**
     * S2. TODO→DONE 직행(IN_PROGRESS 미경유) 이슈는 lead 표본에는 남고 cycle 표본에서는
     * 제외된다 — count 차이로 비-vacuous 검증한다.
     *
     * Given  이슈 A(정상 경유: open→in_progress→done)
     *          이슈 B(미경유 직행: open→done, IN_PROGRESS 없음)
     * When   GET /api/v1/projects/CYCP/cycle-time?from=2026-06-01&to=2026-06-05
     * Then   200, leadTime.count=2(A,B 둘 다) > cycleTime.count=1(A만) — B는 lead 표본에는
     *          있고 cycle 표본에는 없음을 issueKey 집합 대조로 실측한다.
     */
    @Test
    fun `S2 미경유 - TODO에서 DONE 직행 이슈는 lead표본엔 남고 cycle표본에선 제외된다`() {
        val created = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val inProgressAt = OffsetDateTime.of(2026, 6, 2, 0, 0, 0, 0, ZoneOffset.UTC)
        val doneAtA = OffsetDateTime.of(2026, 6, 3, 0, 0, 0, 0, ZoneOffset.UTC)
        val (keyA, idA) = createIssue(testProjectId, PROJECT_KEY, "이슈 A - 정상 경유", DONE_KEY, taskTypeId, created)
        seedStatusChange(idA, keyA, inProgressAt, OPEN_KEY, IN_PROGRESS_KEY)
        seedStatusChange(idA, keyA, doneAtA, IN_PROGRESS_KEY, DONE_KEY)

        val doneAtB = OffsetDateTime.of(2026, 6, 4, 0, 0, 0, 0, ZoneOffset.UTC)
        val (keyB, idB) = createIssue(testProjectId, PROJECT_KEY, "이슈 B - 미경유 직행", DONE_KEY, taskTypeId, created)
        seedStatusChange(idB, keyB, doneAtB, OPEN_KEY, DONE_KEY)

        val response = fetchCycleTime("2026-06-01", "2026-06-05")

        assertThat(response.leadTime.count).isEqualTo(2)
        assertThat(response.cycleTime.count).isEqualTo(1)
        assertThat(response.leadTime.count).isGreaterThan(response.cycleTime.count)

        val leadKeys = response.leadTime.samples.map { it.issueKey }
        val cycleKeys = response.cycleTime.samples.map { it.issueKey }
        assertThat(leadKeys).containsExactlyInAnyOrder(keyA, keyB)
        assertThat(cycleKeys).containsExactly(keyA)
        assertThat(cycleKeys).doesNotContain(keyB)
    }

    // ── S4. 창 밖 — 완료일이 to보다 뒤인 이슈는 모집단에서 제외된다 ─────────────

    /**
     * S4. 완료(마지막 DONE 전환)일이 `to`보다 뒤인 이슈는 모집단에서 제외된다.
     *
     * Given  창 안 완료 이슈(06-03 완료, 창=06-01~06-05)
     *          창 밖 완료 이슈(06-10 완료, 창 밖)
     * When   GET /api/v1/projects/CYCP/cycle-time?from=2026-06-01&to=2026-06-05
     * Then   200, count=1(창 안 이슈만), 창 밖 이슈의 issueKey는 cycle/lead 어디에도 없음.
     */
    @Test
    fun `S4 창 밖 - 완료일이 to보다 뒤인 이슈는 모집단에서 제외된다`() {
        val created = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val inProgressAt = OffsetDateTime.of(2026, 6, 2, 0, 0, 0, 0, ZoneOffset.UTC)

        val doneInWindow = OffsetDateTime.of(2026, 6, 3, 0, 0, 0, 0, ZoneOffset.UTC)
        val (keyIn, idIn) = createIssue(testProjectId, PROJECT_KEY, "창 안 완료", DONE_KEY, taskTypeId, created)
        seedStatusChange(idIn, keyIn, inProgressAt, OPEN_KEY, IN_PROGRESS_KEY)
        seedStatusChange(idIn, keyIn, doneInWindow, IN_PROGRESS_KEY, DONE_KEY)

        val doneOutOfWindow = OffsetDateTime.of(2026, 6, 10, 0, 0, 0, 0, ZoneOffset.UTC)
        val (keyOut, idOut) = createIssue(testProjectId, PROJECT_KEY, "창 밖 완료", DONE_KEY, taskTypeId, created)
        seedStatusChange(idOut, keyOut, inProgressAt, OPEN_KEY, IN_PROGRESS_KEY)
        seedStatusChange(idOut, keyOut, doneOutOfWindow, IN_PROGRESS_KEY, DONE_KEY)

        val response = fetchCycleTime("2026-06-01", "2026-06-05")

        assertThat(response.leadTime.count).isEqualTo(1)
        assertThat(response.cycleTime.count).isEqualTo(1)
        assertThat(response.leadTime.samples.map { it.issueKey }).containsExactly(keyIn)
        assertThat(response.cycleTime.samples.map { it.issueKey }).containsExactly(keyIn)
        assertThat(response.leadTime.samples.map { it.issueKey }).doesNotContain(keyOut)
        assertThat(response.cycleTime.samples.map { it.issueKey }).doesNotContain(keyOut)
    }

    // ── S5. 빈 결과 — 창 내 완료 이슈 0건 ────────────────────────────────────

    /**
     * S5. 창 내 완료된 이슈가 0건이면 count=0·samples 빈 배열·통계 전 필드 null을 반환한다.
     *
     * Given  CYCP 프로젝트, 이슈 0건(@BeforeEach가 프로젝트 이슈를 정리)
     * When   GET /api/v1/projects/CYCP/cycle-time?from=2026-09-01&to=2026-09-03
     * Then   200, cycleTime/leadTime 모두 count=0, samples=[], min/max/avg/p25~p90=null.
     */
    @Test
    fun `S5 빈 결과 - 창 내 완료 이슈 0건이면 count 0,samples 빈 배열,통계 null`() {
        val response = fetchCycleTime("2026-09-01", "2026-09-03")

        assertThat(response.cycleTime.count).isEqualTo(0)
        assertThat(response.cycleTime.samples).isEmpty()
        assertThat(response.cycleTime.min).isNull()
        assertThat(response.cycleTime.max).isNull()
        assertThat(response.cycleTime.avg).isNull()
        assertThat(response.cycleTime.p25).isNull()
        assertThat(response.cycleTime.p50).isNull()
        assertThat(response.cycleTime.p75).isNull()
        assertThat(response.cycleTime.p90).isNull()

        assertThat(response.leadTime.count).isEqualTo(0)
        assertThat(response.leadTime.samples).isEmpty()
        assertThat(response.leadTime.min).isNull()
    }

    // ── S6. 기밀 누출 차단 — 비-vacuous 대조 ─────────────────────────────────

    /**
     * S6. 뷰어가 접근 불가한 보안 등급(기밀) 이슈는 cycle/lead samples·통계 어디에도 나오지
     * 않는다 — 가시 이슈는 나오고 기밀만 빠지는 대조로 비-vacuous 검증한다.
     *
     * Given  공개 이슈 + 기밀 이슈(둘 다 06-01 생성, 06-02 in_progress, 06-03 done — 창 내 완료)
     * When   ① unrestricted 뷰어로 조회 → leadTime.count=2(둘 다 보임)
     *        ② restrict(restrictedSecurityLevelId) 뷰어로 재조회 → leadTime.count=1(기밀 제외),
     *          cycleTime.count=1, 공개 이슈 키만 samples에 남고 기밀 이슈 키는 어디에도 없음.
     */
    @Test
    fun `S6 기밀 누출 차단 - 뷰어가 접근 불가한 보안등급 이슈는 samples에서 제외된다`() {
        val created = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val inProgressAt = OffsetDateTime.of(2026, 6, 2, 0, 0, 0, 0, ZoneOffset.UTC)
        val doneAt = OffsetDateTime.of(2026, 6, 3, 0, 0, 0, 0, ZoneOffset.UTC)

        val (keyPublic, idPublic) = createIssue(testProjectId, PROJECT_KEY, "공개 이슈", DONE_KEY, taskTypeId, created)
        seedStatusChange(idPublic, keyPublic, inProgressAt, OPEN_KEY, IN_PROGRESS_KEY)
        seedStatusChange(idPublic, keyPublic, doneAt, IN_PROGRESS_KEY, DONE_KEY)

        val (keyConfidential, idConfidential) =
            createIssue(
                testProjectId,
                PROJECT_KEY,
                "기밀 이슈",
                DONE_KEY,
                taskTypeId,
                created,
                securityLevelId = restrictedSecurityLevelId,
            )
        seedStatusChange(idConfidential, keyConfidential, inProgressAt, OPEN_KEY, IN_PROGRESS_KEY)
        seedStatusChange(idConfidential, keyConfidential, doneAt, IN_PROGRESS_KEY, DONE_KEY)

        val unrestrictedResponse = fetchCycleTime("2026-06-01", "2026-06-05")
        assertThat(unrestrictedResponse.leadTime.count).isEqualTo(2)
        assertThat(unrestrictedResponse.leadTime.samples.map { it.issueKey })
            .containsExactlyInAnyOrder(keyPublic, keyConfidential)

        TestConfig.securityDirectory.restrict(restrictedSecurityLevelId)
        try {
            val restrictedResponse = fetchCycleTime("2026-06-01", "2026-06-05")
            assertThat(restrictedResponse.leadTime.count).isEqualTo(1)
            assertThat(restrictedResponse.cycleTime.count).isEqualTo(1)
            assertThat(restrictedResponse.leadTime.samples.map { it.issueKey }).containsExactly(keyPublic)
            assertThat(restrictedResponse.cycleTime.samples.map { it.issueKey }).containsExactly(keyPublic)
            assertThat(restrictedResponse.leadTime.samples.map { it.issueKey }).doesNotContain(keyConfidential)
            assertThat(restrictedResponse.cycleTime.samples.map { it.issueKey }).doesNotContain(keyConfidential)
        } finally {
            TestConfig.securityDirectory.reset()
        }
    }

    // ── S7. BROWSE 권한 없음 → 403 ───────────────────────────────────────────

    /**
     * S7. 프로젝트 BROWSE 권한이 없으면 403을 반환한다.
     *
     * Given  actor에게 CYCP 프로젝트 BROWSE 권한 없음
     * When   GET /api/v1/projects/CYCP/cycle-time?from=2026-06-01&to=2026-06-01
     * Then   403, errorCode=ISSUE_ACCESS_DENIED
     */
    @Test
    fun `S7 권한 없음 - BROWSE 권한 없는 actor는 403 ISSUE_ACCESS_DENIED`() {
        TestConfig.permissionResolver.denyBrowseForProject(PROJECT_KEY)

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cycle-time")
                .param("from", "2026-06-01")
                .param("to", "2026-06-01"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_ACCESS_DENIED"))
    }

    // ── S8. 잘못된 창 → 400 ───────────────────────────────────────────────────

    /**
     * S8a. from이 to보다 늦으면 400 Bad Request.
     */
    @Test
    fun `S8a from이 to보다 늦음 - 400 Bad Request`() {
        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cycle-time")
                .param("from", "2026-07-10")
                .param("to", "2026-07-01"),
        ).andExpect(status().isBadRequest)
    }

    /**
     * S8b. 창 길이 180일 초과 시 400 Bad Request.
     */
    @Test
    fun `S8b 창 길이 180일 초과 - 400 Bad Request`() {
        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cycle-time")
                .param("from", "2026-01-01")
                .param("to", "2026-07-01"),
        ).andExpect(status().isBadRequest)
    }

    // ── private helpers — 조회 ────────────────────────────────────────────────

    /**
     * GET /cycle-time을 호출해 200을 검증하고 응답 본문을 [CycleTimeResponse]로 역직렬화한다.
     *
     * @param from 창 시작일(YYYY-MM-DD).
     * @param to 창 종료일(YYYY-MM-DD).
     * @return 역직렬화된 [CycleTimeResponse].
     */
    private fun fetchCycleTime(
        from: String,
        to: String,
    ): CycleTimeResponse {
        val json =
            mockMvc.perform(
                get("/api/v1/projects/$PROJECT_KEY/cycle-time")
                    .param("from", from)
                    .param("to", to),
            )
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        return objectMapper.readValue(json, Envelope::class.java).data
    }

    // ── private helpers — 시드 ────────────────────────────────────────────────

    /**
     * 이슈를 DB에 직접 삽입하고 (이슈 키, 이슈 UUID) 쌍을 반환한다.
     *
     * `Issue.create()`는 `createdAt=Instant.now()`를 강제해 과거 생성일을 시드할 수 없으므로,
     * raw SQL로 `created_at`을 명시적으로 지정한다.
     *
     * @param projectId 소속 프로젝트 UUID.
     * @param projectKeyPrefix 이슈 키 prefix(예: "CYCP").
     * @param summary 이슈 제목.
     * @param stateKey 현재 워크플로우 상태 키(전환 이력이 있으면 소요 시간 계산에는 쓰이지 않는다).
     * @param typeId 이슈 타입 BIGSERIAL id.
     * @param createdAt 이슈 생성 시각(명시 과거 시각 — Lead Time 시드 필수).
     * @param securityLevelId 보안 등급 UUID. null이면 공개(등급 없음).
     * @return (이슈 키, 이슈 UUID).
     */
    @Suppress("LongParameterList", "NestedBlockDepth")
    private fun createIssue(
        projectId: UUID,
        projectKeyPrefix: String,
        summary: String,
        stateKey: String,
        typeId: Long,
        createdAt: OffsetDateTime,
        securityLevelId: UUID? = null,
    ): Pair<String, UUID> {
        var key: String? = null
        var issueId: UUID? = null
        conn().use { c ->
            c.prepareStatement(
                "UPDATE projects SET key_sequence = key_sequence + 1 WHERE id = ?",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.executeUpdate()
            }
            c.prepareStatement("SELECT key_sequence FROM projects WHERE id = ?").use { stmt ->
                stmt.setObject(1, projectId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    key = "$projectKeyPrefix-${rs.getInt(1)}"
                }
            }
            c.prepareStatement(
                """
                INSERT INTO issues
                    (project_id, key, summary, reporter_id, current_state_key, type_id, security_level_id, created_at, version)
                VALUES (?, ?, ?, '$REPORTER_ID', ?, ?, ?, ?, 1)
                RETURNING id
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, key)
                stmt.setString(3, summary)
                stmt.setString(4, stateKey)
                stmt.setLong(5, typeId)
                stmt.setObject(6, securityLevelId, Types.OTHER)
                stmt.setObject(7, createdAt)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    issueId = rs.getObject(1) as UUID
                }
            }
        }
        return requireNotNull(key) to requireNotNull(issueId)
    }

    /**
     * status 전환 이력 1건(`issue_change_group` 1행 + `issue_change_item` 1행, field="status")을
     * 명시적 과거 시각으로 직접 시드한다(실 전환 서비스는 `created_at=NOW()` 강제).
     *
     * @param issueId 소속 이슈 UUID.
     * @param issueKey 기록 시점 이슈 키.
     * @param changedAt 전환 발생 시각(명시 과거 시각).
     * @param fromValue 전환 전 상태 키.
     * @param toValue 전환 후 상태 키.
     */
    private fun seedStatusChange(
        issueId: UUID,
        issueKey: String,
        changedAt: OffsetDateTime,
        fromValue: String?,
        toValue: String?,
    ) {
        val groupId =
            dsl.insertInto(ISSUE_CHANGE_GROUP)
                .set(ISSUE_CHANGE_GROUP.ISSUE_ID, issueId)
                .set(ISSUE_CHANGE_GROUP.ISSUE_KEY, issueKey)
                .set(ISSUE_CHANGE_GROUP.CREATED_AT, changedAt)
                .returning(ISSUE_CHANGE_GROUP.ID)
                .fetchOne()
                ?.get(ISSUE_CHANGE_GROUP.ID)
                ?: error("issue_change_group INSERT RETURNING id 값이 없음")

        dsl.insertInto(ISSUE_CHANGE_ITEM)
            .set(ISSUE_CHANGE_ITEM.GROUP_ID, groupId)
            .set(ISSUE_CHANGE_ITEM.FIELD, "status")
            .set(ISSUE_CHANGE_ITEM.FROM_VALUE, fromValue)
            .set(ISSUE_CHANGE_ITEM.TO_VALUE, toValue)
            .execute()
    }

    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(TestConfig.postgres.jdbcUrl, TestConfig.postgres.username, TestConfig.postgres.password)
            .placeholderReplacement(false)
            .locations(
                "classpath:db/migration/issue-tracking",
                "classpath:db/migration/project-workflow",
            )
            .load()
            .migrate()
    }

    /** 전체 시드: 프로젝트(CYCP) + task 타입 id 조회 + 워크플로우 스킴 배정. */
    private fun seedAll() {
        conn().use { c ->
            c.autoCommit = false
            seedProject(c)
            resolveTaskTypeId(c)
            seedWorkflowAndScheme(c)
            c.commit()
        }
    }

    @Suppress("NestedBlockDepth")
    private fun seedProject(conn: Connection) {
        conn.prepareStatement(
            "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
        ).use { stmt ->
            stmt.setString(1, PROJECT_KEY)
            stmt.setString(2, "Cycle Time Test Project")
            stmt.executeUpdate()
        }
        conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
            stmt.setString(1, PROJECT_KEY)
            stmt.executeQuery().use { rs ->
                rs.next()
                testProjectId = rs.getObject(1) as UUID
            }
        }
    }

    /** V003 seed의 task 타입 id를 조회한다(모든 테스트 이슈가 공유). */
    @Suppress("NestedBlockDepth")
    private fun resolveTaskTypeId(conn: Connection) {
        conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1").use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                taskTypeId = rs.getLong(1)
            }
        }
    }

    /**
     * cycletime-workflow(open=TODO, in_progress=IN_PROGRESS, done=DONE) +
     * cycletime-scheme(default mapping)을 생성하고 CYCP 프로젝트에 배정한다.
     */
    @Suppress("NestedBlockDepth")
    private fun seedWorkflowAndScheme(conn: Connection) {
        val wfId = insertWorkflow(conn, "cycletime-workflow", "Cycle Time 테스트 워크플로우")
        insertState(conn, wfId, OPEN_KEY, "Open", "TODO", 0)
        insertState(conn, wfId, IN_PROGRESS_KEY, "In Progress", "IN_PROGRESS", 1)
        insertState(conn, wfId, DONE_KEY, "Done", "DONE", 2)
        insertTransition(conn, wfId, OPEN_KEY, IN_PROGRESS_KEY, "Start")
        insertTransition(conn, wfId, IN_PROGRESS_KEY, DONE_KEY, "Finish")

        val schemeId: Long =
            conn.prepareStatement(
                """
                INSERT INTO workflow_schemes (key, name, is_default)
                VALUES ('cycletime-scheme', 'Cycle Time Test Scheme', false)
                ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL
                DO UPDATE SET name = EXCLUDED.name RETURNING id
                """.trimIndent(),
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }

        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                SELECT $schemeId, NULL, '$wfId'
                WHERE NOT EXISTS (
                  SELECT 1 FROM workflow_scheme_issue_type_mappings
                  WHERE scheme_id = $schemeId AND issue_type_id IS NULL
                )
                """.trimIndent(),
            )
        }

        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                SELECT p.id, $schemeId, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                FROM projects p WHERE p.key = '$PROJECT_KEY'
                ON CONFLICT (project_id) DO UPDATE SET workflow_scheme_id = $schemeId
                """.trimIndent(),
            )
        }
    }

    private fun insertWorkflow(
        conn: Connection,
        key: String,
        name: String,
    ): UUID =
        conn.prepareStatement(
            "INSERT INTO workflows (key, name) VALUES (?, ?)" +
                " ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL" +
                " DO UPDATE SET name = EXCLUDED.name RETURNING id",
        ).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, name)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
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
    ): UUID = insertWorkflowStatus(conn, wfId, key, name, category, displayOrder)

    private fun insertTransition(
        conn: Connection,
        wfId: UUID,
        fromKey: String,
        toKey: String,
        transitionName: String,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name)
            SELECT ?, f.id, t.id, ?
            FROM workflow_states f, workflow_states t
            WHERE f.workflow_id = ? AND f.key = ?
              AND t.workflow_id = ? AND t.key = ?
              AND NOT EXISTS (
                  SELECT 1 FROM workflow_transitions x
                  WHERE x.workflow_id = f.workflow_id
                    AND x.from_state_id = f.id
                    AND x.to_state_id = t.id
              )
            """.trimIndent(),
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setString(2, transitionName)
            stmt.setObject(3, wfId)
            stmt.setString(4, fromKey)
            stmt.setObject(5, wfId)
            stmt.setString(6, toKey)
            stmt.executeUpdate()
        }
    }

    private fun conn(): Connection =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
