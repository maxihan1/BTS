// oidc_provider_configs 테이블 read 접근 Repository — plain JDBC + NamedParameterJdbcTemplate

package com.atlas.bts.identity.provider.oidc

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * oidc_provider_configs read 계약 — [DbClientRegistrationRepository] 가 의존하는 최소 인터페이스.
 *
 * 변환 로직(ClientRegistration 빌드)을 DB/네트워크 없이 단위 테스트할 수 있도록 read 를 추상화한다.
 * 프로덕션 구현은 [OidcProviderConfigRepository].
 */
interface OidcProviderConfigReader {
    /**
     * registration_id 로 OIDC Provider 설정을 조회한다(활성/비활성 무관).
     * 성공 핸들러의 registration_id → authn_provider_id 해소에도 사용된다.
     * 존재하지 않으면 null.
     */
    fun findByRegistrationId(registrationId: String): OidcProviderConfig?

    /**
     * registration_id 로 **활성(enabled=true)** OIDC Provider 설정만 조회한다 (FR-AU-08b B1).
     *
     * SSO 연결/재인증 콜백 시 start↔콜백 TOCTOU 를 차단하기 위해 enabled 를 재해소한다
     * (SAML `findEnabledByRegistrationId` 동형 — OIDC 의 enabled 미필터 비대칭 보정, EC16).
     * 비활성이거나 존재하지 않으면 null. 핸들러가 인터페이스 타입에 의존하므로 인터페이스에 둔다.
     */
    fun findEnabledByRegistrationId(registrationId: String): OidcProviderConfig?
}

/**
 * oidc_provider_configs 테이블 read 전용 Repository (FR-AU-04 OIDC SSO).
 *
 * OIDC SSO 인증 흐름 진입/콜백 시 registration_id 로 Provider 설정을 조회한다.
 * 쓰기(설정 CRUD)는 관리 API(후속 Task)에서 별도 다룬다 — 본 Repository 는 read 만 책임진다.
 *
 * **메서드 구분**:
 * - [findEnabled] 는 enabled=true row 만 반환한다(EC5 — 비활성 Provider 는 SSO 비노출).
 * - [findByRegistrationId] 는 활성/비활성 무관하게 조회한다(성공 핸들러의 authn_provider_id 해소용).
 *   활성 필터는 ClientRegistration 노출 시점([DbClientRegistrationRepository])에서 별도로 다룬다.
 *
 * **UUID RowMapper**: [ResultSet.getObject] + UUID::class.java (Postgres JDBC 권장 방식).
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩한다.
 * 문자열 결합 금지 (DATA.md §5).
 */
@Repository
@Transactional(readOnly = true)
class OidcProviderConfigRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : OidcProviderConfigReader {
    private val rowMapper = OidcProviderConfigRowMapper()

    /**
     * registration_id 로 OIDC Provider 설정을 조회한다(활성/비활성 무관).
     * 존재하지 않으면 null 을 반환한다.
     */
    override fun findByRegistrationId(registrationId: String): OidcProviderConfig? =
        jdbc.query(
            SQL_FIND_BY_REGISTRATION_ID,
            mapOf("registrationId" to registrationId),
            rowMapper,
        ).firstOrNull()

    /** RED 골격 — 아직 미구현. */
    override fun findEnabledByRegistrationId(registrationId: String): OidcProviderConfig? = null

    /**
     * **활성(enabled=true)** OIDC Provider 설정 전체를 display_name 오름차순으로 조회한다 (EC5).
     *
     * 로그인 화면의 SSO 선택지 노출/ClientRegistration 등록에 사용한다.
     * 비활성 row 는 결과에서 제외된다. 매핑이 없으면 빈 목록을 반환한다.
     */
    fun findEnabled(): List<OidcProviderConfig> =
        jdbc.query(
            SQL_FIND_ENABLED,
            emptyMap<String, Any>(),
            rowMapper,
        )

    private companion object {
        const val SQL_FIND_BY_REGISTRATION_ID = """
            SELECT id, registration_id, display_name, issuer_uri, client_id,
                   client_secret_encrypted, scopes, authn_provider_id, enabled
            FROM oidc_provider_configs
            WHERE registration_id = :registrationId
        """

        const val SQL_FIND_ENABLED = """
            SELECT id, registration_id, display_name, issuer_uri, client_id,
                   client_secret_encrypted, scopes, authn_provider_id, enabled
            FROM oidc_provider_configs
            WHERE enabled = TRUE
            ORDER BY display_name ASC
        """
    }
}

/** OidcProviderConfig RowMapper — ResultSet → OidcProviderConfig 변환 */
private class OidcProviderConfigRowMapper : RowMapper<OidcProviderConfig> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): OidcProviderConfig =
        OidcProviderConfig(
            // getObject + UUID::class.java — Postgres JDBC 권장 방식
            id = rs.getObject("id", UUID::class.java),
            registrationId = rs.getString("registration_id"),
            displayName = rs.getString("display_name"),
            issuerUri = rs.getString("issuer_uri"),
            clientId = rs.getString("client_id"),
            clientSecretEncrypted = rs.getString("client_secret_encrypted"),
            scopes = rs.getString("scopes"),
            authnProviderId = rs.getObject("authn_provider_id", UUID::class.java),
            enabled = rs.getBoolean("enabled"),
        )
}
