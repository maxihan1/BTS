// AtlasIssueUrlParser 단위 테스트 — Atlas 이슈 URL → 이슈키 추출 검증 (FR-SL-03 Task 2)

package com.bts.slack.unfurl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AtlasIssueUrlParserTest {
    private val parser = AtlasIssueUrlParser("https://atlas.example.com")

    @Test
    fun `base-url + issues 경로 + 이슈키 형태면 이슈키를 추출한다`() {
        val issueKey = parser.parse("https://atlas.example.com/issues/PROJ-123")

        assertThat(issueKey).isEqualTo("PROJ-123")
    }

    @Test
    fun `trailing slash 가 있어도 이슈키를 추출한다`() {
        val issueKey = parser.parse("https://atlas.example.com/issues/PROJ-123/")

        assertThat(issueKey).isEqualTo("PROJ-123")
    }

    @Test
    fun `쿼리 파라미터가 붙어도 이슈키를 추출한다`() {
        val issueKey = parser.parse("https://atlas.example.com/issues/PROJ-123?tab=comments")

        assertThat(issueKey).isEqualTo("PROJ-123")
    }

    @Test
    fun `fragment 가 붙어도 이슈키를 추출한다`() {
        val issueKey = parser.parse("https://atlas.example.com/issues/PROJ-123#comment-1")

        assertThat(issueKey).isEqualTo("PROJ-123")
    }

    @Test
    fun `다른 도메인이면 null 을 반환한다`() {
        val issueKey = parser.parse("https://evil.example.com/issues/PROJ-123")

        assertThat(issueKey).isNull()
    }

    @Test
    fun `issues 경로가 아니면 null 을 반환한다`() {
        val issueKey = parser.parse("https://atlas.example.com/projects/PROJ")

        assertThat(issueKey).isNull()
    }

    @Test
    fun `base-url 끝에 슬래시가 있어도 정규화해 매칭한다`() {
        val trailingSlashParser = AtlasIssueUrlParser("https://atlas.example.com/")

        val issueKey = trailingSlashParser.parse("https://atlas.example.com/issues/PROJ-123")

        assertThat(issueKey).isEqualTo("PROJ-123")
    }

    @Test
    fun `이슈키 형식이 아니면 null 을 반환한다`() {
        val issueKey = parser.parse("https://atlas.example.com/issues/not-a-valid-key")

        assertThat(issueKey).isNull()
    }

    @Test
    fun `다중 URL 리스트 중 매칭된 (url, issueKey) 쌍만 반환한다`() {
        val links =
            parser.parseAll(
                listOf(
                    "https://atlas.example.com/issues/PROJ-123",
                    "https://evil.example.com/issues/PROJ-999",
                    "https://atlas.example.com/projects/PROJ",
                    "https://atlas.example.com/issues/OPS-7?tab=comments",
                ),
            )

        assertThat(links)
            .containsExactly(
                AtlasIssueLink("https://atlas.example.com/issues/PROJ-123", "PROJ-123"),
                AtlasIssueLink("https://atlas.example.com/issues/OPS-7?tab=comments", "OPS-7"),
            )
    }
}
