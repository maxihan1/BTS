// SlackEventsController MockMvc 슬라이스 테스트 — 서명 게이트·url_verification·link_shared 위임 (FR-SL-03 Task 11)
package com.bts.slack.web

import com.bts.slack.security.SlackSignatureVerifier
import com.bts.slack.unfurl.LinkSharedCommand
import com.bts.slack.unfurl.SlackUnfurlService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.MockKMatcherScope
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.charset.StandardCharsets

/**
 * [SlackEventsController] MockMvc 슬라이스 테스트 (FR-SL-03 Task 11).
 *
 * [SlackSignatureVerifier]·[SlackUnfurlService] 를 mockk 로 대체해 `MockMvcBuilders.standaloneSetup` 으로
 * 기동한다 — Spring Security 필터 체인/전체 test-boot 컨텍스트 부팅 없이 컨트롤러 자체의 분기 로직만
 * 검증한다([com.bts.issue.cycletime.web.CycleTimeControllerTest] 동형). permitAll 필터 배선(Task 12)과
 * 분리된 관심사다.
 *
 * ## 검증 시나리오
 * - `url_verification`(서명 통과 후) → `{"challenge": …}` 200.
 * - 잘못된 서명 → 401(S5, 빈 바디).
 * - 헤더 누락 → 401.
 * - signing secret 미설정(검증기 fail-closed) → 401 — 코드 경로상 잘못된 서명과 동일하게 수렴하지만
 *   ([SlackSignatureVerifier] KDoc "반환 계약 — boolean 거부로 수렴") 별도 시나리오로 명시 검증한다.
 * - `event_callback` + `link_shared` → 즉시 200 ack + [SlackUnfurlService.handleLinkShared] 위임(커맨드 필드 검증).
 * - `event.user` 부재(봇 게시) → 여전히 위임하되 `slackUserId=null` 로 커맨드 구성.
 * - 그 외 이벤트 타입/최상위 타입 → 200 무시(서비스 미호출).
 * - 401 응답 바디에 원문(rawBody)·서명·예외 메시지 미노출(NFR2).
 */
class SlackEventsControllerTest {
    private val verifier = mockk<SlackSignatureVerifier>()
    private val unfurlService = mockk<SlackUnfurlService>()
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(SlackEventsController(verifier, unfurlService, objectMapper))
                .build()
    }

    /**
     * verifier 에 전달될 원문 **바이트**가 [expected] 와 내용이 같은지 보는 mockk 매처.
     *
     * 컨트롤러는 `HttpServletRequest` 에서 읽은 원문 바이트를 그대로 [SlackSignatureVerifier] 의 [ByteArray]
     * 오버로드에 넘긴다(String 왕복은 유효 UTF-8 이 아닌 원문의 서명을 어긋나게 만든다 —
     * [SlackInboundBodyGuardTest]). ★ [ByteArray] 는 `equals` 가 **참조 동일성**이라
     * `isValid(ts, sig, body.toByteArray())` 로 stub 하면 **절대 매칭되지 않는다**(mockk 가 "no answer found"
     * 로 실패). 반드시 이렇게 내용 비교 매처를 써야 한다.
     */
    private fun MockKMatcherScope.rawBodyEq(expected: String): ByteArray =
        match { it.contentEquals(expected.toByteArray(StandardCharsets.UTF_8)) }

    private fun postEvents(
        body: String,
        timestamp: String? = "1700000000",
        signature: String? = "v0=deadbeef",
    ): MockHttpServletRequestBuilder {
        val builder = post("/slack/events").contentType(MediaType.APPLICATION_JSON).content(body)
        timestamp?.let { builder.header(TIMESTAMP_HEADER, it) }
        signature?.let { builder.header(SIGNATURE_HEADER, it) }
        return builder
    }

    // ── url_verification (서명 통과 후) ─────────────────────────────────────────

    @Test
    fun `url_verification 페이로드 - 서명 검증 통과 시 challenge 를 그대로 반환한다`() {
        val body = """{"type":"url_verification","challenge":"abc123"}"""
        every { verifier.isValid("1700000000", "v0=deadbeef", rawBodyEq(body)) } returns true

        mockMvc.perform(postEvents(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.challenge").value("abc123"))
    }

    // ── 서명 거부 (S5) ────────────────────────────────────────────────────────

    @Test
    fun `잘못된 서명 - 401 을 반환하고 바디를 노출하지 않는다`() {
        val body = """{"type":"url_verification","challenge":"abc123"}"""
        every { verifier.isValid("1700000000", "v0=forged", rawBodyEq(body)) } returns false

        mockMvc.perform(postEvents(body, signature = "v0=forged"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().string(""))
    }

    @Test
    fun `헤더 누락 - 401 을 반환한다`() {
        val body = """{"type":"url_verification","challenge":"abc123"}"""
        every { verifier.isValid(null, null, rawBodyEq(body)) } returns false

        mockMvc.perform(postEvents(body, timestamp = null, signature = null))
            .andExpect(status().isUnauthorized)
            .andExpect(content().string(""))
    }

    @Test
    fun `signing secret 미설정 - 검증기가 fail-closed 로 false 를 반환하면 401 을 반환한다`() {
        // SlackSignatureVerifier.isValid 는 secret 미설정도 서명 위조와 동일하게 false 로 수렴시킨다.
        // 컨트롤러는 그 이유를 구분하지 않고 모두 401 로만 매핑해야 한다(예외가 아니라 boolean 거부 계약).
        val body = """{"type":"event_callback","team_id":"T1","event":{"type":"link_shared"}}"""
        every { verifier.isValid("1700000000", "v0=deadbeef", rawBodyEq(body)) } returns false

        mockMvc.perform(postEvents(body))
            .andExpect(status().isUnauthorized)
            .andExpect(content().string(""))
    }

    // ── event_callback → link_shared 위임 ────────────────────────────────────

    @Test
    fun `event_callback link_shared - 즉시 200 ack 하고 SlackUnfurlService 로 위임한다`() {
        val body =
            """
            {
              "token": "verification-token",
              "team_id": "T123",
              "api_app_id": "A123",
              "event_id": "Ev123",
              "event_time": 1700000000,
              "type": "event_callback",
              "event": {
                "type": "link_shared",
                "user": "U456",
                "channel": "C789",
                "message_ts": "1234567890.123456",
                "links": [
                  {"url": "https://atlas.example.com/issues/PROJ-1", "domain": "atlas.example.com"},
                  {"url": "https://atlas.example.com/issues/PROJ-2", "domain": "atlas.example.com"}
                ]
              }
            }
            """.trimIndent()
        every { verifier.isValid("1700000000", "v0=deadbeef", rawBodyEq(body)) } returns true
        val commandSlot = slot<LinkSharedCommand>()
        every { unfurlService.handleLinkShared(capture(commandSlot)) } just Runs

        mockMvc.perform(postEvents(body))
            .andExpect(status().isOk)
            .andExpect(content().string(""))

        verify(exactly = 1) { unfurlService.handleLinkShared(any()) }
        val command = commandSlot.captured
        assertThat(command.teamId).isEqualTo("T123")
        assertThat(command.slackUserId).isEqualTo("U456")
        assertThat(command.channel).isEqualTo("C789")
        assertThat(command.messageTs).isEqualTo("1234567890.123456")
        assertThat(command.links)
            .containsExactly(
                "https://atlas.example.com/issues/PROJ-1",
                "https://atlas.example.com/issues/PROJ-2",
            )
    }

    @Test
    fun `event_callback link_shared - event user 가 없으면 slackUserId null 로 위임한다`() {
        val body =
            """
            {
              "team_id": "T123",
              "type": "event_callback",
              "event": {
                "type": "link_shared",
                "channel": "C789",
                "message_ts": "1234567890.123456",
                "links": [{"url": "https://atlas.example.com/issues/PROJ-1"}]
              }
            }
            """.trimIndent()
        every { verifier.isValid("1700000000", "v0=deadbeef", rawBodyEq(body)) } returns true
        val commandSlot = slot<LinkSharedCommand>()
        every { unfurlService.handleLinkShared(capture(commandSlot)) } just Runs

        mockMvc.perform(postEvents(body))
            .andExpect(status().isOk)

        assertThat(commandSlot.captured.slackUserId).isNull()
    }

    // ── 그 외 타입/이벤트 → 200 무시 ─────────────────────────────────────────────

    @Test
    fun `event_callback 이지만 link_shared 가 아닌 이벤트 - 200 이지만 서비스는 호출하지 않는다`() {
        val body = """{"team_id":"T123","type":"event_callback","event":{"type":"message"}}"""
        every { verifier.isValid("1700000000", "v0=deadbeef", rawBodyEq(body)) } returns true

        mockMvc.perform(postEvents(body))
            .andExpect(status().isOk)

        verify(exactly = 0) { unfurlService.handleLinkShared(any()) }
    }

    @Test
    fun `최상위 type 이 url_verification·event_callback 도 아니면 - 200 이지만 서비스는 호출하지 않는다`() {
        val body = """{"type":"app_rate_limited"}"""
        every { verifier.isValid("1700000000", "v0=deadbeef", rawBodyEq(body)) } returns true

        mockMvc.perform(postEvents(body))
            .andExpect(status().isOk)

        verify(exactly = 0) { unfurlService.handleLinkShared(any()) }
    }

    private companion object {
        const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
        const val SIGNATURE_HEADER = "X-Slack-Signature"
    }
}
