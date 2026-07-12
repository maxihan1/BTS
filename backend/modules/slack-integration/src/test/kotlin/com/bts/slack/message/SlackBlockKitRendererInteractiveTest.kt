// SlackBlockKitRenderer.renderAssignmentActionsMessage 단위 테스트 — 상세보기/완료 버튼 인터랙션 블록 검증 (FR-SL-05 Task 7)

package com.bts.slack.message

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SlackBlockKitRendererInteractiveTest {
    private val objectMapper = ObjectMapper()
    private val renderer = SlackBlockKitRenderer("https://atlas.example.com", objectMapper)

    @Test
    fun `상세보기 url 버튼과 완료로 표시 버튼을 포함한다`() {
        val rendered = renderer.renderAssignmentActionsMessage("PROJ-1 이 할당되었습니다", "PROJ-1")

        val text = rendered.blocks
        assertThat(text).contains("\"type\":\"actions\"")
        assertThat(text).contains("\"type\":\"button\"")
        assertThat(text).contains("상세보기")
        assertThat(text).contains("완료로 표시")
    }

    @Test
    fun `상세보기 버튼의 url 은 설정 baseUrl 과 issueKey 로 조립된다`() {
        val rendered = renderer.renderAssignmentActionsMessage("PROJ-2 이 할당되었습니다", "PROJ-2")

        assertThat(rendered.blocks).contains("\"url\":\"https://atlas.example.com/issues/PROJ-2\"")
    }

    @Test
    fun `완료로 표시 버튼은 action_id=atlas_complete, value=issueKey 를 담는다`() {
        val rendered = renderer.renderAssignmentActionsMessage("PROJ-3 이 할당되었습니다", "PROJ-3")

        assertThat(rendered.blocks).contains("\"action_id\":\"atlas_complete\"")
        assertThat(rendered.blocks).contains("\"value\":\"PROJ-3\"")
    }

    @Test
    fun `제목과 이슈 링크 섹션도 기존 render 와 동일하게 포함한다`() {
        val rendered = renderer.renderAssignmentActionsMessage("PROJ-4 이 할당되었습니다", "PROJ-4")

        assertThat(rendered.text).contains("PROJ-4 이 할당되었습니다")
        assertThat(rendered.blocks).contains("https://atlas.example.com/issues/PROJ-4")
    }
}
