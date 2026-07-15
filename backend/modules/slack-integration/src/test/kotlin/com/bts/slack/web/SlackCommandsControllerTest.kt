// SlackCommandsController MockMvc 슬라이스 테스트 — 서명 게이트·form-urlencoded 원문 보존·@Async 위임 (FR-SL-04 Task 8)
package com.bts.slack.web

import com.bts.slack.command.SlashCommandService
import com.bts.slack.security.SlackSignatureVerifier
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.charset.StandardCharsets

/**
 * [SlackCommandsController] MockMvc 슬라이스 테스트 (FR-SL-04 Task 8).
 *
 * [SlackSignatureVerifier]·[SlashCommandService] 를 mockk 로 대체해 `MockMvcBuilders.standaloneSetup` 으로
 * 기동한다 — Spring Security 필터 체인/전체 test-boot 컨텍스트 부팅 없이 컨트롤러 자체의 분기 로직만
 * 검증한다([SlackEventsControllerTest] 동형, DB/Docker 불요). permitAll 필터 배선은 별도 관심사다.
 *
 * ## ★핵심 — form-urlencoded 원문 보존 (빈 바디면 서명검증이 조용히 깨진다)
 * Slack slash 요청 바디는 `application/x-www-form-urlencoded` 이고 서명은 **수신 원문 바이트**에 대해
 * 계산된다. 컨트롤러가 `@RequestParam`/`@ModelAttribute` 를 병용하면 Spring 이 form 을 먼저 파싱해
 * 바디 스트림을 소비하고, 그 뒤 원문 읽기는 빈 바디를 보게 되며 서명검증이 무력화된다.
 * 이 테스트는 반드시 `contentType(APPLICATION_FORM_URLENCODED)` 로 실제 form 바디를 전송하고,
 * verifier 에 전달된 rawBody 인자가 **온전한 원문**(빈 바디 아님)인지 mockk slot 으로 캡처 검증한다.
 *
 * ## verifier 인자는 원문 **바이트** ([ByteArray] 오버로드)
 * 컨트롤러는 `HttpServletRequest` 에서 읽은 원문 바이트를 그대로 [SlackSignatureVerifier] 에 넘긴다
 * (String 왕복은 유효 UTF-8 이 아닌 원문의 서명을 어긋나게 만든다 — [SlackInboundBodyGuardTest]).
 * 그래서 stub/verify 는 `any<ByteArray>()` 로 [ByteArray] 오버로드를 대상으로 한다. ★ [ByteArray] 는 `equals`
 * 가 **참조 동일성**이라 `isValid(ts, sig, body.toByteArray())` 같은 값 비교 stub 은 **절대 매칭되지 않는다** —
 * 원문 대조는 값 비교 stub 이 아니라 slot 캡처 후 내용 비교로 한다(아래 (a)).
 *
 * ## 검증 시나리오
 * - (a) 유효 서명 → 즉시 빈 200 ack + `service.process(text,user_id,team_id,response_url)` 위임 + rawBody 온전 캡처.
 * - (b) 서명 실패/헤더 누락 → 빈 401, service 미호출.
 * - (c) form-decode 정확성 — `%2B`(+ → 공백)·`%3D`(=)·`%2F`(/) 디코드, user_id·team_id·response_url 추출.
 * - (d) 필수 필드(response_url) 누락 → 방어적 빈 200(무시), service 미호출.
 * - (e) 본문 크기 상한 초과 → 빈 413, 서명검증·service 모두 미호출(가드 선행).
 */
class SlackCommandsControllerTest {
    private val verifier = mockk<SlackSignatureVerifier>()
    private val service = mockk<SlashCommandService>()

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(SlackCommandsController(verifier, service))
                .build()
    }

    private fun postCommand(
        body: String,
        timestamp: String? = "1700000000",
        signature: String? = "v0=deadbeef",
    ): MockHttpServletRequestBuilder {
        val builder =
            post("/slack/commands")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(body)
        timestamp?.let { builder.header(TIMESTAMP_HEADER, it) }
        signature?.let { builder.header(SIGNATURE_HEADER, it) }
        return builder
    }

    // ── (a) 유효 서명 → 즉시 빈 200 + 위임 + rawBody 온전 캡처 ─────────────────────────

    @Test
    fun `유효 서명 - 즉시 빈 200 ack 하고 위임하며 verifier 에 원문 form 바디를 그대로 전달한다`() {
        val body =
            "command=%2Fatlas&text=search+PROJ&user_id=U1&team_id=T1" +
                "&response_url=https%3A%2F%2Fhooks.slack.com%2Fx&channel_id=C1&trigger_id=x"
        val rawSlot = slot<ByteArray>()
        every { verifier.isValid("1700000000", "v0=deadbeef", capture(rawSlot)) } returns true
        every { service.process(any(), any(), any(), any()) } just Runs

        mockMvc.perform(postCommand(body))
            .andExpect(status().isOk)
            .andExpect(content().string(""))

        // ★ 서명 대상 원문이 온전해야 한다(빈 바디면 서명검증이 조용히 깨진 것).
        assertThat(String(rawSlot.captured, StandardCharsets.UTF_8)).isEqualTo(body)
        verify(exactly = 1) {
            service.process("search PROJ", "U1", "T1", "https://hooks.slack.com/x")
        }
    }

    // ── (b) 서명 실패/헤더 누락 → 빈 401, service 미호출 ────────────────────────────────

    @Test
    fun `서명 실패 - 빈 401 이고 서비스는 호출하지 않는다`() {
        val body = "text=x&user_id=U1&team_id=T1&response_url=https%3A%2F%2Fhooks"
        every { verifier.isValid(any(), any(), any<ByteArray>()) } returns false

        mockMvc.perform(postCommand(body, signature = "v0=forged"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().string(""))

        verify(exactly = 0) { service.process(any(), any(), any(), any()) }
    }

    @Test
    fun `타임스탬프·서명 헤더 누락 - 빈 401 이고 서비스는 호출하지 않는다`() {
        val body = "text=x&user_id=U1&team_id=T1&response_url=https%3A%2F%2Fhooks"
        every { verifier.isValid(any(), any(), any<ByteArray>()) } returns false

        mockMvc.perform(postCommand(body, timestamp = null, signature = null))
            .andExpect(status().isUnauthorized)
            .andExpect(content().string(""))

        verify(exactly = 0) { service.process(any(), any(), any(), any()) }
    }

    // ── (c) form-decode 정확성 ────────────────────────────────────────────────────────

    @Test
    fun `form-decode - text 의 공백·등호 이스케이프를 디코드하고 user_id·team_id·response_url 을 추출한다`() {
        val body =
            "command=%2Fatlas&text=search+PROJ+status%3Dopen&user_id=U1&team_id=T1" +
                "&response_url=https%3A%2F%2Fhooks.slack.com%2Fservices%2FT1%2FB1%2Fabc" +
                "&channel_id=C1&trigger_id=x"
        every { verifier.isValid(any(), any(), any<ByteArray>()) } returns true
        every { service.process(any(), any(), any(), any()) } just Runs

        mockMvc.perform(postCommand(body)).andExpect(status().isOk)

        verify(exactly = 1) {
            service.process(
                "search PROJ status=open",
                "U1",
                "T1",
                "https://hooks.slack.com/services/T1/B1/abc",
            )
        }
    }

    // ── (d) 필수 필드 누락 → 방어적 빈 200, service 미호출 ────────────────────────────────

    @Test
    fun `필수 필드 response_url 누락 - 방어적 빈 200 이고 서비스는 호출하지 않는다`() {
        val body = "command=%2Fatlas&text=help&user_id=U1&team_id=T1&channel_id=C1"
        every { verifier.isValid(any(), any(), any<ByteArray>()) } returns true

        mockMvc.perform(postCommand(body))
            .andExpect(status().isOk)
            .andExpect(content().string(""))

        verify(exactly = 0) { service.process(any(), any(), any(), any()) }
    }

    // ── (e) 본문 크기 상한 → 빈 413, 서명검증·service 모두 미호출(가드 선행) ─────────────────

    @Test
    fun `본문 크기 상한 초과 - 빈 413 이고 서명검증과 서비스를 모두 호출하지 않는다`() {
        // verifier 를 stub 하지 않는다 — 가드보다 먼저 호출되면 mockk 가 예외를 던져 순서 위반을 드러낸다.
        val huge = "text=" + "a".repeat(20_000)

        mockMvc.perform(postCommand(huge))
            .andExpect(status().isPayloadTooLarge)
            .andExpect(content().string(""))

        verify(exactly = 0) { verifier.isValid(any(), any(), any<ByteArray>()) }
        verify(exactly = 0) { service.process(any(), any(), any(), any()) }
    }

    private companion object {
        const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
        const val SIGNATURE_HEADER = "X-Slack-Signature"
    }
}
