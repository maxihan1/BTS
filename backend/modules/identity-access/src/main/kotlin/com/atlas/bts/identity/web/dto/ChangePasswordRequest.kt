// 비밀번호 변경 요청 DTO — currentPassword / newPassword 필수 검증 (FR-AU-05)

package com.atlas.bts.identity.web.dto

import jakarta.validation.constraints.NotBlank

/**
 * POST /api/v1/users/me/password 요청 body (FR-AU-05 Task 3).
 *
 * @param currentPassword 현재 비밀번호 평문. 빈 값 허용 안 함.
 * @param newPassword     새 비밀번호 평문. 빈 값 허용 안 함.
 *                        비즈니스 정책 검증([com.atlas.bts.identity.credential.PasswordPolicy])은
 *                        [com.atlas.bts.identity.credential.ChangePasswordService] 레이어에서 수행한다.
 */
data class ChangePasswordRequest(
    @field:NotBlank
    val currentPassword: String,
    @field:NotBlank
    val newPassword: String,
)
