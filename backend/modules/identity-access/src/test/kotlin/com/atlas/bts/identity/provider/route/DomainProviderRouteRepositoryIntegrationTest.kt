// DomainProviderRouteRepository 통합테스트 (prod 프로파일 + 실 PostgreSQL 16) — FR-AU-07 Task 1

package com.atlas.bts.identity.provider.route

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.spi.ProviderType
import com.atlas.bts.identity.support.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.ldap.core.LdapTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security
import java.util.UUID

/**
 * [DomainProviderRouteRepository] 통합테스트 (FR-AU-07 Task 1).
 *
 * ## 목적
 * prod 프로파일에서 실 PostgreSQL 16(Testcontainers) 위에 V020 마이그레이션이 적용된 뒤,
 * [DomainProviderRouteRepository.findRouteByDomain] 의 도메인→Provider 라우트 매칭을 end-to-end 검증한다.
 *
 * ## 매칭 판정 (type 분기 2-step)
 * ① domain_provider_routes JOIN authn_providers 로 (provider_id, type) 를 얻고,
 * ② type=SAML 이면 saml_idp_configs, type=OIDC 이면 oidc_provider_configs **단일 테이블**에서
 *    enabled=true 인 registration_id/display_name 을 조회한다.
 * LOCAL/LDAP·비활성·미등록은 결과가 없어 null 을 반환한다(fail-safe).
 *
 * ## 시나리오
 * - S1 partner.com → SAML 매칭(registrationId, displayName 정확)
 * - S2 acme.com → OIDC 매칭
 * - S3 gmail.com(미등록) → null
 * - S4 dead.com(LOCAL 라우트 지시) → null (fail-safe)
 * - S5 off-saml.com/off-oidc.com(enabled=false config 지시) → null (fail-safe 회귀 가드, 코드리뷰 CONCERN #1)
 *
 * 대소문자/공백 정규화는 Controller(Task 2) 책임이므로 여기서 검증하지 않는다.
 * 본 Repository 는 받은 값을 그대로 조회한다.
 *
 * ## Testcontainers 설계
 * 같은 모듈 [com.atlas.bts.identity.group.JdbcUserGroupRepositoryIntegrationTest] 와 동일한
 * prod 부팅 셋업(static @Container + @DynamicPropertySource + PEM 파일 + LDAP @MockBean 5종)을 복제한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
class DomainProviderRouteRepositoryIntegrationTest {
    companion object {
        /**
         * 공용 컨테이너의 템플릿 DB 를 복제한 전용 데이터베이스.
         *
         * 격리는 그대로이고 컨테이너 기동과 마이그레이션 재적용만 사라진다.
         * 근거와 주의점은 [com.atlas.bts.identity.support.SharedPostgres] 헤더.
         */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            // 템플릿 DB 에서 이미 적용됐다 — 여기서 다시 돌리면 이 최적화가 무의미해진다
            registry.add("spring.flyway.enabled") { "false" }
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면 기본 풀(10)로는
            // max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            registry.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
        }

        /**
         * 테스트용 임시 RSA 2048 PEM 파일 경로 (PemFileKeyProvider 요구).
         * PemFileKeyProvider 가 BouncyCastle PEMParser 를 사용하므로 BC provider 를 먼저 등록한다.
         */
        val pemFilePath: String =
            run {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val privKey = keyPair.private
                val pemContent =
                    buildString {
                        appendLine("-----BEGIN PRIVATE KEY-----")
                        val mimeEncoder = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
                        appendLine(mimeEncoder.encodeToString(privKey.encoded))
                        append("-----END PRIVATE KEY-----")
                    }
                val tmpFile = Files.createTempFile("bts-test-key-", ".pem")
                Files.writeString(tmpFile, pemContent)
                tmpFile.toAbsolutePath().toString()
            }
    }

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 prod 컨텍스트 부팅 (선례 동일)
    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    @Autowired
    private lateinit var repository: DomainProviderRouteRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────
    // V010/V011 이 시드하는 'SAML SSO'/'OIDC SSO' provider 와 name 충돌을 피하기 위해
    // 테스트 전용 name(fr-au-07-t1-*)과 고유 UUID 를 사용한다.

    private val samlProviderId: UUID = UUID.fromString("00000000-0a07-0001-0000-000000000001")
    private val oidcProviderId: UUID = UUID.fromString("00000000-0a07-0001-0000-000000000002")

    /**
     * LOCAL provider 시드 — 정상 운영에선 LOCAL 이 authn_providers 에 없다(코드 Bean 만 존재).
     * 이 행은 "운영자가 실수로 LOCAL 라우트를 만든 비정상 상태"를 repository 가 fail-safe 로
     * 막는지(S4) 검증하기 위한 의도적 시드다.
     */
    private val localProviderId: UUID = UUID.fromString("00000000-0a07-0001-0000-000000000003")

    /**
     * 비활성(enabled=false) SAML/OIDC config 를 가리키는 provider 시드 — S5 fail-safe 회귀 가드.
     * saml_idp_configs/oidc_provider_configs 의 `enabled = TRUE` 필터가 누군가 실수로 제거되면
     * 이 도메인들이 null 대신 매칭되어 본 테스트가 실패한다(회귀 검출).
     */
    private val disabledSamlProviderId: UUID = UUID.fromString("00000000-0a07-0001-0000-000000000004")
    private val disabledOidcProviderId: UUID = UUID.fromString("00000000-0a07-0001-0000-000000000005")

    @BeforeEach
    fun setUp() {
        cleanUp()
        seedProviders()
        seedSamlIdpConfig()
        seedOidcProviderConfig()
        seedDisabledSamlIdpConfig()
        seedDisabledOidcProviderConfig()
        seedRoutes()
    }

    // ── S1: partner.com → SAML 매칭 ─────────────────────────────────────────────

    @Test
    fun `partner_com은 SAML 라우트로 registrationId와 displayName이 정확히 매칭된다`() {
        val match = repository.findRouteByDomain("partner.com")

        assertThat(match).isNotNull()
        assertThat(match!!.type).isEqualTo(ProviderType.SAML)
        assertThat(match.registrationId).isEqualTo("fr-au-07-t1-saml-reg")
        assertThat(match.displayName).isEqualTo("Partner SAML")
    }

    // ── S2: acme.com → OIDC 매칭 ────────────────────────────────────────────────

    @Test
    fun `acme_com은 OIDC 라우트로 registrationId와 displayName이 정확히 매칭된다`() {
        val match = repository.findRouteByDomain("acme.com")

        assertThat(match).isNotNull()
        assertThat(match!!.type).isEqualTo(ProviderType.OIDC)
        assertThat(match.registrationId).isEqualTo("fr-au-07-t1-oidc-reg")
        assertThat(match.displayName).isEqualTo("Acme OIDC")
    }

    // ── S3: 미등록 도메인 → null ─────────────────────────────────────────────────

    @Test
    fun `미등록 도메인 gmail_com은 null을 반환한다`() {
        assertThat(repository.findRouteByDomain("gmail.com")).isNull()
    }

    // ── S4: LOCAL 라우트 지시 → null (fail-safe) ────────────────────────────────

    @Test
    fun `dead_com은 LOCAL 라우트를 지시해도 null을 반환한다 fail-safe`() {
        assertThat(repository.findRouteByDomain("dead.com")).isNull()
    }

    // ── S5: 비활성(enabled=false) config 지시 → null (fail-safe 회귀 가드) ──────────
    // 구현의 `AND enabled = TRUE` 필터(SQL_FIND_ENABLED_SAML/OIDC)가 제거되면 이 두 테스트가 실패한다.

    @Test
    fun `off-saml_com은 비활성 SAML config를 지시하므로 null을 반환한다 fail-safe`() {
        assertThat(repository.findRouteByDomain("off-saml.com")).isNull()
    }

    @Test
    fun `off-oidc_com은 비활성 OIDC config를 지시하므로 null을 반환한다 fail-safe`() {
        assertThat(repository.findRouteByDomain("off-oidc.com")).isNull()
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

    private fun cleanUp() {
        jdbc.update(
            "DELETE FROM domain_provider_routes WHERE domain IN (:domains)",
            mapOf("domains" to listOf("partner.com", "acme.com", "dead.com", "off-saml.com", "off-oidc.com")),
        )
        jdbc.update(
            "DELETE FROM saml_idp_configs WHERE registration_id IN (:regs)",
            mapOf("regs" to listOf("fr-au-07-t1-saml-reg", "fr-au-07-t1-saml-off-reg")),
        )
        jdbc.update(
            "DELETE FROM oidc_provider_configs WHERE registration_id IN (:regs)",
            mapOf("regs" to listOf("fr-au-07-t1-oidc-reg", "fr-au-07-t1-oidc-off-reg")),
        )
        jdbc.update(
            "DELETE FROM authn_providers WHERE id IN (:ids)",
            mapOf(
                "ids" to
                    listOf(
                        samlProviderId,
                        oidcProviderId,
                        localProviderId,
                        disabledSamlProviderId,
                        disabledOidcProviderId,
                    ),
            ),
        )
    }

    private fun seedProviders() {
        listOf(
            Triple(samlProviderId, "SAML", "fr-au-07-t1-saml"),
            Triple(oidcProviderId, "OIDC", "fr-au-07-t1-oidc"),
            // LOCAL — 비정상 상태 재현용 의도적 시드 (S4 fail-safe 검증)
            Triple(localProviderId, "LOCAL", "fr-au-07-t1-local"),
            // 비활성 config 를 가리킬 provider (S5 fail-safe 회귀 가드)
            Triple(disabledSamlProviderId, "SAML", "fr-au-07-t1-saml-off"),
            Triple(disabledOidcProviderId, "OIDC", "fr-au-07-t1-oidc-off"),
        ).forEach { (id, type, name) ->
            jdbc.update(
                "INSERT INTO authn_providers (id, type, name, config) VALUES (:id, :type, :name, '{}'::jsonb)",
                mapOf("id" to id, "type" to type, "name" to name),
            )
        }
    }

    private fun seedSamlIdpConfig() {
        jdbc.update(
            """
            INSERT INTO saml_idp_configs
                (registration_id, display_name, idp_entity_id, idp_sso_url, idp_x509_cert, authn_provider_id, enabled)
            VALUES
                (:reg, :name, :entityId, :ssoUrl, :cert, :providerId, TRUE)
            """,
            mapOf(
                "reg" to "fr-au-07-t1-saml-reg",
                "name" to "Partner SAML",
                "entityId" to "urn:partner:idp",
                "ssoUrl" to "https://idp.partner.com/sso",
                "cert" to "-----BEGIN CERTIFICATE-----\nTEST\n-----END CERTIFICATE-----",
                "providerId" to samlProviderId,
            ),
        )
    }

    private fun seedOidcProviderConfig() {
        jdbc.update(
            """
            INSERT INTO oidc_provider_configs
                (registration_id, display_name, issuer_uri, client_id, client_secret_encrypted, authn_provider_id, enabled)
            VALUES
                (:reg, :name, :issuer, :clientId, :secret, :providerId, TRUE)
            """,
            mapOf(
                "reg" to "fr-au-07-t1-oidc-reg",
                "name" to "Acme OIDC",
                "issuer" to "https://idp.acme.com",
                "clientId" to "acme-client",
                "secret" to "enc:dummy",
                "providerId" to oidcProviderId,
            ),
        )
    }

    /** S5 — enabled=FALSE 인 SAML config. `AND enabled = TRUE` 필터가 살아 있으면 매칭에서 제외된다. */
    private fun seedDisabledSamlIdpConfig() {
        jdbc.update(
            """
            INSERT INTO saml_idp_configs
                (registration_id, display_name, idp_entity_id, idp_sso_url, idp_x509_cert, authn_provider_id, enabled)
            VALUES
                (:reg, :name, :entityId, :ssoUrl, :cert, :providerId, FALSE)
            """,
            mapOf(
                "reg" to "fr-au-07-t1-saml-off-reg",
                "name" to "Disabled SAML",
                "entityId" to "urn:offsaml:idp",
                "ssoUrl" to "https://idp.off-saml.com/sso",
                "cert" to "-----BEGIN CERTIFICATE-----\nTEST\n-----END CERTIFICATE-----",
                "providerId" to disabledSamlProviderId,
            ),
        )
    }

    /** S5 — enabled=FALSE 인 OIDC config. `AND enabled = TRUE` 필터가 살아 있으면 매칭에서 제외된다. */
    private fun seedDisabledOidcProviderConfig() {
        jdbc.update(
            """
            INSERT INTO oidc_provider_configs
                (registration_id, display_name, issuer_uri, client_id, client_secret_encrypted, authn_provider_id, enabled)
            VALUES
                (:reg, :name, :issuer, :clientId, :secret, :providerId, FALSE)
            """,
            mapOf(
                "reg" to "fr-au-07-t1-oidc-off-reg",
                "name" to "Disabled OIDC",
                "issuer" to "https://idp.off-oidc.com",
                "clientId" to "off-oidc-client",
                "secret" to "enc:dummy",
                "providerId" to disabledOidcProviderId,
            ),
        )
    }

    private fun seedRoutes() {
        listOf(
            "partner.com" to samlProviderId,
            "acme.com" to oidcProviderId,
            // dead.com → LOCAL provider 지시 (운영자 실수 재현, S4 fail-safe)
            "dead.com" to localProviderId,
            // S5 — 비활성 SAML/OIDC config 를 가리키는 라우트 (fail-safe 회귀 가드)
            "off-saml.com" to disabledSamlProviderId,
            "off-oidc.com" to disabledOidcProviderId,
        ).forEach { (domain, providerId) ->
            jdbc.update(
                "INSERT INTO domain_provider_routes (domain, provider_id) VALUES (:domain, :providerId)",
                mapOf("domain" to domain, "providerId" to providerId),
            )
        }
    }
}
