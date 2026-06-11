// MFA 강제 클레임 e2e 통합 테스트 — 강제대상 login/refresh 토큰의 mfa_enrollment_required 발급 chokepoint 검증 (FR-MF-04 Task 5)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.systemrole.SystemRole
import com.atlas.bts.identity.systemrole.SystemRoleAssignmentRepository
import com.atlas.bts.identity.user.UserRepository
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
import java.util.Base64
import java.util.UUID

/**
 * MFA 강제 클레임(`mfa_enrollment_required`) 발급 chokepoint e2e 통합 테스트 (FR-MF-04 Task 5 / SDD §19.7.2).
 *
 * ## 목적 — 발급 chokepoint (리뷰 B1)
 * access JWT 의 `mfa_enrollment_required` 클레임은 [com.atlas.bts.identity.jwt.JwtIssuer] **내부 1곳**에서
 * `MfaEnforcementPolicy.evaluate(userId)` 를 호출해 박는다. 호출처(AuthController.issueTokens /
 * RefreshTokenService.rotate / SSO 성공 핸들러)는 시그니처·코드 변경이 없으므로 어느 발급 경로든
 * 클레임이 자동으로 포함된다. 본 테스트는 그 chokepoint 가 실제 스택(필터→컨트롤러→JwtIssuer→정책)에서
 * 동작함을 검증한다 — 단위 테스트([com.atlas.bts.identity.jwt.JwtIssuerTest])가 구조를 박제하고,
 * 본 통합 테스트가 실제 login·refresh 경로의 토큰에 반영됨을 확정한다.
 *
 * ## 강제 대상 만들기 — SYSTEM_ADMIN 부여 (실 정책 경로)
 * [com.atlas.bts.identity.permission.IdentityAccessSystemPermissionResolver] 는 `@Profile` 분리 없이
 * non-prod 컨텍스트에도 등록되어 `system_role_assignments` 를 실제 조회한다. 따라서 테스트 사용자에게
 * SYSTEM_ADMIN 을 부여하면 [com.atlas.bts.identity.mfa.MfaEnforcementPolicy] 가 실제로 강제 대상으로
 * 판정한다. MFA 를 설정하지 않은(미등록) 상태이므로 `evaluate=true`(강제 대상 AND 미설정).
 * 일반 사용자는 역할 미부여 → `evaluate=false`. 민감 프로젝트 경로는 단독 컨텍스트에서
 * [com.atlas.bts.identity.mfa.NonProdSensitiveProjectResolver](항상 false)가 담당하므로,
 * 본 테스트는 관리자 경로로 강제 대상을 결정적으로 구성한다.
 *
 * ## 테스트 환경 (부팅 레시피 — MfaTotpIntegrationTest 답습)
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 내장 Tomcat + Spring Security 필터 체인 전체 구동.
 * - `!prod` 프로필 — [com.atlas.bts.identity.jwt.DevMemoryKeyProvider] 사용(PEM 불필요).
 * - Testcontainers PostgreSQL 16 — Flyway 자동 마이그레이션.
 * - OAuth2ClientAutoConfiguration 제외 — keycloak issuer-uri 원격 discovery 회피.
 * - LDAP Bean 4종 `@MockBean` — Local 인증 단독 스택 검증.
 *
 * ## 검증 시나리오
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | S1 | 강제대상(SYSTEM_ADMIN, MFA 미설정) login | access_token 의 mfa_enrollment_required = true |
 * | S2 | 일반 사용자 login | access_token 의 mfa_enrollment_required = false |
 * | S3 | 강제대상 refresh 회전 후 | rotate 새 access_token 도 mfa_enrollment_required = true |
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class MfaEnforcementClaimIntegrationTest {
    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway 자동 마이그레이션 적용. */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** MFA 암호화 테스트 키 — MFA 빈 생성(MfaSecretEncryptor)에 필요. setup/enable 은 호출하지 않는다. */
        private const val MFA_TEST_KEY = "test-app-encryption-key-for-mfa-totp"

        /** MFA salt — `Encryptors.stronger` 가 hex 문자열을 요구하므로 유효 hex 리터럴. */
        private const val MFA_TEST_SALT = "deadbeefcafef00d"

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

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var localCredentialService: LocalCredentialService

    @Autowired
    lateinit var systemRoleAssignmentRepository: SystemRoleAssignmentRepository

    private val testPassword = "S3cur3P@ss!"

    @BeforeEach
    fun prepare() {
        // 각 테스트가 사용자 신규 생성으로 격리되므로 별도 정리는 불필요.
    }

    // ── S1. 강제대상(관리자, MFA 미설정) login → 클레임 true ───────────────────────

    @Test
    fun `강제대상(SYSTEM_ADMIN 미설정) login 토큰은 mfa_enrollment_required=true`() {
        val username = createLocalUser(systemAdmin = true)

        val login = performLogin(username, testPassword)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        val accessToken = (login.body as Map<*, *>)["access_token"] as String

        assertThat(mfaEnrollmentRequiredClaim(accessToken))
            .withFailMessage("관리자(미설정) login 토큰의 mfa_enrollment_required 가 true 여야 합니다(발급 chokepoint).")
            .isTrue()
    }

    // ── S2. 일반 사용자 login → 클레임 false ────────────────────────────────────

    @Test
    fun `일반 사용자 login 토큰은 mfa_enrollment_required=false`() {
        val username = createLocalUser(systemAdmin = false)

        val login = performLogin(username, testPassword)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        val accessToken = (login.body as Map<*, *>)["access_token"] as String

        assertThat(mfaEnrollmentRequiredClaim(accessToken))
            .withFailMessage("일반 사용자 login 토큰의 mfa_enrollment_required 가 false 여야 합니다(회귀 0).")
            .isFalse()
    }

    // ── S3. 강제대상 refresh 회전 후에도 클레임 true 유지 ───────────────────────────

    @Test
    fun `강제대상 refresh 회전 토큰도 mfa_enrollment_required=true`() {
        val username = createLocalUser(systemAdmin = true)

        val login = performLogin(username, testPassword)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        val refreshCookie = extractRefreshCookieValue(login)
        assertThat(refreshCookie).isNotBlank()

        val refresh = performRefresh(refreshCookie)
        assertThat(refresh.statusCode).isEqualTo(HttpStatus.OK)
        val rotatedAccessToken = (refresh.body as Map<*, *>)["access_token"] as String

        assertThat(mfaEnrollmentRequiredClaim(rotatedAccessToken))
            .withFailMessage("refresh 회전 후 새 access_token 도 mfa_enrollment_required=true 를 유지해야 합니다.")
            .isTrue()
    }

    // ── private 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * Local 자격 사용자를 신규 생성한다. [systemAdmin] 이면 SYSTEM_ADMIN 을 부여해 강제 대상으로 만든다.
     *
     * @return 생성된 사용자명(login 식별자).
     */
    private fun createLocalUser(systemAdmin: Boolean): String {
        val suffix = UUID.randomUUID().toString().take(8)
        val username = "mfa-enf-$suffix"
        val user =
            userRepository.save(
                username = username,
                email = "mfa-enf-$suffix@example.com",
                displayName = "MfaEnforcementUser",
            )
        localCredentialService.store(user.id, testPassword.toCharArray())
        if (systemAdmin) {
            systemRoleAssignmentRepository.assign(user.id, SystemRole.SYSTEM_ADMIN)
        }
        return username
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

    /** POST /api/v1/auth/refresh — Cookie 기반(CSRF skip). */
    private fun performRefresh(refreshTokenRaw: String): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply { set(HttpHeaders.COOKIE, "refresh_token=$refreshTokenRaw") }
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/refresh",
            HttpMethod.POST,
            HttpEntity<Any?>(null, headers),
            Map::class.java,
        )
    }

    /** 로그인/refresh 응답의 Set-Cookie 헤더에서 refresh_token 값을 추출한다. */
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
     * JWT access_token payload(가운데 세그먼트)를 Base64url 디코드해 `mfa_enrollment_required` 클레임을 반환한다.
     *
     * 서명 검증은 필터 체인이 이미 수행했으므로(login/refresh 200) payload 디코드만으로 클레임을 확인한다.
     * 비밀값을 로깅하지 않는다.
     */
    private fun mfaEnrollmentRequiredClaim(accessToken: String): Boolean {
        val payload = accessToken.split(".")[1]
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        return Regex("\"mfa_enrollment_required\"\\s*:\\s*(true|false)")
            .find(json)
            ?.groupValues
            ?.get(1) == "true"
    }
}
