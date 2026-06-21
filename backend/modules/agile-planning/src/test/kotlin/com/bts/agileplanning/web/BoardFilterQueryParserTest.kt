// BoardFilterQueryParser 순수 단위 테스트 — 쿼리 파라미터 파싱 + 400 에러 경로

package com.bts.agileplanning.web

import com.bts.shared.board.BoardCardFilter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * [BoardFilterQueryParser] 순수 단위 테스트 — Spring 컨텍스트 없음.
 *
 * 검증 시나리오.
 * - PARSE-1. assignee UUID → assigneeIds 에 포함
 * - PARSE-2. assignee "unassigned" → includeUnassigned=true
 * - PARSE-3. assignee UUID + "unassigned" 혼합 → 둘 다 반영
 * - PARSE-4. component UUID → componentIds 에 포함
 * - PARSE-5. label 문자열 → labels 에 포함
 * - PARSE-6. 모두 빈/blank → BoardCardFilter.EMPTY (EC2)
 * - PARSE-7. blank 값은 무시되고 유효한 값만 포함
 * - ERR-1. 비-UUID assignee → ResponseStatusException 400
 * - ERR-2. 비-UUID component → ResponseStatusException 400
 * - ERR-3. "UNASSIGNED" 대문자 → 비-UUID 로 간주하여 400
 */
class BoardFilterQueryParserTest {
    // ── PARSE-1. assignee UUID ────────────────────────────────────────────────

    @Test
    fun `PARSE-1 assignee UUID 값은 assigneeIds 에 포함된다`() {
        val uuid = UUID.randomUUID()

        val filter = BoardFilterQueryParser.parse(
            assignee = listOf(uuid.toString()),
            label = emptyList(),
            component = emptyList(),
        )

        assertThat(filter.assigneeIds).containsExactly(uuid)
        assertThat(filter.includeUnassigned).isFalse()
        assertThat(filter.labels).isEmpty()
        assertThat(filter.componentIds).isEmpty()
    }

    // ── PARSE-2. assignee "unassigned" 센티널 ────────────────────────────────

    @Test
    fun `PARSE-2 assignee 에 "unassigned" 가 있으면 includeUnassigned=true`() {
        val filter = BoardFilterQueryParser.parse(
            assignee = listOf("unassigned"),
            label = emptyList(),
            component = emptyList(),
        )

        assertThat(filter.includeUnassigned).isTrue()
        assertThat(filter.assigneeIds).isEmpty()
    }

    // ── PARSE-3. assignee UUID + "unassigned" 혼합 ───────────────────────────

    @Test
    fun `PARSE-3 assignee 에 UUID 와 "unassigned" 혼합이면 둘 다 반영된다`() {
        val uuid1 = UUID.randomUUID()
        val uuid2 = UUID.randomUUID()

        val filter = BoardFilterQueryParser.parse(
            assignee = listOf(uuid1.toString(), "unassigned", uuid2.toString()),
            label = emptyList(),
            component = emptyList(),
        )

        assertThat(filter.assigneeIds).containsExactlyInAnyOrder(uuid1, uuid2)
        assertThat(filter.includeUnassigned).isTrue()
    }

    // ── PARSE-4. component UUID ───────────────────────────────────────────────

    @Test
    fun `PARSE-4 component UUID 값은 componentIds 에 포함된다`() {
        val uuid = UUID.randomUUID()

        val filter = BoardFilterQueryParser.parse(
            assignee = emptyList(),
            label = emptyList(),
            component = listOf(uuid.toString()),
        )

        assertThat(filter.componentIds).containsExactly(uuid)
    }

    // ── PARSE-5. label 문자열 ─────────────────────────────────────────────────

    @Test
    fun `PARSE-5 label 문자열은 그대로 labels 에 포함된다`() {
        val filter = BoardFilterQueryParser.parse(
            assignee = emptyList(),
            label = listOf("bug", "enhancement"),
            component = emptyList(),
        )

        assertThat(filter.labels).containsExactly("bug", "enhancement")
    }

    // ── PARSE-6. 모두 빈/blank → EMPTY (EC2) ─────────────────────────────────

    @Test
    fun `PARSE-6 모든 파라미터가 비어 있으면 BoardCardFilter_EMPTY 를 반환한다 (EC2)`() {
        val filter = BoardFilterQueryParser.parse(
            assignee = emptyList(),
            label = emptyList(),
            component = emptyList(),
        )

        assertThat(filter).isEqualTo(BoardCardFilter.EMPTY)
        assertThat(filter.isEmpty()).isTrue()
    }

    // ── PARSE-7. blank 값 무시 ────────────────────────────────────────────────

    @Test
    fun `PARSE-7 blank 값은 무시되고 유효한 값만 포함된다`() {
        val uuid = UUID.randomUUID()

        val filter = BoardFilterQueryParser.parse(
            assignee = listOf("  ", uuid.toString(), ""),
            label = listOf("  ", "bug"),
            component = emptyList(),
        )

        assertThat(filter.assigneeIds).containsExactly(uuid)
        assertThat(filter.labels).containsExactly("bug")
    }

    // ── ERR-1. 비-UUID assignee → 400 ────────────────────────────────────────

    @Test
    fun `ERR-1 assignee 에 비-UUID 값이 있으면 ResponseStatusException 400 을 던진다`() {
        assertThatThrownBy {
            BoardFilterQueryParser.parse(
                assignee = listOf("not-a-uuid"),
                label = emptyList(),
                component = emptyList(),
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                val rse = ex as ResponseStatusException
                assertThat(rse.statusCode.value()).isEqualTo(HttpStatus.BAD_REQUEST.value())
            })
    }

    // ── ERR-2. 비-UUID component → 400 ───────────────────────────────────────

    @Test
    fun `ERR-2 component 에 비-UUID 값이 있으면 ResponseStatusException 400 을 던진다`() {
        assertThatThrownBy {
            BoardFilterQueryParser.parse(
                assignee = emptyList(),
                label = emptyList(),
                component = listOf("foo-bar"),
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                val rse = ex as ResponseStatusException
                assertThat(rse.statusCode.value()).isEqualTo(HttpStatus.BAD_REQUEST.value())
            })
    }

    // ── ERR-3. "UNASSIGNED" 대문자 → 400 (센티널은 소문자 "unassigned" 만) ─────

    @Test
    fun `ERR-3 대문자 "UNASSIGNED" 는 센티널이 아니라 비-UUID 로 간주하여 400 을 던진다`() {
        assertThatThrownBy {
            BoardFilterQueryParser.parse(
                assignee = listOf("UNASSIGNED"),
                label = emptyList(),
                component = emptyList(),
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                val rse = ex as ResponseStatusException
                assertThat(rse.statusCode.value()).isEqualTo(HttpStatus.BAD_REQUEST.value())
            })
    }
}
