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
}
