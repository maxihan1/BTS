// 관리자 로컬 계정 생성 응답 DTO — id/username/임시 비밀번호 1회 반환 (FR-AU-05 Task 4)

package com.atlas.bts.identity.web.dto

import java.util.UUID

/**
 * POST /api/v1/users 성공 응답 body (FR-AU-05 Task 4).
 *
 * ## 임시 비밀번호 1회 노출 (CONCERN-3 / DEVELOPMENT.md §1.1)
 * [temporaryPassword] 는 생성 직후 **이 응답에서 한 번만** 평문으로 반환한다. 저장은 해시(must_change=true)로
 * 이루어지므로 이후 재조회는 불가능하다. 컨트롤러가 [com.atlas.bts.identity.credential.CreatedAccount]
 * 의 CharArray 를 직렬화 직전 단 한 번 `String(charArray)` 로 변환해 이 필드를 채운다.
 * 평문 비밀번호는 로그에 출력하지 않는다.
 *
 * @property id                생성된 사용자 식별자 (UUID).
 * @property username          로그인 식별자.
 * @property temporaryPassword 최초 로그인용 임시 비밀번호 평문 (1회 노출).
 */
data class CreateUserResponse(
    val id: UUID,
    val username: String,
    val temporaryPassword: String,
)
