// WebhookDispatchWorker 통합 테스트 — 실 Postgres+pgmq(Testcontainers) 위에서 fanout/이력/circuit/생명주기 검증 (FR-API-03 PR3 Task 8)
package com.bts.search.webhook.dispatch

import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import com.bts.search.webhook.domain.DeliveryStatus
import com.bts.search.webhook.domain.OutboundWebhook
import com.bts.search.webhook.domain.WebhookEventCatalog
import com.bts.search.webhook.persistence.JooqOutboundWebhookRepository
import com.bts.search.webhook.persistence.JooqWebhookDeliveryRepository
import com.bts.shared.crypto.SecretEncryptor
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * [WebhookDispatchWorker] 통합 테스트.
 *
 * Testcontainers(테스트용 DB를 도커로 자동 실행하는 라이브러리) 실 Postgres+pgmq 위에서 fanout(구독
 * 매칭 → 발송 → 이력 기록) + circuit breaker + pgmq 생명주기(delete/archive)를 검증한다.
 * `SearchPersistenceTestBase`(JVM 단위 singleton 컨테이너, V600~V603 마이그레이션 적용)를 재사용한다.
 *
 * ## q_webhook_events 큐 수동 생성 (C5)
 * 이 큐는 issue-tracking V034 소유라 search 자체 Flyway(V600~)에는 없다. `@BeforeEach`에서
 * JVM 당 1회(멱등 가드) `pgmq.create`로 생성한다(notification `q_transition_events` 선례,
 * 메모리 bts-cross-bc-test-migration).
 *
 * ## dispatcher는 mockk — 외부 HTTP 없이 fanout/이력/circuit 로직만 검증
 * 실제 HTTP 발송·SSRF·HMAC 서명은 [SearchWebhookDispatcherTest](Task 7)가 이미 커버한다. 이 테스트는
 * "워커가 매칭 구독마다 [SearchWebhookDispatcher.dispatch]를 올바른 인자로 호출하고, 결과에 따라
 * 실 DB([JooqWebhookDeliveryRepository]/[JooqOutboundWebhookRepository])와 pgmq를 올바르게
 * 갱신하는가"를 검증한다. secret 복호화는 실 [SecretEncryptor]로 round-trip 검증한다.
 */
class WebhookDispatchWorkerIntegrationTest : SearchPersistenceTestBase() {
    private val outboundWebhookRepo get() = JooqOutboundWebhookRepository(dsl)
    private val deliveryRepo get() = JooqWebhookDeliveryRepository(dsl)
    private val secretEncryptor = SecretEncryptor(TEST_KEY, TEST_SALT)

    private lateinit var circuitBreaker: WebhookCircuitBreaker
    private lateinit var dispatcher: SearchWebhookDispatcher
    private lateinit var worker: WebhookDispatchWorker

    @BeforeEach
    fun setUp() {
        ensureQueueCreated(dsl)
        circuitBreaker = WebhookCircuitBreaker()
        dispatcher = mockk()
        worker =
            WebhookDispatchWorker(
                dsl = dsl,
                objectMapper = ObjectMapper(),
                outboundWebhookRepository = outboundWebhookRepo,
                webhookDeliveryRepository = deliveryRepo,
                circuitBreaker = circuitBreaker,
                dispatcher = dispatcher,
                secretEncryptor = secretEncryptor,
            )
    }

    @AfterEach
    fun cleanUp() {
        dsl.execute("DELETE FROM webhook_deliveries")
        dsl.execute("DELETE FROM outbound_webhooks")
        drainQueue()
    }

    private fun createWebhook(
        url: String = "https://example.com/hook",
        eventFilter: List<String> = listOf(WebhookEventCatalog.ISSUE_CREATED),
        secretEncrypted: String? = null,
        projectKey: String? = null,
    ): OutboundWebhook =
        outboundWebhookRepo.save(
            OutboundWebhook.create(
                createdBy = UUID.randomUUID(),
                name = "IT 테스트 웹훅",
                url = url,
                eventFilter = eventFilter,
                secretEncrypted = secretEncrypted,
                projectKey = projectKey,
            ),
        )

    /** wire JSON을 q_webhook_events에 enqueue하고 할당된 msg_id를 반환한다. */
    private fun enqueue(json: String): Long =
        dsl.fetchOne("SELECT * FROM pgmq.send(?, ?::jsonb)", WebhookDispatchWorker.QUEUE_NAME, json)
            ?.get(0, Long::class.java)
            ?: error("pgmq.send가 msg_id를 반환하지 않았습니다")

    /** 큐에 남아있는(가시 상태) 메시지 수를 확인한다. */
    private fun remainingMessages(): Int =
        dsl.fetch("SELECT * FROM pgmq.read(?, ?, ?)", WebhookDispatchWorker.QUEUE_NAME, 1, 10).size

    /** 테스트 간 큐 잔여 메시지를 모두 제거한다(vt=0으로 즉시 가시화 후 delete). */
    private fun drainQueue() {
        while (true) {
            val rows = dsl.fetch("SELECT * FROM pgmq.read(?, ?, ?)", WebhookDispatchWorker.QUEUE_NAME, 0, 50)
            if (rows.isEmpty()) break
            for (row in rows) {
                val msgId = row.get("msg_id", Long::class.java)
                dsl.execute("SELECT pgmq.delete(?, ?)", WebhookDispatchWorker.QUEUE_NAME, msgId)
            }
        }
    }

    // ── IT-1: fanout + 이력 + delete ─────────────────────────────────────────

    @Test
    fun `IT-1 매칭 구독 2건(평문+secret)에 fanout 발송하고 이력을 기록한 후 메시지를 delete한다`() {
        val plainWebhook = createWebhook(url = "https://example.com/hook-a", projectKey = "ATLAS")
        val secretWebhook =
            createWebhook(
                url = "https://example.com/hook-b",
                secretEncrypted = secretEncryptor.encrypt("whsec_abc"),
                projectKey = "ATLAS",
            )

        every { dispatcher.dispatch(any(), any(), any(), any(), any()) } returns WebhookDispatchResult.Sent(200)

        enqueue(ISSUE_CREATED_JSON)
        worker.pollAndProcess()

        verify(exactly = 1) {
            dispatcher.dispatch("https://example.com/hook-a", isNull(), "issue.created", any(), any())
        }
        verify(exactly = 1) {
            dispatcher.dispatch("https://example.com/hook-b", "whsec_abc", "issue.created", any(), any())
        }

        val plainDeliveries = deliveryRepo.listByWebhook(plainWebhook.id!!, page = 0, size = 10)
        val secretDeliveries = deliveryRepo.listByWebhook(secretWebhook.id!!, page = 0, size = 10)
        assertThat(plainDeliveries).hasSize(1)
        assertThat(plainDeliveries[0].status).isEqualTo(DeliveryStatus.SUCCEEDED)
        assertThat(secretDeliveries).hasSize(1)
        assertThat(secretDeliveries[0].status).isEqualTo(DeliveryStatus.SUCCEEDED)

        assertThat(remainingMessages())
            .describedAs("처리 완료 후 큐가 비어있어야 한다(delete 완료)")
            .isZero()
    }

    // ── IT-2: circuit OPEN 스킵 ───────────────────────────────────────────────

    @Test
    fun `IT-2 circuit OPEN 구독은 발송을 스킵하고 이력도 남기지 않는다`() {
        val openWebhook = createWebhook(url = "https://example.com/hook-open", projectKey = "ATLAS")
        val closedWebhook = createWebhook(url = "https://example.com/hook-closed", projectKey = "ATLAS")
        repeat(5) { circuitBreaker.recordFailure(openWebhook.id!!) }

        every { dispatcher.dispatch(any(), any(), any(), any(), any()) } returns WebhookDispatchResult.Sent(200)

        enqueue(ISSUE_CREATED_JSON)
        worker.pollAndProcess()

        verify(exactly = 0) {
            dispatcher.dispatch("https://example.com/hook-open", any(), any(), any(), any())
        }
        verify(exactly = 1) {
            dispatcher.dispatch("https://example.com/hook-closed", any(), any(), any(), any())
        }

        assertThat(deliveryRepo.listByWebhook(openWebhook.id!!, page = 0, size = 10)).isEmpty()
        assertThat(deliveryRepo.listByWebhook(closedWebhook.id!!, page = 0, size = 10)).hasSize(1)
    }

    // ── IT-3: poison → archive ────────────────────────────────────────────────

    @Test
    fun `IT-3 파싱 불가 poison 메시지는 read_ct가 MAX를 넘으면 archive되어 큐에서 사라진다`() {
        val msgId = enqueue("NOT_VALID_JSON{{{")

        // MAX_RECEIVE_COUNT(5)회까지는 재전달 대기 — 매 폴링 후 vt를 0으로 되돌려 즉시 재가시화한다.
        repeat(WebhookDispatchWorker.MAX_RECEIVE_COUNT) {
            worker.pollAndProcess()
            dsl.execute("SELECT pgmq.set_vt(?, ?, ?)", WebhookDispatchWorker.QUEUE_NAME, msgId, 0)
        }

        // read_ct가 MAX(5)를 초과하는 6번째 폴링 — archive 처리
        worker.pollAndProcess()

        assertThat(remainingMessages())
            .describedAs("read_ct > MAX 이면 archive되어 큐에서 사라져야 한다")
            .isZero()
    }

    private companion object {
        const val ISSUE_CREATED_JSON =
            """
            {
              "type": "issue.created",
              "issueKey": "ATLAS-1",
              "projectKey": "ATLAS",
              "summary": "IT 테스트 이슈",
              "reporterId": {"value": "11111111-1111-1111-1111-111111111111"},
              "actorId": {"value": "11111111-1111-1111-1111-111111111111"},
              "occurredAt": "2026-07-01T00:00:00Z"
            }
            """

        const val TEST_KEY = "it-test-app-encryption-key-webhook"
        const val TEST_SALT = "deadbeefcafef00d"

        @Volatile
        private var queueCreated = false

        /**
         * `q_webhook_events` 큐를 JVM 당 1회만 생성한다(멱등 가드).
         *
         * `SearchPersistenceTestBase`의 Postgres 컨테이너는 이 클래스 전용이 아니라 같은 companion
         * object를 상속하는 다른 영속성 테스트와 JVM 단위로 공유되므로, 이미 생성된 경우
         * `pgmq.create`가 예외를 던질 수 있다 — 그 경우 이미 존재한다고 보고 무시한다.
         */
        @Synchronized
        fun ensureQueueCreated(dsl: DSLContext) {
            if (queueCreated) return
            try {
                dsl.execute("SELECT pgmq.create(?)", WebhookDispatchWorker.QUEUE_NAME)
            } catch (e: Exception) {
                LoggerFactory.getLogger(WebhookDispatchWorkerIntegrationTest::class.java)
                    .debug("q_webhook_events 큐 생성 스킵(이미 존재 추정): {}", e.message)
            }
            queueCreated = true
        }
    }
}
