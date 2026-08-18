// 백업 코드 로그인(2단계 method=backup_code) e2e 통합 테스트 — 소진/재사용/동시성/EC-13 전체 스택 (FR-MF-02 Task 8)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.mfa.MfaBackupCodeService
import com.atlas.bts.identity.mfa.MfaService
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 백업 코드 로그인(로그인 2단계 `method=backup_code`) 전체 흐름 통합 테스트 (FR-MF-02 Task 8 / SDD §19.7).
 *
 * ## 목적
 * AuthController.verifyMfa 의 method 분기(`backup_code` → [MfaBackupCodeService.verifyAndConsume])를 실제
 * PostgreSQL + Spring Boot 전체 스택으로 end-to-end 검증한다. 단위 슬라이스(AuthControllerTest)로는
 * 잡히지 않는 FilterChain→Controller→Service→Repository 의 트랜잭션 전파, 챌린지 토큰 일회성, 백업 코드
 * atomic 소진(`consumeIfUnused`)의 동시성(race)을 포함한다.
 *
 * ## 테스트 환경 (MfaTotpIntegrationTest 부팅 레시피 답습)
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 내장 Tomcat + Spring Security 필터 체인 전체 구동.
 * - `!prod` 프로필 — [com.atlas.bts.identity.jwt.DevMemoryKeyProvider] 사용(PEM 파일 불필요).
 * - Testcontainers PostgreSQL 16 — Flyway V001~V023 자동 마이그레이션(totp_secrets + user_mfa_backup_codes).
 * - OAuth2ClientAutoConfiguration 제외 — keycloak issuer-uri 원격 discovery 호출 회피.
 * - LDAP Bean 4종 `@MockBean` — Local 인증 단독 스택 검증.
 * - **MFA 암호화 키 주입** — [com.atlas.bts.identity.mfa.MfaSecretEncryptor] 가 setup/enable 시 요구.
 *
 * ## 백업 코드 발급 경로
 * Task 8 범위(verify method 분기)에는 백업 코드 발급 HTTP 엔드포인트가 없으므로, ACTIVE 전환 후
 * [MfaBackupCodeService.generateOrRegenerate] 를 직접 호출해 평문 코드 묶음을 얻는다(서비스가 ACTIVE
 * 게이트를 강제한다). 이후 검증은 운영 HTTP 경로(`POST /mfa/verify`)로만 수행한다.
 *
 * ## 진짜 RED 확인 절차 (배선 결함 탐지 능력 입증)
 * 본 통합테스트가 "가짜 그린"(분기가 틀려도 통과)이 아님을 입증하기 위해, 작성 직후 다음을 수동 확인했다.
 * - AuthController.verifyMfa 의 `"backup_code"` 분기를 일시로 `mfaService.verifyLogin(...)` 로 바꾼다.
 * - 그러면 백업 코드 검증이 TOTP NotEnabled 경로로 떨어져 [백업코드 로그인 성공] 시나리오가 401 로 실패한다.
 * - 변경을 `git checkout` 으로 즉시 원복한다.
 *
 * ## 검증 시나리오
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | B1 | enable→발급→login→verify(method=backup_code, 정답) | 200 access_token + refresh 쿠키 |
 * | B2 | 같은 백업 코드 재사용(소진된 코드) | 새 챌린지로 verify → 401 invalid_code |
 * | B3 | 동시 같은 코드 2제출(race) | 정확히 1건만 200, 나머지 401 (atomic 소진) |
 * | EC-13 | TOTP ACTIVE 아닌 사용자가 method=backup_code | 401 invalid_code (회귀 가드) |
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class MfaBackupCodeLoginIntegrationTest {
    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway V001~V023 자동 마이그레이션 적용 */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** MFA 암호화 테스트 키 — MfaSecretEncryptor 가 setup/enable 시 요구(미설정이면 IllegalStateException). */
        private const val MFA_TEST_KEY = "test-app-encryption-key-for-mfa-backup"

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
    lateinit var mfaService: MfaService

    @Autowired
    lateinit var backupCodeService: MfaBackupCodeService

    /** 테스트 사용자 자격 (각 테스트마다 신규 생성 — 격리) */
    private val testPassword = "S3cur3P@ss!"
    private lateinit var testUserId: UUID
    private lateinit var testUsername: String
    private lateinit var testEmail: String

    @BeforeEach
    fun prepareTestUser() {
        val suffix = UUID.randomUUID().toString().take(8)
        testUsername = "bkc-test-$suffix"
        testEmail = "bkc-$suffix@example.com"
        val user =
            userRepository.save(
                username = testUsername,
                email = testEmail,
                displayName = "BackupCodeTestUser",
            )
        testUserId = user.id
        localCredentialService.store(testUserId, testPassword.toCharArray())
    }

    // ── B1/B2. 백업 코드 로그인 성공 + 소진된 코드 재사용 거부 ───────────────────────

    /**
     * enable→백업코드 발급→login(method=backup_code 정답)→소진→같은 코드 재사용 401 의 happy/regression path.
     *
     * 흐름이 상태(백업 코드 소진)에 강하게 의존하므로 한 테스트에서 순차 검증한다.
     */
    @Test
    fun `백업 코드 로그인 — 발급·verify 성공·소진·같은 코드 재사용 401`() {
        val secret = enableTotpAndReturnSecret()
        val codes = generateBackupCodes()
        val usedCode = codes.first()

        // ── B1. login(TOTP 활성) → 챌린지 토큰 → verify(method=backup_code, 정답) ──
        val firstChallenge = loginAndGetChallengeToken()
        val verifyResp = verifyBackupCode(firstChallenge, usedCode)
        assertThat(verifyResp.statusCode)
            .withFailMessage("백업 코드 정답 verify 는 200 이어야 합니다. 실제: ${verifyResp.statusCode}, body=${verifyResp.body}")
            .isEqualTo(HttpStatus.OK)
        val accessToken = (verifyResp.body as Map<*, *>)["access_token"] as String
        assertThat(accessToken).isNotBlank()
        assertThat(extractRefreshCookieValue(verifyResp))
            .withFailMessage("백업 코드 verify 성공 시 refresh_token 쿠키가 발급돼야 합니다.")
            .isNotBlank()

        // ── B2. 같은(소진된) 백업 코드 재사용 → 새 챌린지로 verify → 401 ──────────
        val secondChallenge = loginAndGetChallengeToken()
        val replayResp = verifyBackupCode(secondChallenge, usedCode)
        assertThat(replayResp.statusCode)
            .withFailMessage("이미 소진된 백업 코드 재사용은 401 로 거부돼야 합니다. 실제: ${replayResp.statusCode}")
            .isEqualTo(HttpStatus.UNAUTHORIZED)

        // 미사용 코드는 그대로 사용 가능해야 한다(소진은 1개만).
        val thirdChallenge = loginAndGetChallengeToken()
        val secondCodeResp = verifyBackupCode(thirdChallenge, codes[1])
        assertThat(secondCodeResp.statusCode)
            .withFailMessage("아직 미사용인 다른 백업 코드는 로그인에 성공해야 합니다.")
            .isEqualTo(HttpStatus.OK)

        // secret 은 흐름 검증용으로만 쓰였으며 로깅하지 않는다.
        assertThat(secret).isNotBlank()
    }

    // ── B3. 동시 같은 코드 2제출 — atomic 소진(정확히 1건만 성공) ─────────────────

    /**
     * 같은 백업 코드를 서로 다른 챌린지 토큰으로 동시에 2회 제출하면, atomic 소진(`consumeIfUnused`)으로
     * 정확히 1건만 200 이고 나머지는 401 이어야 한다(double-spend 방어).
     */
    @Test
    fun `백업 코드 동시 같은 코드 2제출 — 정확히 1건만 200`() {
        enableTotpAndReturnSecret()
        val code = generateBackupCodes().first()

        // 두 요청 각각 별도 챌린지 토큰을 미리 확보(토큰 일회성 때문에 같은 토큰 공유 불가).
        val challengeA = loginAndGetChallengeToken()
        val challengeB = loginAndGetChallengeToken()

        val pool = Executors.newFixedThreadPool(2)
        try {
            val taskA = Callable { verifyBackupCode(challengeA, code).statusCode }
            val taskB = Callable { verifyBackupCode(challengeB, code).statusCode }
            val futures: List<Future<HttpStatus>> =
                pool.invokeAll(listOf(taskA, taskB)).map {
                    @Suppress("UNCHECKED_CAST")
                    it as Future<HttpStatus>
                }
            val statuses = futures.map { it.get() }

            val okCount = statuses.count { it == HttpStatus.OK }
            val unauthorizedCount = statuses.count { it == HttpStatus.UNAUTHORIZED }
            assertThat(okCount)
                .withFailMessage("동시 같은 코드 2제출 시 정확히 1건만 200 이어야 합니다. 실제 statuses=$statuses")
                .isEqualTo(1)
            assertThat(unauthorizedCount)
                .withFailMessage("동시 같은 코드 2제출 시 나머지 1건은 401 이어야 합니다. 실제 statuses=$statuses")
                .isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    // ── EC-13. TOTP ACTIVE 아닌 사용자가 backup_code verify → 401 ─────────────────

    /**
     * TOTP 가 ACTIVE 가 아니고 백업 코드도 발급되지 않은 사용자가 챌린지 토큰을 위조 없이 얻을 수는 없으나,
     * 방어 차원에서 챌린지 토큰을 발급받아도(여기서는 TOTP enable 없이 챌린지 발급이 불가하므로) 백업 코드
     * 미발급 상태에서 `consumeIfUnused` 가 매칭 0건이라 401 invalid_code 로 거부됨을 검증한다(fail-closed).
     *
     * 챌린지 토큰은 enable 후 login 으로만 얻을 수 있으므로, enable 은 하되 **백업 코드를 발급하지 않은**
     * 상태에서 임의 코드로 verify 를 시도해 미발급 경로의 거부를 가드한다.
     */
    @Test
    fun `EC-13 백업 코드 미발급 상태 backup_code verify 는 401`() {
        enableTotpAndReturnSecret()
        // 백업 코드를 발급하지 않은 상태 — 어떤 코드도 매칭되지 않아야 한다.
        val challenge = loginAndGetChallengeToken()
        val resp = verifyBackupCode(challenge, "ABCDE-FGHJK")
        assertThat(resp.statusCode)
            .withFailMessage("백업 코드 미발급 상태의 backup_code verify 는 401 이어야 합니다. 실제: ${resp.statusCode}")
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── private 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * TOTP setup→enable 로 ACTIVE 전환시키고 평문 secret 을 반환한다.
     *
     * setup 은 HTTP, enable 은 정답 코드 계산이 필요해 1단계 로그인으로 JWT 를 확보한 뒤 HTTP 로 수행한다.
     */
    private fun enableTotpAndReturnSecret(): String {
        val firstLogin = performLogin(testUsername, testPassword)
        assertThat(firstLogin.statusCode).isEqualTo(HttpStatus.OK)
        val accessToken = (firstLogin.body as Map<*, *>)["access_token"] as String

        val setupResp = mfaSetup(accessToken)
        assertThat(setupResp.statusCode).isEqualTo(HttpStatus.OK)
        val secret = (setupResp.body as Map<*, *>)["secret_base32"] as String

        val enableResp = mfaEnable(accessToken, currentTotpCode(secret))
        assertThat(enableResp.statusCode)
            .withFailMessage("enable 은 204 이어야 합니다. 실제: ${enableResp.statusCode}, body=${enableResp.body}")
            .isEqualTo(HttpStatus.NO_CONTENT)
        return secret
    }

    /**
     * ACTIVE 전환 후 백업 코드 10개를 발급하고 평문 묶음을 반환한다.
     *
     * Task 8 범위에 발급 HTTP 엔드포인트가 없어 서비스를 직접 호출한다(서비스가 ACTIVE 게이트를 강제).
     */
    private fun generateBackupCodes(): List<String> {
        val result = backupCodeService.generateOrRegenerate(testUserId)
        assertThat(result)
            .withFailMessage("ACTIVE 사용자에게 백업 코드가 발급돼야 합니다. 실제: $result")
            .isInstanceOf(MfaBackupCodeService.GenerateResult.Generated::class.java)
        return (result as MfaBackupCodeService.GenerateResult.Generated).codes
    }

    /** TOTP 활성 사용자로 login 해 mfa_challenge_token 을 반환한다(정식 세션 미발급). */
    private fun loginAndGetChallengeToken(): String {
        val resp = performLogin(testUsername, testPassword)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val body = resp.body as Map<*, *>
        assertThat(body["mfa_required"])
            .withFailMessage("TOTP 활성 사용자 login 은 mfa_required:true 여야 합니다. 실제: $body")
            .isEqualTo(true)
        return body["mfa_challenge_token"] as String
    }

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

    /** POST /api/v1/auth/mfa/totp/setup — JWT bearer. */
    private fun mfaSetup(accessToken: String): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/mfa/totp/setup",
            HttpMethod.POST,
            HttpEntity<Unit>(bearer(accessToken)),
            Map::class.java,
        )

    /** POST /api/v1/auth/mfa/totp/enable — JWT bearer + 코드 body. */
    private fun mfaEnable(
        accessToken: String,
        code: String,
    ): ResponseEntity<Map<*, *>> {
        val headers = bearer(accessToken).apply { contentType = MediaType.APPLICATION_JSON }
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/mfa/totp/enable",
            HttpMethod.POST,
            HttpEntity("""{"code":"$code"}""", headers),
            Map::class.java,
        )
    }

    /** POST /api/v1/auth/mfa/verify — 챌린지 토큰 + 코드 + method=backup_code(인증 불요, permitAll + CSRF ignore). */
    private fun verifyBackupCode(
        challengeToken: String,
        code: String,
    ): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"mfa_challenge_token":"$challengeToken","code":"$code","method":"backup_code"}"""
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/mfa/verify",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
    }

    private fun bearer(accessToken: String): HttpHeaders = HttpHeaders().apply { setBearerAuth(accessToken) }

    /**
     * 서버의 실제 현재 시각 기준으로 [secret] 의 정답 TOTP 코드를 산출한다(enable 용).
     *
     * 운영 코드와 동일 파라미터(SHA1·6자리·30초 time-step)로 계산하므로 서버 검증과 일치한다.
     */
    private fun currentTotpCode(secret: String): String {
        val timeStep = Instant.now().epochSecond / TOTP_PERIOD_SECONDS
        return DefaultCodeGenerator(HashingAlgorithm.SHA1, TOTP_DIGITS).generate(secret, timeStep)
    }

    /** login/verify 응답의 Set-Cookie 헤더에서 refresh_token 값을 추출한다. */
    private fun extractRefreshCookieValue(response: ResponseEntity<*>): String {
        val cookies = response.headers[HttpHeaders.SET_COOKIE] ?: return ""
        return cookies
            .firstOrNull { it.startsWith("refresh_token=") }
            ?.substringAfter("refresh_token=")
            ?.substringBefore(";")
            ?.trim()
            .orEmpty()
    }
}
