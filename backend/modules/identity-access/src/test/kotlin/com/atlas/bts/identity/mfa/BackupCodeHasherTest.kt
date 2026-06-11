// BackupCodeHasher 단위 테스트 — SHA-256 64자 hex·결정성·입력 정규화(대문자/하이픈 흡수) 검증 (FR-MF-02 Task 3)

package com.atlas.bts.identity.mfa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [BackupCodeHasher] 단위 테스트.
 *
 * MFA(다단계 인증) 1회용 백업 코드를 DB 에 평문 저장하지 않기 위해 SHA-256 단방향 해시로 변환한다.
 * 백업 코드는 비밀이지만 짧고 엔트로피가 충분하므로 패스워드용 Argon2 가 아닌 빠른 SHA-256 을 쓴다
 * (검증 시 사용자가 입력한 1개 코드를 해시해 저장된 해시 집합과 대조).
 *
 * ## 검증 시나리오
 * - 출력은 항상 64자 hex(SHA-256, 32바이트)
 * - 같은 입력은 같은 출력(결정적)
 * - 입력 정규화 — 대문자/소문자, 하이픈/공백 차이를 흡수해 같은 코드는 같은 해시
 * - 다른 코드는 다른 해시
 */
class BackupCodeHasherTest {
    private val hasher = BackupCodeHasher()

    @Test
    fun `해시는 64자 hex 문자열이다`() {
        val hash = hasher.hash("a3k9f-2m7qx")

        assertThat(hash).matches("^[0-9a-f]{64}$")
    }

    @Test
    fun `같은 입력은 같은 해시를 낸다 (결정적)`() {
        val first = hasher.hash("a3k9f-2m7qx")
        val second = hasher.hash("a3k9f-2m7qx")

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `대문자와 하이픈 제거 형식은 같은 해시로 정규화된다`() {
        val hyphenatedLower = hasher.hash("a3k9f-2m7qx")
        val upperNoHyphen = hasher.hash("A3K9F2M7QX")

        assertThat(hyphenatedLower).isEqualTo(upperNoHyphen)
    }

    @Test
    fun `대소문자 차이를 흡수한다`() {
        val lower = hasher.hash("a3k9f2m7qx")
        val upper = hasher.hash("A3K9F2M7QX")

        assertThat(lower).isEqualTo(upper)
    }

    @Test
    fun `주변 공백을 흡수한다`() {
        val withSpaces = hasher.hash("  A3K9F 2M7QX  ")
        val clean = hasher.hash("A3K9F2M7QX")

        assertThat(withSpaces).isEqualTo(clean)
    }

    @Test
    fun `다른 코드는 다른 해시를 낸다`() {
        val first = hasher.hash("A3K9F2M7QX")
        val second = hasher.hash("B4L8G3N6RY")

        assertThat(first).isNotEqualTo(second)
    }
}
