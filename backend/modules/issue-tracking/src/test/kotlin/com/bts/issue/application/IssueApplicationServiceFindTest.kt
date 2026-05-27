// IssueApplicationService.findByKey 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssuePermissionResolver
import com.bts.issue.port.outbound.IssueScope
import com.bts.issue.repository.IssueRepository
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

class IssueApplicationServiceFindTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val clock = Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneOffset.UTC)

    val sut = IssueApplicationService(repo, eventPublisher, permissionResolver, workflowPort, clock)

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, answers = false)
    }

    describe("findByKey") {

        context("권한이 있고 이슈가 존재할 때") {
            val fixedNow = Instant.parse("2026-05-24T00:00:00Z")
            val issue =
                Issue(
                    id = IssueId(UUID.randomUUID()),
                    key = issueKey,
                    projectId = UUID.randomUUID(),
                    summary = "Test issue",
                    reporterId = actor,
                    currentStateKey = "OPEN",
                    version = 1L,
                    deletedAt = null,
                    createdAt = fixedNow,
                    updatedAt = fixedNow,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.VIEW, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns issue
            }

            it("IssueResponse 를 반환한다") {
                val result = sut.findByKey(actor, issueKey)

                result shouldBe IssueResponse.from(issue, issueKey.projectPrefix)
            }

            it("반환된 응답의 key 가 이슈 키와 일치한다") {
                val result = sut.findByKey(actor, issueKey)

                result.key shouldBe issueKey.value
            }
        }

        context("권한이 없을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.VIEW, IssueScope.Issue(issueKey.value))
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.findByKey(actor, issueKey)
                }
            }

            it("repo 가 호출되지 않는다") {
                runCatching { sut.findByKey(actor, issueKey) }

                verify(exactly = 0) { repo.findByKey(issueKey) }
            }
        }

        context("권한은 있지만 이슈가 존재하지 않을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.VIEW, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns null
            }

            it("IssueNotFoundException 을 던진다") {
                shouldThrow<IssueNotFoundException> {
                    sut.findByKey(actor, issueKey)
                }
            }
        }
    }
})
