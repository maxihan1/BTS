// TimelineApplicationService 단위 테스트 — 권한 게이트·정렬·위임 RED 명세 (FR-TL-01 Task 4)

package com.bts.agileplanning.application

import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.timeline.TimelineItemPage
import com.bts.shared.timeline.TimelineItemView
import com.bts.shared.timeline.TimelineLookupPort
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * TimelineApplicationService 단위 테스트.
 *
 * cross-BC 포트([IssuePermissionResolver], [TimelineLookupPort])를 MockK 로 교체해
 * 권한 게이트·정렬·passthrough 로직을 독립 검증한다.
 * Spring 컨텍스트를 부팅하지 않는다.
 *
 * ### 검증 케이스
 * - S5: BROWSE 권한 없으면 403 을 던진다.
 * - FR4: startDate ASC NULLS LAST → dueDate ASC NULLS LAST → key ASC 정렬이 적용된다.
 * - S8: truncated 플래그가 그대로 전파된다.
 * - EC4: 이슈가 없으면 빈 목록을 반환한다 (vacuous 금지 — 빈 결과 단언).
 */
class TimelineApplicationServiceTest {
    private val actorId: UUID = UUID.randomUUID()
    private val projectKey = "PROJ"

    private val permissionResolver: IssuePermissionResolver = mockk()
    private val timelineLookupPort: TimelineLookupPort = mockk()

    private val sut = TimelineApplicationService(permissionResolver, timelineLookupPort)

    // ── helper ────────────────────────────────────────────────────────────────

    /**
     * 테스트용 [TimelineItemView] 생성 헬퍼.
     *
     * @param key 이슈 키.
     * @param startDate 시작일. null 이면 미설정.
     * @param dueDate 마감일. null 이면 미설정.
     */
    private fun item(
        key: String,
        startDate: LocalDate? = null,
        dueDate: LocalDate? = null,
    ): TimelineItemView =
        TimelineItemView(
            key = key,
            summary = "요약 $key",
            issueType = "TASK",
            currentStateKey = "open",
            assigneeId = null,
            startDate = startDate,
            dueDate = dueDate,
            epicKey = null,
        )

    /** permissionResolver 가 BROWSE 권한을 [result] 로 응답하도록 설정한다. */
    private fun stubBrowse(result: Boolean) {
        every {
            permissionResolver.hasPermission(
                actorId,
                IssuePermission.BROWSE,
                IssueScope.Project(projectKey),
            )
        } returns result
    }

    /** timelineLookupPort 가 [page] 를 반환하도록 설정한다. */
    private fun stubPage(page: TimelineItemPage) {
        every {
            timelineLookupPort.listTimelineItemsByProject(projectKey, actorId)
        } returns page
    }

    // ── S5: 권한 거부 → 403 ───────────────────────────────────────────────────

    @Test
    fun `S5 - BROWSE 권한이 없으면 403 ResponseStatusException 을 던진다`() {
        stubBrowse(false)

        assertThatThrownBy { sut.getTimeline(actorId, projectKey) }
            .isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                assertThat((ex as ResponseStatusException).statusCode)
                    .isEqualTo(HttpStatus.FORBIDDEN)
            })
    }

    // ── FR4: 정렬 ─────────────────────────────────────────────────────────────

    @Test
    fun `FR4 - startDate ASC NULLS LAST 1순위로 정렬된다`() {
        stubBrowse(true)
        val earlier = LocalDate.of(2026, 1, 1)
        val later = LocalDate.of(2026, 6, 1)
        stubPage(
            TimelineItemPage(
                items =
                    listOf(
                        item("PROJ-3", startDate = later),
                        item("PROJ-1", startDate = earlier),
                        // NULLS LAST — startDate null 은 뒤로
                        item("PROJ-2", startDate = null),
                    ),
                truncated = false,
            ),
        )

        val result = sut.getTimeline(actorId, projectKey)

        assertThat(result.items.map { it.key })
            .containsExactly("PROJ-1", "PROJ-3", "PROJ-2")
    }

    @Test
    fun `FR4 - startDate 같을 때 dueDate ASC NULLS LAST 2순위로 정렬된다`() {
        stubBrowse(true)
        val sameStart = LocalDate.of(2026, 3, 1)
        val earlyDue = LocalDate.of(2026, 4, 1)
        val lateDue = LocalDate.of(2026, 5, 1)
        stubPage(
            TimelineItemPage(
                items =
                    listOf(
                        item("PROJ-B", startDate = sameStart, dueDate = lateDue),
                        // NULLS LAST — dueDate null 은 뒤로
                        item("PROJ-C", startDate = sameStart, dueDate = null),
                        item("PROJ-A", startDate = sameStart, dueDate = earlyDue),
                    ),
                truncated = false,
            ),
        )

        val result = sut.getTimeline(actorId, projectKey)

        assertThat(result.items.map { it.key })
            .containsExactly("PROJ-A", "PROJ-B", "PROJ-C")
    }

    @Test
    fun `FR4 - startDate 와 dueDate 모두 같을 때 key ASC 3순위로 정렬된다`() {
        stubBrowse(true)
        val date = LocalDate.of(2026, 3, 1)
        stubPage(
            TimelineItemPage(
                items =
                    listOf(
                        item("PROJ-Z", startDate = date, dueDate = date),
                        item("PROJ-A", startDate = date, dueDate = date),
                        item("PROJ-M", startDate = date, dueDate = date),
                    ),
                truncated = false,
            ),
        )

        val result = sut.getTimeline(actorId, projectKey)

        assertThat(result.items.map { it.key })
            .containsExactly("PROJ-A", "PROJ-M", "PROJ-Z")
    }

    @Test
    fun `FR4 - startDate null 끼리는 dueDate 로 보조 정렬된다`() {
        stubBrowse(true)
        val earlyDue = LocalDate.of(2026, 2, 1)
        val lateDue = LocalDate.of(2026, 8, 1)
        stubPage(
            TimelineItemPage(
                items =
                    listOf(
                        item("PROJ-B", startDate = null, dueDate = lateDue),
                        item("PROJ-A", startDate = null, dueDate = earlyDue),
                    ),
                truncated = false,
            ),
        )

        val result = sut.getTimeline(actorId, projectKey)

        assertThat(result.items.map { it.key })
            .containsExactly("PROJ-A", "PROJ-B")
    }

    // ── S8: truncated passthrough ─────────────────────────────────────────────

    @Test
    fun `S8 - truncated=true 이면 결과에도 truncated=true 가 전파된다`() {
        stubBrowse(true)
        stubPage(
            TimelineItemPage(
                items = listOf(item("PROJ-1")),
                truncated = true,
            ),
        )

        val result = sut.getTimeline(actorId, projectKey)

        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `S8 - truncated=false 이면 결과에도 truncated=false 가 전파된다`() {
        stubBrowse(true)
        stubPage(
            TimelineItemPage(
                items = listOf(item("PROJ-1")),
                truncated = false,
            ),
        )

        val result = sut.getTimeline(actorId, projectKey)

        assertThat(result.truncated).isFalse()
    }

    // ── EC4: 빈 결과 ──────────────────────────────────────────────────────────

    @Test
    fun `EC4 - 이슈가 없으면 빈 목록을 반환하며 예외를 던지지 않는다`() {
        stubBrowse(true)
        stubPage(TimelineItemPage(items = emptyList(), truncated = false))

        val result = sut.getTimeline(actorId, projectKey)

        assertThat(result.items).isEmpty()
        assertThat(result.truncated).isFalse()
    }
}
