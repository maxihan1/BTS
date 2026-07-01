// WebhookDispatchWorker 단위 테스트 — pgmq 폴링 생명주기, fanout, circuit breaker, secret 복호화, payload 화이트리스트 검증
package com.bts.search.webhook.dispatch

import com.bts.search.webhook.application.OutboundWebhookRepository
import com.bts.search.webhook.application.WebhookDeliveryRepository
import com.bts.search.webhook.domain.DeliveryStatus
import com.bts.search.webhook.domain.OutboundWebhook
import com.bts.search.webhook.domain.WebhookDelivery
import com.bts.search.webhook.domain.WebhookEventCatalog
import com.bts.shared.crypto.SecretEncryptor
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jooq.DSLContext
import java.time.Instant
import java.util.UUID

/**
 * [WebhookDispatchWorker] 단위 테스트.
 *
 * MockK로 모든 협력자([DSLContext]/[OutboundWebhookRepository]/[WebhookDeliveryRepository]/
 * [WebhookCircuitBreaker]/[SearchWebhookDispatcher]/[SecretEncryptor])를 mock — Testcontainers 불요.
 * pgmq 폴링 생명주기 부분은 notification `WebhookDispatchWorkerTest` 패턴을 미러링한다.
 *
 * ### 검증 항목
 * - W-1. 빈 큐 → 아무 처리 없음
 * - W-2. issue.created + 매칭 1건(secret 없음) → dispatcher 호출 + SUCCEEDED 이력 + circuit success + delete
 * - W-3. payload 화이트리스트(C3) — data 는 issueKey/projectKey/summary 만, actorId/reporterId 미포함
 * - W-4. secret 설정 구독 → 복호화된 평문을 dispatcher 에 전달
 * - W-5. 매칭 구독 0개(EC3) → 발송 없이 delete
 * - W-6. circuit OPEN 구독(EC5) → 스킵(이력 없음), 다른 매칭 구독은 정상 처리
 * - W-7. secret 복호화 실패(C7) → 해당 구독만 FAILED 기록, 배치는 계속
 * - W-8. dispatcher Rejected → FAILED 기록(내부 사유 미echo, EC6) + circuit 실패
 * - W-9. dispatcher Failed(non-2xx) → FAILED 기록(reason/code 그대로) + circuit 실패
 * - W-10. fanout 3건 중 1건 실패해도 메시지는 1회만 delete(EC4)
 * - W-11. issue.transitioned → projectKey 는 issueKey 접두사에서 파싱(EC1), payload 에 summary 없음
 * - W-12. 발행 불가 type(EC8 방어) → 무시하고 delete
 * - W-13. JSON 파싱 실패(poison) → read_ct 초과 여부에 따라 재전달 대기/archive
 * - W-14. fanout 중 예외 발생 → read_ct 초과 여부에 따라 재전달 대기/archive
 * - W-15. attemptCount 는 pgmq read_ct 를 그대로 사용한다
 * - W-16. deliveryId(B1) — 같은 webhookId+msgId 조합은 항상 같은 값을 만든다(결정적)
 */
class WebhookDispatchWorkerTest : DescribeSpec({

    val dsl = mockk<DSLContext>()
    val objectMapper = ObjectMapper()
    val outboundWebhookRepository = mockk<OutboundWebhookRepository>()
    val webhookDeliveryRepository = mockk<WebhookDeliveryRepository>()
    val circuitBreaker = mockk<WebhookCircuitBreaker>()
    val dispatcher = mockk<SearchWebhookDispatcher>()
    val secretEncryptor = mockk<SecretEncryptor>()

    val worker =
        WebhookDispatchWorker(
            dsl = dsl,
            objectMapper = objectMapper,
            outboundWebhookRepository = outboundWebhookRepository,
            webhookDeliveryRepository = webhookDeliveryRepository,
            circuitBreaker = circuitBreaker,
            dispatcher = dispatcher,
            secretEncryptor = secretEncryptor,
        )

    afterEach {
        clearMocks(dsl, outboundWebhookRepository, webhookDeliveryRepository, circuitBreaker, dispatcher, secretEncryptor)
    }

    // ── W-1: 빈 큐 ────────────────────────────────────────────────────────────

    describe("W-1 빈 큐") {
        it("메시지가 없으면 아무 협력자도 호출하지 않는다") {
            stubEmptyQueue(dsl)

            worker.pollAndProcess()

            verify(exactly = 0) { outboundWebhookRepository.findMatching(any(), any()) }
            verify(exactly = 0) { dispatcher.dispatch(any(), any(), any(), any(), any()) }
        }
    }

    // ── W-2: happy path ───────────────────────────────────────────────────────

    describe("W-2 issue.created + 매칭 1건(secret 없음) → 발송 성공") {
        val msgId = 2L
        val webhookId = UUID.randomUUID()
        val webhook = buildWebhook(id = webhookId, url = "https://example.com/hook", projectKey = "ATLAS")

        beforeEach {
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON)
            every { outboundWebhookRepository.findMatching("issue.created", "ATLAS") } returns listOf(webhook)
            every { circuitBreaker.isOpen(webhookId) } returns false
            every { dispatcher.dispatch(any(), any(), any(), any(), any()) } returns WebhookDispatchResult.Sent(200)
            every { webhookDeliveryRepository.record(any(), any(), any(), any(), any(), any()) } returns dummyDelivery()
            every { circuitBreaker.recordSuccess(webhookId) } returns Unit
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("dispatcher.dispatch를 secret=null로 1회 호출한다") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dispatcher.dispatch("https://example.com/hook", isNull(), "issue.created", any(), any())
            }
        }

        it("SUCCEEDED 이력을 기록하고 circuit success를 반영한다") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                webhookDeliveryRepository.record(webhookId, "issue.created", DeliveryStatus.SUCCEEDED, 200, 1, isNull())
            }
            verify(exactly = 1) { circuitBreaker.recordSuccess(webhookId) }
        }

        it("처리 완료 후 pgmq.delete를 호출한다") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, WebhookDispatchWorker.QUEUE_NAME, msgId)
            }
        }
    }

    // ── W-3: payload 화이트리스트 ─────────────────────────────────────────────

    describe("W-3 payload 화이트리스트(C3) — 내부 VO/사용자 UUID 미노출") {
        val msgId = 3L
        val webhookId = UUID.randomUUID()
        val webhook = buildWebhook(id = webhookId, projectKey = "ATLAS")

        beforeEach {
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON)
            every { outboundWebhookRepository.findMatching("issue.created", "ATLAS") } returns listOf(webhook)
            every { circuitBreaker.isOpen(webhookId) } returns false
            every { dispatcher.dispatch(any(), any(), any(), any(), any()) } returns WebhookDispatchResult.Sent(200)
            every { webhookDeliveryRepository.record(any(), any(), any(), any(), any(), any()) } returns dummyDelivery()
            every { circuitBreaker.recordSuccess(webhookId) } returns Unit
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("data는 issueKey/projectKey/summary만 포함하고 actorId/reporterId는 없다") {
            val bodySlot = slot<ByteArray>()
            worker.pollAndProcess()
            verify { dispatcher.dispatch(any(), any(), any(), any(), capture(bodySlot)) }

            val json = ObjectMapper().readTree(bodySlot.captured)
            json.path("event").asText() shouldBe "issue.created"
            json.path("occurredAt").asText() shouldBe "2026-07-01T00:00:00Z"
            val data = json.path("data")
            data.path("issueKey").asText() shouldBe "ATLAS-1"
            data.path("projectKey").asText() shouldBe "ATLAS"
            data.path("summary").asText() shouldBe "테스트 이슈"
            data.has("actorId") shouldBe false
            data.has("reporterId") shouldBe false
        }
    }

    // ── W-4: secret 설정 구독 ─────────────────────────────────────────────────

    describe("W-4 secret이 설정된 구독은 복호화된 평문을 dispatcher에 전달한다") {
        val msgId = 4L
        val webhookId = UUID.randomUUID()
        val webhook = buildWebhook(id = webhookId, secretEncrypted = "cipher-abc", projectKey = "ATLAS")

        beforeEach {
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON)
            every { outboundWebhookRepository.findMatching("issue.created", "ATLAS") } returns listOf(webhook)
            every { circuitBreaker.isOpen(webhookId) } returns false
            every { secretEncryptor.decrypt("cipher-abc") } returns "plaintext-secret"
            every { dispatcher.dispatch(any(), any(), any(), any(), any()) } returns WebhookDispatchResult.Sent(200)
            every { webhookDeliveryRepository.record(any(), any(), any(), any(), any(), any()) } returns dummyDelivery()
            every { circuitBreaker.recordSuccess(webhookId) } returns Unit
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("dispatcher에 secret=plaintext-secret을 전달한다") {
            worker.pollAndProcess()
            verify(exactly = 1) { dispatcher.dispatch(any(), "plaintext-secret", any(), any(), any()) }
        }
    }

    // ── W-5: 매칭 구독 0개 ────────────────────────────────────────────────────

    describe("W-5 매칭 구독 0개(EC3) — 발송 없이 delete") {
        val msgId = 5L

        beforeEach {
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON)
            every { outboundWebhookRepository.findMatching("issue.created", "ATLAS") } returns emptyList()
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("dispatcher를 호출하지 않는다") {
            worker.pollAndProcess()
            verify(exactly = 0) { dispatcher.dispatch(any(), any(), any(), any(), any()) }
        }

        it("메시지는 delete된다") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, WebhookDispatchWorker.QUEUE_NAME, msgId)
            }
        }
    }

    // ── W-6: circuit OPEN 스킵 ────────────────────────────────────────────────

    describe("W-6 circuit OPEN 구독(EC5) — 스킵, 다른 매칭 구독은 정상 처리") {
        val msgId = 6L
        val openId = UUID.randomUUID()
        val closedId = UUID.randomUUID()
        val openWebhook = buildWebhook(id = openId, url = "https://example.com/open", projectKey = "ATLAS")
        val closedWebhook = buildWebhook(id = closedId, url = "https://example.com/closed", projectKey = "ATLAS")

        beforeEach {
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON)
            every { outboundWebhookRepository.findMatching("issue.created", "ATLAS") } returns
                listOf(openWebhook, closedWebhook)
            every { circuitBreaker.isOpen(openId) } returns true
            every { circuitBreaker.isOpen(closedId) } returns false
            every { dispatcher.dispatch("https://example.com/closed", any(), any(), any(), any()) } returns
                WebhookDispatchResult.Sent(200)
            every { webhookDeliveryRepository.record(any(), any(), any(), any(), any(), any()) } returns dummyDelivery()
            every { circuitBreaker.recordSuccess(closedId) } returns Unit
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("OPEN 구독은 dispatcher를 호출하지 않는다") {
            worker.pollAndProcess()
            verify(exactly = 0) { dispatcher.dispatch("https://example.com/open", any(), any(), any(), any()) }
        }

        it("OPEN 구독은 이력을 기록하지 않는다") {
            worker.pollAndProcess()
            verify(exactly = 0) { webhookDeliveryRepository.record(openId, any(), any(), any(), any(), any()) }
        }

        it("CLOSED 구독은 정상 발송되고 이력이 기록된다") {
            worker.pollAndProcess()
            verify(exactly = 1) { dispatcher.dispatch("https://example.com/closed", any(), any(), any(), any()) }
            verify(exactly = 1) {
                webhookDeliveryRepository.record(closedId, "issue.created", DeliveryStatus.SUCCEEDED, 200, 1, isNull())
            }
        }
    }

    // ── W-7: secret 복호화 실패 ───────────────────────────────────────────────

    describe("W-7 secret 복호화 실패(C7) — 해당 구독만 FAILED, 배치는 계속") {
        val msgId = 7L
        val badId = UUID.randomUUID()
        val goodId = UUID.randomUUID()
        val badWebhook =
            buildWebhook(id = badId, url = "https://bad.example.com/hook", secretEncrypted = "corrupt", projectKey = "ATLAS")
        val goodWebhook = buildWebhook(id = goodId, url = "https://good.example.com/hook", projectKey = "ATLAS")

        beforeEach {
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON)
            every { outboundWebhookRepository.findMatching("issue.created", "ATLAS") } returns
                listOf(badWebhook, goodWebhook)
            every { circuitBreaker.isOpen(any()) } returns false
            every { secretEncryptor.decrypt("corrupt") } throws IllegalStateException("decrypt failed: bad key")
            every { dispatcher.dispatch("https://good.example.com/hook", any(), any(), any(), any()) } returns
                WebhookDispatchResult.Sent(200)
            every { webhookDeliveryRepository.record(any(), any(), any(), any(), any(), any()) } returns dummyDelivery()
            every { circuitBreaker.recordFailure(badId) } returns Unit
            every { circuitBreaker.recordSuccess(goodId) } returns Unit
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("복호화 실패 구독은 dispatcher를 호출하지 않는다") {
            worker.pollAndProcess()
            verify(exactly = 0) { dispatcher.dispatch("https://bad.example.com/hook", any(), any(), any(), any()) }
        }

        it("복호화 실패 구독은 FAILED 이력 + circuit 실패를 기록하되 예외 메시지를 그대로 노출하지 않는다") {
            val errorSlot = slot<String>()
            worker.pollAndProcess()
            verify(exactly = 1) {
                webhookDeliveryRepository.record(badId, "issue.created", DeliveryStatus.FAILED, isNull(), 1, capture(errorSlot))
            }
            verify(exactly = 1) { circuitBreaker.recordFailure(badId) }
            errorSlot.captured.contains("bad key") shouldBe false
        }

        it("배치는 계속되어 다음 구독은 정상 발송된다") {
            worker.pollAndProcess()
            verify(exactly = 1) { dispatcher.dispatch("https://good.example.com/hook", any(), any(), any(), any()) }
            verify(exactly = 1) {
                webhookDeliveryRepository.record(goodId, "issue.created", DeliveryStatus.SUCCEEDED, 200, 1, isNull())
            }
        }
    }

    // ── W-8: dispatcher Rejected ──────────────────────────────────────────────

    describe("W-8 dispatcher Rejected(SSRF 차단, EC6) — FAILED 기록(내부 사유 미echo) + circuit 실패") {
        val msgId = 8L
        val webhookId = UUID.randomUUID()
        val webhook = buildWebhook(id = webhookId, projectKey = "ATLAS")
        val internalReason = "내부망 주소 차단: 10.0.0.5"

        beforeEach {
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON)
            every { outboundWebhookRepository.findMatching("issue.created", "ATLAS") } returns listOf(webhook)
            every { circuitBreaker.isOpen(webhookId) } returns false
            every { dispatcher.dispatch(any(), any(), any(), any(), any()) } returns
                WebhookDispatchResult.Rejected(internalReason)
            every { webhookDeliveryRepository.record(any(), any(), any(), any(), any(), any()) } returns dummyDelivery()
            every { circuitBreaker.recordFailure(webhookId) } returns Unit
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("FAILED 이력을 기록하되 내부 차단 사유(IP)는 echo하지 않는다") {
            val errorSlot = slot<String>()
            worker.pollAndProcess()
            verify(exactly = 1) {
                webhookDeliveryRepository.record(webhookId, "issue.created", DeliveryStatus.FAILED, isNull(), 1, capture(errorSlot))
            }
            errorSlot.captured.contains("10.0.0.5") shouldBe false
        }

        it("circuit 실패를 기록한다") {
            worker.pollAndProcess()
            verify(exactly = 1) { circuitBreaker.recordFailure(webhookId) }
        }
    }

    // ── W-9: dispatcher Failed ────────────────────────────────────────────────

    describe("W-9 dispatcher Failed(non-2xx) — FAILED 기록(reason/code 그대로) + circuit 실패") {
        val msgId = 9L
        val webhookId = UUID.randomUUID()
        val webhook = buildWebhook(id = webhookId, projectKey = "ATLAS")

        beforeEach {
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON)
            every { outboundWebhookRepository.findMatching("issue.created", "ATLAS") } returns listOf(webhook)
            every { circuitBreaker.isOpen(webhookId) } returns false
            every { dispatcher.dispatch(any(), any(), any(), any(), any()) } returns
                WebhookDispatchResult.Failed("non-2xx status: 500", 500)
            every { webhookDeliveryRepository.record(any(), any(), any(), any(), any(), any()) } returns dummyDelivery()
            every { circuitBreaker.recordFailure(webhookId) } returns Unit
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("responseCode/errorDetail을 그대로 기록하고 circuit 실패를 반영한다") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                webhookDeliveryRepository.record(
                    webhookId,
                    "issue.created",
                    DeliveryStatus.FAILED,
                    500,
                    1,
                    "non-2xx status: 500",
                )
            }
            verify(exactly = 1) { circuitBreaker.recordFailure(webhookId) }
        }
    }

    // ── W-10: fanout 부분 실패 ────────────────────────────────────────────────

    describe("W-10 fanout — 3개 매칭 중 1개 실패해도 메시지는 1회만 delete된다(EC4)") {
        val msgId = 10L
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val webhooks =
            ids.mapIndexed { idx, id -> buildWebhook(id = id, url = "https://example.com/hook$idx", projectKey = "ATLAS") }

        beforeEach {
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON)
            every { outboundWebhookRepository.findMatching("issue.created", "ATLAS") } returns webhooks
            every { circuitBreaker.isOpen(any()) } returns false
            every { dispatcher.dispatch("https://example.com/hook0", any(), any(), any(), any()) } returns
                WebhookDispatchResult.Sent(200)
            every { dispatcher.dispatch("https://example.com/hook1", any(), any(), any(), any()) } returns
                WebhookDispatchResult.Failed("timeout")
            every { dispatcher.dispatch("https://example.com/hook2", any(), any(), any(), any()) } returns
                WebhookDispatchResult.Sent(200)
            every { webhookDeliveryRepository.record(any(), any(), any(), any(), any(), any()) } returns dummyDelivery()
            every { circuitBreaker.recordSuccess(any()) } returns Unit
            every { circuitBreaker.recordFailure(any()) } returns Unit
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("dispatcher를 3회 호출한다") {
            worker.pollAndProcess()
            verify(exactly = 3) { dispatcher.dispatch(any(), any(), any(), any(), any()) }
        }

        it("메시지는 정확히 1회 delete된다") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, WebhookDispatchWorker.QUEUE_NAME, msgId)
            }
        }
    }

    // ── W-11: issue.transitioned ──────────────────────────────────────────────

    describe("W-11 issue.transitioned — projectKey는 issueKey 접두사에서 파싱된다(EC1)") {
        val msgId = 11L
        val webhookId = UUID.randomUUID()
        val webhook =
            buildWebhook(id = webhookId, eventFilter = listOf(WebhookEventCatalog.ISSUE_TRANSITIONED), projectKey = "ATLAS")

        beforeEach {
            stubMessage(dsl, msgId, ISSUE_TRANSITIONED_JSON)
            every { outboundWebhookRepository.findMatching("issue.transitioned", "ATLAS") } returns listOf(webhook)
            every { circuitBreaker.isOpen(webhookId) } returns false
            every { dispatcher.dispatch(any(), any(), any(), any(), any()) } returns WebhookDispatchResult.Sent(200)
            every { webhookDeliveryRepository.record(any(), any(), any(), any(), any(), any()) } returns dummyDelivery()
            every { circuitBreaker.recordSuccess(webhookId) } returns Unit
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("findMatching을 파싱된 projectKey(ATLAS)로 호출한다") {
            worker.pollAndProcess()
            verify(exactly = 1) { outboundWebhookRepository.findMatching("issue.transitioned", "ATLAS") }
        }

        it("payload data는 issueKey/projectKey/fromState/toState만 포함하고 summary는 없다") {
            val bodySlot = slot<ByteArray>()
            worker.pollAndProcess()
            verify { dispatcher.dispatch(any(), any(), any(), any(), capture(bodySlot)) }

            val data = ObjectMapper().readTree(bodySlot.captured).path("data")
            data.path("issueKey").asText() shouldBe "ATLAS-42"
            data.path("projectKey").asText() shouldBe "ATLAS"
            data.path("fromState").asText() shouldBe "open"
            data.path("toState").asText() shouldBe "in_progress"
            data.has("summary") shouldBe false
        }
    }

    // ── W-12: 발행 불가 type 방어 ─────────────────────────────────────────────

    describe("W-12 발행 불가 type(EC8 방어) — 무시하고 delete") {
        val msgId = 12L

        beforeEach {
            stubMessage(dsl, msgId, """{"type":"issue.updated","issueKey":"ATLAS-1"}""")
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("findMatching/dispatcher를 호출하지 않는다") {
            worker.pollAndProcess()
            verify(exactly = 0) { outboundWebhookRepository.findMatching(any(), any()) }
            verify(exactly = 0) { dispatcher.dispatch(any(), any(), any(), any(), any()) }
        }

        it("메시지는 delete된다") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, WebhookDispatchWorker.QUEUE_NAME, msgId)
            }
        }
    }

    // ── W-13: JSON 파싱 실패(poison) ──────────────────────────────────────────

    describe("W-13 JSON 파싱 실패(poison 메시지)") {
        val msgId = 13L

        describe("read_ct <= MAX이면 재전달 대기") {
            beforeEach { stubMessage(dsl, msgId, "NOT_VALID_JSON{{{", readCt = 1) }

            it("delete/archive 모두 호출하지 않는다") {
                worker.pollAndProcess()
                verify(exactly = 0) { dsl.execute(match<String> { it.contains("pgmq.delete") }, any(), any<Long>()) }
                verify(exactly = 0) { dsl.execute(match<String> { it.contains("pgmq.archive") }, any(), any<Long>()) }
            }
        }

        describe("read_ct > MAX이면 archive") {
            beforeEach {
                stubMessage(dsl, msgId, "NOT_VALID_JSON{{{", readCt = WebhookDispatchWorker.MAX_RECEIVE_COUNT + 1)
                every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
            }

            it("archive를 호출한다") {
                worker.pollAndProcess()
                verify(exactly = 1) {
                    dsl.execute(match<String> { it.contains("pgmq.archive") }, WebhookDispatchWorker.QUEUE_NAME, msgId)
                }
            }
        }
    }

    // ── W-14: fanout 중 예외 ──────────────────────────────────────────────────

    describe("W-14 fanout 중 예외 발생") {
        val msgId = 14L

        describe("read_ct <= MAX이면 delete/archive 생략(재전달 대기)") {
            beforeEach {
                stubMessage(dsl, msgId, ISSUE_CREATED_JSON, readCt = 1)
                every { outboundWebhookRepository.findMatching(any(), any()) } throws RuntimeException("DB 커넥션 오류")
            }

            it("delete/archive 모두 호출하지 않는다") {
                worker.pollAndProcess()
                verify(exactly = 0) { dsl.execute(match<String> { it.contains("pgmq.delete") }, any(), any<Long>()) }
                verify(exactly = 0) { dsl.execute(match<String> { it.contains("pgmq.archive") }, any(), any<Long>()) }
            }
        }

        describe("read_ct > MAX이면 archive") {
            beforeEach {
                stubMessage(dsl, msgId, ISSUE_CREATED_JSON, readCt = WebhookDispatchWorker.MAX_RECEIVE_COUNT + 1)
                every { outboundWebhookRepository.findMatching(any(), any()) } throws RuntimeException("DB 커넥션 오류")
                every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
            }

            it("archive를 호출한다") {
                worker.pollAndProcess()
                verify(exactly = 1) {
                    dsl.execute(match<String> { it.contains("pgmq.archive") }, WebhookDispatchWorker.QUEUE_NAME, msgId)
                }
            }
        }
    }

    // ── W-15: attemptCount = read_ct ──────────────────────────────────────────

    describe("W-15 attemptCount는 pgmq read_ct를 그대로 사용한다") {
        val msgId = 15L
        val webhookId = UUID.randomUUID()
        val webhook = buildWebhook(id = webhookId, projectKey = "ATLAS")

        beforeEach {
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON, readCt = 3)
            every { outboundWebhookRepository.findMatching("issue.created", "ATLAS") } returns listOf(webhook)
            every { circuitBreaker.isOpen(webhookId) } returns false
            every { dispatcher.dispatch(any(), any(), any(), any(), any()) } returns WebhookDispatchResult.Sent(200)
            every { webhookDeliveryRepository.record(any(), any(), any(), any(), any(), any()) } returns dummyDelivery()
            every { circuitBreaker.recordSuccess(webhookId) } returns Unit
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("record의 attemptCount는 3이다") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                webhookDeliveryRepository.record(webhookId, "issue.created", DeliveryStatus.SUCCEEDED, 200, 3, isNull())
            }
        }
    }

    // ── W-16: deliveryId 결정성(B1) ───────────────────────────────────────────

    describe("W-16 deliveryId(B1 안정 멱등키) — 같은 webhookId+msgId는 항상 같은 값을 만든다") {
        val msgId = 16L
        val webhookId = UUID.randomUUID()
        val webhook = buildWebhook(id = webhookId, projectKey = "ATLAS")
        val capturedDeliveryIds = mutableListOf<String>()

        beforeEach {
            capturedDeliveryIds.clear()
            every { outboundWebhookRepository.findMatching("issue.created", "ATLAS") } returns listOf(webhook)
            every { circuitBreaker.isOpen(webhookId) } returns false
            every { dispatcher.dispatch(any(), any(), any(), any(), any()) } answers {
                capturedDeliveryIds.add(invocation.args[3] as String)
                WebhookDispatchResult.Sent(200)
            }
            every { webhookDeliveryRepository.record(any(), any(), any(), any(), any(), any()) } returns dummyDelivery()
            every { circuitBreaker.recordSuccess(webhookId) } returns Unit
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("같은 msgId로 두 번 폴링(재전달 상황을 흉내)해도 동일한 deliveryId를 사용한다") {
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON)
            worker.pollAndProcess()
            stubMessage(dsl, msgId, ISSUE_CREATED_JSON)
            worker.pollAndProcess()

            capturedDeliveryIds.size shouldBe 2
            capturedDeliveryIds[0] shouldBe capturedDeliveryIds[1]
        }
    }
}) {
    companion object {
        private const val ISSUE_CREATED_JSON =
            """
            {
              "type": "issue.created",
              "issueKey": "ATLAS-1",
              "projectKey": "ATLAS",
              "summary": "테스트 이슈",
              "reporterId": {"value": "11111111-1111-1111-1111-111111111111"},
              "actorId": {"value": "11111111-1111-1111-1111-111111111111"},
              "occurredAt": "2026-07-01T00:00:00Z"
            }
            """

        private const val ISSUE_TRANSITIONED_JSON =
            """
            {
              "type": "issue.transitioned",
              "issueKey": "ATLAS-42",
              "fromState": "open",
              "toState": "in_progress",
              "actorId": {"value": "22222222-2222-2222-2222-222222222222"},
              "occurredAt": "2026-07-01T01:00:00Z"
            }
            """
    }
}

// ── test helpers ─────────────────────────────────────────────────────────────

private fun buildWebhook(
    id: UUID,
    url: String = "https://example.com/hook",
    eventFilter: List<String> = listOf(WebhookEventCatalog.ISSUE_CREATED),
    secretEncrypted: String? = null,
    projectKey: String? = null,
): OutboundWebhook =
    OutboundWebhook.create(
        createdBy = UUID.randomUUID(),
        name = "테스트 웹훅",
        url = url,
        eventFilter = eventFilter,
        secretEncrypted = secretEncrypted,
        projectKey = projectKey,
    ).copy(id = id)

private fun dummyDelivery(): WebhookDelivery =
    WebhookDelivery(
        id = UUID.randomUUID(),
        webhookId = UUID.randomUUID(),
        eventType = WebhookEventCatalog.ISSUE_CREATED,
        status = DeliveryStatus.SUCCEEDED,
        responseCode = 200,
        attemptCount = 1,
        errorDetail = null,
        createdAt = Instant.now(),
        deliveredAt = Instant.now(),
    )

/** 빈 큐를 반환하도록 dsl.fetch를 stub한다. */
private fun stubEmptyQueue(dsl: DSLContext) {
    val result =
        mockk<org.jooq.Result<org.jooq.Record>>(relaxed = true) {
            every { isEmpty() } returns true
        }
    every {
        dsl.fetch(
            any<String>(),
            WebhookDispatchWorker.QUEUE_NAME,
            WebhookDispatchWorker.VT_SECONDS,
            WebhookDispatchWorker.BATCH_SIZE,
        )
    } returns result
}

/** pgmq.read 결과를 단건 메시지로 stub하는 공통 헬퍼. */
private fun stubMessage(
    dsl: DSLContext,
    msgId: Long,
    messageJson: String,
    readCt: Int = 1,
) {
    val row =
        mockk<org.jooq.Record>(relaxed = true) {
            every { get("msg_id", Long::class.java) } returns msgId
            every { get("message", String::class.java) } returns messageJson
            every { get("read_ct", Int::class.java) } returns readCt
        }
    val result =
        mockk<org.jooq.Result<org.jooq.Record>>(relaxed = true) {
            every { isEmpty() } returns false
            every { iterator() } answers { mutableListOf(row).iterator() }
        }
    every {
        dsl.fetch(
            any<String>(),
            WebhookDispatchWorker.QUEUE_NAME,
            WebhookDispatchWorker.VT_SECONDS,
            WebhookDispatchWorker.BATCH_SIZE,
        )
    } returns result
}
