// 버전 권한 enum(공용) — issue-tracking이 묻고, prod 판정은 FR-PM-03 이연.

package com.bts.shared.permission

/**
 * 버전(프로젝트 하위 릴리스 단위) 도메인에서 검증하는 권한 목록.
 *
 * [VersionPermissionResolver] 가 이 enum 을 인자로 받아 권한 판정을 수행한다.
 *
 * READ 는 게이트하지 않는다(Jira 동일 정책). 프로젝트 조회 권한이 있으면
 * 해당 프로젝트의 버전 목록도 함께 조회되므로 별도 권한을 두지 않는다.
 *
 * | 권한 | 검증 엔드포인트 |
 * |------|----------------|
 * | [CREATE] | `POST /api/v1/projects/{key}/versions` |
 * | [UPDATE] | `PATCH /api/v1/projects/{key}/versions/{id}` |
 * | [DELETE] | `DELETE /api/v1/projects/{key}/versions/{id}` |
 *
 * @see VersionPermissionResolver
 */
enum class VersionPermission {
    /** 버전 생성 권한. */
    CREATE,

    /** 버전 수정 권한(name/description/releaseDate/released 등). */
    UPDATE,

    /** 버전 삭제 권한. */
    DELETE,
}
