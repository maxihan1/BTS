// FR-SL-02 Task 1 — changeAssignee 가 IssueAssigned 이벤트를 발행하는지 검증하는 단위 테스트

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueAssigned
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * changeAssignee — 담당자가 실제로 변경될 때 [IssueAssigned] 이벤트를 정확히 1회 발행하고,
 * no-op(기존값과 동일한 요청)이면 전혀 발행하지 않는지 검증한다 (FR-SL-02 Task 1).
 *
 * occurredAt 은 [clock] 고정값으로 결정적으로 검증한다 — 임의 `Instant.now()` 사용 시
 * dedupKey 재현성이 깨지는 회귀를 막기 위함(메모리 교훈).
 */
class IssueAssigneeEventTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val fixedInstant = Instant.parse("2026-07-10T00:00:00Z")
    val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

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
            projectLeadRepository = mockk(relaxed = true),
            versionRepository = mockk(relaxed = true),
            clock = clock,
            historyRecorder = mockk(relaxed = true),
        )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")
    val existingVersion = 1L
    val anyProjectId: UUID = UUID.fromString("11111111-0000-0000-0000-000000000001")

    fun makeIssue(assigneeId: ActorId? = null) =
        Issue(
            id = IssueId(UUID.randomUUID()),
            key = issueKey,
            projectId = UUID.randomUUID(),
            summary = "기존 제목",
            reporterId = actor,
            currentStateKey = "open",
            version = existingVersion,
            deletedAt = null,
            createdAt = Instant.parse("2026-05-30T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-30T00:00:00Z"),
            typeId = IssueTypeId(3L),
            assigneeId = assigneeId,
        )

    fun makeResponse() =
        IssueResponse(
            key = issueKey.value,
            id = UUID.randomUUID(),
            projectKey = issueKey.projectPrefix,
            summary = "기존 제목",
            currentStateKey = "open",
            reporterId = actor.value,
            version = existingVersion,
            createdAt = Instant.parse("2026-05-30T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-30T00:00:00Z"),
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
        )

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, userLookupPort, answers = false)
        every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()
        every { repo.findAffectsVersionIdsByIssue(any()) } returns emptyList()
        every { repo.findFixVersionIdsByIssue(any()) } returns emptyList()
        every { repo.findProjectIdByKey(issueKey.projectPrefix) } returns anyProjectId
        every {
            permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
        } returns true
    }

    describe("changeAssignee — IssueAssigned 이벤트 발행") {

        context("담당자를 신규 배정 (기존 미할당 → 신규 UUID)") {
            val assigneeUuid = UUID.randomUUID()
            val request = AppChangeAssigneeRequest(assigneeId = assigneeUuid, expectedVersion = existingVersion)
            val existingIssue = makeIssue(assigneeId = null)
            val updatedResponse = makeResponse()

            beforeEach {
                every { repo.findByKey(issueKey) } returns existingIssue
                every { userLookupPort.exists(assigneeUuid) } returns true
                every { repo.updateAssignee(issueKey, assigneeUuid, existingVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } just Runs
            }

            it("IssueAssigned(issueKey, actor, clock 고정시각) 를 정확히 1회 발행한다") {
                sut.changeAssignee(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        IssueAssigned(issueKey = issueKey, actorId = actor, occurredAt = fixedInstant),
                    )
                }
            }
        }

        context("담당자 해제 (기존 배정 → null)") {
            val existingAssignee = ActorId(UUID.randomUUID())
            val request = AppChangeAssigneeRequest(assigneeId = null, expectedVersion = existingVersion)
            val existingIssue = makeIssue(assigneeId = existingAssignee)
            val updatedResponse = makeResponse()

            beforeEach {
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.updateAssignee(issueKey, null, existingVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } just Runs
            }

            it("IssueAssigned 를 정확히 1회 발행한다") {
                sut.changeAssignee(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        IssueAssigned(issueKey = issueKey, actorId = actor, occurredAt = fixedInstant),
                    )
                }
            }
        }

        context("no-op — 요청 assigneeId 가 기존값과 동일") {
            val assigneeUuid = UUID.randomUUID()
            val request = AppChangeAssigneeRequest(assigneeId = assigneeUuid, expectedVersion = existingVersion)
            val existingIssue = makeIssue(assigneeId = ActorId(assigneeUuid))
            val updatedResponse = makeResponse()

            beforeEach {
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
            }

            it("eventPublisher.publish 가 전혀 호출되지 않는다") {
                sut.changeAssignee(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }
    }
})
