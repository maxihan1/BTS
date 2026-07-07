// slack_installs 테이블 매핑 도메인 VO — 워크스페이스별 봇 설치 + 암호화 토큰 (FR-SL-01 Task 3)

package com.bts.slack.domain

import com.bts.slack.oauth.SlackOAuthTokenResponse
import java.util.UUID

/**
 * `slack_installs` 한 행에 대응하는 도메인 VO — 한 Slack 워크스페이스에 설치된 BTS App 상태.
 *
 * ## 봇 토큰 취급 (DEVELOPMENT.md §1.1)
 * 이 VO 는 **저장 직전 표현**이라 [botTokenEncrypted] 에 이미 **암호화된** 봇 토큰을 담는다.
 * 평문(`xoxb-…`)→암호문 변환은 서비스(Task 8)가 `@Qualifier("slackSecretEncryptor")`
 * [com.bts.shared.crypto.SecretEncryptor] 로 수행하고 그 결과만 [fromToken] 에 넘긴다.
 * 즉 이 VO 는 평문 토큰을 **절대 보유하지 않는다**(§1.1.1 평문 저장 금지). 방어적으로
 * [toString] 에서 암호문조차 마스킹한다(§1.1.2 — 로그 노출 최소화).
 *
 * @property teamId Slack 워크스페이스 id(`T…`) — upsert UNIQUE 기준.
 * @property teamName 워크스페이스 이름.
 * @property botUserId 봇 사용자 id(`U…`).
 * @property appId Slack App id(`A…`).
 * @property botTokenEncrypted AES-256-GCM 으로 암호화된 봇 토큰. **평문 금지**.
 * @property scopes 발급된 봇 스코프 CSV.
 * @property isEnterpriseInstall enterprise 설치 플래그. FR-SL-01 은 워크스페이스 설치만 지원해 항상 false.
 * @property installedBy 설치를 개시한 BTS 사용자 id(서명 state 에서 추출).
 */
data class SlackInstall(
    val teamId: String,
    val teamName: String,
    val botUserId: String,
    val appId: String,
    val botTokenEncrypted: String,
    val scopes: String,
    val isEnterpriseInstall: Boolean,
    val installedBy: UUID,
) {
    /** 암호화된 봇 토큰조차 노출하지 않는 마스킹 toString (방어적, §1.1.2). */
    override fun toString(): String =
        "SlackInstall(teamId=$teamId, teamName=$teamName, botUserId=$botUserId, appId=$appId, " +
            "botTokenEncrypted=<redacted>, scopes=$scopes, " +
            "isEnterpriseInstall=$isEnterpriseInstall, installedBy=$installedBy)"

    companion object {
        /**
         * 성공한 `oauth.v2.access` 응답 + 미리 암호화된 봇 토큰으로 설치 VO 를 조립한다.
         *
         * FR-SL-01 은 **워크스페이스 단위 설치만** 지원한다. enterprise install(응답에 `team` 부재)은
         * 저장하지 않고 예외로 거부한다(상위 서비스가 UnsupportedInstall 로 변환).
         *
         * @param response Slack 토큰 교환 응답(성공·워크스페이스 설치여야 함).
         * @param installedBy 설치 개시자 BTS 사용자 id.
         * @param botTokenEncrypted 서비스가 미리 암호화한 봇 토큰(**평문 금지**).
         * @throws IllegalArgumentException 응답이 `ok:false` 이거나, enterprise install(team 부재)이거나,
         *   필수 메타 필드/암호화 토큰이 비어 있을 때.
         */
        fun fromToken(
            response: SlackOAuthTokenResponse,
            installedBy: UUID,
            botTokenEncrypted: String,
        ): SlackInstall {
            require(response.ok) { "slack oauth response not ok" }
            val teamId =
                requireNotNull(response.teamId) {
                    "workspace team_id missing (enterprise install unsupported)"
                }
            val teamName = requireNotNull(response.teamName) { "workspace team_name missing" }
            val botUserId = requireNotNull(response.botUserId) { "bot_user_id missing" }
            val appId = requireNotNull(response.appId) { "app_id missing" }
            val scopes = requireNotNull(response.scope) { "scope missing" }
            require(botTokenEncrypted.isNotBlank()) { "encrypted bot token is blank" }
            return SlackInstall(
                teamId = teamId,
                teamName = teamName,
                botUserId = botUserId,
                appId = appId,
                botTokenEncrypted = botTokenEncrypted,
                scopes = scopes,
                isEnterpriseInstall = response.isEnterpriseInstall,
                installedBy = installedBy,
            )
        }
    }
}
