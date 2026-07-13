// 규칙 충돌 종류 4종을 정의하는 enum (FR-AT-04)

package com.bts.automation.domain

/**
 * [RuleConflict]가 나타낼 수 있는 충돌 종류 4종.
 *
 * 전부 저장을 막지 않는 soft warning이다 — 어떤 값이든 [ConflictSeverity.WARNING]로만 리포트된다.
 *
 * - [CYCLE] — 규칙 A의 액션이 규칙 B의 트리거를 유발하고 B가 다시 A를 유발하는 정적 루프
 * - [FIELD_CONFLICT] — 같은 트리거에 동시 매칭 가능한 규칙들이 같은 필드를 서로 다른 값으로 SET
 * - [PRIORITY_AMBIGUITY] — 같은 트리거에 복수 규칙이 매칭되는데 실행 순서가 정해지지 않음
 * - [PERMISSION_MISSING] — 규칙의 실행 주체(rule actor)가 액션 대상 권한을 보유하지 않음
 */
enum class ConflictType {
    CYCLE,
    FIELD_CONFLICT,
    PRIORITY_AMBIGUITY,
    PERMISSION_MISSING,
}
