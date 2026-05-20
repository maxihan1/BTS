// BTS 도메인 인증 공급자 SPI — Spring Security 동명 인터페이스와 패키지로 분리됨 (ADR 참고)

package com.atlas.bts.identity.spi

/**
 * BTS 인증 공급자 SPI (Service Provider Interface).
 *
 * Spring Security의 `org.springframework.security.authentication.AuthenticationProvider`와
 * 의도적으로 분리된 도메인 인터페이스 (ADR: docs/decisions/2026-05-20-authentication-provider-spi-naming.md).
 *
 * 구현체는 [ProviderRegistry]에 의해 Spring DI로 수집되며, 각 구현체는 [supports]로
 * 처리 가능한 [Credential] 타입을 선언한다.
 *
 * 현재 구현체: 없음 (이 PR은 SPI 정의만). 이후 FR-AU-02~04에서 LDAP/SAML/OIDC 추가.
 */
interface AuthenticationProvider {
    /** 이 공급자가 담당하는 [ProviderType]. */
    val type: ProviderType

    /**
     * 이 공급자의 우선순위. 높을수록 [ProviderRegistry]가 먼저 시도한다.
     *
     * 기본값은 [ProviderType.priority]에서 위임한다 (EC-25, SDD §19.2, FR-AU-09-28).
     * 특수한 배포 환경에서 우선순위를 재조정해야 할 경우 구현체에서 override 가능.
     *
     * 동률 발생 시 [ProviderRegistry]가 [ProviderType.ordinal] 오름차순으로 최종 결정.
     */
    val priority: Int get() = type.priority

    /**
     * 주어진 [credential]을 이 공급자가 처리할 수 있는지 반환.
     * [ProviderRegistry.findFor]가 적합한 공급자를 찾을 때 호출된다.
     */
    fun supports(credential: Credential): Boolean

    /**
     * [credential]로 인증을 시도하고 [AuthnResult]를 반환.
     *
     * Contract:
     * - [Credential.UsernamePassword]의 password CharArray는 인증 완료 후 이 메서드 내에서
     *   `password.fill(' ')` 등으로 즉시 초기화해야 한다 (DEVELOPMENT.md §1.1).
     * - 예외를 throw하지 말고 [AuthnResult.Failure]로 반환할 것.
     */
    fun authenticate(credential: Credential): AuthnResult
}
