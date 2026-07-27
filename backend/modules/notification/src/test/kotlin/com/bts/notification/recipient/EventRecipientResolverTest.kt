// EventRecipientResolver 단위 테스트 — 역할별 수신자 해석, actor 제외, 중복 제거 전체 커버

package com.bts.notification.recipient

import com.bts.notification.application.PolicyMatch
import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.RecipientRole
import com.bts.shared.issue.IssueRecipientLookupPort
import com.bts.shared.issue.IssueRecipients
import com.bts.shared.issue.ProjectRecipientLookupPort
import com.bts.shared.issue.ProjectRecipients
import com.bts.shared.permission.IssueVisibilityPort
import io.kotest.assertions.throwables.shouldThrow
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
    val projectPort: ProjectRecipientLookupPort = mockk()
    val visibilityPort: IssueVisibilityPort = mockk()
    val resolver = EventRecipientResolver(port, projectPort, visibilityPort)

    val actor = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    val reporter = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    val assignee = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
    val mentionedA = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
    val mentionedB = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
    val watcherA = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val watcherB = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val componentLead = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val previousAssignee = UUID.fromString("44444444-4444-4444-4444-444444444444")
    val memberA = UUID.fromString("55555555-5555-5555-5555-555555555555")
    val memberB = UUID.fromString("66666666-6666-6666-6666-666666666666")
    val adminA = UUID.fromString("77777777-7777-7777-7777-777777777777")
    val issueKey = "ATLAS-42"
    val projectKey = "ATLAS"
    val fixedNow: Instant = Instant.parse("2026-06-12T00:00:00Z")

    @Suppress("LongParameterList")
    fun buildEvent(
        eventType: NotificationEventType = NotificationEventType.ISSUE_CREATED,
        issueKey: String? = "ATLAS-42",
        projectKey: String? = "ATLAS",
        mentionedUserIds: List<UUID> = emptyList(),
        commentAuthorId: UUID? = null,
        reporterId: UUID? = reporter,
        actorId: UUID? = actor,
        occurredAt: Instant = fixedNow,
    ) = NotificationSourceEvent(
        eventType = eventType,
        issueKey = issueKey,
        projectKey = projectKey,
        mentionedUserIds = mentionedUserIds,
        commentAuthorId = commentAuthorId,
        reporterId = reporterId,
        actorId = actorId,
        occurredAt = occurredAt,
    )

    beforeEach {
        clearMocks(port, projectPort, visibilityPort)
        // 기본값: 모든 후보가 이슈를 볼 수 있다(전원 통과). visibility 전용 테스트에서 개별 override.
        every { visibilityPort.filterVisibleUserIds(any(), any()) } answers { secondArg() }
    }

    describe("COMMENT_AUTHOR 역할 해석 (FR-CO-02 모더레이션 통지)") {
        /**
         * ★포트 조회 없이 **이벤트 페이로드**에서 해석한다는 것이 이 역할의 핵심이다.
         * 알림 BC 가 댓글 저작자를 조회하려면 notification → issue-tracking 방향의 신규 cross-BC 포트가
         * 필요한데, 발행 측이 실어 보내면 그 의존이 생기지 않는다(MENTIONED 와 같은 방식).
         */
        it("commentAuthorId 를 수신자로 해석한다 — 포트 조회 없음") {
            val author = UUID.randomUUID()
            val event =
                buildEvent(
                    eventType = NotificationEventType.ISSUE_COMMENT_DELETED,
                    commentAuthorId = author,
                )
            val matches = listOf(PolicyMatch(RecipientRole.COMMENT_AUTHOR, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result.first().userId shouldBe author
            result.first().channel shouldBe Channel.IN_APP
            // 판별자 — 이슈 수신자 포트를 부르지 않는다(cross-BC 의존 부재의 증거).
            verify(exactly = 0) { port.findRecipients(any()) }
        }

        it("commentAuthorId 가 없으면 빈 목록을 반환한다 (다른 이벤트 타입 안전)") {
            val event =
                buildEvent(
                    eventType = NotificationEventType.ISSUE_COMMENT_DELETED,
                    commentAuthorId = null,
                )
            val matches = listOf(PolicyMatch(RecipientRole.COMMENT_AUTHOR, Channel.IN_APP))

            resolver.resolve(event, matches) shouldHaveSize 0
        }
    }

    describe("MENTIONED 역할 해석") {
        it("mentionedUserIds 각각에 대해 ResolvedRecipient를 생성한다") {
            val event =
                buildEvent(
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
            val event =
                buildEvent(
                    eventType = NotificationEventType.ISSUE_MENTIONED,
                    mentionedUserIds = emptyList(),
                )
            val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }

        it("멘션된 사용자 중 actorId와 동일한 경우 제외한다") {
            val event =
                buildEvent(
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
            val matches =
                listOf(
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

    describe("WATCHER 역할 해석") {
        it("watcherIds 다중 사용자에 대해 ResolvedRecipient를 생성한다") {
            val event = buildEvent(issueKey = issueKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.WATCHER, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(
                    reporterId = null,
                    assigneeId = null,
                    watcherIds = listOf(watcherA, watcherB),
                )

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 2
            result.map { it.userId } shouldContainExactlyInAnyOrder listOf(watcherA, watcherB)
            result.forEach { it.channel shouldBe Channel.IN_APP }
        }

        it("watcherIds가 빈 목록이면 빈 결과를 반환한다") {
            val event = buildEvent(issueKey = issueKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.WATCHER, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(reporterId = null, assigneeId = null, watcherIds = emptyList())

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }

        it("watcher 중 actorId와 동일한 사용자는 제외한다") {
            val event = buildEvent(issueKey = issueKey, actorId = actor)
            val matches = listOf(PolicyMatch(RecipientRole.WATCHER, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(
                    reporterId = null,
                    assigneeId = null,
                    watcherIds = listOf(actor, watcherA),
                )

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe watcherA
        }

        it("issueKey가 null이면 포트를 호출하지 않고 빈 목록을 반환한다") {
            val event = buildEvent(issueKey = null, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.WATCHER, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
            verify(exactly = 0) { port.findRecipients(any()) }
        }
    }

    describe("COMPONENT_LEAD 역할 해석") {
        it("componentLeadIds 다중 사용자에 대해 ResolvedRecipient를 생성한다") {
            val event = buildEvent(issueKey = issueKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.COMPONENT_LEAD, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(
                    reporterId = null,
                    assigneeId = null,
                    componentLeadIds = listOf(componentLead, watcherA),
                )

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 2
            result.map { it.userId } shouldContainExactlyInAnyOrder listOf(componentLead, watcherA)
        }

        it("componentLeadIds가 빈 목록(lead 없음)이면 빈 결과를 반환한다") {
            val event = buildEvent(issueKey = issueKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.COMPONENT_LEAD, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(reporterId = null, assigneeId = null, componentLeadIds = emptyList())

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }

        it("componentLead 중 actorId와 동일한 사용자는 제외한다") {
            val event = buildEvent(issueKey = issueKey, actorId = actor)
            val matches = listOf(PolicyMatch(RecipientRole.COMPONENT_LEAD, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(
                    reporterId = null,
                    assigneeId = null,
                    componentLeadIds = listOf(actor, componentLead),
                )

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe componentLead
        }
    }

    describe("PREVIOUS_ASSIGNEE 역할 해석") {
        it("previousAssigneeId가 있으면 단수 수신자를 반환한다") {
            val event = buildEvent(issueKey = issueKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.PREVIOUS_ASSIGNEE, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(
                    reporterId = null,
                    assigneeId = null,
                    previousAssigneeId = previousAssignee,
                )

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe previousAssignee
            result[0].channel shouldBe Channel.IN_APP
        }

        it("previousAssigneeId가 null(이력 없음)이면 빈 목록을 반환한다") {
            val event = buildEvent(issueKey = issueKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.PREVIOUS_ASSIGNEE, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(reporterId = null, assigneeId = null, previousAssigneeId = null)

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }

        it("previousAssignee가 actorId와 동일하면 제외한다") {
            val event = buildEvent(issueKey = issueKey, actorId = actor)
            val matches = listOf(PolicyMatch(RecipientRole.PREVIOUS_ASSIGNEE, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(
                    reporterId = null,
                    assigneeId = null,
                    previousAssigneeId = actor,
                )

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }
    }

    describe("PROJECT_MEMBER 역할 해석") {
        it("projectKey가 있으면 memberIds 전체를 수신자로 반환한다") {
            val event = buildEvent(projectKey = projectKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.PROJECT_MEMBER, Channel.IN_APP))

            every { projectPort.findProjectRecipients(projectKey) } returns
                ProjectRecipients(memberIds = listOf(memberA, memberB), adminIds = emptyList())

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 2
            result.map { it.userId } shouldContainExactlyInAnyOrder listOf(memberA, memberB)
            result.forEach { it.channel shouldBe Channel.IN_APP }
        }

        it("memberIds가 빈 목록이면 빈 결과를 반환한다") {
            val event = buildEvent(projectKey = projectKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.PROJECT_MEMBER, Channel.IN_APP))

            every { projectPort.findProjectRecipients(projectKey) } returns
                ProjectRecipients(memberIds = emptyList(), adminIds = emptyList())

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }

        it("projectKey가 null이면 프로젝트 포트를 호출하지 않고 빈 목록을 반환한다") {
            val event = buildEvent(projectKey = null, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.PROJECT_MEMBER, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
            verify(exactly = 0) { projectPort.findProjectRecipients(any()) }
        }

        it("member 중 actorId와 동일한 사용자는 제외한다") {
            val event = buildEvent(projectKey = projectKey, actorId = actor)
            val matches = listOf(PolicyMatch(RecipientRole.PROJECT_MEMBER, Channel.IN_APP))

            every { projectPort.findProjectRecipients(projectKey) } returns
                ProjectRecipients(memberIds = listOf(actor, memberA), adminIds = emptyList())

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe memberA
        }
    }

    describe("PROJECT_ADMIN 역할 해석") {
        it("projectKey가 있으면 adminIds만 수신자로 반환한다") {
            val event = buildEvent(projectKey = projectKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.PROJECT_ADMIN, Channel.EMAIL))

            every { projectPort.findProjectRecipients(projectKey) } returns
                ProjectRecipients(memberIds = listOf(memberA, memberB), adminIds = listOf(adminA))

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe adminA
            result[0].channel shouldBe Channel.EMAIL
        }

        it("adminIds가 빈 목록이면 빈 결과를 반환한다") {
            val event = buildEvent(projectKey = projectKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.PROJECT_ADMIN, Channel.EMAIL))

            every { projectPort.findProjectRecipients(projectKey) } returns
                ProjectRecipients(memberIds = listOf(memberA), adminIds = emptyList())

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }

        it("projectKey가 null이면 프로젝트 포트를 호출하지 않고 빈 목록을 반환한다") {
            val event = buildEvent(projectKey = null, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.PROJECT_ADMIN, Channel.EMAIL))

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
            verify(exactly = 0) { projectPort.findProjectRecipients(any()) }
        }

        it("admin 중 actorId와 동일한 사용자는 제외한다") {
            val event = buildEvent(projectKey = projectKey, actorId = actor)
            val matches = listOf(PolicyMatch(RecipientRole.PROJECT_ADMIN, Channel.EMAIL))

            every { projectPort.findProjectRecipients(projectKey) } returns
                ProjectRecipients(memberIds = emptyList(), adminIds = listOf(actor, adminA))

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe adminA
        }
    }

    describe("PROJECT_MEMBER + PROJECT_ADMIN 동시 요청 시 N+1 회피") {
        it("프로젝트 포트를 1회만 호출하고 두 역할 모두 해석한다") {
            val event = buildEvent(projectKey = projectKey, actorId = null)
            val matches =
                listOf(
                    PolicyMatch(RecipientRole.PROJECT_MEMBER, Channel.IN_APP),
                    PolicyMatch(RecipientRole.PROJECT_ADMIN, Channel.IN_APP),
                )

            every { projectPort.findProjectRecipients(projectKey) } returns
                ProjectRecipients(memberIds = listOf(memberA, memberB), adminIds = listOf(adminA))

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 3
            result.map { it.userId } shouldContainExactlyInAnyOrder listOf(memberA, memberB, adminA)

            // N+1 방지 — 1회만 호출
            verify(exactly = 1) { projectPort.findProjectRecipients(projectKey) }
        }
    }

    describe("RULE_OWNER 역할 — 명시 skip") {
        it("RULE_OWNER는 빈 목록을 반환한다") {
            val event = buildEvent()
            val matches = listOf(PolicyMatch(RecipientRole.RULE_OWNER, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result.shouldBeEmpty()
        }
    }

    describe("이슈 기반 역할 N+1 회피 — WATCHER + COMPONENT_LEAD + PREVIOUS_ASSIGNEE") {
        it("이슈 포트를 1회만 호출하고 세 역할 모두 해석한다") {
            val event = buildEvent(issueKey = issueKey, actorId = null)
            val matches =
                listOf(
                    PolicyMatch(RecipientRole.WATCHER, Channel.IN_APP),
                    PolicyMatch(RecipientRole.COMPONENT_LEAD, Channel.IN_APP),
                    PolicyMatch(RecipientRole.PREVIOUS_ASSIGNEE, Channel.IN_APP),
                )

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(
                    reporterId = null,
                    assigneeId = null,
                    watcherIds = listOf(watcherA),
                    componentLeadIds = listOf(componentLead),
                    previousAssigneeId = previousAssignee,
                )

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 3
            result.map { it.userId } shouldContainExactlyInAnyOrder
                listOf(watcherA, componentLead, previousAssignee)

            // N+1 방지 — 1회만 호출
            verify(exactly = 1) { port.findRecipients(issueKey) }
        }
    }

    describe("중복 제거 (dedup)") {
        it("동일 (userId, channel) 쌍은 1개로 합친다") {
            // reporter와 assignee가 동일 사용자인 경우
            val event = buildEvent(reporterId = null, issueKey = issueKey, actorId = null)
            val matches =
                listOf(
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
            val matches =
                listOf(
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

    describe("visibility 필터 — 보안수준 제한 이슈 누출 차단 (FR7 / C-S2 / C3)") {
        it("(a) 보안수준 제한 이슈에서 권한 없는 watcher를 제외한다") {
            // watcherA 는 볼 수 있고, watcherB 는 보안등급 미달 → 제외
            val event = buildEvent(issueKey = issueKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.WATCHER, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(
                    reporterId = null,
                    assigneeId = null,
                    watcherIds = listOf(watcherA, watcherB),
                )
            every { visibilityPort.filterVisibleUserIds(issueKey, setOf(watcherA, watcherB)) } returns
                setOf(watcherA)

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe watcherA
            verify(exactly = 1) { visibilityPort.filterVisibleUserIds(issueKey, setOf(watcherA, watcherB)) }
        }

        it("(b) 권한 있는 REPORTER/ASSIGNEE 수신자는 visibility 필터를 통과한다") {
            val event = buildEvent(reporterId = null, issueKey = issueKey, actorId = null)
            val matches =
                listOf(
                    PolicyMatch(RecipientRole.REPORTER, Channel.IN_APP),
                    PolicyMatch(RecipientRole.ASSIGNEE, Channel.IN_APP),
                )

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(reporterId = reporter, assigneeId = assignee)
            every { visibilityPort.filterVisibleUserIds(issueKey, setOf(reporter, assignee)) } returns
                setOf(reporter, assignee)

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 2
            result.map { it.userId } shouldContainExactlyInAnyOrder listOf(reporter, assignee)
        }

        it("(c) C-S2 — 보안수준 제한 이슈에서 권한 없는 멘션 대상은 제외한다") {
            // 멘션도 누출 차단 우선: mentionedB 는 이슈를 볼 수 없으므로 제외
            val event =
                buildEvent(
                    eventType = NotificationEventType.ISSUE_MENTIONED,
                    issueKey = issueKey,
                    mentionedUserIds = listOf(mentionedA, mentionedB),
                    actorId = null,
                )
            val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))

            every { visibilityPort.filterVisibleUserIds(issueKey, setOf(mentionedA, mentionedB)) } returns
                setOf(mentionedA)

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe mentionedA
        }

        it("(d) issueKey가 null인 이벤트는 visibility 필터 비대상 — 전원 통과") {
            val event =
                buildEvent(
                    eventType = NotificationEventType.ISSUE_MENTIONED,
                    issueKey = null,
                    mentionedUserIds = listOf(mentionedA, mentionedB),
                    actorId = null,
                )
            val matches = listOf(PolicyMatch(RecipientRole.MENTIONED, Channel.IN_APP))

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 2
            result.map { it.userId } shouldContainExactlyInAnyOrder listOf(mentionedA, mentionedB)
            // issueKey 없으면 보안 판정 대상이 아니므로 포트 미호출
            verify(exactly = 0) { visibilityPort.filterVisibleUserIds(any(), any()) }
        }

        it("(e) actor 제외 + dedup 이후의 distinct 집합으로 visibility를 1회 적용한다") {
            // reporter == assignee 로 dedup, actor 는 watcher 로도 들어오지만 actor 제외.
            // visibility 포트에는 dedup·actor제외 이후의 집합 {reporter} 만 전달되어야 한다.
            val event = buildEvent(reporterId = null, issueKey = issueKey, actorId = actor)
            val matches =
                listOf(
                    PolicyMatch(RecipientRole.REPORTER, Channel.IN_APP),
                    PolicyMatch(RecipientRole.ASSIGNEE, Channel.IN_APP),
                    PolicyMatch(RecipientRole.WATCHER, Channel.IN_APP),
                )

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(
                    reporterId = reporter,
                    assigneeId = reporter,
                    watcherIds = listOf(actor),
                )
            every { visibilityPort.filterVisibleUserIds(issueKey, setOf(reporter)) } returns setOf(reporter)

            val result = resolver.resolve(event, matches)

            result shouldHaveSize 1
            result[0].userId shouldBe reporter
            // dedup·actor제외 이후의 distinct 집합 {reporter} 으로 정확히 1회 호출
            verify(exactly = 1) { visibilityPort.filterVisibleUserIds(issueKey, setOf(reporter)) }
        }

        it("(f) C3/B-SEC-3 — visibility 포트가 예외를 던지면 resolve가 예외를 전파한다(fail-closed)") {
            // 누출보다 알림 지연이 안전 — 빈 목록으로 삼키지 않고 예외 전파(worker 메시지 보류·재전달)
            val event = buildEvent(issueKey = issueKey, actorId = null)
            val matches = listOf(PolicyMatch(RecipientRole.WATCHER, Channel.IN_APP))

            every { port.findRecipients(issueKey) } returns
                IssueRecipients(reporterId = null, assigneeId = null, watcherIds = listOf(watcherA))
            every { visibilityPort.filterVisibleUserIds(issueKey, setOf(watcherA)) } throws
                IllegalStateException("visibility 판정 일시 장애")

            shouldThrow<IllegalStateException> {
                resolver.resolve(event, matches)
            }
        }
    }
})
