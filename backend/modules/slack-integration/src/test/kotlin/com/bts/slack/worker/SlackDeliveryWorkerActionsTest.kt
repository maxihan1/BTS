// SlackDeliveryWorker eventType 게이팅 단위 테스트 — issue.assigned 만 액션 버튼 렌더 (FR-SL-05 Task 7)

package com.bts.slack.worker

import com.bts.slack.application.SlackDeliveryLogRepository
import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.domain.SlackUserMapping
import com.bts.slack.message.RenderedSlackMessage
import com.bts.slack.message.SlackBlockKitRenderer
import com.bts.slack.message.SlackMessageClient
import com.bts.slack.message.SlackSendResult
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/**
 * [SlackDeliveryWorker]가 `eventType == "issue.assigned"`일 때만 [SlackBlockKitRenderer.renderAssignmentActionsMessage]
 * 를 호출하고, 그 외 이벤트는 기존 [SlackBlockKitRenderer.render]를 그대로 호출함을 검증한다 (FR-SL-05 Task 7 리뷰 CONCERN —
 * 무조건 액션 버튼을 붙이면 멘션/댓글 DM에도 "완료로 표시" 버튼이 오배치된다).
 *
 * jdbcTemplate/renderer/messageClient 등 모든 협력자를 mockk로 대체한 단위 테스트 —
 * [SlackDeliveryWorkerIntegrationTest]의 pgmq 생명주기(Testcontainers)와 달리 게이팅 분기만 격리 검증한다.
 */
class SlackDeliveryWorkerActionsTest {
    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val objectMapper = ObjectMapper()
    private val mappingRepository = mockk<SlackUserMappingRepository>()
    private val deliveryLogRepository = mockk<SlackDeliveryLogRepository>()
    private val botTokenResolver = mockk<SlackBotTokenResolver>()
    private val renderer = mockk<SlackBlockKitRenderer>()
    private val messageClient = mockk<SlackMessageClient>()

    private val worker =
        SlackDeliveryWorker(
            jdbcTemplate = jdbcTemplate,
            objectMapper = objectMapper,
            mappingRepository = mappingRepository,
            deliveryLogRepository = deliveryLogRepository,
            botTokenResolver = botTokenResolver,
            renderer = renderer,
            messageClient = messageClient,
        )

    private val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val actionsMessage = RenderedSlackMessage(text = "할당", blocks = "{\"actions\":true}")
    private val plainMessage = RenderedSlackMessage(text = "멘션", blocks = "{\"actions\":false}")

    @BeforeEach
    fun setUp() {
        every { mappingRepository.findByUserId(userId) } returns
            SlackUserMapping(userId, "U0RECIPIENT", "T_TEAM", Instant.parse("2026-01-01T00:00:00Z"))
        every { deliveryLogRepository.exists(any()) } returns false
        every { deliveryLogRepository.record(any()) } returns Unit
        every { botTokenResolver.resolve("T_TEAM") } returns "xoxb-token"
        every { messageClient.postDirectMessage(any(), any(), any()) } returns SlackSendResult.Sent
        every {
            jdbcTemplate.queryForObject(any<String>(), Boolean::class.java, SlackDeliveryWorker.QUEUE_NAME, 1L)
        } returns true
    }

    @Test
    fun `eventType 이 issue-assigned 이면 renderAssignmentActionsMessage 를 호출하고 액션 버튼 메시지를 발송한다`() {
        every { renderer.renderAssignmentActionsMessage("할당되었습니다", "PROJ-1") } returns actionsMessage
        stubQueue(eventType = "issue.assigned", title = "할당되었습니다", issueKey = "PROJ-1", dedupKey = "d-1")

        worker.pollAndProcess()

        verify(exactly = 1) { renderer.renderAssignmentActionsMessage("할당되었습니다", "PROJ-1") }
        verify(exactly = 0) { renderer.render(any(), any()) }
        verify(exactly = 1) { messageClient.postDirectMessage("xoxb-token", "U0RECIPIENT", actionsMessage) }
    }

    @Test
    fun `eventType 이 issue-assigned 이 아니면 기존 render 를 호출하고 액션 버튼을 붙이지 않는다`() {
        every { renderer.render("멘션되었습니다", "PROJ-2") } returns plainMessage
        stubQueue(eventType = "issue.mentioned", title = "멘션되었습니다", issueKey = "PROJ-2", dedupKey = "d-2")

        worker.pollAndProcess()

        verify(exactly = 1) { renderer.render("멘션되었습니다", "PROJ-2") }
        verify(exactly = 0) { renderer.renderAssignmentActionsMessage(any(), any()) }
        verify(exactly = 1) { messageClient.postDirectMessage("xoxb-token", "U0RECIPIENT", plainMessage) }
    }

    /** msg_id=1 고정 행 하나를 pgmq.read 응답으로 스텁한다(단일 메시지 처리 시나리오). */
    private fun stubQueue(
        eventType: String,
        title: String,
        issueKey: String,
        dedupKey: String,
    ) {
        val messageJson =
            objectMapper.writeValueAsString(
                mapOf(
                    "recipientUserId" to userId.toString(),
                    "eventType" to eventType,
                    "issueKey" to issueKey,
                    "title" to title,
                    "dedupKey" to dedupKey,
                ),
            )
        val row = mapOf("msg_id" to 1L, "read_ct" to 0, "message" to messageJson)
        every {
            jdbcTemplate.queryForList(
                any<String>(),
                SlackDeliveryWorker.QUEUE_NAME,
                SlackDeliveryWorker.VT_SECONDS,
                SlackDeliveryWorker.BATCH_SIZE,
            )
        } returns listOf(row)
    }
}
