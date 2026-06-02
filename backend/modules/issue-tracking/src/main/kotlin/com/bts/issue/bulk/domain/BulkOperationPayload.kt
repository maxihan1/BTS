// 일괄 작업 payload sealed class — BULK_EDIT(Edit) / BULK_TRANSITION(Transition) 타입별 파라미터를 안전하게 표현

package com.bts.issue.bulk.domain

/**
 * 일괄 작업의 타입별 파라미터를 표현하는 sealed class.
 *
 * - [Edit]: BULK_EDIT 작업의 필드 변경 파라미터.
 * - [Transition]: BULK_TRANSITION 작업의 전이 대상 상태 키.
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
     * @property toStateKey 전이할 대상 상태 키. 비어 있으면 안 된다.
     */
    data class Transition(
        val toStateKey: String,
    ) : BulkOperationPayload()
}
