// 아바타 업로드 응답 DTO — 저장된 아바타 다운로드 URL (FR-PR-01 Task 5)

package com.atlas.bts.identity.dto

/**
 * POST `/api/v1/users/me/profile/avatar` 응답.
 *
 * @property avatarUrl 업로드된 아바타 다운로드 경로(`/api/v1/users/{userId}/avatar`).
 */
data class AvatarUploadResponse(
    val avatarUrl: String,
)
