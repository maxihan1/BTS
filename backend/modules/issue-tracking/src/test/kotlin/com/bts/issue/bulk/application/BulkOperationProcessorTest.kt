// BulkOperationProcessor 단위 테스트 — actor 복원, 청크 처리, best-effort, deny-stub, 멱등, 동일 트랜잭션 (Task 6 TDD RED)

package com.bts.issue.bulk.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.TransitionIssueRequest
import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.BulkOperationStatus
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.domain.FailureReasonCode
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.UUID

/**
 * FR-IS-05 Task 6 — BulkOperationProcessor.process 단위 테스트.
 *
 * 검증 범위.
 * - actor 복원: bulk_operations.actorId → ActorId (HTTP SecurityContext 없이 DB에서 복원)
 * - 청크 처리: 상한 BULK_OPERATION_MAX_SIZE / 청크 BULK_OPERATION_CHUNK_SIZE 준수
 * - best-effort 부분 성공: 권한·전이 실패 → 해당 항목 FAILED+reasonCode, 나머지 SUCCEEDED
 * - deny stub 주입: 권한 없는 이슈 → FAILED(FORBIDDEN) (B1 가짜그린 회피)
 * - 멱등 스킵: 이미 SUCCEEDED/FAILED 항목은 재처리 안 함
 * - 동일 트랜잭션: 이슈 변경 + 항목 상태 기록이 같은 트랜잭션에서 수행 (C1 부분실패 창 제거)
 *
 * 단위 테스트 — IssueApplicationService / BulkOperationRepository 는 MockK 모의 객체.
 */
class BulkOperationProcessorTest : DescribeSpec({

    val issueService = mockk<IssueApplicationService>()
    val bulkRepo = mockk<BulkOperationRepository>()
    val sut = BulkOperationProcessor(issueService, bulkRepo)

    val actorUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val operationId = BulkOperationId(UUID.randomUUID())

    fun issueKey(n: Int) = IssueKey("ATLAS-$n")

    fun makeIssueResponse(key: String, version: Long = 1L): IssueResponse =
        IssueResponse(
            key = key,
            id = UUID.randomUUID(),
            projectKey = "ATLAS",
            summary = "Summary $key",
            currentStateKey = "open",
            reporterId = actorUuid,
            version = version,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            typeId = 1L,
            typeKey = "task",
            typeName = "Task",
        )

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
        clearMocks(issueService, bulkRepo)
    }

    describe("process") {

        describe("actor 복원") {
            it("bulk_operations.actorId 를 ActorId 로 복원하여 IssueApplicationService 에 전달한다") {
                val item = BulkOperationItem(issueKey = issueKey(1), status = ItemStatus.PENDING)
                val operation = makeOperation(items = listOf(item))

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(item)
                every { issueService.findByKey(any(), issueKey(1)) } returns makeIssueResponse("ATLAS-1")
                every { issueService.updateIssue(any(), issueKey(1), any()) } returns makeIssueResponse("ATLAS-1")
                justRun { bulkRepo.updateItemResult(any(), any(), any(), any()) }
                justRun { bulkRepo.recomputeAndPersistCounts(any()) }

                sut.process(operationId)

                val actorSlot = slot<ActorId>()
                verify { issueService.findByKey(capture(actorSlot), issueKey(1)) }
                actorSlot.captured.value shouldBe actorUuid
            }
        }

        describe("best-effort 부분 성공") {
            it("성공한 항목은 SUCCEEDED, 실패한 항목은 FAILED+reasonCode 로 기록하고 작업을 계속한다") {
                val item1 = BulkOperationItem(issueKey = issueKey(1), status = ItemStatus.PENDING)
                val item2 = BulkOperationItem(issueKey = issueKey(2), status = ItemStatus.PENDING)
                val operation = makeOperation(items = listOf(item1, item2))

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(item1, item2)

                // item1: 성공
                every { issueService.findByKey(any(), issueKey(1)) } returns makeIssueResponse("ATLAS-1")
                every { issueService.updateIssue(any(), issueKey(1), any()) } returns makeIssueResponse("ATLAS-1")

                // item2: 이슈 미존재 → NOT_FOUND
                every { issueService.findByKey(any(), issueKey(2)) } throws IssueNotFoundException(issueKey(2))

                justRun { bulkRepo.updateItemResult(any(), any(), any(), any()) }
                justRun { bulkRepo.recomputeAndPersistCounts(any()) }

                sut.process(operationId)

                verify { bulkRepo.updateItemResult(operationId, issueKey(1), ItemStatus.SUCCEEDED, null) }
                verify {
                    bulkRepo.updateItemResult(
                        operationId,
                        issueKey(2),
                        ItemStatus.FAILED,
                        FailureReasonCode.NOT_FOUND,
                    )
                }
                // 카운트 재집계는 처리 완료 후 1회
                verify { bulkRepo.recomputeAndPersistCounts(operationId) }
            }

            it("IssueAccessDeniedException → FAILED(FORBIDDEN) 로 기록한다") {
                val item = BulkOperationItem(issueKey = issueKey(1), status = ItemStatus.PENDING)
                val operation = makeOperation(items = listOf(item))

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(item)
                every { issueService.findByKey(any(), issueKey(1)) } throws
                    IssueAccessDeniedException(ActorId(actorUuid), IssuePermission.UPDATE, IssueScope.Issue("ATLAS-1"))
                justRun { bulkRepo.updateItemResult(any(), any(), any(), any()) }
                justRun { bulkRepo.recomputeAndPersistCounts(any()) }

                sut.process(operationId)

                verify {
                    bulkRepo.updateItemResult(
                        operationId,
                        issueKey(1),
                        ItemStatus.FAILED,
                        FailureReasonCode.FORBIDDEN,
                    )
                }
            }

            it("IssueTransitionNotAllowedException → FAILED(TRANSITION_NOT_ALLOWED) 로 기록한다") {
                val transitionPayload = BulkOperationPayload.Transition(toStateKey = "done")
                val item = BulkOperationItem(issueKey = issueKey(1), status = ItemStatus.PENDING)
                val operation =
                    BulkOperation(
                        id = operationId,
                        actorId = actorUuid,
                        type = BulkOperationType.BULK_TRANSITION,
                        status = BulkOperationStatus.RUNNING,
                        payload = transitionPayload,
                        items = listOf(item),
                        totalCount = 1,
                        processedCount = 0,
                        succeededCount = 0,
                        failedCount = 0,
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                    )

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(item)
                every { issueService.findByKey(any(), issueKey(1)) } returns makeIssueResponse("ATLAS-1")
                every { issueService.transitionIssue(any(), issueKey(1), any()) } throws
                    IssueTransitionNotAllowedException(issueKey(1), "open", "done", "guard failed")
                justRun { bulkRepo.updateItemResult(any(), any(), any(), any()) }
                justRun { bulkRepo.recomputeAndPersistCounts(any()) }

                sut.process(operationId)

                verify {
                    bulkRepo.updateItemResult(
                        operationId,
                        issueKey(1),
                        ItemStatus.FAILED,
                        FailureReasonCode.TRANSITION_NOT_ALLOWED,
                    )
                }
            }

            it("IssueVersionConflictException → FAILED(VERSION_CONFLICT) 로 기록한다") {
                val item = BulkOperationItem(issueKey = issueKey(1), status = ItemStatus.PENDING)
                val operation = makeOperation(items = listOf(item))

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(item)
                every { issueService.findByKey(any(), issueKey(1)) } returns makeIssueResponse("ATLAS-1", version = 3L)
                every { issueService.updateIssue(any(), issueKey(1), any()) } throws
                    IssueVersionConflictException(issueKey(1), 5L)
                justRun { bulkRepo.updateItemResult(any(), any(), any(), any()) }
                justRun { bulkRepo.recomputeAndPersistCounts(any()) }

                sut.process(operationId)

                verify {
                    bulkRepo.updateItemResult(
                        operationId,
                        issueKey(1),
                        ItemStatus.FAILED,
                        FailureReasonCode.VERSION_CONFLICT,
                    )
                }
            }
        }

        describe("deny stub 주입 → FAILED(FORBIDDEN)") {
            it("권한 거부 stub 이 주입되면 모든 이슈가 FAILED(FORBIDDEN) 로 기록된다 (B1 가짜그린 회피)") {
                val items =
                    (1..3).map { n ->
                        BulkOperationItem(issueKey = issueKey(n), status = ItemStatus.PENDING)
                    }
                val operation = makeOperation(items = items)

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns items

                // 모든 이슈에 대해 권한 거부 stub
                items.forEach { item ->
                    every { issueService.findByKey(any(), item.issueKey) } throws
                        IssueAccessDeniedException(
                            ActorId(actorUuid),
                            IssuePermission.UPDATE,
                            IssueScope.Issue(item.issueKey.value),
                        )
                }
                justRun { bulkRepo.updateItemResult(any(), any(), any(), any()) }
                justRun { bulkRepo.recomputeAndPersistCounts(any()) }

                sut.process(operationId)

                items.forEach { item ->
                    verify {
                        bulkRepo.updateItemResult(
                            operationId,
                            item.issueKey,
                            ItemStatus.FAILED,
                            FailureReasonCode.FORBIDDEN,
                        )
                    }
                }
                // updateIssue 는 한 번도 호출되지 않아야 한다
                verify(exactly = 0) { issueService.updateIssue(any(), any(), any()) }
            }
        }

        describe("멱등 스킵") {
            it("이미 SUCCEEDED 인 항목은 issueService 를 호출하지 않고 건너뛴다") {
                val alreadySucceeded = BulkOperationItem(
                    issueKey = issueKey(1),
                    status = ItemStatus.SUCCEEDED,
                )
                val operation = makeOperation(items = listOf(alreadySucceeded))

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(alreadySucceeded)
                justRun { bulkRepo.recomputeAndPersistCounts(any()) }

                sut.process(operationId)

                verify(exactly = 0) { issueService.findByKey(any(), any()) }
                verify(exactly = 0) { issueService.updateIssue(any(), any(), any()) }
                verify(exactly = 0) { bulkRepo.updateItemResult(any(), any(), any(), any()) }
            }

            it("이미 FAILED 인 항목도 건너뛴다") {
                val alreadyFailed = BulkOperationItem(
                    issueKey = issueKey(1),
                    status = ItemStatus.FAILED,
                    failureReasonCode = FailureReasonCode.NOT_FOUND,
                )
                val operation = makeOperation(items = listOf(alreadyFailed))

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(alreadyFailed)
                justRun { bulkRepo.recomputeAndPersistCounts(any()) }

                sut.process(operationId)

                verify(exactly = 0) { issueService.findByKey(any(), any()) }
                verify(exactly = 0) { bulkRepo.updateItemResult(any(), any(), any(), any()) }
            }
        }

        describe("동일 트랜잭션 — 이슈 변경 + 항목 상태 기록") {
            it("updateIssue 호출 직후 같은 호출 흐름에서 updateItemResult 가 호출된다 (C1 부분실패 창 제거)") {
                val item = BulkOperationItem(issueKey = issueKey(1), status = ItemStatus.PENDING)
                val operation = makeOperation(items = listOf(item))

                val callOrder = mutableListOf<String>()

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(item)
                every { issueService.findByKey(any(), issueKey(1)) } returns makeIssueResponse("ATLAS-1")
                every { issueService.updateIssue(any(), issueKey(1), any()) } answers {
                    callOrder += "updateIssue"
                    makeIssueResponse("ATLAS-1")
                }
                every {
                    bulkRepo.updateItemResult(any(), issueKey(1), ItemStatus.SUCCEEDED, null)
                } answers {
                    callOrder += "updateItemResult"
                    1
                }
                justRun { bulkRepo.recomputeAndPersistCounts(any()) }

                sut.process(operationId)

                // updateIssue → updateItemResult 순서가 보장되어야 한다
                callOrder shouldBe listOf("updateIssue", "updateItemResult")
            }
        }

        describe("청크 처리") {
            it("항목이 BULK_OPERATION_CHUNK_SIZE 를 초과해도 모든 항목을 처리한다") {
                val chunkSize = 50
                val totalItems = chunkSize + 1 // 51개: 청크 경계를 넘어야 함
                val items =
                    (1..totalItems).map { n ->
                        BulkOperationItem(issueKey = issueKey(n), status = ItemStatus.PENDING)
                    }
                val operation = makeOperation(items = items)

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns items
                items.forEach { item ->
                    every { issueService.findByKey(any(), item.issueKey) } returns makeIssueResponse(item.issueKey.value)
                    every { issueService.updateIssue(any(), item.issueKey, any()) } returns
                        makeIssueResponse(item.issueKey.value)
                }
                justRun { bulkRepo.updateItemResult(any(), any(), any(), any()) }
                justRun { bulkRepo.recomputeAndPersistCounts(any()) }

                sut.process(operationId)

                verify(exactly = totalItems) { issueService.findByKey(any(), any()) }
                verify(exactly = totalItems) {
                    bulkRepo.updateItemResult(any(), any(), ItemStatus.SUCCEEDED, null)
                }
            }
        }

        describe("BULK_TRANSITION payload 처리") {
            it("BulkOperationPayload.Transition 이면 transitionIssue 를 호출하고 SUCCEEDED 로 기록한다") {
                val transitionPayload = BulkOperationPayload.Transition(toStateKey = "done")
                val item = BulkOperationItem(issueKey = issueKey(1), status = ItemStatus.PENDING)
                val operation =
                    BulkOperation(
                        id = operationId,
                        actorId = actorUuid,
                        type = BulkOperationType.BULK_TRANSITION,
                        status = BulkOperationStatus.RUNNING,
                        payload = transitionPayload,
                        items = listOf(item),
                        totalCount = 1,
                        processedCount = 0,
                        succeededCount = 0,
                        failedCount = 0,
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                    )

                every { bulkRepo.findById(operationId) } returns operation
                every { bulkRepo.findItemsByOperationId(operationId) } returns listOf(item)
                every { issueService.findByKey(any(), issueKey(1)) } returns makeIssueResponse("ATLAS-1")
                every { issueService.transitionIssue(any(), issueKey(1), any()) } returns
                    makeIssueResponse("ATLAS-1")
                justRun { bulkRepo.updateItemResult(any(), any(), any(), any()) }
                justRun { bulkRepo.recomputeAndPersistCounts(any()) }

                sut.process(operationId)

                val requestSlot = slot<TransitionIssueRequest>()
                verify { issueService.transitionIssue(any(), issueKey(1), capture(requestSlot)) }
                requestSlot.captured.toStateKey shouldBe "done"
                verify { bulkRepo.updateItemResult(operationId, issueKey(1), ItemStatus.SUCCEEDED, null) }
            }
        }
    }
})
