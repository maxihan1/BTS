// 스킴 변경으로 워크플로우가 바뀌는 이슈 타입의 남는 상태와 그 상태 매핑 (R11 · FR-WF-02)

package com.bts.workflow.scheme.domain

import java.util.UUID

/**
 * 워크플로우가 바뀌는 이슈 타입 하나에서 새 워크플로우에 없는 상태에 남는 이슈.
 *
 * @property issueTypeId `issues.type_id`.
 * @property workflowId 그 타입의 새 워크플로우.
 * @property workflowKey 새 워크플로우 키 — 화면이 도착 상태 목록을 읽는 열쇠다.
 * @property targetStatusKeys 새 워크플로우의 상태 키. 도착지 검증의 근거다.
 * @property statuses 남는 상태 키 → 이슈 수.
 */
data class StrandedIssueType(
    val issueTypeId: Long,
    val workflowId: UUID,
    val workflowKey: String,
    val targetStatusKeys: Set<String>,
    val statuses: Map<String, Long>,
)

/**
 * 이슈 타입 하나의 「옛 상태 → 새 상태」 매핑 한 줄 (이관 마법사 1/2 단계의 답).
 */
data class TypeStatusMapping(
    val issueTypeId: Long,
    val fromStatusKey: String,
    val toStatusKey: String,
)
