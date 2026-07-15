// Slack Events API 요청 서명(X-Slack-Signature) 검증기의 유효/위조/만료/헤더누락/미설정 동작을 검증하는 단위 테스트

package com.bts.slack.security

import com.bts.slack.config.SlackProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [SlackSignatureVerifier] 단위 테스트 (FR-SL-03 Task 1).
 *
 * Slack Events API는 서버-투-서버 POST를 `X-Slack-Signature`(`v0=HMAC-SHA256(signing_secret, "v0:{ts}:{body}")`)와
 * `X-Slack-Request-Timestamp`로 인증한다. 유효 서명 통과 / 위조 거부 / 재전송 윈도우(±300초) 초과 거부 /
 * 헤더 누락·빈 문자열·`v0=` 접두 없음·비숫자 timestamp 거부 / **signing secret 미설정 시 항상 거부**(스킵 아님)를
 * 검증한다. 시각 의존 판정은 [Clock] 을 고정해 결정적으로 테스트한다.
 */
class SlackSignatureVerifierTest {
    private val signingSecret = "8f742231b10e8888abcd99yyyzzz85a5"
    private val t0 = Instant.parse("2026-07-11T00:00:00Z")
    private val body = """{"type":"event_callback","event":{"type":"link_shared"}}"""

    @Test
    fun `유효한 서명과 최신 timestamp는 통과한다`() {
        val ts = t0.epochSecond.toString()
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid(ts, sign(signingSecret, ts, body), body)).isTrue()
    }

    @Test
    fun `정확히 300초 경계는 통과한다`() {
        val ts = t0.minusSeconds(300).epochSecond.toString()
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid(ts, sign(signingSecret, ts, body), body)).isTrue()
    }

    @Test
    fun `위조된 서명은 거부한다`() {
        val ts = t0.epochSecond.toString()
        val forged = sign(signingSecret, ts, body).dropLast(1) + "0"
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid(ts, forged, body)).isFalse()
    }

    @Test
    fun `다른 secret으로 서명하면 거부한다`() {
        val ts = t0.epochSecond.toString()
        val wrong = sign("completely-different-secret-value", ts, body)
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid(ts, wrong, body)).isFalse()
    }

    @Test
    fun `timestamp가 5분을 초과하면 거부한다`() {
        val ts = t0.minusSeconds(301).epochSecond.toString()
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid(ts, sign(signingSecret, ts, body), body)).isFalse()
    }

    @Test
    fun `미래 timestamp도 윈도우 밖이면 거부한다`() {
        val ts = t0.plusSeconds(301).epochSecond.toString()
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid(ts, sign(signingSecret, ts, body), body)).isFalse()
    }

    @Test
    fun `timestamp 헤더가 null이면 거부한다`() {
        val ts = t0.epochSecond.toString()
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid(null, sign(signingSecret, ts, body), body)).isFalse()
    }

    @Test
    fun `signature 헤더가 null이면 거부한다`() {
        val ts = t0.epochSecond.toString()
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid(ts, null, body)).isFalse()
    }

    @Test
    fun `빈 문자열 헤더는 거부한다`() {
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid("", "", body)).isFalse()
    }

    @Test
    fun `v0 접두가 없는 서명 형식은 거부한다`() {
        val ts = t0.epochSecond.toString()
        val noPrefix = sign(signingSecret, ts, body).removePrefix("v0=")
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid(ts, noPrefix, body)).isFalse()
    }

    @Test
    fun `비숫자 timestamp는 예외가 아니라 거부로 수렴한다`() {
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid("not-a-number", sign(signingSecret, "not-a-number", body), body)).isFalse()
    }

    // ── 원문 바이트 경로 (정본) ─────────────────────────────────────────────────

    @Test
    fun `원문 바이트에 대한 유효한 서명은 통과한다`() {
        val ts = t0.epochSecond.toString()
        val rawBytes = body.toByteArray(Charsets.UTF_8)
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid(ts, sign(signingSecret, ts, body), rawBytes)).isTrue()
    }

    @Test
    fun `유효한 UTF-8 이 아닌 원문도 바이트 경로면 통과한다 - String 왕복은 같은 서명을 거부한다`() {
        // 0xFF 는 UTF-8 에서 절대 유효하지 않은 바이트다. Slack 서명은 수신 원문 **바이트**에 대해 계산되므로
        // 바이트 경로는 통과해야 한다. 반면 String 왕복(디코드 → toByteArray)은 0xFF 를 U+FFFD(EF BF BD)로
        // 치환해 HMAC 대상 바이트를 바꿔버리므로 같은 서명을 거부한다 — 컨트롤러가 바이트 경로를 써야 하는 이유.
        val ts = t0.epochSecond.toString()
        val rawBytes = body.toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte())
        val signature = signBytes(signingSecret, ts, rawBytes)
        val verifier = verifierAt(t0, signingSecret)

        assertThat(verifier.isValid(ts, signature, rawBytes)).isTrue()
        assertThat(verifier.isValid(ts, signature, String(rawBytes, Charsets.UTF_8))).isFalse()
    }

    @Test
    fun `signing secret 미설정이면 형식이 유효해도 거부한다`() {
        val ts = t0.epochSecond.toString()
        // 형식상 완전한 서명을 줘도, 검증기 자신의 secret이 미설정이면 스킵 없이 거부해야 한다(fail-open 방지).
        val wellFormed = sign(signingSecret, ts, body)
        val verifier = verifierAt(t0, "")

        assertThat(verifier.isValid(ts, wellFormed, body)).isFalse()
    }

    private fun verifierAt(
        instant: Instant,
        secret: String,
    ) = SlackSignatureVerifier(
        propsWith(secret),
        Clock.fixed(instant, ZoneOffset.UTC),
    )

    private fun propsWith(secret: String) =
        SlackProperties(
            clientId = "",
            clientSecret = "",
            redirectUri = "",
            scopes = "chat:write",
            signingSecret = secret,
        )

    /** 구현과 동일한 방식으로 참조 서명을 독립 계산한다: `v0=` + lowercase-hex(HMAC-SHA256(secret, "v0:{ts}:{body}")). */
    private fun sign(
        secret: String,
        timestamp: String,
        rawBody: String,
    ): String = signBytes(secret, timestamp, rawBody.toByteArray(Charsets.UTF_8))

    /**
     * [sign] 의 바이트 판 — base string 을 String 으로 조립하지 않고 접두 바이트 + 원문 바이트를 그대로
     * 흘려 넣는다(그것이 Slack 의 실제 계약이라, 유효 UTF-8 이 아닌 원문에도 참조 서명을 만들 수 있다).
     */
    private fun signBytes(
        secret: String,
        timestamp: String,
        rawBody: ByteArray,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("v0:$timestamp:".toByteArray(Charsets.UTF_8))
        mac.update(rawBody)
        val hex = mac.doFinal().joinToString("") { "%02x".format(it) }
        return "v0=$hex"
    }
}
