// 특정 프로젝트에 대한 현재 사용자의 권한 맵을 담는 응답 DTO

package com.atlas.bts.identity.web.dto

/**
 * `GET /api/v1/users/me/project-permissions` 응답 DTO (FR-PM-02 CREATE 게이트).
 *
 * @param projectKey 권한 조회 대상 프로젝트 키. 예. "ATLAS".
 * @param permissions 권한 이름 → 보유 여부 맵. 예. `{"CREATE": true}`.
 *
 * @see docs/decisions/2026-06-02-issue-permission-query-api.md
 */
data class ProjectPermissionsResponse(
    val projectKey: String,
    val permissions: Map<String, Boolean>,
)
