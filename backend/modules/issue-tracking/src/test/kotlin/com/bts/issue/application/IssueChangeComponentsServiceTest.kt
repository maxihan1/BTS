// IssueApplicationService.changeComponents 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueComponentNotFoundException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
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

class IssueChangeComponentsServiceTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>(relaxed = true)
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val componentRepository = mockk<ComponentRepository>()
    val projectLeadRepository = mockk<ProjectLeadRepository>(relaxed = true)
    val clock = Clock.fixed(Instant.parse("2026-06-05T00:00:00Z"), ZoneOffset.UTC)

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
            versionRepository = mockk(relaxed = true),
            clock = clock,
        )

    val actor = ActorId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
    val issueKey = IssueKey("BTS-1")
    val projectId = UUID.fromString("00000000-0000-4000-8000-000000000010")
    val issueId = UUID.fromString("00000000-0000-4000-8000-000000000020")
    val existingVersion = 1L

    val c1 = UUID.fromString("00000000-0000-4000-8000-000000000031")
    val c2 = UUID.fromString("00000000-0000-4000-8000-000000000032")

    fun makeIssue(componentIds: List<UUID> = emptyList()) =
        Issue(
            id = IssueId(issueId),
            key = issueKey,
            projectId = projectId,
            summary = "테스트 이슈",
            reporterId = actor,
            currentStateKey = "open",
            version = existingVersion,
            deletedAt = null,
            createdAt = Instant.parse("2026-06-05T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-05T00:00:00Z"),
            typeId = IssueTypeId(3L),
            componentIds = componentIds,
        )

    fun makeResponse(componentIds: List<UUID> = emptyList()) =
        IssueResponse(
            key = issueKey.value,
            id = issueId,
            projectKey = "BTS",
            summary = "테스트 이슈",
            currentStateKey = "open",
            reporterId = actor.value,
            version = existingVersion,
            createdAt = Instant.parse("2026-06-05T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-05T00:00:00Z"),
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
            componentIds = componentIds,
        )

    beforeEach {
        clearMocks(repo, permissionResolver, componentRepository, projectLeadRepository, answers = false)
        every { projectLeadRepository.findLeadUserId(any()) } returns null
        every { repo.findAffectsVersionIdsByIssue(any()) } returns emptyList()
        every { repo.findFixVersionIdsByIssue(any()) } returns emptyList()
    }

    describe("changeComponents") {

        context("happy path — 모든 컴포넌트 활성, 권한 OK") {
            val request =
                AppChangeComponentsRequest(
                    componentIds = listOf(c1, c2),
                    expectedVersion = existingVersion,
                )
            val existingIssue = makeIssue()
            val responseWithComponents = makeResponse(componentIds = listOf(c1, c2))

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { componentRepository.findById(c1, projectId) } returns mockk()
                every { componentRepository.findById(c2, projectId) } returns mockk()
                // FR-CM-03 Task 5: assignee null 이므로 resolveDefaultAssignee 호출 — findByProject stub 필요
                every { componentRepository.findByProject(projectId) } returns emptyList()
                every {
                    repo.replaceComponents(issueKey, issueId, listOf(c1, c2), existingVersion)
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns responseWithComponents
                every { repo.findActiveComponentIdsByIssue(issueId) } returns listOf(c1, c2)
            }

            it("replaceComponents 가 1회 호출된다") {
                sut.changeComponents(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.replaceComponents(issueKey, issueId, listOf(c1, c2), existingVersion)
                }
            }

            it("응답에 componentIds 가 포함된다") {
                val result = sut.changeComponents(actor, issueKey, request)
                result.componentIds shouldBe listOf(c1, c2)
            }

            it("IssueResponse 가 반환된다") {
                val result = sut.changeComponents(actor, issueKey, request)
                result.key shouldBe issueKey.value
            }
        }

        context("중복 ID 정규화 — [C1, C1, C2] 입력이 도메인 assignComponents 경유로 distinct됨") {
            val request =
                AppChangeComponentsRequest(
                    componentIds = listOf(c1, c1, c2),
                    expectedVersion = existingVersion,
                )
            val existingIssue = makeIssue()
            val responseWithComponents = makeResponse(componentIds = listOf(c1, c2))

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { componentRepository.findById(c1, projectId) } returns mockk()
                every { componentRepository.findById(c2, projectId) } returns mockk()
                // FR-CM-03 Task 5: assignee null 이므로 resolveDefaultAssignee 호출 — findByProject stub 필요
                every { componentRepository.findByProject(projectId) } returns emptyList()
                every {
                    repo.replaceComponents(issueKey, issueId, listOf(c1, c2), existingVersion)
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns responseWithComponents
                every { repo.findActiveComponentIdsByIssue(issueId) } returns listOf(c1, c2)
            }

            it("replaceComponents 에 distinct 된 목록 [C1, C2] 가 전달된다") {
                sut.changeComponents(actor, issueKey, request)
                // [C1, C1, C2] 가 raw로 전달되면 stub 미매칭으로 MockK 에러 발생 → 미호출/정규화 확인
                verify(exactly = 1) {
                    repo.replaceComponents(issueKey, issueId, listOf(c1, c2), existingVersion)
                }
            }
        }

        context("422 — 비활성/타 프로젝트 컴포넌트 포함") {
            val unknownId = UUID.fromString("00000000-0000-4000-8000-000000000099")
            val request =
                AppChangeComponentsRequest(
                    componentIds = listOf(c1, unknownId),
                    expectedVersion = existingVersion,
                )
            val existingIssue = makeIssue()

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { componentRepository.findById(c1, projectId) } returns mockk()
                every { componentRepository.findById(unknownId, projectId) } returns null
            }

            it("IssueComponentNotFoundException 을 던진다") {
                shouldThrow<IssueComponentNotFoundException> {
                    sut.changeComponents(actor, issueKey, request)
                }
            }

            it("replaceComponents 가 호출되지 않는다") {
                runCatching { sut.changeComponents(actor, issueKey, request) }
                // IssueKey 는 value class — any() 시그니처 생성 함정 회피를 위해 구체값 사용
                verify(exactly = 0) {
                    repo.replaceComponents(issueKey, issueId, any(), any())
                }
            }
        }

        context("404 — 이슈 미존재") {
            val request =
                AppChangeComponentsRequest(
                    componentIds = listOf(c1),
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKey(issueKey) } returns null
            }

            it("IssueNotFoundException 을 던진다") {
                shouldThrow<IssueNotFoundException> {
                    sut.changeComponents(actor, issueKey, request)
                }
            }
        }

        context("409 — 낙관락 충돌 (replaceComponents 0 반환)") {
            val request =
                AppChangeComponentsRequest(
                    componentIds = listOf(c1),
                    expectedVersion = existingVersion,
                )
            val existingIssue = makeIssue()

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { componentRepository.findById(c1, projectId) } returns mockk()
                every {
                    repo.replaceComponents(issueKey, issueId, listOf(c1), existingVersion)
                } returns 0
            }

            it("IssueVersionConflictException 을 던진다") {
                shouldThrow<IssueVersionConflictException> {
                    sut.changeComponents(actor, issueKey, request)
                }
            }
        }

        context("403 — 권한 없음") {
            val request =
                AppChangeComponentsRequest(
                    componentIds = listOf(c1),
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.changeComponents(actor, issueKey, request)
                }
            }

            it("repo 가 호출되지 않는다") {
                runCatching { sut.changeComponents(actor, issueKey, request) }
                // IssueKey 는 value class — any() 시그니처 생성 함정 회피를 위해 구체값 사용
                verify(exactly = 0) { repo.findByKey(issueKey) }
            }
        }
    }
})
