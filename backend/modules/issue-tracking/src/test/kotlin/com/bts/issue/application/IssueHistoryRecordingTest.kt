// IssueApplicationService 8진입점에서 IssueHistoryRecorder 호출 배선 검증 (MockK)

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.DomainEvent
import com.bts.shared.workflow.FieldChange
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.TransitionPlan
import com.bts.shared.workflow.TransitionResult
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * IssueApplicationService 8진입점에서 IssueHistoryRecorder.record 호출 배선을 검증한다.
 *
 * recorder 는 mock 으로 교체하여 recorder 내부 동작은 이 테스트 범위 밖이다.
 * 각 진입점이 적절한 before/after/actor 인자로 recorder.record 를 호출하는지만 검증한다.
 *
 * TooManyFunctions: 8진입점 전부를 한 파일에서 검증하므로 임계치 초과 허용.
 */
@Suppress("TooManyFunctions", "LargeClass")
class IssueHistoryRecordingTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>(relaxed = true)
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val componentRepository = mockk<ComponentRepository>(relaxed = true)
    val projectLeadRepository = mockk<ProjectLeadRepository>(relaxed = true)
    val versionRepository = mockk<VersionRepository>(relaxed = true)
    val historyRecorder = mockk<IssueHistoryRecorder>()
    val clock = Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC)

    val sut = IssueApplicationService(
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
        versionRepository = versionRepository,
        clock = clock,
        historyRecorder = historyRecorder,
    )

    val actor = ActorId(UUID.randomUUID())
    val projectId = UUID.randomUUID()
    val issueKey = IssueKey("BTS-1")

    val taskTypeId = IssueTypeId(1L)

    fun makeIssue(
        key: IssueKey = issueKey,
        state: String = "open",
        assigneeId: ActorId? = null,
        version: Long = 1L,
    ): Issue = Issue(
        id = IssueId(UUID.randomUUID()),
        key = key,
        projectId = projectId,
        summary = "test summary",
        reporterId = ActorId(UUID.randomUUID()),
        currentStateKey = state,
        version = version,
        deletedAt = null,
        createdAt = Instant.now(clock),
        updatedAt = Instant.now(clock),
        typeId = taskTypeId,
        assigneeId = assigneeId,
    )

    /** IssueResponse mock — withSingleDetail() 은 private extension 이므로 relaxed mock 사용. */
    fun makeResponseMock(): IssueResponse = mockk(relaxed = true)

    beforeTest {
        every { permissionResolver.hasPermission(any(), any(), any()) } returns true
        justRun { historyRecorder.record(any(), any(), any(), any()) }
    }

    // ── createIssue ────────────────────────────────────────────────────────────

    describe("createIssue — recorder.record 호출 배선") {
        val issue = makeIssue()

        beforeTest {
            every { repo.incrementKeySequence("BTS") } returns 1L
            every { repo.findProjectIdByKey("BTS") } returns projectId
            every { issueTypeRepository.findByKey(IssueTypeKey("task")) } returns
                com.bts.issue.type.domain.IssueType.TASK.copy(id = taskTypeId)
            every { workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null) } returns
                WorkflowStartState(workflowKey = "default", startStateKey = "open")
            every { repo.insert(any()) } returns issue
            every { repo.insertComponents(any(), any()) } returns Unit
        }

        it("before=null, after=생성된 이슈로 recorder.record 를 호출한다") {
            val request = CreateIssueRequest(
                projectKey = "BTS",
                summary = "test summary",
                reporterId = actor,
            )
            sut.createIssue(actor, request)

            verify {
                historyRecorder.record(
                    before = null,
                    after = issue,
                    actor = actor,
                    projectId = projectId,
                )
            }
        }
    }

    // ── updateIssue ────────────────────────────────────────────────────────────

    describe("updateIssue — recorder.record 호출 배선") {
        val existing = makeIssue()
        val responseMock = makeResponseMock()

        beforeTest {
            every { repo.findByKey(issueKey) } returns existing
            every {
                repo.updateFields(key = any(), patch = any(), expectedVersion = any())
            } returns 1
            every { repo.findByKeyWithType(issueKey) } returns responseMock
        }

        it("before=existing, after 로 recorder.record 를 호출한다") {
            val request = UpdateIssueRequest(summary = "updated summary", expectedVersion = 1L)
            sut.updateIssue(actor, issueKey, request)

            verify {
                historyRecorder.record(
                    before = existing,
                    after = any(),
                    actor = actor,
                    projectId = projectId,
                )
            }
        }
    }

    // ── transitionIssue ────────────────────────────────────────────────────────

    describe("transitionIssue — recorder.record 호출 배선") {
        val existing = makeIssue(state = "open")
        val responseMock = makeResponseMock()

        beforeTest {
            every { repo.findByKeyForUpdate(issueKey) } returns existing
            every { workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null) } returns
                WorkflowStartState(workflowKey = "default", startStateKey = "open")
            every { workflowPort.plan(any()) } returns
                TransitionResult.Success(
                    TransitionPlan(
                        toStateKey = "in_progress",
                        fieldChanges = emptyList<FieldChange>(),
                        emitEvents = emptyList<DomainEvent>(),
                    ),
                )
            every { repo.applyTransition(issueKey, "in_progress", 1L, null) } returns 1
            every { repo.findByKeyWithType(issueKey) } returns responseMock
        }

        it("recorder.record 를 호출한다") {
            val request = TransitionIssueRequest(toStateKey = "in_progress", expectedVersion = 1L)
            sut.transitionIssue(actor, issueKey, request)

            verify {
                historyRecorder.record(
                    before = existing,
                    after = any(),
                    actor = actor,
                    projectId = projectId,
                )
            }
        }
    }

    // ── changeAssignee ─────────────────────────────────────────────────────────

    describe("changeAssignee — recorder.record 호출 배선") {
        val newAssigneeId = UUID.randomUUID()
        val existing = makeIssue(assigneeId = null)
        val responseMock = makeResponseMock()

        beforeTest {
            every { repo.findByKey(issueKey) } returns existing
            every { userLookupPort.exists(newAssigneeId) } returns true
            every { repo.updateAssignee(issueKey, newAssigneeId, 1L) } returns 1
            every { repo.findByKeyWithType(issueKey) } returns responseMock
        }

        it("recorder.record 를 호출한다") {
            val request = AppChangeAssigneeRequest(assigneeId = newAssigneeId, expectedVersion = 1L)
            sut.changeAssignee(actor, issueKey, request)

            verify {
                historyRecorder.record(
                    before = existing,
                    after = any(),
                    actor = actor,
                    projectId = projectId,
                )
            }
        }
    }

    // ── changeComponents ───────────────────────────────────────────────────────

    describe("changeComponents — recorder.record 호출 배선") {
        val compId = UUID.randomUUID()
        val existing = makeIssue(assigneeId = null)
        val responseMock = makeResponseMock()

        beforeTest {
            every { repo.findByKey(issueKey) } returns existing
            every { repo.replaceComponents(issueKey, existing.id.value, any(), 1L) } returns 1
            every { repo.findByKeyWithType(issueKey) } returns responseMock
        }

        it("recorder.record 를 호출한다") {
            val request = AppChangeComponentsRequest(componentIds = listOf(compId), expectedVersion = 1L)
            sut.changeComponents(actor, issueKey, request)

            verify {
                historyRecorder.record(
                    before = existing,
                    after = any(),
                    actor = actor,
                    projectId = projectId,
                )
            }
        }
    }

    // ── changeAffectsVersions ──────────────────────────────────────────────────

    describe("changeAffectsVersions — recorder.record 호출 배선") {
        val verId = UUID.randomUUID()
        val existing = makeIssue()
        val responseMock = makeResponseMock()

        beforeTest {
            every { repo.findByKey(issueKey) } returns existing
            every { repo.replaceAffectsVersions(issueKey, existing.id.value, any(), 1L) } returns 1
            every { repo.findByKeyWithType(issueKey) } returns responseMock
        }

        it("recorder.record 를 호출한다") {
            val request = AppChangeVersionsRequest(versionIds = listOf(verId), expectedVersion = 1L)
            sut.changeAffectsVersions(actor, issueKey, request)

            verify {
                historyRecorder.record(
                    before = existing,
                    after = any(),
                    actor = actor,
                    projectId = projectId,
                )
            }
        }
    }

    // ── changeFixVersions ──────────────────────────────────────────────────────

    describe("changeFixVersions — recorder.record 호출 배선") {
        val verId = UUID.randomUUID()
        val existing = makeIssue()
        val responseMock = makeResponseMock()

        beforeTest {
            every { repo.findByKey(issueKey) } returns existing
            every { repo.replaceFixVersions(issueKey, existing.id.value, any(), 1L) } returns 1
            every { repo.findByKeyWithType(issueKey) } returns responseMock
        }

        it("recorder.record 를 호출한다") {
            val request = AppChangeVersionsRequest(versionIds = listOf(verId), expectedVersion = 1L)
            sut.changeFixVersions(actor, issueKey, request)

            verify {
                historyRecorder.record(
                    before = existing,
                    after = any(),
                    actor = actor,
                    projectId = projectId,
                )
            }
        }
    }

    // ── softDeleteIssue ────────────────────────────────────────────────────────

    describe("softDeleteIssue — recorder.record 호출 배선") {
        val existing = makeIssue()

        beforeTest {
            every { repo.findByKey(issueKey) } returns existing
            every { repo.softDelete(issueKey) } returns 1
        }

        it("before=existing, after=null 로 recorder.record 를 호출한다") {
            sut.softDeleteIssue(actor, issueKey)

            verify {
                historyRecorder.record(
                    before = existing,
                    after = null,
                    actor = actor,
                    projectId = projectId,
                )
            }
        }
    }
})
