// domain_provider_routes 테이블 read 접근 Repository — 도메인 → Provider 라우트 매칭 (FR-AU-07)

package com.atlas.bts.identity.provider.route

import com.atlas.bts.identity.spi.ProviderType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 도메인 → Provider 라우트 매칭 결과.
 *
 * 매칭된 활성 SSO Provider 의 식별 정보만 담는다(불변).
 * Controller(Task 2)가 이 값으로 로그인 화면을 해당 SAML/OIDC SSO 로 안내한다.
 */
data class RouteMatch(
    val type: ProviderType,
    val registrationId: String,
    val displayName: String,
)

/**
 * domain_provider_routes 테이블 read 전용 Repository (FR-AU-07 도메인 기반 SSO 라우팅).
 *
 * ## 책임 경계
 * 도메인 정규화(소문자/공백 제거)는 Controller(Task 2) 책임이다.
 * 본 Repository 는 받은 값을 그대로 조회한다(정규화 위임).
 *
 * ## 매칭 판정 — type 분기 2-step (LEFT JOIN 2개 혼합 금지)
 * 라우트가 가리키는 Provider 의 type 에 따라 조회할 설정 테이블이 다르므로,
 * 2개 설정 테이블을 한 쿼리에 LEFT JOIN 으로 섞지 않고 type 으로 분기한다(가독성·인덱스 활용).
 *
 * ① [SQL_FIND_PROVIDER_BY_DOMAIN] — domain_provider_routes JOIN authn_providers 로
 *    (provider_id, type) 를 얻는다.
 * ② type 분기.
 *    - SAML → [SQL_FIND_ENABLED_SAML] (saml_idp_configs)
 *    - OIDC → [SQL_FIND_ENABLED_OIDC] (oidc_provider_configs)
 *    각 단일 테이블에서 enabled=true 인 registration_id/display_name 을 조회한다.
 *
 * ## fail-safe — 왜 null 인가
 * LOCAL/LDAP 라우트, 비활성(enabled=false) 설정, 미등록 도메인은 모두 매칭 결과가 없어 null 을 반환한다.
 * - **LOCAL/LDAP**: SSO 라우팅 대상이 아니다. 정상 운영에선 LOCAL 이 authn_providers 에 없지만,
 *   운영자가 실수로 LOCAL 라우트를 만든 비정상 상태라도 SSO 로 잘못 보내지 않도록 type 분기에서 걸러진다.
 * - **비활성**: enabled=false 인 IdP 로 보내면 로그인 자체가 깨지므로 매칭에서 제외한다.
 * null 을 받은 Controller 는 SSO 자동 안내 대신 일반 로그인 흐름으로 fall-through 한다.
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩한다(문자열 결합 금지, DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(readOnly = true)
class DomainProviderRouteRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * 도메인으로 활성 SSO Provider 라우트를 조회한다.
     *
     * 매칭되는 활성 SAML/OIDC 라우트가 있으면 [RouteMatch] 를, 그 외(미등록·비활성·LOCAL/LDAP)는 null 을 반환한다.
     * 입력 [domain] 은 이미 정규화된 값으로 가정한다(정규화는 Controller 책임).
     */
    fun findRouteByDomain(domain: String): RouteMatch? {
        val (providerId, type) = findProviderByDomain(domain) ?: return null
        return when (type) {
            ProviderType.SAML -> findEnabledSaml(providerId, type)
            ProviderType.OIDC -> findEnabledOidc(providerId, type)
            // LOCAL/LDAP/PAT/OAUTH 는 SSO 라우팅 대상이 아니다 → fail-safe null
            else -> null
        }
    }

    /** ① 도메인 → (provider_id, type) 조회. 미등록이면 null. */
    private fun findProviderByDomain(domain: String): Pair<UUID, ProviderType>? =
        jdbc.query(
            SQL_FIND_PROVIDER_BY_DOMAIN,
            mapOf("domain" to domain),
        ) { rs, _ ->
            rs.getObject("provider_id", UUID::class.java) to
                ProviderType.valueOf(rs.getString("type"))
        }.firstOrNull()

    /** ② SAML 분기 — 활성 saml_idp_configs 단일 테이블 조회. 비활성/미존재면 null. */
    private fun findEnabledSaml(
        providerId: UUID,
        type: ProviderType,
    ): RouteMatch? =
        jdbc.query(
            SQL_FIND_ENABLED_SAML,
            mapOf("providerId" to providerId),
        ) { rs, _ ->
            RouteMatch(
                type = type,
                registrationId = rs.getString("registration_id"),
                displayName = rs.getString("display_name"),
            )
        }.firstOrNull()

    /** ② OIDC 분기 — 활성 oidc_provider_configs 단일 테이블 조회. 비활성/미존재면 null. */
    private fun findEnabledOidc(
        providerId: UUID,
        type: ProviderType,
    ): RouteMatch? =
        jdbc.query(
            SQL_FIND_ENABLED_OIDC,
            mapOf("providerId" to providerId),
        ) { rs, _ ->
            RouteMatch(
                type = type,
                registrationId = rs.getString("registration_id"),
                displayName = rs.getString("display_name"),
            )
        }.firstOrNull()

    private companion object {
        const val SQL_FIND_PROVIDER_BY_DOMAIN = """
            SELECT ap.id AS provider_id, ap.type AS type
            FROM domain_provider_routes dpr
            JOIN authn_providers ap ON ap.id = dpr.provider_id
            WHERE dpr.domain = :domain
        """

        const val SQL_FIND_ENABLED_SAML = """
            SELECT registration_id, display_name
            FROM saml_idp_configs
            WHERE authn_provider_id = :providerId AND enabled = TRUE
        """

        const val SQL_FIND_ENABLED_OIDC = """
            SELECT registration_id, display_name
            FROM oidc_provider_configs
            WHERE authn_provider_id = :providerId AND enabled = TRUE
        """
    }
}
