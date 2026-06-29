// XlsxExportWriter 단위 테스트 — POI round-trip 재읽기로 셀 값·타입·injection 방어 검증

package com.bts.search.export

import com.bts.shared.search.IssueSearchHit
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

/**
 * [XlsxExportWriter] 단위 테스트.
 *
 * Apache POI [XSSFWorkbook]으로 바이트 배열을 재읽어(round-trip) 실제 XLSX 내용을 검증한다.
 * 모든 셀은 STRING 타입이어야 하며, formula injection 방어가 적용되어야 한다.
 *
 * 검증 항목.
 * - 시트 1개, 헤더 행 = 선택 컬럼 라벨
 * - 데이터 셀 값이 [IssueSearchHit.extract] 결과와 일치
 * - formula injection 시작 문자(`=`, `+`, `-`, `@`, `\t`, `\r`) → [ExportCellSanitizer.sanitize] 호출로 `'` prefix
 * - 빈 결과 → 헤더 행만 존재(데이터 행 0)
 * - 한글 셀 정상 기록 및 round-trip
 * - 모든 데이터 셀이 [CellType.STRING] 타입
 */
class XlsxExportWriterTest {
    private val writer = XlsxExportWriter()

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

    /** write() 결과 바이트를 [XSSFWorkbook]으로 파싱해 반환하는 헬퍼. */
    private fun writeAndOpen(
        columns: List<ExportColumn>,
        rows: List<IssueSearchHit>,
    ): XSSFWorkbook {
        val out = ByteArrayOutputStream()
        writer.write(out, columns, rows)
        return XSSFWorkbook(out.toByteArray().inputStream())
    }

    // ── 시트 구조 ──────────────────────────────────────────────────────────────

    @Test
    fun `XLSX 파일에 시트가 정확히 1개 생성된다`() {
        val wb = writeAndOpen(ExportColumn.entries.toList(), listOf(sampleHit()))
        wb.use { assertThat(it.numberOfSheets).isEqualTo(1) }
    }

    @Test
    fun `헤더 행이 선택 컬럼의 라벨과 일치한다`() {
        val columns = listOf(ExportColumn.KEY, ExportColumn.SUMMARY, ExportColumn.STATUS)
        val wb = writeAndOpen(columns, listOf(sampleHit()))
        wb.use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val headerRow = sheet.getRow(0)
            assertThat(headerRow.lastCellNum.toInt()).isEqualTo(columns.size)
            columns.forEachIndexed { index, col ->
                assertThat(headerRow.getCell(index).stringCellValue).isEqualTo(col.headerLabel)
            }
        }
    }

    // ── 데이터 셀 값 ──────────────────────────────────────────────────────────

    @Test
    fun `데이터 행의 셀 값이 ExportColumn_extract 결과와 일치한다`() {
        val columns = ExportColumn.entries.toList()
        val hit = sampleHit()
        val wb = writeAndOpen(columns, listOf(hit))
        wb.use { workbook ->
            val sheet = workbook.getSheetAt(0)
            // 0행 = 헤더, 1행 = 데이터 첫 행
            val dataRow = sheet.getRow(1)
            columns.forEachIndexed { index, col ->
                val expected = ExportCellSanitizer.sanitize(col.extract(hit))
                assertThat(dataRow.getCell(index).stringCellValue).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `assigneeId가 null이면 해당 셀은 빈 문자열이다`() {
        val columns = listOf(ExportColumn.ASSIGNEE_ID)
        val hit = sampleHit(assigneeId = null)
        val wb = writeAndOpen(columns, listOf(hit))
        wb.use { workbook ->
            val cell = workbook.getSheetAt(0).getRow(1).getCell(0)
            assertThat(cell.stringCellValue).isEqualTo("")
        }
    }

    @Test
    fun `updatedAt 셀이 ISO-8601 UTC 문자열로 기록된다`() {
        val columns = listOf(ExportColumn.UPDATED_AT)
        val wb = writeAndOpen(columns, listOf(sampleHit()))
        wb.use { workbook ->
            val cell = workbook.getSheetAt(0).getRow(1).getCell(0)
            assertThat(cell.stringCellValue).isEqualTo("2024-03-15T10:30:00Z")
        }
    }

    // ── formula injection 방어 ────────────────────────────────────────────────

    @Test
    fun `등호로 시작하는 셀은 작은따옴표 prefix가 붙어 수식 실행이 차단된다`() {
        val hit = sampleHit(summary = "=SUM(A1)")
        val columns = listOf(ExportColumn.SUMMARY)
        val wb = writeAndOpen(columns, listOf(hit))
        wb.use { workbook ->
            val cell = workbook.getSheetAt(0).getRow(1).getCell(0)
            assertThat(cell.stringCellValue).isEqualTo("'=SUM(A1)")
        }
    }

    @Test
    fun `플러스_마이너스_앳_탭_CR로 시작하는 셀에 작은따옴표 prefix가 붙는다`() {
        val injectionCases =
            listOf(
                "+cmd",
                "-cmd",
                "@SUM(B1)",
                "\tcmd",
                "\rcmd",
            )
        val columns = listOf(ExportColumn.SUMMARY)
        injectionCases.forEach { cellValue ->
            val hit = sampleHit(summary = cellValue)
            val wb = writeAndOpen(columns, listOf(hit))
            wb.use { workbook ->
                val cell = workbook.getSheetAt(0).getRow(1).getCell(0)
                assertThat(cell.stringCellValue)
                    .describedAs("셀 값 '$cellValue'에 prefix가 붙어야 한다")
                    .startsWith("'")
            }
        }
    }

    @Test
    fun `일반 문자열 셀은 변경 없이 그대로 기록된다`() {
        val hit = sampleHit(summary = "일반 텍스트")
        val columns = listOf(ExportColumn.SUMMARY)
        val wb = writeAndOpen(columns, listOf(hit))
        wb.use { workbook ->
            val cell = workbook.getSheetAt(0).getRow(1).getCell(0)
            assertThat(cell.stringCellValue).isEqualTo("일반 텍스트")
        }
    }

    // ── 빈 결과 ───────────────────────────────────────────────────────────────

    @Test
    fun `빈 결과를 전달하면 헤더 행만 존재하고 데이터 행은 없다`() {
        val columns = ExportColumn.entries.toList()
        val wb = writeAndOpen(columns, emptyList())
        wb.use { workbook ->
            val sheet = workbook.getSheetAt(0)
            // 물리적 행 수 = 1(헤더만), 마지막 행 인덱스 = 0
            assertThat(sheet.physicalNumberOfRows).isEqualTo(1)
            assertThat(sheet.lastRowNum).isEqualTo(0)
        }
    }

    // ── 한글 ──────────────────────────────────────────────────────────────────

    @Test
    fun `한글 summary가 손실 없이 round-trip된다`() {
        val koreanSummary = "로그인 버튼 클릭 시 오류 발생"
        val hit = sampleHit(summary = koreanSummary)
        val columns = listOf(ExportColumn.SUMMARY)
        val wb = writeAndOpen(columns, listOf(hit))
        wb.use { workbook ->
            val cell = workbook.getSheetAt(0).getRow(1).getCell(0)
            assertThat(cell.stringCellValue).isEqualTo(koreanSummary)
        }
    }

    @Test
    fun `한글 이슈 키가 손실 없이 round-trip된다`() {
        val hit = sampleHit(key = "한글-프로젝트-1")
        val columns = listOf(ExportColumn.KEY)
        val wb = writeAndOpen(columns, listOf(hit))
        wb.use { workbook ->
            val cell = workbook.getSheetAt(0).getRow(1).getCell(0)
            assertThat(cell.stringCellValue).isEqualTo("한글-프로젝트-1")
        }
    }

    // ── 셀 타입 STRING ────────────────────────────────────────────────────────

    @Test
    fun `모든 데이터 셀이 STRING 타입이다`() {
        val columns = ExportColumn.entries.toList()
        val hit = sampleHit()
        val wb = writeAndOpen(columns, listOf(hit))
        wb.use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val dataRow = sheet.getRow(1)
            columns.indices.forEach { index ->
                val cell = dataRow.getCell(index)
                assertThat(cell.cellType)
                    .describedAs("컬럼 인덱스 $index 의 셀 타입은 STRING이어야 한다")
                    .isEqualTo(CellType.STRING)
            }
        }
    }

    @Test
    fun `다중 행이 있을 때 모든 데이터 행의 셀이 STRING 타입이다`() {
        val columns = listOf(ExportColumn.KEY, ExportColumn.PRIORITY)
        val rows =
            listOf(
                sampleHit(key = "PROJ-1"),
                sampleHit(key = "PROJ-2"),
                sampleHit(key = "PROJ-3"),
            )
        val wb = writeAndOpen(columns, rows)
        wb.use { workbook ->
            val sheet = workbook.getSheetAt(0)
            (1..3).forEach { rowIdx ->
                val row = sheet.getRow(rowIdx)
                columns.indices.forEach { colIdx ->
                    assertThat(row.getCell(colIdx).cellType)
                        .describedAs("행 $rowIdx, 컬럼 $colIdx 의 타입은 STRING이어야 한다")
                        .isEqualTo(CellType.STRING)
                }
            }
        }
    }
}
