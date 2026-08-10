// IssueController PATCH + POST transition MockMvc 슬라이스 테스트 — task-15 RED + task-8 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.type.domain.IssueTypeNotFoundException
import com.bts.shared.issue.IssueTypeId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
            currentStateKey = "open",
            reporterId = actorId.value,
            version = 2L,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
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
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
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
                fromStatus = "open",
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
                currentStateKey = "open",
                reporterId = actorId.value,
                version = 1L,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = 3L,
                typeKey = "task",
                typeName = "Task",
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
                currentStateKey = "open",
                reporterId = actorId.value,
                version = 2L,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = 3L,
                typeKey = "task",
                typeName = "Task",
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
                currentStateKey = "open",
                reporterId = actorId.value,
                version = 1L,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = 3L,
                typeKey = "task",
                typeName = "Task",
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

    // ── D6-1: PATCH typeId 포함 → 200 + 변경된 타입 반영 ─────────────────────

    /**
     * D6-1. PATCH body 에 typeId 를 포함한 경우 200 OK 응답과 함께 변경된 타입이 반영된다.
     *
     * controller 가 typeId 를 IssueTypeId VO 로 변환하여 AppUpdateIssueRequest 에 전달하고,
     * service 가 반환한 IssueResponse 의 typeId/typeKey/typeName 이 응답 body 에 포함된다.
     */
    @Test
    fun `PATCH typeId 포함 — 200 OK + 변경된 타입 응답`() {
        val responseWithNewType =
            sampleResponse.copy(
                typeId = 5L,
                typeKey = "bug",
                typeName = "Bug",
            )

        val capturedRequest = slot<AppUpdateIssueRequest>()
        every {
            issueApplicationService.updateIssue(any(), IssueKey("ATLAS-1"), capture(capturedRequest))
        } returns responseWithNewType

        val body = """{"typeId": 5, "expectedVersion": 1}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.typeId").value(5))
            .andExpect(jsonPath("$.data.typeKey").value("bug"))
            .andExpect(jsonPath("$.data.typeName").value("Bug"))

        assert(capturedRequest.captured.typeId == IssueTypeId(5L)) {
            "controller 가 typeId=IssueTypeId(5) 를 전달해야 하지만 '${capturedRequest.captured.typeId}' 를 전달함"
        }
    }

    // ── D6-2: PATCH 음수 typeId → 400 VALIDATION_FAILED ──────────────────────

    /**
     * D6-2. PATCH body 에 음수 typeId 를 전송한 경우 Bean Validation 이 거부한다.
     *
     * `@Positive` 제약으로 0 이하 값은 400 + VALIDATION_FAILED errorCode 응답.
     */
    @Test
    fun `PATCH 음수 typeId — 400 VALIDATION_FAILED`() {
        val body = """{"typeId": -1, "expectedVersion": 1}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    // ── D6-3: PATCH 존재하지 않는 typeId → 404 ISSUE_TYPE_NOT_FOUND ──────────

    /**
     * D6-3. service 가 IssueTypeNotFoundException 을 throw 한 경우 404 + RFC 7807 ProblemDetail.
     *
     * IssueExceptionHandler 에 IssueTypeNotFoundException 핸들러가 없으면 500 fallback 으로 떨어지므로
     * 핸들러 추가가 필수다. errorCode 는 ISSUE_TYPE_NOT_FOUND.
     */
    @Test
    fun `PATCH 존재하지 않는 typeId — 404 ProblemDetail ISSUE_TYPE_NOT_FOUND`() {
        every {
            issueApplicationService.updateIssue(any(), IssueKey("ATLAS-1"), any())
        } throws IssueTypeNotFoundException(IssueTypeId(999L))

        val body = """{"typeId": 999, "expectedVersion": 1}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.errorCode").value("ISSUE_TYPE_NOT_FOUND"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 라벨 검증 — 생성 경로와 대칭 (TODOS 「도메인 require 실패가 500 으로 나간다」 봉합)
    //
    // `UpdateIssueRequest` 의 `List<@Size(max = 50) String>` 은 **장식이다.** Kotlin 이
    // 타입-use 애노테이션을 런타임 보존 형태로 심지 않아 Bean Validation 이 못 본다.
    // 그래서 51자 라벨이 400 으로 안 걸리고 도메인 `Issue.validateAndNormalizeLabels` 의
    // `require` 까지 내려가는데, `IssueExceptionHandler` 에 `IllegalArgumentException`
    // 핸들러가 없어 **500** 이 된다 — 사용자 입력 오류가 서버 장애로 기록된다.
    //
    // 생성 경로는 FR-UX-09 B1 이 `@AssertTrue` 로 이미 닫았다(IssueControllerCreateTest 참조).
    // 수정 경로를 같은 술어로 맞춘다. ADR D-6 이 열어 둔 ②(전용 예외 → 422)는 채택하지
    // 않는다 — `Issue.create` 도 같은 예외를 던지므로 **생성 경로의 400 계약이 422 로
    // 뒤집혀** 기존 계약 테스트 3건과 프론트 에러 처리를 함께 깨뜨린다.
    // ─────────────────────────────────────────────────────────────────────────

    /** 라벨만 담은 PATCH 를 보낸다. `expectedVersion` 은 필수라 함께 싣는다. */
    private fun patchLabels(labels: List<String>) =
        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("labels" to labels, "expectedVersion" to 1))),
        )

    /** 서비스가 호출될 경우를 대비한 stub — 400 이 나면 서비스는 아예 안 불린다. */
    private fun stubSuccessfulUpdate() {
        every {
            issueApplicationService.updateIssue(any(), IssueKey("ATLAS-1"), any())
        } returns sampleResponse
    }

    @Test
    fun `PATCH 이슈 수정 — label 하나가 51자이면 400`() {
        stubSuccessfulUpdate()
        patchLabels(listOf("A".repeat(51))).andExpect(status().isBadRequest)
    }

    // ★E6 대칭 — 공백만 있는 라벨은 길이 상한은 통과하지만 도메인 require(isNotBlank) 에 걸린다.
    @Test
    fun `PATCH 이슈 수정 — 공백만 있는 label 이면 400`() {
        stubSuccessfulUpdate()
        patchLabels(listOf("   ")).andExpect(status().isBadRequest)
    }

    // ── 양성 대조군 — 「항상 400」이 아님을 증명한다 ────────────────────────
    //
    // 이 짝이 없으면 위 두 단언은 DTO 를 통째로 거부하는 어떤 변경으로도 통과한다.

    @Test
    fun `PATCH 이슈 수정 — label 하나가 50자면 400 이 아니다`() {
        stubSuccessfulUpdate()
        patchLabels(listOf("A".repeat(50))).andExpect(status().isOk)
    }

    // 빈 문자열은 도메인이 필터링 대상으로 삼으므로(Issue.kt) 400 이 아니다 — 생성 경로와 같은 대비 축.
    @Test
    fun `PATCH 이슈 수정 — 빈 문자열 label 은 400 이 아니다`() {
        stubSuccessfulUpdate()
        patchLabels(listOf("")).andExpect(status().isOk)
    }

    // 개수 상한은 `@field:Size` 라 원래 동작한다 — 이 축은 회귀 방지용이다.
    @Test
    fun `PATCH 이슈 수정 — labels 가 21개이면 400`() {
        stubSuccessfulUpdate()
        patchLabels((1..21).map { "label$it" }).andExpect(status().isBadRequest)
    }
}
