// DTO Jackson 라운드트립 + Konform 검증

package com.bts.workflow.domain.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.konform.validation.Invalid
import io.konform.validation.Valid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 4종 DTO (TransitionRequest / TransitionPlan / FieldChange / DomainEvent) 의
 * Jackson 직렬화 → 역직렬화 라운드트립 일치를 검증한다.
 *
 * 추가 검증.
 * - TransitionRequest.validate() Konform DSL 통과/실패 케이스
 *
 * TransitionContext / PostActionPlan 은 다른 도메인 타입(Workflow, IssueView 등)을
 * 직접 참조하므로 단독 라운드트립 검증은 wave 2 이후 일괄 수행한다.
 */
class DtoJsonRoundtripTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    // ── FieldChange 라운드트립 ────────────────────────────────────────────────

    @Test
    fun `FieldChange 라운드트립 — String 값`() {
        val original = FieldChange(field = "status", oldValue = "TODO", newValue = "IN_PROGRESS")
        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, FieldChange::class.java)
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `FieldChange 라운드트립 — null 값`() {
        val original = FieldChange(field = "assignee", oldValue = null, newValue = null)
        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, FieldChange::class.java)
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `FieldChange 라운드트립 — 숫자 값`() {
        val original = FieldChange(field = "priority", oldValue = 1, newValue = 3)
        val json = mapper.writeValueAsString(original)
        // Jackson 역직렬화 시 Any? 는 숫자를 Int 로 복원함 (jsonb 호환)
        val restored = mapper.readValue(json, FieldChange::class.java)
        assertThat(restored.field).isEqualTo(original.field)
        assertThat(restored.oldValue.toString()).isEqualTo(original.oldValue.toString())
        assertThat(restored.newValue.toString()).isEqualTo(original.newValue.toString())
    }

    // ── DomainEvent 라운드트립 ────────────────────────────────────────────────

    @Test
    fun `DomainEvent 라운드트립 — payload 포함`() {
        val original = DomainEvent(
            type = "ISSUE_TRANSITIONED",
            payload = mapOf("issueKey" to "BTS-1", "toState" to "DONE", "count" to 42),
        )
        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, DomainEvent::class.java)
        assertThat(restored.type).isEqualTo(original.type)
        assertThat(restored.payload["issueKey"]).isEqualTo("BTS-1")
        assertThat(restored.payload["toState"]).isEqualTo("DONE")
    }

    @Test
    fun `DomainEvent 라운드트립 — 빈 payload`() {
        val original = DomainEvent(type = "NOOP", payload = emptyMap())
        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, DomainEvent::class.java)
        assertThat(restored).isEqualTo(original)
    }

    // ── TransitionPlan 라운드트립 ─────────────────────────────────────────────

    @Test
    fun `TransitionPlan 라운드트립 — fieldChanges + emitEvents 포함`() {
        val original = TransitionPlan(
            toStateKey = "DONE",
            fieldChanges = listOf(
                FieldChange(field = "status", oldValue = "IN_PROGRESS", newValue = "DONE"),
            ),
            emitEvents = listOf(
                DomainEvent(type = "ISSUE_DONE", payload = mapOf("issueKey" to "BTS-42")),
            ),
        )
        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, TransitionPlan::class.java)
        assertThat(restored.toStateKey).isEqualTo(original.toStateKey)
        assertThat(restored.fieldChanges).hasSize(1)
        assertThat(restored.fieldChanges[0].field).isEqualTo("status")
        assertThat(restored.emitEvents).hasSize(1)
        assertThat(restored.emitEvents[0].type).isEqualTo("ISSUE_DONE")
    }

    @Test
    fun `TransitionPlan 라운드트립 — 빈 리스트`() {
        val original = TransitionPlan(
            toStateKey = "TODO",
            fieldChanges = emptyList(),
            emitEvents = emptyList(),
        )
        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, TransitionPlan::class.java)
        assertThat(restored).isEqualTo(original)
    }

    // ── TransitionRequest 라운드트립 ──────────────────────────────────────────

    @Test
    fun `TransitionRequest 라운드트립`() {
        val original = TransitionRequest(
            workflowKey = "WF-001",
            issueKey = "BTS-1",
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            transitionName = "시작",
            actorId = "user-123",
            issueFields = mapOf("priority" to "HIGH", "labels" to listOf("bug")),
            actorRoles = setOf("DEVELOPER", "VIEWER"),
            version = 1L,
        )
        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, TransitionRequest::class.java)
        assertThat(restored.workflowKey).isEqualTo(original.workflowKey)
        assertThat(restored.issueKey).isEqualTo(original.issueKey)
        assertThat(restored.fromStateKey).isEqualTo(original.fromStateKey)
        assertThat(restored.toStateKey).isEqualTo(original.toStateKey)
        assertThat(restored.transitionName).isEqualTo(original.transitionName)
        assertThat(restored.actorId).isEqualTo(original.actorId)
        assertThat(restored.version).isEqualTo(original.version)
        assertThat(restored.actorRoles).containsExactlyInAnyOrder("DEVELOPER", "VIEWER")
    }

    // ── TransitionRequest Konform 검증 ────────────────────────────────────────

    @Test
    fun `TransitionRequest validate — 정상 입력은 Valid 반환`() {
        val request = TransitionRequest(
            workflowKey = "WF-001",
            issueKey = "BTS-1",
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            transitionName = "시작",
            actorId = "user-123",
            issueFields = emptyMap(),
            actorRoles = setOf("DEVELOPER"),
            version = 1L,
        )
        val result = request.validate()
        assertThat(result).isInstanceOf(Valid::class.java)
    }

    @Test
    fun `TransitionRequest validate — issueKey 가 빈 문자열이면 Invalid 반환`() {
        val request = TransitionRequest(
            workflowKey = "WF-001",
            issueKey = "",
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            transitionName = "시작",
            actorId = "user-123",
            issueFields = emptyMap(),
            actorRoles = setOf("DEVELOPER"),
            version = 1L,
        )
        val result = request.validate()
        assertThat(result).isInstanceOf(Invalid::class.java)
    }

    @Test
    fun `TransitionRequest validate — version 이 0 이면 Invalid 반환`() {
        val request = TransitionRequest(
            workflowKey = "WF-001",
            issueKey = "BTS-1",
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            transitionName = "시작",
            actorId = "user-123",
            issueFields = emptyMap(),
            actorRoles = setOf("DEVELOPER"),
            version = 0L,
        )
        val result = request.validate()
        assertThat(result).isInstanceOf(Invalid::class.java)
    }

    @Test
    fun `TransitionRequest validate — workflowKey 가 빈 문자열이면 Invalid 반환`() {
        val request = TransitionRequest(
            workflowKey = "",
            issueKey = "BTS-1",
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            transitionName = "시작",
            actorId = "user-123",
            issueFields = emptyMap(),
            actorRoles = setOf("DEVELOPER"),
            version = 1L,
        )
        val result = request.validate()
        assertThat(result).isInstanceOf(Invalid::class.java)
    }

    @Test
    fun `TransitionRequest validate — actorId 가 빈 문자열이면 Invalid 반환`() {
        val request = TransitionRequest(
            workflowKey = "WF-001",
            issueKey = "BTS-1",
            fromStateKey = "TODO",
            toStateKey = "IN_PROGRESS",
            transitionName = "시작",
            actorId = "",
            issueFields = emptyMap(),
            actorRoles = setOf("DEVELOPER"),
            version = 1L,
        )
        val result = request.validate()
        assertThat(result).isInstanceOf(Invalid::class.java)
    }
}
