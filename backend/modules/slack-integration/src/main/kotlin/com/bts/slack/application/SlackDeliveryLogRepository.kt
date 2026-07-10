// Slack 전송 dedup 로그 포트 — dedupKey 존재 확인 + 전송 성공 기록 (FR-SL-02 Task 7)

package com.bts.slack.application

/**
 * `slack_delivery_log` 영속화 포트 — 같은 알림이 두 번 발송되지 않도록 dedupKey 단위로 전송 이력을 남긴다.
 *
 * 구현체는 [com.bts.slack.persistence.JdbcSlackDeliveryLogRepository] — JdbcTemplate 기반
 * ([SlackUserMappingRepository]/[SlackInstallRepository] 동형, jOOQ 미도입).
 *
 * ## at-least-once 큐 위 멱등성 (스펙 FR6)
 * pgmq 는 at-least-once 전달이라 같은 메시지가 재전달될 수 있다. [SlackDeliveryWorker] 는 전송 **직전**
 * [exists] 로 중복을 거르고, 전송 **성공 이후**에만 [record] 로 박제한다(전송 실패가 dedup 되어 유실되는
 * 것을 막는 순서 — B4 회귀 방어).
 */
interface SlackDeliveryLogRepository {
    /** [dedupKey] 로 이미 전송 성공한 이력이 있으면 true. */
    fun exists(dedupKey: String): Boolean

    /**
     * [dedupKey] 전송 성공 이력을 기록한다. 이미 있으면 무시(멱등, `ON CONFLICT DO NOTHING`).
     */
    fun record(dedupKey: String)
}
