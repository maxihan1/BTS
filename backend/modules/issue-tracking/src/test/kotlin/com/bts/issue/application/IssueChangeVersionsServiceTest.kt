// IssueApplicationService.changeAffectsVersions / changeFixVersions 단위 테스트 — MockK, TDD RED

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueLinkedVersionNotFoundException
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
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

class IssueChangeVersionsServiceTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>(relaxed = true)
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val componentRepository = mockk<ComponentRepository>(relaxed = true)
    val projectLeadRepository = mockk<ProjectLeadRepository>(relaxed = true)
    val versionRepository = mockk<VersionRepository>()
    val clock = Clock.fixed(Instant.parse("2026-06-10T00:00:00Z"), ZoneOffset.UTC)

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
            componentRepository = componentRepository,
            projectLeadRepository = projectLeadRepository,
            versionRepository = versionRepository,
            clock = clock,
        )

    val actor = ActorId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
    val issueKey = IssueKey("BTS-1")
    val projectId = UUID.fromString("00000000-0000-4000-8000-000000000010")
    val issueId = UUID.fromString("00000000-0000-4000-8000-000000000020")
    val existingVersion = 1L

    val v1 = UUID.fromString("00000000-0000-4000-8000-000000000041")
    val v2 = UUID.fromString("00000000-0000-4000-8000-000000000042")

    fun makeIssue(
        affectsVersionIds: List<UUID> = emptyList(),
        fixVersionIds: List<UUID> = emptyList(),
    ) = Issue(
        id = IssueId(issueId),
        key = issueKey,
        projectId = projectId,
        summary = "테스트 이슈",
        reporterId = actor,
        currentStateKey = "open",
        version = existingVersion,
        deletedAt = null,
        createdAt = Instant.parse("2026-06-10T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-10T00:00:00Z"),
        typeId = IssueTypeId(3L),
        affectsVersionIds = affectsVersionIds,
        fixVersionIds = fixVersionIds,
    )

    fun makeResponse() =
        IssueResponse(
            key = issueKey.value,
            id = issueId,
            projectKey = "BTS",
            summary = "테스트 이슈",
            currentStateKey = "open",
            reporterId = actor.value,
            version = existingVersion,
            createdAt = Instant.parse("2026-06-10T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-10T00:00:00Z"),
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
        )

    beforeEach {
        clearMocks(repo, permissionResolver, versionRepository, answers = false)
    }

    // ── changeAffectsVersions ────────────────────────────────────────────────

    describe("changeAffectsVersions") {

        context("happy path — 모든 버전 활성, 권한 OK") {
            val request = AppChangeVersionsRequest(versionIds = listOf(v1, v2), expectedVersion = existingVersion)
            val existingIssue = makeIssue()
            val response = makeResponse()

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { versionRepository.findById(v1, projectId) } returns mockk()
                every { versionRepository.findById(v2, projectId) } returns mockk()
                every { repo.replaceAffectsVersions(issueKey, issueId, listOf(v1, v2), existingVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns response
                every { repo.findActiveComponentIdsByIssue(issueId) } returns emptyList()
            }

            it("replaceAffectsVersions 가 1회 호출된다") {
                sut.changeAffectsVersions(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.replaceAffectsVersions(issueKey, issueId, listOf(v1, v2), existingVersion)
                }
            }

            it("IssueResponse 가 반환된다") {
                val result = sut.changeAffectsVersions(actor, issueKey, request)
                result.key shouldBe issueKey.value
            }
        }

        context("중복 ID 정규화 — [V1, V1, V2] 입력이 도메인 assignAffectsVersions 경유로 distinct됨") {
            val request = AppChangeVersionsRequest(versionIds = listOf(v1, v1, v2), expectedVersion = existingVersion)
            val existingIssue = makeIssue()
            val response = makeResponse()

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { versionRepository.findById(v1, projectId) } returns mockk()
                every { versionRepository.findById(v2, projectId) } returns mockk()
                every { repo.replaceAffectsVersions(issueKey, issueId, listOf(v1, v2), existingVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns response
                every { repo.findActiveComponentIdsByIssue(issueId) } returns emptyList()
            }

            it("replaceAffectsVersions 에 distinct 된 목록 [V1, V2] 가 전달된다") {
                sut.changeAffectsVersions(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.replaceAffectsVersions(issueKey, issueId, listOf(v1, v2), existingVersion)
                }
            }
        }

        context("422 — 타 프로젝트/삭제 버전 포함") {
            val unknownId = UUID.fromString("00000000-0000-4000-8000-000000000099")
            val request = AppChangeVersionsRequest(versionIds = listOf(v1, unknownId), expectedVersion = existingVersion)
            val existingIssue = makeIssue()

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { versionRepository.findById(v1, projectId) } returns mockk()
                every { versionRepository.findById(unknownId, projectId) } returns null
            }

            it("IssueLinkedVersionNotFoundException 을 던진다") {
                shouldThrow<IssueLinkedVersionNotFoundException> {
                    sut.changeAffectsVersions(actor, issueKey, request)
                }
            }

            it("replaceAffectsVersions 가 호출되지 않는다") {
                runCatching { sut.changeAffectsVersions(actor, issueKey, request) }
                verify(exactly = 0) {
                    repo.replaceAffectsVersions(issueKey, issueId, any(), any())
                }
            }
        }

        context("ARCHIVED 버전 — deleted_at IS NULL 이면 통과") {
            // ARCHIVED 는 status만 다르고 deleted_at=null 이므로 findById 가 Version 을 반환한다.
            val request = AppChangeVersionsRequest(versionIds = listOf(v1), expectedVersion = existingVersion)
            val existingIssue = makeIssue()
            val response = makeResponse()

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                // ARCHIVED Version — versionRepository 는 deleted_at IS NULL 조건만 적용하므로 반환된다.
                every { versionRepository.findById(v1, projectId) } returns mockk()
                every { repo.replaceAffectsVersions(issueKey, issueId, listOf(v1), existingVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns response
                every { repo.findActiveComponentIdsByIssue(issueId) } returns emptyList()
            }

            it("IssueLinkedVersionNotFoundException 을 던지지 않는다") {
                val result = sut.changeAffectsVersions(actor, issueKey, request)
                result.key shouldBe issueKey.value
            }
        }

        context("409 — 낙관락 충돌 (replaceAffectsVersions 0 반환)") {
            val request = AppChangeVersionsRequest(versionIds = listOf(v1), expectedVersion = existingVersion)
            val existingIssue = makeIssue()

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { versionRepository.findById(v1, projectId) } returns mockk()
                every { repo.replaceAffectsVersions(issueKey, issueId, listOf(v1), existingVersion) } returns 0
            }

            it("IssueVersionConflictException 을 던진다") {
                shouldThrow<IssueVersionConflictException> {
                    sut.changeAffectsVersions(actor, issueKey, request)
                }
            }
        }

        context("404 — 이슈 미존재") {
            val request = AppChangeVersionsRequest(versionIds = listOf(v1), expectedVersion = existingVersion)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns null
            }

            it("IssueNotFoundException 을 던진다") {
                shouldThrow<IssueNotFoundException> {
                    sut.changeAffectsVersions(actor, issueKey, request)
                }
            }
        }

        context("403 — 권한 없음") {
            val request = AppChangeVersionsRequest(versionIds = listOf(v1), expectedVersion = existingVersion)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.changeAffectsVersions(actor, issueKey, request)
                }
            }

            it("repo 가 호출되지 않는다") {
                runCatching { sut.changeAffectsVersions(actor, issueKey, request) }
                verify(exactly = 0) { repo.findByKey(issueKey) }
            }
        }
    }

    // ── changeFixVersions ────────────────────────────────────────────────────

    describe("changeFixVersions") {

        context("happy path — 모든 버전 활성, 권한 OK") {
            val request = AppChangeVersionsRequest(versionIds = listOf(v1, v2), expectedVersion = existingVersion)
            val existingIssue = makeIssue()
            val response = makeResponse()

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { versionRepository.findById(v1, projectId) } returns mockk()
                every { versionRepository.findById(v2, projectId) } returns mockk()
                every { repo.replaceFixVersions(issueKey, issueId, listOf(v1, v2), existingVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns response
                every { repo.findActiveComponentIdsByIssue(issueId) } returns emptyList()
            }

            it("replaceFixVersions 가 1회 호출된다") {
                sut.changeFixVersions(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.replaceFixVersions(issueKey, issueId, listOf(v1, v2), existingVersion)
                }
            }

            it("IssueResponse 가 반환된다") {
                val result = sut.changeFixVersions(actor, issueKey, request)
                result.key shouldBe issueKey.value
            }
        }

        context("중복 ID 정규화 — [V1, V1, V2] 입력이 도메인 assignFixVersions 경유로 distinct됨") {
            val request = AppChangeVersionsRequest(versionIds = listOf(v1, v1, v2), expectedVersion = existingVersion)
            val existingIssue = makeIssue()
            val response = makeResponse()

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { versionRepository.findById(v1, projectId) } returns mockk()
                every { versionRepository.findById(v2, projectId) } returns mockk()
                every { repo.replaceFixVersions(issueKey, issueId, listOf(v1, v2), existingVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns response
                every { repo.findActiveComponentIdsByIssue(issueId) } returns emptyList()
            }

            it("replaceFixVersions 에 distinct 된 목록 [V1, V2] 가 전달된다") {
                sut.changeFixVersions(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.replaceFixVersions(issueKey, issueId, listOf(v1, v2), existingVersion)
                }
            }
        }

        context("422 — 타 프로젝트/삭제 버전 포함") {
            val unknownId = UUID.fromString("00000000-0000-4000-8000-000000000099")
            val request = AppChangeVersionsRequest(versionIds = listOf(v1, unknownId), expectedVersion = existingVersion)
            val existingIssue = makeIssue()

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { versionRepository.findById(v1, projectId) } returns mockk()
                every { versionRepository.findById(unknownId, projectId) } returns null
            }

            it("IssueLinkedVersionNotFoundException 을 던진다") {
                shouldThrow<IssueLinkedVersionNotFoundException> {
                    sut.changeFixVersions(actor, issueKey, request)
                }
            }

            it("replaceFixVersions 가 호출되지 않는다") {
                runCatching { sut.changeFixVersions(actor, issueKey, request) }
                verify(exactly = 0) {
                    repo.replaceFixVersions(issueKey, issueId, any(), any())
                }
            }
        }

        context("409 — 낙관락 충돌 (replaceFixVersions 0 반환)") {
            val request = AppChangeVersionsRequest(versionIds = listOf(v1), expectedVersion = existingVersion)
            val existingIssue = makeIssue()

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { versionRepository.findById(v1, projectId) } returns mockk()
                every { repo.replaceFixVersions(issueKey, issueId, listOf(v1), existingVersion) } returns 0
            }

            it("IssueVersionConflictException 을 던진다") {
                shouldThrow<IssueVersionConflictException> {
                    sut.changeFixVersions(actor, issueKey, request)
                }
            }
        }

        context("403 — 권한 없음") {
            val request = AppChangeVersionsRequest(versionIds = listOf(v1), expectedVersion = existingVersion)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.changeFixVersions(actor, issueKey, request)
                }
            }
        }
    }
})
