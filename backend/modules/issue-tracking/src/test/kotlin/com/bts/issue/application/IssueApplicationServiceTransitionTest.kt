// IssueApplicationService.transitionIssue 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueTransitioned
import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssuePermissionResolver
import com.bts.issue.port.outbound.IssueScope
import com.bts.issue.repository.IssueRepository
import com.bts.shared.workflow.TransitionPlan
import com.bts.shared.workflow.TransitionResult
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IssueApplicationServiceTransitionTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val clock = Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneOffset.UTC)

    val sut = IssueApplicationService(repo, eventPublisher, permissionResolver, workflowPort, clock)

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")
    val existingVersion = 1L

    fun makeIssue(
        state: String = "OPEN",
        version: Long = existingVersion,
    ) = Issue(
        id = IssueId(UUID.randomUUID()),
        key = issueKey,
        projectId = UUID.randomUUID(),
        summary = "Some summary",
        reporterId = actor,
        currentStateKey = state,
        version = version,
        deletedAt = null,
        createdAt = Instant.parse("2026-05-24T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-24T00:00:00Z"),
    )

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, workflowPort, answers = false)
    }

    describe("transitionIssue") {

        it("IssueApplicationService 는 @Transactional 어노테이션을 클래스 또는 메서드에 선언한다") {
            val classAnnotation = IssueApplicationService::class.java.getAnnotation(Transactional::class.java)
            val methodAnnotation =
                runCatching {
                    IssueApplicationService::class.java
                        .getMethod("transitionIssue", ActorId::class.java, IssueKey::class.java, TransitionIssueRequest::class.java)
                        .getAnnotation(Transactional::class.java)
                }.getOrNull()
            (classAnnotation != null || methodAnnotation != null) shouldBe true
        }

        context("S1 — TransitionResult.Success 반환 시 정상 전이") {
            val request =
                TransitionIssueRequest(
                    workflowKey = "DEFAULT",
                    toStateKey = "IN_PROGRESS",
                    transitionName = "start",
                    expectedVersion = existingVersion,
                )
            val plan = TransitionPlan(toStateKey = "IN_PROGRESS", fieldChanges = emptyList(), emitEvents = emptyList())
            val updatedIssue = makeIssue(state = "IN_PROGRESS", version = existingVersion + 1)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every { workflowPort.plan(any()) } returns TransitionResult.Success(plan)
                every { repo.applyTransition(issueKey, "IN_PROGRESS", existingVersion) } returns 1
                every { repo.findByKey(issueKey) } returns updatedIssue
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("IssueResponse 를 반환하고 currentStateKey 가 toState 와 같다") {
                val result = sut.transitionIssue(actor, issueKey, request)
                result.key shouldBe issueKey.value
                result.currentStateKey shouldBe "IN_PROGRESS"
            }

            it("IssueTransitioned 이벤트가 fromState, toState 포함해 발행된다") {
                sut.transitionIssue(actor, issueKey, request)
                verify {
                    eventPublisher.publish(
                        match { it is IssueTransitioned && it.fromState == "OPEN" && it.toState == "IN_PROGRESS" },
                    )
                }
            }

            it("workflowPort.plan 에 올바른 TransitionRequest 를 전달한다") {
                sut.transitionIssue(actor, issueKey, request)
                verify {
                    workflowPort.plan(
                        match { req ->
                            req.issueKey == issueKey.value &&
                                req.fromStateKey == "OPEN" &&
                                req.toStateKey == "IN_PROGRESS" &&
                                req.workflowKey == "DEFAULT"
                        },
                    )
                }
            }
        }

        context("S2 — TransitionResult.ValidatorFailure 반환 시 IssueTransitionNotAllowedException") {
            val request =
                TransitionIssueRequest(
                    workflowKey = "DEFAULT",
                    toStateKey = "IN_PROGRESS",
                    transitionName = "start",
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every { workflowPort.plan(any()) } returns TransitionResult.ValidatorFailure("조건 X 위반")
            }

            it("IssueTransitionNotAllowedException 을 던진다") {
                val ex =
                    shouldThrow<IssueTransitionNotAllowedException> {
                        sut.transitionIssue(actor, issueKey, request)
                    }
                ex.issueKey shouldBe issueKey
                ex.fromStatus shouldBe "OPEN"
                ex.toStatus shouldBe "IN_PROGRESS"
            }

            it("예외 메시지에 ValidatorFailure 사유가 포함된다") {
                val ex =
                    shouldThrow<IssueTransitionNotAllowedException> {
                        sut.transitionIssue(actor, issueKey, request)
                    }
                ex.message shouldContain "조건 X 위반"
            }

            it("이벤트가 발행되지 않는다") {
                runCatching { sut.transitionIssue(actor, issueKey, request) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("S3 — TransitionResult.WorkflowNotFound 반환 시 IssueTransitionNotAllowedException") {
            val request =
                TransitionIssueRequest(
                    workflowKey = "ATLAS",
                    toStateKey = "IN_PROGRESS",
                    transitionName = "start",
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every { workflowPort.plan(any()) } returns TransitionResult.WorkflowNotFound("ATLAS")
            }

            it("IssueTransitionNotAllowedException 을 던진다") {
                val ex =
                    shouldThrow<IssueTransitionNotAllowedException> {
                        sut.transitionIssue(actor, issueKey, request)
                    }
                ex.issueKey shouldBe issueKey
                ex.fromStatus shouldBe "OPEN"
                ex.toStatus shouldBe "IN_PROGRESS"
            }

            it("이벤트가 발행되지 않는다") {
                runCatching { sut.transitionIssue(actor, issueKey, request) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("S4 — TransitionResult.ExpressionTimeout 반환 시 IssueTransitionNotAllowedException") {
            val request =
                TransitionIssueRequest(
                    workflowKey = "DEFAULT",
                    toStateKey = "IN_PROGRESS",
                    transitionName = "start",
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every { workflowPort.plan(any()) } returns TransitionResult.ExpressionTimeout("SpEL timeout")
            }

            it("IssueTransitionNotAllowedException 을 던진다") {
                val ex =
                    shouldThrow<IssueTransitionNotAllowedException> {
                        sut.transitionIssue(actor, issueKey, request)
                    }
                ex.issueKey shouldBe issueKey
                ex.fromStatus shouldBe "OPEN"
                ex.toStatus shouldBe "IN_PROGRESS"
            }

            it("이벤트가 발행되지 않는다") {
                runCatching { sut.transitionIssue(actor, issueKey, request) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("version conflict — applyTransition 0 row 반환") {
            val request =
                TransitionIssueRequest(
                    workflowKey = "DEFAULT",
                    toStateKey = "IN_PROGRESS",
                    transitionName = "start",
                    expectedVersion = existingVersion,
                )
            val plan = TransitionPlan(toStateKey = "IN_PROGRESS", fieldChanges = emptyList(), emitEvents = emptyList())

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every { workflowPort.plan(any()) } returns TransitionResult.Success(plan)
                every { repo.applyTransition(issueKey, "IN_PROGRESS", existingVersion) } returns 0
            }

            it("IssueVersionConflictException 을 던진다") {
                shouldThrow<IssueVersionConflictException> {
                    sut.transitionIssue(actor, issueKey, request)
                }
            }
        }

        context("권한 없을 때") {
            val request =
                TransitionIssueRequest(
                    workflowKey = "DEFAULT",
                    toStateKey = "IN_PROGRESS",
                    transitionName = "start",
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue(issueKey.value))
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.transitionIssue(actor, issueKey, request)
                }
            }

            it("workflowPort 가 호출되지 않는다") {
                runCatching { sut.transitionIssue(actor, issueKey, request) }
                verify(exactly = 0) { workflowPort.plan(any()) }
            }
        }
    }
})
