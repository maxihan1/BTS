// BulkItemExecutor 단위 테스트 — 예외→FailureReasonCode 매핑, best-effort 격리 검증

package com.bts.issue.bulk.application

import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.BulkOperationStatus
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.domain.FailureReasonCode
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

/**
 * FR-IS-05 Task 10 — BulkItemExecutor 단위 테스트.
 *
 * 검증 범위.
 * - 성공 경로: applier.applyAndRecordSuccess 호출 및 failureRecorder 미호출
 * - 예외→FailureReasonCode 매핑: 각 도메인 예외가 올바른 reasonCode 로 매핑
 * - best-effort 격리: applier 예외가 이 메서드 밖으로 전파되지 않음
 * - 알 수 없는 예외의 fallback: NOT_FOUND 로 안전하게 처리
 *
 * **멱등 스킵은 BulkOperationProcessor.processChunk 책임** — executor 는 단건 처리 조율만 담당한다.
 * 멱등 스킵은 BulkOperationProcessorTest 에서 검증됨.
 *
 * **IssueKey 는 @JvmInline value class + 포맷 검증 init 블록을 가진다.**
 * MockK any() 매처는 시그니처 생성 시 임의 문자열로 IssueKey 인스턴스를 만들려 해서
 * "Invalid issue key format" IllegalArgumentException 을 유발한다.
 * 따라서 every/justRun 블록 내 IssueKey/BulkOperationId 파라미터는 명시적 값으로 지정한다.
 *
 * 단위 테스트 — BulkItemApplier / BulkItemFailureRecorder 는 MockK 모의 객체.
 */
class BulkItemExecutorTest : DescribeSpec({

    val applier = mockk<BulkItemApplier>()
    val failureRecorder = mockk<BulkItemFailureRecorder>()
    val sut = BulkItemExecutor(applier, failureRecorder)

    val actorId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    val operationId = BulkOperationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    val issueKey = IssueKey("ATLAS-1")
    val defaultEditPayload = BulkOperationPayload.Edit(priority = 2, impact = null)

    fun makeOperation(payload: BulkOperationPayload = defaultEditPayload): BulkOperation =
        BulkOperation(
            id = operationId,
            actorId = actorId.value,
            type = BulkOperationType.BULK_EDIT,
            status = BulkOperationStatus.RUNNING,
            payload = payload,
            items = listOf(BulkOperationItem(issueKey = issueKey, status = ItemStatus.PENDING)),
            totalCount = 1,
            processedCount = 0,
            succeededCount = 0,
            failedCount = 0,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    fun pendingItem(): BulkOperationItem = BulkOperationItem(issueKey = issueKey, status = ItemStatus.PENDING)

    beforeEach {
        clearMocks(applier, failureRecorder)
    }

    describe("executeItem") {

        describe("성공 경로") {
            it("applier.applyAndRecordSuccess 를 올바른 인자로 호출한다") {
                val operation = makeOperation()
                val item = pendingItem()
                justRun {
                    applier.applyAndRecordSuccess(actorId, operationId, issueKey, operation.payload)
                }

                sut.executeItem(actorId, operation, item)

                verify(exactly = 1) {
                    applier.applyAndRecordSuccess(actorId, operationId, issueKey, operation.payload)
                }
            }

            it("성공 시 failureRecorder 는 호출되지 않는다") {
                val operation = makeOperation()
                val item = pendingItem()
                justRun {
                    applier.applyAndRecordSuccess(actorId, operationId, issueKey, operation.payload)
                }

                sut.executeItem(actorId, operation, item)

                verify(exactly = 0) {
                    failureRecorder.recordFailure(operationId, issueKey, any())
                }
            }
        }

        describe("예외→FailureReasonCode 매핑") {
            it("IssueNotFoundException → NOT_FOUND 로 failureRecorder 호출") {
                val operation = makeOperation()
                val item = pendingItem()
                every {
                    applier.applyAndRecordSuccess(actorId, operationId, issueKey, operation.payload)
                } throws IssueNotFoundException(issueKey)
                justRun { failureRecorder.recordFailure(operationId, issueKey, any()) }

                sut.executeItem(actorId, operation, item)

                verify(exactly = 1) {
                    failureRecorder.recordFailure(operationId, issueKey, FailureReasonCode.NOT_FOUND)
                }
            }

            it("IssueAccessDeniedException → FORBIDDEN 로 failureRecorder 호출") {
                val operation = makeOperation()
                val item = pendingItem()
                every {
                    applier.applyAndRecordSuccess(actorId, operationId, issueKey, operation.payload)
                } throws
                    IssueAccessDeniedException(
                        actorId,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                justRun { failureRecorder.recordFailure(operationId, issueKey, any()) }

                sut.executeItem(actorId, operation, item)

                verify(exactly = 1) {
                    failureRecorder.recordFailure(operationId, issueKey, FailureReasonCode.FORBIDDEN)
                }
            }

            it("IssueTransitionNotAllowedException → TRANSITION_NOT_ALLOWED 로 failureRecorder 호출") {
                val payload = BulkOperationPayload.Transition(toStateKey = "DONE")
                val operation = makeOperation(payload)
                val item = pendingItem()
                every {
                    applier.applyAndRecordSuccess(actorId, operationId, issueKey, payload)
                } throws
                    IssueTransitionNotAllowedException(
                        issueKey = issueKey,
                        fromStatus = "IN_PROGRESS",
                        toStatus = "DONE",
                        reason = "only lead can close",
                    )
                justRun { failureRecorder.recordFailure(operationId, issueKey, any()) }

                sut.executeItem(actorId, operation, item)

                verify(exactly = 1) {
                    failureRecorder.recordFailure(
                        operationId,
                        issueKey,
                        FailureReasonCode.TRANSITION_NOT_ALLOWED,
                    )
                }
            }

            it("IssueVersionConflictException → VERSION_CONFLICT 로 failureRecorder 호출") {
                val operation = makeOperation()
                val item = pendingItem()
                every {
                    applier.applyAndRecordSuccess(actorId, operationId, issueKey, operation.payload)
                } throws IssueVersionConflictException(issueKey, currentVersion = 5L)
                justRun { failureRecorder.recordFailure(operationId, issueKey, any()) }

                sut.executeItem(actorId, operation, item)

                verify(exactly = 1) {
                    failureRecorder.recordFailure(operationId, issueKey, FailureReasonCode.VERSION_CONFLICT)
                }
            }

            it("IssueWorkflowNotConfiguredException → WORKFLOW_NOT_CONFIGURED 로 failureRecorder 호출") {
                val operation = makeOperation()
                val item = pendingItem()
                every {
                    applier.applyAndRecordSuccess(actorId, operationId, issueKey, operation.payload)
                } throws IssueWorkflowNotConfiguredException("ATLAS", null)
                justRun { failureRecorder.recordFailure(operationId, issueKey, any()) }

                sut.executeItem(actorId, operation, item)

                verify(exactly = 1) {
                    failureRecorder.recordFailure(
                        operationId,
                        issueKey,
                        FailureReasonCode.WORKFLOW_NOT_CONFIGURED,
                    )
                }
            }

            it("매핑 안 된 예외(RuntimeException) → NOT_FOUND fallback 으로 failureRecorder 호출") {
                val operation = makeOperation()
                val item = pendingItem()
                every {
                    applier.applyAndRecordSuccess(actorId, operationId, issueKey, operation.payload)
                } throws RuntimeException("unexpected db error")
                justRun { failureRecorder.recordFailure(operationId, issueKey, any()) }

                sut.executeItem(actorId, operation, item)

                verify(exactly = 1) {
                    failureRecorder.recordFailure(operationId, issueKey, FailureReasonCode.NOT_FOUND)
                }
            }
        }

        describe("best-effort 격리") {
            it("applier 가 예외를 던져도 executeItem 밖으로 예외가 전파되지 않는다") {
                val operation = makeOperation()
                val item = pendingItem()
                every {
                    applier.applyAndRecordSuccess(actorId, operationId, issueKey, operation.payload)
                } throws RuntimeException("simulated failure")
                justRun { failureRecorder.recordFailure(operationId, issueKey, any()) }

                // 예외가 전파되지 않으면 이 블록이 정상 완료된다
                sut.executeItem(actorId, operation, item)

                verify(exactly = 1) { failureRecorder.recordFailure(operationId, issueKey, any()) }
            }

            it("failureRecorder 도 예외를 던지면 executeItem 밖으로 전파된다") {
                // recorder 예외는 executor 가 삼키지 않음 — 명세 문서화 목적
                val operation = makeOperation()
                val item = pendingItem()
                every {
                    applier.applyAndRecordSuccess(actorId, operationId, issueKey, operation.payload)
                } throws IssueNotFoundException(issueKey)
                every {
                    failureRecorder.recordFailure(operationId, issueKey, FailureReasonCode.NOT_FOUND)
                } throws RuntimeException("recorder failed")

                val thrown = runCatching { sut.executeItem(actorId, operation, item) }
                // recorder 예외는 전파됨 (catch 블록 안에서 recorder 호출 시 발생)
                check(thrown.isFailure) { "recorder 예외는 전파되어야 한다" }
            }
        }
    }
})
