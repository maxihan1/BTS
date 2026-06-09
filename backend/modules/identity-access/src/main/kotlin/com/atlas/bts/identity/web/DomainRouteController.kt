// 도메인 기반 SSO 라우트를 조회해 로그인 화면을 해당 SAML/OIDC IdP 로 안내하는 엔드포인트 (FR-AU-07)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.provider.route.DomainProviderRouteRepository
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@Transactional(readOnly = true)
class DomainRouteController(
    private val domainProviderRouteRepository: DomainProviderRouteRepository,
) {
    @GetMapping("/api/v1/auth/route")
    fun resolveRoute(
        @RequestParam domain: String,
    ): RouteResponse {
        val normalized = domain.trim().lowercase()
        if (normalized.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
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
}

data class RouteResponse(
    val matched: Boolean,
    val type: String? = null,
    val registrationId: String? = null,
    val displayName: String? = null,
)
