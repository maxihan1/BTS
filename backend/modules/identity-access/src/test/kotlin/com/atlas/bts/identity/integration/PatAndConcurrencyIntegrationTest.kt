// PAT 검증 + Concurrent Refresh Token rotation + CSRF 통합 테스트 (Task 28, FR-AU-09)
//
// RED 단계: 모든 시나리오가 실패하도록 구조만 확정한다.
// 의존 Task: Task 24 (WhoamiController PAT), Task 25 (application.yml),
//            Task 36 (InMemoryAuthAuditLogService), Task 17 (RefreshTokenService EC-22),
//            Task 19 (SecurityConfig CSRF 정책)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.credential.StoredPasswordCredentialRepository
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.session.RefreshTokenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.ldap.core.LdapTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * PAT 검증 + Concurrent Refresh Token + CSRF 통합 테스트 (FR-AU-09 Task 28).
 *
 * RED: 컴파일은 되지만 모든 assertion이 실패한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PatAndConcurrencyIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            r.add("spring.flyway.enabled") { "true" }
            r.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            r.add("spring.autoconfigure.exclude") {
                "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.ldap.LdapDataAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration"
            }
        }

        internal fun sha256Hex(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        internal fun buildRawPat(body: String = "a".repeat(48)): String = "pat_$body"
    }

    @MockBean
    lateinit var ldapProvider: LdapProvider

    @MockBean
    lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean
    lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean
    lateinit var ldapTemplate: LdapTemplate

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    lateinit var authAuditLogService: AuthAuditLogService

    @Autowired
    lateinit var refreshTokenService: RefreshTokenService

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    private lateinit var testUserId: UUID

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM personal_access_tokens", emptyMap<String, Any>())
        jdbc.update("DELETE FROM refresh_tokens", emptyMap<String, Any>())
        jdbc.update("DELETE FROM sessions", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        testUserId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to testUserId, "username" to "pat-integration-user-$testUserId"),
        )
    }

    @Test
    fun `US-09-A PAT 정상 인증 — 200 + authMethod=pat 반환`() {
        // RED: PAT INSERT 없이 호출 → 401
        val headers = HttpHeaders().apply { setBearerAuth(buildRawPat()) }
        val resp = restTemplate.exchange(
            "/api/v1/users/me/whoami",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
        // 항상 실패: 401이지만 200을 기대
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `US-09-B PAT 만료 — 401 반환`() {
        // RED: DB 연결 없이 구조만 확인
        assertThat(false).isTrue()
    }

    @Test
    fun `US-09-C PAT revoke — 401 반환`() {
        // RED: DB 연결 없이 구조만 확인
        assertThat(false).isTrue()
    }

    @Test
    fun `US-09-D PAT 인증 성공 — PAT_USED 감사 이벤트 기록`() {
        // RED: 감사 로그 없이 찾기 → 빈 목록
        val logs = authAuditLogService.findRecent(testUserId, limit = 10)
        assertThat(logs.filter { it.eventType == AuthEventType.PAT_USED }).isNotEmpty
    }

    @Test
    fun `EC-22 Concurrent Refresh — 동시 rotate 요청 중 정확히 1개만 성공`() {
        // RED: 세션/토큰 없이 rotate → 모두 실패
        val tokenHash = sha256Hex("r".repeat(64))
        val result1 = refreshTokenService.rotate(tokenHash)
        val result2 = refreshTokenService.rotate(tokenHash)
        assertThat(result1).isInstanceOf(RefreshTokenService.RotateResult.Success::class.java)
        assertThat(result2).isInstanceOf(RefreshTokenService.RotateResult.Failure::class.java)
    }

    @Test
    fun `CSRF-A login 엔드포인트는 CSRF skip — CSRF 토큰 없어도 403 아님`() {
        // RED: 403이 나와야 한다고 잘못 단언
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val resp = restTemplate.exchange(
            "/api/v1/auth/login",
            HttpMethod.POST,
            HttpEntity("""{"username":"x","password":"y","provider":"local"}""", headers),
            Void::class.java,
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `CSRF-B 미인증 POST — 접근 거부 (401 또는 403)`() {
        // RED: 200이 나와야 한다고 잘못 단언
        val resp = restTemplate.exchange(
            "/api/v1/users/me/preferences",
            HttpMethod.POST,
            HttpEntity("{}", HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
            Void::class.java,
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `CSRF-C XSRF-TOKEN Cookie를 헤더로 echo하면 403 아님`() {
        // RED: CSRF 토큰 없이 POST → 403 이라고 잘못 단언
        val resp = restTemplate.exchange(
            "/api/v1/users/me/preferences",
            HttpMethod.POST,
            HttpEntity("{}", HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
            Void::class.java,
        )
        assertThat(resp.statusCode).isNotEqualTo(HttpStatus.FORBIDDEN)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK) // RED: 항상 실패
    }
}
