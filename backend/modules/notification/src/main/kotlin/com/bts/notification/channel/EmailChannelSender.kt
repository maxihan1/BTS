// 알림을 이메일로 발송하는 채널 sender — JavaMailSender + MimeMessageHelper (UTF-8)

package com.bts.notification.channel

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.shared.user.UserLookupPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

/**
 * 알림을 이메일로 발송하는 [NotificationChannelSender] 구현체 (FR-NT-02).
 *
 * [UserLookupPort.findEmailById] 로 수신자 이메일 주소를 cross-BC 조회한 뒤
 * [MimeMessageHelper] (UTF-8, multipart=false) 를 통해 [jakarta.mail.internet.MimeMessage] 를 작성하고
 * [JavaMailSender.send] 로 SMTP 전송한다.
 *
 * ### 실패 동작
 * - 이메일 주소 조회 결과가 null 또는 blank 이면 [IllegalStateException] 을 throw 한다.
 *   거짓 SENT 박제 방지 — NotificationWorker.deliver() 가 이 예외를 catch 해 상태를 PENDING 으로 유지한다.
 * - SMTP 전송 실패 시 예외를 전파한다 (NotificationWorker 가 PENDING 유지).
 *
 * ### PII 처리
 * 로그에는 이메일 주소 원문을 남기지 않는다. recipientUserId 만 기록한다.
 *
 * @param javaMailSender Spring Boot autoconfig 제공 JavaMailSender (spring.mail.host 설정 시 자동 생성)
 * @param userLookupPort 수신자 이메일 cross-BC 조회 포트 (shared-kernel)
 * @param from 발신 이메일 주소 (bts.notification.email.from)
 */
@Component
class EmailChannelSender(
    private val javaMailSender: JavaMailSender,
    private val userLookupPort: UserLookupPort,
    @Value("\${bts.notification.email.from}")
    private val from: String,
) : NotificationChannelSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun supports(channel: Channel): Boolean = channel == Channel.EMAIL

    /**
     * 알림을 이메일로 발송한다.
     *
     * @param notification 발송할 알림 Aggregate
     * @throws IllegalStateException 수신자 이메일 주소를 조회할 수 없을 때 (NotificationWorker 가 PENDING 유지)
     * @throws org.springframework.mail.MailException SMTP 전송 실패 시 (NotificationWorker 가 PENDING 유지)
     */
    override fun send(notification: Notification) {
        val recipientEmail = userLookupPort.findEmailById(notification.recipientUserId)

        if (recipientEmail.isNullOrBlank()) {
            log.warn(
                "email not found for recipient — skipping send recipientUserId={}",
                notification.recipientUserId,
            )
            error("email address not found for recipientUserId=${notification.recipientUserId}")
        }

        val message = javaMailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, false, Charsets.UTF_8.name())

        helper.setFrom(from)
        helper.setTo(recipientEmail)
        helper.setSubject(notification.title)
        helper.setText(notification.body ?: notification.title)

        javaMailSender.send(message)

        log.info(
            "email notification sent notificationId={} recipientUserId={} eventType={}",
            notification.id,
            notification.recipientUserId,
            notification.eventType.wireValue,
        )
    }
}
