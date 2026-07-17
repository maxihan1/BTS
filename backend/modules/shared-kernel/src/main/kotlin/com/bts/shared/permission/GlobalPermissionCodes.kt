// 전역(프로젝트 비종속) 권한코드 상수 모음 — issue-tracking 게이트(T7)가 import (FR-PJ-01)

package com.bts.shared.permission

/**
 * 프로젝트에 종속되지 않는 시스템 전역 권한코드 상수 모음.
 *
 * [CustomFieldPermission], [TemplatePermission] 등 기존 권한 enum 은 프로젝트 스코프 권한을
 * `toPermissionCode()` 인스턴스 메서드로 노출하지만, 여기 상수는 프로젝트 컨텍스트가 없는
 * 전역 권한(예: 프로젝트 생성)이라 enum 없이 top-level 상수로 노출한다.
 *
 * **identity-access 의 기존 `ALLOWED_GLOBAL_PERMISSIONS` 는 이 PR 범위 밖** — 별도 후속 TODO.
 * 이 object 는 issue-tracking 이 [CREATE_PROJECT] 를 import 하는 창구만 제공한다.
 */
object GlobalPermissionCodes {
    /** 프로젝트 생성 권한코드 — role_permissions 시드와 정합해야 한다 (FR-PJ-01). */
    const val CREATE_PROJECT = "CREATE_PROJECT"
}
