// slack_installs 현재 설치 조회용 경량 projection — 봇 토큰 미로드(방어적)

package com.bts.slack.application

import java.time.Instant

/**
 * 관리자 Slack 연결 페이지의 "현재 연결 상태" 표시용 경량 projection.
 *
 * `slack_installs` 에서 `team_id` / `team_name` / `installed_at` 3필드만 담는다.
 * **`bot_token_encrypted` 를 의도적으로 포함하지 않는다** — 상태 조회 경로에 봇 토큰의
 * 암호문조차 싣지 않아 노출 반경을 줄인다(방어적, DEVELOPMENT.md §1.1.2). 그래서
 * [com.bts.slack.domain.SlackInstall] 대신 별도 view 타입을 둔다(타입 상 토큰 부재 보장).
 *
 * @property teamId Slack 워크스페이스 id(`T…`).
 * @property teamName 워크스페이스 표시명.
 * @property installedAt 최초 설치 시각.
 */
data class SlackInstallationView(
    val teamId: String,
    val teamName: String,
    val installedAt: Instant,
)
