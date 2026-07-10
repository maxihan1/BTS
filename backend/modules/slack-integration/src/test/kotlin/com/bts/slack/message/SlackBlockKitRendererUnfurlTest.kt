// SlackBlockKitRenderer.renderUnfurlCard 단위 테스트 — unfurl 카드 렌더 검증 (FR-SL-03 Task 8)

package com.bts.slack.message

import com.bts.shared.issue.IssueUnfurlView
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SlackBlockKitRendererUnfurlTest {
    private val objectMapper = ObjectMapper()
    private val renderer = SlackBlockKitRenderer("https://atlas.example.com", objectMapper)

    private val view =
        IssueUnfurlView(
            issueKey = "PROJ-123",
            summary = "로그인 버튼이 동작하지 않음",
            statusLabel = "진행 중",
            priorityLabel = "높음",
            assigneeDisplayName = "홍길동",
        )

    @Test
    fun `키+제목 링크·상태·우선순위·담당자를 담은 카드를 렌더한다`() {
        val card = renderer.renderUnfurlCard(view)

        val text = card.toString()
        assertThat(text).contains("https://atlas.example.com/issues/PROJ-123")
        assertThat(text).contains("PROJ-123")
        assertThat(text).contains("로그인 버튼이 동작하지 않음")
        assertThat(text).contains("진행 중")
        assertThat(text).contains("높음")
        assertThat(text).contains("홍길동")
    }

    @Test
    fun `담당자가 없으면 미지정으로 폴백한다`() {
        val unassigned = view.copy(assigneeDisplayName = null)

        val card = renderer.renderUnfurlCard(unassigned)

        assertThat(card.toString()).contains("미지정")
    }

    @Test
    fun `액션 버튼(block_actions)을 포함하지 않는다`() {
        val card = renderer.renderUnfurlCard(view)

        assertThat(card.toString()).doesNotContain("\"type\":\"actions\"")
        assertThat(card.toString()).doesNotContain("\"type\":\"button\"")
    }

    @Test
    fun `카드는 blocks 배열을 담은 유효 JSON 이다`() {
        val card = renderer.renderUnfurlCard(view)

        assertThat(card.has("blocks")).isTrue()
        assertThat(card.get("blocks").isArray).isTrue()
    }
}
