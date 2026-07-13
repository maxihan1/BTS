// SlackChannelBroadcaster 단위 테스트 — q_slack_channel_broadcasts pgmq 발행 JSON 계약 및 projectKey 게이팅 검증

package com.bts.notification.channel

import com.bts.notification.domain.NotificationEventType
import com.bts.notification.recipient.NotificationSourceEvent
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
 * [SlackChannelBroadcaster] 단위 테스트.
 *
 * [DSLContext] 를 MockK 로 mock 해 실제 pgmq 큐 없이 pgmq.send raw SQL 호출과
 * 발행 JSON payload 의 계약 필드를 검증한다 (FR-SL-06 PR-B).
 *
 * ### 테스트 케이스
 * - BC-1. projectKey 있는 이벤트 → dsl.execute("SELECT pgmq.send(?, ?::jsonb)", "q_slack_channel_broadcasts", json) 1회 호출
 * - BC-2. 발행 JSON 이 계약 필드(projectKey/eventType/issueKey/title/occurredAt/dedupKey) 를 모두 포함한다
 * - BC-3. issueKey 가 null 인 이벤트도 정상 처리된다(JSON null)
 * - BC-4. projectKey 가 null 인 이벤트 → dsl.execute 미호출 (발행 안 함)
 * - BC-5. 같은 입력이면 dedupKey 가 결정적으로 동일하다
 */
class SlackChannelBroadcasterTest {
    private val dsl = mockk<DSLContext>()
    private val objectMapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val titleBuilder = NotificationTitleBuilder()

    private val broadcaster = SlackChannelBroadcaster(dsl, objectMapper, titleBuilder)

    private val actorId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val fixedNow: Instant = Instant.parse("2026-07-13T10:00:00Z")

    private fun buildEvent(
        projectKey: String? = "ATLAS",
        issueKey: String? = "ATLAS-1",
        eventType: NotificationEventType = NotificationEventType.ISSUE_CREATED,
    ): NotificationSourceEvent =
        NotificationSourceEvent(
            eventType = eventType,
            issueKey = issueKey,
            projectKey = projectKey,
            reporterId = null,
            actorId = actorId,
            occurredAt = fixedNow,
        )

    // ─────────────────────────────────────────────────────────────────────
    // BROADCAST
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `BC-1 projectKey 있는 이벤트는 q_slack_channel_broadcasts 큐로 pgmq_send 가 1회 호출된다`() {
        val event = buildEvent()
        every { dsl.execute(any<String>(), SlackChannelBroadcaster.QUEUE_NAME, any<String>()) } returns 1

        broadcaster.broadcastIfApplicable(event)

        verify(exactly = 1) {
            dsl.execute("SELECT pgmq.send(?, ?::jsonb)", SlackChannelBroadcaster.QUEUE_NAME, any<String>())
        }
    }

    @Test
    fun `BC-2 발행 JSON 이 계약 필드를 모두 포함한다`() {
        val event =
            buildEvent(projectKey = "ATLAS", issueKey = "ATLAS-1", eventType = NotificationEventType.ISSUE_CREATED)
        val payloadSlot = slot<String>()
        every { dsl.execute(any<String>(), SlackChannelBroadcaster.QUEUE_NAME, capture(payloadSlot)) } returns 1

        broadcaster.broadcastIfApplicable(event)

        val json = objectMapper.readTree(payloadSlot.captured)
        assertThat(json.get("projectKey").asText()).isEqualTo("ATLAS")
        assertThat(json.get("eventType").asText()).isEqualTo("issue.created")
        assertThat(json.get("issueKey").asText()).isEqualTo("ATLAS-1")
        assertThat(json.get("title").asText()).isEqualTo(titleBuilder.buildTitle(event))
        assertThat(json.get("occurredAt").asText()).isEqualTo(fixedNow.toString())
        assertThat(json.get("dedupKey").asText()).isNotBlank()
    }

    @Test
    fun `BC-3 issueKey 가 null 인 이벤트도 정상 처리된다`() {
        val event = buildEvent(issueKey = null)
        val payloadSlot = slot<String>()
        every { dsl.execute(any<String>(), SlackChannelBroadcaster.QUEUE_NAME, capture(payloadSlot)) } returns 1

        broadcaster.broadcastIfApplicable(event)

        val json = objectMapper.readTree(payloadSlot.captured)
        assertThat(json.get("issueKey").isNull).isTrue()
    }

    @Test
    fun `BC-4 projectKey 가 null 인 이벤트는 발행하지 않는다`() {
        val event = buildEvent(projectKey = null)

        broadcaster.broadcastIfApplicable(event)

        verify(exactly = 0) { dsl.execute(any<String>(), any<String>(), any<String>()) }
    }

    @Test
    fun `BC-5 같은 입력이면 dedupKey 가 결정적으로 동일하다`() {
        val event1 = buildEvent()
        val event2 = buildEvent()
        val slot1 = slot<String>()
        val slot2 = slot<String>()
        every { dsl.execute(any<String>(), SlackChannelBroadcaster.QUEUE_NAME, capture(slot1)) } returns 1

        broadcaster.broadcastIfApplicable(event1)
        val dedup1 = objectMapper.readTree(slot1.captured).get("dedupKey").asText()

        every { dsl.execute(any<String>(), SlackChannelBroadcaster.QUEUE_NAME, capture(slot2)) } returns 1
        broadcaster.broadcastIfApplicable(event2)
        val dedup2 = objectMapper.readTree(slot2.captured).get("dedupKey").asText()

        assertThat(dedup1).isEqualTo(dedup2)
    }
}
