// UI 로그인 폼에 사용 가능한 username/password 계열 인증 공급자 목록을 반환하는 엔드포인트 (FR-AU-06)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.provider.AuthnProviderConfigRepository
import com.atlas.bts.identity.spi.AuthenticationProvider
import com.atlas.bts.identity.spi.ProviderRegistry
import com.atlas.bts.identity.spi.ProviderType
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GET /api/v1/auth/providers — UI 로그인 폼용 username/password 계열 공급자 목록 (FR-AU-06 / spec FR-06-06).
 *
 * ## 응답 계약
 * - 인증 불필요 (permitAll): SecurityConfig 에서 `/api/v1/auth/providers` permitAll 선언.
 * - username/password 계열만: [LOGIN_FORM_TYPES] (LOCAL/LDAP) 만 포함한다.
 *   SAML/OIDC 는 별도 SSO 진입점(`/saml/idps`·`/oidc/providers`)이 담당하고, PAT/OAUTH 는
 *   UI 로그인 폼에 노출하지 않으므로 제외한다. (CONCERN C4 — 더미 Credential 의 supports() 호출 대신
 *   type 비교로 필터해 명확·안전하게 처리.)
 * - DB enabled 오버레이: [AuthnProviderConfigRepository.isEnabled] 가 false 인 type 은 제외한다.
 *   row 가 없으면 true 라 LOCAL/LDAP 은 기본 노출된다.
 * - 정렬 정책: [sortKey] 참조. sort_order 오름차순 → 동률 시 priority 내림차순.
 * - available 필드: [AuthenticationProvider.available] 현재 가용 여부.
 * - "auto" 항목 없음: 사용자가 명시적으로 공급자를 선택한다.
 *
 * ## 보안 (DEVELOPMENT.md §1.4)
 * 이 엔드포인트는 permitAll 이므로 @PreAuthorize 를 적용하지 않는다.
 * SecurityConfig 의 authorizeHttpRequests 에서 명시적 허용이 선언되어 있다.
 *
 * @see com.atlas.bts.identity.config.SecurityConfig — /api/v1/auth/providers permitAll 선언
 */
@RestController
@Transactional(readOnly = true)
class ProvidersController(
    private val providerRegistry: ProviderRegistry,
    private val authnProviderConfigRepository: AuthnProviderConfigRepository,
) {
    /**
     * UI 로그인 폼용 공급자 목록 반환.
     *
     * username/password 계열(LOCAL/LDAP) 중 DB 에서 enabled=true 인 것만,
     * sort_order 오름차순(동률 시 priority 내림차순)으로 반환한다.
     */
    @GetMapping("/api/v1/auth/providers")
    fun listProviders(): ProvidersResponse {
        val enabled = providerRegistry.all()
            .filter { it.type in LOGIN_FORM_TYPES }
            .filter { authnProviderConfigRepository.isEnabled(it.type) }
        val sortOrders = authnProviderConfigRepository
            .listEnabledByTypes(enabled.map { it.type })
            .toMap()
        val providers = enabled
            .sortedWith(comparatorFor(sortOrders))
            .map { toEntry(it) }
        return ProvidersResponse(providers)
    }

    /**
     * 정렬 비교자.
     *
     * 1순위 sort_order 오름차순 — DB row 가 있는 type 은 그 sort_order, 없는 type 은
     * [SORT_ORDER_DEFAULT] (큰 상수)로 두어 DB 등록 공급자가 항상 앞선다.
     * 2순위(동률) priority 내림차순 — 둘 다 기본값이면 LDAP(80)이 LOCAL(70)보다 앞.
     */
    private fun comparatorFor(
        sortOrders: Map<ProviderType, Int>,
    ): Comparator<AuthenticationProvider> {
        return compareBy<AuthenticationProvider> { sortKey(it.type, sortOrders) }
            .thenByDescending { it.priority }
    }

    /** [type] 의 sort_order — DB row 가 있으면 그 값, 없으면 [SORT_ORDER_DEFAULT]. */
    private fun sortKey(
        type: ProviderType,
        sortOrders: Map<ProviderType, Int>,
    ): Int {
        return sortOrders[type] ?: SORT_ORDER_DEFAULT
    }

    /** [AuthenticationProvider] 를 응답 항목으로 매핑. displayName 은 type capitalize. */
    private fun toEntry(provider: AuthenticationProvider): ProviderEntry {
        val typeName = provider.type.name
        return ProviderEntry(
            id = typeName.lowercase(),
            type = typeName,
            displayName = typeName.lowercase().replaceFirstChar { it.uppercase() },
            priority = provider.priority,
            available = provider.available,
        )
    }

    private companion object {
        /** UI 로그인 폼에 노출하는 username/password 계열 type 집합. */
        val LOGIN_FORM_TYPES = setOf(ProviderType.LOCAL, ProviderType.LDAP)

        /** DB authn_providers row 가 없는 type 의 sort_order 기본값 — DB 등록분보다 항상 뒤로. */
        const val SORT_ORDER_DEFAULT = Int.MAX_VALUE
    }
}

/** providers 응답 루트 객체 */
data class ProvidersResponse(
    val providers: List<ProviderEntry>,
)

/** 공급자 항목 — UI 로그인 폼에 표시되는 단일 공급자 정보 */
data class ProviderEntry(
    /** 공급자 식별자 (소문자 type명, 예: "ldap", "local") */
    val id: String,
    /** 공급자 유형 (대문자 enum명, 예: "LDAP", "LOCAL") */
    val type: String,
    /** UI에 표시할 공급자 명칭 */
    val displayName: String,
    /** 우선순위 — 높을수록 앞에 표시 */
    val priority: Int,
    /** 현재 공급자가 정상 서비스 가능한 상태인지 여부 */
    val available: Boolean,
)
