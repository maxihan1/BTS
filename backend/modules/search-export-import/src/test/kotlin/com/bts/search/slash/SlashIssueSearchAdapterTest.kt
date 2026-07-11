// SlashIssueSearchAdapter 단위 테스트 — AQL 파싱 위임 + 문법오류 결과타입 변환 검증 (FR-SL-04 Task 2)

package com.bts.search.slash

import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import com.bts.shared.search.SlashSearchOutcome
import com.bts.shared.search.SlashSearchQuery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [SlashIssueSearchAdapter] 단위 테스트.
 *
 * [IssueSearchPort]를 MockK로 대체해 어댑터의 파싱·위임·오류변환 책임만 검증한다.
 * 실제 AQL 렉서/파서([com.bts.search.aql.AqlLexer]/[com.bts.search.aql.AqlParser])는 실동작한다.
 *
 * ### 검증 케이스
 * - a. 유효 AQL → [IssueSearchPort.search] 위임 결과를 [SlashSearchOutcome.Success]로 반환.
 *   전달된 [IssueSearchQuery] 필드(projectKey/viewerUserId/page/size)가 커맨드 그대로인지 검증.
 * - b. 파서 문법 오류(이중 연산자) → 예외 전파 없이 [SlashSearchOutcome.SyntaxError] 반환.
 * - c. 렉서 오류(닫히지 않은 따옴표) → 예외 전파 없이 [SlashSearchOutcome.SyntaxError] 반환.
 *   ([com.bts.search.aql.AqlLexException]은 [com.bts.search.aql.AqlSyntaxException]과 별개
 *   RuntimeException 계층이므로 함께 catch하지 않으면 포트 계약(예외 미전파)이 깨진다 — SearchController
 *   B1 회귀와 동일한 함정.)
 */
class SlashIssueSearchAdapterTest {
    private val issueSearchPort: IssueSearchPort = mockk()
    private val adapter = SlashIssueSearchAdapter(issueSearchPort)

    private val viewerUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    // ── a. 유효 AQL → Success 위임 + 커맨드 필드 그대로 전달 ────────────────────

    @Test
    fun `a - 유효 AQL 파싱 성공 시 IssueSearchPort 위임 결과를 Success 로 반환한다`() {
        val fixedPage =
            IssueSearchPage(
                items = listOf(sampleHit()),
                total = 1L,
                page = 0,
                size = 10,
            )
        val querySlot = slot<IssueSearchQuery>()
        every { issueSearchPort.search(capture(querySlot)) } returns fixedPage

        val result =
            adapter.search(
                SlashSearchQuery(
                    rawAql = "status = open",
                    projectKey = "PROJ",
                    viewerUserId = viewerUserId,
                    page = 0,
                    size = 10,
                ),
            )

        assertThat(result).isEqualTo(SlashSearchOutcome.Success(fixedPage))
        assertThat(querySlot.captured.projectKey).isEqualTo("PROJ")
        assertThat(querySlot.captured.viewerUserId).isEqualTo(viewerUserId)
        assertThat(querySlot.captured.page).isEqualTo(0)
        assertThat(querySlot.captured.size).isEqualTo(10)
        verify(exactly = 1) { issueSearchPort.search(any()) }
    }

    // ── b. 파서 문법 오류 → SyntaxError, 예외 미전파 ────────────────────────────

    @Test
    fun `b - 파서 문법 오류 AQL 은 예외 전파 없이 SyntaxError 로 변환된다`() {
        val result =
            adapter.search(
                SlashSearchQuery(
                    rawAql = "status = = open",
                    projectKey = "PROJ",
                    viewerUserId = viewerUserId,
                    page = 0,
                    size = 10,
                ),
            )

        assertThat(result).isInstanceOf(SlashSearchOutcome.SyntaxError::class.java)
        assertThat((result as SlashSearchOutcome.SyntaxError).reason).isNotBlank()
        verify(exactly = 0) { issueSearchPort.search(any()) }
    }

    // ── c. 렉서 오류(닫히지 않은 따옴표) → SyntaxError, 예외 미전파 ──────────────

    @Test
    fun `c - 렉서 오류(닫히지 않은 따옴표) AQL 도 예외 전파 없이 SyntaxError 로 변환된다`() {
        val result =
            adapter.search(
                SlashSearchQuery(
                    rawAql = """summary ~ "abc""",
                    projectKey = "PROJ",
                    viewerUserId = viewerUserId,
                    page = 0,
                    size = 10,
                ),
            )

        assertThat(result).isInstanceOf(SlashSearchOutcome.SyntaxError::class.java)
        assertThat((result as SlashSearchOutcome.SyntaxError).reason).isNotBlank()
        verify(exactly = 0) { issueSearchPort.search(any()) }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun sampleHit(): IssueSearchHit =
        IssueSearchHit(
            key = "PROJ-1",
            summary = "테스트 이슈",
            typeKey = "bug",
            currentStateKey = "open",
            assigneeId = null,
            priority = 2,
            priorityName = "High",
            projectKey = "PROJ",
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
}
