// ProjectSummaryService 단위 테스트 — MockK. 권한 선검증 · DONE 2주 특례 · 완료 판정 · degrade

package com.bts.issue.summary.application

import com.bts.issue.adapter.outbound.velocity.IsolatedWorkflowStateLookup
import com.bts.issue.application.IssueChangeItemMasker
import com.bts.issue.comment.repository.CommentRepository
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
import com.bts.shared.permission.FieldKind
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
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
    val commentRepository = mockk<CommentRepository>()
    val fieldPermissionResolver = mockk<FieldPermissionResolver>()

    val now = Instant.parse("2026-09-03T12:00:00Z")

    // 마스킹은 mock 이 아니라 **실물 협력자**로 태운다 — 단건 이력 경로와 같은 판정을 쓰는지가
    // 검증 대상이므로, 여기서 mock 으로 대체하면 정작 확인하려던 것이 사라진다.
    val sut =
        ProjectSummaryService(
            permissionResolver = permissionResolver,
            issueRepository = issueRepository,
            securityDirectory = securityDirectory,
            issueTypeRepository = issueTypeRepository,
            workflowStateLookup = workflowStateLookup,
            userLookupPort = userLookupPort,
            masker = IssueChangeItemMasker(issueRepository, commentRepository, fieldPermissionResolver),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "SUMP"
    val projectId = UUID.randomUUID()
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

    /**
     * 활동 피드의 이슈 단위 VIEW 게이트를 허용으로 stub 한다.
     *
     * BROWSE 와 별도로 명시해야 한다 — 두 권한은 독립 매트릭스 권한이고(FR-PM-05), 한 번에 열어 주면
     * 게이트를 지워도 테스트가 통과한다.
     */
    fun stubViewAllowed(vararg issueKeys: String) {
        issueKeys.forEach { key ->
            every {
                permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Issue(key))
            } returns true
        }
    }

    /**
     * 타입 카탈로그와 워크플로우 상태를 stub 한다.
     *
     * [IssueTypeKey] 는 value class 라 MockK 의 `any()` 가 `ValueClassAwareCaller` 에서 깨진다.
     * 타입 키를 하나씩 명시해야 한다([com.bts.issue.cfd.application.CfdServiceTest] 도 같은 방식).
     */
    fun stubTypesAndStates(stateList: List<WorkflowStateView> = states) {
        every { issueTypeRepository.findAll() } returns listOf(taskType, bugType)
        every { workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("task")) } returns stateList
        every { workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("bug")) } returns stateList
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
            commentRepository,
            fieldPermissionResolver,
        )
        every { userLookupPort.findDisplayNamesByIds(any()) } returns emptyMap()
        // 마스킹 기본값 — 필드는 전부 보이고 활성 댓글은 없다. 가림을 검증하는 describe 에서만 override 한다.
        every { issueRepository.findProjectIdByKey(projectKey) } returns projectId
        every { fieldPermissionResolver.visibleFields(actor.value, projectId, any()) } answers { thirdArg() }
        every { commentRepository.findActiveOwners(any()) } returns emptyMap()
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
            // done · done2 둘 다 DONE 카테고리 — 사이의 이동은 새 완료가 아니다.
            stubTypesAndStates(
                states + WorkflowStateView(key = "done2", name = "보류 완료", isDone = true, category = "DONE"),
            )
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
            every { workflowStateLookup.listStates(ProjectKey.of(projectKey), IssueTypeKey("task")) } throws
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
            stubViewAllowed("SUMP-1")
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
            stubViewAllowed("SUMP-1")
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
    // ── 이슈 단위 VIEW 게이트 (B3) ────────────────────────────────────────────

    describe("활동 피드의 이슈 단위 VIEW 게이트") {
        val at = Instant.parse("2026-09-03T09:00:00Z")

        fun stubTwoIssueFeed() {
            every { issueRepository.fetchProjectActivity(projectKey, actor.value, unrestrictedAccess, 20) } returns
                listOf(
                    activityRow(1L, "SUMP-1", null, at, "status", "doing", "done", "진행 중", "완료"),
                    activityRow(2L, "SUMP-2", null, at.minusSeconds(60), "status", "open", "doing", "할 일", "진행 중"),
                )
        }

        it("BROWSE 만 있고 VIEW 가 없는 이슈의 항목은 피드에서 제거된다") {
            stubBrowseAndAccess()
            stubTwoIssueFeed()
            // BROWSE_PROJECT 와 VIEW_ISSUE 는 독립 매트릭스 권한이다(FR-PM-05) — 포함 관계가 아니다.
            every {
                permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Issue("SUMP-1"))
            } returns false
            every {
                permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Issue("SUMP-2"))
            } returns true

            val entries = sut.getActivity(actor, projectKey, 20)

            // 마스킹이 아니라 제거다 — 단건 경로가 404 로 존재를 숨기는 의미와 맞춘다.
            entries.map { it.issueKey } shouldContainExactly listOf("SUMP-2")
        }

        it("같은 픽스처에서 VIEW 를 열면 두 이슈가 모두 나온다") {
            stubBrowseAndAccess()
            stubTwoIssueFeed()
            stubViewAllowed("SUMP-1", "SUMP-2")

            val entries = sut.getActivity(actor, projectKey, 20)

            // 비-공허 짝 — 위 테스트가 "항상 빈 목록" 구현으로도 통과하지 않게 한다.
            entries.map { it.issueKey } shouldContainExactly listOf("SUMP-1", "SUMP-2")
        }

        it("같은 이슈의 그룹이 여럿이어도 VIEW 판정은 이슈당 1회다") {
            stubBrowseAndAccess()
            every { issueRepository.fetchProjectActivity(projectKey, actor.value, unrestrictedAccess, 20) } returns
                listOf(
                    activityRow(1L, "SUMP-1", null, at, "status", "doing", "done", null, null),
                    activityRow(2L, "SUMP-1", null, at.minusSeconds(60), "status", "open", "doing", null, null),
                    activityRow(3L, "SUMP-1", null, at.minusSeconds(120), "priority", "3", "1", null, null),
                )
            stubViewAllowed("SUMP-1")

            sut.getActivity(actor, projectKey, 20)

            // resolver 에 배치 API 가 없어 개별 호출이지만, distinct 이슈 키로 캐싱해 N+1 을 막는다.
            verify(exactly = 1) {
                permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Issue("SUMP-1"))
            }
        }
    }

    // ── 창 경계 (T2) ──────────────────────────────────────────────────────────

    describe("집계 창 경계") {
        // 기준 2026-09-03T12:00Z → 최근 [08-28T00:00Z, 09-04T00:00Z) · 직전 [08-21T00:00Z, 08-28T00:00Z)
        val recentFrom = Instant.parse("2026-08-28T00:00:00Z")
        val recentTo = Instant.parse("2026-09-04T00:00:00Z")
        val previousFrom = Instant.parse("2026-08-21T00:00:00Z")

        it("recentFrom 정각은 최근 창에 포함되고 recentTo 정각은 제외된다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            stubIssues(
                listOf(
                    summaryRow(UUID.randomUUID(), createdAt = recentFrom, updatedAt = recentFrom),
                    summaryRow(UUID.randomUUID(), createdAt = recentTo, updatedAt = recentTo),
                ),
            )
            stubChanges(emptyList())

            val summary = sut.getSummary(actor, projectKey)

            // 시작 inclusive · 끝 exclusive. 한쪽만 뒤집혀도 0 또는 2 가 된다.
            summary.recent.created.current shouldBe 1
            summary.recent.updated.current shouldBe 1
            summary.recent.created.previous shouldBe 0
        }

        it("previousFrom 정각은 직전 창에 들어가고 두 창의 접점은 최근 창에만 센다") {
            stubBrowseAndAccess()
            stubTypesAndStates()
            stubIssues(
                listOf(
                    summaryRow(UUID.randomUUID(), createdAt = previousFrom, updatedAt = previousFrom),
                    // previousTo == recentFrom — 인접한 두 창의 접점은 한 번만 세어야 한다.
                    summaryRow(UUID.randomUUID(), createdAt = recentFrom, updatedAt = recentFrom),
                ),
            )
            stubChanges(emptyList())

            val summary = sut.getSummary(actor, projectKey)

            summary.recent.created.previous shouldBe 1
            summary.recent.created.current shouldBe 1
        }
    }

    // ── 필드 수준 마스킹 (B1) ─────────────────────────────────────────────────

    describe("활동 피드의 필드 수준 마스킹") {
        val at = Instant.parse("2026-09-03T09:00:00Z")
        val descriptionRef = FieldRef(FieldKind.CORE, "description")

        fun stubDescriptionFeed() {
            every { issueRepository.fetchProjectActivity(projectKey, actor.value, unrestrictedAccess, 20) } returns
                listOf(
                    activityRow(1L, "SUMP-1", null, at, "description", "옛 설명", "새 설명", "옛 라벨", "새 라벨"),
                )
        }

        it("가려진 필드는 값·라벨 4종이 null 이고 항목 자체는 남는다") {
            stubBrowseAndAccess()
            stubViewAllowed("SUMP-1")
            stubDescriptionFeed()
            // description 이 candidates 에 있는데 visible 에서 빠진다 = 가려진 필드.
            every { fieldPermissionResolver.visibleFields(actor.value, projectId, any()) } returns emptySet()

            val item = sut.getActivity(actor, projectKey, 20).single().items.single()

            // 단건 이력과 같은 시맨틱 — 항목은 남기고 값만 가린다.
            item.field shouldBe "description"
            item.fromValue.shouldBeNull()
            item.toValue.shouldBeNull()
            item.fromLabel.shouldBeNull()
            item.toLabel.shouldBeNull()
        }

        it("같은 픽스처에서 필드 권한만 열면 원문이 보인다") {
            stubBrowseAndAccess()
            stubViewAllowed("SUMP-1")
            stubDescriptionFeed()
            every {
                fieldPermissionResolver.visibleFields(actor.value, projectId, any())
            } returns setOf(descriptionRef)

            val item = sut.getActivity(actor, projectKey, 20).single().items.single()

            // 비-공허 짝 — 위 테스트가 "무조건 null" 구현으로도 통과하지 않게 한다.
            item.fromValue shouldBe "옛 설명"
            item.toValue shouldBe "새 설명"
            item.fromLabel shouldBe "옛 라벨"
            item.toLabel shouldBe "새 라벨"
        }
    }

    // ── 삭제된 댓글 본문 마스킹 (B2) ──────────────────────────────────────────

    describe("활동 피드의 삭제된 댓글 본문 마스킹") {
        val at = Instant.parse("2026-09-03T09:00:00Z")
        val issueOne = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        val issueTwo = UUID.fromString("00000000-0000-4000-8000-0000000000a2")
        val activeCommentId = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
        val deletedCommentId = UUID.fromString("00000000-0000-4000-8000-0000000000b2")
        val activeField = "comment:$activeCommentId"
        val deletedField = "comment:$deletedCommentId"

        it("삭제된 댓글의 본문은 가려지고 활성 댓글의 본문은 그대로 보인다") {
            stubBrowseAndAccess()
            stubViewAllowed("SUMP-1")
            every { issueRepository.fetchProjectActivity(projectKey, actor.value, unrestrictedAccess, 20) } returns
                listOf(
                    activityRow(1L, "SUMP-1", null, at, activeField, "활성 원본", "활성 수정본", null, null, issueOne),
                    activityRow(1L, "SUMP-1", null, at, deletedField, "위장 본문", "부적절한 본문", null, null, issueOne),
                )
            every { commentRepository.findActiveOwners(any()) } returns mapOf(activeCommentId to issueOne)

            val items = sut.getActivity(actor, projectKey, 20).single().items

            val deleted = items.first { it.field == deletedField }
            deleted.fromValue.shouldBeNull()
            deleted.toValue.shouldBeNull()
            // 비-공허 짝 — 같은 그룹의 활성 댓글은 손대지 않는다.
            val active = items.first { it.field == activeField }
            active.fromValue shouldBe "활성 원본"
            active.toValue shouldBe "활성 수정본"
        }

        it("여러 이슈에 걸쳐도 활성 댓글 조회는 피드당 1회다") {
            stubBrowseAndAccess()
            stubViewAllowed("SUMP-1", "SUMP-2")
            every { issueRepository.fetchProjectActivity(projectKey, actor.value, unrestrictedAccess, 20) } returns
                listOf(
                    activityRow(1L, "SUMP-1", null, at, activeField, "본문 A", "수정 A", null, null, issueOne),
                    activityRow(
                        2L, "SUMP-2", null, at.minusSeconds(60), deletedField,
                        "본문 B", "수정 B", null, null, issueTwo,
                    ),
                )
            every { commentRepository.findActiveOwners(any()) } returns mapOf(activeCommentId to issueOne)

            sut.getActivity(actor, projectKey, 20)

            // 이슈당 1회면 N+1 이다 — 이슈가 여럿이어도 한 번으로 묶여야 한다.
            verify(exactly = 1) {
                commentRepository.findActiveOwners(
                    mapOf(issueOne to setOf(activeCommentId), issueTwo to setOf(deletedCommentId)),
                )
            }
        }

        it("다른 이슈 소속으로 확인된 댓글은 활성이어도 가린다") {
            stubBrowseAndAccess()
            stubViewAllowed("SUMP-1")
            every { issueRepository.fetchProjectActivity(projectKey, actor.value, unrestrictedAccess, 20) } returns
                listOf(
                    activityRow(1L, "SUMP-1", null, at, activeField, "본문", "수정", null, null, issueOne),
                )
            // 활성이지만 소속이 issueTwo — 판정 불능이므로 가린다(fail-closed).
            every { commentRepository.findActiveOwners(any()) } returns mapOf(activeCommentId to issueTwo)

            val item = sut.getActivity(actor, projectKey, 20).single().items.single()

            item.fromValue.shouldBeNull()
            item.toValue.shouldBeNull()
        }
    }
})

/** 댓글 소속 대조를 따로 검증하지 않는 픽스처가 공유하는 이슈 UUID. */
private val FIXED_ISSUE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000f1")

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
    issueId: UUID = FIXED_ISSUE_ID,
): ProjectActivityRow =
    ProjectActivityRow(
        groupId = groupId,
        issueId = issueId,
        issueKey = issueKey,
        actorId = actorId,
        createdAt = createdAt,
        field = field,
        fromValue = fromValue,
        toValue = toValue,
        fromLabel = fromLabel,
        toLabel = toLabel,
    )
