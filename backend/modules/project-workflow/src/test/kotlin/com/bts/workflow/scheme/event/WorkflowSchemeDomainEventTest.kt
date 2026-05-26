// 워크플로우 스킴 도메인 이벤트 Jackson JSON 라운드트립 테스트 (3종)
package com.bts.workflow.scheme.event

import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [WorkflowSchemeDomainEvent] sealed 계층 3종의 Jackson 직렬화 라운드트립을 검증한다.
 *
 * 검증 항목.
 * - 직렬화된 JSON에 `type` 식별자 필드가 포함된다.
 * - 역직렬화 시 올바른 서브타입으로 복원된다.
 * - data class equals/hashCode 기반으로 원본과 복원본이 일치한다.
 */
class WorkflowSchemeDomainEventTest {
    // findAndRegisterModules — JavaTimeModule (jsr310) 자동 등록으로 Instant 직렬화 활성화 (운영 Spring autoconfigured ObjectMapper 와 동일)
    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .findAndRegisterModules()

    // ── WorkflowSchemeAssignedEvent 라운드트립 ────────────────────────────────

    @Test
    fun `WorkflowSchemeAssignedEvent 직렬화 — JSON 에 type 필드 포함`() {
        val event =
            WorkflowSchemeAssignedEvent(
                schemeId = WorkflowSchemeId(1L),
                projectId = 42L,
                assignedBy = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
            )

        val json = mapper.writeValueAsString(event)

        assertThat(json).contains("\"type\":\"WorkflowSchemeAssigned\"")
    }

    @Test
    fun `WorkflowSchemeAssignedEvent 라운드트립 — 역직렬화 시 원본과 동일`() {
        val original =
            WorkflowSchemeAssignedEvent(
                schemeId = WorkflowSchemeId(1L),
                projectId = 42L,
                assignedBy = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
            )

        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, WorkflowSchemeDomainEvent::class.java)

        assertThat(restored).isEqualTo(original)
        assertThat(restored).isInstanceOf(WorkflowSchemeAssignedEvent::class.java)
    }

    @Test
    fun `WorkflowSchemeAssignedEvent equals hashCode — 동일 값이면 동등`() {
        val e1 =
            WorkflowSchemeAssignedEvent(
                schemeId = WorkflowSchemeId(1L),
                projectId = 42L,
                assignedBy = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
            )
        val e2 =
            WorkflowSchemeAssignedEvent(
                schemeId = WorkflowSchemeId(1L),
                projectId = 42L,
                assignedBy = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
            )

        assertThat(e1).isEqualTo(e2)
        assertThat(e1).hasSameHashCodeAs(e2)
    }

    // ── WorkflowSchemeUpdatedEvent 라운드트립 ─────────────────────────────────

    @Test
    fun `WorkflowSchemeUpdatedEvent 직렬화 — JSON 에 type 필드 포함`() {
        val event =
            WorkflowSchemeUpdatedEvent(
                schemeId = WorkflowSchemeId(2L),
                field = "name",
                occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
            )

        val json = mapper.writeValueAsString(event)

        assertThat(json).contains("\"type\":\"WorkflowSchemeUpdated\"")
    }

    @Test
    fun `WorkflowSchemeUpdatedEvent 라운드트립 — 역직렬화 시 원본과 동일`() {
        val original =
            WorkflowSchemeUpdatedEvent(
                schemeId = WorkflowSchemeId(2L),
                field = "name",
                occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
            )

        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, WorkflowSchemeDomainEvent::class.java)

        assertThat(restored).isEqualTo(original)
        assertThat(restored).isInstanceOf(WorkflowSchemeUpdatedEvent::class.java)
    }

    @Test
    fun `WorkflowSchemeUpdatedEvent equals hashCode — 동일 값이면 동등`() {
        val e1 =
            WorkflowSchemeUpdatedEvent(schemeId = WorkflowSchemeId(2L), field = "name", occurredAt = Instant.parse("2026-05-26T00:00:00Z"))
        val e2 =
            WorkflowSchemeUpdatedEvent(schemeId = WorkflowSchemeId(2L), field = "name", occurredAt = Instant.parse("2026-05-26T00:00:00Z"))

        assertThat(e1).isEqualTo(e2)
        assertThat(e1).hasSameHashCodeAs(e2)
    }

    // ── WorkflowSchemeDeletedEvent 라운드트립 ─────────────────────────────────

    @Test
    fun `WorkflowSchemeDeletedEvent 직렬화 — JSON 에 type 필드 포함`() {
        val event =
            WorkflowSchemeDeletedEvent(
                schemeId = WorkflowSchemeId(3L),
                occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
            )

        val json = mapper.writeValueAsString(event)

        assertThat(json).contains("\"type\":\"WorkflowSchemeDeleted\"")
    }

    @Test
    fun `WorkflowSchemeDeletedEvent 라운드트립 — 역직렬화 시 원본과 동일`() {
        val original =
            WorkflowSchemeDeletedEvent(
                schemeId = WorkflowSchemeId(3L),
                occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
            )

        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, WorkflowSchemeDomainEvent::class.java)

        assertThat(restored).isEqualTo(original)
        assertThat(restored).isInstanceOf(WorkflowSchemeDeletedEvent::class.java)
    }

    @Test
    fun `WorkflowSchemeDeletedEvent equals hashCode — 동일 값이면 동등`() {
        val e1 = WorkflowSchemeDeletedEvent(schemeId = WorkflowSchemeId(3L), occurredAt = Instant.parse("2026-05-26T00:00:00Z"))
        val e2 = WorkflowSchemeDeletedEvent(schemeId = WorkflowSchemeId(3L), occurredAt = Instant.parse("2026-05-26T00:00:00Z"))

        assertThat(e1).isEqualTo(e2)
        assertThat(e1).hasSameHashCodeAs(e2)
    }
}
