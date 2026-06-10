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
 * ## JWT 전용 (PAT 차단)
 * 계정 연결 셀프서비스의 일부이므로 PAT 는 차단한다([AccountLinkController] 선례와 동일 원칙).
 * step-up 은 요구하지 않는다 — 읽기전용 공개정보(enabled provider 이름)만 노출하기 때문이다.
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

        val linkable = buildList {
            authnProviderConfigRepository.findEnabledByType(ProviderType.LDAP).forEach {
                add(LinkableProviderDto(kind = "LDAP", providerId = it.id, registrationId = null, displayName = it.name))
            }
            samlIdpConfigRepository.findAllEnabled().forEach {
                add(
                    LinkableProviderDto(
                        kind = "SAML",
                        providerId = null,
                        registrationId = it.registrationId,
                        displayName = it.displayName,
                    ),
                )
            }
            oidcProviderConfigRepository.findEnabled().forEach {
                add(
                    LinkableProviderDto(
                        kind = "OIDC",
                        providerId = null,
                        registrationId = it.registrationId,
                        displayName = it.displayName,
                    ),
                )
            }
        }
        return ResponseEntity.ok(LinkableProvidersResponse(linkable = linkable))
    }

    private companion object {
        /** PAT 인증 시 계정 연결 셀프서비스 불가 응답 — [AccountLinkController] PAT 차단 선례와 동형. */
        val PAT_FORBIDDEN_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "account_linking_requires_interactive_login"))
    }
}
