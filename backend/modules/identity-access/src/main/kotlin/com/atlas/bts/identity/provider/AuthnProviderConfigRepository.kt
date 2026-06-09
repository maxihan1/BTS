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
     * 주어진 Provider 타입이 활성 상태인지 반환한다.
     *
     * authn_providers 에 row 가 있으면 그 row 의 enabled 값을 반환한다.
     * row 가 아예 없으면(미등록 type) true 를 반환한다 — fail-open.
     */
    fun isEnabled(type: ProviderType): Boolean {
        val params = MapSqlParameterSource("type", type.name)
        val rows = jdbc.queryForList(SQL_IS_ENABLED, params, Boolean::class.java)
        return rows.firstOrNull() ?: true
    }

    /**
     * 주어진 type 중 enabled=true 인 것의 (type, sort_order) 를 sort_order 오름차순으로 반환한다.
     *
     * authn_providers 에 row 가 없는 type 은 결과에서 빠진다.
     */
    fun listEnabledByTypes(types: List<ProviderType>): List<Pair<ProviderType, Int>> {
        if (types.isEmpty()) return emptyList()
        val params = MapSqlParameterSource("types", types.map { it.name })
        return jdbc.query(SQL_LIST_ENABLED, params) { rs, _ ->
            ProviderType.valueOf(rs.getString("type")) to rs.getInt("sort_order")
        }
    }

    private companion object {
        const val SQL_IS_ENABLED =
            "SELECT enabled FROM authn_providers WHERE type = :type LIMIT 1"

        const val SQL_LIST_ENABLED =
            "SELECT type, sort_order FROM authn_providers " +
                "WHERE type IN (:types) AND enabled = true ORDER BY sort_order ASC"
    }
}
