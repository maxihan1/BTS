// 활성 SAML IdP 목록을 UI 로그인 폼에 반환하는 엔드포인트 (FR-AU-03)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.provider.saml.SamlIdpConfigRepository
import com.atlas.bts.identity.web.dto.SamlIdpResponse
import com.atlas.bts.identity.web.dto.SamlIdpsResponse
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GET /api/v1/auth/saml/idps — UI 로그인 폼용 활성 SAML IdP 목록 (FR-AU-03).
 *
 * ## 응답 계약
 * - 활성(enabled=true) IdP 만 노출한다. 비활성 필터링은 [SamlIdpConfigRepository.findAllEnabled] 책임이다(EC5).
 * - 각 항목은 [SamlIdpResponse] 의 registrationId / displayName 두 필드만 포함한다.
 * - 인증서·SSO URL·entityId·authn_provider_id 등 민감·내부 정보는 절대 노출하지 않는다.
 *
 * ## 보안 (DEVELOPMENT.md §1.4)
 * 이 엔드포인트는 로그인 전 단계에서 호출되므로 permitAll 이어야 한다.
 * **Task 5 (SecurityConfig) 에서 `/api/v1/auth/saml/idps` permitAll 등록이 필요하다** — 미등록 시 401 로 막힌다.
 * permitAll 경로이므로 @PreAuthorize 는 적용하지 않는다.
 *
 * @see com.atlas.bts.identity.config.SecurityConfig — /api/v1/auth/saml/idps permitAll 선언(Task 5)
 */
@RestController
@Transactional(readOnly = true)
class SamlIdpController(
    private val samlIdpConfigRepository: SamlIdpConfigRepository,
) {
    /**
     * 활성 SAML IdP 목록 반환.
     *
     * repository 가 반환한 활성 IdP 를 UI 노출용 최소 필드(registrationId, displayName)로 매핑한다.
     */
    @GetMapping("/api/v1/auth/saml/idps")
    fun listIdps(): SamlIdpsResponse {
        val idps =
            samlIdpConfigRepository.findAllEnabled().map { config ->
                SamlIdpResponse(
                    registrationId = config.registrationId,
                    displayName = config.displayName,
                )
            }
        return SamlIdpsResponse(idps)
    }
}
