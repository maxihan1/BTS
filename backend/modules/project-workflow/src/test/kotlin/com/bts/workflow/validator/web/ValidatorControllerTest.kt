// ValidatorController MockMvc 슬라이스 테스트 — 권한 Guard 선행(존재 probe 차단) + CRUD REST 계약

package com.bts.workflow.validator.web

import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.validator.ValidatorAdminService
import com.bts.workflow.validator.ValidatorNotFoundException
import com.bts.workflow.validator.ValidatorRow
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
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
 * ValidatorController MockMvc 슬라이스 테스트.
 *
 * 검증 범위.
 * - 4개 엔드포인트(GET/POST/PUT/DELETE) 가 권한 없으면 **리소스 조회 전에** 403 을 낸다.
 * - 실재하는 전환과 실재하지 않는 전환의 403 응답이 **구별되지 않는다** (존재 probe 차단).
 * - 403 본문에 actorId·permission·scope 가 실리지 않는다.
 * - POST 성공 201 + `{data:...}` 봉투 · DELETE 성공 204 무본문.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ValidatorControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ValidatorControllerTest {
    /** MockMvc 슬라이스용 최소 컨텍스트 — 컨트롤러 + 예외 핸들러 + mock 협력자. */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    open class TestMvcConfig {
        /** validator 관리 서비스 mock. */
        @Bean
        open fun validatorAdminService(): ValidatorAdminService = mockk()

        /** 권한 평가 포트 mock. */
        @Bean
        open fun permissionResolver(): WorkflowSchemePermissionResolver = mockk(relaxed = true)

        /**
         * 테스트 대상 컨트롤러.
         *
         * @param svc validator 관리 서비스 mock.
         * @param resolver 권한 평가 포트 mock.
         */
        @Bean
        open fun validatorController(
            svc: ValidatorAdminService,
            resolver: WorkflowSchemePermissionResolver,
        ): ValidatorController = ValidatorController(svc, resolver)

        /** validator 패키지 전용 예외 핸들러. */
        @Bean
        open fun validatorExceptionHandler(): ValidatorExceptionHandler = ValidatorExceptionHandler()
    }

    @Autowired
    private lateinit var wac: WebApplicationContext

    @Autowired
    private lateinit var service: ValidatorAdminService

    @Autowired
    private lateinit var permissionResolver: WorkflowSchemePermissionResolver

    private lateinit var mockMvc: MockMvc

    private val mapper = ObjectMapper().registerKotlinModule()

    private val workflowKey = "software-default"
    private val existingTransitionKey = "in_progress__done"
    private val missingTransitionKey = "in_progress__nowhere"
    private val validatorId: UUID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001")
    private val transitionId: UUID = UUID.fromString("ffffffff-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
        clearMocks(service, permissionResolver)
    }

    // ── 권한 Guard — 4개 엔드포인트 전부 403 (리소스 조회 전) ─────────────────────

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `실재하는 전환에 권한 없이 GET 하면 403`() {
        denyPermission()
        stubTransitionExistence()

        mockMvc.perform(get(basePath(existingTransitionKey)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_SCHEME_ACCESS_DENIED"))

        verify(exactly = 0) { service.listForTransition(any(), any()) }
    }

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `실재하지 않는 전환에 권한 없이 GET 해도 같은 403 본문`() {
        denyPermission()
        stubTransitionExistence()

        val onExisting = mockMvc.perform(get(basePath(existingTransitionKey))).andReturn().response
        val onMissing = mockMvc.perform(get(basePath(missingTransitionKey))).andReturn().response

        // 비공개는 한 케이스로 증명되지 않는다 — 두 응답이 status·본문 모두 구별 불가여야 성립한다.
        // 권한 검사가 전환 해석 뒤로 밀리면 미실재 쪽만 404 가 되어 이 대조가 깨진다.
        assertThat(onMissing.status).isEqualTo(HttpStatus.FORBIDDEN.value())
        assertThat(onMissing.status).isEqualTo(onExisting.status)
        assertThat(onMissing.contentAsString).isEqualTo(onExisting.contentAsString)
        verify(exactly = 0) { service.listForTransition(any(), any()) }
    }

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `POST 는 권한 없으면 403`() {
        denyPermission()

        mockMvc.perform(
            post(basePath(existingTransitionKey))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody())),
        )
            .andExpect(status().isForbidden)

        verify(exactly = 0) { service.create(any(), any(), any(), any(), any()) }
    }

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `PUT 은 권한 없으면 403`() {
        denyPermission()

        mockMvc.perform(
            put("${basePath(existingTransitionKey)}/$validatorId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody())),
        )
            .andExpect(status().isForbidden)

        verify(exactly = 0) { service.update(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `DELETE 는 권한 없으면 403`() {
        denyPermission()

        mockMvc.perform(delete("${basePath(existingTransitionKey)}/$validatorId"))
            .andExpect(status().isForbidden)

        verify(exactly = 0) { service.delete(any(), any(), any()) }
    }

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `403 본문에 actorId · permission · scope 가 없다`() {
        denyPermission()

        val body =
            mockMvc.perform(get(basePath(existingTransitionKey)))
                .andExpect(status().isForbidden)
                .andReturn()
                .response
                .contentAsString

        assertThat(body).contains("WORKFLOW_SCHEME_ACCESS_DENIED")
        assertThat(body).doesNotContain(DENIED_ACTOR)
        assertThat(body).doesNotContain("MANAGE_SCHEME")
        assertThat(body).doesNotContain("Global")
    }

    // ── 성공 경로 ─────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `POST 성공은 201 과 DataEnvelope 를 준다`() {
        allowPermission()
        every {
            service.create(workflowKey, existingTransitionKey, "RequiredField", any(), 0)
        } returns sampleRow()

        mockMvc.perform(
            post(basePath(existingTransitionKey))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody())),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(validatorId.toString()))
            .andExpect(jsonPath("$.data.type").value("RequiredField"))
            .andExpect(jsonPath("$.data.config.field").value("resolution"))
            .andExpect(jsonPath("$.data.displayOrder").value(0))
            .andExpect(jsonPath("$.data.transitionId").doesNotExist())
    }

    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `DELETE 성공은 204 무본문`() {
        allowPermission()
        justRun { service.delete(workflowKey, existingTransitionKey, validatorId) }

        val response =
            mockMvc.perform(delete("${basePath(existingTransitionKey)}/$validatorId"))
                .andExpect(status().isNoContent)
                .andReturn()
                .response

        assertThat(response.contentAsString).isEmpty()
        verify(exactly = 1) { service.delete(workflowKey, existingTransitionKey, validatorId) }
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private fun basePath(transitionKey: String): String =
        "/api/v1/workflows/$workflowKey/transitions/$transitionKey/validators"

    private fun requestBody(): Map<String, Any> =
        mapOf(
            "type" to "RequiredField",
            "config" to mapOf("field" to "resolution"),
            "displayOrder" to 0,
        )

    private fun sampleRow(): ValidatorRow =
        ValidatorRow(
            id = validatorId,
            transitionId = transitionId,
            type = "RequiredField",
            config = mapOf("field" to "resolution"),
            displayOrder = 0,
        )

    private fun denyPermission() {
        every {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        } throws
            WorkflowSchemeAccessDeniedException(
                actorId = UUID.fromString(DENIED_ACTOR),
                permission = WorkflowSchemePermission.MANAGE_SCHEME,
                scope = WorkflowSchemeScope.Global,
            )
    }

    private fun allowPermission() {
        justRun {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
    }

    /**
     * 전환 실재 여부를 서비스 계층에 심는다 — 실재 키는 목록을 주고, 미실재 키는 404 를 던진다.
     *
     * 권한 검사가 먼저 돌면 이 stub 은 한 번도 불리지 않고 두 응답이 같아진다.
     */
    private fun stubTransitionExistence() {
        every { service.listForTransition(workflowKey, existingTransitionKey) } returns listOf(sampleRow())
        every { service.listForTransition(workflowKey, missingTransitionKey) } throws
            ValidatorNotFoundException("전환 미존재 — transitionKey='$missingTransitionKey'")
    }

    private companion object {
        const val DENIED_ACTOR = "22222222-2222-2222-2222-222222222222"
        const val ALLOWED_ACTOR = "11111111-1111-1111-1111-111111111111"
    }
}
