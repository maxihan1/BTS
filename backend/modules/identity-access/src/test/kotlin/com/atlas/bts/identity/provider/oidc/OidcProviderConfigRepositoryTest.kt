// OidcProviderConfigRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway V001~V011 적용

package com.atlas.bts.identity.provider.oidc

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * OidcProviderConfigRepository 통합 테스트 (FR-AU-04).
 * @JdbcTest + Testcontainers PostgreSQL + Flyway V001~V011 자동 적용.
 *
 * V011 이 시드한 OIDC authn_providers seed row(고정 UUID) 를 FK 로 참조해
 * oidc_provider_configs row 를 INSERT 한 뒤 read 한다.
 * enabled=false row 는 findEnabled 결과에서 제외되는지(EC5) 검증한다.
 * client_secret_encrypted 는 암호화된 채로 읽힌다(복호화는 repo 책임 아님).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OidcProviderConfigRepository::class)
@Testcontainers
class OidcProviderConfigRepositoryTest {
    companion object {
        /** V011 이 INSERT 하는 OIDC authn_providers seed row 의 고정 UUID */
        private val OIDC_PROVIDER_ID: UUID = UUID.fromString("00000000-0000-4a04-8000-000000000004")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            r.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired
    private lateinit var repo: OidcProviderConfigRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM oidc_provider_configs", emptyMap<String, Any>())
    }

    private fun insertConfig(
        registrationId: String,
        enabled: Boolean,
        secretEncrypted: String = "deadbeef_ciphertext",
        scopes: String = "openid,profile,email",
    ) {
        jdbc.update(
            """
            INSERT INTO oidc_provider_configs
                (id, registration_id, display_name, issuer_uri, client_id,
                 client_secret_encrypted, scopes, authn_provider_id, enabled)
            VALUES (:id, :reg, :name, :issuer, :clientId, :secret, :scopes, :providerId, :enabled)
            """.trimIndent(),
            mapOf(
                "id" to UUID.randomUUID(),
                "reg" to registrationId,
                "name" to "Test OIDC",
                "issuer" to "https://idp.example.com",
                "clientId" to "bts-client",
                "secret" to secretEncrypted,
                "scopes" to scopes,
                "providerId" to OIDC_PROVIDER_ID,
                "enabled" to enabled,
            ),
        )
    }

    @Test
    fun `findByRegistrationId — 매핑 없으면 null 반환`() {
        assertThat(repo.findByRegistrationId("nonexistent")).isNull()
    }

    @Test
    fun `findByRegistrationId — enabled row 를 모든 컬럼과 함께 반환 (secret 은 암호문 그대로)`() {
        insertConfig("keycloak", enabled = true, secretEncrypted = "abc123_ciphertext")

        val config = repo.findByRegistrationId("keycloak")

        assertThat(config).isNotNull()
        assertThat(config!!.registrationId).isEqualTo("keycloak")
        assertThat(config.displayName).isEqualTo("Test OIDC")
        assertThat(config.issuerUri).isEqualTo("https://idp.example.com")
        assertThat(config.clientId).isEqualTo("bts-client")
        // 복호화는 repo 책임 아님 — 암호문 그대로 읽힌다
        assertThat(config.clientSecretEncrypted).isEqualTo("abc123_ciphertext")
        assertThat(config.scopes).isEqualTo("openid,profile,email")
        assertThat(config.authnProviderId).isEqualTo(OIDC_PROVIDER_ID)
        assertThat(config.enabled).isTrue()
    }

    @Test
    fun `findByRegistrationId — enabled=false 도 조회는 가능 (해소 목적, EC5 필터는 findEnabled)`() {
        insertConfig("disabled-oidc", enabled = false)

        val config = repo.findByRegistrationId("disabled-oidc")

        assertThat(config).isNotNull()
        assertThat(config!!.enabled).isFalse()
    }

    @Test
    fun `findEnabled — row 가 없으면 빈 목록 반환`() {
        assertThat(repo.findEnabled()).isEmpty()
    }

    @Test
    fun `findEnabled — enabled row 만 반환하고 disabled 는 제외 (EC5)`() {
        insertConfig("keycloak", enabled = true)
        insertConfig("google", enabled = true)
        insertConfig("disabled-oidc", enabled = false)

        val configs = repo.findEnabled()

        assertThat(configs.map { it.registrationId })
            .containsExactlyInAnyOrder("keycloak", "google")
        assertThat(configs).allMatch({ it.enabled }, "enabled")
    }
}
