// ExportService 단위 테스트 — IssueSearchPort MockK, 경계값/페이지순회/파일명/디스패치 검증

package com.bts.search.export

import com.bts.search.aql.AqlSyntaxException
import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [ExportService] 단위 테스트.
 *
 * [IssueSearchPort]는 MockK로 격리한다.
 * [Clock]은 고정 인스턴스로 파일명 timestamp를 결정적으로 검증한다.
 *
 * 검증 항목.
 * - (1) query → AqlParser → [IssueSearchQuery] 조립 (viewerUserId, sort 반영)
 * - (2) count-first: total > MAX_ROWS → [ExportLimitExceededException] 즉시, search 호출 1회만
 * - (3) 경계 쌍: total=10000 → 통과(100페이지 순회), total=10001 → 거부
 * - (4) total=250, PAGE_SIZE=100 → 3페이지 순회, search 호출 3회
 * - (5) format CSV/XLSX 디스패치 → ContentType + 매직 바이트 검증
 * - (6) 파일명 = {projectKey}-issues-{yyyyMMdd-HHmmss}.{ext} (Clock 결정성)
 * - (7) AQL 문법오류 → [AqlSyntaxException] 전파, search 미호출
 */
class ExportServiceTest {
    private val searchPort: IssueSearchPort = mockk()

    /** UTC 기준 2024-03-15T10:30:45Z 고정 Clock. */
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-03-15T10:30:45Z"), ZoneOffset.UTC)

    private lateinit var service: ExportService

    private val testProjectKey = "TEST"
    private val testViewerUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    /** 파서가 성공적으로 처리하는 단순 AQL 쿼리. */
    private val validQuery = "status = open"

    /**
     * ORDER BY 절을 포함한 AQL 쿼리. sort 반영 검증에 사용.
     * AQL SORTABLE_FIELDS는 snake_case(`updated_at`)를 사용한다.
     */
    private val queryWithSort = "status = open ORDER BY updated_at DESC"

    /** 괄호 불균형 — AqlSyntaxException을 유발하는 문법 오류 쿼리. */
    private val malformedQuery = "("

    /** 전체 9컬럼. 각 테스트에서 반복 생성을 피하기 위해 한 번만 생성한다. */
    private val allColumns = ExportColumn.entries.toList()

    @BeforeEach
    fun setUp() {
        service = ExportService(searchPort, fixedClock)
    }

    // ── (1) query → AqlParser → IssueSearchQuery ────────────────────────────────

    @Test
    fun `query is parsed and viewerUserId is forwarded to IssueSearchQuery`() {
        val querySlot = slot<IssueSearchQuery>()
        every { searchPort.search(capture(querySlot)) } returns emptyPage(0)

        exportCsv()

        val captured = querySlot.captured
        assertThat(captured.viewerUserId).isEqualTo(testViewerUserId)
        assertThat(captured.projectKey).isEqualTo(testProjectKey)
    }

    @Test
    fun `ORDER BY clause is parsed and sort is reflected in IssueSearchQuery`() {
        val querySlot = slot<IssueSearchQuery>()
        every { searchPort.search(capture(querySlot)) } returns emptyPage(0)

        exportCsv(query = queryWithSort)

        assertThat(querySlot.captured.sort).isNotEmpty()
    }

    // ── (2) count-first: total > MAX_ROWS → ExportLimitExceededException, 1 call ──

    @Test
    fun `total exceeding MAX_ROWS throws ExportLimitExceededException immediately`() {
        val overLimit = ExportService.MAX_ROWS + 1L
        every { searchPort.search(any()) } returns pageWithTotal(overLimit)

        val ex =
            assertThrows<ExportLimitExceededException> {
                exportCsv()
            }

        assertThat(ex.resultCount).isEqualTo(overLimit)
        assertThat(ex.limit).isEqualTo(ExportService.MAX_ROWS)
    }

    @Test
    fun `search is called exactly once when total exceeds MAX_ROWS (no further traversal)`() {
        every { searchPort.search(any()) } returns pageWithTotal(ExportService.MAX_ROWS + 1L)

        assertThrows<ExportLimitExceededException> { exportCsv() }

        verify(exactly = 1) { searchPort.search(any()) }
    }

    // ── (3) 경계 쌍: total=10000 통과, total=10001 거부 ───────────────────────────

    @Test
    fun `total=10000 is exactly at boundary and must not throw`() {
        every { searchPort.search(any()) } returns pageWithTotal(ExportService.MAX_ROWS)

        assertDoesNotThrow { exportCsv() }
    }

    @Test
    fun `total=10000 at boundary traverses 100 pages`() {
        every { searchPort.search(any()) } returns pageWithTotal(ExportService.MAX_ROWS)

        exportCsv()

        // ceil(10000 / 100) = 100 페이지
        verify(exactly = 100) { searchPort.search(any()) }
    }

    @Test
    fun `total=10001 exceeds boundary and must throw`() {
        every { searchPort.search(any()) } returns pageWithTotal(ExportService.MAX_ROWS + 1L)

        assertThrows<ExportLimitExceededException> { exportCsv() }
    }

    // ── (4) total=250, PAGE_SIZE=100 → 3페이지 순회, search 3회 ─────────────────

    @Test
    fun `total=250 with PAGE_SIZE=100 traverses exactly 3 pages`() {
        val hits = sampleHits(3)
        val pageOf = { n: Int -> IssueSearchPage(hits, 250L, n, ExportService.PAGE_SIZE) }
        every { searchPort.search(match { it.page == 0 }) } returns pageOf(0)
        every { searchPort.search(match { it.page == 1 }) } returns pageOf(1)
        every { searchPort.search(match { it.page == 2 }) } returns pageOf(2)

        exportCsv()

        verify(exactly = 3) { searchPort.search(any()) }
    }

    // ── (5) format CSV/XLSX 디스패치 ────────────────────────────────────────────

    @Test
    fun `CSV format returns correct contentType`() {
        every { searchPort.search(any()) } returns emptyPage(0)

        val result = exportCsv()

        assertThat(result.contentType).isEqualTo(ExportFormat.CSV.contentType)
    }

    @Test
    fun `CSV bytes start with UTF-8 BOM (EF BB BF)`() {
        every { searchPort.search(any()) } returns emptyPage(0)

        val result = exportCsv()

        assertThat(result.bytes).hasSizeGreaterThanOrEqualTo(3)
        assertThat(result.bytes[0]).isEqualTo(0xEF.toByte())
        assertThat(result.bytes[1]).isEqualTo(0xBB.toByte())
        assertThat(result.bytes[2]).isEqualTo(0xBF.toByte())
    }

    @Test
    fun `XLSX format returns correct contentType`() {
        every { searchPort.search(any()) } returns emptyPage(0)

        val result = exportXlsx()

        assertThat(result.contentType).isEqualTo(ExportFormat.XLSX.contentType)
    }

    @Test
    fun `XLSX bytes start with ZIP magic bytes (PK = 0x50 0x4B)`() {
        every { searchPort.search(any()) } returns emptyPage(0)

        val result = exportXlsx()

        assertThat(result.bytes).hasSizeGreaterThanOrEqualTo(2)
        // XLSX는 OOXML(ZIP) — 첫 2바이트가 PK 시그니처
        assertThat(result.bytes[0]).isEqualTo(0x50.toByte())
        assertThat(result.bytes[1]).isEqualTo(0x4B.toByte())
    }

    // ── (6) 파일명 = {projectKey}-issues-{yyyyMMdd-HHmmss}.{ext} ─────────────────

    @Test
    fun `CSV filename follows projectKey-issues-yyyyMMdd-HHmmss_csv pattern`() {
        every { searchPort.search(any()) } returns emptyPage(0)

        val result = exportCsv()

        // fixedClock = 2024-03-15T10:30:45Z → "20240315-103045"
        assertThat(result.filename).isEqualTo("TEST-issues-20240315-103045.csv")
    }

    @Test
    fun `XLSX filename follows projectKey-issues-yyyyMMdd-HHmmss_xlsx pattern`() {
        every { searchPort.search(any()) } returns emptyPage(0)

        val result = exportXlsx()

        assertThat(result.filename).isEqualTo("TEST-issues-20240315-103045.xlsx")
    }

    // ── (7) AQL 문법오류 → AqlSyntaxException 전파, search 미호출 ─────────────────

    @Test
    fun `malformed AQL propagates AqlSyntaxException without calling searchPort`() {
        assertThrows<AqlSyntaxException> { exportCsv(query = malformedQuery) }

        verify(exactly = 0) { searchPort.search(any()) }
    }

    // ── private helpers ──────────────────────────────────────────────────────────

    /** CSV 형식으로 기본 파라미터로 Export를 수행한다. 반복되는 장문 호출을 짧게 축약한다. */
    private fun exportCsv(query: String = validQuery): ExportResult =
        service.export(testProjectKey, query, ExportFormat.CSV, allColumns, testViewerUserId)

    /** XLSX 형식으로 기본 파라미터로 Export를 수행한다. 반복되는 장문 호출을 짧게 축약한다. */
    private fun exportXlsx(query: String = validQuery): ExportResult =
        service.export(testProjectKey, query, ExportFormat.XLSX, allColumns, testViewerUserId)

    private fun emptyPage(page: Int): IssueSearchPage =
        IssueSearchPage(items = emptyList(), total = 0L, page = page, size = ExportService.PAGE_SIZE)

    private fun pageWithTotal(total: Long): IssueSearchPage =
        IssueSearchPage(items = emptyList(), total = total, page = 0, size = ExportService.PAGE_SIZE)

    private fun sampleHits(count: Int): List<IssueSearchHit> =
        (1..count).map { i ->
            IssueSearchHit(
                key = "TEST-$i",
                summary = "Test Issue $i",
                typeKey = "task",
                currentStateKey = "open",
                assigneeId = null,
                priority = 3,
                priorityName = "Medium",
                projectKey = testProjectKey,
                updatedAt = Instant.parse("2024-03-15T10:30:45Z"),
            )
        }
}
