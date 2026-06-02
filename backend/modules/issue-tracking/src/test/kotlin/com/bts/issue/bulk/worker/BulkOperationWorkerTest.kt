// BulkOperationWorker 단위 테스트 — pgmq 폴링, CAS 단일 진입, processor 호출, 완료 이벤트 1회 발행, delete (Task 7 TDD)

package com.bts.issue.bulk.worker

import com.bts.issue.bulk.application.BulkOperationProcessor
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.repository.BulkOperationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.jooq.DSLContext
import java.util.UUID

/**
 * FR-IS-05 Task 7 — BulkOperationWorker 단위 테스트.
 *
 * 검증 범위.
 * - pgmq read: DSLContext.fetch("SELECT * FROM pgmq.read(…)") 호출 확인
 * - claimForRun CAS: claim 성공(true) → processor 호출, claim 실패(false) → skip
 * - 동시 2워커 단일 진입: 두 번째 claim 이 false 를 반환하면 processor 호출 안 함
 * - processor.process() 호출 확인
 * - completer.completeAndPublish(): 완료 처리 + 이벤트 발행 위임 확인
 * - completeAndPublish true(완료) → delete 호출
 * - pgmq.delete: 성공 처리 후 메시지 삭제 호출 확인
 * - 예외 발생 시: delete 호출 안 함 (at-least-once, vt 만료 후 재전달)
 *
 * 단위 테스트 — DSLContext / BulkOperationRepository / BulkOperationProcessor /
 * BulkOperationCompleter 는 MockK 모의 객체.
 *
 * markCompleted + publishCompleted 는 BulkOperationCompleter 로 위임됐으므로
 * 워커 단위 테스트에서는 completer.completeAndPublish 호출만 검증한다.
 */
class BulkOperationWorkerTest : DescribeSpec({

    val dsl = mockk<DSLContext>()
    val bulkRepo = mockk<BulkOperationRepository>()
    val processor = mockk<BulkOperationProcessor>()
    val completer = mockk<BulkOperationCompleter>()

    val worker = BulkOperationWorker(dsl, bulkRepo, processor, completer)

    afterEach { clearMocks(dsl, bulkRepo, processor, completer) }

    describe("pollAndProcess") {

        context("큐에 메시지가 없을 때") {
            it("아무 처리도 하지 않는다") {
                every {
                    dsl.fetch(
                        any<String>(),
                        BulkOperationWorker.QUEUE_NAME,
                        BulkOperationWorker.VISIBILITY_TIMEOUT_SECONDS,
                        BulkOperationWorker.POLL_BATCH_SIZE,
                    )
                } returns mockk(relaxed = true) { every { isEmpty() } returns true }

                worker.pollAndProcess()

                verify(exactly = 0) { bulkRepo.claimForRun(any()) }
                verify(exactly = 0) { processor.process(any()) }
            }
        }

        context("큐에 메시지 1건, claimForRun 성공, completeAndPublish 성공") {
            val operationId = BulkOperationId(UUID.randomUUID())
            val msgId = 42L

            beforeEach {
                stubReadOneMessage(dsl, operationId, msgId)
                every { bulkRepo.claimForRun(operationId) } returns true
                justRun { processor.process(operationId) }
                every { completer.completeAndPublish(operationId, msgId) } returns true
                every {
                    dsl.execute(any<String>(), BulkOperationWorker.QUEUE_NAME, msgId)
                } returns 1
            }

            it("claimForRun → processor.process → completeAndPublish → delete 순으로 실행한다") {
                worker.pollAndProcess()

                verifyOrder {
                    bulkRepo.claimForRun(operationId)
                    processor.process(operationId)
                    completer.completeAndPublish(operationId, msgId)
                    dsl.execute(any<String>(), BulkOperationWorker.QUEUE_NAME, msgId)
                }
            }

            it("completeAndPublish 는 정확히 1회 호출된다 (C4 중복 발행 금지)") {
                worker.pollAndProcess()

                verify(exactly = 1) { completer.completeAndPublish(operationId, msgId) }
            }
        }

        context("claimForRun 이 false 를 반환할 때 (동시 2워커 — 타 워커가 이미 선점)") {
            val operationId = BulkOperationId(UUID.randomUUID())
            val msgId = 99L

            beforeEach {
                stubReadOneMessage(dsl, operationId, msgId)
                every { bulkRepo.claimForRun(operationId) } returns false
            }

            it("processor, completeAndPublish, delete 를 호출하지 않는다") {
                worker.pollAndProcess()

                verify(exactly = 0) { processor.process(any()) }
                verify(exactly = 0) { completer.completeAndPublish(any(), any()) }
                verify(exactly = 0) { dsl.execute(any<String>(), any(), any<Long>()) }
            }
        }

        context("processor.process 가 예외를 던질 때") {
            val operationId = BulkOperationId(UUID.randomUUID())
            val msgId = 55L

            beforeEach {
                stubReadOneMessage(dsl, operationId, msgId)
                every { bulkRepo.claimForRun(operationId) } returns true
                every { processor.process(operationId) } throws RuntimeException("DB 장애")
            }

            it("delete 를 호출하지 않는다 (vt 만료 후 재전달 허용 — at-least-once)") {
                // 예외가 외부로 전파되지 않아야 한다 (워커 루프 보호)
                worker.pollAndProcess()

                verify(exactly = 0) { dsl.execute(any<String>(), BulkOperationWorker.QUEUE_NAME, msgId) }
                verify(exactly = 0) { completer.completeAndPublish(any(), any()) }
            }
        }
    }
})

// ── test helpers ───────────────────────────────────────────────────────────────

/**
 * dsl.fetch("SELECT * FROM pgmq.read(…)") 가 단일 메시지 1건을 반환하도록 스텁한다.
 *
 * pgmq.read 결과는 msg_id(bigint), message(jsonb) 컬럼을 가진다.
 * MockK Result 모의 객체로 단순화한다.
 */
private fun stubReadOneMessage(
    dsl: DSLContext,
    operationId: BulkOperationId,
    msgId: Long,
) {
    val row =
        mockk<org.jooq.Record>(relaxed = true) {
            every { get("msg_id", Long::class.java) } returns msgId
            every { get("message", String::class.java) } returns """{"bulkOperationId":"${operationId.value}"}"""
        }
    val result =
        mockk<org.jooq.Result<org.jooq.Record>>(relaxed = true) {
            every { isEmpty() } returns false
            every { iterator() } answers { mutableListOf(row).iterator() }
        }
    every {
        dsl.fetch(
            any<String>(),
            BulkOperationWorker.QUEUE_NAME,
            BulkOperationWorker.VISIBILITY_TIMEOUT_SECONDS,
            BulkOperationWorker.POLL_BATCH_SIZE,
        )
    } returns result
}
