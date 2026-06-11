// TOTP(2FA) e2e 통합 테스트 — setup→enable→login 2단계→verify→refresh 회전→disable 전체 스택 (FR-MF-01 Task 11)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.user.UserRepository
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.HashingAlgorithm
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
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * TOTP(Time-based One-Time Password, RFC 6238) 기반 2FA 전체 흐름 통합 테스트 (FR-MF-01 Task 11 / SDD §19.7).
 *
 * ## 목적
 * Task 1~10 의 production 배선(마이그레이션·암호화·TotpService·repo·MfaService·MfaController·AuthController
 * login 2단계+verify·세션 mfa_verified 전파)을 실제 PostgreSQL + Spring Boot 전체 스택으로 end-to-end
 * 검증한다. 단위 테스트(MockK/Mockito)로는 잡히지 않는 FilterChain→Controller→Service→Repository 의
 * 트랜잭션 전파, 쿠키 전달, JWT 클레임(mfa_verified) 회로, secret 평문 미저장을 포함한다.
 *
 * ## 테스트 환경 (부팅 레시피 — LocalAuthFlowIntegrationTest / ChangePasswordIntegrationTest 답습)
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 내장 Tomcat + Spring Security 필터 체인 전체 구동.
 * - `!prod` 프로필 — [com.atlas.bts.identity.jwt.DevMemoryKeyProvider] 사용(PEM 파일 불필요).
 * - Testcontainers PostgreSQL 16 — Flyway V001~V022 자동 마이그레이션(totp_secrets + sessions.mfa_verified).
 * - OAuth2ClientAutoConfiguration 제외 — keycloak issuer-uri 원격 discovery 호출 회피.
 * - LDAP Bean 4종 `@MockBean` — Local 인증 단독 스택 검증.
 * - **MFA 암호화 키 주입** — [MfaSecretEncryptor] 가 `bts.mfa.encryption.{key,salt}` 를 요구한다.
 *   미설정 시 setup/enable 호출에서 `IllegalStateException` 이 나므로 @DynamicPropertySource 로 테스트 키를
 *   주입한다. salt 는 `Encryptors.stronger` 가 hex 검증하므로 유효 hex 문자열을 쓴다.
 *
 * ## TOTP 코드 계산 (실시간)
 * 운영 [com.atlas.bts.identity.mfa.MfaService]·[com.atlas.bts.identity.mfa.TotpService] 는 컨텍스트에서
 * `Clock.systemUTC()` 로 주입되므로(테스트가 Clock 을 오버라이드하지 않음), 검증 시각은 서버의 실제 시각이다.
 * 따라서 테스트도 **실제 현재 시각**의 time-step 으로 정답 코드를 산출한다([currentTotpCode]). 코드 산출과
 * 서버 검증 사이 time-step 경계를 넘어도 서버 검증은 ±1 time-step 오차를 허용하므로 통과한다(flaky 방지).
 *
 * ## 진짜 RED 확인 절차 (C4 — 배선 결함 탐지 능력 입증)
 * 본 통합테스트가 "가짜 그린"(배선이 틀려도 통과)이 아님을 입증하기 위해, 작성 직후 다음을 수동 확인했다.
 * - AuthController.verifyMfa 의 `VerifyResult.Success -> issueTokens(..., mfaVerified = true)` 에서
 *   `mfaVerified` 인자를 **일시로 `false`** 로 바꾼다.
 * - 그러면 본 테스트의 `mfa_verified=true` 단언(S6/S7)이 **실패**한다(JWT 클레임 false → 세션 false).
 * - 변경을 `git checkout` 으로 즉시 원복한다(AuthController 는 본 Task 범위 밖 — 커밋하지 않음).
 * 이로써 본 테스트가 verify→issueTokens 의 mfa_verified 전달 결함을 실제로 잡아냄을 보장한다.
 *
 * ## 검증 시나리오
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | S1 | setup(JWT) | 200 + otpauth_uri/qr_png_data_uri |
 * | S2 | secret 평문 미저장 | DB totp_secrets.secret_cipher ≠ 평문 secret(암호문) |
 * | S3 | enable(정답 코드) | 204 + status ACTIVE |
 * | S4 | login(TOTP 활성 사용자) | 200 mfa_required:true + challenge_token (정식 세션 미발급) |
 * | S5 | mfa/verify(정답 코드) | 200 access_token + refresh 쿠키 |
 * | S6 | verify 후 JWT mfa_verified | access_token 의 mfa_verified 클레임 = true |
 * | S7 | refresh 회전 후 유지 | rotate 후 새 access_token 의 mfa_verified 도 true (GAP-1) |
 * | S8 | 챌린지 토큰 재사용 | 같은 challenge_token 재verify → 401 (C1 일회용) |
 * | S9 | disable(정답 코드) | 204 + status 미활성(enabled=false) |
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class MfaTotpIntegrationTest {
    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway V001~V022 자동 마이그레이션 적용 */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** MFA 암호화 테스트 키 — [MfaSecretEncryptor] 가 setup/enable 시 요구(미설정이면 IllegalStateException). */
        private const val MFA_TEST_KEY = "test-app-encryption-key-for-mfa-totp"

        /** MFA salt — `Encryptors.stronger` 가 hex 문자열을 요구하므로 유효 hex 리터럴. */
        private const val MFA_TEST_SALT = "deadbeefcafef00d"

        /** RFC 6238 time-step 길이(초) — 운영 TotpService.PERIOD_SECONDS 와 동일. */
        const val TOTP_PERIOD_SECONDS = 30L

        /** RFC 6238 코드 자릿수 — 운영 TotpService.DIGITS 와 동일. */
        const val TOTP_DIGITS = 6

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
            // MFA secret 암호화 키/salt — totp_secrets 평문 미저장 + setup/enable 동작에 필수
            registry.add("bts.mfa.encryption.key") { MFA_TEST_KEY }
            registry.add("bts.mfa.encryption.salt") { MFA_TEST_SALT }
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

    /** 테스트 사용자 자격 (각 테스트마다 신규 생성 — 격리) */
    private val testPassword = "S3cur3P@ss!"
    private lateinit var testUserId: UUID
    private lateinit var testUsername: String
    private lateinit var testEmail: String

    @BeforeEach
    fun prepareTestUser() {
        val suffix = UUID.randomUUID().toString().take(8)
        testUsername = "mfa-test-$suffix"
        testEmail = "mfa-$suffix@example.com"
        val user =
            userRepository.save(
                username = testUsername,
                email = testEmail,
                displayName = "MfaTestUser",
            )
        testUserId = user.id
        localCredentialService.store(testUserId, testPassword.toCharArray())
    }

    // ── e2e 전체 흐름 ─────────────────────────────────────────────────────────────

    /**
     * setup→enable→login(2단계)→verify→refresh 회전→disable 의 happy path 전체를 단일 테스트로 검증한다.
     *
     * 흐름이 상태(secret PENDING→ACTIVE→삭제, 세션 발급)에 강하게 의존하므로 한 테스트에서 순차 검증한다.
     * 각 단계의 단언은 위 KDoc 표(S1~S9)에 대응한다.
     */
    @Test
    fun `TOTP 전체 흐름 — setup·enable·login 2단계·verify·refresh 유지·disable`() {
        // 1단계 로그인으로 JWT 확보(setup/enable/disable 은 JWT 전용 self-service).
        val firstLogin = performLogin(testUsername, testPassword)
        assertThat(firstLogin.statusCode).isEqualTo(HttpStatus.OK)
        // TOTP 미활성 사용자는 기존 흐름 그대로 정식 토큰 발급(회귀 0).
        val initialAccessToken = (firstLogin.body as Map<*, *>)["access_token"] as String
        assertThat(initialAccessToken).isNotBlank()

        // ── S1. setup — secret 생성(PENDING) + otpauth/QR 반환 ──────────────────
        val setupResp = mfaSetup(initialAccessToken)
        assertThat(setupResp.statusCode)
            .withFailMessage("setup 은 200 이어야 합니다. 실제: ${setupResp.statusCode}, body=${setupResp.body}")
            .isEqualTo(HttpStatus.OK)
        val setupBody = setupResp.body as Map<*, *>
        val otpauthUri = setupBody["otpauth_uri"] as String
        assertThat(otpauthUri).startsWith("otpauth://totp/")
        assertThat(setupBody["qr_png_data_uri"] as String).startsWith("data:image/png;base64,")

        val secret = extractSecretFromOtpauthUri(otpauthUri)
        assertThat(secret).isNotBlank()

        // ── S2. secret 평문 미저장 — DB 암호문 컬럼이 평문 secret 과 달라야 한다 ────
        val storedCipher = readSecretCipher(testUserId)
        assertThat(storedCipher)
            .withFailMessage("totp_secrets.secret_cipher 가 평문 secret 으로 저장되면 안 됩니다(암호화 필수).")
            .isNotNull
            .isNotEqualTo(secret)

        // ── S3. enable — 정답 코드 검증 후 ACTIVE 전이 ─────────────────────────
        val enableResp = mfaEnable(initialAccessToken, currentTotpCode(secret))
        assertThat(enableResp.statusCode)
            .withFailMessage("enable 은 204 이어야 합니다. 실제: ${enableResp.statusCode}, body=${enableResp.body}")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(mfaStatusEnabled(initialAccessToken))
            .withFailMessage("enable 후 TOTP status 가 ACTIVE(enabled=true) 여야 합니다.")
            .isTrue()

        // ── S4. login(TOTP 활성) — 정식 세션 대신 챌린지 토큰 발급 ──────────────
        val secondLogin = performLogin(testUsername, testPassword)
        assertThat(secondLogin.statusCode).isEqualTo(HttpStatus.OK)
        val challengeBody = secondLogin.body as Map<*, *>
        assertThat(challengeBody["mfa_required"])
            .withFailMessage("TOTP 활성 사용자 login 은 mfa_required:true 여야 합니다. 실제: $challengeBody")
            .isEqualTo(true)
        // 정식 세션(access_token)은 발급되지 않아야 한다.
        assertThat(challengeBody["access_token"])
            .withFailMessage("2단계 진입 전에는 access_token 이 발급되면 안 됩니다.")
            .isNull()
        val challengeToken = challengeBody["mfa_challenge_token"] as String
        assertThat(challengeToken).isNotBlank()

        // ── S5/S6. mfa/verify — 정식 세션 발급 + JWT mfa_verified=true ──────────
        val verifyResp = mfaVerify(challengeToken, currentTotpCode(secret))
        assertThat(verifyResp.statusCode)
            .withFailMessage("verify 는 200 이어야 합니다. 실제: ${verifyResp.statusCode}, body=${verifyResp.body}")
            .isEqualTo(HttpStatus.OK)
        val verifyBody = verifyResp.body as Map<*, *>
        val mfaAccessToken = verifyBody["access_token"] as String
        assertThat(mfaAccessToken).isNotBlank()
        // refresh 쿠키 발급 확인.
        val verifyRefreshCookie = extractRefreshCookieValue(verifyResp)
        assertThat(verifyRefreshCookie)
            .withFailMessage("verify 성공 시 refresh_token 쿠키가 발급돼야 합니다.")
            .isNotBlank()
        // S6 — JWT mfa_verified 클레임 = true (핵심: verify→issueTokens(mfaVerified=true) 전달 검증)
        assertThat(jwtMfaVerifiedClaim(mfaAccessToken))
            .withFailMessage("verify 로 발급된 access_token 의 mfa_verified 클레임이 true 여야 합니다.")
            .isTrue()

        // ── S7. refresh 회전 후에도 mfa_verified=true 유지 (GAP-1) ──────────────
        val refreshResp = performRefresh(verifyRefreshCookie)
        assertThat(refreshResp.statusCode).isEqualTo(HttpStatus.OK)
        val rotatedAccessToken = (refreshResp.body as Map<*, *>)["access_token"] as String
        assertThat(jwtMfaVerifiedClaim(rotatedAccessToken))
            .withFailMessage("refresh 회전 후 새 access_token 도 mfa_verified=true 를 유지해야 합니다(GAP-1).")
            .isTrue()

        // ── S8. 챌린지 토큰 재사용 거부 (C1 일회용) ────────────────────────────
        val replayResp = mfaVerify(challengeToken, currentTotpCode(secret))
        assertThat(replayResp.statusCode)
            .withFailMessage("이미 소비된 challenge_token 재사용은 401 로 거부돼야 합니다(C1).")
            .isEqualTo(HttpStatus.UNAUTHORIZED)

        // ── S9. disable — 현재 코드 검증(step-up) 후 비활성화 ──────────────────
        val disableResp = mfaDisable(mfaAccessToken, currentTotpCode(secret))
        assertThat(disableResp.statusCode)
            .withFailMessage("disable 은 204 이어야 합니다. 실제: ${disableResp.statusCode}, body=${disableResp.body}")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(mfaStatusEnabled(mfaAccessToken))
            .withFailMessage("disable 후 TOTP status 가 미활성(enabled=false) 이어야 합니다.")
            .isFalse()
        assertThat(readSecretCipher(testUserId))
            .withFailMessage("disable 후 totp_secrets 행이 삭제돼야 합니다.")
            .isNull()
    }

    // ── private 헬퍼 ────────────────────────────────────────────────────────────

    /** POST /api/v1/auth/login — CSRF skip(ignoringRequestMatchers). */
    private fun performLogin(
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

    /** POST /api/v1/auth/refresh — Cookie 기반(CSRF skip). */
    private fun performRefresh(refreshTokenRaw: String): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply { set(HttpHeaders.COOKIE, "refresh_token=$refreshTokenRaw") }
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/refresh",
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
    }

    /** POST /api/v1/auth/mfa/totp/setup — JWT bearer. */
    private fun mfaSetup(accessToken: String): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/mfa/totp/setup",
            HttpMethod.POST,
            HttpEntity<Void>(bearer(accessToken)),
            Map::class.java,
        )

    /** POST /api/v1/auth/mfa/totp/enable — JWT bearer + 코드 body. */
    private fun mfaEnable(
        accessToken: String,
        code: String,
    ): ResponseEntity<Map<*, *>> = postCode("/api/v1/auth/mfa/totp/enable", bearer(accessToken), code)

    /** DELETE /api/v1/auth/mfa/totp — JWT bearer + 코드 body(step-up). */
    private fun mfaDisable(
        accessToken: String,
        code: String,
    ): ResponseEntity<Map<*, *>> {
        val headers = bearer(accessToken).apply { contentType = MediaType.APPLICATION_JSON }
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/mfa/totp",
            HttpMethod.DELETE,
            HttpEntity("""{"code":"$code"}""", headers),
            Map::class.java,
        )
    }

    /** GET /api/v1/auth/mfa/totp — enabled 여부. */
    private fun mfaStatusEnabled(accessToken: String): Boolean {
        val resp =
            restTemplate.exchange(
                "http://localhost:$port/api/v1/auth/mfa/totp",
                HttpMethod.GET,
                HttpEntity<Void>(bearer(accessToken)),
                Map::class.java,
            )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        return resp.body?.get("enabled") == true
    }

    /** POST /api/v1/auth/mfa/verify — 챌린지 토큰 + 코드(인증 불요, permitAll + CSRF ignore). */
    private fun mfaVerify(
        challengeToken: String,
        code: String,
    ): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"mfa_challenge_token":"$challengeToken","code":"$code"}"""
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/mfa/verify",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
    }

    private fun postCode(
        path: String,
        headers: HttpHeaders,
        code: String,
    ): ResponseEntity<Map<*, *>> {
        headers.contentType = MediaType.APPLICATION_JSON
        return restTemplate.exchange(
            "http://localhost:$port$path",
            HttpMethod.POST,
            HttpEntity("""{"code":"$code"}""", headers),
            Map::class.java,
        )
    }

    private fun bearer(accessToken: String): HttpHeaders = HttpHeaders().apply { setBearerAuth(accessToken) }

    /**
     * 서버의 실제 현재 시각 기준으로 [secret] 의 정답 TOTP 코드를 산출한다.
     *
     * 운영 코드와 동일 파라미터(SHA1·6자리·30초 time-step)로 계산하므로 서버 검증과 일치한다.
     * 산출↔검증 사이 time-step 경계를 넘어도 서버는 ±1 오차를 허용하므로 통과한다.
     */
    private fun currentTotpCode(secret: String): String {
        val timeStep = Instant.now().epochSecond / TOTP_PERIOD_SECONDS
        return DefaultCodeGenerator(HashingAlgorithm.SHA1, TOTP_DIGITS).generate(secret, timeStep)
    }

    /** otpauth:// URI 의 `secret` 쿼리 파라미터를 추출한다. */
    private fun extractSecretFromOtpauthUri(otpauthUri: String): String {
        val query = URI(otpauthUri).query ?: ""
        return query
            .split("&")
            .first { it.startsWith("secret=") }
            .substringAfter("secret=")
    }

    /** totp_secrets.secret_cipher 를 직접 조회한다(평문 미저장 단언용). 행 없으면 null. */
    private fun readSecretCipher(userId: UUID): String? =
        jdbc.query(
            "SELECT secret_cipher FROM totp_secrets WHERE user_id = :userId",
            mapOf("userId" to userId),
        ) { rs, _ -> rs.getString("secret_cipher") }
            .firstOrNull()

    /** 로그인/refresh/verify 응답의 Set-Cookie 헤더에서 refresh_token 값을 추출한다. */
    private fun extractRefreshCookieValue(response: ResponseEntity<*>): String {
        val cookies = response.headers[HttpHeaders.SET_COOKIE] ?: return ""
        return cookies
            .firstOrNull { it.startsWith("refresh_token=") }
            ?.substringAfter("refresh_token=")
            ?.substringBefore(";")
            ?.trim()
            ?: ""
    }

    /**
     * JWT access_token 의 payload(가운데 세그먼트)를 Base64url 디코드해 `mfa_verified` 클레임을 반환한다.
     *
     * 서명 검증은 필터 체인이 이미 수행하므로(verify/refresh 200) payload 디코드만으로 클레임을 확인한다.
     * 비밀값을 로깅하지 않는다.
     */
    private fun jwtMfaVerifiedClaim(accessToken: String): Boolean {
        val payload = accessToken.split(".")[1]
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        // 단순 JSON 에서 mfa_verified 불리언 값을 파싱한다(라이브러리 의존 최소화).
        return Regex("\"mfa_verified\"\\s*:\\s*(true|false)")
            .find(json)
            ?.groupValues
            ?.get(1) == "true"
    }
}
