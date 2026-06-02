// availableTransitions의 AVAILABILITY 페이즈 필터링 — EXECUTION 게이트 전이는 목록에 노출되어야 함

package com.bts.workflow.engine

import com.bts.shared.workflow.AvailableTransitionView
import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.domain.spi.ValidatorPhase
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowValidator
import com.bts.workflow.validator.RequiredFieldValidator
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ValidatorPhase.EXECUTION 게이트가 availableTransitions 에서 건너뛰어지는지 검증한다.
 *
 * 핵심 불변식.
 * - EXECUTION 페이즈 validator(RequiredField 등)가 걸린 전이도 availableTransitions 결과에 포함되어야 한다
 *   (버튼 노출 — 사용자는 전이 버튼을 볼 수 있어야 함).
 * - 동일 전이에 대해 plan() 은 resolution 필드가 없을 때 WorkflowValidatorFailureException 을 던져야 한다
 *   (EXECUTION 게이트는 plan 경로에서 여전히 유효함).
 *
 * 테스트 시나리오.
 * - S1. EXECUTION 페이즈 RequiredField validator 가 걸린 전이가 availableTransitions 결과에 포함된다.
 * - S2. 동일 전이에 대해 plan() 은 resolution 없으면 WorkflowValidatorFailureException 을 던진다.
 * - S3. AVAILABILITY 페이즈 validator 는 기존대로 availableTransitions 에서 평가되어 전이를 제거한다.
 */
class WorkflowEngineAvailabilityPhaseTest {
    private val mockCache: WorkflowCache = mockk()
    private val mockValidatorFactory: WorkflowValidatorFactory = mockk()
    private val mockPostActionFactory: WorkflowPostActionFactory = mockk()
    private val mockDefinitionRepo: WorkflowDefinitionRepository = mockk()

    private lateinit var engine: WorkflowEngine

    // ── 픽스처 ─────────────────────────────────────────────────────────────

    private val openState = WorkflowState("open", "Open", StateCategory.TODO, 1)
    private val closedState = WorkflowState("closed", "Closed", StateCategory.DONE, 2)

    /** resolution 필드를 EXECUTION 게이트로 요구하는 전이 (Jira 의 "닫기" 전이와 유사). */
    private val txOpenToClosed = WorkflowTransition("open", "closed", "Close")

    private val workflow =
        Workflow.of(
            key = "test-workflow",
            name = "테스트 워크플로우",
            states = listOf(openState, closedState),
            transitions = listOf(txOpenToClosed),
        )

    /** RequiredField(field="resolution") validator 설정 — EXECUTION 페이즈로 명시 */
    private val requiredResolutionConfig =
        ValidatorConfig("RequiredField", mapOf("field" to "resolution"), ValidatorPhase.EXECUTION)

    /** resolution 없는 가용 전이 조회 요청 */
    private val availReqWithoutResolution =
        AvailableTransitionsRequest(
            workflowKey = "test-workflow",
            fromStateKey = "open",
            actorId = "user-001",
            issueKey = "ATLAS-10",
            actorRoles = setOf("MEMBER"),
            // resolution 없음
            issueFields = emptyMap(),
        )

    @BeforeEach
    fun setUp() {
        engine = WorkflowEngine(mockCache, mockValidatorFactory, mockPostActionFactory, mockDefinitionRepo)
    }

    // ── S1. EXECUTION 페이즈 validator가 걸린 전이도 availableTransitions 에 포함됨 ──

    @Test
    fun `S1 — EXECUTION 페이즈 RequiredField validator 전이는 availableTransitions 결과에 포함된다`() {
        every { mockCache.findByKey("test-workflow") } returns workflow
        every {
            mockDefinitionRepo.findValidators("test-workflow", txOpenToClosed)
        } returns listOf(requiredResolutionConfig)
        // factory 가 RequiredFieldValidator(phase=EXECUTION) 인스턴스를 반환
        every {
            mockValidatorFactory.create("RequiredField", mapOf("field" to "resolution"))
        } returns RequiredFieldValidator("resolution")

        val result = engine.availableTransitions(availReqWithoutResolution)

        assertThat(result).isInstanceOf(AvailableTransitionsResult.Success::class.java)
        val success = result as AvailableTransitionsResult.Success
        // EXECUTION 게이트가 있어도 목록에는 반드시 노출되어야 한다
        assertThat(success.transitions).hasSize(1)
        assertThat(success.transitions.single()).isEqualTo(
            AvailableTransitionView("open", "closed", "Close"),
        )
    }

    // ── S2. plan() 은 EXECUTION validator 를 평가해 resolution 없으면 예외 ──

    @Test
    fun `S2 — plan 호출 시 resolution 없으면 WorkflowValidatorFailureException 을 던진다`() {
        every { mockCache.findByKey("test-workflow") } returns workflow
        every {
            mockDefinitionRepo.findValidators("test-workflow", txOpenToClosed)
        } returns listOf(requiredResolutionConfig)
        every {
            mockValidatorFactory.create("RequiredField", mapOf("field" to "resolution"))
        } returns RequiredFieldValidator("resolution")
        // plan 경로에서는 PostAction 조회도 발생하므로 빈 목록 반환
        every {
            mockDefinitionRepo.findPostActions("test-workflow", txOpenToClosed)
        } returns emptyList()

        val planReq =
            TransitionRequest(
                workflowKey = "test-workflow",
                issueKey = "ATLAS-10",
                fromStateKey = "open",
                toStateKey = "closed",
                actorId = "user-001",
                actorRoles = setOf("MEMBER"),
                // resolution 없음 — EXECUTION 차단
                issueFields = emptyMap(),
                version = 1L,
            )

        assertThatThrownBy { engine.plan(planReq) }
            .isInstanceOf(WorkflowValidatorFailureException::class.java)
    }

    // ── S3. AVAILABILITY 페이즈 validator 는 기존대로 availableTransitions 에서 전이 제거 ──

    @Test
    fun `S3 — AVAILABILITY 페이즈 validator 가 Fail 을 반환하면 전이는 목록에서 제외된다`() {
        every { mockCache.findByKey("test-workflow") } returns workflow
        val permissionConfig = ValidatorConfig("Permission", mapOf("role" to "ADMIN"))
        every {
            mockDefinitionRepo.findValidators("test-workflow", txOpenToClosed)
        } returns listOf(permissionConfig)
        // AVAILABILITY 페이즈 validator — Fail 반환
        val blockingValidator =
            object : WorkflowValidator {
                override val type: String = "Permission"
                override val phase = ValidatorPhase.AVAILABILITY

                override fun validate(ctx: TransitionContext): ValidatorResult {
                    return ValidatorResult.Fail(field = null, reason = "ADMIN 역할 필요")
                }
            }
        every {
            mockValidatorFactory.create("Permission", mapOf("role" to "ADMIN"))
        } returns blockingValidator

        val result = engine.availableTransitions(availReqWithoutResolution)

        assertThat(result).isInstanceOf(AvailableTransitionsResult.Success::class.java)
        val success = result as AvailableTransitionsResult.Success
        // AVAILABILITY 게이트가 Fail → 목록에서 제거되어야 한다
        assertThat(success.transitions).isEmpty()
    }
}
