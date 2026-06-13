// TrustedDeviceToken 단위 테스트 — rawToken hex 64자·해시 64자·재해시 결정성·토큰별 해시 상이 검증 (FR-MF-05 Task 2)

package com.atlas.bts.identity.mfa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

/**
 * [TrustedDeviceToken] 단위 테스트.
 *
 * 신뢰 디바이스 식별용 불투명 토큰의 생성/해시 규약을 검증한다. RefreshToken 선례와 동일한
 * hex 인코딩(32바이트 → 64자 소문자 hex)을 강제하고, DB에는 `SHA-256(rawToken)` hex 64자만
 * 저장한다(평문 비영속).
 *
 * ## 검증 시나리오
 * - rawToken 은 hex 64자(32바이트), hash 는 hex 64자(SHA-256)
 * - 동일 rawToken 재해시 시 동일 hash (결정성)
 * - 서로 다른 토큰은 서로 다른 hash (난수성/충돌 회피)
 */
class TrustedDeviceTokenTest {
    // 소문자 hex 64자 패턴.
    private val hex64 = Regex("^[0-9a-f]{64}$")

    @Test
    fun `generate는 hex 64자 rawToken과 hex 64자 hash를 반환한다`() {
        val token = TrustedDeviceToken.generate()

        assertThat(token.rawToken).matches { hex64.matches(it) }
        assertThat(token.hash).matches { hex64.matches(it) }
    }

    @Test
    fun `동일한 rawToken을 다시 해시하면 동일한 hash가 나온다`() {
        val token = TrustedDeviceToken.generate()

        val rehash = TrustedDeviceToken.hash(token.rawToken)

        assertThat(rehash).isEqualTo(token.hash)
    }

    @RepeatedTest(5)
    fun `서로 다른 토큰은 서로 다른 rawToken과 hash를 가진다`() {
        val first = TrustedDeviceToken.generate()
        val second = TrustedDeviceToken.generate()

        assertThat(first.rawToken).isNotEqualTo(second.rawToken)
        assertThat(first.hash).isNotEqualTo(second.hash)
    }
}
