// ProjectWorkflowSchemeController WebMvc 슬라이스 테스트 — Project assignment 2 endpoint (PUT/GET)

package com.bts.workflow.scheme.web

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.domain.ProjectWorkflowSchemeAssignment
import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * [ProjectWorkflowSchemeController] WebMvc 슬라이스 테스트.
 *
 * spec §4.3 Project assignment 2 endpoint 검증.
 * - Case 1. PUT  /api/v1/projects/{projectKey}/workflow-scheme — 200 + assignment 응답
 * - Case 2. GET  /api/v1/projects/{projectKey}/workflow-scheme — 200 + scheme 응답
 * - Case 3. PUT 시 ASSIGN_SCHEME 권한 검증 호출 확인
 * - Case 4. GET 시 ASSIGN_SCHEME 권한 검증 호출 확인
 * - Case 5. projectKey 에 해당하는 project 없으면 404
 *
 * ### ActorId inline value class MockK 우회 (JvmSignatureValueGenerator 제한)
 * MockK 1.13.x 는 @JvmInline value class 파라미터를 가진 함수의 서명 값 생성 시 init 검증에 실패한다.
 * 이 문제를 피하기 위해 [WorkflowSchemePermissionResolver] 와 [WorkflowSchemeApplicationService] 를
 * MockK mock 대신 직접 구현한 stub 클래스로 교체한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ProjectWorkflowSchemeControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ProjectWorkflowSchemeControllerTest {
    /**
     * ActorId inline value class MockK 우회용 권한 resolver stub.
     *
     * 마지막으로 호출된 [permission] 과 [scope] 를 캡처한다.
     */
    class CapturingPermissionResolverStub : WorkflowSchemePermissionResolver {
        var capturedPermission: WorkflowSchemePermission? = null
        var capturedScope: WorkflowSchemeScope? = null
        var callCount = 0

        override fun requirePermission(
            actorId: UUID,
            permission: WorkflowSchemePermission,
            scope: WorkflowSchemeScope,
        ) {
            capturedPermission = permission
            capturedScope = scope
            callCount++
        }

        fun reset() {
            capturedPermission = null
            capturedScope = null
            callCount = 0
        }
    }

    /**
     * ActorId inline value class MockK 우회용 application service stub.
     *
     * [assignToProjectResponse] 와 [findAssignedSchemeResponse] 를 설정해 응답을 제어한다.
     * 나머지 메서드는 UnsupportedOperationException 을 던진다 (호출되지 않아야 함).
     */
    class StubWorkflowSchemeApplicationService : WorkflowSchemeApplicationService(
        schemeRepo = mockk(),
        assignmentRepo = mockk(),
        mappingRepo = mockk(),
        eventPublisher = mockk(),
        permissionResolver =
            object : WorkflowSchemePermissionResolver {
                override fun requirePermission(
                    actorId: UUID,
                    permission: WorkflowSchemePermission,
                    scope: WorkflowSchemeScope,
                ) = Unit
            },
        workflowRepo = mockk(),
        issueTypeLookupPort = mockk(),
    ) {
        var assignToProjectResponse: ProjectWorkflowSchemeAssignment? = null
        var findAssignedSchemeResponse: WorkflowScheme? = null

        override fun assignToProject(
            actor: ActorId,
            projectId: UUID,
            projectKey: String,
            schemeKey: WorkflowSchemeKey,
        ): ProjectWorkflowSchemeAssignment =
            assignToProjectResponse ?: throw IllegalStateException("assignToProjectResponse not configured")

        override fun findAssignedScheme(
            projectId: UUID,
            projectKey: String,
        ): WorkflowScheme = findAssignedSchemeResponse ?: throw IllegalStateException("findAssignedSchemeResponse not configured")
    }

    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        val permResolverStub = CapturingPermissionResolverStub()
        val appServiceStub = StubWorkflowSchemeApplicationService()

        @Bean
        open fun workflowSchemeApplicationService(): WorkflowSchemeApplicationService = appServiceStub

        @Bean
        open fun workflowSchemePermissionResolver(): WorkflowSchemePermissionResolver = permResolverStub

        @Bean
        open fun projectLookupPort(): ProjectLookupPort = mockk(relaxed = true)

        @Bean
        open fun projectWorkflowSchemeController(
            appService: WorkflowSchemeApplicationService,
            permissionResolver: WorkflowSchemePermissionResolver,
            projectLookupPort: ProjectLookupPort,
        ): ProjectWorkflowSchemeController = ProjectWorkflowSchemeController(appService, permissionResolver, projectLookupPort)

        @Bean
        open fun workflowSchemeExceptionHandler(): WorkflowSchemeExceptionHandler = WorkflowSchemeExceptionHandler()
    }

    @Autowired
    private lateinit var wac: WebApplicationContext

    @Autowired
    private lateinit var projectLookupPort: ProjectLookupPort

    @Autowired
    private lateinit var config: TestMvcConfig

    private lateinit var mockMvc: MockMvc

    private val projectId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val schemeId = WorkflowSchemeId(1L)
    private val schemeKey = WorkflowSchemeKey("software-scheme")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
        config.permResolverStub.reset()
        config.appServiceStub.assignToProjectResponse = null
        config.appServiceStub.findAssignedSchemeResponse = null
    }

    // ── Case 1. PUT /api/v1/projects/{projectKey}/workflow-scheme — 200 ───────

    @Test
    fun `PUT — 프로젝트에 스킴 할당 성공 시 200 + assignment 응답`() {
        val assignment =
            ProjectWorkflowSchemeAssignment(
                projectId = projectId,
                workflowSchemeId = schemeId,
                assignedAt = Instant.parse("2026-01-01T00:00:00Z"),
                assignedBy = UUID.fromString("00000000-0000-0000-0000-000000000000"),
            )

        every { projectLookupPort.findIdByKey(ProjectKey("ATLAS")) } returns projectId
        config.appServiceStub.assignToProjectResponse = assignment

        val body = """{"schemeKey":"software-scheme"}"""

        mockMvc.perform(
            put("/api/v1/projects/ATLAS/workflow-scheme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.projectId").value(projectId.toString()))
            .andExpect(jsonPath("$.data.workflowSchemeId").value(1))
    }

    // ── Case 2. GET /api/v1/projects/{projectKey}/workflow-scheme — 200 ────────

    @Test
    fun `GET — 프로젝트에 배정된 스킴 조회 성공 시 200 + scheme 응답`() {
        val scheme =
            WorkflowScheme.reconstruct(
                id = schemeId,
                key = schemeKey,
                name = "Software 표준 스킴",
                description = null,
                isDefault = true,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                deletedAt = null,
            )

        every { projectLookupPort.findIdByKey(ProjectKey("ATLAS")) } returns projectId
        config.appServiceStub.findAssignedSchemeResponse = scheme

        mockMvc.perform(get("/api/v1/projects/ATLAS/workflow-scheme"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value("software-scheme"))
            .andExpect(jsonPath("$.data.name").value("Software 표준 스킴"))
    }

    // ── Case 3. PUT — ASSIGN_SCHEME 권한 검증 호출 확인 ──────────────────────

    @Test
    fun `PUT — ASSIGN_SCHEME 권한 검증이 호출된다`() {
        val assignment =
            ProjectWorkflowSchemeAssignment(
                projectId = projectId,
                workflowSchemeId = schemeId,
                assignedAt = Instant.now(),
                assignedBy = UUID.fromString("00000000-0000-0000-0000-000000000000"),
            )

        every { projectLookupPort.findIdByKey(ProjectKey("ATLAS")) } returns projectId
        config.appServiceStub.assignToProjectResponse = assignment

        val body = """{"schemeKey":"software-scheme"}"""

        mockMvc.perform(
            put("/api/v1/projects/ATLAS/workflow-scheme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)

        assertThat(config.permResolverStub.callCount).isEqualTo(1)
        assertThat(config.permResolverStub.capturedPermission).isEqualTo(WorkflowSchemePermission.ASSIGN_SCHEME)
        assertThat(config.permResolverStub.capturedScope).isEqualTo(WorkflowSchemeScope.Project("ATLAS"))
    }

    // ── Case 4. GET — ASSIGN_SCHEME 권한 검증 호출 확인 ──────────────────────

    @Test
    fun `GET — ASSIGN_SCHEME 권한 검증이 호출된다`() {
        val scheme =
            WorkflowScheme.reconstruct(
                id = schemeId,
                key = schemeKey,
                name = "Software 표준 스킴",
                description = null,
                isDefault = true,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                deletedAt = null,
            )

        every { projectLookupPort.findIdByKey(ProjectKey("ATLAS")) } returns projectId
        config.appServiceStub.findAssignedSchemeResponse = scheme

        mockMvc.perform(get("/api/v1/projects/ATLAS/workflow-scheme"))
            .andExpect(status().isOk)

        assertThat(config.permResolverStub.callCount).isEqualTo(1)
        assertThat(config.permResolverStub.capturedPermission).isEqualTo(WorkflowSchemePermission.ASSIGN_SCHEME)
        assertThat(config.permResolverStub.capturedScope).isEqualTo(WorkflowSchemeScope.Project("ATLAS"))
    }

    // ── Case 5. projectKey 에 해당하는 project 없으면 404 ─────────────────────

    @Test
    fun `PUT — projectKey 에 해당 project 없으면 404`() {
        every { projectLookupPort.findIdByKey(ProjectKey("UNKNOWN")) } returns null

        val body = """{"schemeKey":"software-scheme"}"""

        mockMvc.perform(
            put("/api/v1/projects/UNKNOWN/workflow-scheme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isNotFound)
    }
}
