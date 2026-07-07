// Slack OAuth 설치 콜백을 STATELESS로 잇기 위한 서명된 self-contained state 발급·검증기

package com.bts.slack.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Slack OAuth 설치 플로우에서 인가 요청 → 콜백을 잇는 `state` 파라미터를 **서버 세션 없이**
 * 서명된 self-contained 토큰으로 발급·검증한다 (FR-SL-01 Task 4).
 *
 * ## STATELESS 정합 (핵심 설계)
 * BTS는 STATELESS JWT라 서버 세션에 OAuth `state`·`installedBy`를 담을 수 없다. 대신 설치를 시작한
 * 사용자 id를 서명된 payload에 실어 콜백에서 복원한다. 위조를 막기 위해 payload에 HMAC-SHA256 서명을
 * 덧붙인다(서버만 아는 [stateKey]로 서명 → 클라이언트는 payload를 바꾸면 서명이 깨진다).
 *
 * ```
 * state    = base64url(payload) + "." + base64url(HMAC-SHA256(payload_bytes, stateKey))
 * payload  = { nonce, installedBy(UUID), exp(epoch millis, 발급시각 + 10분) }
 * ```
 *
 * ## 검증 절차
 * 1. `.`로 두 조각 분리 → 형식 위반이면 거부.
 * 2. payload_bytes로 서명을 재계산해 **상수 시간 비교**([MessageDigest.isEqual])로 일치 확인 —
 *    타이밍 공격으로 서명 바이트를 한 자리씩 알아내지 못하게 한다.
 * 3. [Clock] 기준 현재 시각이 `exp`를 지났으면 만료로 거부.
 * 통과 시 payload에서 `installedBy`를 복원해 반환한다.
 *
 * ## 리플레이(재사용)에 대하여 (리뷰 C1)
 * 이 state는 무상태 서명이라 서버가 사용 이력을 남기지 않는다. 따라서 **10분 TTL 이내라면 같은 state가
 * 여러 번 검증을 통과할 수 있다**. 그러나 콜백에서 함께 오는 Slack `code`는 Slack 측에서 **1회용**으로
 * 소진되므로, 동일 state를 재전송해도 두 번째 `oauth.v2.access` 교환은 Slack이 거부한다. 즉 state 자체의
 * 무상태 리플레이 가능성은 Slack code 1회성으로 실효 방어된다(짧은 TTL이 잔여 창을 추가로 좁힌다).
 *
 * ## 부팅 안전성 — 항상 등록 + 사용 시점 검증
 * [stateKey]가 비어 있어도 **생성자에서 예외를 던지지 않는다**. 키 미설정 환경(슬라이스/통합 테스트)에서
 * 컴포넌트 스캔만으로 부팅이 깨지지 않게 하기 위함이다([com.bts.shared.crypto.SecretEncryptor] /
 * `OidcEncryptionConfig`의 부팅 안전 패턴과 동일). 실제 키가 필요한 [issue]/[verify] **호출 시점**에
 * [check]로 미설정을 검증해 명확한 [IllegalStateException]을 던진다. 이 예외 메시지에는 키/payload를
 * 포함하지 않는다.
 *
 * ## 보안 주의 (DEVELOPMENT.md §1.1.2)
 * [stateKey]와 payload 내용(nonce·installedBy·exp)은 **절대 로깅하지 않는다**. 검증 실패 예외
 * ([SlackStateInvalidException]) 메시지에도 내부 사정을 담지 않는다(일반 메시지). 서명 비교는 반드시
 * 상수 시간 비교를 사용한다.
 *
 * @param stateKey HMAC 서명 키. `bts.slack.state-key`(미설정 시 빈 문자열)에서 주입된다.
 * @param clock `exp` 산정·만료 판정용 시계. slack 모듈에 Clock 빈이 없으므로 기본값 [Clock.systemUTC]를
 *   둔다(컴포넌트 스캔 시 `NoSuchBeanDefinitionException` 방지). 테스트는 고정 인스턴스를 주입한다.
 */
@Service
class SlackOAuthStateSigner(
    @param:Value("\${bts.slack.state-key:}") private val stateKey: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val secureRandom = SecureRandom()
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    /**
     * [installedBy] 사용자를 실은 서명된 state를 발급한다.
     *
     * @param installedBy 설치를 시작한 사용자 id. 콜백에서 [verify]로 복원된다.
     * @return `base64url(payload).base64url(signature)` 형식의 state 문자열.
     * @throws IllegalStateException [stateKey] 미설정 시.
     */
    fun issue(installedBy: UUID): String {
        requireKeyConfigured()
        val payloadBytes =
            objectMapper.writeValueAsBytes(
                StatePayload(
                    nonce = generateNonce(),
                    installedBy = installedBy.toString(),
                    exp = clock.millis() + STATE_TTL.toMillis(),
                ),
            )
        val signature = hmac(payloadBytes)
        return urlEncoder.encodeToString(payloadBytes) + SEPARATOR + urlEncoder.encodeToString(signature)
    }

    /**
     * state의 서명·만료를 검증하고 통과 시 `installedBy`를 복원한다.
     *
     * @param state [issue]가 발급한 state 문자열(콜백 쿼리 파라미터).
     * @return 복원된 `installedBy` 사용자 id.
     * @throws IllegalStateException [stateKey] 미설정 시.
     * @throws SlackStateInvalidException 형식 위반 / 서명 불일치 / 만료 시.
     */
    fun verify(state: String): UUID {
        requireKeyConfigured()
        val parts = state.split(SEPARATOR)
        if (parts.size != EXPECTED_PARTS || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw SlackStateInvalidException()
        }
        val payloadBytes: ByteArray
        val providedSignature: ByteArray
        try {
            payloadBytes = urlDecoder.decode(parts[0])
            providedSignature = urlDecoder.decode(parts[1])
        } catch (e: IllegalArgumentException) {
            throw SlackStateInvalidException(cause = e)
        }
        // 상수 시간 비교 — 서명 바이트를 타이밍으로 한 자리씩 유추하지 못하게 한다.
        if (!MessageDigest.isEqual(hmac(payloadBytes), providedSignature)) {
            throw SlackStateInvalidException()
        }
        // 서명이 일치하면 payload는 우리가 발급한 그대로이므로(HMAC 무결성) JSON/UUID 파싱은 실패하지 않는다.
        val payload: StatePayload = objectMapper.readValue(payloadBytes)
        if (clock.millis() > payload.exp) {
            throw SlackStateInvalidException()
        }
        return UUID.fromString(payload.installedBy)
    }

    /** [stateKey] 미설정 시 키/payload를 노출하지 않는 일반 [IllegalStateException]을 던진다. */
    private fun requireKeyConfigured() {
        check(stateKey.isNotEmpty()) { STATE_KEY_NOT_CONFIGURED_MESSAGE }
    }

    /** CSRF 방어를 위한 예측 불가 nonce. [SecureRandom] 16바이트를 base64url로 인코딩한다. */
    private fun generateNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    /** [data]에 대한 HMAC-SHA256 서명. [Mac]은 스레드 안전하지 않으므로 호출마다 새 인스턴스를 만든다. */
    private fun hmac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(stateKey.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    /**
     * 서명·검증 대상 state payload. 필드는 Slack state 길이 제약을 고려해 최소화한다.
     *
     * @param nonce 예측 불가 CSRF nonce.
     * @param installedBy 설치를 시작한 사용자 id(UUID 문자열).
     * @param exp 만료 시각(epoch millis).
     */
    private data class StatePayload(
        val nonce: String,
        val installedBy: String,
        val exp: Long,
    )

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val SEPARATOR = "."
        const val EXPECTED_PARTS = 2
        const val NONCE_BYTES = 16
        const val STATE_KEY_NOT_CONFIGURED_MESSAGE = "Slack OAuth state key not configured"
        val STATE_TTL: Duration = Duration.ofMinutes(10)
    }
}

/**
 * Slack OAuth state 검증 실패(형식 위반 / 서명 불일치 / 만료)를 나타내는 도메인 예외.
 *
 * 메시지는 내부 사정(존재 여부·실패 원인 구분)을 노출하지 않는 **일반 메시지**로 고정한다. Task 8의 콜백
 * 처리기는 이 예외를 일반 400 응답으로 매핑하고 message/cause를 HTTP detail로 그대로 노출하지 않는다
 * (교훈 fr-pm-04-guard-exception-message-http-leak).
 *
 * @param cause 진단용 원인(예: base64 디코딩 실패). HTTP 응답에는 노출하지 않는다.
 */
class SlackStateInvalidException(
    cause: Throwable? = null,
) : RuntimeException("invalid or expired Slack OAuth state", cause)
