// 첨부 파일 서비스 응답 DTO — download 반환 타입(메타+스트림 묶음)

package com.bts.issue.attachment.application

import com.bts.issue.attachment.domain.Attachment
import java.io.InputStream

/**
 * 첨부 파일 다운로드 결과.
 *
 * 컨트롤러가 [stream] 을 HTTP 응답 바디로 복사하고, close 책임을 갖는다.
 *
 * @property attachment 파일명/contentType/sizeBytes 등 메타데이터.
 * @property stream 오브젝트 스토리지로부터 받은 바이트 스트림. 호출자가 close 해야 한다.
 */
data class AttachmentDownloadResult(
    val attachment: Attachment,
    val stream: InputStream,
)
