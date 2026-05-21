// 권한 평가 범위 — Global / Project(key) / Issue(key)

package com.bts.workflow.port.outbound

/**
 * 권한 평가의 적용 범위를 나타내는 sealed interface.
 *
 * 세 가지 구현체로 범위를 완전히 열거(exhaustive)한다.
 * when 식에서 else 분기 없이 컴파일러가 완전성을 보장한다.
 *
 * - [Global] — 시스템 전역 수준 권한 (예. 관리자 기능).
 * - [Project] — 특정 프로젝트 키 수준 권한 (예. 이슈 생성).
 * - [Issue] — 특정 이슈 키 수준 권한 (예. 이슈 삭제).
 *
 * ## 사용 예
 * ```kotlin
 * val label = when (scope) {
 *     is Scope.Global          -> "전역"
 *     is Scope.Project         -> "프로젝트(${scope.key})"
 *     is Scope.Issue           -> "이슈(${scope.key})"
 * }
 * ```
 *
 * ## identity-access 연결
 * [PermissionResolver] 의 구현체 `IdentityAccessPermissionResolver` 는 이 sealed 타입을
 * identity-access BC 의 권한 판정 API 호출 매개변수로 변환한다.
 * 연결 시점. identity-access PR #8 머지 후 별도 PR.
 */
sealed interface Scope {
    /**
     * 시스템 전역 권한 범위.
     *
     * 프로젝트나 이슈에 국한되지 않는 시스템 수준 작업(예. 전체 관리자 기능)에 사용한다.
     */
    data object Global : Scope

    /**
     * 특정 프로젝트 권한 범위.
     *
     * @param key 프로젝트 식별 키 (예. "ATLAS").
     */
    data class Project(val key: String) : Scope

    /**
     * 특정 이슈 권한 범위.
     *
     * @param key 이슈 식별 키 (예. "ATLAS-123").
     */
    data class Issue(val key: String) : Scope
}
