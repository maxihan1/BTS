// WorkflowEngine.resolveTransition — transitionId 우선 실행 · 모호 후보 409 · 후보 1개 하위호환 단위 테스트

package com.bts.workflow.engine

import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.exception.AmbiguousTransitionException
import com.bts.workflow.domain.exception.TransitionCandidate
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [WorkflowEngine.plan] 의 전환 해석(`resolveTransition`) 단위 테스트.
 *
 * 전환 identity 가 `(from, to)` 2튜플에서 전환 ID 로 옮겨간 뒤의 계약을 고정한다
 * (ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §D3 · spec FR-WF-05 S4·S5).
 *
 * - `transitionId` 가 오면 그것으로 지목 실행한다. 소속 워크플로우가 다르면 404 다 (E9).
 * - 없으면 `(from, to)` 후보가 **정확히 1개일 때만** 실행한다 (S5 하위호환).
 * - 후보 0개는 종전과 같은 [WorkflowNotFoundException] (E11).
 * - 후보 2개 이상은 [AmbiguousTransitionException] — 조용히 첫 번째를 고르지 않는다 (S4 · E12).
 *
 * 표준 워크플로우 픽스처에는 같은 상태쌍 전환도 GLOBAL·INITIAL 도 없어서 어떤 기존 픽스처도
 * 이 분기를 밟지 않는다. 그래서 인라인 픽스처 3벌을 직접 만든다.
 */
class WorkflowEngineAmbiguousTest {
    private val mockCache: WorkflowCache = mockk()
    private val mockValidatorFactory: WorkflowValidatorFactory = mockk()
    private val mockPostActionFactory: WorkflowPostActionFactory = mockk()
    private val mockDefinitionRepo: WorkflowDefinitionRepository = mockk()

    private lateinit var engine: WorkflowEngine

    // ── 상태 ────────────────────────────────────────────────────────────────

    private val openState = WorkflowState("open", "Open", StateCategory.TODO, 1)
    private val inProgressState = WorkflowState("in_progress", "In Progress", StateCategory.IN_PROGRESS, 2)
    private val doneState = WorkflowState("done", "Done", StateCategory.DONE, 3)

    // ── 전환 — id 를 고정해야 후보 목록을 값으로 대조할 수 있다 ──────────────

    private val fastDoneId = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val reviewDoneId = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val globalDoneId = UUID.fromString("00000000-0000-4000-8000-000000000003")

    /** 같은 (open, done) 상태쌍에 이름만 다른 두 전환 — spec S1 이 만든 모호성의 원천. */
    private val txFastDone = WorkflowTransition("open", "done", "즉시 완료", id = fastDoneId)
    private val txReviewDone = WorkflowTransition("open", "done", "검토 후 완료", id = reviewDoneId)
    private val txStartWork = WorkflowTransition("open", "in_progress", "Start Work")
    private val txGlobalDone =
        WorkflowTransition(null, "done", "어디서든 완료", id = globalDoneId, kind = TransitionKind.GLOBAL)
    private val txInitialToOpen =
        WorkflowTransition(null, "open", "Create Issue", kind = TransitionKind.INITIAL)
    private val txReopen = WorkflowTransition("in_progress", "open", "Reopen")

    // ── 워크플로우 픽스처 3벌 ───────────────────────────────────────────────

    private val dupWorkflow =
        Workflow.of(
            key = "dup-fixture",
            name = "같은 상태쌍 전환 2개 픽스처",
            states = listOf(openState, inProgressState, doneState),
            transitions = listOf(txFastDone, txReviewDone, txStartWork),
        )

    private val globalDupWorkflow =
        Workflow.of(
            key = "global-dup-fixture",
            name = "NORMAL 과 GLOBAL 의 도착지가 겹치는 픽스처",
            states = listOf(openState, doneState),
            transitions = listOf(txFastDone, txGlobalDone),
        )

    private val initialWorkflow =
        Workflow.of(
            key = "initial-fixture",
            name = "INITIAL 도착지가 NORMAL 도착지와 겹치는 픽스처",
            states = listOf(openState, inProgressState),
            transitions = listOf(txInitialToOpen, txReopen),
        )

    @BeforeEach
    fun setUp() {
        engine = WorkflowEngine(mockCache, mockValidatorFactory, mockPostActionFactory, mockDefinitionRepo)
        every { mockCache.findByKey("dup-fixture") } returns dupWorkflow
        every { mockCache.findByKey("global-dup-fixture") } returns globalDupWorkflow
        every { mockCache.findByKey("initial-fixture") } returns initialWorkflow
        // validator·post-action 없음 → 어떤 전환이 골라졌는지만 순수하게 관측된다
        every { mockDefinitionRepo.findValidators(any(), any()) } returns emptyList()
        every { mockDefinitionRepo.findPostActions(any(), any()) } returns emptyList()
    }

    // ── S4 / C3. 후보 2개 + transitionId 없음 → 모호 ────────────────────────

    @Test
    fun `후보가 2개인데 transitionId 가 없으면 AmbiguousTransitionException`() {
        val thrown =
            catchThrowableOfType(AmbiguousTransitionException::class.java) {
                engine.plan(request("dup-fixture", "open", "done"))
            }

        assertThat(thrown.workflowKey).isEqualTo("dup-fixture")
        assertThat(thrown.candidates).containsExactlyInAnyOrder(
            TransitionCandidate(fastDoneId, "즉시 완료"),
            TransitionCandidate(reviewDoneId, "검토 후 완료"),
        )
        // 조용히 첫 번째를 고르면 validator 평가까지 진행된다 — 그 흔적이 없어야 한다
        verify(exactly = 0) { mockDefinitionRepo.findValidators(any(), any()) }
        verify(exactly = 0) { mockDefinitionRepo.findPostActions(any(), any()) }
    }

    // ── E12. GLOBAL 이 섞여 도착지가 겹쳐도 모호다 ──────────────────────────

    @Test
    fun `NORMAL 과 GLOBAL 이 같은 도착지를 가리켜도 모호다`() {
        val thrown =
            catchThrowableOfType(AmbiguousTransitionException::class.java) {
                engine.plan(request("global-dup-fixture", "open", "done"))
            }

        // 후보 산출을 fromStateKey 자체 필터로 되돌리면 GLOBAL 이 빠져 후보 1개가 되고 예외가 사라진다
        assertThat(thrown.candidates.map { it.transitionId })
            .containsExactlyInAnyOrder(fastDoneId, globalDoneId)
    }

    // ── S4 후반. transitionId 를 실으면 그 전환으로 ─────────────────────────

    @Test
    fun `transitionId 를 주면 그 전환으로 실행된다`() {
        val plan = engine.plan(request("dup-fixture", "open", "done", transitionId = reviewDoneId))

        assertThat(plan.toStateKey).isEqualTo("done")
        // 어느 전환이 골라졌는지는 definitionRepo 에 넘어간 전환 객체가 증언한다 (id·name 이 다르다)
        verify(exactly = 1) { mockDefinitionRepo.findValidators("dup-fixture", txReviewDone) }
        verify(exactly = 1) { mockDefinitionRepo.findPostActions("dup-fixture", txReviewDone) }
        verify(exactly = 0) { mockDefinitionRepo.findValidators("dup-fixture", txFastDone) }
    }

    // ── E9. 남의 워크플로우 전환 ID 는 404 ──────────────────────────────────

    @Test
    fun `그 워크플로우 소속이 아닌 transitionId 는 WorkflowNotFoundException`() {
        val thrown =
            catchThrowableOfType(WorkflowNotFoundException::class.java) {
                engine.plan(request("dup-fixture", "open", "done", transitionId = globalDoneId))
            }

        assertThat(thrown.workflowKey).contains("dup-fixture", globalDoneId.toString())
        verify(exactly = 0) { mockDefinitionRepo.findValidators(any(), any()) }
    }

    // ── S5. 후보 1개 경로는 종전과 완전히 같다 ──────────────────────────────

    @Test
    fun `후보가 1개면 transitionId 없이도 종전대로 실행된다`() {
        // 보드 드래그앤드롭(BoardApplicationService)·슬랙 완료 모달(SlackInteractionService)이
        // 코드 변경 0 으로 사는 경로다. 여기가 깨지면 두 BC 가 즉시 500 이 된다.
        val plan = engine.plan(request("dup-fixture", "open", "in_progress"))

        assertThat(plan.toStateKey).isEqualTo("in_progress")
        verify(exactly = 1) { mockDefinitionRepo.findValidators("dup-fixture", txStartWork) }
        verify(exactly = 1) { mockDefinitionRepo.findPostActions("dup-fixture", txStartWork) }
    }

    // ── INITIAL 은 실행 후보가 아니다 → 있지도 않은 모호성을 만들지 않는다 ──

    @Test
    fun `INITIAL 도착지와 같은 toStateKey 로 호출해도 모호가 아니다`() {
        val plan = engine.plan(request("initial-fixture", "in_progress", "open"))

        assertThat(plan.toStateKey).isEqualTo("open")
        verify(exactly = 1) { mockDefinitionRepo.findValidators("initial-fixture", txReopen) }
        verify(exactly = 0) { mockDefinitionRepo.findValidators("initial-fixture", txInitialToOpen) }
    }

    // ── E11. 후보 0개는 종전 그대로 404 ─────────────────────────────────────

    @Test
    fun `후보가 0개면 종전대로 WorkflowNotFoundException`() {
        val thrown =
            catchThrowableOfType(WorkflowNotFoundException::class.java) {
                engine.plan(request("dup-fixture", "in_progress", "done"))
            }

        assertThat(thrown.workflowKey).isEqualTo("dup-fixture::in_progress→done")
    }

    /** 이 테스트가 쓰는 [TransitionRequest] 를 만든다. [transitionId] 를 안 주면 종전 호출 형태다. */
    private fun request(
        workflowKey: String,
        fromStateKey: String,
        toStateKey: String,
        transitionId: UUID? = null,
    ): TransitionRequest =
        TransitionRequest(
            workflowKey = workflowKey,
            issueKey = "ATLAS-42",
            fromStateKey = fromStateKey,
            toStateKey = toStateKey,
            actorId = "user-001",
            issueFields = emptyMap(),
            actorRoles = setOf("MEMBER"),
            version = 1L,
            transitionId = transitionId,
        )
}
