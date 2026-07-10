// user_slack_mapping 테이블 매핑 도메인 VO — BTS user_id ↔ Slack(slack_user_id, team_id) 매핑 (FR-SL-02 Task 5)

package com.bts.slack.domain

import java.time.Instant
import java.util.UUID

/**
 * `user_slack_mapping` 한 행에 대응하는 도메인 VO — BTS 사용자와 Slack 사용자/워크스페이스의 연결 상태.
 *
 * Slack DM 발송 시 수신 대상 BTS 사용자([userId])를 Slack 사용자 id([slackUserId])로 해석하는 조회용
 * 표현이다. 사용자당 매핑 1행이며(V701 `user_id` PK), 재연결은 upsert, 해제는 행 제거다(소프트 삭제 없음
 * — V701 마이그레이션 주석 정합).
 *
 * @property userId BTS 사용자 id(cross-BC, BC 격리로 FK 아님) — 매핑 조회 키.
 * @property slackUserId Slack 사용자 id(`U…`) — DM 수신 대상.
 * @property teamId Slack workspace(team) id — `slack_installs` 워크스페이스 축.
 * @property linkedAt 매핑 연결(최종 upsert) 시각.
 */
data class SlackUserMapping(
    val userId: UUID,
    val slackUserId: String,
    val teamId: String,
    val linkedAt: Instant,
)
