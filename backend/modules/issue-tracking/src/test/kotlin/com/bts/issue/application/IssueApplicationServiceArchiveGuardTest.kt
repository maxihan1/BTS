// IssueApplicationService — ProjectArchiveGuard 배치 단위 테스트, MockK, TDD RED 단계 (FR-PJ-04 PR-4 Task 8)

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.AvailableTransitionView
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [IssueApplicationService] 의 [ProjectArchiveGuard] 배치를 검증한다 (FR-PJ-04 PR-4 Task 8).
 *
 * ## 불변식
 * - 쓰기 9종(createIssue/cloneIssue/updateIssue/transitionIssue/softDeleteIssue/changeAssignee/
 *   changeComponents/changeAffectsVersions/changeFixVersions) 은 아카이브된 프로젝트에 대해
 *   [ProjectArchivedException] 을 던진다.
 * - D-ORDER — permission 검증이 먼저, guard 는 그 다음. 미인가 actor 는 guard 가 호출되기 전에
 *   [IssueAccessDeniedException] 으로 거부되어 아카이브 상태를 알아낼 수 없다.
 * - ★EC-3 — listIssues/listIssuesByCursor/findByKey/availableTransitions(읽기·목록)는 guard 를
 *   **절대 호출하지 않는다**. guard 가 항상 예외를 던지도록 설정해도 이 경로들은 정상 응답한다
 *   (guard-handler-matrix-blindfold 회귀 방지 — assertPermission(:1477) 이 쓰기 9종과
 *   listIssues/listIssuesByCursor 를 공유하므로, guard 를 그 공유 지점에 두면 목록조회까지
 *   409 가 되어 EC-3 이 파괴된다).
 */
class IssueApplicationServiceArchiveGuardTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>(relaxed = true)
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val projectArchiveGuard = mockk<ProjectArchiveGuard>()
    val clock = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC)

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
            projectArchiveGuard = projectArchiveGuard,
        )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "BTS"
    val issueKey = IssueKey("BTS-1")
    val anyProjectId: UUID = UUID.fromString("11111111-0000-0000-0000-000000000001")

    beforeEach {
        clearMocks(repo, permissionResolver, projectArchiveGuard, workflowPort, workflowKeyResolver, answers = false)
    }

    // ── 쓰기 9종 — permission → guard(D-ORDER), 아카이브 409 / 활성 통과 / 미인가 시 guard 미호출 ──

    describe("쓰기 9종 — ProjectArchiveGuard 배치") {

        context("createIssue") {
            val request = CreateIssueRequest(projectKey = projectKey, summary = "s", reporterId = actor)

            fun stubPermission(allowed: Boolean) {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.CREATE,
                        IssueScope.Project(projectKey),
                    )
                } returns allowed
            }

            it("아카이브된 프로젝트 → ProjectArchivedException") {
                stubPermission(true)
                every { projectArchiveGuard.check(projectKey) } throws ProjectArchivedException(projectKey)

                shouldThrow<ProjectArchivedException> { sut.createIssue(actor, request) }
            }

            it("활성 프로젝트 → guard 통과 후 다음 로직(프로젝트 조회)까지 진행한다 (무조건 차단 아님)") {
                stubPermission(true)
                justRun { projectArchiveGuard.check(projectKey) }
                every { repo.incrementKeySequence(projectKey) } returns 1L
                every { repo.findProjectIdByKey(projectKey) } returns null

                shouldThrow<IssueProjectNotFoundException> { sut.createIssue(actor, request) }

                verify(exactly = 1) { projectArchiveGuard.check(projectKey) }
            }

            it("권한 없음 → guard 호출 전에 거부된다 (D-ORDER: permission 먼저)") {
                stubPermission(false)

                shouldThrow<IssueAccessDeniedException> { sut.createIssue(actor, request) }

                verify(exactly = 0) { projectArchiveGuard.check(any<String>()) }
            }
        }

        context("cloneIssue") {
            val sourceKey = issueKey
            val request = CloneIssueRequest()

            fun stubView(allowed: Boolean) {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(sourceKey.value),
                    )
                } returns allowed
            }

            fun stubCreate(allowed: Boolean) {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.CREATE,
                        IssueScope.Project(projectKey),
                    )
                } returns allowed
            }

            it("아카이브된 프로젝트 → ProjectArchivedException") {
                stubView(true)
                stubCreate(true)
                every { projectArchiveGuard.check(projectKey) } throws ProjectArchivedException(projectKey)

                shouldThrow<ProjectArchivedException> { sut.cloneIssue(actor, sourceKey, request) }
            }

            it("활성 프로젝트 → guard 통과 후 원본 이슈 조회까지 진행한다") {
                stubView(true)
                stubCreate(true)
                justRun { projectArchiveGuard.check(projectKey) }
                every { repo.findByKey(sourceKey) } returns null

                shouldThrow<IssueNotFoundException> { sut.cloneIssue(actor, sourceKey, request) }

                verify(exactly = 1) { projectArchiveGuard.check(projectKey) }
            }

            it("CREATE 권한 없음 → guard 호출 전에 거부된다 (D-ORDER: permission 먼저)") {
                stubView(true)
                stubCreate(false)

                shouldThrow<IssueAccessDeniedException> { sut.cloneIssue(actor, sourceKey, request) }

                verify(exactly = 0) { projectArchiveGuard.check(any<String>()) }
            }
        }

        context("updateIssue") {
            val request = UpdateIssueRequest(summary = "new", expectedVersion = 1L)

            fun stubPermission(allowed: Boolean) {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns allowed
            }

            it("아카이브된 프로젝트 소속 이슈 → ProjectArchivedException") {
                stubPermission(true)
                every {
                    projectArchiveGuard.checkByIssue(issueKey)
                } throws ProjectArchivedException(issueKey.value)

                shouldThrow<ProjectArchivedException> { sut.updateIssue(actor, issueKey, request) }
            }

            it("활성 프로젝트 소속 이슈 → guard 통과 후 이슈 조회까지 진행한다") {
                stubPermission(true)
                justRun { projectArchiveGuard.checkByIssue(issueKey) }
                every { repo.findByKey(issueKey) } returns null

                shouldThrow<IssueNotFoundException> { sut.updateIssue(actor, issueKey, request) }

                verify(exactly = 1) { projectArchiveGuard.checkByIssue(issueKey) }
            }

            it("권한 없음 → guard 호출 전에 거부된다 (D-ORDER: permission 먼저)") {
                stubPermission(false)

                shouldThrow<IssueAccessDeniedException> { sut.updateIssue(actor, issueKey, request) }

                verify(exactly = 0) { projectArchiveGuard.checkByIssue(issueKey) }
            }
        }

        context("transitionIssue") {
            val request = TransitionIssueRequest(toStateKey = "DONE", expectedVersion = 1L)

            fun stubPermission(allowed: Boolean) {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns allowed
            }

            it("아카이브된 프로젝트 소속 이슈 → ProjectArchivedException") {
                stubPermission(true)
                every {
                    projectArchiveGuard.checkByIssue(issueKey)
                } throws ProjectArchivedException(issueKey.value)

                shouldThrow<ProjectArchivedException> { sut.transitionIssue(actor, issueKey, request) }
            }

            it("활성 프로젝트 소속 이슈 → guard 통과 후 이슈 조회까지 진행한다") {
                stubPermission(true)
                justRun { projectArchiveGuard.checkByIssue(issueKey) }
                every { repo.findByKeyForUpdate(issueKey) } returns null

                shouldThrow<IssueNotFoundException> { sut.transitionIssue(actor, issueKey, request) }

                verify(exactly = 1) { projectArchiveGuard.checkByIssue(issueKey) }
            }

            it("권한 없음 → guard 호출 전에 거부된다 (D-ORDER: permission 먼저)") {
                stubPermission(false)

                shouldThrow<IssueAccessDeniedException> { sut.transitionIssue(actor, issueKey, request) }

                verify(exactly = 0) { projectArchiveGuard.checkByIssue(issueKey) }
            }
        }

        context("softDeleteIssue") {
            fun stubPermission(allowed: Boolean) {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SOFT_DELETE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns allowed
            }

            it("아카이브된 프로젝트 소속 이슈 → ProjectArchivedException") {
                stubPermission(true)
                every {
                    projectArchiveGuard.checkByIssue(issueKey)
                } throws ProjectArchivedException(issueKey.value)

                shouldThrow<ProjectArchivedException> { sut.softDeleteIssue(actor, issueKey) }
            }

            it("활성 프로젝트 소속 이슈 → guard 통과 후 삭제 시도까지 진행한다") {
                stubPermission(true)
                justRun { projectArchiveGuard.checkByIssue(issueKey) }
                every { repo.findByKey(issueKey) } returns null
                every { repo.softDelete(issueKey) } returns 0

                shouldThrow<IssueNotFoundException> { sut.softDeleteIssue(actor, issueKey) }

                verify(exactly = 1) { projectArchiveGuard.checkByIssue(issueKey) }
            }

            it("권한 없음 → guard 호출 전에 거부된다 (D-ORDER: permission 먼저)") {
                stubPermission(false)

                shouldThrow<IssueAccessDeniedException> { sut.softDeleteIssue(actor, issueKey) }

                verify(exactly = 0) { projectArchiveGuard.checkByIssue(issueKey) }
            }
        }

        context("changeAssignee") {
            val request = AppChangeAssigneeRequest(assigneeId = UUID.randomUUID(), expectedVersion = 1L)

            fun stubPermission(allowed: Boolean) {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns allowed
            }

            it("아카이브된 프로젝트 소속 이슈 → ProjectArchivedException") {
                stubPermission(true)
                every {
                    projectArchiveGuard.checkByIssue(issueKey)
                } throws ProjectArchivedException(issueKey.value)

                shouldThrow<ProjectArchivedException> { sut.changeAssignee(actor, issueKey, request) }
            }

            it("활성 프로젝트 소속 이슈 → guard 통과 후 이슈 조회까지 진행한다") {
                stubPermission(true)
                justRun { projectArchiveGuard.checkByIssue(issueKey) }
                every { repo.findByKey(issueKey) } returns null

                shouldThrow<IssueNotFoundException> { sut.changeAssignee(actor, issueKey, request) }

                verify(exactly = 1) { projectArchiveGuard.checkByIssue(issueKey) }
            }

            it("권한 없음 → guard 호출 전에 거부된다 (D-ORDER: permission 먼저)") {
                stubPermission(false)

                shouldThrow<IssueAccessDeniedException> { sut.changeAssignee(actor, issueKey, request) }

                verify(exactly = 0) { projectArchiveGuard.checkByIssue(issueKey) }
            }
        }

        context("changeComponents") {
            val request = AppChangeComponentsRequest(componentIds = emptyList(), expectedVersion = 1L)

            fun stubPermission(allowed: Boolean) {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns allowed
            }

            it("아카이브된 프로젝트 소속 이슈 → ProjectArchivedException") {
                stubPermission(true)
                every {
                    projectArchiveGuard.checkByIssue(issueKey)
                } throws ProjectArchivedException(issueKey.value)

                shouldThrow<ProjectArchivedException> { sut.changeComponents(actor, issueKey, request) }
            }

            it("활성 프로젝트 소속 이슈 → guard 통과 후 이슈 조회까지 진행한다") {
                stubPermission(true)
                justRun { projectArchiveGuard.checkByIssue(issueKey) }
                every { repo.findByKey(issueKey) } returns null

                shouldThrow<IssueNotFoundException> { sut.changeComponents(actor, issueKey, request) }

                verify(exactly = 1) { projectArchiveGuard.checkByIssue(issueKey) }
            }

            it("권한 없음 → guard 호출 전에 거부된다 (D-ORDER: permission 먼저)") {
                stubPermission(false)

                shouldThrow<IssueAccessDeniedException> { sut.changeComponents(actor, issueKey, request) }

                verify(exactly = 0) { projectArchiveGuard.checkByIssue(issueKey) }
            }
        }

        context("changeAffectsVersions") {
            val request = AppChangeVersionsRequest(versionIds = emptyList(), expectedVersion = 1L)

            fun stubPermission(allowed: Boolean) {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns allowed
            }

            it("아카이브된 프로젝트 소속 이슈 → ProjectArchivedException") {
                stubPermission(true)
                every {
                    projectArchiveGuard.checkByIssue(issueKey)
                } throws ProjectArchivedException(issueKey.value)

                shouldThrow<ProjectArchivedException> { sut.changeAffectsVersions(actor, issueKey, request) }
            }

            it("활성 프로젝트 소속 이슈 → guard 통과 후 이슈 조회까지 진행한다") {
                stubPermission(true)
                justRun { projectArchiveGuard.checkByIssue(issueKey) }
                every { repo.findByKey(issueKey) } returns null

                shouldThrow<IssueNotFoundException> { sut.changeAffectsVersions(actor, issueKey, request) }

                verify(exactly = 1) { projectArchiveGuard.checkByIssue(issueKey) }
            }

            it("권한 없음 → guard 호출 전에 거부된다 (D-ORDER: permission 먼저)") {
                stubPermission(false)

                shouldThrow<IssueAccessDeniedException> { sut.changeAffectsVersions(actor, issueKey, request) }

                verify(exactly = 0) { projectArchiveGuard.checkByIssue(issueKey) }
            }
        }

        context("changeFixVersions") {
            val request = AppChangeVersionsRequest(versionIds = emptyList(), expectedVersion = 1L)

            fun stubPermission(allowed: Boolean) {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns allowed
            }

            it("아카이브된 프로젝트 소속 이슈 → ProjectArchivedException") {
                stubPermission(true)
                every {
                    projectArchiveGuard.checkByIssue(issueKey)
                } throws ProjectArchivedException(issueKey.value)

                shouldThrow<ProjectArchivedException> { sut.changeFixVersions(actor, issueKey, request) }
            }

            it("활성 프로젝트 소속 이슈 → guard 통과 후 이슈 조회까지 진행한다") {
                stubPermission(true)
                justRun { projectArchiveGuard.checkByIssue(issueKey) }
                every { repo.findByKey(issueKey) } returns null

                shouldThrow<IssueNotFoundException> { sut.changeFixVersions(actor, issueKey, request) }

                verify(exactly = 1) { projectArchiveGuard.checkByIssue(issueKey) }
            }

            it("권한 없음 → guard 호출 전에 거부된다 (D-ORDER: permission 먼저)") {
                stubPermission(false)

                shouldThrow<IssueAccessDeniedException> { sut.changeFixVersions(actor, issueKey, request) }

                verify(exactly = 0) { projectArchiveGuard.checkByIssue(issueKey) }
            }
        }
    }

    // ── ★EC-3 — 읽기·목록은 guard 를 절대 호출하지 않는다 ──────────────────────────

    describe("읽기·목록 경로 — ProjectArchiveGuard 미참조 (아카이브 프로젝트도 200 유지)") {

        // IssueKey 는 inline value class 라 MockK any() 매처가 시그니처 생성에 실패한다
        // (JvmSignatureValueGenerator). 이 파일 전체에서 유일하게 쓰이는 issueKey 를 그대로
        // 넘겨 exact 매칭한다 — checkByIssue 가 호출되는 대상은 항상 issueKey 하나뿐이므로
        // "미호출" 판정 정확도는 동일하다(코드베이스 관례, 예: IssueEpicServiceTest).
        fun stubGuardAlwaysThrows() {
            every { projectArchiveGuard.check(any<String>()) } throws ProjectArchivedException("항상실패")
            every { projectArchiveGuard.check(any<UUID>()) } throws ProjectArchivedException("항상실패")
            every { projectArchiveGuard.checkByIssue(issueKey) } throws ProjectArchivedException("항상실패")
        }

        fun verifyGuardNeverCalled() {
            verify(exactly = 0) { projectArchiveGuard.check(any<String>()) }
            verify(exactly = 0) { projectArchiveGuard.check(any<UUID>()) }
            verify(exactly = 0) { projectArchiveGuard.checkByIssue(issueKey) }
        }

        context("listIssues") {
            val pageable = PageRequest.of(0, 20)

            it("guard 가 항상 예외를 던지도록 설정해도 목록 조회는 정상 반환된다") {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.BROWSE,
                        IssueScope.Project(projectKey),
                    )
                } returns true
                every {
                    repo.listWithType(projectKey, pageable, any(), any<IssueSecurityAccess>(), any())
                } returns PageImpl(emptyList(), pageable, 0L)
                stubGuardAlwaysThrows()

                val result = sut.listIssues(actor, projectKey, pageable)

                result.content.size shouldBe 0
                verifyGuardNeverCalled()
            }
        }

        context("listIssuesByCursor") {
            it("guard 가 항상 예외를 던지도록 설정해도 cursor 목록 조회는 정상 반환된다") {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.BROWSE,
                        IssueScope.Project(projectKey),
                    )
                } returns true
                every {
                    repo.listWithTypeByCursor(projectKey, null, null, 20, actor.value, any(), any())
                } returns IssueRepository.IssueCursorPage(items = emptyList(), hasNext = false)
                stubGuardAlwaysThrows()

                val result = sut.listIssuesByCursor(actor, projectKey, null, 20)

                result.items.size shouldBe 0
                verifyGuardNeverCalled()
            }
        }

        context("findByKey") {
            it("guard 가 항상 예외를 던지도록 설정해도 단건 조회는 정상 반환된다") {
                val issueResponse =
                    com.bts.issue.adapter.inbound.rest.IssueResponse(
                        key = issueKey.value,
                        id = UUID.randomUUID(),
                        projectKey = issueKey.projectPrefix,
                        summary = "테스트 이슈",
                        reporterId = actor.value,
                        currentStateKey = "open",
                        version = 1L,
                        createdAt = Instant.parse("2026-07-18T00:00:00Z"),
                        updatedAt = Instant.parse("2026-07-18T00:00:00Z"),
                        typeId = 3L,
                        typeKey = "task",
                        typeName = "Task",
                    )
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyWithType(issueKey) } returns issueResponse
                every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()
                every { repo.findAffectsVersionIdsByIssue(any()) } returns emptyList()
                every { repo.findFixVersionIdsByIssue(any()) } returns emptyList()
                every { repo.findProjectIdByKey(issueKey.projectPrefix) } returns anyProjectId
                stubGuardAlwaysThrows()

                val result = sut.findByKey(actor, issueKey)

                result.key shouldBe issueKey.value
                verifyGuardNeverCalled()
            }
        }

        context("availableTransitions") {
            it("guard 가 항상 예외를 던지도록 설정해도 전환 가능목록 조회는 정상 반환된다") {
                val transitions =
                    listOf(AvailableTransitionView(fromStateKey = "open", toStateKey = "in_progress", name = "시작"))
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKey(issueKey) } returns
                    com.bts.issue.domain.Issue(
                        id = com.bts.issue.domain.IssueId(UUID.randomUUID()),
                        key = issueKey,
                        projectId = anyProjectId,
                        summary = "테스트 이슈",
                        reporterId = actor,
                        currentStateKey = "open",
                        version = 1L,
                        deletedAt = null,
                        createdAt = Instant.parse("2026-07-18T00:00:00Z"),
                        updatedAt = Instant.parse("2026-07-18T00:00:00Z"),
                        typeId = com.bts.shared.issue.IssueTypeId(3L),
                    )
                every {
                    workflowKeyResolver.resolveExisting(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "DEFAULT", startStateKey = "open")
                every { workflowPort.availableTransitions(any()) } returns
                    AvailableTransitionsResult.Success(transitions)
                stubGuardAlwaysThrows()

                val result = sut.availableTransitions(actor, issueKey)

                result shouldHaveSize 1
                verifyGuardNeverCalled()
            }
        }
    }
})
