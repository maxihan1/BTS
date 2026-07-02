// Import 실패행 CSV 에러 로그 직렬화 — RFC 4180 + formula injection 정화(ExportCellSanitizer 재사용)

package com.bts.search.imports.job.application

import com.bts.search.export.ExportCellSanitizer
import org.springframework.stereotype.Component
import java.io.OutputStream

/**
 * Import 처리 실패행을 CSV 로 직렬화하는 Writer.
 *
 * [ImportJobProcessor] 가 파싱/생성 실패행([FailedRowRecord])을 모은 뒤, 이 Writer 로 CSV 바이트를
 * 만들어 MinIO 에 업로드한다(오브젝트 키 패턴은 [com.bts.search.imports.job.storage.ImportObjectStoragePort]
 * 클래스 KDoc 참조).
 *
 * ### formula injection 방어 (CONCERN #3)
 *
 * 이 CSV는 나중에 사용자가 Excel 등 스프레드시트 프로그램으로 여는 export 산출물이다.
 * [FailedRowRecord.message] 는 원본 업로드 데이터를 그대로 포함할 수 있으므로(예: 이슈 생성 실패
 * 사유에 원본 summary 일부가 인용되는 경우), `export` BC 의 [ExportCellSanitizer] 를 재사용해 모든
 * 셀에 formula injection 방어(`=+-@` 등으로 시작하면 `'` prefix)를 적용한 뒤 RFC 4180 이스케이프한다.
 * `export` BC `CsvExportWriter` 와 동일한 처리 순서(추출 → sanitize → escape)를 따르되, 대상 타입이
 * [FailedRowRecord] 로 다르다.
 */
@Component
class ImportErrorLogWriter {
    /**
     * [records] 를 UTF-8 BOM + RFC 4180 CSV 로 [out] 에 작성한다.
     *
     * 컬럼 순서는 rowNumber, field, reason(code), message 이다.
     * [out] 의 닫기는 호출자 책임.
     *
     * @param out 결과를 쓸 [OutputStream].
     * @param records 직렬화할 실패행 목록. 빈 목록이면 헤더만 출력한다.
     */
    fun write(
        out: OutputStream,
        records: List<FailedRowRecord>,
    ) {
        out.write(UTF8_BOM)
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write(HEADER_LINE)
        writer.write(CRLF)
        for (record in records) {
            writer.write(buildDataLine(record))
            writer.write(CRLF)
        }
        writer.flush()
    }

    /** 실패행 1건을 sanitize + RFC 4180 이스케이프된 CSV 한 줄로 변환한다. */
    private fun buildDataLine(record: FailedRowRecord): String =
        listOf(
            record.rowNumber.toString(),
            record.field.orEmpty(),
            record.reasonCode,
            record.message.orEmpty(),
        ).joinToString(FIELD_SEPARATOR) { escape(ExportCellSanitizer.sanitize(it)) }

    /**
     * RFC 4180 이스케이프를 적용한다.
     *
     * 셀에 쉼표/큰따옴표/개행이 포함되면 셀 전체를 큰따옴표로 감싸고, 기존 큰따옴표는 `""` 로
     * 이중화한다. 해당 문자가 없으면 셀을 그대로 반환한다(`export` BC `CsvExportWriter` 와 동일 규칙).
     */
    private fun escape(cell: String): String {
        if (cell.none { it in RFC4180_SPECIAL_CHARS }) return cell
        return "\"${cell.replace("\"", "\"\"")}\""
    }

    companion object {
        /** UTF-8 BOM 바이트 시퀀스 (EF BB BF). Excel 한글 인코딩 호환. */
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** RFC 4180 행 구분자 — CRLF. */
        private const val CRLF = "\r\n"

        /** RFC 4180 필드 구분자 — 쉼표. */
        private const val FIELD_SEPARATOR = ","

        /** CSV 헤더 행. 컬럼 순서. rowNumber, field, reason(code), message. */
        private const val HEADER_LINE = "Row,Field,Reason,Message"

        /** RFC 4180 인용 처리가 필요한 특수 문자 집합. */
        private val RFC4180_SPECIAL_CHARS = setOf(',', '"', '\n', '\r')
    }
}

/**
 * [ImportErrorLogWriter] 가 CSV 로 직렬화하는 실패행 1건.
 *
 * @property rowNumber 원본 파일에서의 1-기준 데이터 행 번호. [com.bts.search.imports.parse.ParsedImportRow.rowNumber] 값.
 * @property reasonCode 실패 사유 코드. [com.bts.shared.issue.IssueImportResult] companion 상수 참조.
 * @property message 사람이 읽을 수 있는 실패 상세 메시지. 없을 수 있다.
 * @property field 실패와 연관된 필드 이름. [com.bts.shared.issue.IssueImportResult.Failure] 가 현재
 *   필드 단위 정보를 제공하지 않으므로 PR1 에서는 항상 null(향후 확장 대비 필드).
 */
data class FailedRowRecord(
    val rowNumber: Int,
    val reasonCode: String,
    val message: String? = null,
    val field: String? = null,
)
