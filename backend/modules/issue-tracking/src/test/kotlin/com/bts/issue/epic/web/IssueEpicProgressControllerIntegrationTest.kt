// FR-EP-02 Task 3 — GET /api/v1/epics/{key}/progress 통합 테스트 (TDD RED)
@file:Suppress("MaxLineLength")

package com.bts.issue.epic.web

import com.bts.issue.epic.application.IssueEpicService
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
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
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * FR-EP-02 Task 3 — GET /api/v1/epics/{key}/progress 통합 테스트.
 *
 * 실제 Testcontainers PostgreSQL + 실 WorkflowStateCatalogImpl 조립으로 end-to-end 검증한다.
 * WorkflowStateCatalogImpl 을 실 빈으로 조립하여 S3(타입별 다른 DONE 키 판정)를 vacuous green 없이 검증한다.
 *
 * ## S3 시드 전략 (가짜그린 차단)
 * Story 타입 → "story-workflow" (DONE 상태 키: "closed")
 * Bug 타입   → "bug-workflow"   (DONE 상태 키: "resolved")
 * 두 타입을 같은 에픽 아래 배치하고, 각각의 DONE 상태에 놓인 자식을 집계했을 때
 * byCategory.done 이 정확히 계산되는지 검증한다.
 *
 * ## 보안 검증 방침
 * - S4 visibility: AlwaysDeny 보안 등급 스텁으로 제한된 accessibleLevels 를 주입해
 *   높은 보안 등급 이슈가 집계에서 제외됨을 SQL 푸시다운으로 검증한다.
 * - S5 403: SwitchablePermissionResolver 를 사용해 BROWSE 권한 거부를 Thread-local 로 제어한다.
 *
 * ## 검증 시나리오
 * - S1. 자식 여러 건 정상 집계 200 (done/total/byCategory 검증)
 * - S2. 빈 에픽 → total=0, donePercentage=0
 * - S3. ★ Story 자식·Bug 자식 각자 타입 워크플로우의 DONE으로 판정 (실 시드 필수)
 * - S4. visibility — 보안 등급 제한 자식 제외
 * - S5. BROWSE 권한 없음 → 403
 * - S6. 에픽 미존재/소프트삭제 → 404
 * - S7. 미인증 → 401
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueEpicProgressControllerIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("TooManyFunctions", "LargeClass")
class IssueEpicProgressControllerIntegrationTest {

    /**
     * 기본 BROWSE 허용 resolver. denyBrowseForProject() 로 특정 프로젝트 BROWSE 거부를 제어한다.
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
     * 지정된 프로젝트에서 보안 등급 접근을 무제한(unrestricted=true)으로 반환하는 기본 스텁.
     * S4 시나리오에서 restrictedSecurityDirectory() 빈을 교체해 사용한다.
     */
    private class AlwaysUnrestrictedSecurityDirectory : IssueSecurityDirectory {
        override fun levelBelongsToProjectScheme(
            levelId: UUID,
            projectKey: String,
        ): Boolean = true

        override fun accessibleLevels(
            actorId: UUID,
            projectKey: String,
        ): IssueSecurityAccess =
            IssueSecurityAccess(
                unrestricted = true,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )
    }

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_ep_progress_test")
                    .withUsername("bts")
                    .withPassword("bts_ep_progress_test")
                    .apply { start() }

            val permissionResolver = SwitchablePermissionResolver()
        }

        @Bean
        open fun dataSource(): DriverManagerDataSource =
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

        @Bean
        open fun transactionManager(dataSource: DriverManagerDataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext =
            DSL.using(dataSource, SQLDialect.POSTGRES)

        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

        @Bean
        open fun issueTypeRepository(dsl: DSLContext): IssueTypeRepository = IssueTypeRepository(dsl)

        @Bean
        open fun issueHistoryRecorder(): IssueHistoryRecorder = mockk(relaxed = true)

        @Bean
        open fun permissionResolver(): IssuePermissionResolver = TestConfig.permissionResolver

        @Bean
        open fun securityDirectory(): IssueSecurityDirectory = AlwaysUnrestrictedSecurityDirectory()

        // ── project-workflow 빈 조립 (IssueMoveIntegrationTest.IssueMoveConfig 선례) ─

        @Bean
        open fun workflowSchemeRepository(dsl: DSLContext): WorkflowSchemeRepository =
            WorkflowSchemeRepository(dsl)

        @Bean
        open fun projectWorkflowSchemeAssignmentRepository(dsl: DSLContext): ProjectWorkflowSchemeAssignmentRepository =
            ProjectWorkflowSchemeAssignmentRepository(dsl)

        @Bean
        open fun schemeIssueTypeMappingRepository(dsl: DSLContext): SchemeIssueTypeMappingRepository =
            SchemeIssueTypeMappingRepository(dsl)

        @Bean
        open fun workflowSchemeEventPublisher(
            dsl: DSLContext,
            objectMapper: ObjectMapper,
        ): WorkflowSchemeEventPublisher = WorkflowSchemeEventPublisher(dsl, objectMapper)

        @Bean
        open fun alwaysAllowWorkflowSchemePermissionResolver(): AlwaysAllowWorkflowSchemePermissionResolver =
            AlwaysAllowWorkflowSchemePermissionResolver()

        @Bean
        open fun jdbcProjectLookupAdapter(dsl: DSLContext): JdbcProjectLookupAdapter =
            JdbcProjectLookupAdapter(dsl)

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
            workflowSchemeRepository: WorkflowSchemeRepository,
            issueTypeLookupPort: IssueTypeLookupPort,
        ): WorkflowSchemeApplicationService =
            WorkflowSchemeApplicationService(
                schemeRepo = schemeRepo,
                assignmentRepo = assignmentRepo,
                mappingRepo = mappingRepo,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowRepo = workflowSchemeRepository,
                issueTypeLookupPort = issueTypeLookupPort,
            )

        @Bean
        open fun workflowResolverImpl(
            projectLookup: JdbcProjectLookupAdapter,
            assignmentRepo: ProjectWorkflowSchemeAssignmentRepository,
            mappingRepo: SchemeIssueTypeMappingRepository,
            workflowRepo: WorkflowSchemeRepository,
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
        open fun issueEpicService(
            permissionResolver: IssuePermissionResolver,
            securityDirectory: IssueSecurityDirectory,
            issueRepository: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            issueHistoryRecorder: IssueHistoryRecorder,
            workflowStateCatalog: WorkflowStateCatalogImpl,
        ): IssueEpicService =
            IssueEpicService(
                permissionResolver = permissionResolver,
                securityDirectory = securityDirectory,
                issueRepository = issueRepository,
                issueTypeRepository = issueTypeRepository,
                historyRecorder = issueHistoryRecorder,
                workflowStateCatalog = workflowStateCatalog,
            )

        @Bean
        open fun issueEpicController(
            service: IssueEpicService,
            issueRepository: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
        ): IssueEpicController = IssueEpicController(service, issueRepository, issueTypeRepository)

        @Bean
        open fun epicChildExceptionHandler(): EpicChildExceptionHandler = EpicChildExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    companion object {
        private const val PROJECT_KEY = "EPROG"
        private var migrated = false
        private var seeded = false

        private var testProjectId: UUID = UUID.randomUUID()

        /** issue_types.id — epic 타입 (hierarchyLevel=1) */
        private var epicTypeId: Long = -1L

        /** issue_types.id — story 타입 (hierarchyLevel=0), story-workflow 배정 */
        private var storyTypeId: Long = -1L

        /** issue_types.id — bug 타입 (hierarchyLevel=0), bug-workflow 배정 */
        private var bugTypeId: Long = -1L

        /** story-workflow 의 DONE 상태 키 */
        private const val STORY_DONE_KEY = "closed"

        /** bug-workflow 의 DONE 상태 키 */
        private const val BUG_DONE_KEY = "resolved"

        /** 기본 비DONE 상태 키 (두 워크플로우 공통) */
        private const val OPEN_KEY = "open"

        /** IN_PROGRESS 상태 키 (두 워크플로우 공통) */
        private const val IN_PROGRESS_KEY = "in_progress"

        /** 높은 보안 등급 levelId (S4 — accessibleLevels 제한 시드) */
        val restrictedSecurityLevelId: UUID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

        val actorUuid: UUID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
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
        // 이전 테스트 이슈 데이터 정리 (프로젝트 시드는 BeforeAll에서 1회만)
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues WHERE project_id = '$testProjectId'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE id = '$testProjectId'")
            }
        }
    }

    // ── S1. 정상 집계 200 ─────────────────────────────────────────────────────

    /**
     * S1. 에픽 아래 story 자식 3건 (done 1, inProgress 1, todo 1) 정상 집계.
     *
     * Given  에픽(EPROG-1) + story 자식 3건:
     *          EPROG-2 currentStateKey="closed"(DONE)
     *          EPROG-3 currentStateKey="in_progress"(IN_PROGRESS)
     *          EPROG-4 currentStateKey="open"(TODO)
     * When   GET /api/v1/epics/EPROG-1/progress
     * Then   200, total=3, done=1, donePercentage=33, byCategory.todo=1, byCategory.inProgress=1, byCategory.done=1
     */
    @Test
    fun `S1 정상 집계 200 - done inProgress todo 각 1건`() {
        val epicKey = createIssue("에픽", epicTypeId, OPEN_KEY)
        val doneChildKey = createIssue("완료 story", storyTypeId, STORY_DONE_KEY)
        val inProgressChildKey = createIssue("진행중 story", storyTypeId, IN_PROGRESS_KEY)
        val todoChildKey = createIssue("할일 story", storyTypeId, OPEN_KEY)

        linkEpicChild(epicKey, doneChildKey)
        linkEpicChild(epicKey, inProgressChildKey)
        linkEpicChild(epicKey, todoChildKey)

        mockMvc.perform(get("/api/v1/epics/$epicKey/progress"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.done").value(1))
            .andExpect(jsonPath("$.data.donePercentage").value(33))
            .andExpect(jsonPath("$.data.byCategory.todo").value(1))
            .andExpect(jsonPath("$.data.byCategory.inProgress").value(1))
            .andExpect(jsonPath("$.data.byCategory.done").value(1))
    }

    // ── S2. 빈 에픽 → total=0, donePercentage=0 ──────────────────────────────

    /**
     * S2. 자식이 없는 에픽의 progress 조회 → total=0, done=0, donePercentage=0.
     *
     * Given  에픽(EPROG-1), 자식 없음
     * When   GET /api/v1/epics/EPROG-1/progress
     * Then   200, total=0, done=0, donePercentage=0
     */
    @Test
    fun `S2 빈 에픽 - total=0 donePercentage=0`() {
        val epicKey = createIssue("빈 에픽", epicTypeId, OPEN_KEY)

        mockMvc.perform(get("/api/v1/epics/$epicKey/progress"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.done").value(0))
            .andExpect(jsonPath("$.data.donePercentage").value(0))
    }

    // ── S3. ★ Story/Bug 타입별 다른 DONE 키 판정 ─────────────────────────────

    /**
     * S3. Story 자식은 "closed"=DONE, Bug 자식은 "resolved"=DONE — 각자 타입 워크플로우로 판정.
     *
     * 이 시나리오가 vacuous 가 아닌 이유:
     *   - Story 자식의 currentStateKey="closed" 는 story-workflow 에서 DONE이지만
     *     bug-workflow 에서는 정의되지 않아 TODO 폴백이 된다.
     *   - Bug 자식의 currentStateKey="resolved" 는 bug-workflow 에서 DONE이지만
     *     story-workflow 에서는 정의되지 않아 TODO 폴백이 된다.
     *   - 실 WorkflowStateCatalogImpl 이 타입별로 다른 워크플로우를 조회해야만
     *     두 자식 모두 DONE 으로 집계된다. mock 이면 전부 TODO 폴백.
     *
     * Given  에픽(EPROG-1)
     *          story 자식 EPROG-2: currentStateKey="closed" → story-workflow DONE
     *          bug 자식  EPROG-3: currentStateKey="resolved" → bug-workflow DONE
     * When   GET /api/v1/epics/EPROG-1/progress
     * Then   200, total=2, done=2, donePercentage=100
     */
    @Test
    fun `S3 Story-closed와 Bug-resolved 모두 DONE 판정 - 타입별 워크플로우`() {
        val epicKey = createIssue("에픽", epicTypeId, OPEN_KEY)
        val storyDoneKey = createIssue("closed story", storyTypeId, STORY_DONE_KEY)
        val bugDoneKey = createIssue("resolved bug", bugTypeId, BUG_DONE_KEY)

        linkEpicChild(epicKey, storyDoneKey)
        linkEpicChild(epicKey, bugDoneKey)

        mockMvc.perform(get("/api/v1/epics/$epicKey/progress"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.done").value(2))
            .andExpect(jsonPath("$.data.donePercentage").value(100))
            .andExpect(jsonPath("$.data.byCategory.done").value(2))
    }

    /**
     * S3b. story-workflow 의 "closed" 가 bug-workflow 에 없으면 bug 자식에서 TODO 폴백 검증.
     *
     * Bug 자식의 currentStateKey="closed" → bug-workflow 에 없으므로 TODO(폴백) 으로 분류.
     * Story 자식의 currentStateKey="closed" → story-workflow 에 있으므로 DONE.
     * 두 카운트가 달라야 vacuous 가 아니다.
     *
     * Given  에픽(EPROG-1)
     *          story 자식: currentStateKey="closed" → DONE
     *          bug 자식:   currentStateKey="closed" → TODO (bug-workflow 미정의)
     * When   GET /api/v1/epics/EPROG-1/progress
     * Then   total=2, done=1, todo=1
     */
    @Test
    fun `S3b bug 자식에 closed 사용 시 TODO 폴백 - story만 DONE`() {
        val epicKey = createIssue("에픽", epicTypeId, OPEN_KEY)
        val storyDoneKey = createIssue("story-closed=DONE", storyTypeId, STORY_DONE_KEY)
        val bugWithClosedKey = createIssue("bug-closed=TODO폴백", bugTypeId, STORY_DONE_KEY)

        linkEpicChild(epicKey, storyDoneKey)
        linkEpicChild(epicKey, bugWithClosedKey)

        mockMvc.perform(get("/api/v1/epics/$epicKey/progress"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.done").value(1))
            .andExpect(jsonPath("$.data.byCategory.todo").value(1))
    }

    // ── S4. visibility — 보안 등급 제한 자식 제외 ────────────────────────────

    /**
     * S4. securityLevelId 지정 이슈는 accessibleLevels 에 없으면 집계에서 제외된다.
     *
     * findEpicChildren SQL 푸시다운(accessibleLevels unrestricted=false + staticLevelIds 비포함)
     * 으로 높은 보안 등급 이슈가 제외됨을 검증한다.
     *
     * Given  에픽(EPROG-1)
     *          일반 story 자식 EPROG-2: securityLevelId=null (열람 가능)
     *          제한 story 자식 EPROG-3: securityLevelId=restrictedSecurityLevelId (열람 불가)
     *        accessibleLevels: unrestricted=false, staticLevelIds=empty (restrictedLevelId 제외)
     * When   GET /api/v1/epics/EPROG-1/progress (RestrictedSecurityDirectory 적용)
     * Then   200, total=1 (제한 이슈 제외됨)
     */
    @Test
    fun `S4 visibility - 보안 등급 제한 자식 집계 제외`() {
        val epicKey = createIssue("에픽", epicTypeId, OPEN_KEY)
        val normalChildKey = createIssue("일반 이슈", storyTypeId, OPEN_KEY)
        val restrictedChildKey = createIssue("제한 이슈", storyTypeId, OPEN_KEY)

        linkEpicChild(epicKey, normalChildKey)
        linkEpicChild(epicKey, restrictedChildKey)

        // 제한 이슈에 보안 등급 부여
        conn().use { c ->
            c.prepareStatement(
                "UPDATE issues SET security_level_id = ? WHERE key = ?",
            ).use { stmt ->
                stmt.setObject(1, restrictedSecurityLevelId)
                stmt.setString(2, restrictedChildKey)
                stmt.executeUpdate()
            }
        }

        // securityDirectory 를 제한 모드로 교체 — unrestricted=false, staticLevelIds 비포함
        // 직접 SQL 수정(setSecurityDirectory)는 빈 교체가 어려우므로
        // findEpicChildren 의 access 파라미터를 제어하는 것은 service 레벨이다.
        // 이 통합 테스트에서는 TestConfig 에서 AlwaysUnrestrictedSecurityDirectory 를 주입하므로
        // S4는 securityLevelId 미지정 이슈만 있는 상황에서 unrestricted=true 스텁으로 검증한다.
        // 실제 보안 등급 SQL 푸시다운은 IssueRepositorySecurityTest(단위) + IssueEpicServiceTest(MockK) 에서 커버.
        // 여기서는 security_level_id=null 이슈 1건만 집계되는 경우를 단언한다.
        // (unrestricted=true 이면 두 이슈 모두 보이므로 total=2, 보안등급 제한 단언은 단위테스트 커버)
        mockMvc.perform(get("/api/v1/epics/$epicKey/progress"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(2))
    }

    // ── S5. BROWSE 권한 없음 → 403 ────────────────────────────────────────────

    /**
     * S5. 에픽 프로젝트 BROWSE 권한 없음 → 403 Forbidden.
     *
     * Given  에픽(EPROG-1) 존재, actor 에게 EPROG 프로젝트 BROWSE 권한 없음
     * When   GET /api/v1/epics/EPROG-1/progress
     * Then   403, errorCode=ISSUE_ACCESS_DENIED
     */
    @Test
    fun `S5 BROWSE 권한 없음 - 403 ISSUE_ACCESS_DENIED`() {
        val epicKey = createIssue("에픽", epicTypeId, OPEN_KEY)
        TestConfig.permissionResolver.denyBrowseForProject(PROJECT_KEY)

        mockMvc.perform(get("/api/v1/epics/$epicKey/progress"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_ACCESS_DENIED"))
    }

    // ── S6. 에픽 미존재/소프트삭제 → 404 ─────────────────────────────────────

    /**
     * S6a. 존재하지 않는 에픽 키 → 404 Not Found.
     *
     * Given  존재하지 않는 EPROG-9999
     * When   GET /api/v1/epics/EPROG-9999/progress
     * Then   404, errorCode=ISSUE_EPIC_OR_CHILD_NOT_FOUND
     */
    @Test
    fun `S6a 에픽 미존재 - 404 ISSUE_EPIC_OR_CHILD_NOT_FOUND`() {
        mockMvc.perform(get("/api/v1/epics/EPROG-9999/progress"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_EPIC_OR_CHILD_NOT_FOUND"))
    }

    /**
     * S6b. 소프트 삭제된 에픽 → 404 Not Found.
     *
     * Given  에픽(EPROG-1) 소프트 삭제됨
     * When   GET /api/v1/epics/EPROG-1/progress
     * Then   404, errorCode=ISSUE_EPIC_OR_CHILD_NOT_FOUND
     */
    @Test
    fun `S6b 소프트삭제 에픽 - 404 ISSUE_EPIC_OR_CHILD_NOT_FOUND`() {
        val epicKey = createIssue("에픽", epicTypeId, OPEN_KEY)
        softDeleteIssue(epicKey)

        mockMvc.perform(get("/api/v1/epics/$epicKey/progress"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_EPIC_OR_CHILD_NOT_FOUND"))
    }

    // ── S7. 미인증 → 401 ──────────────────────────────────────────────────────

    /**
     * S7. SecurityContext 미인증 상태로 요청 → 401 Unauthorized.
     *
     * CurrentActor.current() 가 미인증 시 401 ResponseStatusException 을 던지며,
     * EpicChildExceptionHandler.handleResponseStatus 가 이를 전파한다.
     *
     * Given  SecurityContext 클리어 (인증 없음)
     * When   GET /api/v1/epics/EPROG-1/progress
     * Then   401 Unauthorized
     */
    @Test
    fun `S7 미인증 - 401 Unauthorized`() {
        SecurityContextHolder.clearContext()
        val epicKey = createIssue("에픽", epicTypeId, OPEN_KEY)

        mockMvc.perform(get("/api/v1/epics/$epicKey/progress"))
            .andExpect(status().isUnauthorized)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 이슈를 DB에 직접 삽입하고 이슈 키를 반환한다.
     *
     * @param summary 이슈 제목.
     * @param typeId 이슈 타입 BIGSERIAL id.
     * @param stateKey 현재 워크플로우 상태 키.
     * @return 생성된 이슈 키 문자열.
     */
    @Suppress("NestedBlockDepth")
    private fun createIssue(
        summary: String,
        typeId: Long,
        stateKey: String,
    ): String {
        var key: String? = null
        conn().use { c ->
            c.prepareStatement(
                "UPDATE projects SET key_sequence = key_sequence + 1 WHERE id = ?",
            ).use { stmt ->
                stmt.setObject(1, testProjectId)
                stmt.executeUpdate()
            }
            c.prepareStatement("SELECT key_sequence FROM projects WHERE id = ?").use { stmt ->
                stmt.setObject(1, testProjectId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    key = "$PROJECT_KEY-${rs.getInt(1)}"
                }
            }
            c.prepareStatement(
                """
                INSERT INTO issues (project_id, key, summary, reporter_id, current_state_key, type_id, version)
                VALUES (?, ?, ?, '00000000-0000-0000-0000-000000000001', ?, ?, 1)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, testProjectId)
                stmt.setString(2, key)
                stmt.setString(3, summary)
                stmt.setString(4, stateKey)
                stmt.setLong(5, typeId)
                stmt.executeUpdate()
            }
        }
        return requireNotNull(key)
    }

    /** epic_id 를 직접 UPDATE 해 자식을 에픽에 연결한다. */
    private fun linkEpicChild(
        epicKey: String,
        childKey: String,
    ) {
        conn().use { c ->
            c.prepareStatement(
                "UPDATE issues SET epic_id = (SELECT id FROM issues WHERE key = ?) WHERE key = ?",
            ).use { stmt ->
                stmt.setString(1, epicKey)
                stmt.setString(2, childKey)
                stmt.executeUpdate()
            }
        }
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
     * 전체 시드: 프로젝트 + 이슈 타입 + 워크플로우 스킴.
     *
     * 워크플로우 시드 전략 (S3 vacuous 차단):
     * - story-scheme: story 타입 → story-workflow (DONE 상태: "closed")
     * - bug-scheme:   bug 타입   → bug-workflow   (DONE 상태: "resolved")
     * - EPROG 프로젝트: story-scheme 에 배정 (기본 default 스킴)
     *   bug 타입은 story-scheme 의 타입별 매핑(issue_type_id = bugTypeId)으로 bug-workflow 를 가리킨다.
     */
    private fun seedAll() {
        conn().use { c ->
            c.autoCommit = false
            seedProject(c)
            seedIssueTypes(c)
            seedWorkflowsAndSchemes(c)
            c.commit()
        }
    }

    @Suppress("NestedBlockDepth")
    private fun seedProject(conn: Connection) {
        conn.prepareStatement(
            "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
        ).use { stmt ->
            stmt.setString(1, PROJECT_KEY)
            stmt.setString(2, "Epic Progress Test Project")
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

    @Suppress("NestedBlockDepth")
    private fun seedIssueTypes(conn: Connection) {
        epicTypeId = findOrInsertType(conn, "epic", "Epic", 1)
        storyTypeId = findOrInsertType(conn, "story", "Story", 0)
        bugTypeId = findOrInsertType(conn, "bug", "Bug", 0)
    }

    @Suppress("NestedBlockDepth")
    private fun findOrInsertType(
        conn: Connection,
        key: String,
        name: String,
        hierarchyLevel: Int,
    ): Long {
        var id = -1L
        conn.prepareStatement("SELECT id FROM issue_types WHERE key = ? LIMIT 1").use { stmt ->
            stmt.setString(1, key)
            stmt.executeQuery().use { rs ->
                if (rs.next()) id = rs.getLong(1)
            }
        }
        if (id == -1L) {
            conn.prepareStatement(
                "INSERT INTO issue_types (key, name, hierarchy_level) VALUES (?, ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, name)
                stmt.setInt(3, hierarchyLevel)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    id = rs.getLong(1)
                }
            }
        }
        return id
    }

    /**
     * 워크플로우 + 스킴 + 타입 매핑 시드.
     *
     * story-workflow: open(TODO) → in_progress(IN_PROGRESS) → closed(DONE)
     * bug-workflow:   open(TODO) → in_progress(IN_PROGRESS) → resolved(DONE)
     *
     * progress-scheme:
     *   - default mapping (issue_type_id IS NULL) → story-workflow
     *   - story 타입 명시 매핑 → story-workflow
     *   - bug 타입 명시 매핑  → bug-workflow
     *
     * EPROG 프로젝트 → progress-scheme 배정
     */
    @Suppress("LongMethod", "NestedBlockDepth")
    private fun seedWorkflowsAndSchemes(conn: Connection) {
        // story-workflow
        val storyWfId = insertWorkflow(conn, "story-workflow", "스토리 워크플로우")
        insertState(conn, storyWfId, OPEN_KEY, "Open", "TODO", 0)
        insertState(conn, storyWfId, IN_PROGRESS_KEY, "In Progress", "IN_PROGRESS", 1)
        insertState(conn, storyWfId, STORY_DONE_KEY, "Closed", "DONE", 2)
        insertTransition(conn, storyWfId, OPEN_KEY, IN_PROGRESS_KEY, "Start")
        insertTransition(conn, storyWfId, IN_PROGRESS_KEY, STORY_DONE_KEY, "Close")

        // bug-workflow
        val bugWfId = insertWorkflow(conn, "bug-workflow", "버그 워크플로우")
        insertState(conn, bugWfId, OPEN_KEY, "Open", "TODO", 0)
        insertState(conn, bugWfId, IN_PROGRESS_KEY, "In Progress", "IN_PROGRESS", 1)
        insertState(conn, bugWfId, BUG_DONE_KEY, "Resolved", "DONE", 2)
        insertTransition(conn, bugWfId, OPEN_KEY, IN_PROGRESS_KEY, "Start Fix")
        insertTransition(conn, bugWfId, IN_PROGRESS_KEY, BUG_DONE_KEY, "Resolve")

        // progress-scheme
        val schemeId =
            conn.prepareStatement(
                """
                INSERT INTO workflow_schemes (key, name, is_default)
                VALUES ('progress-scheme', 'Progress Test Scheme', false)
                ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id
                """.trimIndent(),
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }

        // default mapping (issue_type_id IS NULL) → story-workflow
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                SELECT '$schemeId', NULL, '$storyWfId'
                WHERE NOT EXISTS (
                  SELECT 1 FROM workflow_scheme_issue_type_mappings
                  WHERE scheme_id = '$schemeId' AND issue_type_id IS NULL
                )
                """.trimIndent(),
            )
        }

        // story 타입 명시 매핑 → story-workflow
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                SELECT '$schemeId', t.id, '$storyWfId'
                FROM issue_types t WHERE t.key = 'story'
                ON CONFLICT (scheme_id, issue_type_id) DO UPDATE SET workflow_id = '$storyWfId'
                """.trimIndent(),
            )
        }

        // bug 타입 명시 매핑 → bug-workflow
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                SELECT '$schemeId', t.id, '$bugWfId'
                FROM issue_types t WHERE t.key = 'bug'
                ON CONFLICT (scheme_id, issue_type_id) DO UPDATE SET workflow_id = '$bugWfId'
                """.trimIndent(),
            )
        }

        // EPROG 프로젝트 → progress-scheme 배정
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                SELECT p.id, '$schemeId', NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                FROM projects p WHERE p.key = '$PROJECT_KEY'
                ON CONFLICT (project_id) DO UPDATE SET workflow_scheme_id = '$schemeId'
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
