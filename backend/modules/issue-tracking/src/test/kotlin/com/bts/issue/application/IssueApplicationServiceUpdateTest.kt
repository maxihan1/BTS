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

class IssueApplicationServiceUpdateTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val clock = Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneOffset.UTC)

    val sut = IssueApplicationService(repo, eventPublisher, permissionResolver, workflowPort, workflowKeyResolver, clock)

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")
    val existingVersion = 1L

    fun makeIssue(
        summary: String = "원래",
        version: Long = existingVersion,
    ) = Issue(
        id = IssueId(UUID.randomUUID()),
        key = issueKey,
        projectId = UUID.randomUUID(),
        summary = summary,
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

        // T7-1: summary=null 이면 updateSummary·eventPublisher 모두 호출하지 않고 기존 이슈를 그대로 반환한다
        context("T7-1 — summary null (RFC 7396 JSON Merge Patch: 필드 생략)") {
            val request = UpdateIssueRequest(summary = null, expectedVersion = existingVersion)
            val existingIssue = makeIssue(summary = "원래")

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
            }

            it("updateSummary 가 호출되지 않는다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { repo.updateSummary(issueKey, any<String>(), any<Long>()) }
            }

            it("eventPublisher.publish 가 호출되지 않는다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }

            it("기존 이슈의 summary 와 version 을 그대로 반환한다") {
                val result = sut.updateIssue(actor, issueKey, request)
                result.summary shouldBe "원래"
                result.version shouldBe existingVersion
            }
        }

        // T7-2: summary 가 새 값이면 updateSummary 1회 + IssueUpdated(fields={"summary"}) 1회 발행
        context("T7-2 — summary 변경 (기존값과 다른 새 값)") {
            val request = UpdateIssueRequest(summary = "새 제목", expectedVersion = existingVersion)
            val existingIssue = makeIssue(summary = "원래")
            val updatedIssue = makeIssue(summary = "새 제목", version = existingVersion + 1)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returnsMany listOf(existingIssue, updatedIssue)
                every { repo.updateSummary(issueKey, "새 제목", existingVersion) } returns 1
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateSummary 가 1회 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) { repo.updateSummary(issueKey, "새 제목", existingVersion) }
            }

            it("IssueUpdated(fields={summary}) 이벤트가 1회 발행된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { it is IssueUpdated && it.fields == setOf("summary") },
                    )
                }
            }

            it("응답 summary='새 제목', version=2 를 반환한다") {
                val result = sut.updateIssue(actor, issueKey, request)
                result.summary shouldBe "새 제목"
                result.version shouldBe existingVersion + 1
            }
        }

        // T7-3: summary 가 기존값과 동일하면 updateSummary·eventPublisher 모두 호출하지 않는다
        context("T7-3 — summary 동일값 (변경 없음)") {
            val request = UpdateIssueRequest(summary = "원래", expectedVersion = existingVersion)
            val existingIssue = makeIssue(summary = "원래")

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
            }

            it("updateSummary 가 호출되지 않는다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { repo.updateSummary(issueKey, any<String>(), any<Long>()) }
            }

            it("eventPublisher.publish 가 호출되지 않는다 (changedFields empty)") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("version 불일치 — 낙관락 충돌") {
            val request = UpdateIssueRequest(summary = "새 제목", expectedVersion = existingVersion)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns makeIssue(summary = "원래")
                every { repo.updateSummary(issueKey, "새 제목", existingVersion) } returns 0
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
            val request = UpdateIssueRequest(summary = "새 제목", expectedVersion = existingVersion)

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
            val request = UpdateIssueRequest(summary = "새 제목", expectedVersion = existingVersion)

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
