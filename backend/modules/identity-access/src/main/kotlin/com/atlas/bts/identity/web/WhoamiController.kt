// JWT 클레임에서 현재 인증된 사용자 정보를 반환하는 whoami 엔드포인트

package com.atlas.bts.identity.web

import com.atlas.bts.identity.dto.WhoamiResponse
import com.atlas.bts.identity.dto.toWhoami
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class WhoamiController {
    @GetMapping("/api/v1/users/me/whoami")
    fun whoami(
        @AuthenticationPrincipal jwt: Jwt,
    ): WhoamiResponse = jwt.toWhoami()
}
