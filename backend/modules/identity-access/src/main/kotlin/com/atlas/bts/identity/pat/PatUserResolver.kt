// pat_ raw token 을 BTS 사용자 id 로 바꾸는 단일 진입점 — BTS DB 먼저, 없으면 Auth_platform (FR-API-04)

package com.atlas.bts.identity.pat

import java.util.UUID

/**
 * PAT 인증 필터와 header 를 직접 읽는 컨트롤러들이 모두 이 함수 하나로 PAT 주인을 찾는다.
 *
 * 기존 BTS PAT(`personal_access_tokens`)는 만료까지 살아 있으므로 먼저 본다. 없으면 Auth_platform PAT 로 본다.
 * 빈 등록은 [com.atlas.bts.identity.config.SecurityConfig] 가 한다 — Auth 확인기 빈이 없는 컨텍스트(WebMvcTest
 * 슬라이스)에서는 BTS DB 만 본다.
 */
class PatUserResolver(
    private val personalAccessTokenService: PersonalAccessTokenService,
    private val authPlatformPatVerifier: AuthPlatformPatVerifier?,
) {
    /**
     * @param raw `pat_` 로 시작하는 raw token
     * @return PAT 주인인 BTS 사용자 id. 어느 쪽에서도 확인되지 않으면 null
     */
    fun resolvePatUserId(raw: String): UUID? =
        personalAccessTokenService.verify(raw).getOrNull()?.userId
            ?: authPlatformPatVerifier?.resolveUserId(raw)
}
