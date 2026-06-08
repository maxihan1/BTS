// 관리자 로컬 계정 생성 요청 DTO — username/email/displayName 검증 (FR-AU-05 Task 4)

package com.atlas.bts.identity.web.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * POST /api/v1/users 요청 body — 관리자가 로컬 계정을 생성한다 (FR-AU-05 Task 4).
 *
 * ## 검증 규칙
 * - [username]: 빈 값 불가, 길이 3~255 (EC1), 영숫자·`.`·`_`·`-` 만 허용 (EC1).
 *   검증 실패는 Spring MVC 기본 핸들러가 400 Bad Request 로 변환한다.
 * - [email]: null 허용. 값이 있으면 RFC 이메일 형식이어야 한다 (EC2).
 * - [displayName]: 빈 값 불가.
 *
 * @property username    로그인 식별자 (UNIQUE). 영숫자·`.`·`_`·`-` 조합 3~255자.
 * @property email       이메일 (선택). null 또는 빈 값이면 이메일 미보유로 처리한다.
 * @property displayName 화면 표시 이름. 빈 값 허용 안 함.
 */
data class CreateUserRequest(
    @field:NotBlank
    @field:Size(min = USERNAME_MIN_LENGTH, max = USERNAME_MAX_LENGTH)
    @field:Pattern(regexp = USERNAME_PATTERN)
    val username: String,
    @field:Email
    val email: String?,
    @field:NotBlank
    val displayName: String,
) {
    companion object {
        /** username 최소 길이 (EC1) */
        const val USERNAME_MIN_LENGTH = 3

        /** username 최대 길이 (EC1) — users.username 컬럼 상한과 정합 */
        const val USERNAME_MAX_LENGTH = 255

        /** username 허용 문자 — 영숫자·`.`·`_`·`-` (EC1) */
        const val USERNAME_PATTERN = "^[A-Za-z0-9._-]+$"
    }
}
