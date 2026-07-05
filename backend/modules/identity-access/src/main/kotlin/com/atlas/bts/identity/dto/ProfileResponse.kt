// 사용자 프로필 조회 응답 DTO — 프로필 필드 + 파생 avatarUrl (FR-PR-01 Task 5)

package com.atlas.bts.identity.dto

import java.util.UUID

/**
 * GET/PATCH `/api/v1/users/me/profile` 응답.
 *
 * [UserProfileService][com.atlas.bts.identity.profile.UserProfileService] 는 `avatarObjectKey` 원본만
 * 노출하고, 다운로드 경로 조립(avatarUrl 파생)은
 * [UserProfileController][com.atlas.bts.identity.web.UserProfileController] 책임이다.
 *
 * @property userId 사용자 식별자.
 * @property username 사용자명.
 * @property email 이메일(없으면 null).
 * @property displayName 표시 이름.
 * @property avatarUrl 아바타 다운로드 경로(`/api/v1/users/{userId}/avatar`). 아바타 미설정 시 null.
 * @property timezone IANA 타임존(기본 "UTC").
 * @property department 부서(없으면 null).
 */
data class ProfileResponse(
    val userId: UUID,
    val username: String,
    val email: String?,
    val displayName: String,
    val avatarUrl: String?,
    val timezone: String,
    val department: String?,
)
