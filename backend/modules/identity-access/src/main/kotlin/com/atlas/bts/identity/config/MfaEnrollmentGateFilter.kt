// MFA 미등록 강제대상의 비-허용 경로 접근을 차단하는 게이트 필터 — JWT 클레임만 읽음(DB 0) (FR-MF-04 Task 7)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.jwt.JwtIssuer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.web.filter.OncePerRequestFilter

/**
 * MFA 등록 게이트 필터 (FR-MF-04 Task 7 / SDD §19.7.2 / ADR mfa-enforcement-policy §D3).
 *
 * 강제 대상(관리자·민감 프로젝트 멤버)이 MFA 를 아직 설정하지 않은 경우, access JWT 의
 * `mfa_enrollment_required=true` 클레임([JwtIssuer.CLAIM_MFA_ENROLLMENT_REQUIRED], 발급 chokepoint)이 박힌다.
 * 이 필터는 그 클레임이 true 인 요청을 [ALLOW_LIST] 외 모든 경로에서 **403** 으로 차단해 사용자가 MFA 등록을
 * 먼저 완료하도록 강제한다. 프론트만 게이팅하면 직접 API 호출로 우회 가능하므로 백엔드에서도 차단한다(보안 정합).
 *
 * ## 평가는 JWT 클레임만 — DB 조회 0
 * 매 요청 정책을 재계산하지 않고 발급 시점에 박힌 클레임만 읽는다(게이트와 whoami 가 단일 출처 일치).
 * `require_2fa` 변경은 다음 토큰 발급(refresh)까지 지연된다 — fail-safe(강제가 늦게 켜지지, 꺼지지 않음).
 *
 * ## 인증 후 동작 — 미인증은 기존 401 유지
 * 인증 필터(BearerTokenAuthenticationFilter·PatAuthenticationFilter) 뒤에 등록되어
 * principal 이 가용할 때 동작한다. 미인증(principal 부재)은 이 필터가 손대지 않고 통과시켜
 * Spring Security 의 기존 401 흐름을 유지한다(게이트가 401 을 403 으로 바꾸지 않는다).
 *
 * ## 통과 조건(게이트 미적용)
 * - principal 이 [Jwt] 가 아님 — PAT(non-Jwt) 인증은 클레임이 없으므로 통과(회귀 0).
 * - 클레임이 false 또는 부재 — 강제 대상이 아니거나 이미 설정 완료 → 통과(회귀 0).
 * - 요청 경로 ∈ [ALLOW_LIST] — MFA 등록/상태조회·whoami·logout·refresh 는 차단 예외.
 *
 * ## 영구 락 방지 — [ALLOW_LIST] 누락 위험
 * MFA 등록에 필요한 경로([com.atlas.bts.identity.web.MfaController] 의 /api/v1/auth/mfa 하위)나
 * 상태 확인(whoami)·세션 종료(logout)·토큰 갱신(refresh) 을 allow-list 에서 빠뜨리면, 강제 대상이
 * MFA 를 설정하려는 요청 자체가 차단돼 영구 락에 빠진다(이 사용자는 영영 로그인 후 아무것도 못 함).
 * allow-list 변경 시 [com.atlas.bts.identity.integration.MfaEnrollmentGateIntegrationTest] 의 경로별
 * 통과 테스트를 반드시 함께 갱신한다(누락 시 회귀 탐지).
 *
 * ## 응답
 * 차단 시 403 + `{"error":"mfa_enrollment_required"}` (에러 코드만 — 내부 상세 누출 금지).
 */
class MfaEnrollmentGateFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (isEnrollmentRequired(request) && !isAllowed(request)) {
            writeForbidden(response)
            return
        }
        filterChain.doFilter(request, response)
    }

    /**
     * 현재 인증 principal 이 [Jwt] 이고 `mfa_enrollment_required` 클레임이 true 인지 판정한다.
     *
     * PAT(non-Jwt principal)·미인증·클레임 부재/false 는 모두 false 를 반환해 게이트를 통과시킨다(회귀 0).
     *
     * @return 강제 등록이 필요한 요청이면 true.
     */
    private fun isEnrollmentRequired(request: HttpServletRequest): Boolean {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        if (principal !is Jwt) return false
        return principal.getClaim<Boolean>(JwtIssuer.CLAIM_MFA_ENROLLMENT_REQUIRED) == true
    }

    /** 요청 경로가 차단 예외([ALLOW_LIST]) 에 해당하면 true. */
    private fun isAllowed(request: HttpServletRequest): Boolean = ALLOW_LIST.any { it.matches(request) }

    /** 403 + `{"error":"mfa_enrollment_required"}` JSON 본문을 기록한다(에러 코드만 노출). */
    private fun writeForbidden(response: HttpServletResponse) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(FORBIDDEN_BODY)
    }

    private companion object {
        /** 게이트 차단 에러 코드 — whoami/JwtIssuer 클레임 키와 동일 값으로 단일 출처 일치. */
        const val GATE_ERROR_CODE = JwtIssuer.CLAIM_MFA_ENROLLMENT_REQUIRED

        /** 차단 응답 본문(에러 코드만 — 내부 상세 누출 금지, DEVELOPMENT.md §1.1). */
        const val FORBIDDEN_BODY = """{"error":"$GATE_ERROR_CODE"}"""

        /**
         * 차단 예외 경로(allow-list) — 누락 시 영구 락(클래스 KDoc 참조).
         * - /api/v1/auth/mfa 하위 — MFA 설정/활성화/상태/비활성화·백업코드(등록 흐름 자체).
         * - /api/v1/users/me/whoami — 강제 등록 필요 여부 확인(프론트 게이팅 진입점).
         * - /api/v1/auth/logout — 세션 종료(잠긴 상태에서도 로그아웃 가능해야 함).
         * - /api/v1/auth/refresh — 토큰 갱신(MFA 등록 완료 후 클레임 해제 경로).
         */
        val ALLOW_LIST: List<AntPathRequestMatcher> =
            listOf(
                AntPathRequestMatcher("/api/v1/auth/mfa/**"),
                AntPathRequestMatcher("/api/v1/users/me/whoami"),
                AntPathRequestMatcher("/api/v1/auth/logout"),
                AntPathRequestMatcher("/api/v1/auth/refresh"),
            )
    }
}
