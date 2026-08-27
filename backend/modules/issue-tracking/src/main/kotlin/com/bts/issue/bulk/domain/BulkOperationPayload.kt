// 일괄 작업 payload sealed class — BULK_EDIT(Edit) / BULK_TRANSITION(Transition) 타입별 파라미터를 안전하게 표현

package com.bts.issue.bulk.domain

import java.util.UUID

/**
 * 일괄 작업의 타입별 파라미터를 표현하는 sealed class.
 *
 * - [Edit]: BULK_EDIT 작업의 필드 변경 파라미터.
 * - [Transition]: BULK_TRANSITION 작업의 전환 대상 상태 키.
 * - [StatusMigration]: STATUS_MIGRATION 작업의 상태 매핑과 프로젝트 범위.
 *
 * Repository 는 이 값을 JSONB 컬럼에 직렬화하여 저장하고, 조회 시 복원한다.
 * PR2 워커는 이 payload 를 읽어 실제 이슈 갱신을 수행한다.
 */
sealed class BulkOperationPayload {
    /**
     * BULK_EDIT 작업 파라미터.
     *
     * 각 필드는 null=무변경 (merge-patch 시맨틱).
     *
     * @property priority 변경할 우선순위. non-null 이면 1..5 범위. null 이면 무변경.
     * @property impact 변경할 영향도. non-null 이면 1..3 범위. null 이면 무변경.
     */
    data class Edit(
        val priority: Int?,
        val impact: Int?,
    ) : BulkOperationPayload()

    /**
     * BULK_TRANSITION 작업 파라미터.
     *
     * 일괄 전환 대상 전체에 동일한 resolutionId 를 적용한다.
     * null 이면 각 이슈의 resolution_id 를 clear (비DONE 전환 시맨틱과 동일).
     *
     * @property toStateKey 전환할 대상 상태 키. 비어 있으면 안 된다.
     * @property resolutionId DONE 상태로 전환할 때 지정하는 해결책 UUID.
     *   null 이면 resolution_id clear. 전체 일괄에 동일하게 적용된다.
     */
    data class Transition(
        val toStateKey: String,
        val resolutionId: UUID? = null,
    ) : BulkOperationPayload()

    /**
     * STATUS_MIGRATION 작업 파라미터.
     *
     * 워크플로우에서 빠지는 상태에 남은 이슈를 어디로 옮길지 정의한다.
     * 대상 이슈 목록은 큐잉 시점에 확정되지 않는다 — 워커가 실행 시점에
     * [mappings] 의 출발 상태와 [projectKeys] 로 이슈를 다시 조회한다.
     *
     * @property mappings 출발 상태 키 → 대상 상태 키. 항목의 현재 상태로 조회해 대상을 정한다.
     * @property projectKeys 이관 대상 프로젝트 키 범위. 상태 키는 전역이라 이 범위가 없으면
     *   다른 프로젝트의 이슈까지 함께 옮겨진다.
     */
    data class StatusMigration(
        val mappings: Map<String, String>,
        val projectKeys: Set<String>,
    ) : BulkOperationPayload()
}
