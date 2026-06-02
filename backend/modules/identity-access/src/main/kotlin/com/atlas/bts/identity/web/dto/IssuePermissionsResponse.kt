// 특정 이슈에 대한 현재 사용자의 권한 맵을 담는 응답 DTO

package com.atlas.bts.identity.web.dto

/**
 * `GET /api/v1/users/me/issue-permissions` 응답 DTO (FR-PM-02).
 *
 * @param issueKey 권한 조회 대상 이슈 키. 예. "ATLAS-1".
 * @param permissions 권한 이름 → 보유 여부 맵. 예. `{"UPDATE": true, "SOFT_DELETE": false, "TRANSITION": true}`.
 *
 * @see docs/decisions/2026-06-02-issue-permission-query-api.md
 */
data class IssuePermissionsResponse(
    val issueKey: String,
    val permissions: Map<String, Boolean>,
)
