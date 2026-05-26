// IssueApplicationService.createIssue 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueCreated
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssuePermissionResolver
import com.bts.issue.port.outbound.IssueScope
import com.bts.issue.repository.IssueRepository
import com.bts.workflow.port.inbound.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IssueApplicationServiceCreateTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val clock = Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneOffset.UTC)

    val sut = IssueApplicationService(repo, eventPublisher, permissionResolver, workflowPort, clock)

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "BTS"
    val request =
        CreateIssueRequest(
            projectKey = projectKey,
            summary = "Test summary",
            reporterId = actor,
        )

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, answers = false)
    }

    describe("createIssue") {

        context("권한이 있을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.CREATE, IssueScope.Project(projectKey))
                } returns true
                every { repo.incrementKeySequence(projectKey) } returns 1L
                every { repo.insert(any()) } answers { firstArg() }
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("권한 체크 → incrementKeySequence → insert → publish 순서로 호출한다") {
                sut.createIssue(actor, request)

                verifyOrder {
                    permissionResolver.hasPermission(actor, IssuePermission.CREATE, IssueScope.Project(projectKey))
                    repo.incrementKeySequence(projectKey)
                    repo.insert(any())
                    eventPublisher.publish(any())
                }
            }

            it("반환된 이슈의 키가 projectKey-seq 형식이다") {
                val result = sut.createIssue(actor, request)

                result.key shouldBe IssueKey.of(projectKey, 1L)
            }

            it("IssueCreated 이벤트가 발행된다") {
                sut.createIssue(actor, request)

                verify {
                    eventPublisher.publish(match { it is IssueCreated && it.projectKey == projectKey })
                }
            }
        }

        context("권한이 없을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.CREATE, IssueScope.Project(projectKey))
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.createIssue(actor, request)
                }
            }

            it("repo 및 eventPublisher 가 호출되지 않는다") {
                runCatching { sut.createIssue(actor, request) }

                verify(exactly = 0) { repo.incrementKeySequence(any()) }
                verify(exactly = 0) { repo.insert(any()) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        it("IssueApplicationService 클래스는 @Transactional 을 선언한다") {
            val annotation = IssueApplicationService::class.java.getAnnotation(Transactional::class.java)
            annotation shouldNotBe null
        }
    }
})
