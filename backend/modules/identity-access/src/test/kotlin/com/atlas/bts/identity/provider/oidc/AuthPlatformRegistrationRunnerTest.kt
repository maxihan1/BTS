// AuthPlatformRegistrationRunner 통합 테스트 — env 값으로 registration_id=auth 행을 upsert 하는지 (Testcontainers PostgreSQL)

package com.atlas.bts.identity.provider.oidc

import com.atlas.bts.identity.support.SharedPostgres
import com.bts.shared.crypto.SecretEncryptor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OidcProviderConfigRepository::class)
class AuthPlatformRegistrationRunnerTest {
    companion object {
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            r.add("spring.flyway.enabled") { "false" }
            r.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
        }
    }

    @Autowired
    private lateinit var repo: OidcProviderConfigRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private val encryptor = SecretEncryptor("test-app-encryption-key-for-oidc", "deadbeefcafef00d")

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM oidc_provider_configs", emptyMap<String, Any>())
    }

    private fun run(
        issuer: String,
        secret: String = "local-bts-secret",
    ) = AuthPlatformRegistrationRunner(jdbc, encryptor, issuer, "bts", secret).run(DefaultApplicationArguments())

    @Test
    fun `issuer 가 있으면 registration_id auth 행을 만들고 secret 은 복호화하면 원문이다`() {
        run("http://localhost:9000")

        val row = repo.findByRegistrationId("auth")!!
        assertThat(row.issuerUri).isEqualTo("http://localhost:9000")
        assertThat(row.clientId).isEqualTo("bts")
        assertThat(row.clientSecretEncrypted).isNotEqualTo("local-bts-secret")
        assertThat(encryptor.decrypt(row.clientSecretEncrypted)).isEqualTo("local-bts-secret")
        assertThat(row.scopes).isEqualTo("openid,profile,email")
        assertThat(row.enabled).isTrue()
    }

    @Test
    fun `다시 돌면 같은 행을 새 값으로 갱신하되 관리자가 끈 행은 그대로 꺼 둔다 (kill switch)`() {
        run("http://localhost:9000", secret = "old")
        jdbc.update("UPDATE oidc_provider_configs SET enabled = false", emptyMap<String, Any>())

        run("https://auth.maxihan.com", secret = "new")

        val count =
            jdbc.queryForObject("SELECT count(*) FROM oidc_provider_configs", emptyMap<String, Any>(), Int::class.java)
        assertThat(count).isEqualTo(1)
        val row = repo.findByRegistrationId("auth")!!
        assertThat(row.issuerUri).isEqualTo("https://auth.maxihan.com")
        assertThat(encryptor.decrypt(row.clientSecretEncrypted)).isEqualTo("new")
        assertThat(row.enabled).isFalse()
    }

    @Test
    fun `issuer 는 있는데 secret 이 비면 기동을 멈춘다 (로그인 때 invalid_client 로 늦게 알게 되지 않게)`() {
        assertThatThrownBy { run("http://localhost:9000", secret = "") }
            .hasMessageContaining("BTS_AUTH_PLATFORM_CLIENT_SECRET")
    }

    @Test
    fun `issuer 가 비면 아무것도 하지 않는다`() {
        run("")

        assertThat(repo.findByRegistrationId("auth")).isNull()
    }
}
