// IssueController PATCH + POST transition MockMvc 슬라이스 테스트 — task-15 RED + task-8 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import com.bts.issue.application.UpdateIssueRequest as AppUpdateIssueRequest

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

    private val fixedNow: Instant = Instant.parse("2026-05-26T00:00:00Z")
    private val issueKey = IssueKey("ATLAS-1")
    private val actorId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val issueId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private val sampleResponse =
        IssueResponse(
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

        val body =
            mapOf(
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

        val body =
            mapOf(
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

        val body =
            mapOf(
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
        } throws
            IssueTransitionNotAllowedException(
                issueKey = issueKey,
                fromStatus = "OPEN",
                toStatus = "DONE",
            )

        val body =
            mapOf(
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

    // ── T8-1: PATCH summary=null → 200, service 에 AppUpdateIssueRequest.summary==null 전달 ──

    /**
     * T8-1. PATCH body 에 summary=null 을 명시적으로 포함한 경우.
     *
     * controller 가 `?: ""` 없이 null 을 그대로 application 계층에 전달해야 한다.
     * service mock 은 "변경 없음" 동작을 시뮬레이션 — 원래 summary "원래" 그대로 반환.
     */
    @Test
    fun `PATCH summary null 명시 — 200 OK, service 에 summary null 전달`() {
        val originalResponse =
            IssueResponse(
                key = "ATLAS-1",
                id = issueId,
                projectKey = "ATLAS",
                summary = "원래",
                currentStateKey = "OPEN",
                reporterId = actorId.value,
                version = 1L,
                createdAt = fixedNow,
                updatedAt = fixedNow,
            )

        val capturedRequest = slot<AppUpdateIssueRequest>()
        every {
            issueApplicationService.updateIssue(any(), IssueKey("ATLAS-1"), capture(capturedRequest))
        } returns originalResponse

        val body = """{"summary": null, "expectedVersion": 1}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.summary").value("원래"))

        assert(capturedRequest.captured.summary == null) {
            "controller 가 summary null 을 그대로 전달해야 하지만 '${capturedRequest.captured.summary}' 를 전달함"
        }
    }

    // ── T8-2: PATCH summary="새 제목" → 200, service 에 summary=="새 제목" 전달 ──

    /**
     * T8-2. PATCH body 에 summary 값이 있는 경우.
     *
     * controller 가 non-null summary 를 그대로 application 계층에 전달해야 한다.
     */
    @Test
    fun `PATCH summary 비어 있지 않음 — 200 OK, service 에 새 summary 전달`() {
        val updatedResponse =
            IssueResponse(
                key = "ATLAS-1",
                id = issueId,
                projectKey = "ATLAS",
                summary = "새 제목",
                currentStateKey = "OPEN",
                reporterId = actorId.value,
                version = 2L,
                createdAt = fixedNow,
                updatedAt = fixedNow,
            )

        val capturedRequest = slot<AppUpdateIssueRequest>()
        every {
            issueApplicationService.updateIssue(any(), IssueKey("ATLAS-1"), capture(capturedRequest))
        } returns updatedResponse

        val body = """{"summary": "새 제목", "expectedVersion": 1}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.summary").value("새 제목"))

        assert(capturedRequest.captured.summary == "새 제목") {
            "controller 가 summary '새 제목' 을 전달해야 하지만 '${capturedRequest.captured.summary}' 를 전달함"
        }
    }

    // ── T8-3: PATCH body 에 summary 필드 미포함 → T8-1 과 동일, summary null 전달 ──

    /**
     * T8-3. PATCH body 에 summary 필드 자체를 포함하지 않은 경우 (JSON Merge Patch 시맨틱).
     *
     * RFC 7396 에 따라 필드 미포함 = null 과 동등하게 처리되어야 한다.
     * controller 가 summary null 을 그대로 application 계층에 전달해야 한다.
     */
    @Test
    fun `PATCH summary 필드 미포함 — 200 OK, service 에 summary null 전달`() {
        val originalResponse =
            IssueResponse(
                key = "ATLAS-1",
                id = issueId,
                projectKey = "ATLAS",
                summary = "원래",
                currentStateKey = "OPEN",
                reporterId = actorId.value,
                version = 1L,
                createdAt = fixedNow,
                updatedAt = fixedNow,
            )

        val capturedRequest = slot<AppUpdateIssueRequest>()
        every {
            issueApplicationService.updateIssue(any(), IssueKey("ATLAS-1"), capture(capturedRequest))
        } returns originalResponse

        val body = """{"expectedVersion": 1}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.summary").value("원래"))

        assert(capturedRequest.captured.summary == null) {
            "controller 가 summary null 을 그대로 전달해야 하지만 '${capturedRequest.captured.summary}' 를 전달함"
        }
    }

    // ── T8-4: PATCH summary="" 빈 문자열 → 400 VALIDATION_FAILED (F-1 가드) ──

    /**
     * T8-4. PATCH body 에 summary="" 명시적 빈 문자열을 전송한 경우.
     *
     * PR #23 adversarial F-1 — `?: ""` 제거 후 빈 문자열 명시 입력 가드가 부재하면
     * production 시점에 사용자가 모든 이슈를 빈 제목으로 만들 수 있는 회귀 위험.
     * Bean Validation `@NotBlank` 가 null 통과 + 빈 문자열/공백 거부 시맨틱으로
     * RFC 7396 partial 시맨틱과 양립. 400 + VALIDATION_FAILED errorCode 응답.
     */
    @Test
    fun `PATCH summary 빈 문자열 — 400 VALIDATION_FAILED (F-1 가드)`() {
        val body = """{"summary": "", "expectedVersion": 1}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    // ── T8-5: PATCH summary="   " 공백만 → 400 VALIDATION_FAILED (F-1 가드) ──

    /**
     * T8-5. PATCH body 에 summary="   " 공백만으로 구성된 입력을 전송한 경우.
     *
     * `@NotBlank` 가 공백만으로 구성된 문자열도 거부. 빈 문자열과 동일 시맨틱.
     */
    @Test
    fun `PATCH summary 공백만 — 400 VALIDATION_FAILED (F-1 가드)`() {
        val body = """{"summary": "   ", "expectedVersion": 1}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }
}
