// slack_channel_broadcast_log 영속화 포트 — 채널 브로드캐스트 dedup 저장소 (FR-SL-06 PR-B Task 3)

package com.bts.slack.application

/**
 * `slack_channel_broadcast_log` 영속화 포트 — 채널 브로드캐스트 dedup 저장소.
 *
 * 구현체는 [com.bts.slack.persistence.JdbcSlackChannelBroadcastDedupRepository] — JdbcTemplate 기반
 * ([SlackChannelMappingRepository] 동형, 단일 테이블).
 *
 * pgmq(`q_slack_channel_broadcasts`)는 at-least-once 배달이라 같은 메시지가 재전달될 수 있다. 채널 워커는
 * 게시 전 [existsPosted] 로 이미 게시된 (이벤트, 채널) 조합인지 확인하고, 게시 후 [recordPosted] 로 기록해
 * 같은 이벤트가 같은 채널에 두 번 게시되는 것을 막는다.
 */
interface SlackChannelBroadcastDedupRepository {
    /**
     * [dedupKey](이벤트레벨 해시 + channelId)가 이미 게시 기록된 조합인지 확인한다.
     *
     * @return 이미 게시된 (이벤트, 채널) 조합이면 true, 아니면 false.
     */
    fun existsPosted(dedupKey: String): Boolean

    /**
     * [dedupKey](이벤트레벨 해시 + channelId)를 게시 기록한다.
     *
     * 멱등하다 — 같은 key 를 다시 기록해도 예외 없이 통과한다(`ON CONFLICT DO NOTHING`). pgmq 재전달로
     * 워커가 같은 (이벤트, 채널)을 두 번 처리해도 안전하다.
     */
    fun recordPosted(dedupKey: String)
}
