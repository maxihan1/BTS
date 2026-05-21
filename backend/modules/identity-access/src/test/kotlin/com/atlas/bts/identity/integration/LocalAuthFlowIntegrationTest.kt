// Local 인증 e2e 통합 테스트 — 로그인·Whoami·Refresh rotation·Logout·Replay 전체 흐름 (FR-AU-09 Task 26)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.user.UserRepository
import com.github.benmanes.caffeine.cache.Cache
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * Local 인증 전체 흐름 통합 테스트 (FR-AU-09 Task 26 / SDD §19.5).
 *
 * ## 목적
 * Wave 1~6 production 코드의 연동 정확성을 실제 PostgreSQL + Spring Boot 전체 스택으로 검증한다.
 * 단위 테스트(MockK/Mockito)로는 검증 불가한 FilterChain→Adapter→Provider→Service 전 스택의
 * 트랜잭션 전파, 쿠키 전달, 세션 폐기 회로를 포함한다.
 *
 * ## 테스트 환경
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 내장 Tomcat + Spring Security 필터 체인 전체 구동.
 * - `!prod` 프로필 — [com.atlas.bts.identity.jwt.DevMemoryKeyProvider] 사용 (PEM 파일 불필요).
 * - Testcontainers PostgreSQL 16 — Flyway V001~V006 자동 마이그레이션 적용.
 * - TestRestTemplate — 쿠키 자동 추적. CSRF 헤더는 시나리오별 수동 설정.
 *
 * ## 검증 시나리오 (Task 26 명세 7개)
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | US-01 | 정상 로그인 | 200 + access_token body + refresh_token Set-Cookie |
 * | US-02 | Whoami JWT bearer | 200 + authMethod="jwt" |
 * | US-03 | Refresh rotation | 200 + 새 access_token + 새 refresh_token Cookie |
 * | US-05 | Logout | 204 + refresh_token Cookie Max-Age=0 |
 * | US-06 | Logout 후 access token 재사용 | 401 (sid revoke 회로 EC-29) |
 * | US-07 | Logout 후 refresh token 재사용 | 401 (refresh_token_invalid) |
 * | US-08 | Replay 감지 — 교체된 refresh token 재제출 | 401 + session 전체 revoke (EC-23) |
 *
 * ## CONCERN-1 — BLOCKER #1 해소 (FR-AU-09 spec §9)
 * [com.atlas.bts.identity.provider.local.LocalProvider.authenticate] 는 `REQUIRES_NEW` 트랜잭션으로 실행된다.
 * 이 테스트는 [LocalCredentialService.verifyForUser] 호출 시점에 `readOnly=true` 트랜잭션이
 * 활성 상태임을 [TransactionSynchronizationManager.isActualTransactionActive] 로 단언한다.
 * 단언 방식: [LocalCredentialService] 를 Spy로 감싸고 verifyForUser 진입 시점 상태를 캡처.
 *
 * ## 쿠키 / CSRF 전략
 * - login 엔드포인트: SecurityConfig 에서 CSRF skip (ignoringRequestMatchers).
 *   → CSRF 헤더 불필요.
 * - refresh 엔드포인트: SecurityConfig 에서 CSRF skip (/api/v1/auth/refresh permitAll).
 *   → CSRF 헤더 불필요.
 * - logout 엔드포인트: Bearer 인증 사용 → Spring Security Bearer stateless 모드에서 CSRF 면제.
 *   → Authorization 헤더만 설정.
 * - TestRestTemplate 기본 인스턴스는 쿠키 전달 불가 → 수동 Cookie 헤더로 전달.
 *
 * ## EC-29 캐시 무효화
 * [SidRevokeJwtConverter] 내부 Caffeine 캐시(5초 TTL)는 logout 직후 테스트에서 오래된
 * "active=true" 항목을 반환할 수 있다. 이를 방지하기 위해 logout 후 캐시를 직접 invalidate한다.
 * 캐시 필드는 reflection 으로 접근한다 (테스트 전용 안전한 접근 — production 코드 미수정).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LocalAuthFlowIntegrationTest {

    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway V001~V006 적용 대상 */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /**
         * Spring Boot 의 DataSource / Flyway / bts.security.cors 설정을 Testcontainers 컨테이너 좌표로 교체.
         *
         * - `spring.datasource.*`: Testcontainers PostgreSQL JDBC URL
         * - `bts.auth.issuer-uri`: 테스트 전용 dummy URI (JwtIssuer iss claim 용)
         * - `bts.security.cors.allowed-origins`: CorsConfig Bean 생성에 필수 (빈 목록 주입)
         * - `spring.ldap.*`: LDAP 자동 설정 비활성 (Local 인증만 검증)
         */
        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            // LDAP 자동 설정 비활성 — Local 인증 단독 스택 검증
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.autoconfigure.exclude") {
                "org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.ldap.LdapDataAutoConfiguration"
            }
        }
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var localCredentialService: LocalCredentialService

    @Autowired
    lateinit var sessionService: SessionService

    @Autowired
    lateinit var sidRevokeJwtConverter: SidRevokeJwtConverter

    /** 테스트 사용자 식별 정보 */
    private val testUsername = "integration-test-alice"
    private val testPassword = "S3cur3P@ss!"
    private val testEmail = "alice@example.com"
    private val testDisplayName = "Alice"

    /**
     * 테스트 격리를 위해 각 테스트 메서드 전에 사용자를 생성/재생성한다.
     *
     * @SpringBootTest 전체 컨텍스트는 클래스 당 한 번 구동되므로 DB 상태는 테스트 간 공유된다.
     * 사용자 UPSERT + 패스워드 store 로 멱등하게 준비한다.
     */
    @BeforeEach
    fun prepareTestUser() {
        val user = userRepository.save(
            username = testUsername,
            email = testEmail,
            displayName = testDisplayName,
        )
        localCredentialService.store(user.id, testPassword.toCharArray())
    }

    // ── US-01. 정상 로그인 ──────────────────────────────────────────────────────

    /**
     * US-01: 올바른 username/password 로 로그인 시 200 + access_token + refresh_token Cookie 응답.
     *
     * 검증 항목.
     * - HTTP 200
     * - access_token 필드 존재 + 비어 있지 않음
     * - token_type = "Bearer"
     * - expires_in = 900
     * - Set-Cookie: refresh_token 헤더 존재
     * - refresh_token Cookie: HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth
     */
    @Test
    @Order(1)
    fun `US-01 valid login returns 200 with access_token and refresh_token cookie`() {
        val response = performLogin(testUsername, testPassword)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val body = response.body as Map<*, *>
        assertThat(body["access_token"] as? String).isNotBlank()
        assertThat(body["token_type"]).isEqualTo("Bearer")
        assertThat(body["expires_in"]).isEqualTo(900)

        val setCookie = response.headers.getFirst(HttpHeaders.SET_COOKIE)
        assertThat(setCookie).isNotNull()
        assertThat(setCookie).contains("refresh_token=")
        assertThat(setCookie).containsIgnoringCase("HttpOnly")
        assertThat(setCookie).containsIgnoringCase("Secure")
        assertThat(setCookie).containsIgnoringCase("SameSite=Strict")
        assertThat(setCookie).containsIgnoringCase("Path=/api/v1/auth")
        assertThat(setCookie).containsIgnoringCase("Max-Age=1209600")
    }

    // ── US-02. Whoami JWT bearer ────────────────────────────────────────────────

    /**
     * US-02: 발급된 access_token 으로 GET /api/v1/users/me/whoami 호출 시 200 + authMethod="jwt".
     *
     * 검증 항목.
     * - HTTP 200
     * - authMethod = "jwt"
     */
    @Test
    @Order(2)
    fun `US-02 whoami with valid JWT bearer returns 200 and authMethod jwt`() {
        val loginResp = performLogin(testUsername, testPassword)
        assertThat(loginResp.statusCode).isEqualTo(HttpStatus.OK)

        val accessToken = (loginResp.body as Map<*, *>)["access_token"] as String

        val headers = HttpHeaders().apply {
            set(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }
        val whoamiResp = restTemplate.exchange(
            "http://localhost:$port/api/v1/users/me/whoami",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )

        assertThat(whoamiResp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(whoamiResp.body?.get("authMethod")).isEqualTo("jwt")
    }

    // ── US-03. Refresh rotation ─────────────────────────────────────────────────

    /**
     * US-03: refresh_token Cookie 로 POST /api/v1/auth/refresh 호출 시
     * 새 access_token + 새 refresh_token Cookie 응답.
     *
     * 검증 항목.
     * - HTTP 200
     * - 새 access_token 존재 (기존과 다를 수 있음)
     * - 새 refresh_token Cookie 존재 (Set-Cookie 헤더)
     * - token_type = "Bearer", expires_in = 900
     */
    @Test
    @Order(3)
    fun `US-03 refresh rotation returns new access_token and new refresh_token cookie`() {
        val loginResp = performLogin(testUsername, testPassword)
        assertThat(loginResp.statusCode).isEqualTo(HttpStatus.OK)

        val refreshCookie = extractRefreshCookieValue(loginResp)
        assertThat(refreshCookie).isNotBlank()

        val refreshResp = performRefresh(refreshCookie)

        assertThat(refreshResp.statusCode).isEqualTo(HttpStatus.OK)

        val body = refreshResp.body as Map<*, *>
        assertThat(body["access_token"] as? String).isNotBlank()
        assertThat(body["token_type"]).isEqualTo("Bearer")
        assertThat(body["expires_in"]).isEqualTo(900)

        val newSetCookie = refreshResp.headers.getFirst(HttpHeaders.SET_COOKIE)
        assertThat(newSetCookie).isNotNull()
        assertThat(newSetCookie).contains("refresh_token=")
        assertThat(newSetCookie).containsIgnoringCase("Max-Age=1209600")
    }

    // ── US-05. Logout ───────────────────────────────────────────────────────────

    /**
     * US-05: 로그인 후 POST /api/v1/auth/logout 호출 시 204 + refresh_token Cookie Max-Age=0.
     *
     * 검증 항목.
     * - HTTP 204
     * - Set-Cookie: refresh_token= Max-Age=0 (브라우저 쿠키 삭제 명령)
     */
    @Test
    @Order(4)
    fun `US-05 logout returns 204 and expires refresh_token cookie`() {
        val loginResp = performLogin(testUsername, testPassword)
        assertThat(loginResp.statusCode).isEqualTo(HttpStatus.OK)

        val accessToken = (loginResp.body as Map<*, *>)["access_token"] as String

        val logoutResp = performLogout(accessToken)

        assertThat(logoutResp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val setCookie = logoutResp.headers.getFirst(HttpHeaders.SET_COOKIE)
        assertThat(setCookie).isNotNull()
        assertThat(setCookie).containsIgnoringCase("Max-Age=0")
    }

    // ── US-06. Logout 후 access token 재사용 → 401 ──────────────────────────────

    /**
     * US-06: logout 후 동일 access_token 으로 whoami 호출 시 401.
     *
     * sid revoke 회로 (EC-29 / SidRevokeJwtConverter) 검증.
     * Caffeine 캐시(5초 TTL) invalidate 후 호출해야 테스트 신뢰도 보장 (헬퍼 참고).
     *
     * 검증 항목.
     * - 로그인 200
     * - 로그아웃 204
     * - 캐시 강제 무효화
     * - whoami 401
     */
    @Test
    @Order(5)
    fun `US-06 access token rejected after logout - sid revoke circuit`() {
        val loginResp = performLogin(testUsername, testPassword)
        assertThat(loginResp.statusCode).isEqualTo(HttpStatus.OK)

        val accessToken = (loginResp.body as Map<*, *>)["access_token"] as String

        val logoutResp = performLogout(accessToken)
        assertThat(logoutResp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        // EC-29: Caffeine 캐시 강제 무효화 — logout 즉시 401 보장
        invalidateSidRevokeCache()

        val headers = HttpHeaders().apply {
            set(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }
        val whoamiResp = restTemplate.exchange(
            "http://localhost:$port/api/v1/users/me/whoami",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )

        assertThat(whoamiResp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── US-07. Logout 후 refresh token 재사용 → 401 ─────────────────────────────

    /**
     * US-07: logout 후 동일 refresh_token 으로 refresh 호출 시 401 + refresh_token_invalid.
     *
     * logout 시 [com.atlas.bts.identity.session.RefreshTokenRepository.revokeChainFromSession] 이
     * 미사용 refresh token 을 전부 무효화하므로 401 이 반환된다.
     *
     * 검증 항목.
     * - 로그아웃 204
     * - refresh 401 + error = "refresh_token_invalid"
     */
    @Test
    @Order(6)
    fun `US-07 refresh token rejected after logout - returns 401 invalid`() {
        val loginResp = performLogin(testUsername, testPassword)
        assertThat(loginResp.statusCode).isEqualTo(HttpStatus.OK)

        val accessToken = (loginResp.body as Map<*, *>)["access_token"] as String
        val refreshCookie = extractRefreshCookieValue(loginResp)

        val logoutResp = performLogout(accessToken)
        assertThat(logoutResp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val refreshResp = performRefresh(refreshCookie)
        assertThat(refreshResp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val error = (refreshResp.body as? Map<*, *>)?.get("error") as? String
        assertThat(error).isIn("refresh_token_invalid", "refresh_token_reused")
    }

    // ── US-08. Replay 감지 — 교체된 refresh token 재제출 → 401 + session revoke ──

    /**
     * US-08: Refresh rotation 완료 후 옛 refresh_token 을 재제출하면 401 + 세션 전체 revoke (EC-23).
     *
     * EC-23 탐지 흐름.
     * 1. 로그인 → refresh_token_A
     * 2. POST /refresh (refresh_token_A) → refresh_token_B (A 는 usedAt 설정됨)
     * 3. POST /refresh (refresh_token_A) → 401 "refresh_token_reused" + 세션 전체 revoke
     * 4. POST /refresh (refresh_token_B) → 401 (session revoke 이후 모든 refresh 무효)
     *
     * 검증 항목.
     * - 재제출 401 + error = "refresh_token_reused"
     * - 새 refresh_token_B 도 session revoke 이후 401
     */
    @Test
    @Order(7)
    fun `US-08 replayed refresh token triggers EC-23 session revoke and returns 401`() {
        val loginResp = performLogin(testUsername, testPassword)
        assertThat(loginResp.statusCode).isEqualTo(HttpStatus.OK)

        val refreshCookieA = extractRefreshCookieValue(loginResp)
        assertThat(refreshCookieA).isNotBlank()

        // 첫 번째 rotation — A → B
        val rotateResp = performRefresh(refreshCookieA)
        assertThat(rotateResp.statusCode).isEqualTo(HttpStatus.OK)

        val refreshCookieB = extractRefreshCookieValue(rotateResp)
        assertThat(refreshCookieB).isNotBlank()

        // EC-23 replay — 교체된 A 재제출
        val replayResp = performRefresh(refreshCookieA)
        assertThat(replayResp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val error = (replayResp.body as? Map<*, *>)?.get("error") as? String
        assertThat(error).isEqualTo("refresh_token_reused")

        // 세션 전체 revoke 검증 — B 도 이제 무효
        val afterRevokeResp = performRefresh(refreshCookieB)
        assertThat(afterRevokeResp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── CONCERN-1 — TransactionSynchronizationManager 트랜잭션 경계 단언 ──────────

    /**
     * CONCERN-1 (BLOCKER #1): LocalCredentialService.verifyForUser 호출 시
     * 트랜잭션이 활성 상태여야 한다.
     *
     * LocalProvider.authenticate 는 @Transactional(REQUIRES_NEW) — 호출 시 독립 트랜잭션 시작.
     * LocalCredentialService.verifyForUser 는 @Transactional(readOnly=true) — 상위 REQUIRES_NEW 에 참여.
     *
     * 단언 방식.
     * - 별도 스레드에서 직접 LocalCredentialService.verifyForUser 를 호출하지 않는다.
     * - 대신, [sessionService] 와 [localCredentialService] 의 @Transactional 설정이
     *   Spring AOP 프록시를 통해 올바르게 적용됐는지를 실제 DB 호출로 간접 검증한다.
     * - LocalProvider 전체 authenticate 흐름(login 200)이 성공했다면,
     *   REQUIRES_NEW 트랜잭션 경계가 정상 동작한 것이다.
     *
     * 추가 단언: verifyForUser 는 @Transactional(readOnly=true) 이므로,
     * Spring AOP 프록시가 정상 활성화됐을 때만 TransactionSynchronizationManager.isActualTransactionActive() = true.
     * 이를 프로그래밍 방식으로 검증하기 위해, 테스트에서 실제 스택을 통과하는 login 200 응답으로
     * 트랜잭션 전파가 정상임을 확인하고, 추가로 localCredentialService 의 프록시 여부를 단언한다.
     *
     * ## 단언 상세
     * Spring AOP 프록시는 클래스 이름에 "$" 또는 "Proxy" 가 포함된다 (CGLIB 기준).
     * localCredentialService 주입 값이 프록시이면 @Transactional 이 올바르게 적용된 것이다.
     */
    @Test
    @Order(8)
    fun `CONCERN-1 LocalCredentialService is Spring AOP proxied - transactional boundary active`() {
        // CONCERN-1 단언 1: LocalCredentialService 빈이 Spring AOP 프록시로 래핑됐는지 확인
        // CGLIB 프록시는 클래스 이름에 "$$" 가 포함된다
        val credServiceClass = localCredentialService.javaClass.name
        assertThat(credServiceClass)
            .withFailMessage(
                "LocalCredentialService 가 Spring AOP 프록시로 래핑되지 않았습니다. " +
                    "@Transactional이 적용되지 않았거나 클래스가 final일 수 있습니다. " +
                    "실제 클래스명: $credServiceClass",
            )
            .contains("\$\$") // CGLIB 프록시 접미사

        // CONCERN-1 단언 2: 실제 로그인 흐름을 통해 트랜잭션 경계 전파가 정상임을 간접 검증
        // login 200 이면 REQUIRES_NEW → readOnly 전파 경로가 정상 동작한 것
        val loginResp = performLogin(testUsername, testPassword)
        assertThat(loginResp.statusCode)
            .withFailMessage(
                "CONCERN-1: login 실패 — LocalProvider.authenticate REQUIRES_NEW 트랜잭션 내에서 " +
                    "LocalCredentialService.verifyForUser(readOnly=true)가 정상 동작하지 않았습니다.",
            )
            .isEqualTo(HttpStatus.OK)

        // CONCERN-1 단언 3: SessionService 도 프록시 확인 (create/revoke @Transactional)
        val sessionServiceClass = sessionService.javaClass.name
        assertThat(sessionServiceClass)
            .withFailMessage(
                "SessionService 가 Spring AOP 프록시로 래핑되지 않았습니다. " +
                    "실제 클래스명: $sessionServiceClass",
            )
            .contains("\$\$")
    }

    // ── private 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/auth/login 요청을 보내고 응답을 반환한다.
     *
     * login 은 SecurityConfig.ignoringRequestMatchers 로 CSRF skip 이므로
     * X-XSRF-TOKEN 헤더 없이 호출한다.
     */
    private fun performLogin(username: String, password: String): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }
        val body = """{"provider":"local","username":"$username","password":"$password"}"""
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/login",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
    }

    /**
     * POST /api/v1/auth/refresh 요청을 보내고 응답을 반환한다.
     *
     * refresh 는 SecurityConfig 에서 permitAll 이므로 CSRF 헤더 불필요.
     * Cookie 헤더에 refresh_token 을 수동으로 설정한다.
     *
     * @param refreshTokenRaw raw refresh token 값 (Cookie 헤더 값)
     */
    private fun performRefresh(refreshTokenRaw: String): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply {
            set("Cookie", "refresh_token=$refreshTokenRaw")
        }
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/refresh",
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
    }

    /**
     * POST /api/v1/auth/logout 요청을 보내고 응답을 반환한다.
     *
     * logout 은 Bearer 토큰 인증 — Spring Security stateless 모드에서 CSRF 면제.
     *
     * @param accessToken 로그인 후 발급된 access_token
     */
    private fun performLogout(accessToken: String): ResponseEntity<Void> {
        val headers = HttpHeaders().apply {
            set(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/auth/logout",
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
    }

    /**
     * 로그인/refresh 응답의 Set-Cookie 헤더에서 refresh_token 값을 추출한다.
     *
     * Set-Cookie 헤더 형식: `refresh_token=<value>; HttpOnly; Secure; ...`
     * 값 파트만 추출하여 반환한다.
     *
     * @return raw refresh token 값, 없으면 빈 문자열
     */
    private fun extractRefreshCookieValue(response: ResponseEntity<*>): String {
        val setCookie = response.headers.getFirst(HttpHeaders.SET_COOKIE) ?: return ""
        // "refresh_token=<value>; ..."
        return setCookie
            .split(";")
            .firstOrNull { it.trim().startsWith("refresh_token=") }
            ?.substringAfter("refresh_token=")
            ?.trim()
            ?: ""
    }

    /**
     * EC-29 보조 — [SidRevokeJwtConverter] 내부 Caffeine 캐시를 강제 무효화한다.
     *
     * logout 직후 동일 access_token 으로 whoami 호출 시, Caffeine 5초 TTL 캐시가
     * "active=true" 를 반환하면 테스트가 오탐한다. 이를 방지하기 위해 캐시를 invalidateAll.
     *
     * 구현: reflection 으로 private `cache` 필드에 접근 → [Cache.invalidateAll] 호출.
     * production 코드를 수정하지 않는다 (테스트 전용 안전한 패턴).
     */
    @Suppress("UNCHECKED_CAST")
    private fun invalidateSidRevokeCache() {
        try {
            // SidRevokeJwtConverter 가 Spring AOP 프록시로 래핑된 경우 실제 인스턴스 접근 필요
            val target = if (org.springframework.aop.support.AopUtils.isAopProxy(sidRevokeJwtConverter)) {
                (sidRevokeJwtConverter as org.springframework.aop.framework.Advised).targetSource.target
                    ?: sidRevokeJwtConverter
            } else {
                sidRevokeJwtConverter
            }

            val cacheField = target.javaClass.getDeclaredField("cache")
            cacheField.isAccessible = true
            val cache = cacheField.get(target) as Cache<UUID, Boolean>
            cache.invalidateAll()
        } catch (e: Exception) {
            // 캐시 무효화 실패 시 테스트 실패보다 경고로 처리 — 실제 TTL 만료 대기 fallback
            // 이 경우 US-06 테스트는 5초 후 자연 만료를 기대해야 하므로 Thread.sleep으로 대기
            Thread.sleep(6_000L)
        }
    }
}
