// 워크플로우 스킴 권한 enum(공용) — project-workflow가 묻고, prod 판정은 identity-access(FR-PM-04).

package com.bts.shared.permission

/**
 * 워크플로우 스킴 도메인에서 검증하는 권한 목록.
 *
 * [WorkflowSchemePermissionResolver] 가 이 enum 을 인자로 받아 권한 판정을 수행한다.
 *
 * ## 배치 (BC 격리)
 * FR-PM-04 D2 — project-workflow → shared-kernel(`com.bts.shared.permission`)로 이동했다.
 * identity-access 가 prod adapter 를 제공해야 하나 BC 격리 ArchUnit 룰상 project-workflow 를
 * 의존할 수 없으므로, 계약 enum 을 공용 패키지에 둔다(FR-PM-03 ComponentPermission 동형).
 *
 * | 권한 | 설명 |
 * |------|------|
 * | [MANAGE_SCHEME] | 스킴 생성·수정·삭제 (전역 자원, 시스템 관리자) |
 * | [ASSIGN_SCHEME] | 프로젝트에 스킴 배정 (프로젝트 관리자) |
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
