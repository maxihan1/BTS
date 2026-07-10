// SlackChannelSender 단위 테스트 — q_slack_deliveries pgmq 발행 JSON 계약 및 supports 검증

package com.bts.notification.channel

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [SlackChannelSender] 단위 테스트.
 *
 * [DSLContext] 를 MockK 로 mock 해 실제 pgmq 큐 없이 pgmq.send raw SQL 호출과
 * 발행 JSON payload 의 계약 필드를 검증한다.
 *
 * ### 테스트 케이스
 * - SEND-1. send 호출 시 dsl.execute("SELECT pgmq.send(?, ?::jsonb)", "q_slack_deliveries", json) 로 전달된다.
 * - SEND-2. 발행 JSON 이 계약 필드(recipientUserId/eventType/issueKey/title/occurredAt/dedupKey) 를 모두 포함한다.
 * - SEND-3. issueKey 가 null 인 Notification 도 정상 처리된다(JSON null).
 * - SUPPORTS-1. supports(SLACK) = true
 * - SUPPORTS-2~5. supports(IN_APP/EMAIL/WEBHOOK/TEAMS) = false
 */
class SlackChannelSenderTest {
    private val dsl = mockk<DSLContext>()
    private val objectMapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private val sender = SlackChannelSender(dsl, objectMapper)

    private val recipientId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val notificationId: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val fixedNow: Instant = Instant.parse("2026-07-10T10:00:00Z")

    private fun buildNotification(issueKey: String? = "PROJ-123"): Notification =
        Notification(
            id = notificationId,
            recipientUserId = recipientId,
            eventType = NotificationEventType.ISSUE_MENTIONED,
            channel = Channel.SLACK,
            issueKey = issueKey,
            title = "회원님을 언급했습니다",
            body = null,
            payload = null,
            status = NotificationStatus.PENDING,
            dedupKey = "dedupkey-slack-1",
            readAt = null,
            createdAt = fixedNow,
        )

    // ─────────────────────────────────────────────────────────────────────
    // SEND
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `SEND-1 send 호출 시 dsl_execute 가 q_slack_deliveries 큐로 pgmq_send 호출된다`() {
        val notification = buildNotification()
        every { dsl.execute(any<String>(), SlackChannelSender.QUEUE_NAME, any<String>()) } returns 1

        sender.send(notification)

        verify(exactly = 1) {
            dsl.execute("SELECT pgmq.send(?, ?::jsonb)", SlackChannelSender.QUEUE_NAME, any<String>())
        }
    }

    @Test
    fun `SEND-2 발행 JSON 이 계약 필드를 모두 포함한다`() {
        val notification = buildNotification(issueKey = "PROJ-123")
        val payloadSlot = slot<String>()
        every { dsl.execute(any<String>(), SlackChannelSender.QUEUE_NAME, capture(payloadSlot)) } returns 1

        sender.send(notification)

        val json = objectMapper.readTree(payloadSlot.captured)
        assertThat(json.get("recipientUserId").asText()).isEqualTo(recipientId.toString())
        assertThat(json.get("eventType").asText()).isEqualTo("issue.mentioned")
        assertThat(json.get("issueKey").asText()).isEqualTo("PROJ-123")
        assertThat(json.get("title").asText()).isEqualTo(notification.title)
        assertThat(json.get("occurredAt").asText()).isEqualTo(fixedNow.toString())
        assertThat(json.get("dedupKey").asText()).isEqualTo("dedupkey-slack-1")
    }

    @Test
    fun `SEND-3 issueKey 가 null 인 Notification 도 정상 처리된다`() {
        val notification = buildNotification(issueKey = null)
        val payloadSlot = slot<String>()
        every { dsl.execute(any<String>(), SlackChannelSender.QUEUE_NAME, capture(payloadSlot)) } returns 1

        sender.send(notification)

        val json = objectMapper.readTree(payloadSlot.captured)
        assertThat(json.get("issueKey").isNull).isTrue()
    }

    // ─────────────────────────────────────────────────────────────────────
    // SUPPORTS
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `SUPPORTS-1 supports(SLACK) 는 true 를 반환한다`() {
        assertThat(sender.supports(Channel.SLACK)).isTrue()
    }

    @Test
    fun `SUPPORTS-2 supports(IN_APP) 는 false 를 반환한다`() {
        assertThat(sender.supports(Channel.IN_APP)).isFalse()
    }

    @Test
    fun `SUPPORTS-3 supports(EMAIL) 는 false 를 반환한다`() {
        assertThat(sender.supports(Channel.EMAIL)).isFalse()
    }

    @Test
    fun `SUPPORTS-4 supports(WEBHOOK) 는 false 를 반환한다`() {
        assertThat(sender.supports(Channel.WEBHOOK)).isFalse()
    }

    @Test
    fun `SUPPORTS-5 supports(TEAMS) 는 false 를 반환한다`() {
        assertThat(sender.supports(Channel.TEAMS)).isFalse()
    }
}
