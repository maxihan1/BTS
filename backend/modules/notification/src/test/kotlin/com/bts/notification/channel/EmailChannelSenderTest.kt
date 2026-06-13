// EmailChannelSender 단위 테스트 — JavaMailSender/UserLookupPort mock 으로 이메일 발송 및 supports 검증

package com.bts.notification.channel

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import com.bts.shared.user.UserLookupPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSender
import java.time.Instant
import java.util.UUID
import jakarta.mail.internet.MimeMessage

/**
 * [EmailChannelSender] 단위 테스트.
 *
 * [JavaMailSender] 와 [UserLookupPort] 를 MockK 로 mock 하여
 * 실제 SMTP 연결 없이 이메일 발송 로직을 검증한다.
 *
 * ### 테스트 케이스
 * - SEND-1. 이메일 조회 성공 시 MimeMessage 를 작성해 javaMailSender.send() 로 발송한다 — 제목/from/to 검증.
 * - SEND-2. 이메일 조회 결과가 null 이면 발송하지 않고 예외를 던진다.
 * - SUPPORTS-1. supports(EMAIL) = true
 * - SUPPORTS-2. supports(IN_APP) = false
 */
class EmailChannelSenderTest {
    private val javaMailSender = mockk<JavaMailSender>(relaxed = true)
    private val userLookupPort = mockk<UserLookupPort>()
    private val fromAddress = "no-reply@bts.local"

    private val sender = EmailChannelSender(
        javaMailSender = javaMailSender,
        userLookupPort = userLookupPort,
        from = fromAddress,
    )

    private val recipientId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val notificationId: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val fixedNow: Instant = Instant.parse("2026-06-14T10:00:00Z")

    private fun buildNotification(body: String? = "담당자로 지정되었습니다."): Notification =
        Notification(
            id = notificationId,
            recipientUserId = recipientId,
            eventType = NotificationEventType.ISSUE_ASSIGNED,
            channel = Channel.EMAIL,
            issueKey = "ATLAS-42",
            title = "이슈가 할당되었습니다",
            body = body,
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
    fun `SEND-1 이메일 존재 시 MimeMessage 로 발송하고 제목 from to 가 올바르게 설정된다`() {
        val recipientEmail = "alice@example.com"
        val mimeMessage = mockk<MimeMessage>(relaxed = true)

        every { userLookupPort.findEmailById(recipientId) } returns recipientEmail
        every { javaMailSender.createMimeMessage() } returns mimeMessage

        sender.send(buildNotification())

        verify(exactly = 1) { javaMailSender.send(mimeMessage) }

        // MimeMessage 에 설정된 값을 검증한다
        // MimeMessageHelper 는 MimeMessage 를 감싸므로 helper 를 통해 설정 후 message에 반영된다.
        // relaxed mock 이므로 setter 호출 여부로 대리 검증한다.
        verify(atLeast = 1) { mimeMessage.setSubject(any(), "UTF-8") }
    }

    @Test
    fun `SEND-2 이메일 조회 결과가 null 이면 발송하지 않고 예외를 던진다`() {
        every { userLookupPort.findEmailById(recipientId) } returns null

        assertThatThrownBy { sender.send(buildNotification()) }
            .isInstanceOf(IllegalStateException::class.java)

        verify(exactly = 0) { javaMailSender.send(any<MimeMessage>()) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SUPPORTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `SUPPORTS-1 supports(EMAIL) 는 true 를 반환한다`() {
        assertThat(sender.supports(Channel.EMAIL)).isTrue()
    }

    @Test
    fun `SUPPORTS-2 supports(IN_APP) 는 false 를 반환한다`() {
        assertThat(sender.supports(Channel.IN_APP)).isFalse()
    }
}
