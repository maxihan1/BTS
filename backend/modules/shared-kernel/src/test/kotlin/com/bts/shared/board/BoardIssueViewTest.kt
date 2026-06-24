// BoardIssueView VO 단위 테스트 — rank 필드 존재·default null·명시 지정 검증

package com.bts.shared.board

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [BoardIssueView] 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [BoardIssueView.rank] 필드가 존재한다.
 * - rank 를 지정하지 않으면 기본값 null 이다.
 * - rank 를 명시 지정하면 그 값을 보유한다.
 */
class BoardIssueViewTest {
    private val dummyId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `rank 를 지정하지 않으면 기본값 null 이다`() {
        val view =
            BoardIssueView(
                key = "PROJ-1",
                summary = "테스트 이슈",
                currentStateKey = "TODO",
                assigneeId = null,
                priority = 0,
                version = 1L,
            )

        assertThat(view.rank).isNull()
    }

    @Test
    fun `rank 를 명시 지정하면 해당 값을 보유한다`() {
        val expectedRank = "0|hzzzzz:"

        val view =
            BoardIssueView(
                key = "PROJ-2",
                summary = "랭크 이슈",
                currentStateKey = "IN_PROGRESS",
                assigneeId = dummyId,
                priority = 1,
                version = 2L,
                rank = expectedRank,
            )

        assertThat(view.rank).isEqualTo(expectedRank)
    }

    @Test
    fun `epicKey 와 rank 모두 기본값 null 이다`() {
        val view =
            BoardIssueView(
                key = "PROJ-3",
                summary = "에픽+랭크 기본값 이슈",
                currentStateKey = "DONE",
                assigneeId = null,
                priority = 2,
                version = 3L,
            )

        assertThat(view.epicKey).isNull()
        assertThat(view.rank).isNull()
    }
}
