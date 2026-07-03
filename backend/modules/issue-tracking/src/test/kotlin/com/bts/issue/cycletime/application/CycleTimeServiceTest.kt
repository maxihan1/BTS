// CycleTimeService 단위 테스트 — MockK. 권한·빈이슈 방어·전이기반 firstInProgress/lastDone 추출·삭제상태키 폴백 검증 (FR-RP-04 Task 5)

package com.bts.issue.cycletime.application

import com.bts.issue.adapter.outbound.velocity.IsolatedWorkflowStateLookup
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.repository.CycleTimeIssueSourceRow
import com.bts.issue.repository.IssueRepository
import com.bts.issue.statushistory.repository.StatusChangeRow
import com.bts.issue.statushistory.repository.StatusHistoryRepository
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateView
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [CycleTimeService] 단위 테스트.
 *
 * permissionResolver / issueRepository / statusHistoryRepository / securityDirectory /
 * issueTypeRepository / workflowStateLookup 6개 협력자를 MockK 로 stub 한다.
 *
 * 검증 목록.
 * - BROWSE 권한 없음 → [IssueAccessDeniedException], repo 조회 미수행(probe 차단)
 * - 가시 이슈 0개 → 빈 결과(cycle/lead count 0), fetchStatusChanges/listStates 미호출(빈 IN 방어)
 * - IN_PROGRESS + DONE 전이가 있으면 firstInProgressAt/lastDoneAt 이 각 전이 시각으로 정확히 추출된다
 * - IN_PROGRESS 전이가 없으면 firstInProgressAt=null → cycle 표본 제외, lead 표본에는 남는다
 * - 카탈로그에 없는(삭제된) 상태 키는 TODO 로 폴백되어 IN_PROGRESS 진입으로 오집계되지 않는다
 */
class CycleTimeServiceTest : DescribeSpec({

    val permissionResolver = mockk<IssuePermissionResolver>()
    val issueRepository = mockk<IssueRepository>()
    val statusHistoryRepository = mockk<StatusHistoryRepository>()
    val securityDirectory = mockk<IssueSecurityDirectory>()
    val issueTypeRepository = mockk<IssueTypeRepository>()
    val workflowStateLookup = mockk<IsolatedWorkflowStateLookup>()

    val sut =
        CycleTimeService(
            permissionResolver = permissionResolver,
            issueRepository = issueRepository,
            statusHistoryRepository = statusHistoryRepository,
            securityDirectory = securityDirectory,
            issueTypeRepository = issueTypeRepository,
            workflowStateLookup = workflowStateLookup,
        )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "CTP"
    val projectScope = IssueScope.Project(projectKey)
    val from = LocalDate.of(2026, 6, 1)
    val to = LocalDate.of(2026, 6, 5)

    val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    fun stubAllowed() {
        every { permissionResolver.hasPermission(actor.value, IssuePermission.BROWSE, projectScope) } returns true
    }

    fun stubDenied() {
        every { permissionResolver.hasPermission(actor.value, IssuePermission.BROWSE, projectScope) } returns false
    }

    /** BROWSE 허용 + accessibleLevels stub — 대부분의 시나리오가 공유하는 선행 조건. */
    fun stubBrowseAndAccess() {
        stubAllowed()
        every { securityDirectory.accessibleLevels(actor.value, projectKey) } returns unrestrictedAccess
    }

    beforeEach {
        clearMocks(
            permissionResolver,
            issueRepository,
            statusHistoryRepository,
            securityDirectory,
            issueTypeRepository,
            workflowStateLookup,
        )
    }

    // ── 권한 검증 ─────────────────────────────────────────────────────────────

    describe("권한 검증") {
        it("BROWSE 권한 없음 → IssueAccessDeniedException, repo 조회 미수행(probe 차단)") {
            stubDenied()

            shouldThrow<IssueAccessDeniedException> {
                sut.getCycleTime(actor, projectKey, from, to)
            }

            verify(exactly = 0) { issueRepository.fetchActiveVisibleIssuesForCycleTime(any(), any(), any()) }
        }
    }

    // ── 가시 이슈 0개 — 빈 IN 방어 ────────────────────────────────────────────

    describe("가시 이슈 0개") {
        it("빈 결과 반환, fetchStatusChanges/listStates/findAll 미호출") {
            stubBrowseAndAccess()
            every {
                issueRepository.fetchActiveVisibleIssuesForCycleTime(projectKey, actor.value, unrestrictedAccess)
            } returns emptyList()

            val result = sut.getCycleTime(actor, projectKey, from, to)

            result.cycleTime.stats.count shouldBe 0
            result.leadTime.stats.count shouldBe 0
            result.cycleTime.samples shouldHaveSize 0
            result.leadTime.samples shouldHaveSize 0

            verify(exactly = 0) { statusHistoryRepository.fetchStatusChanges(any()) }
            // value class(ProjectKey/IssueTypeKey) 인자에 any() 매처를 쓰면 MockK 리플렉션 오류가
            // 발생하므로(CfdServiceTest 선례), 인자 매칭 없이 호출 자체가 없었음을 검증한다.
            verify { workflowStateLookup wasNot Called }
            verify(exactly = 0) { issueTypeRepository.findAll() }
        }
    }

    // ── 전이 기반 firstInProgressAt/lastDoneAt 추출 ─────────────────────────────

    describe("buildDurationInput — 전이 기반 추출") {
        it("IN_PROGRESS + DONE 전이가 있으면 firstInProgressAt/lastDoneAt 이 각 전이 시각으로 정확히 추출된다") {
            stubBrowseAndAccess()
            val typeId = 10L
            val issueId = UUID.randomUUID()
            val createdAt = Instant.parse("2026-06-01T00:00:00Z")
            val inProgressAt = Instant.parse("2026-06-02T00:00:00Z")
            val doneAt = Instant.parse("2026-06-03T00:00:00Z")
            val issue = CycleTimeIssueSourceRow(issueId, "CTP-1", typeId, "done", createdAt)
            every {
                issueRepository.fetchActiveVisibleIssuesForCycleTime(projectKey, actor.value, unrestrictedAccess)
            } returns listOf(issue)
            every { statusHistoryRepository.fetchStatusChanges(setOf(issueId)) } returns
                listOf(
                    StatusChangeRow(issueId, inProgressAt, 1L, fromValue = "open", toValue = "in_progress"),
                    StatusChangeRow(issueId, doneAt, 2L, fromValue = "in_progress", toValue = "done"),
                )
            every { issueTypeRepository.findAll() } returns
                listOf(
                    IssueType.create(key = IssueTypeKey("cttype"), name = "CT Type").copy(id = IssueTypeId(typeId)),
                )
            every {
                workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("cttype"))
            } returns
                listOf(
                    WorkflowStateView(key = "open", name = "Open", category = "TODO"),
                    WorkflowStateView(key = "in_progress", name = "In Progress", category = "IN_PROGRESS"),
                    WorkflowStateView(key = "done", name = "Done", category = "DONE"),
                )

            val result = sut.getCycleTime(actor, projectKey, from, to)

            result.cycleTime.samples shouldHaveSize 1
            result.cycleTime.samples[0].issueKey shouldBe "CTP-1"
            result.cycleTime.samples[0].seconds shouldBe Duration.between(inProgressAt, doneAt).seconds

            result.leadTime.samples shouldHaveSize 1
            result.leadTime.samples[0].issueKey shouldBe "CTP-1"
            result.leadTime.samples[0].seconds shouldBe Duration.between(createdAt, doneAt).seconds
        }

        it("IN_PROGRESS 전이가 없으면 firstInProgressAt=null → cycle 표본 제외, lead 표본에는 남는다") {
            stubBrowseAndAccess()
            val typeId = 11L
            val issueId = UUID.randomUUID()
            val createdAt = Instant.parse("2026-06-01T00:00:00Z")
            val doneAt = Instant.parse("2026-06-02T00:00:00Z")
            val issue = CycleTimeIssueSourceRow(issueId, "CTP-2", typeId, "done", createdAt)
            every {
                issueRepository.fetchActiveVisibleIssuesForCycleTime(projectKey, actor.value, unrestrictedAccess)
            } returns listOf(issue)
            every { statusHistoryRepository.fetchStatusChanges(setOf(issueId)) } returns
                listOf(
                    // TODO → DONE 직행, IN_PROGRESS 를 한 번도 거치지 않는다.
                    StatusChangeRow(issueId, doneAt, 1L, fromValue = "open", toValue = "done"),
                )
            every { issueTypeRepository.findAll() } returns
                listOf(
                    IssueType.create(key = IssueTypeKey("notrans"), name = "No Transition")
                        .copy(id = IssueTypeId(typeId)),
                )
            every {
                workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("notrans"))
            } returns
                listOf(
                    WorkflowStateView(key = "open", name = "Open", category = "TODO"),
                    WorkflowStateView(key = "done", name = "Done", category = "DONE"),
                )

            val result = sut.getCycleTime(actor, projectKey, from, to)

            result.cycleTime.samples shouldHaveSize 0
            result.leadTime.samples shouldHaveSize 1
            result.leadTime.samples[0].issueKey shouldBe "CTP-2"
            result.leadTime.samples[0].seconds shouldBe Duration.between(createdAt, doneAt).seconds
        }
    }

    // ── 삭제된(카탈로그에 없는) 상태 키 — TODO 폴백 ──────────────────────────────

    describe("삭제된 상태 키 폴백") {
        it("카탈로그에 없는 상태 키는 TODO 로 폴백되어 IN_PROGRESS 진입으로 오집계되지 않는다") {
            stubBrowseAndAccess()
            val typeId = 12L
            val issueId = UUID.randomUUID()
            val createdAt = Instant.parse("2026-06-01T00:00:00Z")
            val archivedAt = Instant.parse("2026-06-02T00:00:00Z")
            val doneAt = Instant.parse("2026-06-03T00:00:00Z")
            val issue = CycleTimeIssueSourceRow(issueId, "CTP-3", typeId, "done", createdAt)
            every {
                issueRepository.fetchActiveVisibleIssuesForCycleTime(projectKey, actor.value, unrestrictedAccess)
            } returns listOf(issue)
            every { statusHistoryRepository.fetchStatusChanges(setOf(issueId)) } returns
                listOf(
                    // "archived_wip" 는 삭제되어 카탈로그(listStates)에 더 이상 존재하지 않는 상태 키.
                    StatusChangeRow(issueId, archivedAt, 1L, fromValue = "open", toValue = "archived_wip"),
                    StatusChangeRow(issueId, doneAt, 2L, fromValue = "archived_wip", toValue = "done"),
                )
            every { issueTypeRepository.findAll() } returns
                listOf(
                    IssueType.create(key = IssueTypeKey("gaptype"), name = "Gap Type").copy(id = IssueTypeId(typeId)),
                )
            every {
                workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("gaptype"))
            } returns
                listOf(
                    WorkflowStateView(key = "open", name = "Open", category = "TODO"),
                    WorkflowStateView(key = "done", name = "Done", category = "DONE"),
                )

            val result = sut.getCycleTime(actor, projectKey, from, to)

            // archived_wip 는 categoryMap 부재 → TODO 폴백이라 IN_PROGRESS 진입으로 잡히지 않는다.
            result.cycleTime.samples shouldHaveSize 0
            result.leadTime.samples shouldHaveSize 1
            result.leadTime.samples[0].issueKey shouldBe "CTP-3"
            result.leadTime.samples[0].seconds shouldBe Duration.between(createdAt, doneAt).seconds
        }
    }
})
