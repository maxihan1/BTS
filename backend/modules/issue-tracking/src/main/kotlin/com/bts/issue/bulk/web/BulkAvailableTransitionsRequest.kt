// POST /api/v1/issues/bulk-transitions/available 요청 DTO

package com.bts.issue.bulk.web

/**
 * 일괄 가용 전환 조회 HTTP 요청 바디 DTO.
 *
 * 검증은 컨트롤러 진입부 [IllegalArgumentException] → [BulkOperationExceptionHandler.handleIllegalArgument] 경로로 처리한다.
 * @Valid / @Size 같은 Bean Validation 어노테이션을 사용하지 않는다.
 *
 * @property issueKeys 가용 전환을 조회할 이슈 키 목록.
 */
data class BulkAvailableTransitionsRequest(
    val issueKeys: List<String>,
)
