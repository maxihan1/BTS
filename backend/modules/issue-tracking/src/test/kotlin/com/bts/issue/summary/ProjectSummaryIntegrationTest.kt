// 프로젝트 요약·활동 HTTP 통합 테스트 — 실 Testcontainers PostgreSQL + 실 컨트롤러/서비스/리포지토리 전체 스택
@file:Suppress("MaxLineLength")

package com.bts.issue.summary

import com.bts.issue.adapter.outbound.velocity.IsolatedWorkflowStateLookup
import com.bts.issue.application.IssueChangeItemMasker
import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_GROUP
import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_ITEM
import com.bts.issue.repository.IssueRepository
import com.bts.issue.summary.application.ProjectSummaryService
import com.bts.issue.summary.web.ProjectSummaryController
import com.bts.issue.summary.web.ProjectSummaryExceptionHandler
import com.bts.issue.testsupport.insertWorkflowStatus
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.user.UserLookupPort
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `GET /api/v1/projects/{projectKey}/{summary,activity}` 실 스택 통합 테스트 (Jira 패리티 캠페인 PR ③).
 *
 * 실제 Testcontainers PostgreSQL + 실 컨트롤러→서비스→리포지토리(jOOQ) + 실 워크플로우 스킴 해석
 * 경로로 조회한다([com.bts.issue.cfd.CfdIntegrationTest] 스캐폴드 동형).
 *
 * ## 시각 기준 — 상대 시각으로 시드한다
 * [ProjectSummaryService] 는 `Clock.systemUTC()` 를 기본값으로 받으므로 창이 **실행 시각** 기준이다.
 * 고정 날짜로 시드하면 하루만 지나도 테스트가 썩는다. 그래서 시드를 「지금으로부터 N일 전」으로
 * 잡고, 창 경계에서 최소 하루 이상 떨어뜨려 자정 롤오버에도 흔들리지 않게 한다.
 *
 * `ProjectSummaryService`·`ProjectSummaryController` 를 수동 `@Bean` 이 아니라 `@Import` 로 등록해
 * Spring 이 생성자의 optional [java.time.Clock] 기본값을 실제로 resolve 하는 프로덕션 배선을 태운다.
 *
 * ## 워크플로우 스킴 — SUMP(할당) / SUMU(미할당)
 * - SUMP: summary-scheme(default mapping → open=TODO, in_progress=IN_PROGRESS, done=DONE) 배정.
 * - SUMU: 스킴 **미배정** — I6 전용. 500 이 아니라 200 + TODO 폴백이어야 한다.
 *
 * ## 검증 시나리오
 * - I1. 카드 3종 + 분포 4종이 실 DB·실 스킴을 거쳐 계약대로 나온다.
 * - I2. DONE 2주 특례가 실 스택에서도 걸린다 — 오래된 완료 이슈는 상태 개요에서만 빠진다.
 * - I3. 기밀 이슈 제외 — 뷰어 권한만 바꿔 숫자가 달라짐을 대조한다(비-vacuous).
 * - I4. BROWSE 권한 없음 → 403 · 미인증 → 401.
 * - I5. 활동 피드 최신순 · 담당자 표시명 결선 · limit 상한 초과 → 400.
 * - I6. 워크플로우 스킴 미배정 → 200 + TODO 폴백(500 차단).
 * - I7. 활동 피드의 이슈 단위 VIEW 게이트 — BROWSE 만으로는 이슈 내용이 나가지 않는다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ProjectSummaryIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("TooManyFunctions", "LargeClass")
class ProjectSummaryIntegrationTest {
    /**
     * 기본 전부 허용. [denyBrowseForProject]·[denyViewForIssue] 로 하나씩 닫는다.
     *
     * BROWSE 와 VIEW 를 **따로** 닫을 수 있어야 한다 — 둘은 독립 매트릭스 권한이고(FR-PM-05),
     * 한 스위치로 묶으면 활동 피드의 이슈 단위 게이트를 지워도 테스트가 통과한다.
     */
    class SwitchablePermissionResolver : IssuePermissionResolver {
        private val denyBrowseProject = ThreadLocal<String?>()
        private val denyViewIssue = ThreadLocal<String?>()

        fun denyBrowseForProject(projectKey: String) = denyBrowseProject.set(projectKey)

        fun denyViewForIssue(issueKey: String) = denyViewIssue.set(issueKey)

        fun resetPermissions() {
            denyBrowseProject.remove()
            denyViewIssue.remove()
        }

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            if (permission == IssuePermission.BROWSE && scope is IssueScope.Project) {
                if (scope.key == denyBrowseProject.get()) return false
            }
            if (permission == IssuePermission.VIEW && scope is IssueScope.Issue) {
                if (scope.key == denyViewIssue.get()) return false
            }
            return true
        }
    }

    /**
     * 보안 등급 접근을 토글 가능한 스텁.
     *
     * [restrict] 호출 후에는 `unrestricted=false` + 빈 집합이라 `security_level_id` 가 NULL 이 아닌
     * 이슈가 전부 제외된다([IssueRepository] `buildSecurityCondition`).
     */
    internal class SwitchableSecurityDirectory : IssueSecurityDirectory {
        private val restricted = ThreadLocal<Boolean?>()

        fun restrict() = restricted.set(true)

        fun reset() = restricted.remove()

        override fun levelBelongsToProjectScheme(
            levelId: UUID,
            projectKey: String,
        ): Boolean = true

        override fun accessibleLevels(
            actorId: UUID,
            projectKey: String,
        ): IssueSecurityAccess =
            IssueSecurityAccess(
                unrestricted = restricted.get() != true,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )
    }

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    // 수동 @Bean 이 아니라 @Import — Spring 이 ProjectSummaryService 의 optional Clock 기본값을
    // 실제로 resolve 하는 프로덕션 배선 경로를 테스트가 태우게 한다.
    @Import(ProjectSummaryService::class, ProjectSummaryController::class, IssueChangeItemMasker::class)
    open class TestConfig : WebMvcConfigurer {
        /**
         * `@EnableWebMvc` 기본 Jackson 컨버터는 [java.time.Instant] 를 숫자로 직렬화한다.
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
                    .withDatabaseName("bts_summary_http_test")
                    .withUsername("bts")
                    .withPassword("bts_summary_http_test")
                    .apply { start() }

            val permissionResolver = SwitchablePermissionResolver()
            internal val securityDirectory = SwitchableSecurityDirectory()

            /** I5 에서 표시명 결선을 확인할 담당자. */
            val assigneeUuid: UUID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
            const val ASSIGNEE_NAME = "홍길동"
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

        /**
         * 마스킹 협력자가 삭제 댓글을 판정할 때 쓴다.
         *
         * `IssueChangeItemMasker` 의 `fieldPermissionResolver` 는 Kotlin 기본값(allow-all)으로
         * 남겨 둔다 — 이 테스트의 관심사는 배선과 이슈 단위 VIEW 게이트다.
         */
        @Bean
        open fun commentRepository(dsl: DSLContext): CommentRepository = CommentRepository(dsl)

        @Bean
        open fun permissionResolver(): IssuePermissionResolver = TestConfig.permissionResolver

        @Bean
        open fun securityDirectory(): IssueSecurityDirectory = TestConfig.securityDirectory

        /** identity-access 대역 — 표시명 결선이 실제로 응답까지 도달하는지 본다. */
        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                override fun exists(userId: UUID): Boolean = true

                override fun findDisplayNamesByIds(ids: Set<UUID>): Map<UUID, String> =
                    ids.filter { it == assigneeUuid }.associateWith { ASSIGNEE_NAME }
            }

        // ── project-workflow 빈 조립 (CfdIntegrationTest.TestConfig 선례) ──────

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

        @Bean
        open fun projectSummaryExceptionHandler(): ProjectSummaryExceptionHandler = ProjectSummaryExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dsl: DSLContext

    private lateinit var mockMvc: MockMvc

    companion object {
        private const val PROJECT_KEY = "SUMP"
        private const val UNASSIGNED_PROJECT_KEY = "SUMU"
        private var migrated = false
        private var seeded = false

        private var testProjectId: UUID = UUID.randomUUID()
        private var unassignedProjectId: UUID = UUID.randomUUID()
        private var taskTypeId: Long = -1L

        private const val OPEN_KEY = "open"
        private const val IN_PROGRESS_KEY = "in_progress"
        private const val DONE_KEY = "done"

        val restrictedSecurityLevelId: UUID = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
        val actorUuid: UUID = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff")

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
                // 변경 이력은 issues 로의 FK 가 없어 함께 지워지지 않는다 — 직접 지운다.
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
                stmt.execute("DELETE FROM issues WHERE project_id = '$testProjectId' OR project_id = '$unassignedProjectId'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE id IN ('$testProjectId', '$unassignedProjectId')")
            }
        }
    }

    // ── I1. 카드 3종 + 분포 4종 ──────────────────────────────────────────────

    /**
     * I1. 실 DB·실 워크플로우 스킴을 거쳐 카드와 분포가 계약대로 나온다.
     *
     * Given  최근 7일 생성 2건 · 직전 7일 생성 1건 · 최근 7일 완료 1건 · 향후 마감 1건 · 지연 1건
     * When   GET /api/v1/projects/SUMP/summary
     * Then   200, 카드 값과 분포 합계가 시드와 일치한다.
     */
    @Test
    fun `I1 요약 - 카드 3종과 분포 4종이 실 스택을 거쳐 계약대로 나온다`() {
        // 최근 7일 생성 2건 (2일 전 · 3일 전 — 창 경계에서 충분히 떨어뜨린다)
        createIssue(daysAgo(2), stateKey = OPEN_KEY, priority = 1, dueDate = LocalDate.now(ZoneOffset.UTC).plusDays(2))
        val (recentDoneKey, recentDoneId) = createIssue(daysAgo(3), stateKey = DONE_KEY, priority = 2)
        // 직전 7일 생성 1건 (10일 전 — [-13, -7) 안)
        createIssue(daysAgo(10), stateKey = IN_PROGRESS_KEY, priority = 3, dueDate = LocalDate.now(ZoneOffset.UTC).minusDays(3))

        // 최근 7일 안에 DONE 진입
        seedStatusChange(recentDoneId, recentDoneKey, daysAgo(1), IN_PROGRESS_KEY, DONE_KEY)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.projectKey").value(PROJECT_KEY))
            .andExpect(jsonPath("$.data.recent.windowDays").value(7))
            .andExpect(jsonPath("$.data.recent.created.current").value(2))
            .andExpect(jsonPath("$.data.recent.created.previous").value(1))
            .andExpect(jsonPath("$.data.recent.completed.current").value(1))
            .andExpect(jsonPath("$.data.recent.completed.previous").value(0))
            // 마감 예정 1건(2일 뒤) · 지연 1건(3일 전, 미완료)
            .andExpect(jsonPath("$.data.upcoming.due").value(1))
            .andExpect(jsonPath("$.data.upcoming.overdue").value(1))
            // 분포는 기간 제한 없음 — 이슈 3건이 각 축에서 전부 세어진다.
            .andExpect(jsonPath("$.data.priorityBreakdown.length()").value(3))
            .andExpect(jsonPath("$.data.typesOfWork[0].count").value(3))
            .andExpect(jsonPath("$.data.typesOfWork[0].typeKey").value("task"))
            .andExpect(jsonPath("$.data.teamWorkload[0].count").value(3))
    }

    // ── I2. DONE 2주 특례 ────────────────────────────────────────────────────

    /**
     * I2. 2주 안에 완료 이력이 없는 DONE 이슈는 **상태 개요에서만** 빠진다.
     *
     * Given  DONE 상태 2건 — 하나는 3일 전 완료, 하나는 20일 전 완료(2주 밖)
     * When   GET /api/v1/projects/SUMP/summary
     * Then   상태 개요의 done 카운트는 1, 유형 분포 카운트는 2 — 특례가 Done 버킷에만 걸린다.
     */
    @Test
    fun `I2 요약 - DONE 2주 특례는 상태 개요에만 걸린다`() {
        val (freshKey, freshId) = createIssue(daysAgo(5), stateKey = DONE_KEY)
        val (staleKey, staleId) = createIssue(daysAgo(30), stateKey = DONE_KEY)
        seedStatusChange(freshId, freshKey, daysAgo(3), IN_PROGRESS_KEY, DONE_KEY)
        seedStatusChange(staleId, staleKey, daysAgo(20), IN_PROGRESS_KEY, DONE_KEY)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.statusOverview.length()").value(1))
            .andExpect(jsonPath("$.data.statusOverview[0].statusKey").value(DONE_KEY))
            .andExpect(jsonPath("$.data.statusOverview[0].category").value("DONE"))
            .andExpect(jsonPath("$.data.statusOverview[0].count").value(1))
            // 나머지 분포는 이슈를 잃지 않는다.
            .andExpect(jsonPath("$.data.typesOfWork[0].count").value(2))
            .andExpect(jsonPath("$.data.teamWorkload[0].count").value(2))
    }

    // ── I3. 기밀 이슈 제외 (비-vacuous) ──────────────────────────────────────

    /**
     * I3. 기밀 이슈는 집계에서 빠진다 — 뷰어 권한만 바꿔 숫자가 달라짐을 대조한다.
     *
     * Given  공개 이슈 1건 + 기밀 이슈 1건(둘 다 2일 전 생성)
     * When   ① unrestricted 로 조회 → created.current = 2
     *        ② restrict() 후 재조회 → created.current = 1
     * Then   같은 시드에서 권한만 바뀌어 2→1 — 술어가 실제로 동작함을 증명한다.
     */
    @Test
    fun `I3 요약 - 기밀 이슈 제외를 권한 대조로 증명한다`() {
        createIssue(daysAgo(2), stateKey = OPEN_KEY)
        createIssue(daysAgo(2), stateKey = OPEN_KEY, securityLevelId = restrictedSecurityLevelId)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.recent.created.current").value(2))

        TestConfig.securityDirectory.restrict()

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.recent.created.current").value(1))
    }

    // ── I4. 권한·인증 ────────────────────────────────────────────────────────

    @Test
    fun `I4 요약 - BROWSE 권한이 없으면 403`() {
        TestConfig.permissionResolver.denyBrowseForProject(PROJECT_KEY)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/summary"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `I4 활동 - BROWSE 권한이 없으면 403`() {
        TestConfig.permissionResolver.denyBrowseForProject(PROJECT_KEY)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/activity"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `I4 요약 - 미인증이면 401`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/summary"))
            .andExpect(status().isUnauthorized)
    }

    // ── I5. 활동 피드 ────────────────────────────────────────────────────────

    /**
     * I5. 활동 피드가 최신순으로 나오고 담당자 표시명이 결선된다.
     *
     * Given  전환 3건(1일 전 · 2일 전 · 3일 전), 전부 [TestConfig.assigneeUuid] 가 수행
     * When   GET /api/v1/projects/SUMP/activity?limit=2
     * Then   200, 최신 2건만, 이름이 채워져 나온다.
     */
    @Test
    fun `I5 활동 - 최신순 limit 과 담당자 표시명 결선`() {
        val (key, id) = createIssue(daysAgo(5), stateKey = DONE_KEY)
        seedStatusChange(id, key, daysAgo(3), OPEN_KEY, "a", actorId = TestConfig.assigneeUuid)
        seedStatusChange(id, key, daysAgo(2), "a", "b", actorId = TestConfig.assigneeUuid)
        seedStatusChange(id, key, daysAgo(1), "b", DONE_KEY, actorId = TestConfig.assigneeUuid)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/activity").param("limit", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.entries.length()").value(2))
            .andExpect(jsonPath("$.data.entries[0].issueKey").value(key))
            .andExpect(jsonPath("$.data.entries[0].actorName").value(TestConfig.ASSIGNEE_NAME))
            .andExpect(jsonPath("$.data.entries[0].items[0].toValue").value(DONE_KEY))
            .andExpect(jsonPath("$.data.entries[1].items[0].toValue").value("b"))
    }

    @Test
    fun `I5 활동 - limit 상한을 넘으면 400`() {
        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/activity").param("limit", "51"))
            .andExpect(status().isBadRequest)
    }

    /**
     * I7. BROWSE 는 있어도 VIEW 가 없는 이슈의 항목은 피드에서 사라진다.
     *
     * Given  같은 프로젝트의 이슈 2건에 각각 전환 1건
     * When   ① 그대로 조회 → 2건 ② 한 이슈의 VIEW 만 거부하고 재조회 → 1건
     * Then   같은 시드에서 권한만 바뀌어 2→1. BROWSE_PROJECT 로 이슈 내용을 내보내지 않는다.
     */
    @Test
    fun `I7 활동 - VIEW 가 없는 이슈의 항목은 제거된다`() {
        val (keyA, idA) = createIssue(daysAgo(5), stateKey = DONE_KEY)
        val (keyB, idB) = createIssue(daysAgo(5), stateKey = DONE_KEY)
        seedStatusChange(idA, keyA, daysAgo(1), IN_PROGRESS_KEY, DONE_KEY)
        seedStatusChange(idB, keyB, daysAgo(2), IN_PROGRESS_KEY, DONE_KEY)

        // 비-공허 짝 — 게이트를 지워도 통과하는 테스트가 되지 않게 먼저 2건을 확인한다.
        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/activity"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.entries.length()").value(2))

        TestConfig.permissionResolver.denyViewForIssue(keyA)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/activity"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.entries.length()").value(1))
            .andExpect(jsonPath("$.data.entries[0].issueKey").value(keyB))
    }

    @Test
    fun `I5 활동 - 이력이 없으면 200 빈 목록`() {
        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/activity"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.entries.length()").value(0))
    }

    // ── I6. 워크플로우 스킴 미배정 ───────────────────────────────────────────

    /**
     * I6. 스킴이 배정되지 않은 프로젝트도 200 이다.
     *
     * Given  SUMU(스킴 미배정)에 이슈 1건
     * When   GET /api/v1/projects/SUMU/summary
     * Then   200, 상태 카테고리는 TODO 폴백, statusName 은 없음 — 500 이 아니다.
     */
    @Test
    fun `I6 요약 - 워크플로우 스킴 미배정이어도 200 이고 TODO 로 폴백한다`() {
        createIssue(daysAgo(2), stateKey = OPEN_KEY, projectId = unassignedProjectId, projectKeyPrefix = UNASSIGNED_PROJECT_KEY)

        mockMvc.perform(get("/api/v1/projects/$UNASSIGNED_PROJECT_KEY/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.statusOverview[0].statusKey").value(OPEN_KEY))
            .andExpect(jsonPath("$.data.statusOverview[0].category").value("TODO"))
            // 스킴을 해석하지 못하면 표시명이 없다 — 화면이 키로 폴백할 수 있어야 한다.
            .andExpect(jsonPath("$.data.statusOverview[0].statusName").doesNotExist())
    }

    // ── 시드 헬퍼 ────────────────────────────────────────────────────────────

    /** 지금으로부터 [days] 일 전 정오(UTC). 창 경계에서 떨어뜨려 자정 롤오버에 흔들리지 않게 한다. */
    private fun daysAgo(days: Long): OffsetDateTime =
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(days).withHour(12).withMinute(0).withSecond(0).withNano(0)

    @Suppress("LongParameterList", "NestedBlockDepth")
    private fun createIssue(
        createdAt: OffsetDateTime,
        stateKey: String,
        priority: Int = 3,
        dueDate: LocalDate? = null,
        securityLevelId: UUID? = null,
        projectId: UUID = testProjectId,
        projectKeyPrefix: String = PROJECT_KEY,
    ): Pair<String, UUID> {
        var key: String? = null
        var issueId: UUID? = null
        conn().use { c ->
            c.prepareStatement("UPDATE projects SET key_sequence = key_sequence + 1 WHERE id = ?").use { stmt ->
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
                    (project_id, key, summary, reporter_id, current_state_key, type_id,
                     priority, assignee_id, due_date, security_level_id, created_at, updated_at, version)
                VALUES (?, ?, ?, '$REPORTER_ID', ?, ?, ?, ?, ?, ?, ?, ?, 1)
                RETURNING id
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, key)
                stmt.setString(3, "요약 통합 테스트 이슈 $key")
                stmt.setString(4, stateKey)
                stmt.setLong(5, taskTypeId)
                stmt.setInt(6, priority)
                stmt.setObject(7, TestConfig.assigneeUuid, Types.OTHER)
                stmt.setObject(8, dueDate)
                stmt.setObject(9, securityLevelId, Types.OTHER)
                stmt.setObject(10, createdAt)
                // updated_at 을 created_at 과 같게 둔다 — 「업데이트」 카드가 생성 창을 따라간다.
                stmt.setObject(11, createdAt)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    issueId = rs.getObject(1) as UUID
                }
            }
        }
        return requireNotNull(key) to requireNotNull(issueId)
    }

    /**
     * status 전환 이력 1건을 명시적 과거 시각으로 직접 시드한다.
     *
     * 실 전환 서비스는 `created_at=NOW()` 를 박으므로 과거 시각 창을 시드할 수 없다
     * ([com.bts.issue.cfd.CfdIntegrationTest] 와 같은 이유).
     */
    @Suppress("LongParameterList")
    private fun seedStatusChange(
        issueId: UUID,
        issueKey: String,
        changedAt: OffsetDateTime,
        fromValue: String?,
        toValue: String?,
        actorId: UUID? = null,
    ) {
        val groupId =
            dsl.insertInto(ISSUE_CHANGE_GROUP)
                .set(ISSUE_CHANGE_GROUP.ISSUE_ID, issueId)
                .set(ISSUE_CHANGE_GROUP.ISSUE_KEY, issueKey)
                .set(ISSUE_CHANGE_GROUP.ACTOR_ID, actorId)
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

    private fun seedAll() {
        conn().use { c ->
            c.autoCommit = false
            seedProjects(c)
            resolveTaskTypeId(c)
            seedWorkflowAndScheme(c)
            c.commit()
        }
    }

    @Suppress("NestedBlockDepth")
    private fun seedProjects(conn: Connection) {
        listOf(PROJECT_KEY to "Summary Test Project", UNASSIGNED_PROJECT_KEY to "Summary Unassigned Scheme Project")
            .forEach { (key, name) ->
                conn.prepareStatement("INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING").use { stmt ->
                    stmt.setString(1, key)
                    stmt.setString(2, name)
                    stmt.executeUpdate()
                }
            }
        conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
            stmt.setString(1, PROJECT_KEY)
            stmt.executeQuery().use { rs ->
                rs.next()
                testProjectId = rs.getObject(1) as UUID
            }
        }
        conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
            stmt.setString(1, UNASSIGNED_PROJECT_KEY)
            stmt.executeQuery().use { rs ->
                rs.next()
                unassignedProjectId = rs.getObject(1) as UUID
            }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun resolveTaskTypeId(conn: Connection) {
        conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1").use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                taskTypeId = rs.getLong(1)
            }
        }
    }

    /** SUMP 에만 스킴을 배정한다. SUMU 는 의도적으로 미배정(I6). */
    @Suppress("NestedBlockDepth")
    private fun seedWorkflowAndScheme(conn: Connection) {
        val wfId = insertWorkflow(conn, "summary-workflow", "요약 테스트 워크플로우")
        insertWorkflowStatus(conn, wfId, OPEN_KEY, "Open", "TODO", 0)
        insertWorkflowStatus(conn, wfId, IN_PROGRESS_KEY, "In Progress", "IN_PROGRESS", 1)
        insertWorkflowStatus(conn, wfId, DONE_KEY, "Done", "DONE", 2)
        // I5 가 쓰는 중간 상태 — 카테고리는 중요하지 않다.
        insertWorkflowStatus(conn, wfId, "a", "A", "IN_PROGRESS", 3)
        insertWorkflowStatus(conn, wfId, "b", "B", "IN_PROGRESS", 4)

        val schemeId: Long =
            conn.prepareStatement(
                """
                INSERT INTO workflow_schemes (key, name, is_default)
                VALUES ('summary-scheme', 'Summary Test Scheme', false)
                ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id
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
                " ON CONFLICT (key) WHERE deleted_at IS NULL" +
                " DO UPDATE SET name = EXCLUDED.name RETURNING id",
        ).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, name)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
            }
        }

    private fun conn(): Connection =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
