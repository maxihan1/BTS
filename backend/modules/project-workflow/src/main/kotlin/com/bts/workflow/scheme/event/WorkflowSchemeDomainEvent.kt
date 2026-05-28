// 워크플로우 스킴 도메인 이벤트 sealed 계층 — pgmq publish 용 3 종 (Assigned/Updated/Deleted)
package com.bts.workflow.scheme.event

import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant
import java.util.UUID

/**
 * 워크플로우 스킴 도메인 이벤트 sealed 계층.
 *
 * pgmq(PostgreSQL 기반 메시지 큐) publish 시점에 outbox에 저장되며,
 * 워커 프로세스가 소비해 notification/automation BC 등 다른 BC에 전달한다.
 *
 * [occurredAt]은 pgmq publish 호출 시점의 UTC Instant 값이다.
 *
 * Jackson [JsonTypeInfo]로 `type` 필드를 포함한 JSON 직렬화/역직렬화를 지원한다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = WorkflowSchemeAssignedEvent::class, name = "WorkflowSchemeAssigned"),
    JsonSubTypes.Type(value = WorkflowSchemeUpdatedEvent::class, name = "WorkflowSchemeUpdated"),
    JsonSubTypes.Type(value = WorkflowSchemeDeletedEvent::class, name = "WorkflowSchemeDeleted"),
)
sealed interface WorkflowSchemeDomainEvent {
    /** 이벤트 발생 시각 (pgmq publish 시점 UTC). */
    val occurredAt: Instant
}

/**
 * 워크플로우 스킴이 프로젝트에 배정된 이벤트.
 *
 * @property schemeId 배정된 스킴 식별자.
 * @property projectId 스킴이 배정된 프로젝트 UUID (projects.id UUID — V202 에서 BIGINT → UUID 정정).
 * @property assignedBy 배정을 수행한 사용자 UUID.
 * @property occurredAt 이벤트 발생 시각 (pgmq publish 시점 UTC).
 */
data class WorkflowSchemeAssignedEvent(
    val schemeId: WorkflowSchemeId,
    val projectId: UUID,
    val assignedBy: UUID,
    override val occurredAt: Instant,
) : WorkflowSchemeDomainEvent

/**
 * 워크플로우 스킴 필드가 변경된 이벤트.
 *
 * @property schemeId 변경된 스킴 식별자.
 * @property field 변경된 필드 이름 (예: "name", "description").
 * @property occurredAt 이벤트 발생 시각 (pgmq publish 시점 UTC).
 */
data class WorkflowSchemeUpdatedEvent(
    val schemeId: WorkflowSchemeId,
    val field: String,
    override val occurredAt: Instant,
) : WorkflowSchemeDomainEvent

/**
 * 워크플로우 스킴이 삭제된 이벤트.
 *
 * @property schemeId 삭제된 스킴 식별자.
 * @property occurredAt 이벤트 발생 시각 (pgmq publish 시점 UTC).
 */
data class WorkflowSchemeDeletedEvent(
    val schemeId: WorkflowSchemeId,
    override val occurredAt: Instant,
) : WorkflowSchemeDomainEvent
