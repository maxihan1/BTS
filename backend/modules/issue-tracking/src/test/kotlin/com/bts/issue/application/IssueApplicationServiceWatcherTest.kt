// 이슈 자동 watcher 배선 단위 테스트 — createIssue / changeAssignee / changeComponents (FR-WT-01).

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueDomainEvent
import com.bts.issue.event.IssueMentioned
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.mention.MentionSource
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.watcher.repository.IssueWatcherRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * IssueApplicationService 자동 watcher 배선 단위 테스트 (FR-WT-01).
 *
 * 검증 대상.
 * - createIssue: reporter 자동 watcher. resolvedAssignee non-null 이면 assignee 도 watcher.
 * - changeAssignee(non-null): 새 assignee watcher 추가(멱등).
 * - changeComponents 자동재배정: resolved assignee watcher 추가(CONCERN-1 — 모든 assignee 배정 통로 일관).
 * - 재배정 A→B: B 추가, A 유지(자동 제거 없음).
 * - unassign(null): watcher 무변경.
 *
 * ## MockK value class 우회 전략
 * MockK 1.13.x 에서 value class 파라미터를 가진 메서드의 stub 설정 시
 * JvmSignatureValueGenerator 가 primary constructor 를 호출하여 IssueKey 형식 검증에 실패한다.
 * 공통 stub 은 spec 최상위에서 1회만 설정하고, beforeEach 에서 `answers = false` 로
 * recorded calls 만 초기화(stub 유지)하여 이 문제를 우회한다.
 */
class IssueApplicationServiceWatcherTest : DescribeSpec({

    val repo = mockk<IssueRepository>(relaxed = true)
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>(relaxed = true)
    val permissionResolver = mockk<IssuePermissionResolver>(relaxed = true)
    val workflowPort = mockk<WorkflowTransitionPort>(relaxed = true)
    val workflowKeyResolver = mockk<WorkflowKeyResolver>(relaxed = true)
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val watcherRepository = mockk<IssueWatcherRepository>(relaxed = true)
    val projectLeadRepository = mockk<ProjectLeadRepository>(relaxed = true)
    val clock = Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneOffset.UTC)

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
            watcherRepository = watcherRepository,
        )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "BTS"
    val projectId = UUID.randomUUID()
    val issueKey = IssueKey("BTS-1")

    /** IssueResponse 헬퍼 — withSingleDetail() 경로에서 필요한 최소 필드. */
    fun makeResponse(issueId: UUID) =
        IssueResponse(
            key = issueKey.value,
            id = issueId,
            projectKey = projectKey,
            summary = "테스트 이슈",
            currentStateKey = "open",
            reporterId = actor.value,
            version = 2L,
            createdAt = Instant.parse("2026-06-16T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-16T00:00:00Z"),
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
        )

    fun makeIssue(
        assigneeId: ActorId? = null,
        reporterId: ActorId = actor,
        id: UUID = UUID.randomUUID(),
    ) = Issue(
        id = IssueId(id),
        key = issueKey,
        projectId = projectId,
        summary = "테스트 이슈",
        reporterId = reporterId,
        currentStateKey = "open",
        version = 1L,
        deletedAt = null,
        createdAt = Instant.parse("2026-06-16T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-16T00:00:00Z"),
        typeId = IssueTypeId(3L),
        assigneeId = assigneeId,
    )

    fun makeCreateRequest(componentIds: List<UUID> = emptyList()) =
        CreateIssueRequest(
            projectKey = projectKey,
            summary = "신규 이슈",
            reporterId = actor,
            componentIds = componentIds,
        )

    // ── 공통 stub — spec 최상위에서 1회만 설정 (MockK value class signature value 캐싱 활용) ──
    every { permissionResolver.hasPermission(any(), any(), any()) } returns true
    every { repo.findProjectIdByKey(projectKey) } returns projectId
    every { repo.incrementKeySequence(projectKey) } returns 1L
    every { repo.insertComponents(any(), any()) } returns Unit
    // withSingleDetail() 경로 — IssueKey value class stub 는 issueKey 고정값으로 1회 등록
    every { repo.findByKey(issueKey) } returns null
    every { repo.findByKeyWithType(issueKey) } returns null
    every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()
    every { repo.findAffectsVersionIdsByIssue(any()) } returns emptyList()
    every { repo.findFixVersionIdsByIssue(any()) } returns emptyList()
    every { projectLeadRepository.findLeadUserId(any()) } returns null
    every { issueTypeRepository.findByKey(IssueTypeKey("task")) } returns
        com.bts.issue.type.domain.IssueType(
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
    every { workflowKeyResolver.resolveStart(ProjectKey.of(projectKey), null) } returns
        WorkflowStartState(workflowKey = "default", startStateKey = "open")
    every { eventPublisher.publish(any()) } returns Unit

    // beforeEach: answers = false 로 recorded calls 만 초기화, stub 은 유지
    beforeEach {
        clearMocks(repo, eventPublisher, watcherRepository, projectLeadRepository, answers = false)
        // answers=false 여도 resetAll 방지: 공통 stub 기본값 재설정 (clearMocks 는 calls 만 초기화하므로 불필요하지만,
        // recorded call 수 리셋 목적 — answers 는 유지됨)
        every { projectLeadRepository.findLeadUserId(any()) } returns null
    }

    describe("createIssue — 자동 watcher") {

        it("reporter 는 항상 watcher 로 자동 등록된다") {
            // projectLead 없음 → resolvedAssignee = null → reporter 만 watcher
            every { repo.insert(any()) } answers { firstArg() }

            sut.createIssue(actor, makeCreateRequest())

            // reporter(actor) 가 watcher 로 등록됐는지 검증 (issueId 는 createIssue 내부 UUID.randomUUID())
            // FR-MN-03 이후 autoWatch 는 배치 1문장이다 — 등록 대상은 그대로고 호출 형태만 바뀌었다.
            verify { watcherRepository.addAll(any(), listOf(actor.value)) }
        }

        it("생성 시 projectLead 가 있으면 reporter + projectLead 모두 watcher 로 자동 등록된다") {
            val leadId = UUID.randomUUID()
            // projectLead stub → resolveDefaultAssignee 가 leadId 반환
            every { projectLeadRepository.findLeadUserId(projectId) } returns leadId
            // repo.insert 는 Issue.create 결과(assigneeId = ActorId(leadId) 포함)를 그대로 반환
            every { repo.insert(any()) } answers { firstArg() }

            sut.createIssue(actor, makeCreateRequest())

            // reporter(actor) 와 projectLead(assignee) 모두 watcher 로 등록됐는지 검증
            // 순서는 autoWatch 가 받는 listOfNotNull(reporter, assignee) 그대로다.
            verify { watcherRepository.addAll(any(), listOf(actor.value, leadId)) }
        }

        it("reporter 와 assignee 가 같으면 등록 대상이 1명으로 접힌다") {
            // projectLead = actor UUID → resolvedAssignee = actor → distinct 후 add 1회
            every { projectLeadRepository.findLeadUserId(projectId) } returns actor.value
            every { repo.insert(any()) } answers { firstArg() }

            sut.createIssue(actor, makeCreateRequest())

            verify(exactly = 1) { watcherRepository.addAll(any(), listOf(actor.value)) }
        }
    }

    describe("changeAssignee — 자동 watcher") {

        it("새 assignee(non-null) 는 watcher 로 자동 등록된다") {
            val newAssigneeId = UUID.randomUUID()
            val existing = makeIssue(assigneeId = null)

            every { repo.findByKey(issueKey) } returns existing
            every { userLookupPort.exists(newAssigneeId) } returns true
            every { repo.updateAssignee(issueKey, newAssigneeId, 1L) } returns 1
            every { repo.findByKeyWithType(issueKey) } returns makeResponse(existing.id.value)

            sut.changeAssignee(
                actor,
                issueKey,
                AppChangeAssigneeRequest(assigneeId = newAssigneeId, expectedVersion = 1L),
            )

            verify { watcherRepository.addAll(existing.id.value, listOf(newAssigneeId)) }
        }

        it("unassign(null) 이면 watcher 변경 없이 add 가 호출되지 않는다") {
            val oldAssigneeId = ActorId(UUID.randomUUID())
            val existing = makeIssue(assigneeId = oldAssigneeId)

            every { repo.findByKey(issueKey) } returns existing
            every { repo.updateAssignee(issueKey, null, 1L) } returns 1
            every { repo.findByKeyWithType(issueKey) } returns makeResponse(existing.id.value)

            sut.changeAssignee(
                actor,
                issueKey,
                AppChangeAssigneeRequest(assigneeId = null, expectedVersion = 1L),
            )

            verify(exactly = 0) { watcherRepository.add(any(), any()) }
            verify(exactly = 0) { watcherRepository.addAll(any(), any()) }
        }

        it("재배정 A→B 시 B 가 watcher 로 추가되고 A 는 제거되지 않는다") {
            val oldAssigneeId = ActorId(UUID.randomUUID())
            val newAssigneeId = UUID.randomUUID()
            val existing = makeIssue(assigneeId = oldAssigneeId)

            every { repo.findByKey(issueKey) } returns existing
            every { userLookupPort.exists(newAssigneeId) } returns true
            every { repo.updateAssignee(issueKey, newAssigneeId, 1L) } returns 1
            every { repo.findByKeyWithType(issueKey) } returns makeResponse(existing.id.value)

            sut.changeAssignee(
                actor,
                issueKey,
                AppChangeAssigneeRequest(assigneeId = newAssigneeId, expectedVersion = 1L),
            )

            // B 추가
            verify { watcherRepository.addAll(existing.id.value, listOf(newAssigneeId)) }
            // A 제거 없음
            verify(exactly = 0) { watcherRepository.remove(any(), any()) }
        }
    }

    describe("changeComponents 자동재배정 — 자동 watcher (CONCERN-1)") {

        it("projectLead 가 있고 기존 assignee 가 null 이면 projectLead 가 watcher 로 자동 등록된다") {
            val resolvedLeadId = UUID.randomUUID()
            val existing = makeIssue(assigneeId = null)
            val issueIssueId = existing.id.value

            // assignee null → resolveDefaultAssignee → projectLead 반환
            every { projectLeadRepository.findLeadUserId(projectId) } returns resolvedLeadId
            every { repo.findByKey(issueKey) } returns existing
            every { repo.replaceComponents(issueKey, issueIssueId, emptyList(), 1L) } returns 1
            every { repo.setAssignee(issueIssueId, resolvedLeadId) } returns Unit
            every { repo.findByKeyWithType(issueKey) } returns makeResponse(issueIssueId)

            sut.changeComponents(
                actor,
                issueKey,
                AppChangeComponentsRequest(componentIds = emptyList(), expectedVersion = 1L),
            )

            // resolved projectLead → autoWatch 호출
            verify { watcherRepository.addAll(issueIssueId, listOf(resolvedLeadId)) }
        }

        it("기존 assignee 가 있으면 자동재배정이 발동하지 않으므로 autoWatch 도 호출되지 않는다") {
            val existingAssignee = ActorId(UUID.randomUUID())
            val existing = makeIssue(assigneeId = existingAssignee)
            val issueIssueId = existing.id.value

            every { repo.findByKey(issueKey) } returns existing
            every { repo.replaceComponents(issueKey, issueIssueId, emptyList(), 1L) } returns 1
            every { repo.findByKeyWithType(issueKey) } returns makeResponse(issueIssueId)

            sut.changeComponents(
                actor,
                issueKey,
                AppChangeComponentsRequest(componentIds = emptyList(), expectedVersion = 1L),
            )

            // 기존 assignee 있음 → 자동재배정 미발동 → autoWatch 미호출
            verify(exactly = 0) { watcherRepository.add(any(), any()) }
            verify(exactly = 0) { watcherRepository.addAll(any(), any()) }
        }
    }

    // ── FR-MN-03 — 생성 시 description 멘션 ─────────────────────────────
    describe("createIssue — description 멘션 발행 + 자동 watcher") {

        val bobId = UUID.fromString("bb000000-0000-0000-0000-0000000000b1")
        val carolId = UUID.fromString("cc000000-0000-0000-0000-0000000000c1")

        beforeEach {
            every { repo.insert(any()) } answers { firstArg() }
            every { userLookupPort.findIdsByUsernames(any()) } answers {
                mapOf("bob" to bobId, "carol" to carolId, "me" to actor.value)
                    .filterKeys { it in firstArg<Set<String>>() }
            }
        }

        it("생성 본문의 멘션 전원이 대상이다 — 비교 대상이 없으므로 diff 가 아니다") {
            val captured = mutableListOf<IssueDomainEvent>()
            every { eventPublisher.publish(capture(captured)) } returns Unit

            sut.createIssue(actor, makeCreateRequest().copy(description = "@bob 과 @carol 봐주세요"))

            val mentioned = captured.filterIsInstance<IssueMentioned>().single()
            mentioned.mentionedUserIds shouldBe listOf(bobId, carolId).sorted()
            mentioned.sourceField shouldBe MentionSource.DESCRIPTION
            mentioned.commentId shouldBe null
        }

        it("멘션 대상이 자동 watcher 가 된다 — reporter 등록과는 별개 호출이다") {
            sut.createIssue(actor, makeCreateRequest().copy(description = "@bob"))

            verify(exactly = 1) { watcherRepository.addAll(any(), listOf(actor.value)) }
            verify(exactly = 1) { watcherRepository.addAll(any(), listOf(bobId)) }
        }

        it("멘션이 없으면 IssueMentioned 를 발행하지 않는다") {
            val captured = mutableListOf<IssueDomainEvent>()
            every { eventPublisher.publish(capture(captured)) } returns Unit

            sut.createIssue(actor, makeCreateRequest().copy(description = "멘션 없는 본문"))

            captured.filterIsInstance<IssueMentioned>() shouldBe emptyList()
        }

        it("자기 자신만 멘션하면 이벤트도 멘션 watcher 도 없다") {
            val captured = mutableListOf<IssueDomainEvent>()
            every { eventPublisher.publish(capture(captured)) } returns Unit

            sut.createIssue(actor, makeCreateRequest().copy(description = "@me 혼잣말"))

            captured.filterIsInstance<IssueMentioned>() shouldBe emptyList()
            // reporter 등록 1회만 — 멘션발 등록은 없다
            verify(exactly = 1) { watcherRepository.addAll(any(), any()) }
        }
    }
})
