// 워크플로우 스킴 권한 enum — ADR project-scheme-mapping-jira-align 권한 분리 정당화 참조

package com.bts.workflow.scheme.port.outbound

/**
 * 워크플로우 스킴 도메인에서 검증하는 권한 목록.
 *
 * [WorkflowSchemePermissionResolver] 가 이 enum 을 인자로 받아 권한 판정을 수행한다.
 *
 * ## ADR 참조
 * `docs/adr/project-scheme-mapping-jira-align` — 스킴 권한을 이슈 권한(IssuePermission)에서
 * 분리한 이유: 스킴 관리는 시스템 어드민/프로젝트 어드민 레벨이며,
 * 이슈 CRUD 권한과 생명주기·책임 범위가 다르다.
 *
 * | 권한 | 설명 |
 * |------|------|
 * | [MANAGE_SCHEME] | 스킴 생성·수정·삭제 |
 * | [ASSIGN_SCHEME] | 프로젝트에 스킴 배정 |
 */
enum class WorkflowSchemePermission {
    /**
     * 워크플로우 스킴 생성·수정·삭제 권한.
     *
     * 시스템 어드민 또는 스킴 관리 전용 역할에 부여한다.
     * 표준 스킴(is_default=true)의 핵심 필드 변경에도 이 권한이 요구된다.
     */
    MANAGE_SCHEME,

    /**
     * 프로젝트에 워크플로우 스킴을 배정하는 권한.
     *
     * 프로젝트 어드민 레벨이 보유한다.
     * MANAGE_SCHEME 없이도 이 권한만으로 스킴 배정이 가능하다 (최소 권한 원칙).
     */
    ASSIGN_SCHEME,
}
