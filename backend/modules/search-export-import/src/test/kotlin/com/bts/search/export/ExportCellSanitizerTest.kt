// ExportCellSanitizer 단위 테스트 — formula injection 방어 CSV/XLSX 공용 (= + - @ \t \r)

package com.bts.search.export

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [ExportCellSanitizer] 단위 테스트.
 *
 * 검증 항목.
 * - `=`, `+`, `-`, `@` 로 시작하는 셀 → `'` prefix
 * - 탭(`\t`), CR(`\r`) 로 시작하는 셀 → `'` prefix
 * - 일반 텍스트 / 숫자 / 빈 문자열 → 변경 없음
 */
class ExportCellSanitizerTest {

    // ── 위험 시작 문자 → prefix ────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["=SUM(A1)", "+1", "-1", "@user", "=CMD", "==", "-1+2"])
    fun `위험 시작 문자(등호·플러스·마이너스·골뱅이)로 시작하면 작은따옴표를 prefix한다`(cell: String) {
        val result = ExportCellSanitizer.sanitize(cell)
        assertThat(result).startsWith("'")
        assertThat(result.substring(1)).isEqualTo(cell)
    }

    @Test
    fun `탭 문자로 시작하는 셀 앞에 작은따옴표를 prefix한다`() {
        val cell = "\tcell value"
        val result = ExportCellSanitizer.sanitize(cell)
        assertThat(result).isEqualTo("'$cell")
    }

    @Test
    fun `CR 문자로 시작하는 셀 앞에 작은따옴표를 prefix한다`() {
        val cell = "\rcell value"
        val result = ExportCellSanitizer.sanitize(cell)
        assertThat(result).isEqualTo("'$cell")
    }

    // ── 일반 셀 → 무변경 ─────────────────────────────────────────────────────

    @Test
    fun `일반 텍스트 셀은 변경하지 않는다`() {
        assertThat(ExportCellSanitizer.sanitize("Normal text")).isEqualTo("Normal text")
    }

    @Test
    fun `빈 문자열은 변경하지 않는다`() {
        assertThat(ExportCellSanitizer.sanitize("")).isEqualTo("")
    }

    @Test
    fun `숫자 문자열은 변경하지 않는다`() {
        assertThat(ExportCellSanitizer.sanitize("12345")).isEqualTo("12345")
    }

    @Test
    fun `한글 셀은 변경하지 않는다`() {
        assertThat(ExportCellSanitizer.sanitize("한글 이슈 제목")).isEqualTo("한글 이슈 제목")
    }

    @Test
    fun `공백으로 시작하는 셀은 변경하지 않는다`() {
        val cell = " leading space"
        assertThat(ExportCellSanitizer.sanitize(cell)).isEqualTo(cell)
    }

    @Test
    fun `문자열 중간에 위험 문자가 있어도 변경하지 않는다`() {
        assertThat(ExportCellSanitizer.sanitize("text=value")).isEqualTo("text=value")
    }
}
