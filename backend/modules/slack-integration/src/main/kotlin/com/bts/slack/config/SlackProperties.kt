// Slack OAuth 설치에 필요한 client 자격증명·redirect·스코프 설정 프로퍼티 (FR-SL-01 Task 6)

package com.bts.slack.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * @Value 기본값은 컴파일 타임 상수여야 하므로 봇 스코프 기본값을 top-level const로 둔다(SDD 09.3.1).
 *
 * `users:read.email`은 `users.lookupByEmail`(D6 사용자 연결)용 스코프다. 스코프 추가는 기존
 * Slack 워크스페이스 설치에 소급 적용되지 않으므로, 이미 설치된 워크스페이스는 재연결(OAuth 재승인)이
 * 필요하다.
 */
const val DEFAULT_SLACK_SCOPES: String =
    "chat:write,chat:write.public,links:read,links:write,commands,app_mentions:read,users:read.email"

/**
 * Slack App OAuth 설치 흐름에 필요한 client 설정값 (FR-SL-01 Task 6).
 *
 * ## 환경변수 주입 (DEVELOPMENT.md §1.1.2 / DATA.md §8)
 * - [clientId] ← `BTS_SLACK_CLIENT_ID` (`bts.slack.client-id`). 공개값 — authorize URL에 노출된다.
 * - [clientSecret] ← `BTS_SLACK_CLIENT_SECRET` (`bts.slack.client-secret`). **비밀값** — DB/코드/로그 저장 금지(N3).
 * - [redirectUri] ← `BTS_SLACK_REDIRECT_URI` (`bts.slack.redirect-uri`). authorize/exchange 동일값 사용(G3).
 * - [scopes] ← `bts.slack.scopes` (기본값 [DEFAULT_SLACK_SCOPES]). 발급 요청 봇 스코프 CSV.
 * - [signingSecret] ← `BTS_SLACK_SIGNING_SECRET` (`bts.slack.signing-secret`). **비밀값** — Slack Events API
 *   요청 서명(`X-Slack-Signature`) 검증용 HMAC 키(FR-SL-03 ADR D6). DB/코드/로그 저장 금지(N3).
 *
 * `System.getenv` 직접 호출 없이 Spring 프로퍼티 경유로만 접근한다(`SlackEncryptionConfig` 동일 패턴).
 *
 * ## 부팅 안전성 — 항상 등록 + 사용 시점 검증 (N4)
 * 값이 미설정이어도 빈 문자열로 바인딩되어 빈은 **항상** 등록된다. 컴포넌트 스캔만으로 부팅이 깨지지
 * 않게 하기 위함이다(`profile-scoped-bean-boot-failure` 회귀 방지). 실제 값이 필요한 시점에
 * [requireConfiguredForAuthorize]/[requireConfiguredForExchange]로 미설정을 검증한다.
 *
 * ## 비밀값 로깅 금지 (§1.1.2)
 * [clientSecret]·[signingSecret]을 우연히라도 로그에 흘리지 않도록 [toString]에서 마스킹한다. [clientId]는
 * 비밀값이 아니므로(authorize URL 공개 파라미터) 마스킹하지 않는다.
 */
@Component
class SlackProperties(
    @param:Value("\${bts.slack.client-id:}") val clientId: String,
    @param:Value("\${bts.slack.client-secret:}") val clientSecret: String,
    @param:Value("\${bts.slack.redirect-uri:}") val redirectUri: String,
    @param:Value("\${bts.slack.scopes:" + DEFAULT_SLACK_SCOPES + "}") val scopes: String,
    @param:Value("\${bts.slack.signing-secret:}") val signingSecret: String = "",
) {
    /**
     * authorize URL 생성에 필요한 값([clientId]·[redirectUri])이 설정되었는지 검증한다.
     * client_secret은 authorize 단계에서 쓰지 않으므로 검사하지 않는다.
     *
     * @throws IllegalStateException 미설정 시. 메시지에 값을 담지 않는다.
     */
    fun requireConfiguredForAuthorize() {
        check(clientId.isNotBlank() && redirectUri.isNotBlank()) { NOT_CONFIGURED_MESSAGE }
    }

    /**
     * 토큰 교환에 필요한 값([clientId]·[clientSecret]·[redirectUri])이 설정되었는지 검증한다.
     *
     * @throws IllegalStateException 미설정 시. 메시지에 값(특히 client_secret)을 담지 않는다.
     */
    fun requireConfiguredForExchange() {
        check(clientId.isNotBlank() && clientSecret.isNotBlank() && redirectUri.isNotBlank()) {
            NOT_CONFIGURED_MESSAGE
        }
    }

    /** client_secret·signing_secret을 노출하지 않는 마스킹 toString (§1.1.2). */
    override fun toString(): String =
        "SlackProperties(clientId=$clientId, clientSecret=${maskSecret(clientSecret)}, " +
            "redirectUri=$redirectUri, scopes=$scopes, signingSecret=${maskSecret(signingSecret)})"

    private companion object {
        const val NOT_CONFIGURED_MESSAGE = "Slack OAuth client is not configured"

        /** 비밀값 존재 여부만 노출하고 값은 가린다. */
        fun maskSecret(secret: String): String = if (secret.isBlank()) "<blank>" else "<redacted>"
    }
}
