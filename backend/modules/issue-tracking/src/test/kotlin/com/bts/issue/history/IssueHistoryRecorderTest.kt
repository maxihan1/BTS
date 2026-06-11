// IssueHistoryRecorder 단위 테스트 — created/deleted/수정/no-op 경로 검증 (MockK)

package com.bts.issue.history

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

class IssueHistoryRecorderTest : DescribeSpec({

    val detector = mockk<IssueChangeDetector>()
    val resolver = mockk<IssueChangeLabelResolver>()
    val repository = mockk<IssueChangeHistoryRepository>()

    val sut = IssueHistoryRecorder(
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

    fun makeIssue(id: IssueId = issueId, key: IssueKey = issueKey): com.bts.issue.domain.Issue =
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
})
