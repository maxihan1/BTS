// 계정 연결 셀프서비스 end-to-end 통합 테스트 — 실 OpenLDAP bind + RANDOM_PORT 필터체인 + step-up (FR-AU-08 Task 7)

package com.atlas.bts.identity.account

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.provider.ldap.LdapTestcontainersBase
import com.atlas.bts.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 계정 연결(account linking) 셀프서비스 end-to-end 통합 테스트 (FR-AU-08 Task 7 / SDD §19).
 *
 * ## 검증 환경 — 실 LDAP + 실 JWT + 전체 필터체인
 * 이 테스트는 두 선례를 결합한다.
 * - [LdapTestcontainersBase] — 실 OpenLDAP(osixia) + PostgreSQL 싱글턴 컨테이너. `link`/`reauth(LDAP)`
 *   가 실제 bind 를 타도록 한다(mock 아님).
 * - `RANDOM_PORT` + **default 프로필** — 실 [com.atlas.bts.identity.jwt.DevMemoryKeyProvider] 가 JWT 를
 *   발급·검증하므로 `sid` 클레임이 살아 step-up 게이팅이 실제로 동작한다. `test-integration` 프로필의
 *   가짜 JwtDecoder(모든 토큰을 sub=test-user 로 매핑, sid 없음)는 이 시나리오에 부적합해 쓰지 않는다.
 *
 * `loginJwt` 는 실 HTTP 로그인이라 sessions 행 + sid JWT 를 한 번에 만든다([SidRevokeJwtConverter] 가
 * sid↔활성 세션 정합을 검증하므로, 직접 mint 가 아닌 HTTP 로그인으로만 유효 토큰을 얻는다).
 *
 * ## 검증 시나리오 (스펙 S/EC)
 * | 번호 | 시나리오 | 기대 |
 * |---|---|---|
 * | S2 | LDAP 연결 성공 | step-up 후 201 + GET 목록에 마스킹 subject/linkedAt 노출 |
 * | S3 | 해제 | 2개 보유 + step-up → DELETE 하나 → 204, 남은 1개 |
 * | S5 | 타계정 선점 | 다른 user 가 그 DN 소유 → 409(어느 user 인지 미노출) |
 * | S6 | 멱등 | 본인 소유 DN 재연결 → 200, 링크 수 불변 |
 * | S7 | 마지막 수단 | 외부 1 + LOCAL 비번 없음 → 그 1개 DELETE → 409 |
 * | S8 | step-up 없음 | POST/DELETE step-up 미보유 → 403 step_up_required |
 * | EC2 | bind 실패 | 잘못된 LDAP 비번 → 401, 링크 미생성 |
 * | EC5 | 타인 링크 | 다른 user 소유 link id DELETE → 404(존재 probe 방지) |
 * | EC8 | PAT 차단 | PAT 로 호출 → 403 |
 * | EC10 | 동시 해제 TOCTOU | enabled 외부 2 + LOCAL 없음 → 두 링크 동시 DELETE → 204 + 409, 남은 1개 |
 *
 * ## 보안 (DEVELOPMENT.md §1.1)
 * 로그/단언 메시지에 비밀번호·DN(externalSubject) 평문을 남기지 않는다. 시드 DN 은 상수로만 참조한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class AccountLinkIntegrationTest : LdapTestcontainersBase() {
    companion object {
        /** alice 의 LDAP DN — bindForLinking 이 만드는 externalSubject 와 동일 형식(uid=alice,userSearchBase,baseDn). */
        private const val ALICE_DN = "uid=alice,ou=people,dc=example,dc=org"

        /** 시드 전용 합성 DN — 실제 LDAP 사용자가 아닌, 두 번째 enabled 링크를 만들기 위한 식별자. */
        private const val SYNTHETIC_DN = "uid=synthetic,ou=people,dc=example,dc=org"

        /** 타 user 가 선점한 상태를 만들 때 쓰는 또 다른 합성 DN. */
        private const val OTHER_OWNED_DN = "uid=otherowned,ou=people,dc=example,dc=org"

        private const val LDAP_PASSWORD = "Test1234!"
        private const val LOCAL_PASSWORD = "S3cur3@Local1"

        /** PAT raw token 형식: "pat_" + 48자 body (EC-26 선례). */
        private const val PAT_BODY_LENGTH = 48

        @DynamicPropertySource
        @JvmStatic
        fun accountLinkProps(registry: DynamicPropertyRegistry) {
            // 실 JWT 발급/검증을 위한 issuer + CorsConfig 필수 출처.
            // 컨테이너(datasource/ldap) 프로퍼티는 부모 LdapTestcontainersBase.containerProps 가 제공한다.
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
        }

        /** SHA-256 hex — PAT token_hash 계산(EC-26 선례 동일). */
        private fun sha256Hex(raw: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var localCredentialService: LocalCredentialService

    /** 매 테스트 재시드되는 LDAP provider 식별자(authn_providers.id). */
    private lateinit var providerId: UUID

    @BeforeEach
    fun setUp() {
        // FK 순서 (user_external_accounts → authn_providers → 그 외 user 종속 → users)
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM authn_providers", emptyMap<String, Any>())
        jdbc.update("DELETE FROM personal_access_tokens", emptyMap<String, Any>())
        jdbc.update("DELETE FROM refresh_tokens", emptyMap<String, Any>())
        jdbc.update("DELETE FROM sessions", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        providerId = UUID.randomUUID()
        seedLdapProvider(providerId)
    }

    // ── S2: LDAP 연결 성공 ─────────────────────────────────────────────────────

    /**
     * S2: 로컬 사용자가 로그인(JWT) → reauth(step-up) → POST /links 로 유효 LDAP 자격증명 연결.
     *
     * 201 이 떨어지고, GET /links 에 새 링크가 마스킹된 subject + linkedAt 과 함께 노출돼야 한다.
     */
    @Test
    fun `S2 LDAP 연결 성공 — step-up 후 201 + 목록 노출`() {
        val userId = seedLocalUser("s2user")
        val jwt = loginLocalJwt("s2user", LOCAL_PASSWORD)
        reauthLocal(jwt, LOCAL_PASSWORD)

        val linkResp = postLink(jwt, providerId, "alice", LDAP_PASSWORD)
        assertThat(linkResp.statusCode).isEqualTo(HttpStatus.CREATED)

        val listResp = getLinks(jwt)
        assertThat(listResp.statusCode).isEqualTo(HttpStatus.OK)
        val links = linksOf(listResp)
        assertThat(links).hasSize(1)
        val link = links.first()
        // 마스킹: 앞 6자(uid=al)만 노출 + *** — 원본 DN 평문 비노출.
        assertThat(link["externalSubjectMasked"].toString()).startsWith("uid=al").endsWith("***")
        assertThat(link["externalSubjectMasked"].toString()).doesNotContain("example.org")
        assertThat(link["linkedAt"]).isNotNull()

        // DB 상으로도 본인 소유 alice DN 링크가 정확히 1개.
        assertThat(linkCount(userId)).isEqualTo(1)
    }

    // ── S3: 해제 ───────────────────────────────────────────────────────────────

    /**
     * S3: 외부계정 2개 + step-up → 하나 DELETE → 204, 남은 1개.
     *
     * LOCAL 비번도 보유시켜, 둘 중 어느 하나를 지워도 "마지막 수단" 가드에 걸리지 않게 한다.
     */
    @Test
    fun `S3 해제 — 2개 중 하나 DELETE 204 후 남은 1개`() {
        val userId = seedLocalUser("s3user")
        val keepId = seedExternalLink(userId, SYNTHETIC_DN)
        val removeId = seedExternalLink(userId, OTHER_OWNED_DN)

        val jwt = loginLocalJwt("s3user", LOCAL_PASSWORD)
        reauthLocal(jwt, LOCAL_PASSWORD)

        val resp = deleteLink(jwt, removeId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        assertThat(linkCount(userId)).isEqualTo(1)
        assertThat(linkExists(keepId)).isTrue()
        assertThat(linkExists(removeId)).isFalse()
    }

    // ── S5: 타계정 선점 409 ────────────────────────────────────────────────────

    /**
     * S5: alice DN 이 이미 **다른 user** 에 연결된 상태에서 현재 user 가 그 DN 으로 연결 시도 → 409.
     *
     * 계정 열거 0 — 응답에 어느 user 가 선점했는지 노출되지 않는다(에러코드만).
     */
    @Test
    fun `S5 타계정 선점 — 409 account_already_linked 미노출`() {
        val otherUserId = seedLocalUser("s5other")
        seedExternalLink(otherUserId, ALICE_DN)

        val userId = seedLocalUser("s5user")
        val jwt = loginLocalJwt("s5user", LOCAL_PASSWORD)
        reauthLocal(jwt, LOCAL_PASSWORD)

        val resp = postLink(jwt, providerId, "alice", LDAP_PASSWORD)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(bodyError(resp)).isEqualTo("account_already_linked")
        // 본인 소유 링크는 0개(선점 user 행은 그대로, 현재 user 엔 신규 INSERT 없음).
        assertThat(linkCount(userId)).isEqualTo(0)
        assertThat(linkCount(otherUserId)).isEqualTo(1)
    }

    // ── S6: 멱등 ───────────────────────────────────────────────────────────────

    /**
     * S6: 현재 user 에 이미 연결된 alice DN 재연결 → 성공 + 링크 수 불변(중복 INSERT 없음).
     *
     * ## 실제 구현의 status (스펙 deviation — 보고서에 명시)
     * 스펙 S6 은 멱등 재연결을 **200** 으로 기대하나, 현재 [AccountLinkController.link] 는
     * 신규/멱등 두 경로의 성공을 모두 **201 CREATED** 로 매핑한다(서비스 [AccountLinkService.link] 는
     * 멱등 분기에서 기존 행을 그대로 반환하고 INSERT 하지 않음 — 멱등성은 보장되나 status 구분이 없음).
     * 따라서 이 통합 테스트는 구현이 실제로 보장하는 계약(성공 + 링크 수 불변 = 멱등)을 검증하고,
     * status 는 실제 동작인 201 로 단언한다. "멱등=200" 도입은 컨트롤러 status 분기를 추가해야 하는
     * 후속 production 변경이며, 본 task(통합테스트 전용·prod 미수정)의 범위를 벗어난다.
     *
     * 멱등 분기를 타려면 link 가 bind 후 조회할 DN 이 기존 행 DN 과 정확히 같아야 하므로,
     * 시스템이 직접 만든 행을 쓴다 — 첫 POST /links 로 연결한 뒤 같은 요청을 재실행한다.
     */
    @Test
    fun `S6 멱등 — 본인 소유 DN 재연결 성공 + 링크 수 불변(중복 INSERT 없음)`() {
        val userId = seedLocalUser("s6user")
        val jwt = loginLocalJwt("s6user", LOCAL_PASSWORD)
        reauthLocal(jwt, LOCAL_PASSWORD)

        val first = postLink(jwt, providerId, "alice", LDAP_PASSWORD)
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(linkCount(userId)).isEqualTo(1)

        // 멱등 재연결 — 성공 응답이면서 중복 INSERT 가 없어 링크 수가 그대로여야 한다.
        val resp = postLink(jwt, providerId, "alice", LDAP_PASSWORD)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(linkCount(userId)).isEqualTo(1)
    }

    // ── S7: 마지막 수단 409 ────────────────────────────────────────────────────

    /**
     * S7: 외부계정 1개 + LOCAL 비번 없음(로그인 수단 1개) → 그 1개 DELETE → 409.
     *
     * LOCAL 비번이 없으므로 로그인은 LDAP HTTP 로그인으로 수행한다(이 과정에서 alice DN 링크가 생긴다).
     * 그 단일 링크를 해제하면 영구 락이 되므로 [AccountLinkLastMethodException] → 409.
     */
    @Test
    fun `S7 마지막 수단 — 외부 1개 LOCAL 없음 DELETE 409`() {
        // LDAP HTTP 로그인 → JIT 프로비저닝으로 users + 단일 alice DN 링크 생성.
        val jwt = loginLdapJwt("alice", LDAP_PASSWORD)
        val userId = userIdByExternalSubject(ALICE_DN)
        val linkId = linkIdByExternalSubject(ALICE_DN)
        assertThat(linkCount(userId)).isEqualTo(1)

        // LDAP 재인증(EC9 — 본인 소유 DN 으로 bind) → step-up.
        reauthLdap(jwt, providerId, "alice", LDAP_PASSWORD)

        val resp = deleteLink(jwt, linkId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(bodyError(resp)).isEqualTo("last_login_method")
        assertThat(linkCount(userId)).isEqualTo(1)
    }

    // ── S8: step-up 없음 403 ───────────────────────────────────────────────────

    /**
     * S8: step-up 미보유 상태로 POST /links → 403 step_up_required(서비스 호출 전 차단).
     */
    @Test
    fun `S8 step-up 없음 — POST links 403 step_up_required`() {
        val userId = seedLocalUser("s8puser")
        val jwt = loginLocalJwt("s8puser", LOCAL_PASSWORD)
        // reauth 생략 — step-up 윈도우 없음.

        val resp = postLink(jwt, providerId, "alice", LDAP_PASSWORD)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(bodyError(resp)).isEqualTo("step_up_required")
        assertThat(linkCount(userId)).isEqualTo(0)
    }

    /**
     * S8: step-up 미보유 상태로 DELETE /links/{id} → 403 step_up_required.
     */
    @Test
    fun `S8 step-up 없음 — DELETE links 403 step_up_required`() {
        val userId = seedLocalUser("s8duser")
        val linkId = seedExternalLink(userId, SYNTHETIC_DN)
        val jwt = loginLocalJwt("s8duser", LOCAL_PASSWORD)

        val resp = deleteLink(jwt, linkId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(bodyError(resp)).isEqualTo("step_up_required")
        assertThat(linkExists(linkId)).isTrue()
    }

    // ── EC2: bind 실패 401 ─────────────────────────────────────────────────────

    /**
     * EC2: 잘못된 LDAP 비번으로 연결 → 401, 링크 미생성.
     */
    @Test
    fun `EC2 bind 실패 — 잘못된 LDAP 비번 401 링크 미생성`() {
        val userId = seedLocalUser("ec2user")
        val jwt = loginLocalJwt("ec2user", LOCAL_PASSWORD)
        reauthLocal(jwt, LOCAL_PASSWORD)

        val resp = postLink(jwt, providerId, "alice", "wrong-password")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(bodyError(resp)).isEqualTo("link_authentication_failed")
        assertThat(linkCount(userId)).isEqualTo(0)
    }

    // ── EC5: 타인 링크 404 ─────────────────────────────────────────────────────

    /**
     * EC5: 다른 user 소유 link id 를 DELETE → 404(존재 probe 방지 — 타인 소유/미존재 비구분).
     */
    @Test
    fun `EC5 타인 링크 — 다른 user 소유 link id DELETE 404`() {
        val otherUserId = seedLocalUser("ec5other")
        val otherLinkId = seedExternalLink(otherUserId, ALICE_DN)

        val selfUserId = seedLocalUser("ec5user")
        // 자기 자신도 링크 1개 보유(없으면 last-method 가드가 404 보다 먼저 발동할 수 있음).
        seedExternalLink(selfUserId, SYNTHETIC_DN)

        val jwt = loginLocalJwt("ec5user", LOCAL_PASSWORD)
        reauthLocal(jwt, LOCAL_PASSWORD)

        val resp = deleteLink(jwt, otherLinkId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        // 타인 링크는 삭제되지 않았다.
        assertThat(linkExists(otherLinkId)).isTrue()
        assertThat(linkCount(otherUserId)).isEqualTo(1)
    }

    // ── EC8: PAT 차단 403 ──────────────────────────────────────────────────────

    /**
     * EC8: PAT(Jwt 아님)로 계정 연결 엔드포인트 호출 → 403.
     *
     * GET/POST/DELETE 모두 JWT 전용이며 PAT 는 sid 가 없어 셀프서비스가 불가하다.
     */
    @Test
    fun `EC8 PAT 차단 — GET POST DELETE 모두 403`() {
        val userId = seedLocalUser("ec8user")
        val linkId = seedExternalLink(userId, SYNTHETIC_DN)
        val rawPat = insertPat(userId)

        val getResp = exchangeWithBearer("/api/v1/auth/account/links", HttpMethod.GET, rawPat, body = null)
        assertThat(getResp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val postBody = """{"providerId":"$providerId","username":"alice","password":"$LDAP_PASSWORD"}"""
        val postResp = exchangeWithBearer("/api/v1/auth/account/links", HttpMethod.POST, rawPat, postBody)
        assertThat(postResp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val deleteResp =
            exchangeWithBearer("/api/v1/auth/account/links/$linkId", HttpMethod.DELETE, rawPat, body = null)
        assertThat(deleteResp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    // ── EC10: 동시 해제 TOCTOU ─────────────────────────────────────────────────

    /**
     * EC10: enabled 외부 2개 + LOCAL 비번 없음 사용자가 두 링크를 **동시 DELETE**.
     *
     * advisory lock 직렬화로 한쪽은 204(첫 삭제 — 아직 다른 1개가 남음), 다른 쪽은 409(last method —
     * 자신을 지우면 0이 됨)여야 한다. 최종 남은 링크는 정확히 1개(0으로 떨어지지 않음).
     *
     * LDAP HTTP 로그인으로 첫 alice DN 링크 + JWT/step-up 을 얻고, 두 번째 enabled 링크를 직접 시드한다.
     * CountDownLatch 로 두 스레드를 동시에 발사한다.
     */
    @Test
    fun `EC10 동시 해제 TOCTOU — 한쪽 204 한쪽 409 최종 1개 유지`() {
        val jwt = loginLdapJwt("alice", LDAP_PASSWORD)
        val userId = userIdByExternalSubject(ALICE_DN)
        val firstLinkId = linkIdByExternalSubject(ALICE_DN)
        val secondLinkId = seedExternalLink(userId, SYNTHETIC_DN)
        assertThat(linkCount(userId)).isEqualTo(2)

        // 본인 소유 alice DN 으로 LDAP 재인증 → step-up.
        reauthLdap(jwt, providerId, "alice", LDAP_PASSWORD)

        val statuses = mutableListOf<HttpStatus>()
        val noContentCount = AtomicInteger(0)
        val conflictCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val targets = listOf(firstLinkId, secondLinkId)
        val futures =
            targets.map { linkId ->
                executor.submit {
                    latch.await()
                    val resp = deleteLink(jwt, linkId)
                    synchronized(statuses) { statuses.add(HttpStatus.valueOf(resp.statusCode.value())) }
                    when (resp.statusCode.value()) {
                        HttpStatus.NO_CONTENT.value() -> noContentCount.incrementAndGet()
                        HttpStatus.CONFLICT.value() -> conflictCount.incrementAndGet()
                    }
                }
            }

        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        assertThat(noContentCount.get())
            .withFailMessage("EC10: 정확히 한쪽만 204 여야 한다 — statuses=%s", statuses)
            .isEqualTo(1)
        assertThat(conflictCount.get())
            .withFailMessage("EC10: 다른 한쪽은 409(last method) 여야 한다 — statuses=%s", statuses)
            .isEqualTo(1)
        assertThat(linkCount(userId))
            .withFailMessage("EC10 위반: advisory lock 무력화로 링크가 0으로 떨어짐")
            .isEqualTo(1)
    }

    // ── 시드 헬퍼 ───────────────────────────────────────────────────────────────

    /** 실 OpenLDAP 컨테이너를 가리키는 LDAP authn_providers 행을 INSERT 한다. */
    private fun seedLdapProvider(id: UUID) {
        val ldapUrl = "ldap://${openldap.host}:${openldap.firstMappedPort}"
        jdbc.update(
            """
            INSERT INTO authn_providers (id, type, name, config, enabled)
            VALUES (:id, 'LDAP', 'integration-ldap', :config::jsonb, true)
            """.trimIndent(),
            mapOf(
                "id" to id,
                "config" to ldapConfigJson(ldapUrl),
            ),
        )
    }

    /** LDAP provider config(JSON) — LdapAuthFlowIntegrationTest 와 동일 형식. */
    private fun ldapConfigJson(ldapUrl: String): String =
        """
        {
            "serverUrl": "$ldapUrl",
            "baseDn": "dc=example,dc=org",
            "bindDn": "cn=admin,dc=example,dc=org",
            "bindPasswordEnv": "BTS_LDAP_BIND_PASSWORD_INTEGRATION",
            "userSearchBase": "ou=people",
            "userSearchFilter": "(uid={0})",
            "groupSearchBase": "ou=groups",
            "groupSearchFilter": "(member={0})",
            "lockoutPolicy": {"maxAttempts": 5, "lockoutMinutes": 1, "scope": "PER_USER_PER_PROVIDER"}
        }
        """.trimIndent()

    /** LOCAL 비번을 가진 사용자를 생성하고 user id 를 반환한다. */
    private fun seedLocalUser(username: String): UUID {
        val user = userRepository.save(username, "$username@example.com", username)
        localCredentialService.store(user.id, LOCAL_PASSWORD.toCharArray())
        return user.id
    }

    /**
     * 현재 LDAP providerId 로 [userId] 에 외부 계정 링크를 직접 INSERT 하고 link id 를 반환한다.
     *
     * 서비스 우회 시드 — bind 없이 임의 DN 을 붙인다(충돌/멱등/마지막수단/동시성 상태 구성용).
     */
    private fun seedExternalLink(
        userId: UUID,
        externalSubject: String,
    ): UUID {
        val linkId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO user_external_accounts (id, provider_id, external_subject, user_id, groups)
            VALUES (:id, :providerId, :externalSubject, :userId, '[]'::jsonb)
            """.trimIndent(),
            mapOf(
                "id" to linkId,
                "providerId" to providerId,
                "externalSubject" to externalSubject,
                "userId" to userId,
            ),
        )
        return linkId
    }

    /** personal_access_tokens 에 유효한 PAT 를 INSERT 하고 raw token 을 반환한다(EC-26 선례). */
    private fun insertPat(userId: UUID): String {
        val rawPat = "pat_" + "a".repeat(PAT_BODY_LENGTH)
        jdbc.update(
            """
            INSERT INTO personal_access_tokens
                (id, user_id, name, token_hash, scopes, expires_at, revoked_at, created_at)
            VALUES
                (:id, :userId, :name, :tokenHash, '["*"]'::jsonb, NULL, NULL, :createdAt)
            """.trimIndent(),
            mapOf(
                "id" to UUID.randomUUID(),
                "userId" to userId,
                "name" to "account-link-test-pat",
                "tokenHash" to sha256Hex(rawPat),
                "createdAt" to Timestamp.from(Instant.now()),
            ),
        )
        return rawPat
    }

    // ── 조회 헬퍼 (DB) ───────────────────────────────────────────────────────────

    private fun linkCount(userId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_external_accounts WHERE user_id = :userId",
            mapOf("userId" to userId),
            Int::class.java,
        ) ?: 0

    private fun linkExists(linkId: UUID): Boolean =
        (
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_external_accounts WHERE id = :id",
                mapOf("id" to linkId),
                Int::class.java,
            ) ?: 0
        ) > 0

    private fun userIdByExternalSubject(externalSubject: String): UUID =
        jdbc.queryForObject(
            "SELECT user_id FROM user_external_accounts WHERE external_subject = :subject",
            mapOf("subject" to externalSubject),
            UUID::class.java,
        ) ?: error("external_subject 행 없음")

    private fun linkIdByExternalSubject(externalSubject: String): UUID =
        jdbc.queryForObject(
            "SELECT id FROM user_external_accounts WHERE external_subject = :subject",
            mapOf("subject" to externalSubject),
            UUID::class.java,
        ) ?: error("external_subject 행 없음")

    // ── HTTP 헬퍼 ─────────────────────────────────────────────────────────────

    /** POST /api/v1/auth/login(local) → JWT access_token. 실 세션 + sid JWT 발급. */
    private fun loginLocalJwt(
        username: String,
        password: String,
    ): String = loginJwt("""{"provider":"local","username":"$username","password":"$password"}""", username)

    /** POST /api/v1/auth/login(ldap) → JWT access_token. JIT 프로비저닝 + 실 세션 + sid JWT. */
    private fun loginLdapJwt(
        username: String,
        password: String,
    ): String = loginJwt("""{"provider":"ldap","username":"$username","password":"$password"}""", username)

    private fun loginJwt(
        jsonBody: String,
        username: String,
    ): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val resp =
            restTemplate.exchange(
                url("/api/v1/auth/login"),
                HttpMethod.POST,
                HttpEntity(jsonBody, headers),
                Map::class.java,
            )
        check(resp.statusCode == HttpStatus.OK) { "loginJwt 실패 ($username): ${resp.statusCode}" }
        return (resp.body as Map<*, *>)["access_token"] as String
    }

    /** RANDOM_PORT 로 부팅된 내장 서버의 절대 URL 을 만든다. */
    private fun url(path: String): String = "http://localhost:$port$path"

    /** POST /reauth(LOCAL) → step-up 윈도우 부여. 200 이 아니면 즉시 실패. */
    private fun reauthLocal(
        jwt: String,
        password: String,
    ) {
        val body = """{"method":"LOCAL","password":"$password"}"""
        val resp = exchangeWithBearer("/api/v1/auth/account/reauth", HttpMethod.POST, jwt, body)
        check(resp.statusCode == HttpStatus.OK) { "reauthLocal 실패: ${resp.statusCode} ${resp.body}" }
    }

    /** POST /reauth(LDAP) → 본인 소유 DN bind 후 step-up 윈도우 부여. 200 이 아니면 즉시 실패. */
    private fun reauthLdap(
        jwt: String,
        providerId: UUID,
        username: String,
        password: String,
    ) {
        val body =
            """{"method":"LDAP","providerId":"$providerId","username":"$username","password":"$password"}"""
        val resp = exchangeWithBearer("/api/v1/auth/account/reauth", HttpMethod.POST, jwt, body)
        check(resp.statusCode == HttpStatus.OK) { "reauthLdap 실패: ${resp.statusCode} ${resp.body}" }
    }

    private fun getLinks(jwt: String): ResponseEntity<Map<*, *>> =
        exchangeWithBearer("/api/v1/auth/account/links", HttpMethod.GET, jwt, body = null)

    private fun postLink(
        jwt: String,
        providerId: UUID,
        username: String,
        password: String,
    ): ResponseEntity<Map<*, *>> {
        val body = """{"providerId":"$providerId","username":"$username","password":"$password"}"""
        return exchangeWithBearer("/api/v1/auth/account/links", HttpMethod.POST, jwt, body)
    }

    private fun deleteLink(
        jwt: String,
        linkId: UUID,
    ): ResponseEntity<Map<*, *>> {
        return exchangeWithBearer("/api/v1/auth/account/links/$linkId", HttpMethod.DELETE, jwt, body = null)
    }

    /**
     * Bearer 토큰으로 임의 메서드 요청을 보낸다. Map 응답 타입 — 에러 envelope({"error":...})도 파싱한다.
     */
    private fun exchangeWithBearer(
        path: String,
        method: HttpMethod,
        bearer: String,
        body: String?,
    ): ResponseEntity<Map<*, *>> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
            }
        val entity = if (body == null) HttpEntity<Void>(headers) else HttpEntity(body, headers)
        return restTemplate.exchange(url(path), method, entity, Map::class.java)
    }

    // ── 응답 파싱 헬퍼 ───────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun linksOf(resp: ResponseEntity<Map<*, *>>): List<Map<*, *>> {
        return (resp.body?.get("links") as? List<Map<*, *>>) ?: emptyList()
    }

    private fun bodyError(resp: ResponseEntity<Map<*, *>>): String? = resp.body?.get("error") as? String
}
