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
import com.bts.issue.event.IssueAssigned
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
            historyRecorder = io.mockk.mockk(relaxed = true),
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
        // ★eventPublisher 를 빼면 발행 횟수가 테스트 간에 **누적**돼 `verify(exactly = 0)` 이
        //   이전 테스트의 발행 때문에 실패한다. 배정 알림 단언이 들어오면서 필요해졌다.
        clearMocks(
            repo,
            permissionResolver,
            componentRepository,
            projectLeadRepository,
            eventPublisher,
            answers = false,
        )
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

        // ─────────────────────────────────────────────────────────────────────
        // 자동 배정 알림 (TODOS 「changeComponents 자동배정도 IssueAssigned 를 발행하지 않는다」)
        //
        // `cloneIssue` 와 **정확히 같은 양식**이었다. 같은 「배정」인데 경로에 따라 알림이 갈렸다 —
        // 컴포넌트를 바꿔 자동 배정된 담당자는 자기가 담당자가 된 사실을 통보받지 못한다.
        //
        // ★게이트는 `createIssue`·`cloneIssue` 와 같은 fail-safe 형태(기본 false)다.
        //   `changeAssignee` 처럼 무조건 발행하지 않는 이유 — 그쪽은 **배정 자체가 목적**인
        //   함수라 모든 생산자가 알림을 의도한다. 여기는 컴포넌트 교체의 **부수효과**로 배정이
        //   일어나므로, 앞으로 생길 생산자(대량 컴포넌트 편집·자동화 규칙 등)가 알림을
        //   켠 채로 태어나면 안 된다.
        // ─────────────────────────────────────────────────────────────────────
        context("자동 배정 알림 — notifyAssignment × 배정 성사 2×2") {
            val leadId = UUID.fromString("00000000-0000-4000-8000-000000000041")

            fun stubCommon(existingIssue: Issue) {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.UPDATE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { componentRepository.findById(c1, projectId) } returns mockk()
                every { componentRepository.findByProject(projectId) } returns emptyList()
                every {
                    repo.replaceComponents(issueKey, issueId, listOf(c1), existingVersion)
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns makeResponse(componentIds = listOf(c1))
                every { repo.findActiveComponentIdsByIssue(issueId) } returns listOf(c1)
                every { repo.setAssignee(issueId, leadId) } returns Unit
            }

            fun request(notify: Boolean) =
                AppChangeComponentsRequest(
                    componentIds = listOf(c1),
                    expectedVersion = existingVersion,
                    notifyAssignment = notify,
                )

            it("notify=true + 자동 배정 성사면 IssueAssigned 를 대상 이슈 키로 1회 발행한다") {
                stubCommon(makeIssue())
                every { projectLeadRepository.findLeadUserId(projectId) } returns leadId

                sut.changeComponents(actor, issueKey, request(notify = true))

                // ★issueKey 는 반드시 **대상 이슈**의 키다. 알림이 엉뚱한 이슈로 가면
                //   담당자가 아닌 사람이 통보를 받는 더 나쁜 결함이 된다.
                verify(exactly = 1) {
                    eventPublisher.publish(match { it is IssueAssigned && it.issueKey == issueKey })
                }
            }

            it("notify=true 인데 배정 후보가 없으면 발행하지 않는다") {
                stubCommon(makeIssue())
                every { projectLeadRepository.findLeadUserId(projectId) } returns null

                sut.changeComponents(actor, issueKey, request(notify = true))

                verify(exactly = 0) { eventPublisher.publish(match { it is IssueAssigned }) }
            }

            it("notify 기본값(false)이면 자동 배정이 성사돼도 발행하지 않는다 (fail-safe)") {
                stubCommon(makeIssue())
                every { projectLeadRepository.findLeadUserId(projectId) } returns leadId

                // 기본값을 그대로 쓴다 — 새 생산자가 알림을 꺼진 채로 물려받는지 확인하는 것이 요점이다.
                sut.changeComponents(
                    actor,
                    issueKey,
                    AppChangeComponentsRequest(componentIds = listOf(c1), expectedVersion = existingVersion),
                )

                verify(exactly = 0) { eventPublisher.publish(match { it is IssueAssigned }) }
            }

            it("notify=false + 배정 후보 없음이면 발행하지 않는다") {
                stubCommon(makeIssue())
                every { projectLeadRepository.findLeadUserId(projectId) } returns null

                sut.changeComponents(actor, issueKey, request(notify = false))

                verify(exactly = 0) { eventPublisher.publish(match { it is IssueAssigned }) }
            }

            it("이미 담당자가 있으면 자동 배정 자체를 하지 않으므로 notify=true 여도 발행하지 않는다") {
                // 경계 확인 — 「배정이 일어났을 때만」이 판정식이다. 기존 담당자 유지는 배정이 아니다.
                val withAssignee = makeIssue().assignTo(actor)
                stubCommon(withAssignee)
                every { projectLeadRepository.findLeadUserId(projectId) } returns leadId

                sut.changeComponents(actor, issueKey, request(notify = true))

                verify(exactly = 0) { eventPublisher.publish(match { it is IssueAssigned }) }
                verify(exactly = 0) { repo.setAssignee(any(), any()) }
            }
        }
    }
})
