// 일괄 작업 접수 커맨드 DTO — BULK_EDIT/BULK_TRANSITION 공통 요청 구조

package com.bts.issue.bulk.application

import com.bts.issue.bulk.domain.BulkOperationType
import java.util.UUID

/**
 * 일괄 작업 접수 요청 커맨드 DTO.
 *
 * 컨트롤러(PR2)에서 변환되어 [BulkOperationApplicationService.submit] 에 전달된다.
 *
 * ### 페이로드 규칙
 * - [BulkOperationType.BULK_EDIT]: [editPayload] non-null 필수. [transitionPayload] null.
 * - [BulkOperationType.BULK_TRANSITION]: [transitionPayload] non-null 필수. [editPayload] null.
 * operationType ↔ payload 불일치 시 [BulkOperationApplicationService.submit] 에서 [IllegalArgumentException].
 *
 * @property operationType 작업 유형.
 * @property issueKeys 처리 대상 이슈 키 목록. 1개 이상, 1000개 이하. 중복은 접수 시 dedup 처리된다.
 * @property editPayload BULK_EDIT 전용 페이로드. operationType=BULK_EDIT 일 때만 non-null.
 * @property transitionPayload BULK_TRANSITION 전용 페이로드. operationType=BULK_TRANSITION 일 때만 non-null.
 */
data class BulkUpdateRequest(
    val operationType: BulkOperationType,
    val issueKeys: List<String>,
    val editPayload: BulkEditPayload?,
    val transitionPayload: BulkTransitionPayload?,
)

/**
 * BULK_EDIT 작업의 페이로드.
 *
 * 각 필드는 null=무변경 (merge-patch 시맨틱).
 * non-null 인 경우 범위 검증이 [BulkOperationApplicationService] 에서 수행된다.
 *
 * @property priority 변경할 우선순위. non-null 이면 1..5 범위.
 * @property impact 변경할 영향도. non-null 이면 1..3 범위.
 */
data class BulkEditPayload(
    val priority: Int?,
    val impact: Int?,
)

/**
 * BULK_TRANSITION 작업의 페이로드.
 *
 * 일괄 전이 대상 전체에 동일한 resolutionId 를 적용한다.
 * null 이면 각 이슈의 resolution_id 를 clear (비DONE 전이 시맨틱과 동일).
 *
 * @property toStateKey 전이할 대상 상태 키. 비어 있으면 [BulkOperationApplicationService] 에서 거부.
 * @property resolutionId DONE 상태로 전이할 때 지정하는 해결책 UUID.
 *   null 이면 resolution_id clear. 전체 일괄에 동일하게 적용된다.
 */
data class BulkTransitionPayload(
    val toStateKey: String,
    val resolutionId: UUID? = null,
)
