// PostActionController MockMvc 슬라이스 테스트 — GET/POST/PUT/DELETE + 권한 Guard 검증

package com.bts.workflow.postaction.web

import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.postaction.PostActionAdminService
import com.bts.workflow.postaction.PostActionNotFoundException
import com.bts.workflow.postaction.PostActionRow
import com.bts.workflow.postaction.PostActionValidationException
import com.bts.workflow.scheme.web.WorkflowSchemeExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * PostActionController MockMvc 슬라이스 테스트.
 *
 * 검증 범위.
 * - GET    /api/v1/workflows/{wk}/transitions/{tk}/post-actions → 200 + 목록
 * - POST   .../post-actions → 201 + 생성된 행
 * - PUT    .../post-actions/{id} → 200 + 수정된 행
 * - DELETE .../post-actions/{id} → 204
 * - 권한 없음 → 403 (Guard 가 service 호출 전에 동작)
 * - 전이 미존재 → 404
 * - 검증 실패 → 400
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [PostActionControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class PostActionControllerTest {
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun postActionAdminService(): PostActionAdminService = mockk()

        @Bean
        open fun permissionResolver(): WorkflowSchemePermissionResolver = mockk(relaxed = true)

        @Bean
        open fun postActionController(
            svc: PostActionAdminService,
            resolver: WorkflowSchemePermissionResolver,
        ): PostActionController = PostActionController(svc, resolver)

        @Bean
        open fun postActionExceptionHandler(): PostActionExceptionHandler = PostActionExceptionHandler()

        // WorkflowSchemeExceptionHandler 는 WorkflowSchemeAccessDeniedException 처리에 필요
        @Bean
        open fun workflowSchemeExceptionHandler(): WorkflowSchemeExceptionHandler = WorkflowSchemeExceptionHandler()
    }

    @Autowired
    private lateinit var wac: WebApplicationContext

    @Autowired
    private lateinit var service: PostActionAdminService

    @Autowired
    private lateinit var permissionResolver: WorkflowSchemePermissionResolver

    private lateinit var mockMvc: MockMvc

    private val mapper = ObjectMapper().registerKotlinModule()

    private val workflowKey = "software-default"
    private val transitionKey = "open__in_progress"
    private val postActionId: UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001")
    private val transitionId: UUID = UUID.fromString("dddddddd-0000-0000-0000-000000000001")

    private val basePath = "/api/v1/workflows/$workflowKey/transitions/$transitionKey/post-actions"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
        clearMocks(service, permissionResolver)
    }

    // ── GET ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `GET post-actions - 200 목록 반환`() {
        val rows =
            listOf(
                PostActionRow(
                    id = postActionId,
                    transitionId = transitionId,
                    type = "CALL_WEBHOOK",
                    config = mapOf("url" to "https://x.com", "method" to "POST"),
                    displayOrder = 0,
                ),
            )
        justRun {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
        every { service.listForTransition(workflowKey, transitionKey) } returns rows

        mockMvc.perform(get(basePath))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].id").value(postActionId.toString()))
            .andExpect(jsonPath("$.data[0].type").value("CALL_WEBHOOK"))
    }

    // ── POST ──────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `POST post-actions - 201 Created`() {
        val requestBody =
            mapOf(
                "type" to "CALL_WEBHOOK",
                "config" to mapOf("url" to "https://hook.example.com", "method" to "POST"),
                "displayOrder" to 0,
            )
        val created =
            PostActionRow(
                id = postActionId,
                transitionId = transitionId,
                type = "CALL_WEBHOOK",
                config = mapOf("url" to "https://hook.example.com", "method" to "POST"),
                displayOrder = 0,
            )
        justRun {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
        every {
            service.create(workflowKey, transitionKey, "CALL_WEBHOOK", any(), 0)
        } returns created

        mockMvc.perform(
            post(basePath)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(postActionId.toString()))
            .andExpect(jsonPath("$.data.type").value("CALL_WEBHOOK"))
    }

    // ── PUT ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `PUT post-actions - 200 수정된 행 반환`() {
        val requestBody =
            mapOf(
                "type" to "CALL_WEBHOOK",
                "config" to mapOf("url" to "https://updated.example.com", "method" to "PUT"),
                "displayOrder" to 10,
            )
        val updated =
            PostActionRow(
                id = postActionId,
                transitionId = transitionId,
                type = "CALL_WEBHOOK",
                config = mapOf("url" to "https://updated.example.com", "method" to "PUT"),
                displayOrder = 10,
            )
        justRun {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
        every {
            service.update(workflowKey, transitionKey, postActionId, "CALL_WEBHOOK", any(), 10)
        } returns updated

        mockMvc.perform(
            put("$basePath/$postActionId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(postActionId.toString()))
            .andExpect(jsonPath("$.data.displayOrder").value(10))
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `DELETE post-actions - 204 No Content`() {
        justRun {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
        justRun { service.delete(workflowKey, transitionKey, postActionId) }

        mockMvc.perform(delete("$basePath/$postActionId"))
            .andExpect(status().isNoContent)
    }

    // ── 권한 Guard — 403 (리소스 조회 전) ───────────────────────────────────────

    @Test
    @WithMockUser(username = "22222222-2222-2222-2222-222222222222")
    fun `권한 없음 - POST 진입 직후 403 (service 미호출)`() {
        every {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        } throws
            WorkflowSchemeAccessDeniedException(
                actorId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                permission = WorkflowSchemePermission.MANAGE_SCHEME,
                scope = WorkflowSchemeScope.Global,
            )

        val requestBody = mapOf("type" to "CALL_WEBHOOK", "config" to emptyMap<String, Any>(), "displayOrder" to 0)

        mockMvc.perform(
            post(basePath)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody)),
        )
            .andExpect(status().isForbidden)

        verify(exactly = 0) { service.create(any(), any(), any(), any(), any()) }
    }

    @Test
    @WithMockUser(username = "22222222-2222-2222-2222-222222222222")
    fun `권한 없음 - GET 도 403`() {
        every {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        } throws
            WorkflowSchemeAccessDeniedException(
                actorId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                permission = WorkflowSchemePermission.MANAGE_SCHEME,
                scope = WorkflowSchemeScope.Global,
            )

        mockMvc.perform(get(basePath))
            .andExpect(status().isForbidden)

        verify(exactly = 0) { service.listForTransition(any(), any()) }
    }

    // ── 404 전이 미존재 ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `전이 미존재 - GET 404`() {
        justRun {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
        every {
            service.listForTransition(workflowKey, transitionKey)
        } throws PostActionNotFoundException("전이 미존재")

        mockMvc.perform(get(basePath))
            .andExpect(status().isNotFound)
    }

    // ── 400 검증 실패 ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `검증 실패 - POST 400`() {
        justRun {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
        every {
            service.create(any(), any(), any(), any(), any())
        } throws PostActionValidationException("미지원 type")

        val requestBody = mapOf("type" to "INVALID", "config" to emptyMap<String, Any>(), "displayOrder" to 0)

        mockMvc.perform(
            post(basePath)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody)),
        )
            .andExpect(status().isBadRequest)
    }
}
