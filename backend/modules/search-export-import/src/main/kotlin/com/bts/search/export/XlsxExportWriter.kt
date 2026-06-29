// 이슈 검색 결과를 XLSX 형식으로 직렬화하는 writer — Apache POI XSSF 기반

package com.bts.search.export

import com.bts.shared.search.IssueSearchHit
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.OutputStream

/**
 * 이슈 검색 결과를 XLSX 형식으로 [OutputStream]에 기록하는 writer.
 *
 * Apache POI [XSSFWorkbook](Open XML OOXML)을 사용한다.
 * 모든 셀은 STRING 타입으로 기록해
 * 숫자 자동 변환을 방지하고 formula injection 안전성을 보장한다.
 *
 * ### Formula Injection 방어
 *
 * 모든 데이터 셀 값은 [ExportCellSanitizer.sanitize]를 통과한 후 기록된다.
 * `=`, `+`, `-`, `@`, `\t`, `\r`로 시작하는 값에 `'` prefix를 붙여
 * Excel/스프레드시트가 수식으로 해석하지 못하게 한다.
 *
 * ### 사용 예시
 *
 * ```kotlin
 * val writer = XlsxExportWriter()
 * response.outputStream.use { out ->
 *     writer.write(out, ExportColumn.entries.toList(), rows)
 * }
 * ```
 *
 * @see ExportCellSanitizer formula injection 방어
 * @see ExportColumn 컬럼 정의 및 셀 값 추출
 */
class XlsxExportWriter {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈 검색 결과를 XLSX 형식으로 [out]에 기록한다.
     *
     * 시트 1개를 생성하고, 첫 번째 행에 헤더를, 이후 행에 데이터를 기록한다.
     * [rows]가 빈 목록이면 헤더 행만 기록한다.
     *
     * workbook은 [use] 블록으로 close가 보장된다(메모리 누수 방지).
     *
     * @param out 결과를 기록할 출력 스트림. 이 메서드가 close하지 않는다 — 호출자가 관리한다.
     * @param columns 기록할 컬럼 목록. [ExportColumn.parse]로 조립된 값을 전달한다.
     * @param rows 기록할 이슈 검색 결과 목록.
     */
    fun write(
        out: OutputStream,
        columns: List<ExportColumn>,
        rows: List<IssueSearchHit>,
    ) {
        log.debug("XLSX export 시작 — 컬럼 수: {}, 행 수: {}", columns.size, rows.size)
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet()
            writeHeaderRow(sheet, columns)
            rows.forEachIndexed { rowIndex, hit ->
                writeDataRow(sheet, columns, hit, rowIndex + DATA_ROW_OFFSET)
            }
            workbook.write(out)
        }
        log.debug("XLSX export 완료")
    }

    /**
     * 시트 첫 번째 행(0번)에 헤더 라벨을 기록한다.
     *
     * 헤더는 [ExportColumn.headerLabel] 정적 상수이므로 sanitize를 적용하지 않는다.
     *
     * @param sheet 대상 시트.
     * @param columns 헤더를 기록할 컬럼 목록.
     */
    private fun writeHeaderRow(
        sheet: XSSFSheet,
        columns: List<ExportColumn>,
    ) {
        val row = sheet.createRow(HEADER_ROW_INDEX)
        columns.forEachIndexed { colIndex, col ->
            row.createCell(colIndex).setCellValue(col.headerLabel)
        }
    }

    /**
     * 지정한 행 번호에 이슈 데이터를 기록한다.
     *
     * 각 셀 값은 [ExportCellSanitizer.sanitize] 후 setCellValue(String)으로 기록한다.
     * setCellValue(String)은 셀 타입을 STRING으로 고정한다.
     *
     * @param sheet 대상 시트.
     * @param columns 기록할 컬럼 목록.
     * @param hit 기록할 이슈 검색 결과 단건.
     * @param rowIndex 기록할 행 번호(0-base).
     */
    private fun writeDataRow(
        sheet: XSSFSheet,
        columns: List<ExportColumn>,
        hit: IssueSearchHit,
        rowIndex: Int,
    ) {
        val row = sheet.createRow(rowIndex)
        columns.forEachIndexed { colIndex, col ->
            val rawValue = col.extract(hit)
            val sanitized = ExportCellSanitizer.sanitize(rawValue)
            row.createCell(colIndex).setCellValue(sanitized)
        }
    }

    private companion object {
        /** 헤더 행 인덱스(0-base). */
        const val HEADER_ROW_INDEX = 0

        /** 데이터 첫 행의 행 번호 오프셋(헤더 1행 다음). */
        const val DATA_ROW_OFFSET = 1
    }
}
