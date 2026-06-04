// SamlIdpConfigRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway V001~V010 적용

package com.atlas.bts.identity.provider.saml

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
 * SamlIdpConfigRepository 통합 테스트 (FR-AU-03).
 * @JdbcTest + Testcontainers PostgreSQL + Flyway V001~V010 자동 적용.
 *
 * V010 이 시드한 SAML authn_providers seed row(고정 UUID) 를 FK 로 참조해
 * saml_idp_configs row 를 INSERT 한 뒤 registration_id 로 read 한다.
 * enabled=false row 는 read 결과에서 제외되는지(EC5) 검증한다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SamlIdpConfigRepository::class)
@Testcontainers
class SamlIdpConfigRepositoryTest {
    companion object {
        /** V010 이 INSERT 하는 SAML authn_providers seed row 의 고정 UUID */
        private val SAML_PROVIDER_ID: UUID = UUID.fromString("00000000-0000-4a03-8000-000000000003")

        private const val TEST_CERT_PEM =
            "-----BEGIN CERTIFICATE-----\n" +
                "MIIDBzCCAe+gAwIBAgIUI4yv17NnJ0u2j4PKWMm2cvVjLCcwDQYJKoZIhvcNAQEL\n" +
                "BQAwEzERMA8GA1UEAwwIdGVzdC1pZHAwHhcNMjYwNjA0MDA0ODM5WhcNMzYwNjAx\n" +
                "MDA0ODM5WjATMREwDwYDVQQDDAh0ZXN0LWlkcDCCASIwDQYJKoZIhvcNAQEBBQAD\n" +
                "ggEPADCCAQoCggEBALSprleUH2lVcARLIBrsj5hpcHtKB1dtq7b/aAyCHuRyMGgE\n" +
                "entbfQWsV9vnY7eho8Ca0b3LALKnv/ThiJg7R0WD9jeevW0IRkHAGO7sLlO0a4er\n" +
                "M3ngLZIKPJGq0dw3I29QwOeLnTukyDx7C+BXNFeBGiloALuMTXXeUnjtPN2xgg9h\n" +
                "fjFXVqj9OFHMiSyKvJ4jowbmnhL78vmzetmKZEDbo4nBUmS/XEtmLLpbvHXPo5qN\n" +
                "1c6dYg3kXN8A8/gQzUCpcO0SkasfXwBuMJs4UavjCDkG07rFwN4V3l8GSJNBHXcx\n" +
                "A1kuruYsbu5dkO+lGSnhNkXBi+PRjbOMfP1xJQ8CAwEAAaNTMFEwHQYDVR0OBBYE\n" +
                "FFAws2b5nhMkC83ZBl8g5e7J8mc6MB8GA1UdIwQYMBaAFFAws2b5nhMkC83ZBl8g\n" +
                "5e7J8mc6MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAJcjAA2t\n" +
                "8ZCAKaJ36Lw12VnOvKp9XVbuB2GJTAe8o/yjMv8LWMfRSNFpVzAnwmrf62koxiUy\n" +
                "scGihyaIo1SPjaP9LulfQCbTbAIS0ukwp/8EM6iUq4/eqbimJ1Pwk/36KMVHmonC\n" +
                "yWkUL0Zn9X3an/IoP4JmW0A+CCOJeqCTUO9S4Pcku43yOgCGlvtKwTqMzR4av2nJ\n" +
                "yUkoCIJIpntFyeKR53wcKXvSoOrRbQv4KsBkJRiUgDHsaNEuKc7I2sXyzIN6VRgJ\n" +
                "CyBTeGRKICMQqNaXWgJSzbpYm2DWsd1qe9ldRmAADeZ6WZhA1pOPR4IsUB7ASnkE\n" +
                "B4GPDShOZ2wzEe0=\n" +
                "-----END CERTIFICATE-----\n"

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
    private lateinit var repo: SamlIdpConfigRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM saml_idp_configs", emptyMap<String, Any>())
    }

    private fun insertConfig(
        registrationId: String,
        enabled: Boolean,
    ) {
        jdbc.update(
            """
            INSERT INTO saml_idp_configs
                (id, registration_id, display_name, idp_entity_id, idp_sso_url, idp_x509_cert,
                 authn_provider_id, enabled)
            VALUES (:id, :reg, :name, :entityId, :ssoUrl, :cert, :providerId, :enabled)
            """.trimIndent(),
            mapOf(
                "id" to UUID.randomUUID(),
                "reg" to registrationId,
                "name" to "Test IdP",
                "entityId" to "https://idp.example.com/entity",
                "ssoUrl" to "https://idp.example.com/sso",
                "cert" to TEST_CERT_PEM,
                "providerId" to SAML_PROVIDER_ID,
                "enabled" to enabled,
            ),
        )
    }

    @Test
    fun `findEnabledByRegistrationId — 매핑 없으면 null 반환`() {
        assertThat(repo.findEnabledByRegistrationId("nonexistent")).isNull()
    }

    @Test
    fun `findEnabledByRegistrationId — enabled row 를 모든 컬럼과 함께 반환`() {
        insertConfig("okta", enabled = true)

        val config = repo.findEnabledByRegistrationId("okta")

        assertThat(config).isNotNull()
        assertThat(config!!.registrationId).isEqualTo("okta")
        assertThat(config.displayName).isEqualTo("Test IdP")
        assertThat(config.idpEntityId).isEqualTo("https://idp.example.com/entity")
        assertThat(config.idpSsoUrl).isEqualTo("https://idp.example.com/sso")
        assertThat(config.idpX509Cert).contains("BEGIN CERTIFICATE")
        assertThat(config.authnProviderId).isEqualTo(SAML_PROVIDER_ID)
        assertThat(config.enabled).isTrue()
    }

    @Test
    fun `findEnabledByRegistrationId — enabled=false row 는 제외 (EC5)`() {
        insertConfig("disabled-idp", enabled = false)

        assertThat(repo.findEnabledByRegistrationId("disabled-idp")).isNull()
    }
}
