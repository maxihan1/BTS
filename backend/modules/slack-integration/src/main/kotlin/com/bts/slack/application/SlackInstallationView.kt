// slack_installs 현재 설치 조회용 경량 projection — 봇 토큰 미로드(방어적)

package com.bts.slack.application

import java.time.Instant
import java.util.UUID

/**
 * 관리자 Slack 연결 페이지의 "현재 연결 상태" 표시용 경량 projection.
 *
 * `slack_installs` 에서 상태 표시·메타 필드만 담고 **`bot_token_encrypted` 를 의도적으로
 * 포함하지 않는다** — 상태 조회 경로에 봇 토큰의 암호문조차 싣지 않아 노출 반경을 줄인다
 * (방어적, DEVELOPMENT.md §1.1.2). 그래서 [com.bts.slack.domain.SlackInstall] 대신 별도
 * view 타입을 둔다(타입 상 토큰 부재 보장).
 *
 * @property teamId Slack 워크스페이스 id(`T…`).
 * @property teamName 워크스페이스 표시명.
 * @property botUserId 봇 사용자 id(`U…`) — 응답 메타 표시용.
 * @property installedAt 최초 설치 시각.
 * @property updatedAt 마지막 갱신 시각(재설치 upsert 시 `now()` 로 갱신) — 응답 메타 표시용.
 * @property installedBy 설치를 개시한 BTS 사용자 id. **이름 해석 전용**(설치자 표시명 lookup 에만
 *   사용하고 응답 본문에는 노출하지 않는다) — 원시 사용자 id 를 외부로 흘리지 않기 위함(방어적).
 */
data class SlackInstallationView(
    val teamId: String,
    val teamName: String,
    val botUserId: String,
    val installedAt: Instant,
    val updatedAt: Instant,
    val installedBy: UUID,
)
