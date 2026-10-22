// WorkflowEngine.plan — 이슈 생성 토큰으로 최초(INITIAL) 전환의 validator · post-action 을 돌리는 단위 테스트 (WF-05-3)

package com.bts.workflow.engine

import com.bts.shared.workflow.DomainEvent
import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.PostActionPlan
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowPostAction
import com.bts.workflow.domain.spi.WorkflowValidator
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 이슈 생성 경로가 [TransitionRequest.INITIAL_FROM_STATE_KEY] 로 [WorkflowEngine.plan] 을 부르면
 * 최초 전환에 붙은 규칙이 돈다 — Jira Create 전환의 validator · post function 과 같은 자리다.
 *
 * 픽스처에 GLOBAL 을 섞는다. 생성 토큰이 GLOBAL 후보를 열면 모호 409 가 나므로 그 분리를 함께 잰다.
 */
class WorkflowEngineInitialTransitionTest {
    private val cache = mockk<WorkflowCache>()
    private val validatorFactory = mockk<WorkflowValidatorFactory>()
    private val postActionFactory = mockk<WorkflowPostActionFactory>()
    private val definitionRepo = mockk<WorkflowDefinitionRepository>()

    private lateinit var engine: WorkflowEngine

    private val openState = WorkflowState("open", "Open", StateCategory.TODO, 1)
    private val doneState = WorkflowState("done", "Done", StateCategory.DONE, 2)

    private val txInitial = WorkflowTransition(null, "open", "Create", kind = TransitionKind.INITIAL)
    private val txGlobalOpen = WorkflowTransition(null, "open", "Reopen Anytime", kind = TransitionKind.GLOBAL)
    private val txResolve = WorkflowTransition("open", "done", "Resolve")

    private val withInitial =
        Workflow.of(
            key = "with-initial",
            name = "최초 전환 픽스처",
            states = listOf(openState, doneState),
            transitions = listOf(txInitial, txGlobalOpen, txResolve),
        )

    private val withoutInitial =
        Workflow.of(
            key = "without-initial",
            name = "최초 전환 없는 픽스처",
            states = listOf(openState, doneState),
            transitions = listOf(txResolve),
        )

    private val validatorConfig = ValidatorConfig("RequiredField", mapOf("field" to "summary"))
    private val postActionConfig = PostActionConfig("CALL_WEBHOOK", mapOf("url" to "https://example.com/hook"))

    @BeforeEach
    fun setUp() {
        engine = WorkflowEngine(cache, validatorFactory, postActionFactory, definitionRepo)
        every { cache.findByKey("with-initial") } returns withInitial
        every { cache.findByKey("without-initial") } returns withoutInitial
    }

    @Test
    fun `생성 토큰은 shared-kernel 상수와 TransitionKind INITIAL 이름이 같다`() {
        // 두 BC 가 같은 문자열을 서로 다른 곳에서 든다 — 갈리면 생성 경로가 조용히 규칙 0개가 된다
        assertThat(TransitionRequest.INITIAL_FROM_STATE_KEY).isEqualTo(TransitionKind.INITIAL.name)
    }

    @Test
    fun `생성 토큰이면 최초 전환의 validator 와 post-action 이 돌고 출발 상태 없이 평가된다`() {
        val seen = slot<TransitionContext>()
        val validator =
            mockk<WorkflowValidator> {
                every { type } returns "RequiredField"
                every { validate(capture(seen)) } returns ValidatorResult.Pass
            }
        val event = DomainEvent("WebhookRequested", mapOf("url" to "https://example.com/hook"))
        val postAction =
            mockk<WorkflowPostAction> {
                every { type } returns "CALL_WEBHOOK"
                every { evaluate(any()) } returns PostActionPlan(emptyList(), listOf(event))
            }
        every { definitionRepo.findValidators("with-initial", txInitial) } returns listOf(validatorConfig)
        every { definitionRepo.findPostActions("with-initial", txInitial) } returns listOf(postActionConfig)
        every { validatorFactory.create("RequiredField", validatorConfig.config) } returns validator
        every { postActionFactory.create("CALL_WEBHOOK", postActionConfig.config) } returns postAction

        val plan = engine.plan(createRequest("with-initial"))

        assertThat(plan.toStateKey).isEqualTo("open")
        assertThat(plan.emitEvents).containsExactly(event)
        assertThat(seen.captured.transition).isEqualTo(txInitial)
        assertThat(seen.captured.fromState).isNull()
        // GLOBAL(→open) 은 생성 후보가 아니다 — 섞이면 모호 409 였다
        verify(exactly = 0) { definitionRepo.findValidators(any(), txGlobalOpen) }
    }

    @Test
    fun `최초 전환 validator 가 거절하면 생성 계획이 실패한다`() {
        val validator =
            mockk<WorkflowValidator> {
                every { type } returns "RequiredField"
                every { validate(any()) } returns ValidatorResult.Fail("summary", "summary is required")
            }
        every { definitionRepo.findValidators("with-initial", txInitial) } returns listOf(validatorConfig)
        every { validatorFactory.create("RequiredField", validatorConfig.config) } returns validator

        assertThatThrownBy { engine.plan(createRequest("with-initial")) }
            .isInstanceOf(WorkflowValidatorFailureException::class.java)
        verify(exactly = 0) { definitionRepo.findPostActions(any(), any()) }
    }

    @Test
    fun `최초 전환이 없는 워크플로우는 돌릴 규칙 없이 요청한 시작 상태를 그대로 돌려준다`() {
        val plan = engine.plan(createRequest("without-initial"))

        assertThat(plan.toStateKey).isEqualTo("open")
        assertThat(plan.fieldChanges).isEmpty()
        assertThat(plan.emitEvents).isEmpty()
        verify(exactly = 0) { definitionRepo.findValidators(any(), any()) }
    }

    private fun createRequest(workflowKey: String) =
        TransitionRequest(
            workflowKey = workflowKey,
            issueKey = "ATLAS-1",
            fromStateKey = TransitionRequest.INITIAL_FROM_STATE_KEY,
            toStateKey = "open",
            actorId = "user-1",
            issueFields = mapOf("summary" to "새 이슈"),
            actorRoles = emptySet(),
            version = 1L,
        )
}
