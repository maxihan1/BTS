// 허용되지 않은 첨부 파일 타입 업로드 시 발생하는 예외 (FR-AC-01 D2)

package com.bts.issue.attachment.application

/**
 * 업로드 파일의 MIME/확장자가 [AttachmentTypePolicy] 화이트리스트에 없을 때 발생한다.
 *
 * 차단된 contentType/filename 은 진단 로그용으로만 보관하고, HTTP 응답 메시지에는
 * 내부 정책 상세를 노출하지 않는다([com.bts.issue.attachment.web.AttachmentExceptionHandler] 415 매핑).
 *
 * @param contentType 거부된 요청의 MIME 타입.
 * @param filename 거부된 요청의 파일명.
 */
class UnsupportedAttachmentTypeException(
    val contentType: String,
    val filename: String,
) : RuntimeException("Unsupported attachment type. contentType=$contentType filename=$filename")
