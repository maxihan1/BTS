// LockoutPolicy 기본값 검증 테스트

package com.atlas.bts.identity.provider.ldap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * LockoutPolicy 기본값 + LockoutScope 열거형 검증.
 * spec FR-3: maxAttempts=5, lockoutMinutes=15, scope=PER_USER_PER_PROVIDER.
 */
class LockoutPolicyDefaultsTest {
    @Test
    fun `LockoutPolicy 기본값 — maxAttempts 5`() {
        val policy = LockoutPolicy()
        assertThat(policy.maxAttempts).isEqualTo(5)
    }

    @Test
    fun `LockoutPolicy 기본값 — lockoutMinutes 15`() {
        val policy = LockoutPolicy()
        assertThat(policy.lockoutMinutes).isEqualTo(15)
    }

    @Test
    fun `LockoutPolicy 기본값 — scope PER_USER_PER_PROVIDER`() {
        val policy = LockoutPolicy()
        assertThat(policy.scope).isEqualTo(LockoutScope.PER_USER_PER_PROVIDER)
    }

    @Test
    fun `LockoutScope GLOBAL 값 존재`() {
        assertThat(LockoutScope.values()).contains(LockoutScope.GLOBAL)
    }

    @Test
    fun `LockoutPolicy 커스텀 값 — maxAttempts 3 lockoutMinutes 1`() {
        val policy = LockoutPolicy(maxAttempts = 3, lockoutMinutes = 1)
        assertThat(policy.maxAttempts).isEqualTo(3)
        assertThat(policy.lockoutMinutes).isEqualTo(1)
        assertThat(policy.scope).isEqualTo(LockoutScope.PER_USER_PER_PROVIDER)
    }
}
