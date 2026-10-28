// SchemeStatusMigrationController WebMvc 슬라이스 테스트 — 게시 · 교체 409 응답 모양 · 가드 순서 (R11)

package com.bts.workflow.scheme.web

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowScope
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.scheme.application.SchemeStatusMigrationService
import com.bts.workflow.scheme.application.WorkflowOwnershipScopeResolver
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.domain.StrandedIssueType
import com.bts.workflow.scheme.domain.TypeStatusMapping
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.exception.SchemeChangeStrandsIssuesException
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
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * [SchemeStatusMigrationController] 슬라이스 테스트.
 *
 * ActorId value class 를 받는 서비스 함수는 MockK 로 못 잡아 stub 서브클래스로 바꾼다
 * ([ProjectWorkflowSchemeControllerTest] 와 같은 우회).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [SchemeStatusMigrationControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class SchemeStatusMigrationControllerTest {
    class StubMigrationService : SchemeStatusMigrationService(mockk(), mockk(), mockk(), mockk()) {
        var failWith: RuntimeException? = null
        var capturedStatusMappings: List<TypeStatusMapping>? = null

        override fun publish(
            actor: ActorId,
            schemeKey: WorkflowSchemeKey,
            mappings: List<Pair<IssueTypeId?, UUID>>,
            statusMappings: List<TypeStatusMapping>,
        ): List<UUID> {
            capturedStatusMappings = statusMappings
            failWith?.let { throw it }
            return listOf(BULK_ID)
        }

        override fun switchScheme(
            actor: ActorId,
            projectId: UUID,
            projectKey: String,
            schemeKey: WorkflowSchemeKey,
            statusMappings: List<TypeStatusMapping>,
        ): List<UUID> {
            failWith?.let { throw it }
            return listOf(BULK_ID)
        }
    }

    class StubSchemeService : WorkflowSchemeApplicationService(
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        mockk(),
    ) {
        override fun resolveMappingKeys(mappings: List<Pair<String?, String>>): List<Pair<IssueTypeId?, UUID>> =
            mappings.map { null to UUID.randomUUID() }
    }

    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        val permResolverStub = CapturingPermissionResolverStub()
        val migrationStub = StubMigrationService()

        @Bean
        open fun projectLookupPort(): ProjectLookupPort = mockk(relaxed = true)

        @Bean
        open fun controller(projectLookupPort: ProjectLookupPort): SchemeStatusMigrationController {
            val scopeResolver = mockk<WorkflowOwnershipScopeResolver>()
            every { scopeResolver.ofScheme(any()) } returns WorkflowScope.Global
            return SchemeStatusMigrationController(migrationStub, StubSchemeService(), permResolverStub, scopeResolver, projectLookupPort)
        }

        @Bean
        open fun workflowSchemeExceptionHandler(): WorkflowSchemeExceptionHandler = WorkflowSchemeExceptionHandler()
    }

    @Autowired private lateinit var wac: WebApplicationContext

    @Autowired private lateinit var config: TestMvcConfig

    @Autowired private lateinit var projectLookupPort: ProjectLookupPort

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
        config.permResolverStub.reset()
        config.migrationStub.failWith = null
        config.migrationStub.capturedStatusMappings = null
    }

    @Test
    @WithMockUser(username = AUTH_ACTOR)
    fun `게시 — 남는 이슈가 있으면 409 에 유형별 목록과 상태 합계를 싣는다`() {
        config.migrationStub.failWith =
            SchemeChangeStrandsIssuesException(
                listOf(StrandedIssueType(10L, UUID.randomUUID(), "wf-plain", setOf("open", "done"), mapOf("review" to 3L))),
            )

        mockMvc.perform(
            post("/api/v1/workflow-schemes/review-scheme/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mappings":[{"issueTypeKey":null,"workflowKey":"wf-plain"}]}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("SCHEME_CHANGE_STRANDS_ISSUES"))
            .andExpect(jsonPath("$.strandedByStatus.review").value(3))
            .andExpect(jsonPath("$.strandedByType[0].issueTypeId").value(10))
            .andExpect(jsonPath("$.strandedByType[0].workflowKey").value("wf-plain"))
            .andExpect(jsonPath("$.strandedByType[0].statuses.review").value(3))
    }

    @Test
    @WithMockUser(username = AUTH_ACTOR)
    fun `게시 — 상태 매핑을 받아 넘기고 큐잉한 작업 id 를 돌려준다`() {
        mockMvc.perform(
            post("/api/v1/workflow-schemes/review-scheme/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"mappings":[{"issueTypeKey":null,"workflowKey":"wf-plain"}],
                    "statusMappings":[{"issueTypeId":10,"fromStatusKey":"review","toStatusKey":"done"}]}""",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.bulkOperationIds[0]").value(BULK_ID.toString()))

        assertThat(config.migrationStub.capturedStatusMappings).containsExactly(TypeStatusMapping(10L, "review", "done"))
        assertThat(config.permResolverStub.capturedPermission).isEqualTo(WorkflowSchemePermission.MANAGE_SCHEME)
    }

    @Test
    @WithMockUser(username = AUTH_ACTOR)
    fun `교체 — 권한이 없으면 프로젝트를 찾기 전에 403`() {
        // 조회가 먼저면 없는 키라 404 가 나온다 — 403 이 순서의 증거다.
        every { projectLookupPort.findIdByKey(ProjectKey("UNKNOWN")) } returns null
        config.permResolverStub.denyWith =
            WorkflowSchemeAccessDeniedException(
                UUID.fromString(AUTH_ACTOR),
                WorkflowSchemePermission.ASSIGN_SCHEME,
                WorkflowScope.Project("UNKNOWN"),
            )

        mockMvc.perform(
            post("/api/v1/projects/UNKNOWN/workflow-scheme/switch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"schemeKey":"plain-scheme"}"""),
        )
            .andExpect(status().isForbidden)
    }

    private companion object {
        const val AUTH_ACTOR = "22222222-2222-4222-8222-222222222222"
        val BULK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    }
}
