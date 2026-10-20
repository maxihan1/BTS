// 기동 때 env(BTS_AUTH_PLATFORM_*)로 Auth_platform OIDC 등록 행(registration_id=auth)을 맞추는 러너 (FR-AU-04)

package com.atlas.bts.identity.provider.oidc

import com.bts.shared.crypto.SecretEncryptor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 사내 통합 로그인 Auth_platform 을 OIDC provider 로 등록한다.
 *
 * `oidc_provider_configs` 는 관리 API 가 없고(쓰기는 SQL 뿐) `client_secret_encrypted` 는
 * [SecretEncryptor] 암호문이라 손 SQL 로 넣을 수 없다. 그래서 env 로 받은 값을 기동마다 upsert 한다 —
 * 운영도 env 만 넣으면 된다. issuer 가 비면 아무것도 하지 않는다 (기본).
 *
 * 콜백은 `{baseUrl}/login/oauth2/code/auth` — Auth_platform 에 이 주소를 redirect URI 로 등록한다.
 * secret 은 로그에 남기지 않는다.
 */
@Component
class AuthPlatformRegistrationRunner(
    private val jdbc: NamedParameterJdbcTemplate,
    @param:Qualifier("oidcSecretEncryptor") private val secretEncryptor: SecretEncryptor,
    @param:Value("\${bts.auth-platform.issuer-uri:}") private val issuerUri: String,
    @param:Value("\${bts.auth-platform.client-id:bts}") private val clientId: String,
    @param:Value("\${bts.auth-platform.client-secret:}") private val clientSecret: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(AuthPlatformRegistrationRunner::class.java)

    override fun run(args: ApplicationArguments) {
        if (issuerUri.isBlank()) return
        jdbc.update(
            """
            INSERT INTO oidc_provider_configs
                (id, registration_id, display_name, issuer_uri, client_id, client_secret_encrypted, scopes, authn_provider_id)
            VALUES (:id, :reg, :name, :issuer, :clientId, :secret, 'openid,profile,email', :providerId)
            ON CONFLICT (registration_id) DO UPDATE SET
                issuer_uri = EXCLUDED.issuer_uri, client_id = EXCLUDED.client_id,
                client_secret_encrypted = EXCLUDED.client_secret_encrypted, enabled = true, updated_at = NOW()
            """.trimIndent(),
            mapOf(
                "id" to UUID.randomUUID(),
                "reg" to REGISTRATION_ID,
                "name" to DISPLAY_NAME,
                "issuer" to issuerUri,
                "clientId" to clientId,
                "secret" to secretEncryptor.encrypt(clientSecret),
                "providerId" to OIDC_PROVIDER_ID,
            ),
        )
        log.info("Auth_platform OIDC 등록 — registrationId={}, issuer={}", REGISTRATION_ID, issuerUri)
    }

    internal companion object {
        /** 이 provider 만 preferred_username 을 BTS username 으로 믿는다 ([OidcAuthenticationSuccessHandler]) */
        const val REGISTRATION_ID = "auth"
        const val DISPLAY_NAME = "maxihan 계정"

        /** V011 이 넣은 OIDC authn_providers seed 행 */
        val OIDC_PROVIDER_ID: UUID = UUID.fromString("00000000-0000-4a04-8000-000000000004")
    }
}
