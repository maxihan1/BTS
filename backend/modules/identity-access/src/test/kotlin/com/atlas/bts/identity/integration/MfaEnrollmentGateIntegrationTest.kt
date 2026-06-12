// MFA 등록 게이트 e2e 통합 테스트 — 강제대상 미등록 토큰의 보호경로 403 + allow-list 통과 + 회귀 0 검증 (FR-MF-04 Task 7)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenRepository
import com.atlas.bts.identity.pat.sha256Hex
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.systemrole.SystemRole
import com.atlas.bts.identity.systemrole.SystemRoleAssignmentRepository
import com.atlas.bts.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
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

/**
 * MFA 등록 게이트([com.atlas.bts.identity.config.MfaEnrollmentGateFilter]) e2e 통합 테스트
 * (FR-MF-04 Task 7 / SDD §19.7.2).
 *
 * ## 목적 — 미등록 강제대상의 보호경로 차단 + allow-list 통과 양방향 검증
 * access JWT 의 `mfa_enrollment_required=true` 인 요청은 enrollment(MFA 설정) + whoami + logout +
 * refresh 의 allow-list 외 경로를 전부 **403 `mfa_enrollment_required`** 로 차단한다. allow-list 경로는
 * 그대로 통과해야 한다 — 누락 시 강제대상이 MFA 설정조차 못 하는 **영구 락**이 되므로 경로별로 명시 검증한다
 * (리뷰 C3). 클레임 false 토큰·PAT(클레임 부재)는 게이트가 통과시켜 기존 인증 흐름 회귀가 0임을 확정한다.
 *
 * ## 강제 대상 만들기 — SYSTEM_ADMIN 부여(실 정책 경로)
 * [com.atlas.bts.identity.permission.IdentityAccessSystemPermissionResolver] 가 non-prod 컨텍스트에서
 * `system_role_assignments` 를 실제 조회하므로, 테스트 사용자에게 SYSTEM_ADMIN 을 부여하면
 * [com.atlas.bts.identity.mfa.MfaEnforcementPolicy] 가 강제대상으로 판정한다. MFA 미설정 상태이므로
 * `evaluate=true` → login 토큰 클레임 `mfa_enrollment_required=true`. 일반 사용자는 역할 미부여 →
 * `evaluate=false`(회귀 케이스). 민감 프로젝트 경로는 단독 컨텍스트의
 * [com.atlas.bts.identity.mfa.NonProdSensitiveProjectResolver](항상 false)가 담당하므로,
 * 본 테스트는 관리자 경로로 강제대상을 결정적으로 구성한다.
 *
 * ## 보호 경로 선택 — `GET /api/v1/auth/sessions` (리뷰 C4)
 * 게이트의 차단을 검증하려면 identity-access 단독 컨텍스트에 **실재하는** authenticated 보호 경로가 필요하다.
 * `/api/v1/issues` 는 issue-tracking 소속이라 단독 컨텍스트에 부재하므로 쓰지 않는다(C4).
 * `GET /api/v1/auth/sessions` 는 authenticated + allow-list 외이므로 게이트의 차단 대상으로 적합하다.
 * (JWT 강제대상 → 컨트롤러 도달 전에 게이트가 403 으로 끊는다.)
 *
 * ## 테스트 환경 (부팅 레시피 — MfaEnforcementClaimIntegrationTest 답습)
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 Tomcat + Spring Security 필터 체인 전체 구동(게이트 포함).
 * - `!prod` 프로필 — DevMemoryKeyProvider 사용(PEM 불필요).
 * - Testcontainers PostgreSQL 16 — Flyway 자동 마이그레이션.
 * - OAuth2ClientAutoConfiguration 제외 — keycloak issuer-uri 원격 discovery 회피.
 * - LDAP Bean 4종 `@MockBean` — Local 인증 단독 스택 검증.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class MfaEnrollmentGateIntegrationTest {
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

        /** 게이트가 차단 시 반환하는 에러 코드. */
        private const val GATE_ERROR_CODE = "mfa_enrollment_required"

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

    @Autowired
    lateinit var patRepository: PersonalAccessTokenRepository

    private val testPassword = "S3cur3P@ss!"

    // ── (a) 강제대상(미등록) → 보호경로 GET /auth/sessions 403 ─────────────────────

    @Test
    fun `강제대상 미등록 토큰은 allow-list 외 보호경로(sessions)에서 403 mfa_enrollment_required`() {
        val accessToken = loginAccessToken(systemAdmin = true)

        val response = getWithBearer("/api/v1/auth/sessions", accessToken)

        assertThat(response.statusCode)
            .withFailMessage("강제대상 미등록 토큰은 allow-list 외 보호경로에서 게이트가 403 으로 차단해야 합니다.")
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(errorCode(response))
            .withFailMessage("게이트 차단 응답 바디는 {error:mfa_enrollment_required} 여야 합니다(내부 상세 누출 금지).")
            .isEqualTo(GATE_ERROR_CODE)
    }

    // ── (b) allow-list 경로별 명시 통과(영구 락 방지 — 리뷰 C3) ──────────────────────

    @Test
    fun `강제대상 토큰도 allow-list 경로는 게이트를 통과한다(영구 락 방지)`() {
        val accessToken = loginAccessToken(systemAdmin = true)

        // MFA enrollment 경로 — 게이트는 통과(컨트롤러가 자체 상태코드 반환). 게이트 403 만 아니면 통과로 판정.
        assertPassesGate(postWithBearer("/api/v1/auth/mfa/totp/setup", accessToken))
        assertPassesGate(postWithBearerBody("/api/v1/auth/mfa/totp/enable", accessToken, """{"code":"000000"}"""))
        assertPassesGate(getWithBearer("/api/v1/auth/mfa/totp", accessToken))
        assertPassesGate(deleteWithBearerBody("/api/v1/auth/mfa/totp", accessToken, """{"code":"000000"}"""))
        assertPassesGate(postWithBearer("/api/v1/auth/mfa/backup-codes", accessToken))
        assertPassesGate(getWithBearer("/api/v1/auth/mfa/backup-codes", accessToken))

        // whoami / logout / refresh allow-list
        assertPassesGate(getWithBearer("/api/v1/users/me/whoami", accessToken))
        assertPassesGate(postWithBearer("/api/v1/auth/logout", accessToken))
        assertPassesGate(postWithBearer("/api/v1/auth/refresh", accessToken))
    }

    // ── (c) 클레임 false 토큰 → 모든 경로 통과(회귀 0) ────────────────────────────

    @Test
    fun `클레임 false 토큰은 보호경로를 포함해 모든 경로를 통과한다(회귀 0)`() {
        val accessToken = loginAccessToken(systemAdmin = false)

        // allow-list 외 보호경로도 게이트 차단 없이 통과(일반 사용자는 강제대상 아님).
        assertPassesGate(getWithBearer("/api/v1/auth/sessions", accessToken))
        assertPassesGate(getWithBearer("/api/v1/users/me/whoami", accessToken))
        assertPassesGate(postWithBearer("/api/v1/auth/mfa/totp/setup", accessToken))
    }

    // ── (d) PAT(클레임 부재) → 게이트 통과 ──────────────────────────────────────

    @Test
    fun `PAT 인증은 클레임 부재로 게이트를 통과한다`() {
        val rawPat = createPat()

        // PAT 로 allow-list 외 보호경로 호출 — 게이트는 통과(컨트롤러의 PAT 정책 403 은 게이트 차단과 별개).
        val response = getWithBearer("/api/v1/auth/sessions", rawPat)

        assertThat(errorCode(response))
            .withFailMessage("PAT 요청은 클레임 부재로 게이트를 통과해야 합니다(게이트 403 mfa_enrollment_required 아님).")
            .isNotEqualTo(GATE_ERROR_CODE)
    }

    // ── private 헬퍼 ────────────────────────────────────────────────────────────

    /** Local 자격 사용자를 신규 생성하고 login 해 access_token 을 반환한다. [systemAdmin] 이면 강제대상. */
    private fun loginAccessToken(systemAdmin: Boolean): String {
        val username = createLocalUser(systemAdmin)
        val login = performLogin(username, testPassword)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        return (login.body as Map<*, *>)["access_token"] as String
    }

    /**
     * Local 자격 사용자를 신규 생성한다. [systemAdmin] 이면 SYSTEM_ADMIN 을 부여해 강제 대상으로 만든다.
     *
     * @return 생성된 사용자명(login 식별자).
     */
    private fun createLocalUser(systemAdmin: Boolean): String {
        val suffix = UUID.randomUUID().toString().take(8)
        val username = "mfa-gate-$suffix"
        val user =
            userRepository.save(
                username = username,
                email = "mfa-gate-$suffix@example.com",
                displayName = "MfaGateUser",
            )
        localCredentialService.store(user.id, testPassword.toCharArray())
        if (systemAdmin) {
            systemRoleAssignmentRepository.assign(user.id, SystemRole.SYSTEM_ADMIN)
        }
        return username
    }

    /**
     * 활성 PAT 를 직접 영속해 raw token 을 반환한다(PAT 발급 엔드포인트 미구현 — FR-AU-09 KDoc).
     *
     * token_hash = SHA-256(raw) (EC-26). 무기한(만료 없음) + revoke 없음 → 활성.
     */
    private fun createPat(): String {
        val suffix = UUID.randomUUID().toString().take(8)
        val user =
            userRepository.save(
                username = "mfa-gate-pat-$suffix",
                email = "mfa-gate-pat-$suffix@example.com",
                displayName = "MfaGatePatUser",
            )
        val rawToken =
            PersonalAccessToken.TOKEN_PREFIX +
                UUID.randomUUID().toString().replace("-", "") +
                "abcdef0123456789"
        patRepository.save(
            PersonalAccessToken(
                id = UUID.randomUUID(),
                userId = user.id,
                name = "gate-test",
                tokenHash = sha256Hex(rawToken),
                scopes = listOf("*"),
                expiresAt = null,
                lastUsedAt = null,
                revokedAt = null,
                createdAt = Instant.now(),
            ),
        )
        return rawToken
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

    private fun getWithBearer(
        path: String,
        bearer: String,
    ): ResponseEntity<Map<*, *>> = exchange(path, HttpMethod.GET, bearer, body = null)

    private fun postWithBearer(
        path: String,
        bearer: String,
    ): ResponseEntity<Map<*, *>> = exchange(path, HttpMethod.POST, bearer, body = null)

    private fun postWithBearerBody(
        path: String,
        bearer: String,
        json: String,
    ): ResponseEntity<Map<*, *>> = exchange(path, HttpMethod.POST, bearer, body = json)

    private fun deleteWithBearerBody(
        path: String,
        bearer: String,
        json: String,
    ): ResponseEntity<Map<*, *>> = exchange(path, HttpMethod.DELETE, bearer, body = json)

    /** Bearer 토큰 + 선택적 JSON body 로 [method] 요청을 보낸다. */
    private fun exchange(
        path: String,
        method: HttpMethod,
        bearer: String,
        body: String?,
    ): ResponseEntity<Map<*, *>> {
        val headers =
            HttpHeaders().apply {
                set(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
                contentType = MediaType.APPLICATION_JSON
            }
        return restTemplate.exchange(
            "http://localhost:$port$path",
            method,
            HttpEntity(body, headers),
            Map::class.java,
        )
    }

    /** 응답 바디에서 `error` 코드를 추출한다(없으면 null). */
    private fun errorCode(response: ResponseEntity<Map<*, *>>): String? = response.body?.get("error") as? String

    /** 게이트가 차단하지 않았음(=통과)을 단언한다 — 403 이 아니거나, 403 이어도 게이트 코드가 아니어야 한다. */
    private fun assertPassesGate(response: ResponseEntity<Map<*, *>>) {
        val blockedByGate =
            response.statusCode == HttpStatus.FORBIDDEN && errorCode(response) == GATE_ERROR_CODE
        assertThat(blockedByGate)
            .withFailMessage(
                "allow-list 경로는 게이트를 통과해야 합니다(영구 락 방지). " +
                    "실제 status=${response.statusCode}, body=${response.body}",
            )
            .isFalse()
    }
}
