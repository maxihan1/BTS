// ImportErrorLogWriter 단위 테스트 — severity 컬럼 헤더/데이터라인 + 정화(sanitize) 무회귀 검증 (FR-IM-01 PR2 Task 5)

package com.bts.search.imports.job.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * [ImportErrorLogWriter] 단위 테스트.
 *
 * PR1 에서는 실패행(FAILURE)만 CSV 로 직렬화했으나, PR2 Task 5(G1)에서 [FailedRowRecord.severity] 가
 * 추가되어 best-effort 경고(WARNING)도 같은 CSV 에 노출된다. 이 테스트는 (1) 헤더에 Severity 컬럼이
 * 포함되는지, (2) FAILURE/WARNING 행이 각각 올바른 severity 값으로 기록되는지, (3) severity 컬럼 추가
 * 이후에도 formula injection 정화(sanitize)와 RFC 4180 이스케이프가 무회귀인지 검증한다.
 */
class ImportErrorLogWriterTest {
    private val writer = ImportErrorLogWriter()

    private fun writeToString(records: List<FailedRowRecord>): String {
        val out = ByteArrayOutputStream()
        writer.write(out, records)
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    @Test
    fun `header line includes Severity column`() {
        val csv = writeToString(emptyList())

        assertThat(csv).contains("Row,Field,Reason,Message,Severity")
    }

    @Test
    fun `failure record defaults to FAILURE severity`() {
        val csv =
            writeToString(
                listOf(FailedRowRecord(rowNumber = 1, reasonCode = "VALIDATION", message = "bad summary")),
            )

        assertThat(csv).contains("1,,VALIDATION,bad summary,FAILURE")
    }

    @Test
    fun `warning record is written with WARNING severity`() {
        val csv =
            writeToString(
                listOf(
                    FailedRowRecord(
                        rowNumber = 2,
                        reasonCode = "IMPORT_WARNING",
                        message = "컴포넌트를 찾을 수 없습니다",
                        severity = FailedRowRecord.SEVERITY_WARNING,
                    ),
                ),
            )

        assertThat(csv).contains("2,,IMPORT_WARNING,컴포넌트를 찾을 수 없습니다,WARNING")
    }

    @Test
    fun `mixed failure and warning rows are both present with distinct severities`() {
        val csv =
            writeToString(
                listOf(
                    FailedRowRecord(rowNumber = 1, reasonCode = "VALIDATION", message = "bad"),
                    FailedRowRecord(
                        rowNumber = 2,
                        reasonCode = "IMPORT_WARNING",
                        message = "skip",
                        severity = FailedRowRecord.SEVERITY_WARNING,
                    ),
                ),
            )

        assertThat(csv).contains("1,,VALIDATION,bad,FAILURE")
        assertThat(csv).contains("2,,IMPORT_WARNING,skip,WARNING")
    }

    @Test
    fun `formula injection prefix in message is sanitized regardless of severity`() {
        val csv =
            writeToString(
                listOf(
                    FailedRowRecord(
                        rowNumber = 1,
                        reasonCode = "VALIDATION",
                        message = "=cmd()",
                        severity = FailedRowRecord.SEVERITY_WARNING,
                    ),
                ),
            )

        assertThat(csv).contains("'=cmd()")
    }

    @Test
    fun `RFC 4180 escaping still applies to message field when severity column is present`() {
        val csv =
            writeToString(
                listOf(FailedRowRecord(rowNumber = 1, reasonCode = "VALIDATION", message = "has,comma")),
            )

        assertThat(csv).contains("\"has,comma\",FAILURE")
    }
}
