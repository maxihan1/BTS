// 이슈 생성 · 복제가 최초(INITIAL) 전환 규칙을 타는지 — validator 거절은 생성 거절, post-action 이벤트는 발행 (WF-05-3)

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.TransitionEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.workflow.DomainEvent
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.TransitionPlan
import com.bts.shared.workflow.TransitionRequest
import com.bts.shared.workflow.TransitionResult
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Jira Create 전환의 Validator · Post function 과 같은 자리 — 생성이 insert **전에** 최초 전환 계획을 받는다.
 *
 * 앞 PR(project-workflow)이 엔진에 실행 경로를 열었고, 이 테스트는 issue-tracking 이 그 길을 실제로
 * 부르는지를 잰다. 부르지 않으면 규칙은 다시 「저장되지만 안 도는」 상태로 돌아간다.
 */
class IssueApplicationServiceCreationRulesTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>()
    val eventPublisher = mockk<IssueEventPublisher>(relaxed = true)
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val transitionEventPublisher = mockk<TransitionEventPublisher>(relaxed = true)

    val sut =
        IssueApplicationService(
            repo = repo,
            issueTypeRepository = issueTypeRepository,
            resolutionRepository = mockk(relaxed = true),
            eventPublisher = eventPublisher,
            permissionResolver = permissionResolver,
            workflowPort = workflowPort,
            workflowKeyResolver = workflowKeyResolver,
            userLookupPort = mockk(relaxed = true),
            componentRepository = mockk(relaxed = true),
            projectLeadRepository = mockk(relaxed = true),
            versionRepository = mockk(relaxed = true),
            clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC),
            historyRecorder = mockk(relaxed = true),
            transitionEventPublisher = transitionEventPublisher,
        )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "BTS"
    val newKey = IssueKey.of(projectKey, 7L)
    val taskType =
        IssueType(
            id = IssueTypeId(3L),
            key = IssueTypeKey("task"),
            name = "Task",
            description = null,
            iconName = null,
            isStandard = true,
            hierarchyLevel = 0,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            deletedAt = null,
        )
    val request = CreateIssueRequest(projectKey = projectKey, summary = "새 이슈", reporterId = actor, typeId = null)
    val webhook = DomainEvent("WebhookRequested", mapOf("issueKey" to newKey.value))

    beforeEach {
        clearMocks(repo, workflowPort, transitionEventPublisher)
        every { permissionResolver.hasPermission(any(), any(), any()) } returns true
        every { repo.incrementKeySequence(projectKey) } returns 7L
        every { repo.findProjectIdByKey(projectKey) } returns UUID.randomUUID()
        every { repo.insert(any(), any()) } answers { firstArg() }
        every { repo.insertComponents(any(), any()) } returns Unit
        every { issueTypeRepository.findByKey(IssueTypeKey("task")) } returns taskType
        every { issueTypeRepository.findById(IssueTypeId(3L)) } returns taskType
        every { workflowKeyResolver.resolveStart(ProjectKey.of(projectKey), IssueTypeKey("task")) } returns
            WorkflowStartState(workflowKey = "software-default", startStateKey = "open")
    }

    describe("createIssue") {
        it("최초 전환 계획을 생성 요청 · 시작 상태 · 제목으로 요청하고 post-action 이벤트를 발행한다") {
            val sent = slot<TransitionRequest>()
            every { workflowPort.plan(capture(sent)) } returns
                TransitionResult.Success(TransitionPlan("open", emptyList(), listOf(webhook)))

            val created = sut.createIssue(actor, request)

            sent.captured.creation shouldBe true
            sent.captured.toStateKey shouldBe "open"
            sent.captured.workflowKey shouldBe "software-default"
            sent.captured.issueKey shouldBe newKey.value
            sent.captured.issueFields["summary"] shouldBe "새 이슈"
            created.currentStateKey shouldBe "open"
            verify(exactly = 1) { transitionEventPublisher.publish(webhook) }
        }

        it("최초 전환 validator 가 거절하면 409 이고 이슈를 만들지 않는다") {
            every { workflowPort.plan(any()) } returns TransitionResult.ValidatorFailure("summary is required")

            shouldThrow<IssueTransitionNotAllowedException> { sut.createIssue(actor, request) }

            verify(exactly = 0) { repo.insert(any(), any()) }
            verify(exactly = 0) { transitionEventPublisher.publish(any()) }
        }
    }

    describe("cloneIssue") {
        val sourceKey = IssueKey.of(projectKey, 1L)
        val source =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = sourceKey,
                projectId = UUID.randomUUID(),
                typeId = IssueTypeId(3L),
                summary = "원본",
                reporterId = actor,
                currentStateKey = "done",
            )

        beforeEach {
            every { repo.findByKey(sourceKey) } returns source
            every { repo.findDescriptionHtml(sourceKey) } returns null
        }

        it("복제도 최초 전환을 타고 원본 상태가 아니라 시작 상태로 간다") {
            val sent = slot<TransitionRequest>()
            every { workflowPort.plan(capture(sent)) } returns
                TransitionResult.Success(TransitionPlan("open", emptyList(), listOf(webhook)))

            val clone = sut.cloneIssue(actor, sourceKey, CloneIssueRequest())

            sent.captured.creation shouldBe true
            sent.captured.issueKey shouldBe newKey.value
            clone.currentStateKey shouldBe "open"
            verify(exactly = 1) { transitionEventPublisher.publish(webhook) }
        }

        it("최초 전환 validator 가 거절하면 복제본을 만들지 않는다") {
            every { workflowPort.plan(any()) } returns TransitionResult.ValidatorFailure("summary is required")

            shouldThrow<IssueTransitionNotAllowedException> { sut.cloneIssue(actor, sourceKey, CloneIssueRequest()) }

            verify(exactly = 0) { repo.insert(any(), any()) }
        }
    }
})
