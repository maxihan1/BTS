// SlackBlockKitRenderer 단위 테스트 — 제목/이슈 링크 Block Kit 렌더 검증 (FR-SL-02 Task 6)

package com.bts.slack.message

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SlackBlockKitRendererTest {
    private val objectMapper = ObjectMapper()
    private val renderer = SlackBlockKitRenderer("https://atlas.example.com", objectMapper)

    @Test
    fun `제목과 이슈 링크를 담은 Block Kit 을 렌더한다`() {
        val rendered = renderer.render("PROJ-123 에서 멘션되었습니다", "PROJ-123")

        // 접근성 폴백 text 에 제목 포함
        assertThat(rendered.text).contains("PROJ-123 에서 멘션되었습니다")

        // blocks 는 유효한 JSON 배열이고 이슈 링크 URL 과 제목을 담는다
        val node = objectMapper.readTree(rendered.blocks)
        assertThat(node.isArray).isTrue()
        assertThat(rendered.blocks).contains("https://atlas.example.com/issues/PROJ-123")
        assertThat(rendered.blocks).contains("PROJ-123 에서 멘션되었습니다")
    }

    @Test
    fun `issueKey 가 null 이면 링크 없이 제목만 렌더한다`() {
        val rendered = renderer.render("스프린트가 시작되었습니다", null)

        assertThat(rendered.text).contains("스프린트가 시작되었습니다")
        assertThat(rendered.blocks).doesNotContain("/issues/")
        assertThat(objectMapper.readTree(rendered.blocks).isArray).isTrue()
    }

    @Test
    fun `base-url 끝 슬래시를 정규화해 이중 슬래시를 만들지 않는다`() {
        val trailingSlashRenderer = SlackBlockKitRenderer("https://atlas.example.com/", objectMapper)

        val rendered = trailingSlashRenderer.render("제목", "PROJ-1")

        assertThat(rendered.blocks).contains("https://atlas.example.com/issues/PROJ-1")
        assertThat(rendered.blocks).doesNotContain("com//issues")
    }
}
