// whoami 엔드포인트 응답 DTO — JWT 클레임에서 추출한 사용자 식별 정보

package com.atlas.bts.identity.dto

import org.springframework.security.oauth2.jwt.Jwt

data class WhoamiResponse(
    val username: String,
    val email: String,
)

fun Jwt.toWhoami(): WhoamiResponse =
    WhoamiResponse(
        username = getClaimAsString("preferred_username") ?: "",
        email = getClaimAsString("email") ?: "",
    )
