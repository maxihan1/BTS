// ProjectWorkflowController WebMvc 슬라이스 — 프로젝트 스코프 목록 창구의 가드 순서와 스코프

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.shared.permission.WorkflowScope
import com.bts.workflow.application.WorkflowApplicationService
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/**
 * [ProjectWorkflowController] 슬라이스 테스트.
 *
 * ## 무엇을 재는가
 * 1. **가드 순서** — 권한(403)이 프로젝트 조회(404)보다 먼저다. 뒤집히면 권한 없는 사용자가
 *    응답 코드 차이로 프로젝트 키의 실재를 열거한다(존재 probe).
 * 2. **스코프** — 판정에 넘기는 스코프가 `Project(그 키)` 다. `Global` 로 넘기면 프로젝트
 *    관리자가 통과하지 못하고, 스코프를 아예 안 보면 남의 프로젝트도 통과한다.
 * 3. **목록 창구** — 전량 목록([WorkflowApplicationService.listWorkflows])이 아니라
 *    프로젝트 스코프 목록을 탄다. 전량을 타면 남의 프로젝트 워크플로우가 이름째 샌다.
 *
 * 소유 필터의 SQL 진실은 `WorkflowCrudIntegrationTest` 가 실 DB 로 잰다.
 */
class ProjectWorkflowControllerTest {
    private val actorId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val projectId = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000a1")

    /** 판정에 실제로 넘어온 (권한, 스코프)를 잡아 둔다 — 「불렀다」가 아니라 「무엇으로 불렀나」를 잰다. */
    private var capturedPermission: WorkflowDefinitionPermission? = null
    private var capturedScope: WorkflowScope? = null
    private var denyPermission = false

    private val permissionResolver =
        object : WorkflowDefinitionPermissionResolver {
            override fun requirePermission(
                actorId: UUID,
                permission: WorkflowDefinitionPermission,
                scope: WorkflowScope,
            ) {
                capturedPermission = permission
                capturedScope = scope
                if (denyPermission) {
                    throw WorkflowDefinitionAccessDeniedException(actorId, permission, scope)
                }
            }
        }

    private val projectLookupPort =
        object : ProjectLookupPort {
            // 블록 본문으로 둔다 — 식 본문이면 ktlint 가 「한 줄로 합쳐라」를, detekt 가 「120자를
            // 넘지 마라」를 요구해 서로 배타가 된다(저장소 관례).
            override fun findIdByKey(projectKey: ProjectKey): UUID? {
                return if (projectKey.value == "ATLAS") projectId else null
            }

            override fun findKeyById(projectId: UUID): ProjectKey? = null
        }

    private val applicationService: WorkflowApplicationService = mockk()

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        capturedPermission = null
        capturedScope = null
        denyPermission = false
        every { applicationService.listWorkflowsForProject(projectId) } returns listOf(sampleWorkflow())
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(ProjectWorkflowController(applicationService, permissionResolver, projectLookupPort))
                .setControllerAdvice(WorkflowExceptionHandler())
                .build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(actorId.toString(), null, AuthorityUtils.NO_AUTHORITIES)
    }

    @Test
    fun `목록은 프로젝트 스코프 조회를 탄다`() {
        mockMvc
            .perform(get("/api/v1/projects/ATLAS/workflows").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].key").value("owned-flow"))
    }

    @Test
    fun `판정에 넘기는 스코프는 그 프로젝트다`() {
        mockMvc.perform(get("/api/v1/projects/ATLAS/workflows"))

        assertThat(capturedScope).isEqualTo(WorkflowScope.Project("ATLAS"))
        assertThat(capturedPermission).isEqualTo(WorkflowDefinitionPermission.UPDATE)
    }

    @Test
    fun `권한이 없으면 403 이다`() {
        denyPermission = true

        mockMvc
            .perform(get("/api/v1/projects/ATLAS/workflows"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `없는 프로젝트는 404 다`() {
        mockMvc
            .perform(get("/api/v1/projects/NOPE/workflows"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `권한을 프로젝트 실재 확인보다 먼저 본다`() {
        // ★없는 프로젝트에 대해서도 권한이 **먼저** 평가돼야 한다. 순서가 뒤집히면 권한 없는
        //  사용자가 있는 키엔 403, 없는 키엔 404 를 받아 프로젝트를 열거할 수 있다.
        denyPermission = true

        mockMvc
            .perform(get("/api/v1/projects/NOPE/workflows"))
            .andExpect(status().isForbidden)

        assertThat(capturedScope).isEqualTo(WorkflowScope.Project("NOPE"))
    }

    private fun sampleWorkflow(): Workflow =
        Workflow.of(
            key = "owned-flow",
            name = "소유 워크플로우",
            description = "",
            states = listOf(WorkflowState(key = "todo", name = "할 일", category = StateCategory.TODO, displayOrder = 0)),
            transitions = emptyList(),
        )
}
