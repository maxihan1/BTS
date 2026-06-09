// authn_providers 테이블의 enabled/sort_order 조회 repository (FR-AU-06)

package com.atlas.bts.identity.provider

import com.atlas.bts.identity.spi.ProviderType
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * authn_providers 테이블에서 Provider 의 활성 여부와 정렬 순서를 조회한다 (FR-AU-06).
 *
 * login 디스패처와 providers 목록 API 가 공유한다. config(JSONB) 는 다루지 않고
 * enabled/sort_order 만 본다.
 */
@Repository
class AuthnProviderConfigRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * 주어진 Provider 타입이 활성 상태인지 fail-safe 로 판정해 반환한다.
     *
     * authn_providers 에 해당 type 의 enabled=false row 가 하나라도 있으면 false(비활성),
     * 그 외(비활성 row 없음 또는 row 자체 없음)는 true(활성)를 반환한다.
     *
     * ## 왜 fail-safe(비활성 row 하나면 비활성)인가
     * authn_providers 는 type 에 UNIQUE 제약이 없어 한 type 에 여러 Provider row 가
     * 공존할 수 있다(V002 스키마). 운영자가 같은 type 에 enabled=true·false row 를
     * 둘 다 넣은 모순 상태에서, LIMIT 1 의 반환 순서에 의존하면 활성 판정이 비결정적이 된다.
     * 따라서 끄려는 의도(enabled=false)가 하나라도 있으면 비활성으로 본다 — 결정적이며
     * 보안상 안전한 쪽으로 기운다.
     *
     * ## row 가 아예 없으면 활성인 이유
     * authn_providers 는 운영자가 특정 Provider 를 끄기 위해 enabled=false row 를 등록하는
     * 용도다. LOCAL/LDAP 은 코드 Bean(LocalProvider/LdapProvider)으로 상시 구현·존재하므로,
     * row 미등록 = 비활성화 의도 없음 = 활성으로 본다. SAML/OIDC 는 V010/V011 마이그레이션에서
     * row 가 seed 되므로 이 경로를 타지 않고 등록된 enabled 값을 따른다.
     */
    fun isEnabled(type: ProviderType): Boolean {
        val params = MapSqlParameterSource("type", type.name)
        val disabledRows = jdbc.queryForList(SQL_DISABLED_EXISTS, params, Int::class.java)
        return disabledRows.isEmpty()
    }

    /**
     * 주어진 type 중 enabled=true 인 것의 (type, sort_order) 를 sort_order 오름차순으로 반환한다.
     *
     * authn_providers 에 row 가 없는 type 은 결과에서 빠진다 — 목록의 동적 구성은
     * 호출자가 코드 Bean 목록과 이 결과를 결합해 결정한다.
     */
    fun listEnabledByTypes(types: List<ProviderType>): List<Pair<ProviderType, Int>> {
        if (types.isEmpty()) return emptyList()
        val params = MapSqlParameterSource("types", types.map { it.name })
        return jdbc.query(SQL_LIST_ENABLED, params) { rs, _ ->
            ProviderType.valueOf(rs.getString("type")) to rs.getInt("sort_order")
        }
    }

    private companion object {
        const val SQL_DISABLED_EXISTS =
            "SELECT 1 FROM authn_providers WHERE type = :type AND enabled = false LIMIT 1"

        const val SQL_LIST_ENABLED =
            "SELECT type, sort_order FROM authn_providers " +
                "WHERE type IN (:types) AND enabled = true ORDER BY sort_order ASC"
    }
}
