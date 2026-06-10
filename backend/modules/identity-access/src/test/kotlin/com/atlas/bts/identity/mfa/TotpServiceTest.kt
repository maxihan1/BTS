// TotpService 단위 테스트 — samstevens 래핑(secret 생성/otpauth URI/QR/RFC 6238 코드 검증) (FR-MF-01 Task 3)

package com.atlas.bts.identity.mfa

import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.HashingAlgorithm
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * [TotpService] 단위 테스트.
 *
 * RFC 6238(Time-based One-Time Password) 파라미터(SHA1 · 6자리 · 30초 주기)로 TOTP secret 생성,
 * `otpauth://` provisioning URI, QR PNG data URI, 코드 검증을 검증한다.
 *
 * ## 시각 결정성
 * 코드 검증의 현재 time-step 계산은 주입 [Clock]/명시 [Instant] 기준이다. `Instant.now()` 하드코딩은
 * 특정 시각에 깨지는 time-bomb 이 되므로, 알려진 secret 의 정답 코드/±1 window 테스트는 고정 시각으로
 * 결정적으로 수행한다(메모리 authcontroller-revokesession-timebomb).
 *
 * ## 기대 코드 산출
 * 정답 코드는 라이브러리의 [DefaultCodeGenerator] 로 동일 파라미터(SHA1/6자리)·동일 time-step 에 대해
 * 직접 계산한다 — 하드코딩 상수에 의존하지 않고 samstevens 동작과 정합을 검증한다.
 */
class TotpServiceTest {
    private val fixedNow: Instant = Instant.parse("2024-06-10T07:33:20Z") // epochSecond=1718004800
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val service = TotpService(clock)

    // 유효한 base32 secret (라이브러리 코드 생성기 입력으로 사용).
    private val knownSecret = "ABCDEFGHIJKLMNOP"
    private val codeGenerator = DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)

    // ── generateSecret ────────────────────────────────────────────────────────

    @Test
    fun `generateSecret 는 비공백 base32 secret 을 생성한다`() {
        val secret = service.generateSecret()

        assertThat(secret).isNotBlank()
        // base32 알파벳(A-Z2-7)만 포함.
        assertThat(secret).matches("[A-Z2-7]+")
    }

    @Test
    fun `generateSecret 는 매번 다른 secret 을 생성한다`() {
        assertThat(service.generateSecret()).isNotEqualTo(service.generateSecret())
    }

    // ── otpauthUri ────────────────────────────────────────────────────────────

    @Test
    fun `otpauthUri 는 issuer=BTS · digits=6 · period=30 · SHA1 형식을 가진다`() {
        val uri = service.otpauthUri(knownSecret, "alice@example.com")

        assertThat(uri).startsWith("otpauth://totp/BTS:")
        assertThat(uri).contains("secret=$knownSecret")
        assertThat(uri).contains("issuer=BTS")
        assertThat(uri).contains("algorithm=SHA1")
        assertThat(uri).contains("digits=6")
        assertThat(uri).contains("period=30")
    }

    @Test
    fun `otpauthUri 는 label 의 특수문자를 URL 인코딩한다`() {
        val uri = service.otpauthUri(knownSecret, "alice@example.com")

        // '@' 는 %40 으로 인코딩. 'BTS:' 콜론은 issuer 구분자로 그대로 유지.
        assertThat(uri).startsWith("otpauth://totp/BTS:alice%40example.com?")
        assertThat(uri).doesNotContain("alice@example.com")
    }

    @Test
    fun `otpauthUri 는 label 의 공백을 %20 으로 인코딩한다`() {
        val uri = service.otpauthUri(knownSecret, "Alice Kim")

        assertThat(uri).startsWith("otpauth://totp/BTS:Alice%20Kim?")
    }

    // ── qrPngDataUri ──────────────────────────────────────────────────────────

    @Test
    fun `qrPngDataUri 는 PNG data URI 접두를 가진다`() {
        val uri = service.otpauthUri(knownSecret, "alice@example.com")

        val dataUri = service.qrPngDataUri(uri)

        assertThat(dataUri).startsWith("data:image/png;base64,")
        // base64 페이로드가 비어있지 않음.
        assertThat(dataUri.removePrefix("data:image/png;base64,")).isNotBlank()
    }

    // ── verify (RFC 6238 코드 검증) ────────────────────────────────────────────

    @Test
    fun `verify 는 현재 time-step 의 정답 코드를 통과시킨다`() {
        val correctCode = codeGenerator.generate(knownSecret, service.currentTimeStep(fixedNow))

        assertThat(service.verify(knownSecret, correctCode, fixedNow)).isTrue()
    }

    @Test
    fun `verify 는 주입 Clock 의 현재 시각으로도 정답 코드를 통과시킨다`() {
        // atInstant 미지정 오버로드는 주입 clock 의 현재 시각(fixedNow)을 사용한다.
        val correctCode = codeGenerator.generate(knownSecret, service.currentTimeStep(fixedNow))

        assertThat(service.verify(knownSecret, correctCode)).isTrue()
    }

    @Test
    fun `verify 는 오답 코드를 거부한다`() {
        assertThat(service.verify(knownSecret, "000000", fixedNow)).isFalse()
    }

    @Test
    fun `verify 는 직전 time-step 코드를 통과시킨다 (±1 window)`() {
        val prevStepCode = codeGenerator.generate(knownSecret, service.currentTimeStep(fixedNow) - 1)

        assertThat(service.verify(knownSecret, prevStepCode, fixedNow)).isTrue()
    }

    @Test
    fun `verify 는 직후 time-step 코드를 통과시킨다 (±1 window)`() {
        val nextStepCode = codeGenerator.generate(knownSecret, service.currentTimeStep(fixedNow) + 1)

        assertThat(service.verify(knownSecret, nextStepCode, fixedNow)).isTrue()
    }

    @Test
    fun `verify 는 ±1 window 를 벗어난 time-step 코드를 거부한다`() {
        val farStepCode = codeGenerator.generate(knownSecret, service.currentTimeStep(fixedNow) + 2)

        assertThat(service.verify(knownSecret, farStepCode, fixedNow)).isFalse()
    }

    // ── currentTimeStep ───────────────────────────────────────────────────────

    @Test
    fun `currentTimeStep 은 epochSecond를 30으로 나눈 몫이다`() {
        // fixedNow.epochSecond = 1718004800, 1718004800 / 30 = 57266826
        assertThat(service.currentTimeStep(fixedNow)).isEqualTo(57266826L)
    }
}
