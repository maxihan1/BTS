// IssueController POST /api/v1/issues/{key}/clone MockMvc 슬라이스 테스트 — FR-IS-06 task-3 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID
import com.bts.issue.application.CloneIssueRequest as AppCloneIssueRequest

/**
 * IssueController POST /api/v1/issues/{key}/clone MockMvc 슬라이스 테스트.
 *
 * [IssueApplicationService] 는 MockK stub 으로 대체하고, [IssueExceptionHandler] 를 등록하여
 * IssueNotFoundException → 404, IssueAccessDeniedException → 403 변환을 검증한다.
 *
 * 테스트 케이스.
 * - CL-1. 정상 → 201 + Location + DataResponse(descriptionHtml 포함)
 * - CL-2. body {} → 기본 옵션으로 처리
 * - CL-3. includeAssignee=false → 서비스에 그대로 전달
 * - CL-4. summaryOverride → 서비스에 그대로 전달
 * - CL-5. 원본 미존재 → 404 ISSUE_NOT_FOUND
 * - CL-6. 권한 없음 → 403 ACCESS_DENIED
 * - CL-7. summaryOverride 255자 초과 → 400 VALIDATION_FAILED
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerCloneTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueControllerCloneTest {
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

    private val fixedNow: Instant = Instant.parse("2026-06-02T00:00:00Z")
    private val actorId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val sourceKey = IssueKey("BTS-1")
    private val cloneKey = IssueKey("BTS-2")

    private val clonedIssue =
        Issue(
            id = IssueId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
            key = cloneKey,
            projectId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            summary = "결제 버그",
            reporterId = actorId,
            currentStateKey = "open",
            version = 1L,
            deletedAt = null,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = IssueTypeId(7L),
        )

    private val cloneResponse =
        IssueResponse(
            key = "BTS-2",
            id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            projectKey = "BTS",
            summary = "결제 버그",
            currentStateKey = "open",
            reporterId = actorId.value,
            version = 1L,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = 7L,
            typeKey = "bug",
            typeName = "Bug",
            description = "재현 절차",
            descriptionHtml = "<p>재현 절차</p>",
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
        every { issueApplicationService.cloneIssue(any(), sourceKey, any()) } returns clonedIssue
        every { issueApplicationService.findByKey(any(), cloneKey) } returns cloneResponse
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── CL-1: 정상 → 201 + Location + body ──────────────────────────────────────

    @Test
    fun `POST clone — 정상이면 201 + Location + DataResponse(descriptionHtml 포함)`() {
        mockMvc.perform(
            post("/api/v1/issues/BTS-1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/v1/issues/BTS-2"))
            .andExpect(jsonPath("$.data.key").value("BTS-2"))
            .andExpect(jsonPath("$.data.summary").value("결제 버그"))
            .andExpect(jsonPath("$.data.descriptionHtml").value("<p>재현 절차</p>"))
    }

    // ── CL-2: body 생략 → 기본 옵션 ──────────────────────────────────────────────

    @Test
    fun `POST clone — body 없이도 기본 옵션으로 201`() {
        mockMvc.perform(post("/api/v1/issues/BTS-1/clone"))
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/v1/issues/BTS-2"))
    }

    // ── CL-3: includeAssignee=false 전달 ─────────────────────────────────────────

    @Test
    fun `POST clone — includeAssignee=false 가 서비스에 전달된다`() {
        val reqSlot: CapturingSlot<AppCloneIssueRequest> = slot()
        every { issueApplicationService.cloneIssue(any(), sourceKey, capture(reqSlot)) } returns clonedIssue

        mockMvc.perform(
            post("/api/v1/issues/BTS-1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("includeAssignee" to false))),
        )
            .andExpect(status().isCreated)

        org.junit.jupiter.api.Assertions.assertEquals(false, reqSlot.captured.includeAssignee)
    }

    // ── CL-4: summaryOverride 전달 ───────────────────────────────────────────────

    @Test
    fun `POST clone — summaryOverride 가 서비스에 전달된다`() {
        val reqSlot: CapturingSlot<AppCloneIssueRequest> = slot()
        every { issueApplicationService.cloneIssue(any(), sourceKey, capture(reqSlot)) } returns clonedIssue

        mockMvc.perform(
            post("/api/v1/issues/BTS-1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("summaryOverride" to "결제 버그 (재현)"))),
        )
            .andExpect(status().isCreated)

        org.junit.jupiter.api.Assertions.assertEquals("결제 버그 (재현)", reqSlot.captured.summaryOverride)
    }

    // ── CL-5: 원본 미존재 → 404 ──────────────────────────────────────────────────

    @Test
    fun `POST clone — 원본 미존재면 404 ISSUE_NOT_FOUND`() {
        every { issueApplicationService.cloneIssue(any(), sourceKey, any()) } throws IssueNotFoundException(sourceKey)

        mockMvc.perform(
            post("/api/v1/issues/BTS-1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── CL-6: 권한 없음 → 403 ────────────────────────────────────────────────────

    @Test
    fun `POST clone — 권한 없으면 403 ACCESS_DENIED`() {
        every {
            issueApplicationService.cloneIssue(any(), sourceKey, any())
        } throws IssueAccessDeniedException(actorId, IssuePermission.CREATE, IssueScope.Project("BTS"))

        mockMvc.perform(
            post("/api/v1/issues/BTS-1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
    }

    // ── CL-7: summaryOverride 255자 초과 → 400 ───────────────────────────────────

    @Test
    fun `POST clone — summaryOverride 255자 초과면 400 VALIDATION_FAILED`() {
        mockMvc.perform(
            post("/api/v1/issues/BTS-1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("summaryOverride" to "A".repeat(256)))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }
}
