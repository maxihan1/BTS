// WorkflowEngine.availableTransitions — GLOBAL 전환 후보 포함 · 자기 자신 제외 · INITIAL 제외 단위 테스트

package com.bts.workflow.engine

import com.bts.shared.workflow.AvailableTransitionView
import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [WorkflowEngine.availableTransitions] 의 전환 종류별 후보 산출 단위 테스트.
 *
 * 표준 4개 워크플로우에는 GLOBAL·INITIAL 전환이 하나도 없어서 어떤 픽스처도 그 분기를 밟지 않는다.
 * 그래서 이 테스트는 **GLOBAL 과 INITIAL 을 모두 가진 인라인 워크플로우 픽스처**를 직접 만든다.
 *
 * 검증 규칙 (spec `2026-08-20-backend-workflow-transition-id-multi-global` F5 · E1).
 * - [TransitionKind.NORMAL] — `fromStateKey` 가 현재 상태와 같을 때만 후보.
 * - [TransitionKind.GLOBAL] — 현재 상태와 무관하게 항상 후보. 단 도착지가 현재 상태면 제외(E1).
 * - [TransitionKind.INITIAL] — 이슈 생성 진입 전용이라 후보에 절대 안 나온다.
 */
class WorkflowEngineGlobalTransitionTest {
    private val mockCache: WorkflowCache = mockk()
    private val mockValidatorFactory: WorkflowValidatorFactory = mockk()
    private val mockPostActionFactory: WorkflowPostActionFactory = mockk()
    private val mockDefinitionRepo: WorkflowDefinitionRepository = mockk()

    private lateinit var engine: WorkflowEngine

    // ── GLOBAL·INITIAL 을 모두 가진 인라인 픽스처 ──────────────────────────────

    private val openState = WorkflowState("open", "Open", StateCategory.TODO, 1)
    private val inProgressState = WorkflowState("in_progress", "In Progress", StateCategory.IN_PROGRESS, 2)
    private val doneState = WorkflowState("done", "Done", StateCategory.DONE, 3)
    private val closedState = WorkflowState("closed", "Closed", StateCategory.DONE, 4)

    private val txOpenToInProgress = WorkflowTransition("open", "in_progress", "Start Work")
    private val txInProgressToDone = WorkflowTransition("in_progress", "done", "Resolve")
    private val txGlobalToClosed =
        WorkflowTransition(null, "closed", "Close Anytime", kind = TransitionKind.GLOBAL)
    private val txGlobalToInProgress =
        WorkflowTransition(null, "in_progress", "Jump To Work", kind = TransitionKind.GLOBAL)
    private val txInitialToOpen =
        WorkflowTransition(null, "open", "Create Issue", kind = TransitionKind.INITIAL)

    private val globalWorkflow =
        Workflow.of(
            key = "global-fixture",
            name = "전역 전환 픽스처 워크플로우",
            states = listOf(openState, inProgressState, doneState, closedState),
            transitions =
                listOf(
                    txOpenToInProgress,
                    txInProgressToDone,
                    txGlobalToClosed,
                    txGlobalToInProgress,
                    txInitialToOpen,
                ),
        )

    private val baseRequest =
        AvailableTransitionsRequest(
            workflowKey = "global-fixture",
            fromStateKey = "open",
            actorId = "user-001",
            issueKey = "ATLAS-42",
            actorRoles = setOf("MEMBER"),
            issueFields = emptyMap(),
        )

    @BeforeEach
    fun setUp() {
        engine = WorkflowEngine(mockCache, mockValidatorFactory, mockPostActionFactory, mockDefinitionRepo)
        every { mockCache.findByKey("global-fixture") } returns globalWorkflow
        // validator 없음 → 후보 산출 결과가 그대로 노출된다 (validator 필터가 판정을 흐리지 않게)
        every { mockDefinitionRepo.findValidators(any(), any()) } returns emptyList()
    }

    // ── C2. GLOBAL 은 어느 상태에서도 후보 ────────────────────────────────────

    @Test
    fun `GLOBAL 전환이 어느 상태에서도 후보에 나온다`() {
        // NORMAL 출발 전환이 있는 상태(open)
        val fromOpen = successOf(baseRequest)
        assertThat(fromOpen).containsExactlyInAnyOrder(
            viewOf(txOpenToInProgress, "open", "IN_PROGRESS"),
            viewOf(txGlobalToClosed, "open", "DONE"),
            viewOf(txGlobalToInProgress, "open", "IN_PROGRESS"),
        )

        // NORMAL 출발 전환이 하나도 없는 상태(done) 에서도 GLOBAL 은 그대로 나온다
        val fromDone = successOf(baseRequest.copy(fromStateKey = "done"))
        assertThat(fromDone).containsExactlyInAnyOrder(
            viewOf(txGlobalToClosed, "done", "DONE"),
            viewOf(txGlobalToInProgress, "done", "IN_PROGRESS"),
        )
    }

    // ── E1. GLOBAL 도착지 == 현재 상태면 제외 ─────────────────────────────────

    @Test
    fun `GLOBAL 전환의 도착지가 현재 상태와 같으면 후보에서 빠진다`() {
        val fromInProgress = successOf(baseRequest.copy(fromStateKey = "in_progress"))

        assertThat(fromInProgress).containsExactlyInAnyOrder(
            viewOf(txInProgressToDone, "in_progress", "DONE"),
            viewOf(txGlobalToClosed, "in_progress", "DONE"),
        )
        // 자기 자신으로 가는 전환은 이름조차 나오면 안 된다
        assertThat(fromInProgress.map { it.name }).doesNotContain("Jump To Work")
        assertThat(fromInProgress.map { it.toStateKey }).doesNotContain("in_progress")
        // 제외된 전환은 validator 평가까지 가지도 않는다
        verify(exactly = 0) { mockDefinitionRepo.findValidators(any(), txGlobalToInProgress) }
    }

    // ── INITIAL 은 항상 제외 ─────────────────────────────────────────────────

    @Test
    fun `INITIAL 전환은 availableTransitions 후보에 절대 안 나온다`() {
        val fromDone = successOf(baseRequest.copy(fromStateKey = "done"))

        // GLOBAL 2건만 남는다 — INITIAL(→open) 이 섞이면 3건이 된다
        assertThat(fromDone).containsExactlyInAnyOrder(
            viewOf(txGlobalToClosed, "done", "DONE"),
            viewOf(txGlobalToInProgress, "done", "IN_PROGRESS"),
        )
        assertThat(fromDone.map { it.name }).doesNotContain("Create Issue")
        assertThat(fromDone.map { it.toStateKey }).doesNotContain("open")
        verify(exactly = 0) { mockDefinitionRepo.findValidators(any(), txInitialToOpen) }
    }

    // ── transitionId·kind 채우기 — 409 재요청 왕복의 전제 ─────────────────────

    @Test
    fun `GLOBAL 전환 뷰가 실제 전환 id 와 GLOBAL kind 를 싣는다`() {
        val fromDone = successOf(baseRequest.copy(fromStateKey = "done"))

        val closeAnytime = fromDone.single { it.name == "Close Anytime" }
        // 409 AMBIGUOUS_TRANSITION 재요청은 이 id 를 그대로 되실어 보내는 왕복이다 (ADR 2026-08-18 §D3).
        assertThat(closeAnytime.transitionId).isEqualTo(txGlobalToClosed.id)
        // BC 격리상 내부 enum 이 아니라 문자열로 나간다. 값은 enum 이름과 같아야 한다.
        assertThat(closeAnytime.kind).isEqualTo("GLOBAL")
        // GLOBAL 은 출발 상태가 없으므로 요청한 현재 상태로 대체된다
        assertThat(closeAnytime.fromStateKey).isEqualTo("done")

        val jumpToWork = fromDone.single { it.name == "Jump To Work" }
        assertThat(jumpToWork.transitionId).isEqualTo(txGlobalToInProgress.id)
        assertThat(jumpToWork.kind).isEqualTo("GLOBAL")

        // 두 GLOBAL 이 같은 id 를 실으면 왕복이 엉뚱한 전환을 실행한다
        assertThat(fromDone.map { it.transitionId }).doesNotContainNull().doesNotHaveDuplicates()
    }

    /**
     * 기대 뷰 한 건을 만든다.
     *
     * `transitionId` 는 픽스처가 무작위 UUID 로 만들어 손으로 쓸 수 없고 `kind` 도 enum 에서 파생시켜야
     * 문자열이 굳어 썩지 않는다. `fromStateKey`·`toCategory` 는 호출부가 명시로 넘겨 기대값이 구현을 베끼지 않게 한다.
     *
     * @param transition 기대값의 출처가 되는 픽스처 전환
     * @param fromStateKey 응답에 실려야 할 출발 상태 키 (GLOBAL 이면 요청한 현재 상태)
     * @param toCategory 도착 상태의 카테고리 문자열
     */
    private fun viewOf(
        transition: WorkflowTransition,
        fromStateKey: String,
        toCategory: String,
    ) = AvailableTransitionView(
        fromStateKey = fromStateKey,
        toStateKey = transition.toStateKey,
        name = transition.name,
        toCategory = toCategory,
        transitionId = transition.id,
        kind = transition.kind.name,
    )

    /** [AvailableTransitionsResult.Success] 임을 확인하고 전환 뷰 목록을 꺼낸다. */
    private fun successOf(req: AvailableTransitionsRequest): List<AvailableTransitionView> {
        val result = engine.availableTransitions(req)
        assertThat(result).isInstanceOf(AvailableTransitionsResult.Success::class.java)
        return (result as AvailableTransitionsResult.Success).transitions
    }
}
