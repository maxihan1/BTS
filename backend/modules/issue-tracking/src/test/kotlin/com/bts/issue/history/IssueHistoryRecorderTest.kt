// IssueHistoryRecorder 단위 테스트 — created/deleted/수정/no-op/댓글본문수정 경로 검증 (MockK)

package com.bts.issue.history

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.UUID

class IssueHistoryRecorderTest : DescribeSpec({

    val detector = mockk<IssueChangeDetector>()
    val resolver = mockk<IssueChangeLabelResolver>()
    val repository = mockk<IssueChangeHistoryRepository>()

    val sut =
        IssueHistoryRecorder(
            detector = detector,
            resolver = resolver,
            repository = repository,
        )

    val projectId = UUID.randomUUID()
    val actor = ActorId(UUID.randomUUID())
    val issueId = IssueId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")

    beforeTest {
        clearAllMocks()
    }

    fun makeIssue(
        id: IssueId = issueId,
        key: IssueKey = issueKey,
    ): com.bts.issue.domain.Issue =
        com.bts.issue.domain.Issue(
            id = id,
            key = key,
            projectId = projectId,
            summary = "summary",
            reporterId = ActorId(UUID.randomUUID()),
            currentStateKey = "open",
            version = 1L,
            deletedAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            typeId = IssueTypeId(1L),
        )

    describe("record — created 경로 (before=null)") {
        val issue = makeIssue()
        val createdItem = IssueChangeItem(field = "lifecycle", fromValue = null, toValue = "created")

        beforeTest {
            every { detector.created(issue) } returns listOf(createdItem)
            every { resolver.resolveLabels(listOf(createdItem), projectId) } returns listOf(createdItem)
            every { repository.record(any()) } returns Unit
        }

        it("detector.created 를 호출하고 repository.record 를 호출한다") {
            sut.record(before = null, after = issue, actor = actor, projectId = projectId)

            verify { detector.created(issue) }
            verify { repository.record(any()) }
        }

        it("group 의 issueId 와 issueKey 가 after 이슈 값과 일치한다") {
            sut.record(before = null, after = issue, actor = actor, projectId = projectId)

            verify {
                repository.record(
                    match { group ->
                        group.issueId == issue.id.value &&
                            group.issueKey == issue.key.value &&
                            group.actorId == actor.value
                    },
                )
            }
        }
    }

    describe("record — deleted 경로 (after=null)") {
        val issue = makeIssue()
        val deletedItem = IssueChangeItem(field = "lifecycle", fromValue = null, toValue = "deleted")

        beforeTest {
            every { detector.deleted(issue) } returns listOf(deletedItem)
            every { resolver.resolveLabels(listOf(deletedItem), projectId) } returns listOf(deletedItem)
            every { repository.record(any()) } returns Unit
        }

        it("detector.deleted 를 호출하고 repository.record 를 호출한다") {
            sut.record(before = issue, after = null, actor = actor, projectId = projectId)

            verify { detector.deleted(issue) }
            verify { repository.record(any()) }
        }

        it("group 의 issueId 와 issueKey 가 before 이슈 값과 일치한다") {
            sut.record(before = issue, after = null, actor = actor, projectId = projectId)

            verify {
                repository.record(
                    match { group ->
                        group.issueId == issue.id.value &&
                            group.issueKey == issue.key.value
                    },
                )
            }
        }
    }

    describe("record — 수정 경로 (before, after 모두 non-null)") {
        val before = makeIssue()
        val after = before.copy(summary = "변경된 제목")
        val summaryItem = IssueChangeItem(field = "summary", fromValue = "summary", toValue = "변경된 제목")
        val resolvedItem = summaryItem.copy()

        beforeTest {
            every { detector.detect(before, after) } returns listOf(summaryItem)
            every { resolver.resolveLabels(listOf(summaryItem), projectId) } returns listOf(resolvedItem)
            every { repository.record(any()) } returns Unit
        }

        it("detector.detect → resolver.resolveLabels → repository.record 순으로 호출된다") {
            sut.record(before = before, after = after, actor = actor, projectId = projectId)

            verify { detector.detect(before, after) }
            verify { resolver.resolveLabels(listOf(summaryItem), projectId) }
            verify { repository.record(any()) }
        }

        it("group 의 items 가 resolver 결과와 일치한다") {
            sut.record(before = before, after = after, actor = actor, projectId = projectId)

            verify {
                repository.record(
                    match { group -> group.items == listOf(resolvedItem) },
                )
            }
        }
    }

    describe("record — no-op 경로 (수정이지만 변경 없음)") {
        val before = makeIssue()
        val after = before.copy()

        beforeTest {
            every { detector.detect(before, after) } returns emptyList()
        }

        it("items 가 빈 경우 repository.record 를 호출하지 않는다") {
            sut.record(before = before, after = after, actor = actor, projectId = projectId)

            verify(exactly = 0) { repository.record(any()) }
        }

        it("items 가 빈 경우 resolver.resolveLabels 도 호출하지 않는다") {
            sut.record(before = before, after = after, actor = actor, projectId = projectId)

            verify(exactly = 0) { resolver.resolveLabels(any(), any()) }
        }
    }

    describe("record — actor null 허용") {
        val issue = makeIssue()
        val createdItem = IssueChangeItem(field = "lifecycle", fromValue = null, toValue = "created")

        beforeTest {
            every { detector.created(issue) } returns listOf(createdItem)
            every { resolver.resolveLabels(listOf(createdItem), projectId) } returns listOf(createdItem)
            every { repository.record(any()) } returns Unit
        }

        it("actor 가 null 이면 group.actorId 도 null 이다") {
            sut.record(before = null, after = issue, actor = null, projectId = projectId)

            verify {
                repository.record(
                    match { group -> group.actorId == null },
                )
            }
        }
    }

    describe("recordCommentEdited — 댓글 본문 수정 경로 (FR-CO-02)") {
        val commentId = UUID.randomUUID()
        val beforeBody = "수정 전 본문"
        val afterBody = "수정 후 본문"

        // detector/resolver 는 strict mock 이므로 stub 하지 않는다 —
        // 이 진입점이 둘 중 하나라도 경유하면 테스트가 즉시 실패한다(음성 가드).
        fun recordAndCapture(): IssueChangeGroup {
            val captured = slot<IssueChangeGroup>()
            every { repository.record(capture(captured)) } returns Unit

            sut.recordCommentEdited(
                issueId = issueId.value,
                issueKey = issueKey.value,
                actor = actor,
                commentId = commentId,
                beforeBody = beforeBody,
                afterBody = afterBody,
            )

            return captured.captured
        }

        it("field=comment 접두사 항목 1건을 가진 그룹을 기록한다") {
            val group = recordAndCapture()

            group.issueId shouldBe issueId.value
            group.issueKey shouldBe issueKey.value
            group.actorId shouldBe actor.value
            group.items shouldHaveSize 1
            group.items[0].field shouldStartWith IssueHistoryRecorder.COMMENT_FIELD_PREFIX
        }

        it("field 에 commentId 를 실어 어느 댓글인지 식별 가능하다") {
            val group = recordAndCapture()

            // Task 11 의 삭제 댓글 마스킹이 이 값으로 대상 댓글을 역추적한다.
            group.items[0].field shouldBe "${IssueHistoryRecorder.COMMENT_FIELD_PREFIX}$commentId"
            group.items[0].field.removePrefix(IssueHistoryRecorder.COMMENT_FIELD_PREFIX) shouldBe
                commentId.toString()
        }

        it("이전·이후 본문을 fromValue·toValue 에 담는다") {
            val group = recordAndCapture()

            group.items[0].fromValue shouldBe beforeBody
            group.items[0].toValue shouldBe afterBody
            // 본문은 라벨 해석 대상이 아니다 — resolver 미경유의 관측 가능한 증거.
            group.items[0].fromLabel shouldBe null
            group.items[0].toLabel shouldBe null
        }

        it("createdAt 을 null 로 두어 DB DEFAULT NOW 를 쓴다") {
            val group = recordAndCapture()

            // recordImported 와 달리 원본 시각 재생이 아니라 "지금" 이 정답이다.
            group.createdAt shouldBe null
        }
    }
})
