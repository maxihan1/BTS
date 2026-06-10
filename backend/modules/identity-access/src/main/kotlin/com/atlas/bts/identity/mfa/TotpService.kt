// TOTP(RFC 6238) secret 생성 · otpauth provisioning URI · QR · 코드 검증을 담당하는 서비스 (FR-MF-01 Task 3)

package com.atlas.bts.identity.mfa

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.DefaultCodeVerifier
import dev.samstevens.totp.code.HashingAlgorithm
import dev.samstevens.totp.secret.DefaultSecretGenerator
import dev.samstevens.totp.time.TimeProvider
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * TOTP(Time-based One-Time Password, RFC 6238) 기반 MFA 의 암호 연산 서비스.
 *
 * `dev.samstevens.totp` 라이브러리를 래핑해 secret 생성, Authenticator 앱용 `otpauth://` provisioning
 * URI, QR PNG data URI, 코드 검증을 제공한다. 영속화·오케스트레이션은 상위 서비스(Task 8)가 담당하며,
 * 본 서비스는 순수 연산만 한다(상태 없음).
 *
 * ## RFC 6238 파라미터 (고정)
 * - 해시 알고리즘: SHA1 ([ALGORITHM]) — Authenticator 앱 기본값, 상호운용성 최대.
 * - 자릿수: 6 ([DIGITS]).
 * - 주기: 30초 ([PERIOD_SECONDS]) — time-step 길이.
 * - 허용 오차: ±1 time-step ([ALLOWED_DISCREPANCY]) — 시계 오차/입력 지연 흡수.
 * - issuer: `BTS` ([ISSUER]) — otpauth label/issuer 파라미터에 고정.
 *
 * ## 시각 의존 — Clock 주입
 * 코드 검증의 현재 time-step 계산은 주입 [clock] 또는 명시 [Instant] 기준이다. 핸들러에서
 * `Instant.now()` 를 하드코딩하면 특정 시각에 깨지는 time-bomb 이 되므로, 테스트는 `Clock.fixed` 로
 * 고정한다(메모리 authcontroller-revokesession-timebomb).
 *
 * ## 보안 주의
 * secret·코드 등 비밀값은 로깅하지 않는다(§1.1.2). [verify] 는 잘못된 코드에 대해 단순히 `false` 를
 * 반환한다(불명은 거부 — fail-closed).
 *
 * ## 참조
 * - FR-MF-01 Task 3, SDD §19.7.1 (지원 방식 — TOTP 필수)
 * - RFC 6238 (TOTP), RFC 4226 (HOTP)
 */
@Service
class TotpService(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val secretGenerator = DefaultSecretGenerator()
    private val codeGenerator = DefaultCodeGenerator(ALGORITHM, DIGITS)

    /**
     * 새 TOTP secret(base32)을 생성한다.
     *
     * @return base32(A-Z2-7) 인코딩된 비공백 secret. 암호화 저장은 상위 서비스 책임.
     */
    fun generateSecret(): String = secretGenerator.generate()

    /**
     * Authenticator 앱이 스캔할 `otpauth://` provisioning URI 를 만든다.
     *
     * 형식: `otpauth://totp/BTS:{label}?secret=...&issuer=BTS&algorithm=SHA1&digits=6&period=30`.
     * `BTS:` 콜론은 issuer 구분자로 그대로 두고, [label] 본문만 URL 인코딩한다(otpauth 라벨 규약).
     *
     * @param secret [generateSecret] 가 만든 base32 secret.
     * @param label 계정 식별자(보통 이메일). 특수문자는 URL 인코딩된다.
     * @return otpauth provisioning URI 문자열.
     */
    fun otpauthUri(
        secret: String,
        label: String,
    ): String {
        val encodedLabel = urlEncode(label)
        return "otpauth://totp/$ISSUER:$encodedLabel" +
            "?secret=$secret" +
            "&issuer=$ISSUER" +
            "&algorithm=${ALGORITHM.name}" +
            "&digits=$DIGITS" +
            "&period=$PERIOD_SECONDS"
    }

    /**
     * [otpauthUri] 문자열을 QR 코드 PNG 로 렌더링해 `data:image/png;base64,...` data URI 로 반환한다.
     *
     * QR 에는 전달된 URI 문자열이 그대로 인코딩되므로, [otpauthUri] 반환값과 QR 내용이 일치한다.
     *
     * @param otpauthUri [otpauthUri] 가 만든 provisioning URI.
     * @return `data:image/png;base64,` 접두의 PNG data URI(서버 렌더 — 프론트는 그대로 `<img>` 표시).
     */
    fun qrPngDataUri(otpauthUri: String): String {
        val bitMatrix = QRCodeWriter().encode(otpauthUri, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX)
        val png =
            ByteArrayOutputStream().use { out ->
                MatrixToImageWriter.writeToStream(bitMatrix, "PNG", out)
                out.toByteArray()
            }
        return "data:image/png;base64,${Base64.getEncoder().encodeToString(png)}"
    }

    /**
     * 주입 [clock] 의 현재 시각 기준으로 [code] 를 검증한다.
     *
     * @param secret base32 secret.
     * @param code 사용자가 입력한 6자리 코드.
     * @return ±1 time-step 이내에서 유효하면 `true`, 아니면 `false`(불명은 거부).
     */
    fun verify(
        secret: String,
        code: String,
    ): Boolean = verify(secret, code, clock.instant())

    /**
     * 명시한 [atInstant] 기준으로 [code] 를 검증한다(결정적 테스트/replay step 계산용).
     *
     * @param secret base32 secret.
     * @param code 사용자가 입력한 6자리 코드.
     * @param atInstant 현재 time-step 계산 기준 시각.
     * @return ±1 time-step ([ALLOWED_DISCREPANCY]) 이내에서 유효하면 `true`, 아니면 `false`.
     */
    fun verify(
        secret: String,
        code: String,
        atInstant: Instant,
    ): Boolean {
        val timeProvider = TimeProvider { atInstant.epochSecond }
        val verifier =
            DefaultCodeVerifier(codeGenerator, timeProvider).apply {
                setTimePeriod(PERIOD_SECONDS)
                setAllowedTimePeriodDiscrepancy(ALLOWED_DISCREPANCY)
            }
        return verifier.isValidCode(secret, code)
    }

    /**
     * [atInstant] 의 TOTP time-step(`floor(epochSecond / 30)`)을 계산한다.
     *
     * replay 방어(Task 4/8 의 `last_verified_step` 비교)에서 동일 time-step 재사용을 막는 데 쓴다.
     *
     * @param atInstant 기준 시각.
     * @return time-step 번호.
     */
    fun currentTimeStep(atInstant: Instant): Long = atInstant.epochSecond / PERIOD_SECONDS

    private fun urlEncode(value: String): String =
        // otpauth 라벨 규약 — 공백은 '+' 가 아니라 %20 으로 인코딩한다.
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    internal companion object {
        /** otpauth issuer/label 에 고정되는 발급자명. */
        const val ISSUER = "BTS"

        /** RFC 6238 해시 알고리즘 — SHA1(Authenticator 앱 기본, 상호운용성 최대). */
        val ALGORITHM: HashingAlgorithm = HashingAlgorithm.SHA1

        /** RFC 6238 코드 자릿수. */
        const val DIGITS = 6

        /** RFC 6238 time-step 길이(초). */
        const val PERIOD_SECONDS = 30

        /** 허용 시간 오차 — ±1 time-step(시계 오차/입력 지연 흡수). */
        const val ALLOWED_DISCREPANCY = 1

        /** QR PNG 한 변 픽셀 크기. */
        const val QR_SIZE_PX = 250
    }
}
