// SAML 인증 공급자 — type/메타 제공 thin 구현 (인증 자체는 Spring SAML2 필터가 수행)

package com.atlas.bts.identity.provider.saml

import com.atlas.bts.identity.spi.AuthenticationProvider
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.ProviderType
import org.springframework.stereotype.Service

/**
 * SAML SSO 인증 공급자 (FR-AU-03).
 *
 * **thin 구현 — 인증은 필터가 처리 (게이트1 D4 / C4 dead-path 방지)**:
 * SAML Assertion 의 서명·시각·audience 검증과 인증 흐름 자체는 Spring Security SAML2 필터가
 * 수행한다 ([com.atlas.bts.identity.provider.saml.Saml2AuthenticationSuccessHandler] 가 후처리).
 * 따라서 이 공급자는 [ProviderType.SAML] 등록과 로그인 폼 노출용 메타데이터만 제공하며,
 * 도메인 SPI 의 [authenticate] 진입점으로는 SAML 흐름이 흐르지 않는다.
 *
 * [ProvidersController] 가 활성 Provider 목록을 조회할 때 이 Bean 을 수집해 SAML 옵션을 노출한다.
 *
 * **supports/authenticate 계약**:
 * - [supports]: 항상 false. SAML 은 [Credential.SamlAssertion] 을 쓰지만 그 자격증명은
 *   POST /login 의 [com.atlas.bts.identity.spi.ProviderRegistry] 경로로 들어오지 않는다.
 *   따라서 ProviderRegistry 가 이 공급자를 자격증명 매칭 대상으로 삼지 않도록 false 를 반환한다.
 * - [authenticate]: 호출 진입점이 없는 dead-path 이므로 호출되어서는 안 된다.
 *   방어적으로 [AuthnResult.Failure] 를 반환한다(예외 throw 금지 — SPI 계약).
 *
 * **priority = 40 (SDD §19.2, FR-AU-09-28)**:
 * [ProviderType.SAML].priority = 40 — [AuthenticationProvider] 인터페이스 default getter 위임.
 */
@Service
class SamlProvider : AuthenticationProvider {
    override val type: ProviderType = ProviderType.SAML

    /**
     * 항상 false 를 반환한다.
     *
     * SAML 은 필터가 처리하므로 ProviderRegistry 의 자격증명 매칭 대상이 아니다.
     */
    override fun supports(credential: Credential): Boolean = false

    /**
     * SAML 은 필터가 처리하므로 이 메서드는 호출되지 않는 dead-path 다.
     *
     * 방어적으로 [AuthnResult.Failure] 를 반환한다(SPI 계약상 예외 throw 금지).
     */
    override fun authenticate(credential: Credential): AuthnResult =
        AuthnResult.Failure(SAML_NOT_HANDLED_HERE)

    private companion object {
        /** SAML 은 필터가 처리하므로 SPI authenticate 로 인증되지 않음을 나타내는 실패 사유. */
        val SAML_NOT_HANDLED_HERE = FailureReason.PROVIDER_UNAVAILABLE
    }
}
