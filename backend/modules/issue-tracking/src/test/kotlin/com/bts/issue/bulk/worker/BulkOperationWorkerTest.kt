// BulkOperationWorker 단위 테스트 — pgmq 폴링, CAS 단일 진입, processor 호출, 완료 이벤트 1회 발행, delete (Task 7 TDD RED)

package com.bts.issue.bulk.worker

import com.bts.issue.bulk.application.BulkOperationProcessor
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.event.BulkOperationEventPublisher
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
 * - markCompleted CAS: 완료 처리
 * - BulkOperationCompleted 이벤트 1회 발행 (C4 중복 발행 금지)
 * - markCompleted false(이미 완료) → 이벤트 발행 안 함
 * - pgmq.delete: 성공 처리 후 메시지 삭제 호출 확인
 * - 예외 발생 시: delete 호출 안 함 (at-least-once, vt 만료 후 재전달)
 *
 * 단위 테스트 — DSLContext / BulkOperationRepository / BulkOperationProcessor /
 * BulkOperationEventPublisher 는 MockK 모의 객체.
 */
class BulkOperationWorkerTest : DescribeSpec({

    val dsl = mockk<DSLContext>()
    val bulkRepo = mockk<BulkOperationRepository>()
    val processor = mockk<BulkOperationProcessor>()
    val eventPublisher = mockk<BulkOperationEventPublisher>()

    val worker = BulkOperationWorker(dsl, bulkRepo, processor, eventPublisher)

    afterEach { clearMocks(dsl, bulkRepo, processor, eventPublisher) }

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

        context("큐에 메시지 1건, claimForRun 성공, markCompleted 성공") {
            val operationId = BulkOperationId(UUID.randomUUID())
            val msgId = 42L

            beforeEach {
                stubReadOneMessage(dsl, operationId, msgId)
                every { bulkRepo.claimForRun(operationId) } returns true
                justRun { processor.process(operationId) }
                every { bulkRepo.markCompleted(operationId) } returns true
                justRun { eventPublisher.publishCompleted(operationId) }
                every {
                    dsl.execute(any<String>(), BulkOperationWorker.QUEUE_NAME, msgId)
                } returns 1
            }

            it("claimForRun → processor.process → markCompleted → 이벤트 발행 → delete 순으로 실행한다") {
                worker.pollAndProcess()

                verifyOrder {
                    bulkRepo.claimForRun(operationId)
                    processor.process(operationId)
                    bulkRepo.markCompleted(operationId)
                    eventPublisher.publishCompleted(operationId)
                    dsl.execute(any<String>(), BulkOperationWorker.QUEUE_NAME, msgId)
                }
            }

            it("이벤트는 정확히 1회 발행된다 (C4 중복 발행 금지)") {
                worker.pollAndProcess()

                verify(exactly = 1) { eventPublisher.publishCompleted(operationId) }
            }
        }

        context("claimForRun 이 false 를 반환할 때 (동시 2워커 — 타 워커가 이미 선점)") {
            val operationId = BulkOperationId(UUID.randomUUID())
            val msgId = 99L

            beforeEach {
                stubReadOneMessage(dsl, operationId, msgId)
                every { bulkRepo.claimForRun(operationId) } returns false
            }

            it("processor, markCompleted, 이벤트 발행, delete 를 호출하지 않는다") {
                worker.pollAndProcess()

                verify(exactly = 0) { processor.process(any()) }
                verify(exactly = 0) { bulkRepo.markCompleted(any()) }
                verify(exactly = 0) { eventPublisher.publishCompleted(any()) }
                verify(exactly = 0) { dsl.execute(any<String>(), any(), any<Long>()) }
            }
        }

        context("markCompleted 가 false 를 반환할 때 (이미 다른 경로로 완료됨)") {
            val operationId = BulkOperationId(UUID.randomUUID())
            val msgId = 77L

            beforeEach {
                stubReadOneMessage(dsl, operationId, msgId)
                every { bulkRepo.claimForRun(operationId) } returns true
                justRun { processor.process(operationId) }
                every { bulkRepo.markCompleted(operationId) } returns false
                // delete 는 멱등하게 호출 — 중복 처리 방지를 위해 메시지는 제거
                every {
                    dsl.execute(any<String>(), BulkOperationWorker.QUEUE_NAME, msgId)
                } returns 1
            }

            it("이벤트를 발행하지 않는다 (중복 완료 이벤트 금지)") {
                worker.pollAndProcess()

                verify(exactly = 0) { eventPublisher.publishCompleted(any()) }
            }

            it("메시지는 삭제한다 (재전달 차단 — 이미 처리됐으므로)") {
                worker.pollAndProcess()

                verify(exactly = 1) { dsl.execute(any<String>(), BulkOperationWorker.QUEUE_NAME, msgId) }
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
                verify(exactly = 0) { eventPublisher.publishCompleted(any()) }
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
