// IssueApplicationService.createIssue 단위 테스트 — MockK, TDD RED 단계 (typeId + task fallback 포함)

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.event.IssueCreated
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IssueApplicationServiceCreateTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>()
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val clock = Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneOffset.UTC)

    val sut =
        IssueApplicationService(
            repo = repo,
            issueTypeRepository = issueTypeRepository,
            eventPublisher = eventPublisher,
            permissionResolver = permissionResolver,
            workflowPort = workflowPort,
            workflowKeyResolver = workflowKeyResolver,
            userLookupPort = userLookupPort,
            clock = clock,
        )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "BTS"

    /** task fallback 에 사용할 표준 task 타입 stub. */
    val taskTypeId = IssueTypeId(3L)
    val taskIssueType =
        IssueType(
            id = taskTypeId,
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

    val request =
        CreateIssueRequest(
            projectKey = projectKey,
            summary = "Test summary",
            reporterId = actor,
            typeId = null,
        )

    beforeEach {
        clearMocks(repo, issueTypeRepository, eventPublisher, permissionResolver, answers = false)
    }

    describe("createIssue") {

        context("권한이 있을 때") {
            val fixedProjectId = UUID.fromString("00000000-0000-0000-0000-000000000001")

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.CREATE,
                        IssueScope.Project(projectKey),
                    )
                } returns true
                every { repo.incrementKeySequence(projectKey) } returns 1L
                every { repo.findProjectIdByKey(projectKey) } returns fixedProjectId
                every { repo.insert(any()) } answers { firstArg() }
                every { eventPublisher.publish(any()) } returns Unit
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of(projectKey), null)
                } returns WorkflowStartState(workflowKey = "software-default", startStateKey = "open")
                every { issueTypeRepository.findByKey(IssueTypeKey("task")) } returns taskIssueType
            }

            it("권한 체크 → incrementKeySequence → insert → publish 순서로 호출한다") {
                sut.createIssue(actor, request)

                verifyOrder {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.CREATE,
                        IssueScope.Project(projectKey),
                    )
                    repo.incrementKeySequence(projectKey)
                    repo.insert(any())
                    eventPublisher.publish(any())
                }
            }

            it("반환된 이슈의 키가 projectKey-seq 형식이다") {
                val result = sut.createIssue(actor, request)

                result.key shouldBe IssueKey.of(projectKey, 1L)
            }

            it("IssueCreated 이벤트가 발행된다") {
                sut.createIssue(actor, request)

                verify {
                    eventPublisher.publish(match { it is IssueCreated && it.projectKey == projectKey })
                }
            }

            // A-1: CRITICAL-1 — createIssue 가 findProjectIdByKey 로 조회한 projectId 를 사용하는지 검증
            it("조회한 projectId 가 repo.insert 의 Issue.projectId 와 일치한다") {
                val issueSlot: CapturingSlot<com.bts.issue.domain.Issue> = slot()
                every { repo.insert(capture(issueSlot)) } answers { issueSlot.captured }

                sut.createIssue(actor, request)

                issueSlot.captured.projectId shouldBe fixedProjectId
            }

            // C-1: WorkflowKeyResolver 호출 계약 — resolveStart 가 실제로 호출되었는지
            it("create uses startStateKey from WorkflowKeyResolver (not hardcoded OPEN)") {
                val issueSlot: CapturingSlot<com.bts.issue.domain.Issue> = slot()
                every { repo.insert(capture(issueSlot)) } answers { issueSlot.captured }

                sut.createIssue(actor, request)

                verify { workflowKeyResolver.resolveStart(ProjectKey.of(projectKey), null) }
                issueSlot.captured.currentStateKey shouldBe "open"
            }

            // C-2: startStateKey = "open" (소문자) 로 Issue 가 생성되는지
            it("create assigns currentStateKey = open (lowercase) for software-default workflow") {
                val issueSlot: CapturingSlot<com.bts.issue.domain.Issue> = slot()
                every { repo.insert(capture(issueSlot)) } answers { issueSlot.captured }

                sut.createIssue(actor, request)

                issueSlot.captured.currentStateKey shouldBe "open"
            }

            // T7-A: typeId null → task fallback — FR-6 모든 이슈는 타입 보유
            it("typeId 가 null 이면 task 타입으로 fallback 해 Issue.typeId 에 task 타입 id 가 저장된다") {
                val issueSlot: CapturingSlot<com.bts.issue.domain.Issue> = slot()
                every { repo.insert(capture(issueSlot)) } answers { issueSlot.captured }

                val requestWithoutType =
                    CreateIssueRequest(
                        projectKey = projectKey,
                        summary = "Test summary",
                        reporterId = actor,
                        typeId = null,
                    )
                sut.createIssue(actor, requestWithoutType)

                issueSlot.captured.typeId shouldBe taskTypeId
            }

            // T7-B: typeId 지정 → 지정된 typeId 가 그대로 사용된다
            it("typeId 를 지정하면 지정된 IssueTypeId 가 Issue.typeId 에 저장된다") {
                val specifiedTypeId = IssueTypeId(7L)
                val specifiedIssueType =
                    IssueType(
                        id = specifiedTypeId,
                        key = IssueTypeKey("bug"),
                        name = "Bug",
                        description = null,
                        iconName = null,
                        isStandard = true,
                        hierarchyLevel = 0,
                        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                        deletedAt = null,
                    )
                every { issueTypeRepository.findById(specifiedTypeId) } returns specifiedIssueType

                val issueSlot: CapturingSlot<com.bts.issue.domain.Issue> = slot()
                every { repo.insert(capture(issueSlot)) } answers { issueSlot.captured }

                val requestWithType =
                    CreateIssueRequest(
                        projectKey = projectKey,
                        summary = "Test summary",
                        reporterId = actor,
                        typeId = specifiedTypeId,
                    )
                sut.createIssue(actor, requestWithType)

                issueSlot.captured.typeId shouldBe specifiedTypeId
            }

            // T7-C: typeId 지정 + 해당 타입이 존재하지 않으면 예외
            it("typeId 를 지정했으나 해당 이슈 타입이 없으면 IssueTypeNotFoundException 을 던진다") {
                val nonExistentTypeId = IssueTypeId(999L)
                every { issueTypeRepository.findById(nonExistentTypeId) } returns null

                val requestWithType =
                    CreateIssueRequest(
                        projectKey = projectKey,
                        summary = "Test summary",
                        reporterId = actor,
                        typeId = nonExistentTypeId,
                    )
                shouldThrow<com.bts.issue.type.domain.IssueTypeNotFoundException> {
                    sut.createIssue(actor, requestWithType)
                }
            }
        }

        // C-3: WorkflowSchemeNoDefaultException → IssueWorkflowNotConfiguredException 변환
        context("WorkflowKeyResolver 가 WorkflowSchemeNoDefaultException 을 던질 때") {
            val fixedProjectId = UUID.fromString("00000000-0000-0000-0000-000000000002")

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.CREATE,
                        IssueScope.Project(projectKey),
                    )
                } returns true
                every { repo.incrementKeySequence(projectKey) } returns 1L
                every { repo.findProjectIdByKey(projectKey) } returns fixedProjectId
                every { issueTypeRepository.findByKey(IssueTypeKey("task")) } returns taskIssueType
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of(projectKey), null)
                } throws WorkflowSchemeNoDefaultException(projectKey)
            }

            it("create throws IssueWorkflowNotConfiguredException when resolver default-mapping missing") {
                shouldThrow<IssueWorkflowNotConfiguredException> {
                    sut.createIssue(actor, request)
                }
            }
        }

        // A-2: CRITICAL-1 — 미존재 프로젝트 키 → IssueProjectNotFoundException
        context("프로젝트가 존재하지 않을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.CREATE,
                        IssueScope.Project("UNKNOWN"),
                    )
                } returns true
                every { repo.incrementKeySequence("UNKNOWN") } returns 1L
                every { repo.findProjectIdByKey("UNKNOWN") } returns null
                every { issueTypeRepository.findByKey(IssueTypeKey("task")) } returns taskIssueType
            }

            it("IssueProjectNotFoundException 을 던진다") {
                val unknownRequest =
                    CreateIssueRequest(
                        projectKey = "UNKNOWN",
                        summary = "Test summary",
                        reporterId = actor,
                        typeId = null,
                    )
                io.kotest.assertions.throwables.shouldThrow<IssueProjectNotFoundException> {
                    sut.createIssue(actor, unknownRequest)
                }
            }
        }

        context("권한이 없을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.CREATE,
                        IssueScope.Project(projectKey),
                    )
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.createIssue(actor, request)
                }
            }

            it("repo 및 eventPublisher 가 호출되지 않는다") {
                runCatching { sut.createIssue(actor, request) }

                verify(exactly = 0) { repo.incrementKeySequence(any()) }
                verify(exactly = 0) { repo.insert(any()) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        it("IssueApplicationService 클래스는 @Transactional 을 선언한다") {
            val annotation = IssueApplicationService::class.java.getAnnotation(Transactional::class.java)
            annotation shouldNotBe null
        }
    }
})

// WorkflowSchemeNoDefaultException 스텁은 WorkflowSchemeNoDefaultException.kt (공유 파일) 에 정의.
