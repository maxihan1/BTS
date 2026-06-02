// BulkOperationProcessor 단위 테스트 — actor 복원, 청크 처리, 멱등 스킵 (Task 6 TDD)
// processItem 로직은 BulkItemExecutor 로 분리되어 BulkItemExecutorTest 에서 검증됨

package com.bts.issue.bulk.application

import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.BulkOperationStatus
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.domain.FailureReasonCode
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.IssueKey
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

/**
 * FR-IS-05 Task 6 — BulkOperationProcessor.process 단위 테스트.
 *
 * 검증 범위.
 * - actor 복원: bulk_operations.actorId → ActorId (HTTP SecurityContext 없이 DB에서 복원)
 * - 청크 처리: BULK_OPERATION_CHUNK_SIZE 단위 처리, 전체 항목 커버
 * - 멱등 스킵: 이미 SUCCEEDED/FAILED 항목은 itemExecutor.executeItem 호출 안 함
 * - 카운트 재집계: 모든 항목 처리 후 recomputeAndPersistCounts 1회 호출
 *
 * **설계 변경(BulkItemExecutor 도입)** — best-effort 트랜잭션 격리를 위해 항목별 처리 로직이
 * [BulkItemExecutor] 로 분리됐다. BulkOperationProcessor 단위 테스트는 itemExecutor mock 으로
 * 조율 흐름만 검증한다. 예외 매핑·SUCCEEDED/FAILED 기록은 BulkItemExecutorTest 에서 검증한다.
 *
 * 단위 테스트 — IssueApplicationService / BulkOperationRepository / BulkItemExecutor 는 MockK 모의 객체.
 */
class BulkOperationProcessorTest : DescribeSpec({

    val bulkRepo = mockk<BulkOperationRepository>(relaxed = true)
    val itemExecutor = mockk<BulkItemExecutor>(relaxed = true)
    val sut = BulkOperationProcessor(bulkRepo, itemExecutor)

    val actorUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val operationId = BulkOperationId(UUID.randomUUID())

    fun issueKey(n: Int) = IssueKey("ATLAS-$n")

    fun makeOperation(
        items: List<BulkOperationItem>,
        payload: BulkOperationPayload = BulkOperationPayload.Edit(priority = 2, impact = null),
        status: BulkOperationStatus = BulkOperationStatus.RUNNING,
    ): BulkOperation =
        BulkOperation(
            id = operationId,
            actorId = actorUuid,
            type = BulkOperationType.BULK_EDIT,
            status = status,
            payload = payload,
            items = items,
            totalCount = items.size,
            processedCount = 0,
            succeededCount = 0,
            failedCount = 0,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    beforeEach {
        clearMocks(bulkRepo, itemExecutor)
        every { bulkRepo.findById(any()) } returns null
        every { bulkRepo.findItemsByOperationId(any()) } returns emptyList()
        justRun { bulkRepo.recomputeAndPersistCounts(any()) }
    }

    describe("process") {

        describe("actor 복원") {
            it("bulk_operations.actorId 로 ActorId 를 복원해 itemExecutor.executeItem 에 전달한다") {
                val item = BulkOperationItem(issueKey = issueKey(1), status = ItemStatus.PENDING)
                val operation = makeOperation(items = listOf(item))

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(item)
                justRun { itemExecutor.executeItem(any(), any(), any()) }

                sut.process(operationId)

                // actorId 가 올바르게 복원되어 executeItem 에 전달됐는지 verify
                verify {
                    itemExecutor.executeItem(
                        match { it.value == actorUuid },
                        operation,
                        item,
                    )
                }
            }
        }

        describe("멱등 스킵") {
            it("이미 SUCCEEDED 인 항목은 itemExecutor.executeItem 을 호출하지 않는다") {
                val alreadySucceeded = BulkOperationItem(
                    issueKey = issueKey(1),
                    status = ItemStatus.SUCCEEDED,
                )
                val operation = makeOperation(items = listOf(alreadySucceeded))

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(alreadySucceeded)

                sut.process(operationId)

                verify(exactly = 0) { itemExecutor.executeItem(any(), any(), any()) }
            }

            it("이미 FAILED 인 항목도 itemExecutor.executeItem 을 호출하지 않는다") {
                val alreadyFailed = BulkOperationItem(
                    issueKey = issueKey(1),
                    status = ItemStatus.FAILED,
                    failureReasonCode = FailureReasonCode.NOT_FOUND,
                )
                val operation = makeOperation(items = listOf(alreadyFailed))

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(alreadyFailed)

                sut.process(operationId)

                verify(exactly = 0) { itemExecutor.executeItem(any(), any(), any()) }
            }
        }

        describe("청크 처리") {
            it("항목이 BULK_OPERATION_CHUNK_SIZE 를 초과해도 모든 항목의 executeItem 이 호출된다") {
                val chunkSize = 50
                val totalItems = chunkSize + 1 // 51개: 청크 경계를 넘어야 함
                val items =
                    (1..totalItems).map { n ->
                        BulkOperationItem(issueKey = issueKey(n), status = ItemStatus.PENDING)
                    }
                val operation = makeOperation(items = items)

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns items
                justRun { itemExecutor.executeItem(any(), any(), any()) }

                sut.process(operationId)

                verify(exactly = totalItems) { itemExecutor.executeItem(any(), any(), any()) }
                verify { bulkRepo.recomputeAndPersistCounts(operationId) }
            }
        }

        describe("카운트 재집계") {
            it("모든 항목 처리 후 recomputeAndPersistCounts 가 1회 호출된다") {
                val items = (1..3).map { n ->
                    BulkOperationItem(issueKey = issueKey(n), status = ItemStatus.PENDING)
                }
                val operation = makeOperation(items = items)

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns items
                justRun { itemExecutor.executeItem(any(), any(), any()) }

                sut.process(operationId)

                verify(exactly = 1) { bulkRepo.recomputeAndPersistCounts(operationId) }
            }
        }
    }
})
