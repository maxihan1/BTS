// 비밀번호 변경 엔드포인트 — FR-AU-05 Task 3 (POST /api/v1/users/me/password)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.credential.ChangePasswordResult
import com.atlas.bts.identity.credential.ChangePasswordService
import com.atlas.bts.identity.web.dto.ChangePasswordRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 비밀번호 변경 컨트롤러 (FR-AU-05 Task 3).
// SecurityConfig 의 /api 인증 필터로 미인증 요청을 401 반환.
// inline ResponseEntity 에러 응답 — identity-access 에 RestControllerAdvice 없음.
// 응답 본문에 비밀번호/해시 절대 미포함 (DEVELOPMENT.md 보안 규칙).
@RestController
class PasswordController(
    private val changePasswordService: ChangePasswordService,
) {

    // POST /api/v1/users/me/password: JWT subject -> userId, sid claim -> currentSid.
    // CharArray 변환 후 ChangePasswordService.change 위임. wipe 는 서비스 내부 finally 처리.
    // 반환: 200 changed=true / 400 에러코드+메시지 / 401 미인증(필터 체인)
    @PostMapping("/api/v1/users/me/password")
    fun changePassword(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody req: ChangePasswordRequest,
    ): ResponseEntity<*> {
        val userId = UUID.fromString(jwt.subject)
        val currentSid = UUID.fromString(jwt.getClaimAsString("sid"))

        return when (
            val result = changePasswordService.change(
                userId = userId,
                currentSid = currentSid,
                current = req.currentPassword.toCharArray(),
                new = req.newPassword.toCharArray(),
            )
        ) {
            is ChangePasswordResult.Success ->
                ResponseEntity.ok(mapOf("changed" to true))

            is ChangePasswordResult.PolicyViolation ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    mapOf(
                        "code" to "POLICY_VIOLATION",
                        "violations" to result.violations.map { it.name },
                        "message" to "비밀번호가 정책을 위반합니다.",
                    ),
                )

            is ChangePasswordResult.CurrentMismatch ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    mapOf(
                        "code" to "CURRENT_PASSWORD_MISMATCH",
                        "message" to "현재 비밀번호가 일치하지 않습니다.",
                    ),
                )

            is ChangePasswordResult.SameAsCurrent ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    mapOf(
                        "code" to "SAME_AS_CURRENT",
                        "message" to "새 비밀번호가 현재 비밀번호와 같습니다.",
                    ),
                )
        }
    }
}
