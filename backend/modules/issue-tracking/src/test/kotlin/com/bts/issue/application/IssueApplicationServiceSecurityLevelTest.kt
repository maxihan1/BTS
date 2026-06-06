// IssueApplicationService 보안 등급 지정 단위 테스트 — SET_SECURITY 가드·422·3-state, MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueSecurityLevelNotInSchemeException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 보안 등급 지정 API(FR-PM-06 PR-B Task 6) 단위 테스트.
 *
 * 검증 대상.
 * - S10. securityLevelId 지정/변경/해제 요청 시 SET_SECURITY 권한 미보유 → 403.
 * - S11. 지정 등급이 적용 스킴 미소속(levelBelongsToProjectScheme==false) → 422.
 * - S12. 3-state(부재=무변경 / 명시 null=해제 / 값=지정).
 */
class IssueApplicationServiceSecurityLevelTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>(relaxed = true)
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val securityDirectory = mockk<IssueSecurityDirectory>()
    val clock = Clock.fixed(Instant.parse("2026-06-06T00:00:00Z"), ZoneOffset.UTC)

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
            securityDirectory = securityDirectory,
            clock = clock,
        )

    val actor = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000099"))
    val projectKey = "BTS"
    val issueKey = IssueKey("BTS-1")
    val existingVersion = 3L
    val levelId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    fun makeIssue() =
        Issue(
            id = IssueId(UUID.randomUUID()),
            key = issueKey,
            projectId = UUID.randomUUID(),
            summary = "원래",
            reporterId = actor,
            currentStateKey = "open",
            version = existingVersion,
            deletedAt = null,
            createdAt = Instant.parse("2026-06-06T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-06T00:00:00Z"),
            typeId = IssueTypeId(3L),
        )

    fun makeResponse() =
        IssueResponse(
            key = issueKey.value,
            id = UUID.randomUUID(),
            projectKey = issueKey.projectPrefix,
            summary = "원래",
            currentStateKey = "open",
            reporterId = actor.value,
            version = existingVersion + 1,
            createdAt = Instant.parse("2026-06-06T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-06T00:00:00Z"),
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
        )

    beforeEach {
        clearMocks(repo, permissionResolver, securityDirectory, answers = false)
        every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()
        every { repo.findByKey(issueKey) } returns makeIssue()
        every { repo.findByKeyWithType(issueKey) } returns makeResponse()
        // UPDATE 권한은 기본 보유 — 보안 가드와 분리해 검증하기 위함.
        every {
            permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
        } returns true
    }

    describe("updateIssue 보안 등급 3-state") {

        context("S12-a — securityLevel 부재(Unchanged): 무변경") {
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    securityLevel = SecurityLevelPatch.Unchanged,
                )

            it("updateSecurityLevel 및 SET_SECURITY 가드를 호출하지 않는다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { repo.updateSecurityLevel(any(), any(), any()) }
                verify(exactly = 0) {
                    permissionResolver.hasPermission(actor.value, IssuePermission.SET_SECURITY, any())
                }
            }
        }

        context("S12-b — securityLevel 명시 null(Clear): 해제") {
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    securityLevel = SecurityLevelPatch.Clear,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SET_SECURITY,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.updateSecurityLevel(issueKey, null, existingVersion) } returns 1
            }

            it("SET_SECURITY 가드 후 updateSecurityLevel(null) 1회 — levelBelongsToProjectScheme 미호출") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) { repo.updateSecurityLevel(issueKey, null, existingVersion) }
                verify(exactly = 0) { securityDirectory.levelBelongsToProjectScheme(any(), any()) }
            }
        }

        context("S12-c — securityLevel 값(Assign): 지정") {
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    securityLevel = SecurityLevelPatch.Assign(levelId),
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SET_SECURITY,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { securityDirectory.levelBelongsToProjectScheme(levelId, projectKey) } returns true
                every { repo.updateSecurityLevel(issueKey, levelId, existingVersion) } returns 1
            }

            it("스킴 소속 검증 통과 후 updateSecurityLevel(levelId) 1회") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) { repo.updateSecurityLevel(issueKey, levelId, existingVersion) }
            }
        }

        context("S10 — SET_SECURITY 미보유: 403 (Assign)") {
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    securityLevel = SecurityLevelPatch.Assign(levelId),
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SET_SECURITY,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns false
            }

            it("IssueAccessDeniedException(403) — updateSecurityLevel 미도달") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.updateIssue(actor, issueKey, request)
                }
                verify(exactly = 0) { repo.updateSecurityLevel(any(), any(), any()) }
            }
        }

        context("S10 — SET_SECURITY 미보유: 403 (Clear)") {
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    securityLevel = SecurityLevelPatch.Clear,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SET_SECURITY,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns false
            }

            it("해제도 SET_SECURITY 가드 — 미보유 시 403") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.updateIssue(actor, issueKey, request)
                }
                verify(exactly = 0) { repo.updateSecurityLevel(any(), any(), any()) }
            }
        }

        context("S11 — 적용 스킴 미소속 등급: 422 (Assign)") {
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    securityLevel = SecurityLevelPatch.Assign(levelId),
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SET_SECURITY,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { securityDirectory.levelBelongsToProjectScheme(levelId, projectKey) } returns false
            }

            it("IssueSecurityLevelNotInSchemeException(422) — updateSecurityLevel 미도달") {
                shouldThrow<IssueSecurityLevelNotInSchemeException> {
                    sut.updateIssue(actor, issueKey, request)
                }
                verify(exactly = 0) { repo.updateSecurityLevel(any(), any(), any()) }
            }
        }
    }

    describe("createIssue 보안 등급 지정") {
        val fixedProjectId = UUID.fromString("00000000-0000-0000-0000-000000000001")

        fun createRequest(securityLevelId: UUID?) =
            CreateIssueRequest(
                projectKey = projectKey,
                summary = "새 이슈",
                reporterId = actor,
                typeId = null,
                securityLevelId = securityLevelId,
            )

        beforeEach {
            every {
                permissionResolver.hasPermission(actor.value, IssuePermission.CREATE, IssueScope.Project(projectKey))
            } returns true
            every { repo.incrementKeySequence(projectKey) } returns 1L
            every { repo.findProjectIdByKey(projectKey) } returns fixedProjectId
            every { repo.insert(any()) } answers { firstArg() }
            every { repo.insertComponents(any(), any()) } returns Unit
            every {
                workflowKeyResolver.resolveStart(ProjectKey.of(projectKey), null)
            } returns WorkflowStartState(workflowKey = "software-default", startStateKey = "open")
            every { repo.findByKeyWithType(any()) } returns makeResponse()
            every { permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, any()) } returns true
        }

        context("securityLevelId=null — 등급 없음: SET_SECURITY 가드/스킴 검증 미호출") {
            it("보안 가드 없이 생성된다") {
                sut.createIssue(actor, createRequest(null))
                verify(exactly = 0) {
                    permissionResolver.hasPermission(actor.value, IssuePermission.SET_SECURITY, any())
                }
                verify(exactly = 0) { securityDirectory.levelBelongsToProjectScheme(any(), any()) }
            }
        }

        context("securityLevelId=값 — SET_SECURITY 미보유: 403") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SET_SECURITY,
                        IssueScope.Project(projectKey),
                    )
                } returns false
            }

            it("IssueAccessDeniedException — insert 미도달") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.createIssue(actor, createRequest(levelId))
                }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("securityLevelId=값 — 적용 스킴 미소속: 422") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SET_SECURITY,
                        IssueScope.Project(projectKey),
                    )
                } returns true
                every { securityDirectory.levelBelongsToProjectScheme(levelId, projectKey) } returns false
            }

            it("IssueSecurityLevelNotInSchemeException — insert 미도달") {
                shouldThrow<IssueSecurityLevelNotInSchemeException> {
                    sut.createIssue(actor, createRequest(levelId))
                }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("securityLevelId=값 — 가드/스킴 통과: securityLevelId 가 도메인에 반영") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SET_SECURITY,
                        IssueScope.Project(projectKey),
                    )
                } returns true
                every { securityDirectory.levelBelongsToProjectScheme(levelId, projectKey) } returns true
            }

            it("insert 된 Issue.securityLevelId 가 지정값이다") {
                sut.createIssue(actor, createRequest(levelId))
                verify(exactly = 1) { repo.insert(match { it.securityLevelId == levelId }) }
            }
        }
    }
})
