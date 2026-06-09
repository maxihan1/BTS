// SSO(SAML/OIDC) 계정 연결/재인증 end-to-end 통합 테스트 — 실 Postgres + 서비스/processor 스택 (FR-AU-08b Task 10)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.account.AccountLinkConflictException
import com.atlas.bts.identity.account.AccountLinkService
import com.atlas.bts.identity.account.ReauthChallengeFailedException
import com.atlas.bts.identity.account.ReauthService
import com.atlas.bts.identity.account.SsoLinkingCallbackProcessor
import com.atlas.bts.identity.account.SsoLinkingIntent
import com.atlas.bts.identity.account.SsoLinkingIntentStore
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.ProviderType
import com.atlas.bts.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * SSO(SAML/OIDC) 계정 연결/재인증 end-to-end 통합 테스트 (FR-AU-08b Task 10 / SDD §19).
 *
 * ## 검증 환경 — 실 Postgres + 실 JWT + 서비스/processor 스택
 * LDAP 연결([com.atlas.bts.identity.account.AccountLinkIntegrationTest])과 달리 SSO 는 IdP 로
 * 리다이렉트 왕복하므로 동기 bind 가 없다. SAML ACS cross-site POST 브라우저 왕복은 JVM
 * 통합테스트로 재현 불가하다(기존 [SamlAuthFlowIntegrationTest] 도 ACS POST 자체는 제외).
 * **따라서 본 테스트는 브라우저 왕복을 흉내내지 않고**, 실 PostgreSQL + Flyway 스택 위에서
 * 콜백 처리기([SsoLinkingCallbackProcessor]) · 연결 서비스([AccountLinkService]) ·
 * 재인증 서비스([ReauthService])를 직접 구동해 DB 상태/리다이렉트/예외를 검증한다.
 *
 * - `RANDOM_PORT` + **default 프로필** — 실 [com.atlas.bts.identity.jwt.DevMemoryKeyProvider] 가 JWT 를
 *   발급·검증하므로 `sid` 클레임이 살아 step-up 게이팅이 실제로 동작한다(SidRevokeJwtConverter 가
 *   sid↔활성 세션 정합을 검증). SSO-only 해제 흐름(S3)은 이 실 JWT + DELETE HTTP 경로를 탄다.
 * - OAuth2ClientAutoConfiguration 은 제외(LDAP/SSO 통합테스트 선례 동일 — 외부 IdP 의존 제거).
 *
 * ## C3 분해 (브라우저 왕복 위임)
 * session-fixation 속성 이관(EC10)·JSESSIONID SameSite(EC17)는 Task 8 단위/설정 검증에서 끝났다.
 * 진짜 브라우저 start→IdP→ACS POST 왕복은 E2E(D7) 위임이다. 본 테스트는 "통합 왕복 실증" 단일
 * 항목을 두지 않고, 아래 시나리오 분해로 보안 핵심(연결 모드 분기·충돌·마지막수단·EC9)을 실증한다.
 *
 * ## 검증 시나리오 (스펙 S/EC)
 * | 번호 | 시나리오 | 기대 |
 * |---|---|---|
 * | S1 | LINK 신규 attach | processor LINK → 행 생성 + `?link=success` + 세션/refresh row 0(미발급) |
 * | S4/EC3 | 타계정 선점 거부 | 다른 user 매핑 → `?link=conflict` + attach 0 |
 * | EC2 | LINK 멱등 | 현재 user 재연결 → `?link=already_linked` + 중복행 0 |
 * | S2/EC9 | REAUTH 성공 grant | 본인 링크 일치 → step-up 부여 |
 * | EC8 | REAUTH 타신원/미연결 | grant 안 함(예외) |
 * | C3 | registrationId 불일치 | processor REAUTH/LINK 불일치 → `?reauth=failed`/`?link=error`, grant/attach 0 |
 * | S3/C4 | SSO-only 해제 | step-up→DELETE 1개 204(남은 1)→마지막 1개 409 |
 * | EC16/C4 | 마지막수단 카운트 | 비활성 SSO 링크는 카운트 제외(영구 락 방지) |
 * | EC1 | 일반 SSO 로그인 회귀 0 | intent 없으면 process=false(JIT 위임) |
 * | EC18 | 동시 콜백 직렬화 | 같은 subject 두 user 동시 link → 한쪽 Created·다른쪽 Conflict |
 *
 * ## 보안 (DEVELOPMENT.md §1.1)
 * 로그/단언 메시지에 external_subject(sub/NameID) 평문을 남기지 않는다. 시드 subject 는 상수로만 참조한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class SsoAccountLinkingIntegrationTest {
    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway 마이그레이션 자동 적용. */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** SAML 외부 신원(NameID) — 합성 상수(실제 PII 아님). */
        private const val SAML_SUBJECT_A = "urn:saml:subject:alpha"
        private const val SAML_SUBJECT_B = "urn:saml:subject:bravo"
        private const val SAML_SUBJECT_OTHER = "urn:saml:subject:other"

        private const val SAML_REGISTRATION = "corp-idp"
        private const val OTHER_REGISTRATION = "other-idp"

        /** saml_idp_configs.idp_x509_cert 더미 PEM — SamlIdpConfigRepositoryTest 와 동일 형식. */
        private const val TEST_CERT_PEM: String =
            "-----BEGIN CERTIFICATE-----\nMIIBdummycertforintegrationtest==\n-----END CERTIFICATE-----"

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
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
    private lateinit var accountLinkService: AccountLinkService

    @Autowired
    private lateinit var reauthService: ReauthService

    @Autowired
    private lateinit var callbackProcessor: SsoLinkingCallbackProcessor

    @Autowired
    private lateinit var intentStore: SsoLinkingIntentStore

    @Autowired
    private lateinit var sessionService: SessionService

    @Autowired
    private lateinit var jwtIssuer: JwtIssuer

    /** 매 테스트 재시드되는 SAML provider 식별자(authn_providers.id). */
    private lateinit var samlProviderId: UUID

    @BeforeEach
    fun setUp() {
        // FK 순서 (자식 → 부모).
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM saml_idp_configs", emptyMap<String, Any>())
        jdbc.update("DELETE FROM authn_providers", emptyMap<String, Any>())
        jdbc.update("DELETE FROM personal_access_tokens", emptyMap<String, Any>())
        jdbc.update("DELETE FROM refresh_tokens", emptyMap<String, Any>())
        jdbc.update("DELETE FROM sessions", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        samlProviderId = UUID.randomUUID()
        seedSamlProvider(samlProviderId, SAML_REGISTRATION, enabled = true)
    }

    // ── S1: LINK 신규 attach + 새 세션/JWT 미발급 ────────────────────────────────

    /**
     * S1: LINK intent 가 있는 콜백 → 현재 user 에 외부 신원 attach + `?link=success`.
     *
     * **새 세션/JWT 미발급(EC12)**: processor 가 LINK 모드를 처리하면 일반 로그인 발급 경로
     * (SessionService.create/JwtIssuer/RefreshToken)에 진입하지 않는다. 통합 차원의 강한 증거로
     * 처리 전후 sessions/refresh_tokens row count 가 불변임을 단언한다(핸들러 단위 verify exactly=0 은
     * Task 7 에서 완료).
     */
    @Test
    fun `S1 LINK 신규 attach — 행 생성 + link=success + 세션 refresh 미발급`() {
        val userId = seedUser("s1user")
        val sessionsBefore = rowCount("sessions")
        val refreshBefore = rowCount("refresh_tokens")

        val session = sessionWithIntent(linkIntent(userId))
        val response = MockHttpServletResponse()

        val handled =
            callbackProcessor.process(
                session = session,
                providerType = ProviderType.SAML,
                registrationId = SAML_REGISTRATION,
                providerId = samlProviderId,
                externalSubject = SAML_SUBJECT_A,
                groups = emptyList(),
                response = response,
            )

        assertThat(handled).isTrue()
        assertThat(response.redirectedUrl).isEqualTo("/settings/account-links?link=success")
        assertThat(linkCount(userId)).isEqualTo(1)
        // 정확히 그 신원이 현재 user 에 attach 됐는지(잘못된 subject 가 붙지 않았는지) 확인.
        assertThat(linkOwnerOf(SAML_SUBJECT_A)).isEqualTo(userId)
        // EC12 — 발급 경로 미진입: 신규 세션/refresh 행이 생기지 않는다.
        assertThat(rowCount("sessions")).isEqualTo(sessionsBefore)
        assertThat(rowCount("refresh_tokens")).isEqualTo(refreshBefore)
    }

    // ── S4/EC3: 타계정 선점 거부 ────────────────────────────────────────────────

    /**
     * S4/EC3: 연결 대상 신원이 이미 다른 user 에 매핑 → `?link=conflict`, attach 0.
     *
     * 선점 user 행은 그대로 1개, 현재 user 에는 신규 INSERT 가 없어야 한다(계정 열거 0 — 쿼리에
     * 어느 user 인지 노출 안 함).
     */
    @Test
    fun `S4 타계정 선점 — link=conflict + attach 0`() {
        val otherUserId = seedUser("s4other")
        seedLink(otherUserId, samlProviderId, SAML_SUBJECT_A)

        val userId = seedUser("s4user")
        val session = sessionWithIntent(linkIntent(userId))
        val response = MockHttpServletResponse()

        callbackProcessor.process(
            session = session,
            providerType = ProviderType.SAML,
            registrationId = SAML_REGISTRATION,
            providerId = samlProviderId,
            externalSubject = SAML_SUBJECT_A,
            groups = emptyList(),
            response = response,
        )

        assertThat(response.redirectedUrl).isEqualTo("/settings/account-links?link=conflict")
        assertThat(linkCount(userId)).isEqualTo(0)
        assertThat(linkCount(otherUserId)).isEqualTo(1)
    }

    // ── EC2: LINK 멱등 ──────────────────────────────────────────────────────────

    /**
     * EC2: 이미 현재 user 에 연결된 신원 재연결 → `?link=already_linked`, 중복행 없음.
     */
    @Test
    fun `EC2 LINK 멱등 — already_linked + 중복행 없음`() {
        val userId = seedUser("ec2user")
        seedLink(userId, samlProviderId, SAML_SUBJECT_A)

        val session = sessionWithIntent(linkIntent(userId))
        val response = MockHttpServletResponse()

        callbackProcessor.process(
            session = session,
            providerType = ProviderType.SAML,
            registrationId = SAML_REGISTRATION,
            providerId = samlProviderId,
            externalSubject = SAML_SUBJECT_A,
            groups = emptyList(),
            response = response,
        )

        assertThat(response.redirectedUrl).isEqualTo("/settings/account-links?link=already_linked")
        assertThat(linkCount(userId)).isEqualTo(1)
    }

    // ── S2/EC9: REAUTH 성공 grant ───────────────────────────────────────────────

    /**
     * S2/EC9: 돌아온 신원이 현재 user 에 이미 연결 → step-up grant.
     *
     * processor REAUTH 경로를 실 DB 로 구동해 `?reauth=success` 리다이렉트 + sid step-up 활성화를 확인한다.
     */
    @Test
    fun `S2 REAUTH 성공 — 본인 링크 일치 시 step-up grant`() {
        val userId = seedUser("s2user")
        val sid = seedSessionAndSid(userId)
        seedLink(userId, samlProviderId, SAML_SUBJECT_A)

        val session = sessionWithIntent(reauthIntent(userId, sid))
        val response = MockHttpServletResponse()

        callbackProcessor.process(
            session = session,
            providerType = ProviderType.SAML,
            registrationId = SAML_REGISTRATION,
            providerId = samlProviderId,
            externalSubject = SAML_SUBJECT_A,
            groups = emptyList(),
            response = response,
        )

        assertThat(response.redirectedUrl).isEqualTo("/settings/account-links?reauth=success")
        // grant 됐는지 — 같은 sid 로 step-up 게이트가 요구되는 DELETE 가 통과되는지로 실증.
        seedLink(userId, samlProviderId, SAML_SUBJECT_B)
        val linkId = linkIdBySubject(SAML_SUBJECT_B)
        val jwt = jwtIssuer.issue(userId, sid, "saml", emptyList())
        val delResp = deleteLink(jwt, linkId)
        assertThat(delResp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    /**
     * EC8: 돌아온 신원이 현재 user 와 불일치(미연결 신원) → grant 안 함(`?reauth=failed`).
     *
     * 직접 [ReauthService.reauthenticateSso] 도 호출해 미연결 신원이 [ReauthChallengeFailedException]
     * 으로 거부됨을 확인한다(타 신원으로 우회 grant 차단).
     */
    @Test
    fun `EC8 REAUTH 타신원 미연결 — grant 안 함`() {
        val userId = seedUser("ec8user")
        val sid = seedSessionAndSid(userId)
        seedLink(userId, samlProviderId, SAML_SUBJECT_A)

        // 미연결 신원 OTHER 로 재인증 시도 → 예외(grant 미호출).
        assertThatThrownBy {
            reauthService.reauthenticateSso(userId, sid, samlProviderId, SAML_SUBJECT_OTHER)
        }.isInstanceOf(ReauthChallengeFailedException::class.java)

        // processor 도 동형으로 `?reauth=failed` 리다이렉트.
        val session = sessionWithIntent(reauthIntent(userId, sid))
        val response = MockHttpServletResponse()
        callbackProcessor.process(
            session = session,
            providerType = ProviderType.SAML,
            registrationId = SAML_REGISTRATION,
            providerId = samlProviderId,
            externalSubject = SAML_SUBJECT_OTHER,
            groups = emptyList(),
            response = response,
        )
        assertThat(response.redirectedUrl).isEqualTo("/settings/account-links?reauth=failed")
    }

    // ── C3: registrationId/providerType 불일치 ─────────────────────────────────

    /**
     * C3: intent.registrationId 와 콜백 registrationId 불일치 → REAUTH `?reauth=failed`(grant 0).
     *
     * intent 는 corp-idp 로 시작했는데 콜백이 other-idp 로 돌아온 경우(방어) — 일치하더라도
     * 신원 owner 검증이 또 막지만, registrationId 불일치는 owner 조회 이전에 거른다.
     */
    @Test
    fun `C3 REAUTH registrationId 불일치 — reauth=failed grant 0`() {
        val userId = seedUser("c3user")
        val sid = seedSessionAndSid(userId)
        seedLink(userId, samlProviderId, SAML_SUBJECT_A)

        val session = sessionWithIntent(reauthIntent(userId, sid))
        val response = MockHttpServletResponse()

        callbackProcessor.process(
            session = session,
            providerType = ProviderType.SAML,
            registrationId = OTHER_REGISTRATION,
            providerId = samlProviderId,
            externalSubject = SAML_SUBJECT_A,
            groups = emptyList(),
            response = response,
        )

        assertThat(response.redirectedUrl).isEqualTo("/settings/account-links?reauth=failed")
    }

    /**
     * C3(동형): LINK 모드에서도 registrationId 불일치 → `?link=error`, attach 0.
     */
    @Test
    fun `C3 LINK registrationId 불일치 — link=error attach 0`() {
        val userId = seedUser("c3luser")
        val session = sessionWithIntent(linkIntent(userId))
        val response = MockHttpServletResponse()

        callbackProcessor.process(
            session = session,
            providerType = ProviderType.SAML,
            registrationId = OTHER_REGISTRATION,
            providerId = samlProviderId,
            externalSubject = SAML_SUBJECT_A,
            groups = emptyList(),
            response = response,
        )

        assertThat(response.redirectedUrl).isEqualTo("/settings/account-links?link=error")
        assertThat(linkCount(userId)).isEqualTo(0)
    }

    // ── S3/C4: SSO-only 사용자 해제 ─────────────────────────────────────────────

    /**
     * S3/C4: SSO 전용 사용자(LOCAL 비번 없음) — SSO 재인증 step-up → DELETE 1개 204(남은 1)
     * → 마지막 1개 409(self-lockout 방지).
     *
     * SSO 전용 사용자는 HTTP 로그인 경로가 없으므로 실 session + 실 JWT 를 직접 구성하고(sid 정합),
     * [ReauthService.reauthenticateSso] 로 step-up 을 부여한 뒤 DELETE HTTP 경로(step-up 게이트)를 탄다.
     */
    @Test
    fun `S3 SSO-only 해제 — step-up 후 2개 중 1 DELETE 204 후 마지막 409`() {
        val userId = seedUser("s3user")
        val sid = seedSessionAndSid(userId)
        val firstId = seedLink(userId, samlProviderId, SAML_SUBJECT_A)
        val secondId = seedLink(userId, samlProviderId, SAML_SUBJECT_B)
        assertThat(linkCount(userId)).isEqualTo(2)

        // SSO 재인증으로 step-up(EC9 — 본인 소유 신원 A 로).
        reauthService.reauthenticateSso(userId, sid, samlProviderId, SAML_SUBJECT_A)
        val jwt = jwtIssuer.issue(userId, sid, "saml", emptyList())

        val firstDelete = deleteLink(jwt, secondId)
        assertThat(firstDelete.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(linkCount(userId)).isEqualTo(1)

        // 마지막 1개 해제 거부 — enabled SSO 링크가 유일한 로그인 수단이므로 self-lockout.
        val lastDelete = deleteLink(jwt, firstId)
        assertThat(lastDelete.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(bodyError(lastDelete)).isEqualTo("last_login_method")
        assertThat(linkCount(userId)).isEqualTo(1)
    }

    /**
     * EC16/C4: 마지막수단 카운트는 enabled SSO 링크만 — 비활성 provider 링크는 제외(영구 락 방지).
     *
     * enabled SAML 링크 1개 + **비활성** provider 링크 1개를 보유한 SSO 전용 사용자가 enabled
     * 링크를 해제하면, 남는 것은 비활성 링크뿐이라 실제 로그인 불가 → 그 enabled 링크 해제는 거부돼야 한다(409).
     */
    @Test
    fun `EC16 마지막수단 카운트 — 비활성 SSO 링크 제외(영구 락 방지)`() {
        val userId = seedUser("ec16user")
        val sid = seedSessionAndSid(userId)

        val disabledProviderId = UUID.randomUUID()
        seedSamlProvider(disabledProviderId, "disabled-idp", enabled = false)

        val enabledLinkId = seedLink(userId, samlProviderId, SAML_SUBJECT_A)
        seedLink(userId, disabledProviderId, SAML_SUBJECT_B)
        assertThat(linkCount(userId)).isEqualTo(2)

        reauthService.reauthenticateSso(userId, sid, samlProviderId, SAML_SUBJECT_A)
        val jwt = jwtIssuer.issue(userId, sid, "saml", emptyList())

        // enabled 링크가 유일한 실효 로그인 수단(비활성 링크는 카운트 제외) → 해제 거부.
        val resp = deleteLink(jwt, enabledLinkId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(bodyError(resp)).isEqualTo("last_login_method")
        assertThat(linkCount(userId)).isEqualTo(2)
    }

    // ── EC1: 일반 SSO 로그인 회귀 0 ─────────────────────────────────────────────

    /**
     * EC1: intent 가 없으면 processor.process 가 false 를 반환해 일반 JIT 로그인에 위임한다(무변경).
     *
     * intent 없는 콜백에서 false 가 떨어져야 핸들러가 기존 발급 경로를 계속한다. 리다이렉트도 쓰지 않는다.
     */
    @Test
    fun `EC1 일반 SSO 로그인 회귀 — intent 없으면 process=false 위임`() {
        val userId = seedUser("ec1user")
        val emptySession = MockHttpSession()
        val response = MockHttpServletResponse()

        val handled =
            callbackProcessor.process(
                session = emptySession,
                providerType = ProviderType.SAML,
                registrationId = SAML_REGISTRATION,
                providerId = samlProviderId,
                externalSubject = SAML_SUBJECT_A,
                groups = emptyList(),
                response = response,
            )

        assertThat(handled).isFalse()
        assertThat(response.redirectedUrl).isNull()
        // 위임 경로라 attach 도 일어나지 않는다(processor 가 손대지 않음).
        assertThat(linkCount(userId)).isEqualTo(0)
    }

    // ── EC18: 동시 콜백 직렬화 ──────────────────────────────────────────────────

    /**
     * EC18: 같은 (provider, subject) 를 두 user 가 **동시** 연결 시도 → advisory lock 직렬화로
     * 한쪽만 Created·다른쪽 Conflict. 거짓 success 없이 정확히 1개만 attach.
     *
     * 실 DB 동시 tx 로 [AccountLinkService.linkExternalSubject] 를 두 스레드에서 같은 subject 로
     * 발사한다. [com.atlas.bts.identity.provider.ldap.ExternalAccountRepository.acquireSubjectLock]
     * advisory 가 check-then-insert 를 직렬화하므로, 늦은 쪽은 선행 INSERT 를 보고 conflict 로 거부한다.
     */
    @Test
    fun `EC18 동시 콜백 — 같은 subject 두 user 동시 link → 한쪽 Created 다른쪽 Conflict`() {
        val userA = seedUser("ec18a")
        val userB = seedUser("ec18b")

        val createdCount = AtomicInteger(0)
        val conflictCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val futures =
            listOf(userA, userB).map { uid ->
                executor.submit {
                    latch.await()
                    try {
                        accountLinkService.linkExternalSubject(uid, samlProviderId, SAML_SUBJECT_A, emptyList())
                        createdCount.incrementAndGet()
                    } catch (_: AccountLinkConflictException) {
                        conflictCount.incrementAndGet()
                    }
                }
            }

        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        assertThat(createdCount.get())
            .withFailMessage("EC18: 정확히 한쪽만 Created 여야 한다")
            .isEqualTo(1)
        assertThat(conflictCount.get())
            .withFailMessage("EC18: 다른 한쪽은 Conflict 여야 한다(advisory lock 직렬화)")
            .isEqualTo(1)
        // subject A 매핑은 정확히 1개(거짓 success 로 두 user 가 모두 붙는 일 없음).
        assertThat(subjectLinkCount(SAML_SUBJECT_A)).isEqualTo(1)
    }

    // ── 시드 헬퍼 ───────────────────────────────────────────────────────────────

    /** SSO(SAML) authn_providers + saml_idp_configs 한 쌍을 INSERT 한다. */
    private fun seedSamlProvider(
        providerId: UUID,
        registrationId: String,
        enabled: Boolean,
    ) {
        jdbc.update(
            """
            INSERT INTO authn_providers (id, type, name, config, enabled, sort_order)
            VALUES (:id, 'SAML', :name, '{}'::jsonb, :enabled, 0)
            """.trimIndent(),
            mapOf("id" to providerId, "name" to "saml-$registrationId", "enabled" to enabled),
        )
        jdbc.update(
            """
            INSERT INTO saml_idp_configs
                (id, registration_id, display_name, idp_entity_id, idp_sso_url, idp_x509_cert,
                 authn_provider_id, enabled)
            VALUES (:id, :reg, :name, :entityId, :ssoUrl, :cert, :providerId, :enabled)
            """.trimIndent(),
            mapOf(
                "id" to UUID.randomUUID(),
                "reg" to registrationId,
                "name" to "Test IdP $registrationId",
                "entityId" to "https://idp.example.com/$registrationId",
                "ssoUrl" to "https://idp.example.com/$registrationId/sso",
                "cert" to TEST_CERT_PEM,
                "providerId" to providerId,
                "enabled" to enabled,
            ),
        )
    }

    /** LOCAL 비번 없는 SSO 전용 사용자 — user id 반환. */
    private fun seedUser(username: String): UUID = userRepository.save(username, "$username@example.com", username).id

    /** [userId] 에 외부 신원 링크를 직접 INSERT 하고 link id 를 반환한다(서비스 우회 시드). */
    private fun seedLink(
        userId: UUID,
        providerId: UUID,
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

    /** 실 session 행을 만들고 그 id(=JWT sid 클레임)를 반환한다. */
    private fun seedSessionAndSid(userId: UUID): UUID = sessionService.create(userId, "saml", null, null).id

    // ── intent 헬퍼 ───────────────────────────────────────────────────────────

    private fun linkIntent(userId: UUID): SsoLinkingIntent =
        SsoLinkingIntent(
            mode = SsoLinkingIntent.Mode.LINK,
            userId = userId,
            sid = null,
            registrationId = SAML_REGISTRATION,
            providerType = ProviderType.SAML,
            expiresAt = farFuture(),
        )

    private fun reauthIntent(
        userId: UUID,
        sid: UUID,
    ): SsoLinkingIntent =
        SsoLinkingIntent(
            mode = SsoLinkingIntent.Mode.REAUTH,
            userId = userId,
            sid = sid,
            registrationId = SAML_REGISTRATION,
            providerType = ProviderType.SAML,
            expiresAt = farFuture(),
        )

    private fun sessionWithIntent(intent: SsoLinkingIntent): MockHttpSession {
        val session = MockHttpSession()
        intentStore.put(session, intent)
        return session
    }

    private fun farFuture() = java.time.Instant.now().plusSeconds(300)

    // ── 조회 헬퍼 (DB) ───────────────────────────────────────────────────────────

    private fun rowCount(table: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM $table", emptyMap<String, Any>(), Int::class.java) ?: 0

    private fun linkCount(userId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_external_accounts WHERE user_id = :userId",
            mapOf("userId" to userId),
            Int::class.java,
        ) ?: 0

    private fun subjectLinkCount(externalSubject: String): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_external_accounts WHERE external_subject = :subject",
            mapOf("subject" to externalSubject),
            Int::class.java,
        ) ?: 0

    private fun linkIdBySubject(externalSubject: String): UUID =
        jdbc.queryForObject(
            "SELECT id FROM user_external_accounts WHERE external_subject = :subject",
            mapOf("subject" to externalSubject),
            UUID::class.java,
        ) ?: error("external_subject 행 없음")

    private fun linkOwnerOf(externalSubject: String): UUID =
        jdbc.queryForObject(
            "SELECT user_id FROM user_external_accounts WHERE external_subject = :subject",
            mapOf("subject" to externalSubject),
            UUID::class.java,
        ) ?: error("external_subject 행 없음")

    // ── HTTP 헬퍼 ─────────────────────────────────────────────────────────────

    private fun url(path: String): String = "http://localhost:$port$path"

    private fun deleteLink(
        jwt: String,
        linkId: UUID,
    ): ResponseEntity<Map<*, *>> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set(HttpHeaders.AUTHORIZATION, "Bearer $jwt")
            }
        return restTemplate.exchange(
            url("/api/v1/auth/account/links/$linkId"),
            HttpMethod.DELETE,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
    }

    private fun bodyError(resp: ResponseEntity<Map<*, *>>): String? = resp.body?.get("error") as? String
}
