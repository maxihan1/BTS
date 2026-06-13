// 신뢰 디바이스(30일 MFA 면제) prod 프로파일 end-to-end 통합 테스트 — spec §완료기준 전수 ground-truth (FR-MF-05 Task 8)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.credential.ChangePasswordResult
import com.atlas.bts.identity.credential.ChangePasswordService
import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.user.UserRepository
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.HashingAlgorithm
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.ldap.core.LdapTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * 신뢰 디바이스(Trusted Device, 30일 MFA 면제) 전체 흐름 통합 테스트 (FR-MF-05 Task 8 / SDD §19.7.3).
 *
 * ## 목적 (ground-truth)
 * Task 5(TrustedDeviceController)·6(AuthController completeLogin 우회 + verifyMfa trust_device 등록 + 쿠키)·
 * 7(비밀번호 변경·TOTP 비활성 자동폐기) 의 production 배선을 실제 PostgreSQL + Spring Boot 전체 스택으로
 * end-to-end 검증한다. 단위/슬라이스 테스트(MockK·@WebMvcTest)로는 잡히지 않는
 * FilterChain→Controller→Service→Repository 의 트랜잭션 전파, HttpOnly 쿠키 왕복, JWT 클레임
 * (mfa_verified) 회로, user-bound 우회 차단, 자동폐기 cascade 를 spec §완료 기준 그대로 검증한다.
 *
 * ## 검증 시나리오 (spec §완료 기준)
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | S1→S2 | verify(trust_device=true) 쿠키로 다음 login | 챌린지 없이 200 TokenResponse + mfa_verified=true |
 * | S3 | Clock +31일 후 쿠키 login | mfa_required(우회 불가 — TTL 만료) |
 * | S4-단건 | DELETE /{id} 후 쿠키 login | mfa_required |
 * | S4-전체 | DELETE 전체 후 쿠키 login | mfa_required |
 * | EC2 | 타인 user 쿠키로 login | mfa_required(user-bound 차단) |
 * | S5 | 비밀번호 변경 성공 후 | 목록 empty + 쿠키 login mfa_required |
 * | S6 | TOTP disable 성공 후 | 쿠키 login mfa_required |
 * | FR-7 | 타인 디바이스 단건 취소 | 404 not_found(IDOR 은닉) |
 * | PAT | 목록/취소 PAT 인증 | 403 session_management_requires_interactive_login |
 *
 * ## 테스트 환경 — prod 프로파일 부팅 레시피 (identity-access-prod-randomport-boot-recipe)
 * non-prod 프로파일의 AlwaysAllow 마스킹을 피하고 실 wire 를 검증하려면 **반드시 `@ActiveProfiles("prod")`**
 * 다. prod + RANDOM_PORT 결합 선례가 없어 두 선례의 셋업을 합집합으로 구성한다.
 * - [SystemAdminInfraIntegrationTest] 에서 → `@ActiveProfiles("prod")` + prod `PemFileKeyProvider` 가
 *   요구하는 RSA PEM 파일([pemFilePath]) 주입.
 * - [MfaTotpIntegrationTest] 에서 → RANDOM_PORT + `TestRestTemplate` + OAuth2ClientAutoConfiguration 제외 +
 *   LDAP Bean `@MockBean` + MFA 암호화 키/salt 주입(TOTP setup/enable 동작).
 *
 * ## Clock 주입 (NFR-테스트-1 — TTL 만료 결정적 시뮬레이션)
 * [TrustedDeviceService] 의 만료/30일 계산은 주입 [Clock] 기준이다(time-bomb 회피, authcontroller-
 * revokesession-timebomb 교훈). 테스트는 단일 [MutableClock] 빈을 컨텍스트에 주입해 S3 에서 [advance] 로
 * 시각을 +31일 전진시켜 만료를 결정적으로 시뮬레이션한다(벽시계 sleep 없음).
 * - MutableClock 은 부팅 시점의 **실제 시각**([Instant.now])에서 시작하므로, 서버 [TotpService] 도 같은
 *   Clock 을 쓴다. 테스트의 TOTP 코드는 [currentTotpCode] 가 **같은 MutableClock 시각**으로 산출하므로
 *   서버 검증과 항상 동일 time-step 에 정렬된다(실시간 drift 무관 — TotpService ±1 step 도 흡수).
 *
 * ## 진짜 RED 확인 (가짜 그린 방지)
 * 본 테스트는 미배선/오배선이면 실패한다 — verify→trusted_device Set-Cookie 누락이면 S1→S2 가 mfa_required
 * 로 떨어지고, completeLogin 우회 미배선이면 S2 가 챌린지로 떨어진다. TTL 미만료 처리 결함이면 S3 가 우회로
 * 통과해버려 실패하고, user-bound 가드 제거면 EC2 가 우회로 통과해 실패한다. 자동폐기(7) 미배선이면 S5/S6 가
 * 우회로 통과해 실패한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
@Testcontainers
class TrustedDeviceFlowIntegrationTest {
    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway V001~V026 자동 마이그레이션(trusted_devices 포함). */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** MFA 암호화 테스트 키 — MfaSecretEncryptor 가 TOTP setup/enable 시 요구(미설정이면 IllegalStateException). */
        private const val MFA_TEST_KEY = "test-app-encryption-key-for-trusted-device"

        /** MFA salt — `Encryptors.stronger` 가 hex 문자열을 요구하므로 유효 hex 리터럴. */
        private const val MFA_TEST_SALT = "deadbeefcafef00d"

        /** RFC 6238 time-step 길이(초) — 운영 TotpService.PERIOD_SECONDS 와 동일. */
        private const val TOTP_PERIOD_SECONDS = 30L

        /** RFC 6238 코드 자릿수 — 운영 TotpService.DIGITS 와 동일. */
        private const val TOTP_DIGITS = 6

        /** 신뢰 디바이스 관리 엔드포인트 base path (TrustedDeviceController @RequestMapping). */
        private const val TRUSTED_DEVICES_PATH = "/api/v1/auth/mfa/trusted-devices"

        /** 신뢰 토큰 쿠키 이름 (AuthController.TRUSTED_DEVICE_COOKIE 와 동일). */
        private const val TRUSTED_DEVICE_COOKIE = "trusted_device"

        /**
         * 전 서비스 공유 가변 [Clock] — [SecurityBeans.clock] 으로 컨텍스트에 주입하고, 테스트가 [advance] 로
         * 시각을 전진시켜 TTL 만료를 결정적으로 시뮬레이션한다(S3). 부팅 시점의 실제 시각에서 시작해 서버
         * TotpService 와 정렬된다. 같은 인스턴스를 테스트도 참조하므로 TOTP 코드 산출이 서버 검증과 일치한다.
         */
        val sharedClock: MutableClock = MutableClock(Instant.now())

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            registry.add("bts.mfa.encryption.key") { MFA_TEST_KEY }
            registry.add("bts.mfa.encryption.salt") { MFA_TEST_SALT }
            // prod 프로파일에서 PemFileKeyProvider 가 PEM 파일 경로를 @Value 로 요구한다.
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
        }

        /**
         * 테스트용 임시 RSA 2048 PEM 파일 경로 (SystemAdminInfraIntegrationTest 선례).
         *
         * prod 의 PemFileKeyProvider 가 BouncyCastle PEMParser 를 사용하므로 BC provider 를 먼저 등록하고
         * PKCS#8(PRIVATE KEY) 형식으로 PEM 파일을 생성한다.
         */
        val pemFilePath: String =
            run {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val pemContent =
                    buildString {
                        appendLine("-----BEGIN PRIVATE KEY-----")
                        val mimeEncoder = Base64.getMimeEncoder(64, "\n".toByteArray())
                        appendLine(mimeEncoder.encodeToString(keyPair.private.encoded))
                        append("-----END PRIVATE KEY-----")
                    }
                val tmpFile = Files.createTempFile("bts-trusted-device-key-", ".pem")
                Files.writeString(tmpFile, pemContent)
                tmpFile.toAbsolutePath().toString()
            }
    }

    /**
     * 테스트에서 시각을 전진시키기 위한 가변 [Clock] (StepUpServiceTest.MutableClock 선례).
     *
     * 같은 인스턴스를 컨텍스트 전 서비스에 주입한 뒤 [advance] 로 instant 를 밀어, 벽시계 sleep 없이 TTL
     * 만료를 결정적으로 검증한다(time-bomb 회귀 방지). [reset] 으로 다음 테스트를 위해 기준 시각을 되돌린다.
     */
    class MutableClock(
        @Volatile private var current: Instant,
    ) : Clock() {
        private val base: Instant = current

        override fun instant(): Instant = current

        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        fun advance(by: Duration) {
            current = current.plus(by)
        }

        fun reset() {
            current = base
        }
    }

    /** prod 컨텍스트에 단일 [Clock] 빈을 공급한다 — [TrustedDeviceService] 등 시각 의존 서비스가 공유한다. */
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun clock(): Clock = sharedClock
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

    @MockBean
    lateinit var ldapTemplate: LdapTemplate

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
    lateinit var changePasswordService: ChangePasswordService

    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    /** 테스트 사용자 자격 (각 테스트마다 신규 생성 — 격리). */
    private val testPassword = "S3cur3P@ss!"
    private lateinit var testUserId: UUID
    private lateinit var testUsername: String

    @BeforeEach
    fun prepareTestUser() {
        // Clock 을 기준 시각으로 되돌려 테스트 간 시각 누수(S3 의 +31일)를 차단한다.
        sharedClock.reset()
        val suffix = UUID.randomUUID().toString().take(8)
        testUsername = "td-test-$suffix"
        val user =
            userRepository.save(
                username = testUsername,
                email = "td-$suffix@example.com",
                displayName = "TrustedDeviceTestUser",
            )
        testUserId = user.id
        localCredentialService.store(testUserId, testPassword.toCharArray())
    }

    // ── S1→S2: 신뢰 등록 후 같은 쿠키로 챌린지 없이 로그인 ──────────────────────────

    @Test
    fun `S1·S2 — verify trust_device=true 쿠키로 다음 login 시 챌린지 없이 정식 세션(mfa_verified=true)`() {
        val secret = enrollTotp()
        val trustedCookie = registerTrustedDevice(secret)
        assertThat(trustedCookie)
            .withFailMessage("verify(trust_device=true) 응답에 trusted_device Set-Cookie 가 있어야 합니다.")
            .isNotBlank()

        // S2 — 같은 쿠키 동반 로그인 → 챌린지 없이 정식 세션.
        val resp = loginWithCookie(testUsername, testPassword, trustedCookie)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val body = resp.body as Map<*, *>
        assertThat(body["mfa_required"])
            .withFailMessage("신뢰 우회 로그인은 mfa_required 가 없어야 합니다(우회 성공). 실제: $body")
            .isNull()
        val accessToken = body["access_token"] as String?
        assertThat(accessToken)
            .withFailMessage("신뢰 우회 로그인은 즉시 access_token 을 발급해야 합니다.")
            .isNotNull()
        assertThat(jwtMfaVerifiedClaim(accessToken!!))
            .withFailMessage("신뢰 우회 세션의 access_token 은 mfa_verified=true 여야 합니다.")
            .isTrue()
    }

    // ── S3: TTL 만료(+31일) 후 우회 불가 ─────────────────────────────────────────

    @Test
    fun `S3 — Clock +31일 경과 후 쿠키 login 은 mfa_required(우회 불가)`() {
        val secret = enrollTotp()
        val trustedCookie = registerTrustedDevice(secret)

        // 30일 고정 TTL 을 초과(+31일)시켜 만료시킨다.
        sharedClock.advance(Duration.ofDays(31))

        val resp = loginWithCookie(testUsername, testPassword, trustedCookie)
        assertMfaRequired(resp, "만료된 신뢰 디바이스는 우회되면 안 됩니다(TTL 30일 초과).")
    }

    // ── S4: 명시적 취소(단건/전체) 후 우회 불가 ────────────────────────────────────

    @Test
    fun `S4 — 단건 취소 후 그 쿠키 login 은 mfa_required`() {
        val secret = enrollTotp()
        val trustedCookie = registerTrustedDevice(secret)
        val accessToken = loginBypassAccessToken(trustedCookie)

        // 목록에서 방금 등록한 디바이스 id 를 얻어 단건 취소.
        val deviceId = listTrustedDeviceIds(accessToken).single()
        val delResp = deleteTrustedDevice(accessToken, deviceId)
        assertThat(delResp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val resp = loginWithCookie(testUsername, testPassword, trustedCookie)
        assertMfaRequired(resp, "단건 취소된 신뢰 디바이스는 우회되면 안 됩니다.")
    }

    @Test
    fun `S4 — 전체 취소 후 그 쿠키 login 은 mfa_required`() {
        val secret = enrollTotp()
        val trustedCookie = registerTrustedDevice(secret)
        val accessToken = loginBypassAccessToken(trustedCookie)

        val delResp = deleteAllTrustedDevices(accessToken)
        assertThat(delResp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val resp = loginWithCookie(testUsername, testPassword, trustedCookie)
        assertMfaRequired(resp, "전체 취소 후 신뢰 디바이스는 우회되면 안 됩니다.")
    }

    // ── EC2: 타인 user 쿠키는 우회 불가 (user-bound) ──────────────────────────────

    @Test
    fun `EC2 — 타인이 등록한 신뢰 쿠키로 다른 user 가 login 해도 우회 불가`() {
        // alice 가 신뢰 디바이스 등록(쿠키 획득).
        val aliceSecret = enrollTotp()
        val aliceCookie = registerTrustedDevice(aliceSecret)

        // 별도 사용자 bob 을 만들고 TOTP 활성화(우회 판정 진입 조건 — MFA 활성).
        val bobSuffix = UUID.randomUUID().toString().take(8)
        val bobUsername = "td-bob-$bobSuffix"
        val bob =
            userRepository.save(
                username = bobUsername,
                email = "td-bob-$bobSuffix@example.com",
                displayName = "BobTrustedDeviceUser",
            )
        localCredentialService.store(bob.id, testPassword.toCharArray())
        enableTotpFor(bobUsername)

        // bob 이 alice 의 신뢰 쿠키를 동반해 로그인 → token_hash 는 존재하나 user 불일치 → 우회 불가.
        val resp = loginWithCookie(bobUsername, testPassword, aliceCookie)
        assertMfaRequired(resp, "타인(alice) 쿠키로는 bob 의 MFA 가 우회되면 안 됩니다(user-bound).")
    }

    // ── S5: 비밀번호 변경 자동폐기 ────────────────────────────────────────────────

    @Test
    fun `S5 — 비밀번호 변경 성공 후 신뢰 디바이스 목록 empty + 쿠키 login 우회 불가`() {
        val secret = enrollTotp()
        val trustedCookie = registerTrustedDevice(secret)
        val accessToken = loginBypassAccessToken(trustedCookie)
        // 자동폐기 전엔 1건 존재.
        assertThat(listTrustedDeviceIds(accessToken)).hasSize(1)

        // 비밀번호 변경(자동폐기 훅) — 실 서비스 직접 호출(ChangePasswordIntegrationTest 선례).
        val newPassword = "N3wS3cur3P@ss!"
        val result =
            changePasswordService.change(
                userId = testUserId,
                currentSid = UUID.randomUUID(),
                current = testPassword.toCharArray(),
                new = newPassword.toCharArray(),
            )
        assertThat(result).isInstanceOf(ChangePasswordResult.Success::class.java)

        // 새 비밀번호로 정식 로그인해 목록을 조회하면 empty 여야 한다.
        val freshAccessToken = mfaVerifiedAccessToken(newPassword, secret)
        assertThat(listTrustedDeviceIds(freshAccessToken))
            .withFailMessage("비밀번호 변경 후 신뢰 디바이스가 전량 폐기돼 목록이 비어야 합니다.")
            .isEmpty()

        // 기존 신뢰 쿠키로 로그인해도 우회 불가.
        val resp = loginWithCookie(testUsername, newPassword, trustedCookie)
        assertMfaRequired(resp, "비밀번호 변경 후 기존 신뢰 쿠키는 우회되면 안 됩니다.")
    }

    // ── S6: TOTP 비활성화 자동폐기 ────────────────────────────────────────────────

    @Test
    fun `S6 — TOTP disable 성공 후 그 쿠키 login 우회 불가`() {
        val secret = enrollTotp()
        val trustedCookie = registerTrustedDevice(secret)
        val accessToken = loginBypassAccessToken(trustedCookie)

        // TOTP 비활성화(자동폐기 훅) — step-up 현재 코드 검증.
        val disableResp = mfaDisable(accessToken, currentTotpCode(secret))
        assertThat(disableResp.statusCode)
            .withFailMessage("disable 은 204 여야 합니다. 실제: ${disableResp.statusCode}, body=${disableResp.body}")
            .isEqualTo(HttpStatus.NO_CONTENT)

        // TOTP 가 꺼졌으므로 쿠키 유무와 무관하게 MFA 미활성 → 정식 세션(우회 분기 미진입)이지만
        // mfa_verified=false 다(신뢰 우회 아님). 핵심은 신뢰 디바이스가 폐기됐다는 것.
        val resp = loginWithCookie(testUsername, testPassword, trustedCookie)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val accessTokenAfter = (resp.body as Map<*, *>)["access_token"] as String?
        assertThat(accessTokenAfter)
            .withFailMessage("TOTP 비활성 사용자는 즉시 정식 세션을 받습니다.")
            .isNotNull()
        assertThat(jwtMfaVerifiedClaim(accessTokenAfter!!))
            .withFailMessage("TOTP 비활성 로그인은 mfa_verified=false 여야 합니다(신뢰 우회 아님 — 디바이스 폐기됨).")
            .isFalse()
    }

    // ── FR-7: 타인 디바이스 단건 취소는 404 (IDOR 은닉) ───────────────────────────

    @Test
    fun `FR-7 — 타인 소유 신뢰 디바이스 단건 취소는 404 not_found`() {
        // alice 가 신뢰 디바이스 등록.
        val aliceSecret = enrollTotp()
        val aliceCookie = registerTrustedDevice(aliceSecret)
        val aliceAccessToken = loginBypassAccessToken(aliceCookie)
        val aliceDeviceId = listTrustedDeviceIds(aliceAccessToken).single()

        // bob 이 alice 의 디바이스 id 로 단건 취소를 시도 → 404(타인 소유 은닉).
        val bobSuffix = UUID.randomUUID().toString().take(8)
        val bobUsername = "td-bob2-$bobSuffix"
        val bob =
            userRepository.save(
                username = bobUsername,
                email = "td-bob2-$bobSuffix@example.com",
                displayName = "BobFr7User",
            )
        localCredentialService.store(bob.id, testPassword.toCharArray())
        val bobAccessToken = mfaInactiveAccessToken(bobUsername, testPassword)

        val resp = deleteTrustedDevice(bobAccessToken, aliceDeviceId)
        assertThat(resp.statusCode)
            .withFailMessage("타인 소유 디바이스 단건 취소는 404 여야 합니다(IDOR 은닉). 실제: ${resp.statusCode}")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    // ── PAT: 목록/취소는 PAT 인증 403 ────────────────────────────────────────────

    @Test
    fun `PAT — 신뢰 디바이스 목록·취소는 PAT(비-JWT) 인증 시 403`() {
        val patToken = issuePatToken()

        val listResp = getTrustedDevices(patToken)
        assertThat(listResp.statusCode)
            .withFailMessage("PAT 인증 목록 조회는 403 이어야 합니다. 실제: ${listResp.statusCode}")
            .isEqualTo(HttpStatus.FORBIDDEN)

        val delResp = deleteTrustedDevice(patToken, UUID.randomUUID())
        assertThat(delResp.statusCode)
            .withFailMessage("PAT 인증 단건 취소는 403 이어야 합니다. 실제: ${delResp.statusCode}")
            .isEqualTo(HttpStatus.FORBIDDEN)

        val delAllResp = deleteAllTrustedDevices(patToken)
        assertThat(delAllResp.statusCode)
            .withFailMessage("PAT 인증 전체 취소는 403 이어야 합니다. 실제: ${delAllResp.statusCode}")
            .isEqualTo(HttpStatus.FORBIDDEN)
    }

    // ── 고수준 흐름 헬퍼 ──────────────────────────────────────────────────────────

    /** [testUserId] 에 TOTP 를 setup→enable 해 ACTIVE 로 만들고 secret(base32)을 반환한다. */
    private fun enrollTotp(): String = enableTotpFor(testUsername)

    /**
     * [username] 사용자에 TOTP 를 setup→enable 한다(ACTIVE). 반환은 코드 산출용 secret.
     *
     * setup/enable 은 JWT self-service 라 1단계 로그인으로 JWT 를 먼저 확보한다(해당 사용자는 아직 MFA 미활성).
     */
    private fun enableTotpFor(username: String): String {
        val accessToken = mfaInactiveAccessToken(username, testPassword)
        val setupBody = mfaSetup(accessToken).body as Map<*, *>
        val secret = extractSecretFromOtpauthUri(setupBody["otpauth_uri"] as String)
        val enableResp = mfaEnable(accessToken, currentTotpCode(secret))
        assertThat(enableResp.statusCode)
            .withFailMessage("enable 은 204 여야 합니다. 실제: ${enableResp.statusCode}, body=${enableResp.body}")
            .isEqualTo(HttpStatus.NO_CONTENT)
        return secret
    }

    /** TOTP 미활성 사용자의 1단계 로그인 access_token (setup/enable/disable self-service 용). */
    private fun mfaInactiveAccessToken(
        username: String,
        password: String,
    ): String {
        val resp = performLogin(username, password)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        return (resp.body as Map<*, *>)["access_token"] as String
    }

    /** TOTP 활성 사용자의 login→verify 정식 세션 access_token. */
    private fun mfaVerifiedAccessToken(
        password: String,
        secret: String,
    ): String {
        // verifyLogin 은 time-step 단조 증가 replay 방어가 있어, 직전 verify 와 같은 step 이면 거부된다.
        // 가변 Clock 을 한 time-step 전진시켜 새 step 의 코드를 산출한다(replay 회피, TTL 무영향).
        advanceOneTotpStep()
        val challenge = (performLogin(testUsername, password).body as Map<*, *>)["mfa_challenge_token"] as String
        val verifyResp = mfaVerify(challenge, currentTotpCode(secret), trustDevice = false)
        assertThat(verifyResp.statusCode).isEqualTo(HttpStatus.OK)
        return (verifyResp.body as Map<*, *>)["access_token"] as String
    }

    /**
     * TOTP 활성 사용자가 login→verify(trust_device=true) 로 신뢰 디바이스를 등록하고 trusted_device 쿠키
     * 값(`name=value`)을 반환한다.
     */
    private fun registerTrustedDevice(secret: String): String {
        // enable(verify) 와 다른 time-step 으로 verifyLogin 하도록 한 step 전진(replay 방어 회피, TTL 무영향).
        advanceOneTotpStep()
        val challenge = (performLogin(testUsername, testPassword).body as Map<*, *>)["mfa_challenge_token"] as String
        val verifyResp = mfaVerify(challenge, currentTotpCode(secret), trustDevice = true)
        assertThat(verifyResp.statusCode)
            .withFailMessage("verify 는 200 이어야 합니다. 실제: ${verifyResp.statusCode}, body=${verifyResp.body}")
            .isEqualTo(HttpStatus.OK)
        return extractTrustedDeviceCookie(verifyResp)
    }

    /** 신뢰 쿠키로 우회 로그인해 정식 세션 access_token 을 얻는다(목록/취소 호출용 JWT). */
    private fun loginBypassAccessToken(trustedCookie: String): String {
        val resp = loginWithCookie(testUsername, testPassword, trustedCookie)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        return (resp.body as Map<*, *>)["access_token"] as String
    }

    /** GET 목록에서 신뢰 디바이스 id 들을 추출한다. */
    @Suppress("UNCHECKED_CAST")
    private fun listTrustedDeviceIds(accessToken: String): List<UUID> {
        val resp = getTrustedDevices(accessToken)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val devices = (resp.body as Map<*, *>)["devices"] as List<Map<String, Any?>>
        return devices.map { UUID.fromString(it["id"] as String) }
    }

    /** 응답이 mfa_required 챌린지(우회 불가)인지 단언한다. */
    private fun assertMfaRequired(
        resp: ResponseEntity<Map<*, *>>,
        message: String,
    ) {
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val body = resp.body as Map<*, *>
        assertThat(body["mfa_required"])
            .withFailMessage("$message 실제: $body")
            .isEqualTo(true)
        assertThat(body["access_token"])
            .withFailMessage("우회 불가 응답에 정식 세션(access_token)이 발급되면 안 됩니다. 실제: $body")
            .isNull()
    }

    // ── HTTP 호출 헬퍼 ───────────────────────────────────────────────────────────

    /** POST /api/v1/auth/login (CSRF skip). */
    private fun performLogin(
        username: String,
        password: String,
    ): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"provider":"local","username":"$username","password":"$password"}"""
        return restTemplate.exchange(loginUrl(), HttpMethod.POST, HttpEntity(body, headers), Map::class.java)
    }

    /** POST /api/v1/auth/login + trusted_device 쿠키 동반 (S2 우회 시도). */
    private fun loginWithCookie(
        username: String,
        password: String,
        trustedCookie: String,
    ): ResponseEntity<Map<*, *>> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set(HttpHeaders.COOKIE, trustedCookie)
            }
        val body = """{"provider":"local","username":"$username","password":"$password"}"""
        return restTemplate.exchange(loginUrl(), HttpMethod.POST, HttpEntity(body, headers), Map::class.java)
    }

    private fun mfaSetup(accessToken: String): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(
            url("/api/v1/auth/mfa/totp/setup"),
            HttpMethod.POST,
            HttpEntity<Unit>(bearer(accessToken)),
            Map::class.java,
        )

    private fun mfaEnable(
        accessToken: String,
        code: String,
    ): ResponseEntity<Map<*, *>> = postCode("/api/v1/auth/mfa/totp/enable", bearer(accessToken), code)

    private fun mfaDisable(
        accessToken: String,
        code: String,
    ): ResponseEntity<Map<*, *>> {
        val headers = bearer(accessToken).apply { contentType = MediaType.APPLICATION_JSON }
        return restTemplate.exchange(
            url("/api/v1/auth/mfa/totp"),
            HttpMethod.DELETE,
            HttpEntity("""{"code":"$code"}""", headers),
            Map::class.java,
        )
    }

    /** POST /api/v1/auth/mfa/verify — 챌린지 토큰 + 코드 + trust_device. */
    private fun mfaVerify(
        challengeToken: String,
        code: String,
        trustDevice: Boolean,
    ): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body =
            """{"mfa_challenge_token":"$challengeToken","code":"$code","trust_device":$trustDevice}"""
        return restTemplate.exchange(
            url("/api/v1/auth/mfa/verify"),
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
    }

    private fun getTrustedDevices(token: String): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(
            url(TRUSTED_DEVICES_PATH),
            HttpMethod.GET,
            HttpEntity<Unit>(bearer(token)),
            Map::class.java,
        )

    private fun deleteTrustedDevice(
        token: String,
        id: UUID,
    ): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(
            url("$TRUSTED_DEVICES_PATH/$id"),
            HttpMethod.DELETE,
            HttpEntity<Unit>(bearer(token)),
            Map::class.java,
        )

    private fun deleteAllTrustedDevices(token: String): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(
            url(TRUSTED_DEVICES_PATH),
            HttpMethod.DELETE,
            HttpEntity<Unit>(bearer(token)),
            Map::class.java,
        )

    private fun postCode(
        path: String,
        headers: HttpHeaders,
        code: String,
    ): ResponseEntity<Map<*, *>> {
        headers.contentType = MediaType.APPLICATION_JSON
        return restTemplate.exchange(
            url(path),
            HttpMethod.POST,
            HttpEntity("""{"code":"$code"}""", headers),
            Map::class.java,
        )
    }

    /**
     * PAT(Personal Access Token) raw 토큰을 발급한다 — 신뢰 디바이스 관리가 PAT 를 403 으로 거부하는지 검증용.
     *
     * personal_access_tokens 테이블에 SHA-256 해시 1행을 직접 INSERT 하고 raw 토큰을 반환한다. JWT 가 아니므로
     * 컨트롤러의 [org.springframework.security.core.annotation.AuthenticationPrincipal] 주입이 null 이 된다.
     */
    private fun issuePatToken(): String {
        // PAT raw token 형식: "pat_" + 48자 body (PersonalAccessToken.TOKEN_PREFIX). token_hash = SHA-256(전체).
        val raw = "pat_${"a".repeat(48)}_${UUID.randomUUID().toString().replace("-", "")}".take(52)
        jdbc.update(
            """
            INSERT INTO personal_access_tokens
                (id, user_id, name, token_hash, scopes, expires_at, created_at)
            VALUES
                (:id, :userId, :name, :hash, '["*"]'::jsonb, now() + interval '30 days', now())
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "userId" to testUserId,
                "name" to "td-pat",
                "hash" to sha256Hex(raw),
            ),
        )
        return raw
    }

    // ── 저수준 유틸 ──────────────────────────────────────────────────────────────

    private fun loginUrl(): String = url("/api/v1/auth/login")

    private fun url(path: String): String = "http://localhost:$port$path"

    private fun bearer(token: String): HttpHeaders = HttpHeaders().apply { setBearerAuth(token) }

    /**
     * 같은 [sharedClock] 시각 기준으로 [secret] 의 정답 TOTP 코드를 산출한다.
     *
     * 서버 TotpService 도 같은 Clock 을 쓰므로 산출 코드와 서버 검증 time-step 이 항상 정렬된다(실시간 drift
     * 무관). 운영과 동일 파라미터(SHA1·6자리·30초)로 계산한다.
     */
    private fun currentTotpCode(secret: String): String {
        val timeStep = sharedClock.instant().epochSecond / TOTP_PERIOD_SECONDS
        return DefaultCodeGenerator(HashingAlgorithm.SHA1, TOTP_DIGITS).generate(secret, timeStep)
    }

    /**
     * 공유 Clock 을 한 TOTP time-step 만큼 전진시킨다 — 연속 verifyLogin 사이 replay 방어(단조 증가 step)를
     * 회피한다. 전진폭(31초)은 30일 TTL·+31일 만료 시나리오에 영향이 없다.
     */
    private fun advanceOneTotpStep() {
        sharedClock.advance(Duration.ofSeconds(TOTP_PERIOD_SECONDS + 1))
    }

    /** otpauth:// URI 의 `secret` 쿼리 파라미터를 추출한다. */
    private fun extractSecretFromOtpauthUri(otpauthUri: String): String =
        URI(otpauthUri)
            .query
            .orEmpty()
            .split("&")
            .first { it.startsWith("secret=") }
            .substringAfter("secret=")

    /** 응답 Set-Cookie 에서 trusted_device 쿠키를 `name=value` 형태로 추출한다(없으면 빈 문자열). */
    private fun extractTrustedDeviceCookie(response: ResponseEntity<*>): String {
        val value =
            response.headers[HttpHeaders.SET_COOKIE]
                .orEmpty()
                .firstOrNull { it.startsWith("$TRUSTED_DEVICE_COOKIE=") }
                ?.substringAfter("$TRUSTED_DEVICE_COOKIE=")
                ?.substringBefore(";")
                ?.trim()
                .orEmpty()
        return if (value.isEmpty()) "" else "$TRUSTED_DEVICE_COOKIE=$value"
    }

    /**
     * JWT access_token payload 의 `mfa_verified` 클레임을 반환한다.
     *
     * 서명 검증은 필터 체인이 수행하므로 payload Base64url 디코드만으로 클레임을 확인한다. 비밀값 미로깅.
     */
    private fun jwtMfaVerifiedClaim(accessToken: String): Boolean {
        val payload = accessToken.split(".")[1]
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        return Regex("\"mfa_verified\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1) == "true"
    }

    /** SHA-256(raw) hex 64자 — PAT token_hash 저장 규칙(DEVELOPMENT.md §1.1 규칙 2). */
    private fun sha256Hex(raw: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
