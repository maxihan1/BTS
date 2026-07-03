// CfdService 단위 테스트 — MockK. 권한·빈이슈 방어·타입별 listStates 캐싱·초기상태 역산·동일날짜 말일 카테고리 검증 (FR-RP-03 Task 4)

package com.bts.issue.cfd.application

import com.bts.issue.adapter.outbound.velocity.IsolatedWorkflowStateLookup
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.repository.CfdIssueSourceRow
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
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [CfdService] 단위 테스트.
 *
 * permissionResolver / issueRepository / statusHistoryRepository / securityDirectory /
 * issueTypeRepository / workflowStateLookup 6개 협력자를 MockK 로 stub 한다.
 *
 * 검증 목록.
 * - BROWSE 권한 없음 → [IssueAccessDeniedException], repo 조회 미수행(probe 차단)
 * - 가시 이슈 0개 → 창 전체 0점, fetchStatusChanges/listStates 미호출(빈 IN 방어)
 * - 같은 타입 이슈 여러 개라도 listStates 는 타입당 1회(N+1 차단)
 * - WorkflowSchemeNoDefaultException → 해당 타입 TODO 폴백, 예외 전파 없음(500 차단)
 * - 초기 상태 역산 — 전이 없으면 currentStateKey, 있으면 첫 전이 fromValue
 * - 같은 날 다중 전이 → end-of-day(마지막 toValue) 카테고리만 반영
 */
class CfdServiceTest : DescribeSpec({

    val permissionResolver = mockk<IssuePermissionResolver>()
    val issueRepository = mockk<IssueRepository>()
    val statusHistoryRepository = mockk<StatusHistoryRepository>()
    val securityDirectory = mockk<IssueSecurityDirectory>()
    val issueTypeRepository = mockk<IssueTypeRepository>()
    val workflowStateLookup = mockk<IsolatedWorkflowStateLookup>()

    val sut =
        CfdService(
            permissionResolver = permissionResolver,
            issueRepository = issueRepository,
            statusHistoryRepository = statusHistoryRepository,
            securityDirectory = securityDirectory,
            issueTypeRepository = issueTypeRepository,
            workflowStateLookup = workflowStateLookup,
        )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "CFDP"
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
                sut.getCfd(actor, projectKey, from, to)
            }

            verify(exactly = 0) { issueRepository.fetchActiveVisibleIssuesForCfd(any(), any(), any()) }
        }
    }

    // ── 가시 이슈 0개 — 빈 IN 방어 ────────────────────────────────────────────

    describe("가시 이슈 0개") {
        it("창 전체 0점 반환, fetchStatusChanges/listStates/findAll 미호출") {
            stubBrowseAndAccess()
            every {
                issueRepository.fetchActiveVisibleIssuesForCfd(projectKey, actor.value, unrestrictedAccess)
            } returns emptyList()

            val result = sut.getCfd(actor, projectKey, from, to)

            result.points shouldHaveSize 5
            result.points.forEach { point ->
                point.todoCount shouldBe 0
                point.inProgressCount shouldBe 0
                point.doneCount shouldBe 0
            }

            verify(exactly = 0) { statusHistoryRepository.fetchStatusChanges(any()) }
            // value class(ProjectKey/IssueTypeKey) 인자에 any() 매처를 쓰면 MockK 리플렉션 오류가
            // 발생하므로(IssueEpicServiceProgressTest 선례), 인자 매칭 없이 호출 자체가 없었음을 검증한다.
            verify { workflowStateLookup wasNot Called }
            verify(exactly = 0) { issueTypeRepository.findAll() }
        }
    }

    // ── 타입별 listStates 캐싱 — N+1 차단 ──────────────────────────────────────

    describe("타입별 listStates 캐싱") {
        it("같은 타입 이슈 여러 개라도 listStates 는 타입당 1회 호출") {
            stubBrowseAndAccess()
            val typeId = 10L
            val issueId1 = UUID.randomUUID()
            val issueId2 = UUID.randomUUID()
            val issues =
                listOf(
                    CfdIssueSourceRow(issueId1, typeId, "open", Instant.parse("2026-06-01T00:00:00Z")),
                    CfdIssueSourceRow(issueId2, typeId, "open", Instant.parse("2026-06-02T00:00:00Z")),
                )
            every {
                issueRepository.fetchActiveVisibleIssuesForCfd(projectKey, actor.value, unrestrictedAccess)
            } returns issues
            every {
                statusHistoryRepository.fetchStatusChanges(setOf(issueId1, issueId2))
            } returns emptyList()
            val cacheType =
                IssueType.create(key = IssueTypeKey("cachetype"), name = "Cache Type").copy(id = IssueTypeId(typeId))
            every { issueTypeRepository.findAll() } returns listOf(cacheType)
            every {
                workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("cachetype"))
            } returns listOf(WorkflowStateView(key = "open", name = "Open", category = "TODO"))

            sut.getCfd(actor, projectKey, from, to)

            verify(exactly = 1) { workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("cachetype")) }
        }
    }

    // ── WorkflowSchemeNoDefaultException 폴백 — 500 차단 ───────────────────────

    describe("스킴 미할당 타입") {
        it("WorkflowSchemeNoDefaultException → 예외 전파 없이 TODO 폴백") {
            stubBrowseAndAccess()
            val typeId = 20L
            val issueId = UUID.randomUUID()
            val issue = CfdIssueSourceRow(issueId, typeId, "done", Instant.parse("2026-06-01T00:00:00Z"))
            every {
                issueRepository.fetchActiveVisibleIssuesForCfd(projectKey, actor.value, unrestrictedAccess)
            } returns listOf(issue)
            every { statusHistoryRepository.fetchStatusChanges(setOf(issueId)) } returns emptyList()
            every { issueTypeRepository.findAll() } returns
                listOf(
                    IssueType.create(key = IssueTypeKey("gaptype"), name = "Gap Type").copy(id = IssueTypeId(typeId)),
                )
            every {
                workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("gaptype"))
            } throws WorkflowSchemeNoDefaultException()

            val singleDay = LocalDate.of(2026, 6, 1)

            shouldNotThrowAny {
                sut.getCfd(actor, projectKey, singleDay, singleDay)
            }

            // categoryMap 이 빈 맵으로 폴백되므로 currentStateKey="done" 이어도 TODO 로 집계된다.
            val result = sut.getCfd(actor, projectKey, singleDay, singleDay)
            result.points shouldHaveSize 1
            result.points[0].todoCount shouldBe 1
            result.points[0].doneCount shouldBe 0
        }
    }

    // ── 초기 상태 역산 ──────────────────────────────────────────────────────────

    describe("초기 상태 역산") {
        it("전이 이력이 없으면 currentStateKey 카테고리로 전 기간 집계") {
            stubBrowseAndAccess()
            val typeId = 30L
            val issueId = UUID.randomUUID()
            val issue = CfdIssueSourceRow(issueId, typeId, "done", Instant.parse("2026-06-01T00:00:00Z"))
            every {
                issueRepository.fetchActiveVisibleIssuesForCfd(projectKey, actor.value, unrestrictedAccess)
            } returns listOf(issue)
            every { statusHistoryRepository.fetchStatusChanges(setOf(issueId)) } returns emptyList()
            val noTransType =
                IssueType.create(key = IssueTypeKey("notrans"), name = "No Transition").copy(id = IssueTypeId(typeId))
            every { issueTypeRepository.findAll() } returns listOf(noTransType)
            every {
                workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("notrans"))
            } returns listOf(WorkflowStateView(key = "done", name = "Done", category = "DONE"))

            val singleDay = LocalDate.of(2026, 6, 1)
            val result = sut.getCfd(actor, projectKey, singleDay, singleDay)

            result.points[0].doneCount shouldBe 1
            result.points[0].todoCount shouldBe 0
        }

        it("전이 이력이 있으면 첫 전이의 fromValue 가 초기 상태") {
            stubBrowseAndAccess()
            val typeId = 31L
            val issueId = UUID.randomUUID()
            // currentStateKey 는 무시되고 첫 전이의 fromValue("open")가 초기 상태로 쓰인다.
            val issue = CfdIssueSourceRow(issueId, typeId, "in_progress", Instant.parse("2026-06-01T00:00:00Z"))
            every {
                issueRepository.fetchActiveVisibleIssuesForCfd(projectKey, actor.value, unrestrictedAccess)
            } returns listOf(issue)
            every { statusHistoryRepository.fetchStatusChanges(setOf(issueId)) } returns
                listOf(
                    StatusChangeRow(
                        issueId = issueId,
                        changedAt = Instant.parse("2026-06-02T00:00:00Z"),
                        groupId = 1L,
                        fromValue = "open",
                        toValue = "in_progress",
                    ),
                )
            every { issueTypeRepository.findAll() } returns
                listOf(
                    IssueType.create(key = IssueTypeKey("withtrans"), name = "With Transition")
                        .copy(id = IssueTypeId(typeId)),
                )
            every {
                workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("withtrans"))
            } returns
                listOf(
                    WorkflowStateView(key = "open", name = "Open", category = "TODO"),
                    WorkflowStateView(key = "in_progress", name = "In Progress", category = "IN_PROGRESS"),
                )

            val windowFrom = LocalDate.of(2026, 6, 1)
            val windowTo = LocalDate.of(2026, 6, 2)
            val result = sut.getCfd(actor, projectKey, windowFrom, windowTo)

            result.points shouldHaveSize 2
            // 2026-06-01: 초기 상태(open→TODO)
            result.points[0].todoCount shouldBe 1
            result.points[0].inProgressCount shouldBe 0
            // 2026-06-02: 전이 이후(in_progress→IN_PROGRESS)
            result.points[1].todoCount shouldBe 0
            result.points[1].inProgressCount shouldBe 1
        }
    }

    // ── 같은 날 다중 전이 — end-of-day 카테고리만 반영 ─────────────────────────

    describe("같은 날 다중 전이") {
        it("마지막 toValue 만 그 날짜 카테고리로 반영된다") {
            stubBrowseAndAccess()
            val typeId = 40L
            val issueId = UUID.randomUUID()
            val issue = CfdIssueSourceRow(issueId, typeId, "done", Instant.parse("2026-06-01T00:00:00Z"))
            every {
                issueRepository.fetchActiveVisibleIssuesForCfd(projectKey, actor.value, unrestrictedAccess)
            } returns listOf(issue)
            every { statusHistoryRepository.fetchStatusChanges(setOf(issueId)) } returns
                listOf(
                    StatusChangeRow(
                        issueId = issueId,
                        changedAt = Instant.parse("2026-06-01T05:00:00Z"),
                        groupId = 1L,
                        fromValue = "open",
                        toValue = "in_progress",
                    ),
                    StatusChangeRow(
                        issueId = issueId,
                        changedAt = Instant.parse("2026-06-01T20:00:00Z"),
                        groupId = 2L,
                        fromValue = "in_progress",
                        toValue = "done",
                    ),
                )
            every { issueTypeRepository.findAll() } returns
                listOf(
                    IssueType.create(key = IssueTypeKey("sameday"), name = "Same Day").copy(id = IssueTypeId(typeId)),
                )
            every {
                workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("sameday"))
            } returns
                listOf(
                    WorkflowStateView(key = "open", name = "Open", category = "TODO"),
                    WorkflowStateView(key = "in_progress", name = "In Progress", category = "IN_PROGRESS"),
                    WorkflowStateView(key = "done", name = "Done", category = "DONE"),
                )

            val singleDay = LocalDate.of(2026, 6, 1)
            val result = sut.getCfd(actor, projectKey, singleDay, singleDay)

            result.points shouldHaveSize 1
            result.points[0].doneCount shouldBe 1
            result.points[0].inProgressCount shouldBe 0
            result.points[0].todoCount shouldBe 0
        }
    }
})

/**
 * WorkflowSchemeNoDefaultException 테스트 전용 스텁 예외.
 *
 * 서비스 코드는 `e.javaClass.simpleName == "WorkflowSchemeNoDefaultException"` 으로 폴백을 결정한다.
 * project-workflow BC 내부 예외는 import 불가하므로 동일 simpleName 을 가진 테스트 전용 클래스를 사용한다
 * ([com.bts.issue.epic.application.IssueEpicServiceProgressTest] 선례와 동일 패턴).
 * `private` 선언으로 이 파일 밖에서는 보이지 않는다.
 */
private class WorkflowSchemeNoDefaultException :
    RuntimeException("test stub — workflow scheme not assigned")
