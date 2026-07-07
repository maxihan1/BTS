// Slack oauth.v2.access 응답을 우리 도메인 타입으로 매핑한 파싱 결과 VO (FR-SL-01 Task 3)

package com.bts.slack.oauth

import com.slack.api.methods.response.oauth.OAuthV2AccessResponse

/**
 * Slack `oauth.v2.access` 응답을 BTS 도메인 표현으로 옮긴 파싱 결과.
 *
 * slack-api-client 의 [OAuthV2AccessResponse] 는 Lombok `@Data` 로 생성된 toString 이
 * **평문 access_token 을 그대로 노출**한다. 이 VO 는 그 SDK 타입을 대체하는 안전한 경계로,
 * [toString] 에서 [accessToken] 을 마스킹한다(DEVELOPMENT.md §1.1.2 — 비밀값 로깅 금지).
 *
 * 이 타입은 "Slack 이 반환한 그대로"를 충실히 담는다(성공 필드는 모두 nullable). 설치 가능 여부
 * (필수 필드 존재·워크스페이스 설치 여부) 판정은 [com.bts.slack.domain.SlackInstall.fromToken] 이
 * 수행하고, 이 VO 는 판정하지 않는다(단일 책임).
 *
 * @property ok Slack 성공 플래그. false 면 [error] 에 코드가 담긴다.
 * @property error `ok:false` 일 때의 Slack 에러 코드(예: `invalid_code`). 성공 시 null.
 * @property accessToken **평문** 봇 토큰(`xoxb-…`). 잠깐만 보유하며 toString/로그 노출 금지(§1.1.2).
 * @property tokenType 토큰 종류(워크스페이스 봇 설치는 `bot`).
 * @property scope 발급된 봇 스코프 CSV.
 * @property botUserId 봇 사용자 id(`U…`).
 * @property appId Slack App id(`A…`).
 * @property teamId Slack 워크스페이스 id(`T…`). enterprise install 이면 응답에 team 이 없어 null.
 * @property teamName 워크스페이스 이름. team 부재 시 null.
 * @property isEnterpriseInstall org-wide(enterprise) 설치 여부. true 면 워크스페이스 설치가 아니다.
 */
data class SlackOAuthTokenResponse(
    val ok: Boolean,
    val error: String?,
    val accessToken: String?,
    val tokenType: String?,
    val scope: String?,
    val botUserId: String?,
    val appId: String?,
    val teamId: String?,
    val teamName: String?,
    val isEnterpriseInstall: Boolean,
) {
    /**
     * 평문 access_token 을 노출하지 않는 마스킹 toString (§1.1.2).
     * 값의 존재 여부만 드러내고 실제 토큰 문자열은 [REDACTED] 로 가린다.
     */
    override fun toString(): String =
        "SlackOAuthTokenResponse(ok=$ok, error=$error, accessToken=${mask(accessToken)}, " +
            "tokenType=$tokenType, scope=$scope, botUserId=$botUserId, appId=$appId, " +
            "teamId=$teamId, teamName=$teamName, isEnterpriseInstall=$isEnterpriseInstall)"

    companion object {
        private const val REDACTED = "<redacted>"

        /** 비밀값 존재 여부만 노출하고 값은 가린다(null 은 그대로, 값이 있으면 [REDACTED]). */
        private fun mask(secret: String?): String = if (secret == null) "null" else REDACTED

        /**
         * slack-api-client 의 [OAuthV2AccessResponse] 를 우리 타입으로 매핑한다.
         * team 이 null(enterprise install)이면 [teamId]/[teamName] 은 null 로 남는다.
         */
        fun from(sdk: OAuthV2AccessResponse): SlackOAuthTokenResponse =
            SlackOAuthTokenResponse(
                ok = sdk.isOk,
                error = sdk.error,
                accessToken = sdk.accessToken,
                tokenType = sdk.tokenType,
                scope = sdk.scope,
                botUserId = sdk.botUserId,
                appId = sdk.appId,
                teamId = sdk.team?.id,
                teamName = sdk.team?.name,
                isEnterpriseInstall = sdk.isEnterpriseInstall,
            )
    }
}
