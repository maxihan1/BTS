// 알림을 STOMP WebSocket 으로 실시간 푸시하는 인앱 채널 sender

package com.bts.notification.channel

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * 인앱 알림을 STOMP user destination 으로 실시간 푸시하는 [NotificationChannelSender] 구현체 (FR-NT-02).
 *
 * 수신자 user destination 은 WebSocketConfig 에서 설정한 user prefix `/user` 와 결합해
 * `/user/{recipientUserId}/queue/notifications` 로 라우팅된다.
 * [SimpMessagingTemplate.convertAndSendToUser] 가 user prefix 를 자동으로 붙여주므로
 * destination 인자로는 `/queue/notifications` 만 전달한다.
 *
 * @param messagingTemplate Spring STOMP 메시지 발송 템플릿 (spring-messaging 제공)
 */
@Component
class InAppChannelSender(
    private val messagingTemplate: SimpMessagingTemplate,
) : NotificationChannelSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun supports(channel: Channel): Boolean = channel == Channel.IN_APP

    /**
     * 알림을 수신자 STOMP user destination 으로 푸시한다.
     *
     * @param notification 발송할 알림 Aggregate
     */
    override fun send(notification: Notification) {
        val payload =
            InAppNotificationPayload(
                id = notification.id,
                eventType = notification.eventType.wireValue,
                issueKey = notification.issueKey,
                title = notification.title,
                body = notification.body,
                occurredAt = notification.createdAt,
            )

        messagingTemplate.convertAndSendToUser(
            notification.recipientUserId.toString(),
            DESTINATION,
            payload,
        )

        log.info(
            "inapp notification sent notificationId={} recipientUserId={} eventType={}",
            notification.id,
            notification.recipientUserId,
            notification.eventType.wireValue,
        )
    }

    private companion object {
        const val DESTINATION = "/queue/notifications"
    }
}
