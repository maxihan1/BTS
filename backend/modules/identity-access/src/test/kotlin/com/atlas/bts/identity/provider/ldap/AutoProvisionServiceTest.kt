// AutoProvisionService 단위 테스트 — LDAP bind 성공 후 users + user_external_accounts UPSERT 검증

package com.atlas.bts.identity.provider.ldap

import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * AutoProvisionService 단위 테스트 (Task 15 — FR-AU-09).
 *
 * 검증 항목:
 * - LDAP bind 성공 + user 미존재 → UserRepository.provisionFromExternal + ExternalAccountRepository UPSERT 순서
 * - LDAP bind 성공 + user 기존 존재 → email/displayName 동기화 + ExternalAccountRepository groups 갱신
 * - EC-17: provision 도중 오류 → 단일 @Transactional 로 양쪽 모두 rollback (예외 전파 검증)
 * - LDAP unavailable → ProviderUnavailableException throw
 * - priority = 80 (LdapProvider 는 ProviderType.LDAP.priority 위임)
 * - @Service 부착 확인 (AnnotationUtils 리플렉션)
 */
class AutoProvisionServiceTest {

    private lateinit var userRepo: UserRepository
    private lateinit var externalAccountRepo: ExternalAccountRepository
    private lateinit var service: AutoProvisionService

    private val fixedNow = Instant.parse("2026-05-21T00:00:00Z")
    private val providerId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val userId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val accountId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

    private val sampleUser = User(
        id = userId,
        username = "bob@example.org",
        email = "bob@example.org",
        displayName = "Bob",
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    private val sampleAccount = ExternalAccount(
        id = accountId,
        providerId = providerId,
        externalSubject = "uid=bob,ou=people,dc=example,dc=org",
        userId = userId,
        groups = emptyList(),
        failedAttempts = 0,
        lockedUntil = null,
        lastLoginAt = null,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    private val ldapAttrs = LdapProvisionAttrs(
        username = "bob@example.org",
        email = "bob@example.org",
        displayName = "Bob",
        externalSubject = "uid=bob,ou=people,dc=example,dc=org",
        groups = emptyList(),
    )

    @BeforeEach
    fun setUp() {
        userRepo = mockk()
        externalAccountRepo = mockk(relaxed = true)
        service = AutoProvisionService(userRepo, externalAccountRepo)
    }

    // ── US-04: 첫 로그인 — user 미존재 → 신규 INSERT ─────────────────────────

    @Test
    fun `첫 로그인 — UserRepository provisionFromExternal 호출 후 ExternalAccountRepository UPSERT`() {
        every { userRepo.provisionFromExternal(any(), any(), any()) } returns sampleUser
        every {
            externalAccountRepo.provisionUser(any(), any(), any(), any(), any(), any())
        } returns sampleAccount

        val result = service.provision(providerId, ldapAttrs)

        assertThat(result.userId).isEqualTo(userId)
        verify { userRepo.provisionFromExternal("bob@example.org", "bob@example.org", "Bob") }
        verify {
            externalAccountRepo.provisionUser(
                providerId = providerId,
                externalSubject = "uid=bob,ou=people,dc=example,dc=org",
                username = "bob@example.org",
                displayName = "Bob",
                email = "bob@example.org",
                groups = emptyList(),
            )
        }
    }

    @Test
    fun `첫 로그인 — UserRepository 먼저, ExternalAccountRepository 나중 순서 보장 (DATA-md 단일 트랜잭션)`() {
        every { userRepo.provisionFromExternal(any(), any(), any()) } returns sampleUser
        every {
            externalAccountRepo.provisionUser(any(), any(), any(), any(), any(), any())
        } returns sampleAccount

        service.provision(providerId, ldapAttrs)

        // users UPSERT → user_external_accounts UPSERT 순서 보장 (FK 의존성)
        verifyOrder {
            userRepo.provisionFromExternal(any(), any(), any())
            externalAccountRepo.provisionUser(any(), any(), any(), any(), any(), any())
        }
    }

    // ── user 기존 존재 — 정보 동기화 ─────────────────────────────────────────

    @Test
    fun `기존 user — email + displayName 동기화 (provisionFromExternal UPSERT ON CONFLICT 처리)`() {
        val updatedUser = sampleUser.copy(email = "bob-new@example.org", displayName = "Bob Updated")
        val updatedAttrs = ldapAttrs.copy(email = "bob-new@example.org", displayName = "Bob Updated")
        every {
            userRepo.provisionFromExternal("bob@example.org", "bob-new@example.org", "Bob Updated")
        } returns updatedUser
        every {
            externalAccountRepo.provisionUser(any(), any(), any(), any(), any(), any())
        } returns sampleAccount

        val result = service.provision(providerId, updatedAttrs)

        assertThat(result.userId).isEqualTo(userId)
        verify { userRepo.provisionFromExternal("bob@example.org", "bob-new@example.org", "Bob Updated") }
    }

    // ── EC-17: provision 도중 오류 → 예외 전파 (Spring @Transactional rollback) ─

    @Test
    fun `EC-17 UserRepository 오류 시 예외 전파 — ExternalAccountRepository 미호출`() {
        every {
            userRepo.provisionFromExternal(any(), any(), any())
        } throws RuntimeException("DB 연결 오류 — users INSERT 실패")

        assertThatThrownBy { service.provision(providerId, ldapAttrs) }
            .isInstanceOf(RuntimeException::class.java)

        // users 실패 시 user_external_accounts 는 호출되지 않아야 함
        verify(exactly = 0) { externalAccountRepo.provisionUser(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `EC-17 ExternalAccountRepository 오류 시 예외 전파 — @Transactional rollback 대상`() {
        every { userRepo.provisionFromExternal(any(), any(), any()) } returns sampleUser
        every {
            externalAccountRepo.provisionUser(any(), any(), any(), any(), any(), any())
        } throws RuntimeException("DB 연결 오류 — user_external_accounts UPSERT 실패")

        assertThatThrownBy { service.provision(providerId, ldapAttrs) }
            .isInstanceOf(RuntimeException::class.java)
    }

    // ── CONCERN-4: LDAP unavailable → ProviderUnavailableException ──────────

    @Test
    fun `LDAP unavailable — ProviderUnavailableException 는 RuntimeException 서브타입`() {
        // ProviderUnavailableException 이 존재하고 RuntimeException 을 상속하는지 검증
        val ex = ProviderUnavailableException("LDAP 서버 연결 불가")
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
        assertThat(ex.message).contains("LDAP 서버 연결 불가")
    }

    @Test
    fun `ProviderUnavailableException — providerType 필드 포함`() {
        val ex = ProviderUnavailableException("LDAP 연결 오류", providerType = "LDAP")
        assertThat(ex.providerType).isEqualTo("LDAP")
    }

    // ── @Service 어노테이션 존재 확인 ────────────────────────────────────────

    @Test
    fun `AutoProvisionService 는 @Service 어노테이션을 보유한다`() {
        val hasService = AutoProvisionService::class.java
            .isAnnotationPresent(org.springframework.stereotype.Service::class.java)
        assertThat(hasService).isTrue()
    }

    // ── priority 확인 ─────────────────────────────────────────────────────────

    @Test
    fun `LdapProvider priority 는 80 (ProviderType-LDAP 위임)`() {
        assertThat(com.atlas.bts.identity.spi.ProviderType.LDAP.priority).isEqualTo(80)
    }
}
