// CFD(누적 흐름도) HTTP 통합 테스트 — 실 Testcontainers PostgreSQL + 실 컨트롤러/서비스/리포지토리 전체 스택 검증 (FR-RP-03 Task 6)
@file:Suppress("MaxLineLength")

package com.bts.issue.cfd

import com.bts.issue.adapter.outbound.velocity.IsolatedWorkflowStateLookup
import com.bts.issue.cfd.application.CfdService
import com.bts.issue.cfd.repository.CfdStatusHistoryRepository
import com.bts.issue.cfd.web.CfdController
import com.bts.issue.cfd.web.CfdExceptionHandler
import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_GROUP
import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_ITEM
import com.bts.issue.repository.IssueRepository
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * FR-RP-03 Task 6 — GET /api/v1/projects/{projectKey}/cfd 실 스택 통합 테스트.
 *
 * 실제 Testcontainers PostgreSQL + 실 컨트롤러→서비스→리포지토리(jOOQ) 경로로 조회한다.
 * status 전이 이력은 [IssueHistoryRecorder] 등 실 전이 서비스를 거치지 않고
 * `issue_change_group`/`issue_change_item` 에 **직접 jOOQ insert** 하되 `created_at` 을 명시적
 * 과거 [OffsetDateTime] 으로 지정한다(CONCERN-2). 실 전이 서비스를 쓰면 `created_at=NOW()` 로
 * 박혀 30일 과거 CFD 를 시드할 수 없기 때문이다.
 *
 * ## 결정성 — 명시 from/to
 * 모든 요청에 from/to 를 명시적으로 지정해 [CfdController] 의 [java.time.Clock] 기본값(오늘 기준
 * 창 해석)에 의존하지 않는다. Clock 결정성 문제 자체는 [com.bts.issue.cfd.web.CfdControllerTest]
 * 가 이미 검증했다.
 *
 * ## 워크플로우 스킴 — CFDP(할당) / CFDU(미할당)
 * - CFDP: cfd-scheme(default mapping → cfd-workflow: open=TODO, in_progress=IN_PROGRESS, done=DONE)
 *   프로젝트에 배정. S1/S2/S3/S4/S7/S8/S9 가 사용한다.
 * - CFDU: 워크플로우 스킴 **미배정**([WorkflowResolverImpl.resolveExistingFor] 이 `null` 반환 →
 *   [WorkflowStateCatalogImpl] 이 빈 리스트 반환, 예외 없음). S6 전용.
 *
 * ## 검증 시나리오
 * - S1. 이력 있는 프로젝트 — 여러 이슈 + status 전이 이력 시드, 날짜별 밴드 이동 검증.
 * - S2. 빈 프로젝트(이슈 0) — 창 길이만큼 0점.
 * - S3. 기밀 이슈 제외 — unrestricted/restricted 뷰어 대조로 비-vacuous 검증.
 * - S4. BROWSE 권한 없음 → 403.
 * - S5. 미인증 → 401.
 * - S6. 워크플로우 스킴 미할당 → 200, TODO 폴백.
 * - S7. 잘못된 창(파싱 불가/from>to/180일 초과) → 400.
 * - S8. 전이 이력 없음 — currentStateKey 카테고리로 전 기간 집계.
 * - S9. 소프트 삭제 이슈 — 삭제 전후 대조로 비-vacuous 검증.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [CfdIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("TooManyFunctions", "LargeClass")
class CfdIntegrationTest {
    /**
     * 기본 BROWSE 허용 resolver. denyBrowseForProject() 로 특정 프로젝트 BROWSE 거부를 제어한다.
     * ([com.bts.issue.epic.web.IssueEpicProgressControllerIntegrationTest.SwitchablePermissionResolver] 동형)
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
     * securityLevelId 가 NULL 이 아닌 이슈는 전부 제외된다([IssueRepository] 의
     * `buildSecurityCondition` — unrestricted=false 이면 `SECURITY_LEVEL_ID IS NULL` 만 통과).
     * ([com.bts.issue.epic.web.IssueEpicProgressControllerIntegrationTest.SwitchableSecurityDirectory] 동형)
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
    // CfdController 는 수동 @Bean 이 아니라 @Import 로 등록해, Spring 이 생성자의 optional Clock
    // 파라미터(기본값 Clock.systemUTC())를 실제로 resolve 하는 프로덕션 배선 경로를 테스트가 태우게 한다
    // (코드리뷰 CONCERN 2 — Clock 기본 파라미터 Spring 배선 미검증 갭 해소).
    @Import(CfdController::class)
    open class TestConfig : WebMvcConfigurer {
        /**
         * `@EnableWebMvc` 기본 Jackson 컨버터는 [java.time.LocalDate] 를 배열로 직렬화한다.
         * 기존 컨버터를 교체하지 않고(errorCode 등 다른 직렬화 보존) 매퍼 설정만 보강해
         * ISO-8601 문자열로 나오게 한다(`enablewebmvc-slice-localdate-array-serialization` 교훈).
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
                    .withDatabaseName("bts_cfd_http_test")
                    .withUsername("bts")
                    .withPassword("bts_cfd_http_test")
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
        open fun cfdStatusHistoryRepository(dsl: DSLContext): CfdStatusHistoryRepository = CfdStatusHistoryRepository(dsl)

        @Bean
        open fun permissionResolver(): IssuePermissionResolver = TestConfig.permissionResolver

        @Bean
        open fun securityDirectory(): IssueSecurityDirectory = TestConfig.securityDirectory

        // ── project-workflow 빈 조립 (IssueEpicProgressControllerIntegrationTest.TestConfig 선례) ──

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

        // ── CFD 빈 ───────────────────────────────────────────────────────────

        @Bean
        @Suppress("LongParameterList")
        open fun cfdService(
            permissionResolver: IssuePermissionResolver,
            issueRepository: IssueRepository,
            cfdStatusHistoryRepository: CfdStatusHistoryRepository,
            securityDirectory: IssueSecurityDirectory,
            issueTypeRepository: IssueTypeRepository,
            workflowStateLookup: IsolatedWorkflowStateLookup,
        ): CfdService =
            CfdService(
                permissionResolver = permissionResolver,
                issueRepository = issueRepository,
                cfdStatusHistoryRepository = cfdStatusHistoryRepository,
                securityDirectory = securityDirectory,
                issueTypeRepository = issueTypeRepository,
                workflowStateLookup = workflowStateLookup,
            )

        // cfdController 는 @Import(CfdController::class) 로 등록 — Spring 이 optional Clock 기본값을 resolve.

        @Bean
        open fun cfdExceptionHandler(): CfdExceptionHandler = CfdExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dsl: DSLContext

    private lateinit var mockMvc: MockMvc

    companion object {
        private const val PROJECT_KEY = "CFDP"
        private const val UNASSIGNED_PROJECT_KEY = "CFDU"
        private var migrated = false
        private var seeded = false

        private var testProjectId: UUID = UUID.randomUUID()
        private var unassignedProjectId: UUID = UUID.randomUUID()

        /** V003 seed 의 task 타입 id — 모든 시드 이슈가 공유한다. */
        private var taskTypeId: Long = -1L

        private const val OPEN_KEY = "open"
        private const val IN_PROGRESS_KEY = "in_progress"
        private const val DONE_KEY = "done"

        /** S3 — viewer 가 접근 불가한 보안 등급 UUID. */
        val restrictedSecurityLevelId: UUID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

        val actorUuid: UUID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")

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
                stmt.execute("DELETE FROM issues WHERE project_id = '$testProjectId' OR project_id = '$unassignedProjectId'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE id IN ('$testProjectId', '$unassignedProjectId')")
            }
        }
    }

    // ── S1. 이력 있는 프로젝트 — 밴드 이동 검증 ──────────────────────────────

    /**
     * S1. 이슈 3건(A/B/C) + status 전이 이력으로 날짜별 밴드 이동을 검증한다.
     *
     * Given  A(06-01 생성, 06-03 open→in_progress 전이)
     *          B(06-01 생성, 06-02 open→in_progress, 06-04 in_progress→done 전이)
     *          C(06-03 생성, 전이 없음, open)
     * When   GET /api/v1/projects/CFDP/cfd?from=2026-06-01&to=2026-06-05
     * Then   200, 날짜별 todo/inProgress/done 이 정확히 밴드 이동(폭 변화)을 반영한다.
     *          06-01: todo=2,       06-02: todo=1,inProgress=1,
     *          06-03: todo=1,inProgress=2,  06-04/05: todo=1,inProgress=1,done=1
     */
    @Test
    fun `S1 이력 있는 프로젝트 - 날짜별 밴드 이동 검증`() {
        val createdAB = OffsetDateTime.of(2026, 6, 1, 1, 0, 0, 0, ZoneOffset.UTC)
        val (keyA, idA) = createIssue(testProjectId, PROJECT_KEY, "이슈 A", IN_PROGRESS_KEY, taskTypeId, createdAB)
        seedStatusChange(idA, keyA, OffsetDateTime.of(2026, 6, 3, 12, 0, 0, 0, ZoneOffset.UTC), OPEN_KEY, IN_PROGRESS_KEY)

        val (keyB, idB) = createIssue(testProjectId, PROJECT_KEY, "이슈 B", DONE_KEY, taskTypeId, createdAB)
        seedStatusChange(idB, keyB, OffsetDateTime.of(2026, 6, 2, 12, 0, 0, 0, ZoneOffset.UTC), OPEN_KEY, IN_PROGRESS_KEY)
        seedStatusChange(idB, keyB, OffsetDateTime.of(2026, 6, 4, 12, 0, 0, 0, ZoneOffset.UTC), IN_PROGRESS_KEY, DONE_KEY)

        val createdC = OffsetDateTime.of(2026, 6, 3, 1, 0, 0, 0, ZoneOffset.UTC)
        createIssue(testProjectId, PROJECT_KEY, "이슈 C", OPEN_KEY, taskTypeId, createdC)

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cfd")
                .param("from", "2026-06-01")
                .param("to", "2026-06-05"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.points.length()").value(5))
            .andExpect(jsonPath("$.data.points[0].date").value("2026-06-01"))
            .andExpect(jsonPath("$.data.points[0].todoCount").value(2))
            .andExpect(jsonPath("$.data.points[0].inProgressCount").value(0))
            .andExpect(jsonPath("$.data.points[0].doneCount").value(0))
            .andExpect(jsonPath("$.data.points[1].todoCount").value(1))
            .andExpect(jsonPath("$.data.points[1].inProgressCount").value(1))
            .andExpect(jsonPath("$.data.points[1].doneCount").value(0))
            .andExpect(jsonPath("$.data.points[2].todoCount").value(1))
            .andExpect(jsonPath("$.data.points[2].inProgressCount").value(2))
            .andExpect(jsonPath("$.data.points[2].doneCount").value(0))
            .andExpect(jsonPath("$.data.points[3].todoCount").value(1))
            .andExpect(jsonPath("$.data.points[3].inProgressCount").value(1))
            .andExpect(jsonPath("$.data.points[3].doneCount").value(1))
            .andExpect(jsonPath("$.data.points[4].todoCount").value(1))
            .andExpect(jsonPath("$.data.points[4].inProgressCount").value(1))
            .andExpect(jsonPath("$.data.points[4].doneCount").value(1))
    }

    // ── S2. 빈 프로젝트 — 창 길이만큼 0점 ──────────────────────────────────

    /**
     * S2. 이슈가 없는 프로젝트는 창 길이만큼 0점을 반환한다.
     *
     * Given  CFDP 프로젝트, 이슈 0건(이전 테스트 데이터는 @BeforeEach 에서 정리됨)
     * When   GET /api/v1/projects/CFDP/cfd?from=2026-09-01&to=2026-09-03
     * Then   200, points 3개, 모두 todo/inProgress/done = 0
     */
    @Test
    fun `S2 빈 프로젝트 - 창 길이만큼 0점 반환`() {
        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cfd")
                .param("from", "2026-09-01")
                .param("to", "2026-09-03"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.points.length()").value(3))
            .andExpect(jsonPath("$.data.points[0].todoCount").value(0))
            .andExpect(jsonPath("$.data.points[0].inProgressCount").value(0))
            .andExpect(jsonPath("$.data.points[0].doneCount").value(0))
            .andExpect(jsonPath("$.data.points[1].todoCount").value(0))
            .andExpect(jsonPath("$.data.points[2].doneCount").value(0))
    }

    // ── S3. 기밀 이슈 제외 — 비-vacuous 대조 ──────────────────────────────

    /**
     * S3. 기밀(보안 등급) 이슈는 카운트에서 제외된다 — 포함 시 카운트가 달라짐을 대조한다.
     *
     * Given  공개 이슈 1건 + 기밀 이슈 1건(둘 다 06-10 생성, open)
     * When   ① unrestricted 뷰어로 조회 → todo=2 (둘 다 보임)
     *        ② restrict(restrictedSecurityLevelId) 뷰어로 재조회 → todo=1 (기밀 이슈 제외)
     * Then   같은 시드 데이터에서 뷰어 권한만 바뀌어도 카운트가 2→1 로 달라짐 — 비-vacuous 증명.
     */
    @Test
    fun `S3 기밀 이슈 제외 - 포함시 카운트 달라짐을 대조`() {
        val created = OffsetDateTime.of(2026, 6, 10, 1, 0, 0, 0, ZoneOffset.UTC)
        createIssue(testProjectId, PROJECT_KEY, "공개 이슈", OPEN_KEY, taskTypeId, created)
        createIssue(
            testProjectId,
            PROJECT_KEY,
            "기밀 이슈",
            OPEN_KEY,
            taskTypeId,
            created,
            securityLevelId = restrictedSecurityLevelId,
        )

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cfd")
                .param("from", "2026-06-10")
                .param("to", "2026-06-10"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.points[0].todoCount").value(2))

        TestConfig.securityDirectory.restrict(restrictedSecurityLevelId)
        try {
            mockMvc.perform(
                get("/api/v1/projects/$PROJECT_KEY/cfd")
                    .param("from", "2026-06-10")
                    .param("to", "2026-06-10"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.points[0].todoCount").value(1))
        } finally {
            TestConfig.securityDirectory.reset()
        }
    }

    // ── S4. BROWSE 권한 없음 → 403 ────────────────────────────────────────

    /**
     * S4. 프로젝트 BROWSE 권한이 없으면 403 을 반환한다.
     *
     * Given  actor 에게 CFDP 프로젝트 BROWSE 권한 없음
     * When   GET /api/v1/projects/CFDP/cfd?from=2026-06-01&to=2026-06-01
     * Then   403, errorCode=ISSUE_ACCESS_DENIED
     */
    @Test
    fun `S4 BROWSE 권한 없음 - 403 ISSUE_ACCESS_DENIED`() {
        TestConfig.permissionResolver.denyBrowseForProject(PROJECT_KEY)

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cfd")
                .param("from", "2026-06-01")
                .param("to", "2026-06-01"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_ACCESS_DENIED"))
    }

    // ── S5. 미인증 → 401 ──────────────────────────────────────────────────

    /**
     * S5. SecurityContext 미인증 상태로 요청하면 401 을 반환한다.
     *
     * Given  SecurityContext 클리어(인증 없음)
     * When   GET /api/v1/projects/CFDP/cfd?from=2026-06-01&to=2026-06-01
     * Then   401 Unauthorized
     */
    @Test
    fun `S5 미인증 - 401 Unauthorized`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cfd")
                .param("from", "2026-06-01")
                .param("to", "2026-06-01"),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── S6. 워크플로우 스킴 미할당 → 200 TODO 폴백 ────────────────────────

    /**
     * S6. 워크플로우 스킴이 배정되지 않은 프로젝트는 500 이 아니라 200 + TODO 폴백을 반환한다.
     *
     * CFDU 프로젝트는 `project_workflow_scheme_assignments` 행이 없으므로
     * [WorkflowResolverImpl.resolveExistingFor] 가 `null` 을 반환하고,
     * [WorkflowStateCatalogImpl] 이 빈 상태 목록을 반환한다(예외 없음). 이슈의
     * currentStateKey="done" 이지만 카테고리 맵이 비어있어 TODO 로 폴백된다.
     *
     * Given  CFDU 프로젝트(스킴 미배정) 이슈 1건, currentStateKey="done"
     * When   GET /api/v1/projects/CFDU/cfd?from=2026-06-01&to=2026-06-01
     * Then   200, todo=1, done=0 (currentStateKey="done" 임에도 TODO 폴백)
     */
    @Test
    fun `S6 워크플로우 스킴 미할당 - 200 TODO 폴백`() {
        val created = OffsetDateTime.of(2026, 6, 1, 1, 0, 0, 0, ZoneOffset.UTC)
        createIssue(unassignedProjectId, UNASSIGNED_PROJECT_KEY, "스킴 미할당 이슈", DONE_KEY, taskTypeId, created)

        mockMvc.perform(
            get("/api/v1/projects/$UNASSIGNED_PROJECT_KEY/cfd")
                .param("from", "2026-06-01")
                .param("to", "2026-06-01"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.points[0].todoCount").value(1))
            .andExpect(jsonPath("$.data.points[0].doneCount").value(0))
    }

    // ── S7. 잘못된 창 → 400 ───────────────────────────────────────────────

    /**
     * S7a. from 파라미터 파싱 불가 → 400 Bad Request.
     */
    @Test
    fun `S7a 파싱 불가 from - 400 Bad Request`() {
        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cfd").param("from", "not-a-date"),
        ).andExpect(status().isBadRequest)
    }

    /**
     * S7b. from 이 to 보다 늦음 → 400 Bad Request.
     */
    @Test
    fun `S7b from이 to보다 늦음 - 400 Bad Request`() {
        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cfd")
                .param("from", "2026-07-10")
                .param("to", "2026-07-01"),
        ).andExpect(status().isBadRequest)
    }

    /**
     * S7c. 창 길이 180일 초과 → 400 Bad Request.
     */
    @Test
    fun `S7c 창 길이 180일 초과 - 400 Bad Request`() {
        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cfd")
                .param("from", "2026-01-01")
                .param("to", "2026-07-01"),
        ).andExpect(status().isBadRequest)
    }

    // ── S8. 전이 이력 없음 — currentStateKey 로 전 기간 집계 ────────────────

    /**
     * S8. 전이 이력이 없는 이슈는 존재 기간 내내 currentStateKey 카테고리로 집계된다.
     *
     * Given  06-01 생성, currentStateKey="done", 전이 이력 없음
     * When   GET /api/v1/projects/CFDP/cfd?from=2026-06-01&to=2026-06-03
     * Then   200, 3일 모두 doneCount=1, todoCount=0 (전이 이력이 없어도 창 전체에서 일관)
     */
    @Test
    fun `S8 전이 이력 없음 - currentStateKey 카테고리로 전 기간 집계`() {
        val created = OffsetDateTime.of(2026, 6, 1, 1, 0, 0, 0, ZoneOffset.UTC)
        createIssue(testProjectId, PROJECT_KEY, "이력 없는 이슈", DONE_KEY, taskTypeId, created)

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cfd")
                .param("from", "2026-06-01")
                .param("to", "2026-06-03"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.points[0].doneCount").value(1))
            .andExpect(jsonPath("$.data.points[0].todoCount").value(0))
            .andExpect(jsonPath("$.data.points[1].doneCount").value(1))
            .andExpect(jsonPath("$.data.points[2].doneCount").value(1))
            .andExpect(jsonPath("$.data.points[2].todoCount").value(0))
    }

    // ── S9. 소프트 삭제 이슈 — 삭제 전후 대조 ───────────────────────────────

    /**
     * S9. 소프트 삭제된 이슈는 카운트에서 제외된다 — 삭제 전후 대조로 비-vacuous 검증한다.
     *
     * Given  활성 이슈 1건 + 삭제 예정 이슈 1건(둘 다 06-01 생성, open)
     * When   ① 삭제 전 조회 → todo=2
     *        ② 한 이슈를 소프트 삭제(deleted_at 설정) 후 재조회 → todo=1
     * Then   같은 시드 데이터에서 소프트 삭제 여부만 바뀌어도 카운트가 2→1 로 달라짐.
     */
    @Test
    fun `S9 소프트 삭제 이슈 - 삭제 전후 대조로 카운트 미포함 검증`() {
        val created = OffsetDateTime.of(2026, 6, 1, 1, 0, 0, 0, ZoneOffset.UTC)
        createIssue(testProjectId, PROJECT_KEY, "활성 이슈", OPEN_KEY, taskTypeId, created)
        val (deletedKey, _) = createIssue(testProjectId, PROJECT_KEY, "삭제될 이슈", OPEN_KEY, taskTypeId, created)

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cfd")
                .param("from", "2026-06-01")
                .param("to", "2026-06-01"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.points[0].todoCount").value(2))

        softDeleteIssue(deletedKey)

        mockMvc.perform(
            get("/api/v1/projects/$PROJECT_KEY/cfd")
                .param("from", "2026-06-01")
                .param("to", "2026-06-01"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.points[0].todoCount").value(1))
    }

    // ── private helpers — 시드 ────────────────────────────────────────────

    /**
     * 이슈를 DB 에 직접 삽입하고 (이슈 키, 이슈 UUID) 쌍을 반환한다.
     *
     * `Issue.create()` 는 `createdAt=Instant.now()` 를 강제해 CFD 시나리오가 필요로 하는 과거
     * 생성일을 시드할 수 없으므로, raw SQL 로 `created_at` 을 명시적으로 지정한다.
     *
     * @param projectId 소속 프로젝트 UUID.
     * @param projectKeyPrefix 이슈 키 prefix(예: "CFDP").
     * @param summary 이슈 제목.
     * @param stateKey 현재 워크플로우 상태 키(전이 이력이 있으면 초기 상태 계산에는 쓰이지 않는다).
     * @param typeId 이슈 타입 BIGSERIAL id.
     * @param createdAt 이슈 생성 시각(명시 과거 시각 — CFD 밴드 이동 시드 필수).
     * @param securityLevelId 보안 등급 UUID. null 이면 공개(등급 없음).
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
     * status 전이 이력 1건(`issue_change_group` 1행 + `issue_change_item` 1행, field="status")
     * 을 명시적 과거 시각으로 직접 시드한다(CONCERN-2 — 실 전이 서비스는 `created_at=NOW()` 강제).
     *
     * @param issueId 소속 이슈 UUID.
     * @param issueKey 기록 시점 이슈 키.
     * @param changedAt 전이 발생 시각(명시 과거 시각).
     * @param fromValue 전이 전 상태 키.
     * @param toValue 전이 후 상태 키.
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

    /** 이슈를 소프트 삭제한다 (deleted_at 설정). */
    private fun softDeleteIssue(key: String) {
        conn().use { c ->
            c.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeUpdate()
            }
        }
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

    /**
     * 전체 시드: 프로젝트(CFDP/CFDU) + task 타입 id 조회 + CFDP 전용 워크플로우 스킴.
     *
     * CFDU 는 의도적으로 워크플로우 스킴을 배정하지 않는다(S6 전용).
     */
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
        conn.prepareStatement(
            "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
        ).use { stmt ->
            stmt.setString(1, PROJECT_KEY)
            stmt.setString(2, "CFD Test Project")
            stmt.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
        ).use { stmt ->
            stmt.setString(1, UNASSIGNED_PROJECT_KEY)
            stmt.setString(2, "CFD Unassigned Scheme Project")
            stmt.executeUpdate()
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

    /** V003 seed 의 task 타입 id 를 조회한다(모든 테스트 이슈가 공유). */
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
     * cfd-workflow(open=TODO, in_progress=IN_PROGRESS, done=DONE) + cfd-scheme(default mapping)
     * 를 생성하고 CFDP 프로젝트에만 배정한다. CFDU 는 의도적으로 배정하지 않는다(S6).
     */
    @Suppress("NestedBlockDepth")
    private fun seedWorkflowAndScheme(conn: Connection) {
        val wfId = insertWorkflow(conn, "cfd-workflow", "CFD 테스트 워크플로우")
        insertState(conn, wfId, OPEN_KEY, "Open", "TODO", 0)
        insertState(conn, wfId, IN_PROGRESS_KEY, "In Progress", "IN_PROGRESS", 1)
        insertState(conn, wfId, DONE_KEY, "Done", "DONE", 2)
        insertTransition(conn, wfId, OPEN_KEY, IN_PROGRESS_KEY, "Start")
        insertTransition(conn, wfId, IN_PROGRESS_KEY, DONE_KEY, "Finish")

        val schemeId: Long =
            conn.prepareStatement(
                """
                INSERT INTO workflow_schemes (key, name, is_default)
                VALUES ('cfd-scheme', 'CFD Test Scheme', false)
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
        // CFDU 는 의도적으로 project_workflow_scheme_assignments 행을 남기지 않는다(S6).
    }

    private fun insertWorkflow(
        conn: Connection,
        key: String,
        name: String,
    ): UUID =
        conn.prepareStatement(
            "INSERT INTO workflows (key, name) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
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
            ON CONFLICT (workflow_id, from_state_id, to_state_id) DO NOTHING
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
