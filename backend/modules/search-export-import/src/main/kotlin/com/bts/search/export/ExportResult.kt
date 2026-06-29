// 이슈 Export 결과 VO — 파일명, Content-Type, 직렬화된 바이트

package com.bts.search.export

/**
 * 이슈 Export 결과 값 객체.
 *
 * [ExportService.export]가 반환하는 불변 VO.
 * HTTP 응답 조립에 필요한 세 가지 정보를 캡슐화한다.
 *
 * [ByteArray]는 기본적으로 참조 동등성을 사용하므로
 * 값 동등성이 필요한 테스트를 위해 [equals]/[hashCode]를 재정의한다.
 *
 * @property filename 다운로드 파일명. 예: `"PROJ-issues-20240315-103045.csv"`.
 * @property contentType HTTP `Content-Type` 헤더 값. [ExportFormat.contentType]에서 가져온다.
 * @property bytes 직렬화된 파일 바이트. CSV는 UTF-8 BOM 포함, XLSX는 OOXML(ZIP) 형식.
 */
data class ExportResult(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExportResult) return false
        return filename == other.filename &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
