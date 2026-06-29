// 페이지 배치 단위 스트리밍 직렬화기 — CSV(UTF-8 BOM + RFC4180) / XLSX(SXSSF 메모리 상수 보장)

package com.bts.search.export.job.serialize

import com.bts.search.export.ExportCellSanitizer
import com.bts.search.export.ExportColumn
import com.bts.search.export.ExportFormat
import com.bts.shared.search.IssueSearchHit
import org.apache.poi.xssf.streaming.SXSSFSheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.OutputStream

/**
 * 페이지 배치(List<[IssueSearchHit]>)를 순차로 받아 임시파일에 직렬화하는 스트리밍 직렬화기.
 *
 * ## 메모리 상수 보장
 *
 * - CSV: [BufferedWriter] + 행 단위 기록으로 전체 리스트를 메모리에 적재하지 않는다.
 * - XLSX: [SXSSFWorkbook](rowAccessWindowSize = [SXSSF_ROW_WINDOW]) 으로
 *   슬라이딩 윈도우 크기만큼만 메모리를 점유하고, 나머지는 임시 백킹파일에 flush 한다.
 *   [finish] 호출 시 [SXSSFWorkbook.dispose] 로 백킹 임시파일을 정리한다.
 *
 * ## 사용법
 *
 * ```kotlin
 * val serializer = StreamingExportSerializer(ExportFormat.CSV, columns)
 * for (batch in pages) {
 *     serializer.appendBatch(batch)
 * }
 * val resultFile = serializer.finish()
 * // resultFile 을 MinIO 에 업로드한 뒤 file.delete()
 * ```
 *
 * ## 셀 처리 순서 (기존 ExportColumn/ExportCellSanitizer 재사용)
 *
 * 1. [ExportColumn.extract] — [IssueSearchHit] 에서 문자열 추출
 * 2. [ExportCellSanitizer.sanitize] — formula injection 방어 (`'` prefix)
 * 3. CSV 한정: [rfc4180Escape] — 쉼표/큰따옴표/개행 RFC 4180 이스케이프
 *
 * @param format 직렬화 형식. [ExportFormat.CSV] 또는 [ExportFormat.XLSX].
 * @param columns 출력할 컬럼 목록. [ExportColumn.parse] 로 조립된 값을 전달한다.
 */
class StreamingExportSerializer(
    private val format: ExportFormat,
    private val columns: List<ExportColumn>,
) : Closeable {
    private val log = LoggerFactory.getLogger(javaClass)

    private val tempFile: File = File.createTempFile("bts-export-", ".${format.fileExtension}")

    // CSV 전용 — OutputStream + BufferedWriter (헤더는 생성자에서 즉시 기록)
    private val csvStream: OutputStream?
    private val csvWriter: BufferedWriter?

    // XLSX 전용 — SXSSFWorkbook + sheet (헤더는 생성자에서 즉시 기록)
    private val xlsxWorkbook: SXSSFWorkbook?
    private val xlsxSheet: SXSSFSheet?

    // XLSX 다음 기록할 행 번호(0 = 헤더)
    private var xlsxRowIndex = 0

    init {
        when (format) {
            ExportFormat.CSV -> {
                val out = tempFile.outputStream()
                out.write(UTF8_BOM)
                val writer = out.bufferedWriter(Charsets.UTF_8)
                writer.write(buildCsvHeaderLine())
                writer.write(CRLF)
                writer.flush()
                csvStream = out
                csvWriter = writer
                xlsxWorkbook = null
                xlsxSheet = null
            }

            ExportFormat.XLSX -> {
                val wb = SXSSFWorkbook(SXSSF_ROW_WINDOW)
                val sheet = wb.createSheet()
                writeXlsxHeaderRow(sheet)
                xlsxWorkbook = wb
                xlsxSheet = sheet
                csvStream = null
                csvWriter = null
            }
        }
    }

    /**
     * 한 페이지 배치를 직렬화해 임시파일에 추가 기록한다.
     *
     * [rows] 가 비어 있어도 안전하게 처리한다(no-op).
     *
     * @param rows 기록할 이슈 검색 결과 배치.
     */
    fun appendBatch(rows: List<IssueSearchHit>) {
        log.debug("appendBatch rows={} format={}", rows.size, format)
        when (format) {
            ExportFormat.CSV -> appendCsvBatch(rows)
            ExportFormat.XLSX -> appendXlsxBatch(rows)
        }
    }

    /**
     * 모든 배치 기록을 완료하고 결과 임시파일을 반환한다.
     *
     * - CSV: [BufferedWriter] flush + close
     * - XLSX: [SXSSFWorkbook] 을 임시파일에 write 후 [SXSSFWorkbook.dispose] 로 백킹파일 정리
     *
     * 반환된 [File] 은 호출자가 MinIO 업로드 후 [File.delete] 로 삭제해야 한다.
     *
     * @return 직렬화 완료된 임시파일.
     */
    fun finish(): File {
        log.debug("finish format={} tempFile={}", format, tempFile.absolutePath)
        when (format) {
            ExportFormat.CSV -> {
                csvWriter?.flush()
                csvWriter?.close()
                csvStream?.close()
            }

            ExportFormat.XLSX -> {
                checkNotNull(xlsxWorkbook) { "XLSX workbook 초기화 실패" }
                tempFile.outputStream().use { out ->
                    xlsxWorkbook.write(out)
                }
                // SXSSF 백킹 임시파일 정리 — 메모리/디스크 누수 방지.
                // dispose() 는 POI 5.x 에서 Deprecated(close() 가 내부 호출)이지만
                // 명시적 정리를 요구하는 스펙(task-6-e) 준수를 위해 유지한다.
                @Suppress("DEPRECATION")
                xlsxWorkbook.dispose()
                xlsxWorkbook.close()
            }
        }
        return tempFile
    }

    /**
     * 스트림/워크북을 닫고 임시파일을 삭제한다.
     *
     * [finish] 를 호출하지 않은 탈출 경로(상한 초과 조기 반환, 검색 예외)에서
     * 임시파일과 열린 스트림/[SXSSFWorkbook] 을 정리한다.
     * [finish] 호출 후에도 [use] 블록이 이 메서드를 호출하므로 이중 호출에 안전하다.
     * 표준 Java 스트림과 Apache POI [SXSSFWorkbook] 은 모두 이중 [close] 를 안전하게 처리한다.
     */
    override fun close() {
        when (format) {
            ExportFormat.CSV -> {
                csvWriter?.close()
                csvStream?.close()
            }
            ExportFormat.XLSX -> {
                @Suppress("DEPRECATION")
                xlsxWorkbook?.dispose()
                xlsxWorkbook?.close()
            }
        }
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    // ── CSV 내부 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * CSV 헤더 행 문자열을 빌드한다.
     *
     * 헤더 라벨은 정적 상수이므로 sanitize/escape 를 적용하지 않는다.
     */
    private fun buildCsvHeaderLine(): String = columns.joinToString(FIELD_SEP) { it.headerLabel }

    /**
     * 단일 데이터 행의 CSV 문자열을 빌드한다.
     *
     * extract → sanitize → RFC 4180 escape 순서로 처리한다.
     */
    private fun buildCsvDataLine(hit: IssueSearchHit): String =
        columns.joinToString(FIELD_SEP) { col ->
            rfc4180Escape(ExportCellSanitizer.sanitize(col.extract(hit)))
        }

    /** 배치를 CSV 임시파일에 추가 기록한다. */
    private fun appendCsvBatch(rows: List<IssueSearchHit>) {
        checkNotNull(csvWriter) { "CSV writer 초기화 실패" }
        for (hit in rows) {
            csvWriter.write(buildCsvDataLine(hit))
            csvWriter.write(CRLF)
        }
        csvWriter.flush()
    }

    /**
     * RFC 4180 이스케이프를 적용한다.
     *
     * 쉼표/큰따옴표/개행 포함 시 전체를 큰따옴표로 감싸고 내부 `"` 를 `""` 로 이중화한다.
     */
    private fun rfc4180Escape(cell: String): String {
        if (cell.none { it in RFC4180_SPECIAL }) return cell
        return "\"${cell.replace("\"", "\"\"")}\""
    }

    // ── XLSX 내부 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * XLSX 헤더 행(0번)을 기록하고 [xlsxRowIndex] 를 1로 전진시킨다.
     */
    private fun writeXlsxHeaderRow(sheet: SXSSFSheet) {
        val row = sheet.createRow(xlsxRowIndex++)
        columns.forEachIndexed { colIdx, col ->
            row.createCell(colIdx).setCellValue(col.headerLabel)
        }
    }

    /**
     * 배치를 XLSX 시트에 추가 기록한다.
     *
     * 각 셀은 extract → sanitize 후 setCellValue(String) 으로 기록한다.
     * setCellValue(String) 은 셀 타입을 STRING 으로 고정해 숫자 자동 변환을 방지한다.
     */
    private fun appendXlsxBatch(rows: List<IssueSearchHit>) {
        checkNotNull(xlsxSheet) { "XLSX sheet 초기화 실패" }
        for (hit in rows) {
            val row = xlsxSheet.createRow(xlsxRowIndex++)
            columns.forEachIndexed { colIdx, col ->
                val sanitized = ExportCellSanitizer.sanitize(col.extract(hit))
                row.createCell(colIdx).setCellValue(sanitized)
            }
        }
    }

    companion object {
        /** UTF-8 BOM 바이트 시퀀스 (EF BB BF). Excel 한글 인코딩 호환. */
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** RFC 4180 행 구분자 — CRLF. */
        private const val CRLF = "\r\n"

        /** RFC 4180 필드 구분자 — 쉼표. */
        private const val FIELD_SEP = ","

        /** RFC 4180 인용 처리가 필요한 특수 문자 집합. */
        private val RFC4180_SPECIAL = setOf(',', '"', '\n', '\r')

        /**
         * SXSSFWorkbook 슬라이딩 행 윈도우 크기.
         *
         * 100행 초과분은 임시 백킹파일로 자동 flush 된다.
         * 메모리 점유를 상수 범위로 제한하는 핵심 파라미터다.
         */
        const val SXSSF_ROW_WINDOW = 100
    }
}
