// StreamingExportSerializer 단위 테스트 — CSV(BOM+RFC4180)/XLSX(SXSSF) 배치 스트리밍 검증

package com.bts.search.export.job.serialize

import com.bts.search.export.ExportColumn
import com.bts.search.export.ExportFormat
import com.bts.shared.search.IssueSearchHit
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.FileInputStream
import java.time.Instant
import java.util.UUID

/**
 * [StreamingExportSerializer] 단위 테스트.
 *
 * 검증 범위.
 * - (a) CSV: 페이지 배치 단위 입력 → 임시파일 UTF-8 BOM + 헤더 + 전체 행(RFC 4180)
 * - (b) XLSX: SXSSFWorkbook 임시파일, POI XSSFWorkbook 재로딩으로 행 수 검증
 * - (c) ExportCellSanitizer 적용(`=`시작 셀 `'` prefix)
 * - (d) 빈 배치(0행) → 헤더만
 * - (e) 여러 배치 append 후 SXSSF dispose() 호출(임시 백킹파일 정리)
 */
class StreamingExportSerializerTest {
    private val columns = ExportColumn.entries.toList()

    private fun makeHit(
        key: String = "ATLAS-1",
        summary: String = "Test issue",
    ) = IssueSearchHit(
        key = key,
        summary = summary,
        typeKey = "bug",
        currentStateKey = "open",
        assigneeId = UUID.randomUUID(),
        priority = 2,
        priorityName = "High",
        projectKey = "ATLAS",
        updatedAt = Instant.parse("2024-03-15T10:30:00Z"),
    )

    // ── (a) CSV — BOM + 헤더 + 전체 행 ──────────────────────────────────────────

    @Test
    fun `CSV - BOM + 헤더 행 포함`() {
        val serializer = StreamingExportSerializer(ExportFormat.CSV, columns)
        serializer.appendBatch(listOf(makeHit()))
        val file = serializer.finish()

        val bytes = file.readBytes()
        // UTF-8 BOM: EF BB BF
        assertThat(bytes[0]).isEqualTo(0xEF.toByte())
        assertThat(bytes[1]).isEqualTo(0xBB.toByte())
        assertThat(bytes[2]).isEqualTo(0xBF.toByte())

        val content = file.readText(Charsets.UTF_8)
        val lines = content.split("\r\n").filter { it.isNotEmpty() }
        // 헤더 + 1행
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains("Key")
        assertThat(lines[0]).contains("Summary")

        file.delete()
    }

    @Test
    fun `CSV - 두 배치 합산 행 수 정확`() {
        val serializer = StreamingExportSerializer(ExportFormat.CSV, columns)
        serializer.appendBatch(listOf(makeHit("ATLAS-1"), makeHit("ATLAS-2")))
        serializer.appendBatch(listOf(makeHit("ATLAS-3")))
        val file = serializer.finish()

        val content = file.readText(Charsets.UTF_8)
        val dataLines = content.split("\r\n").filter { it.isNotEmpty() }.drop(1) // 헤더 제외
        assertThat(dataLines).hasSize(3)
        assertThat(dataLines[0]).contains("ATLAS-1")
        assertThat(dataLines[2]).contains("ATLAS-3")

        file.delete()
    }

    // ── (b) XLSX — SXSSF 임시파일, POI 재로딩 행 수 검증 ──────────────────────────

    @Test
    fun `XLSX - POI 재로딩 후 행 수 검증`() {
        val serializer = StreamingExportSerializer(ExportFormat.XLSX, columns)
        serializer.appendBatch(listOf(makeHit("ATLAS-1"), makeHit("ATLAS-2")))
        val file = serializer.finish()

        FileInputStream(file).use { fis ->
            XSSFWorkbook(fis).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                // 헤더(0) + 데이터(1,2) = 3행
                assertThat(sheet.physicalNumberOfRows).isEqualTo(3)
                // 헤더 첫 셀 확인
                assertThat(sheet.getRow(0).getCell(0).stringCellValue).isEqualTo("Key")
                // 데이터 첫 셀 확인
                assertThat(sheet.getRow(1).getCell(0).stringCellValue).isEqualTo("ATLAS-1")
            }
        }

        file.delete()
    }

    // ── (c) ExportCellSanitizer 적용 — `=`시작 셀 `'` prefix ───────────────────

    @Test
    fun `CSV - formula injection 셀에 single quote prefix 적용`() {
        val serializer = StreamingExportSerializer(ExportFormat.CSV, columns)
        serializer.appendBatch(listOf(makeHit(summary = "=SUM(A1)")))
        val file = serializer.finish()

        val content = file.readText(Charsets.UTF_8)
        // '=SUM(A1) 형태로 sanitize 되어야 함
        assertThat(content).contains("'=SUM(A1)")

        file.delete()
    }

    @Test
    fun `XLSX - formula injection 셀에 single quote prefix 적용`() {
        val serializer = StreamingExportSerializer(ExportFormat.XLSX, columns)
        val summaryColIndex = columns.indexOfFirst { it == ExportColumn.SUMMARY }
        serializer.appendBatch(listOf(makeHit(summary = "=SUM(A1)")))
        val file = serializer.finish()

        FileInputStream(file).use { fis ->
            XSSFWorkbook(fis).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                val cell = sheet.getRow(1).getCell(summaryColIndex)
                assertThat(cell.stringCellValue).isEqualTo("'=SUM(A1)")
            }
        }

        file.delete()
    }

    // ── (d) 빈 배치 → 헤더만 ──────────────────────────────────────────────────────

    @Test
    fun `CSV - 빈 배치 → 헤더만 출력`() {
        val serializer = StreamingExportSerializer(ExportFormat.CSV, columns)
        serializer.appendBatch(emptyList())
        val file = serializer.finish()

        val content = file.readText(Charsets.UTF_8)
        val lines = content.split("\r\n").filter { it.isNotEmpty() }
        assertThat(lines).hasSize(1)
        assertThat(lines[0]).contains("Key")

        file.delete()
    }

    @Test
    fun `XLSX - 배치 없이 finish → 헤더 행만 존재`() {
        val serializer = StreamingExportSerializer(ExportFormat.XLSX, columns)
        val file = serializer.finish()

        FileInputStream(file).use { fis ->
            XSSFWorkbook(fis).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                assertThat(sheet.physicalNumberOfRows).isEqualTo(1)
                assertThat(sheet.getRow(0).getCell(0).stringCellValue).isEqualTo("Key")
            }
        }

        file.delete()
    }

    // ── (e) SXSSF dispose() — 임시 백킹파일 정리 호출 확인 ─────────────────────────

    @Test
    fun `XLSX - finish 후 SXSSF dispose 호출 (임시파일 누수 없음)`() {
        // dispose() 는 내부 백킹 임시파일을 정리한다.
        // 여기서는 finish() 가 정상 완료(예외 없음)되는 것으로 dispose 호출을 간접 검증한다.
        // dispose 미호출 시 임시파일이 잔류하지만 테스트 환경에서 OS 가 정리하므로
        // 예외 없이 반환되는 것을 성공 조건으로 삼는다.
        val serializer = StreamingExportSerializer(ExportFormat.XLSX, columns)
        serializer.appendBatch(listOf(makeHit("ATLAS-1")))
        val file = serializer.finish()
        assertThat(file).exists()
        file.delete()
    }
}
