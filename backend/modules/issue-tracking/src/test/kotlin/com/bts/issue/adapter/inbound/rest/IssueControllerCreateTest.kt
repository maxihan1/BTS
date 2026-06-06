// IssueController POST /api/v1/issues MockMvc 슬라이스 테스트 — T13 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
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

/**
 * IssueController POST /api/v1/issues MockMvc 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다.
 * [IssueApplicationService] 는 MockK stub 으로 대체한다.
 *
 * 테스트 케이스 4건.
 * - C-1. projectKey blank → 400
 * - C-2. summary blank → 400
 * - C-3. summary 200자 초과 → 400
 * - C-4. 정상 입력 → 201 + IssueResponse body + Location 헤더
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerCreateTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueControllerCreateTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueController] 와 MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun issueController(service: IssueApplicationService): IssueController = IssueController(service)
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

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

    // ── C-1: projectKey blank → 400 ──────────────────────────────────────────

    @Test
    fun `POST 이슈 생성 — projectKey blank 이면 400`() {
        val body =
            mapOf(
                "projectKey" to "",
                "summary" to "유효한 요약",
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── C-2: summary blank → 400 ─────────────────────────────────────────────

    @Test
    fun `POST 이슈 생성 — summary blank 이면 400`() {
        val body =
            mapOf(
                "projectKey" to "ATLAS",
                "summary" to "",
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── C-3: summary 200자 초과 → 400 ────────────────────────────────────────

    @Test
    fun `POST 이슈 생성 — summary 200자 초과이면 400`() {
        val body =
            mapOf(
                "projectKey" to "ATLAS",
                "summary" to "A".repeat(201),
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── C-4: 정상 입력 → 201 + body + Location 헤더 ───────────────────────────

    @Test
    fun `POST 이슈 생성 — 정상 입력이면 201 + IssueResponse + Location 헤더`() {
        val fixedNow = Instant.parse("2026-05-26T00:00:00Z")
        val issueKey = IssueKey("ATLAS-1")
        val actorId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

        val stubIssue =
            Issue(
                id = IssueId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                key = issueKey,
                projectId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
                summary = "정상 요약",
                reporterId = actorId,
                currentStateKey = "open",
                version = 1L,
                deletedAt = null,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = IssueTypeId(3L),
            )

        val stubResponse =
            IssueResponse(
                key = "ATLAS-1",
                id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                projectKey = "ATLAS",
                summary = "정상 요약",
                currentStateKey = "open",
                reporterId = actorId.value,
                version = 1L,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = 3L,
                typeKey = "task",
                typeName = "Task",
            )

        every { issueApplicationService.createIssue(any(), any()) } returns stubIssue
        // 컨트롤러는 createIssue 후 findByKey 를 호출하여 type 요약 포함 응답을 얻는다.
        every { issueApplicationService.findByKey(any(), issueKey) } returns stubResponse

        val body =
            mapOf(
                "projectKey" to "ATLAS",
                "summary" to "정상 요약",
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.key").value("ATLAS-1"))
            .andExpect(jsonPath("$.data.summary").value("정상 요약"))
            .andExpect(header().string("Location", "/api/v1/issues/ATLAS-1"))
    }
}
