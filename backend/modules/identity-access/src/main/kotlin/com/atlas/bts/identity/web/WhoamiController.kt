// JWT 및 PAT Bearer 토큰으로 현재 인증된 사용자 정보를 반환하는 whoami 엔드포인트

package com.atlas.bts.identity.web

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.dto.WhoamiResponse
import com.atlas.bts.identity.dto.toWhoami
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class WhoamiController(
    private val personalAccessTokenService: PersonalAccessTokenService,
    private val authAuditLogService: AuthAuditLogService,
) {

    @GetMapping("/api/v1/users/me/whoami")
    fun whoami(
        request: HttpServletRequest,
        @AuthenticationPrincipal jwt: Jwt?,
    ): WhoamiResponse {
        val rawToken = extractBearerToken(request)

        // EC-26: "pat_" prefix 검사 — token body 시작 부분이 정확히 "pat_" 인지 확인
        if (rawToken != null && rawToken.startsWith(PersonalAccessToken.TOKEN_PREFIX)) {
            return handlePat(rawToken)
        }

        // JWT 흐름 — Spring Security 필터 체인이 이미 검증 완료
        if (jwt != null) {
            return jwt.toWhoami()
        }

        throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
    }

    private fun handlePat(rawToken: String): WhoamiResponse {
        val result = personalAccessTokenService.verify(rawToken)

        if (result.isFailure) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }

        val pat = result.getOrThrow()

        authAuditLogService.record(
            AuthAuditLog(
                userId = pat.userId,
                eventType = AuthEventType.PAT_USED,
                providerId = "pat",
            ),
        )

        return WhoamiResponse(
            username = "",
            email = "",
            authMethod = "pat",
            userId = pat.userId,
        )
    }

    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        return header.removePrefix("Bearer ")
    }
}
