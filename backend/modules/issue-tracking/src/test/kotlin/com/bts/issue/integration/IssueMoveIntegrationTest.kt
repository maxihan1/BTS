// 이슈 이동 HTTP 통합 테스트 — EC 전수 + 308 redirect + 권한 end-to-end + 보존 invariant (FR-MV-01 Task 9 / FR-MV-02 Task 2)

package com.bts.issue.integration

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.adapter.inbound.rest.IssueExceptionHandler
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.IssueMoveService
import com.bts.issue.application.MovePreviewService
import com.bts.issue.attachment.repository.AttachmentRepository
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.link.domain.IssueLink
import com.bts.issue.link.domain.LinkType
import com.bts.issue.link.repository.IssueLinkRepository
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueKeyRedirectRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.issue.watcher.repository.IssueWatcherRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import com.bts.workflow.scheme.adapter.inbound.WorkflowResolverImpl
import com.bts.workflow.scheme.adapter.inbound.WorkflowStateCatalogImpl
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * 이슈 이동 HTTP 통합 테스트.
 *
 * 실제 Testcontainers PostgreSQL + 실제 Spring 컨텍스트로 end-to-end 검증한다.
 * TestConfig(singleton Testcontainers + Flyway + 기본 빈)를 재사용하고,
 * IssueMoveConfig로 이동 관련 빈을 추가 wire한다.
 *
 * ## 검증 시나리오
 *
 * ### happy path
 * - H1. 단건 이동 → 200 + 새 키(DST prefix) + 이전 키 반환
 * - H2. 이동 후 DB 영속 검증 — issues.id 불변 + project_id 변경 + issue_key_redirects 행 삽입
 * - H3. 이동 후 GET 옛 키 → 308 Permanent Redirect + Location: /api/v1/issues/{새키}
 * - H4. 체인 이동(A→B→C): A 조회 → 최종 키 C로 308 (redirect repository nullable default 주입 확인)
 *
 * ### EC 시나리오
 * - EC1. 같은 프로젝트 이동 → 422 MOVE_SAME_PROJECT
 * - EC3. 미존재 이슈 → 404 ISSUE_NOT_FOUND
 * - EC5. OCC 충돌 → 409 VERSION_CONFLICT
 * - EC6. 대상 프로젝트 워크플로우 미설정 → 422 WORKFLOW_NOT_CONFIGURED
 * - EC15. 자식 이슈 보유 → 422 ISSUE_HAS_SUBTASKS
 *
 * ### 권한 시나리오
 * - P1. 원본 프로젝트 UPDATE 권한 없음 → 403 ACCESS_DENIED
 * - P2. 대상 프로젝트 CREATE 권한 없음 → 403 ACCESS_DENIED
 *
 * ## 설계 원칙
 * 308은 MockMvc가 기본적으로 redirect를 따라가지 않으므로 status().isPermanentRedirect()와
 * header().string("Location", ...) 조합으로 직접 검증한다.
 * sleep/waitFor 없이 MockMvc 동기 단언만 사용한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class, IssueMoveIntegrationTest.IssueMoveConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LongMethod", "TooManyFunctions")
class IssueMoveIntegrationTest {
    /**
     * Thread-local 기반 스위처블 권한 resolver.
     *
     * 기본값은 허용(AlwaysAllow 동형)이며, denyUpdateForProject / denyCreateForProject 로
     * 특정 프로젝트에 대한 권한 거부를 테스트 단위로 제어한다.
     *
     * IssuePermissionResolver.hasPermission 시그니처: (actorId: UUID, permission, scope) → Boolean.
     */
    class SwitchablePermissionResolver : IssuePermissionResolver {
        private val denyUpdateProject = ThreadLocal<String?>()
        private val denyCreateProject = ThreadLocal<String?>()

        fun denyUpdateForProject(projectKey: String) = denyUpdateProject.set(projectKey)

        fun denyCreateForProject(projectKey: String) = denyCreateProject.set(projectKey)

        fun resetPermissions() {
            denyUpdateProject.remove()
            denyCreateProject.remove()
        }

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            if (scope !is IssueScope.Project) return true
            val deniedUpdate = permission == IssuePermission.UPDATE && scope.key == denyUpdateProject.get()
            val deniedCreate = permission == IssuePermission.CREATE && scope.key == denyCreateProject.get()
            return !(deniedUpdate || deniedCreate)
        }
    }

    /**
     * 이슈 이동 관련 추가 빈 구성.
     *
     * TestConfig의 기본 빈(IssueRepository, WorkflowKeyResolverImpl 등)을 autowire 받아
     * IssueMoveService, MovePreviewService, IssueMoveController를 wire한다.
     * IssueApplicationService는 @Primary로 교체하여 IssueKeyRedirectRepository를 주입받도록 한다.
     * 권한 거부 시나리오를 위해 SwitchablePermissionResolver를 @Primary로 등록한다.
     */
    @Configuration
    @Suppress("LongParameterList")
    open class IssueMoveConfig {
        /**
         * 권한 거부 모드를 Thread-local로 제어하는 테스트 전용 resolver.
         * @Primary로 TestConfig의 AlwaysAllowIssuePermissionResolver를 대체한다.
         */
        @Bean
        @Primary
        open fun switchablePermissionResolver(): SwitchablePermissionResolver = SwitchablePermissionResolver()

        @Bean
        open fun issueKeyRedirectRepository(dsl: DSLContext): IssueKeyRedirectRepository {
            return IssueKeyRedirectRepository(dsl)
        }

        @Bean
        open fun moveComponentRepository(dsl: DSLContext): ComponentRepository = ComponentRepository(dsl)

        @Bean
        open fun moveVersionRepository(dsl: DSLContext): VersionRepository = VersionRepository(dsl)

        @Bean
        open fun moveProjectLeadRepository(dsl: DSLContext): ProjectLeadRepository = ProjectLeadRepository(dsl)

        @Bean
        open fun moveCustomFieldDefinitionRepository(dsl: DSLContext): CustomFieldDefinitionRepository =
            CustomFieldDefinitionRepository(dsl)

        @Bean
        open fun moveIssueWatcherRepository(dsl: DSLContext): IssueWatcherRepository = IssueWatcherRepository(dsl)

        @Bean
        open fun moveIssueLinkRepository(dsl: DSLContext): IssueLinkRepository = IssueLinkRepository(dsl)

        @Bean
        open fun moveAttachmentRepository(dsl: DSLContext): AttachmentRepository = AttachmentRepository(dsl)

        /**
         * IssueApplicationService를 @Primary로 교체하여 IssueKeyRedirectRepository를 주입받는다.
         *
         * keyRedirectRepository가 null이면 308 redirect가 동작하지 않으므로(findByKey에서 skip)
         * 실 Bean을 주입하여 H3/H4 시나리오를 검증한다.
         */
        @Bean
        @Primary
        @Suppress("LongParameterList")
        open fun issueMoveApplicationService(
            repo: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            eventPublisher: IssueEventPublisher,
            permissionResolver: SwitchablePermissionResolver,
            workflowTransitionAdapter: WorkflowTransitionAdapter,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            userLookupPort: UserLookupPort,
            componentRepository: ComponentRepository,
            projectLeadRepository: ProjectLeadRepository,
            versionRepository: VersionRepository,
            clock: Clock,
            keyRedirectRepository: IssueKeyRedirectRepository,
            watcherRepository: IssueWatcherRepository,
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
                componentRepository = componentRepository,
                projectLeadRepository = projectLeadRepository,
                versionRepository = versionRepository,
                clock = clock,
                historyRecorder = mockk(relaxed = true),
                keyRedirectRepository = keyRedirectRepository,
                watcherRepository = watcherRepository,
            )

        @Bean
        open fun workflowStateCatalogImpl(workflowResolver: WorkflowResolverImpl): WorkflowStateCatalogImpl =
            WorkflowStateCatalogImpl(workflowResolver)

        @Bean
        open fun issueMoveHistoryRecorder(): IssueHistoryRecorder = mockk(relaxed = true)

        /** FR-PJ-04 PR-4 Task 9 — 실 ProjectArchiveGuard(공유 dsl 위). */
        @Bean
        open fun projectArchiveGuard(dsl: DSLContext): ProjectArchiveGuard =
            ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))

        @Bean
        @Suppress("LongParameterList")
        open fun issueMoveService(
            repo: IssueRepository,
            keyRedirectRepository: IssueKeyRedirectRepository,
            permissionResolver: SwitchablePermissionResolver,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            workflowStateCatalog: WorkflowStateCatalogImpl,
            historyRecorder: IssueHistoryRecorder,
            componentRepository: ComponentRepository,
            versionRepository: VersionRepository,
            customFieldDefinitionRepository: CustomFieldDefinitionRepository,
            archiveGuard: ProjectArchiveGuard,
        ): IssueMoveService =
            IssueMoveService(
                issueRepository = repo,
                redirectRepository = keyRedirectRepository,
                permissionResolver = permissionResolver,
                workflowKeyResolver = workflowKeyResolver,
                workflowStateCatalog = workflowStateCatalog,
                historyRecorder = historyRecorder,
                componentRepository = componentRepository,
                versionRepository = versionRepository,
                customFieldDefinitionRepository = customFieldDefinitionRepository,
                archiveGuard = archiveGuard,
            )

        @Bean
        open fun movePreviewService(
            permissionResolver: SwitchablePermissionResolver,
            repo: IssueRepository,
            componentRepository: ComponentRepository,
            versionRepository: VersionRepository,
            customFieldDefinitionRepository: CustomFieldDefinitionRepository,
            workflowStateCatalog: WorkflowStateCatalogImpl,
        ): MovePreviewService =
            MovePreviewService(
                permissionResolver = permissionResolver,
                issueRepository = repo,
                componentRepository = componentRepository,
                versionRepository = versionRepository,
                customFieldDefinitionRepository = customFieldDefinitionRepository,
                workflowStateCatalog = workflowStateCatalog,
            )

        @Bean
        open fun issueMoveController(
            previewService: MovePreviewService,
            moveService: IssueMoveService,
        ): com.bts.issue.adapter.inbound.rest.IssueMoveController =
            com.bts.issue.adapter.inbound.rest.IssueMoveController(
                previewService = previewService,
                moveService = moveService,
            )

        @Bean
        open fun issueMoveExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dataSource: DriverManagerDataSource

    @Autowired
    lateinit var permissionResolver: SwitchablePermissionResolver

    @Autowired
    lateinit var issueLinkRepository: IssueLinkRepository

    @Autowired
    lateinit var watcherRepository: IssueWatcherRepository

    @Autowired
    lateinit var attachmentRepository: AttachmentRepository

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        /** 이동 원본 프로젝트 — 이동 실행 + redirect 검증에 사용 */
        private const val SRC_KEY = "MVSRC"

        /** 이동 대상 프로젝트 (software-scheme 배정) */
        private const val DST_KEY = "MVDST"

        /** 체인 이동 두 번째 대상 프로젝트 */
        private const val DST2_KEY = "MVDS2"

        /** 워크플로우 미설정 대상 프로젝트 (EC6 검증) */
        private const val NO_WF_KEY = "MVNOWF"

        /** 행위자 UUID — TestConfig의 SecurityContext principal과 동일 */
        private const val ACTOR_ID = "11111111-1111-4111-8111-111111111111"

        private var bootstrapped = false
    }

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
                ACTOR_ID,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        permissionResolver.resetPermissions()
        cleanIssues()
    }

    @AfterEach
    fun tearDown() {
        permissionResolver.resetPermissions()
        SecurityContextHolder.clearContext()
    }

    // ── H1. happy path — 단건 이동 200 + 새 키/이전 키 반환 ─────────────────────

    /**
     * Given  SRC 프로젝트에 이슈 MVSRC-1 (open 상태)
     * When   POST /api/v1/issues/MVSRC-1/move { targetProjectKey: "MVDST", expectedVersion: 1, ... }
     * Then   200 OK + data.issueKey 가 "MVDST-1" + data.previousKey 가 "MVSRC-1"
     */
    @Test
    fun `H1 단건 이동 200 - 새 키 MVDST-1 + 이전 키 MVSRC-1`() {
        insertIssue(SRC_KEY, "open")

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 1L))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.issueKey").value("$DST_KEY-1"))
            .andExpect(jsonPath("$.data.previousKey").value("$SRC_KEY-1"))
    }

    // ── H2. DB 영속 검증 — issues.id 불변 + project 변경 + redirect 행 삽입 ──────

    /**
     * Given  SRC 프로젝트에 이슈 MVSRC-1 (id=UUID 고정)
     * When   POST /api/v1/issues/MVSRC-1/move
     * Then   - issues.id 불변
     *        - issues.key = "MVDST-1"
     *        - issue_key_redirects (MVSRC-1 → MVDST-1) 행 존재
     *        - MVSRC-1 키로 issues 조회 결과 없음
     */
    @Test
    fun `H2 DB 영속 - id 불변 + redirect 행 삽입 + 원본 키 소멸`() {
        val originalId = insertIssue(SRC_KEY, "open")

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 1L))),
        ).andExpect(status().isOk)

        getConnection().use { conn ->
            // issues.id 불변 + key 갱신 확인
            val newId =
                conn.prepareStatement("SELECT id FROM issues WHERE key = '$DST_KEY-1'").use { ps ->
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "MVDST-1 행이 없음" }
                        rs.getObject(1) as UUID
                    }
                }
            assert(newId == originalId) { "issues.id 가 이동 후 변경됨: 기대=$originalId 실제=$newId" }

            // redirect 행 존재
            val redirectCount =
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM issue_key_redirects WHERE old_key = '$SRC_KEY-1' AND new_key = '$DST_KEY-1'",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            assert(redirectCount == 1L) { "issue_key_redirects 에 행이 없음" }

            // 원본 키 소멸
            val srcCount =
                conn.prepareStatement("SELECT COUNT(*) FROM issues WHERE key = '$SRC_KEY-1'").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            assert(srcCount == 0L) { "이동 후에도 원본 키 MVSRC-1이 남아 있음" }
        }
    }

    // ── H3. GET 옛 키 → 308 Permanent Redirect ────────────────────────────────

    /**
     * Given  MVSRC-1 → MVDST-1 이동 완료
     * When   GET /api/v1/issues/MVSRC-1
     * Then   308 Permanent Redirect + Location: /api/v1/issues/MVDST-1
     *
     * MockMvc는 기본적으로 redirect를 따라가지 않으므로 308 자체를 단언한다.
     * 이 시나리오로 IssueKeyRedirectRepository 빈 주입이 실 Spring 컨텍스트에서 정상 동작함을 확정한다.
     */
    @Test
    fun `H3 GET 옛 키 - 308 Permanent Redirect + Location 헤더`() {
        insertIssue(SRC_KEY, "open")

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 1L))),
        ).andExpect(status().isOk)

        // 이동 후 옛 키 조회 → 308
        mockMvc.perform(get("/api/v1/issues/$SRC_KEY-1"))
            .andExpect(status().isPermanentRedirect)
            .andExpect(header().string("Location", "/api/v1/issues/$DST_KEY-1"))
    }

    // ── H4. 체인 이동(A→B→C): A 조회 → 최종 키 C로 308 ─────────────────────────

    /**
     * Given  MVSRC-1 → MVDST-1 이동 후, MVDST-1 → MVDS2-1 연속 이동
     * When   GET /api/v1/issues/MVSRC-1
     * Then   308 Permanent Redirect + Location: /api/v1/issues/MVDS2-1 (최종 키)
     *
     * IssueKeyRedirectRepository.findCurrentKey가 체인을 순회하여 최종 키를 반환함을 검증한다.
     */
    @Test
    fun `H4 체인 이동 - A to B to C 후 A 조회 시 최종 키 C로 308`() {
        insertIssue(SRC_KEY, "open")

        // Step 1: MVSRC-1 → MVDST-1
        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 1L))),
        ).andExpect(status().isOk)

        // Step 2: MVDST-1 → MVDS2-1 (버전 2로 이동됨)
        mockMvc.perform(
            post("/api/v1/issues/$DST_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST2_KEY, 2L))),
        ).andExpect(status().isOk)

        // A 조회 → 최종 키 C로 308
        mockMvc.perform(get("/api/v1/issues/$SRC_KEY-1"))
            .andExpect(status().isPermanentRedirect)
            .andExpect(header().string("Location", "/api/v1/issues/$DST2_KEY-1"))
    }

    // ── EC1. 같은 프로젝트 이동 → 422 MOVE_SAME_PROJECT ─────────────────────────

    /**
     * Given  MVSRC 프로젝트에 이슈 MVSRC-1
     * When   POST /api/v1/issues/MVSRC-1/move { targetProjectKey: "MVSRC" }
     * Then   422 Unprocessable Entity + errorCode: "MOVE_SAME_PROJECT"
     */
    @Test
    fun `EC1 같은 프로젝트 이동 - 422 MOVE_SAME_PROJECT`() {
        insertIssue(SRC_KEY, "open")

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(SRC_KEY, 1L))),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("MOVE_SAME_PROJECT"))
    }

    // ── EC3. 미존재 이슈 → 404 ISSUE_NOT_FOUND ───────────────────────────────

    /**
     * Given  MVSRC 프로젝트에 이슈 없음
     * When   POST /api/v1/issues/MVSRC-999/move { targetProjectKey: "MVDST" }
     * Then   404 Not Found + errorCode: "ISSUE_NOT_FOUND"
     */
    @Test
    fun `EC3 미존재 이슈 - 404 ISSUE_NOT_FOUND`() {
        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-999/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 1L))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── EC5. OCC 충돌 → 409 VERSION_CONFLICT ─────────────────────────────────

    /**
     * Given  MVSRC-1 (DB version=1)
     * When   POST /api/v1/issues/MVSRC-1/move { expectedVersion: 999 }
     * Then   409 Conflict + errorCode: "VERSION_CONFLICT"
     */
    @Test
    fun `EC5 OCC 충돌 - 409 VERSION_CONFLICT`() {
        insertIssue(SRC_KEY, "open")

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 999L))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
    }

    // ── EC6. 대상 프로젝트 워크플로우 미설정 → 422 WORKFLOW_NOT_CONFIGURED ────────

    /**
     * Given  MVSRC-1 존재, MVNOWF 프로젝트는 workflow_scheme 배정 없음
     * When   POST /api/v1/issues/MVSRC-1/move { targetProjectKey: "MVNOWF" }
     * Then   422 Unprocessable Entity + errorCode: "WORKFLOW_NOT_CONFIGURED"
     */
    @Test
    fun `EC6 대상 워크플로우 미설정 - 422 WORKFLOW_NOT_CONFIGURED`() {
        insertIssue(SRC_KEY, "open")

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(NO_WF_KEY, 1L))),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("WORKFLOW_NOT_CONFIGURED"))
    }

    // ── EC15. 자식 이슈 보유 → 422 ISSUE_HAS_SUBTASKS ────────────────────────

    /**
     * Given  MVSRC-1(부모) + MVSRC-2(자식, parent_id=MVSRC-1의 UUID)
     * When   POST /api/v1/issues/MVSRC-1/move { targetProjectKey: "MVDST" }
     * Then   422 Unprocessable Entity + errorCode: "ISSUE_HAS_SUBTASKS"
     */
    @Test
    fun `EC15 자식 이슈 보유 - 422 ISSUE_HAS_SUBTASKS`() {
        val parentId = insertIssue(SRC_KEY, "open")
        insertIssue(SRC_KEY, "open", parentId = parentId)

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 1L))),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_HAS_SUBTASKS"))
    }

    // ── P1. 원본 프로젝트 UPDATE 권한 없음 → 403 ACCESS_DENIED ───────────────────

    /**
     * Given  MVSRC-1 존재, 행위자에게 MVSRC UPDATE 권한 없음
     * When   POST /api/v1/issues/MVSRC-1/move { targetProjectKey: "MVDST" }
     * Then   403 Forbidden + errorCode: "ACCESS_DENIED"
     */
    @Test
    fun `P1 원본 UPDATE 권한 없음 - 403 ACCESS_DENIED`() {
        insertIssue(SRC_KEY, "open")
        permissionResolver.denyUpdateForProject(SRC_KEY)

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 1L))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
    }

    // ── P2. 대상 프로젝트 CREATE 권한 없음 → 403 ACCESS_DENIED ───────────────────

    /**
     * Given  MVSRC-1 존재, 행위자에게 MVDST CREATE 권한 없음
     * When   POST /api/v1/issues/MVSRC-1/move { targetProjectKey: "MVDST" }
     * Then   403 Forbidden + errorCode: "ACCESS_DENIED"
     */
    @Test
    fun `P2 대상 CREATE 권한 없음 - 403 ACCESS_DENIED`() {
        insertIssue(SRC_KEY, "open")
        permissionResolver.denyCreateForProject(DST_KEY)

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 1L))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
    }

    // ── FR-MV-02 Task 2 — 보존 invariant 테스트 (D5) ───────────────────────────

    /**
     * 단건 이동 시 링크·워처·첨부 행 집합이 보존됨을 검증한다 (FR-MV-02 Task 2).
     *
     * ## 시드 구성
     * - 이동 대상 이슈(MVSRC-1)에 나가는 blocks 링크 1개 + 들어오는 relates 링크 1개.
     * - 워처 2명.
     * - 첨부 2개 (DB 행만 — MinIO 왕복 없음, G3).
     *
     * ## vacuous 차단 (메모리 B3)
     * 시드 직후 populated(>0) 선단언으로 시드 0건 false-green을 차단한다.
     *
     * ## 보존 단언
     * 이동 후 이슈 id로 재조회한 링크(id·type·방향)·워처(userId)·첨부(id) 집합이
     * 이동 전과 동일함을 단언한다.
     *
     * Given  MVSRC-1에 나가는 blocks 링크 1 + 들어오는 relates 링크 1 + 워처 2 + 첨부 2 시드
     * When   POST /api/v1/issues/MVSRC-1/move { targetProjectKey: "MVDST" }
     * Then   issue_links·issue_watchers·issue_attachments 행 집합이 이동 전후 동일
     */
    @Test
    @Suppress("LongMethod")
    fun `INV1 단건 이동 - 링크 워처 첨부 행 집합 보존`() {
        val issueId = insertIssue(SRC_KEY, "open")
        val otherIssueId = insertIssue(SRC_KEY, "open")

        // 시드 — 링크 (반환값은 미사용 — populated 단언과 이동 후 재조회로 id 집합 비교)
        issueLinkRepository.insert(
            IssueLink.create(sourceId = issueId, targetId = otherIssueId, linkType = LinkType.BLOCKS),
        )
        issueLinkRepository.insert(
            IssueLink.create(sourceId = otherIssueId, targetId = issueId, linkType = LinkType.RELATES),
        )

        // 시드 — 워처
        val watcher1Id = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val watcher2Id = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        watcherRepository.add(issueId, watcher1Id)
        watcherRepository.add(issueId, watcher2Id)

        // 시드 — 첨부 (DB 행 직접, MinIO 연결 없음)
        val attachId1 = UUID.randomUUID()
        val attachId2 = UUID.randomUUID()
        insertAttachmentRow(issueId, attachId1, "file1.txt")
        insertAttachmentRow(issueId, attachId2, "file2.txt")

        // ─── populated 선단언 (vacuous 차단) ────────────────────────────────
        val outboundBefore = issueLinkRepository.findBySourceId(issueId)
        val inboundBefore = issueLinkRepository.findByTargetId(issueId)
        val watchersBefore = watcherRepository.listByIssue(issueId)
        val attachmentsBefore = attachmentRepository.findByIssueId(issueId)

        assert(outboundBefore.isNotEmpty()) { "시드 후 나가는 링크가 0건 — vacuous 차단 실패" }
        assert(inboundBefore.isNotEmpty()) { "시드 후 들어오는 링크가 0건 — vacuous 차단 실패" }
        assert(watchersBefore.size == 2) { "시드 후 워처가 ${watchersBefore.size}건 (기대 2) — vacuous 차단 실패" }
        assert(attachmentsBefore.size == 2) { "시드 후 첨부가 ${attachmentsBefore.size}건 (기대 2) — vacuous 차단 실패" }

        // ─── 이동 실행 ────────────────────────────────────────────────────
        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 1L))),
        ).andExpect(status().isOk)

        // ─── 이동 후 보존 단언 — id·type·방향까지 동일 ─────────────────────
        val outboundAfter = issueLinkRepository.findBySourceId(issueId)
        val inboundAfter = issueLinkRepository.findByTargetId(issueId)
        val watchersAfter = watcherRepository.listByIssue(issueId)
        val attachmentsAfter = attachmentRepository.findByIssueId(issueId)

        assert(outboundAfter.map { it.id }.toSet() == outboundBefore.map { it.id }.toSet()) {
            "이동 후 나가는 링크 id 집합 변경됨. 전=${outboundBefore.map { it.id }} 후=${outboundAfter.map { it.id }}"
        }
        assert(outboundAfter.first().linkType == LinkType.BLOCKS) {
            "이동 후 나가는 링크 타입 변경됨: ${outboundAfter.first().linkType}"
        }
        assert(inboundAfter.map { it.id }.toSet() == inboundBefore.map { it.id }.toSet()) {
            "이동 후 들어오는 링크 id 집합 변경됨. 전=${inboundBefore.map { it.id }} 후=${inboundAfter.map { it.id }}"
        }
        assert(inboundAfter.first().linkType == LinkType.RELATES) {
            "이동 후 들어오는 링크 타입 변경됨: ${inboundAfter.first().linkType}"
        }
        assert(watchersAfter.map { it.userId }.toSet() == watchersBefore.map { it.userId }.toSet()) {
            "이동 후 워처 집합 변경됨. 전=${watchersBefore.map { it.userId }} 후=${watchersAfter.map { it.userId }}"
        }
        assert(attachmentsAfter.map { it.id }.toSet() == attachmentsBefore.map { it.id }.toSet()) {
            "이동 후 첨부 id 집합 변경됨. 전=${attachmentsBefore.map { it.id }} 후=${attachmentsAfter.map { it.id }}"
        }
    }

    /**
     * 서브태스크 동반 이동 시 부모·자식 각각의 링크·워처·첨부 행 집합이 보존됨을 검증한다 (FR-MV-02 Task 2).
     *
     * ## 시드 구성
     * - 부모 이슈(MVSRC-1): 나가는 blocks 링크 1 + 워처 1 + 첨부 1.
     * - 자식 이슈(MVSRC-2, parent=MVSRC-1): 나가는 relates 링크 1 + 워처 1 + 첨부 1.
     * - 링크 상대방(MVSRC-3)은 이동 대상에서 제외 — 링크 행 보존만 검증.
     *
     * ## vacuous 차단
     * 부모·자식 각자에 대해 시드 직후 populated(>0) 선단언.
     *
     * Given  부모(MVSRC-1) + 자식(MVSRC-2)에 각각 링크·워처·첨부 시드
     * When   POST /api/v1/issues/MVSRC-1/move (subtasks=[MVSRC-2])
     * Then   부모·자식 각각의 issue_links·issue_watchers·issue_attachments 행 집합 보존
     */
    @Test
    @Suppress("LongMethod")
    fun `INV2 서브태스크 동반 이동 - 부모 자식 각각 링크 워처 첨부 보존`() {
        val parentId = insertIssue(SRC_KEY, "open")
        val childId = insertIssue(SRC_KEY, "open", parentId = parentId)
        val otherIssueId = insertIssue(SRC_KEY, "open")

        // 시드 — 부모 링크 (나가는 blocks)
        issueLinkRepository.insert(
            IssueLink.create(sourceId = parentId, targetId = otherIssueId, linkType = LinkType.BLOCKS),
        )
        // 시드 — 자식 링크 (나가는 relates)
        issueLinkRepository.insert(
            IssueLink.create(sourceId = childId, targetId = otherIssueId, linkType = LinkType.RELATES),
        )

        // 시드 — 부모 워처
        val parentWatcherId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        watcherRepository.add(parentId, parentWatcherId)

        // 시드 — 자식 워처
        val childWatcherId = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
        watcherRepository.add(childId, childWatcherId)

        // 시드 — 부모 첨부
        val parentAttachId = UUID.randomUUID()
        insertAttachmentRow(parentId, parentAttachId, "parent-doc.txt")

        // 시드 — 자식 첨부
        val childAttachId = UUID.randomUUID()
        insertAttachmentRow(childId, childAttachId, "child-doc.txt")

        // ─── populated 선단언 (vacuous 차단) ────────────────────────────────
        val parentLinksBefore = issueLinkRepository.findBySourceId(parentId)
        val childLinksBefore = issueLinkRepository.findBySourceId(childId)
        val parentWatchersBefore = watcherRepository.listByIssue(parentId)
        val childWatchersBefore = watcherRepository.listByIssue(childId)
        val parentAttachBefore = attachmentRepository.findByIssueId(parentId)
        val childAttachBefore = attachmentRepository.findByIssueId(childId)

        assert(parentLinksBefore.isNotEmpty()) { "부모 나가는 링크 0건 — vacuous 차단 실패" }
        assert(childLinksBefore.isNotEmpty()) { "자식 나가는 링크 0건 — vacuous 차단 실패" }
        assert(parentWatchersBefore.isNotEmpty()) { "부모 워처 0건 — vacuous 차단 실패" }
        assert(childWatchersBefore.isNotEmpty()) { "자식 워처 0건 — vacuous 차단 실패" }
        assert(parentAttachBefore.isNotEmpty()) { "부모 첨부 0건 — vacuous 차단 실패" }
        assert(childAttachBefore.isNotEmpty()) { "자식 첨부 0건 — vacuous 차단 실패" }

        // ─── 동반 이동 실행 ───────────────────────────────────────────────
        val moveWithSubtasksRequest =
            mapOf(
                "targetProjectKey" to DST_KEY,
                "expectedVersion" to 1L,
                "targetStateIsDone" to false,
                "componentMapping" to emptyMap<String, String>(),
                "affectsVersionMapping" to emptyMap<String, String>(),
                "fixVersionMapping" to emptyMap<String, String>(),
                "customFieldValues" to emptyMap<String, Any>(),
                "subtasks" to
                    listOf(
                        mapOf(
                            "issueKey" to "$SRC_KEY-2",
                            "expectedVersion" to 1L,
                            "targetStateIsDone" to false,
                            "componentMapping" to emptyMap<String, String>(),
                            "affectsVersionMapping" to emptyMap<String, String>(),
                            "fixVersionMapping" to emptyMap<String, String>(),
                            "customFieldValues" to emptyMap<String, Any>(),
                        ),
                    ),
            )

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(moveWithSubtasksRequest)),
        ).andExpect(status().isOk)

        // ─── 이동 후 보존 단언 — 부모 ────────────────────────────────────
        val parentLinksAfter = issueLinkRepository.findBySourceId(parentId)
        val parentWatchersAfter = watcherRepository.listByIssue(parentId)
        val parentAttachAfter = attachmentRepository.findByIssueId(parentId)

        assert(parentLinksAfter.map { it.id }.toSet() == parentLinksBefore.map { it.id }.toSet()) {
            "이동 후 부모 링크 집합 변경됨"
        }
        assert(parentWatchersAfter.map { it.userId }.toSet() == parentWatchersBefore.map { it.userId }.toSet()) {
            "이동 후 부모 워처 집합 변경됨"
        }
        assert(parentAttachAfter.map { it.id }.toSet() == parentAttachBefore.map { it.id }.toSet()) {
            "이동 후 부모 첨부 집합 변경됨"
        }

        // ─── 이동 후 보존 단언 — 자식 ────────────────────────────────────
        val childLinksAfter = issueLinkRepository.findBySourceId(childId)
        val childWatchersAfter = watcherRepository.listByIssue(childId)
        val childAttachAfter = attachmentRepository.findByIssueId(childId)

        assert(childLinksAfter.map { it.id }.toSet() == childLinksBefore.map { it.id }.toSet()) {
            "이동 후 자식 링크 집합 변경됨"
        }
        assert(childWatchersAfter.map { it.userId }.toSet() == childWatchersBefore.map { it.userId }.toSet()) {
            "이동 후 자식 워처 집합 변경됨"
        }
        assert(childAttachAfter.map { it.id }.toSet() == childAttachBefore.map { it.id }.toSet()) {
            "이동 후 자식 첨부 집합 변경됨"
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — issue-tracking + project-workflow 두 BC를 단일 pass로 적용.
     * TestConfig의 singleton 컨테이너를 재사용하므로 이미 마이그레이션된 경우 멱등 동작한다.
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
     * 테스트 전용 프로젝트 시드.
     *
     * 1. MVSRC / MVDST / MVDS2 — software-scheme(software-default workflow) 배정
     * 2. MVNOWF — 워크플로우 스킴 배정 없음 (EC6 검증)
     *
     * TestConfig.seedWorkflowsAndSchemes()가 같은 컨테이너에 시드하는 경우 ON CONFLICT 멱등.
     * 아직 시드되지 않은 경우를 대비해 IssueCloneIntegrationTest 패턴처럼 fallback 시드도 수행한다.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun seedProjects() {
        getConnection().use { conn ->
            conn.autoCommit = false

            for ((key, name) in listOf(
                SRC_KEY to "Move Source Project",
                DST_KEY to "Move Dest Project",
                DST2_KEY to "Move Dest 2 Project",
                NO_WF_KEY to "Move No Workflow Project",
            )) {
                conn.prepareStatement(
                    "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                ).use { ps ->
                    ps.setString(1, key)
                    ps.setString(2, name)
                    ps.executeUpdate()
                }
            }

            // software-default workflow — TestConfig 시드가 앞서면 ON CONFLICT로 skip
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO workflows (key, name) " +
                        "VALUES ('software-default', '소프트웨어 개발 기본 워크플로우') " +
                        "ON CONFLICT (key) DO NOTHING",
                )
            }

            val wfId =
                conn.prepareStatement("SELECT id FROM workflows WHERE key = 'software-default'").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1, UUID::class.java)
                    }
                }

            // workflow states (ON CONFLICT 멱등)
            fun insertStateIfAbsent(
                key: String,
                name: String,
                category: String,
                displayOrder: Int,
            ): UUID =
                conn.prepareStatement(
                    "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                        "VALUES (?, ?, ?, ?, ?) ON CONFLICT (workflow_id, key) " +
                        "DO UPDATE SET display_order = EXCLUDED.display_order RETURNING id",
                ).use { ps ->
                    ps.setObject(1, wfId)
                    ps.setString(2, key)
                    ps.setString(3, name)
                    ps.setString(4, category)
                    ps.setInt(5, displayOrder)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1, UUID::class.java)
                    }
                }

            val openId = insertStateIfAbsent("open", "Open", "TODO", 0)
            val inProgressId = insertStateIfAbsent("in_progress", "In Progress", "IN_PROGRESS", 1)
            val inReviewId = insertStateIfAbsent("in_review", "In Review", "IN_PROGRESS", 2)
            val doneId = insertStateIfAbsent("done", "Done", "DONE", 3)

            // workflow transitions (ON CONFLICT 멱등)
            fun insertTransitionIfAbsent(
                fromId: UUID,
                toId: UUID,
                name: String,
            ) {
                conn.prepareStatement(
                    "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) " +
                        "VALUES (?, ?, ?, ?) ON CONFLICT (workflow_id, from_state_id, to_state_id) DO NOTHING",
                ).use { ps ->
                    ps.setObject(1, wfId)
                    ps.setObject(2, fromId)
                    ps.setObject(3, toId)
                    ps.setString(4, name)
                    ps.executeUpdate()
                }
            }

            insertTransitionIfAbsent(openId, inProgressId, "Start Work")
            insertTransitionIfAbsent(inProgressId, inReviewId, "Submit for Review")
            insertTransitionIfAbsent(inReviewId, doneId, "Approve")

            // software-scheme (ON CONFLICT 멱등)
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO workflow_schemes (key, name, is_default) " +
                        "VALUES ('software-scheme', 'Software Scheme', true) " +
                        "ON CONFLICT (key) DO NOTHING",
                )
            }

            // software-scheme default mapping → software-default workflow (멱등)
            // ix_scheme_default_mapping은 (scheme_id) WHERE issue_type_id IS NULL partial UNIQUE INDEX이므로
            // ON CONFLICT ON CONSTRAINT uq_scheme_issue_type으로는 NULL 중복을 막지 못한다.
            // WHERE NOT EXISTS로 이미 삽입된 경우 skip한다.
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                    SELECT s.id, NULL, '$wfId'
                    FROM workflow_schemes s
                    WHERE s.key = 'software-scheme'
                      AND NOT EXISTS (
                        SELECT 1 FROM workflow_scheme_issue_type_mappings m
                        WHERE m.scheme_id = s.id AND m.issue_type_id IS NULL
                      )
                    """.trimIndent(),
                )
            }

            // MVSRC / MVDST / MVDS2 에 software-scheme 배정 (ON CONFLICT 멱등)
            // workflow_scheme_assignments는 scheme_id(BIGINT) 기반이므로 SELECT로 직접 조인
            for (key in listOf(SRC_KEY, DST_KEY, DST2_KEY)) {
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                        SELECT p.id, s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                        FROM projects p, workflow_schemes s
                        WHERE p.key = '$key'
                          AND s.key = 'software-scheme'
                        ON CONFLICT (project_id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }

            conn.commit()
        }
    }

    /**
     * 각 테스트 전 이슈/redirect 초기화.
     * 테스트 전용 프로젝트 이슈만 삭제하고 key_sequence를 초기화한다.
     */
    private fun cleanIssues() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "DELETE FROM issue_key_redirects WHERE old_key LIKE '$SRC_KEY-%' " +
                        "OR old_key LIKE '$DST_KEY-%' OR old_key LIKE '$DST2_KEY-%'",
                )
                stmt.execute(
                    "DELETE FROM issues WHERE key LIKE '$SRC_KEY-%' " +
                        "OR key LIKE '$DST_KEY-%' OR key LIKE '$DST2_KEY-%'",
                )
                stmt.execute(
                    "UPDATE projects SET key_sequence = 0 WHERE key IN " +
                        "('$SRC_KEY', '$DST_KEY', '$DST2_KEY', '$NO_WF_KEY')",
                )
            }
        }
    }

    /**
     * 이슈를 DB에 직접 삽입하고 생성된 이슈 UUID를 반환한다.
     *
     * @param projectKey 프로젝트 키
     * @param stateKey 초기 상태 키
     * @param parentId 부모 이슈 UUID. null이면 최상위 이슈.
     * @return 삽입된 이슈 UUID
     */
    private fun insertIssue(
        projectKey: String,
        stateKey: String,
        parentId: UUID? = null,
    ): UUID {
        val issueId = UUID.randomUUID()

        getConnection().use { conn ->
            conn.autoCommit = false

            val seq =
                conn.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { ps ->
                    ps.setString(1, projectKey)
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "프로젝트 $projectKey 가 없음" }
                        rs.getLong(1)
                    }
                }

            val issueKey = "$projectKey-$seq"

            val taskTypeId =
                conn.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음. V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            conn.prepareStatement(
                """
                INSERT INTO issues (id, key, project_id, summary, reporter_id, current_state_key, type_id, version, parent_id)
                SELECT ?, ?, p.id, '이동 테스트용 이슈', ?::uuid, ?, ?, 1, ?
                FROM projects p WHERE p.key = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, issueId)
                ps.setString(2, issueKey)
                ps.setString(3, ACTOR_ID)
                ps.setString(4, stateKey)
                ps.setLong(5, taskTypeId)
                ps.setObject(6, parentId)
                ps.setString(7, projectKey)
                ps.executeUpdate()
            }

            conn.commit()
        }

        return issueId
    }

    /**
     * `issue_attachments` 테이블에 첨부 메타데이터 행을 직접 삽입한다.
     *
     * MinIO 객체 업로드 없이 DB 행만 생성한다 (이동은 MinIO를 건드리지 않으므로 행 보존 검증에 충분).
     * 파라미터 바인딩 prepared statement 사용 — SQL 문자열 결합 금지 (DATA.md §1.3).
     *
     * @param issueId 첨부가 속할 이슈 UUID.
     * @param attachId 첨부 고유 UUID.
     * @param filename 원본 파일명.
     */
    private fun insertAttachmentRow(
        issueId: UUID,
        attachId: UUID,
        filename: String,
    ) {
        @Suppress("MaxLineLength")
        val sql =
            "INSERT INTO issue_attachments " +
                "(id, issue_id, filename, content_type, size_bytes, storage_key, uploaded_by, created_at) " +
                "VALUES (?, ?, ?, 'text/plain', 0, ?, ?::uuid, NOW())"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setObject(1, attachId)
                ps.setObject(2, issueId)
                ps.setString(3, filename)
                ps.setString(4, "test-storage/$attachId")
                ps.setString(5, ACTOR_ID)
                ps.executeUpdate()
            }
        }
    }

    /** 이동 요청 바디를 빌드한다. */
    private fun buildMoveRequest(
        targetProjectKey: String,
        expectedVersion: Long,
    ): Map<String, Any> =
        mapOf(
            "targetProjectKey" to targetProjectKey,
            "expectedVersion" to expectedVersion,
            "targetStateIsDone" to false,
            "componentMapping" to emptyMap<String, String>(),
            "affectsVersionMapping" to emptyMap<String, String>(),
            "fixVersionMapping" to emptyMap<String, String>(),
            "customFieldValues" to emptyMap<String, Any>(),
        )

    /** TestConfig singleton PostgreSQL 컨테이너 연결을 반환한다. */
    private fun getConnection(): Connection =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
