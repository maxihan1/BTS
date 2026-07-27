// 권한 스킴 조회 포트 — 유효 스킴 해석 + 역할 권한 보유 판정

package com.atlas.bts.identity.permission

import java.util.UUID

/**
 * 권한 스킴 조회 인터페이스 (FR-PM-02 Task 3).
 *
 * 구현체: [JdbcPermissionSchemeRepository].
 *
 * ## 현재 스킴은 기본 스킴 하나뿐이다 (2026-07-27 실측)
 * `role_permissions` 시드 마이그레이션 9종(V008 · V009 · V013 · V014 · V016 · V017 · V018 · V024 ·
 * V035)은 scheme_id 리터럴 18개가 **전부 기본 스킴 UUID `...0001`** 이고, 프로덕션 코드에는
 * `permission_schemes` / `project_permission_scheme` 에 INSERT/UPDATE 하는 경로가 **하나도 없다**
 * (쓰기는 마이그레이션과 테스트 픽스처뿐, 프론트에도 스킴 관리 화면이 없다).
 *
 * 그래서 "`MANAGE_WORKFLOW`(V013)가 기본 스킴에만 시드돼 비-기본 스킴 프로젝트의 관리자는 워크플로우
 * 스킴을 배정할 수 없다"는 지적은 **현재 도달 불가**다 — 비-기본 스킴 자체를 만들 수 없다. 지금
 * 보정 마이그레이션을 넣어도 대상 행이 0건인 dead 시드가 된다. 이 전제는
 * `PermissionSchemaMigrationTest`의 `권한 스킴은 기본 스킴 하나뿐이다` 단언이 지킨다.
 *
 * **두 번째 스킴을 만드는 순간** 그 단언이 깨지고 결함이 실재하게 된다. 그때는 위 9종 시드를 새
 * 스킴에도 적용하는 마이그레이션을 함께 넣어야 한다(안 그러면 새 스킴 프로젝트의 PROJECT_ADMIN 이
 * MANAGE_WORKFLOW 만이 아니라 권한을 통째로 잃는다).
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
