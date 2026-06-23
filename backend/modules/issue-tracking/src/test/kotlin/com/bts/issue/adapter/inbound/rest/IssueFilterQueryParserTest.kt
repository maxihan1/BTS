// IssueFilterQueryParser 단위 테스트 — 쿼리 파라미터 파싱 및 400 오류 검증

package com.bts.issue.adapter.inbound.rest

import com.bts.shared.board.BoardCardFilter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * [IssueFilterQueryParser] 단위 테스트.
 *
 * 파싱 케이스.
 * - P-1. status 값 전달 → statusKeys 설정
 * - P-2. assignee UUID 값 전달 → assigneeIds 설정
 * - P-3. assignee="unassigned" 센티널 → includeUnassigned=true
 * - P-4. label 문자열 그대로 → labels 설정
 * - P-5. component UUID 전달 → componentIds 설정
 * - P-6. blank/공백 값 무시
 * - P-7. 모두 비면 BoardCardFilter.EMPTY 반환
 * - P-8. 잘못된 UUID(assignee) → ResponseStatusException 400
 * - P-9. 잘못된 UUID(component) → ResponseStatusException 400
 */
class IssueFilterQueryParserTest {
    private val assigneeUuid = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val componentUuid = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")

    // ── P-1: status ────────────────────────────────────────────────────────────

    @Test
    fun `status 값 전달하면 statusKeys 에 설정된다`() {
        val result =
            IssueFilterQueryParser.parse(
                status = listOf("open", "in_progress"),
                assignee = emptyList(),
                label = emptyList(),
                component = emptyList(),
            )

        assertThat(result.statusKeys).containsExactlyInAnyOrder("open", "in_progress")
        assertThat(result.assigneeIds).isEmpty()
        assertThat(result.labels).isEmpty()
        assertThat(result.componentIds).isEmpty()
        assertThat(result.includeUnassigned).isFalse()
    }

    // ── P-2: assignee UUID ──────────────────────────────────────────────────────

    @Test
    fun `assignee UUID 값 전달하면 assigneeIds 에 설정된다`() {
        val result =
            IssueFilterQueryParser.parse(
                status = emptyList(),
                assignee = listOf(assigneeUuid.toString()),
                label = emptyList(),
                component = emptyList(),
            )

        assertThat(result.assigneeIds).containsExactly(assigneeUuid)
        assertThat(result.includeUnassigned).isFalse()
    }

    // ── P-3: assignee=unassigned 센티널 ─────────────────────────────────────────

    @Test
    fun `assignee=unassigned 센티널이면 includeUnassigned=true 이다`() {
        val result =
            IssueFilterQueryParser.parse(
                status = emptyList(),
                assignee = listOf("unassigned"),
                label = emptyList(),
                component = emptyList(),
            )

        assertThat(result.includeUnassigned).isTrue()
        assertThat(result.assigneeIds).isEmpty()
    }

    @Test
    fun `assignee 에 UUID와 unassigned 혼합이면 둘 다 반영된다`() {
        val result =
            IssueFilterQueryParser.parse(
                status = emptyList(),
                assignee = listOf(assigneeUuid.toString(), "unassigned"),
                label = emptyList(),
                component = emptyList(),
            )

        assertThat(result.assigneeIds).containsExactly(assigneeUuid)
        assertThat(result.includeUnassigned).isTrue()
    }

    // ── P-4: label 문자열 ───────────────────────────────────────────────────────

    @Test
    fun `label 문자열은 그대로 labels 에 설정된다`() {
        val result =
            IssueFilterQueryParser.parse(
                status = emptyList(),
                assignee = emptyList(),
                label = listOf("bug", "urgent"),
                component = emptyList(),
            )

        assertThat(result.labels).containsExactlyInAnyOrder("bug", "urgent")
    }

    // ── P-5: component UUID ─────────────────────────────────────────────────────

    @Test
    fun `component UUID 전달하면 componentIds 에 설정된다`() {
        val result =
            IssueFilterQueryParser.parse(
                status = emptyList(),
                assignee = emptyList(),
                label = emptyList(),
                component = listOf(componentUuid.toString()),
            )

        assertThat(result.componentIds).containsExactly(componentUuid)
    }

    // ── P-6: blank/공백 무시 ────────────────────────────────────────────────────

    @Test
    fun `공백 값은 무시된다`() {
        val result =
            IssueFilterQueryParser.parse(
                status = listOf("  ", "open"),
                assignee = listOf("  "),
                label = listOf("  ", "bug"),
                component = listOf("  "),
            )

        assertThat(result.statusKeys).containsExactly("open")
        assertThat(result.assigneeIds).isEmpty()
        assertThat(result.includeUnassigned).isFalse()
        assertThat(result.labels).containsExactly("bug")
        assertThat(result.componentIds).isEmpty()
    }

    // ── P-7: 모두 비면 EMPTY ────────────────────────────────────────────────────

    @Test
    fun `모든 파라미터가 비면 BoardCardFilter-EMPTY 를 반환한다`() {
        val result =
            IssueFilterQueryParser.parse(
                status = emptyList(),
                assignee = emptyList(),
                label = emptyList(),
                component = emptyList(),
            )

        assertThat(result).isEqualTo(BoardCardFilter.EMPTY)
    }

    @Test
    fun `공백 값만 있으면 BoardCardFilter-EMPTY 를 반환한다`() {
        val result =
            IssueFilterQueryParser.parse(
                status = listOf("  "),
                assignee = listOf("  "),
                label = listOf("  "),
                component = listOf("  "),
            )

        assertThat(result).isEqualTo(BoardCardFilter.EMPTY)
    }

    // ── P-8: 잘못된 UUID(assignee) → 400 ──────────────────────────────────────

    @Test
    fun `assignee 에 잘못된 UUID 이면 ResponseStatusException 400 을 던진다`() {
        assertThatThrownBy {
            IssueFilterQueryParser.parse(
                status = emptyList(),
                assignee = listOf("not-a-uuid"),
                label = emptyList(),
                component = emptyList(),
            )
        }
            .isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                val rse = ex as ResponseStatusException
                assertThat(rse.statusCode.value()).isEqualTo(400)
                assertThat(rse.message).contains("assignee")
                assertThat(rse.message).contains("not-a-uuid")
            })
    }

    // ── P-9: 잘못된 UUID(component) → 400 ─────────────────────────────────────

    @Test
    fun `component 에 잘못된 UUID 이면 ResponseStatusException 400 을 던진다`() {
        assertThatThrownBy {
            IssueFilterQueryParser.parse(
                status = emptyList(),
                assignee = emptyList(),
                label = emptyList(),
                component = listOf("bad-component-id"),
            )
        }
            .isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                val rse = ex as ResponseStatusException
                assertThat(rse.statusCode.value()).isEqualTo(400)
                assertThat(rse.message).contains("component")
                assertThat(rse.message).contains("bad-component-id")
            })
    }
}
