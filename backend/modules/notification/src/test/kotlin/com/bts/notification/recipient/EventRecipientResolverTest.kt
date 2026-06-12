// EventRecipientResolver 단위 테스트 — 역할별 수신자 해석, actor 제외, 중복 제거 전체 커버

package com.bts.notification.recipient

import com.bts.notification.application.PolicyMatch
import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.RecipientRole
import com.bts.shared.issue.IssueRecipientLookupPort
import com.bts.shared.issue.IssueRecipients
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

class EventRecipientResolverTest : DescribeSpec({

    val port: IssueRecipientLookupPort = mockk()
    val resolver = EventRecipientResolver(port)

    val actor = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    val reporter = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    val assignee = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
    val mentionedA = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
    val mentionedB = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
    val issueKey = "ATLAS-42"
    val fixedNow: Instant = Instant.parse("2026-06-12T00:00:00Z")

    fun buildEvent(
        eventType: NotificationEventType = NotificationEventType.ISSUE_CREATED,
        issueKey: String? = "ATLAS-42",
        projectKey: String? = "ATLAS",
        mentionedUserIds: List<UUID> = emptyList(),
        reporterId: UUID? = reporter,
        actorId: UUID? = actor,
        occurredAt: Instant = fixedNow,
    ) = NotificationSourceEvent(
        eventType = eventType,
        issueKey = issueKey,
        projectKey = projectKey,
        mentionedUserIds = mentionedUserIds,
        reporterId = reporterId,
        actorId = actorId,
        occurredAt = occurredAt,
    )

    beforeEach { clearMocks(port) }

    describe("MENTIONED 역할 해석") {
        it("mentionedUserIds 각각에 대해 ResolvedRecipient를 생성한다") {
            val event = buildEvent(
                eventType = NotificationEventType.ISSUE_MENTIONED,
                mentionedUserIds = listOf(mentionedA, mentionedB),
                actorId = null,
            )
            val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 2
            result.map { it.userId } shouldContainExactlyInAnyOrder listOf(mentionedA, mentionedB)
            result.forEach { it.channel shouldBe Channel.IN_APP }
        }

        it("멘션 목록이 비어 있으면 빈 목록을 반환한다") {
            val event = buildEvent(
                eventType = NotificationEventType.ISSUE_MENTIONED,
                mentionedUserIds = emptyList(),
            )
            val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }

        it("멘션된 사용자 중 actorId와 동일한 경우 제외한다") {
            val event = buildEvent(
                eventType = NotificationEventType.ISSUE_MENTIONED,
                mentionedUserIds = listOf(actor, mentionedA),
                actorId = actor,
            )
            val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe mentionedA
        }
    }

    describe("REPORTER 역할 해석") {
        it("event.reporterId가 있으면 포트를 호출하지 않고 반환한다") {
            val event = buildEvent(reporterId = reporter, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.REPORTER, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe reporter
            result[0].channel shouldBe Channel.IN_APP
            // 포트 호출 없어야 한다
            verify(exactly = 0) { port.findRecipients(any()) }
        }

        it("event.reporterId가 null이고 issueKey가 있으면 포트로 조회한다") {
            val event = buildEvent(reporterId = null, issueKey = issueKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.REPORTER, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns IssueRecipients(reporterId = reporter, assigneeId = null)

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe reporter
        }

        it("event.reporterId가 null이고 issueKey도 null이면 빈 목록을 반환한다") {
            val event = buildEvent(reporterId = null, issueKey = null, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.REPORTER, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
            verify(exactly = 0) { port.findRecipients(any()) }
        }

        it("reporter가 actorId와 동일하면 제외한다") {
            val event = buildEvent(reporterId = actor, actorId = actor)
            val matches = listOf(PolicyMatch(RecipientRole.REPORTER, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }
    }

    describe("ASSIGNEE 역할 해석") {
        it("issueKey가 있으면 포트로 담당자를 조회한다") {
            val event = buildEvent(issueKey = issueKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.ASSIGNEE, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns IssueRecipients(reporterId = null, assigneeId = assignee)

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe assignee
        }

        it("포트가 assigneeId=null을 반환하면 빈 목록을 반환한다") {
            val event = buildEvent(issueKey = issueKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.ASSIGNEE, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns IssueRecipients(reporterId = null, assigneeId = null)

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }

        it("issueKey가 null이면 포트를 호출하지 않고 빈 목록을 반환한다") {
            val event = buildEvent(issueKey = null, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.ASSIGNEE, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
            verify(exactly = 0) { port.findRecipients(any()) }
        }

        it("assignee가 actorId와 동일하면 제외한다") {
            val event = buildEvent(issueKey = issueKey, actorId = actor)
            val matches = listOf(PolicyMatch(RecipientRole.ASSIGNEE, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns IssueRecipients(reporterId = null, assigneeId = actor)

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }
    }

    describe("REPORTER + ASSIGNEE 동시 요청 시 N+1 회피") {
        it("포트를 1회만 호출하고 두 역할 모두 해석한다") {
            val event = buildEvent(reporterId = null, issueKey = issueKey, actorId = null)
            val matches = listOf(
                PolicyMatch(RecipientRole.REPORTER, Channel.IN_APP),
                PolicyMatch(RecipientRole.ASSIGNEE, Channel.IN_APP),
            )

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(reporterId = reporter, assigneeId = assignee)

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 2
            result.map { it.userId } shouldContainExactlyInAnyOrder listOf(reporter, assignee)

            // N+1 방지 — 1회만 호출
            verify(exactly = 1) { port.findRecipients(issueKey) }
        }
    }

    describe("미지원 역할 (FR-NT-03 대상) — WATCHER, PROJECT_MEMBER 등") {
        it("WATCHER 역할은 빈 목록으로 skip한다") {
            val event = buildEvent()
            val matches = listOf(PolicyMatch(RecipientRole.WATCHER, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }

        it("PROJECT_ADMIN 역할은 빈 목록으로 skip한다") {
            val event = buildEvent()
            val matches = listOf(PolicyMatch(RecipientRole.PROJECT_ADMIN, Channel.EMAIL))

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }
    }

    describe("중복 제거 (dedup)") {
        it("동일 (userId, channel) 쌍은 1개로 합친다") {
            // reporter와 assignee가 동일 사용자인 경우
            val event = buildEvent(reporterId = null, issueKey = issueKey, actorId = null)
            val matches = listOf(
                PolicyMatch(RecipientRole.REPORTER, Channel.IN_APP),
                PolicyMatch(RecipientRole.ASSIGNEE, Channel.IN_APP),
            )

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(reporterId = reporter, assigneeId = reporter)

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe reporter
            result[0].channel shouldBe Channel.IN_APP
        }

        it("채널이 다르면 별도 항목으로 유지한다") {
            val event = buildEvent(reporterId = reporter, actorId = null)
            val matches = listOf(
                PolicyMatch(RecipientRole.REPORTER, Channel.IN_APP),
                PolicyMatch(RecipientRole.REPORTER, Channel.EMAIL),
            )

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 2
            result.map { it.channel } shouldContainExactlyInAnyOrder listOf(Channel.IN_APP, Channel.EMAIL)
        }
    }

    describe("matches가 빈 목록이면") {
        it("빈 목록을 반환한다") {
            val event = buildEvent()
            val result = resolver.resolve(event, emptyList())

            result.shouldBeEmpty()
        }
    }
})
