// StoredPasswordCredential data class 단위 테스트 — 필드 노출 / toString 마스킹 / equals userId 기반

package com.atlas.bts.identity.credential

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StoredPasswordCredentialTest {

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val now = Instant.parse("2026-05-20T00:00:00Z")

    @Test
    fun `인스턴스 생성 후 5개 필드가 정상 노출된다`() {
        val hash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$salt\$hash"
        val credential = StoredPasswordCredential(
            userId = userId,
            passwordHash = hash,
            algoVersion = "argon2id-v1",
            createdAt = now,
            updatedAt = now,
        )

        assertEquals(userId, credential.userId)
        assertEquals(hash, credential.passwordHash)
        assertEquals("argon2id-v1", credential.algoVersion)
        assertEquals(now, credential.createdAt)
        assertEquals(now, credential.updatedAt)
    }

    @Test
    fun `toString 은 passwordHash 를 *** 로 마스킹한다`() {
        val originalHash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$salt\$hash"
        val credential = StoredPasswordCredential(
            userId = userId,
            passwordHash = originalHash,
            algoVersion = "argon2id-v1",
            createdAt = now,
            updatedAt = now,
        )

        val str = credential.toString()

        assertTrue(str.contains("passwordHash=***"), "toString 에 'passwordHash=***' 가 포함되어야 한다. 실제: $str")
        assertFalse(str.contains(originalHash), "toString 에 실제 해시 값이 노출되면 안 된다. 실제: $str")
    }

    @Test
    fun `equals 는 userId 기반이므로 passwordHash 와 algoVersion 이 달라도 같은 userId 면 equal 이다`() {
        val credentialA = StoredPasswordCredential(
            userId = userId,
            passwordHash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$saltA\$hashA",
            algoVersion = "argon2id-v1",
            createdAt = now,
            updatedAt = now,
        )
        val credentialB = StoredPasswordCredential(
            userId = userId,
            passwordHash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$saltB\$hashB",
            algoVersion = "argon2id-v2",
            createdAt = now.plusSeconds(3600),
            updatedAt = now.plusSeconds(7200),
        )
        val credentialOther = StoredPasswordCredential(
            userId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            passwordHash = "\$argon2id\$v=19\$m=65536,t=3,p=4\$saltA\$hashA",
            algoVersion = "argon2id-v1",
            createdAt = now,
            updatedAt = now,
        )

        assertEquals(credentialA, credentialB, "같은 userId 면 다른 필드가 달라도 equal 이어야 한다")
        assertEquals(credentialA.hashCode(), credentialB.hashCode(), "같은 userId 면 hashCode 도 같아야 한다")
        assertNotEquals(credentialA, credentialOther, "다른 userId 면 equal 이 아니어야 한다")
    }
}
