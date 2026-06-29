// CsvExportWriter 단위 테스트 — UTF-8 BOM, RFC 4180 이스케이프, formula injection, 한글 round-trip

package com.bts.search.export

import com.bts.shared.search.IssueSearchHit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

/**
 * [CsvExportWriter] 단위 테스트.
 *
 * 검증 항목.
 * - (1) 선두 3바이트 UTF-8 BOM (EF BB BF)
 * - (2) 헤더 행 = 선택 컬럼 라벨
 * - (3) 쉼표/따옴표/LF 개행 포함 셀 RFC 4180 이스케이프 (`"..."`, 내부 `""`)
 * - (4) formula injection 셀 → [ExportCellSanitizer.sanitize] 호출 결과 (`'` prefix)
 * - (5) 빈 결과 → 헤더만
 * - (6) 한글 셀 UTF-8 round-trip
 * - (7) assigneeId null → 빈 셀
 */
class CsvExportWriterTest {
    private val writer = CsvExportWriter()
    private val fixedInstant = Instant.parse("2024-03-15T10:30:00Z")
    private val fixedUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun sampleHit(
        key: String = "PROJ-1",
        summary: String = "Test issue",
        assigneeId: UUID? = fixedUuid,
    ): IssueSearchHit =
        IssueSearchHit(
            key = key,
            summary = summary,
            typeKey = "bug",
            currentStateKey = "open",
            assigneeId = assigneeId,
            priority = 2,
            priorityName = "High",
            projectKey = "PROJ",
            updatedAt = fixedInstant,
        )

    private fun write(
        columns: List<ExportColumn> = ExportColumn.entries.toList(),
        rows: List<IssueSearchHit> = emptyList(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writer.write(out, columns, rows)
        return out.toByteArray()
    }

    /**
     * BOM(3바이트) 제거 후 UTF-8 디코딩, CRLF 분리.
     *
     * 매 행 끝의 CRLF 로 인해 split("\r\n") 결과 마지막 원소는 항상 빈 문자열 — 이것만 제거한다.
     * 실제 빈 데이터 행(예: assigneeId null)은 중간 빈 원소로 보존된다.
     * 셀에 LF 만 포함된 경우 (CR 없음) split("\r\n") 은 그 LF 에서 분리하지 않으므로
     * 일반적인 행 파싱에 사용 가능하다.
     */
    private fun parseLines(bytes: ByteArray): List<String> {
        val withoutBom = bytes.drop(3).toByteArray()
        val all = String(withoutBom, Charsets.UTF_8).split("\r\n")
        // 구현이 모든 행 뒤에 CRLF 를 쓰므로 마지막 원소는 항상 trailing empty — 1개만 제거
        return if (all.lastOrNull() == "") all.dropLast(1) else all
    }

    // ── (1) UTF-8 BOM ─────────────────────────────────────────────────────────

    @Test
    fun `출력의 첫 3바이트가 UTF-8 BOM이다(EF BB BF)`() {
        val bytes = write(rows = listOf(sampleHit()))
        assertThat(bytes[0]).isEqualTo(0xEF.toByte())
        assertThat(bytes[1]).isEqualTo(0xBB.toByte())
        assertThat(bytes[2]).isEqualTo(0xBF.toByte())
    }

    // ── (2) 헤더 행 ────────────────────────────────────────────────────────────

    @Test
    fun `헤더 행이 선택 컬럼의 라벨을 쉼표로 구분해 출력한다`() {
        val columns = listOf(ExportColumn.KEY, ExportColumn.SUMMARY, ExportColumn.STATUS)
        val bytes = write(columns = columns, rows = emptyList())
        val header = parseLines(bytes).first()
        assertThat(header).isEqualTo("Key,Summary,Status")
    }

    @Test
    fun `전체 9컬럼 헤더가 enum 표준 순서로 출력된다`() {
        val bytes = write(columns = ExportColumn.entries.toList(), rows = emptyList())
        val header = parseLines(bytes).first()
        val expected = ExportColumn.entries.joinToString(",") { it.headerLabel }
        assertThat(header).isEqualTo(expected)
    }

    // ── (3) RFC 4180 이스케이프 ────────────────────────────────────────────────

    @Test
    fun `셀에 쉼표가 포함되면 큰따옴표로 감싼다`() {
        val hit = sampleHit(summary = "value,with,commas")
        val bytes = write(columns = listOf(ExportColumn.SUMMARY), rows = listOf(hit))
        val dataRow = parseLines(bytes)[1]
        // 기대값: "value,with,commas"
        assertThat(dataRow).isEqualTo("\"value,with,commas\"")
    }

    @Test
    fun `셀에 큰따옴표가 포함되면 두 개로 이스케이프하고 큰따옴표로 감싼다`() {
        val hit = sampleHit(summary = "say \"hello\"")
        val bytes = write(columns = listOf(ExportColumn.SUMMARY), rows = listOf(hit))
        val dataRow = parseLines(bytes)[1]
        // 기대값: "say ""hello"""
        assertThat(dataRow).isEqualTo("\"say \"\"hello\"\"\"")
    }

    @Test
    fun `셀에 LF 개행이 포함되면 큰따옴표로 감싼다`() {
        val hit = sampleHit(summary = "line1\nline2")
        val bytes = write(columns = listOf(ExportColumn.SUMMARY), rows = listOf(hit))
        // parseLines 는 CRLF 로만 분리하므로 셀 내부 LF 는 그대로 보존된다.
        val dataRow = parseLines(bytes)[1]
        // 기대값: "line1\nline2" (LF 포함)
        assertThat(dataRow).isEqualTo("\"line1\nline2\"")
    }

    // ── (4) formula injection → ExportCellSanitizer 호출 ──────────────────────

    @Test
    fun `등호로 시작하는 셀은 ExportCellSanitizer 경유로 작은따옴표가 prefix된다`() {
        val hit = sampleHit(summary = "=SUM(A1)")
        val bytes = write(columns = listOf(ExportColumn.SUMMARY), rows = listOf(hit))
        val dataRow = parseLines(bytes)[1]
        // ExportCellSanitizer.sanitize("=SUM(A1)") == "'=SUM(A1)" 임을 출력에서 확인
        assertThat(dataRow).isEqualTo("'=SUM(A1)")
    }

    @Test
    fun `골뱅이로 시작하는 셀은 ExportCellSanitizer 경유로 작은따옴표가 prefix된다`() {
        val hit = sampleHit(summary = "@user")
        val bytes = write(columns = listOf(ExportColumn.SUMMARY), rows = listOf(hit))
        val dataRow = parseLines(bytes)[1]
        assertThat(dataRow).isEqualTo("'@user")
    }

    @Test
    fun `sanitize 후 RFC 4180 이스케이프가 적용된다(formula + 쉼표 복합 셀)`() {
        // "=val,ue" → sanitize → "'=val,ue" → RFC 4180 → "'=val,ue" 에 쉼표 있으므로 감쌈
        val hit = sampleHit(summary = "=val,ue")
        val bytes = write(columns = listOf(ExportColumn.SUMMARY), rows = listOf(hit))
        val dataRow = parseLines(bytes)[1]
        assertThat(dataRow).isEqualTo("\"'=val,ue\"")
    }

    // ── (5) 빈 결과 → 헤더만 ──────────────────────────────────────────────────

    @Test
    fun `행이 없으면 헤더 행만 출력된다`() {
        val bytes = write(rows = emptyList())
        val lines = parseLines(bytes)
        assertThat(lines).hasSize(1)
        val expectedHeader = ExportColumn.entries.joinToString(",") { it.headerLabel }
        assertThat(lines.first()).isEqualTo(expectedHeader)
    }

    // ── (6) 한글 UTF-8 round-trip ─────────────────────────────────────────────

    @Test
    fun `한글 셀 값이 UTF-8로 정확히 round-trip된다`() {
        val hit = sampleHit(summary = "한글 이슈 제목")
        val bytes = write(columns = listOf(ExportColumn.SUMMARY), rows = listOf(hit))
        val dataRow = parseLines(bytes)[1]
        assertThat(dataRow).isEqualTo("한글 이슈 제목")
    }

    @Test
    fun `한글이 포함된 key도 정상 출력된다`() {
        val hit = sampleHit(key = "프로젝트-1")
        val bytes = write(columns = listOf(ExportColumn.KEY), rows = listOf(hit))
        val dataRow = parseLines(bytes)[1]
        assertThat(dataRow).isEqualTo("프로젝트-1")
    }

    // ── (7) assigneeId null → 빈 셀 ───────────────────────────────────────────

    @Test
    fun `assigneeId가 null이면 해당 셀이 빈 문자열이다`() {
        val hit = sampleHit(assigneeId = null)
        val bytes = write(columns = listOf(ExportColumn.ASSIGNEE_ID), rows = listOf(hit))
        val dataRow = parseLines(bytes)[1]
        assertThat(dataRow).isEmpty()
    }

    // ── 복합 시나리오 ─────────────────────────────────────────────────────────

    @Test
    fun `여러 행이 각각 올바른 데이터로 출력된다`() {
        val hits =
            listOf(
                sampleHit(key = "PROJ-1", summary = "First issue"),
                sampleHit(key = "PROJ-2", summary = "Second issue"),
            )
        val bytes = write(columns = listOf(ExportColumn.KEY, ExportColumn.SUMMARY), rows = hits)
        val lines = parseLines(bytes)
        assertThat(lines).hasSize(3) // 헤더 + 데이터 2행
        assertThat(lines[0]).isEqualTo("Key,Summary")
        assertThat(lines[1]).isEqualTo("PROJ-1,First issue")
        assertThat(lines[2]).isEqualTo("PROJ-2,Second issue")
    }
}
