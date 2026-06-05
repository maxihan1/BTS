// 워크플로우 스킴 권한 평가 범위 sealed interface(공용) — FR-PM-04 D2 project-workflow→shared-kernel 이동.

package com.bts.shared.permission

/**
 * 워크플로우 스킴 권한 평가의 적용 범위.
 *
 * when 식에서 else 분기 없이 컴파일러가 완전성(exhaustiveness)을 보장한다.
 *
 * ## 배치 (BC 격리)
 * FR-PM-04 D2 — project-workflow → shared-kernel 로 이동했다. [Project.key] 가 plain String
 * 이라 이동에 BC 의존이 없다(identity 도메인 타입 미참조).
 *
 * ## 구현체 설명
 * - [Global] — 시스템 수준 권한. 예. 스킴 생성·수정·삭제.
 * - [Project] — 프로젝트 단위 권한. 예. `ATLAS` 프로젝트에 스킴 배정.
 */
sealed interface WorkflowSchemeScope {
    /**
     * 시스템 전역 권한 범위.
     *
     * 프로젝트에 국한되지 않는 스킴 수준 작업(예. 스킴 생성·삭제)에 사용한다.
     * data object 이므로 참조 동일성이 보장된다.
     */
    data object Global : WorkflowSchemeScope

    /**
     * 특정 프로젝트 권한 범위.
     *
     * 프로젝트에 스킴을 배정(ASSIGN_SCHEME)하는 작업에 사용한다.
     *
     * @param key 프로젝트 식별 키. 예. "ATLAS".
     */
    data class Project(val key: String) : WorkflowSchemeScope
}
