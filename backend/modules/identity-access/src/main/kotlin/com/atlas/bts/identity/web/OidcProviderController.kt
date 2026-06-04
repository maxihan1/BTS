// 활성 OIDC IdP 목록을 UI 로그인 폼에 반환하는 엔드포인트 (FR-AU-04)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.provider.oidc.OidcProviderConfigRepository
import com.atlas.bts.identity.web.dto.OidcProviderResponse
import com.atlas.bts.identity.web.dto.OidcProvidersResponse
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GET /api/v1/auth/oidc/providers — UI 로그인 폼용 활성 OIDC IdP 목록 (FR-AU-04).
 *
 * ## 응답 계약
 * - 활성(enabled=true) IdP 만 노출한다. 비활성 필터링은 [OidcProviderConfigRepository.findEnabled] 책임이다(EC5).
 * - 각 항목은 [OidcProviderResponse] 의 registrationId / displayName 두 필드만 포함한다.
 * - issuer URI·client_id·client_secret·scopes·authn_provider_id 등 민감·내부 정보는 절대 노출하지 않는다(§1.1.2).
 *
 * ## 보안 (DEVELOPMENT.md §1.4)
 * 이 엔드포인트는 로그인 전 단계에서 호출되므로 permitAll 이어야 한다.
 * **Task 5 (SecurityConfig) 에서 `/api/v1/auth/oidc/providers` permitAll 등록이 필요하다** — 미등록 시 401 로 막힌다.
 * permitAll 경로이므로 @PreAuthorize 는 적용하지 않는다.
 *
 * @see com.atlas.bts.identity.config.SecurityConfig — /api/v1/auth/oidc/providers permitAll 선언(Task 5)
 */
@RestController
@Transactional(readOnly = true)
class OidcProviderController(
    private val oidcProviderConfigRepository: OidcProviderConfigRepository,
) {
    /**
     * 활성 OIDC IdP 목록 반환.
     *
     * repository 가 반환한 활성 IdP 를 UI 노출용 최소 필드(registrationId, displayName)로 매핑한다.
     */
    @GetMapping("/api/v1/auth/oidc/providers")
    fun listProviders(): OidcProvidersResponse {
        val providers =
            oidcProviderConfigRepository.findEnabled().map { config ->
                OidcProviderResponse(
                    registrationId = config.registrationId,
                    displayName = config.displayName,
                )
            }
        return OidcProvidersResponse(providers)
    }
}
