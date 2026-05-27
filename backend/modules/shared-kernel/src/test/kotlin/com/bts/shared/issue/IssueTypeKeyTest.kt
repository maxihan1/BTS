// IssueTypeKey VO 단위 테스트 — 유효 슬러그 통과/위반 케이스 거부

package com.bts.shared.issue

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [IssueTypeKey] VO 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 테스트 케이스.
 * - 유효한 슬러그(`bug`, `subtask`, `task-item`, `a1`)는 정상 생성된다.
 * - 대문자 시작, 숫자 시작, 빈 문자열, 1자, 31자 초과, 언더스코어 포함은
 *   [IllegalArgumentException]을 던진다.
 */
class IssueTypeKeyTest {

    @ParameterizedTest(name = "정상 — `{0}` 은 유효한 IssueTypeKey 이다")
    @ValueSource(strings = ["bug", "subtask", "task-item", "a1", "ab", "a1b2c3-d4e5"])
    fun `정상 — 유효한 슬러그는 IssueTypeKey 를 생성한다`(value: String) {
        val key = IssueTypeKey(value)

        assertThat(key.value).isEqualTo(value)
    }

    @Test
    fun `정상 — 정확히 2자 길이는 허용된다`() {
        val key = IssueTypeKey("ab")

        assertThat(key.value).isEqualTo("ab")
    }

    @Test
    fun `정상 — 정확히 30자 길이는 허용된다`() {
        // a 로 시작 + 소문자 29자 = 총 30자
        val value = "a" + "b".repeat(29)
        val key = IssueTypeKey(value)

        assertThat(key.value).isEqualTo(value)
    }

    @Test
    fun `거부 — 대문자로 시작하는 값은 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { IssueTypeKey("Bug") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `거부 — 숫자로 시작하는 값은 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { IssueTypeKey("1bug") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `거부 — 빈 문자열은 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { IssueTypeKey("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `거부 — 1자는 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { IssueTypeKey("a") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `거부 — 31자 초과는 IllegalArgumentException 을 던진다`() {
        // a 로 시작 + 소문자 30자 = 총 31자
        val value = "a" + "b".repeat(30)
        assertThatThrownBy { IssueTypeKey(value) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `거부 — 언더스코어 포함 값은 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { IssueTypeKey("task_item") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
