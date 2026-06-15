// 이슈 첨부 파일 REST 컨트롤러 — upload/list/download/delete 4 엔드포인트

package com.bts.issue.attachment.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.attachment.application.IssueAttachmentService
import com.bts.issue.domain.IssueKey
import org.slf4j.LoggerFactory
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 이슈 첨부 파일 REST 컨트롤러.
 *
 * 엔드포인트 목록.
 * - POST   /api/v1/issues/{key}/attachments — 첨부 업로드 (FR-AC-01)
 * - GET    /api/v1/issues/{key}/attachments — 첨부 목록 조회
 * - GET    /api/v1/issues/{key}/attachments/{attachmentId} — 첨부 다운로드
 * - DELETE /api/v1/issues/{key}/attachments/{attachmentId} — 첨부 삭제
 *
 * ### ActorId 결선
 * [CurrentActor.current] 로 SecurityContextHolder 의 인증 주체를 actor 로 추출한다.
 * 미인증·익명·비-UUID·nil-UUID 주체는 401(UNAUTHORIZED)로 거부한다.
 * actor 추출은 리소스 조회보다 앞서 수행하여 미인증자가 404 로 리소스 존재를 probe 하지 못하게 한다.
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * [IssueAttachmentService] 가 MinIO I/O 분리 트랜잭션 경계를 담당한다.
 *
 * ### 스트림 close 책임 (CONCERN-C)
 * [download] 엔드포인트는 [InputStreamResource] 를 반환한다.
 * Spring MVC 가 응답 바디 복사 완료 후 스트림을 자동으로 close 한다.
 * 컨트롤러에서 try/finally 로 미리 close 하지 않는다.
 *
 * @param service 첨부 파일 유스케이스 서비스.
 */
@RestController
@RequestMapping("/api/v1/issues/{key}/attachments")
class IssueAttachmentController(
    private val service: IssueAttachmentService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 첨부 파일을 업로드한다.
     *
     * @param key path variable 이슈 키 문자열.
     * @param file multipart 파일. name="file" part 필수.
     * @return 201 Created + [DataResponse]<[AttachmentResponse]> + Location 헤더.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재 또는 소프트 삭제 시 → 404.
     * @throws com.bts.issue.domain.IssueAccessDeniedException UPDATE 권한 미보유 시 → 403.
     * @throws org.springframework.web.multipart.MaxUploadSizeExceededException 크기 초과 시 → 413.
     */
    @PostMapping
    fun upload(
        @PathVariable key: String,
        file: MultipartFile?,
    ): ResponseEntity<DataResponse<AttachmentResponse>> {
        val resolvedFile = file ?: throw org.springframework.web.multipart.MultipartException(
            "Required request part 'file' is not present",
        )
        log.info(
            "IssueAttachmentController.upload key={} filename={} size={}",
            key,
            resolvedFile.originalFilename,
            resolvedFile.size,
        )

        val actor = CurrentActor.current()
        val attachment = service.upload(
            actor = actor,
            issueKey = IssueKey(key),
            filename = resolvedFile.originalFilename ?: resolvedFile.name,
            contentType = resolvedFile.contentType ?: "application/octet-stream",
            sizeBytes = resolvedFile.size,
            input = resolvedFile.inputStream,
        )
        val response = AttachmentResponse.from(attachment)
        val location = URI.create("/api/v1/issues/$key/attachments/${attachment.id}")
        return ResponseEntity.created(location).body(DataResponse(data = response))
    }

    /**
     * 이슈의 첨부 파일 목록을 반환한다.
     *
     * @param key path variable 이슈 키 문자열.
     * @return 200 OK + [DataResponse]<List<[AttachmentResponse]>>.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재 또는 소프트 삭제 시 → 404.
     * @throws com.bts.issue.domain.IssueAccessDeniedException VIEW 권한 미보유 시 → 403.
     */
    @GetMapping
    fun list(
        @PathVariable key: String,
    ): ResponseEntity<DataResponse<List<AttachmentResponse>>> {
        log.info("IssueAttachmentController.list key={}", key)

        val actor = CurrentActor.current()
        val attachments = service.list(actor, IssueKey(key))
        val responses = attachments.map { AttachmentResponse.from(it) }
        return ResponseEntity.ok(DataResponse(data = responses))
    }

    /**
     * 첨부 파일을 다운로드한다.
     *
     * Content-Disposition 헤더는 RFC 5987(UTF-8 인코딩) 방식으로 한글/특수문자 파일명을 안전하게 전달한다.
     * [InputStreamResource] 가 응답 후 MinIO 스트림 close 를 책임진다(CONCERN-C).
     *
     * @param key path variable 이슈 키 문자열.
     * @param attachmentId path variable 첨부 UUID.
     * @return 200 OK + 바이트 스트림 + Content-Type / Content-Disposition / Content-Length 헤더.
     * @throws com.bts.issue.domain.IssueNotFoundException 첨부 또는 이슈 미존재 시 → 404.
     * @throws com.bts.issue.domain.IssueAccessDeniedException VIEW 권한 미보유 시 → 403.
     */
    @GetMapping("/{attachmentId}")
    fun download(
        @PathVariable key: String,
        @PathVariable attachmentId: UUID,
    ): ResponseEntity<InputStreamResource> {
        log.info("IssueAttachmentController.download key={} attachmentId={}", key, attachmentId)

        val actor = CurrentActor.current()
        val result = service.download(actor, IssueKey(key), attachmentId)
        val attachment = result.attachment

        val contentDisposition = ContentDisposition.attachment()
            .filename(attachment.filename, StandardCharsets.UTF_8)
            .build()

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, attachment.contentType)
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
            .header(HttpHeaders.CONTENT_LENGTH, attachment.sizeBytes.toString())
            .body(InputStreamResource(result.stream))
    }

    /**
     * 첨부 파일을 삭제한다.
     *
     * @param key path variable 이슈 키 문자열.
     * @param attachmentId path variable 첨부 UUID.
     * @throws com.bts.issue.domain.IssueNotFoundException 첨부 또는 이슈 미존재 시 → 404.
     * @throws com.bts.issue.domain.IssueAccessDeniedException UPDATE 권한 미보유 시 → 403.
     */
    @DeleteMapping("/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable key: String,
        @PathVariable attachmentId: UUID,
    ) {
        log.info("IssueAttachmentController.delete key={} attachmentId={}", key, attachmentId)

        val actor = CurrentActor.current()
        service.delete(actor, IssueKey(key), attachmentId)
    }
}
