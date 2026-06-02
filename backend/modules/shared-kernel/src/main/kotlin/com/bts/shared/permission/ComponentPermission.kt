// 컴포넌트 권한 enum(공용) — issue-tracking이 묻고, prod 판정은 FR-PM-03 이연.

package com.bts.shared.permission

/**
 * 컴포넌트(프로젝트 하위 분류 단위) 도메인에서 검증하는 권한 목록.
 *
 * [ComponentPermissionResolver] 가 이 enum 을 인자로 받아 권한 판정을 수행한다.
 *
 * READ 는 게이트하지 않는다(Jira 동일 정책). 프로젝트 조회 권한이 있으면
 * 해당 프로젝트의 컴포넌트 목록도 함께 조회되므로 별도 권한을 두지 않는다.
 *
 * | 권한 | 검증 엔드포인트 |
 * |------|----------------|
 * | [CREATE] | `POST /api/v1/projects/{key}/components` |
 * | [UPDATE] | `PATCH /api/v1/projects/{key}/components/{id}` |
 * | [DELETE] | `DELETE /api/v1/projects/{key}/components/{id}` |
 *
 * @see ComponentPermissionResolver
 */
enum class ComponentPermission {
    /** 컴포넌트 생성 권한. */
    CREATE,

    /** 컴포넌트 수정 권한(name/description/lead 등). */
    UPDATE,

    /** 컴포넌트 삭제 권한. */
    DELETE,
}
