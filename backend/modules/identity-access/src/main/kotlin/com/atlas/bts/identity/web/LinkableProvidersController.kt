// 연결 가능 provider 통합 목록 엔드포인트 — LDAP/SAML/OIDC 활성 목록을 한 배열로 반환 (FR-AU-08 D6 Task 2)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.provider.AuthnProviderConfigRepository
import com.atlas.bts.identity.provider.oidc.OidcProviderConfigRepository
import com.atlas.bts.identity.provider.saml.SamlIdpConfigRepository
import com.atlas.bts.identity.spi.ProviderType
import com.atlas.bts.identity.web.dto.LinkableProviderDto
import com.atlas.bts.identity.web.dto.LinkableProvidersResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 연결 가능 provider 통합 목록을 반환하는 읽기전용 컨트롤러 (FR-AU-08 / SDD §19).
 *
 * ## 엔드포인트
 * - [listLinkable]: GET /linkable-providers — LDAP/SAML/OIDC 활성 provider 를 한 배열로 반환.
 *
 * ## 노출 범위 — enabled 공개정보, per-user 필터 없음
 * 노출 대상은 활성(enabled) provider 의 표시 이름·식별자뿐인 공개정보다. 사용자별로 다르게 거를
 * 항목이 없으므로 per-user 필터링을 하지 않는다(이미 연결됐는지 여부는 별도 /links 엔드포인트가 담당).
 *
 * ## JWT 전용 (PAT 차단) — `@PreAuthorize` 불요 사유
 * 이 경로는 SecurityConfig 의 api/v1 매칭에서 `authenticated()` 에 떨어져 PAT 도 필터 체인을 통과한다.
 * 따라서 PAT 차단은 메서드 내부에서 [AccountLinkJwtSupport.resolveClaims] 가 null(=PAT, Jwt 아님)을
 * 반환하는지로만 게이팅한다 — 이 null 검사가 **유일한 PAT 가드**다. PAT/JWT 구분은
 * `@PreAuthorize` SpEL 로는 표현되지 않으므로(둘 다 authenticated), 별도 `@PreAuthorize` 를 두지 않는다
 * ([AccountLinkController] 선례와 동일 원칙). step-up 도 요구하지 않는다(읽기전용 공개정보).
 *
 * ## 트랜잭션 경계
 * @Transactional 없음 — repository 의 읽기 전용 조회만 수행한다.
 *
 * @see com.atlas.bts.identity.config.SecurityConfig — api/v1 경로가 authenticated() 에 떨어져 인증 필수
 */
@RestController
@RequestMapping("/api/v1/auth/account")
class LinkableProvidersController(
    private val authnProviderConfigRepository: AuthnProviderConfigRepository,
    private val samlIdpConfigRepository: SamlIdpConfigRepository,
    private val oidcProviderConfigRepository: OidcProviderConfigRepository,
    private val jwtSupport: AccountLinkJwtSupport,
) {
    /**
     * GET /api/v1/auth/account/linkable-providers — 연결 가능 provider 통합 목록 (FR-AU-08).
     *
     * PAT 면 403. JWT 면 활성 LDAP/SAML/OIDC 를 한 [LinkableProvidersResponse] 배열로 반환한다.
     * per-user 필터는 없다 — 노출 대상은 enabled 공개정보(provider 이름)뿐이다.
     *
     * @param jwt 인증 JWT principal. PAT 인증 시 null → 403.
     * @return 200 [LinkableProvidersResponse] / 403 PAT / 401 미인증(필터)
     */
    @GetMapping("/linkable-providers")
    fun listLinkable(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        jwtSupport.resolveClaims(jwt) ?: return PAT_FORBIDDEN_RESPONSE

        val linkable = ldapLinkables() + samlLinkables() + oidcLinkables()
        return ResponseEntity.ok(LinkableProvidersResponse(linkable = linkable))
    }

    /** 활성 LDAP provider 를 kind=LDAP(providerId) 항목으로 매핑한다. */
    private fun ldapLinkables(): List<LinkableProviderDto> =
        authnProviderConfigRepository.findEnabledByType(ProviderType.LDAP).map {
            LinkableProviderDto(
                kind = KIND_LDAP,
                providerId = it.id,
                registrationId = null,
                displayName = it.name,
            )
        }

    /** 활성 SAML IdP 를 kind=SAML(registrationId) 항목으로 매핑한다. */
    private fun samlLinkables(): List<LinkableProviderDto> =
        samlIdpConfigRepository.findAllEnabled().map {
            LinkableProviderDto(
                kind = KIND_SAML,
                providerId = null,
                registrationId = it.registrationId,
                displayName = it.displayName,
            )
        }

    /** 활성 OIDC provider 를 kind=OIDC(registrationId) 항목으로 매핑한다. */
    private fun oidcLinkables(): List<LinkableProviderDto> =
        oidcProviderConfigRepository.findEnabled().map {
            LinkableProviderDto(
                kind = KIND_OIDC,
                providerId = null,
                registrationId = it.registrationId,
                displayName = it.displayName,
            )
        }

    private companion object {
        /** LDAP 항목 kind — providerId(authn_providers.id) 로 식별. */
        const val KIND_LDAP = "LDAP"

        /** SAML 항목 kind — registrationId 로 식별. */
        const val KIND_SAML = "SAML"

        /** OIDC 항목 kind — registrationId 로 식별. */
        const val KIND_OIDC = "OIDC"

        /** PAT 인증 시 계정 연결 셀프서비스 불가 응답 — [AccountLinkController] PAT 차단 선례와 동형. */
        val PAT_FORBIDDEN_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "account_linking_requires_interactive_login"))
    }
}
