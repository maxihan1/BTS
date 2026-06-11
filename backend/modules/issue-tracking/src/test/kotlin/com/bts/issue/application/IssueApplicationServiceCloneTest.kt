// IssueApplicationService.cloneIssue 단위 테스트 — MockK, TDD RED 단계 (FR-IS-06 이슈 클론)

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.event.IssueCreated
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * IssueApplicationService.cloneIssue 단위 테스트.
 *
 * FR-IS-06 — 원본 이슈의 필드(summary/description/typeId/priority/labels/environment/impact/assignee)를
 * 복사한 새 이슈를 같은 프로젝트에 생성한다. key/reporter/상태/version/시각은 새로 시작한다.
 * 첨부/Watcher/댓글은 미구현이므로 복사 대상 아님 (ADR 2026-06-02-issue-clone-semantics).
 */
class IssueApplicationServiceCloneTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>()
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val projectLeadRepository = mockk<ProjectLeadRepository>(relaxed = true)
    val clock = Clock.fixed(Instant.parse("2026-06-02T00:00:00Z"), ZoneOffset.UTC)

    val sut =
        IssueApplicationService(
            repo = repo,
            issueTypeRepository = issueTypeRepository,
            resolutionRepository = resolutionRepository,
            eventPublisher = eventPublisher,
            permissionResolver = permissionResolver,
            workflowPort = workflowPort,
            workflowKeyResolver = workflowKeyResolver,
            userLookupPort = userLookupPort,
            componentRepository = mockk(relaxed = true),
            projectLeadRepository = projectLeadRepository,
            versionRepository = mockk(relaxed = true),
            clock = clock,
            historyRecorder = mockk(relaxed = true),
        )

    val actor = ActorId(UUID.randomUUID())
    val originalReporter = ActorId(UUID.randomUUID())
    val sourceAssignee = ActorId(UUID.randomUUID())
    val projectKey = "BTS"
    val sourceKey = IssueKey.of(projectKey, 1L)
    val sourceProjectId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val sourceTypeId = IssueTypeId(7L)

    /** 모든 복사 대상 필드가 채워진 원본 이슈. 상태는 초기상태가 아닌 in_progress. */
    val source =
        Issue.create(
            id = IssueId(UUID.randomUUID()),
            key = sourceKey,
            projectId = sourceProjectId,
            typeId = sourceTypeId,
            summary = "결제 버그",
            reporterId = originalReporter,
            currentStateKey = "in_progress",
            description = "재현 절차: 1. 결제 2. 오류",
            priority = 2,
            labels = listOf("payment", "urgent"),
            environment = "prod",
            impact = 1,
            assigneeId = sourceAssignee,
        )

    beforeEach {
        clearMocks(repo, issueTypeRepository, eventPublisher, permissionResolver, answers = false)
    }

    describe("cloneIssue") {

        context("권한이 있고 원본이 존재할 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value, IssuePermission.VIEW, IssueScope.Issue(sourceKey.value),
                    )
                } returns true
                every {
                    permissionResolver.hasPermission(
                        actor.value, IssuePermission.CREATE, IssueScope.Project(projectKey),
                    )
                } returns true
                every { repo.findByKey(sourceKey) } returns source
                every { repo.incrementKeySequence(projectKey) } returns 2L
                every { repo.insert(any()) } answers { firstArg() }
                every { eventPublisher.publish(any()) } returns Unit
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of(projectKey), null)
                } returns WorkflowStartState(workflowKey = "software-default", startStateKey = "open")
            }

            it("원본의 복사 대상 필드(summary/description/typeId/priority/labels/environment/impact)를 복사한다") {
                val slot: CapturingSlot<Issue> = slot()
                every { repo.insert(capture(slot)) } answers { slot.captured }

                sut.cloneIssue(actor, sourceKey, CloneIssueRequest())

                slot.captured.summary shouldBe "결제 버그"
                slot.captured.description shouldBe "재현 절차: 1. 결제 2. 오류"
                slot.captured.typeId shouldBe sourceTypeId
                slot.captured.priority shouldBe 2
                slot.captured.labels shouldBe listOf("payment", "urgent")
                slot.captured.environment shouldBe "prod"
                slot.captured.impact shouldBe 1
            }

            it("includeAssignee=true(기본)면 담당자를 복사한다") {
                val slot: CapturingSlot<Issue> = slot()
                every { repo.insert(capture(slot)) } answers { slot.captured }

                sut.cloneIssue(actor, sourceKey, CloneIssueRequest(includeAssignee = true))

                slot.captured.assigneeId shouldBe sourceAssignee
            }

            it("includeAssignee=false면 담당자를 비운다") {
                val slot: CapturingSlot<Issue> = slot()
                every { repo.insert(capture(slot)) } answers { slot.captured }

                sut.cloneIssue(actor, sourceKey, CloneIssueRequest(includeAssignee = false))

                slot.captured.assigneeId.shouldBeNull()
            }

            it("summaryOverride를 주면 클론본 제목을 덮어쓴다") {
                val slot: CapturingSlot<Issue> = slot()
                every { repo.insert(capture(slot)) } answers { slot.captured }

                sut.cloneIssue(actor, sourceKey, CloneIssueRequest(summaryOverride = "결제 버그 (재현 케이스)"))

                slot.captured.summary shouldBe "결제 버그 (재현 케이스)"
            }

            it("summaryOverride가 공백만이면 원본 summary로 폴백한다") {
                val slot: CapturingSlot<Issue> = slot()
                every { repo.insert(capture(slot)) } answers { slot.captured }

                sut.cloneIssue(actor, sourceKey, CloneIssueRequest(summaryOverride = "   "))

                slot.captured.summary shouldBe "결제 버그"
            }

            it("key(새 시퀀스)·reporter(actor)·상태(초기상태)·version=1·projectId(원본)로 새로 시작한다") {
                val slot: CapturingSlot<Issue> = slot()
                every { repo.insert(capture(slot)) } answers { slot.captured }

                sut.cloneIssue(actor, sourceKey, CloneIssueRequest())

                slot.captured.key shouldBe IssueKey.of(projectKey, 2L)
                slot.captured.reporterId shouldBe actor
                slot.captured.currentStateKey shouldBe "open"
                slot.captured.version shouldBe 1L
                slot.captured.projectId shouldBe sourceProjectId
            }

            it("IssueCreated 이벤트를 새 키로 발행한다") {
                sut.cloneIssue(actor, sourceKey, CloneIssueRequest())

                verify {
                    eventPublisher.publish(
                        match {
                            it is IssueCreated &&
                                it.projectKey == projectKey &&
                                it.issueKey == IssueKey.of(projectKey, 2L)
                        },
                    )
                }
            }

            it("원본 typeId를 활성 재검증 없이 그대로 복사한다 (EC-8)") {
                val slot: CapturingSlot<Issue> = slot()
                every { repo.insert(capture(slot)) } answers { slot.captured }

                sut.cloneIssue(actor, sourceKey, CloneIssueRequest())

                slot.captured.typeId shouldBe sourceTypeId
                verify { issueTypeRepository wasNot Called }
            }

            it("권한체크 → findByKey → incrementKeySequence → insert → publish 순서로 호출한다") {
                sut.cloneIssue(actor, sourceKey, CloneIssueRequest())

                verifyOrder {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(sourceKey.value),
                    )
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.CREATE,
                        IssueScope.Project(projectKey),
                    )
                    repo.findByKey(sourceKey)
                    repo.incrementKeySequence(projectKey)
                    repo.insert(any())
                    eventPublisher.publish(any())
                }
            }
        }

        context("원본이 존재하지 않을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value, IssuePermission.VIEW, IssueScope.Issue(sourceKey.value),
                    )
                } returns true
                every {
                    permissionResolver.hasPermission(
                        actor.value, IssuePermission.CREATE, IssueScope.Project(projectKey),
                    )
                } returns true
                every { repo.findByKey(sourceKey) } returns null
            }

            it("IssueNotFoundException 을 던진다") {
                shouldThrow<IssueNotFoundException> {
                    sut.cloneIssue(actor, sourceKey, CloneIssueRequest())
                }
            }

            it("key_sequence 증가 및 insert 가 일어나지 않는다") {
                runCatching { sut.cloneIssue(actor, sourceKey, CloneIssueRequest()) }

                verify(exactly = 0) { repo.incrementKeySequence(any()) }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("원본 VIEW 권한이 없을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value, IssuePermission.VIEW, IssueScope.Issue(sourceKey.value),
                    )
                } returns false
            }

            it("IssueNotFoundException 을 던지고 어떤 부수효과도 없다 (존재 숨김)") {
                shouldThrow<IssueNotFoundException> {
                    sut.cloneIssue(actor, sourceKey, CloneIssueRequest())
                }
                verify(exactly = 0) { repo.findByKey(sourceKey) }
                verify(exactly = 0) { repo.incrementKeySequence(any()) }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("EC6 — 클론은 프로젝트 리드 폴백 미적용 (FR-CM-04 Task 5 회귀)") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value, IssuePermission.VIEW, IssueScope.Issue(sourceKey.value),
                    )
                } returns true
                every {
                    permissionResolver.hasPermission(
                        actor.value, IssuePermission.CREATE, IssueScope.Project(projectKey),
                    )
                } returns true
                every { repo.findByKey(sourceKey) } returns source
                every { repo.incrementKeySequence(projectKey) } returns 2L
                every { repo.insert(any()) } answers { firstArg() }
                every { eventPublisher.publish(any()) } returns Unit
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of(projectKey), null)
                } returns WorkflowStartState(workflowKey = "software-default", startStateKey = "open")
            }

            it("클론 시 projectLeadRepository.findLeadUserId 를 호출하지 않는다") {
                sut.cloneIssue(actor, sourceKey, CloneIssueRequest())

                verify(exactly = 0) { projectLeadRepository.findLeadUserId(any()) }
            }
        }

        context("대상 프로젝트 CREATE 권한이 없을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value, IssuePermission.VIEW, IssueScope.Issue(sourceKey.value),
                    )
                } returns true
                every {
                    permissionResolver.hasPermission(
                        actor.value, IssuePermission.CREATE, IssueScope.Project(projectKey),
                    )
                } returns false
            }

            it("IssueAccessDeniedException 을 던지고 key_sequence 를 증가시키지 않는다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.cloneIssue(actor, sourceKey, CloneIssueRequest())
                }
                verify(exactly = 0) { repo.incrementKeySequence(any()) }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }
    }
})
