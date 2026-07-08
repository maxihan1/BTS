// Slack oauth.v2.access 토큰 교환 + authorize URL 생성 클라이언트 (FR-SL-01 Task 6)

package com.bts.slack.oauth

import com.bts.slack.config.SlackProperties
import com.slack.api.Slack
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Slack App OAuth 2.0 설치 흐름의 두 단계(authorize URL 발급 · code↔token 교환)를 담당하는 클라이언트
 * (FR-SL-01 Task 6).
 *
 * 인터페이스로 분리해 Task 8의 `SlackInstallService`가 테스트에서 fake를 주입할 수 있게 한다.
 */
interface SlackOAuthClient {
    /**
     * Slack이 콜백으로 넘긴 1회용 `code`를 `oauth.v2.access`로 교환해 bot token 등 설치 결과를 받는다.
     *
     * `ok:false`(예: `invalid_code`)는 예외로 승격하지 않고 [SlackOAuthTokenResponse.ok] = false로 반환한다.
     * 설치 가능 여부 판정은 상위 서비스(Task 8)의 책임이다(단일 책임).
     *
     * @param code Slack 콜백의 1회용 인가 코드.
     * @return 교환 결과. `ok:false`면 [SlackOAuthTokenResponse.error]에 Slack 에러 코드가 담긴다.
     * @throws SlackOAuthExchangeException 네트워크/전송 오류로 교환 자체가 실패한 경우.
     * @throws IllegalStateException client 설정([SlackProperties]) 미완비 시.
     */
    fun exchangeCode(code: String): SlackOAuthTokenResponse

    /**
     * 사용자를 보낼 Slack authorize URL을 만든다. `client_id`·`scope`·`state`·`redirect_uri`를 실는다.
     *
     * @param state CSRF 방어용 서명 state([SlackOAuthStateSigner]가 발급).
     * @return `https://slack.com/oauth/v2/authorize?...` 형식의 완성된 URL.
     * @throws IllegalStateException client 설정([SlackProperties]) 미완비 시.
     */
    fun buildAuthorizeUrl(state: String): String
}

/**
 * [SlackOAuthClient] 기본 구현. Slack 공식 SDK(`slack-api-client`)의 [MethodsClient]로 토큰을 교환한다.
 *
 * ## 아웃바운드 대상은 Slack 고정 호스트 (SSRF 무관, ADR D2 / 스펙 N5)
 * 교환 대상은 항상 `slack.com`이고 사용자 입력 URL이 없으므로 SSRF 표면이 없다. 따라서 webhook의
 * `OutboundHttpClientConfig`(사용자 지정 URL 전송용) 대신 SDK 내장 HTTP 클라이언트를 사용한다.
 *
 * ## 부팅 안전성 (N4)
 * [methods] 기본값 `Slack.getInstance().methods()`는 자격증명·네트워크 없이 생성되므로, MethodsClient
 * 빈이 없어도 Spring이 Kotlin 기본 인자로 채워 부팅이 깨지지 않는다([SlackOAuthStateSigner]의 `clock`
 * 기본값과 동일 패턴). 테스트는 [methods]에 stub을 주입한다.
 *
 * ## 비밀값 로깅 금지 (§1.1.2)
 * client_secret과 발급된 bot token(access_token)은 **절대 로깅하지 않는다**. 교환 응답은 마스킹
 * toString을 가진 [SlackOAuthTokenResponse]로만 다루고, [SlackProperties.clientSecret]을 직접 로그에
 * 남기지 않는다. 교환 실패 예외([SlackOAuthExchangeException]) 메시지에도 요청 값을 담지 않는다.
 */
@Component
class DefaultSlackOAuthClient(
    private val properties: SlackProperties,
    private val methods: MethodsClient = Slack.getInstance().methods(),
) : SlackOAuthClient {
    override fun exchangeCode(code: String): SlackOAuthTokenResponse {
        properties.requireConfiguredForExchange()
        val response =
            try {
                methods.oauthV2Access { req ->
                    req.clientId(properties.clientId)
                        .clientSecret(properties.clientSecret)
                        .code(code)
                        .redirectUri(properties.redirectUri)
                }
            } catch (e: IOException) {
                throw SlackOAuthExchangeException(cause = e)
            } catch (e: SlackApiException) {
                throw SlackOAuthExchangeException(cause = e)
            }
        return SlackOAuthTokenResponse.from(response)
    }

    override fun buildAuthorizeUrl(state: String): String {
        properties.requireConfiguredForAuthorize()
        val query =
            listOf(
                "client_id" to properties.clientId,
                "scope" to properties.scopes,
                "state" to state,
                "redirect_uri" to properties.redirectUri,
            ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        return "$AUTHORIZE_ENDPOINT?$query"
    }

    /** 쿼리 파라미터 값을 퍼센트 인코딩한다(예약 문자 `:`·`,`·`/` → `%3A`·`%2C`·`%2F`). */
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val AUTHORIZE_ENDPOINT = "https://slack.com/oauth/v2/authorize"
    }
}

/**
 * `oauth.v2.access` 호출 자체가 전송/네트워크 오류로 실패했음을 나타내는 도메인 예외.
 *
 * `ok:false`(Slack이 응답을 정상 반환한 실패)와는 구분된다 — 그 경우는 [SlackOAuthTokenResponse]로 반환한다.
 * 메시지는 요청 값을 담지 않는 **일반 메시지**로 고정한다. Task 8은 이 예외를 설치 실패로 매핑하되
 * message/cause를 HTTP 응답 detail로 노출하지 않는다(교훈 fr-pm-04-guard-exception-message-http-leak).
 *
 * @param cause 진단용 원인(IOException / SlackApiException). HTTP 응답에는 노출하지 않는다.
 */
class SlackOAuthExchangeException(
    cause: Throwable? = null,
) : RuntimeException("Slack OAuth token exchange failed", cause)
