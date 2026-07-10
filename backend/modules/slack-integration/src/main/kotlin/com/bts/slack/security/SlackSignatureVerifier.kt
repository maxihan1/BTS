// Slack Events API 요청 서명(X-Slack-Signature)을 상수시간 HMAC 비교로 검증하는 컴포넌트 (FR-SL-03 Task 1)

package com.bts.slack.security

import com.bts.slack.config.SlackProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Slack이 우리 서버로 보내는 서버-투-서버 요청(`POST /slack/events` 등)이 **정말 Slack에서 왔는지**를
 * 서명으로 검증한다 (FR-SL-03 ADR D6). Slack은 요청마다 `X-Slack-Request-Timestamp`와
 * `X-Slack-Signature`(`v0=HMAC-SHA256(signing_secret, "v0:{timestamp}:{raw_body}")`)를 붙여 보낸다.
 * 이 검증기는 서버만 아는 signing secret으로 같은 서명을 재계산해 일치하면 진짜 Slack 요청으로 판정한다.
 *
 * ## 왜 상수 시간 비교인가 ([MessageDigest.isEqual])
 * 서명 비교를 `String.equals`/`==`로 하면 앞자리부터 다른 순간 즉시 false가 되어, 응답 시간 차이로 서명
 * 바이트를 한 자리씩 알아내는 **타이밍 공격**이 가능해진다. [MessageDigest.isEqual]은 길이가 같으면 전체를
 * 훑어 비교해 이 누출을 막는다([com.bts.slack.oauth.SlackOAuthStateSigner]와 동일 원칙).
 *
 * ## 왜 재전송(replay) 윈도우인가
 * 서명이 유효해도 오래된 요청을 가로채 재전송하면 위험하다. `timestamp`가 현재 시각 기준 ±[REPLAY_WINDOW_SECONDS]초
 * 밖이면 재전송으로 간주해 거부한다(Slack 권장 5분). 시각 판정은 하드코딩된 `Instant.now()`가 아니라 주입된
 * [clock]으로 수행해 특정 날짜에 깨지는 time-bomb을 피하고 테스트에서 [Clock.fixed]로 고정한다.
 *
 * ## 왜 미설정이면 거부(fail-closed)인가
 * signing secret이 비어 있으면(미설정) 검증을 **스킵하지 않고 항상 false로 거부**한다. "키가 없으니 통과"
 * 라는 fail-open 지름길은 무인증 엔드포인트를 통째로 여는 사고로 이어진다
 * (교훈 `use-time-validated-env-passes-boot-fails-on-use`). 부팅은 [SlackProperties]가 미설정이어도 빈을
 * 등록해 안전하게 통과시키고([profile-scoped-bean-boot-failure]), 거부는 이 검증 호출 시점에서 한다.
 *
 * ## 반환 계약 — 예외가 아니라 boolean 거부로 수렴
 * 헤더 누락·빈 문자열·`v0=` 접두 없음·비숫자 timestamp·윈도우 초과·서명 불일치·미설정은 **모두 `false`**로
 * 수렴한다(예외를 던지지 않는다). 컨트롤러(Task 11)가 `false`를 401로 매핑한다. 반환·로그에 signing secret이나
 * rawBody를 절대 담지 않는다(§1.1.2).
 *
 * @param slackProperties signing secret 보관처. 미설정 시 빈 문자열로 등록되며(부팅 안전) 검증 시점에 거부된다.
 * @param clock 재전송 윈도우 판정용 시계. slack 모듈에 Clock 빈이 없으므로 기본값 [Clock.systemUTC]를 둔다
 *   (컴포넌트 스캔 시 `NoSuchBeanDefinitionException` 방지). 테스트는 고정 인스턴스를 주입한다.
 */
@Component
class SlackSignatureVerifier(
    private val slackProperties: SlackProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Slack 요청 서명을 검증한다.
     *
     * @param timestampHeader `X-Slack-Request-Timestamp` 값(epoch seconds 문자열). null/빈/비숫자면 거부.
     * @param signatureHeader `X-Slack-Signature` 값(`v0=`+hex). null/빈/접두 없음/불일치면 거부.
     * @param rawBody 서명 대상 원문 바디(재조립 없이 수신 원문 그대로여야 함).
     * @return 진짜 Slack 요청으로 검증되면 `true`, 그 외 모든 경우 `false`(fail-closed).
     */
    @Suppress("ReturnCount") // 거부 사유별 guard clause가 보안 판정을 명확히 한다(정상 경로 1 + 거부 5)
    fun isValid(
        timestampHeader: String?,
        signatureHeader: String?,
        rawBody: String,
    ): Boolean {
        if (slackProperties.signingSecret.isBlank()) {
            return false
        }
        if (timestampHeader.isNullOrEmpty() || signatureHeader.isNullOrEmpty()) {
            return false
        }
        val timestamp = timestampHeader.toLongOrNull() ?: return false
        if (!isWithinReplayWindow(timestamp)) {
            return false
        }
        if (!signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false
        }
        val expected = computeSignature(timestampHeader, rawBody)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signatureHeader.toByteArray(Charsets.UTF_8),
        )
    }

    /** 요청 timestamp가 현재 시각 기준 ±[REPLAY_WINDOW_SECONDS]초 이내인지 확인한다(재전송 방어). */
    private fun isWithinReplayWindow(timestamp: Long): Boolean =
        abs(clock.instant().epochSecond - timestamp) <= REPLAY_WINDOW_SECONDS

    /**
     * `v0=` + lowercase-hex(HMAC-SHA256(signing_secret, "v0:{timestamp}:{rawBody}"))를 계산한다.
     * [Mac]은 스레드 안전하지 않으므로 호출마다 새 인스턴스를 만든다.
     */
    private fun computeSignature(
        timestamp: String,
        rawBody: String,
    ): String {
        val baseString = "$SIGNATURE_VERSION:$timestamp:$rawBody"
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(slackProperties.signingSecret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(baseString.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"

        /** base string의 버전 태그. base string = `v0:{timestamp}:{rawBody}`. */
        const val SIGNATURE_VERSION = "v0"

        /** `X-Slack-Signature` 값의 버전 접두. 서명 = `v0=`+hex. */
        const val SIGNATURE_PREFIX = "v0="

        /** 재전송 방어 허용 시각차(Slack 권장 5분). */
        const val REPLAY_WINDOW_SECONDS = 300L
    }
}
