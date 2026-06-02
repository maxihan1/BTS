// BulkOperationCleanupWorker 단위 테스트 — 30일 TTL 경과 작업+항목 삭제, 미경과 보존, 경계(정확히 30일) 검증

package com.bts.issue.bulk.worker

import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.BulkOperationStatus
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.repository.BulkOperationRepository
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.jooq.DSLContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * FR-IS-05 Task 9 — BulkOperationCleanupWorker 단위 테스트.
 *
 * 검증 범위.
 * - 30일 경과 작업: findCompletedBefore 기준 시각 검증, items DELETE 후 operations DELETE 순서 보장
 * - 미경과(30일 미만) 보존: findCompletedBefore 가 빈 목록 반환 시 DELETE 호출 없음
 * - 경계 — 정확히 30일(CLEANUP_RETENTION_DAYS) 경과: threshold 계산 검증 (Clock.fixed 핀)
 * - 삭제 대상 없음(빈 목록): DELETE 쿼리 실행 안 함
 *
 * 단위 테스트 — BulkOperationRepository / DSLContext 는 MockK 모의 객체.
 * Clock.fixed 로 기준 시각을 고정하여 time-bomb 방지 (learnings: authcontroller-revokesession-timebomb).
 */
class BulkOperationCleanupWorkerTest : DescribeSpec({

    val bulkRepo = mockk<BulkOperationRepository>()
    val dsl = mockk<DSLContext>()

    /** 고정 현재 시각: 2026-06-02T12:00:00Z */
    val fixedNow: Instant = Instant.parse("2026-06-02T12:00:00Z")
    val fixedClock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    val worker = BulkOperationCleanupWorker(bulkRepo, dsl, fixedClock)

    afterEach { clearMocks(bulkRepo, dsl) }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    fun makeOperation(id: UUID = UUID.randomUUID()): BulkOperation =
        BulkOperation(
            id = BulkOperationId(id),
            actorId = UUID.randomUUID(),
            type = BulkOperationType.BULK_EDIT,
            status = BulkOperationStatus.COMPLETED,
            payload = BulkOperationPayload.Edit(priority = null, impact = null),
            items = emptyList(),
            totalCount = 1,
            processedCount = 1,
            succeededCount = 1,
            failedCount = 0,
            // 40일 전 생성
            createdAt = fixedNow.minusSeconds(60 * 60 * 24 * 40L),
            // 31일 전 완료
            updatedAt = fixedNow.minusSeconds(60 * 60 * 24 * 31L),
        )

    // ── 테스트 ─────────────────────────────────────────────────────────────────

    describe("cleanupExpired") {

        context("30일 경과 완료 작업이 있을 때") {
            it("findCompletedBefore 를 threshold(현재 - 30일)로 호출한다") {
                val expectedThreshold =
                    fixedNow.minusSeconds(
                        BulkOperationCleanupWorker.CLEANUP_RETENTION_SECONDS,
                    )
                val capturedInstant = slot<Instant>()
                every { bulkRepo.findCompletedBefore(capture(capturedInstant)) } returns emptyList()

                worker.cleanupExpired()

                assert(capturedInstant.captured == expectedThreshold) {
                    "threshold mismatch: expected=$expectedThreshold actual=${capturedInstant.captured}"
                }
            }

            it("대상 작업의 items 를 삭제한 뒤 operations 를 삭제한다 (FK 순서)") {
                val op1 = makeOperation()
                val op2 = makeOperation()
                every { bulkRepo.findCompletedBefore(any()) } returns listOf(op1, op2)
                every { dsl.execute(any<String>(), any<UUID>()) } returns 0

                worker.cleanupExpired()

                // items 먼저(FK 제약) — op1 items, op2 items, op1 ops, op2 ops 순
                verifyOrder {
                    dsl.execute(match { it.contains("bulk_operation_items") }, op1.id.value)
                    dsl.execute(match { it.contains("bulk_operation_items") }, op2.id.value)
                    dsl.execute(match { it.contains("bulk_operations") && !it.contains("items") }, op1.id.value)
                    dsl.execute(match { it.contains("bulk_operations") && !it.contains("items") }, op2.id.value)
                }
            }
        }

        context("삭제 대상이 없을 때 (빈 목록)") {
            it("DELETE 쿼리를 실행하지 않는다") {
                every { bulkRepo.findCompletedBefore(any()) } returns emptyList()

                worker.cleanupExpired()

                verify(exactly = 0) { dsl.execute(any<String>(), *anyVararg()) }
            }
        }

        context("정확히 30일 경과 경계 검증") {
            it("threshold 는 현재 시각에서 정확히 CLEANUP_RETENTION_DAYS 일을 뺀 값이다") {
                val capturedInstant = slot<Instant>()
                every { bulkRepo.findCompletedBefore(capture(capturedInstant)) } returns emptyList()

                worker.cleanupExpired()

                val expectedThreshold = fixedNow.minusSeconds(BulkOperationCleanupWorker.CLEANUP_RETENTION_SECONDS)
                assert(capturedInstant.captured == expectedThreshold) {
                    "경계 threshold 불일치: expected=$expectedThreshold actual=${capturedInstant.captured}"
                }
            }
        }

        context("FAILED 상태 종료 작업도 삭제 대상이 될 수 있다") {
            it("findCompletedBefore 가 반환한 목록 전체를 삭제한다 (상태 무관)") {
                val failedOp = makeOperation().copy(status = BulkOperationStatus.FAILED)
                every { bulkRepo.findCompletedBefore(any()) } returns listOf(failedOp)
                every { dsl.execute(any<String>(), any<UUID>()) } returns 0

                worker.cleanupExpired()

                // items 1회 + operations 1회 = 2회
                verify(exactly = 2) { dsl.execute(any<String>(), any<UUID>()) }
            }
        }
    }
})
