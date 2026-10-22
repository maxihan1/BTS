// 전환 화면 실행 단위 테스트 — 가용 전환에 화면이 실리고 실행 때 필수 칸을 강제하는지 (FR-WF-01 D11)

package com.bts.workflow.engine

import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.TransitionRequest
import com.bts.shared.workflow.TransitionScreenFieldView
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.DraftScreenFieldDto
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 화면의 필수는 [WorkflowEngine.plan] 이 [TransitionRequest.issueFields] 의 같은 키로 본다 — 호출자 BC 가
 * 전환 후 값을 싣는다. 필수가 아닌 칸 · 화면이 없는 전환은 비어도 통과한다.
 */
class WorkflowEngineScreenTest {
    private val cache: WorkflowCache = mockk()
    private val definitionRepo: WorkflowDefinitionRepository = mockk()
    private val engine = WorkflowEngine(cache, mockk(), mockk(), definitionRepo)

    private val resolve =
        WorkflowTransition(
            "open",
            "done",
            "Resolve",
            screen =
                listOf(
                    DraftScreenFieldDto("resolution", required = true),
                    DraftScreenFieldDto("assignee", required = false),
                    DraftScreenFieldDto("comment", required = true),
                ),
        )
    private val start = WorkflowTransition("open", "doing", "Start")

    private val workflow =
        Workflow.of(
            key = "wf",
            name = "화면",
            states =
                listOf(
                    WorkflowState("open", "Open", StateCategory.TODO, 1),
                    WorkflowState("doing", "Doing", StateCategory.IN_PROGRESS, 2),
                    WorkflowState("done", "Done", StateCategory.DONE, 3),
                ),
            transitions = listOf(resolve, start),
        )

    @BeforeEach
    fun setUp() {
        every { cache.findByKey("wf") } returns workflow
        every { cache.findForTransition("wf") } returns workflow
        every { definitionRepo.findValidators(any(), any()) } returns emptyList()
        every { definitionRepo.findPostActions(any(), any()) } returns emptyList()
    }

    private fun plan(
        to: String,
        fields: Map<String, Any?>,
    ) = engine.plan(
        TransitionRequest(
            workflowKey = "wf",
            issueKey = "ATLAS-1",
            fromStateKey = "open",
            toStateKey = to,
            actorId = "user-1",
            issueFields = fields,
            actorRoles = emptySet(),
            version = 1,
        ),
    )

    @Test
    fun `가용 전환에 화면 칸이 받은 순서 그대로 실린다`() {
        val result =
            engine.availableTransitions(
                AvailableTransitionsRequest(
                    workflowKey = "wf",
                    fromStateKey = "open",
                    actorId = "user-1",
                    issueKey = "ATLAS-1",
                    actorRoles = emptySet(),
                    issueFields = emptyMap(),
                ),
            ) as AvailableTransitionsResult.Success

        assertThat(result.transitions.single { it.name == "Resolve" }.screen).containsExactly(
            TransitionScreenFieldView("resolution", true),
            TransitionScreenFieldView("assignee", false),
            TransitionScreenFieldView("comment", true),
        )
        assertThat(result.transitions.single { it.name == "Start" }.screen).isEmpty()
    }

    @Test
    fun `필수 칸이 다 차면 전환한다 — 필수가 아닌 담당자는 비어도 된다`() {
        val plan = plan("done", mapOf("resolution" to "r-1", "assignee" to null, "comment" to "확인함"))

        assertThat(plan.toStateKey).isEqualTo("done")
    }

    @Test
    fun `필수 코멘트가 공백뿐이면 거절하고 어느 칸인지 싣는다`() {
        assertThatThrownBy { plan("done", mapOf("resolution" to "r-1", "comment" to "   ")) }
            .isInstanceOfSatisfying(WorkflowValidatorFailureException::class.java) {
                assertThat(it.field).isEqualTo("comment")
                assertThat(it.reason).contains("코멘트")
            }
    }

    @Test
    fun `필수 해결 결과가 없으면 거절한다`() {
        assertThatThrownBy { plan("done", mapOf("comment" to "확인함")) }
            .isInstanceOfSatisfying(WorkflowValidatorFailureException::class.java) {
                assertThat(it.field).isEqualTo("resolution")
            }
    }

    @Test
    fun `화면이 없는 전환은 아무것도 싣지 않아도 전환한다`() {
        assertThat(plan("doing", emptyMap()).toStateKey).isEqualTo("doing")
    }
}
