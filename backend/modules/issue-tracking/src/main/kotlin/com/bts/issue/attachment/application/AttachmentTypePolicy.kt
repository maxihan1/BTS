// 첨부 업로드 허용 MIME/확장자 화이트리스트 정책 — 단일 출처 (FR-AC-01 D2)

package com.bts.issue.attachment.application

/**
 * 첨부 업로드 시 허용 파일 타입 판정 정책.
 *
 * ## 판정 규칙 (오탐 회피)
 * essence(Content-Type) 와 파일 확장자를 **각각 독립적으로** 화이트리스트와 대조한다.
 * - `mimeOk` = 정규화 MIME 이 [ALLOWED_MIME] 에 있거나, `application/octet-stream`/빈 값(브라우저 미판별 → 확장자에 위임)
 * - `extOk`  = 확장자가 [ALLOWED_EXT] 에 있음
 * - 허용 = `mimeOk && extOk`
 *
 * "MIME 과 확장자가 서로 대응"이 아니라 "각자 독립 화이트리스트"인 이유.
 * docx/xlsx/pptx 는 zip 컨테이너라 브라우저가 `application/zip`/`application/octet-stream` 으로
 * 보고하는 경우가 흔하다. 대응을 요구하면 정상 업무 파일이 오탐 거부된다.
 * 각자 독립 + octet-stream 위임이면 오탐 없이 위험 확장자/타입(html·svg·exe 등)을 차단한다.
 *
 * 한계. 내용 위조(예: png 바이트에 html 삽입)는 내용기반 탐지 미도입(의존성 0)으로 미탐지한다.
 * 다운로드 `Content-Disposition: attachment` + `X-Content-Type-Options: nosniff` + 미리보기 blob
 * 화이트리스트(FR-AC-02)가 인라인 실행을 차단하므로, 본 정책의 목적은 저장소 오염 감소다.
 */
object AttachmentTypePolicy {
    /** 브라우저가 타입을 판별하지 못했을 때 보내는 값 — 확장자 검증에 위임한다. */
    private val UNKNOWN_MIME = setOf("application/octet-stream", "")

    /** 업로드를 허용하는 essence MIME 타입(소문자). */
    val ALLOWED_MIME: Set<String> =
        setOf(
            // 이미지
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/tiff",
            // 문서
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            // 텍스트
            "text/plain", "text/csv", "text/markdown",
            "application/json", "application/xml", "text/xml",
            // 아카이브
            "application/zip", "application/x-7z-compressed", "application/x-tar", "application/gzip",
            // 동영상
            "video/mp4", "video/webm", "video/quicktime", "video/x-msvideo",
            // 오디오
            "audio/mpeg", "audio/wav", "audio/ogg", "audio/mp4", "audio/webm",
        )

    /** 업로드를 허용하는 파일 확장자(소문자, `.` 제외). */
    val ALLOWED_EXT: Set<String> =
        setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff",
            "pdf",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
            "txt", "log", "md", "csv", "json", "xml",
            "zip", "7z", "tar", "gz",
            "mp4", "webm", "mov", "avi",
            "mp3", "wav", "ogg", "m4a",
        )

    /**
     * 주어진 Content-Type 과 파일명이 업로드 허용 대상인지 판정한다.
     *
     * @param contentType 클라이언트가 보낸 MIME 타입(파라미터·대문자 포함 가능).
     * @param filename 원본 파일명(확장자 추출용).
     * @return 허용이면 true, 차단이면 false.
     */
    fun isAllowed(
        contentType: String,
        filename: String,
    ): Boolean {
        val normMime = normalizeMime(contentType)
        val ext = extensionOf(filename)
        val mimeOk = normMime in ALLOWED_MIME || normMime in UNKNOWN_MIME
        val extOk = ext in ALLOWED_EXT
        return mimeOk && extOk
    }

    /** MIME 을 essence 형태(소문자, `;` 이후 파라미터 제거)로 정규화한다. */
    private fun normalizeMime(contentType: String): String = contentType.substringBefore(';').trim().lowercase()

    /** 파일명의 마지막 `.` 이후 확장자를 소문자로 추출한다. 확장자가 없으면 빈 문자열. */
    private fun extensionOf(filename: String): String {
        val dot = filename.lastIndexOf('.')
        if (dot < 0 || dot == filename.length - 1) return ""
        return filename.substring(dot + 1).trim().lowercase()
    }
}
