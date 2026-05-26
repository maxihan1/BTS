// 스킴-이슈타입-워크플로우 매핑 Entity — Jira workflowschemeentity align (issue_type NULL = default mapping)
package com.bts.workflow.scheme.domain

import com.bts.issue.type.domain.IssueTypeId
import java.time.Instant
import java.util.UUID

/**
 * 워크플로우 스킴과 이슈 타입 간의 매핑 Entity.
 *
 * Jira의 `workflowschemeentity` 테이블 구조를 따른다.
 * 이슈 타입별로 사용할 워크플로우를 지정하며, [issueTypeId] 가 null 인 레코드는
 * 명시적으로 매핑되지 않은 이슈 타입에 적용되는 **default mapping** 을 의미한다.
 *
 * DB 테이블: `workflow_scheme_issue_type_mappings`
 * - `scheme_id BIGINT FK` → [schemeId]
 * - `issue_type_id BIGINT FK NULL` → [issueTypeId] (NULL = unmatched 이슈 타입 전부 적용)
 * - `workflow_id UUID FK NOT NULL` → [workflowId] (V001 `workflows.id UUID` 와 타입 일치)
 *
 * @property id DB PK. 저장 전(신규 생성 시)에는 null 이다.
 * @property schemeId 이 매핑이 속한 [WorkflowScheme] 의 식별자.
 * @property issueTypeId 매핑 대상 이슈 타입 식별자.
 *   `null` = default mapping for unmatched types —
 *   스킴 내에서 다른 명시적 매핑이 없는 이슈 타입 전부에 이 워크플로우가 적용된다.
 *   (Jira `workflowschemeentity.issuetype = NULL` 패턴 일치)
 * @property workflowId 실제로 사용할 워크플로우의 UUID.
 * @property createdAt 레코드 생성 시각 (UTC).
 */
data class SchemeIssueTypeMapping(
    val id: Long?,
    val schemeId: WorkflowSchemeId,
    val issueTypeId: IssueTypeId?,
    val workflowId: UUID,
    val createdAt: Instant,
)
