// IssueApplicationService.softDeleteIssue 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueSoftDeleted
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IssueApplicationServiceSoftDeleteTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val clock = Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneOffset.UTC)

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
            clock = clock,
        )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, answers = false)
    }

    describe("softDeleteIssue") {

        context("정상 — 권한 있고 삭제 성공") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SOFT_DELETE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.softDelete(issueKey) } returns 1
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("예외 없이 완료된다") {
                sut.softDeleteIssue(actor, issueKey)
            }

            it("IssueSoftDeleted 이벤트가 발행된다") {
                sut.softDeleteIssue(actor, issueKey)
                verify {
                    eventPublisher.publish(
                        match { it is IssueSoftDeleted && it.issueKey == issueKey },
                    )
                }
            }
        }

        context("미존재 또는 이미 삭제된 이슈 — softDelete 0 row 반환") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SOFT_DELETE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.softDelete(issueKey) } returns 0
            }

            it("IssueNotFoundException 을 던진다") {
                shouldThrow<IssueNotFoundException> {
                    sut.softDeleteIssue(actor, issueKey)
                }
            }

            it("이벤트가 발행되지 않는다") {
                runCatching { sut.softDeleteIssue(actor, issueKey) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("권한 없을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.SOFT_DELETE,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.softDeleteIssue(actor, issueKey)
                }
            }

            it("repo 및 eventPublisher 가 호출되지 않는다") {
                runCatching { sut.softDeleteIssue(actor, issueKey) }
                verify(exactly = 0) { repo.softDelete(issueKey) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }
    }
})
