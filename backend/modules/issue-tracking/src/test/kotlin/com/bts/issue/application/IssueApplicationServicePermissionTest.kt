// IssueApplicationService 권한 가드 단위 테스트 — FR-PM-05 BROWSE/VIEW 분리, MockK
// 단건 VIEW 미인가는 존재 숨김(404), 목록은 BROWSE(403) 정책 검증.

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * FR-PM-05 — BROWSE/VIEW 권한 분리 가드 단위 테스트.
 *
 * - 단건 경로(findByKey / availableTransitions / cloneIssue 소스)의 VIEW 미인가는
 *   존재 숨김 정책(ADR 2026-06-05-issue-browse-view-permission D2)에 따라
 *   IssueAccessDeniedException(403)이 아니라 IssueNotFoundException(404)으로 응답한다.
 * - 목록 경로(listIssues)는 BROWSE 권한을 Project 범위로 검사하며, 미인가는 403(IssueAccessDeniedException)을 유지한다.
 */
class IssueApplicationServicePermissionTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
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
            componentRepository = mockk(relaxed = true),
            projectLeadRepository = mockk(relaxed = true),
            versionRepository = mockk(relaxed = true),
            clock = clock,
        )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")
    val projectKey = "BTS"

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, workflowPort, answers = false)
    }

    describe("단건 VIEW 미인가 → 존재 숨김(404)") {

        context("findByKey — VIEW false") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns false
            }

            it("IssueNotFoundException 을 던진다 (IssueAccessDeniedException 아님)") {
                shouldThrow<IssueNotFoundException> {
                    sut.findByKey(actor, issueKey)
                }
            }

            it("repo 조회 부수효과가 없다") {
                runCatching { sut.findByKey(actor, issueKey) }
                verify(exactly = 0) { repo.findByKeyWithType(issueKey) }
            }
        }

        context("availableTransitions — VIEW false") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns false
            }

            it("IssueNotFoundException 을 던진다") {
                shouldThrow<IssueNotFoundException> {
                    sut.availableTransitions(actor, issueKey)
                }
            }

            it("repo/워크플로우 조회 부수효과가 없다") {
                runCatching { sut.availableTransitions(actor, issueKey) }
                verify(exactly = 0) { repo.findByKey(issueKey) }
                verify(exactly = 0) { workflowPort.availableTransitions(any()) }
            }
        }

        context("cloneIssue 소스 — 소스 VIEW false") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns false
            }

            it("IssueNotFoundException 을 던진다 (IssueAccessDeniedException 아님)") {
                shouldThrow<IssueNotFoundException> {
                    sut.cloneIssue(actor, issueKey, CloneIssueRequest())
                }
            }

            it("원본 조회·키 증가·insert 부수효과가 없다") {
                runCatching { sut.cloneIssue(actor, issueKey, CloneIssueRequest()) }
                verify(exactly = 0) { repo.findByKey(issueKey) }
                verify(exactly = 0) { repo.incrementKeySequence(any()) }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }
    }

    describe("listIssues — BROWSE 권한(Project 범위)") {

        val pageable = PageRequest.of(0, 20)

        context("BROWSE false") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.BROWSE,
                        IssueScope.Project(projectKey),
                    )
                } returns false
            }

            it("IssueAccessDeniedException(403) 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.listIssues(actor, projectKey, pageable)
                }
            }

            it("hasPermission 을 BROWSE + Project 범위로 호출한다") {
                runCatching { sut.listIssues(actor, projectKey, pageable) }
                verify {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.BROWSE,
                        IssueScope.Project(projectKey),
                    )
                }
            }

            it("VIEW 로는 호출하지 않는다") {
                runCatching { sut.listIssues(actor, projectKey, pageable) }
                verify(exactly = 0) {
                    permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Project(projectKey))
                }
            }
        }
    }
})
