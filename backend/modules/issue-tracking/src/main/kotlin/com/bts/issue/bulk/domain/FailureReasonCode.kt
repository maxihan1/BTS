// 일괄 작업 항목 실패 사유 코드 enum — 각 이슈 처리 실패의 원인을 분류한다

package com.bts.issue.bulk.domain

/**
 * [BulkOperationItem] 처리 실패 시 원인을 나타내는 코드.
 *
 * - [NOT_FOUND]: 대상 이슈를 찾을 수 없다.
 * - [FORBIDDEN]: 요청자에게 해당 이슈 수정 권한이 없다.
 * - [TRANSITION_NOT_ALLOWED]: 현재 상태에서 요청한 전이가 허용되지 않는다.
 * - [VERSION_CONFLICT]: 낙관적 잠금 충돌 — 다른 요청이 먼저 수정했다.
 * - [WORKFLOW_NOT_CONFIGURED]: 이슈에 워크플로우가 구성되어 있지 않다.
 * - [TYPE_NOT_FOUND]: 지정한 이슈 유형을 찾을 수 없다.
 */
enum class FailureReasonCode {
    NOT_FOUND,
    FORBIDDEN,
    TRANSITION_NOT_ALLOWED,
    VERSION_CONFLICT,
    WORKFLOW_NOT_CONFIGURED,
    TYPE_NOT_FOUND,
}
