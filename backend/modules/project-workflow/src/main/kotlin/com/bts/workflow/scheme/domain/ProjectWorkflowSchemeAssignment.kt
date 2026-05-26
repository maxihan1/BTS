// 프로젝트-워크플로우 스킴 할당 Aggregate — Project ↔ Scheme N:1 (별도 매핑 테이블, Jira align)
package com.bts.workflow.scheme.domain

import java.time.Instant
import java.util.UUID

/**
 * 프로젝트에 워크플로우 스킴이 할당된 상태를 나타내는 Aggregate Root.
 *
 * ## Project ↔ Scheme N:1 의미
 * 하나의 프로젝트([projectId])는 언제나 정확히 하나의 워크플로우 스킴([workflowSchemeId])에만
 * 할당된다(N:1). 반대로 하나의 스킴은 여러 프로젝트에 동시 적용될 수 있다.
 * DB 레벨에서 `project_workflow_scheme_assignments(project_id)` 에 UNIQUE 제약을 두어
 * 단일 프로젝트-스킴 할당을 보장한다.
 *
 * ## Jira nodeassociation 미도입 이유 (ADR: project-scheme-mapping-jira-align)
 * Jira 서버는 범용 `nodeassociation` 테이블을 사용해 엔티티 간 관계를 한 테이블로 처리하지만,
 * BTS는 아래 이유로 별도 매핑 테이블(`project_workflow_scheme_assignments`)을 채택한다.
 * 1. 타입 안전성: 범용 key-value 쌍 대신 명시적 컬럼으로 컴파일 타임 검증 가능.
 * 2. 색인 최적화: project_id, workflow_scheme_id 각각에 독립 인덱스 적용 용이.
 * 3. 감사 추적: [assignedAt], [assignedBy] 컬럼을 테이블에 직접 보유.
 * 4. jOOQ DSL 호환: 생성된 레코드 타입이 BC 내부에서 타입 안전하게 사용됨.
 *
 * @property projectId 스킴이 할당된 프로젝트 ID (`projects.id BIGINT`).
 * @property workflowSchemeId 할당된 워크플로우 스킴 식별자.
 * @property assignedAt 할당이 이루어진 시각 (UTC Instant).
 * @property assignedBy 할당을 수행한 사용자 UUID.
 */
data class ProjectWorkflowSchemeAssignment(
    val projectId: Long,
    val workflowSchemeId: WorkflowSchemeId,
    val assignedAt: Instant,
    val assignedBy: UUID,
)
