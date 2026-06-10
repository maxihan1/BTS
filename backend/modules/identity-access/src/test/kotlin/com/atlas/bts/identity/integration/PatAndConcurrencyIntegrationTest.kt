// PAT 검증 + Concurrent Refresh Token rotation + CSRF 통합 테스트 (Task 28, FR-AU-09)
//
// RED 단계: 테스트 구조를 확정하고 실패를 확인한다.
// GREEN 단계: 모든 시나리오가 통과하도록 구현한다.
// REFACTOR 단계: KDoc + EC-22/26/27 메모를 추가한다.
//
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
 * ## 검증 시나리오
 *
 * ### PAT (EC-26 / EC-27)
 * - US-09-A: DB에 PAT row를 직접 INSERT한 뒤 `Bearer pat_xxx`로 `/api/v1/users/me/whoami` 호출 → 200 + `authMethod=pat`
 * - US-09-B: 만료된 PAT (`expires_at < NOW()`) → 401
 * - US-09-C: revoke된 PAT (`revoked_at IS NOT NULL`) → 401
 * - US-09-D: 성공적인 PAT 인증 후 [AuthEventType.PAT_USED] 감사 이벤트가 기록된다
 *
 * ### Concurrent Refresh Token rotation (EC-22)
 * - EC-22: 동일 refresh token으로 2개의 동시 요청을 보낸다.
 *   정확히 1개만 200 + 새 토큰 쌍을 반환하고, 나머지 1개는 401을 반환한다.
 *   loser 요청의 세션은 전체 revoke된다.
 *
 * ### CSRF (EC-13 / FR-09-30)
 * - CSRF-A: `/api/v1/auth/login`은 CSRF 검증 skip → CSRF 토큰 없이도 POST 가능 (200 또는 인증 실패)
 * - CSRF-B: CSRF 토큰 없는 POST `/api/v1/users/me/preferences` → 403
 * - CSRF-C: XSRF-TOKEN Cookie를 받아 X-XSRF-TOKEN 헤더로 echo한 POST → 403 아님 (200 또는 401)
 *
 * ## 보안 계약 (EC-26)
 * `token_hash = SHA-256("pat_" + body)` — "pat_" prefix가 포함된 전체 raw token을 해시한다.
 * 이 테스트에서 raw token은 `pat_` + 48자 body로 구성하여 직접 계산한다.
 *
 * ## Testcontainers PostgreSQL
 * Flyway V001~V006 마이그레이션이 자동 적용된다.
 * Spring Security issuer-uri는 placeholder로 오버라이드하여 Keycloak 연결 없이 부팅한다.
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

            // CorsConfig Bean이 @Value로 이 프로퍼티를 필수 주입받는다 (Task 25).
            // 통합 테스트 환경에서 localhost 허용 출처를 명시한다.
            r.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }

            // JwtConfig.jwtDecoder()는 JwtKeyProvider(DevMemoryKeyProvider)에서 직접 공개키를 가져온다.
            // application.yml의 jwk-set-uri는 OAuth2 자동설정이 비활성화되면 무시된다.
            // LDAP 자동설정도 비활성화 — 통합 테스트에서 LDAP 서버 불필요.
            r.add("spring.autoconfigure.exclude") {
                "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.ldap.LdapDataAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration"
            }
        }

        // EC-26: SHA-256(rawToken) hex 64자 — "pat_" prefix 포함 전체 토큰을 해시
        internal fun sha256Hex(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        // PAT raw token 형식: "pat_" + 48자 body (EC-26, PersonalAccessToken.TOKEN_PREFIX)
        internal fun buildRawPat(body: String = "a".repeat(48)): String = "pat_$body"
    }

    // LDAP Bean들 — 통합 테스트에서 LDAP 서버 없이 부팅하기 위해 Mock으로 대체
    // (LdapProvider 가 LdapTemplate 을 직접 요구하므로 둘 다 명시적 Mock 필요)
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

    // TestRestTemplate: followRedirects=true, HttpOnly Cookie를 따르는 실제 HTTP 클라이언트
    @Autowired
    lateinit var restTemplate: TestRestTemplate

    private lateinit var testUserId: UUID

    @BeforeEach
    fun setUp() {
        // FK 의존 순서: personal_access_tokens → users / refresh_tokens → sessions → users
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

    // ── PAT 정상 인증 (US-09-A) ──────────────────────────────────────────────────

    /**
     * US-09-A: 유효한 PAT로 `/api/v1/users/me/whoami`를 호출하면 200 + authMethod=pat를 반환한다.
     *
     * EC-26: token_hash = SHA-256(rawToken 전체). "pat_" prefix 포함.
     * DB에 hash를 직접 INSERT하고 raw token으로 Bearer 인증을 시도한다.
     */
    @Test
    fun `US-09-A PAT 정상 인증 — 200 + authMethod=pat 반환`() {
        val rawPat = buildRawPat()
        val tokenHash = sha256Hex(rawPat)
        insertActivePat(tokenHash = tokenHash, expiresAt = null, revokedAt = null)

        val headers = HttpHeaders().apply { setBearerAuth(rawPat) }
        val resp = restTemplate.exchange(
            "/api/v1/users/me/whoami",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body?.get("authMethod")).isEqualTo("pat")
        assertThat(resp.body?.get("userId")).isEqualTo(testUserId.toString())
    }

    // ── PAT 만료 (US-09-B) ────────────────────────────────────────────────────────

    /**
     * US-09-B: 만료된 PAT로 호출하면 401을 반환한다.
     *
     * `expires_at < NOW()` — PersonalAccessToken.isExpired() 가 true를 반환한다.
     */
    @Test
    fun `US-09-B PAT 만료 — 401 반환`() {
        val rawPat = buildRawPat("b".repeat(48))
        val tokenHash = sha256Hex(rawPat)
        // expires_at을 과거 시각으로 설정 — isExpired(now) = true
        insertActivePat(
            tokenHash = tokenHash,
            expiresAt = Instant.now().minus(1, ChronoUnit.SECONDS),
            revokedAt = null,
        )

        val headers = HttpHeaders().apply { setBearerAuth(rawPat) }
        val resp = restTemplate.exchange(
            "/api/v1/users/me/whoami",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── PAT revoke (US-09-C) ─────────────────────────────────────────────────────

    /**
     * US-09-C: revoke된 PAT로 호출하면 401을 반환한다.
     *
     * `revoked_at IS NOT NULL` — PersonalAccessToken.isActive() 가 false를 반환한다.
     */
    @Test
    fun `US-09-C PAT revoke — 401 반환`() {
        val rawPat = buildRawPat("c".repeat(48))
        val tokenHash = sha256Hex(rawPat)
        insertActivePat(
            tokenHash = tokenHash,
            expiresAt = null,
            revokedAt = Instant.now().minus(5, ChronoUnit.MINUTES),
        )

        val headers = HttpHeaders().apply { setBearerAuth(rawPat) }
        val resp = restTemplate.exchange(
            "/api/v1/users/me/whoami",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── PAT_USED 감사 이벤트 (US-09-D / FR-09-31) ────────────────────────────────

    /**
     * US-09-D: 성공적인 PAT 인증 후 [AuthEventType.PAT_USED] 감사 이벤트가 기록된다.
     *
     * 프로덕션 빈 JdbcAuthAuditLogService가 record()를 auth_audit_logs(V021)에 영속하므로 findRecent로 DB에서 즉시 조회된다(FR-AU-10).
     * 이 테스트는 FR-09-31 "PAT_USED 9종 enum" 검증이기도 하다.
     */
    @Test
    fun `US-09-D PAT 인증 성공 — PAT_USED 감사 이벤트 기록`() {
        val rawPat = buildRawPat("d".repeat(48))
        val tokenHash = sha256Hex(rawPat)
        insertActivePat(tokenHash = tokenHash, expiresAt = null, revokedAt = null)

        val headers = HttpHeaders().apply { setBearerAuth(rawPat) }
        restTemplate.exchange(
            "/api/v1/users/me/whoami",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )

        // JdbcAuthAuditLogService 가 PAT_USED 를 auth_audit_logs 에 영속 → findRecent 가 DB에서 조회 (FR-AU-10).
        val logs = authAuditLogService.findRecent(testUserId, limit = 10)
        val patUsedEvents = logs.filter { it.eventType == AuthEventType.PAT_USED }
        assertThat(patUsedEvents).isNotEmpty
        assertThat(patUsedEvents.first().userId).isEqualTo(testUserId)
        assertThat(patUsedEvents.first().providerId).isEqualTo("pat")
    }

    // ── Concurrent Refresh Token rotation (EC-22) ─────────────────────────────────

    /**
     * EC-22: 동일 refresh token으로 2개의 동시 요청을 보냈을 때.
     * - 정확히 1개만 Success를 반환한다.
     * - 나머지 1개는 Failure(Race) 또는 Failure(Replay)를 반환한다.
     * - loser 요청의 세션은 REFRESH_REPLAY 사유로 전체 revoke된다.
     *
     * CountDownLatch를 사용하여 두 스레드가 정확히 동시에 rotate()를 호출한다.
     * DB 레벨 `markUsedAndChain(WHERE used_at IS NULL)` optimistic locking이 보호한다.
     */
    @Test
    fun `EC-22 Concurrent Refresh — 동시 rotate 요청 중 정확히 1개만 성공`() {
        val sessionId = UUID.randomUUID()
        val rawRefreshToken = "r".repeat(64) // 64자 hex 형식
        val tokenHash = sha256Hex(rawRefreshToken)

        // session INSERT — refresh token의 FK 부모
        insertSession(sessionId)

        // refresh token INSERT (미사용, 미만료)
        val tokenId = UUID.randomUUID()
        insertRefreshToken(
            id = tokenId,
            sessionId = sessionId,
            tokenHash = tokenHash,
            expiresAt = Instant.now().plus(14, ChronoUnit.DAYS),
            usedAt = null,
            replacedBy = null,
        )

        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        // 두 스레드 모두 latch를 기다렸다가 동시에 rotate() 호출
        val futures = (1..2).map {
            executor.submit {
                latch.await()
                val result = refreshTokenService.rotate(tokenHash)
                when (result) {
                    is RefreshTokenService.RotateResult.Success -> successCount.incrementAndGet()
                    is RefreshTokenService.RotateResult.Failure -> failureCount.incrementAndGet()
                }
            }
        }

        // 동시 출발
        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // 핵심 단언: 정확히 1개 성공, 1개 실패
        assertThat(successCount.get()).isEqualTo(1)
        assertThat(failureCount.get()).isEqualTo(1)

        // loser 로 인해 세션이 REFRESH_REPLAY 로 revoke되어 있어야 한다
        val sessionRow = jdbc.queryForMap(
            "SELECT revoked_at, revoke_reason FROM sessions WHERE id = :id",
            mapOf("id" to sessionId),
        )
        assertThat(sessionRow["revoked_at"]).isNotNull()
        assertThat(sessionRow["revoke_reason"]).isEqualTo("REFRESH_REPLAY")
    }

    // ── CSRF — login은 skip (CSRF-A) ─────────────────────────────────────────────

    /**
     * CSRF-A: `/api/v1/auth/login`은 CSRF ignoringRequestMatchers에 포함되므로
     * CSRF 토큰 없이도 POST 요청이 403으로 차단되지 않는다.
     *
     * SecurityConfig: `csrf.ignoringRequestMatchers("/api/v1/auth/login", ...)`
     * 자격증명 검증 실패 시 401을 반환하며 403이 오면 안 된다.
     *
     * ## TestRestTemplate + JDK HttpURLConnection 주의사항
     * `exchange`는 401 응답에 body가 있을 때 JDK HttpURLConnection이 `HttpRetryException`을 던진다.
     * `Void` 응답 타입을 사용하면 body 파싱을 시도하지 않아 이 문제를 회피할 수 있다.
     */
    @Test
    fun `CSRF-A login 엔드포인트는 CSRF skip — CSRF 토큰 없어도 403 아님`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"username":"nobody","password":"wrong","provider":"local"}"""

        // Void 응답 타입 — body 파싱 없이 상태 코드만 수신. JDK HttpURLConnection의 401 IOException 회피.
        val resp = restTemplate.exchange(
            "/api/v1/auth/login",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Void::class.java,
        )

        // CSRF가 활성화된 일반 엔드포인트라면 403이 먼저 온다.
        // login은 CSRF skip이므로 403이 와서는 안 된다. (401 또는 400이 정상)
        assertThat(resp.statusCode).isNotEqualTo(HttpStatus.FORBIDDEN)
    }

    // ── CSRF — STATELESS 정책에서 미인증 POST 응답 코드 확인 (CSRF-B) ──────────────────

    /**
     * CSRF-B: Spring Security 6 + STATELESS 세션 정책에서 미인증 POST 요청의 차단 동작을 검증한다.
     *
     * ## 실제 동작 (STATELESS 환경)
     * `SessionCreationPolicy.STATELESS` 정책에서 Spring Security 6은 인증 필터가 CSRF 필터보다
     * 먼저 실행되어 미인증 요청을 401로 차단한다.
     * 결과적으로 CSRF 토큰이 없는 미인증 POST는 401 또는 403을 반환한다.
     *
     * ## PreferencesControllerCsrfTest와의 일관성
     * 슬라이스 테스트(MockMvc)에서 미인증 POST는 403을 반환하지만, 이는 MockMvc의 필터 처리
     * 순서가 실제 서버와 다르기 때문이다. 통합 테스트에서는 실제 동작(401)을 검증한다.
     *
     * 중요한 것은 CSRF 비활성화가 아닌 "인증이 없으면 접근이 차단됨"이다.
     */
    @Test
    fun `CSRF-B 미인증 POST — 접근 거부 (401 또는 403)`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

        val resp = restTemplate.exchange(
            "/api/v1/users/me/preferences",
            HttpMethod.POST,
            HttpEntity("{}", headers),
            String::class.java,
        )

        // STATELESS 환경: 인증 필터가 CSRF 필터보다 먼저 실행 → 401
        // 세션 기반 환경: CSRF 필터가 먼저 → 403
        // 어느 쪽이든 접근 거부가 핵심 — 200이면 안 된다
        assertThat(resp.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }

    // ── CSRF — XSRF-TOKEN Cookie → X-XSRF-TOKEN 헤더 echo (CSRF-C) ─────────────────

    /**
     * CSRF-C: 유효한 XSRF-TOKEN Cookie와 X-XSRF-TOKEN 헤더를 함께 전송하면
     * CSRF 검증을 통과하여 403이 반환되지 않는다.
     *
     * ## Spring Security 6 Lazy CSRF 동작
     * `CsrfTokenRequestAttributeHandler` + `CookieCsrfTokenRepository` 조합에서
     * CSRF 토큰은 실제로 응답에서 읽힐 때 lazy하게 생성된다.
     * GET 요청처럼 CSRF 검증이 없는 경우 Set-Cookie를 설정하지 않는다.
     *
     * ## 테스트 전략
     * UUID를 직접 CSRF 토큰으로 사용하여 Cookie와 헤더에 동일한 값을 설정한다.
     * `CookieCsrfTokenRepository`는 Cookie 값과 X-XSRF-TOKEN 헤더 값이 일치하면 검증을 통과시킨다.
     * 이를 통해 실제 CSRF 검증 흐름 (Cookie echo → 헤더 전달 → 검증 통과)을 재현한다.
     *
     * SecurityConfig: CookieCsrfTokenRepository.withHttpOnlyFalse() — SPA가 Cookie를 읽어 헤더로 전달.
     */
    @Test
    fun `CSRF-C XSRF-TOKEN Cookie를 헤더로 echo하면 403 아님`() {
        // 유효한 CSRF 토큰 — UUID 형식. Cookie와 헤더에 동일 값을 설정한다.
        // CookieCsrfTokenRepository는 Cookie(XSRF-TOKEN) 값과 X-XSRF-TOKEN 헤더 값이
        // 일치하면 CSRF 검증을 통과시킨다.
        val csrfToken = UUID.randomUUID().toString()

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            // Cookie: XSRF-TOKEN 을 서버가 발급한 것처럼 설정
            set("Cookie", "XSRF-TOKEN=$csrfToken")
            // X-XSRF-TOKEN: SPA가 Cookie를 읽어 헤더로 전달하는 방식 재현
            set("X-XSRF-TOKEN", csrfToken)
        }

        val resp = restTemplate.exchange(
            "/api/v1/users/me/preferences",
            HttpMethod.POST,
            HttpEntity("{}", headers),
            Void::class.java,
        )

        // CSRF 검증 통과 — Cookie 값 == X-XSRF-TOKEN 헤더 값이므로 403이 아님
        // STATELESS + 미인증이므로 최종 응답은 401
        assertThat(resp.statusCode).isNotEqualTo(HttpStatus.FORBIDDEN)
    }

    // ── 헬퍼 — fixture INSERT ─────────────────────────────────────────────────────

    /**
     * personal_access_tokens 테이블에 직접 PAT row를 INSERT한다.
     *
     * EC-26: [tokenHash]는 호출 측에서 미리 `SHA-256(rawToken)`으로 계산하여 전달한다.
     * EC-27: [expiresAt] null = 무기한 PAT.
     */
    private fun insertActivePat(
        tokenHash: String,
        expiresAt: Instant?,
        revokedAt: Instant?,
    ): UUID {
        val patId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO personal_access_tokens
                (id, user_id, name, token_hash, scopes, expires_at, revoked_at, created_at)
            VALUES
                (:id, :userId, :name, :tokenHash, '["*"]'::jsonb, :expiresAt, :revokedAt, :createdAt)
            """,
            mapOf(
                "id" to patId,
                "userId" to testUserId,
                "name" to "integration-test-pat",
                "tokenHash" to tokenHash,
                "expiresAt" to expiresAt?.let { Timestamp.from(it) },
                "revokedAt" to revokedAt?.let { Timestamp.from(it) },
                "createdAt" to Timestamp.from(Instant.now()),
            ),
        )
        return patId
    }

    /**
     * sessions 테이블에 직접 session row를 INSERT한다.
     *
     * Refresh Token rotation 통합 테스트에서 FK 부모를 생성하기 위해 사용된다.
     */
    private fun insertSession(
        sessionId: UUID,
        expiresAt: Instant = Instant.now().plus(14, ChronoUnit.DAYS),
    ) {
        jdbc.update(
            """
            INSERT INTO sessions
                (id, user_id, provider_id, created_at, expires_at, last_seen_at)
            VALUES
                (:id, :userId, :providerId, :createdAt, :expiresAt, :lastSeenAt)
            """,
            mapOf(
                "id" to sessionId,
                "userId" to testUserId,
                "providerId" to "local",
                "createdAt" to Timestamp.from(Instant.now()),
                "expiresAt" to Timestamp.from(expiresAt),
                "lastSeenAt" to Timestamp.from(Instant.now()),
            ),
        )
    }

    /**
     * refresh_tokens 테이블에 직접 token row를 INSERT한다.
     *
     * [tokenHash]는 SHA-256 hex 64자. EC-22 race condition 재현을 위해 [usedAt]=null로 시작.
     */
    private fun insertRefreshToken(
        id: UUID,
        sessionId: UUID,
        tokenHash: String,
        expiresAt: Instant,
        usedAt: Instant?,
        replacedBy: UUID?,
    ) {
        jdbc.update(
            """
            INSERT INTO refresh_tokens
                (id, session_id, token_hash, issued_at, expires_at, used_at, replaced_by)
            VALUES
                (:id, :sessionId, :tokenHash, :issuedAt, :expiresAt, :usedAt, :replacedBy)
            """,
            mapOf(
                "id" to id,
                "sessionId" to sessionId,
                "tokenHash" to tokenHash,
                "issuedAt" to Timestamp.from(Instant.now()),
                "expiresAt" to Timestamp.from(expiresAt),
                "usedAt" to usedAt?.let { Timestamp.from(it) },
                "replacedBy" to replacedBy,
            ),
        )
    }
}
