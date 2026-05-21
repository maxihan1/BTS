// whoami 엔드포인트 응답 DTO — JWT/PAT 인증 사용자 식별 정보 + authMethod 구분 필드

package com.atlas.bts.identity.dto

import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

data class WhoamiResponse(
    val username: String,
    val email: String,
    val authMethod: String,
    val userId: UUID? = null,
)

fun Jwt.toWhoami(): WhoamiResponse =
    WhoamiResponse(
        username = getClaimAsString("preferred_username") ?: "",
        email = getClaimAsString("email") ?: "",
        authMethod = "jwt",
    )
