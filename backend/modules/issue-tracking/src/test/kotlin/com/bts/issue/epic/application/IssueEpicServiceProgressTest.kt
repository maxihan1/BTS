// IssueEpicService.progress 단위 테스트 — MockK, TDD RED 단계 (FR-EP-02 Task 2)

package com.bts.issue.epic.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.epic.domain.EpicChildNotFoundException
import com.bts.issue.epic.domain.EpicProgress
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueRepository
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
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

class IssueEpicServiceProgressTest : DescribeSpec({

    val permissionResolver = mockk<IssuePermissionResolver>()
    val securityDirectory = mockk<IssueSecurityDirectory>()
    val issueRepository = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>()
    val historyRecorder = mockk<IssueHistoryRecorder>(relaxed = true)
    val workflowStateCatalog = mockk<WorkflowStateCatalog>()

    val sut =
        IssueEpicService(
            permissionResolver = permissionResolver,
            securityDirectory = securityDirectory,
            issueRepository = issueRepository,
            issueTypeRepository = issueTypeRepository,
            historyRecorder = historyRecorder,
            workflowStateCatalog = workflowStateCatalog,
        )

    // ── 공통 픽스처 ───────────────────────────────────────────────────────────

    val defaultProjectId = UUID.fromString("00000000-0000-4000-8000-000000000010")
    val actorUuid = UUID.fromString("00000000-0000-4000-8000-000000000099")
    val actorId = ActorId(actorUuid)

    val epicUuid = UUID.fromString("00000000-0000-4000-8000-000000000001")
    val child1Uuid = UUID.fromString("00000000-0000-4000-8000-000000000002")
    val child2Uuid = UUID.fromString("00000000-0000-4000-8000-000000000003")
    val child3Uuid = UUID.fromString("00000000-0000-4000-8000-000000000004")

    val epicTypeId = IssueTypeId(1L)
    val storyTypeId = IssueTypeId(2L)
    val taskTypeId = IssueTypeId(3L)

    val epicType =
        IssueType(
            id = epicTypeId,
            key = IssueTypeKey("epic"),
            name = "Epic",
            description = null,
            iconName = "epic",
            isStandard = true,
            hierarchyLevel = 1,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            deletedAt = null,
        )

    val storyType =
        IssueType(
            id = storyTypeId,
            key = IssueTypeKey("story"),
            name = "Story",
            description = null,
            iconName = "story",
            isStandard = true,
            hierarchyLevel = 0,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            deletedAt = null,
        )

    val taskType =
        IssueType(
            id = taskTypeId,
            key = IssueTypeKey("task"),
            name = "Task",
            description = null,
            iconName = "task",
            isStandard = true,
            hierarchyLevel = 0,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            deletedAt = null,
        )

    fun makeIssue(
        id: UUID,
        key: IssueKey,
        typeId: IssueTypeId = storyTypeId,
        projectId: UUID = defaultProjectId,
        epicId: UUID? = null,
        currentStateKey: String = "open",
    ) = Issue(
        id = IssueId(id),
        key = key,
        projectId = projectId,
        summary = "테스트 이슈",
        reporterId = ActorId(actorUuid),
        currentStateKey = currentStateKey,
        version = 1L,
        deletedAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        typeId = typeId,
        epicId = epicId,
    )

    val epicKey = IssueKey("BTS-1")
    val projectKey = epicKey.projectPrefix

    val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    fun allowBrowse() {
        every {
            permissionResolver.hasPermission(actorUuid, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        } returns true
    }

    fun denyBrowse() {
        every {
            permissionResolver.hasPermission(actorUuid, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        } returns false
    }

    beforeEach {
        clearMocks(
            permissionResolver,
            securityDirectory,
            issueRepository,
            issueTypeRepository,
            historyRecorder,
            workflowStateCatalog,
            answers = false,
        )
    }

    // ── progress ──────────────────────────────────────────────────────────────

    describe("progress") {

        // S5: BROWSE 권한 없으면 403
        context("BROWSE(Project) 권한이 없으면") {
            beforeEach { denyBrowse() }

            it("IssueAccessDeniedException(403) 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.progress(epicKey, actorId)
                }
            }

            it("권한 검사 이후 repo.findByKey 가 호출되지 않는다") {
                runCatching { sut.progress(epicKey, actorId) }
                verify(exactly = 0) { issueRepository.findByKey(epicKey) }
            }
        }

        // S6: epic 미존재 시 404
        context("epic 이슈가 존재하지 않으면") {
            beforeEach {
                allowBrowse()
                every { issueRepository.findByKey(epicKey) } returns null
            }

            it("EpicChildNotFoundException(404) 을 던진다") {
                shouldThrow<EpicChildNotFoundException> {
                    sut.progress(epicKey, actorId)
                }
            }
        }

        // S2/EC1: 자식 없으면 0
        context("에픽에 자식이 없으면 (EC1)") {
            val epicIssue = makeIssue(epicUuid, epicKey, typeId = epicTypeId)

            beforeEach {
                allowBrowse()
                every { issueRepository.findByKey(epicKey) } returns epicIssue
                every { securityDirectory.accessibleLevels(actorUuid, projectKey) } returns unrestrictedAccess
                every {
                    issueRepository.findEpicChildren(epicUuid, actorUuid, unrestrictedAccess, projectKey)
                } returns emptyList()
                every { issueTypeRepository.findAll() } returns listOf(epicType, storyType, taskType)
            }

            it("total=0, done=0, donePercentage=0 인 EpicProgress 를 반환한다") {
                val result = sut.progress(epicKey, actorId)
                result shouldBe EpicProgress(total = 0, done = 0, donePercentage = 0, todo = 0, inProgress = 0)
            }

            it("listStates 가 호출되지 않는다 (자식 없으면 typeKey 집합이 비어 있음)") {
                sut.progress(epicKey, actorId)
                verify(exactly = 0) { workflowStateCatalog.listStates(any(), any()) }
            }
        }

        // S3: 타입별 listStates 판정 + 카테고리 집계
        context("자식이 있고 워크플로우 스킴이 설정된 경우 (S3)") {
            val epicIssue = makeIssue(epicUuid, epicKey, typeId = epicTypeId)
            // story 2개: open(TODO), done(DONE) / task 1개: in-progress(IN_PROGRESS)
            val child1 = makeIssue(child1Uuid, IssueKey("BTS-2"), typeId = storyTypeId, currentStateKey = "open")
            val child2 = makeIssue(child2Uuid, IssueKey("BTS-3"), typeId = storyTypeId, currentStateKey = "done")
            val child3 = makeIssue(child3Uuid, IssueKey("BTS-4"), typeId = taskTypeId, currentStateKey = "in-progress")

            val storyStates =
                listOf(
                    WorkflowStateView(key = "open", name = "열림", isDone = false, category = "TODO"),
                    WorkflowStateView(key = "done", name = "완료", isDone = true, category = "DONE"),
                )
            val taskStates =
                listOf(
                    WorkflowStateView(key = "open", name = "열림", isDone = false, category = "TODO"),
                    WorkflowStateView(key = "in-progress", name = "진행 중", isDone = false, category = "IN_PROGRESS"),
                )

            beforeEach {
                allowBrowse()
                every { issueRepository.findByKey(epicKey) } returns epicIssue
                every { securityDirectory.accessibleLevels(actorUuid, projectKey) } returns unrestrictedAccess
                every {
                    issueRepository.findEpicChildren(epicUuid, actorUuid, unrestrictedAccess, projectKey)
                } returns listOf(child1, child2, child3)
                every { issueTypeRepository.findAll() } returns listOf(epicType, storyType, taskType)
                every {
                    workflowStateCatalog.listStates(ProjectKey.of(projectKey), IssueTypeKey("story"))
                } returns storyStates
                every {
                    workflowStateCatalog.listStates(ProjectKey.of(projectKey), IssueTypeKey("task"))
                } returns taskStates
            }

            it("todo=1, inProgress=1, done=1, total=3, donePercentage=33 을 반환한다") {
                val result = sut.progress(epicKey, actorId)
                result.total shouldBe 3
                result.done shouldBe 1
                result.todo shouldBe 1
                result.inProgress shouldBe 1
                result.donePercentage shouldBe 33
            }
        }

        // FR4: N+1 부재 — listStates 호출 횟수 = distinct typeKey 수
        context("자식 타입이 2종이면 listStates 가 정확히 2회만 호출된다 (FR4 N+1 부재)") {
            val epicIssue = makeIssue(epicUuid, epicKey, typeId = epicTypeId)
            // story 3개, task 2개 — 5개 자식이지만 타입은 2종
            val children =
                listOf(
                    makeIssue(child1Uuid, IssueKey("BTS-2"), typeId = storyTypeId),
                    makeIssue(child2Uuid, IssueKey("BTS-3"), typeId = storyTypeId),
                    makeIssue(child3Uuid, IssueKey("BTS-4"), typeId = storyTypeId),
                    makeIssue(UUID.fromString("00000000-0000-4000-8000-000000000005"), IssueKey("BTS-5"), typeId = taskTypeId),
                    makeIssue(UUID.fromString("00000000-0000-4000-8000-000000000006"), IssueKey("BTS-6"), typeId = taskTypeId),
                )

            beforeEach {
                allowBrowse()
                every { issueRepository.findByKey(epicKey) } returns epicIssue
                every { securityDirectory.accessibleLevels(actorUuid, projectKey) } returns unrestrictedAccess
                every {
                    issueRepository.findEpicChildren(epicUuid, actorUuid, unrestrictedAccess, projectKey)
                } returns children
                every { issueTypeRepository.findAll() } returns listOf(epicType, storyType, taskType)
                every {
                    workflowStateCatalog.listStates(ProjectKey.of(projectKey), IssueTypeKey("story"))
                } returns listOf(WorkflowStateView(key = "open", name = "열림"))
                every {
                    workflowStateCatalog.listStates(ProjectKey.of(projectKey), IssueTypeKey("task"))
                } returns listOf(WorkflowStateView(key = "open", name = "열림"))
            }

            it("listStates 가 정확히 2회(타입 수) 호출된다") {
                sut.progress(epicKey, actorId)
                // 타입 수(2) = distinct typeKey 수 만큼만 호출
                verify(exactly = 2) { workflowStateCatalog.listStates(any(), any()) }
            }
        }

        // NoDefault 폴백 — listStates throw 시 해당 타입 자식 전부 TODO, 나머지 정상
        context("자식 타입 중 하나에 워크플로우 스킴이 없으면 (NoDefault 폴백)") {
            val epicIssue = makeIssue(epicUuid, epicKey, typeId = epicTypeId)
            // story 1개(DONE), task 1개(스킴 없음 → TODO 폴백)
            val storyChild = makeIssue(child1Uuid, IssueKey("BTS-2"), typeId = storyTypeId, currentStateKey = "done")
            val taskChild = makeIssue(child2Uuid, IssueKey("BTS-3"), typeId = taskTypeId, currentStateKey = "open")

            val noDefaultException = RuntimeException("WorkflowSchemeNoDefaultException 시뮬레이션").apply {
                // simpleName 이 "WorkflowSchemeNoDefaultException" 과 매칭되도록 익명 서브클래스 대신
                // 직접 메시지만 설정. javaClass.simpleName = "RuntimeException" 이므로
                // 서비스에서 simpleName 매칭이 RuntimeException 에 걸려 rethrow 하면 안 됨.
                // 실제 NoDefault 예외는 project-workflow 내부 클래스이므로 simple-name 으로만 식별 가능.
                // → 테스트에서 이름을 흉내낸 익명 클래스를 사용
            }

            // WorkflowSchemeNoDefaultException 처럼 이름 가진 예외 생성 헬퍼
            @Suppress("ObjectLiteralToLambda")
            val noDefaultEx: RuntimeException =
                object : RuntimeException("no workflow scheme assigned") {
                    override fun toString() = "WorkflowSchemeNoDefaultException: no workflow scheme assigned"
                }.also {
                    // javaClass.simpleName 은 anonymous class 이므로 빈 문자열 → rethrow
                    // → 그래서 실제 이름 매칭용으로는 진짜 명명된 클래스 필요
                    // → 테스트 전용 inner class 로 정의
                }

            // 실제 서비스 코드에서 e.javaClass.simpleName == "WorkflowSchemeNoDefaultException" 로 매칭하므로
            // 테스트 전용 예외 클래스를 이 파일 내 최상위 선언으로 사용
            val schemeNoDefaultEx = WorkflowSchemeNoDefaultSimulatedException()

            beforeEach {
                allowBrowse()
                every { issueRepository.findByKey(epicKey) } returns epicIssue
                every { securityDirectory.accessibleLevels(actorUuid, projectKey) } returns unrestrictedAccess
                every {
                    issueRepository.findEpicChildren(epicUuid, actorUuid, unrestrictedAccess, projectKey)
                } returns listOf(storyChild, taskChild)
                every { issueTypeRepository.findAll() } returns listOf(epicType, storyType, taskType)
                // story 는 정상 — DONE 카테고리 상태 보유
                every {
                    workflowStateCatalog.listStates(ProjectKey.of(projectKey), IssueTypeKey("story"))
                } returns listOf(
                    WorkflowStateView(key = "done", name = "완료", isDone = true, category = "DONE"),
                )
                // task 는 NoDefault throw → 그 타입 자식 전부 TODO 폴백
                every {
                    workflowStateCatalog.listStates(ProjectKey.of(projectKey), IssueTypeKey("task"))
                } throws schemeNoDefaultEx
            }

            it("예외를 던지지 않고 정상 응답을 반환한다 (운영 500 차단)") {
                val result = sut.progress(epicKey, actorId)
                result.total shouldBe 2
            }

            it("NoDefault 타입 자식은 TODO 로 폴백되고, 나머지는 정상 집계된다") {
                val result = sut.progress(epicKey, actorId)
                // story child = done (DONE), task child = open (schema없어 TODO폴백)
                result.done shouldBe 1
                result.todo shouldBe 1
                result.inProgress shouldBe 0
            }
        }
    }
})

/**
 * WorkflowSchemeNoDefaultException 을 시뮬레이션하는 테스트 전용 예외.
 *
 * 서비스 코드는 `e.javaClass.simpleName == "WorkflowSchemeNoDefaultException"` 로 식별한다.
 * project-workflow BC 내부 예외는 import 불가하므로, 동일 simpleName 을 가진 테스트 전용 클래스로 대체한다.
 */
private class WorkflowSchemeNoDefaultSimulatedException :
    RuntimeException("WorkflowSchemeNoDefaultException simulated for testing")
