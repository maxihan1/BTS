// WorkflowEngine 4 단위 테스트 — happy / validator fail / workflow not found / MANDATORY annotation

package com.bts.workflow.engine

import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.DomainEvent
import com.bts.workflow.domain.dto.FieldChange
import com.bts.workflow.domain.dto.PostActionPlan
import com.bts.workflow.domain.dto.TransitionRequest
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowPostAction
import com.bts.workflow.domain.spi.WorkflowValidator
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [WorkflowEngine] 단위 테스트.
 *
 * Spring 컨텍스트 없이 MockK 만으로 실행한다.
 * Propagation.MANDATORY 검증은 reflection 으로 어노테이션 존재 여부를 확인한다
 * (실제 트랜잭션 동작은 Spring AOP 프록시 레이어에서 발효되므로 단위 테스트 범위 밖).
 */
class WorkflowEngineUnitTest {
    private val cache = mockk<WorkflowCache>()
    private val validatorFactory = mockk<WorkflowValidatorFactory>()
    private val postActionFactory = mockk<WorkflowPostActionFactory>()
    private val definitionRepo = mockk<WorkflowDefinitionRepository>()

    private lateinit var engine: WorkflowEngine

    // --- 픽스처 ---

    private val todoState = WorkflowState("TODO", "할 일", StateCategory.TODO, 0)
    private val inProgressState = WorkflowState("IN_PROGRESS", "진행 중", StateCategory.IN_PROGRESS, 1)

    private val transition =
        WorkflowTransition(
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            name = "시작",
        )

    private val workflow =
        Workflow.of(
            key = "DEFAULT",
            name = "기본 워크플로우",
            states = listOf(todoState, inProgressState),
            transitions = listOf(transition),
        )

    private val baseRequest =
        TransitionRequest(
            workflowKey = "DEFAULT",
            issueKey = "BTS-1",
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            transitionName = "시작",
            actorId = "user-1",
            issueFields = mapOf("priority" to "HIGH"),
            actorRoles = setOf("MEMBER"),
            version = 1L,
        )

    private val validatorConfigA = ValidatorConfig("RequiredField", mapOf("field" to "priority"))
    private val postActionConfigA = PostActionConfig("SetField", mapOf("field" to "assignee", "value" to "user-1"))

    @BeforeEach
    fun setUp() {
        engine = WorkflowEngine(cache, validatorFactory, postActionFactory, definitionRepo)
    }

    // ------------------------------------------------------------------ //
    // 1. happy path — 4 Validator pass + PostAction 누적 → TransitionPlan //
    // ------------------------------------------------------------------ //

    @Test
    fun `happy path — Validator pass + PostAction 누적 → TransitionPlan 반환`() {
        // Validator 2개 — 둘 다 Pass
        val validatorA =
            mockk<WorkflowValidator> {
                every { type } returns "RequiredField"
                every { validate(any()) } returns ValidatorResult.Pass
            }
        val validatorB =
            mockk<WorkflowValidator> {
                every { type } returns "Permission"
                every { validate(any()) } returns ValidatorResult.Pass
            }

        // PostAction 2개 — 각각 다른 결과 반환
        val fieldChange1 = FieldChange("assignee", null, "user-1")
        val event1 = DomainEvent("ISSUE_TRANSITIONED", mapOf("issueKey" to "BTS-1"))
        val postActionA =
            mockk<WorkflowPostAction> {
                every { type } returns "SetField"
                every { evaluate(any()) } returns PostActionPlan(listOf(fieldChange1), listOf(event1))
            }
        val fieldChange2 = FieldChange("priority", "HIGH", "MEDIUM")
        val postActionB =
            mockk<WorkflowPostAction> {
                every { type } returns "Notify"
                every { evaluate(any()) } returns PostActionPlan(listOf(fieldChange2), emptyList())
            }

        val validatorConfigB = ValidatorConfig("Permission", emptyMap())
        val postActionConfigB = PostActionConfig("Notify", emptyMap())

        every { cache.findByKey("DEFAULT") } returns workflow
        every { definitionRepo.findValidators(transition) } returns listOf(validatorConfigA, validatorConfigB)
        every { definitionRepo.findPostActions(transition) } returns listOf(postActionConfigA, postActionConfigB)
        every { validatorFactory.create("RequiredField", validatorConfigA.config) } returns validatorA
        every { validatorFactory.create("Permission", validatorConfigB.config) } returns validatorB
        every { postActionFactory.create("SetField", postActionConfigA.config) } returns postActionA
        every { postActionFactory.create("Notify", postActionConfigB.config) } returns postActionB

        val plan = engine.plan(baseRequest)

        assertThat(plan.toStateKey).isEqualTo("IN_PROGRESS")
        assertThat(plan.fieldChanges).containsExactly(fieldChange1, fieldChange2)
        assertThat(plan.emitEvents).containsExactly(event1)
    }

    // -------------------------------------------------------- //
    // 2. Validator fail → WorkflowValidatorFailureException    //
    // -------------------------------------------------------- //

    @Test
    fun `Validator Fail 반환 시 WorkflowValidatorFailureException 을 던진다`() {
        val failValidator =
            mockk<WorkflowValidator> {
                every { type } returns "RequiredField"
                every { validate(any()) } returns
                    ValidatorResult.Fail(
                        field = "priority", reason = "priority 필드는 필수입니다",
                    )
            }

        every { cache.findByKey("DEFAULT") } returns workflow
        every { definitionRepo.findValidators(transition) } returns listOf(validatorConfigA)
        every { validatorFactory.create("RequiredField", validatorConfigA.config) } returns failValidator

        assertThatThrownBy { engine.plan(baseRequest) }
            .isInstanceOf(WorkflowValidatorFailureException::class.java)
            .satisfies({ ex ->
                val failure = ex as WorkflowValidatorFailureException
                assertThat(failure.validatorType).isEqualTo("RequiredField")
                assertThat(failure.field).isEqualTo("priority")
            })
    }

    // -------------------------------------------------------- //
    // 3. workflow 부재 → WorkflowNotFoundException             //
    // -------------------------------------------------------- //

    @Test
    fun `cache 에 workflow 가 없으면 WorkflowNotFoundException 을 던진다`() {
        every { cache.findByKey("MISSING") } returns null

        val request = baseRequest.copy(workflowKey = "MISSING")

        assertThatThrownBy { engine.plan(request) }
            .isInstanceOf(WorkflowNotFoundException::class.java)
            .hasMessageContaining("MISSING")
    }

    // ---------------------------------------------------------------------- //
    // 4. Propagation.MANDATORY — plan() 메서드에 어노테이션이 선언돼 있는지 검증 //
    // ---------------------------------------------------------------------- //

    @Test
    fun `plan() 메서드에 Transactional(MANDATORY) 어노테이션이 선언돼 있다`() {
        val planMethod = WorkflowEngine::class.java.getMethod("plan", TransitionRequest::class.java)
        val txAnnotation = planMethod.getAnnotation(Transactional::class.java)

        assertThat(txAnnotation)
            .withFailMessage("plan() 에 @Transactional 어노테이션이 없습니다")
            .isNotNull()
        assertThat(txAnnotation.propagation)
            .withFailMessage("plan() 의 propagation 이 MANDATORY 가 아닙니다 — 실제: %s", txAnnotation.propagation)
            .isEqualTo(Propagation.MANDATORY)
    }
}
