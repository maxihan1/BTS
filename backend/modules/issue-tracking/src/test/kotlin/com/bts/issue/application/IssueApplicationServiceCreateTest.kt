// IssueApplicationService.createIssue 단위 테스트 — MockK, TDD RED 단계 (typeId + task fallback 포함)

package com.bts.issue.application

import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.event.IssueCreated
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.repository.ProjectLeadRepository
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
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)

    // FR-UX-09 B1 — 자동 배정(resolveDefaultAssignee)의 호출 **여부**를 단언해야 하므로
    // 인라인 mock 대신 val 로 꺼낸다. 「담당자가 null 이다」만 보면
    // 자동 배정이 꺼진 것과 자동 배정이 돌았는데 결과가 null 인 것을 구분할 수 없다.
    val componentRepository = mockk<ComponentRepository>(relaxed = true)
    val projectLeadRepository = mockk<ProjectLeadRepository>(relaxed = true)

    // autoWatch 는 watcherRepository 가 null 이면 no-op 이라, 주입하지 않으면 FR7 이 무검증으로 남는다.
    val watcherRepository = mockk<com.bts.issue.watcher.repository.IssueWatcherRepository>(relaxed = true)

    val clock = Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneOffset.UTC)

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
            componentRepository = componentRepository,
            projectLeadRepository = projectLeadRepository,
            versionRepository = mockk(relaxed = true),
            clock = clock,
            historyRecorder = mockk(relaxed = true),
            watcherRepository = watcherRepository,
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
                every { repo.insertComponents(any(), any()) } returns Unit
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

        // ──────────────────────────────────────────────────────────────
        // FR-UX-09 B1 — 생성 시 priority / labels 지정 (ADR D-2)
        // ──────────────────────────────────────────────────────────────
        context("priority·labels 를 생성 시 지정할 때") {
            val b1ProjectId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
            lateinit var issueSlot: CapturingSlot<com.bts.issue.domain.Issue>

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.CREATE,
                        IssueScope.Project(projectKey),
                    )
                } returns true
                every { repo.incrementKeySequence(projectKey) } returns 1L
                every { repo.findProjectIdByKey(projectKey) } returns b1ProjectId
                every { repo.insertComponents(any(), any()) } returns Unit
                every { eventPublisher.publish(any()) } returns Unit
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of(projectKey), null)
                } returns WorkflowStartState(workflowKey = "software-default", startStateKey = "open")
                every { issueTypeRepository.findByKey(IssueTypeKey("task")) } returns taskIssueType
                issueSlot = slot()
                every { repo.insert(capture(issueSlot)) } answers { issueSlot.captured }
            }

            it("지정한 priority 와 labels 가 저장된 Issue 에 반영된다") {
                sut.createIssue(
                    actor,
                    request.copy(priority = 1, labels = listOf("urgent")),
                )

                issueSlot.captured.priority shouldBe 1
                issueSlot.captured.labels shouldBe listOf("urgent")
            }

            // ★무회귀 가드 — 3필드를 안 보내던 기존 요청이 그대로 동작해야 한다.
            it("priority 를 생략하면 도메인 기본값(MEDIUM=3)이 적용된다") {
                sut.createIssue(actor, request)

                issueSlot.captured.priority shouldBe com.bts.issue.domain.IssuePriority.MEDIUM.number
            }

            it("labels 를 생략하면 빈 목록이 적용된다") {
                sut.createIssue(actor, request)

                issueSlot.captured.labels shouldBe emptyList()
            }
        }

        // ──────────────────────────────────────────────────────────────
        // FR-UX-09 B1 — assigneeId 3-state (ADR D-2)
        //
        // ★양성 대조군 전제. projectLeadRepository 가 리드를 반환하도록 스텁해
        //   「Auto 면 담당자가 붙는다」를 먼저 성립시킨다. 그래야 None/User 에서
        //   담당자가 안 붙는 것이 의미를 갖는다.
        // ──────────────────────────────────────────────────────────────
        context("assigneeId 3-state 로 담당자를 정할 때") {
            val b1ProjectId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
            val projectLeadId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
            val explicitAssignee = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
            lateinit var issueSlot: CapturingSlot<com.bts.issue.domain.Issue>

            beforeEach {
                clearMocks(componentRepository, projectLeadRepository, watcherRepository, answers = false)
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.CREATE,
                        IssueScope.Project(projectKey),
                    )
                } returns true
                every { repo.incrementKeySequence(projectKey) } returns 1L
                every { repo.findProjectIdByKey(projectKey) } returns b1ProjectId
                every { repo.insertComponents(any(), any()) } returns Unit
                every { eventPublisher.publish(any()) } returns Unit
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of(projectKey), null)
                } returns WorkflowStartState(workflowKey = "software-default", startStateKey = "open")
                every { issueTypeRepository.findByKey(IssueTypeKey("task")) } returns taskIssueType
                every { componentRepository.findByProject(b1ProjectId) } returns emptyList()
                every { projectLeadRepository.findLeadUserId(b1ProjectId) } returns projectLeadId
                issueSlot = slot()
                every { repo.insert(capture(issueSlot)) } answers { issueSlot.captured }
            }

            // ★양성 대조군 — 이게 통과해야 아래 두 케이스가 의미를 갖는다.
            it("Auto(키 생략)면 자동 배정 결과가 담당자가 된다") {
                sut.createIssue(actor, request)

                issueSlot.captured.assigneeId?.value shouldBe projectLeadId
                verify { projectLeadRepository.findLeadUserId(b1ProjectId) }
            }

            it("None(명시 null)이면 미할당이고 자동 배정이 아예 호출되지 않는다") {
                sut.createIssue(actor, request.copy(assignee = AssigneeIntent.None))

                issueSlot.captured.assigneeId shouldBe null
                verify(exactly = 0) { projectLeadRepository.findLeadUserId(any()) }
                verify(exactly = 0) { componentRepository.findByProject(any()) }
            }

            it("User(값 지정)면 그 사용자가 담당자이고 자동 배정이 호출되지 않는다") {
                sut.createIssue(actor, request.copy(assignee = AssigneeIntent.User(explicitAssignee)))

                issueSlot.captured.assigneeId?.value shouldBe explicitAssignee
                verify(exactly = 0) { projectLeadRepository.findLeadUserId(any()) }
                verify(exactly = 0) { componentRepository.findByProject(any()) }
            }

            // ★R3 — 담당자 분기가 워처(autoWatch)까지 전파되는지. FR7.
            it("None 이면 워처는 reporter 한 명뿐이다") {
                sut.createIssue(actor, request.copy(assignee = AssigneeIntent.None))

                verify(exactly = 1) { watcherRepository.add(any(), actor.value) }
                verify(exactly = 1) { watcherRepository.add(any(), any()) }
            }

            it("User 면 워처가 reporter 와 담당자 두 명이다") {
                sut.createIssue(actor, request.copy(assignee = AssigneeIntent.User(explicitAssignee)))

                verify(exactly = 1) { watcherRepository.add(any(), actor.value) }
                verify(exactly = 1) { watcherRepository.add(any(), explicitAssignee) }
                verify(exactly = 2) { watcherRepository.add(any(), any()) }
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
