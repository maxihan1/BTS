// Slack 설치 오케스트레이션의 도메인 예외 — 관리자 가드/교환 실패/미지원 설치 유형 (FR-SL-01 Task 8)

package com.bts.slack.application

// SlackInstallService 가 던지는 도메인 예외 모음.
//
// 예외 → HTTP 매핑 (Task 9 웹 레이어가 소비).
//   SlackForbiddenException          — 시스템 관리자 아닌 사용자의 설치 시도(S5)          → 403
//   SlackStateInvalidException(Task4) — state 부재/서명 위조/만료(EC1)                    → 400
//   SlackOAuthFailedException        — oauth.v2.access ok:false 또는 토큰 부재(EC3/EC7)  → 502
//   SlackUnsupportedInstallException — enterprise 등 미지원 설치 유형(EC5/G1)            → 400
// SlackStateInvalidException 은 Task 4 가 oauth 패키지에 이미 정의한 것을 재사용한다(재정의 금지).
//
// 예외 메시지 위생 (DEVELOPMENT.md §1.1.2 / 교훈 fr-pm-04-guard-exception-message-http-leak).
// 모든 메시지는 봇 토큰·암호화 키·내부 정책/존재 여부를 담지 않는 일반 메시지로 고정한다. Slack 이 돌려준
// 실패 코드처럼 리다이렉트에 필요한 비-비밀 값은 errorCode 같은 별도 속성으로만 전달하고 message 로는
// 노출하지 않는다(HTTP detail 누출 차단).

/**
 * 시스템 전역 관리자가 아닌 행위자가 Slack App 설치를 시도했음을 나타낸다(스펙 S5).
 *
 * `GET /slack/install` 의 관리자 가드에서 발생하며 웹 레이어가 403 으로 매핑한다. 메시지에는 행위자
 * id 등 식별 정보를 담지 않는다.
 */
class SlackForbiddenException :
    RuntimeException("Slack app installation requires system administrator privileges")

/**
 * `oauth.v2.access` 교환이 사용 가능한 봇 토큰을 돌려주지 못했음을 나타낸다.
 *
 * 두 경우를 포괄한다.
 * - Slack 이 정상 응답으로 반환한 실패(`ok:false`, EC3) — [errorCode] 에 Slack 에러 코드(예: `invalid_code`).
 * - `ok:true` 이지만 필수 `access_token` 이 없는 경우(EC7) — [errorCode] = `missing_access_token`.
 *
 * `ok:false` 아닌 **전송 자체 실패**(네트워크/타임아웃)는 이 예외가 아니라
 * [com.bts.slack.oauth.SlackOAuthExchangeException] 으로 구분된다.
 *
 * [errorCode] 는 실패 화면 리다이렉트(`/admin/slack?error=<code>`)에 쓰이는 **비-비밀** 값이며,
 * 예외 `message` 로는 노출하지 않는다(§1.1.2 — 내부 사정 HTTP detail 누출 차단).
 *
 * @param code Slack 에러 코드(`ok:false`) 또는 내부 실패 사유 코드. null 이면 일반 코드로 치환한다.
 */
class SlackOAuthFailedException(
    code: String?,
) : RuntimeException("Slack OAuth token exchange did not yield a usable bot token") {
    /** 실패 화면 리다이렉트에 실을 비-비밀 에러 코드. null 입력은 일반 코드로 치환된다. */
    val errorCode: String = code?.takeIf { it.isNotBlank() } ?: DEFAULT_ERROR_CODE

    private companion object {
        const val DEFAULT_ERROR_CODE = "oauth_failed"
    }
}

/**
 * FR-SL-01 이 지원하지 않는 설치 유형(org-wide enterprise install 등)임을 나타낸다(EC5/G1).
 *
 * 워크스페이스 단위 설치만 지원하므로 응답에 `team` 이 없는 enterprise install 은 저장하지 않고 거부한다.
 * [com.bts.slack.domain.SlackInstall.fromToken] 이 던진 [IllegalArgumentException] 을 서비스가 이
 * 예외로 변환하며, 웹 레이어는 실패 코드 `unsupported_install_type` 으로 매핑한다.
 *
 * @param cause 진단용 원인([IllegalArgumentException]). HTTP 응답에는 노출하지 않는다.
 */
class SlackUnsupportedInstallException(
    cause: Throwable? = null,
) : RuntimeException("Unsupported Slack install type (workspace install required)", cause) {
    /** 실패 화면 리다이렉트에 실을 비-비밀 에러 코드. */
    val errorCode: String = "unsupported_install_type"
}
