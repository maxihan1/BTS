// IssueApplicationService.transitionIssue 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueTransitioned
import com.bts.issue.event.TransitionEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.DomainEvent
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.TransitionPlan
import com.bts.shared.workflow.TransitionResult
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowTransitionPort
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val transitionEventPublisher = mockk<TransitionEventPublisher>(relaxed = true)
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
            versionRepository = mockk(relaxed = true),
            clock = clock,
            historyRecorder = mockk(relaxed = true),
            transitionEventPublisher = transitionEventPublisher,
        )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")
    val existingVersion = 1L

    fun makeIssue(
        state: String = "open",
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
        typeId = IssueTypeId(3L),
    )

    fun makeResponse(
        state: String = "open",
        version: Long = existingVersion,
    ) = IssueResponse(
        key = issueKey.value,
        id = UUID.randomUUID(),
        projectKey = issueKey.projectPrefix,
        summary = "Some summary",
        currentStateKey = state,
        reporterId = actor.value,
        version = version,
        createdAt = Instant.parse("2026-05-24T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-24T00:00:00Z"),
        typeId = 3L,
        typeKey = "task",
        typeName = "Task",
    )

    beforeEach {
        clearMocks(
            repo,
            eventPublisher,
            permissionResolver,
            workflowPort,
            workflowKeyResolver,
            transitionEventPublisher,
            answers = false,
        )
        // withSingleDetail() 내부에서 findActiveComponentIdsByIssue 호출 — 단건 응답 테스트 기본 stub
        every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()
        every { repo.findAffectsVersionIdsByIssue(any()) } returns emptyList()
        every { repo.findFixVersionIdsByIssue(any()) } returns emptyList()
    }

    describe("transitionIssue") {

        it("IssueApplicationService 는 @Transactional 어노테이션을 클래스 또는 메서드에 선언한다") {
            val classAnnotation = IssueApplicationService::class.java.getAnnotation(Transactional::class.java)
            val methodAnnotation =
                runCatching {
                    IssueApplicationService::class.java
                        .getMethod(
                            "transitionIssue",
                            ActorId::class.java,
                            IssueKey::class.java,
                            TransitionIssueRequest::class.java,
                        )
                        .getAnnotation(Transactional::class.java)
                }.getOrNull()
            (classAnnotation != null || methodAnnotation != null) shouldBe true
        }

        context("S1 — TransitionResult.Success 반환 시 정상 전환") {
            val request =
                TransitionIssueRequest(
                    toStateKey = "IN_PROGRESS",
                    expectedVersion = existingVersion,
                )
            val plan = TransitionPlan(toStateKey = "IN_PROGRESS", fieldChanges = emptyList(), emitEvents = emptyList())
            val updatedResponse = makeResponse(state = "IN_PROGRESS", version = existingVersion + 1)

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "DEFAULT", startStateKey = "open")
                every { workflowPort.plan(any()) } returns TransitionResult.Success(plan)
                every { repo.applyTransition(issueKey, "IN_PROGRESS", existingVersion, null) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
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
                        match { it is IssueTransitioned && it.fromState == "open" && it.toState == "IN_PROGRESS" },
                    )
                }
            }

            it("workflowPort.plan 에 올바른 TransitionRequest 를 전달한다") {
                sut.transitionIssue(actor, issueKey, request)
                verify {
                    workflowPort.plan(
                        match { req ->
                            req.issueKey == issueKey.value &&
                                req.fromStateKey == "open" &&
                                req.toStateKey == "IN_PROGRESS" &&
                                req.workflowKey == "DEFAULT"
                        },
                    )
                }
            }
        }

        context("S5 — WorkflowKeyResolver 호출 계약") {
            val request =
                TransitionIssueRequest(
                    toStateKey = "IN_PROGRESS",
                    expectedVersion = existingVersion,
                )
            val plan = TransitionPlan(toStateKey = "IN_PROGRESS", fieldChanges = emptyList(), emitEvents = emptyList())
            val updatedResponse = makeResponse(state = "IN_PROGRESS", version = existingVersion + 1)

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "RESOLVED-WF", startStateKey = "open")
                every { workflowPort.plan(any()) } returns TransitionResult.Success(plan)
                every { repo.applyTransition(issueKey, "IN_PROGRESS", existingVersion, null) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("workflowKeyResolver.resolveStart 를 issueKey.projectPrefix 로 호출한다") {
                sut.transitionIssue(actor, issueKey, request)
                verify {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                }
            }

            it("resolver 가 반환한 workflowKey 를 workflowPort.plan 에 전달한다 (request.workflowKey 하드코딩 아님)") {
                sut.transitionIssue(actor, issueKey, request)
                verify {
                    workflowPort.plan(match { req -> req.workflowKey == "RESOLVED-WF" })
                }
            }
        }

        context("S6 — WorkflowSchemeNoDefaultException → IssueWorkflowNotConfiguredException 변환") {
            val request =
                TransitionIssueRequest(
                    toStateKey = "IN_PROGRESS",
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                // WorkflowSchemeNoDefaultException 을 직접 import 하면 BC 격리 위반이므로
                // GREEN 구현체가 javaClass.simpleName 으로 감지하는 것을 시뮬레이션하기 위해
                // simpleName 이 "WorkflowSchemeNoDefaultException" 인 named class 를 테스트 companion 에 정의한다.
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                } throws WorkflowSchemeNoDefaultException("software-default")
            }

            it("IssueWorkflowNotConfiguredException 을 던진다") {
                shouldThrow<IssueWorkflowNotConfiguredException> {
                    sut.transitionIssue(actor, issueKey, request)
                }
            }

            it("예외에 projectKey 가 포함된다") {
                val ex =
                    shouldThrow<IssueWorkflowNotConfiguredException> {
                        sut.transitionIssue(actor, issueKey, request)
                    }
                ex.message shouldContain "BTS"
            }

            it("이벤트가 발행되지 않는다") {
                runCatching { sut.transitionIssue(actor, issueKey, request) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("S2 — TransitionResult.ValidatorFailure 반환 시 IssueTransitionNotAllowedException") {
            val request =
                TransitionIssueRequest(
                    toStateKey = "IN_PROGRESS",
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "DEFAULT", startStateKey = "open")
                every { workflowPort.plan(any()) } returns TransitionResult.ValidatorFailure("조건 X 위반")
            }

            it("IssueTransitionNotAllowedException 을 던진다") {
                val ex =
                    shouldThrow<IssueTransitionNotAllowedException> {
                        sut.transitionIssue(actor, issueKey, request)
                    }
                ex.issueKey shouldBe issueKey
                ex.fromStatus shouldBe "open"
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
                    toStateKey = "IN_PROGRESS",
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "ATLAS", startStateKey = "open")
                every { workflowPort.plan(any()) } returns TransitionResult.WorkflowNotFound("ATLAS")
            }

            it("IssueTransitionNotAllowedException 을 던진다") {
                val ex =
                    shouldThrow<IssueTransitionNotAllowedException> {
                        sut.transitionIssue(actor, issueKey, request)
                    }
                ex.issueKey shouldBe issueKey
                ex.fromStatus shouldBe "open"
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
                    toStateKey = "IN_PROGRESS",
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "DEFAULT", startStateKey = "open")
                every { workflowPort.plan(any()) } returns TransitionResult.ExpressionTimeout("SpEL timeout")
            }

            it("IssueTransitionNotAllowedException 을 던진다") {
                val ex =
                    shouldThrow<IssueTransitionNotAllowedException> {
                        sut.transitionIssue(actor, issueKey, request)
                    }
                ex.issueKey shouldBe issueKey
                ex.fromStatus shouldBe "open"
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
                    toStateKey = "IN_PROGRESS",
                    expectedVersion = existingVersion,
                )
            val plan = TransitionPlan(toStateKey = "IN_PROGRESS", fieldChanges = emptyList(), emitEvents = emptyList())

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "DEFAULT", startStateKey = "open")
                every { workflowPort.plan(any()) } returns TransitionResult.Success(plan)
                every { repo.applyTransition(issueKey, "IN_PROGRESS", existingVersion, null) } returns 0
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
                    toStateKey = "IN_PROGRESS",
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
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

            // sec codereview-fix P1 — 존재/버전 probe 차단.
            // 권한 체크가 findByKeyForUpdate(버전 비교) 보다 먼저 수행되므로,
            // 권한 없는 actor 의 전환은 version 상태와 무관하게 403(IssueAccessDeniedException)으로 거부되고
            // 이슈 조회(findByKeyForUpdate) 자체가 호출되지 않는다.
            // → BROWSE-yes/TRANSITION-no actor 가 409(버전충돌) vs 403 차이로 이슈 존재/버전을 probe 할 수 없다.
            it("findByKeyForUpdate(버전 probe)가 권한 거부보다 먼저 호출되지 않는다") {
                // findByKeyForUpdate 가 만약 호출되면 정상 이슈를 반환하도록 stub 한다.
                // 그래도 권한 체크가 선행하므로 이 stub 은 호출되지 않아야 한다.
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()

                runCatching { sut.transitionIssue(actor, issueKey, request) }

                verify(exactly = 0) { repo.findByKeyForUpdate(issueKey) }
            }

            it("권한 없는 actor 의 전환은 version 불일치여도 409 가 아니라 403 으로 거부된다") {
                // expectedVersion 을 실제와 다르게 줘도(99) version 충돌(409) 이전에 권한(403)이 거부돼야 한다.
                val staleRequest =
                    TransitionIssueRequest(toStateKey = "IN_PROGRESS", expectedVersion = 99L)

                shouldThrow<IssueAccessDeniedException> {
                    sut.transitionIssue(actor, issueKey, staleRequest)
                }
            }
        }

        context("T1 — emitEvents 포함 plan 전환 시 TransitionEventPublisher.publish 호출") {
            val webhookEvent =
                DomainEvent(
                    type = "WebhookRequested",
                    payload = mapOf("issueKey" to "BTS-1", "url" to "https://example.test/hook", "method" to "POST"),
                )
            val planWithEvents =
                TransitionPlan(
                    toStateKey = "IN_PROGRESS",
                    fieldChanges = emptyList(),
                    emitEvents = listOf(webhookEvent),
                )
            val request =
                TransitionIssueRequest(
                    toStateKey = "IN_PROGRESS",
                    expectedVersion = existingVersion,
                )
            val updatedResponse = makeResponse(state = "IN_PROGRESS", version = existingVersion + 1)

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "DEFAULT", startStateKey = "open")
                every { workflowPort.plan(any()) } returns TransitionResult.Success(planWithEvents)
                every { repo.applyTransition(issueKey, "IN_PROGRESS", existingVersion, null) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("plan.emitEvents 이벤트를 TransitionEventPublisher.publish 로 발행한다") {
                sut.transitionIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    transitionEventPublisher.publish(
                        match { it.type == "WebhookRequested" },
                    )
                }
            }
        }

        context("T2 — emitEvents 빈 plan 전환 시 TransitionEventPublisher.publish 미호출") {
            val emptyPlan =
                TransitionPlan(
                    toStateKey = "IN_PROGRESS",
                    fieldChanges = emptyList(),
                    emitEvents = emptyList(),
                )
            val request =
                TransitionIssueRequest(
                    toStateKey = "IN_PROGRESS",
                    expectedVersion = existingVersion,
                )
            val updatedResponse = makeResponse(state = "IN_PROGRESS", version = existingVersion + 1)

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "DEFAULT", startStateKey = "open")
                every { workflowPort.plan(any()) } returns TransitionResult.Success(emptyPlan)
                every { repo.applyTransition(issueKey, "IN_PROGRESS", existingVersion, null) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("plan.emitEvents 가 비어있으면 TransitionEventPublisher.publish 를 호출하지 않는다") {
                sut.transitionIssue(actor, issueKey, request)
                verify(exactly = 0) { transitionEventPublisher.publish(any()) }
            }
        }

        context("S9 — 요청의 transitionId 를 shared-kernel TransitionRequest 로 넘긴다") {
            val targetTransitionId = UUID.fromString("66666666-6666-4666-8666-666666666666")
            val plan = TransitionPlan(toStateKey = "IN_PROGRESS", fieldChanges = emptyList(), emitEvents = emptyList())
            val updatedResponse = makeResponse(state = "IN_PROGRESS", version = existingVersion + 1)

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "DEFAULT", startStateKey = "open")
                every { workflowPort.plan(any()) } returns TransitionResult.Success(plan)
                every { repo.applyTransition(issueKey, "IN_PROGRESS", existingVersion, null) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("transitionId 가 지정되면 workflowPort.plan 에 그대로 실려야 한다") {
                sut.transitionIssue(actor, issueKey, requestWithTransitionId(targetTransitionId))
                verify {
                    workflowPort.plan(match { req -> req.transitionId == targetTransitionId })
                }
            }

            it("transitionId 를 안 주면 null 로 넘겨 (from,to) 후보 탐색 경로를 유지한다") {
                sut.transitionIssue(
                    actor,
                    issueKey,
                    TransitionIssueRequest(toStateKey = "IN_PROGRESS", expectedVersion = existingVersion),
                )
                verify {
                    workflowPort.plan(match { req -> req.transitionId == null })
                }
            }
        }

        context("T3 — transitionEventPublisher null (기존 단위 테스트 호환)") {
            val sutNoPublisher =
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
                    // transitionEventPublisher 기본값 null — 기존 단위 테스트 호환 경로
                )
            val webhookEvent =
                DomainEvent(
                    type = "WebhookRequested",
                    payload = mapOf("issueKey" to "BTS-1", "url" to "https://example.test/hook", "method" to "POST"),
                )
            val planWithEvents =
                TransitionPlan(
                    toStateKey = "IN_PROGRESS",
                    fieldChanges = emptyList(),
                    emitEvents = listOf(webhookEvent),
                )
            val request =
                TransitionIssueRequest(
                    toStateKey = "IN_PROGRESS",
                    expectedVersion = existingVersion,
                )
            val updatedResponse = makeResponse(state = "IN_PROGRESS", version = existingVersion + 1)

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.TRANSITION,
                        IssueScope.Issue(issueKey.value),
                    )
                } returns true
                every { repo.findByKeyForUpdate(issueKey) } returns makeIssue()
                every {
                    workflowKeyResolver.resolveStart(ProjectKey.of("BTS"), null)
                } returns WorkflowStartState(workflowKey = "DEFAULT", startStateKey = "open")
                every { workflowPort.plan(any()) } returns TransitionResult.Success(planWithEvents)
                every { repo.applyTransition(issueKey, "IN_PROGRESS", existingVersion, null) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("transitionEventPublisher 가 null 이어도 예외 없이 정상 전환한다") {
                val result = sutNoPublisher.transitionIssue(actor, issueKey, request)
                result.currentStateKey shouldBe "IN_PROGRESS"
            }
        }
    }
})

/**
 * `transitionId` 를 담은 application 전환 요청을 만든다.
 *
 * 생성자를 직접 부르지 않고 JSON 으로 역직렬화하는 이유는, 이 테스트가 **DTO 에 필드가 있는지**를
 * 묻기 때문이다. 생성자 인자로 쓰면 필드가 없을 때 컴파일이 깨져 red 가 실행 결과로 남지 않는다.
 * 기본 [ObjectMapper] 는 미지의 필드를 예외로 거절하므로 필드 부재가 실행 시점에 그대로 드러난다.
 *
 * @param transitionId 지목할 전환의 1급 식별자.
 * @return `transitionId` 가 실린 application 전환 요청 DTO.
 */
private fun requestWithTransitionId(transitionId: UUID): TransitionIssueRequest =
    ObjectMapper().registerKotlinModule().convertValue(
        mapOf(
            "toStateKey" to "IN_PROGRESS",
            "expectedVersion" to 1L,
            "transitionId" to transitionId.toString(),
        ),
        TransitionIssueRequest::class.java,
    )

// WorkflowSchemeNoDefaultException 스텁은 WorkflowSchemeNoDefaultException.kt (공유 파일) 에 정의.
