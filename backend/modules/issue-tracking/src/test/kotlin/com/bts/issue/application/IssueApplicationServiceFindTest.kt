// IssueApplicationService.findByKey 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.event.IssueEventPublisher
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
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

class IssueApplicationServiceFindTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
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
            eventPublisher = eventPublisher,
            permissionResolver = permissionResolver,
            workflowPort = workflowPort,
            workflowKeyResolver = workflowKeyResolver,
            userLookupPort = userLookupPort,
            clock = clock,
        )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, answers = false)
    }

    describe("findByKey") {

        context("권한이 있고 이슈가 존재할 때") {
            val fixedNow = Instant.parse("2026-05-24T00:00:00Z")
            val issueResponse =
                com.bts.issue.adapter.inbound.rest.IssueResponse(
                    key = issueKey.value,
                    id = UUID.randomUUID(),
                    projectKey = issueKey.projectPrefix,
                    summary = "Test issue",
                    reporterId = actor.value,
                    currentStateKey = "open",
                    version = 1L,
                    createdAt = fixedNow,
                    updatedAt = fixedNow,
                    typeId = 3L,
                    typeKey = "task",
                    typeName = "Task",
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKeyWithType(issueKey) } returns issueResponse
            }

            it("IssueResponse 를 반환한다") {
                val result = sut.findByKey(actor, issueKey)

                result shouldBe issueResponse
            }

            it("반환된 응답의 key 가 이슈 키와 일치한다") {
                val result = sut.findByKey(actor, issueKey)

                result.key shouldBe issueKey.value
            }
        }

        context("권한이 없을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Issue(issueKey.value))
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.findByKey(actor, issueKey)
                }
            }

            it("repo 가 호출되지 않는다") {
                runCatching { sut.findByKey(actor, issueKey) }

                verify(exactly = 0) { repo.findByKeyWithType(issueKey) }
            }
        }

        context("권한은 있지만 이슈가 존재하지 않을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKeyWithType(issueKey) } returns null
            }

            it("IssueNotFoundException 을 던진다") {
                shouldThrow<IssueNotFoundException> {
                    sut.findByKey(actor, issueKey)
                }
            }
        }
    }
})
