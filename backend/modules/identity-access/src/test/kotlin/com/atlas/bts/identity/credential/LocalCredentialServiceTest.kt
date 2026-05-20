// LocalCredentialService 단위 테스트 — Argon2id 해싱/검증 3개 케이스

package com.atlas.bts.identity.credential

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
