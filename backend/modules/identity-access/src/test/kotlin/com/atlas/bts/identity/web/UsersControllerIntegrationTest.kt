// GET /api/v1/users 엔드포인트 통합 테스트 — 전체 목록 조회, query 필터, 미인증 401 검증

package com.atlas.bts.identity.web

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * GET /api/v1/users 통합 테스트 (FR-IS-03 Task 4).
 *
 * ## 테스트 환경
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 내장 Tomcat + Spring Security 필터 체인 전체 구동.
 * - Testcontainers PostgreSQL 16 — Flyway V001~V006 자동 마이그레이션 적용.
 * - LDAP Bean 은 @MockBean 으로 대체 (Local 인증만 사용).
 *
 * ## 검증 시나리오
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | TC-01 | 인증된 사용자 → GET /api/v1/users | 200 + 사용자 목록(id/username/displayName/email) |
 * | TC-02 | query=al 필터 → username/display_name ILIKE 부분일치 반환 | 200 + 일치 항목만 |
 * | TC-03 | 미인증 요청 → GET /api/v1/users | 401 |
 *
 * ## 인증 방식
 * 실제 로그인 흐름(POST /api/v1/auth/login)으로 JWT access_token 발급 후 Bearer 헤더 사용.
 * 미인증 케이스는 Authorization 헤더 없이 요청.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class UsersControllerIntegrationTest {
    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway V001~V006 적용 대상 */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

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
        }
    }

    // LDAP Bean — Local 인증 단독 스택 검증을 위해 목킹
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

    private val testUsername = "users-test-alice"
    private val testPassword = "S3cur3P@ss!"
    private val testEmail = "alice@bts.local"
    private val testDisplayName = "Alice Kim"

    private val otherUsername = "users-test-bob"
    private val otherEmail = "bob@bts.local"
    private val otherDisplayName = "Bob Lee"

    @BeforeEach
    fun prepareUsers() {
        val alice =
            userRepository.save(
                username = testUsername,
                email = testEmail,
                displayName = testDisplayName,
            )
        localCredentialService.store(alice.id, testPassword.toCharArray())

        userRepository.save(
            username = otherUsername,
            email = otherEmail,
            displayName = otherDisplayName,
        )
    }

    // ── TC-01. 인증된 사용자 전체 목록 조회 ─────────────────────────────────────

    /**
     * TC-01: 인증된 JWT Bearer 토큰으로 GET /api/v1/users 호출 시 200 + 사용자 목록 반환.
     *
     * 각 항목에 id / username / displayName / email 필드가 존재해야 한다.
     */
    @Test
    fun `TC-01 인증된 사용자가 GET users 호출 시 200과 사용자 목록 반환`() {
        val accessToken = login()

        val headers =
            HttpHeaders().apply {
                set(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            }
        val response =
            restTemplate.exchange(
                "http://localhost:$port/api/v1/users",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                List::class.java,
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val users = response.body as List<Map<String, Any>>
        assertThat(users).isNotEmpty()

        val alice = users.firstOrNull { it["username"] == testUsername }
        assertThat(alice).isNotNull()
        assertThat(alice!!["id"]).isNotNull()
        assertThat(alice["username"]).isEqualTo(testUsername)
        assertThat(alice["displayName"]).isEqualTo(testDisplayName)
        assertThat(alice["email"]).isEqualTo(testEmail)
    }

    // ── TC-02. query 파라미터 부분일치 필터 ──────────────────────────────────────

    /**
     * TC-02: query=alice 로 GET /api/v1/users?query=alice 호출 시
     * username 또는 display_name에 "alice" 포함 항목만 반환.
     *
     * "bob" 계정은 결과에 포함되지 않아야 한다.
     */
    @Test
    fun `TC-02 query 필터로 username 또는 displayName 부분일치 항목만 반환`() {
        val accessToken = login()

        val headers =
            HttpHeaders().apply {
                set(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            }
        val response =
            restTemplate.exchange(
                "http://localhost:$port/api/v1/users?query=alice",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                List::class.java,
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val users = response.body as List<Map<String, Any>>
        assertThat(users).isNotEmpty()

        val usernames = users.map { it["username"] as String }
        assertThat(usernames).contains(testUsername)
        assertThat(usernames).doesNotContain(otherUsername)
    }

    // ── TC-03. 미인증 요청 → 401 ──────────────────────────────────────────────

    /**
     * TC-03: Authorization 헤더 없이 GET /api/v1/users 호출 시 401 반환.
     *
     * §1.1-4 인증 없는 엔드포인트 추가 금지 — SecurityFilterChain 가드 검증.
     */
    @Test
    fun `TC-03 미인증 요청은 401 반환`() {
        val response =
            restTemplate.exchange(
                "http://localhost:$port/api/v1/users",
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders()),
                Map::class.java,
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── private 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/auth/login 으로 JWT access_token 을 발급하여 반환한다.
     *
     * @return raw JWT access_token 문자열
     */
    private fun login(): String {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
            }
        val body = """{"provider":"local","username":"$testUsername","password":"$testPassword"}"""
        val response =
            restTemplate.exchange(
                "http://localhost:$port/api/v1/auth/login",
                HttpMethod.POST,
                HttpEntity(body, headers),
                Map::class.java,
            )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return (response.body as Map<*, *>)["access_token"] as String
    }
}
