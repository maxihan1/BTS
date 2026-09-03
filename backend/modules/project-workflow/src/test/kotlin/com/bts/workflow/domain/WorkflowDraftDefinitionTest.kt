// 초안 정의 → 도메인 변환 검증 — 초안과 발행이 같은 invariant 를 지나는지 본다

package com.bts.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [WorkflowDraftDefinition.toWorkflow] 검증.
 *
 * ### 이 테스트가 지키는 계약
 * **초안 저장과 발행이 같은 검증을 지나야 한다.** 두 경로가 갈리면 「초안은 저장됐는데 발행에서
 * 터지는」 상태가 생기고, 관리자는 고칠 방법을 모르는 막다른 길에 놓인다. 여기서 거부되는 정의는
 * 초안 단계에서 이미 거부된다는 뜻이다.
 *
 * invariant 자체의 정본은 [Workflow.of] 이고 그쪽에 전용 테스트가 있다. 여기서는 **초안 DTO 가
 * 그 검증에 실제로 도달하는지**와, DTO 자신이 지는 두 가지(카테고리·종류 문자열 해석)를 본다.
 *
 * 참조. FR-WF-07 D1 · ADR 2026-08-18-workflow-db-as-source-of-truth §D4
 */
class WorkflowDraftDefinitionTest {
    private fun state(
        key: String,
        category: String = "TODO",
        order: Int = 0,
    ) = DraftStateDto(key = key, name = "이름 $key", category = category, displayOrder = order)

    private fun draft(
        states: List<DraftStateDto>,
        transitions: List<DraftTransitionDto>,
    ) = WorkflowDraftDefinition(
        key = "wf-draft",
        name = "초안 워크플로우",
        states = states,
        transitions = transitions,
    )

    // ── 통과 경로 ─────────────────────────────────────────────────────────────

    @Test
    fun `유효한 초안은 도메인 워크플로우로 바뀐다`() {
        val definition =
            draft(
                states = listOf(state("open"), state("done", category = "DONE", order = 1)),
                transitions =
                    listOf(
                        DraftTransitionDto(from = null, to = "open", name = "이슈 생성", kind = "INITIAL"),
                        DraftTransitionDto(from = "open", to = "done", name = "완료"),
                    ),
            )

        val workflow = definition.toWorkflow()

        assertThat(workflow.key).isEqualTo("wf-draft")
        assertThat(workflow.states).hasSize(2)
        assertThat(workflow.transitions).hasSize(2)
        assertThat(workflow.states.map { it.category })
            .containsExactly(StateCategory.TODO, StateCategory.DONE)
    }

    @Test
    fun `전환에 임시 id 가 붙는다 — 초안은 아직 DB 행이 아니다`() {
        // 시작 전환은 정확히 1개여야 하므로(toWorkflow 의 쓰기 경계 규칙) INITIAL 로 둔다 —
        // 이 테스트가 보는 것은 kind 가 아니라 임시 id 가 붙는가다.
        val definition =
            draft(
                states = listOf(state("open")),
                transitions =
                    listOf(DraftTransitionDto(from = null, to = "open", name = "이슈 생성", kind = "INITIAL")),
            )

        val transitions = definition.toWorkflow().transitions

        // 실제 identity 는 발행 시 DB 가 정한다. 여기서는 두 전환이 서로 구분되기만 하면 된다.
        assertThat(transitions.single().id).isNotNull()
    }

    // ── 시작 전환 개수는 세기만 한다 (판정은 발행 경계가 한다) ────────────────

    /**
     * `toWorkflow()` 는 개수를 **보지 않는다.** 「정확히 1개」는 `WorkflowPublishService` 의 규칙이다.
     *
     * 저장까지 막으면 `WorkflowCommandService.create` 가 만든(전환 0개) 워크플로우가 초안 편집기에서
     * 처음부터 못 쓰인다 — 편집기가 `GET /draft` 로 받은 본문을 그대로 `PUT` 해도 400 이 된다.
     */
    @Test
    fun `전환이 하나도 없어도 도메인 변환은 통과한다`() {
        val definition = draft(states = listOf(state("open")), transitions = emptyList())

        assertThat(definition.toWorkflow().transitions).isEmpty()
        assertThat(definition.initialTransitionCount()).isEqualTo(0)
    }

    @Test
    fun `시작 전환 개수를 센다`() {
        val definition =
            draft(
                states = listOf(state("open")),
                transitions =
                    listOf(
                        DraftTransitionDto(from = null, to = "open", name = "이슈 생성", kind = "INITIAL"),
                        DraftTransitionDto(from = null, to = "open", name = "전역", kind = "GLOBAL"),
                    ),
            )

        assertThat(definition.initialTransitionCount()).isEqualTo(1)
    }

    // ── Workflow.of 의 invariant 에 실제로 도달하는가 ─────────────────────────

    @Test
    fun `상태가 하나도 없으면 거부한다`() {
        val definition = draft(states = emptyList(), transitions = emptyList())

        assertThatThrownBy { definition.toWorkflow() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("states must not be empty")
    }

    @Test
    fun `상태 키가 중복되면 거부한다`() {
        val definition =
            draft(
                states = listOf(state("open"), state("open", order = 1)),
                transitions = emptyList(),
            )

        assertThatThrownBy { definition.toWorkflow() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("duplicate state keys")
    }

    @Test
    fun `INITIAL 전환이 둘이면 거부한다`() {
        // 이슈 생성 진입 상태가 어느 쪽인지 정할 수 없다.
        val definition =
            draft(
                states = listOf(state("open"), state("todo", order = 1)),
                transitions =
                    listOf(
                        DraftTransitionDto(from = null, to = "open", name = "생성 A", kind = "INITIAL"),
                        DraftTransitionDto(from = null, to = "todo", name = "생성 B", kind = "INITIAL"),
                    ),
            )

        assertThatThrownBy { definition.toWorkflow() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `NORMAL 전환에 출발 상태가 없으면 거부한다`() {
        val definition =
            draft(
                states = listOf(state("open")),
                transitions = listOf(DraftTransitionDto(from = null, to = "open", name = "출발지 없는 보통 전환")),
            )

        assertThatThrownBy { definition.toWorkflow() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `GLOBAL 전환에 출발 상태가 있으면 거부한다`() {
        val definition =
            draft(
                states = listOf(state("open"), state("done", category = "DONE", order = 1)),
                transitions =
                    listOf(DraftTransitionDto(from = "open", to = "done", name = "출발지 있는 전역", kind = "GLOBAL")),
            )

        assertThatThrownBy { definition.toWorkflow() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `도착 상태가 상태 목록 밖이면 거부한다`() {
        val definition =
            draft(
                states = listOf(state("open")),
                transitions = listOf(DraftTransitionDto(from = "open", to = "없는상태", name = "끊긴 전환")),
            )

        assertThatThrownBy { definition.toWorkflow() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── DTO 자신이 지는 검증 — 조용한 기본값 강등을 막는다 ────────────────────

    @Test
    fun `모르는 카테고리는 TODO 로 떨어지지 않고 거부된다`() {
        // 모르는 값을 기본값으로 접으면 보드 열이 말없이 옮겨간다. 그것이 이 단언의 이유다.
        val definition =
            draft(
                states = listOf(state("open", category = "IN_REVIEW")),
                transitions = emptyList(),
            )

        assertThatThrownBy { definition.toWorkflow() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("IN_REVIEW")
            .hasMessageContaining("TODO")
    }

    @Test
    fun `모르는 전환 종류는 NORMAL 로 떨어지지 않고 거부된다`() {
        val definition =
            draft(
                states = listOf(state("open")),
                transitions = listOf(DraftTransitionDto(from = "open", to = "open", name = "x", kind = "AUTO")),
            )

        assertThatThrownBy { definition.toWorkflow() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("AUTO")
    }

    // ── 저장된 초안과의 호환 ──────────────────────────────────────────────────

    @Test
    fun `모든 필드에 기본값이 있어 필드가 늘어도 옛 초안이 읽힌다`() {
        // Jackson 이 누락 필드를 기본값으로 채우려면 기본값이 있어야 한다. 없으면 필드를 더하는
        // 순간 이미 저장된 초안 JSONB 가 전부 깨진다 — 그 계약을 여기서 못박는다.
        val empty = WorkflowDraftDefinition()

        assertThat(empty.key).isEmpty()
        assertThat(empty.states).isEmpty()
        assertThat(empty.transitions).isEmpty()
        assertThat(DraftStateDto().displayOrder).isZero()
        // 좌표는 다이어그램 편집기가 뒤늦게 더한 필드다(FR-WF-07 D8). 기본값이 없으면 그 이전에
        // 저장된 초안 JSONB 가 전부 읽히지 않는다 — 0.0 으로 접어도 안 된다. 「배치한 적 없음」과
        // 「원점에 두었음」은 다른 뜻이고, 접으면 편집기가 모든 노드를 원점에 겹쳐 그린다.
        assertThat(DraftStateDto().layoutX).isNull()
        assertThat(DraftStateDto().layoutY).isNull()
        assertThat(DraftTransitionDto().kind).isEqualTo(TransitionKind.NORMAL.name)
        assertThat(DraftRuleDto().config).isEmpty()
    }
}
