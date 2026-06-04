// OIDC 인증 공급자 — type/메타 제공 thin 구현 (인증 자체는 Spring OAuth2/OIDC 필터가 수행)

package com.atlas.bts.identity.provider.oidc

import com.atlas.bts.identity.spi.AuthenticationProvider
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.ProviderType
import org.springframework.stereotype.Service

/**
 * OIDC(OpenID Connect) SSO 인증 공급자 (FR-AU-04).
 *
 * **thin 구현 — 인증은 필터가 처리 (dead-path 방지, SamlProvider 동형)**:
 * OIDC ID Token 의 서명·issuer·audience·만료 검증과 인증 흐름(authorization code 교환 등)은
 * Spring Security OAuth2/OIDC 필터가 수행한 뒤
 * [com.atlas.bts.identity.provider.oidc.OidcAuthenticationSuccessHandler] 가 후처리한다.
 * 따라서 이 공급자는 [ProviderType.OIDC] 등록과 로그인 폼 노출용 메타데이터만 제공하며,
 * 도메인 SPI 의 [authenticate] 진입점으로는 OIDC 흐름이 흐르지 않는다.
 *
 * **supports/authenticate 계약**:
 * - [supports]: 항상 false. OIDC 는 [Credential.OidcToken] 을 쓰지만 그 자격증명은
 *   POST /login 의 [com.atlas.bts.identity.spi.ProviderRegistry] 경로로 들어오지 않는다.
 *   따라서 ProviderRegistry 가 이 공급자를 자격증명 매칭 대상으로 삼지 않도록 false 를 반환한다.
 * - [authenticate]: 호출 진입점이 없는 dead-path 이므로 호출되어서는 안 된다.
 *   방어적으로 [AuthnResult.Failure] 를 반환한다(예외 throw 금지 — SPI 계약).
 *
 * **priority = 50 (ProviderType.OIDC 기본값)**:
 * [ProviderType.OIDC].priority = 50 — [AuthenticationProvider] 인터페이스 default getter 위임.
 */
@Service
class OidcProvider : AuthenticationProvider {
    override val type: ProviderType = ProviderType.OIDC

    /**
     * 항상 false 를 반환한다.
     *
     * OIDC 는 필터가 처리하므로 ProviderRegistry 의 자격증명 매칭 대상이 아니다.
     */
    override fun supports(credential: Credential): Boolean = false

    /**
     * OIDC 는 필터가 처리하므로 이 메서드는 호출되지 않는 dead-path 다.
     *
     * 방어적으로 [AuthnResult.Failure] 를 반환한다(SPI 계약상 예외 throw 금지).
     */
    override fun authenticate(credential: Credential): AuthnResult = AuthnResult.Failure(OIDC_NOT_HANDLED_HERE)

    private companion object {
        /** OIDC 는 필터가 처리하므로 SPI authenticate 로 인증되지 않음을 나타내는 실패 사유. */
        val OIDC_NOT_HANDLED_HERE = FailureReason.PROVIDER_UNAVAILABLE
    }
}
