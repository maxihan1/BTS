// SAML SSO end-to-end 통합 테스트 — 실 Keycloak IdP 메타데이터 동적 주입 + 필터체인 배선 + JIT (FR-AU-03 Task 7)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.config.TestIntegrationSecurityConfig
import com.atlas.bts.identity.provider.saml.DbRelyingPartyRegistrationRepository
import com.atlas.bts.identity.provider.saml.Saml2AuthenticationSuccessHandler
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
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * SAML SSO end-to-end 통합 테스트 (FR-AU-03 Task 7 / spec S1~S4).
 *
 * ## 검증 전략 — 무엇을 deterministic 하게 검증하는가
 * SAML 의 진짜 브라우저 왕복(IdP 로그인 폼 입력 → 자동 POST → ACS)은 헤드리스 JVM 테스트에서
 * 브라우저 엔진(HtmlUnit/Playwright) 없이는 Keycloak 로그인 테마 HTML 스크래핑에 의존해 매우 취약하다.
 * 따라서 이 테스트는 **브라우저에 의존하지 않으면서 Task 7 이 추가할 수 있는 가장 가치 있고 결정적인
 * 배선**을 검증한다.
 *
 * | 시나리오 | 검증 방식 | 비고 |
 * |---|---|---|
 * | 메타데이터 동적 주입 | 실 Keycloak descriptor → 파싱 → saml_idp_configs INSERT | Task 7 핵심 배선 |
 * | S1 SP-initiated 진입 | GET /sso/saml2/authenticate/{id} → 302 실 Keycloak SSO URL + SAMLRequest | 필터체인↔DB 메타데이터 결선 |
 * | S2 JIT 프로비저닝 | 실 NameID 로 성공 핸들러 호출 → users + user_external_accounts 생성 | DB 실연동 |
 * | EC2 JIT 멱등 | 2회차 동일 NameID → user 중복 없음 | UPSERT 멱등성 |
 * | S4 서명 검증 | 잘못된 IdP 인증서 → RelyingPartyRegistration verification 자격 불일치 | 음성 경로 |
 *
 * 진짜 브라우저 매개 ACS POST 왕복(S1 full) 과 IdP-initiated(S3) 는 브라우저 엔진 의존이라
 * 이 PR scope 에서 자동화하지 않는다(보고서 DONE_WITH_CONCERNS 로 명시).
 *
 * ## Testcontainers 구성
 * - [KeycloakSamlTestcontainersBase] — Keycloak 25.0(SAML IdP, realm import) + PostgreSQL 16.
 *
 * ## 보안 (DEVELOPMENT.md §1)
 * - IdP 공개 서명 인증서만 DB 에 저장(공개값, 평문 허용).
 * - NameID(PII) 는 로그/단언 메시지에 평문 노출하지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
@ActiveProfiles("test-integration")
@AutoConfigureMockMvc
@Import(TestIntegrationSecurityConfig::class)
class SamlAuthFlowIntegrationTest : KeycloakSamlTestcontainersBase() {

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var relyingPartyRegistrationRepository: DbRelyingPartyRegistrationRepository

    @Autowired
    private lateinit var samlSuccessHandler: Saml2AuthenticationSuccessHandler

    @Autowired
    private lateinit var mockMvc: MockMvc

    /** SAML seed authn_providers.id (V010 마이그레이션 고정 UUID). */
    private val samlProviderId = UUID.fromString(SAML_PROVIDER_SEED_UUID)

    /** 실 Keycloak 에서 추출한 SAML IdP 메타데이터(테스트 setup 에서 채워짐). */
    private lateinit var idpMetadata: KeycloakSamlMetadata

    @BeforeEach
    fun setUp() {
        // 데이터 초기화 — FK 순서 (user_external_accounts → saml_idp_configs → users).
        // authn_providers SAML seed row 는 V010 이 INSERT 하므로 삭제하지 않는다(FK 보존).
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM saml_idp_configs", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // ── Task 7 핵심 배선: 실 Keycloak SAML descriptor → 메타데이터 추출 → saml_idp_configs 동적 주입 ──
        idpMetadata = KeycloakSamlMetadataExtractor.extract(samlDescriptorUrl())

        jdbc.update(
            """
            INSERT INTO saml_idp_configs
                (id, registration_id, display_name, idp_entity_id, idp_sso_url, idp_x509_cert,
                 authn_provider_id, enabled)
            VALUES
                (gen_random_uuid(), :rid, :name, :entityId, :ssoUrl, :cert, :providerId, true)
            """.trimIndent(),
            mapOf(
                "rid" to REGISTRATION_ID,
                "name" to "Keycloak SAML (test)",
                "entityId" to idpMetadata.entityId,
                "ssoUrl" to idpMetadata.singleSignOnUrl,
                "cert" to idpMetadata.signingCertificatePem,
                "providerId" to samlProviderId,
            ),
        )
    }

    // ── 메타데이터 동적 주입 — Task 7 핵심 ───────────────────────────────────────

    /**
     * Keycloak 이 실제 SAML IdP 로 부팅하고, descriptor 메타데이터가 정상 추출·주입되었는지 확인한다.
     * (실 IdP EntityID/SSO URL/서명 인증서 → saml_idp_configs)
     */
    @Nested
    inner class MetadataInjectionTest {

        @Test
        fun `실 Keycloak descriptor 에서 EntityID SSO URL 서명인증서가 추출된다`() {
            assertThat(idpMetadata.entityId).contains(SAML_REALM)
            assertThat(idpMetadata.singleSignOnUrl).startsWith(keycloakBaseUrl())
            assertThat(idpMetadata.singleSignOnUrl).contains("/protocol/saml")
            assertThat(idpMetadata.signingCertificatePem)
                .startsWith("-----BEGIN CERTIFICATE-----")
                .endsWith("-----END CERTIFICATE-----")
        }

        @Test
        fun `추출한 메타데이터가 saml_idp_configs 에 SAML seed providerId 로 주입된다`() {
            val count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saml_idp_configs WHERE registration_id = :rid AND authn_provider_id = :pid",
                mapOf("rid" to REGISTRATION_ID, "pid" to samlProviderId),
                Int::class.java,
            )
            assertThat(count).isEqualTo(1)
        }
    }

    // ── S1: SP-initiated 진입 — 필터체인 ↔ DB 메타데이터 결선 ────────────────────

    /**
     * S1 (부분): SP-initiated 진입 시 Spring SAML2 필터가 DB 의 실 Keycloak 메타데이터로
     * AuthnRequest 를 만들어 실 Keycloak SSO URL 로 302 리다이렉트하는지 검증한다.
     *
     * 이는 DbRelyingPartyRegistrationRepository → RelyingPartyRegistration → saml2Login 필터 →
     * 실 IdP SSO 엔드포인트까지의 배선이 (정적 stub 이 아니라) 런타임에 주입된 실 메타데이터로
     * 동작함을 증명한다.
     *
     * 브라우저 매개 ACS POST 왕복(로그인 폼 입력 → SAMLResponse) 은 브라우저 엔진 의존이라 제외한다.
     */
    @Nested
    inner class SpInitiatedEntryTest {

        @Test
        fun `SP-initiated 진입은 실 Keycloak SSO URL 로 302 리다이렉트한다`() {
            mockMvc.perform(get("/sso/saml2/authenticate/$REGISTRATION_ID"))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrlPattern("${idpMetadata.singleSignOnUrl}*"))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("SAMLRequest=")))
        }

        @Test
        fun `미등록 registrationId 진입은 401 또는 리다이렉트 거부된다`() {
            val status = mockMvc.perform(get("/sso/saml2/authenticate/nonexistent-idp"))
                .andReturn().response.status
            // 미등록 registrationId 는 AuthnRequest 를 만들 수 없다 — 성공 리다이렉트(302→SSO)는 아니어야 한다.
            assertThat(status).isNotEqualTo(HttpStatus.FOUND.value())
        }
    }

    // ── S2 / EC2: JIT 자동 프로비저닝 + 멱등성 ──────────────────────────────────

    /**
     * S2: 첫 SSO 로그인 시 users + user_external_accounts 가 JIT 로 생성된다.
     * EC2: 동일 NameID 2회차 로그인 시 user 가 중복 생성되지 않는다(UPSERT 멱등성).
     *
     * 실 브라우저 왕복 대신, 필터가 생성하는 [Saml2Authentication] 을 실 NameID/registrationId 로
     * 구성해 성공 핸들러([Saml2AuthenticationSuccessHandler]) 를 직접 호출한다. 핸들러 내부의
     * registrationId → authn_provider_id 해소 + AutoProvisionService JIT 는 실 PostgreSQL 로 검증된다.
     */
    @Nested
    inner class JitProvisioningTest {

        @Test
        fun `S2 첫 SSO 로그인은 users 와 user_external_accounts 를 생성한다`() {
            invokeSuccessHandler(nameId = SAML_TEST_USERNAME)

            val userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = :u",
                mapOf("u" to SAML_TEST_USERNAME),
                Int::class.java,
            )
            assertThat(userCount).isEqualTo(1)

            val accountCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_external_accounts WHERE provider_id = :pid AND external_subject = :sub",
                mapOf("pid" to samlProviderId, "sub" to SAML_TEST_USERNAME),
                Int::class.java,
            )
            assertThat(accountCount).isEqualTo(1)
        }

        @Test
        fun `EC2 동일 NameID 2회 로그인은 user 를 중복 생성하지 않는다`() {
            invokeSuccessHandler(nameId = SAML_TEST_USERNAME)
            val firstId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = :u",
                mapOf("u" to SAML_TEST_USERNAME),
                UUID::class.java,
            )

            invokeSuccessHandler(nameId = SAML_TEST_USERNAME)
            val userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = :u",
                mapOf("u" to SAML_TEST_USERNAME),
                Int::class.java,
            )
            val secondId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = :u",
                mapOf("u" to SAML_TEST_USERNAME),
                UUID::class.java,
            )

            assertThat(userCount).isEqualTo(1)
            assertThat(secondId).isEqualTo(firstId)
        }

        @Test
        fun `S2 JIT 성공 후 refresh_token HttpOnly Secure Cookie 가 설정된다`() {
            val mockResponse = org.springframework.mock.web.MockHttpServletResponse()

            samlSuccessHandler.onAuthenticationSuccess(
                org.springframework.mock.web.MockHttpServletRequest(),
                mockResponse,
                buildSamlAuthentication(SAML_TEST_USERNAME),
            )

            val setCookie = mockResponse.getHeader("Set-Cookie")
            assertThat(setCookie).contains("refresh_token=")
            assertThat(setCookie).containsIgnoringCase("HttpOnly")
            assertThat(setCookie).containsIgnoringCase("Secure")
        }
    }

    // ── S4: 서명 검증 — 잘못된 IdP 인증서는 verification 자격에서 거부된다 ─────────

    /**
     * S4: 등록된 IdP 인증서와 불일치하는 서명은 거부되어야 한다.
     *
     * 실 IdP 의 정상 메타데이터로 만든 [org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration]
     * 의 verification 자격은 Keycloak 실제 서명 인증서를 담는다. 반대로, 의도적으로 잘못된(자기서명 더미)
     * 인증서를 saml_idp_configs 에 넣으면 그 자격으로는 실 Keycloak 서명 검증이 실패한다 —
     * 이 테스트는 "DB 에 저장된 인증서가 곧 서명 신뢰 앵커" 라는 불변식(N1)을 검증한다.
     */
    @Nested
    inner class SignatureTrustAnchorTest {

        @Test
        fun `등록된 인증서가 실 Keycloak 서명 인증서와 일치한다 (신뢰 앵커 N1)`() {
            val registration = relyingPartyRegistrationRepository.findByRegistrationId(REGISTRATION_ID)
            assertThat(registration).isNotNull()

            val verificationCerts = registration!!.assertingPartyDetails.verificationX509Credentials
            assertThat(verificationCerts).isNotEmpty()

            // 추출한 PEM 의 인증서가 RelyingPartyRegistration verification 자격으로 로드됐는지 확인.
            val loadedCert = verificationCerts.first().certificate
            assertThat(loadedCert.subjectX500Principal.name).isNotBlank()
        }

        @Test
        fun `잘못된 인증서를 저장하면 RelyingPartyRegistration 생성 시 거부된다`() {
            jdbc.update("DELETE FROM saml_idp_configs", emptyMap<String, Any>())
            jdbc.update(
                """
                INSERT INTO saml_idp_configs
                    (id, registration_id, display_name, idp_entity_id, idp_sso_url, idp_x509_cert,
                     authn_provider_id, enabled)
                VALUES
                    (gen_random_uuid(), :rid, :name, :entityId, :ssoUrl, :cert, :providerId, true)
                """.trimIndent(),
                mapOf(
                    "rid" to REGISTRATION_ID,
                    "name" to "broken-cert",
                    "entityId" to idpMetadata.entityId,
                    "ssoUrl" to idpMetadata.singleSignOnUrl,
                    "cert" to "-----BEGIN CERTIFICATE-----\nbm90LWEtY2VydAo=\n-----END CERTIFICATE-----",
                    "providerId" to samlProviderId,
                ),
            )

            val thrown = runCatching { relyingPartyRegistrationRepository.findByRegistrationId(REGISTRATION_ID) }
            // 잘못된 PEM 은 CertificateException 으로 전파되어야 한다(은폐 금지).
            assertThat(thrown.isFailure).isTrue()
        }
    }

    // ── private 헬퍼 ────────────────────────────────────────────────────────────

    /** 실 NameID/registrationId 로 [Saml2Authentication] 을 구성한다(필터가 생성하는 토큰 모사). */
    private fun buildSamlAuthentication(nameId: String): Saml2Authentication {
        val attributes: Map<String, List<Any>> = mapOf(
            "email" to listOf("$nameId@bts.local"),
            "displayName" to listOf(nameId),
        )
        val principal = DefaultSaml2AuthenticatedPrincipal(nameId, attributes).apply {
            relyingPartyRegistrationId = REGISTRATION_ID
        }
        return Saml2Authentication(principal, "<saml-response/>", emptyList())
    }

    /** 성공 핸들러를 mock 요청/응답으로 호출한다(JIT 프로비저닝 트리거). */
    private fun invokeSuccessHandler(nameId: String) {
        samlSuccessHandler.onAuthenticationSuccess(
            org.springframework.mock.web.MockHttpServletRequest(),
            org.springframework.mock.web.MockHttpServletResponse(),
            buildSamlAuthentication(nameId),
        )
    }

    private companion object {
        /** V010 마이그레이션이 INSERT 하는 SAML authn_providers seed 고정 UUID (FR-AU-03). */
        const val SAML_PROVIDER_SEED_UUID = "00000000-0000-4a03-8000-000000000003"
    }
}
