// MFA 강제 정책 종합 e2e 인수 테스트 — login→whoami→게이트 403→enable→refresh 해제 전체 스택 (FR-MF-04 Task 8)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.project.ProjectMembership
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.atlas.bts.identity.project.ProjectRole
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.systemrole.SystemRole
import com.atlas.bts.identity.systemrole.SystemRoleAssignmentRepository
import com.atlas.bts.identity.user.UserRepository
import com.bts.shared.permission.SensitiveProjectResolver
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.HashingAlgorithm
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * MFA(2FA) 강제 정책 종합 end-to-end 인수 테스트 (FR-MF-04 Task 8 / SDD §19.7.2).
 *
 * ## 목적 — 전 스택을 엮어 enforcement 시나리오(S1~S5 + B1)를 인수 검증
 * 기능은 선행 Task(T1~T7, 전부 머지됨)로 이미 구현됐다. 본 테스트는 분리된 단위/슬라이스 검증을
 * **실제 스택(필터 체인 → 컨트롤러 → 정책 → JwtIssuer → 게이트 필터)** 으로 묶어, 강제 대상의
 * 'login → whoami required → 보호경로 403 → MFA enable → refresh → 해제(200 + whoami false)'
 * 라이프사이클이 end-to-end 로 동작함을 확정한다. 단일 컴포넌트가 옳아도 배선이 틀리면 깨지는
 * 회로(클레임 발급 chokepoint ↔ whoami ↔ 게이트 allow-list)를 한 테스트군으로 박제한다.
 *
 * ## 강제 대상 만들기 — 두 경로
 * - **관리자 경로(S1/S2/S5)**: 테스트 사용자에게 SYSTEM_ADMIN 을 부여하면
 *   [com.atlas.bts.identity.permission.IdentityAccessSystemPermissionResolver] 가 non-prod 컨텍스트에서
 *   `system_role_assignments` 를 실제 조회해 강제 대상으로 판정한다.
 * - **민감 프로젝트 멤버 경로(S3)**: 관리자가 아닌 일반 사용자라도 '민감 프로젝트(require_2fa=true)' 멤버면
 *   강제 대상이다. 민감 표시 데이터는 issue-tracking BC 소유라 단독 컨텍스트에 부재하므로,
 *   [SensitiveProjectFakeConfig] 가 `@Primary` fake [SensitiveProjectResolver] 를 주입한다. fake 는
 *   미리 정한 [SensitiveProjectFakeConfig.SENSITIVE_PROJECT_ID] 가 입력 집합에 있을 때만 true 를 반환하므로,
 *   실제 멤버십 조회([ProjectMembershipRepository.listProjectIdsByUser]) 경로를 그대로 엮어 검증한다
 *   (입력 무관 true 면 멤버십 경로를 우회해 회로 검증력이 떨어진다).
 *
 * ## (B1) SSO 발급 경로 클레임 — chokepoint 단위 박제로 커버
 * `mfa_enrollment_required` 클레임은 [com.atlas.bts.identity.jwt.JwtIssuer] 내부 **단일 chokepoint** 에서
 * 박는다. 따라서 발급 경로(local login / refresh / OIDC / SAML 성공 핸들러)가 모두 동일 코드패스를 거쳐
 * 클레임을 포함한다. 본 인수 테스트에서 풀 OIDC/SAML IdP(Keycloak Testcontainers)를 띄우는 것은 과하고
 * 본 테스트 목적(강제 라이프사이클)과 직교하므로, SSO 경로의 클레임 보장은 chokepoint 단위 테스트
 * ([com.atlas.bts.identity.jwt.JwtIssuerTest] = T5)로 박제된 것을 신뢰한다. 여기서는 동일 chokepoint 를
 * 통과하는 local login·refresh 토큰의 클레임을 실 스택으로 확정해 chokepoint 동작을 간접 보증한다
 * (SSO 핸들러도 `jwtIssuer.issue(userId, ...)` 한 줄을 거치므로 동일 결과).
 *
 * ## 테스트 환경 (부팅 레시피 — MfaEnrollmentGateIntegrationTest / MfaTotpIntegrationTest 답습)
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 내장 Tomcat + Spring Security 필터 체인 전체 구동(게이트 포함).
 * - `!prod` 프로필 — [com.atlas.bts.identity.jwt.DevMemoryKeyProvider] 사용(PEM 불필요).
 * - Testcontainers PostgreSQL 16 — Flyway 자동 마이그레이션.
 * - OAuth2ClientAutoConfiguration 제외 — keycloak issuer-uri 원격 discovery 회피.
 * - LDAP Bean 4종 `@MockBean` — Local 인증 단독 스택 검증.
 * - `@Import([SensitiveProjectFakeConfig])` — 민감 프로젝트 판정만 fake(`@Primary`)로 대체(인증 우회 아님).
 *
 * ## 검증 시나리오
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | S1 | 관리자(미설정) login → whoami | mfaEnrollmentRequired=true |
 * | S1 | 관리자(미설정) 보호경로 GET /auth/sessions | 403 mfa_enrollment_required |
 * | S2 | 관리자 MFA enable → refresh → 보호경로 | 200 + whoami mfaEnrollmentRequired=false |
 * | S3 | 민감 프로젝트 멤버(일반, fake true) 미설정 | whoami required + 게이트 403 |
 * | S4 | 일반 사용자(관리자X, fake false, 미설정) | whoami false + 보호경로 200(회귀 0) |
 * | S5 | 이미 TOTP 설정한 관리자 | whoami false + 보호경로 200 |
 * | B1 | 발급 chokepoint(local/refresh) 클레임 | local·refresh 토큰 모두 클레임 반영(T5 SSO 박제 신뢰) |
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Import(MfaEnforcementEndToEndTest.SensitiveProjectFakeConfig::class)
@Testcontainers
class MfaEnforcementEndToEndTest {
    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway 자동 마이그레이션 적용. */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** MFA 암호화 테스트 키 — MFA 빈 생성(MfaSecretEncryptor) + setup/enable 동작에 필수. */
        private const val MFA_TEST_KEY = "test-app-encryption-key-for-mfa-totp"

        /** MFA salt — `Encryptors.stronger` 가 hex 문자열을 요구하므로 유효 hex 리터럴. */
        private const val MFA_TEST_SALT = "deadbeefcafef00d"

        /** 게이트가 차단 시 반환하는 에러 코드. */
        private const val GATE_ERROR_CODE = "mfa_enrollment_required"

        /** RFC 6238 time-step 길이(초) — 운영 TotpService.PERIOD_SECONDS 와 동일. */
        private const val TOTP_PERIOD_SECONDS = 30L

        /** RFC 6238 코드 자릿수 — 운영 TotpService.DIGITS 와 동일. */
        private const val TOTP_DIGITS = 6

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

    /**
     * 민감 프로젝트 판정만 fake 로 대체하는 테스트 설정 (S3 — 민감 멤버 경로).
     *
     * `@Primary` 로 non-prod fallback([com.atlas.bts.identity.mfa.NonProdSensitiveProjectResolver],
     * 항상 false)보다 우선 주입된다. fake 는 [SENSITIVE_PROJECT_ID] 가 입력 집합에 포함될 때만 true 를
     * 반환하므로, 실제 멤버십 조회 경로를 우회하지 않고 그대로 엮어 검증한다(인증 자체는 우회하지 않음).
     */
    @TestConfiguration
    class SensitiveProjectFakeConfig {
        companion object {
            /** S3 에서 사용자에게 멤버십을 부여할 '민감 프로젝트' 식별자(고정). */
            val SENSITIVE_PROJECT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000005ea")
        }

        @Bean
        @Primary
        fun fakeSensitiveProjectResolver(): SensitiveProjectResolver =
            object : SensitiveProjectResolver {
                override fun anyRequiresMfa(projectIds: Set<UUID>): Boolean = SENSITIVE_PROJECT_ID in projectIds
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

    @Autowired
    lateinit var membershipRepository: ProjectMembershipRepository

    private val testPassword = "S3cur3P@ss!"

    // ── S1/S2. 관리자 미설정 → required → 403 → enable → refresh → 해제 ───────────

    @Test
    fun `S1·S2 관리자 미설정은 게이트로 차단되다 MFA 등록·refresh 후 해제된다`() {
        val username = createLocalUser(systemAdmin = true)
        val login = performLogin(username, testPassword)
        val accessToken = accessTokenOf(login)

        // S1 — whoami required + 보호경로 403.
        assertThat(whoamiMfaEnrollmentRequired(accessToken))
            .withFailMessage("관리자(미설정) whoami 의 mfaEnrollmentRequired 가 true 여야 합니다.")
            .isTrue()
        assertGateBlocks(getWithBearer("/api/v1/auth/sessions", accessToken))

        // S2 — TOTP 등록 → refresh 로 새 토큰 → 게이트 해제 + whoami false.
        val secret = enableTotp(accessToken)
        val refreshed = performRefresh(extractRefreshCookieValue(login))
        assertThat(refreshed.statusCode).isEqualTo(HttpStatus.OK)
        val refreshedToken = accessTokenOf(refreshed)

        assertThat(getWithBearer("/api/v1/auth/sessions", refreshedToken).statusCode)
            .withFailMessage("MFA 등록 + refresh 후 보호경로는 200 으로 통과해야 합니다(게이트 해제).")
            .isEqualTo(HttpStatus.OK)
        assertThat(whoamiMfaEnrollmentRequired(refreshedToken))
            .withFailMessage("MFA 등록 + refresh 후 whoami mfaEnrollmentRequired 가 false 여야 합니다.")
            .isFalse()

        // secret 은 enable 산출물(로깅 금지). 사용 후 참조만 유지해 미사용 경고 회피.
        assertThat(secret).isNotBlank()
    }

    // ── S3. 민감 프로젝트 멤버(일반, fake true) 미설정 → required + 게이트 403 ──────

    @Test
    fun `S3 민감 프로젝트 멤버(일반 사용자)는 미설정 시 강제 대상이다`() {
        val username = createLocalUser(systemAdmin = false)
        seedSensitiveMembership(username)
        val accessToken = accessTokenOf(performLogin(username, testPassword))

        assertThat(whoamiMfaEnrollmentRequired(accessToken))
            .withFailMessage("민감 프로젝트 멤버(미설정) whoami 의 mfaEnrollmentRequired 가 true 여야 합니다.")
            .isTrue()
        assertGateBlocks(getWithBearer("/api/v1/auth/sessions", accessToken))
    }

    // ── S4. 일반 사용자(관리자X, fake false, 미설정) → 영향 0(회귀) ────────────────

    @Test
    fun `S4 일반 사용자는 강제 대상이 아니며 보호경로를 통과한다(회귀 0)`() {
        val username = createLocalUser(systemAdmin = false)
        val accessToken = accessTokenOf(performLogin(username, testPassword))

        assertThat(whoamiMfaEnrollmentRequired(accessToken))
            .withFailMessage("일반 사용자 whoami 의 mfaEnrollmentRequired 가 false 여야 합니다(회귀 0).")
            .isFalse()
        assertThat(getWithBearer("/api/v1/auth/sessions", accessToken).statusCode)
            .withFailMessage("일반 사용자는 보호경로를 200 으로 통과해야 합니다(회귀 0).")
            .isEqualTo(HttpStatus.OK)
    }

    // ── S5. 이미 TOTP 설정한 관리자 → 무영향 ─────────────────────────────────────

    @Test
    fun `S5 이미 MFA 설정한 관리자는 강제 대상이 아니며 통과한다`() {
        val username = createLocalUser(systemAdmin = true)
        // enable 전 login(정식 토큰 + refresh 쿠키 발급). 이후 enable 으로 TOTP 활성화하면 재login 은
        // mfa challenge 만 반환하므로, 보관한 refresh 쿠키로 chokepoint 를 재통과해 false 클레임 토큰을 받는다.
        val initialLogin = performLogin(username, testPassword)
        enableTotp(accessTokenOf(initialLogin))

        // 설정 완료 후 새 토큰을 받아야 클레임이 false 로 재발급된다(refresh = 발급 chokepoint 재통과).
        val refreshedToken = accessTokenOf(performRefresh(extractRefreshCookieValue(initialLogin)))

        assertThat(whoamiMfaEnrollmentRequired(refreshedToken))
            .withFailMessage("이미 MFA 설정한 관리자 whoami 의 mfaEnrollmentRequired 가 false 여야 합니다(EC9).")
            .isFalse()
        assertThat(getWithBearer("/api/v1/auth/sessions", refreshedToken).statusCode)
            .withFailMessage("이미 MFA 설정한 관리자는 보호경로를 200 으로 통과해야 합니다.")
            .isEqualTo(HttpStatus.OK)
    }

    // ── B1. 발급 chokepoint 클레임 — local/refresh 토큰 (SSO 는 T5 단위 박제) ───────

    @Test
    fun `B1 발급 chokepoint는 local과 refresh 토큰 모두에 강제 클레임을 반영한다`() {
        val username = createLocalUser(systemAdmin = true)
        val login = performLogin(username, testPassword)

        assertThat(mfaEnrollmentRequiredClaim(accessTokenOf(login)))
            .withFailMessage("local login 토큰에 mfa_enrollment_required=true 가 박혀야 합니다(발급 chokepoint).")
            .isTrue()
        val refreshedToken = accessTokenOf(performRefresh(extractRefreshCookieValue(login)))
        assertThat(mfaEnrollmentRequiredClaim(refreshedToken))
            .withFailMessage("refresh 회전 토큰에도 동일 chokepoint 로 클레임이 박혀야 합니다(SSO 경로는 T5 단위 박제).")
            .isTrue()
    }

    // ── private 헬퍼 ────────────────────────────────────────────────────────────

    /** Local 자격 사용자를 신규 생성한다. [systemAdmin] 이면 SYSTEM_ADMIN 부여(관리자 강제 경로). 사용자명 반환. */
    private fun createLocalUser(systemAdmin: Boolean): String {
        val suffix = UUID.randomUUID().toString().take(8)
        val username = "mfa-e2e-$suffix"
        val user =
            userRepository.save(
                username = username,
                email = "mfa-e2e-$suffix@example.com",
                displayName = "MfaE2eUser",
            )
        localCredentialService.store(user.id, testPassword.toCharArray())
        if (systemAdmin) {
            systemRoleAssignmentRepository.assign(user.id, SystemRole.SYSTEM_ADMIN)
        }
        return username
    }

    /** [username] 사용자를 민감 프로젝트([SensitiveProjectFakeConfig.SENSITIVE_PROJECT_ID])의 멤버로 시드한다. */
    private fun seedSensitiveMembership(username: String) {
        val userId = userRepository.findByUsername(username)?.id ?: error("시드한 사용자를 찾지 못했습니다: $username")
        val now = Instant.now()
        membershipRepository.save(
            ProjectMembership(
                projectId = SensitiveProjectFakeConfig.SENSITIVE_PROJECT_ID,
                userId = userId,
                role = ProjectRole.MEMBER,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * setup→enable 로 [accessToken] 사용자의 TOTP 를 활성화하고 secret(base32)을 반환한다.
     *
     * 운영 코드와 동일 파라미터(SHA1·6자리·30초)로 정답 코드를 산출한다. 비밀값은 로깅하지 않는다.
     */
    private fun enableTotp(accessToken: String): String {
        val setup = mfaSetup(accessToken)
        assertThat(setup.statusCode)
            .withFailMessage("TOTP setup 은 200 이어야 합니다. 실제: ${setup.statusCode}")
            .isEqualTo(HttpStatus.OK)
        val secret = extractSecretFromOtpauthUri((setup.body as Map<*, *>)["otpauth_uri"] as String)
        val enable = mfaEnable(accessToken, currentTotpCode(secret))
        assertThat(enable.statusCode)
            .withFailMessage("TOTP enable 은 204 이어야 합니다. 실제: ${enable.statusCode}")
            .isEqualTo(HttpStatus.NO_CONTENT)
        return secret
    }

    /** 응답 바디의 access_token 을 추출한다. */
    private fun accessTokenOf(response: ResponseEntity<Map<*, *>>): String {
        return (response.body as Map<*, *>)["access_token"] as String
    }

    /** whoami 를 호출해 mfaEnrollmentRequired 플래그를 읽는다(클레임 단일 출처 노출 검증). */
    private fun whoamiMfaEnrollmentRequired(accessToken: String): Boolean {
        val resp = getWithBearer("/api/v1/users/me/whoami", accessToken)
        assertThat(resp.statusCode)
            .withFailMessage("whoami 는 200 이어야 합니다(allow-list 통과). 실제: ${resp.statusCode}, body=${resp.body}")
            .isEqualTo(HttpStatus.OK)
        return resp.body?.get("mfaEnrollmentRequired") == true
    }

    /** 게이트가 보호경로 요청을 403 `mfa_enrollment_required` 로 차단했음을 단언한다. */
    private fun assertGateBlocks(response: ResponseEntity<Map<*, *>>) {
        assertThat(response.statusCode)
            .withFailMessage("강제대상 미설정 토큰은 보호경로에서 게이트가 403 으로 차단해야 합니다. 실제: ${response.statusCode}")
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body?.get("error") as? String)
            .withFailMessage("게이트 차단 응답 바디는 {error:mfa_enrollment_required} 여야 합니다(내부 상세 누출 금지).")
            .isEqualTo(GATE_ERROR_CODE)
    }

    /** POST /api/v1/auth/login — CSRF skip(ignoringRequestMatchers). */
    private fun performLogin(
        username: String,
        password: String,
    ): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"provider":"local","username":"$username","password":"$password"}"""
        val response =
            restTemplate.exchange(
                "http://localhost:$port/api/v1/auth/login",
                HttpMethod.POST,
                HttpEntity(body, headers),
                Map::class.java,
            )
        assertThat(response.statusCode)
            .withFailMessage("login 은 200 이어야 합니다(TOTP 미활성 사용자). 실제: ${response.statusCode}, body=${response.body}")
            .isEqualTo(HttpStatus.OK)
        return response
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

    /** POST /api/v1/auth/mfa/totp/setup — JWT bearer. */
    private fun mfaSetup(accessToken: String): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/mfa/totp/setup",
            HttpMethod.POST,
            HttpEntity<Unit>(bearerHeaders(accessToken)),
            Map::class.java,
        )

    /** POST /api/v1/auth/mfa/totp/enable — JWT bearer + 코드 body. */
    private fun mfaEnable(
        accessToken: String,
        code: String,
    ): ResponseEntity<Map<*, *>> {
        val headers = bearerHeaders(accessToken).apply { contentType = MediaType.APPLICATION_JSON }
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/mfa/totp/enable",
            HttpMethod.POST,
            HttpEntity("""{"code":"$code"}""", headers),
            Map::class.java,
        )
    }

    private fun getWithBearer(
        path: String,
        bearer: String,
    ): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(
            "http://localhost:$port$path",
            HttpMethod.GET,
            HttpEntity<Unit>(bearerHeaders(bearer)),
            Map::class.java,
        )

    private fun bearerHeaders(accessToken: String): HttpHeaders =
        HttpHeaders().apply { set(HttpHeaders.AUTHORIZATION, "Bearer $accessToken") }

    /**
     * 서버의 실제 현재 시각 기준으로 [secret] 의 정답 TOTP 코드를 산출한다.
     *
     * 운영 코드와 동일 파라미터(SHA1·6자리·30초)로 계산해 서버 검증과 일치한다.
     * 산출↔검증 사이 time-step 경계를 넘어도 서버는 ±1 오차를 허용하므로 통과한다(flaky 방지).
     */
    private fun currentTotpCode(secret: String): String {
        val timeStep = Instant.now().epochSecond / TOTP_PERIOD_SECONDS
        return DefaultCodeGenerator(HashingAlgorithm.SHA1, TOTP_DIGITS).generate(secret, timeStep)
    }

    /** otpauth:// URI 의 `secret` 쿼리 파라미터를 추출한다. */
    private fun extractSecretFromOtpauthUri(otpauthUri: String): String {
        val query = URI(otpauthUri).query.orEmpty()
        return query
            .split("&")
            .first { it.startsWith("secret=") }
            .substringAfter("secret=")
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
        val json = String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        return Regex("\"mfa_enrollment_required\"\\s*:\\s*(true|false)")
            .find(json)
            ?.groupValues
            ?.get(1) == "true"
    }
}
