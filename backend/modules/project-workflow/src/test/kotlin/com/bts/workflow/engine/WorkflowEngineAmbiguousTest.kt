// WorkflowEngine.resolveTransition — transitionId 지목 실행 · 지목의 상태 관문 통과 · 모호 후보 409 왕복 단위 테스트

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
    private val initialOpenId = UUID.fromString("00000000-0000-4000-8000-000000000004")

    /** 같은 (open, done) 상태쌍에 이름만 다른 두 전환 — spec S1 이 만든 모호성의 원천. */
    private val txFastDone = WorkflowTransition("open", "done", "즉시 완료", id = fastDoneId)
    private val txReviewDone = WorkflowTransition("open", "done", "검토 후 완료", id = reviewDoneId)
    private val txStartWork = WorkflowTransition("open", "in_progress", "Start Work")
    private val txGlobalDone =
        WorkflowTransition(null, "done", "어디서든 완료", id = globalDoneId, kind = TransitionKind.GLOBAL)
    private val txInitialToOpen =
        WorkflowTransition(null, "open", "Create Issue", id = initialOpenId, kind = TransitionKind.INITIAL)
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

    // ── BLOCKER 1. 지목(transitionId)도 `candidatesFor` 관문을 통과해야 한다 ─
    //
    // 「열거와 실행이 같은 함수를 쓴다」가 [WorkflowEngine] KDoc 이 스스로 적어 둔 계약인데,
    // 지목 경로가 소속만 대조하면 그 계약이 지목에서만 깨진다. 우회되는 관문은 4개다.
    //   ① NORMAL 출발 상태 일치  ② GLOBAL 자기 자신 전환 금지  ③ INITIAL 은 실행 후보 아님
    //   ④ 요청이 선언한 도착지와 실제 도착지 일치
    // 네 경우 모두 [WorkflowNotFoundException] 이다 — 「이 요청의 후보가 아니다」를 한 가지
    // 응답으로 통일해, 전환 id 의 존재 여부를 되묻는 oracle 이 생기지 않게 한다 (E9 와 같은 처리).

    @Test
    fun `출발 상태가 다른 NORMAL 전환을 지목해도 실행되지 않는다`() {
        // txFastDone 은 open 출발인데 이슈는 in_progress 에 있다 — 관문 ①
        val req = request("dup-fixture", "in_progress", "done", transitionId = fastDoneId)

        assertRejected(req, "지목이 NORMAL 출발 관문을 우회하면 어느 상태에서나 실행되는 전환이 된다")
    }

    @Test
    fun `INITIAL 전환을 id 로 지목해도 실행되지 않는다`() {
        // INITIAL 은 이슈 생성 진입 전용이라 실행 후보가 아니다 — 관문 ③
        val req = request("initial-fixture", "in_progress", "open", transitionId = initialOpenId)

        assertRejected(req, "INITIAL 이 지목으로 실행되면 생성 전용 전환의 post-action 이 임의로 돈다")
    }

    @Test
    fun `GLOBAL 자기 자신 전환을 id 로 지목해도 실행되지 않는다`() {
        // 이미 done 인 이슈를 다시 done 으로 — 열거에서는 제외되는 조합이다 — 관문 ②
        val req = request("global-dup-fixture", "done", "done", transitionId = globalDoneId)

        assertRejected(req, "목록에 없는 자기 전환이 지목으로만 실행되면 열거와 실행이 갈린다")
    }

    @Test
    fun `선언한 toStateKey 와 지목 전환의 도착지가 다르면 실행되지 않는다`() {
        // in_progress 로 가겠다고 선언하고 open→done 전환을 지목한다 — 관문 ④.
        // 호출자는 plan.toStateKey 를 그대로 영속하므로, 통과시키면 이슈가 선언하지 않은 곳으로 간다.
        val req = request("dup-fixture", "open", "in_progress", transitionId = fastDoneId)

        assertRejected(req, "선언 도착지 in_progress 와 실제 도착지 done 이 갈리면 거부해야 한다")
    }

    @Test
    fun `409 후보의 transitionId 를 되실어 재요청하면 그대로 실행된다`() {
        // 관문을 지목 경로에 붙여도 왕복이 깨지지 않아야 한다 — 409 후보는 언제나 후보 집합의 부분집합이다.
        val ambiguous =
            catchThrowableOfType(AmbiguousTransitionException::class.java) {
                engine.plan(request("dup-fixture", "open", "done"))
            }
        val chosen = ambiguous.candidates.last().transitionId

        val plan = engine.plan(request("dup-fixture", "open", "done", transitionId = chosen))

        assertThat(plan.toStateKey).isEqualTo("done")
        verify(exactly = 1) { mockDefinitionRepo.findPostActions("dup-fixture", match { it.id == chosen }) }
    }

    /**
     * 지목 전환이 거부되고 부수 효과가 없음을 단언한다.
     *
     * 통과해 버리면 실패 메시지에 **실제로 어디로 갔는지**(`plan.toStateKey`)가 찍힌다 —
     * 「예외가 안 났다」만 남으면 무엇이 뚫렸는지 읽을 수 없기 때문이다.
     *
     * @param req 거부되어야 할 전환 요청
     * @param why 이 관문이 왜 필요한지 — 실패 메시지에 그대로 실린다
     */
    private fun assertRejected(
        req: TransitionRequest,
        why: String,
    ) {
        val outcome = runCatching { engine.plan(req) }

        assertThat(outcome.exceptionOrNull())
            .describedAs("%s — 실제 결과=%s", why, outcome.getOrNull())
            .isInstanceOf(WorkflowNotFoundException::class.java)
        verify(exactly = 0) { mockDefinitionRepo.findPostActions(any(), any()) }
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
