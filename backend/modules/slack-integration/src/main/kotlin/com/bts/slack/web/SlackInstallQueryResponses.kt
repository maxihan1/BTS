// Slack 연결 상태/설치 URL JSON 조회 엔드포인트의 응답 DTO — 표시용 비-비밀 필드만 (FR-SL-01 D6/D7 Task 3)

package com.bts.slack.web

import java.time.Instant

/**
 * `GET /api/v1/slack/installation` 응답 — 관리자 Slack 연결 페이지 상태 배너용 (FR-SL-01 D6/D7 Task 3).
 *
 * **표시용 비-비밀 필드만** 담는다(DEVELOPMENT.md §1.1.2 — 비밀값 노출 최소화). 봇 토큰(평문/암호문)이나
 * `installedBy`(설치자 UUID) 등 비-표시 필드는 애초에
 * [com.bts.slack.application.SlackInstallationStatus] 에 로드되지 않아 타입 상 새어 나갈 수 없다(방어적).
 * 미설치 시 [connected] 는 false 이고 나머지 필드는 모두 null 이다.
 *
 * @property connected Slack 워크스페이스가 연결되어 있으면 true.
 * @property teamId 연결된 워크스페이스 id(`T…`) — 미설치 시 null.
 * @property teamName 워크스페이스 표시명 — 미설치 시 null.
 * @property installedAt 최초 설치 시각 — 미설치 시 null. JSON 직렬화 시 ISO-8601 문자열.
 */
data class SlackInstallationResponse(
    val connected: Boolean,
    val teamId: String?,
    val teamName: String?,
    val installedAt: Instant?,
)

/**
 * `GET /api/v1/slack/install-url` 응답 — SPA 가 사용자를 보낼 Slack authorize URL (FR-SL-01 D6/D7 Task 3).
 *
 * [url] 은 서명 state 를 실은 `https://slack.com/oauth/v2/authorize?...` 다. SPA(Bearer 인증)는 이 URL 로
 * 브라우저를 이동시켜 설치를 개시한다(302 흐름 [SlackInstallController.startInstall] 과 목적은 같으나,
 * SPA 가 Location 헤더를 따라갈 수 없어 JSON 으로 URL 만 돌려준다).
 *
 * @property url Slack authorize URL(`client_id`·`scope`·서명 `state`·`redirect_uri` 포함).
 */
data class SlackInstallUrlResponse(
    val url: String,
)
