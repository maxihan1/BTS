// SlackChannelBroadcastWorker 통합 테스트 — q_slack_channel_broadcasts 소비 → 보안게이트 → 매핑/이벤트필터 →
// per-channel dedup/토큰 → chat.postMessage (FR-SL-06 PR-B Task 6)

package com.bts.slack.worker

import com.bts.shared.issue.IssueSecurityClassificationPort
import com.bts.slack.domain.ChannelProjectMapping
import com.bts.slack.domain.SlackChannelEventType
import com.bts.slack.message.SlackBlockKitRenderer
import com.bts.slack.message.SlackMessageClient
import com.bts.slack.message.SlackSendResult
import com.bts.slack.persistence.JdbcSlackChannelBroadcastDedupRepository
import com.bts.slack.persistence.JdbcSlackChannelMappingRepository
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
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID

/**
 * [SlackChannelBroadcastWorker]가 `q_slack_channel_broadcasts`를 소비해 보안게이트 → 매핑/이벤트필터 →
 * per-channel dedup/토큰 해석 → `chat.postMessage`를 수행하고, 결과별로 큐를 삭제/보존/archive 하는지
 * 검증한다 (FR-SL-06 PR-B Task 6).
 *
 * 실제 pgmq/테이블(Testcontainers pgmq 이미지)과 실제 매핑·dedup repository를 쓰고, 외부 경계인
 * [IssueSecurityClassificationPort]/[SlackBotTokenResolver]/[SlackMessageClient]만 mock 한다
 * ([SlackDeliveryWorkerIntegrationTest] 동형).
 */
class SlackChannelBroadcastWorkerTest {
    private val objectMapper = ObjectMapper()
    private val securityClassificationPort = mockk<IssueSecurityClassificationPort>()
    private val tokenResolver = mockk<SlackBotTokenResolver>()
    private val messageClient = mockk<SlackMessageClient>()

    private lateinit var mappingRepository: JdbcSlackChannelMappingRepository
    private lateinit var dedupRepository: JdbcSlackChannelBroadcastDedupRepository
    private lateinit var worker: SlackChannelBroadcastWorker

    @BeforeEach
    fun setUp() {
        val jdbcTemplate = JdbcTemplate(dataSource)
        mappingRepository = JdbcSlackChannelMappingRepository(jdbcTemplate)
        dedupRepository = JdbcSlackChannelBroadcastDedupRepository(jdbcTemplate)
        worker =
            SlackChannelBroadcastWorker(
                jdbcTemplate = JdbcTemplate(dataSource),
                objectMapper = objectMapper,
                mappingRepository = mappingRepository,
                dedupRepository = dedupRepository,
                securityClassificationPort = securityClassificationPort,
                botTokenResolver = tokenResolver,
                renderer = SlackBlockKitRenderer("https://atlas.example.com", objectMapper),
                messageClient = messageClient,
            )
        // 각 테스트 격리 — 큐/archive/테이블 초기화
        jdbc.execute("DELETE FROM slack_channel_project_map")
        jdbc.execute("DELETE FROM slack_channel_broadcast_log")
        jdbc.execute("SELECT pgmq.purge_queue('q_slack_channel_broadcasts')")
        jdbc.execute("DELETE FROM pgmq.\"${pgmqTable("a")}\"")
    }

    // ── S1: happy path ────────────────────────────────────────────────────────

    @Test
    fun `매핑된 채널에 게시 성공하면 postChannelMessage 호출 + dedup 기록 + 메시지 삭제`() {
        saveMapping(channelId = "C1", eventTypes = setOf(SlackChannelEventType.ISSUE_CREATED))
        every { securityClassificationPort.isSecurityRestricted("ATLAS-1") } returns false
        every { tokenResolver.resolve("T1") } returns "xoxb-token"
        every { messageClient.postChannelMessage("xoxb-token", "C1", any()) } returns SlackSendResult.Sent
        enqueue(eventType = SlackChannelEventType.ISSUE_CREATED, issueKey = "ATLAS-1", dedupKey = "dedup-1")

        worker.pollAndProcess()

        verify(exactly = 1) { messageClient.postChannelMessage("xoxb-token", "C1", any()) }
        assertThat(dedupRepository.existsPosted("dedup-1:C1")).isTrue()
        assertThat(undeliveredCount()).isEqualTo(0)
    }

    // ── S2: 미매핑 프로젝트 ─────────────────────────────────────────────────────

    @Test
    fun `매핑 없는 프로젝트면 postChannelMessage 미호출 + 메시지 삭제`() {
        every { securityClassificationPort.isSecurityRestricted("ATLAS-2") } returns false
        enqueue(eventType = SlackChannelEventType.ISSUE_CREATED, issueKey = "ATLAS-2", dedupKey = "dedup-2")

        worker.pollAndProcess()

        verify(exactly = 0) { messageClient.postChannelMessage(any(), any(), any()) }
        assertThat(undeliveredCount()).isEqualTo(0)
    }

    // ── S3: event_filter 불일치 ────────────────────────────────────────────────

    @Test
    fun `event_filter 불일치 채널은 skip + 메시지 삭제`() {
        saveMapping(channelId = "C1", eventTypes = setOf(SlackChannelEventType.ISSUE_ASSIGNED))
        every { securityClassificationPort.isSecurityRestricted("ATLAS-3") } returns false
        enqueue(eventType = SlackChannelEventType.ISSUE_CREATED, issueKey = "ATLAS-3", dedupKey = "dedup-3")

        worker.pollAndProcess()

        verify(exactly = 0) { messageClient.postChannelMessage(any(), any(), any()) }
        assertThat(undeliveredCount()).isEqualTo(0)
    }

    // ── S4: 보안등급 이슈 — 전 채널 게시 0 ──────────────────────────────────────

    @Test
    fun `보안등급 제한 이슈면 전 채널 게시 0 + 메시지 삭제`() {
        saveMapping(channelId = "C1", eventTypes = setOf(SlackChannelEventType.ISSUE_CREATED))
        every { securityClassificationPort.isSecurityRestricted("ATLAS-4") } returns true
        enqueue(eventType = SlackChannelEventType.ISSUE_CREATED, issueKey = "ATLAS-4", dedupKey = "dedup-4")

        worker.pollAndProcess()

        verify(exactly = 0) { messageClient.postChannelMessage(any(), any(), any()) }
        assertThat(dedupRepository.existsPosted("dedup-4:C1")).isFalse()
        assertThat(undeliveredCount()).isEqualTo(0)
    }

    // ── S5: issueKey null — 보안게이트 우회 ─────────────────────────────────────

    @Test
    fun `issueKey null 이면 보안게이트 우회하고 event_filter 통과 시 게시`() {
        saveMapping(channelId = "C1", eventTypes = setOf(SlackChannelEventType.SPRINT_STARTED))
        every { tokenResolver.resolve("T1") } returns "xoxb-token"
        every { messageClient.postChannelMessage("xoxb-token", "C1", any()) } returns SlackSendResult.Sent
        enqueue(eventType = SlackChannelEventType.SPRINT_STARTED, issueKey = null, dedupKey = "dedup-5")

        worker.pollAndProcess()

        // securityClassificationPort 는 이 테스트에서 전혀 stub 하지 않았다 — 호출되면 mockk 가 예외를 던져
        // 실패한다(issueKey==null 게이트 우회 검증).
        verify(exactly = 1) { messageClient.postChannelMessage("xoxb-token", "C1", any()) }
        assertThat(undeliveredCount()).isEqualTo(0)
    }

    // ── S6: 봇 미설치 ──────────────────────────────────────────────────────────

    @Test
    fun `봇 미설치 채널은 skip + 메시지 삭제`() {
        saveMapping(channelId = "C1", eventTypes = setOf(SlackChannelEventType.ISSUE_CREATED))
        every { securityClassificationPort.isSecurityRestricted("ATLAS-6") } returns false
        every { tokenResolver.resolve("T1") } returns null
        enqueue(eventType = SlackChannelEventType.ISSUE_CREATED, issueKey = "ATLAS-6", dedupKey = "dedup-6")

        worker.pollAndProcess()

        verify(exactly = 0) { messageClient.postChannelMessage(any(), any(), any()) }
        assertThat(undeliveredCount()).isEqualTo(0)
    }

    // ── S7: 재시도 ─────────────────────────────────────────────────────────────
    // pgmq vt(visibility timeout, 60초) 로 인해 한 번 읽힌 메시지는 같은 테스트 안에서 곧바로 재폴링해도
    // 다시 읽히지 않는다 — 두 국면(보존/archive)을 [SlackDeliveryWorkerIntegrationTest] 동형으로 별도
    // 테스트로 분리한다(같은 시나리오의 두 하위 검증).

    @Test
    fun `재시도가능 실패(rate_limited)면 dedup 미기록 + 큐 보존(재전달)`() {
        saveMapping(channelId = "C1", eventTypes = setOf(SlackChannelEventType.ISSUE_CREATED))
        every { securityClassificationPort.isSecurityRestricted("ATLAS-7") } returns false
        every { tokenResolver.resolve("T1") } returns "xoxb-token"
        every { messageClient.postChannelMessage("xoxb-token", "C1", any()) } returns
            SlackSendResult.RetryableFailure("rate_limited")
        enqueue(eventType = SlackChannelEventType.ISSUE_CREATED, issueKey = "ATLAS-7", dedupKey = "dedup-7")

        worker.pollAndProcess()

        assertThat(dedupRepository.existsPosted("dedup-7:C1")).isFalse()
        assertThat(undeliveredCount()).isEqualTo(1) // 삭제 안 됨 → 재전달 대기
    }

    @Test
    fun `재시도가능 실패가 readCt MAX 초과면 archive + dedup 미기록`() {
        saveMapping(channelId = "C1", eventTypes = setOf(SlackChannelEventType.ISSUE_CREATED))
        every { securityClassificationPort.isSecurityRestricted("ATLAS-7B") } returns false
        every { tokenResolver.resolve("T1") } returns "xoxb-token"
        every { messageClient.postChannelMessage("xoxb-token", "C1", any()) } returns
            SlackSendResult.RetryableFailure("rate_limited")
        enqueue(eventType = SlackChannelEventType.ISSUE_CREATED, issueKey = "ATLAS-7B", dedupKey = "dedup-7b")
        forceReadCount(SlackChannelBroadcastWorker.MAX_RECEIVE_COUNT)

        worker.pollAndProcess()

        assertThat(dedupRepository.existsPosted("dedup-7b:C1")).isFalse()
        assertThat(undeliveredCount()).isEqualTo(0) // 큐에서 제거
        assertThat(archivedCount()).isEqualTo(1) // dead-letter 이동
    }

    // ── S8: 다채널 + 재전달 dedup(effectively-once) ─────────────────────────────

    @Test
    fun `다채널 매핑에서 첫 실행 둘 다 게시 성공 후 같은 메시지 재전달되면 두 채널 모두 dedup skip`() {
        saveMapping(channelId = "C1", eventTypes = setOf(SlackChannelEventType.ISSUE_CREATED))
        saveMapping(channelId = "C2", eventTypes = setOf(SlackChannelEventType.ISSUE_CREATED))
        every { securityClassificationPort.isSecurityRestricted("ATLAS-8") } returns false
        every { tokenResolver.resolve("T1") } returns "xoxb-token"
        every { messageClient.postChannelMessage("xoxb-token", "C1", any()) } returns SlackSendResult.Sent
        every { messageClient.postChannelMessage("xoxb-token", "C2", any()) } returns SlackSendResult.Sent
        enqueue(eventType = SlackChannelEventType.ISSUE_CREATED, issueKey = "ATLAS-8", dedupKey = "dedup-8")

        worker.pollAndProcess()

        assertThat(dedupRepository.existsPosted("dedup-8:C1")).isTrue()
        assertThat(dedupRepository.existsPosted("dedup-8:C2")).isTrue()
        verify(exactly = 1) { messageClient.postChannelMessage("xoxb-token", "C1", any()) }
        verify(exactly = 1) { messageClient.postChannelMessage("xoxb-token", "C2", any()) }

        // 같은 이벤트가 재전달(at-least-once)되어 새 메시지로 다시 큐에 들어온 상황을 시뮬레이션한다.
        enqueue(eventType = SlackChannelEventType.ISSUE_CREATED, issueKey = "ATLAS-8", dedupKey = "dedup-8")
        worker.pollAndProcess()

        // effectively-once — 재전달분은 두 채널 모두 dedup skip 되어 postChannelMessage 총 호출수가 늘지 않는다.
        verify(exactly = 1) { messageClient.postChannelMessage("xoxb-token", "C1", any()) }
        verify(exactly = 1) { messageClient.postChannelMessage("xoxb-token", "C2", any()) }
        assertThat(undeliveredCount()).isEqualTo(0)
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    private fun saveMapping(
        channelId: String,
        eventTypes: Set<String>,
        projectKey: String = DEFAULT_PROJECT_KEY,
        teamId: String = DEFAULT_TEAM_ID,
    ) {
        val now = Instant.now()
        mappingRepository.save(
            ChannelProjectMapping(
                id = UUID.randomUUID(),
                teamId = teamId,
                projectKey = projectKey,
                channelId = channelId,
                channelName = null,
                eventTypes = eventTypes,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun enqueue(
        eventType: String,
        issueKey: String?,
        dedupKey: String,
        projectKey: String = DEFAULT_PROJECT_KEY,
    ) {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "projectKey" to projectKey,
                    "eventType" to eventType,
                    "issueKey" to issueKey,
                    "title" to "$projectKey 활동",
                    "occurredAt" to "2026-07-10T00:00:00Z",
                    "dedupKey" to dedupKey,
                ),
            )
        jdbc.queryForObject("SELECT pgmq.send('q_slack_channel_broadcasts', ?::jsonb)", Long::class.java, payload)
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

    /** q_slack_channel_broadcasts 큐 테이블 이름(pgmq prefix `q_`). */
    private fun queueTableName(): String = pgmqTable("q")

    /** pgmq 스키마에서 [prefix](`q`=큐·`a`=archive)로 시작하는 slack_channel_broadcasts 테이블 이름을 찾는다. */
    private fun pgmqTable(prefix: String): String =
        jdbc.queryForObject(
            "SELECT table_name FROM information_schema.tables" +
                " WHERE table_schema = 'pgmq' AND table_name LIKE ? ESCAPE '!'",
            String::class.java,
            "$prefix!_%slack_channel_broadcasts",
        ) ?: error("pgmq $prefix table for slack_channel_broadcasts not found")

    companion object {
        private const val DEFAULT_PROJECT_KEY = "ATLAS"
        private const val DEFAULT_TEAM_ID = "T1"

        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_slack_channel_broadcast_worker_test")
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
