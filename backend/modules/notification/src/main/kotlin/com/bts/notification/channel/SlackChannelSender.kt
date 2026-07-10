// SLACK 채널 알림을 pgmq q_slack_deliveries 큐로 발행하는 sender — slack-integration BC 직접 의존 없이 JSON 경계만 공유

package com.bts.notification.channel

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * SLACK 채널 알림을 pgmq [QUEUE_NAME] 큐에 발행하는 [NotificationChannelSender] 구현체 (FR-SL-02).
 *
 * ### BC 격리
 * slack-integration 코드를 직접 import 하지 않는다. [QUEUE_NAME] 큐에 발행하는 JSON payload 가
 * 유일한 계약 경계다. 큐 자체는 slack-integration `V701__slack_notification_delivery.sql` 이 생성하며
 * (`V409__slack_notification_delivery.sql` 참고 — notification 테스트 다수가 vanilla postgres 이미지를
 * 써 pgmq 확장을 요구할 수 없는 producer-creates 예외), 이 sender 는 발행(enqueue)만 담당한다.
 * 실제 Slack `chat.postMessage` 호출은 slack-integration `SlackDeliveryWorker`(별도 워커 프로세스)가 수행한다.
 *
 * ### JSON 계약 (q_slack_deliveries)
 * ```json
 * { "recipientUserId": "<uuid>", "eventType": "issue.mentioned", "issueKey": "PROJ-123",
 *   "title": "<notification.title>", "occurredAt": "<iso-8601>", "dedupKey": "<notification.dedupKey>" }
 * ```
 *
 * @param dsl jOOQ [DSLContext] — pgmq.send raw SQL 실행에 사용.
 * @param objectMapper Jackson [ObjectMapper] — payload Map → JSON 직렬화.
 */
@Component
class SlackChannelSender(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : NotificationChannelSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun supports(channel: Channel): Boolean = channel == Channel.SLACK

    /**
     * 알림을 [QUEUE_NAME] 큐에 JSON 으로 발행한다.
     *
     * @param notification 발송할 알림 Aggregate
     */
    override fun send(notification: Notification) {
        val payload =
            mapOf(
                "recipientUserId" to notification.recipientUserId.toString(),
                "eventType" to notification.eventType.wireValue,
                "issueKey" to notification.issueKey,
                "title" to notification.title,
                "occurredAt" to notification.createdAt.toString(),
                "dedupKey" to notification.dedupKey,
            )
        val json = objectMapper.writeValueAsString(payload)

        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, json)

        log.info(
            "slack notification enqueued queue={} notificationId={} recipientUserId={} eventType={}",
            QUEUE_NAME,
            notification.id,
            notification.recipientUserId,
            notification.eventType.wireValue,
        )
    }

    companion object {
        /** pgmq 큐 이름 — slack-integration V701__slack_notification_delivery.sql 에서 생성된 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_slack_deliveries"
    }
}
