// 비밀번호 변경 통합 테스트 — S1 정상 변경·S7 세션 무효화·정책 위반 3개 시나리오 (FR-AU-05 Task 4)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.credential.ChangePasswordResult
import com.atlas.bts.identity.credential.ChangePasswordService
import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.credential.StoredPasswordCredentialRepository
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.session.RefreshToken
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 비밀번호 변경 통합 테스트 (FR-AU-05 Task 4 / SDD §19.3).
 *
 * ## 목적
 * [ChangePasswordService.change] 가 실제 PostgreSQL + Flyway V001~V006 스택에서
 * credential rotate / 세션 무효화 / 정책 위반 거부를 올바르게 수행하는지 검증한다.
 *
 * ## 검증 시나리오
 * | 시나리오 | 검증 항목 |
 * |---|---|
 * | S1 정상 변경 | 이전 비번 verify=false, 새 비번 verify=true, updated_at 갱신 |
 * | S7 다른 세션 무효화 | 세션 B revoked + refresh chain revoked, 현재 세션 A active 유지 |
 * | 정책 위반 변경 거부 | PolicyViolation 반환, DB password_hash 불변 |
 *
 * ## Testcontainers 전략
 * 기존 [LocalAuthFlowIntegrationTest] 와 동일한 @SpringBootTest(RANDOM_PORT) + singleton container 패턴.
 * 새 @Container 를 만들지 않고 같은 설정 구조를 재사용하여 stale port 함정을 방지한다 (learnings 2026-05-21).
 *
 * ## 보안 계약
 * - [verifyForUser] 는 호출마다 전달된 CharArray 를 내부에서 wipe 한다.
 *   각 검증 호출에 독립적인 새 배열을 전달한다.
 * - 평문 비밀번호 / 해시값을 로그에 출력하지 않는다.
 * - refresh token 저장 시 SHA-256(rawToken) hash 만 DB 에 저장한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class ChangePasswordIntegrationTest {

    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway V001~V006 자동 마이그레이션 적용 */
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

        /** SHA-256(rawToken) hex 64자 — refresh token 해시 저장 규칙 (DEVELOPMENT.md §1.1 규칙 2) */
        private fun sha256Hex(raw: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
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

    // ── 테스트 대상 Bean ────────────────────────────────────────────────────────
    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var localCredentialService: LocalCredentialService

    @Autowired
    lateinit var changePasswordService: ChangePasswordService

    @Autowired
    lateinit var sessionService: SessionService

    @Autowired
    lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    lateinit var credentialRepository: StoredPasswordCredentialRepository

    /** 테스트 격리 — 각 테스트마다 독립적인 사용자를 생성한다 */
    private lateinit var testUserId: UUID

    /** 정책을 통과하는 초기 비밀번호 (12자+, 대소문자+숫자+특수문자) */
    private val initialPassword = "Initial@Pass1"

    /** 정책을 통과하는 새 비밀번호 */
    private val newValidPassword = "NewValid@Pass2"

    /** 정책 위반 비밀번호 — 11자 (PasswordPolicy MIN_LENGTH 미달) */
    private val policyViolatingPassword = "Short@123ab"

    @BeforeEach
    fun prepareTestUser() {
        // 테스트 격리: 각 테스트마다 유일한 username 으로 신규 사용자 생성
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        val user = userRepository.save(
            username = "change-pw-test-$uniqueSuffix",
            email = "changepw-$uniqueSuffix@example.com",
            displayName = "ChangePasswordTestUser",
        )
        testUserId = user.id
        // 초기 비밀번호 설정
        localCredentialService.store(testUserId, initialPassword.toCharArray())
    }

    // ── S1. 정상 변경 ─────────────────────────────────────────────────────────

    /**
     * S1: change(현재 비번, 새 비번) → Success
     * - verifyForUser(이전 비번) = false
     * - verifyForUser(새 비번) = true
     * - local_credentials.updated_at 이 변경 전보다 같거나 이후
     *
     * ## updated_at 검증 방법
     * change 호출 전 [StoredPasswordCredentialRepository.findByUserId] 로 updated_at 을 캡처한다.
     * change 호출 후 재조회하여 변경 전 updated_at 이하가 아님을 단언한다.
     * DB now() 해상도(ms)로 인해 "이후 또는 같음" 조건을 사용한다.
     */
    @Test
    fun `S1 정상 변경 — 이전 비번 verify 실패, 새 비번 verify 성공, updated_at 갱신`() {
        // 변경 전 updated_at 캡처
        val before = credentialRepository.findByUserId(testUserId)
        assertThat(before).isNotNull
        val updatedAtBefore = before!!.updatedAt

        val currentSid = UUID.randomUUID()

        // 비밀번호 변경 실행
        val result = changePasswordService.change(
            userId = testUserId,
            currentSid = currentSid,
            current = initialPassword.toCharArray(),
            new = newValidPassword.toCharArray(),
        )

        assertThat(result)
            .withFailMessage("비밀번호 변경이 Success 를 반환해야 하나 실제: $result")
            .isEqualTo(ChangePasswordResult.Success)

        // 이전 비밀번호는 더 이상 유효하지 않아야 한다
        val oldPwValid = localCredentialService.verifyForUser(testUserId, initialPassword.toCharArray())
        assertThat(oldPwValid)
            .withFailMessage("변경 후 이전 비밀번호가 유효해서는 안 됩니다.")
            .isFalse()

        // 새 비밀번호는 유효해야 한다
        val newPwValid = localCredentialService.verifyForUser(testUserId, newValidPassword.toCharArray())
        assertThat(newPwValid)
            .withFailMessage("변경 후 새 비밀번호가 유효해야 합니다.")
            .isTrue()

        // updated_at 이 갱신됐는지 확인 (같거나 이후 — DB now() ms 해상도 허용)
        val after = credentialRepository.findByUserId(testUserId)
        assertThat(after).isNotNull
        assertThat(after!!.updatedAt)
            .withFailMessage(
                "updated_at 이 변경 전보다 이전이어서는 안 됩니다. " +
                    "before=$updatedAtBefore after=${after.updatedAt}",
            )
            .isAfterOrEqualTo(updatedAtBefore)
    }

    // ── S7. 다른 세션 무효화 ──────────────────────────────────────────────────

    /**
     * S7: 세션 A(현재) + 세션 B 생성, 각 refresh token chain 생성 → 세션 A의 sid 로 change
     * - 세션 B revoked (isRevoked() = true)
     * - 세션 B의 refresh chain revoked (used_at 설정)
     * - 세션 A active 유지 (isRevoked() = false)
     *
     * ## refresh token 생성 방법
     * [RefreshTokenRepository.save] 를 사용하여 세션 A와 B에 각각 미사용 refresh token 을 저장한다.
     * 해시 저장 규칙: [sha256Hex](rawToken) — raw token 은 DB 에 저장하지 않는다.
     */
    @Test
    fun `S7 다른 세션 무효화 — 세션 B revoked + refresh chain revoked, 현재 세션 A active 유지`() {
        val now = Instant.now()

        // 세션 A (현재 요청 세션)
        val sessionA = sessionService.create(
            userId = testUserId,
            providerId = "local",
            ipAddress = "127.0.0.1",
            userAgent = "TestAgent/1.0",
        )

        // 세션 B (다른 세션 — 비밀번호 변경 시 무효화 대상)
        val sessionB = sessionService.create(
            userId = testUserId,
            providerId = "local",
            ipAddress = "192.168.1.2",
            userAgent = "TestAgent/2.0",
        )

        // 세션 A의 refresh token (미사용 상태)
        val rawTokenA = "token-a-${UUID.randomUUID()}"
        val refreshTokenA = RefreshToken(
            id = UUID.randomUUID(),
            sessionId = sessionA.id,
            tokenHash = sha256Hex(rawTokenA),
            issuedAt = now,
            expiresAt = now.plus(14, ChronoUnit.DAYS),
            usedAt = null,
            replacedBy = null,
        )
        refreshTokenRepository.save(refreshTokenA)

        // 세션 B의 refresh token (미사용 상태)
        val rawTokenB = "token-b-${UUID.randomUUID()}"
        val refreshTokenB = RefreshToken(
            id = UUID.randomUUID(),
            sessionId = sessionB.id,
            tokenHash = sha256Hex(rawTokenB),
            issuedAt = now,
            expiresAt = now.plus(14, ChronoUnit.DAYS),
            usedAt = null,
            replacedBy = null,
        )
        refreshTokenRepository.save(refreshTokenB)

        // 세션 A 의 sid 로 비밀번호 변경 실행
        val result = changePasswordService.change(
            userId = testUserId,
            currentSid = sessionA.id,
            current = initialPassword.toCharArray(),
            new = newValidPassword.toCharArray(),
        )

        assertThat(result)
            .withFailMessage("비밀번호 변경이 Success 를 반환해야 하나 실제: $result")
            .isEqualTo(ChangePasswordResult.Success)

        // 세션 A 는 여전히 active 여야 한다
        val sessionAAfter = sessionService.lookup(sessionA.id)
        assertThat(sessionAAfter)
            .withFailMessage("세션 A 가 존재해야 합니다.")
            .isNotNull
        assertThat(sessionAAfter!!.isRevoked())
            .withFailMessage("현재 세션 A 는 revoke 되어서는 안 됩니다.")
            .isFalse()

        // 세션 B 는 revoked 여야 한다
        val sessionBAfter = sessionService.lookup(sessionB.id)
        assertThat(sessionBAfter)
            .withFailMessage("세션 B 가 존재해야 합니다.")
            .isNotNull
        assertThat(sessionBAfter!!.isRevoked())
            .withFailMessage("세션 B 는 비밀번호 변경으로 revoke 되어야 합니다.")
            .isTrue()
        assertThat(sessionBAfter.revokeReason)
            .withFailMessage("세션 B 의 revoke 사유가 '${ChangePasswordService.REVOKE_REASON}' 이어야 합니다.")
            .isEqualTo(ChangePasswordService.REVOKE_REASON)

        // 세션 B 의 refresh token chain 은 revoke 됐어야 한다 (used_at 설정)
        // findByTokenHash 로 B 의 refresh token 상태를 확인한다
        val rtBAfter = refreshTokenRepository.findByTokenHash(sha256Hex(rawTokenB))
        assertThat(rtBAfter)
            .withFailMessage("세션 B 의 refresh token 이 존재해야 합니다.")
            .isNotNull
        assertThat(rtBAfter!!.usedAt)
            .withFailMessage("세션 B 의 refresh token 이 폐기(used_at 설정)되어야 합니다.")
            .isNotNull()

        // 세션 A 의 refresh token 은 여전히 미사용 상태여야 한다
        val rtAAfter = refreshTokenRepository.findByTokenHash(sha256Hex(rawTokenA))
        assertThat(rtAAfter)
            .withFailMessage("세션 A 의 refresh token 이 존재해야 합니다.")
            .isNotNull
        assertThat(rtAAfter!!.usedAt)
            .withFailMessage("현재 세션 A 의 refresh token 은 폐기되어서는 안 됩니다.")
            .isNull()
    }

    // ── 정책 위반 변경 거부 ───────────────────────────────────────────────────

    /**
     * 정책 위반 변경 거부: change(현재 비번, 11자 비번) → PolicyViolation, DB password_hash 불변
     *
     * ## 검증 방법
     * change 호출 전 [StoredPasswordCredentialRepository.findByUserId] 로 password_hash 를 캡처한다.
     * change 호출 후 재조회하여 password_hash 가 동일함을 단언한다.
     * password_hash 를 로그에 출력하지 않는다 — assertThat 단언에서만 비교한다.
     */
    @Test
    fun `정책 위반 변경 거부 — DB password_hash 불변`() {
        val currentSid = UUID.randomUUID()

        // 변경 전 hash 캡처
        val credBefore = credentialRepository.findByUserId(testUserId)
        assertThat(credBefore).isNotNull
        val hashBefore = credBefore!!.passwordHash

        // 정책 위반 비밀번호(11자)로 변경 시도
        val result = changePasswordService.change(
            userId = testUserId,
            currentSid = currentSid,
            current = initialPassword.toCharArray(),
            new = policyViolatingPassword.toCharArray(),
        )

        // PolicyViolation 을 반환해야 한다
        assertThat(result)
            .withFailMessage("정책 위반 시 PolicyViolation 이 반환되어야 합니다. 실제: $result")
            .isInstanceOf(ChangePasswordResult.PolicyViolation::class.java)

        val pv = result as ChangePasswordResult.PolicyViolation
        assertThat(pv.violations)
            .withFailMessage("PolicyViolation 의 violations 목록이 비어 있어서는 안 됩니다.")
            .isNotEmpty()

        // DB password_hash 가 변경되지 않아야 한다
        val credAfter = credentialRepository.findByUserId(testUserId)
        assertThat(credAfter).isNotNull
        assertThat(credAfter!!.passwordHash)
            .withFailMessage("정책 위반 시 DB password_hash 가 변경되어서는 안 됩니다.")
            .isEqualTo(hashBefore)

        // 초기 비밀번호가 여전히 유효해야 한다 (hash 불변 간접 검증)
        val originalPwStillValid = localCredentialService.verifyForUser(
            testUserId,
            initialPassword.toCharArray(),
        )
        assertThat(originalPwStillValid)
            .withFailMessage("정책 위반 거부 후 초기 비밀번호가 여전히 유효해야 합니다.")
            .isTrue()
    }
}
