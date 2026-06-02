// POST /api/v1/issues/bulk-transitions/available 응답 DTO

package com.bts.issue.bulk.web

import com.bts.issue.adapter.inbound.rest.TransitionItem
import com.bts.issue.bulk.application.BulkAvailableTransitionsResult

/**
 * 일괄 가용 전이 조회 HTTP 응답 DTO.
 *
 * `data.transitions` 는 모든 대상 이슈에 공통으로 존재하는 전이 교집합이다.
 * `data.unresolvedIssueKeys` 는 미존재·워크플로우 미설정·접근 불가 이유로 조회에 실패한 이슈 키 목록이다.
 *
 * @property transitions 교집합 가용 전이 목록.
 * @property unresolvedIssueKeys 조회 실패 이슈 키 목록.
 */
data class BulkAvailableTransitionsResponse(
    val transitions: List<TransitionItem>,
    val unresolvedIssueKeys: List<String>,
) {
    companion object {
        /**
         * [BulkAvailableTransitionsResult] 를 [BulkAvailableTransitionsResponse] 로 변환한다.
         *
         * @param result 서비스 계층의 교집합 계산 결과.
         * @return REST 응답 DTO.
         */
        fun from(result: BulkAvailableTransitionsResult): BulkAvailableTransitionsResponse =
            BulkAvailableTransitionsResponse(
                transitions = result.transitions.map { TransitionItem.from(it) },
                unresolvedIssueKeys = result.unresolvedIssueKeys,
            )
    }
}
