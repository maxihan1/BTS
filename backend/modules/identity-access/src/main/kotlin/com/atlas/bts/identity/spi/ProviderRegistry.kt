// 인증 공급자 레지스트리 — Spring DI로 수집된 AuthenticationProvider Bean 관리

package com.atlas.bts.identity.spi

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.Collections

/**
 * BTS 인증 공급자 등록소.
 *
 * Spring 부팅 시 모든 [AuthenticationProvider] Bean을 자동 수집한다.
 * providers 파라미터가 비어있어도 정상 동작 (required = false).
 *
 * 주의: 이 레지스트리는 Spring Security FilterChain에 직접 연결되지 않는다.
 * FilterChain 통합은 [com.atlas.bts.identity.adapter.spring.SpringSecurityProviderAdapter]를
 * 통해 수행되며, 해당 어댑터는 FR-AU-09 PR에서 SecurityConfig에 명시 등록된다.
 */
@Component
class ProviderRegistry(
    @Autowired(required = false)
    private val providers: List<AuthenticationProvider> = emptyList(),
) {
    /**
     * 주어진 [type]을 담당하는 공급자를 반환.
     * 해당 type의 공급자가 없으면 null.
     */
    fun findByType(type: ProviderType): AuthenticationProvider? = providers.firstOrNull { it.type == type }

    /**
     * 주어진 [credential]을 처리할 수 있는 공급자를 반환.
     *
     * [AuthenticationProvider.priority] 내림차순으로 정렬 후 [AuthenticationProvider.supports]가
     * true인 첫 번째 공급자를 반환한다. 없으면 null.
     *
     * 동률 발생 시 [ProviderType.ordinal] 오름차순으로 최종 순서를 결정한다 (EC-25).
     * 정렬은 호출마다 수행되므로 런타임 priority override가 즉시 반영된다.
     *
     * Priority 기본값 (SDD §19.2, FR-AU-09-28):
     * | Provider | priority |
     * |---|---|
     * | LDAP   | 80 |
     * | LOCAL  | 70 |
     * | PAT    | 60 |
     * | OIDC   | 50 |
     * | SAML   | 40 |
     * | OAUTH  | 30 |
     */
    fun findFor(credential: Credential): AuthenticationProvider? =
        providers
            .sortedWith(compareByDescending<AuthenticationProvider> { it.priority }.thenBy { it.type.ordinal })
            .firstOrNull { it.supports(credential) }

    /**
     * 등록된 모든 공급자의 불변 복사본을 반환.
     * [Collections.unmodifiableList]로 래핑하여 add/remove 시 [UnsupportedOperationException] 발생.
     */
    fun all(): List<AuthenticationProvider> = Collections.unmodifiableList(providers.toList())
}
