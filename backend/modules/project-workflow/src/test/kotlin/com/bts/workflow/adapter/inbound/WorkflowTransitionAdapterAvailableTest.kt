// availableTransitions — fromStateKey 기준 전이 enumerate + validator 평가 단위 테스트

package com.bts.workflow.adapter.inbound

import com.bts.shared.workflow.AvailableTransitionView
import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowValidator
import com.bts.workflow.engine.ValidatorConfig
import com.bts.workflow.engine.WorkflowDefinitionRepository
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.engine.WorkflowValidatorFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [WorkflowTransitionAdapter.availableTransitions] 단위 테스트.
 *
 * WorkflowCache / WorkflowDefinitionRepository / WorkflowValidatorFactory 를 MockK 로 대체하여
 * Spring 컨텍스트 없이 실행한다.
 *
 * 테스트 시나리오.
 * - S1. software-default 워크플로우에서 fromStateKey=open 요청 → Success에 [open→in_progress, open→closed] 정확히 2건 반환.
 * - S2. 존재하지 않는 workflowKey → WorkflowNotFound.
 * - S3. validator가 특정 전이를 거부하면 해당 전이는 결과에서 제외된다.
 */
class WorkflowTransitionAdapterAvailableTest {

    private val mockEngine: WorkflowEngine = mockk()
    private val mockCache: WorkflowCache = mockk()
    private val mockDefinitionRepo: WorkflowDefinitionRepository = mockk()
    private val mockValidatorFactory: WorkflowValidatorFactory = mockk()

    private lateinit var adapter: WorkflowTransitionAdapter

    // ── software-default 픽스처 ────────────────────────────────────────────

    private val openState = WorkflowState("open", "Open", StateCategory.TODO, 1)
    private val inProgressState = WorkflowState("in_progress", "In Progress", StateCategory.IN_PROGRESS, 2)
    private val inReviewState = WorkflowState("in_review", "In Review", StateCategory.IN_PROGRESS, 3)
    private val doneState = WorkflowState("done", "Done", StateCategory.DONE, 4)
    private val closedState = WorkflowState("closed", "Closed", StateCategory.DONE, 5)

    private val txOpenToInProgress = WorkflowTransition("open", "in_progress", "Start Work")
    private val txOpenToClosed = WorkflowTransition("open", "closed", "Cancel")
    private val txInProgressToInReview = WorkflowTransition("in_progress", "in_review", "Submit for Review")
    private val txInReviewToDone = WorkflowTransition("in_review", "done", "Approve")
    private val txInReviewToInProgress = WorkflowTransition("in_review", "in_progress", "Request Changes")
    private val txDoneToClosed = WorkflowTransition("done", "closed", "Close")

    private val softwareDefaultWorkflow = Workflow.of(
        key = "software-default",
        name = "소프트웨어 개발 기본 워크플로우",
        states = listOf(openState, inProgressState, inReviewState, doneState, closedState),
        transitions = listOf(
            txOpenToInProgress,
            txInProgressToInReview,
            txInReviewToDone,
            txInReviewToInProgress,
            txDoneToClosed,
            txOpenToClosed,
        ),
    )

    private val baseRequest = AvailableTransitionsRequest(
        workflowKey = "software-default",
        fromStateKey = "open",
        actorId = "user-001",
        actorRoles = setOf("MEMBER"),
        issueFields = emptyMap(),
    )

    @BeforeEach
    fun setUp() {
        adapter = WorkflowTransitionAdapter(mockEngine, mockCache, mockDefinitionRepo, mockValidatorFactory)
    }

    // ── S1. 정상 케이스 ──────────────────────────────────────────────────────

    @Test
    fun `S1 — open 상태에서 가용 전이 2건 반환 — 다른 fromState 전이는 제외된다`() {
        every { mockCache.findByKey("software-default") } returns softwareDefaultWorkflow
        // validator 없음 → 모두 통과
        every { mockDefinitionRepo.findValidators(txOpenToInProgress) } returns emptyList()
        every { mockDefinitionRepo.findValidators(txOpenToClosed) } returns emptyList()

        val result = adapter.availableTransitions(baseRequest)

        assertThat(result).isInstanceOf(AvailableTransitionsResult.Success::class.java)
        val success = result as AvailableTransitionsResult.Success
        assertThat(success.transitions).hasSize(2)
        assertThat(success.transitions).containsExactlyInAnyOrder(
            AvailableTransitionView("open", "in_progress", "Start Work"),
            AvailableTransitionView("open", "closed", "Cancel"),
        )
        // in_progress, in_review, done 에서 출발하는 전이는 포함되지 않아야 한다
        assertThat(success.transitions.map { it.fromStateKey }).allMatch { it == "open" }
    }

    // ── S2. WorkflowNotFound ─────────────────────────────────────────────────

    @Test
    fun `S2 — 존재하지 않는 workflowKey 요청 시 WorkflowNotFound 반환`() {
        every { mockCache.findByKey("UNKNOWN_KEY") } returns null

        val req = baseRequest.copy(workflowKey = "UNKNOWN_KEY")
        val result = adapter.availableTransitions(req)

        assertThat(result).isInstanceOf(AvailableTransitionsResult.WorkflowNotFound::class.java)
        val notFound = result as AvailableTransitionsResult.WorkflowNotFound
        assertThat(notFound.key).isEqualTo("UNKNOWN_KEY")
        // findValidators 는 호출되면 안 된다
        verify(exactly = 0) { mockDefinitionRepo.findValidators(any()) }
    }

    // ── S3. 가드 validator 거부 시 해당 전이 제외 ──────────────────────────

    @Test
    fun `S3 — validator가 open→closed 전이를 거부하면 결과에서 제외된다`() {
        every { mockCache.findByKey("software-default") } returns softwareDefaultWorkflow
        // open→in_progress: validator 없음 → 통과
        every { mockDefinitionRepo.findValidators(txOpenToInProgress) } returns emptyList()
        // open→closed: validator 1개 → Fail 반환
        val blockingConfig = ValidatorConfig("Permission", mapOf("role" to "ADMIN"))
        every { mockDefinitionRepo.findValidators(txOpenToClosed) } returns listOf(blockingConfig)
        val blockingValidator = mockk<WorkflowValidator>()
        every { blockingValidator.type } returns "Permission"
        every { blockingValidator.validate(any()) } returns ValidatorResult.Fail(
            field = null,
            reason = "ADMIN 역할만 취소할 수 있습니다.",
        )
        every { mockValidatorFactory.create("Permission", mapOf("role" to "ADMIN")) } returns blockingValidator

        val result = adapter.availableTransitions(baseRequest)

        assertThat(result).isInstanceOf(AvailableTransitionsResult.Success::class.java)
        val success = result as AvailableTransitionsResult.Success
        assertThat(success.transitions).hasSize(1)
        assertThat(success.transitions.single()).isEqualTo(
            AvailableTransitionView("open", "in_progress", "Start Work"),
        )
    }
}
