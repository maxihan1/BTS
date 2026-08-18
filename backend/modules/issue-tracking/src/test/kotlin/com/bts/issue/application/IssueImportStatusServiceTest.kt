// IssueImportStatusService 단위 테스트 — MockK, TDD RED 단계 (FR-IM-01 PR2 Task 3)

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
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

class IssueImportStatusServiceTest : DescribeSpec({

    val issueRepository = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>()
    val workflowStateCatalog = mockk<WorkflowStateCatalog>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val archiveGuard = mockk<ProjectArchiveGuard>(relaxUnitFun = true)

    val sut =
        IssueImportStatusService(
            issueRepository = issueRepository,
            workflowStateCatalog = workflowStateCatalog,
            permissionResolver = permissionResolver,
            issueTypeRepository = issueTypeRepository,
            archiveGuard = archiveGuard,
        )

    beforeEach {
        clearMocks(issueRepository, issueTypeRepository, workflowStateCatalog, permissionResolver, archiveGuard)
    }

    // ── 공통 픽스처 ───────────────────────────────────────────────────────────

    val actorUuid = UUID.fromString("00000000-0000-4000-8000-000000000099")
    val actor = ActorId(actorUuid)
    val issueKey = IssueKey("BTS-1")
    val typeId = IssueTypeId(1L)
    val projectKey = ProjectKey.of("BTS")
    val issueType = IssueType.TASK.copy(id = typeId)

    fun makeIssue(
        currentStateKey: String = "open",
        version: Long = 1L,
    ) = Issue(
        id = IssueId(UUID.fromString("00000000-0000-4000-8000-000000000001")),
        key = issueKey,
        projectId = UUID.fromString("00000000-0000-4000-8000-000000000010"),
        summary = "테스트 이슈",
        reporterId = actor,
        currentStateKey = currentStateKey,
        version = version,
        deletedAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        typeId = typeId,
    )

    val states =
        listOf(
            WorkflowStateView(key = "open", name = "열림"),
            WorkflowStateView(key = "done", name = "완료", isDone = true, category = "DONE"),
        )

    fun stubLookup(currentStateKey: String = "open") {
        every { issueRepository.findByKey(issueKey) } returns makeIssue(currentStateKey = currentStateKey)
        every { issueTypeRepository.findById(typeId) } returns issueType
    }

    fun stubPermission(allowed: Boolean) {
        every {
            permissionResolver.hasPermission(actorUuid, IssuePermission.TRANSITION, IssueScope.Issue(issueKey.value))
        } returns allowed
    }

    describe("applyImportedStatus") {

        context("TRANSITION 권한이 없으면") {
            beforeEach {
                stubLookup()
                stubPermission(allowed = false)
            }

            it("NoPermission 을 반환한다") {
                val result = sut.applyImportedStatus(actor, issueKey, "완료", 1L)
                result shouldBe ImportStatusOutcome.NoPermission
            }

            it("applyTransition 이 호출되지 않는다") {
                sut.applyImportedStatus(actor, issueKey, "완료", 1L)
                // value class IssueKey 에 any() 를 쓰면 MockK 서명 생성 오류 — 첫 인자는 구체 키로 verify
                verify(exactly = 0) { issueRepository.applyTransition(issueKey, any(), any(), any()) }
            }
        }

        context("statusName 이 워크플로우 상태 name 에 매칭되지 않으면") {
            beforeEach {
                stubLookup()
                stubPermission(allowed = true)
                every { workflowStateCatalog.listStates(projectKey, issueType.key) } returns states
            }

            it("NoMatch 를 반환한다") {
                val result = sut.applyImportedStatus(actor, issueKey, "존재하지않는상태", 1L)
                result shouldBe ImportStatusOutcome.NoMatch
            }

            it("applyTransition 이 호출되지 않는다") {
                sut.applyImportedStatus(actor, issueKey, "존재하지않는상태", 1L)
                verify(exactly = 0) { issueRepository.applyTransition(issueKey, any(), any(), any()) }
            }
        }

        context("매칭된 상태 key 가 이슈의 현재 상태와 같으면") {
            beforeEach {
                stubLookup(currentStateKey = "open")
                stubPermission(allowed = true)
                every { workflowStateCatalog.listStates(projectKey, issueType.key) } returns states
            }

            it("NoOp 을 반환한다 (대소문자 무시 매칭)") {
                val result = sut.applyImportedStatus(actor, issueKey, "열림", 1L)
                result shouldBe ImportStatusOutcome.NoOp
            }

            it("applyTransition 이 호출되지 않는다") {
                sut.applyImportedStatus(actor, issueKey, "열림", 1L)
                verify(exactly = 0) { issueRepository.applyTransition(issueKey, any(), any(), any()) }
            }
        }

        context("매칭 + 권한 + 상태가 다르면") {
            beforeEach {
                stubLookup(currentStateKey = "open")
                stubPermission(allowed = true)
                every { workflowStateCatalog.listStates(projectKey, issueType.key) } returns states
                every { issueRepository.applyTransition(issueKey, "done", 1L, null) } returns 1
            }

            it("applyTransition(key, matchedKey, expectedVersion, resolutionId=null) 을 호출한다") {
                sut.applyImportedStatus(actor, issueKey, "완료", 1L)
                verify(exactly = 1) { issueRepository.applyTransition(issueKey, "done", 1L, null) }
            }

            it("Applied(newVersion) 을 반환한다") {
                val result = sut.applyImportedStatus(actor, issueKey, "완료", 1L)
                result shouldBe ImportStatusOutcome.Applied(2L)
            }
        }

        context("applyTransition 이 0행을 반환하면 (OCC 충돌 — 락 없이 조회했으므로 동시 수정 가능)") {
            beforeEach {
                stubLookup(currentStateKey = "open")
                stubPermission(allowed = true)
                every { workflowStateCatalog.listStates(projectKey, issueType.key) } returns states
                every { issueRepository.applyTransition(issueKey, "done", 1L, null) } returns 0
            }

            it("IssueVersionConflictException 을 던진다") {
                shouldThrow<IssueVersionConflictException> {
                    sut.applyImportedStatus(actor, issueKey, "완료", 1L)
                }
            }
        }
    }

    describe("아카이브 잠금 (FR-PJ-04 PR-4 Task 9c)") {
        context("applyImportedStatus — 아카이브된 프로젝트") {
            it("TRANSITION 권한 통과 후 archiveGuard.checkByIssue 가 ProjectArchivedException 을 던지면 그대로 전파된다") {
                stubPermission(allowed = true)
                every { archiveGuard.checkByIssue(issueKey) } throws ProjectArchivedException(issueKey.value)

                shouldThrow<ProjectArchivedException> {
                    sut.applyImportedStatus(actor, issueKey, "완료", 1L)
                }
                verify(exactly = 0) { issueRepository.findByKey(issueKey) }
            }
        }

        context("applyImportedStatus — 활성 프로젝트 (판별자 baseline)") {
            it("archiveGuard.checkByIssue 가 호출되고 정상 반영된다") {
                stubLookup(currentStateKey = "open")
                stubPermission(allowed = true)
                every { workflowStateCatalog.listStates(projectKey, issueType.key) } returns states
                every { issueRepository.applyTransition(issueKey, "done", 1L, null) } returns 1

                val result = sut.applyImportedStatus(actor, issueKey, "완료", 1L)

                result shouldBe ImportStatusOutcome.Applied(2L)
                verify(exactly = 1) { archiveGuard.checkByIssue(issueKey) }
            }
        }
    }

    describe("statusNameMatches (dry-run 미리보기 — 실제 전환 없이 name 매칭만 검사)") {
        beforeEach {
            every { issueTypeRepository.findById(typeId) } returns issueType
            every { workflowStateCatalog.listStates(projectKey, issueType.key) } returns states
        }

        it("대상 워크플로우에 존재하는 상태 이름이면 true (대소문자 무시)") {
            sut.statusNameMatches("BTS", typeId, "완료") shouldBe true
            sut.statusNameMatches("BTS", typeId, "열림") shouldBe true
        }

        it("존재하지 않는 상태 이름이면 false — 실행 경로의 NoMatch 를 미리 예측") {
            sut.statusNameMatches("BTS", typeId, "Frozen") shouldBe false
        }
    }
})
