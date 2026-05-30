// 비밀번호 변경 요청 DTO — currentPassword / newPassword 필수 검증 (FR-AU-05)

package com.atlas.bts.identity.web.dto

import jakarta.validation.constraints.NotBlank

/**
 * POST /api/v1/users/me/password 요청 body (FR-AU-05 Task 3).
 *
 * **String 타입 선택 이유와 한계.**
 * Jackson 역직렬화가 String 을 생성하므로 평문 String 의 heap 잔존은 수용된 한계(spec NFR).
 * 컨트롤러가 즉시 CharArray 로 변환 후 서비스에 위임하여 평문 보유 시간을 최소화한다.
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
