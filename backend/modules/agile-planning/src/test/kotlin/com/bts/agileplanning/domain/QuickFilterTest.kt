// QuickFilter 도메인 name 검증 단위테스트

package com.bts.agileplanning.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * QuickFilter 도메인 단위테스트.
 *
 * 외부 의존(DB, Spring, mock) 없이 순수 도메인 로직만 검증한다.
 *
 * 검증 시나리오.
 * - 정상 생성: id/boardId/name/query 필드가 그대로 보관된다.
 * - name 불변식: 공백/빈 문자열은 IllegalArgumentException.
 * - name 경계값: 50자는 통과, 51자는 IllegalArgumentException.
 */
class QuickFilterTest {
    private val boardId = UUID.randomUUID()
    private val filterId = UUID.randomUUID()

    private fun quickFilter(
        name: String = "내 버그",
        query: String = "assignee=me&label=bug",
    ) = QuickFilter(
        id = filterId,
        boardId = boardId,
        name = name,
        query = query,
    )

    @Nested
    inner class Creation {
        @Test
        fun `유효한 필드로 생성하면 값이 그대로 보관된다`() {
            val filter = quickFilter(name = "내 버그", query = "assignee=me&label=bug")
            assertThat(filter.id).isEqualTo(filterId)
            assertThat(filter.boardId).isEqualTo(boardId)
            assertThat(filter.name).isEqualTo("내 버그")
            assertThat(filter.query).isEqualTo("assignee=me&label=bug")
        }
    }

    @Nested
    inner class NameInvariant {
        @Test
        fun `name이 빈 문자열이면 IllegalArgumentException이 발생한다`() {
            assertThatThrownBy { quickFilter(name = "") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `name이 공백 문자열이면 IllegalArgumentException이 발생한다`() {
            assertThatThrownBy { quickFilter(name = "   ") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `name이 50자면 정상 생성된다`() {
            val name = "가".repeat(50)
            val filter = quickFilter(name = name)
            assertThat(filter.name).hasSize(50)
        }

        @Test
        fun `name이 51자면 IllegalArgumentException이 발생한다`() {
            val name = "가".repeat(51)
            assertThatThrownBy { quickFilter(name = name) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
