// Slack 사용자 매핑 오케스트레이션 — link/unlink/resolveByUserId (FR-SL-02 Task 5)

package com.bts.slack.application

import com.bts.slack.domain.SlackUserMapping
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * BTS 사용자 ↔ Slack 사용자/워크스페이스 매핑을 오케스트레이션한다 (FR-SL-02 Task 5).
 *
 * Slack DM 발송 대상 해석을 위한 연결/해제/조회 3개 진입점을 제공한다. D6 연결 플로우(웹 레이어)가
 * [link]/[unlink] 를 호출하고, Slack 알림 발송 워커가 [resolveByUserId] 로 수신 대상 Slack 사용자를
 * 해석할 예정이다(이번 태스크는 서비스 계약까지만 — 워커 연동은 후속 태스크).
 *
 * @param repository `user_slack_mapping` 영속화 포트(upsert 멱등, 하드 삭제).
 */
@Service
class SlackUserMappingService(
    private val repository: SlackUserMappingRepository,
) {
    /**
     * BTS 사용자와 Slack 사용자/워크스페이스를 연결한다(멱등 업서트).
     *
     * 같은 [userId] 로 재호출하면 최신 [slackUserId]/[teamId] 로 갱신된다(재연결).
     *
     * @param userId BTS 사용자 id.
     * @param slackUserId Slack 사용자 id(`U…`).
     * @param teamId Slack workspace(team) id.
     */
    @Transactional
    fun link(
        userId: UUID,
        slackUserId: String,
        teamId: String,
    ) {
        repository.upsert(userId, slackUserId, teamId)
    }

    /**
     * [userId] 의 Slack 매핑을 해제한다(행 제거 — 소프트 삭제 없음).
     *
     * @param userId BTS 사용자 id.
     */
    @Transactional
    fun unlink(userId: UUID) {
        repository.deleteByUserId(userId)
    }

    /**
     * [userId] 의 Slack 매핑을 조회한다.
     *
     * @param userId BTS 사용자 id.
     * @return 매핑이 없으면 null.
     */
    @Transactional(readOnly = true)
    fun resolveByUserId(userId: UUID): SlackUserMapping? = repository.findByUserId(userId)
}
