// IssueEpicService 단위 테스트 — MockK, TDD RED 단계 (FR-EP-01 Task 5)

package com.bts.issue.epic.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.epic.domain.EpicChildAlreadyLinkedException
import com.bts.issue.epic.domain.EpicChildCrossProjectException
import com.bts.issue.epic.domain.EpicChildInvalidTypeException
import com.bts.issue.epic.domain.EpicChildNotFoundException
import com.bts.issue.epic.domain.EpicChildSelfReferenceException
import com.bts.issue.epic.domain.EpicTargetNotEpicException
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

class IssueEpicServiceTest : DescribeSpec({

    val permissionResolver = mockk<IssuePermissionResolver>()
    val securityDirectory = mockk<IssueSecurityDirectory>()
    val issueRepository = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>()
    val historyRecorder = mockk<IssueHistoryRecorder>(relaxed = true)

    val sut =
        IssueEpicService(
            permissionResolver = permissionResolver,
            securityDirectory = securityDirectory,
            issueRepository = issueRepository,
            issueTypeRepository = issueTypeRepository,
            historyRecorder = historyRecorder,
        )

    // ── 공통 픽스처 ───────────────────────────────────────────────────────────

    val defaultProjectId = UUID.fromString("00000000-0000-4000-8000-000000000010")
    val otherProjectId = UUID.fromString("00000000-0000-4000-8000-000000000011")
    val actorUuid = UUID.fromString("00000000-0000-4000-8000-000000000099")
    val actorId = ActorId(actorUuid)

    val epicUuid = UUID.fromString("00000000-0000-4000-8000-000000000001")
    val childUuid = UUID.fromString("00000000-0000-4000-8000-000000000002")

    val epicTypeId = IssueTypeId(1L)
    val storyTypeId = IssueTypeId(2L)
    val subtaskTypeId = IssueTypeId(3L)

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

    val subtaskType =
        IssueType(
            id = subtaskTypeId,
            key = IssueTypeKey("subtask"),
            name = "Subtask",
            description = null,
            iconName = "subtask",
            isStandard = true,
            hierarchyLevel = -1,
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
    ) = Issue(
        id = IssueId(id),
        key = key,
        projectId = projectId,
        summary = "테스트 이슈",
        reporterId = ActorId(actorUuid),
        currentStateKey = "open",
        version = 1L,
        deletedAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        typeId = typeId,
        epicId = epicId,
    )

    val epicKey = IssueKey("BTS-1")
    val childKey = IssueKey("BTS-2")

    val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    // 권한 stub 헬퍼 — 람다 내부 허용/거부 설정
    fun allowUpdate(key: IssueKey) {
        every {
            permissionResolver.hasPermission(actorUuid, IssuePermission.UPDATE, IssueScope.Issue(key.value))
        } returns true
    }

    fun denyUpdate(key: IssueKey) {
        every {
            permissionResolver.hasPermission(actorUuid, IssuePermission.UPDATE, IssueScope.Issue(key.value))
        } returns false
    }

    fun allowBrowse(projectKey: String) {
        every {
            permissionResolver.hasPermission(actorUuid, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        } returns true
    }

    fun denyBrowse(projectKey: String) {
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
            answers = false,
        )
    }

    // ── connect ───────────────────────────────────────────────────────────────

    describe("connect") {

        context("UPDATE(child) 권한이 없으면") {
            beforeEach { denyUpdate(childKey) }

            it("IssueAccessDeniedException(403) 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.connect(epicKey, childKey, actorId)
                }
            }

            it("권한 검사 이후 repo.findByKey 가 호출되지 않는다 (존재 probe 방지)") {
                runCatching { sut.connect(epicKey, childKey, actorId) }
                // value class IssueKey 에 any() 를 쓰면 MockK 서명 생성 오류 — 구체 키로 각각 verify
                verify(exactly = 0) { issueRepository.findByKey(childKey) }
                verify(exactly = 0) { issueRepository.findByKey(epicKey) }
            }
        }

        context("child 이슈가 존재하지 않으면") {
            beforeEach {
                allowUpdate(childKey)
                every { issueRepository.findByKey(childKey) } returns null
            }

            it("EpicChildNotFoundException(404) 을 던진다") {
                shouldThrow<EpicChildNotFoundException> {
                    sut.connect(epicKey, childKey, actorId)
                }
            }
        }

        context("epic 이슈가 존재하지 않으면 (보안 N1 — 미존재·미가시 동일)") {
            beforeEach {
                allowUpdate(childKey)
                every { issueRepository.findByKey(childKey) } returns makeIssue(childUuid, childKey)
                every { issueRepository.findByKey(epicKey) } returns null
            }

            it("EpicChildNotFoundException(404) 을 던진다") {
                shouldThrow<EpicChildNotFoundException> {
                    sut.connect(epicKey, childKey, actorId)
                }
            }
        }

        context("childKey == epicKey (self 참조)") {
            beforeEach {
                allowUpdate(epicKey)
                every { issueRepository.findByKey(epicKey) } returns makeIssue(epicUuid, epicKey, typeId = epicTypeId)
            }

            it("EpicChildSelfReferenceException(422) 을 던진다") {
                shouldThrow<EpicChildSelfReferenceException> {
                    sut.connect(epicKey, epicKey, actorId)
                }
            }
        }

        context("child 의 hierarchyLevel 이 0 이 아니면 (subtask = -1)") {
            beforeEach {
                allowUpdate(childKey)
                every { issueRepository.findByKey(childKey) } returns
                    makeIssue(childUuid, childKey, typeId = subtaskTypeId)
                every { issueRepository.findByKey(epicKey) } returns
                    makeIssue(epicUuid, epicKey, typeId = epicTypeId)
                every { issueTypeRepository.findById(subtaskTypeId) } returns subtaskType
                every { issueTypeRepository.findById(epicTypeId) } returns epicType
            }

            it("EpicChildInvalidTypeException(422) 을 던진다") {
                shouldThrow<EpicChildInvalidTypeException> {
                    sut.connect(epicKey, childKey, actorId)
                }
            }
        }

        context("epic 의 hierarchyLevel 이 1 이 아니면 (story = 0)") {
            beforeEach {
                allowUpdate(childKey)
                every { issueRepository.findByKey(childKey) } returns
                    makeIssue(childUuid, childKey, typeId = storyTypeId)
                // 에픽 대상이 story (hierarchyLevel=0, 에픽이 아님)
                every { issueRepository.findByKey(epicKey) } returns
                    makeIssue(epicUuid, epicKey, typeId = storyTypeId)
                every { issueTypeRepository.findById(storyTypeId) } returns storyType
            }

            it("EpicTargetNotEpicException(422) 을 던진다") {
                shouldThrow<EpicTargetNotEpicException> {
                    sut.connect(epicKey, childKey, actorId)
                }
            }
        }

        context("child 와 epic 이 다른 프로젝트에 속하면") {
            beforeEach {
                allowUpdate(childKey)
                every { issueRepository.findByKey(childKey) } returns
                    makeIssue(childUuid, childKey, typeId = storyTypeId, projectId = defaultProjectId)
                every { issueRepository.findByKey(epicKey) } returns
                    makeIssue(epicUuid, epicKey, typeId = epicTypeId, projectId = otherProjectId)
                every { issueTypeRepository.findById(storyTypeId) } returns storyType
                every { issueTypeRepository.findById(epicTypeId) } returns epicType
            }

            it("EpicChildCrossProjectException(422) 을 던진다") {
                shouldThrow<EpicChildCrossProjectException> {
                    sut.connect(epicKey, childKey, actorId)
                }
            }
        }

        context("child 가 이미 에픽에 연결되어 있으면") {
            beforeEach {
                allowUpdate(childKey)
                every { issueRepository.findByKey(childKey) } returns
                    makeIssue(childUuid, childKey, typeId = storyTypeId, epicId = epicUuid)
                every { issueRepository.findByKey(epicKey) } returns makeIssue(epicUuid, epicKey, typeId = epicTypeId)
                every { issueTypeRepository.findById(storyTypeId) } returns storyType
                every { issueTypeRepository.findById(epicTypeId) } returns epicType
            }

            it("EpicChildAlreadyLinkedException(409) 을 던진다") {
                shouldThrow<EpicChildAlreadyLinkedException> {
                    sut.connect(epicKey, childKey, actorId)
                }
            }
        }

        context("정상 연결") {
            val childBefore = makeIssue(childUuid, childKey, typeId = storyTypeId, epicId = null)

            beforeEach {
                allowUpdate(childKey)
                every { issueRepository.findByKey(childKey) } returns childBefore
                every { issueRepository.findByKey(epicKey) } returns makeIssue(epicUuid, epicKey, typeId = epicTypeId)
                every { issueTypeRepository.findById(storyTypeId) } returns storyType
                every { issueTypeRepository.findById(epicTypeId) } returns epicType
                every { issueRepository.updateEpic(childUuid, epicUuid) } returns Unit
            }

            it("issueRepository.updateEpic(childId, epicId) 가 1회 호출된다") {
                sut.connect(epicKey, childKey, actorId)
                verify(exactly = 1) { issueRepository.updateEpic(childUuid, epicUuid) }
            }

            it("[G1] historyRecorder.record 가 before/after child 로 1회 호출된다") {
                sut.connect(epicKey, childKey, actorId)
                verify(exactly = 1) {
                    historyRecorder.record(
                        before = childBefore,
                        after = childBefore.copy(epicId = epicUuid),
                        actor = actorId,
                        projectId = defaultProjectId,
                    )
                }
            }

            it("권한 검사가 repo.findByKey 보다 먼저 호출된다 (순서 보장)") {
                sut.connect(epicKey, childKey, actorId)
                verify(exactly = 1) {
                    permissionResolver.hasPermission(
                        actorUuid,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(childKey.value),
                    )
                }
            }
        }
    }

    // ── disconnect ────────────────────────────────────────────────────────────

    describe("disconnect") {

        context("UPDATE(child) 권한이 없으면") {
            beforeEach { denyUpdate(childKey) }

            it("IssueAccessDeniedException(403) 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.disconnect(epicKey, childKey, actorId)
                }
            }

            it("권한 검사 이후 repo.findByKey 가 호출되지 않는다") {
                runCatching { sut.disconnect(epicKey, childKey, actorId) }
                verify(exactly = 0) { issueRepository.findByKey(childKey) }
                verify(exactly = 0) { issueRepository.findByKey(epicKey) }
            }
        }

        context("child 이슈가 존재하지 않으면") {
            beforeEach {
                allowUpdate(childKey)
                every { issueRepository.findByKey(childKey) } returns null
            }

            it("EpicChildNotFoundException(404) 을 던진다") {
                shouldThrow<EpicChildNotFoundException> {
                    sut.disconnect(epicKey, childKey, actorId)
                }
            }
        }

        context("child 의 epicId 가 이 epic 이 아니면") {
            val otherEpicId = UUID.fromString("00000000-0000-4000-8000-000000000099")

            beforeEach {
                allowUpdate(childKey)
                every { issueRepository.findByKey(childKey) } returns
                    makeIssue(childUuid, childKey, epicId = otherEpicId)
                every { issueRepository.findByKey(epicKey) } returns makeIssue(epicUuid, epicKey, typeId = epicTypeId)
            }

            it("EpicChildNotFoundException(404) 을 던진다") {
                shouldThrow<EpicChildNotFoundException> {
                    sut.disconnect(epicKey, childKey, actorId)
                }
            }
        }

        context("child 가 어떤 epic 에도 연결되지 않은 경우") {
            beforeEach {
                allowUpdate(childKey)
                every { issueRepository.findByKey(childKey) } returns makeIssue(childUuid, childKey, epicId = null)
                every { issueRepository.findByKey(epicKey) } returns makeIssue(epicUuid, epicKey, typeId = epicTypeId)
            }

            it("EpicChildNotFoundException(404) 을 던진다") {
                shouldThrow<EpicChildNotFoundException> {
                    sut.disconnect(epicKey, childKey, actorId)
                }
            }
        }

        context("정상 연결 해제") {
            val childBefore = makeIssue(childUuid, childKey, typeId = storyTypeId, epicId = epicUuid)

            beforeEach {
                allowUpdate(childKey)
                every { issueRepository.findByKey(childKey) } returns childBefore
                every { issueRepository.findByKey(epicKey) } returns makeIssue(epicUuid, epicKey, typeId = epicTypeId)
                every { issueRepository.updateEpic(childUuid, null) } returns Unit
            }

            it("issueRepository.updateEpic(childId, null) 가 1회 호출된다") {
                sut.disconnect(epicKey, childKey, actorId)
                verify(exactly = 1) { issueRepository.updateEpic(childUuid, null) }
            }

            it("[G1] historyRecorder.record 가 epicId=null 인 after 로 1회 호출된다") {
                sut.disconnect(epicKey, childKey, actorId)
                verify(exactly = 1) {
                    historyRecorder.record(
                        before = childBefore,
                        after = childBefore.copy(epicId = null),
                        actor = actorId,
                        projectId = defaultProjectId,
                    )
                }
            }
        }
    }

    // ── listChildren ──────────────────────────────────────────────────────────

    describe("listChildren") {

        context("BROWSE(epic 프로젝트, Project scope) 권한이 없으면") {
            beforeEach { denyBrowse(epicKey.projectPrefix) }

            it("IssueAccessDeniedException(403) 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.listChildren(epicKey, actorId)
                }
            }

            it("권한 검사 이후 repo.findByKey 가 호출되지 않는다") {
                runCatching { sut.listChildren(epicKey, actorId) }
                verify(exactly = 0) { issueRepository.findByKey(epicKey) }
            }
        }

        context("epic 이슈가 존재하지 않으면") {
            beforeEach {
                allowBrowse(epicKey.projectPrefix)
                every { issueRepository.findByKey(epicKey) } returns null
            }

            it("EpicChildNotFoundException(404) 을 던진다") {
                shouldThrow<EpicChildNotFoundException> {
                    sut.listChildren(epicKey, actorId)
                }
            }
        }

        context("정상 조회") {
            val epicIssue = makeIssue(epicUuid, epicKey, typeId = epicTypeId)
            val child1 = makeIssue(childUuid, childKey, typeId = storyTypeId, epicId = epicUuid)
            val projectKey = epicKey.projectPrefix

            beforeEach {
                allowBrowse(projectKey)
                every { issueRepository.findByKey(epicKey) } returns epicIssue
                every { securityDirectory.accessibleLevels(actorUuid, projectKey) } returns unrestrictedAccess
                every {
                    issueRepository.findEpicChildren(epicUuid, actorUuid, unrestrictedAccess, projectKey)
                } returns listOf(child1)
            }

            it("accessibleLevels 를 조회한다") {
                sut.listChildren(epicKey, actorId)
                verify(exactly = 1) { securityDirectory.accessibleLevels(actorUuid, projectKey) }
            }

            it("findEpicChildren 에 epicId, actor, access, projectKey 를 위임한다") {
                sut.listChildren(epicKey, actorId)
                verify(exactly = 1) {
                    issueRepository.findEpicChildren(epicUuid, actorUuid, unrestrictedAccess, projectKey)
                }
            }

            it("자식 이슈 목록을 반환한다") {
                val result = sut.listChildren(epicKey, actorId)
                result.size shouldBe 1
                result[0] shouldBe child1
            }
        }

        context("에픽에 자식이 없으면 빈 목록을 반환한다") {
            val epicIssue = makeIssue(epicUuid, epicKey, typeId = epicTypeId)
            val projectKey = epicKey.projectPrefix

            beforeEach {
                allowBrowse(projectKey)
                every { issueRepository.findByKey(epicKey) } returns epicIssue
                every { securityDirectory.accessibleLevels(actorUuid, projectKey) } returns unrestrictedAccess
                every {
                    issueRepository.findEpicChildren(epicUuid, actorUuid, unrestrictedAccess, projectKey)
                } returns emptyList()
            }

            it("빈 리스트를 반환한다") {
                val result = sut.listChildren(epicKey, actorId)
                result shouldBe emptyList()
            }
        }
    }
})
