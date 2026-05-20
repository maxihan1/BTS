// authn_providers 테이블에서 활성 LDAP 설정을 로드하는 서비스

package com.atlas.bts.identity.provider.ldap

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * authn_providers 테이블에서 활성 LDAP Provider 설정(LdapConfig)을 로드한다 (FR-AU-02).
 *
 * 단일 LDAP Provider 가정 (FR-AU-06 이전).
 * 결과가 null 이면 LdapProvider 가 Failure(PROVIDER_UNAVAILABLE) 을 반환한다.
 */
@Service
class LdapProviderConfigService(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(LdapProviderConfigService::class.java)

    // ObjectMapper: Bean 의존 없이 독립 인스턴스 (컨텍스트 로드 단순화)
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /**
     * 활성 LDAP Provider 설정을 반환한다.
     * @return Pair(providerId, LdapConfig) 또는 null (미설정 / 비활성 시)
     */
    @Suppress("TooGenericExceptionCaught")
    fun findEnabledLdapConfig(): Pair<UUID, LdapConfig>? {
        return try {
            val rows =
                jdbc.query(
                    "SELECT id, config FROM authn_providers WHERE type = 'LDAP' AND enabled = true LIMIT 1",
                    emptyMap<String, Any>(),
                ) { rs, _ ->
                    val id = UUID.fromString(rs.getString("id"))
                    val config = objectMapper.readValue(rs.getString("config"), LdapConfig::class.java)
                    Pair(id, config)
                }
            rows.firstOrNull()
        } catch (ex: Exception) {
            log.warn("LDAP provider 설정 로드 실패: {}", ex.message)
            null
        }
    }
}
