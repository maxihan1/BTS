// 구독형 아웃바운드 Webhook end-to-end 통합 테스트 — 발행 wire → 워커 → 실 HTTP 발송(서명) → 이력까지 조립 검증 (FR-API-03 PR3 Task 10)
package com.bts.search.webhook

import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import com.bts.search.webhook.dispatch.SearchWebhookDispatcher
import com.bts.search.webhook.dispatch.WebhookCircuitBreaker
import com.bts.search.webhook.dispatch.WebhookDispatchWorker
import com.bts.search.webhook.dispatch.WebhookSigner
import com.bts.search.webhook.domain.DeliveryStatus
import com.bts.search.webhook.domain.OutboundWebhook
import com.bts.search.webhook.domain.WebhookEventCatalog
import com.bts.search.webhook.persistence.JooqOutboundWebhookRepository
import com.bts.search.webhook.persistence.JooqWebhookDeliveryRepository
import com.bts.shared.crypto.SecretEncryptor
import com.bts.shared.http.OutboundHttpClientConfig
import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.http.UrlCheck
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 구독형 아웃바운드 Webhook end-to-end 통합 테스트 (FR-API-03 PR3 Task 10).
 *
 * 개별 seam 은 T2(발행)/T7(HTTP+서명)/T8(fanout·이력·circuit)/T9(이력 조회 API)의 단위·통합 테스트가
 * 이미 커버한다. 이 테스트는 그것들을 **하나의 실 경로로 잇는다** — 발행 wire JSON 을 `q_webhook_events`
 * 에 넣고, 실 [WebhookDispatchWorker] 가 폴링 → 매칭 → **실 [SearchWebhookDispatcher] 로 로컬 stub 서버에
 * HTTP POST** → HMAC 서명·payload·발송 이력을 검증한다. 신규 프로덕션 코드는 없다(조립 검증).
 *
 * ## 실 컴포넌트 조립 (mockk 는 SSRF 검증기만)
 * dispatcher/worker/signer/circuit/repository/pgmq 모두 실제다. SSRF 검증기([OutboundUrlValidator])만
 * mockk 로 loopback(127.0.0.1) 을 허용시킨다 — 실 SSRF 차단 규칙은 [SearchWebhookDispatcherTest](T7)가
 * 전담하고, 이 테스트의 관심사(발송이 실제로 도달하는가)에는 loopback 허용이 필요하기 때문이다.
 *
 * ## wire-contract seam 검증 (C2) — BC 격리에 의한 실용 구현
 * plan C2 는 "큐 fixture 를 손수 JSON 대신 실 `IssueEventPublisher` ObjectMapper 로 직렬화"를 요구한다.
 * 그러나 search BC 는 issue-tracking BC 에 의존하지 않으므로(BC 격리, 모듈 의존성 없음) 발행측
 * `IssueDomainEvent` 클래스를 직접 import 해 직렬화할 수 없다. 대신 **발행측(Spring Boot 기본
 * ObjectMapper)과 동일한 설정** — `registerKotlinModule` + `JavaTimeModule` +
 * `WRITE_DATES_AS_TIMESTAMPS=off` — 으로 [publisherMapper] 를 구성하고, 발행측과 **동일한 wire shape**
 * (내부 VO `reporterId`/`actorId`={value} nested 포함)를 이 mapper 로 직렬화해 주입한다. 이렇게 하면
 * (1) `occurredAt` 이 숫자 타임스탬프가 아닌 ISO 문자열로 나가고(pgmq occurredAt ISO 계약), (2) 소비측
 * 워커의 기본 [ObjectMapper] 파싱과 호환되는지, (3) 발행측이 실어 보내는 내부 VO 가 외부 payload 로
 * 새지 않는지(C3)를 실제 직렬화 경로로 검증한다. issue-tracking 클래스 직접 직렬화가 아니라는 점만
 * 원안과 다르다(BC 격리 제약).
 *
 * ## 큐 수동 생성 (C5)
 * `q_webhook_events` 는 issue-tracking V034 소유라 search 자체 Flyway 에는 없다. `@BeforeEach` 에서
 * JVM 당 1회 `pgmq.create` 로 생성한다([WebhookDispatchWorkerIntegrationTest] 동일 패턴).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookDispatchEndToEndIntegrationTest : SearchPersistenceTestBase() {
    private val outboundWebhookRepo get() = JooqOutboundWebhookRepository(dsl)
    private val deliveryRepo get() = JooqWebhookDeliveryRepository(dsl)
    private val secretEncryptor = SecretEncryptor(TEST_KEY, TEST_SALT)

    /** 발행측(IssueEventPublisher)과 동일한 ObjectMapper 설정 — C2 wire-contract seam. */
    private val publisherMapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private val validator = mockk<OutboundUrlValidator>()
    private lateinit var circuitBreaker: WebhookCircuitBreaker
    private lateinit var worker: WebhookDispatchWorker

    // ── 로컬 stub 수신 서버 (JDK 내장 HttpServer, 경로별 캡처) ────────────────────

    private data class Capture(
        val hits: AtomicInteger = AtomicInteger(0),
        val lastEvent: AtomicReference<String?> = AtomicReference(),
        val lastDelivery: AtomicReference<String?> = AtomicReference(),
        val lastSignature: AtomicReference<String?> = AtomicReference(),
        val lastBody: AtomicReference<String?> = AtomicReference(),
    )

    private val captures = ConcurrentHashMap<String, Capture>()

    @Volatile
    private var stubStatusCode = 200

    private val stubServer: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        stubServer.executor = null
        stubServer.createContext("/") { exchange ->
            val cap = captures.getOrPut(exchange.requestURI.path) { Capture() }
            cap.hits.incrementAndGet()
            cap.lastEvent.set(exchange.requestHeaders.getFirst("X-BTS-Event"))
            cap.lastDelivery.set(exchange.requestHeaders.getFirst("X-BTS-Delivery"))
            cap.lastSignature.set(exchange.requestHeaders.getFirst("X-BTS-Signature"))
            cap.lastBody.set(exchange.requestBody.bufferedReader().readText())
            val responseBytes = "OK".toByteArray()
            exchange.sendResponseHeaders(stubStatusCode, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        stubServer.start()
    }

    @AfterAll
    fun stopServer() {
        stubServer.stop(0)
    }

    private val stubBase get() = "http://127.0.0.1:${stubServer.address.port}"

    @BeforeEach
    fun setUp() {
        ensureQueueCreated(dsl)
        circuitBreaker = WebhookCircuitBreaker()
        val dispatcher = SearchWebhookDispatcher(validator, OutboundHttpClientConfig().outboundHttpRestClient())
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
        // loopback stub 은 허용 — 실 SSRF 차단 규칙은 T7 이 전담.
        every { validator.check(any()) } returns UrlCheck.Allowed
        captures.clear()
        stubStatusCode = 200
    }

    @AfterEach
    fun cleanUp() {
        dsl.execute("DELETE FROM webhook_deliveries")
        dsl.execute("DELETE FROM outbound_webhooks")
        drainQueue()
    }

    // ── IT-E2E-1: issue.created 실 발송 — C2 직렬화 + HMAC 서명 + C3 누출 차단 + B1 멱등키 + 이력 ──

    @Test
    fun `IT-E2E-1 issue_created 구독에 실제 HTTP 발송하고 서명·payload·이력을 검증한다`() {
        val secret = "whsec_e2e_created"
        val webhook =
            saveWebhook(
                url = "$stubBase/hook-created",
                eventFilter = listOf(WebhookEventCatalog.ISSUE_CREATED),
                secretEncrypted = secretEncryptor.encrypt(secret),
                projectKey = "ATLAS",
            )

        val actorId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val wire =
            publisherMapper.writeValueAsString(
                // 발행측과 동일한 wire shape — 내부 VO(reporterId/actorId) 포함, occurredAt=Instant → ISO.
                linkedMapOf(
                    "type" to "issue.created",
                    "issueKey" to "ATLAS-42",
                    "projectKey" to "ATLAS",
                    "summary" to "E2E 생성 이슈",
                    "reporterId" to linkedMapOf("value" to actorId.toString()),
                    "actorId" to linkedMapOf("value" to actorId.toString()),
                    "occurredAt" to Instant.parse("2026-07-01T00:00:00Z"),
                ),
            )
        // occurredAt 이 숫자 타임스탬프가 아닌 ISO 문자열로 직렬화됐는지(발행↔소비 계약).
        assertThat(wire).contains("\"occurredAt\":\"2026-07-01T00:00:00Z\"")

        val msgId = enqueue(wire)
        worker.pollAndProcess()

        val cap = captures["/hook-created"] ?: error("stub 서버가 발송을 받지 못했습니다")
        assertThat(cap.hits.get()).isEqualTo(1)
        assertThat(cap.lastEvent.get()).isEqualTo("issue.created")

        // B1 — X-BTS-Delivery 는 webhookId:msgId 기반 결정적 안정 키.
        val expectedDeliveryId =
            UUID.nameUUIDFromBytes("${webhook.id}:$msgId".toByteArray(Charsets.UTF_8)).toString()
        assertThat(cap.lastDelivery.get()).isEqualTo(expectedDeliveryId)

        // HMAC — 서명값은 수신 body 원문에 대한 sha256 HMAC.
        val body = cap.lastBody.get() ?: error("수신 body 가 없습니다")
        assertThat(cap.lastSignature.get())
            .isEqualTo(WebhookSigner.sign(secret, body.toByteArray(Charsets.UTF_8)))

        // C3 — payload 는 화이트리스트 스칼라만. 내부 VO/사용자 UUID 미노출.
        assertThat(body).contains("ATLAS-42", "E2E 생성 이슈", expectedDeliveryId)
        assertThat(body)
            .doesNotContain("actorId", "reporterId", "value", actorId.toString())

        // 이력 — SUCCEEDED.
        val deliveries = deliveryRepo.listByWebhook(webhook.id!!, page = 0, size = 10)
        assertThat(deliveries).hasSize(1)
        assertThat(deliveries[0].status).isEqualTo(DeliveryStatus.SUCCEEDED)
        assertThat(deliveries[0].responseCode).isEqualTo(200)

        // 처리 완료 후 큐 비어있음(delete).
        assertThat(remainingMessages()).isZero()
    }

    // ── IT-E2E-2: issue.transitioned 실 발송 — projectKey 파생 + fromState/toState payload ──

    @Test
    fun `IT-E2E-2 issue_transitioned 구독에 발송하고 projectKey 파생·전환 payload 를 검증한다`() {
        val webhook =
            saveWebhook(
                url = "$stubBase/hook-transitioned",
                eventFilter = listOf(WebhookEventCatalog.ISSUE_TRANSITIONED),
                secretEncrypted = null,
                projectKey = "ATLAS",
            )

        val wire =
            publisherMapper.writeValueAsString(
                // issue.transitioned 는 projectKey 필드가 없다 — 워커가 issueKey 접두사에서 파생(EC1).
                linkedMapOf(
                    "type" to "issue.transitioned",
                    "issueKey" to "ATLAS-7",
                    "fromState" to "OPEN",
                    "toState" to "IN_PROGRESS",
                    "actorId" to linkedMapOf("value" to UUID.randomUUID().toString()),
                    "occurredAt" to Instant.parse("2026-07-01T01:00:00Z"),
                ),
            )

        enqueue(wire)
        worker.pollAndProcess()

        val cap = captures["/hook-transitioned"] ?: error("stub 서버가 발송을 받지 못했습니다")
        assertThat(cap.hits.get()).isEqualTo(1)
        assertThat(cap.lastEvent.get()).isEqualTo("issue.transitioned")
        // secret 미설정 구독은 서명 헤더가 없다.
        assertThat(cap.lastSignature.get()).isNull()

        val body = cap.lastBody.get() ?: error("수신 body 가 없습니다")
        assertThat(body).contains("ATLAS-7", "OPEN", "IN_PROGRESS", "\"projectKey\":\"ATLAS\"")
        assertThat(body).doesNotContain("actorId", "value")

        val deliveries = deliveryRepo.listByWebhook(webhook.id!!, page = 0, size = 10)
        assertThat(deliveries).hasSize(1)
        assertThat(deliveries[0].status).isEqualTo(DeliveryStatus.SUCCEEDED)
    }

    // ── IT-E2E-3: under-send 가드 (C4) — PUBLISHABLE 각 이벤트가 실제로 발송 도달 ──

    @Test
    fun `IT-E2E-3 PUBLISHABLE 각 이벤트가 구독에 발송 도달하고 allowlist 정합을 지킨다`() {
        // C4 — allowlist 가 조용히 축소되면(under-send) 이 단언이 깨진다.
        assertThat(WebhookEventCatalog.PUBLISHABLE).hasSize(2)

        val createdHook =
            saveWebhook(
                url = "$stubBase/hook-c4-created",
                eventFilter = listOf(WebhookEventCatalog.ISSUE_CREATED),
                projectKey = "ATLAS",
            )
        val transitionedHook =
            saveWebhook(
                url = "$stubBase/hook-c4-transitioned",
                eventFilter = listOf(WebhookEventCatalog.ISSUE_TRANSITIONED),
                projectKey = "ATLAS",
            )

        enqueue(createdWire("ATLAS-100", "C4 생성"))
        worker.pollAndProcess()
        enqueue(transitionedWire("ATLAS-101"))
        worker.pollAndProcess()

        assertThat(captures["/hook-c4-created"]?.hits?.get())
            .describedAs("issue.created 구독에 발송 도달")
            .isEqualTo(1)
        assertThat(captures["/hook-c4-transitioned"]?.hits?.get())
            .describedAs("issue.transitioned 구독에 발송 도달")
            .isEqualTo(1)

        assertThat(deliveryRepo.listByWebhook(createdHook.id!!, 0, 10)).hasSize(1)
        assertThat(deliveryRepo.listByWebhook(transitionedHook.id!!, 0, 10)).hasSize(1)
    }

    // ── private helpers ─────────────────────────────────────────────────────────

    private fun saveWebhook(
        url: String,
        eventFilter: List<String>,
        secretEncrypted: String? = null,
        projectKey: String? = null,
    ): OutboundWebhook =
        outboundWebhookRepo.save(
            OutboundWebhook.create(
                createdBy = UUID.randomUUID(),
                name = "E2E 웹훅",
                url = url,
                eventFilter = eventFilter,
                secretEncrypted = secretEncrypted,
                projectKey = projectKey,
            ),
        )

    private fun createdWire(
        issueKey: String,
        summary: String,
    ): String =
        publisherMapper.writeValueAsString(
            linkedMapOf(
                "type" to "issue.created",
                "issueKey" to issueKey,
                "projectKey" to issueKey.substringBefore("-"),
                "summary" to summary,
                "actorId" to linkedMapOf("value" to UUID.randomUUID().toString()),
                "occurredAt" to Instant.parse("2026-07-01T02:00:00Z"),
            ),
        )

    private fun transitionedWire(issueKey: String): String =
        publisherMapper.writeValueAsString(
            linkedMapOf(
                "type" to "issue.transitioned",
                "issueKey" to issueKey,
                "fromState" to "OPEN",
                "toState" to "DONE",
                "occurredAt" to Instant.parse("2026-07-01T02:00:00Z"),
            ),
        )

    private fun enqueue(json: String): Long =
        dsl.fetchOne("SELECT * FROM pgmq.send(?, ?::jsonb)", WebhookDispatchWorker.QUEUE_NAME, json)
            ?.get(0, Long::class.java)
            ?: error("pgmq.send 가 msg_id 를 반환하지 않았습니다")

    private fun remainingMessages(): Int {
        return dsl.fetch("SELECT * FROM pgmq.read(?, ?, ?)", WebhookDispatchWorker.QUEUE_NAME, 1, 10).size
    }

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

    private companion object {
        const val TEST_KEY = "it-test-app-encryption-key-webhook"
        const val TEST_SALT = "deadbeefcafef00d"

        @Volatile
        private var queueCreated = false

        /** `q_webhook_events` 큐를 JVM 당 1회만 생성한다(멱등 가드 — 이미 있으면 무시). */
        @Synchronized
        fun ensureQueueCreated(dsl: DSLContext) {
            if (queueCreated) return
            try {
                dsl.execute("SELECT pgmq.create(?)", WebhookDispatchWorker.QUEUE_NAME)
            } catch (e: Exception) {
                LoggerFactory.getLogger(WebhookDispatchEndToEndIntegrationTest::class.java)
                    .debug("q_webhook_events 큐 생성 스킵(이미 존재 추정): {}", e.message)
            }
            queueCreated = true
        }
    }
}
