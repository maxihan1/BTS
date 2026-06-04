// saml_idp_configs 테이블 read 접근 Repository — plain JDBC + NamedParameterJdbcTemplate

package com.atlas.bts.identity.provider.saml

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * saml_idp_configs 테이블 read 전용 Repository (FR-AU-03 SAML SSO).
 *
 * SAML SSO 인증 흐름 진입 시 registration_id 로 IdP 설정을 조회한다.
 * 쓰기(설정 CRUD)는 관리 API(후속 Task)에서 별도 다룬다 — 본 Repository 는 read 만 책임진다.
 *
 * **활성 필터 (EC5)**:
 * [findEnabledByRegistrationId] 는 enabled=true row 만 반환한다. 비활성/미존재는 null.
 * Spring Security 의 RelyingPartyRegistrationRepository 계약(없으면 null)에 맞춘다.
 *
 * **UUID RowMapper**:
 * [ResultSet.getObject] + UUID::class.java 를 사용한다(Postgres JDBC 권장 방식,
 * UUID.fromString 캐스팅 회피 — ExternalAccountRepository 와 동일 규약).
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩한다.
 * 문자열 결합 금지 (DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(readOnly = true)
class SamlIdpConfigRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val rowMapper = SamlIdpConfigRowMapper()

    /**
     * registration_id 로 **활성(enabled=true)** SAML IdP 설정을 조회한다.
     * 비활성이거나 존재하지 않으면 null 을 반환한다 (EC5).
     */
    fun findEnabledByRegistrationId(registrationId: String): SamlIdpConfig? =
        jdbc.query(
            SQL_FIND_ENABLED_BY_REGISTRATION_ID,
            mapOf("registrationId" to registrationId),
            rowMapper,
        ).firstOrNull()

    /**
     * **활성(enabled=true)** SAML IdP 설정 전체를 display_name 오름차순으로 조회한다 (EC5).
     *
     * UI 로그인 폼의 SSO 선택지 노출(GET /api/v1/auth/saml/idps)에 사용한다.
     * 비활성 row 는 결과에서 제외된다. 매핑이 없으면 빈 목록을 반환한다.
     */
    fun findAllEnabled(): List<SamlIdpConfig> =
        jdbc.query(
            SQL_FIND_ALL_ENABLED,
            emptyMap<String, Any>(),
            rowMapper,
        )

    private companion object {
        const val SQL_FIND_ENABLED_BY_REGISTRATION_ID = """
            SELECT id, registration_id, display_name, idp_entity_id, idp_sso_url,
                   idp_x509_cert, authn_provider_id, enabled
            FROM saml_idp_configs
            WHERE registration_id = :registrationId AND enabled = TRUE
        """

        const val SQL_FIND_ALL_ENABLED = """
            SELECT id, registration_id, display_name, idp_entity_id, idp_sso_url,
                   idp_x509_cert, authn_provider_id, enabled
            FROM saml_idp_configs
            WHERE enabled = TRUE
            ORDER BY display_name ASC
        """
    }
}

/** SamlIdpConfig RowMapper — ResultSet → SamlIdpConfig 변환 */
private class SamlIdpConfigRowMapper : RowMapper<SamlIdpConfig> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): SamlIdpConfig =
        SamlIdpConfig(
            // getObject + UUID::class.java — Postgres JDBC 권장 방식
            id = rs.getObject("id", UUID::class.java),
            registrationId = rs.getString("registration_id"),
            displayName = rs.getString("display_name"),
            idpEntityId = rs.getString("idp_entity_id"),
            idpSsoUrl = rs.getString("idp_sso_url"),
            idpX509Cert = rs.getString("idp_x509_cert"),
            authnProviderId = rs.getObject("authn_provider_id", UUID::class.java),
            enabled = rs.getBoolean("enabled"),
        )
}
