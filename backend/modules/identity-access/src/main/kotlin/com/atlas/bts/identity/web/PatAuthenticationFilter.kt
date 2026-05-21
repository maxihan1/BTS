// PAT Bearer 토큰을 Spring SecurityContext에 설정하는 필터 — JWT 필터 이전에 실행 (Task 24)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * PAT Bearer 토큰 인증 필터 (FR-AU-09 Task 24).
 *
 * `Authorization: Bearer pat_xxx` 형식의 요청을 감지하여 [PersonalAccessTokenService.verify] 로 검증하고,
 * 검증 성공 시 [SecurityContextHolder] 에 인증 정보를 설정한다.
 *
 * Spring Security JWT 필터보다 먼저 실행되어야 한다. SecurityConfig 에서
 * `addFilterBefore(patAuthenticationFilter, BearerTokenAuthenticationFilter::class.java)` 로 등록.
 *
 * ## 보안 정책
 * - EC-26: token 이 "pat_" prefix 로 시작하는 경우에만 이 필터가 처리한다.
 * - PAT 검증 실패(만료·revoke·미존재) 시 SecurityContext 를 설정하지 않고 필터 체인을 계속 진행한다.
 *   이후 JWT 필터 또는 인가 체크에서 401 이 반환된다.
 * - 검증 실패 원인은 외부에 노출하지 않는다 (내부 로깅 한정).
 */
class PatAuthenticationFilter(
    private val personalAccessTokenService: PersonalAccessTokenService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawToken = extractPatToken(request)

        if (rawToken != null) {
            val result = personalAccessTokenService.verify(rawToken)
            if (result.isSuccess) {
                val pat = result.getOrThrow()
                val auth = UsernamePasswordAuthenticationToken(
                    pat.userId.toString(),
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_PAT")),
                )
                SecurityContextHolder.getContext().authentication = auth
            }
        }

        filterChain.doFilter(request, response)
    }

    /**
     * `Authorization: Bearer pat_xxx` 헤더에서 PAT raw token 을 추출한다.
     *
     * EC-26: "pat_" prefix 가 있는 경우에만 반환. 그 외 (JWT 등) 는 null.
     */
    private fun extractPatToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        val token = header.removePrefix("Bearer ")
        return if (token.startsWith(PersonalAccessToken.TOKEN_PREFIX)) token else null
    }
}
