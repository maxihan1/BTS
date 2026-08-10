// PATCH /api/v1/issues/{key}/components MockMvc 슬라이스 테스트 — FR-CM-02 task-5 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.AppChangeComponentsRequest
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.IssueComponentNotFoundException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueVersionConflictException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * IssueController PATCH /api/v1/issues/{key}/components MockMvc 슬라이스 테스트.
 *
 * [IssueApplicationService] 는 MockK stub 으로 대체하고 [IssueExceptionHandler] 를 등록하여
 * 예외 → HTTP 상태 변환을 검증한다.
 *
 * 테스트 케이스.
 * - CC-1. 정상 → 200 + componentIds 포함
 * - CC-2. IssueComponentNotFoundException → 422 + COMPONENT_NOT_FOUND
 * - CC-3. IssueVersionConflictException → 409 + VERSION_CONFLICT
 * - CC-4. IssueNotFoundException → 404 + ISSUE_NOT_FOUND
 * - CC-5. 잘못된 UUID 형식 → 400 + VALIDATION_FAILED
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueComponentsControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueComponentsControllerTest {
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun issueController(service: IssueApplicationService): IssueController = IssueController(service)

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val fixedNow: Instant = Instant.parse("2026-06-05T00:00:00Z")
    private val componentId1 = UUID.fromString("00000000-0000-4000-8000-000000000011")
    private val componentId2 = UUID.fromString("00000000-0000-4000-8000-000000000012")
    private val issueKey = IssueKey("ATLAS-1")

    private val successResponse =
        IssueResponse(
            key = "ATLAS-1",
            id = UUID.fromString("00000000-0000-4000-8000-000000000001"),
            projectKey = "ATLAS",
            summary = "테스트 이슈",
            currentStateKey = "open",
            reporterId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            version = 2L,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = 1L,
            typeKey = "task",
            typeName = "Task",
            componentIds = listOf(componentId1, componentId2),
        )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        clearMocks(issueApplicationService, answers = false)
        every { issueApplicationService.changeComponents(any(), issueKey, any()) } returns successResponse
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── CC-1: 정상 → 200 + componentIds 포함 ────────────────────────────────────

    @Test
    fun `PATCH components — 정상이면 200 + componentIds 포함`() {
        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "componentIds" to listOf(componentId1.toString(), componentId2.toString()),
                            "expectedVersion" to 1L,
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value("ATLAS-1"))
            .andExpect(jsonPath("$.data.componentIds[0]").value(componentId1.toString()))
            .andExpect(jsonPath("$.data.componentIds[1]").value(componentId2.toString()))
    }

    // ── CC-6: REST 경로가 배정 알림을 켠다 (fail-safe 게이트의 필수 짝) ──────────────

    /**
     * 애플리케이션 계층 `AppChangeComponentsRequest.notifyAssignment` 는 **기본 false**(fail-safe)다.
     * 새 생산자가 알림을 조용히 켜지 못하게 하는 것이 목적이라 그 기본값은 옳지만,
     * **컨트롤러가 true 를 넘기는지 검증하는 짝이 없으면 게이트가 통째로 공허해진다** —
     * 서비스 테스트는 전부 `notifyAssignment` 를 직접 넣어 호출하므로 「실제 프로덕션 경로가
     * 그 값을 넘기는가」는 아무도 안 본다. 클론 경로에서 같은 이유로 짝을 세운 선례가 있다
     * (`IssueControllerCloneTest` CL-8).
     *
     * 기본값 인자로 추가된 필드라 **기존 호출부가 조용히 컴파일된다** — 컴파일러가 잡아 주지 않는다.
     * 이 단언이 그 자리를 메운다.
     */
    @Test
    fun `PATCH components — REST 경로는 notifyAssignment=true 를 서비스에 전달한다`() {
        val reqSlot: CapturingSlot<AppChangeComponentsRequest> = slot()
        every {
            issueApplicationService.changeComponents(any(), issueKey, capture(reqSlot))
        } returns successResponse

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "componentIds" to listOf(componentId1.toString()),
                            "expectedVersion" to 1L,
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)

        org.junit.jupiter.api.Assertions.assertEquals(true, reqSlot.captured.notifyAssignment)
    }

    // ── CC-2: IssueComponentNotFoundException → 422 + COMPONENT_NOT_FOUND ─────────

    @Test
    fun `PATCH components — IssueComponentNotFoundException 이면 422 COMPONENT_NOT_FOUND`() {
        every {
            issueApplicationService.changeComponents(any(), issueKey, any())
        } throws IssueComponentNotFoundException(componentId1)

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to listOf(componentId1.toString()), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("COMPONENT_NOT_FOUND"))
    }

    // ── CC-3: IssueVersionConflictException → 409 ────────────────────────────

    @Test
    fun `PATCH components — IssueVersionConflictException 이면 409 VERSION_CONFLICT`() {
        every {
            issueApplicationService.changeComponents(any(), issueKey, any())
        } throws IssueVersionConflictException(issueKey, 1L)

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to emptyList<String>(), "expectedVersion" to 99L),
                    ),
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
    }

    // ── CC-4: IssueNotFoundException → 404 ───────────────────────────────────

    @Test
    fun `PATCH components — IssueNotFoundException 이면 404 ISSUE_NOT_FOUND`() {
        every {
            issueApplicationService.changeComponents(any(), issueKey, any())
        } throws IssueNotFoundException(issueKey)

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to emptyList<String>(), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── CC-5: 잘못된 요청(expectedVersion 누락) → 400 ─────────────────────────

    @Test
    fun `PATCH components — expectedVersion 누락이면 400 VALIDATION_FAILED`() {
        val body = mapOf("componentIds" to listOf(componentId1.toString()))
        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }
}
