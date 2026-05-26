// IssueController PATCH + POST transition MockMvc 슬라이스 테스트 — task-15 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * IssueController PATCH /api/v1/issues/{key} 및 POST /api/v1/issues/{key}/transition MockMvc 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 최소 컨텍스트로 구성한다.
 * [IssueApplicationService] 는 MockK stub 으로 대체한다.
 * [IssueExceptionHandler] 를 컨텍스트에 등록하여 예외 → ProblemDetail 변환을 검증한다.
 *
 * 테스트 케이스 4건.
 * - U-1. PATCH /{key} 정상 → 200 + IssueResponse
 * - U-2. PATCH /{key} version 충돌 → 409 + ProblemDetail (VERSION_CONFLICT)
 * - T-1. POST /{key}/transition 정상 → 200 + IssueResponse
 * - T-2. POST /{key}/transition 전이 거부 → 409 + ProblemDetail (TRANSITION_NOT_ALLOWED)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerUpdateTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueControllerUpdateTest {

    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueController], [IssueExceptionHandler], MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun issueController(service: IssueApplicationService): IssueController =
            IssueController(service)

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val fixedNow: Instant = Instant.parse("2026-05-26T00:00:00Z")
    private val issueKey = IssueKey("ATLAS-1")
    private val actorId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val issueId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private val sampleResponse = IssueResponse(
        key = "ATLAS-1",
        id = issueId,
        projectKey = "ATLAS",
        summary = "수정된 요약",
        currentStateKey = "OPEN",
        reporterId = actorId.value,
        version = 2L,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    // ── U-1: PATCH /{key} 정상 → 200 + IssueResponse ─────────────────────────

    @Test
    fun `PATCH 이슈 수정 — 정상 요청이면 200 + IssueResponse`() {
        every {
            issueApplicationService.updateIssue(any(), IssueKey("ATLAS-1"), any())
        } returns sampleResponse

        val body = mapOf(
            "summary" to "수정된 요약",
            "expectedVersion" to 1,
        )

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value("ATLAS-1"))
            .andExpect(jsonPath("$.data.summary").value("수정된 요약"))
            .andExpect(jsonPath("$.data.version").value(2))
    }

    // ── U-2: PATCH /{key} version 충돌 → 409 ProblemDetail ───────────────────

    @Test
    fun `PATCH 이슈 수정 — version 충돌이면 409 ProblemDetail VERSION_CONFLICT`() {
        every {
            issueApplicationService.updateIssue(any(), IssueKey("ATLAS-1"), any())
        } throws IssueVersionConflictException(issueKey, currentVersion = 5L)

        val body = mapOf(
            "summary" to "수정된 요약",
            "expectedVersion" to 1,
        )

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
    }

    // ── T-1: POST /{key}/transition 정상 → 200 + IssueResponse ───────────────

    @Test
    fun `POST transition 정상 요청이면 200 + IssueResponse`() {
        val transitionedResponse = sampleResponse.copy(currentStateKey = "IN_PROGRESS")

        every {
            issueApplicationService.transitionIssue(any(), IssueKey("ATLAS-1"), any())
        } returns transitionedResponse

        val body = mapOf(
            "toStatusKey" to "IN_PROGRESS",
            "expectedVersion" to 1,
        )

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value("ATLAS-1"))
            .andExpect(jsonPath("$.data.currentStateKey").value("IN_PROGRESS"))
    }

    // ── T-2: POST /{key}/transition 전이 거부 → 409 ProblemDetail ─────────────

    @Test
    fun `POST transition 전이 거부이면 409 ProblemDetail TRANSITION_NOT_ALLOWED`() {
        every {
            issueApplicationService.transitionIssue(any(), IssueKey("ATLAS-1"), any())
        } throws IssueTransitionNotAllowedException(
            issueKey = issueKey,
            fromStatus = "OPEN",
            toStatus = "DONE",
        )

        val body = mapOf(
            "toStatusKey" to "DONE",
            "expectedVersion" to 1,
        )

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.errorCode").value("TRANSITION_NOT_ALLOWED"))
    }
}
