// 이슈 템플릿 관리 권한 enum(공용) — issue-tracking이 묻고, prod 판정은 FR-TM-01 T8에서 identity가 채운다.

package com.bts.shared.permission

/**
 * 이슈 본문 템플릿(프로젝트+타입별 기본 본문) 관리 도메인에서 검증하는 권한 목록.
 *
 * [TemplatePermissionResolver] 가 이 enum 을 인자로 받아 권한 판정을 수행한다.
 * [CustomFieldPermission] 과 동형이며, 세 항목 모두 단일 권한코드 `MANAGE_TEMPLATES` 로
 * 매핑된다(템플릿 CRUD 는 별도 세분화 없이 하나의 관리 권한으로 게이트한다).
 *
 * READ 는 게이트하지 않는다(커스텀 필드 동일 정책). 프로젝트 조회 권한이 있으면
 * 해당 프로젝트의 이슈 템플릿 목록·resolve 도 함께 조회되므로 별도 권한을 두지 않는다.
 *
 * | 권한 | 검증 엔드포인트 |
 * |------|----------------|
 * | [CREATE] | `POST /api/v1/projects/{key}/issue-templates` |
 * | [UPDATE] | `PATCH /api/v1/projects/{key}/issue-templates/{id}` |
 * | [DELETE] | `DELETE /api/v1/projects/{key}/issue-templates/{id}` |
 *
 * @see TemplatePermissionResolver
 * @see CustomFieldPermission
 */
enum class TemplatePermission {
    /** 이슈 템플릿 생성 권한. */
    CREATE,

    /** 이슈 템플릿 수정 권한(name/content 등). */
    UPDATE,

    /** 이슈 템플릿 삭제(소프트 삭제) 권한. */
    DELETE,
    ;

    /**
     * 이 권한이 매핑되는 role_permissions 권한코드를 반환한다.
     *
     * 세 항목(CREATE/UPDATE/DELETE) 모두 단일 권한코드 [MANAGE_TEMPLATES] 로 매핑된다.
     * prod resolver(identity-access)는 이 코드로 role_permissions 를 조회해 판정한다.
     *
     * @return 권한코드 문자열 `"MANAGE_TEMPLATES"`.
     */
    fun toPermissionCode(): String = MANAGE_TEMPLATES

    companion object {
        /** 이슈 템플릿 관리 권한코드 — role_permissions 시드(FR-TM-01 T8)와 일치해야 한다. */
        const val MANAGE_TEMPLATES = "MANAGE_TEMPLATES"
    }
}
