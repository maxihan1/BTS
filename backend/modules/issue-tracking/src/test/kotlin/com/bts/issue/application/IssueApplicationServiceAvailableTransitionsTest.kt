// IssueApplicationService.availableTransitions 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.AvailableTransitionView
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
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

class IssueApplicationServiceAvailableTransitionsTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val clock = Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC)

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
            historyRecorder = mockk(relaxed = true),
        )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")

    fun makeIssue(state: String = "TODO") =
        Issue(
            id = IssueId(UUID.randomUUID()),
            key = issueKey,
            projectId = UUID.randomUUID(),
            summary = "테스트 이슈",
            reporterId = actor,
            currentStateKey = state,
            version = 1L,
            deletedAt = null,
            createdAt = Instant.parse("2026-05-29T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-29T00:00:00Z"),
            typeId = IssueTypeId(3L),
        )

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, workflowPort, workflowKeyResolver, answers = false)
    }

    describe("availableTransitions") {

        it("IssueApplicationService.availableTransitions 는 @Transactional(readOnly=true) 을 선언한다") {
            val classAnnotation = IssueApplicationService::class.java.getAnnotation(Transactional::class.java)
            val methodAnnotation =
                runCatching {
                    IssueApplicationService::class.java
                        .getMethod("availableTransitions", ActorId::class.java, IssueKey::class.java)
                        .getAnnotation(Transactional::class.java)
                }.getOrNull()
            // 클래스 또는 메서드 중 하나에 선언되어 있어야 한다.
            // 메서드 레벨 @Transactional(readOnly=true) 가 있으면 그것을,
            // 없으면 클래스 레벨 확인.
            (classAnnotation != null || methodAnnotation != null) shouldBe true
        }

        it("메서드 레벨에 @Transactional(readOnly=true) 가 선언된다") {
            // IssueKey 는 value class 라 JVM 메서드명이 mangling 된다(availableTransitions-<hash>).
            // getMethod(name, paramTypes) 로는 못 찾으므로 @Transactional 이 붙은 메서드를
            // 이름 prefix 로 조회한다.
            val method =
                IssueApplicationService::class.java.methods
                    .first {
                        it.name.startsWith("availableTransitions") &&
                            it.isAnnotationPresent(Transactional::class.java)
                    }
            method.getAnnotation(Transactional::class.java).readOnly shouldBe true
        }

        context("S1 — 정상: port.availableTransitions 가 Success(2건) 반환 시 2건 매핑") {
            val transitions =
                listOf(
                    AvailableTransitionView(fromStateKey = "TODO", toStateKey = "IN_PROGRESS", name = "시작"),
                    AvailableTransitionView(fromStateKey = "TODO", toStateKey = "DONE", name = "완료"),
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKey(issueKey) } returns makeIssue(state = "TODO")
                // availableTransitions 는 읽기 경로 — resolveExisting 을 호출한다 (resolveStart 아님)
                every {
                    workflowKeyResolver.resolveExisting(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "DEFAULT", startStateKey = "TODO")
                every { workflowPort.availableTransitions(any()) } returns
                    AvailableTransitionsResult.Success(transitions)
            }

            it("2건의 AvailableTransitionView 를 반환한다") {
                val result = sut.availableTransitions(actor, issueKey)
                result shouldHaveSize 2
            }

            it("반환된 전이의 toStateKey 가 올바르게 매핑된다") {
                val result = sut.availableTransitions(actor, issueKey)
                result[0].toStateKey shouldBe "IN_PROGRESS"
                result[1].toStateKey shouldBe "DONE"
            }

            it("workflowPort.availableTransitions 에 fromStateKey=issue.currentStateKey 와 issueKey 를 전달한다") {
                sut.availableTransitions(actor, issueKey)
                verify {
                    workflowPort.availableTransitions(
                        match { req ->
                            req.fromStateKey == "TODO" &&
                                req.workflowKey == "DEFAULT" &&
                                req.actorId == actor.value.toString() &&
                                req.issueKey == issueKey.value
                        },
                    )
                }
            }
        }

        context("S2 — 이슈 없음 → IssueNotFoundException") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKey(issueKey) } returns null
            }

            it("IssueNotFoundException 을 던진다") {
                shouldThrow<IssueNotFoundException> {
                    sut.availableTransitions(actor, issueKey)
                }
            }

            it("workflowPort 가 호출되지 않는다") {
                runCatching { sut.availableTransitions(actor, issueKey) }
                verify(exactly = 0) { workflowPort.availableTransitions(any()) }
            }
        }

        // S3: resolveExisting 이 WorkflowSchemeNoDefaultException 던짐 → IssueWorkflowNotConfiguredException
        context("S3 — WorkflowSchemeNoDefaultException → IssueWorkflowNotConfiguredException") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKey(issueKey) } returns makeIssue()
                // availableTransitions 는 resolveExisting 을 호출하므로 resolveExisting 에 stub 한다
                every {
                    workflowKeyResolver.resolveExisting(ProjectKey.of("BTS"), null)
                } throws WorkflowSchemeNoDefaultException("software-default")
            }

            it("IssueWorkflowNotConfiguredException 을 던진다") {
                shouldThrow<IssueWorkflowNotConfiguredException> {
                    sut.availableTransitions(actor, issueKey)
                }
            }

            it("예외 메시지에 projectKey 가 포함된다") {
                val ex =
                    shouldThrow<IssueWorkflowNotConfiguredException> {
                        sut.availableTransitions(actor, issueKey)
                    }
                ex.message shouldContain "BTS"
            }
        }

        context("S4 — port 가 WorkflowNotFound 반환 → IssueWorkflowNotConfiguredException (리뷰 주의 2)") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKey(issueKey) } returns makeIssue()
                // availableTransitions 는 resolveExisting 을 호출한다
                every {
                    workflowKeyResolver.resolveExisting(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "MISSING-WF", startStateKey = "TODO")
                every { workflowPort.availableTransitions(any()) } returns
                    AvailableTransitionsResult.WorkflowNotFound("MISSING-WF")
            }

            it("IssueWorkflowNotConfiguredException 을 던진다") {
                shouldThrow<IssueWorkflowNotConfiguredException> {
                    sut.availableTransitions(actor, issueKey)
                }
            }

            it("예외 메시지에 projectKey 가 포함된다") {
                val ex =
                    shouldThrow<IssueWorkflowNotConfiguredException> {
                        sut.availableTransitions(actor, issueKey)
                    }
                ex.message shouldContain "BTS"
            }
        }
    }
})
