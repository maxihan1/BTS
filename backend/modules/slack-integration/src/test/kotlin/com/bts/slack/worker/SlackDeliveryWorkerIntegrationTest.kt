// SlackDeliveryWorker 통합 테스트 — q_slack_deliveries 소비 → 매핑/토큰/dedup → 전송/재시도/skip (FR-SL-02 Task 7)

package com.bts.slack.worker

import com.bts.slack.message.SlackBlockKitRenderer
import com.bts.slack.message.SlackMessageClient
import com.bts.slack.message.SlackSendResult
import com.bts.slack.persistence.JdbcSlackDeliveryLogRepository
import com.bts.slack.persistence.JdbcSlackUserMappingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * [SlackDeliveryWorker]가 `q_slack_deliveries` 를 소비해 매핑 해석 → 봇 토큰 해석 → dedup → chat.postMessage
 * 를 수행하고, 결과별로 큐를 삭제/보존/archive 하는지 검증한다 (FR-SL-02 Task 7).
 *
 * 실제 pgmq/테이블(Testcontainers pgmq 이미지)과 실제 매핑·dedup repository를 쓰고, 외부 경계인
 * [SlackBotTokenResolver]/[SlackMessageClient]만 mock 한다.
 */
class SlackDeliveryWorkerIntegrationTest {
    private val objectMapper = ObjectMapper()
    private val tokenResolver = mockk<SlackBotTokenResolver>()
    private val messageClient = mockk<SlackMessageClient>()

    private lateinit var mappingRepository: JdbcSlackUserMappingRepository
    private lateinit var deliveryLogRepository: JdbcSlackDeliveryLogRepository
    private lateinit var worker: SlackDeliveryWorker

    @BeforeEach
    fun setUp() {
        val named = NamedParameterJdbcTemplate(dataSource)
        mappingRepository = JdbcSlackUserMappingRepository(named)
        deliveryLogRepository = JdbcSlackDeliveryLogRepository(named)
        worker =
            SlackDeliveryWorker(
                jdbcTemplate = JdbcTemplate(dataSource),
                objectMapper = objectMapper,
                mappingRepository = mappingRepository,
                deliveryLogRepository = deliveryLogRepository,
                botTokenResolver = tokenResolver,
                renderer = SlackBlockKitRenderer("https://atlas.example.com", objectMapper),
                messageClient = messageClient,
            )
        // 각 테스트 격리 — 큐/archive/테이블 초기화 (purge_queue 는 archive 테이블을 비우지 않으므로 별도 DELETE)
        jdbc.execute("DELETE FROM user_slack_mapping")
        jdbc.execute("DELETE FROM slack_delivery_log")
        jdbc.execute("SELECT pgmq.purge_queue('q_slack_deliveries')")
        jdbc.execute("DELETE FROM pgmq.\"${pgmqTable("a")}\"")
    }

    // ── 시나리오 ────────────────────────────────────────────────────────────────

    @Test
    fun `매핑·토큰 있고 전송 성공이면 postMessage 호출 + dedup 기록 + 큐 삭제`() {
        val userId = UUID.randomUUID()
        mappingRepository.upsert(userId, "U0RECIPIENT", "T_TEAM")
        every { tokenResolver.resolve("T_TEAM") } returns "xoxb-token"
        every { messageClient.postDirectMessage("xoxb-token", "U0RECIPIENT", any()) } returns SlackSendResult.Sent
        enqueue(userId, dedupKey = "PROJ-1:issue.mentioned:$userId", title = "PROJ-1 에서 멘션되었습니다", issueKey = "PROJ-1")

        worker.pollAndProcess()

        verify(exactly = 1) { messageClient.postDirectMessage("xoxb-token", "U0RECIPIENT", any()) }
        assertThat(deliveryLogRepository.exists("PROJ-1:issue.mentioned:$userId")).isTrue()
        assertThat(undeliveredCount()).isEqualTo(0)
    }

    @Test
    fun `매핑 없으면 postMessage 미호출 + 큐 삭제(skip)`() {
        val userId = UUID.randomUUID()
        enqueue(userId, dedupKey = "PROJ-2:issue.assigned:$userId", title = "할당됨", issueKey = "PROJ-2")

        worker.pollAndProcess()

        verify(exactly = 0) { messageClient.postDirectMessage(any(), any(), any()) }
        assertThat(undeliveredCount()).isEqualTo(0)
    }

    @Test
    fun `설치(봇 토큰) 없으면 postMessage 미호출 + 큐 삭제`() {
        val userId = UUID.randomUUID()
        mappingRepository.upsert(userId, "U0RECIPIENT", "T_TEAM")
        every { tokenResolver.resolve("T_TEAM") } returns null
        enqueue(userId, dedupKey = "PROJ-3:issue.mentioned:$userId", title = "멘션", issueKey = "PROJ-3")

        worker.pollAndProcess()

        verify(exactly = 0) { messageClient.postDirectMessage(any(), any(), any()) }
        assertThat(undeliveredCount()).isEqualTo(0)
    }

    @Test
    fun `이미 전송된 dedupKey면 postMessage 미호출 + 큐 삭제`() {
        val userId = UUID.randomUUID()
        mappingRepository.upsert(userId, "U0RECIPIENT", "T_TEAM")
        every { tokenResolver.resolve("T_TEAM") } returns "xoxb-token"
        deliveryLogRepository.record("PROJ-4:issue.mentioned:$userId") // 이미 전송됨
        enqueue(userId, dedupKey = "PROJ-4:issue.mentioned:$userId", title = "멘션", issueKey = "PROJ-4")

        worker.pollAndProcess()

        verify(exactly = 0) { messageClient.postDirectMessage(any(), any(), any()) }
        assertThat(undeliveredCount()).isEqualTo(0)
    }

    @Test
    fun `재시도가능 실패(429)면 dedup 미기록 + 큐 보존(재전달)`() {
        val userId = UUID.randomUUID()
        mappingRepository.upsert(userId, "U0RECIPIENT", "T_TEAM")
        every { tokenResolver.resolve("T_TEAM") } returns "xoxb-token"
        every { messageClient.postDirectMessage(any(), any(), any()) } returns
            SlackSendResult.RetryableFailure("rate_limited")
        enqueue(userId, dedupKey = "PROJ-5:issue.mentioned:$userId", title = "멘션", issueKey = "PROJ-5")

        worker.pollAndProcess()

        verify(exactly = 1) { messageClient.postDirectMessage(any(), any(), any()) }
        // B4 회귀 방어: 전송 실패는 dedup에 박제되지 않아야 재시도가 가능하다.
        assertThat(deliveryLogRepository.exists("PROJ-5:issue.mentioned:$userId")).isFalse()
        assertThat(undeliveredCount()).isEqualTo(1) // 삭제 안 됨 → 재전달 대기
    }

    @Test
    fun `영구 실패(channel_not_found)면 dedup 미기록 + 큐 삭제`() {
        val userId = UUID.randomUUID()
        mappingRepository.upsert(userId, "U0RECIPIENT", "T_TEAM")
        every { tokenResolver.resolve("T_TEAM") } returns "xoxb-token"
        every { messageClient.postDirectMessage(any(), any(), any()) } returns
            SlackSendResult.PermanentFailure("channel_not_found")
        enqueue(userId, dedupKey = "PROJ-6:issue.mentioned:$userId", title = "멘션", issueKey = "PROJ-6")

        worker.pollAndProcess()

        assertThat(deliveryLogRepository.exists("PROJ-6:issue.mentioned:$userId")).isFalse()
        assertThat(undeliveredCount()).isEqualTo(0) // 재시도 무의미 → 삭제
    }

    @Test
    fun `parse 실패(poison)면 postMessage 미호출 + readCt 낮으면 큐 보존`() {
        enqueuePoison()

        worker.pollAndProcess()

        verify(exactly = 0) { messageClient.postDirectMessage(any(), any(), any()) }
        assertThat(undeliveredCount()).isEqualTo(1) // 재전달 대기(아직 dead-letter 아님)
        assertThat(archivedCount()).isEqualTo(0)
    }

    @Test
    fun `parse 실패(poison)가 readCt MAX 초과면 archive(dead-letter)`() {
        enqueuePoison()
        forceReadCount(SlackDeliveryWorker.MAX_RECEIVE_COUNT) // read 시 +1 → MAX 초과

        worker.pollAndProcess()

        assertThat(undeliveredCount()).isEqualTo(0) // 큐에서 제거
        assertThat(archivedCount()).isEqualTo(1) // dead-letter 이동
    }

    @Test
    fun `재시도가능 실패가 readCt MAX 초과면 archive + dedup 미기록`() {
        val userId = UUID.randomUUID()
        mappingRepository.upsert(userId, "U0RECIPIENT", "T_TEAM")
        every { tokenResolver.resolve("T_TEAM") } returns "xoxb-token"
        every { messageClient.postDirectMessage(any(), any(), any()) } returns
            SlackSendResult.RetryableFailure("rate_limited")
        enqueue(userId, dedupKey = "PROJ-7:issue.mentioned:$userId", title = "멘션", issueKey = "PROJ-7")
        forceReadCount(SlackDeliveryWorker.MAX_RECEIVE_COUNT)

        worker.pollAndProcess()

        // 반복 재시도 실패는 dead-letter 로 수렴하되, dedup 에는 여전히 기록하지 않는다(B4).
        assertThat(deliveryLogRepository.exists("PROJ-7:issue.mentioned:$userId")).isFalse()
        assertThat(undeliveredCount()).isEqualTo(0)
        assertThat(archivedCount()).isEqualTo(1)
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    private fun enqueue(
        recipientUserId: UUID,
        dedupKey: String,
        title: String,
        issueKey: String?,
    ) {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "recipientUserId" to recipientUserId.toString(),
                    "eventType" to "issue.mentioned",
                    "issueKey" to issueKey,
                    "title" to title,
                    "occurredAt" to "2026-07-10T00:00:00Z",
                    "dedupKey" to dedupKey,
                ),
            )
        jdbc.queryForObject("SELECT pgmq.send('q_slack_deliveries', ?::jsonb)", Long::class.java, payload)
    }

    /** 유효 JSON 이지만 recipientUserId 가 UUID 가 아니라 parse 실패(poison)하는 메시지를 넣는다. */
    private fun enqueuePoison() {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "recipientUserId" to "not-a-uuid",
                    "eventType" to "issue.mentioned",
                    "title" to "제목",
                    "dedupKey" to "poison-key",
                ),
            )
        jdbc.queryForObject("SELECT pgmq.send('q_slack_deliveries', ?::jsonb)", Long::class.java, payload)
    }

    /** 큐에 남은 메시지의 read_ct 를 [value] 로 강제한다 — pgmq.read 가 +1 하므로 dead-letter 임계 검증에 쓴다. */
    private fun forceReadCount(value: Int) {
        jdbc.update("UPDATE pgmq.\"${queueTableName()}\" SET read_ct = ?", value)
    }

    /** 큐에 남아 있는(삭제·archive 되지 않은) 메시지 수 — 보이지 않는(vt 미래) 메시지도 포함. */
    private fun undeliveredCount(): Int {
        return jdbc.queryForObject("SELECT count(*) FROM pgmq.\"${queueTableName()}\"", Int::class.java) ?: 0
    }

    /** archive(dead-letter) 테이블로 이동한 메시지 수. */
    private fun archivedCount(): Int {
        return jdbc.queryForObject("SELECT count(*) FROM pgmq.\"${pgmqTable("a")}\"", Int::class.java) ?: 0
    }

    /** q_slack_deliveries 큐 테이블 이름(pgmq prefix `q_`). */
    private fun queueTableName(): String = pgmqTable("q")

    /** pgmq 스키마에서 [prefix](`q`=큐·`a`=archive)로 시작하는 slack_deliveries 테이블 이름을 찾는다. */
    private fun pgmqTable(prefix: String): String =
        jdbc.queryForObject(
            "SELECT table_name FROM information_schema.tables" +
                " WHERE table_schema = 'pgmq' AND table_name LIKE ? ESCAPE '!'",
            String::class.java,
            "$prefix!_%slack_deliveries",
        ) ?: error("pgmq $prefix table for slack_deliveries not found")

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_slack_worker_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        private val dataSource: DriverManagerDataSource =
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

        private val jdbc: JdbcTemplate = JdbcTemplate(dataSource)

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/slack-integration")
                .load()
                .migrate()
        }
    }
}
