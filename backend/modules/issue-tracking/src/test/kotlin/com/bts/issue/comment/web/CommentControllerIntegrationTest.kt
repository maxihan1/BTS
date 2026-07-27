// CommentController MockMvc 슬라이스 통합 테스트 (FR-IM-01 PR3 Task 4)

package com.bts.issue.comment.web

import com.bts.issue.comment.application.CommentApplicationService
import com.bts.issue.comment.application.CommentApplicationService.Companion.MAX_BODY_LENGTH
import com.bts.issue.comment.application.CommentView
import com.bts.issue.comment.domain.Comment
import com.bts.issue.comment.domain.CommentBodyBlankException
import com.bts.issue.comment.domain.CommentBodyTooLongException
import com.bts.issue.comment.domain.CommentNotFoundException
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
 * - CO-P1~P8. POST /comments → 201·401·403·404·400 (FR-CO-01)
 * - CO2-P1~P12. PATCH·DELETE /comments/{commentId} → 200·204·400·401·404 (FR-CO-02 Task 5)
 *
 * ## ★ 이 파일이 검증할 수 있는 것과 없는 것 (슬라이스의 한계)
 * 서비스가 stub 이므로 **권한 게이트는 여기서 검증되지 않는다** — 403 을 단정해도 "stub 이 던지도록
 * 시킨 예외를 되받는 것"이라 게이트 로직이 바뀌어도 그대로 초록이다. 수정=작성자 한정,
 * 삭제=작성자 OR `SOFT_DELETE` 의 실제 판별자는
 * [com.bts.issue.comment.application.CommentApplicationServiceTest] 에 있다.
 *
 * 여기서만 증명 가능한 것은 **상태코드 매핑 · 직렬화 · 정화(sanitize) 경로**다. 특히 정화는
 * stub 이 [Comment] 를 반환하고 [CommentResponse.from] 이 **진짜**
 * [com.bts.issue.markdown.MarkdownRenderer] 를 태우므로 유효하다 — 프론트 테스트는 MSW 모크가
 * `bodyHtml` 을 `<p>` 로 감싸기만 해서 변환기·정화기를 거치지 않아 증명할 수 없다.
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
        /**
         * ★ 프로덕션 설정을 미러링한다 (FR-CO-01 발견).
         *
         * Spring Boot 의 Jackson 자동설정은 `FAIL_ON_UNKNOWN_PROPERTIES` 를 **비활성**으로 둔다.
         * 이 슬라이스는 맨 `ObjectMapper()` 를 쓰므로 그 값이 **활성**(Jackson 기본)이어서,
         * 미지 필드가 온 요청에 대해 프로덕션은 무시하고 슬라이스는 400 을 내는 **불일치**가 있었다.
         * 저작자 위조 시도(요청에 `authorId` 를 끼워 넣는 케이스)를 프로덕션과 같은 조건에서
         * 검증하려면 이 설정을 맞춰야 한다 — 아니면 "테스트는 400, 실서버는 통과" 가 된다.
         */
        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

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

    // ══ FR-CO-01 — POST /comments ════════════════════════════════════════════

    private val sampleComment =
        Comment(
            id = commentId,
            issueId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            authorId = actorUuid,
            body = "확인했습니다.",
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun postComment(
        key: String,
        json: String,
    ) = mockMvc.perform(
        post("/api/v1/issues/$key/comments")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json),
    )

    /**
     * CO-P1. 댓글 작성 → 201 Created + 생성된 댓글 (스펙 S1).
     */
    @Test
    fun `POST 댓글 작성 — 201 Created plus 생성된 댓글`() {
        every {
            commentApplicationService.create(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                body = "확인했습니다.",
            )
        } returns sampleComment

        postComment("ATLAS-1", """{"body":"확인했습니다."}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(commentId.toString()))
            .andExpect(jsonPath("$.data.authorId").value(actorUuid.toString()))
            .andExpect(jsonPath("$.data.body").value("확인했습니다."))
            .andExpect(jsonPath("$.data.bodyHtml").exists())
    }

    /**
     * CO-P2. ★저작자 위조 시도 → 무시되고 authorId 는 actor (스펙 S2 / EC-5).
     *
     * 요청 본문에 `authorId` 를 끼워 넣어도 [com.bts.issue.comment.web.AddCommentRequest] 에
     * 그 필드가 **존재하지 않아** 바인딩되지 않는다. 서비스 호출도 3인자여서 저작자를 넘길 방법이 없다.
     */
    @Test
    fun `POST 요청의 authorId 필드는 무시되고 actor 가 저작자다`() {
        val otherUuid = UUID.fromString("99999999-9999-4999-8999-999999999999")
        every {
            commentApplicationService.create(actor = ActorId(actorUuid), issueKey = IssueKey("ATLAS-1"), body = "위조 시도")
        } returns sampleComment

        postComment("ATLAS-1", """{"body":"위조 시도","authorId":"$otherUuid"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.authorId").value(actorUuid.toString()))

        // 저작자를 넘기는 창구가 없음을 호출 형태로 고정 — 3인자만 존재한다
        verify(exactly = 1) {
            commentApplicationService.create(actor = ActorId(actorUuid), issueKey = IssueKey("ATLAS-1"), body = "위조 시도")
        }
    }

    /**
     * CO-P3. 미인증 POST → 401 (이슈 존재 여부와 무관, actor 추출이 먼저).
     */
    @Test
    fun `POST 미인증 — 401 Unauthorized`() {
        SecurityContextHolder.clearContext()

        postComment("ATLAS-1", """{"body":"본문"}""")
            .andExpect(status().isUnauthorized)
    }

    /**
     * CO-P4. UPDATE 권한 없음 → 403 (스펙 S4).
     */
    @Test
    fun `POST UPDATE 권한 없음 — 403 Forbidden`() {
        every {
            commentApplicationService.create(actor = ActorId(actorUuid), issueKey = IssueKey("ATLAS-1"), body = "본문")
        } throws
            IssueAccessDeniedException(
                actor = ActorId(actorUuid),
                permission = IssuePermission.UPDATE,
                scope = IssueScope.Issue("ATLAS-1"),
            )

        postComment("ATLAS-1", """{"body":"본문"}""")
            .andExpect(status().isForbidden)
    }

    /**
     * CO-P5. 이슈 미존재 → 404.
     */
    @Test
    fun `POST 이슈 미존재 — 404 Not Found`() {
        every {
            commentApplicationService.create(actor = ActorId(actorUuid), issueKey = IssueKey("ATLAS-99"), body = "본문")
        } throws IssueNotFoundException(IssueKey("ATLAS-99"))

        postComment("ATLAS-99", """{"body":"본문"}""")
            .andExpect(status().isNotFound)
    }

    /**
     * CO-P6. `body` 필드 누락 → 400 (EC-4). 요청 형식 문제는 웹 계층 소관.
     */
    @Test
    fun `POST body 누락 — 400 Bad Request`() {
        postComment("ATLAS-1", "{}")
            .andExpect(status().isBadRequest)
    }

    /**
     * CO-P7. 공백만 본문 → 400. 서비스의 [CommentBodyBlankException] 이 400 으로 번역돼야 한다.
     *
     * ★핸들러 미등록 시 catch-all `Exception` 이 삼켜 **500** 이 된다 — 본문 코드까지 판별자로 잡는다
     * (learnings: catch-all-exceptionhandler-swallows-responsestatusexception).
     */
    @Test
    fun `POST 공백만 본문 — 400 이고 500 이 아니다`() {
        every {
            commentApplicationService.create(actor = ActorId(actorUuid), issueKey = IssueKey("ATLAS-1"), body = "   ")
        } throws CommentBodyBlankException()

        postComment("ATLAS-1", """{"body":"   "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("COMMENT_BODY_BLANK"))
    }

    /**
     * CO-P8. 길이 초과 본문 → 400 + 상한 값이 응답에 담긴다.
     */
    @Test
    fun `POST 길이 초과 본문 — 400 이고 상한을 알려준다`() {
        val tooLong = "가".repeat(MAX_BODY_LENGTH + 1)
        every {
            commentApplicationService.create(actor = ActorId(actorUuid), issueKey = IssueKey("ATLAS-1"), body = tooLong)
        } throws CommentBodyTooLongException(actual = tooLong.length, max = MAX_BODY_LENGTH)

        postComment("ATLAS-1", """{"body":"$tooLong"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("COMMENT_BODY_TOO_LONG"))
    }

    // ══ FR-CO-02 — PATCH / DELETE /comments/{commentId} ══════════════════════

    /** 수정 결과 시각. `createdAt` 과 달라야 프론트의 "(수정됨)" 표시가 성립한다. */
    private val editedAt = Instant.parse("2026-06-20T10:30:00Z")

    /** 존재하지 않는 댓글 UUID — 404 판정용. */
    private val missingCommentId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")

    private fun patchComment(
        key: String,
        id: String,
        json: String,
    ) = mockMvc.perform(
        patch("/api/v1/issues/$key/comments/$id")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json),
    )

    private fun deleteComment(
        key: String,
        id: String,
    ) = mockMvc.perform(delete("/api/v1/issues/$key/comments/$id"))

    /**
     * `service.update` 가 돌려줄 [Comment] — 본문과 수정 시각만 바꾼다.
     *
     * ★ [CommentResponse] 가 아니라 **[Comment]** 를 반환해야 컨트롤러가
     * [CommentResponse.from] → [CommentView.of] → `MarkdownRenderer.renderSafe` 경로를 실제로 탄다.
     * DTO 를 바로 돌려주면 정화 테스트가 stub 값을 되받는 거짓 초록이 된다.
     */
    private fun editedComment(body: String) = sampleComment.copy(body = body, updatedAt = editedAt)

    /** `service.update` 를 [body] 로 stub 하고 수정된 [Comment] 를 반환하게 한다. */
    private fun stubUpdate(body: String) {
        every {
            commentApplicationService.update(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                commentId = commentId,
                body = body,
            )
        } returns editedComment(body)
    }

    /**
     * CO2-P1. 댓글 수정 → 200 OK + 수정된 본문/시각 (스펙 S1).
     */
    @Test
    fun `PATCH 댓글 수정 — 200 OK plus 렌더된 bodyHtml`() {
        stubUpdate("수정된 본문")

        patchComment("ATLAS-1", commentId.toString(), """{"body":"수정된 본문"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(commentId.toString()))
            .andExpect(jsonPath("$.data.authorId").value(actorUuid.toString()))
            .andExpect(jsonPath("$.data.body").value("수정된 본문"))
            .andExpect(jsonPath("$.data.bodyHtml").value(containsString("<p>수정된 본문</p>")))
            .andExpect(jsonPath("$.data.updatedAt").value(editedAt.toString()))
    }

    /**
     * CO2-P2. ★수정 응답의 `bodyHtml` 은 [CommentView] 를 경유한다 — 마크다운이 HTML 로 변환된다 (FR-15).
     *
     * 컨트롤러가 렌더링을 건너뛰거나 원문을 그대로 담으면 `<strong>` 이 나오지 않아 실패한다.
     * 목록·작성·수정 세 경로의 렌더 단일 지점([CommentView.of])이 살아 있음을 고정한다.
     */
    @Test
    fun `PATCH 응답 bodyHtml 은 CommentView 를 경유한다 — 마크다운이 HTML 로 변환됨`() {
        val markdown = "**굵게** 그리고 `코드`"
        stubUpdate(markdown)

        patchComment("ATLAS-1", commentId.toString(), """{"body":"$markdown"}""")
            .andExpect(status().isOk)
            // 원문은 그대로 보존된다 (편집 폼이 다시 마크다운을 보여줘야 하므로)
            .andExpect(jsonPath("$.data.body").value(markdown))
            .andExpect(jsonPath("$.data.bodyHtml").value(containsString("<strong>굵게</strong>")))
            .andExpect(jsonPath("$.data.bodyHtml").value(containsString("<code>코드</code>")))
    }

    /**
     * CO2-P3. ★수정 응답의 `bodyHtml` 에서 `script` 태그가 제거된다 (C8 — 정화 회귀).
     *
     * 프론트 테스트는 MSW 모크가 `bodyHtml` 을 `<p>` 로 감싸기만 해 정화기를 타지 않는다.
     * 따라서 이 단정은 **백엔드에서만** 가능하다 (`MarkdownRendererTest` 1번과 동일 판별자).
     */
    @Test
    fun `PATCH 응답 bodyHtml 에서 script 태그가 제거된다`() {
        val payload = "<script>alert(1)</script>"
        stubUpdate(payload)

        patchComment("ATLAS-1", commentId.toString(), """{"body":"$payload"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.bodyHtml").value(not(containsString("<script"))))
            .andExpect(jsonPath("$.data.bodyHtml").value(not(containsString("</script>"))))
    }

    /**
     * CO2-P4. ★수정 응답의 `bodyHtml` 에서 이벤트 핸들러 속성이 제거된다 (C8 — 정화 회귀).
     *
     * 브라우저가 평가하는 `onerror=값` 형태가 남지 않아야 한다
     * (`MarkdownRendererTest` 2번과 동일 판별자 — 엔티티 escape 형태는 실행 불가라 허용).
     */
    @Test
    fun `PATCH 응답 bodyHtml 에서 이벤트 핸들러 속성이 제거된다`() {
        val payload = "<img src=x onerror=alert(1)>"
        stubUpdate(payload)

        patchComment("ATLAS-1", commentId.toString(), """{"body":"$payload"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.bodyHtml").value(not(containsString("onerror="))))
    }

    /**
     * CO2-P5. 공백만 본문 → 400. 서비스의 [CommentBodyBlankException] 이 400 으로 번역돼야 한다.
     */
    @Test
    fun `PATCH 공백만 본문 — 400 이고 500 이 아니다`() {
        every {
            commentApplicationService.update(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                commentId = commentId,
                body = "   ",
            )
        } throws CommentBodyBlankException()

        patchComment("ATLAS-1", commentId.toString(), """{"body":"   "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("COMMENT_BODY_BLANK"))
    }

    /**
     * CO2-P6. ★비-UUID `commentId` → 400. **500 아님** (F3 회귀 차단).
     *
     * `@PathVariable commentId: UUID` 가 생기면서 [MethodArgumentTypeMismatchException] 이
     * 처음으로 이 컨트롤러에서 발생 가능해진다. 전용 핸들러가 없으면 catch-all `Exception` 이
     * 삼켜 500 이 된다 — 상태코드만이 아니라 `errorCode` 본문까지 판별자로 잡는다.
     */
    @Test
    fun `PATCH 비-UUID commentId — 400 이고 500 이 아니다`() {
        patchComment("ATLAS-1", "abc", """{"body":"본문"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("COMMENT_VALIDATION_FAILED"))
    }

    /**
     * CO2-P7. ★깨진 JSON 본문 → 400. **500 아님** (F3 회귀 차단).
     *
     * `@RequestBody` 가 생기면서 [HttpMessageNotReadableException] 이 발생 가능해진다.
     * CO2-P6 과 같은 이유로 전용 핸들러가 필요하다.
     */
    @Test
    fun `PATCH 깨진 JSON 본문 — 400 이고 500 이 아니다`() {
        patchComment("ATLAS-1", commentId.toString(), "{\"body\":}")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("COMMENT_VALIDATION_FAILED"))
    }

    /**
     * CO2-P8. 미존재 `commentId` → 404 (스펙 E1).
     *
     * 상태코드만 보면 "매핑 자체가 없어서 나온 404" 와 구별되지 않으므로 `errorCode` 를 함께 단정한다.
     */
    @Test
    fun `PATCH 미존재 commentId — 404 Not Found`() {
        every {
            commentApplicationService.update(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                commentId = missingCommentId,
                body = "본문",
            )
        } throws CommentNotFoundException(missingCommentId)

        patchComment("ATLAS-1", missingCommentId.toString(), """{"body":"본문"}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("COMMENT_NOT_FOUND"))
    }

    /**
     * CO2-P9. 댓글 삭제 → 204 No Content (본문 없음).
     */
    @Test
    fun `DELETE 댓글 삭제 — 204 No Content`() {
        every {
            commentApplicationService.delete(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                commentId = commentId,
            )
        } just Runs

        deleteComment("ATLAS-1", commentId.toString())
            .andExpect(status().isNoContent)

        verify(exactly = 1) {
            commentApplicationService.delete(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                commentId = commentId,
            )
        }
    }

    /**
     * CO2-P10. ★DELETE 비-UUID `commentId` → 400. **500 아님**.
     *
     * PATCH(CO2-P6)만 고치고 DELETE 를 빠뜨리는 형태의 부분 봉합을 막는다.
     */
    @Test
    fun `DELETE 비-UUID commentId — 400 이고 500 이 아니다`() {
        deleteComment("ATLAS-1", "not-a-uuid")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("COMMENT_VALIDATION_FAILED"))
    }

    /**
     * CO2-P11. 미인증 PATCH·DELETE → 401 (500 도 404 도 아님).
     *
     * `CurrentActor.current()` 를 리소스 조회보다 **먼저** 호출하므로 댓글 존재 여부와 무관하게 401 이다
     * (미인증자가 404/403 차이로 리소스 존재를 probe 하지 못한다 — DEVELOPMENT.md §1.1 #4).
     * "여전히 401" 이 공허해지지 않도록 `errorCode` 를 함께 단정한다.
     */
    @Test
    fun `PATCH·DELETE 미인증 — 401 Unauthorized`() {
        SecurityContextHolder.clearContext()

        patchComment("ATLAS-1", commentId.toString(), """{"body":"본문"}""")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))

        deleteComment("ATLAS-1", commentId.toString())
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
    }

    /**
     * CO2-P12. 기존 GET·POST 무회귀 — 신규 하위경로 매핑이 컬렉션 경로를 가리지 않는다.
     *
     * ★이 한 건은 **RED 단계에서도 통과한다** — 보존을 단정하는 회귀 가드라 성질상 그렇다.
     * 실패 조건은 이 task 이후 `/{commentId}` 매핑이 `/comments` 컬렉션 경로를 잠식하는 경우다.
     */
    @Test
    fun `기존 GET·POST 는 신규 하위경로 매핑에 가려지지 않는다`() {
        every {
            commentApplicationService.list(actor = ActorId(actorUuid), issueKey = IssueKey("ATLAS-1"))
        } returns listOf(sampleView)
        every {
            commentApplicationService.create(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                body = "확인했습니다.",
            )
        } returns sampleComment

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/comments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].id").value(commentId.toString()))

        postComment("ATLAS-1", """{"body":"확인했습니다."}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(commentId.toString()))
    }
}
