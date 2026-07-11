// SlashCommandHandlers 단위 테스트 — 서브커맨드별 포트 호출 인자·렌더 결과 검증 (FR-SL-04 Task 6)

package com.bts.slack.command

import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportPort
import com.bts.shared.issue.IssueImportResult
import com.bts.shared.issue.IssueUnfurlPort
import com.bts.shared.issue.IssueUnfurlView
import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.SlashIssueSearchPort
import com.bts.shared.search.SlashSearchOutcome
import com.bts.shared.search.SlashSearchQuery
import com.bts.slack.message.SlackBlockKitRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

/**
 * [SlashCommandHandlers] 단위 테스트 (FR-SL-04 Task 6).
 *
 * cross-BC 포트([IssueUnfurlPort]/[SlashIssueSearchPort]/[IssueImportPort])는 mockk로 대체하고,
 * 렌더 결과 검증을 위해 [SlackBlockKitRenderer]는 실 인스턴스를 주입한다 — 핸들러가 렌더러에
 * 넘긴 인자가 올바른지를 실제 Block Kit JSON 문자열로 확인하기 위해서다.
 */
class SlashCommandHandlersTest {
    private val issueUnfurlPort = mockk<IssueUnfurlPort>()
    private val slashIssueSearchPort = mockk<SlashIssueSearchPort>()
    private val issueImportPort = mockk<IssueImportPort>()
    private val renderer = SlackBlockKitRenderer(ATLAS_BASE_URL, ObjectMapper())

    private val handlers =
        SlashCommandHandlers(
            issueUnfurlPort = issueUnfurlPort,
            slashIssueSearchPort = slashIssueSearchPort,
            issueImportPort = issueImportPort,
            renderer = renderer,
            atlasBaseUrl = ATLAS_BASE_URL,
        )

    private val viewerUserId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @Test
    fun `Help — 도움말 블록을 그대로 반환한다`() {
        val result = handlers.handle(SlashCommand.Help, viewerUserId)

        assertThat(result.toString()).isEqualTo(renderer.renderHelp().toString())
    }

    @Test
    fun `View — 포트가 카드를 반환하면 정확한 이슈키-viewerUserId로 조회 후 카드 블록을 렌더한다`() {
        val view = IssueUnfurlView("PROJ-1", "로그인 버그", "진행 중", "높음", "홍길동")
        every { issueUnfurlPort.getVisibleIssueCard("PROJ-1", viewerUserId) } returns view

        val result = handlers.handle(SlashCommand.View("PROJ-1"), viewerUserId)

        verify(exactly = 1) { issueUnfurlPort.getVisibleIssueCard("PROJ-1", viewerUserId) }
        assertThat(result.toString()).isEqualTo(renderer.renderIssueCard(view).toString())
    }

    @Test
    fun `View — 포트가 null이면 찾을 수 없거나 권한 없음 오류 블록을 렌더한다`() {
        every { issueUnfurlPort.getVisibleIssueCard("PROJ-404", viewerUserId) } returns null

        val result = handlers.handle(SlashCommand.View("PROJ-404"), viewerUserId)

        verify(exactly = 1) { issueUnfurlPort.getVisibleIssueCard("PROJ-404", viewerUserId) }
        assertThat(result.toString()).contains("찾을 수 없")
        assertThat(result.toString()).contains("권한")
    }

    @Test
    fun `Search — 정확한 SlashSearchQuery를 전달하고 결과가 있으면 목록 블록을 렌더한다`() {
        val expectedQuery =
            SlashSearchQuery(
                rawAql = "status=open",
                projectKey = "PROJ",
                viewerUserId = viewerUserId,
                page = 0,
                size = 10,
            )
        val hit =
            IssueSearchHit(
                key = "PROJ-1",
                summary = "로그인 버그",
                typeKey = "bug",
                currentStateKey = "open",
                assigneeId = null,
                priority = 3,
                priorityName = "Medium",
                projectKey = "PROJ",
                updatedAt = Instant.parse("2026-07-01T00:00:00Z"),
            )
        val page = IssueSearchPage(items = listOf(hit), total = 1, page = 0, size = 10)
        every { slashIssueSearchPort.search(expectedQuery) } returns SlashSearchOutcome.Success(page)

        val result = handlers.handle(SlashCommand.Search("PROJ", "status=open"), viewerUserId)

        verify(exactly = 1) { slashIssueSearchPort.search(expectedQuery) }
        assertThat(result.toString()).isEqualTo(renderer.renderSearchResults(page.items, page.total).toString())
    }

    @Test
    fun `Search — 빈 page이면 결과 없음 블록을 렌더한다`() {
        val emptyPage = IssueSearchPage.empty(0, 10)
        every { slashIssueSearchPort.search(any()) } returns SlashSearchOutcome.Success(emptyPage)

        val result = handlers.handle(SlashCommand.Search("PROJ", "status=open"), viewerUserId)

        assertThat(result.toString()).contains("결과 없음")
    }

    @Test
    fun `Search — 문법 오류면 검색 문법 오류 안내 블록을 렌더한다`() {
        every { slashIssueSearchPort.search(any()) } returns SlashSearchOutcome.SyntaxError("예상치 못한 토큰")

        val result = handlers.handle(SlashCommand.Search("PROJ", "status="), viewerUserId)

        assertThat(result.toString()).contains("검색 문법 오류")
        assertThat(result.toString()).contains("예상치 못한 토큰")
    }

    @Test
    fun `Create — 정확한 IssueImportCommand를 전달하고 성공하면 생성 완료 블록을 렌더한다`() {
        val expectedCmd =
            IssueImportCommand(
                projectKey = "PROJ",
                requesterUserId = viewerUserId,
                summary = "로그인 버튼 동작 안 함",
            )
        every { issueImportPort.importIssue(expectedCmd) } returns IssueImportResult.success("PROJ-42")

        val result = handlers.handle(SlashCommand.Create("PROJ", "로그인 버튼 동작 안 함"), viewerUserId)

        verify(exactly = 1) { issueImportPort.importIssue(expectedCmd) }
        assertThat(result.toString()).contains("PROJ-42")
        assertThat(result.toString()).contains("만들었습니다")
        assertThat(result.toString()).contains("$ATLAS_BASE_URL/issues/PROJ-42")
    }

    @ParameterizedTest(name = "Create — 실패 reasonCode={0}은 한국어 오류 블록을 렌더한다")
    @MethodSource("failureReasonCodes")
    fun `Create 실패 reasonCode별 오류 메시지`(reasonCode: String) {
        every { issueImportPort.importIssue(any()) } returns IssueImportResult.failure(reasonCode)

        val result = handlers.handle(SlashCommand.Create("PROJ", "제목"), viewerUserId)

        val text = result.toString()
        assertThat(text).doesNotContain(reasonCode)
        assertThat(text).matches(".*[가-힣]+.*")
    }

    @Test
    fun `UsageError — reason을 그대로 오류 블록에 담는다`() {
        val result = handlers.handle(SlashCommand.UsageError("사용법: /atlas view <이슈키>"), viewerUserId)

        assertThat(result.toString()).isEqualTo(renderer.renderError("사용법: /atlas view <이슈키>").toString())
    }

    private companion object {
        const val ATLAS_BASE_URL = "https://atlas.example.com"

        @JvmStatic
        fun failureReasonCodes(): Stream<Arguments> =
            Stream.of(
                Arguments.of(IssueImportResult.FORBIDDEN),
                Arguments.of(IssueImportResult.NOT_FOUND),
                Arguments.of(IssueImportResult.WORKFLOW_NOT_CONFIGURED),
                Arguments.of(IssueImportResult.VALIDATION),
                Arguments.of(IssueImportResult.TYPE_NOT_FOUND),
                Arguments.of(IssueImportResult.ADAPTER_UNAVAILABLE),
                Arguments.of(IssueImportResult.UNKNOWN),
            )
    }
}
