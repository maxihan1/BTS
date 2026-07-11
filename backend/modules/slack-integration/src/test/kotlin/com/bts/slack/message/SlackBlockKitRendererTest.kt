// SlackBlockKitRenderer 단위 테스트 — 제목/이슈 링크·slash 명령 ephemeral Block Kit 렌더 검증
// (FR-SL-02 Task 6 / FR-SL-04 Task 5)

package com.bts.slack.message

import com.bts.shared.issue.IssueUnfurlView
import com.bts.shared.search.IssueSearchHit
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

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

    @Test
    fun `renderHelp 는 4종 서브커맨드 사용법을 담은 블록을 반환한다`() {
        val blocks = renderer.renderHelp()

        assertThat(blocks.isArray).isTrue()
        assertThat(blocks.size()).isEqualTo(1)
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("section")
        val text = blocks.toString()
        assertThat(text).contains("/atlas help")
        assertThat(text).contains("/atlas view")
        assertThat(text).contains("/atlas search")
        assertThat(text).contains("/atlas create")
    }

    @Test
    fun `renderHelp 는 계정연결 안내를 옵션으로 포함한다`() {
        val blocks = renderer.renderHelp(includeAccountLinkNotice = true)

        assertThat(blocks.size()).isEqualTo(2)
        assertThat(blocks.get(1).get("type").asText()).isEqualTo("context")
        assertThat(blocks.toString()).contains("https://atlas.example.com/settings/slack")
    }

    @Test
    fun `renderHelp 는 계정연결 안내를 기본값으로 포함하지 않는다`() {
        val blocks = renderer.renderHelp()

        assertThat(blocks.toString()).doesNotContain("/settings/slack")
    }

    @Test
    fun `renderIssueCard 는 키+제목·상태·우선순위·담당자 블록을 반환한다`() {
        val view =
            IssueUnfurlView(
                issueKey = "PROJ-123",
                summary = "로그인 버튼이 동작하지 않음",
                statusLabel = "진행 중",
                priorityLabel = "높음",
                assigneeDisplayName = "홍길동",
            )

        val blocks = renderer.renderIssueCard(view)

        assertThat(blocks.size()).isEqualTo(2)
        val text = blocks.toString()
        assertThat(text).contains("https://atlas.example.com/issues/PROJ-123")
        assertThat(text).contains("PROJ-123")
        assertThat(text).contains("로그인 버튼이 동작하지 않음")
        assertThat(text).contains("진행 중")
        assertThat(text).contains("높음")
        assertThat(text).contains("홍길동")
    }

    @Test
    fun `renderSearchResults 는 결과 목록과 N-total 컨텍스트를 반환한다`() {
        val hits =
            listOf(
                IssueSearchHit(
                    key = "PROJ-1",
                    summary = "첫 번째 이슈",
                    typeKey = "bug",
                    currentStateKey = "open",
                    assigneeId = null,
                    priority = 3,
                    priorityName = "Medium",
                    projectKey = "PROJ",
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
                IssueSearchHit(
                    key = "PROJ-2",
                    summary = "두 번째 이슈",
                    typeKey = "task",
                    currentStateKey = "in_progress",
                    assigneeId = UUID.randomUUID(),
                    priority = 2,
                    priorityName = "High",
                    projectKey = "PROJ",
                    updatedAt = Instant.parse("2026-01-02T00:00:00Z"),
                ),
            )

        val blocks = renderer.renderSearchResults(hits, total = 5L)

        assertThat(blocks.size()).isEqualTo(3)
        val text = blocks.toString()
        assertThat(text).contains("PROJ-1")
        assertThat(text).contains("첫 번째 이슈")
        assertThat(text).contains("open")
        assertThat(text).contains("PROJ-2")
        assertThat(text).contains("in_progress")
        assertThat(blocks.get(2).get("type").asText()).isEqualTo("context")
        assertThat(text).contains("2/5")
    }

    @Test
    fun `renderSearchResults 는 빈 목록이면 결과 없음 텍스트를 반환한다`() {
        val blocks = renderer.renderSearchResults(emptyList(), total = 0L)

        assertThat(blocks.size()).isEqualTo(1)
        assertThat(blocks.toString()).contains("결과 없음")
    }

    @Test
    fun `renderCreated 는 이슈 생성 안내와 링크를 반환한다`() {
        val blocks = renderer.renderCreated("PROJ-99", "https://atlas.example.com/issues/PROJ-99")

        assertThat(blocks.size()).isEqualTo(1)
        val text = blocks.toString()
        assertThat(text).contains("PROJ-99")
        assertThat(text).contains("https://atlas.example.com/issues/PROJ-99")
        assertThat(text).contains("이슈를 만들었습니다")
    }

    @Test
    fun `renderError 는 단순 안내 텍스트 블록을 반환한다`() {
        val blocks = renderer.renderError("사용법: /atlas view <이슈키>")

        assertThat(blocks.size()).isEqualTo(1)
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("section")
        assertThat(blocks.toString()).contains("사용법: /atlas view <이슈키>")
    }
}
