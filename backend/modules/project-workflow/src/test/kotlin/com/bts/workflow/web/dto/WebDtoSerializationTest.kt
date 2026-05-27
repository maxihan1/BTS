// Web DTO Jackson 라운드트립 + Konform 검증 3 case

package com.bts.workflow.web.dto

import com.bts.shared.workflow.DomainEvent
import com.bts.shared.workflow.FieldChange
import com.bts.shared.workflow.TransitionPlan
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.konform.validation.Invalid
import io.konform.validation.Valid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Web DTO 3종의 Jackson 직렬화 라운드트립 및 Konform 검증을 검증한다.
 *
 * Case 1. [WorkflowDto] Jackson 직렬화 — 스펙 §4.1 응답 형태 일치.
 * Case 2. [TransitionRequestDto] Konform 검증 통과/실패.
 * Case 3. [TransitionResponseDto] 라운드트립 — [TransitionPlan.toDto] 변환 포함.
 */
class WebDtoSerializationTest {
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    // ── Case 1: WorkflowDto Jackson 직렬화 ───────────────────────────────────

    @Test
    fun `WorkflowDto 직렬화 — 계층 구조가 JSON 에 올바르게 반영된다`() {
        val workflow =
            Workflow.of(
                key = "DEFAULT",
                name = "기본 워크플로우",
                states =
                    listOf(
                        WorkflowState(key = "TODO", name = "할 일", category = StateCategory.TODO, displayOrder = 0),
                        WorkflowState(
                            key = "IN_PROGRESS",
                            name = "진행 중",
                            category = StateCategory.IN_PROGRESS,
                            displayOrder = 1,
                        ),
                        WorkflowState(key = "DONE", name = "완료", category = StateCategory.DONE, displayOrder = 2),
                    ),
                transitions =
                    listOf(
                        WorkflowTransition(fromStateKey = "TODO", toStateKey = "IN_PROGRESS", name = "시작"),
                        WorkflowTransition(fromStateKey = "IN_PROGRESS", toStateKey = "DONE", name = "완료"),
                    ),
            )

        val dto = workflow.toDto()

        assertThat(dto.key).isEqualTo("DEFAULT")
        assertThat(dto.name).isEqualTo("기본 워크플로우")
        assertThat(dto.states).hasSize(3)
        assertThat(dto.states[0].key).isEqualTo("TODO")
        assertThat(dto.states[0].category).isEqualTo("TODO")
        assertThat(dto.states[0].displayOrder).isEqualTo(0)
        assertThat(dto.transitions).hasSize(2)
        assertThat(dto.transitions[0].fromStateKey).isEqualTo("TODO")
        assertThat(dto.transitions[0].toStateKey).isEqualTo("IN_PROGRESS")
        assertThat(dto.transitions[0].name).isEqualTo("시작")

        // Jackson 직렬화 → 역직렬화 라운드트립
        val json = mapper.writeValueAsString(dto)
        val restored = mapper.readValue(json, WorkflowDto::class.java)
        assertThat(restored.key).isEqualTo(dto.key)
        assertThat(restored.states).hasSize(3)
        assertThat(restored.transitions).hasSize(2)
    }

    // ── T7-1: WorkflowDto description 직렬화 검증 ────────────────────────────

    @Test
    fun `T7-1 WorkflowDto toDto — description 필드가 JSON 에 포함된다`() {
        val workflow =
            Workflow.of(
                key = "TEST",
                name = "테스트 워크플로우",
                description = "워크플로우 설명 텍스트",
                states =
                    listOf(
                        WorkflowState(key = "TODO", name = "할 일", category = StateCategory.TODO, displayOrder = 0),
                        WorkflowState(key = "DONE", name = "완료", category = StateCategory.DONE, displayOrder = 1),
                    ),
                transitions =
                    listOf(
                        WorkflowTransition(fromStateKey = "TODO", toStateKey = "DONE", name = "완료"),
                    ),
            )

        val dto = workflow.toDto()

        assertThat(dto.description).isEqualTo("워크플로우 설명 텍스트")

        val json = mapper.writeValueAsString(dto)
        assertThat(json).contains("\"description\"")
        assertThat(json).contains("워크플로우 설명 텍스트")
    }

    @Test
    fun `T7-1b WorkflowDto toDto — 도메인 description null 이면 DTO description 은 빈 문자열 (CONCERN-1 hot-fix)`() {
        val workflow =
            Workflow.of(
                key = "TEST-NULL",
                name = "설명 없는 워크플로우",
                description = null,
                states =
                    listOf(
                        WorkflowState(key = "TODO", name = "할 일", category = StateCategory.TODO, displayOrder = 0),
                        WorkflowState(key = "DONE", name = "완료", category = StateCategory.DONE, displayOrder = 1),
                    ),
                transitions = emptyList(),
            )

        val dto = workflow.toDto()

        assertThat(dto.description).isEqualTo("")
    }

    // ── T9: description null 흡수 — CONCERN-1 hot-fix ───────────────────────

    @Test
    fun `T9-1 WorkflowDto toDto — 도메인 description null 이면 DTO description 은 빈 문자열`() {
        val workflow =
            Workflow.of(
                key = "T9-NULL",
                name = "설명 없는 워크플로우",
                description = null,
                states =
                    listOf(
                        WorkflowState(key = "TODO", name = "할 일", category = StateCategory.TODO, displayOrder = 0),
                        WorkflowState(key = "DONE", name = "완료", category = StateCategory.DONE, displayOrder = 1),
                    ),
                transitions = emptyList(),
            )

        val dto = workflow.toDto()

        assertThat(dto.description).isEqualTo("")
    }

    @Test
    fun `T9-2 WorkflowDto toDto — 도메인 description non-null 이면 DTO description 에 그대로 전달`() {
        val workflow =
            Workflow.of(
                key = "T9-NONNULL",
                name = "설명 있는 워크플로우",
                description = "abc",
                states =
                    listOf(
                        WorkflowState(key = "TODO", name = "할 일", category = StateCategory.TODO, displayOrder = 0),
                        WorkflowState(key = "DONE", name = "완료", category = StateCategory.DONE, displayOrder = 1),
                    ),
                transitions = emptyList(),
            )

        val dto = workflow.toDto()

        assertThat(dto.description).isEqualTo("abc")
    }

    @Test
    fun `T9-3 WorkflowDto JSON 직렬화 — description 은 항상 string (null 아님)`() {
        val workflow =
            Workflow.of(
                key = "T9-JSON",
                name = "JSON 검증 워크플로우",
                description = null,
                states =
                    listOf(
                        WorkflowState(key = "TODO", name = "할 일", category = StateCategory.TODO, displayOrder = 0),
                        WorkflowState(key = "DONE", name = "완료", category = StateCategory.DONE, displayOrder = 1),
                    ),
                transitions = emptyList(),
            )

        val json = mapper.writeValueAsString(workflow.toDto())

        assertThat(json).contains("\"description\":\"\"")
        assertThat(json).doesNotContain("\"description\":null")
    }

    // ── T7-2: WorkflowTransitionDto key 직렬화 검증 ──────────────────────────

    @Test
    fun `T7-2 WorkflowTransitionDto toDto — key 필드가 JSON 에 포함된다`() {
        val transition = WorkflowTransition(fromStateKey = "TODO", toStateKey = "IN_PROGRESS", name = "시작")

        val dto = transition.toDto()

        assertThat(dto.key).isEqualTo("TODO__IN_PROGRESS")

        val json = mapper.writeValueAsString(dto)
        assertThat(json).contains("\"key\"")
        assertThat(json).contains("TODO__IN_PROGRESS")
    }

    // ── Case 2: TransitionRequestDto Konform 검증 ────────────────────────────

    @Test
    fun `TransitionRequestDto validate — 정상 입력은 Valid 반환`() {
        val dto =
            TransitionRequestDto(
                toStateKey = "IN_PROGRESS",
                transitionName = "시작",
                fields = mapOf("priority" to "HIGH"),
                version = 1L,
            )
        val result = dto.validate()
        assertThat(result).isInstanceOf(Valid::class.java)
    }

    @Test
    fun `TransitionRequestDto validate — toStateKey 가 빈 문자열이면 Invalid 반환`() {
        val dto =
            TransitionRequestDto(
                toStateKey = "",
                transitionName = "시작",
                version = 1L,
            )
        val result = dto.validate()
        assertThat(result).isInstanceOf(Invalid::class.java)
    }

    @Test
    fun `TransitionRequestDto validate — transitionName 이 빈 문자열이면 Invalid 반환`() {
        val dto =
            TransitionRequestDto(
                toStateKey = "IN_PROGRESS",
                transitionName = "",
                version = 1L,
            )
        val result = dto.validate()
        assertThat(result).isInstanceOf(Invalid::class.java)
    }

    @Test
    fun `TransitionRequestDto validate — version 이 0 이면 Invalid 반환`() {
        val dto =
            TransitionRequestDto(
                toStateKey = "IN_PROGRESS",
                transitionName = "시작",
                version = 0L,
            )
        val result = dto.validate()
        assertThat(result).isInstanceOf(Invalid::class.java)
    }

    // ── Case 3: TransitionResponseDto 라운드트립 ─────────────────────────────

    @Test
    fun `TransitionResponseDto 라운드트립 — TransitionPlan 변환 + Jackson 왕복 일치`() {
        val plan =
            TransitionPlan(
                toStateKey = "DONE",
                fieldChanges =
                    listOf(
                        FieldChange(field = "status", oldValue = "IN_PROGRESS", newValue = "DONE"),
                    ),
                emitEvents =
                    listOf(
                        DomainEvent(type = "ISSUE_TRANSITIONED", payload = mapOf("issueKey" to "BTS-1")),
                    ),
            )

        val dto = plan.toDto()

        assertThat(dto.toStateKey).isEqualTo("DONE")
        assertThat(dto.fieldChanges).hasSize(1)
        assertThat(dto.fieldChanges[0].field).isEqualTo("status")
        assertThat(dto.fieldChanges[0].oldValue).isEqualTo("IN_PROGRESS")
        assertThat(dto.fieldChanges[0].newValue).isEqualTo("DONE")
        assertThat(dto.events).hasSize(1)
        assertThat(dto.events[0].type).isEqualTo("ISSUE_TRANSITIONED")
        assertThat(dto.events[0].payload["issueKey"]).isEqualTo("BTS-1")

        // Jackson 라운드트립
        val json = mapper.writeValueAsString(dto)
        val restored = mapper.readValue(json, TransitionResponseDto::class.java)
        assertThat(restored.toStateKey).isEqualTo(dto.toStateKey)
        assertThat(restored.fieldChanges).hasSize(1)
        assertThat(restored.events).hasSize(1)
    }

    @Test
    fun `TransitionResponseDto 라운드트립 — 빈 fieldChanges + 빈 events`() {
        val plan =
            TransitionPlan(
                toStateKey = "TODO",
                fieldChanges = emptyList(),
                emitEvents = emptyList(),
            )

        val dto = plan.toDto()

        assertThat(dto.toStateKey).isEqualTo("TODO")
        assertThat(dto.fieldChanges).isEmpty()
        assertThat(dto.events).isEmpty()

        val json = mapper.writeValueAsString(dto)
        val restored = mapper.readValue(json, TransitionResponseDto::class.java)
        assertThat(restored).isEqualTo(dto)
    }
}
