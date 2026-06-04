// OIDC SSO end-to-end 통합 테스트 — 실 Keycloak issuer discovery 동적 주입 + 필터체인 배선 (FR-AU-04 Task 7)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.config.SecretEncryptor
import com.atlas.bts.identity.config.TestIntegrationSecurityConfig
import com.atlas.bts.identity.provider.oidc.DbClientRegistrationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * OIDC SSO end-to-end 통합 테스트 (FR-AU-04 Task 7).
 *
 * [SamlAuthFlowIntegrationTest] 동형 — 실 Keycloak 을 OIDC IdP 로 띄우고, BTS 의 OAuth2/OIDC 배선이
 * 런타임 실 IdP 메타데이터(.well-known/openid-configuration)로 end-to-end 동작하는지 검증한다.
 *
 * ## 검증 전략 — 무엇을 deterministic 하게 검증하는가
 * OIDC 의 진짜 브라우저 왕복(IdP 로그인 폼 입력 → authorization code 콜백 → token 교환)은 헤드리스
 * JVM 테스트에서 브라우저 엔진(HtmlUnit/Playwright) 없이는 Keycloak 로그인 테마 HTML 스크래핑에
 * 의존해 매우 취약하다. 따라서 이 테스트는 **브라우저에 의존하지 않으면서 Task 7 이 추가할 수 있는
 * 가장 가치 있고 결정적인 배선**을 검증한다.
 *
 * | 시나리오 | 검증 방식 | 비고 |
 * |---|---|---|
 * | issuer discovery 동적 주입 | 실 Keycloak issuer → oidc_provider_configs INSERT(secret 암호화) | Task 7 핵심 배선 |
 * | SP-initiated 진입 | GET /oauth2/authorization/{id} → 302 실 Keycloak authorization endpoint | 필터체인↔DB↔discovery 결선 |
 * | discovery endpoint 해소 | ClientRegistration 의 authorization endpoint 가 실 Keycloak realm 경로 | .well-known 실연동 |
 * | client_secret 복호화 | SecretEncryptor round-trip 으로 ClientRegistration.clientSecret 복원 | §1.1.1 암호화 저장 |
 *
 * 진짜 브라우저 매개 code 콜백 왕복(full login → token 교환 → ID Token 검증 → 세션 발급)은
 * 브라우저 엔진 의존이라 이 PR scope 에서 자동화하지 않는다(보고서 DONE_WITH_CONCERNS / E2E 후속).
 * SAML 이 게이트2에서 확정한 "실 IdP 302 검증" 수준을 OIDC 동형으로 충족한다.
 *
 * ## Testcontainers 구성
 * - [KeycloakOidcTestcontainersBase] — Keycloak 25.0(OIDC IdP, realm import) + PostgreSQL 16.
 *
 * ## 보안 (DEVELOPMENT.md §1)
 * - client_secret 은 DB 에 [SecretEncryptor] 로 암호화하여 저장한다(평문 금지 §1.1.1).
 * - sub/email(PII) 은 로그/단언 메시지에 평문 노출하지 않는다(§1.1.2).
 *
 * ## OAuth2ClientAutoConfiguration 제외 영향 없음
 * `application-test-integration.yml` 이 [OAuth2 client 자동설정]을 제외하지만, OIDC 필터는
 * SecurityFilterChain 의 `oauth2Login` DSL 이 [DbClientRegistrationRepository]([ClientRegistrationRepository]
 * 빈) 로 직접 배선한다. 자동설정은 application.yml 기반 정적 ClientRegistration 등록 역할이라,
 * DB 기반 동적 등록을 쓰는 BTS 에는 제외해도 진입 필터(OAuth2AuthorizationRequestRedirectFilter)가
 * 정상 동작한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test-integration")
@AutoConfigureMockMvc
@Import(TestIntegrationSecurityConfig::class)
class OidcAuthFlowIntegrationTest : KeycloakOidcTestcontainersBase() {
    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var clientRegistrationRepository: DbClientRegistrationRepository

    @Autowired
    private lateinit var secretEncryptor: SecretEncryptor

    @Autowired
    private lateinit var mockMvc: MockMvc

    /** OIDC seed authn_providers.id (V011 마이그레이션 고정 UUID). */
    private val oidcProviderId = UUID.fromString(OIDC_PROVIDER_SEED_UUID)

    @BeforeEach
    fun setUp() {
        // 데이터 초기화 — FK 순서 (user_external_accounts → oidc_provider_configs → users).
        // authn_providers OIDC seed row 는 V011 이 INSERT 하므로 삭제하지 않는다(FK 보존).
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM oidc_provider_configs", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // ── Task 7 핵심 배선: 실 Keycloak issuer → oidc_provider_configs 동적 주입(secret 암호화) ──
        jdbc.update(
            """
            INSERT INTO oidc_provider_configs
                (id, registration_id, display_name, issuer_uri, client_id,
                 client_secret_encrypted, scopes, authn_provider_id, enabled)
            VALUES
                (gen_random_uuid(), :rid, :name, :issuer, :clientId,
                 :secret, :scopes, :providerId, true)
            """.trimIndent(),
            mapOf(
                "rid" to REGISTRATION_ID,
                "name" to "Keycloak OIDC (test)",
                "issuer" to issuerUri(),
                "clientId" to OIDC_CLIENT_ID,
                // 평문 client_secret 은 절대 평문 저장 금지 — SecretEncryptor 로 암호화하여 저장(§1.1.1).
                "secret" to secretEncryptor.encrypt(OIDC_CLIENT_SECRET),
                "scopes" to "openid,profile,email",
                "providerId" to oidcProviderId,
            ),
        )
    }

    // ── issuer discovery 동적 주입 — Task 7 핵심 ─────────────────────────────────

    /**
     * Keycloak 이 실제 OIDC IdP 로 부팅하고, issuer discovery(.well-known/openid-configuration) 가
     * 정상 동작하여 [DbClientRegistrationRepository] 가 [ClientRegistration] 으로 변환하는지 확인한다.
     */
    @Nested
    inner class DiscoveryInjectionTest {
        @Test
        fun `oidc_provider_configs 에 OIDC seed providerId 로 주입된다`() {
            val count =
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM oidc_provider_configs " +
                        "WHERE registration_id = :rid AND authn_provider_id = :pid",
                    mapOf("rid" to REGISTRATION_ID, "pid" to oidcProviderId),
                    Int::class.java,
                )
            assertThat(count).isEqualTo(1)
        }

        @Test
        fun `실 Keycloak discovery 로 ClientRegistration authorization endpoint 가 해소된다`() {
            val registration = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID)
            assertThat(registration).isNotNull()

            // .well-known/openid-configuration 에서 채워진 endpoint 가 실 Keycloak realm 경로여야 한다.
            val authUri = registration!!.providerDetails.authorizationUri
            assertThat(authUri).startsWith(keycloakBaseUrl())
            assertThat(authUri).contains("/realms/$OIDC_REALM/protocol/openid-connect/auth")
            assertThat(registration.providerDetails.tokenUri)
                .contains("/realms/$OIDC_REALM/protocol/openid-connect/token")
        }

        @Test
        fun `암호화 저장한 client_secret 이 ClientRegistration 에서 평문으로 복원된다`() {
            val registration = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID)
            assertThat(registration).isNotNull()

            // DB 에는 암호화 ciphertext 가 저장되고, ClientRegistration 빌드 시 SecretEncryptor 로 복호화된다.
            assertThat(registration!!.clientId).isEqualTo(OIDC_CLIENT_ID)
            assertThat(registration.clientSecret).isEqualTo(OIDC_CLIENT_SECRET)

            // DB 에 저장된 값은 평문이 아니어야 한다(§1.1.1 평문 저장 금지 불변식).
            val stored =
                jdbc.queryForObject(
                    "SELECT client_secret_encrypted FROM oidc_provider_configs WHERE registration_id = :rid",
                    mapOf("rid" to REGISTRATION_ID),
                    String::class.java,
                )
            assertThat(stored).isNotEqualTo(OIDC_CLIENT_SECRET)
        }
    }

    // ── SP-initiated 진입 — 필터체인 ↔ DB ↔ discovery 결선 ───────────────────────

    /**
     * SP-initiated 진입 시 Spring OAuth2 필터가 DB 의 실 Keycloak 설정 + discovery 로
     * authorization request 를 생성하고 IdP authorization endpoint 로 302 리다이렉트하는지 검증한다
     * (= [DbClientRegistrationRepository] → discovery → oauth2Login 필터 →
     * OAuth2AuthorizationRequestRedirectFilter → IdP 리다이렉트가 런타임 실 메타데이터로 end-to-end 동작).
     *
     * SAML 의 SpInitiatedEntryTest(302 + SAMLRequest) 와 동형 — OIDC 는 302 + response_type=code + state.
     * 브라우저 매개 code 콜백 왕복(로그인 폼 → token 교환)은 브라우저 엔진 의존이라 제외한다.
     *
     * ## PKCE(code_challenge) 단언 제외 사유
     * Spring Security 6.x 의 DefaultOAuth2AuthorizationRequestResolver 는 **public client** 이거나
     * ClientRegistration.ClientSettings.requireProofKey=true 일 때만 code_challenge 를 부착한다.
     * 본 FR 의 [DbClientRegistrationRepository] 는 client_secret 을 가진 **confidential client** 를
     * requireProofKey 미설정으로 빌드하므로, IdP realm 이 PKCE 를 지원해도 진입 요청에 code_challenge 가
     * 부착되지 않는다. 따라서 결정적 단언 대상은 response_type=code + state 로 고정한다(SAML 게이트2
     * 확정 수준과 동형). PKCE 강제는 ClientSettings 변경이 필요한 production 코드(T3) 범위라 후속으로 둔다.
     */
    @Nested
    inner class SpInitiatedEntryTest {
        @Test
        fun `SP-initiated 진입은 실 Keycloak authorization endpoint 로 302 리다이렉트한다`() {
            val result = mockMvc.perform(get("$AUTHORIZATION_PATH/$REGISTRATION_ID")).andReturn()

            assertThat(result.response.status).isEqualTo(HttpStatus.FOUND.value())
            val location = result.response.getHeader("Location")
            assertThat(location).isNotNull()

            // 실 Keycloak authorization endpoint 로 향해야 한다(정적 stub 이 아닌 실 discovery 배선).
            assertThat(location!!).startsWith(keycloakBaseUrl())
            assertThat(location).contains("/realms/$OIDC_REALM/protocol/openid-connect/auth")

            // OAuth2 Authorization Code flow 핵심 파라미터 — response_type=code + state(CSRF 방어).
            val params = queryParams(location)
            assertThat(params["response_type"]).isEqualTo("code")
            assertThat(params["client_id"]).isEqualTo(OIDC_CLIENT_ID)
            assertThat(params["state"]).isNotBlank()
            assertThat(params["scope"]).contains("openid")
            assertThat(params["redirect_uri"]).contains("/login/oauth2/code/$REGISTRATION_ID")
        }

        @Test
        fun `미등록 registrationId 진입은 실 Keycloak authorization 으로 리다이렉트하지 않는다`() {
            // 미등록 registrationId 는 ClientRegistration 이 없어 authorization request 를 만들 수 없다
            // (InvalidClientRegistrationIdException → 302 리다이렉트 미발생, Location 헤더 부재).
            val location =
                runCatching {
                    val result = mockMvc.perform(get("$AUTHORIZATION_PATH/nonexistent-idp")).andReturn()
                    result.response.getHeader("Location")
                }.getOrNull()
            // 핵심: 실 Keycloak authorization endpoint 로는 절대 향하지 않는다(null 이거나 다른 경로).
            if (location != null) {
                assertThat(location).doesNotContain("/realms/$OIDC_REALM/protocol/openid-connect/auth")
            }
        }
    }

    // ── private 헬퍼 ────────────────────────────────────────────────────────────

    /** 302 Location URL 의 query string 을 key→value 맵으로 분해한다(중복 키는 마지막 값). */
    private fun queryParams(location: String): Map<String, String> {
        val query = URI.create(location).rawQuery ?: return emptyMap()
        return query.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) return@mapNotNull null
                val key = URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8)
                val value = URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8)
                key to value
            }
            .toMap()
    }

    private companion object {
        /** V011 마이그레이션이 INSERT 하는 OIDC authn_providers seed 고정 UUID (FR-AU-04). */
        const val OIDC_PROVIDER_SEED_UUID = "00000000-0000-4a04-8000-000000000004"

        /** Spring Security `oauth2Login` 표준 authorization request 진입 경로(registrationId 접미). */
        const val AUTHORIZATION_PATH = "/oauth2/authorization"
    }
}
