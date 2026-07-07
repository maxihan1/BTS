// SlackOAuthStateSigner의 서명 발급·검증(위조/만료/형식 거부) 동작을 검증하는 단위 테스트

package com.bts.slack.oauth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [SlackOAuthStateSigner] 단위 테스트 (FR-SL-01 Task 4).
 *
 * STATELESS JWT 정합상 서버 세션 없이 self-contained 서명 state 로 installedBy 를 실어 나른다.
 * round-trip 복원 + 위조/만료/형식 거부를 검증한다. 만료는 [Clock] 을 고정·전진시켜 결정적으로 테스트한다.
 */
class SlackOAuthStateSignerTest {
    private val stateKey = "slack-oauth-state-hmac-key-for-tests-0123456789"
    private val t0 = Instant.parse("2026-07-07T00:00:00Z")
    private val fixedClock = Clock.fixed(t0, ZoneOffset.UTC)
    private val installedBy = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private fun signerAt(instant: Instant) = SlackOAuthStateSigner(stateKey, Clock.fixed(instant, ZoneOffset.UTC))

    @Test
    fun `발급한 state는 검증을 통과하고 installedBy를 복원한다`() {
        val signer = SlackOAuthStateSigner(stateKey, fixedClock)
        val state = signer.issue(installedBy)
        assertThat(signer.verify(state)).isEqualTo(installedBy)
    }

    @Test
    fun `발급 state는 점(.)으로 구분된 두 조각 형식이다`() {
        val signer = SlackOAuthStateSigner(stateKey, fixedClock)
        val state = signer.issue(installedBy)
        assertThat(state.count { it == '.' }).isEqualTo(1)
    }

    @Test
    fun `payload를 변조하면 서명 불일치로 거부한다`() {
        val signer = SlackOAuthStateSigner(stateKey, fixedClock)
        val state = signer.issue(installedBy)
        val (payload, sig) = state.split(".")
        val tampered = flipFirstChar(payload) + "." + sig
        assertThatThrownBy { signer.verify(tampered) }
            .isInstanceOf(SlackStateInvalidException::class.java)
    }

    @Test
    fun `서명을 변조하면 거부한다`() {
        val signer = SlackOAuthStateSigner(stateKey, fixedClock)
        val state = signer.issue(installedBy)
        val (payload, sig) = state.split(".")
        val tampered = payload + "." + flipFirstChar(sig)
        assertThatThrownBy { signer.verify(tampered) }
            .isInstanceOf(SlackStateInvalidException::class.java)
    }

    @Test
    fun `다른 키로 검증하면 서명 불일치로 거부한다`() {
        val state = SlackOAuthStateSigner(stateKey, fixedClock).issue(installedBy)
        val otherKey = SlackOAuthStateSigner("completely-different-key-value-987654321", fixedClock)
        assertThatThrownBy { otherKey.verify(state) }
            .isInstanceOf(SlackStateInvalidException::class.java)
    }

    @Test
    fun `TTL 이내에는 검증을 통과한다`() {
        val state = signerAt(t0).issue(installedBy)
        val within = signerAt(t0.plus(Duration.ofMinutes(9)))
        assertThat(within.verify(state)).isEqualTo(installedBy)
    }

    @Test
    fun `exp가 지나면 만료로 거부한다`() {
        val state = signerAt(t0).issue(installedBy)
        val later = signerAt(t0.plus(Duration.ofMinutes(11)))
        assertThatThrownBy { later.verify(state) }
            .isInstanceOf(SlackStateInvalidException::class.java)
    }

    @Test
    fun `점이 없는 형식은 거부한다`() {
        val signer = SlackOAuthStateSigner(stateKey, fixedClock)
        assertThatThrownBy { signer.verify("no-dot-present") }
            .isInstanceOf(SlackStateInvalidException::class.java)
    }

    @Test
    fun `base64가 깨진 형식은 거부한다`() {
        val signer = SlackOAuthStateSigner(stateKey, fixedClock)
        assertThatThrownBy { signer.verify("@@@.@@@") }
            .isInstanceOf(SlackStateInvalidException::class.java)
    }

    @Test
    fun `키 미설정이면 발급 시 명확한 예외를 던진다`() {
        val unconfigured = SlackOAuthStateSigner("", fixedClock)
        assertThatThrownBy { unconfigured.issue(installedBy) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `키 미설정이면 검증 시 명확한 예외를 던진다`() {
        val unconfigured = SlackOAuthStateSigner("", fixedClock)
        assertThatThrownBy { unconfigured.verify("any.thing") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    /** 첫 문자를 다른 base64url 문자로 바꿔 디코딩은 되지만 바이트는 달라지게 한다(서명 불일치 유도). */
    private fun flipFirstChar(s: String): String {
        val replacement = if (s[0] == 'A') 'B' else 'A'
        return replacement + s.substring(1)
    }
}
