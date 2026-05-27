// WorkflowApplicationService 단위 테스트 — MockK 기반 서비스 메서드 동작 검증

package com.bts.workflow.application

import com.bts.shared.workflow.DomainEvent
import com.bts.shared.workflow.FieldChange
import com.bts.shared.workflow.TransitionPlan
import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.repository.WorkflowRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [WorkflowApplicationService] 단위 테스트.
 *
 * 트랜잭션 AOP 가 없는 순수 단위 테스트.
 * [WorkflowEngine] / [WorkflowRepository] 는 MockK stub 으로 대체한다.
 */
class WorkflowApplicationServiceTest {
    private val workflowEngine: WorkflowEngine = mockk()
    private val workflowRepository: WorkflowRepository = mockk()
    private lateinit var service: WorkflowApplicationService

    @BeforeEach
    fun setUp() {
        service = WorkflowApplicationService(workflowEngine, workflowRepository)
    }

    // ── listWorkflows ─────────────────────────────────────────────────────────

    @Test
    fun `listWorkflows — repository findAll 결과를 그대로 반환한다`() {
        val workflows =
            listOf(
                buildWorkflow("software-default"),
                buildWorkflow("service-desk"),
            )
        every { workflowRepository.findAll() } returns workflows

        val result = service.listWorkflows()

        assertThat(result).hasSize(2)
        assertThat(result.map { it.key }).containsExactly("software-default", "service-desk")
        verify(exactly = 1) { workflowRepository.findAll() }
    }

    // ── getWorkflow ───────────────────────────────────────────────────────────

    @Test
    fun `getWorkflow — 존재하는 key 조회 시 워크플로우를 반환한다`() {
        val workflow = buildWorkflow("software-default")
        every { workflowRepository.findByKey("software-default") } returns workflow

        val result = service.getWorkflow("software-default")

        assertThat(result.key).isEqualTo("software-default")
    }

    @Test
    fun `getWorkflow — 존재하지 않는 key 조회 시 WorkflowNotFoundException 을 던진다`() {
        every { workflowRepository.findByKey("unknown") } returns null

        assertThatThrownBy { service.getWorkflow("unknown") }
            .isInstanceOf(WorkflowNotFoundException::class.java)
    }

    // ── planTransition ────────────────────────────────────────────────────────

    @Test
    fun `planTransition — engine plan 결과를 그대로 반환한다`() {
        val request = buildTransitionRequest()
        val plan =
            TransitionPlan(
                toStateKey = "IN_PROGRESS",
                fieldChanges = listOf(FieldChange(field = "status", oldValue = "TODO", newValue = "IN_PROGRESS")),
                emitEvents = listOf(DomainEvent(type = "ISSUE_TRANSITIONED", payload = mapOf("issueKey" to "BTS-1"))),
            )
        every { workflowEngine.plan(request) } returns plan

        val result = service.planTransition(request)

        assertThat(result.toStateKey).isEqualTo("IN_PROGRESS")
        assertThat(result.fieldChanges).hasSize(1)
        assertThat(result.emitEvents).hasSize(1)
        verify(exactly = 1) { workflowEngine.plan(request) }
    }

    @Test
    fun `planTransition — engine 이 예외를 던지면 그대로 전파한다`() {
        val request = buildTransitionRequest()
        val notFoundKey = "software-default::시작(TODO→IN_PROGRESS)"
        every { workflowEngine.plan(request) } throws WorkflowNotFoundException(notFoundKey)

        assertThatThrownBy { service.planTransition(request) }
            .isInstanceOf(WorkflowNotFoundException::class.java)
    }

    // ── 픽스처 ───────────────────────────────────────────────────────────────

    private fun buildWorkflow(key: String): Workflow {
        val states =
            listOf(
                WorkflowState(key = "TODO", name = "할 일", category = StateCategory.TODO, displayOrder = 0),
                WorkflowState(
                    key = "IN_PROGRESS",
                    name = "진행 중",
                    category = StateCategory.IN_PROGRESS,
                    displayOrder = 1,
                ),
                WorkflowState(key = "DONE", name = "완료", category = StateCategory.DONE, displayOrder = 2),
            )
        val transitions =
            listOf(
                WorkflowTransition(fromStateKey = "TODO", toStateKey = "IN_PROGRESS", name = "시작"),
            )
        return Workflow.of(key = key, name = "워크플로우 $key", states = states, transitions = transitions)
    }

    private fun buildTransitionRequest(): TransitionRequest =
        TransitionRequest(
            workflowKey = "software-default",
            issueKey = "BTS-1",
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            transitionName = "시작",
            actorId = "user-1",
            issueFields = mapOf("priority" to "HIGH"),
            actorRoles = setOf("MEMBER"),
            version = 1L,
        )
}
