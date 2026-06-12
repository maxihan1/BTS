// InAppChannelSender 단위 테스트 — STOMP convertAndSendToUser 호출 및 supports 검증

package com.bts.notification.channel

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.time.Instant
import java.util.UUID

/**
 * [InAppChannelSender] 단위 테스트.
 *
 * [SimpMessagingTemplate] 을 MockK 로 mock 하여 실제 WebSocket 연결 없이
 * send 호출 시의 destination 및 payload 를 검증한다.
 *
 * ### 테스트 케이스
 * - SEND-1. send 호출 시 convertAndSendToUser(recipientId, "/queue/notifications", payload) 로 전달된다.
 * - SEND-2. payload 의 각 필드가 Notification 으로부터 올바르게 매핑된다.
 * - SEND-3. issueKey 가 null 인 Notification 도 정상 처리된다.
 * - SUPPORTS-1. supports(IN_APP) = true
 * - SUPPORTS-2. supports(EMAIL) = false
 * - SUPPORTS-3. supports(SLACK) = false
 */
class InAppChannelSenderTest {

    private val messagingTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
    private val sender = InAppChannelSender(messagingTemplate)

    private val recipientId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val notificationId: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val fixedNow: Instant = Instant.parse("2026-06-12T10:00:00Z")

    private fun buildNotification(issueKey: String? = "ATLAS-42"): Notification =
        Notification(
            id = notificationId,
            recipientUserId = recipientId,
            eventType = NotificationEventType.ISSUE_ASSIGNED,
            channel = Channel.IN_APP,
            issueKey = issueKey,
            title = "이슈가 할당되었습니다",
            body = "담당자로 지정되었습니다.",
            payload = null,
            status = NotificationStatus.PENDING,
            dedupKey = "dedupkey",
            readAt = null,
            createdAt = fixedNow,
        )

    // ─────────────────────────────────────────────────────────────────────
    // SEND
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `SEND-1 send 호출 시 convertAndSendToUser 가 올바른 userId 와 destination 으로 호출된다`() {
        val notification = buildNotification()

        sender.send(notification)

        verify(exactly = 1) {
            messagingTemplate.convertAndSendToUser(
                recipientId.toString(),
                "/queue/notifications",
                any<InAppNotificationPayload>(),
            )
        }
    }

    @Test
    fun `SEND-2 payload 필드가 Notification 으로부터 올바르게 매핑된다`() {
        val notification = buildNotification(issueKey = "ATLAS-42")
        val payloadSlot = slot<InAppNotificationPayload>()

        sender.send(notification)

        verify(exactly = 1) {
            messagingTemplate.convertAndSendToUser(
                recipientId.toString(),
                "/queue/notifications",
                capture(payloadSlot),
            )
        }
        val payload = payloadSlot.captured
        assertThat(payload.id).isEqualTo(notificationId)
        assertThat(payload.eventType).isEqualTo(NotificationEventType.ISSUE_ASSIGNED.wireValue)
        assertThat(payload.issueKey).isEqualTo("ATLAS-42")
        assertThat(payload.title).isEqualTo("이슈가 할당되었습니다")
        assertThat(payload.body).isEqualTo("담당자로 지정되었습니다.")
        assertThat(payload.occurredAt).isEqualTo(fixedNow)
    }

    @Test
    fun `SEND-3 issueKey 가 null 인 Notification 도 정상 처리된다`() {
        val notification = buildNotification(issueKey = null)
        val payloadSlot = slot<InAppNotificationPayload>()

        sender.send(notification)

        verify(exactly = 1) {
            messagingTemplate.convertAndSendToUser(
                recipientId.toString(),
                "/queue/notifications",
                capture(payloadSlot),
            )
        }
        assertThat(payloadSlot.captured.issueKey).isNull()
    }

    // ─────────────────────────────────────────────────────────────────────
    // SUPPORTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `SUPPORTS-1 supports(IN_APP) 는 true 를 반환한다`() {
        assertThat(sender.supports(Channel.IN_APP)).isTrue()
    }

    @Test
    fun `SUPPORTS-2 supports(EMAIL) 는 false 를 반환한다`() {
        assertThat(sender.supports(Channel.EMAIL)).isFalse()
    }

    @Test
    fun `SUPPORTS-3 supports(SLACK) 는 false 를 반환한다`() {
        assertThat(sender.supports(Channel.SLACK)).isFalse()
    }
}
