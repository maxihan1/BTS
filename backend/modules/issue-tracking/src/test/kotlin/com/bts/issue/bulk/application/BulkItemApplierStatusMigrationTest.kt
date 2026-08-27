// BulkItemApplier 의 STATUS_MIGRATION 가지 단위 테스트 — 매핑·전량 재작성·엔진 우회·해결책 보존·아카이브 가드·OCC

package com.bts.issue.bulk.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.FailureReasonCode
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.domain.StateNotInMigrationMappingException
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.repository.IssueRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

/**
 * FR-WF-07 Task 4 — `BulkItemApplier` STATUS_MIGRATION 가지 단위 테스트.
 *
 * 검증 범위 (spec `docs/specs/2026-08-27-issue-tracking-status-migration.md`).
 * - 완료기준 1 · J7 · F7 — 매핑이 여러 개면 항목마다 **자기 출발 상태의 대상**으로 간다.
 * - 완료기준 2 — 대상이 전량 새 상태로 재작성되고 아무도 남지 않는다.
 * - 완료기준 4 · J8 · F8 — 엔진(`transitionIssue`)을 타지 않아 `TRANSITION_NOT_ALLOWED` 가 0건이다.
 * - 완료기준 6 · G6 · F10 — 기존 `resolutionId` 가 보존된다.
 * - E8 · F7 — 처리 시점 현재 상태가 매핑에 없으면 밀지 않고 [StateNotInMigrationMappingException] 을 던진다.
 * - E14 · D3 ② — 아카이브된 프로젝트의 이슈는 [ProjectArchiveGuard] 가 막아 상태가 재작성되지 않는다.
 * - E10 · F9 — `applyTransition` 이 0행(OCC 충돌)이면 SUCCEEDED 로 기록하지 않는다.
 *
 * **실패 기록은 이 클래스의 책임이 아니다.** 매핑 부재·아카이브 모두 예외로 올리고
 * [BulkItemExecutor] 가 `FailureReasonCode` 로 번역해 FAILED 를 적는다. 여기서 직접 적으면 executor 의
 * 성공 로그(`bulk_op_item_succeeded`)가 FAILED 장부와 어긋난다 — 그 매핑은 `BulkItemExecutorTest` 가 덮는다.
 *
 * **가드는 실제로 주입한다.** `projectArchiveGuard` 는 nullable(`?`) 이라 null 픽스처면 `?.` 가 조용히
 * 통과해 「가드를 검사하는 테스트」가 공허해진다. 그래서 모의 Bean 을 주입하고 호출 자체를 verify 한다.
 *
 * **OCC 0행이 도달 불가 픽스처가 아닌 근거.** `IssueRepositoryTest` 의 `T5 - applyTransition stale`
 * 이 실제 DB 에서 version 불일치 시 0 이 반환되는 것을 증명한다. 이 단위 테스트의 `returns 0` 은
 * 그 실측 계약을 그대로 세운 것이다. 0행 → `IssueVersionConflictException` → `VERSION_CONFLICT`
 * 기록의 마지막 구간은 `BulkItemExecutorTest` 의 예외→코드 매핑 테스트가 이미 덮는다.
 *
 * **MockK 와 IssueKey.** `IssueKey` 는 `@JvmInline value class` + 포맷 검증 `init` 를 가진다.
 * `any()` 매처는 임의 문자열로 인스턴스를 만들려다 `Invalid issue key format` 을 유발하므로
 * `IssueKey`/`BulkOperationId` 파라미터는 항상 명시값으로 지정한다 (`BulkItemExecutorTest` 와 동일).
 */
class BulkItemApplierStatusMigrationTest : DescribeSpec({

    val issueService = mockk<IssueApplicationService>()
    val bulkRepo = mockk<BulkOperationRepository>()
    val issueRepository = mockk<IssueRepository>()
    val archiveGuard = mockk<ProjectArchiveGuard>()
    val sut = BulkItemApplier(issueService, bulkRepo, issueRepository, archiveGuard)

    val actor = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    val operationId = BulkOperationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))

    // ATLAS-1·ATLAS-3 은 in_review, ATLAS-2 는 blocked 에서 출발한다 — 대상이 서로 다르다.
    val inReviewKey = IssueKey("ATLAS-1")
    val blockedKey = IssueKey("ATLAS-2")
    val otherInReviewKey = IssueKey("ATLAS-3")

    /** 매핑 2개. `mapOf` 는 삽입 순서를 보존하므로 「첫 항목 고정」 구현은 blocked 를 in_progress 로 민다. */
    val payload =
        BulkOperationPayload.StatusMigration(
            mappings = mapOf("in_review" to "in_progress", "blocked" to "todo"),
            projectKeys = setOf("ATLAS"),
        )

    fun issueResponse(
        key: IssueKey,
        currentStateKey: String,
        version: Long,
        resolutionId: UUID? = null,
    ): IssueResponse =
        IssueResponse(
            key = key.value,
            id = UUID.randomUUID(),
            projectKey = key.projectPrefix,
            summary = "이관 대상 이슈",
            currentStateKey = currentStateKey,
            reporterId = UUID.randomUUID(),
            version = version,
            createdAt = Instant.parse("2026-08-27T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-27T00:00:00Z"),
            typeId = 1L,
            typeKey = "task",
            typeName = "Task",
            resolutionId = resolutionId,
        )

    beforeEach {
        clearMocks(issueService, bulkRepo, issueRepository, archiveGuard)
        // 기본은 「아카이브 아님」. IssueKey 는 value class 라 any() 매처를 못 쓰므로 키를 명시한다.
        listOf(inReviewKey, blockedKey, otherInReviewKey).forEach { key ->
            justRun { archiveGuard.checkByIssue(key) }
        }
    }

    describe("applyAndRecordSuccess — StatusMigration 가지") {

        it("매핑이 2개면 각 이슈가 자기 출발 상태의 대상으로 간다 (완료기준 1 · J7 · F7)") {
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L)
            every { issueService.findByKey(actor, blockedKey) } returns
                issueResponse(blockedKey, "blocked", version = 7L)
            every { issueRepository.applyTransition(inReviewKey, any(), any(), any()) } returns 1
            every { issueRepository.applyTransition(blockedKey, any(), any(), any()) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) } returns 1
            every { bulkRepo.updateItemResult(operationId, blockedKey, ItemStatus.SUCCEEDED, null) } returns 1

            sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)
            sut.applyAndRecordSuccess(actor, operationId, blockedKey, payload)

            verify(exactly = 1) {
                issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null)
            }
            verify(exactly = 1) {
                issueRepository.applyTransition(blockedKey, "todo", 7L, null)
            }
        }

        it("대상 이슈가 전량 새 상태로 재작성되고 남는 항목이 없다 (완료기준 2)") {
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L)
            every { issueService.findByKey(actor, blockedKey) } returns
                issueResponse(blockedKey, "blocked", version = 7L)
            every { issueService.findByKey(actor, otherInReviewKey) } returns
                issueResponse(otherInReviewKey, "in_review", version = 9L)
            every { issueRepository.applyTransition(inReviewKey, any(), any(), any()) } returns 1
            every { issueRepository.applyTransition(blockedKey, any(), any(), any()) } returns 1
            every { issueRepository.applyTransition(otherInReviewKey, any(), any(), any()) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) } returns 1
            every { bulkRepo.updateItemResult(operationId, blockedKey, ItemStatus.SUCCEEDED, null) } returns 1
            every { bulkRepo.updateItemResult(operationId, otherInReviewKey, ItemStatus.SUCCEEDED, null) } returns 1

            listOf(inReviewKey, blockedKey, otherInReviewKey).forEach {
                sut.applyAndRecordSuccess(actor, operationId, it, payload)
            }

            verify(exactly = 1) { issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null) }
            verify(exactly = 1) { issueRepository.applyTransition(blockedKey, "todo", 7L, null) }
            verify(exactly = 1) { issueRepository.applyTransition(otherInReviewKey, "in_progress", 9L, null) }
            verify(exactly = 1) { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) }
            verify(exactly = 1) { bulkRepo.updateItemResult(operationId, blockedKey, ItemStatus.SUCCEEDED, null) }
            verify(exactly = 1) {
                bulkRepo.updateItemResult(operationId, otherInReviewKey, ItemStatus.SUCCEEDED, null)
            }
        }

        it("유효 전환이 0인 상태여도 엔진을 타지 않아 TRANSITION_NOT_ALLOWED 가 0건이다 (완료기준 4 · J8 · F8)") {
            // 엔진은 이 전환을 거부하도록 무장돼 있다 — 이관이 엔진을 타면 그 예외가 그대로 터진다.
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L)
            every { issueService.transitionIssue(actor, inReviewKey, any()) } throws
                IssueTransitionNotAllowedException(inReviewKey, "in_review", "in_progress")
            every { issueRepository.applyTransition(inReviewKey, any(), any(), any()) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) } returns 1

            sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)

            verify(exactly = 0) { issueService.transitionIssue(actor, inReviewKey, any()) }
            verify(exactly = 0) {
                bulkRepo.updateItemResult(
                    operationId,
                    inReviewKey,
                    ItemStatus.FAILED,
                    FailureReasonCode.TRANSITION_NOT_ALLOWED,
                )
            }
            verify(exactly = 1) { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) }
        }

        it("이관 후 기존 resolutionId 가 보존된다 (완료기준 6 · G6 · F10)") {
            val resolutionId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L, resolutionId = resolutionId)
            every { issueRepository.applyTransition(inReviewKey, any(), any(), any()) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) } returns 1

            sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)

            verify(exactly = 1) {
                issueRepository.applyTransition(inReviewKey, "in_progress", 5L, resolutionId)
            }
        }

        it("현재 상태가 매핑에 없으면 밀지 않고 예외를 던진다 — 장부는 executor 가 적는다 (E8 · F7)") {
            // 큐잉·적재 이후 누가 done 으로 옮겨 놓았다 — 매핑에 done 이 없다.
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "done", version = 5L)
            every { issueRepository.applyTransition(inReviewKey, any(), any(), any()) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, any(), any()) } returns 1

            shouldThrow<StateNotInMigrationMappingException> {
                sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)
            }

            verify(exactly = 0) { issueRepository.applyTransition(inReviewKey, any(), any(), any()) }
            // 성공도 실패도 이 트랜잭션에서 적지 않는다 — 적으면 executor 가 성공 로그를 찍어 장부와 어긋난다.
            verify(exactly = 0) { bulkRepo.updateItemResult(operationId, inReviewKey, any(), any()) }
        }

        it("아카이브된 프로젝트의 이슈는 가드가 막아 상태가 재작성되지 않는다 (E14 · D3 ②)") {
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L)
            every { issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, any(), any()) } returns 1

            // ① 아카이브 프로젝트 — 가드가 409 도메인 예외를 던진다.
            every { archiveGuard.checkByIssue(inReviewKey) } throws ProjectArchivedException(inReviewKey.value)

            shouldThrow<ProjectArchivedException> {
                sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)
            }

            verify(exactly = 1) { archiveGuard.checkByIssue(inReviewKey) }
            verify(exactly = 0) { issueRepository.applyTransition(inReviewKey, any(), any(), any()) }
            verify(exactly = 0) { bulkRepo.updateItemResult(operationId, inReviewKey, any(), any()) }

            // ② 비-공허 짝 — 아카이브가 아니면 여전히 정상 이관된다. 「항상 거부」 구현을 배제한다.
            justRun { archiveGuard.checkByIssue(inReviewKey) }

            sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)

            verify(exactly = 1) { issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null) }
            verify(exactly = 1) { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) }
        }

        it("applyTransition 이 0행(OCC 충돌)이면 SUCCEEDED 로 기록하지 않는다 — 1행이면 기록된다 (E10 · F9)") {
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L)
            every { bulkRepo.updateItemResult(operationId, inReviewKey, any(), any()) } returns 1

            // ① OCC 충돌 — 다른 트랜잭션이 먼저 버전을 올려 0행이 갱신됐다.
            every { issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null) } returns 0

            shouldThrow<IssueVersionConflictException> {
                sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)
            }

            verify(exactly = 0) { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) }

            // ② 비-공허 짝 — 정상 경로(1행)는 여전히 SUCCEEDED 다. 「항상 실패」 구현을 배제한다.
            every { issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null) } returns 1

            sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)

            verify(exactly = 1) { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) }
        }
    }
})
