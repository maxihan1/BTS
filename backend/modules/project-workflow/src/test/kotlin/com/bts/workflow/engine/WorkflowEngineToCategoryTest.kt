// availableTransitions toCategory — 목표 상태 StateCategory 매핑 단위 테스트

package com.bts.workflow.engine

import com.bts.shared.workflow.AvailableTransitionView
import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [WorkflowEngine.availableTransitions] 가 반환하는 각 [AvailableTransitionView.toCategory] 가
 * 목표 상태의 [StateCategory] 문자열로 채워지는지 검증한다.
 *
 * 테스트 시나리오.
 * - S1. TODO→IN_PROGRESS 전환: toCategory = "IN_PROGRESS".
 * - S2. IN_PROGRESS→DONE 전환: toCategory = "DONE".
 * - S3. 복수 전환 혼재 시 각 전환마다 올바른 toCategory 가 매핑된다.
 */
class WorkflowEngineToCategoryTest {
    private val mockCache: WorkflowCache = mockk()
    private val mockValidatorFactory: WorkflowValidatorFactory = mockk()
    private val mockPostActionFactory: WorkflowPostActionFactory = mockk()
    private val mockDefinitionRepo: WorkflowDefinitionRepository = mockk()

    private lateinit var engine: WorkflowEngine

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private val todoState = WorkflowState("open", "Open", StateCategory.TODO, 1)
    private val inProgressState = WorkflowState("in_progress", "In Progress", StateCategory.IN_PROGRESS, 2)
    private val doneState = WorkflowState("done", "Done", StateCategory.DONE, 3)

    private val txOpenToInProgress = WorkflowTransition("open", "in_progress", "Start Work")
    private val txOpenToDone = WorkflowTransition("open", "done", "Fast Close")
    private val txInProgressToDone = WorkflowTransition("in_progress", "done", "Complete")

    private val simpleWorkflow =
        Workflow.of(
            key = "simple",
            name = "단순 워크플로우",
            states = listOf(todoState, inProgressState, doneState),
            transitions = listOf(txOpenToInProgress, txOpenToDone, txInProgressToDone),
        )

    private val baseRequest =
        AvailableTransitionsRequest(
            workflowKey = "simple",
            fromStateKey = "open",
            actorId = "user-001",
            issueKey = "ATLAS-1",
            actorRoles = setOf("MEMBER"),
            issueFields = emptyMap(),
        )

    @BeforeEach
    fun setUp() {
        engine = WorkflowEngine(mockCache, mockValidatorFactory, mockPostActionFactory, mockDefinitionRepo)
        every { mockCache.findByKey("simple") } returns simpleWorkflow
        // validator 없음 — 모든 전환 통과
        every { mockDefinitionRepo.findValidators("simple", any()) } returns emptyList()
    }

    // ── S1. TODO → IN_PROGRESS ────────────────────────────────────────────

    @Test
    fun `S1 — open→in_progress 전환의 toCategory 는 IN_PROGRESS 여야 한다`() {
        val result = engine.availableTransitions(baseRequest)

        assertThat(result).isInstanceOf(AvailableTransitionsResult.Success::class.java)
        val success = result as AvailableTransitionsResult.Success
        val transition = success.transitions.single { it.toStateKey == "in_progress" }
        assertThat(transition.toCategory).isEqualTo("IN_PROGRESS")
    }

    // ── S2. TODO → DONE ───────────────────────────────────────────────────

    @Test
    fun `S2 — open→done 전환의 toCategory 는 DONE 이어야 한다`() {
        val result = engine.availableTransitions(baseRequest)

        assertThat(result).isInstanceOf(AvailableTransitionsResult.Success::class.java)
        val success = result as AvailableTransitionsResult.Success
        val transition = success.transitions.single { it.toStateKey == "done" }
        assertThat(transition.toCategory).isEqualTo("DONE")
    }

    // ── S3. 복수 전환 혼재 ─────────────────────────────────────────────────

    @Test
    fun `S3 — open 에서 전환 2건 모두 toCategory 가 올바르게 채워진다`() {
        val result = engine.availableTransitions(baseRequest)

        assertThat(result).isInstanceOf(AvailableTransitionsResult.Success::class.java)
        val success = result as AvailableTransitionsResult.Success
        assertThat(success.transitions).hasSize(2)
        assertThat(success.transitions).containsExactlyInAnyOrder(
            AvailableTransitionView("open", "in_progress", "Start Work", "IN_PROGRESS"),
            AvailableTransitionView("open", "done", "Fast Close", "DONE"),
        )
    }
}
