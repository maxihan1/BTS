// IssueTypeId VO 단위 테스트 — 생성 정상, 음수/0 거부, equals/hashCode 검증
package com.bts.issue.type.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [IssueTypeId] VO 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 테스트 케이스.
 * - create — 양의 Long 으로 인스턴스 생성 시 value 필드가 입력값 그대로임을 검증.
 * - reject_zero — 0 으로 생성 시 [IllegalArgumentException] 을 던진다.
 * - reject_negative — 음수로 생성 시 [IllegalArgumentException] 을 던진다.
 * - equality — 같은 값을 가진 두 인스턴스는 equals true, hashCode 동일.
 */
class IssueTypeIdTest {
    @Test
    fun `create — 양의 Long 으로 IssueTypeId 를 생성하면 value 가 입력값 그대로다`() {
        val id = IssueTypeId(1L)

        assertThat(id.value as Long).isEqualTo(1L)
    }

    @Test
    fun `reject_zero — 0 으로 생성하면 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { IssueTypeId(0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `reject_negative — 음수로 생성하면 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { IssueTypeId(-1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `equality — 같은 값이면 equals true 이고 hashCode 가 동일하다`() {
        val a = IssueTypeId(42L)
        val b = IssueTypeId(42L)

        assertThat(a as Any).isEqualTo(b)
        assertThat(a.hashCode() as Int).isEqualTo(b.hashCode())
    }
}
