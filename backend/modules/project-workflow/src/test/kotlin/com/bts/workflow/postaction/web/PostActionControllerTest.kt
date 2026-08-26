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
 * - 권한 없음 → 403 (GET/POST/PUT/DELETE 4개 전부 · Guard 가 service 호출 전에 동작)
 * - 전환 미존재 → 404
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

    @Test
    @WithMockUser(username = "22222222-2222-2222-2222-222222222222")
    fun `권한 없음 - PUT 도 403`() {
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
            put("$basePath/$postActionId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody)),
        )
            .andExpect(status().isForbidden)

        // 403 만 재면 「가드는 없는데 우연히 403」과 구별되지 않는다. 수정 대상 조회 이전에 끊겼음을 못박는다.
        verify(exactly = 0) { service.update(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    @WithMockUser(username = "22222222-2222-2222-2222-222222222222")
    fun `권한 없음 - DELETE 도 403`() {
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

        mockMvc.perform(delete("$basePath/$postActionId"))
            .andExpect(status().isForbidden)

        verify(exactly = 0) { service.delete(any(), any(), any()) }
    }

    // ── 404 전환 미존재 ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `전환 미존재 - GET 404`() {
        justRun {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
        every {
            service.listForTransition(workflowKey, transitionKey)
        } throws PostActionNotFoundException("전환 미존재")

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

    // ── 프레임워크 예외 3종 — 도메인 봉투 밖으로 새지 않는다 (부채 1 · FR-10) ────────
    //
    // 상태 코드만 재면 공허하다. 스프링 기본 응답도 같은 상태를 내면서 본문은 `timestamp`/`path`/
    // `status` 형식이라, 화면이 `error.code` 로 분기하려는 자리가 비고 「알 수 없는 오류」로 뭉개진다.
    // 그래서 셋 다 **상태 + `error.code` 값 + `error.message` 값**을 함께 단언한다.
    //
    // ★`error.message` 를 「존재(isString)」로만 재던 자리를 **정확 일치 + 누수 카나리** 로 바꿨다
    // (부채 136). 존재만 재면 누가 `ex.message` 를 그 자리에 꽂아도 여전히 통과한다 — 그리고 그
    // 메시지에는 파라미터 이름·타입·요청 본문 조각이 들어 있어 응답이 곧 내부 구조의 설명서가 된다.
    // 두 판정은 서로를 대신하지 못한다. 정확 일치는 `message` 칸이 바뀌는 것을 잡고, 카나리는
    // **다른 칸으로 새는 것**까지 잡는다.
    //
    // 코드·메시지 문자열은 리터럴로 적는다. 형제 [com.bts.workflow.validator.web.ValidatorControllerTest]
    // 가 **같은 리터럴**을 적고 있고, 「두 표면이 같은 값을 쓴다」는 계약을 지키는 것은 그 대칭뿐이다
    // — 양쪽이 구현 상수를 import 하면 상수 한 벌이 갈려도 둘 다 초록이 된다.
    //
    // 400 두 건에 권한 stub 이 없는 것은 실수가 아니다. 두 예외는 **인자 해석 단계**에서 나므로
    // 컨트롤러 본문(=권한 가드)보다 앞선다. stub 을 깔면 검사되지 않는 죽은 셋업이 된다.

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `비-UUID id 는 400 과 error 봉투로 나가고 요청 값이 응답에 실리지 않는다`() {
        // 경로 변수에 카나리를 심는다. `MethodArgumentTypeMismatchException` 메시지에는 이 값이
        // 그대로 들어가므로, 그 메시지를 응답에 실으면 카나리가 응답에 나타난다.
        val body =
            mockMvc.perform(delete("$basePath/$LEAK_CANARY-not-a-uuid"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("WORKFLOW_INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("요청 경로 또는 파라미터 형식이 올바르지 않습니다."))
                .andReturn()
                .response
                .contentAsString

        assertThat(body).doesNotContain(LEAK_CANARY)

        verify(exactly = 0) { service.delete(any(), any(), any()) }
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `깨진 JSON 본문은 400 과 error 봉투로 나가고 본문 조각이 응답에 실리지 않는다`() {
        // 문자열이 닫히지 않은 JSON. 카나리는 그 열린 문자열 안에 있다.
        val body =
            mockMvc.perform(
                post(basePath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\": \"$LEAK_CANARY"),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("WORKFLOW_INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("요청 본문을 읽을 수 없습니다."))
                .andReturn()
                .response
                .contentAsString

        assertThat(body).doesNotContain(LEAK_CANARY)

        verify(exactly = 0) { service.create(any(), any(), any(), any(), any()) }
    }

    /**
     * 미인증 — `@WithMockUser` 를 **일부러 붙이지 않는다**.
     *
     * 이 401 은 Spring Security 필터가 아니라 `CurrentActor.current()` 가 컨트롤러 실행 중에 던진다
     * (`ManageSchemeGuard.requireManageScheme` → `CurrentActor`). 필터 체인에서 났다면
     * DispatcherServlet 앞이라 advice 가 잡을 수 없다.
     *
     * 이 건에는 카나리를 심을 자리가 없다 — 예외를 우리 코드가 던지므로 요청 값이 메시지에 들어가지
     * 않는다. 그래서 메시지 정확 일치 하나로 잠근다.
     */
    @Test
    fun `미인증 요청은 401 과 error 봉투로 나간다`() {
        mockMvc.perform(get(basePath))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_UNAUTHENTICATED"))
            .andExpect(jsonPath("$.error.message").value("인증이 필요합니다. 다시 로그인해 주세요."))

        verify(exactly = 0) { service.listForTransition(any(), any()) }
    }

    private companion object {
        /**
         * 누수 카나리 — **요청에 심어 응답에 없음을 재는** 고유 토큰.
         *
         * 형제 [com.bts.workflow.validator.web.ValidatorControllerTest] 가 **같은 리터럴**을 적는다.
         * 그것이 「두 표면이 같은 값을 쓴다」를 지키는 유일한 장치다 — 상수를 한 곳에 두고 양쪽이
         * import 하면 그 한 벌이 갈려도 둘 다 초록이 된다.
         *
         * ★ 「허용된 메시지 집합」을 여기 들고 오지 않는다. 집합을 참조하면 그것이 또 하나의 목록이
         * 되고 두 목록은 서로를 검사하지 않는다. 요청에 심은 토큰이 응답에 **없음**만 재면 집합을
         * 알 필요가 없다.
         */
        const val LEAK_CANARY = "canary9f3a"
    }
}
