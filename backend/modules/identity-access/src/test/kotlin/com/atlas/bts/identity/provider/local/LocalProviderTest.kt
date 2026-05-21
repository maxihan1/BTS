// LocalProvider 단위 테스트 — MockK 기반, EC-01/EC-02 + 성공 흐름 + password wipe 검증

package com.atlas.bts.identity.provider.local

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.ProviderType
import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * LocalProvider 단위 테스트 (MockK).
 *
 * 검증 대상:
 * - supports(credential): UsernamePassword → true, 다른 타입 → false
 * - authenticate 성공 흐름: Success(principal, providerId="local")
 * - EC-01 (bad password): INVALID_CREDENTIALS
 * - EC-02 (user 미존재): INVALID_CREDENTIALS + dummy verify 수행 (timing attack 방어)
 * - user disabled (local_credentials row 없음): INVALID_CREDENTIALS
 * - priority = 70 (ProviderType.LOCAL.priority)
 * - @Service 부착 확인 (ArchUnit 룰 Task 29 — 여기서 직접 어노테이션 존재 검증)
 * - password CharArray wipe (DEVELOPMENT.md §1.1)
 */
class LocalProviderTest {

    private lateinit var localCredentialService: LocalCredentialService
    private lateinit var userRepository: UserRepository
    private lateinit var provider: LocalProvider

    private val userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val fixedNow = Instant.parse("2026-05-21T00:00:00Z")

    private val sampleUser =
        User(
            id = userId,
            username = "alice",
            email = "alice@example.com",
            displayName = "Alice",
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )

    @BeforeEach
    fun setUp() {
        localCredentialService = mockk()
        userRepository = mockk()
        provider = LocalProvider(localCredentialService, userRepository)
    }

    // ── supports ─────────────────────────────────────────────────────────────

    @Test
    fun `supports — UsernamePassword 는 true`() {
        val credential = Credential.UsernamePassword("alice", "pw".toCharArray())
        assertThat(provider.supports(credential)).isTrue()
    }

    @Test
    fun `supports — Pat 는 false`() {
        assertThat(provider.supports(Credential.Pat("bts_some_token"))).isFalse()
    }

    @Test
    fun `supports — LdapBind 는 false`() {
        assertThat(provider.supports(Credential.LdapBind("alice", "pw".toCharArray()))).isFalse()
    }

    // ── priority ─────────────────────────────────────────────────────────────

    @Test
    fun `priority 는 70 (ProviderType LOCAL priority)`() {
        assertThat(provider.priority).isEqualTo(ProviderType.LOCAL.priority)
        assertThat(provider.priority).isEqualTo(70)
    }

    // ── type ─────────────────────────────────────────────────────────────────

    @Test
    fun `type 은 LOCAL`() {
        assertThat(provider.type).isEqualTo(ProviderType.LOCAL)
    }

    // ── authenticate 성공 ─────────────────────────────────────────────────────

    @Test
    fun `authenticate 성공 — Success principal 반환 providerId=local`() {
        every { userRepository.findByUsername("alice") } returns sampleUser
        every { localCredentialService.verifyForUser(userId, any()) } returns true

        val result = provider.authenticate(Credential.UsernamePassword("alice", "correct!".toCharArray()))

        assertThat(result).isInstanceOf(AuthnResult.Success::class.java)
        val success = result as AuthnResult.Success
        assertThat(success.principal.userId).isEqualTo(userId)
        assertThat(success.principal.providerType).isEqualTo(ProviderType.LOCAL)
        assertThat(success.principal.displayName).isEqualTo("Alice")
        assertThat(success.principal.externalSubject).isNull()
    }

    // ── EC-01 (bad password) ─────────────────────────────────────────────────

    @Test
    fun `EC-01 — 패스워드 불일치 시 INVALID_CREDENTIALS`() {
        every { userRepository.findByUsername("alice") } returns sampleUser
        every { localCredentialService.verifyForUser(userId, any()) } returns false

        val result = provider.authenticate(Credential.UsernamePassword("alice", "wrong".toCharArray()))

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS))
    }

    // ── EC-02 (user 미존재) — dummy verify 로 timing attack 방어 ─────────────

    @Test
    fun `EC-02 — user 미존재 시 INVALID_CREDENTIALS + dummy verify 호출`() {
        every { userRepository.findByUsername("ghost") } returns null
        // dummy verify: verifyForUser 가 userId=DUMMY_USER_ID 로 호출돼야 함
        every { localCredentialService.verifyForUser(any(), any()) } returns false

        val result = provider.authenticate(Credential.UsernamePassword("ghost", "anything".toCharArray()))

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS))
        // timing attack 방어 — verifyForUser 가 반드시 호출됨 (user 없어도)
        verify(exactly = 1) { localCredentialService.verifyForUser(any(), any()) }
    }

    // ── user disabled (local_credentials row 없음) ────────────────────────────

    @Test
    fun `user disabled — local_credentials row 없음 시 INVALID_CREDENTIALS`() {
        // user 는 존재하지만 verifyForUser 가 false (row 없으면 LocalCredentialService 내부에서 dummy verify 후 false)
        every { userRepository.findByUsername("alice") } returns sampleUser
        every { localCredentialService.verifyForUser(userId, any()) } returns false

        val result = provider.authenticate(Credential.UsernamePassword("alice", "any".toCharArray()))

        assertThat(result).isEqualTo(AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS))
    }

    // ── @Service 어노테이션 부착 확인 (Task 29 ArchUnit 회귀 가드) ─────────────

    @Test
    fun `@Service 어노테이션이 LocalProvider 클래스에 부착돼 있음`() {
        assertThat(LocalProvider::class.java.isAnnotationPresent(Service::class.java)).isTrue()
    }

    // ── password wipe ─────────────────────────────────────────────────────────

    @Test
    fun `password wipe — 인증 완료 후 CharArray 가 비워짐`() {
        every { userRepository.findByUsername("alice") } returns sampleUser
        every { localCredentialService.verifyForUser(userId, any()) } returns true

        val password = "correct!".toCharArray()
        provider.authenticate(Credential.UsernamePassword("alice", password))

        assertThat(password).containsOnly(' ')
    }

    @Test
    fun `password wipe — user 미존재 시에도 CharArray 가 비워짐`() {
        every { userRepository.findByUsername("ghost") } returns null
        every { localCredentialService.verifyForUser(any(), any()) } returns false

        val password = "anything".toCharArray()
        provider.authenticate(Credential.UsernamePassword("ghost", password))

        assertThat(password).containsOnly(' ')
    }

    @Test
    fun `password wipe — 패스워드 불일치 시에도 CharArray 가 비워짐`() {
        every { userRepository.findByUsername("alice") } returns sampleUser
        every { localCredentialService.verifyForUser(userId, any()) } returns false

        val password = "wrong".toCharArray()
        provider.authenticate(Credential.UsernamePassword("alice", password))

        assertThat(password).containsOnly(' ')
    }
}
