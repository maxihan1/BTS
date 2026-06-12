// WebAuthn(보안키/패스키) 2FA e2e 통합 테스트 — 가상 인증기로 register/authenticate/clone/다중키/회귀 전체 스택 (FR-MF-03 Task 8)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.user.UserRepository
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.client.Origin
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.Base64
import java.util.UUID

/**
 * WebAuthn(보안키/패스키, FR-MF-03 / SDD §19.8) 2차 인증 전체 흐름 통합 테스트.
 *
 * ## 목적
 * 등록(attestation)·인증(assertion) 두 ceremony 의 production 배선(WebAuthnService 검증·
 * WebAuthnSecurityKeyService 오케스트레이션·MfaController/AuthController HTTP·WebAuthnChallengeStore
 * 1회용 challenge·clone 방어 advanceSignCount·세션 mfa_verified 전파)을 **실제 가상 인증기**로
 * 실기기 없이 실제 PostgreSQL + Spring Boot 전체 스택으로 end-to-end 검증한다. 단위/슬라이스
 * 테스트(MockK/Mockito·@MockBean)로는 잡히지 않는 FilterChain→Controller→Service→Repository 의
 * 트랜잭션 전파, 쿠키 전달, JWT 클레임(mfa_verified) 회로, signCount clone 거부 회로를 포함한다.
 *
 * ## 가상 인증기 (webauthn4j-test)
 * webauthn4j-test 의 [ClientPlatform] 은 실제 브라우저/보안키 없이 등록/인증 응답을 가상 서명한다
 * (WebAuthnServiceTest 선례). 서버가 만든 옵션 JSON 을 [objectConverter] 로 역직렬화해
 * [ClientPlatform] 에 넘기고, 그 결과(PublicKeyCredential)를 다시 직렬화해 finish/verify 로 전송한다.
 *
 * - 등록↔인증 round-trip 은 **반드시 동일 [ClientPlatform] 인스턴스(동일 인증기)를 공유**한다. 새
 *   인스턴스를 쓰면 인증기가 등록 키를 몰라 `NotAllowedException` 이 난다(WebAuthnServiceTest 주석).
 * - origin 정합: 서버 검증은 응답 origin 이 [WebAuthnProperties.origin] 과 일치해야 한다. 그래서
 *   가상 [ClientPlatform] 도 같은 [Origin] ([WEBAUTHN_ORIGIN])으로 생성하고 rpId 도 [WEBAUTHN_RP_ID]
 *   로 주입해 정상 경로를 재현한다(rpId 는 origin 호스트의 registrable suffix — `localhost==localhost`).
 *
 * ## 테스트 환경 (MfaTotpIntegrationTest / MfaBackupCodeLoginIntegrationTest 부팅 레시피 답습)
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 내장 Tomcat + Spring Security 필터 체인 전체 구동.
 * - `!prod` 프로필 — [com.atlas.bts.identity.jwt.DevMemoryKeyProvider] 사용(PEM 파일 불필요).
 * - Testcontainers PostgreSQL 16 — Flyway V001~V025 자동 마이그레이션(webauthn_credentials).
 * - OAuth2ClientAutoConfiguration 제외 — keycloak issuer-uri 원격 discovery 호출 회피.
 * - LDAP Bean 4종 `@MockBean` — Local 인증 단독 스택 검증.
 * - MFA 암호화 키/salt 주입 — 기존 MFA 빈([com.atlas.bts.identity.mfa.MfaSecretEncryptor]) 부팅을
 *   위해 유지한다(WebAuthn 공개키는 평문이라 암호화 불요하나 같은 컨텍스트의 TOTP 빈이 요구).
 * - `bts.webauthn.{rp-id,origin}` 주입 — 가상 [ClientPlatform] origin 과 일치해야 검증 통과.
 *
 * ## 검증 시나리오
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | W1 | register start→(가상 서명)→finish | 201 + webauthn_credentials DB INSERT(공개키 박제) |
 * | W2 | login 1단계(비번) — 보안키 등록 사용자 | 200 mfa_required + challenge_token (정식 세션 미발급) |
 * | W3 | authenticate start→(가상 서명)→verify(method=webauthn) | 200 access_token + JWT mfa_verified=true |
 * | W4 | signCount clone 거부(저장값 이하 재제출) | 401 invalid_code (advanceSignCount 0행 → fail-closed) |
 * | W5 | 다중 키 등록 + 개별 삭제 + 타인 키 404 | 2건 등록 후 1건 삭제 204 / 타인 키 DELETE 404 |
 * | W6 | MFA 미활성 사용자 로그인 회귀 0 | 즉시 정식 세션(access_token, mfa_verified=false) |
 *
 * ## 진짜 RED 확인 (가짜 그린 방지)
 * 본 테스트는 미배선/오배선이면 실패한다 — register/finish 경로 결함(401/400)이면 W1, verify→
 * issueTokens(mfaVerified=true) 결함이면 W3 의 mfa_verified 단언, advanceSignCount clone 가드 제거면
 * W4 가 200 으로 떨어져 실패한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class MfaWebauthnIntegrationTest {
    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway V001~V025 자동 마이그레이션 적용 */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** MFA 암호화 테스트 키 — MfaSecretEncryptor 가 같은 컨텍스트 TOTP 빈 부팅에 요구. */
        private const val MFA_TEST_KEY = "test-app-encryption-key-for-mfa-webauthn"

        /** MFA salt — `Encryptors.stronger` 가 hex 문자열을 요구하므로 유효 hex 리터럴. */
        private const val MFA_TEST_SALT = "deadbeefcafef00d"

        /** WebAuthn RP ID — origin 호스트의 registrable suffix (localhost == localhost). */
        const val WEBAUTHN_RP_ID = "localhost"

        /** WebAuthn 허용 origin — 가상 ClientPlatform origin 과 동일해야 검증 통과. */
        const val WEBAUTHN_ORIGIN = "http://localhost:5173"

        /** clone 판정 모사용 — 저장 sign_count 를 이 값으로 올려 가상 인증기 카운터가 항상 더 작게 만든다. */
        const val CLONE_GUARD_SIGN_COUNT = 1_000_000L

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            registry.add("bts.mfa.encryption.key") { MFA_TEST_KEY }
            registry.add("bts.mfa.encryption.salt") { MFA_TEST_SALT }
            // WebAuthn RP — 가상 인증기 origin/rpId 와 일치시켜 서명 검증 통과.
            registry.add("bts.webauthn.rp-id") { WEBAUTHN_RP_ID }
            registry.add("bts.webauthn.rp-name") { "BTS Test" }
            registry.add("bts.webauthn.origin") { WEBAUTHN_ORIGIN }
        }
    }

    // ── LDAP Bean @MockBean — Local 인증 단독 스택 검증을 위해 목킹 ────────────────
    @MockBean
    lateinit var ldapProvider: LdapProvider

    @MockBean
    lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean
    lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean
    lateinit var autoProvisionService: AutoProvisionService

    // ── 테스트 대상 Bean ────────────────────────────────────────────────────────
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var localCredentialService: LocalCredentialService

    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    /** 가상 인증기 옵션 JSON 직렬화/역직렬화용 — WebAuthnService 와 동일 변환기. */
    private val objectConverter = ObjectConverter()

    /** 테스트 사용자 자격 (각 테스트마다 신규 생성 — 격리) */
    private val testPassword = "S3cur3P@ss!"
    private lateinit var testUserId: UUID
    private lateinit var testUsername: String
    private lateinit var testEmail: String

    @BeforeEach
    fun prepareTestUser() {
        val suffix = UUID.randomUUID().toString().take(8)
        testUsername = "wak-test-$suffix"
        testEmail = "wak-$suffix@example.com"
        val user =
            userRepository.save(
                username = testUsername,
                email = testEmail,
                displayName = "WebAuthnTestUser",
            )
        testUserId = user.id
        localCredentialService.store(testUserId, testPassword.toCharArray())
    }

    // ── W1/W2/W3. register → login 2단계 → authenticate → verify(mfa_verified) ───────

    /**
     * register(가상 등록)→DB INSERT→login(보안키 활성)→authenticate(가상 인증)→verify 의 happy path.
     *
     * 흐름이 상태(자격증명 등록 → 보안키 활성 → 챌린지 토큰 → 세션 발급)에 강하게 의존하므로 한
     * 테스트에서 순차 검증한다(W1~W3). 등록·인증은 같은 [ClientPlatform] 인스턴스를 공유한다.
     *
     * LongMethod 억제 — register→login→authenticate→verify 는 상태 전달이 큰 단일 e2e 시나리오라,
     * 분할하면 단계 간 상태(자격증명/세션) 전달 비용이 커지고 흐름 가독성이 떨어진다.
     */
    @Suppress("LongMethod")
    @Test
    fun `WebAuthn 전체 흐름 — register·login 2단계·authenticate·verify mfa_verified`() {
        val platform = newClientPlatform()

        // ── W1. register start→(가상 서명)→finish → 201 + DB INSERT ─────────────
        val registerResp = registerSecurityKey(platform, name = "회사 노트북")
        assertThat(registerResp.statusCode)
            .withFailMessage("register/finish 는 201 이어야 합니다. 실제: ${registerResp.statusCode}, body=${registerResp.body}")
            .isEqualTo(HttpStatus.CREATED)
        assertThat(countCredentials(testUserId))
            .withFailMessage("register/finish 성공 시 webauthn_credentials 에 1행이 저장돼야 합니다.")
            .isEqualTo(1)

        // ── W2. login 1단계(비번) — 보안키 활성 사용자 → mfa_required + challenge_token ──
        val login = performLogin(testUsername, testPassword)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        val loginBody = login.body as Map<*, *>
        assertThat(loginBody["mfa_required"])
            .withFailMessage("보안키 활성 사용자 login 은 mfa_required:true 여야 합니다. 실제: $loginBody")
            .isEqualTo(true)
        // 정식 세션(access_token)은 발급되지 않아야 한다.
        assertThat(loginBody["access_token"])
            .withFailMessage("2단계 진입 전에는 access_token 이 발급되면 안 됩니다.")
            .isNull()
        val challengeToken = loginBody["mfa_challenge_token"] as String
        assertThat(challengeToken).isNotBlank()

        // ── W3. authenticate start→(가상 서명)→verify(method=webauthn) → 정식 세션 + mfa_verified ──
        val verifyResp = authenticateAndVerify(platform, challengeToken)
        assertThat(verifyResp.statusCode)
            .withFailMessage("verify(webauthn) 는 200 이어야 합니다. 실제: ${verifyResp.statusCode}, body=${verifyResp.body}")
            .isEqualTo(HttpStatus.OK)
        val verifyBody = verifyResp.body as Map<*, *>
        val accessToken = verifyBody["access_token"] as String
        assertThat(accessToken).isNotBlank()
        assertThat(extractRefreshCookieValue(verifyResp))
            .withFailMessage("verify 성공 시 refresh_token 쿠키가 발급돼야 합니다.")
            .isNotBlank()
        assertThat(jwtMfaVerifiedClaim(accessToken))
            .withFailMessage("verify(webauthn) 로 발급된 access_token 의 mfa_verified 클레임이 true 여야 합니다.")
            .isTrue()
    }

    // ── W4. signCount clone 거부 — 저장값 이하 재제출 ────────────────────────────────

    /**
     * 첫 정상 인증으로 signCount 가 전진한 뒤, 저장값을 인위적으로 크게 올려(복제 인증기가 더 작은
     * 카운터를 보고하는 상황 모사) 새 정상 인증을 시도하면 [advanceSignCount] 0행 → 거부(401)되어야 한다.
     *
     * 가상 인증기는 호출마다 자체 카운터를 단조 증가시키므로, 클라이언트만으로는 "저장값 이하" 응답을
     * 재현할 수 없다. 따라서 저장 sign_count 를 DB 로 직접 높여 clone 판정 경로(advanceSignCount false →
     * verifyLogin InvalidAssertion → 401 invalid_code)를 실 HTTP 로 결정적으로 검증한다(clone 방어 회로).
     */
    @Test
    fun `WebAuthn signCount clone — 저장값 이하 카운터 재제출은 401 로 거부된다`() {
        val platform = newClientPlatform()
        assertThat(registerSecurityKey(platform, name = "키").statusCode).isEqualTo(HttpStatus.CREATED)

        // 첫 정상 인증 — 성공해야 한다(카운터 전진).
        val firstChallenge = (performLogin(testUsername, testPassword).body as Map<*, *>)["mfa_challenge_token"] as String
        assertThat(authenticateAndVerify(platform, firstChallenge).statusCode)
            .withFailMessage("첫 보안키 인증은 성공(200)해야 합니다.")
            .isEqualTo(HttpStatus.OK)

        // 저장된 sign_count 를 인위적으로 매우 크게 올린다(복제 인증기의 더 작은 카운터를 모사).
        bumpStoredSignCount(testUserId, CLONE_GUARD_SIGN_COUNT)

        // 새 정상 인증 — 가상 인증기 카운터는 저장값보다 작으므로 clone 으로 판정돼 거부돼야 한다.
        val cloneChallenge = (performLogin(testUsername, testPassword).body as Map<*, *>)["mfa_challenge_token"] as String
        val cloneResp = authenticateAndVerify(platform, cloneChallenge)
        assertThat(cloneResp.statusCode)
            .withFailMessage("저장값 이하 signCount 재제출(clone 의심)은 401 로 거부돼야 합니다. 실제: ${cloneResp.statusCode}")
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── W5. 다중 키 등록 + 개별 삭제 + 타인 키 404 ──────────────────────────────────

    /**
     * 한 사용자에 보안키 2개를 등록하고(서로 다른 가상 인증기 = 서로 다른 credential), 1개를 삭제하면
     * 204 + 목록 1건이 되며, 타인 사용자가 등록한 키 id 로 삭제 시도하면 404(존재 비노출 IDOR)여야 한다.
     */
    @Test
    fun `WebAuthn 다중 키 — 2건 등록·1건 삭제 204·타인 키 삭제 404`() {
        // 첫 키는 MFA 미활성 상태(1단계 로그인=정식 세션)에서 등록한다.
        val firstKey = newClientPlatform()
        assertThat(registerSecurityKey(firstKey, name = "키1").statusCode).isEqualTo(HttpStatus.CREATED)

        // 첫 키 등록으로 사용자는 MFA 활성 — 이후 self-service(2번째 키 등록·목록·삭제)는 보안키 2단계로 얻은
        // JWT 로 호출해야 한다(1단계 로그인은 mfa_required). 첫 키로 2단계 인증해 JWT 를 확보한다.
        val accessToken = accessTokenViaWebauthn(firstKey)

        // 같은 사용자, 다른 가상 인증기로 2번째 키 등록(credentialId 가 달라 중복 등록 아님).
        assertThat(registerSecurityKeyWith(accessToken, newClientPlatform(), name = "키2").statusCode)
            .withFailMessage("MFA 활성 상태에서도 추가 보안키 등록은 가능해야 합니다(2번째 키).")
            .isEqualTo(HttpStatus.CREATED)

        val keys = listKeys(accessToken)
        assertThat(keys)
            .withFailMessage("2건 등록 후 목록은 2건이어야 합니다. 실제: $keys")
            .hasSize(2)

        // 1건 삭제 → 204, 목록 1건.
        val deleteId = keys.first()["id"] as String
        assertThat(deleteKey(accessToken, deleteId).statusCode)
            .withFailMessage("소유 키 삭제는 204 여야 합니다.")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(listKeys(accessToken)).hasSize(1)

        // 타인 사용자가 등록한 키 id 로 삭제 시도 → 404(타인 키 존재 비노출).
        val otherKeyId = registerKeyForOtherUser()
        assertThat(deleteKey(accessToken, otherKeyId.toString()).statusCode)
            .withFailMessage("타인 소유 키 삭제는 404 여야 합니다(IDOR 비노출).")
            .isEqualTo(HttpStatus.NOT_FOUND)
        // 타인 키는 그대로 남아 있어야 한다(삭제되지 않음).
        assertThat(countCredentialsById(otherKeyId))
            .withFailMessage("타인 키는 삭제되지 않고 그대로 남아 있어야 합니다.")
            .isEqualTo(1)
    }

    // ── W6. MFA 미활성 사용자 로그인 회귀 0 ─────────────────────────────────────────

    /**
     * 보안키도 TOTP 도 등록하지 않은 사용자는 기존 흐름 그대로 1단계 로그인에서 즉시 정식 세션을 받아야
     * 한다(mfa_required 없음, access_token 발급, mfa_verified=false). FR-MF-03 도입의 회귀 0 가드.
     */
    @Test
    fun `WebAuthn 미활성 사용자 로그인 — 즉시 정식 세션(회귀 0)`() {
        val login = performLogin(testUsername, testPassword)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        val body = login.body as Map<*, *>
        assertThat(body["mfa_required"])
            .withFailMessage("MFA 미활성 사용자는 mfa_required 가 없어야 합니다. 실제: $body")
            .isNull()
        val accessToken = body["access_token"] as String
        assertThat(accessToken).isNotBlank()
        assertThat(jwtMfaVerifiedClaim(accessToken))
            .withFailMessage("MFA 미활성 로그인의 access_token 은 mfa_verified=false 여야 합니다.")
            .isFalse()
    }

    // ── 가상 ceremony 헬퍼 ──────────────────────────────────────────────────────────

    /**
     * 새 가상 인증기를 단 [ClientPlatform] 을 만든다(WebAuthnServiceTest 선례 동일).
     *
     * NoneAttestationAuthenticator + WebAuthnAuthenticatorAdaptor 를 [WEBAUTHN_ORIGIN] 으로 묶는다.
     * 인스턴스마다 독립 인증기(credentialId)를 가지므로, 등록↔인증 round-trip 은 같은 인스턴스를
     * 공유해야 하고(NotAllowedException 회피), 다중 키 테스트는 서로 다른 인스턴스를 쓴다.
     */
    private fun newClientPlatform(): ClientPlatform {
        val authenticator = NoneAttestationAuthenticator()
        val adaptor = WebAuthnAuthenticatorAdaptor(authenticator, objectConverter)
        return ClientPlatform(Origin(WEBAUTHN_ORIGIN), adaptor)
    }

    /**
     * 보안키 등록 ceremony 전체 — 1단계 로그인(JWT)→register/start→(가상 서명)→register/finish 를 수행한다.
     *
     * register/start·finish 는 JWT 전용 self-service 다. 이 헬퍼는 **MFA 미활성**(첫 키 등록) 상황 전용으로,
     * 1단계 로그인이 곧 정식 세션이라 그 JWT 로 등록한다. MFA 활성 상태에서 추가 등록할 때는 보안키 2단계로
     * 얻은 JWT 를 넘기는 [registerSecurityKeyWith] 를 쓴다.
     */
    private fun registerSecurityKey(
        platform: ClientPlatform,
        name: String,
    ): ResponseEntity<Map<*, *>> = registerSecurityKeyWith(freshAccessTokenBeforeMfa(), platform, name)

    /**
     * 주어진 [accessToken] 으로 register/start→(가상 서명)→register/finish 를 수행한다.
     *
     * MFA 활성 상태에서 추가 보안키를 등록할 때(2번째 키), 보안키 2단계로 미리 받은 JWT 를 그대로 넘긴다.
     */
    private fun registerSecurityKeyWith(
        accessToken: String,
        platform: ClientPlatform,
        name: String,
    ): ResponseEntity<Map<*, *>> {
        val optionsJson = webAuthnRegisterStart(accessToken)
        val options = objectConverter.jsonConverter.readValue(optionsJson, PublicKeyCredentialCreationOptions::class.java)
        val credential = platform.create(options)
        val credentialJson = objectConverter.jsonConverter.writeValueAsString(credential)
        return webAuthnRegisterFinish(accessToken, credentialJson, name)
    }

    /**
     * 보안키 인증 ceremony 전체 — authenticate/start→(가상 서명)→mfa/verify(method=webauthn) 를 수행한다.
     *
     * [challengeToken] 은 1단계 로그인 응답의 mfa_challenge_token 이며, start 는 이 토큰으로 사용자를
     * 식별해 WebAuthn challenge 와 인증 옵션을 발급한다. 가상 인증 응답을 verify 로 보내 정식 세션을 받는다.
     */
    private fun authenticateAndVerify(
        platform: ClientPlatform,
        challengeToken: String,
    ): ResponseEntity<Map<*, *>> {
        val optionsJson = webAuthnAuthenticateStart(challengeToken)
        val options = objectConverter.jsonConverter.readValue(optionsJson, PublicKeyCredentialRequestOptions::class.java)
        val credential = platform.get(options)
        val credentialJson = objectConverter.jsonConverter.writeValueAsString(credential)
        return mfaVerifyWebauthn(challengeToken, credentialJson)
    }

    /**
     * 이미 등록된 [platform] 으로 1단계 로그인 + webauthn 2단계 인증을 거쳐 정식 세션 access_token 을 얻는다.
     *
     * 보안키가 등록된(=MFA 활성) 사용자는 1단계 로그인만으론 JWT 를 받지 못하므로(mfa_required),
     * 등록에 쓴 인증기 인스턴스로 2단계 인증을 마쳐 목록/삭제 호출용 JWT 를 확보한다(목록/삭제 self-service).
     */
    private fun accessTokenViaWebauthn(platform: ClientPlatform): String {
        val challengeToken = (performLogin(testUsername, testPassword).body as Map<*, *>)["mfa_challenge_token"] as String
        val verifyResp = authenticateAndVerify(platform, challengeToken)
        assertThat(verifyResp.statusCode)
            .withFailMessage("등록 키로 2단계 인증해 access_token 을 받아야 합니다. 실제: ${verifyResp.statusCode}")
            .isEqualTo(HttpStatus.OK)
        return (verifyResp.body as Map<*, *>)["access_token"] as String
    }

    /** 타인 사용자(별도 user)를 만들고 보안키 1건을 등록한 뒤 그 자격증명 PK 를 반환한다(IDOR 404 검증용). */
    private fun registerKeyForOtherUser(): UUID {
        val suffix = UUID.randomUUID().toString().take(8)
        val otherUser =
            userRepository.save(
                username = "wak-other-$suffix",
                email = "wak-other-$suffix@example.com",
                displayName = "OtherUser",
            )
        localCredentialService.store(otherUser.id, testPassword.toCharArray())
        val otherLogin = performLoginRaw("wak-other-$suffix", testPassword)
        val accessToken = (otherLogin.body as Map<*, *>)["access_token"] as String
        val optionsJson = webAuthnRegisterStart(accessToken)
        val options = objectConverter.jsonConverter.readValue(optionsJson, PublicKeyCredentialCreationOptions::class.java)
        val credential = newClientPlatform().create(options)
        val credentialJson = objectConverter.jsonConverter.writeValueAsString(credential)
        assertThat(webAuthnRegisterFinish(accessToken, credentialJson, "남의키").statusCode)
            .isEqualTo(HttpStatus.CREATED)
        return jdbc.query(
            "SELECT id FROM webauthn_credentials WHERE user_id = :userId",
            mapOf("userId" to otherUser.id),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }.first()
    }

    /**
     * 보안키 등록 직전 발급용 JWT — 등록 전에는 MFA 미활성이라 1단계 로그인이 곧 정식 세션이다.
     *
     * 등록을 마치면 사용자는 보안키 활성이 되어 이후 1단계 로그인은 mfa_required 가 되므로, register/start·
     * finish 에 쓸 JWT 는 항상 "이번 등록 직전" 시점에 새로 발급한다.
     */
    private fun freshAccessTokenBeforeMfa(): String {
        val resp = performLogin(testUsername, testPassword)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val body = resp.body as Map<*, *>
        return body["access_token"] as? String
            ?: error("등록용 JWT 확보 실패 — 이미 MFA 활성 상태로 추정: $body")
    }

    // ── HTTP 헬퍼 ──────────────────────────────────────────────────────────────────

    /** POST /api/v1/auth/login (현재 테스트 사용자). */
    private fun performLogin(
        username: String,
        password: String,
    ): ResponseEntity<Map<*, *>> = performLoginRaw(username, password)

    /** POST /api/v1/auth/login — CSRF skip(ignoringRequestMatchers). */
    private fun performLoginRaw(
        username: String,
        password: String,
    ): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"provider":"local","username":"$username","password":"$password"}"""
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/login",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
    }

    /** POST /webauthn/register/start — JWT bearer, 등록 옵션 JSON(raw) 반환. */
    private fun webAuthnRegisterStart(accessToken: String): String {
        val resp =
            restTemplate.exchange(
                "http://localhost:$port/api/v1/auth/mfa/webauthn/register/start",
                HttpMethod.POST,
                HttpEntity<Void>(bearer(accessToken)),
                String::class.java,
            )
        assertThat(resp.statusCode)
            .withFailMessage("register/start 는 200 이어야 합니다. 실제: ${resp.statusCode}, body=${resp.body}")
            .isEqualTo(HttpStatus.OK)
        return resp.body ?: error("register/start 응답 본문이 비었습니다.")
    }

    /** POST /webauthn/register/finish — JWT bearer + credential JSON + name. */
    private fun webAuthnRegisterFinish(
        accessToken: String,
        credentialJson: String,
        name: String,
    ): ResponseEntity<Map<*, *>> {
        val headers = bearer(accessToken).apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"credential":$credentialJson,"name":"$name"}"""
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/mfa/webauthn/register/finish",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
    }

    /** POST /webauthn/authenticate/start — 챌린지 토큰만(permitAll), 인증 옵션 JSON(raw) 반환. */
    private fun webAuthnAuthenticateStart(challengeToken: String): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"mfa_challenge_token":"$challengeToken"}"""
        val resp =
            restTemplate.exchange(
                "http://localhost:$port/api/v1/auth/mfa/webauthn/authenticate/start",
                HttpMethod.POST,
                HttpEntity(body, headers),
                String::class.java,
            )
        assertThat(resp.statusCode)
            .withFailMessage("authenticate/start 는 200 이어야 합니다. 실제: ${resp.statusCode}, body=${resp.body}")
            .isEqualTo(HttpStatus.OK)
        return resp.body ?: error("authenticate/start 응답 본문이 비었습니다.")
    }

    /** POST /api/v1/auth/mfa/verify — method=webauthn + credential(assertion JSON). */
    private fun mfaVerifyWebauthn(
        challengeToken: String,
        credentialJson: String,
    ): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"mfa_challenge_token":"$challengeToken","method":"webauthn","credential":$credentialJson}"""
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/mfa/verify",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
    }

    /** GET /webauthn — JWT bearer, 등록 키 목록(keys 배열)을 반환한다. */
    @Suppress("UNCHECKED_CAST")
    private fun listKeys(accessToken: String): List<Map<String, Any?>> {
        val resp =
            restTemplate.exchange(
                "http://localhost:$port/api/v1/auth/mfa/webauthn",
                HttpMethod.GET,
                HttpEntity<Void>(bearer(accessToken)),
                Map::class.java,
            )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        return (resp.body?.get("keys") as? List<Map<String, Any?>>).orEmpty()
    }

    /** DELETE /webauthn/{id} — JWT bearer. */
    private fun deleteKey(
        accessToken: String,
        id: String,
    ): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/mfa/webauthn/$id",
            HttpMethod.DELETE,
            HttpEntity<Void>(bearer(accessToken)),
            Map::class.java,
        )

    private fun bearer(accessToken: String): HttpHeaders = HttpHeaders().apply { setBearerAuth(accessToken) }

    // ── DB / JWT 헬퍼 ──────────────────────────────────────────────────────────────

    /** 사용자의 등록 보안키 행 수를 직접 조회한다(W1 INSERT 단언용). */
    private fun countCredentials(userId: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM webauthn_credentials WHERE user_id = :userId",
            mapOf("userId" to userId),
            Int::class.java,
        ) ?: 0

    /** 특정 자격증명 PK 행 수(타인 키 미삭제 단언용). */
    private fun countCredentialsById(id: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM webauthn_credentials WHERE id = :id",
            mapOf("id" to id),
            Int::class.java,
        ) ?: 0

    /** 사용자의 저장 sign_count 를 인위적으로 [value] 로 올린다(clone 판정 경로 모사 — W4). */
    private fun bumpStoredSignCount(
        userId: UUID,
        value: Long,
    ) {
        jdbc.update(
            "UPDATE webauthn_credentials SET sign_count = :v WHERE user_id = :userId",
            mapOf("v" to value, "userId" to userId),
        )
    }

    /** 로그인/verify 응답의 Set-Cookie 헤더에서 refresh_token 값을 추출한다. */
    private fun extractRefreshCookieValue(response: ResponseEntity<*>): String {
        val cookies = response.headers[HttpHeaders.SET_COOKIE] ?: return ""
        return cookies
            .firstOrNull { it.startsWith("refresh_token=") }
            ?.substringAfter("refresh_token=")
            ?.substringBefore(";")
            ?.trim()
            .orEmpty()
    }

    /**
     * JWT access_token 의 payload 를 Base64url 디코드해 `mfa_verified` 클레임을 반환한다.
     *
     * 서명 검증은 필터 체인이 이미 수행하므로 payload 디코드만으로 클레임을 확인한다(비밀값 미로깅).
     */
    private fun jwtMfaVerifiedClaim(accessToken: String): Boolean {
        val payload = accessToken.split(".")[1]
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        return Regex("\"mfa_verified\"\\s*:\\s*(true|false)")
            .find(json)
            ?.groupValues
            ?.get(1) == "true"
    }
}
