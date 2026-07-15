// Slack 인바운드 3개 컨트롤러의 미인증 힙 DoS 가드 — 본문 미적재(구조)·상한 이중 방어·원문 바이트 HMAC 회귀 방지

package com.bts.slack.web

import com.bts.slack.command.SlashCommandService
import com.bts.slack.config.SlackProperties
import com.bts.slack.interaction.SlackInteractionPayloadParser
import com.bts.slack.interaction.SlackInteractionService
import com.bts.slack.security.SlackSignatureVerifier
import com.bts.slack.unfurl.SlackUnfurlService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import java.lang.reflect.Method
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Slack 인바운드 3개 컨트롤러([SlackEventsController]·[SlackCommandsController]·[SlackInteractionsController])의
 * **미인증 힙 DoS 가드** 회귀 방지 테스트.
 *
 * ## 무엇을 막는가 (이 테스트가 존재하는 이유)
 * 세 엔드포인트는 필터 체인 permitAll + 컨트롤러 서명검증으로 인가된다. 즉 **서명이 틀린 요청도 컨트롤러
 * 핸들러까지 도달**한다. 따라서 핸들러가 `@RequestBody String` 으로 본문을 받으면, signing secret 을 모르는
 * 공격자도 요청 본문 전체를 우리 힙에 적재시킬 수 있다(서명검증은 적재 **이후**라 fail-closed 401 이
 * 방어선이 되지 못한다). nginx 는 110MB 를 허용하고 백엔드 힙은 1152MB 이므로 동시 몇 건이면 9-BC 모놀리스
 * 전체가 OOM 으로 내려간다 — slack 을 쓰지 않는 배포까지 포함해서.
 *
 * ## ★ 가짜 그린 주의 — "상한 초과 → 413" 만으로는 이 결함을 못 잡는다
 * [SlackCommandsController]·[SlackInteractionsController] 는 **이미** 413 을 준다. 다만 그 가드가
 * `rawBody.toByteArray().size > MAX` 라 이미 String 이 힙에 있고 복사본을 하나 더 뜬 뒤에 재는, 방어선이
 * 아니라 증폭기였다. 그래서 이 클래스는 상태코드가 아니라 **본문이 힙에 적재되지 않는 구조**를 직접 단언한다
 * (memory `multipart-default-limit-app-policy-false-green` — 앱 정책을 우회하는 경로가 있으면 상태코드
 * 단언은 가짜 그린이 된다).
 *
 * ## 검증 축 3가지
 * 1. **구조 — `@RequestBody` 부재**(리플렉션). 핸들러가 `@RequestBody` 를 쓰면 Spring 이 본문 전체를
 *    역직렬화해 힙에 올린 **뒤에야** 핸들러 코드가 실행되므로, 어떤 애플리케이션 레벨 가드도 늦는다.
 *    `HttpServletRequest` 를 직접 받아 상한만큼만 스트리밍으로 읽어야 한다
 *    ([com.bts.automation.adapter.web.AutomationWebhookController] 선례 동형).
 *    ArchUnit 대신 평이한 JVM 리플렉션을 쓴다 — 클래스 참조가 컴파일 타임에 검증되어 룰 오타로 조용히
 *    통과하는 vacuous 사고(memory `archunit-vacuous-rule-silent-pass`)를 구조적으로 배제하고, 대상
 *    핸들러를 실제로 찾았는지도 [POST 핸들러가 정확히 하나씩 발견된다] 로 함께 단언한다.
 * 2. **크기 상한 — `/slack/events`**(신규). SL-03 이 후속으로 미룬 부채
 *    (`docs/plans/2026-07-11-fr-sl-03-slack-unfurl.md` §후속 ③ "`/slack/events` payload 크기 상한 + prod
 *    SecurityConfig permitAll 중앙 등록")를 여기서 갚는다. 상한 초과는 **서명검증 이전에** 413.
 * 3. **원문 바이트 HMAC**. Slack 서명은 수신 원문 **바이트**에 대해 계산된다. `@RequestBody String` →
 *    `toByteArray(UTF_8)` 왕복은 유효 UTF-8 이 아닌 바이트를 U+FFFD 로 치환해 서명을 어긋나게 만든다
 *    (fail-closed 라 안전하지만 원인 불명 401). 검증기가 원문 바이트에 직접 HMAC 을 걸어야 한다.
 *
 * ## 실 서명검증기 + 고정 Clock (mockk 아님)
 * 축 2·3 은 **실** [SlackSignatureVerifier] 를 쓴다. mockk 로 대체하면 "서명이 통과했는지"가 stub 설정에
 * 좌우되어 원문 바이트 왕복 결함 자체를 관측할 수 없다. 시각 의존 판정(재전송 윈도우)은 [Clock.fixed] 로
 * 고정한다(memory — 시각 의존 로직 Clock 주입).
 * - 축 3 은 secret 을 **설정**한 검증기 → 서명이 맞으면 200, 어긋나면 401 이 관측된다.
 * - 축 2 는 secret 을 **미설정**한 검증기 → 항상 fail-closed 401. 따라서 413 이 나오면 그것은 크기 가드가
 *   서명검증보다 **먼저** 거절했다는 증거다(secret 없이도 413 = 무인증 공격자 시나리오 그대로).
 */
@Suppress("TooManyFunctions") // 3개 컨트롤러 × 3개 검증축 + 서명/요청 헬퍼로 임계값(11)을 넘는다 — 책임은 단일(인바운드 본문 가드).
class SlackInboundBodyGuardTest {
    // ── 축 1. 구조 — 본문을 힙에 적재하는 @RequestBody 금지 ──────────────────────────

    @Test
    fun `인바운드 핸들러는 @RequestBody 로 본문 전체를 힙에 적재하지 않는다`() {
        val offenders =
            INBOUND_CONTROLLERS
                .filter { controller ->
                    postHandlerOf(controller).parameters.any { it.isAnnotationPresent(RequestBody::class.java) }
                }.map { it.simpleName }

        assertThat(offenders)
            .describedAs(
                "permitAll 인바운드 핸들러는 @RequestBody 를 쓰면 안 된다 — 서명검증 이전에 본문 전체가 " +
                    "힙에 적재되어 미인증 공격자가 OOM 을 유발할 수 있다. HttpServletRequest 를 직접 받아 " +
                    "상한만큼만 스트리밍으로 읽어라.",
            ).isEmpty()
    }

    @Test
    fun `인바운드 핸들러는 HttpServletRequest 를 받아 상한 스트리밍으로 본문을 읽는다`() {
        val missing =
            INBOUND_CONTROLLERS
                .filter { controller ->
                    postHandlerOf(controller).parameterTypes.none { it == HttpServletRequest::class.java }
                }.map { it.simpleName }

        assertThat(missing)
            .describedAs("인바운드 핸들러는 본문을 상한 이내로만 읽기 위해 HttpServletRequest 를 직접 받아야 한다")
            .isEmpty()
    }

    /**
     * ★ vacuous 방지 — 위 두 룰이 "검사 대상을 하나도 못 찾아서" 통과하는 상황을 차단한다.
     * 핸들러 탐색이 깨지면(메서드 이름/어노테이션 변경) 위 룰은 빈 리스트로 조용히 PASS 하므로,
     * 대상이 실제로 존재함을 독립적으로 단언한다(memory `archunit-vacuous-rule-silent-pass`).
     */
    @Test
    fun `대상 컨트롤러마다 POST 핸들러가 정확히 하나씩 발견된다`() {
        INBOUND_CONTROLLERS.forEach { controller ->
            assertThat(postHandlerOf(controller).name)
                .describedAs("%s 의 @PostMapping 핸들러", controller.simpleName)
                .isEqualTo("receive")
        }
    }

    // ── 축 2. `/slack/events` 크기 상한 — 서명검증 이전 거절 (SL-03 후속 부채) ──────────

    @Test
    fun `events - 상한을 초과한 본문은 signing secret 없이도 413 으로 거절한다`() {
        // secret 미설정 검증기 = 항상 401(fail-closed). 그런데도 413 이 나와야 크기 가드가 서명검증보다
        // 먼저 거절했다는 뜻이다 — 공격자가 secret 을 모르는 상태 그대로의 시나리오.
        val mockMvc = eventsMockMvc(verifierWith(secret = ""))

        mockMvc
            .perform(eventsRequest(bodyOfSize(EVENTS_MAX_BODY_BYTES + 1), signature = "v0=irrelevant"))
            .andExpect(status().isPayloadTooLarge)
    }

    @Test
    fun `events - 정확히 상한 크기인 본문은 크기 가드를 통과해 서명검증까지 진행한다`() {
        // 경계값(off-by-one 방지) — 상한 이내이므로 413 이 아니라 서명검증 결과(미설정 → 401)가 나와야 한다.
        val mockMvc = eventsMockMvc(verifierWith(secret = ""))

        mockMvc
            .perform(eventsRequest(bodyOfSize(EVENTS_MAX_BODY_BYTES), signature = "v0=irrelevant"))
            .andExpect(status().isUnauthorized)
    }

    // ── 축 3. 원문 바이트 HMAC — UTF-8 왕복 없음 ──────────────────────────────────────

    /**
     * ★ 양성 대조군(positive control) — 이 테스트의 [signBytes] 참조 구현이 실 검증기가 받아들이는 서명을
     * 만든다는 증거. 이게 없으면 아래 비UTF-8 케이스가 "서명 헬퍼가 틀려서" 실패해도 구분할 수 없다.
     */
    @Test
    fun `events - 유효 UTF-8 원문에 원문 바이트 서명을 붙이면 정상 처리된다`() {
        val mockMvc = eventsMockMvc(verifierWith(SIGNING_SECRET))
        val body = """{"type":"url_verification","challenge":"abc123"}""".toByteArray(Charsets.UTF_8)

        mockMvc
            .perform(eventsRequest(body, signature = signBytes(SIGNING_SECRET, TIMESTAMP, body)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.challenge").value("abc123"))
    }

    @Test
    fun `events - 유효 UTF-8 이 아닌 원문도 원문 바이트 서명이면 401 이 아니다`() {
        // 0xFF 는 UTF-8 에서 절대 유효하지 않은 바이트다. String 왕복(@RequestBody String → toByteArray)은
        // 이를 U+FFFD(EF BF BD)로 치환해 HMAC 대상 바이트를 바꿔버리므로 서명이 어긋나 401 이 된다.
        // 원문 바이트에 직접 HMAC 을 걸면 서명은 일치하고, 이후 파싱 실패는 계약대로 빈 200 으로 수렴한다.
        val mockMvc = eventsMockMvc(verifierWith(SIGNING_SECRET))
        val body = """{"type":"app_rate_limited"}""".toByteArray(Charsets.UTF_8) + INVALID_UTF8_BYTE

        mockMvc
            .perform(eventsRequest(body, signature = signBytes(SIGNING_SECRET, TIMESTAMP, body)))
            .andExpect(status().isOk)
    }

    @Test
    fun `commands - 유효 UTF-8 이 아닌 원문도 원문 바이트 서명이면 401 이 아니다`() {
        // 서명 통과 후 필수 필드(user_id 등)가 없으므로 계약대로 방어적 빈 200 — service 는 호출되지 않는다
        // (미stub mockk 라 호출되면 예외로 드러난다).
        val mockMvc = commandsMockMvc(verifierWith(SIGNING_SECRET))
        val body = "foo=bar".toByteArray(Charsets.UTF_8) + INVALID_UTF8_BYTE

        mockMvc
            .perform(formRequest("/slack/commands", body, signBytes(SIGNING_SECRET, TIMESTAMP, body)))
            .andExpect(status().isOk)
    }

    @Test
    fun `interactions - 유효 UTF-8 이 아닌 원문도 원문 바이트 서명이면 401 이 아니다`() {
        // 서명 통과 후 payload 필드가 없으므로 계약대로 방어적 빈 200 — parser/service 는 호출되지 않는다.
        val mockMvc = interactionsMockMvc(verifierWith(SIGNING_SECRET))
        val body = "foo=bar".toByteArray(Charsets.UTF_8) + INVALID_UTF8_BYTE

        mockMvc
            .perform(formRequest("/slack/interactions", body, signBytes(SIGNING_SECRET, TIMESTAMP, body)))
            .andExpect(status().isOk)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

    /** 컨트롤러의 유일한 `@PostMapping` 핸들러를 찾는다(0개/2개 이상이면 룰 자체가 깨진 것이므로 실패). */
    private fun postHandlerOf(controller: Class<*>): Method {
        val handlers = controller.declaredMethods.filter { it.isAnnotationPresent(PostMapping::class.java) }
        assertThat(handlers)
            .describedAs("%s 의 @PostMapping 핸들러", controller.simpleName)
            .hasSize(1)
        return handlers.single()
    }

    /** 미설정(빈 문자열) secret 은 fail-closed 로 항상 거부된다([SlackSignatureVerifier] 계약). */
    private fun verifierWith(secret: String) =
        SlackSignatureVerifier(
            SlackProperties(
                clientId = "",
                clientSecret = "",
                redirectUri = "",
                scopes = "chat:write",
                signingSecret = secret,
            ),
            Clock.fixed(T0, ZoneOffset.UTC),
        )

    private fun eventsMockMvc(verifier: SlackSignatureVerifier): MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                SlackEventsController(verifier, mockk<SlackUnfurlService>(), ObjectMapper().registerKotlinModule()),
            ).build()

    private fun commandsMockMvc(verifier: SlackSignatureVerifier): MockMvc =
        MockMvcBuilders
            .standaloneSetup(SlackCommandsController(verifier, mockk<SlashCommandService>()))
            .build()

    private fun interactionsMockMvc(verifier: SlackSignatureVerifier): MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                SlackInteractionsController(
                    verifier,
                    mockk<SlackInteractionPayloadParser>(),
                    mockk<SlackInteractionService>(),
                ),
            ).build()

    private fun eventsRequest(
        body: ByteArray,
        signature: String,
    ): MockHttpServletRequestBuilder =
        post("/slack/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .header(TIMESTAMP_HEADER, TIMESTAMP)
            .header(SIGNATURE_HEADER, signature)

    private fun formRequest(
        path: String,
        body: ByteArray,
        signature: String,
    ): MockHttpServletRequestBuilder =
        post(path)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .content(body)
            .header(TIMESTAMP_HEADER, TIMESTAMP)
            .header(SIGNATURE_HEADER, signature)

    private fun bodyOfSize(bytes: Int): ByteArray = ByteArray(bytes) { 'a'.code.toByte() }

    /**
     * 구현과 독립적으로 참조 서명을 계산한다 — `v0=` + lowercase-hex(HMAC-SHA256(secret, `v0:{ts}:` + 원문바이트)).
     * [SlackSignatureVerifierTest] 의 `sign` 헬퍼와 동일하되, base string 을 String 으로 조립하지 않고
     * **원문 바이트를 그대로** [Mac.update] 에 흘려 넣는다(그것이 Slack 의 실제 계약이다).
     */
    private fun signBytes(
        secret: String,
        timestamp: String,
        body: ByteArray,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("v0:$timestamp:".toByteArray(Charsets.UTF_8))
        mac.update(body)
        return "v0=" + mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val INBOUND_CONTROLLERS =
            listOf(
                SlackEventsController::class.java,
                SlackCommandsController::class.java,
                SlackInteractionsController::class.java,
            )

        const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
        const val SIGNATURE_HEADER = "X-Slack-Signature"
        const val SIGNING_SECRET = "slack-inbound-body-guard-signing-secret-0123456789"

        /** 고정 기준 시각 + 그 시각의 timestamp — 재전송 윈도우(±300초) 안에 들어온다. */
        val T0: Instant = Instant.parse("2026-07-15T00:00:00Z")
        val TIMESTAMP: String = T0.epochSecond.toString()

        /** UTF-8 에서 절대 유효하지 않은 바이트(0xFF) — String 왕복 시 U+FFFD 로 치환되어 서명이 어긋난다. */
        val INVALID_UTF8_BYTE = byteArrayOf(0xFF.toByte())

        /** [SlackEventsController] 의 상한과 동일해야 한다(테스트 미러 — automation 웹훅 테스트 동형 관례). */
        const val EVENTS_MAX_BODY_BYTES = 64 * 1024
    }
}
