// Argon2Params.DUMMY_HASH 상수 — 존재 + 포맷 + timing attack 방어 verify 동작 검증

package com.atlas.bts.identity.credential

import de.mkammerer.argon2.Argon2Factory
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Argon2ParamsTest {
    @Test
    fun `DUMMY_HASH starts with argon2id prefix`() {
        assertTrue(Argon2Params.DUMMY_HASH.startsWith("\$argon2id\$"))
    }

    @Test
    fun `DUMMY_HASH verify with arbitrary plain returns false`() {
        val argon2 = Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)
        val result = argon2.verify(Argon2Params.DUMMY_HASH, "not-real-password".toCharArray())
        assertFalse(result)
    }
}
