// WorkflowEngine.plan — 이슈 생성 요청(creation)으로 최초(INITIAL) 전환의 validator · post-action 을 돌리는 단위 테스트 (WF-05-3)

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
import com.bts.workflow.domain.spi.ValidatorPhase
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
 * 이슈 생성 경로가 [TransitionRequest.creation] 으로 [WorkflowEngine.plan] 을 부르면
 * 최초 전환에 붙은 규칙이 돈다 — Jira Create 전환의 validator · post function 과 같은 자리다.
 *
 * 픽스처에 GLOBAL 을 섞는다. 생성 요청이 GLOBAL 후보를 열면 모호 409 가 나므로 그 분리를 함께 잰다.
 * `INITIAL` 이라는 **이름의 실제 상태**도 둔다 — 생성 판정을 상태 키로 하면 그 상태의 이슈가 갇힌다.
 */
class WorkflowEngineInitialTransitionTest {
    private val cache = mockk<WorkflowCache>()
    private val validatorFactory = mockk<WorkflowValidatorFactory>()
    private val postActionFactory = mockk<WorkflowPostActionFactory>()
    private val definitionRepo = mockk<WorkflowDefinitionRepository>()

    private lateinit var engine: WorkflowEngine

    private val openState = WorkflowState("open", "Open", StateCategory.TODO, 1)
    private val doneState = WorkflowState("done", "Done", StateCategory.DONE, 2)
    private val initialNamedState = WorkflowState("INITIAL", "Initial", StateCategory.TODO, 3)

    private val txInitial = WorkflowTransition(null, "open", "Create", kind = TransitionKind.INITIAL)
    private val txGlobalOpen = WorkflowTransition(null, "open", "Reopen Anytime", kind = TransitionKind.GLOBAL)
    private val txResolve = WorkflowTransition("open", "done", "Resolve")
    private val txLeaveInitialState = WorkflowTransition("INITIAL", "done", "Leave")

    private val withInitial =
        Workflow.of(
            key = "with-initial",
            name = "최초 전환 픽스처",
            states = listOf(openState, doneState, initialNamedState),
            transitions = listOf(txInitial, txGlobalOpen, txResolve, txLeaveInitialState),
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
    fun `INITIAL 이라는 이름의 상태에 있는 이슈는 일반 전환으로 나가고 최초 전환이 끼지 않는다`() {
        every { definitionRepo.findValidators(any(), any()) } returns emptyList()
        every { definitionRepo.findPostActions(any(), any()) } returns emptyList()

        val leave = createRequest("with-initial").copy(fromStateKey = "INITIAL", toStateKey = "done", creation = false)
        val plan = engine.plan(leave)

        assertThat(plan.toStateKey).isEqualTo("done")
        verify(exactly = 1) { definitionRepo.findPostActions("with-initial", txLeaveInitialState) }
        verify(exactly = 0) { definitionRepo.findPostActions(any(), txInitial) }
    }

    @Test
    fun `최초 전환의 조건 validator 는 생성에서 건너뛴다`() {
        // 관문이 새로 붙이는 것은 막지만 종류 변경 · 이전 행은 관문을 안 거친다 — 실행 자리에서 거른다
        val condition =
            mockk<WorkflowValidator> {
                every { type } returns "permission-check"
                every { phase } returns ValidatorPhase.AVAILABILITY
            }
        val conditionConfig = ValidatorConfig("permission-check", mapOf("permission" to "TRANSITION_ISSUE"))
        every { definitionRepo.findValidators("with-initial", txInitial) } returns listOf(conditionConfig)
        every { definitionRepo.findPostActions("with-initial", txInitial) } returns emptyList()
        every { validatorFactory.create("permission-check", conditionConfig.config) } returns condition

        val plan = engine.plan(createRequest("with-initial"))

        assertThat(plan.toStateKey).isEqualTo("open")
        verify(exactly = 0) { condition.validate(any()) }
    }

    @Test
    fun `생성 요청이면 최초 전환의 validator 와 post-action 이 돌고 출발 상태 없이 평가된다`() {
        val seen = slot<TransitionContext>()
        val validator =
            mockk<WorkflowValidator> {
                every { type } returns "RequiredField"
                every { phase } returns ValidatorPhase.EXECUTION
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
                every { phase } returns ValidatorPhase.EXECUTION
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
            fromStateKey = "open",
            toStateKey = "open",
            actorId = "user-1",
            issueFields = mapOf("summary" to "새 이슈"),
            actorRoles = emptySet(),
            version = 1L,
            creation = true,
        )
}
