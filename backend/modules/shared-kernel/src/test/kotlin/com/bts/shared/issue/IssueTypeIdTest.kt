// IssueTypeId VO 단위 테스트 — 양수 정상/0 거부/음수 거부/큰 값 정상

package com.bts.shared.issue

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [IssueTypeId] VO 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 테스트 케이스.
 * - 양수는 정상 생성된다.
 * - 0은 [IllegalArgumentException]을 던진다.
 * - 음수는 [IllegalArgumentException]을 던진다.
 * - Long.MAX_VALUE 같은 큰 값도 정상 생성된다.
 */
class IssueTypeIdTest {

    @Test
    fun `정상 — 양수 값으로 IssueTypeId 를 생성한다`() {
        val id = IssueTypeId(1L)

        assertThat(id.value).isEqualTo(1L)
    }

    @Test
    fun `거부 — 0 은 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { IssueTypeId(0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("0")
    }

    @Test
    fun `거부 — 음수는 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { IssueTypeId(-1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("-1")
    }

    @Test
    fun `정상 — Long 최대값도 정상 생성된다`() {
        val id = IssueTypeId(Long.MAX_VALUE)

        assertThat(id.value).isEqualTo(Long.MAX_VALUE)
    }
}
