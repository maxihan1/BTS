// IssueApplicationService.updateIssue 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueUpdated
import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssuePermissionResolver
import com.bts.issue.port.outbound.IssueScope
import com.bts.issue.repository.IssueRepository
import com.bts.workflow.port.inbound.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IssueApplicationServiceUpdateTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val clock = Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneOffset.UTC)

    val sut = IssueApplicationService(repo, eventPublisher, permissionResolver, workflowPort, clock)

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")
    val existingVersion = 1L

    fun makeIssue(version: Long = existingVersion) = Issue(
        id = IssueId(UUID.randomUUID()),
        key = issueKey,
        projectId = UUID.randomUUID(),
        summary = "Old summary",
        reporterId = actor,
        currentStateKey = "OPEN",
        version = version,
        deletedAt = null,
        createdAt = Instant.parse("2026-05-24T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-24T00:00:00Z"),
    )

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, answers = false)
    }

    describe("updateIssue") {

        context("정상 — 권한 있고 version 일치") {
            val request = UpdateIssueRequest(summary = "New summary", expectedVersion = existingVersion)
            val updatedIssue = makeIssue(version = existingVersion + 1).copy(summary = "New summary")

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returnsMany listOf(makeIssue(), updatedIssue)
                every { repo.updateSummary(issueKey, "New summary", existingVersion) } returns 1
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("IssueResponse 를 반환한다") {
                val result = sut.updateIssue(actor, issueKey, request)
                result.key shouldBe issueKey.value
                result.summary shouldBe "New summary"
            }

            it("IssueUpdated 이벤트가 summary 필드를 포함해 발행된다") {
                sut.updateIssue(actor, issueKey, request)
                verify {
                    eventPublisher.publish(
                        match { it is IssueUpdated && it.fields.contains("summary") },
                    )
                }
            }

            it("변경 없을 때 fields 집합이 비어 있다") {
                val sameRequest = UpdateIssueRequest(summary = "Old summary", expectedVersion = existingVersion)
                val sameIssue = makeIssue()
                every { repo.findByKey(issueKey) } returnsMany listOf(sameIssue, sameIssue)
                every { repo.updateSummary(issueKey, "Old summary", existingVersion) } returns 1

                sut.updateIssue(actor, issueKey, sameRequest)

                verify {
                    eventPublisher.publish(
                        match { it is IssueUpdated && it.fields.isEmpty() },
                    )
                }
            }
        }

        context("version 불일치 — 낙관락 충돌") {
            val request = UpdateIssueRequest(summary = "New summary", expectedVersion = existingVersion)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns makeIssue()
                every { repo.updateSummary(issueKey, "New summary", existingVersion) } returns 0
            }

            it("IssueVersionConflictException 을 던진다") {
                shouldThrow<IssueVersionConflictException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }

            it("이벤트가 발행되지 않는다") {
                runCatching { sut.updateIssue(actor, issueKey, request) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("이슈 미존재") {
            val request = UpdateIssueRequest(summary = "New summary", expectedVersion = existingVersion)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns null
            }

            it("IssueNotFoundException 을 던진다") {
                shouldThrow<IssueNotFoundException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }
        }

        context("권한 없을 때") {
            val request = UpdateIssueRequest(summary = "New summary", expectedVersion = existingVersion)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }

            it("repo 및 eventPublisher 가 호출되지 않는다") {
                runCatching { sut.updateIssue(actor, issueKey, request) }
                verify(exactly = 0) { repo.findByKey(issueKey) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }
    }
})
