// 이슈 내보내기 파일 형식 열거형 — CSV/XLSX ContentType 및 확장자 정의

package com.bts.search.export

/**
 * 이슈 내보내기 파일 형식.
 *
 * CSV와 XLSX 두 형식을 지원한다.
 * HTTP 응답 `Content-Type` 헤더와 다운로드 파일명 확장자를 각 형식에 맞게 제공한다.
 *
 * @property contentType HTTP `Content-Type` 헤더 값. 응답 헤더 조립에 사용한다.
 * @property fileExtension 다운로드 파일명에 붙는 확장자(점 제외). 예: `"csv"`, `"xlsx"`.
 */
enum class ExportFormat(val contentType: String, val fileExtension: String) {
    /** UTF-8 BOM 포함 CSV. RFC 4180 이스케이프. 한글 인코딩 호환. */
    CSV("text/csv; charset=UTF-8", "csv"),

    /** Apache POI XLSX — Open XML(OOXML) 형식. Excel/스프레드시트 프로그램 호환. */
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
}
