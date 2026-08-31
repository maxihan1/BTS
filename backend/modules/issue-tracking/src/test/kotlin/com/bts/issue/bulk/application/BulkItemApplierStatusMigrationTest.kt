// BulkItemApplier 의 STATUS_MIGRATION 가지 단위 테스트 — 매핑·재작성·엔진 우회·해결책·가드·OCC·이벤트·이력

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
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.event.IssueDomainEvent
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueTransitioned
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.repository.IssueRepository
import com.bts.shared.issue.IssueTypeId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Instant
import java.util.UUID

/**
 * FR-WF-07 Task 4·6 — `BulkItemApplier` STATUS_MIGRATION 가지 단위 테스트.
 *
 * 검증 범위 (spec `docs/specs/2026-08-27-issue-tracking-status-migration.md`).
 * - 완료기준 1 · J7 · F7 — 매핑이 여러 개면 항목마다 **자기 출발 상태의 대상**으로 간다.
 * - 완료기준 2 — 대상이 전량 새 상태로 재작성되고 아무도 남지 않는다.
 * - 완료기준 4 · J8 · F8 — 엔진(`transitionIssue`)을 타지 않아 `TRANSITION_NOT_ALLOWED` 가 0건이다.
 * - 완료기준 6 · G6 · F10 — 기존 `resolutionId` 가 보존된다.
 * - E8 · F7 — 처리 시점 현재 상태가 매핑에 없으면 밀지 않고 [StateNotInMigrationMappingException] 을 던진다.
 * - E14 · D3 ② — 아카이브된 프로젝트의 이슈는 [ProjectArchiveGuard] 가 막아 상태가 재작성되지 않는다.
 * - E10 · F9 — `applyTransition` 이 0행(OCC 충돌)이면 SUCCEEDED 로 기록하지 않는다.
 * - 완료기준 10 · C-B1 · F11 · F17 — 이관 성공 시 [IssueTransitioned] 를 발행하고 그 이벤트에만
 *   `cause = "STATUS_MIGRATION"` 이 실린다. 일반 전환 발행부가 만드는 이벤트는 `cause` 가 `null` 이다.
 * - F12 — 이관 성공 시 [IssueHistoryRecorder] 로 상태 변경 이력을 남긴다.
 * - spec §D3 ③ — 쓰기 전에 [IssueRepository.findByKeyForUpdate] 로 비관락을 잡고, 대상 상태·버전·해결책을
 *   **락 뒤 재조회**가 정한다. 락을 잡고도 락 밖에서 읽은 값으로 판단하면 TOCTOU 로 락이 무력해진다.
 *
 * **`cause` 하위호환을 왜 여기서 왕복 검증하는가.** `cause` 는 이 task 가 더한 필드이고
 * [IssueTransitioned] 는 pgmq 로 JSON 이 흐르는 타입이다. 배포 순서상 **`cause` 없는 옛 메시지를
 * 신버전이 읽는** 구간이 반드시 생기므로, 필드를 더한 자리에서 그 왕복을 함께 못박는다.
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
    val eventPublisher = mockk<IssueEventPublisher>()
    val historyRecorder = mockk<IssueHistoryRecorder>()
    val sut =
        BulkItemApplier(
            issueService,
            bulkRepo,
            issueRepository,
            eventPublisher,
            historyRecorder,
            archiveGuard,
        )

    val actor = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    val operationId = BulkOperationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))

    // ATLAS-1·ATLAS-3 은 in_review, ATLAS-2 는 blocked 에서 출발한다 — 대상이 서로 다르다.
    val inReviewKey = IssueKey("ATLAS-1")
    val blockedKey = IssueKey("ATLAS-2")
    val otherInReviewKey = IssueKey("ATLAS-3")

    /** 이력 기록의 `projectId` 인자 출처. 이관 대상 3건은 모두 같은 프로젝트다. */
    val projectId = UUID.fromString("00000000-0000-0000-0000-0000000000b0")

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

    /**
     * 이력 `before` 스냅샷의 출처. [IssueHistoryRecorder.record] 는 REST DTO 가 아니라 도메인
     * [Issue] 를 받으므로(그리고 `projectId` 는 DTO 에 없다) 이관 가지가 별도로 읽어야 한다.
     */
    fun issueEntity(
        key: IssueKey,
        currentStateKey: String,
        version: Long,
        resolutionId: UUID? = null,
    ): Issue =
        Issue(
            id = IssueId(UUID.randomUUID()),
            key = key,
            projectId = projectId,
            summary = "이관 대상 이슈",
            reporterId = actor,
            currentStateKey = currentStateKey,
            version = version,
            deletedAt = null,
            createdAt = Instant.parse("2026-08-27T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-27T00:00:00Z"),
            typeId = IssueTypeId(1L),
            resolutionId = resolutionId,
        )

    beforeEach {
        clearMocks(issueService, bulkRepo, issueRepository, archiveGuard, eventPublisher, historyRecorder)
        // 기본은 「아카이브 아님」. IssueKey 는 value class 라 any() 매처를 못 쓰므로 키를 명시한다.
        listOf(inReviewKey, blockedKey, otherInReviewKey).forEach { key ->
            justRun { archiveGuard.checkByIssue(key) }
        }
        // 락 뒤 재조회 기본 stub — 이관 가지의 상태·버전·해결책과 이력 before 스냅샷이 모두 이 읽기에서 나온다.
        every { issueRepository.findByKeyForUpdate(inReviewKey) } returns
            issueEntity(inReviewKey, "in_review", 5L)
        every { issueRepository.findByKeyForUpdate(blockedKey) } returns
            issueEntity(blockedKey, "blocked", 7L)
        every { issueRepository.findByKeyForUpdate(otherInReviewKey) } returns
            issueEntity(otherInReviewKey, "in_review", 9L)
        // 잠그지 않는 읽기도 같은 값으로 무장해 둔다 — 구현이 그리로 되돌아가면 MockK 「answer 없음」 예외가
        // 아니라 「락을 안 잡았다」는 단언 실패로 드러나야 진단이 산다.
        every { issueRepository.findByKey(inReviewKey) } returns issueEntity(inReviewKey, "in_review", 5L)
        every { issueRepository.findByKey(blockedKey) } returns issueEntity(blockedKey, "blocked", 7L)
        every { issueRepository.findByKey(otherInReviewKey) } returns
            issueEntity(otherInReviewKey, "in_review", 9L)
        justRun { eventPublisher.publish(any()) }
        justRun { historyRecorder.record(any(), any(), any(), any()) }
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
            every { issueRepository.findByKeyForUpdate(inReviewKey) } returns
                issueEntity(inReviewKey, "in_review", 5L, resolutionId = resolutionId)
            every { issueRepository.applyTransition(inReviewKey, any(), any(), any()) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) } returns 1

            sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)

            verify(exactly = 1) {
                issueRepository.applyTransition(inReviewKey, "in_progress", 5L, resolutionId)
            }
        }

        it("현재 상태가 매핑에 없으면 밀지 않고 예외를 던진다 — 장부는 executor 가 적는다 (E8 · F7)") {
            // 큐잉·적재 이후 누가 done 으로 옮겨 놓았다 — 매핑에 done 이 없다.
            // 같은 행을 두 번 읽는 것이므로 락 뒤 재조회도 done 이다(픽스처 정합).
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "done", version = 5L)
            every { issueRepository.findByKeyForUpdate(inReviewKey) } returns
                issueEntity(inReviewKey, "done", 5L)
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

        it("D-ORDER — 권한 → 아카이브 가드 → 비관락 → 쓰기 순서를 지킨다 (spec §D3 ②③ · 게이트 2 리뷰 ③)") {
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L)
            every { issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) } returns 1

            sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)

            // [migrateStatus] KDoc 이 이 순서를 계약으로 선언한다. 단언이 없으면 계약이 문장으로만 남는다.
            // - findByKey 를 다른 가지로 옮기면 이관 가지의 유일한 권한 검사(BROWSE)가 사라진다.
            // - 가드와 락을 맞바꾸면 미인가 actor 가 행을 잠글 수 있게 된다.
            // - 쓴 뒤에 잠그면 동시 편집과의 경합을 하나도 막지 못한다.
            verifyOrder {
                issueService.findByKey(actor, inReviewKey)
                archiveGuard.checkByIssue(inReviewKey)
                issueRepository.findByKeyForUpdate(inReviewKey)
                issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null)
            }
            verify(exactly = 1) { issueService.findByKey(actor, inReviewKey) }
            verify(exactly = 1) { archiveGuard.checkByIssue(inReviewKey) }
            verify(exactly = 1) { issueRepository.findByKeyForUpdate(inReviewKey) }
            // 잠그지 않는 읽기로 되돌아가면 여기가 red 다 (beforeEach 가 그 읽기도 무장해 두었다).
            verify(exactly = 0) { issueRepository.findByKey(inReviewKey) }
        }

        it("잠그기 전 읽은 버전이 낡아도 락 뒤 버전으로 써서 이관이 성공한다 (spec §D3 ③ · TOCTOU)") {
            // 잠그기 전 읽기(v5) 와 락 획득 사이에 다른 트랜잭션이 상태 아닌 필드를 고쳐 v6 이 됐다.
            // 락은 그 커밋을 기다린 뒤 진행해야 한다 — 낡은 v5 로 쓰면 그 건이 통째로 실패한다.
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L)
            every { issueRepository.findByKeyForUpdate(inReviewKey) } returns
                issueEntity(inReviewKey, "in_review", 6L)
            // 낡은 버전으로 쓰면 DB 는 0행이다 — IssueRepositoryTest 의 `T5 - applyTransition stale` 이 실측한 계약.
            every { issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null) } returns 0
            every { issueRepository.applyTransition(inReviewKey, "in_progress", 6L, null) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) } returns 1

            sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)

            verify(exactly = 1) { issueRepository.applyTransition(inReviewKey, "in_progress", 6L, null) }
            verify(exactly = 1) { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) }
        }

        it("잠그기 전 읽은 상태가 낡았으면 락 뒤 상태가 대상을 정한다 — 밀지 않는다 (E8 · TOCTOU)") {
            // 잠그기 전에는 in_review 로 보였지만 락을 잡고 다시 읽으니 누가 done 으로 옮겨 놓았다.
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L)
            every { issueRepository.findByKeyForUpdate(inReviewKey) } returns
                issueEntity(inReviewKey, "done", 6L)
            // 낡은 상태로 계산한 대상을 일부러 무장해 둔다 — 「그리로 가지 않는다」를 단언하기 위해서다.
            every { issueRepository.applyTransition(inReviewKey, "in_progress", any(), any()) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, any(), any()) } returns 1

            val thrown =
                shouldThrow<StateNotInMigrationMappingException> {
                    sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)
                }

            // 보고되는 상태도 락 뒤 값이어야 한다 — 낡은 값을 보고하면 운영자가 엉뚱한 상태를 쫓는다.
            thrown.currentStateKey shouldBe "done"
            verify(exactly = 0) { issueRepository.applyTransition(inReviewKey, any(), any(), any()) }
            verify(exactly = 0) { bulkRepo.updateItemResult(operationId, inReviewKey, any(), any()) }
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

        it("이관 성공 시 IssueTransitioned 가 발행된다 — 실패한 건은 발행하지 않는다 (F11 · G2)") {
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L)
            every { issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) } returns 1
            val published = mutableListOf<IssueDomainEvent>()
            justRun { eventPublisher.publish(capture(published)) }

            sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)

            published.size shouldBe 1
            val event = published.single().shouldBeInstanceOf<IssueTransitioned>()
            event.issueKey shouldBe inReviewKey
            event.fromState shouldBe "in_review"
            event.toState shouldBe "in_progress"
            event.actorId shouldBe actor

            // 비-공허 짝 — 매핑에 없어 실패한 건은 발행되지 않는다. 「무조건 발행」 구현을 배제한다.
            every { issueService.findByKey(actor, blockedKey) } returns
                issueResponse(blockedKey, "done", version = 7L)
            every { issueRepository.findByKeyForUpdate(blockedKey) } returns
                issueEntity(blockedKey, "done", 7L)

            shouldThrow<StateNotInMigrationMappingException> {
                sut.applyAndRecordSuccess(actor, operationId, blockedKey, payload)
            }

            published.size shouldBe 1
        }

        it("이관 이벤트에만 cause=STATUS_MIGRATION 이 실린다 — 일반 전환은 null 이다 (완료기준 10 · C-B1 · F17)") {
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L)
            every { issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) } returns 1
            val published = mutableListOf<IssueDomainEvent>()
            justRun { eventPublisher.publish(capture(published)) }

            // ① 이관 경로 — 표시가 실린다.
            sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)

            published.single().shouldBeInstanceOf<IssueTransitioned>().cause shouldBe "STATUS_MIGRATION"

            // ② 같은 applier 의 일반 전환 가지는 스스로 발행하지 않는다 — 표시가 번지지 않는다.
            //    (일반 전환의 발행은 IssueApplicationService.transitionIssue 가 한다.)
            every { issueService.findByKey(actor, blockedKey) } returns
                issueResponse(blockedKey, "blocked", version = 7L)
            every { issueService.transitionIssue(actor, blockedKey, any()) } returns
                issueResponse(blockedKey, "done", version = 8L)
            every { bulkRepo.updateItemResult(operationId, blockedKey, ItemStatus.SUCCEEDED, null) } returns 1

            sut.applyAndRecordSuccess(
                actor,
                operationId,
                blockedKey,
                BulkOperationPayload.Transition(toStateKey = "done", resolutionId = null),
            )

            published.size shouldBe 1

            // ③ 일반 전환 발행부(IssueApplicationService.transitionIssue)와 **같은 인자 목록**으로 만든
            //    이벤트는 cause 가 null 이다 — 기본값을 non-null 로 바꾸면 여기가 red 다 (F17 하위호환).
            IssueTransitioned(
                issueKey = blockedKey,
                fromState = "blocked",
                toState = "done",
                actorId = actor,
                occurredAt = Instant.parse("2026-08-27T00:00:00Z"),
            ).cause shouldBe null
        }

        it("이관 성공 시 상태 변경 이력이 기록된다 — 실패한 건은 기록되지 않는다 (F12)") {
            every { issueService.findByKey(actor, inReviewKey) } returns
                issueResponse(inReviewKey, "in_review", version = 5L)
            every { issueRepository.applyTransition(inReviewKey, "in_progress", 5L, null) } returns 1
            every { bulkRepo.updateItemResult(operationId, inReviewKey, ItemStatus.SUCCEEDED, null) } returns 1

            sut.applyAndRecordSuccess(actor, operationId, inReviewKey, payload)

            verify(exactly = 1) {
                historyRecorder.record(
                    before = match { it?.key == inReviewKey && it.currentStateKey == "in_review" },
                    after = match { it?.key == inReviewKey && it.currentStateKey == "in_progress" },
                    actor = actor,
                    projectId = projectId,
                )
            }

            // 비-공허 짝 — 매핑에 없어 실패한 건은 이력을 남기지 않는다(총 호출은 여전히 1회).
            every { issueService.findByKey(actor, blockedKey) } returns
                issueResponse(blockedKey, "done", version = 7L)
            every { issueRepository.findByKeyForUpdate(blockedKey) } returns
                issueEntity(blockedKey, "done", 7L)

            shouldThrow<StateNotInMigrationMappingException> {
                sut.applyAndRecordSuccess(actor, operationId, blockedKey, payload)
            }

            verify(exactly = 1) { historyRecorder.record(any(), any(), any(), any()) }
        }
    }

    describe("IssueTransitioned.cause 하위호환 (F17)") {

        val mapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())

        it("cause 없는 옛 큐 메시지가 cause=null 로 복원된다") {
            // 이 PR 이전 발행부가 실제로 쏘던 형태 그대로다 — cause 키 자체가 없다.
            val legacyJson =
                """
                {
                  "type": "issue.transitioned",
                  "issueKey": "ATLAS-1",
                  "fromState": "in_review",
                  "toState": "in_progress",
                  "actorId": {"value": "00000000-0000-0000-0000-000000000001"},
                  "occurredAt": "2026-08-27T00:00:00Z"
                }
                """.trimIndent()

            val restored = mapper.readValue(legacyJson, IssueDomainEvent::class.java)

            restored.shouldBeInstanceOf<IssueTransitioned>().cause shouldBe null
        }

        it("cause 를 실은 새 메시지는 왕복해도 값이 보존된다") {
            val event =
                IssueTransitioned(
                    issueKey = inReviewKey,
                    fromState = "in_review",
                    toState = "in_progress",
                    actorId = actor,
                    occurredAt = Instant.parse("2026-08-27T00:00:00Z"),
                    cause = "STATUS_MIGRATION",
                )

            val json = mapper.writeValueAsString(event)

            json shouldContain "\"cause\":\"STATUS_MIGRATION\""
            mapper.readValue(json, IssueDomainEvent::class.java) shouldBe event
        }
    }
})
