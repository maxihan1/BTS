// 도메인 기반 SSO 라우트를 조회해 로그인 화면을 해당 SAML/OIDC IdP 로 안내하는 엔드포인트 (FR-AU-07)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.provider.route.DomainProviderRouteRepository
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * GET /api/v1/auth/route — 이메일 도메인으로 활성 SSO Provider 라우트를 조회한다 (FR-AU-07).
 *
 * UI 로그인 화면이 사용자가 입력한 이메일 도메인을 보내면, 해당 도메인에 매핑된 활성
 * SAML/OIDC IdP 정보(registrationId/displayName)를 반환해 SSO 진입점으로 안내한다.
 * 매칭이 없으면 일반 로그인 흐름으로 fall-through 하도록 matched=false 를 반환한다.
 *
 * ## 응답 계약
 * - 인증 불필요 (permitAll): 로그인 화면은 미인증 상태에서 호출하므로 자유 접근이 필요하다.
 *   SecurityConfig 의 `/api/v1/auth/route` permitAll 선언은 Task 3 에서 등록한다.
 *   이 엔드포인트는 permitAll 경로이므로 @PreAuthorize 를 적용하지 않는다 (DEVELOPMENT.md §1.4).
 *
 * ## 보안
 * - **계정 열거 방지**: 도메인만으로 라우트 존재 여부만 판단하며, 자격증명(이메일 로컬파트·비밀번호)을
 *   취급하지 않는다. 응답은 "이 도메인이 SSO 로 라우팅되는가"만 알려줄 뿐 특정 사용자의 존재를 드러내지 않는다.
 * - **민감정보 미노출**: 응답에는 registrationId/displayName 만 담는다. authn_provider_id, IdP 인증서,
 *   SSO URL, entityId 등 내부 식별자·비밀값은 [RouteResponse] 에 포함하지 않는다(Repository 가 이미 제외).
 *
 * @see com.atlas.bts.identity.provider.route.DomainProviderRouteRepository — 라우트 매칭 + 정규화 위임 계약
 */
@RestController
@Transactional(readOnly = true)
class DomainRouteController(
    private val domainProviderRouteRepository: DomainProviderRouteRepository,
) {
    /**
     * 이메일 도메인으로 활성 SSO 라우트를 조회한다.
     *
     * 입력 [domain] 을 정규화(공백 제거·소문자화)한 뒤 [DomainProviderRouteRepository.findRouteByDomain]
     * 에 위임한다. 매칭되면 matched=true 와 type/registrationId/displayName 을, 없으면 matched=false 를 반환한다.
     *
     * @param domain 조회할 이메일 도메인. 예. "partner.com". 누락 시 Spring 이 400 을 반환한다.
     * @return [RouteResponse] — 매칭 여부와 (매칭 시) SSO Provider 식별 정보
     * @throws ResponseStatusException 정규화 결과가 빈 문자열(빈/공백 입력)이면 400
     */
    @GetMapping("/api/v1/auth/route")
    fun resolveRoute(
        @RequestParam domain: String,
    ): RouteResponse {
        val normalized = normalizeDomain(domain)
        val match = domainProviderRouteRepository.findRouteByDomain(normalized)
        return if (match == null) {
            RouteResponse(matched = false)
        } else {
            RouteResponse(
                matched = true,
                type = match.type.name,
                registrationId = match.registrationId,
                displayName = match.displayName,
            )
        }
    }

    /**
     * 도메인을 정규화한다 — 앞뒤 공백 제거 후 소문자화.
     *
     * 정규화 결과가 빈 문자열(빈/공백 입력)이면 400 을 던진다. 정규화를 Controller 가 담당하므로
     * Repository 는 정규화된 값을 그대로 조회한다(책임 경계 — Repository KDoc 참조).
     */
    private fun normalizeDomain(domain: String): String {
        val normalized = domain.trim().lowercase()
        if (normalized.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        return normalized
    }
}

/**
 * 도메인 라우트 조회 응답.
 *
 * 매칭 시 [matched]=true 이며 [type]/[registrationId]/[displayName] 이 모두 non-null 로 채워진다.
 * 미매칭 시 [matched]=false 이며 나머지 세 필드는 null 이다.
 * 프론트는 `matched` 를 discriminator 로 하는 union 으로 파싱한다.
 */
data class RouteResponse(
    /** SSO 라우트 매칭 여부 (응답 union discriminator) */
    val matched: Boolean,
    /** 매칭된 Provider 유형 (예. "SAML", "OIDC"). 미매칭 시 null */
    val type: String? = null,
    /** Spring Security 등록 식별자 (SSO 진입 URL 구성용). 미매칭 시 null */
    val registrationId: String? = null,
    /** UI 에 표시할 IdP 명칭. 미매칭 시 null */
    val displayName: String? = null,
)
