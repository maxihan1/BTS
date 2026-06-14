// WebhookDispatchWorker 단위 테스트 — pgmq 폴링 생명주기, Webhook 디스패치, delete/archive 검증

package com.bts.notification.worker

import com.bts.notification.webhook.WebhookDispatchResult
import com.bts.notification.webhook.WebhookDispatcher
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jooq.DSLContext

/**
 * [WebhookDispatchWorker] 단위 테스트.
 *
 * MockK로 [DSLContext]와 [WebhookDispatcher]를 mock — Testcontainers 불요.
 * [NotificationWorkerTest] 패턴 미러.
 *
 * ### 검증 항목
 * - W-1. 빈 큐 → 아무 처리 없음
 * - W-2. WebhookRequested + dispatcher=Sent → pgmq.delete 호출
 * - W-3. dispatcher=Rejected(SSRF) → delete 호출(영구), dispatcher 1회만
 * - W-4. dispatcher=Failed → delete 미호출(재전달), read_ct≤MAX이면 archive 미호출
 * - W-5. read_ct>MAX_RECEIVE_COUNT + Failed → pgmq.archive
 * - W-6. 범위 외 type(WatcherAdded) → dispatch 미호출 + delete(ack)
 * - W-7. JSON 파싱 실패(poison) → read_ct>MAX 시 archive, 미만 시 재전달 대기
 */
class WebhookDispatchWorkerTest : DescribeSpec({

    val dsl = mockk<DSLContext>()
    val dispatcher = mockk<WebhookDispatcher>()
    val objectMapper = ObjectMapper()

    val worker = WebhookDispatchWorker(dsl = dsl, dispatcher = dispatcher, objectMapper = objectMapper)

    afterEach { clearMocks(dsl, dispatcher) }

    // ── W-1: 빈 큐 ────────────────────────────────────────────────────────────

    describe("W-1 빈 큐") {
        it("메시지가 없으면 dispatcher를 호출하지 않는다") {
            stubEmptyQueue(dsl)

            worker.pollAndProcess()

            verify(exactly = 0) { dispatcher.dispatch(any(), any(), any()) }
        }
    }

    // ── W-2: WebhookRequested + Sent → delete ────────────────────────────────

    describe("W-2 WebhookRequested + Sent → delete") {
        val msgId = 2L

        beforeEach {
            stubWebhookMessage(dsl, msgId, "https://example.com/hook", "POST", "PROJ-1")
            every {
                dispatcher.dispatch("https://example.com/hook", "POST", "PROJ-1")
            } returns WebhookDispatchResult.Sent
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("dispatcher.dispatch를 1회 호출한다") {
            worker.pollAndProcess()
            verify(exactly = 1) { dispatcher.dispatch("https://example.com/hook", "POST", "PROJ-1") }
        }

        it("전송 성공 후 pgmq.delete를 호출한다") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(
                    match<String> { it.contains("pgmq.delete") },
                    WebhookDispatchWorker.QUEUE_NAME,
                    msgId,
                )
            }
        }
    }

    // ── W-3: Rejected(SSRF) → delete (영구 거부) ─────────────────────────────

    describe("W-3 Rejected(SSRF) → delete") {
        val msgId = 3L

        beforeEach {
            stubWebhookMessage(dsl, msgId, "http://10.0.0.5/hook", "POST", "PROJ-1")
            every {
                dispatcher.dispatch("http://10.0.0.5/hook", "POST", "PROJ-1")
            } returns WebhookDispatchResult.Rejected("내부망 차단")
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("dispatcher를 1회만 호출한다") {
            worker.pollAndProcess()
            verify(exactly = 1) { dispatcher.dispatch(any(), any(), any()) }
        }

        it("Rejected 이면 pgmq.delete를 호출한다 (영구 거부, 재전달 불필요)") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(
                    match<String> { it.contains("pgmq.delete") },
                    WebhookDispatchWorker.QUEUE_NAME,
                    msgId,
                )
            }
        }
    }

    // ── W-4: Failed + read_ct≤MAX → delete 미호출, archive 미호출 ────────────

    describe("W-4 Failed + read_ct≤MAX → 재전달") {
        val msgId = 4L

        beforeEach {
            stubWebhookMessage(dsl, msgId, "https://example.com/hook", "POST", "PROJ-1", readCt = 1)
            every {
                dispatcher.dispatch("https://example.com/hook", "POST", "PROJ-1")
            } returns WebhookDispatchResult.Failed("타임아웃")
        }

        it("pgmq.delete를 호출하지 않는다 (vt 만료 재전달)") {
            worker.pollAndProcess()
            verify(exactly = 0) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, any(), any<Long>())
            }
        }

        it("pgmq.archive를 호출하지 않는다 (read_ct≤MAX)") {
            worker.pollAndProcess()
            verify(exactly = 0) {
                dsl.execute(match<String> { it.contains("pgmq.archive") }, any(), any<Long>())
            }
        }
    }

    // ── W-5: Failed + read_ct>MAX → archive ──────────────────────────────────

    describe("W-5 Failed + read_ct>MAX → archive") {
        val msgId = 5L
        val poisonReadCt = WebhookDispatchWorker.MAX_RECEIVE_COUNT + 1

        beforeEach {
            stubWebhookMessage(dsl, msgId, "https://example.com/hook", "POST", "PROJ-1", readCt = poisonReadCt)
            every {
                dispatcher.dispatch("https://example.com/hook", "POST", "PROJ-1")
            } returns WebhookDispatchResult.Failed("5xx 서버 오류")
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("pgmq.archive를 호출한다 (dead-letter)") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(
                    match<String> { it.contains("pgmq.archive") },
                    WebhookDispatchWorker.QUEUE_NAME,
                    msgId,
                )
            }
        }

        it("pgmq.delete는 호출하지 않는다") {
            worker.pollAndProcess()
            verify(exactly = 0) {
                dsl.execute(match<String> { it.contains("pgmq.delete") }, any(), any<Long>())
            }
        }
    }

    // ── W-6: 범위 외 type → dispatch 미호출 + delete ─────────────────────────

    describe("W-6 범위 외 type(WatcherAdded) → delete(ack)") {
        val msgId = 6L

        beforeEach {
            stubUnknownTypeMessage(dsl, msgId)
            every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
        }

        it("dispatcher를 호출하지 않는다") {
            worker.pollAndProcess()
            verify(exactly = 0) { dispatcher.dispatch(any(), any(), any()) }
        }

        it("pgmq.delete를 호출한다 (소임 없음, 무한 재전달 차단)") {
            worker.pollAndProcess()
            verify(exactly = 1) {
                dsl.execute(
                    match<String> { it.contains("pgmq.delete") },
                    WebhookDispatchWorker.QUEUE_NAME,
                    msgId,
                )
            }
        }
    }

    // ── W-7: JSON 파싱 실패(poison) ───────────────────────────────────────────

    describe("W-7 JSON 파싱 실패 (poison 메시지)") {
        val msgId = 7L

        describe("read_ct≤MAX이면 재전달 대기") {
            beforeEach {
                stubPoisonMessage(dsl, msgId, readCt = 1)
            }

            it("dispatcher를 호출하지 않는다") {
                worker.pollAndProcess()
                verify(exactly = 0) { dispatcher.dispatch(any(), any(), any()) }
            }

            it("pgmq.delete를 호출하지 않는다") {
                worker.pollAndProcess()
                verify(exactly = 0) {
                    dsl.execute(match<String> { it.contains("pgmq.delete") }, any(), any<Long>())
                }
            }

            it("pgmq.archive를 호출하지 않는다") {
                worker.pollAndProcess()
                verify(exactly = 0) {
                    dsl.execute(match<String> { it.contains("pgmq.archive") }, any(), any<Long>())
                }
            }
        }

        describe("read_ct>MAX이면 archive") {
            beforeEach {
                val poisonReadCt = WebhookDispatchWorker.MAX_RECEIVE_COUNT + 1
                stubPoisonMessage(dsl, msgId, readCt = poisonReadCt)
                every { dsl.execute(any<String>(), WebhookDispatchWorker.QUEUE_NAME, msgId) } returns 1
            }

            it("pgmq.archive를 호출한다") {
                worker.pollAndProcess()
                verify(exactly = 1) {
                    dsl.execute(
                        match<String> { it.contains("pgmq.archive") },
                        WebhookDispatchWorker.QUEUE_NAME,
                        msgId,
                    )
                }
            }
        }
    }
})

// ── test helpers ─────────────────────────────────────────────────────────────

/**
 * WebhookRequested 메시지를 반환하도록 dsl.fetch를 stub한다.
 *
 * 메시지 형식: `{"type":"WebhookRequested","payload":{"issueKey":"...","url":"...","method":"..."}}`
 */
@Suppress("LongParameterList") // 테스트 stub 헬퍼 — 모든 파라미터가 필수 검증 요소
private fun stubWebhookMessage(
    dsl: DSLContext,
    msgId: Long,
    url: String,
    method: String,
    issueKey: String,
    readCt: Int = 1,
) {
    val json =
        """
        {
          "type": "WebhookRequested",
          "payload": {
            "issueKey": "$issueKey",
            "url": "$url",
            "method": "$method"
          }
        }
        """.trimIndent()

    stubReadResult(dsl, msgId, json, readCt)
}

/** 미지원 type 메시지를 반환하도록 dsl.fetch를 stub한다. */
private fun stubUnknownTypeMessage(
    dsl: DSLContext,
    msgId: Long,
    readCt: Int = 1,
) {
    val json =
        """
        {
          "type": "WatcherAdded",
          "payload": {}
        }
        """.trimIndent()

    stubReadResult(dsl, msgId, json, readCt)
}

/** JSON이 아닌 poison 메시지를 반환하도록 dsl.fetch를 stub한다. */
private fun stubPoisonMessage(
    dsl: DSLContext,
    msgId: Long,
    readCt: Int = 1,
) {
    stubReadResult(dsl, msgId, "NOT_VALID_JSON{{{", readCt)
}

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
private fun stubReadResult(
    dsl: DSLContext,
    msgId: Long,
    messageJson: String,
    readCt: Int,
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
