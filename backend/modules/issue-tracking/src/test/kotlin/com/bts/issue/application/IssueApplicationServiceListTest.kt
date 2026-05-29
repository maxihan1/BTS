// IssueApplicationService.listIssues 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssuePermissionResolver
import com.bts.issue.port.outbound.IssueScope
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IssueApplicationServiceListTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val clock = Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneOffset.UTC)

    val sut =
        IssueApplicationService(
            repo,
            issueTypeRepository,
            eventPublisher,
            permissionResolver,
            workflowPort,
            workflowKeyResolver,
            clock,
        )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "BTS"

    fun makeIssue(seq: Int) =
        Issue(
            id = IssueId(UUID.randomUUID()),
            key = IssueKey("BTS-$seq"),
            projectId = UUID.randomUUID(),
            summary = "Issue $seq",
            reporterId = actor,
            currentStateKey = "open",
            version = 1L,
            deletedAt = null,
            createdAt = Instant.parse("2026-05-24T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-24T00:00:00Z"),
            typeId = IssueTypeId(3L),
        )

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, answers = false)
    }

    describe("listIssues") {

        context("정상 — 권한 있고 pageSize ≤ 100") {
            val pageable = PageRequest.of(0, 20)
            val issues = listOf(makeIssue(1), makeIssue(2))
            val page = PageImpl(issues, pageable, 2L)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.VIEW, IssueScope.Project(projectKey))
                } returns true
                every { repo.list(projectKey, pageable) } returns page
            }

            it("Page<IssueResponse> 를 반환하며 content 크기가 일치한다") {
                val result = sut.listIssues(actor, projectKey, pageable)
                result.content.size shouldBe 2
                result.totalElements shouldBe 2L
            }

            it("IssueResponse 의 projectKey 가 요청한 projectKey 와 일치한다") {
                val result = sut.listIssues(actor, projectKey, pageable)
                result.content.forEach { it.key.startsWith("BTS-") shouldBe true }
            }
        }

        context("pageSize > 100 이면 거부") {
            val pageable = PageRequest.of(0, 101)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.VIEW, IssueScope.Project(projectKey))
                } returns true
            }

            it("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    sut.listIssues(actor, projectKey, pageable)
                }
            }
        }

        context("pageSize = 100 은 허용") {
            val pageable = PageRequest.of(0, 100)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.VIEW, IssueScope.Project(projectKey))
                } returns true
                every { repo.list(projectKey, pageable) } returns PageImpl(emptyList(), pageable, 0L)
            }

            it("예외 없이 빈 페이지를 반환한다") {
                val result = sut.listIssues(actor, projectKey, pageable)
                result.content.size shouldBe 0
            }
        }

        context("권한 없을 때") {
            val pageable = PageRequest.of(0, 20)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.VIEW, IssueScope.Project(projectKey))
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.listIssues(actor, projectKey, pageable)
                }
            }
        }
    }
})
