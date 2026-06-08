// 커스텀 필드 정의 관리 권한 enum(공용) — issue-tracking이 묻고, prod 판정은 FR-IS-10 T6에서 identity가 채운다.

package com.bts.shared.permission

/**
 * 커스텀 필드 정의(프로젝트별 사용자 정의 이슈 필드) 관리 도메인에서 검증하는 권한 목록.
 *
 * [CustomFieldPermissionResolver] 가 이 enum 을 인자로 받아 권한 판정을 수행한다.
 * [ComponentPermission] 과 동형이나, 세 항목 모두 단일 권한코드 `MANAGE_CUSTOM_FIELDS` 로
 * 매핑된다(필드 정의 CRUD 는 별도 세분화 없이 하나의 관리 권한으로 게이트한다).
 *
 * READ 는 게이트하지 않는다(컴포넌트 동일 정책). 프로젝트 조회 권한이 있으면
 * 해당 프로젝트의 커스텀 필드 정의 목록도 함께 조회되므로 별도 권한을 두지 않는다.
 *
 * | 권한 | 검증 엔드포인트 |
 * |------|----------------|
 * | [CREATE] | `POST /api/v1/projects/{key}/custom-fields` |
 * | [UPDATE] | `PATCH /api/v1/projects/{key}/custom-fields/{id}` |
 * | [DELETE] | `DELETE /api/v1/projects/{key}/custom-fields/{id}` |
 *
 * @see CustomFieldPermissionResolver
 * @see ComponentPermission
 */
enum class CustomFieldPermission {
    /** 커스텀 필드 정의 생성 권한. */
    CREATE,

    /** 커스텀 필드 정의 수정 권한(name/description/required/display_order 등). */
    UPDATE,

    /** 커스텀 필드 정의 삭제(소프트 삭제) 권한. */
    DELETE,
    ;

    /**
     * 이 권한이 매핑되는 role_permissions 권한코드를 반환한다.
     *
     * 세 항목(CREATE/UPDATE/DELETE) 모두 단일 권한코드 [MANAGE_CUSTOM_FIELDS] 로 매핑된다.
     * prod resolver(identity-access)는 이 코드로 role_permissions 를 조회해 판정한다.
     *
     * @return 권한코드 문자열 `"MANAGE_CUSTOM_FIELDS"`.
     */
    fun toPermissionCode(): String = MANAGE_CUSTOM_FIELDS

    companion object {
        /** 커스텀 필드 정의 관리 권한코드 — role_permissions 시드(FR-IS-10 T6)와 일치해야 한다. */
        const val MANAGE_CUSTOM_FIELDS = "MANAGE_CUSTOM_FIELDS"
    }
}
