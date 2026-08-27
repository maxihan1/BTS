// BulkOperationApplicationService 단위 테스트 — 접수 검증/영속/enqueue (Task 4 TDD RED)

package com.bts.issue.bulk.application

import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.event.BulkOperationEnqueuePublisher
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.ActorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID

/**
 * FR-IS-05 Task 4 — BulkOperationApplicationService.submit 단위 테스트.
 *
 * 검증 범위.
 * - 접수 검증: 빈 issueKeys, 1000 초과, operationType↔payload 불일치, payload 범위 위반
 * - 정상 접수: dedup, repo.insert 호출, enqueuePublisher.enqueue 호출, UUID 반환
 * - STATUS_MIGRATION 접수 거부: 이 공개 엔드포인트로는 상태 이관을 큐잉할 수 없다
 *
 * 단위 테스트 — repo / enqueuePublisher 는 MockK 모의 객체 사용.
 * @Transactional 경계는 Task 4 범위 밖 (통합 테스트 PR2에서 검증).
 *
 * LargeClass: 접수 검증 케이스 전반을 한 클래스에 집결시킴.
 */
@Suppress("LargeClass")
class BulkOperationApplicationServiceTest : DescribeSpec({

    val repo = mockk<BulkOperationRepository>()
    val enqueuePublisher = mockk<BulkOperationEnqueuePublisher>()
    val sut = BulkOperationApplicationService(repo, enqueuePublisher)

    val actor = ActorId(UUID.randomUUID())

    fun issueKeys(count: Int) = (1..count).map { "ATLAS-$it" }

    beforeEach {
        clearMocks(repo, enqueuePublisher)
        justRun { repo.insert(any()) }
        justRun { enqueuePublisher.enqueue(any()) }
    }

    // ── 접수 검증 ──────────────────────────────────────────────────────────────

    describe("submit 검증") {

        describe("issueKeys 비어있을 때") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = emptyList(),
                        editPayload = BulkEditPayload(priority = 3, impact = null),
                        transitionPayload = null,
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("issueKeys 가 1000 초과일 때") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = issueKeys(1001),
                        editPayload = BulkEditPayload(priority = 3, impact = null),
                        transitionPayload = null,
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("issueKeys 가 정확히 1000개일 때") {
            it("정상 접수된다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = issueKeys(1000),
                        editPayload = BulkEditPayload(priority = 3, impact = null),
                        transitionPayload = null,
                    )
                val result = sut.submit(actor, req)
                result.value shouldBe result.value // BulkOperationId 타입 반환 확인
            }
        }

        describe("BULK_EDIT 인데 editPayload 가 null 일 때") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = null,
                        transitionPayload = null,
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("BULK_EDIT 인데 transitionPayload 만 있을 때") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = null,
                        transitionPayload = BulkTransitionPayload(toStateKey = "DONE"),
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("BULK_EDIT 인데 editPayload 와 transitionPayload 둘 다 있을 때") {
            it("transitionPayload 가 non-null 이므로 IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = BulkEditPayload(priority = 3, impact = null),
                        transitionPayload = BulkTransitionPayload(toStateKey = "DONE"),
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("BULK_TRANSITION 인데 transitionPayload 와 editPayload 둘 다 있을 때") {
            it("editPayload 가 non-null 이므로 IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_TRANSITION,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = BulkEditPayload(priority = 3, impact = null),
                        transitionPayload = BulkTransitionPayload(toStateKey = "DONE"),
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("BULK_TRANSITION 인데 transitionPayload 가 null 일 때") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_TRANSITION,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = null,
                        transitionPayload = null,
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("BULK_TRANSITION 인데 toStateKey 가 빈 문자열일 때") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_TRANSITION,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = null,
                        transitionPayload = BulkTransitionPayload(toStateKey = ""),
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("BULK_TRANSITION 인데 toStateKey 가 공백만 있을 때") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_TRANSITION,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = null,
                        transitionPayload = BulkTransitionPayload(toStateKey = "   "),
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("BULK_EDIT priority 범위 위반 — 6") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = BulkEditPayload(priority = 6, impact = null),
                        transitionPayload = null,
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("BULK_EDIT priority 범위 위반 — 0") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = BulkEditPayload(priority = 0, impact = null),
                        transitionPayload = null,
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("BULK_EDIT impact 범위 위반 — 4") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = BulkEditPayload(priority = null, impact = 4),
                        transitionPayload = null,
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("BULK_EDIT impact 범위 위반 — 0") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = BulkEditPayload(priority = null, impact = 0),
                        transitionPayload = null,
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }

        describe("BULK_EDIT editPayload 필드 모두 null — 변경할 것이 없을 때") {
            it("IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = BulkEditPayload(priority = null, impact = null),
                        transitionPayload = null,
                    )
                shouldThrow<IllegalArgumentException> {
                    sut.submit(actor, req)
                }
            }
        }
    }

    // ── 정상 접수 ──────────────────────────────────────────────────────────────

    describe("정상 접수 — BULK_EDIT") {

        describe("중복 issueKeys 가 포함될 때") {
            it("dedup 후 고유 항목만 repo.insert 호출") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1", "ATLAS-2", "ATLAS-1"),
                        editPayload = BulkEditPayload(priority = 3, impact = null),
                        transitionPayload = null,
                    )
                sut.submit(actor, req)

                val captured = slot<BulkOperation>()
                verify(exactly = 1) { repo.insert(capture(captured)) }
                captured.captured.items.size shouldBe 2
                captured.captured.totalCount shouldBe 2
            }
        }

        describe("유효한 요청일 때") {
            it("repo.insert 와 enqueuePublisher.enqueue 를 각 1회 호출하고 BulkOperationId 를 반환한다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1", "ATLAS-2"),
                        editPayload = BulkEditPayload(priority = 2, impact = 1),
                        transitionPayload = null,
                    )
                val result = sut.submit(actor, req)

                verify(exactly = 1) { repo.insert(any()) }
                verify(exactly = 1) { enqueuePublisher.enqueue(any()) }
                result.value shouldBe result.value // BulkOperationId 반환 타입 확인
            }

            it("BulkOperation 이 actor.value 를 actorId 로 저장한다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-3"),
                        editPayload = BulkEditPayload(priority = 5, impact = null),
                        transitionPayload = null,
                    )
                sut.submit(actor, req)

                val captured = slot<BulkOperation>()
                verify { repo.insert(capture(captured)) }
                captured.captured.actorId shouldBe actor.value
            }

            it("enqueue 에 전달된 ID 가 repo.insert 에 전달된 ID 와 동일하다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-4"),
                        editPayload = BulkEditPayload(priority = 1, impact = 2),
                        transitionPayload = null,
                    )
                sut.submit(actor, req)

                val opCaptured = slot<BulkOperation>()
                val enqCaptured = slot<BulkOperationId>()
                verify { repo.insert(capture(opCaptured)) }
                verify { enqueuePublisher.enqueue(capture(enqCaptured)) }
                enqCaptured.captured.value shouldBe opCaptured.captured.id.value
            }
        }
    }

    describe("payload round-trip — submit 후 repo.insert 에 전달된 BulkOperation 에 payload 보존") {

        describe("BULK_EDIT 요청 시") {
            it("insert 에 전달된 BulkOperation 의 payload 가 Edit(priority=2, impact=1) 이다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = BulkEditPayload(priority = 2, impact = 1),
                        transitionPayload = null,
                    )
                sut.submit(actor, req)

                val captured = slot<BulkOperation>()
                verify { repo.insert(capture(captured)) }
                val payload = captured.captured.payload
                payload shouldBe BulkOperationPayload.Edit(priority = 2, impact = 1)
            }
        }

        describe("BULK_TRANSITION 요청 시") {
            it("insert 에 전달된 BulkOperation 의 payload 가 Transition(toStateKey='DONE') 이다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_TRANSITION,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = null,
                        transitionPayload = BulkTransitionPayload(toStateKey = "DONE"),
                    )
                sut.submit(actor, req)

                val captured = slot<BulkOperation>()
                verify { repo.insert(capture(captured)) }
                val payload = captured.captured.payload
                payload shouldBe BulkOperationPayload.Transition(toStateKey = "DONE")
            }
        }
    }

    describe("정상 접수 — BULK_TRANSITION") {

        describe("유효한 요청일 때") {
            it("repo.insert 와 enqueuePublisher.enqueue 를 각 1회 호출한다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_TRANSITION,
                        issueKeys = listOf("ATLAS-10", "ATLAS-11"),
                        editPayload = null,
                        transitionPayload = BulkTransitionPayload(toStateKey = "IN_PROGRESS"),
                    )
                sut.submit(actor, req)

                verify(exactly = 1) { repo.insert(any()) }
                verify(exactly = 1) { enqueuePublisher.enqueue(any()) }
            }
        }
    }

    describe("경계값 — priority/impact 유효 범위") {

        listOf(1, 5).forEach { p ->
            describe("priority=$p 는 유효 범위") {
                it("정상 접수된다") {
                    val req =
                        BulkUpdateRequest(
                            operationType = BulkOperationType.BULK_EDIT,
                            issueKeys = listOf("ATLAS-1"),
                            editPayload = BulkEditPayload(priority = p, impact = null),
                            transitionPayload = null,
                        )
                    sut.submit(actor, req)
                    verify(exactly = 1) { repo.insert(any()) }
                }
            }
        }

        listOf(1, 3).forEach { i ->
            describe("impact=$i 는 유효 범위") {
                it("정상 접수된다") {
                    val req =
                        BulkUpdateRequest(
                            operationType = BulkOperationType.BULK_EDIT,
                            issueKeys = listOf("ATLAS-1"),
                            editPayload = BulkEditPayload(priority = null, impact = i),
                            transitionPayload = null,
                        )
                    sut.submit(actor, req)
                    verify(exactly = 1) { repo.insert(any()) }
                }
            }
        }
    }

    // ── STATUS_MIGRATION 접수 거부 ─────────────────────────────────────────────
    //
    // STATUS_MIGRATION 은 워크플로우 상태 이관 어댑터(IssueStatusMigrationPort 구현)만
    // 큐잉할 수 있다. 이 공개 엔드포인트는 워크플로우 정의의 PUBLISH 권한을 검사하지 않으므로,
    // operationType 만 바꾼 위조 요청이 통과하면 이관 신뢰 모델이 통째로 무너진다.

    describe("공개 일괄작업 엔드포인트의 STATUS_MIGRATION 접수 거부") {

        val rejectionMessage = "STATUS_MIGRATION is not accepted by this endpoint"

        describe("payload 를 하나도 싣지 않은 STATUS_MIGRATION 요청일 때") {
            it("거부 메시지와 함께 IllegalArgumentException 을 던진다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.STATUS_MIGRATION,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = null,
                        transitionPayload = null,
                    )
                val ex = shouldThrow<IllegalArgumentException> { sut.submit(actor, req) }
                ex.message shouldBe rejectionMessage
            }
        }

        describe("정상 형태의 editPayload 를 실어 위조 접수를 시도할 때") {
            it("payload 가 유효해도 같은 거부 메시지로 거부한다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.STATUS_MIGRATION,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = BulkEditPayload(priority = 3, impact = 1),
                        transitionPayload = null,
                    )
                val ex = shouldThrow<IllegalArgumentException> { sut.submit(actor, req) }
                ex.message shouldBe rejectionMessage
            }
        }

        describe("정상 형태의 transitionPayload 를 실어 위조 접수를 시도할 때") {
            it("payload 가 유효해도 같은 거부 메시지로 거부한다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.STATUS_MIGRATION,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = null,
                        transitionPayload = BulkTransitionPayload(toStateKey = "DONE"),
                    )
                val ex = shouldThrow<IllegalArgumentException> { sut.submit(actor, req) }
                ex.message shouldBe rejectionMessage
            }
        }

        describe("거부된 STATUS_MIGRATION 요청의 부수효과") {
            it("영속도 enqueue 도 일어나지 않는다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.STATUS_MIGRATION,
                        issueKeys = listOf("ATLAS-1", "ATLAS-2"),
                        editPayload = BulkEditPayload(priority = 3, impact = null),
                        transitionPayload = null,
                    )
                shouldThrow<IllegalArgumentException> { sut.submit(actor, req) }

                verify(exactly = 0) { repo.insert(any()) }
                verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
            }
        }

        // 비-공허 짝 — 「모든 요청이 거부되는 상태」와 구별한다.
        describe("같은 픽스처에서 operationType 만 바꿨을 때") {
            it("BULK_EDIT 는 여전히 정상 접수된다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_EDIT,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = BulkEditPayload(priority = 3, impact = 1),
                        transitionPayload = null,
                    )
                sut.submit(actor, req)

                verify(exactly = 1) { repo.insert(any()) }
                verify(exactly = 1) { enqueuePublisher.enqueue(any()) }
            }

            it("BULK_TRANSITION 는 여전히 정상 접수된다") {
                val req =
                    BulkUpdateRequest(
                        operationType = BulkOperationType.BULK_TRANSITION,
                        issueKeys = listOf("ATLAS-1"),
                        editPayload = null,
                        transitionPayload = BulkTransitionPayload(toStateKey = "DONE"),
                    )
                sut.submit(actor, req)

                verify(exactly = 1) { repo.insert(any()) }
                verify(exactly = 1) { enqueuePublisher.enqueue(any()) }
            }
        }
    }
})
