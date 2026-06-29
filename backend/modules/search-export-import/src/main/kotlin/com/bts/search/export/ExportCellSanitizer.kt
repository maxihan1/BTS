// 스프레드시트 formula injection 방어 Sanitizer — CSV/XLSX 공용 셀 값 정화

package com.bts.search.export

/**
 * 스프레드시트 formula injection 공격을 방어하는 셀 값 sanitizer.
 *
 * CSV와 XLSX 두 형식 모두에서 재사용한다.
 * `=`, `+`, `-`, `@`, 탭(`\t`), CR(`\r`)로 시작하는 셀 값 앞에 작은따옴표(`'`)를 prefix하여
 * Excel/스프레드시트 프로그램이 수식으로 해석하지 못하게 한다.
 *
 * ### 적용 시점
 *
 * [ExportColumn.extract] 결과를 CSV 또는 XLSX 셀에 기록하기 직전에 호출한다.
 * 헤더 라벨([ExportColumn.headerLabel])은 정적 상수이므로 적용하지 않는다.
 */
object ExportCellSanitizer {
    /**
     * 스프레드시트 수식으로 해석될 수 있는 시작 문자 집합.
     *
     * - `=` — Excel 수식 시작 (`=SUM(A1)` 등)
     * - `+` — 양수 수식 (`+1`, `+HYPERLINK(...)`)
     * - `-` — 음수/빼기 수식
     * - `@` — Excel 암묵적 교차 연산자 / DDE 공격 (`@SUM(...)`)
     * - `\t` — 탭 — 일부 파서가 수식으로 해석
     * - `\r` — CR — CSV 행 분리 오인 + 인젝션
     */
    private val INJECTION_START_CHARS = setOf('=', '+', '-', '@', '\t', '\r')

    /**
     * 셀 값에서 formula injection 위험을 제거한다.
     *
     * [INJECTION_START_CHARS]에 포함된 문자로 시작하면 `'` 문자를 앞에 추가한다.
     * 빈 문자열이나 일반 텍스트는 변경 없이 그대로 반환한다.
     *
     * @param cell sanitize할 셀 값.
     * @return sanitize된 셀 값. 위험 시작 문자가 없으면 [cell] 그대로 반환한다.
     */
    fun sanitize(cell: String): String {
        if (cell.isEmpty()) return cell
        return if (cell.first() in INJECTION_START_CHARS) "'$cell" else cell
    }
}
