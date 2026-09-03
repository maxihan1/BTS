// ProjectSummaryService 단위 테스트 — MockK. 권한 선검증 · DONE 2주 특례 · 완료 판정 · degrade

package com.bts.issue.summary.application

import com.bts.issue.adapter.outbound.velocity.IsolatedWorkflowStateLookup
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.repository.IssueRepository
import com.bts.issue.repository.ProjectActivityRow
import com.bts.issue.repository.SummaryIssueRow
import com.bts.issue.statushistory.StatusCategory
import com.bts.issue.statushistory.repository.StatusChangeRow
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateView
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [ProjectSummaryService] 단위 테스트.
 *
 * 협력자 6개를 MockK 로 stub 하고 [Clock.fixed] 로 창을 고정한다.
 *
 * 기준 시각 = 2026-09-03T12:00Z → 최근 7일 = [08-28, 09-04), 직전 7일 = [08-21, 08-28).
 *
 * 검증 목록.
 * - BROWSE 권한 없음 → [IssueAccessDeniedException], repo 조회 미수행(존재 probe 차단)
 * - 가시 이슈 0건 → 이력·워크플로우·사용자 조회 없이 빈 요약(빈 IN 방어)
 * - 완료 판정은 **상태 이력의 DONE 진입 시각** 기준 — updated_at 이 아니다
 * - DONE 안에서의 상태 이동은 완료로 두 번 세지 않는다
 * - **DONE 2주 특례** — 2주 안에 완료 이력이 없는 DONE 이슈는 상태 개요에서 빠진다.
 *   같은 이슈가 우선순위·유형·담당자 분포에는 **그대로 남는다**
 * - 마감/지연은 미완료 이슈만 센다
 * - 같은 타입 이슈가 여럿이어도 listStates 는 타입당 1회(N+1 차단)
 * - WorkflowSchemeNoDefaultException → TODO 폴백, 예외 전파 없음(500 차단)
 * - UserLookupPort 실패 → 이름만 null 로 degrade, 조회는 계속된다
 * - 활동 피드는 그룹 단위로 묶이고 시스템 변경은 actorId 가 null
 */
@Suppress("LargeClass")
class ProjectSummaryServiceTest : DescribeSpec({

    val permissionResolver = mockk<IssuePermissionResolver>()
    val issueRepository = mockk<IssueRepository>()
    val securityDirectory = mockk<IssueSecurityDirectory>()
    val issueTypeRepository = mockk<IssueTypeRepository>()
    val workflowStateLookup = mockk<IsolatedWorkflowStateLookup>()
    val userLookupPort = mockk<UserLookupPort>()

    val now = Instant.parse("2026-09-03T12:00:00Z")

    val sut =
        ProjectSummaryService(
            permissionResolver = permissionResolver,
            issueRepository = issueRepository,
            securityDirectory = securityDirectory,
            issueTypeRepository = issueTypeRepository,
            workflowStateLookup = workflowStateLookup,
            userLookupPort = userLookupPort,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "SUMP"
    val projectScope = IssueScope.Project(projectKey)

    val taskTypeId = 1L
    val bugTypeId = 2L
    val taskType = issueType(taskTypeId, "task", "작업")
    val bugType = issueType(bugTypeId, "bug", "버그")

    val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /** open=TODO · doing=IN_PROGRESS · done=DONE 3상태 워크플로우. */
    val states =
        listOf(
            WorkflowStateView(key = "open", name = "할 일", category = "TODO"),
            WorkflowStateView(key = "doing", name = "진행 중", category = "IN_PROGRESS"),
            WorkflowStateView(key = "done", name = "완료", isDone = true, category = "DONE"),
        )

    fun stubBrowseAndAccess() {
        every { permissionResolver.hasPermission(actor.value, IssuePermission.BROWSE, projectScope) } returns true
        every { securityDirectory.accessibleLevels(actor.value, projectKey) } returns unrestrictedAccess
    }

    fun stubTypesAndStates() {
        every { issueTypeRepository.findAll() } returns listOf(taskType, bugType)
        every { workflowStateLookup.listStates(ProjectKey.of(projectKey), any()) } returns states
    }

    fun stubIssues(rows: List<SummaryIssueRow>) {
        every {
            issueRepository.fetchActiveVisibleIssuesForSummary(projectKey, actor.value, unrestrictedAccess)
        } returns rows
    }

    fun stubChanges(rows: List<StatusChangeRow>) {
        every {
            issueRepository.fetchStatusChangesSinceForProject(
                projectKey,
                actor.value,
                unrestrictedAccess,
                any<OffsetDateTime>(),
            )
        } returns rows
    }

    beforeEach {
        clearMocks(
            permissionResolver,
            issueRepository,
            securityDirectory,
            issueTypeRepository,
            workflowStateLookup,
            userLookupPort,
        )
        every { userLookupPort.findDisplayNamesByIds(any()) } returns emptyMap()
    }

    // ── 권한 ──────────────────────────────────────────────────────────────────

    describe("BROWSE 권한 검증") {
        it("권한이 없으면 IssueAccessDeniedException 을 던지고 repo 를 조회하지 않는다") {
            every {
                permissionResolver.hasPermission(actor.value, IssuePermission.BROWSE, projectScope)
            } returns false

            shouldThrow<IssueAccessDeniedException> { sut.getSummary(actor, projectKey) }

            // 권한 판정이 조회보다 먼저다 — 뒤집히면 권한 없는 사용자가 403 대신 404 로 존재를 probe 한다.
            verify { issueRepository wasNot Called }
            verify { securityDirectory wasNot Called }
        }

        it("활동 피드도 같은 순서로 권한을 먼저 검증한다") {
            every {
                permissionResolver.hasPermission(actor.value, IssuePermission.BROWSE, projectScope)
            } returns false

            shouldThrow<IssueAccessDeniedException> { sut.getActivity(actor, projectKey, 20) }

            verify { issueRepository wasNot Called }
        }
    }

    // ── 빈 프로젝트 방어 ───────────────────────────────────────────────────────

    describe("가시 이슈가 없는 프로젝트") {
        it("이력·워크플로우·사용자 조회 없이 0으로 채운 요약을 반환한다") {
            stubBrowseAndAccess()
            stubIssues(emptyList())

            val summary = sut.getSummary(actor, projectKey)

            summary.recent.completed.current shouldBe 0
            summary.recent.created.current shouldBe 0
            summary.statusOverview.shouldHaveSize(0)
            summary.priorityBreakdown.shouldHaveSize(0)
            summary.teamWorkload.shouldHaveSize(0)
            // jOOQ 빈 IN 절 방어 — 이슈가 0건이면 이력을 물을 이유가 없다.
            verify(exactly = 0) {
                issueRepository.fetchStatusChangesSinceForProject(any(), any(), any(), any())
            }
            verify { workflowStateLookup wasNot Called }
        }
    }

    // ── 완료 판정 (D1) ────────────────────────────────────────────────────────

    describe("최근 7일 완료 판정") {
        it("updated_at 이 아니라 DONE 진입 시각으로 센다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            val issueId = UUID.randomUUID()
            // updated_at 은 오늘이지만 완료는 3개월 전 — updated_at 기반이면 「오늘 완료」로 거짓 양성.
            stubIssues(
                listOf(
                    summaryRow(
                        issueId = issueId,
                        stateKey = "done",
                        createdAt = Instant.parse("2026-05-01T00:00:00Z"),
                        updatedAt = Instant.parse("2026-09-03T09:00:00Z"),
                    ),
                ),
            )
            stubChanges(emptyList())

            val summary = sut.getSummary(actor, projectKey)

            summary.recent.completed.current shouldBe 0
            summary.recent.updated.current shouldBe 1
        }

        it("최근 7일 안의 DONE 진입은 current, 직전 7일 안의 진입은 previous 로 센다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            val recentIssue = UUID.randomUUID()
            val previousIssue = UUID.randomUUID()
            stubIssues(
                listOf(
                    summaryRow(issueId = recentIssue, stateKey = "done"),
                    summaryRow(issueId = previousIssue, stateKey = "done"),
                ),
            )
            stubChanges(
                listOf(
                    statusChange(recentIssue, Instant.parse("2026-08-30T10:00:00Z"), "doing", "done"),
                    statusChange(previousIssue, Instant.parse("2026-08-23T10:00:00Z"), "doing", "done"),
                ),
            )

            val summary = sut.getSummary(actor, projectKey)

            summary.recent.completed.current shouldBe 1
            summary.recent.completed.previous shouldBe 1
        }

        it("DONE 안에서의 상태 이동은 완료로 세지 않는다") {
            stubBrowseAndAccess()
            every { issueTypeRepository.findAll() } returns listOf(taskType, bugType)
            // done · done2 둘 다 DONE 카테고리 — 사이의 이동은 새 완료가 아니다.
            every { workflowStateLookup.listStates(ProjectKey.of(projectKey), any()) } returns
                states + WorkflowStateView(key = "done2", name = "보류 완료", isDone = true, category = "DONE")
            val issueId = UUID.randomUUID()
            stubIssues(listOf(summaryRow(issueId = issueId, stateKey = "done2")))
            stubChanges(
                listOf(statusChange(issueId, Instant.parse("2026-08-30T10:00:00Z"), "done", "done2")),
            )

            val summary = sut.getSummary(actor, projectKey)

            summary.recent.completed.current shouldBe 0
        }

        it("같은 이슈가 창 안에서 두 번 완료돼도 1로 센다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            val issueId = UUID.randomUUID()
            stubIssues(listOf(summaryRow(issueId = issueId, stateKey = "done")))
            stubChanges(
                listOf(
                    statusChange(issueId, Instant.parse("2026-08-29T10:00:00Z"), "doing", "done"),
                    statusChange(issueId, Instant.parse("2026-08-31T10:00:00Z"), "doing", "done"),
                ),
            )

            val summary = sut.getSummary(actor, projectKey)

            summary.recent.completed.current shouldBe 1
        }
    }

    // ── DONE 2주 특례 (D2) ────────────────────────────────────────────────────

    describe("상태 개요의 DONE 2주 특례") {
        it("2주 안에 완료 이력이 없는 DONE 이슈는 상태 개요에서 빠진다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            val staleDone = UUID.randomUUID()
            val freshDone = UUID.randomUUID()
            stubIssues(
                listOf(
                    summaryRow(issueId = staleDone, stateKey = "done"),
                    summaryRow(issueId = freshDone, stateKey = "done"),
                ),
            )
            // 이력은 historySince(2주 전) 이후만 담기므로 staleDone 은 아예 등장하지 않는다.
            stubChanges(
                listOf(statusChange(freshDone, Instant.parse("2026-08-30T10:00:00Z"), "doing", "done")),
            )

            val summary = sut.getSummary(actor, projectKey)

            summary.statusOverview.shouldHaveSize(1)
            summary.statusOverview.single().statusKey shouldBe "done"
            summary.statusOverview.single().count shouldBe 1
        }

        it("특례에서 빠진 DONE 이슈도 우선순위·유형·담당자 분포에는 그대로 남는다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            val staleDone = UUID.randomUUID()
            val assignee = UUID.randomUUID()
            stubIssues(
                listOf(summaryRow(issueId = staleDone, stateKey = "done", priority = 2, assigneeId = assignee)),
            )
            stubChanges(emptyList())

            val summary = sut.getSummary(actor, projectKey)

            // 상태 개요만 비고, 나머지 세 분포는 이슈를 잃지 않는다 — Jira 는 2주를 Done 버킷에만 건다.
            summary.statusOverview.shouldHaveSize(0)
            summary.priorityBreakdown.single().count shouldBe 1
            summary.typesOfWork.single().count shouldBe 1
            summary.teamWorkload.single().count shouldBe 1
        }

        it("TODO·IN_PROGRESS 상태에는 특례가 걸리지 않는다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            stubIssues(
                listOf(
                    summaryRow(issueId = UUID.randomUUID(), stateKey = "open"),
                    summaryRow(issueId = UUID.randomUUID(), stateKey = "doing"),
                ),
            )
            stubChanges(emptyList())

            val summary = sut.getSummary(actor, projectKey)

            summary.statusOverview.map { it.statusKey } shouldContainExactly listOf("open", "doing")
            summary.statusOverview.map { it.category } shouldContainExactly
                listOf(StatusCategory.TODO, StatusCategory.IN_PROGRESS)
        }
    }

    // ── 마감·지연 ─────────────────────────────────────────────────────────────

    describe("마감 예정과 지연") {
        it("향후 7일 마감과 지연을 나눠 세고 완료된 이슈는 둘 다에서 뺀다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            stubIssues(
                listOf(
                    // 오늘(09-03) 마감 — due 경계 하한
                    summaryRow(UUID.randomUUID(), stateKey = "open", dueDate = LocalDate.of(2026, 9, 3)),
                    // 09-09 마감 — due 경계 상한
                    summaryRow(UUID.randomUUID(), stateKey = "open", dueDate = LocalDate.of(2026, 9, 9)),
                    // 09-10 마감 — 창 밖
                    summaryRow(UUID.randomUUID(), stateKey = "open", dueDate = LocalDate.of(2026, 9, 10)),
                    // 09-01 마감 미완료 — 지연
                    summaryRow(UUID.randomUUID(), stateKey = "doing", dueDate = LocalDate.of(2026, 9, 1)),
                    // 09-01 마감이지만 완료 — 지연이 아니다
                    summaryRow(UUID.randomUUID(), stateKey = "done", dueDate = LocalDate.of(2026, 9, 1)),
                    // 마감일 미지정 — 어디에도 안 센다
                    summaryRow(UUID.randomUUID(), stateKey = "open", dueDate = null),
                ),
            )
            stubChanges(emptyList())

            val summary = sut.getSummary(actor, projectKey)

            summary.upcoming.due shouldBe 2
            summary.upcoming.overdue shouldBe 1
        }
    }

    // ── N+1 차단 · 폴백 ───────────────────────────────────────────────────────

    describe("워크플로우 조회") {
        it("같은 타입 이슈가 여럿이어도 listStates 는 타입당 1회만 호출한다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            stubIssues(
                listOf(
                    summaryRow(UUID.randomUUID(), typeId = taskTypeId),
                    summaryRow(UUID.randomUUID(), typeId = taskTypeId),
                    summaryRow(UUID.randomUUID(), typeId = taskTypeId),
                    summaryRow(UUID.randomUUID(), typeId = bugTypeId),
                ),
            )
            stubChanges(emptyList())

            sut.getSummary(actor, projectKey)

            verify(exactly = 1) { workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("task")) }
            verify(exactly = 1) { workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("bug")) }
        }

        it("워크플로우 스킴이 없으면 TODO 로 폴백하고 예외를 전파하지 않는다") {
            stubBrowseAndAccess()
            every { issueTypeRepository.findAll() } returns listOf(taskType, bugType)
            every { workflowStateLookup.listStates(ProjectKey.of(projectKey), any()) } throws
                WorkflowSchemeNoDefaultException("no default scheme")
            stubIssues(listOf(summaryRow(UUID.randomUUID(), stateKey = "open")))
            stubChanges(emptyList())

            val summary = shouldNotThrowAny { sut.getSummary(actor, projectKey) }

            summary.statusOverview.single().category shouldBe StatusCategory.TODO
            // 이름을 해석하지 못하면 null — 화면이 키로 폴백할 수 있어야 한다.
            summary.statusOverview.single().statusName.shouldBeNull()
        }
    }

    // ── 담당자 이름 degrade ───────────────────────────────────────────────────

    describe("담당자 표시명") {
        it("UserLookupPort 가 실패해도 이름만 null 로 degrade 하고 집계는 유지된다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            val assignee = UUID.randomUUID()
            stubIssues(listOf(summaryRow(UUID.randomUUID(), assigneeId = assignee)))
            stubChanges(emptyList())
            every { userLookupPort.findDisplayNamesByIds(any()) } throws IllegalStateException("identity-access down")

            val summary = shouldNotThrowAny { sut.getSummary(actor, projectKey) }

            summary.teamWorkload.single().assigneeId shouldBe assignee
            summary.teamWorkload.single().assigneeName.shouldBeNull()
            summary.teamWorkload.single().count shouldBe 1
        }

        it("미할당 버킷은 assigneeId 가 null 이고 항상 마지막에 온다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            val assignee = UUID.randomUUID()
            stubIssues(
                listOf(
                    summaryRow(UUID.randomUUID(), assigneeId = null),
                    summaryRow(UUID.randomUUID(), assigneeId = assignee),
                    summaryRow(UUID.randomUUID(), assigneeId = assignee),
                ),
            )
            stubChanges(emptyList())
            every { userLookupPort.findDisplayNamesByIds(setOf(assignee)) } returns mapOf(assignee to "홍길동")

            val summary = sut.getSummary(actor, projectKey)

            summary.teamWorkload.map { it.assigneeId } shouldContainExactly listOf(assignee, null)
            summary.teamWorkload.first().assigneeName shouldBe "홍길동"
            summary.teamWorkload.last().assigneeName.shouldBeNull()
        }
    }

    // ── 활동 피드 ─────────────────────────────────────────────────────────────

    describe("활동 피드") {
        it("같은 그룹의 항목을 한 줄로 묶고 이슈 키와 라벨을 싣는다") {
            stubBrowseAndAccess()
            val at = Instant.parse("2026-09-03T09:00:00Z")
            val who = UUID.randomUUID()
            every { issueRepository.fetchProjectActivity(projectKey, actor.value, unrestrictedAccess, 20) } returns
                listOf(
                    activityRow(1L, "SUMP-1", who, at, "status", "doing", "done", "진행 중", "완료"),
                    activityRow(1L, "SUMP-1", who, at, "assignee", null, who.toString(), null, "홍길동"),
                )
            every { userLookupPort.findDisplayNamesByIds(setOf(who)) } returns mapOf(who to "홍길동")

            val entries = sut.getActivity(actor, projectKey, 20)

            entries.shouldHaveSize(1)
            entries.single().issueKey shouldBe "SUMP-1"
            entries.single().actorName shouldBe "홍길동"
            entries.single().items.map { it.field } shouldContainExactly listOf("status", "assignee")
            entries.single().items.first().toLabel shouldBe "완료"
        }

        it("시스템 자동 변경은 actorId 와 actorName 이 모두 null 이다") {
            stubBrowseAndAccess()
            every { issueRepository.fetchProjectActivity(projectKey, actor.value, unrestrictedAccess, 20) } returns
                listOf(
                    activityRow(
                        1L, "SUMP-1", null, Instant.parse("2026-09-03T09:00:00Z"),
                        "status", "doing", "done", null, null,
                    ),
                )

            val entries = sut.getActivity(actor, projectKey, 20)

            entries.single().actorId.shouldBeNull()
            entries.single().actorName.shouldBeNull()
            // 조회할 actor 가 없으면 identity-access 를 부르지 않는다.
            verify { userLookupPort wasNot Called }
        }

        it("이력이 없으면 빈 목록이고 사용자 조회를 하지 않는다") {
            stubBrowseAndAccess()
            every {
                issueRepository.fetchProjectActivity(projectKey, actor.value, unrestrictedAccess, 20)
            } returns emptyList()

            sut.getActivity(actor, projectKey, 20).shouldHaveSize(0)

            verify { userLookupPort wasNot Called }
        }
    }
})

/** project-workflow BC 내부 예외를 simpleName 으로 흉내 낸다 — 직접 import 불가. */
private class WorkflowSchemeNoDefaultException(message: String) : RuntimeException(message)

private fun issueType(
    id: Long,
    key: String,
    name: String,
): IssueType =
    IssueType(
        id = IssueTypeId(id),
        key = IssueTypeKey(key),
        name = name,
        description = null,
        iconName = null,
        isStandard = true,
        hierarchyLevel = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        deletedAt = null,
    )

@Suppress("LongParameterList")
private fun summaryRow(
    issueId: UUID,
    typeId: Long = 1L,
    stateKey: String = "open",
    priority: Int = 3,
    assigneeId: UUID? = null,
    dueDate: LocalDate? = null,
    createdAt: Instant = Instant.parse("2026-09-01T00:00:00Z"),
    updatedAt: Instant = Instant.parse("2026-09-01T00:00:00Z"),
): SummaryIssueRow =
    SummaryIssueRow(
        issueId = issueId,
        typeId = typeId,
        currentStateKey = stateKey,
        priority = priority,
        assigneeId = assigneeId,
        dueDate = dueDate,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun statusChange(
    issueId: UUID,
    changedAt: Instant,
    from: String?,
    to: String?,
): StatusChangeRow =
    StatusChangeRow(
        issueId = issueId,
        changedAt = changedAt,
        groupId = changedAt.toEpochMilli(),
        fromValue = from,
        toValue = to,
    )

@Suppress("LongParameterList")
private fun activityRow(
    groupId: Long,
    issueKey: String,
    actorId: UUID?,
    createdAt: Instant,
    field: String,
    fromValue: String?,
    toValue: String?,
    fromLabel: String?,
    toLabel: String?,
): ProjectActivityRow =
    ProjectActivityRow(
        groupId = groupId,
        issueKey = issueKey,
        actorId = actorId,
        createdAt = createdAt,
        field = field,
        fromValue = fromValue,
        toValue = toValue,
        fromLabel = fromLabel,
        toLabel = toLabel,
    )
