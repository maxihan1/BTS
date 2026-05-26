// 워크플로우 스킴 권한 평가 범위 sealed interface — BC 격리 원칙에 따라 이 BC 전용으로 정의

package com.bts.workflow.scheme.port.outbound

/**
 * 워크플로우 스킴 권한 평가의 적용 범위.
 *
 * project-workflow BC 의 기존 [com.bts.workflow.port.outbound.Scope] 와 동일한 패턴이나,
 * 스킴 권한은 이슈 전이 권한과 분리된 독립 포트를 가진다.
 * ADR `project-scheme-mapping-jira-align` 참조.
 *
 * when 식에서 else 분기 없이 컴파일러가 완전성(exhaustiveness)을 보장한다.
 *
 * ## 구현체 설명
 * - [Global] — 시스템 수준 권한. 예. 스킴 목록 조회, 스킴 생성.
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
