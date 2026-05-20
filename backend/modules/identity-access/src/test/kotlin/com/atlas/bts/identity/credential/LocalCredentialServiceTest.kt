// LocalCredentialService 단위 테스트 — Argon2id 해싱/검증 3개 케이스 + store/verifyForUser/rotate 9개 케이스

package com.atlas.bts.identity.credential

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class LocalCredentialServiceTest {
    private val sut = LocalCredentialService()

    @Test
    fun `hash returns argon2id encoded string`() {
        val plain = "S3cur3P@ss!".toCharArray()

        val hash = sut.hash(plain)

        assertThat(hash).startsWith("\$argon2id\$")
    }

    @Test
    fun `verify returns true for matching plain`() {
        val plain = "S3cur3P@ss!".toCharArray()
        val hash = sut.hash(plain)

        val result = sut.verify(hash, "S3cur3P@ss!".toCharArray())

        assertTrue(result)
    }

    @Test
    fun `verify returns false for mismatching plain`() {
        val plain = "S3cur3P@ss!".toCharArray()
        val hash = sut.hash(plain)

        val result = sut.verify(hash, "WrongP@ss!".toCharArray())

        assertFalse(result)
    }

    // store 테스트

    @Test
    fun `store - 신규 user 저장 시 Repository save 호출 및 엔티티 반환`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        val savedCredential =
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$stubhash",
                createdAt = now,
                updatedAt = now,
            )
        every { repo.save(any()) } returns savedCredential

        val result = service.store(userId, "P@ssw0rd!".toCharArray())

        assertThat(result.userId).isEqualTo(userId)
        verify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `store - 같은 user 두 번 호출 시 UPSERT (두 번 모두 save 호출)`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        val credential =
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$stubhash",
                createdAt = now,
                updatedAt = now,
            )
        every { repo.save(any()) } returns credential

        service.store(userId, "P@ssw0rd!1".toCharArray())
        service.store(userId, "P@ssw0rd!2".toCharArray())

        verify(exactly = 2) { repo.save(any()) }
    }

    @Test
    fun `store - plain CharArray 는 호출 후 wipe 됨`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        val plain = "P@ssw0rd!".toCharArray()
        val credential =
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$stubhash",
                createdAt = now,
                updatedAt = now,
            )
        every { repo.save(any()) } returns credential

        service.store(userId, plain)

        // wipe 후 배열 내 모든 문자가 공백(' ')이어야 한다
        assertTrue(plain.all { it == ' ' })
    }

    // verifyForUser 테스트

    @Test
    fun `verifyForUser - 정상 비밀번호 일치 시 true 반환`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        // 실제 Argon2 해시를 사전 생성
        val rawHash = service.hash("CorrectPass!".toCharArray())
        val now = Instant.now()
        every { repo.findByUserId(userId) } returns
            StoredPasswordCredential(
                userId = userId,
                passwordHash = rawHash,
                createdAt = now,
                updatedAt = now,
            )

        val result = service.verifyForUser(userId, "CorrectPass!".toCharArray())

        assertTrue(result)
    }

    @Test
    fun `verifyForUser - 잘못된 비밀번호 입력 시 false 반환`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val rawHash = service.hash("CorrectPass!".toCharArray())
        val now = Instant.now()
        every { repo.findByUserId(userId) } returns
            StoredPasswordCredential(
                userId = userId,
                passwordHash = rawHash,
                createdAt = now,
                updatedAt = now,
            )

        val result = service.verifyForUser(userId, "WrongPass!".toCharArray())

        assertFalse(result)
    }

    @Test
    fun `verifyForUser - row 없음 시 dummy verify 수행 후 false 반환 (timing attack 방어)`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        // dummy verify 는 DUMMY_HASH 와 비교하므로 실제 verify 결과는 항상 false
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        every { repo.findByUserId(userId) } returns null

        val result = service.verifyForUser(userId, "AnyPass!".toCharArray())

        // false 반환 검증
        assertFalse(result)
        // findByUserId 는 반드시 1회 호출
        verify(exactly = 1) { repo.findByUserId(userId) }
    }

    @Test
    fun `verifyForUser - Argon2 verify 예외 발생 시 false 반환 (EC-07)`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        // 비정상 해시값으로 Argon2 라이브러리가 예외를 던지도록 유도
        every { repo.findByUserId(userId) } returns
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "not-a-valid-argon2-hash",
                createdAt = now,
                updatedAt = now,
            )

        val result = service.verifyForUser(userId, "AnyPass!".toCharArray())

        // 예외가 발생해도 false 반환 — 예외 전파 금지 (EC-07)
        assertFalse(result)
    }

    // rotate 테스트

    @Test
    fun `rotate - old 비밀번호 일치 시 store 호출 및 true 반환`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        val oldHash = service.hash("OldPass!".toCharArray())
        val newCredential =
            StoredPasswordCredential(
                userId = userId,
                passwordHash = "\$argon2id\$newhash",
                createdAt = now,
                updatedAt = now,
            )
        every { repo.findByUserId(userId) } returns
            StoredPasswordCredential(
                userId = userId,
                passwordHash = oldHash,
                createdAt = now,
                updatedAt = now,
            )
        every { repo.save(any()) } returns newCredential

        val result = service.rotate(userId, "OldPass!".toCharArray(), "NewPass!".toCharArray())

        assertTrue(result)
        verify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `rotate - old 비밀번호 불일치 시 false 반환 및 Repository save 미호출`() {
        val repo = mockk<StoredPasswordCredentialRepository>()
        val service = LocalCredentialService(repo)
        val userId = UUID.randomUUID()
        val now = Instant.now()
        val oldHash = service.hash("OldPass!".toCharArray())
        every { repo.findByUserId(userId) } returns
            StoredPasswordCredential(
                userId = userId,
                passwordHash = oldHash,
                createdAt = now,
                updatedAt = now,
            )

        val result = service.rotate(userId, "WrongOld!".toCharArray(), "NewPass!".toCharArray())

        assertFalse(result)
        // save 가 호출되지 않아야 한다 (DB 변경 없음)
        verify(exactly = 0) { repo.save(any()) }
    }
}
