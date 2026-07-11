// Slack 사용자 매핑 영속화 포트 — upsert(멱등) + userId 삭제/조회 (FR-SL-02 Task 5)

package com.bts.slack.application

import com.bts.slack.domain.SlackUserMapping
import java.util.UUID

/**
 * `user_slack_mapping` 영속화 포트.
 *
 * 구현체는 [com.bts.slack.persistence.JdbcSlackUserMappingRepository] — JdbcTemplate 기반
 * (jOOQ 미도입, [com.bts.slack.application.SlackInstallRepository] 동형 — 단일 테이블 단순 CRUD).
 */
interface SlackUserMappingRepository {
    /**
     * BTS 사용자 ↔ Slack 사용자/워크스페이스 매핑을 저장한다.
     *
     * 같은 [userId] 가 이미 존재하면 최신 [slackUserId]/[teamId] 로 갱신한다(업서트, 멱등 —
     * V701 마이그레이션 `ON CONFLICT (user_id) DO UPDATE`, last-write-wins).
     */
    fun upsert(
        userId: UUID,
        slackUserId: String,
        teamId: String,
    )

    /**
     * [userId] 의 매핑을 제거한다(하드 삭제 — 소프트 삭제 대상 아님, V701 주석 정합).
     */
    fun deleteByUserId(userId: UUID)

    /**
     * [userId] 로 매핑을 조회한다. 존재하지 않으면 null.
     */
    fun findByUserId(userId: UUID): SlackUserMapping?

    /**
     * [slackUserId]/[teamId] 로 역방향 조회해 매핑된 BTS `userId` 를 반환한다(FR-SL-03 Slack Unfurl
     * viewer 해석 — Slack 채널에 공유된 Atlas 이슈 URL 을 펼칠 때 공유자의 열람 권한을 판정하는 데 쓰인다).
     *
     * 매핑이 없거나 [teamId] 가 불일치하면 null. **같은 (slackUserId, teamId) 조합으로 행이 2개 이상
     * 매칭되면 임의로 하나를 고르지 않고 null 을 반환한다(fail-closed)** — V702 UNIQUE 인덱스가 1차
     * 방어이지만, 인덱스가 없거나 무력화된 상태에서도 잘못된(더 높은 권한의) viewer 가 선택돼 이슈 카드가
     * 과다노출되는 사고를 쿼리 로직 자체가 독립적으로 막기 위한 2차 방어다.
     */
    fun findUserIdBySlackUserId(
        slackUserId: String,
        teamId: String,
    ): UUID?
}
