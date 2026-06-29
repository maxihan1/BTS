// CSV 형식으로 이슈 검색 결과를 직렬화하는 Writer — UTF-8 BOM + RFC 4180 이스케이프

package com.bts.search.export

import com.bts.shared.search.IssueSearchHit
import org.slf4j.LoggerFactory
import java.io.OutputStream

/**
 * UTF-8 BOM + RFC 4180 이스케이프 CSV 직렬화 Writer.
 *
 * ### 셀 처리 순서
 *
 * 1. [ExportColumn.extract] — [IssueSearchHit] 에서 문자열 추출 (null → `""`)
 * 2. [ExportCellSanitizer.sanitize] — formula injection 방어 (`'` prefix)
 * 3. [rfc4180Escape] — 쉼표/큰따옴표/개행 포함 시 큰따옴표로 감싸고 내부 `"` 를 `""` 로 이중화
 *
 * 외부 라이브러리 의존 없는 zero-dep 손수 구현 (ADR §D2 결정).
 * 행 구분자는 RFC 4180 표준에 따라 CRLF(`\r\n`)를 사용한다.
 * 헤더 라벨([ExportColumn.headerLabel])은 정적 상수이므로 sanitize/escape를 적용하지 않는다.
 */
class CsvExportWriter {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [OutputStream] 에 UTF-8 BOM + RFC 4180 CSV 를 작성한다.
     *
     * BOM → 헤더 행 → 데이터 행 순서로 작성한다.
     * 행이 없으면 BOM + 헤더 행만 출력한다.
     * [OutputStream] 의 닫기는 호출자가 책임진다.
     *
     * @param out 결과를 쓸 [OutputStream].
     * @param columns 출력할 컬럼 목록. 선언 순서 그대로 헤더와 데이터 행에 반영된다.
     * @param rows 출력할 이슈 검색 결과 목록. 빈 목록이면 헤더만 출력한다.
     */
    fun write(
        out: OutputStream,
        columns: List<ExportColumn>,
        rows: List<IssueSearchHit>,
    ) {
        log.debug("CSV 직렬화 시작: columns={}, rows={}", columns.size, rows.size)
        out.write(UTF8_BOM)
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write(buildHeaderLine(columns))
        writer.write(CRLF)
        for (row in rows) {
            writer.write(buildDataLine(columns, row))
            writer.write(CRLF)
        }
        writer.flush()
    }

    /**
     * 컬럼 라벨을 쉼표로 연결한 헤더 행 문자열을 반환한다.
     *
     * 헤더 라벨은 정적 상수이므로 RFC 4180 이스케이프를 적용하지 않는다.
     */
    private fun buildHeaderLine(columns: List<ExportColumn>): String =
        columns.joinToString(FIELD_SEPARATOR) { it.headerLabel }

    /**
     * 단일 이슈 행의 CSV 문자열을 반환한다.
     *
     * 각 셀에 대해 extract → sanitize → RFC 4180 이스케이프 순서로 처리한다.
     */
    private fun buildDataLine(
        columns: List<ExportColumn>,
        hit: IssueSearchHit,
    ): String =
        columns.joinToString(FIELD_SEPARATOR) { column ->
            val raw = column.extract(hit)
            val sanitized = ExportCellSanitizer.sanitize(raw)
            rfc4180Escape(sanitized)
        }

    /**
     * RFC 4180 이스케이프를 적용한다.
     *
     * 셀에 쉼표(`,`), 큰따옴표(`"`), LF(`\n`), CR(`\r`) 중 하나라도 포함되면
     * 셀 전체를 큰따옴표로 감싸고, 기존 큰따옴표는 `""` 로 이중화한다.
     * 해당 문자가 없으면 셀을 그대로 반환한다.
     *
     * @param cell sanitize 이후의 셀 값.
     * @return RFC 4180 규격 셀 문자열.
     */
    private fun rfc4180Escape(cell: String): String {
        if (cell.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return cell
        return "\"${cell.replace("\"", "\"\"")}\""
    }

    companion object {
        /** UTF-8 BOM 바이트 시퀀스 (EF BB BF). Excel 한글 인코딩 호환. */
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** RFC 4180 행 구분자 — CRLF. */
        private const val CRLF = "\r\n"

        /** RFC 4180 필드 구분자 — 쉼표. */
        private const val FIELD_SEPARATOR = ","
    }
}
