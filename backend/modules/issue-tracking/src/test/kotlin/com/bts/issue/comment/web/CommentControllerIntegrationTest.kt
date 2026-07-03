// CommentController MockMvc 슬라이스 통합 테스트 (FR-IM-01 PR3 Task 4)

package com.bts.issue.comment.web

import com.bts.issue.comment.application.CommentApplicationService
import com.bts.issue.comment.application.CommentView
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * [CommentController] MockMvc 슬라이스 테스트 (FR-IM-01 PR3 Task 4).
 *
 * [CommentApplicationService] 는 MockK stub 으로 대체한다.
 * [CommentExceptionHandler] 를 컨텍스트에 등록하여 예외→HTTP 변환을 검증한다.
 * worklog 선례([com.bts.issue.worklog.web.WorklogControllerIntegrationTest],
 * [com.bts.issue.worklog.aggregate.web.WorklogAggregateControllerTest]) 와 동일한 슬라이스 방식이다.
 *
 * ## 테스트 케이스
 * - CM-1. GET /comments → 200 + 댓글 목록(bodyHtml 포함)
 * - CM-2. GET 권한 없음 → 403 Forbidden
 * - CM-3. GET 이슈 미존재 → 404 Not Found
 * - CM-4. GET 미인증(SecurityContext 없음) → 401 (★C1 — catch-all 이 500 으로 변질시키지 않는지 검증)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [CommentControllerIntegrationTest.TestMvcConfig::class])
@WebAppConfiguration
class CommentControllerIntegrationTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [CommentController], [CommentExceptionHandler], MockK stub Bean 을 등록한다.
     * ObjectMapper 에 JavaTimeModule 을 등록하여 Instant ISO 직렬화가 동작하도록 한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        @Bean
        open fun commentApplicationService(): CommentApplicationService = mockk(relaxed = true)

        @Bean
        open fun commentController(service: CommentApplicationService): CommentController = CommentController(service)

        @Bean
        open fun commentExceptionHandler(): CommentExceptionHandler = CommentExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var commentApplicationService: CommentApplicationService

    lateinit var mockMvc: MockMvc

    private val actorUuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val commentId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val createdAt = Instant.parse("2026-06-20T09:01:00Z")
    private val updatedAt = Instant.parse("2026-06-20T09:01:00Z")

    private val sampleView =
        CommentView(
            id = commentId,
            authorId = actorUuid,
            body = "**댓글** 본문",
            bodyHtml = "<p><strong>댓글</strong> 본문</p>\n",
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorUuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    @AfterEach
    fun tearDown() {
        clearMocks(commentApplicationService)
        SecurityContextHolder.clearContext()
    }

    // ── CM-1. GET → 200 + 댓글 목록(bodyHtml 포함) ────────────────────────────

    /**
     * CM-1. 댓글 목록 조회 → 200 OK + bodyHtml 포함 응답 검증.
     *
     * Given  service.list 가 sampleView 1건 반환
     * When   GET /api/v1/issues/ATLAS-1/comments
     * Then   200, data 배열에 id/authorId/body/bodyHtml/createdAt/updatedAt 포함
     */
    @Test
    fun `GET 댓글 목록 조회 — 200 OK plus bodyHtml 포함`() {
        every {
            commentApplicationService.list(actor = ActorId(actorUuid), issueKey = IssueKey("ATLAS-1"))
        } returns listOf(sampleView)

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/comments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].id").value(commentId.toString()))
            .andExpect(jsonPath("$.data[0].authorId").value(actorUuid.toString()))
            .andExpect(jsonPath("$.data[0].body").value("**댓글** 본문"))
            .andExpect(jsonPath("$.data[0].bodyHtml").value("<p><strong>댓글</strong> 본문</p>\n"))
    }

    // ── CM-2. GET 권한 없음 → 403 ──────────────────────────────────────────────

    /**
     * CM-2. VIEW 권한 없는 사용자 GET → 403 Forbidden.
     *
     * Given  service.list 가 IssueAccessDeniedException throw
     * When   GET /api/v1/issues/ATLAS-1/comments
     * Then   403 Forbidden
     */
    @Test
    fun `GET 권한 없음 — 403 Forbidden`() {
        every {
            commentApplicationService.list(actor = ActorId(actorUuid), issueKey = IssueKey("ATLAS-1"))
        } throws
            IssueAccessDeniedException(
                actor = ActorId(actorUuid),
                permission = IssuePermission.VIEW,
                scope = IssueScope.Issue("ATLAS-1"),
            )

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/comments"))
            .andExpect(status().isForbidden)
    }

    // ── CM-3. GET 이슈 미존재 → 404 ────────────────────────────────────────────

    /**
     * CM-3. 이슈 미존재 → 404 Not Found.
     *
     * Given  service.list 가 IssueNotFoundException throw
     * When   GET /api/v1/issues/ATLAS-99/comments
     * Then   404 Not Found
     */
    @Test
    fun `GET 이슈 미존재 — 404 Not Found`() {
        every {
            commentApplicationService.list(actor = ActorId(actorUuid), issueKey = IssueKey("ATLAS-99"))
        } throws IssueNotFoundException(IssueKey("ATLAS-99"))

        mockMvc.perform(get("/api/v1/issues/ATLAS-99/comments"))
            .andExpect(status().isNotFound)
    }

    // ── CM-4. GET 미인증 → 401 (★C1 — catch-all 삼킴 차단) ────────────────────

    /**
     * CM-4. SecurityContext 없음(미인증) → 401 Unauthorized. **500 아님**.
     *
     * [com.bts.issue.adapter.inbound.rest.CurrentActor.current] 가 던지는 401
     * [org.springframework.web.server.ResponseStatusException] 이 [CommentExceptionHandler] 의
     * catch-all `Exception` 핸들러에 삼켜져 500 으로 변질되지 않는지 검증한다
     * (learnings: catch-all-exceptionhandler-swallows-responsestatusexception).
     *
     * actor 추출이 리소스 조회보다 먼저이므로 이슈 존재 여부와 무관하게 401 을 반환한다.
     *
     * When   GET /api/v1/issues/ATLAS-1/comments (SecurityContext cleared)
     * Then   401 Unauthorized
     */
    @Test
    fun `GET 미인증 — 401 Unauthorized (C1, 500 아님)`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/comments"))
            .andExpect(status().isUnauthorized)
    }
}
