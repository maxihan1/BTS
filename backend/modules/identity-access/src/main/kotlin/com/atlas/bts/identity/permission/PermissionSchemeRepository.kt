// 권한 스킴 조회 포트 — 유효 스킴 해석 + 역할 권한 보유 판정

package com.atlas.bts.identity.permission

import java.util.UUID

/**
 * 권한 스킴 조회 인터페이스 (FR-PM-02 Task 3).
 *
 * 구현체: [JdbcPermissionSchemeRepository].
 */
interface PermissionSchemeRepository {
    /**
     * 프로젝트의 유효 스킴에서 지정된 role이 permissionCode를 보유하는지 판정한다.
     *
     * ## 유효 스킴 fallback 규칙
     * 1. `project_permission_scheme` 에 projectId 매핑이 있으면 해당 scheme_id 를 사용한다.
     * 2. 매핑이 없으면 `permission_schemes.is_default = TRUE` 인 기본 스킴을 사용한다.
     *
     * @param projectId 판정 대상 프로젝트 UUID
     * @param role      역할 문자열 (예: "PROJECT_ADMIN", "MEMBER")
     * @param permissionCode 권한 코드 (예: "CREATE_ISSUE", "EDIT_ISSUE", "DELETE_ISSUE")
     * @return 유효 스킴의 role_permissions 행이 존재하면 true, 아니면 false
     */
    fun roleHasPermission(
        projectId: UUID,
        role: String,
        permissionCode: String,
    ): Boolean
}
