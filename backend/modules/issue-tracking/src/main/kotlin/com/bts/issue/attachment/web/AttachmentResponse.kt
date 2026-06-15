// 첨부 파일 REST 응답 DTO — storageKey 비노출, 도메인 Attachment → 응답 변환 포함

package com.bts.issue.attachment.web

import com.bts.issue.attachment.domain.Attachment
import java.time.Instant
import java.util.UUID

/**
 * 첨부 파일 REST 응답 DTO.
 *
 * [Attachment] 도메인 객체의 공개 필드만 노출한다.
 * [storageKey] 는 MinIO 내부 경로이므로 응답에 포함하지 않는다.
 *
 * @property id 첨부 고유 식별자.
 * @property filename 원본 파일명.
 * @property contentType MIME 타입.
 * @property sizeBytes 파일 크기(바이트).
 * @property uploadedBy 업로더 사용자 UUID.
 * @property createdAt 업로드 시각.
 */
data class AttachmentResponse(
    val id: UUID,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedBy: UUID,
    val createdAt: Instant,
) {
    companion object {
        /**
         * [Attachment] 도메인 객체를 [AttachmentResponse] 로 변환한다.
         *
         * @param attachment 변환 대상 도메인 객체.
         * @return storageKey 를 제외한 공개 필드 응답 DTO.
         */
        fun from(attachment: Attachment): AttachmentResponse =
            AttachmentResponse(
                id = attachment.id,
                filename = attachment.filename,
                contentType = attachment.contentType,
                sizeBytes = attachment.sizeBytes,
                uploadedBy = attachment.uploadedBy,
                createdAt = attachment.createdAt,
            )
    }
}
