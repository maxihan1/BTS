// 규칙 충돌의 심각도를 정의하는 enum (FR-AT-04)

package com.bts.automation.domain

/**
 * [RuleConflict]의 심각도.
 *
 * 현재는 [WARNING] 단일 값만 존재한다 — FR-AT-04 범위의 충돌 4종은 전부 저장을 막지 않는
 * soft warning이기 때문이다. 향후 저장을 차단하는 hard 충돌이 도입되면 이 enum에 값을 추가한다.
 */
enum class ConflictSeverity {
    WARNING,
}
