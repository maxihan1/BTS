// cross-BC 타임라인 조회 포트 계약 검증 — TimelineLookupPort·TimelineItemView·TimelineItemPage

package com.bts.shared.timeline

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * cross-BC 타임라인 조회 포트 계약 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [TimelineItemView] VO 필드 구성 — 필수/선택 필드, nullable 보존.
 * - [TimelineLookupPort.listTimelineItemsByProject] default 구현이 빈 [TimelineItemPage] 를 반환함(fail-safe).
 * - adapter 가 미등록된 환경에서 빈 페이지를 반환해 간트 차트가 안전하게 표시된다.
 */
class TimelineLookupPortTest {
    // ── TimelineItemView 필드 계약 ───────────────────────────────────────────

    @Test
    fun `TimelineItemView 는 필수 필드를 모두 보존한다`() {
        val assigneeId = UUID.randomUUID()
        val view =
            TimelineItemView(
                key = "PROJ-1",
                summary = "로그인 버그 수정",
                issueType = "BUG",
                currentStateKey = "in-progress",
                assigneeId = assigneeId,
                startDate = LocalDate.of(2026, 6, 1),
                dueDate = LocalDate.of(2026, 6, 30),
                epicKey = "PROJ-100",
            )

        assertThat(view.key).isEqualTo("PROJ-1")
        assertThat(view.summary).isEqualTo("로그인 버그 수정")
        assertThat(view.issueType).isEqualTo("BUG")
        assertThat(view.currentStateKey).isEqualTo("in-progress")
        assertThat(view.assigneeId).isEqualTo(assigneeId)
        assertThat(view.startDate).isEqualTo(LocalDate.of(2026, 6, 1))
        assertThat(view.dueDate).isEqualTo(LocalDate.of(2026, 6, 30))
        assertThat(view.epicKey).isEqualTo("PROJ-100")
    }

    @Test
    fun `TimelineItemView 는 assigneeId 가 null 일 수 있다`() {
        val view =
            TimelineItemView(
                key = "PROJ-2",
                summary = "미배정 이슈",
                issueType = "TASK",
                currentStateKey = "open",
                assigneeId = null,
                startDate = null,
                dueDate = null,
                epicKey = null,
            )

        assertThat(view.assigneeId).isNull()
    }

    @Test
    fun `TimelineItemView 는 날짜 필드(startDate·dueDate)가 독립적으로 null 일 수 있다`() {
        // 날짜 0개(둘 다 null): 기간 미설정 이슈.
        // 날짜 1개(한쪽만): 시작일만 지정하거나 마감일만 지정한 이슈.
        // 날짜 2개: 전체 기간이 확정된 이슈.
        val startOnly =
            TimelineItemView(
                key = "PROJ-3",
                summary = "시작일만 있음",
                issueType = "STORY",
                currentStateKey = "open",
                assigneeId = null,
                startDate = LocalDate.of(2026, 7, 1),
                dueDate = null,
                epicKey = null,
            )

        val dueOnly =
            TimelineItemView(
                key = "PROJ-4",
                summary = "마감일만 있음",
                issueType = "STORY",
                currentStateKey = "open",
                assigneeId = null,
                startDate = null,
                dueDate = LocalDate.of(2026, 7, 31),
                epicKey = null,
            )

        assertThat(startOnly.startDate).isNotNull()
        assertThat(startOnly.dueDate).isNull()
        assertThat(dueOnly.startDate).isNull()
        assertThat(dueOnly.dueDate).isNotNull()
    }

    @Test
    fun `TimelineItemView 는 epicKey 가 null 일 수 있다`() {
        val view =
            TimelineItemView(
                key = "PROJ-5",
                summary = "에픽 없는 이슈",
                issueType = "BUG",
                currentStateKey = "open",
                assigneeId = null,
                startDate = null,
                dueDate = null,
                epicKey = null,
            )

        assertThat(view.epicKey).isNull()
    }

    // ── TimelineLookupPort fail-safe default ─────────────────────────────────

    @Test
    fun `TimelineLookupPort default 구현은 빈 TimelineItemPage 를 반환한다`() {
        // adapter 가 미등록된 환경에서 빈 페이지를 반환해 간트 차트가 안전하게 표시된다.
        val port = object : TimelineLookupPort {}
        val result = port.listTimelineItemsByProject("PROJ", UUID.randomUUID())

        assertThat(result.items).isEmpty()
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `TimelineLookupPort default 는 projectKey 나 viewerUserId 가 달라도 빈 페이지를 반환한다`() {
        val port = object : TimelineLookupPort {}

        assertThat(port.listTimelineItemsByProject("OTHER", UUID.randomUUID()).items).isEmpty()
        assertThat(port.listTimelineItemsByProject("ATLAS", UUID.randomUUID()).items).isEmpty()
    }

    // ── TimelineItemPage 계약 ─────────────────────────────────────────────────

    @Test
    fun `TimelineItemPage 는 items 목록과 truncated 플래그를 보존한다`() {
        val item =
            TimelineItemView(
                key = "PROJ-10",
                summary = "기간이 있는 이슈",
                issueType = "TASK",
                currentStateKey = "in-progress",
                assigneeId = null,
                startDate = LocalDate.of(2026, 6, 1),
                dueDate = LocalDate.of(2026, 6, 15),
                epicKey = null,
            )
        val page = TimelineItemPage(items = listOf(item), truncated = true)

        assertThat(page.items).containsExactly(item)
        assertThat(page.truncated).isTrue()
    }

    @Test
    fun `TimelineItemPage truncated=false 는 LIMIT 미초과 정상 조회를 나타낸다`() {
        val page = TimelineItemPage(items = emptyList(), truncated = false)

        assertThat(page.items).isEmpty()
        assertThat(page.truncated).isFalse()
    }
}
