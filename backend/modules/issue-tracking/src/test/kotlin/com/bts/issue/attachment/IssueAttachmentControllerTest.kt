// 이슈 첨부 파일 REST 컨트롤러 MockMvc 슬라이스 테스트

package com.bts.issue.attachment

import com.bts.issue.attachment.application.AttachmentDownloadResult
import com.bts.issue.attachment.application.AttachmentInfectedException
import com.bts.issue.attachment.application.AttachmentScanUnavailableException
import com.bts.issue.attachment.application.IssueAttachmentService
import com.bts.issue.attachment.application.UnsupportedAttachmentTypeException
import com.bts.issue.attachment.domain.Attachment
import com.bts.issue.attachment.web.AttachmentExceptionHandler
import com.bts.issue.attachment.web.IssueAttachmentController
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

/**
 * IssueAttachmentController MockMvc 슬라이스 테스트.
 *
 * [IssueAttachmentService] 는 MockK stub으로 대체한다.
 * [AttachmentExceptionHandler] 를 컨텍스트에 등록하여 예외→HTTP 변환을 검증한다.
 *
 * 테스트 케이스.
 * - C-1. POST multipart → 201 Created + Location 헤더 + body.data.id
 * - C-2. GET 목록 → 200 OK + body.data 배열
 * - C-3. GET /{id} 다운로드 → 200 + Content-Disposition(attachment) + Content-Type + Content-Length
 * - C-4. DELETE /{id} → 204 No Content
 * - C-5. 413: MaxUploadSizeExceededException → 413
 * - C-6. 400: file part 누락 → 400 (MultipartException)
 * - C-7. IssueNotFoundException → 404
 * - C-8. IssueAccessDeniedException → 403
 * - C-9. AttachmentInfectedException → 422 + ISSUE_ATTACHMENT_INFECTED (시그니처 비노출)
 * - C-10. AttachmentScanUnavailableException → 503 + ISSUE_ATTACHMENT_SCAN_UNAVAILABLE
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueAttachmentControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueAttachmentControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueAttachmentController], [AttachmentExceptionHandler], MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueAttachmentService(): IssueAttachmentService = mockk(relaxed = true)

        @Bean
        open fun issueAttachmentController(service: IssueAttachmentService): IssueAttachmentController {
            return IssueAttachmentController(service)
        }

        @Bean
        open fun attachmentExceptionHandler(): AttachmentExceptionHandler = AttachmentExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueAttachmentService: IssueAttachmentService

    lateinit var mockMvc: MockMvc

    private val actorUuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val attachmentId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val issueId = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val fixedNow: Instant = Instant.parse("2026-06-15T00:00:00Z")

    private val sampleAttachment =
        Attachment(
            id = attachmentId,
            issueId = issueId,
            filename = "테스트파일.png",
            contentType = "image/png",
            sizeBytes = 1024L,
            storageKey = "issues/$issueId/$attachmentId",
            uploadedBy = actorUuid,
            createdAt = fixedNow,
        )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorUuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    @AfterEach
    fun tearDown() {
        clearMocks(issueAttachmentService)
        SecurityContextHolder.clearContext()
    }

    // ── C-1. POST multipart → 201 ─────────────────────────────────────────────

    /**
     * C-1. 유효한 multipart 파일 업로드 → 201 Created + Location + body.
     *
     * Given  service.upload 가 sampleAttachment 를 반환함
     * When   POST /api/v1/issues/ATLAS-1/attachments (multipart file=image.png)
     * Then   201, Location: /api/v1/issues/ATLAS-1/attachments/{id}, body.data.id 포함
     */
    @Test
    fun `POST multipart 업로드 — 201 Created plus Location plus body`() {
        every {
            issueAttachmentService.upload(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                filename = "image.png",
                contentType = "image/png",
                sizeBytes = 4L,
                input = any(),
            )
        } returns sampleAttachment

        val file = MockMultipartFile("file", "image.png", "image/png", "data".toByteArray())

        mockMvc.perform(
            multipart("/api/v1/issues/ATLAS-1/attachments").file(file),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/v1/issues/ATLAS-1/attachments/$attachmentId"))
            .andExpect(jsonPath("$.data.id").value(attachmentId.toString()))
            .andExpect(jsonPath("$.data.filename").value("테스트파일.png"))
    }

    // ── C-1b. POST 허용되지 않은 타입 → 415 ────────────────────────────────────

    /**
     * C-1b. 허용되지 않은 MIME/확장자 업로드 → 415 Unsupported Media Type + 표준 errorCode.
     *
     * Given  service.upload 가 UnsupportedAttachmentTypeException 을 던짐
     * When   POST /api/v1/issues/ATLAS-1/attachments (multipart file=evil.html)
     * Then   415 + errorCode=ISSUE_UNSUPPORTED_FILE_TYPE
     */
    @Test
    fun `POST 허용되지 않은 타입 — 415 Unsupported Media Type`() {
        every {
            issueAttachmentService.upload(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                filename = "evil.html",
                contentType = "text/html",
                sizeBytes = any(),
                input = any(),
            )
        } throws UnsupportedAttachmentTypeException("text/html", "evil.html")

        val file = MockMultipartFile("file", "evil.html", "text/html", "<script>".toByteArray())

        mockMvc.perform(
            multipart("/api/v1/issues/ATLAS-1/attachments").file(file),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_UNSUPPORTED_FILE_TYPE"))
    }

    // ── C-2. GET 목록 → 200 ──────────────────────────────────────────────────

    /**
     * C-2. 첨부 목록 조회 → 200 OK + body.data 배열.
     *
     * Given  service.list 가 [sampleAttachment] 를 반환함
     * When   GET /api/v1/issues/ATLAS-1/attachments
     * Then   200 OK, body.data 배열에 항목 1건
     */
    @Test
    fun `GET 목록 — 200 OK plus data 배열`() {
        every {
            issueAttachmentService.list(ActorId(actorUuid), IssueKey("ATLAS-1"))
        } returns listOf(sampleAttachment)

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/attachments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].id").value(attachmentId.toString()))
    }

    // ── C-3. GET /{id} 다운로드 → 200 ────────────────────────────────────────

    /**
     * C-3. 첨부 다운로드 → 200 + Content-Disposition(attachment) + Content-Type + Content-Length + nosniff.
     *
     * Given  service.download 가 AttachmentDownloadResult 를 반환함
     * When   GET /api/v1/issues/ATLAS-1/attachments/{id}
     * Then   200, Content-Type=image/png, Content-Length=1024, Content-Disposition contains filename,
     *        X-Content-Type-Options=nosniff (브라우저 MIME 스니핑 차단 — 미리보기/다운로드 콘텐츠 타입 신뢰 강화)
     */
    @Test
    fun `GET 다운로드 — 200 plus Content-Disposition plus Content-Type plus Content-Length plus nosniff`() {
        val bytes = ByteArray(1024) { 0 }
        every {
            issueAttachmentService.download(ActorId(actorUuid), IssueKey("ATLAS-1"), attachmentId)
        } returns
            AttachmentDownloadResult(
                attachment = sampleAttachment,
                stream = ByteArrayInputStream(bytes),
            )

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/attachments/$attachmentId"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("image/png"))
            .andExpect(header().string("Content-Length", "1024"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
    }

    /**
     * C-3b. MinIO I/O 실패 → 500 + BC 표준 errorCode (catch-all 핸들러 검증).
     *
     * Given  service.download 가 MinioStorageException 을 던짐
     * When   GET /api/v1/issues/ATLAS-1/attachments/{id}
     * Then   500 + errorCode=ISSUE_INTERNAL_ERROR (Spring 기본 /error 가 아닌 ProblemDetail 포맷)
     */
    @Test
    fun `GET 다운로드 — MinIO 실패 시 500 plus 표준 errorCode`() {
        every {
            issueAttachmentService.download(ActorId(actorUuid), IssueKey("ATLAS-1"), attachmentId)
        } throws MinioStorageException("오브젝트 다운로드 실패", RuntimeException("boom"))

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/attachments/$attachmentId"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_INTERNAL_ERROR"))
    }

    // ── C-4. DELETE → 204 ────────────────────────────────────────────────────

    /**
     * C-4. 첨부 삭제 → 204 No Content.
     *
     * Given  service.delete 가 정상 반환
     * When   DELETE /api/v1/issues/ATLAS-1/attachments/{id}
     * Then   204 No Content
     */
    @Test
    fun `DELETE — 204 No Content`() {
        every { issueAttachmentService.delete(ActorId(actorUuid), IssueKey("ATLAS-1"), attachmentId) } returns Unit

        mockMvc.perform(delete("/api/v1/issues/ATLAS-1/attachments/$attachmentId"))
            .andExpect(status().isNoContent)

        verify(exactly = 1) { issueAttachmentService.delete(ActorId(actorUuid), IssueKey("ATLAS-1"), attachmentId) }
    }

    // ── C-5. 413 MaxUploadSizeExceededException ───────────────────────────────

    /**
     * C-5. 최대 업로드 크기 초과 → 413 Payload Too Large.
     *
     * Given  service.upload 가 MaxUploadSizeExceededException 을 throw
     * When   POST /api/v1/issues/ATLAS-1/attachments
     * Then   413
     */
    @Test
    fun `POST 파일 크기 초과 — 413 Payload Too Large`() {
        // MaxUploadSizeExceededException 은 MultipartException 의 서브클래스이다.
        // 컨트롤러가 MultipartFile? null 체크 전에 Spring 이 예외를 throw하는 상황을 시뮬레이션하기 위해
        // service.upload 가 아닌 null file 경로로 MultipartException 계열 예외가 핸들러를 타는지 검증한다.
        // 실제 운영에서 Spring multipart 파서가 크기 초과 시 DispatcherServlet 수준에서 throw한다.
        // AttachmentExceptionHandler 가 MaxUploadSizeExceededException 을 413 으로 변환함을 단위 검증.
        every {
            issueAttachmentService.upload(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                filename = "big.bin",
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                sizeBytes = 1L,
                input = any(),
            )
        } throws MaxUploadSizeExceededException(100L)

        val file = MockMultipartFile("file", "big.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, "x".toByteArray())

        mockMvc.perform(
            multipart("/api/v1/issues/ATLAS-1/attachments").file(file),
        )
            .andExpect(status().isPayloadTooLarge)
    }

    // ── C-6. 400 file part 누락 ───────────────────────────────────────────────

    /**
     * C-6. file part 없이 요청 → 400 Bad Request.
     *
     * When   POST /api/v1/issues/ATLAS-1/attachments (multipart 없이 일반 POST)
     * Then   400
     */
    @Test
    fun `POST file part 누락 — 400 Bad Request`() {
        mockMvc.perform(
            multipart("/api/v1/issues/ATLAS-1/attachments"),
        )
            .andExpect(status().isBadRequest)
    }

    // ── C-7. 404 IssueNotFoundException ──────────────────────────────────────

    /**
     * C-7. service 가 IssueNotFoundException → 404 Not Found.
     *
     * Given  service.list 가 IssueNotFoundException 을 throw
     * When   GET /api/v1/issues/ATLAS-99/attachments
     * Then   404
     */
    @Test
    fun `GET 이슈 미존재 — 404 Not Found`() {
        every {
            issueAttachmentService.list(any(), IssueKey("ATLAS-99"))
        } throws IssueNotFoundException(IssueKey("ATLAS-99"))

        mockMvc.perform(get("/api/v1/issues/ATLAS-99/attachments"))
            .andExpect(status().isNotFound)
    }

    // ── C-8. 403 IssueAccessDeniedException ──────────────────────────────────

    /**
     * C-8. service 가 IssueAccessDeniedException → 403 Forbidden.
     *
     * Given  service.list 가 IssueAccessDeniedException 을 throw
     * When   GET /api/v1/issues/ATLAS-1/attachments
     * Then   403
     */
    @Test
    fun `GET 권한 없음 — 403 Forbidden`() {
        every {
            issueAttachmentService.list(any(), IssueKey("ATLAS-1"))
        } throws
            IssueAccessDeniedException(
                actor = ActorId(actorUuid),
                permission = com.bts.shared.permission.IssuePermission.VIEW,
                scope = com.bts.shared.permission.IssueScope.Issue("ATLAS-1"),
            )

        mockMvc.perform(get("/api/v1/issues/ATLAS-1/attachments"))
            .andExpect(status().isForbidden)
    }

    // ── C-9. 422 AttachmentInfectedException ─────────────────────────────────

    /**
     * C-9. service 가 AttachmentInfectedException → 422 + ISSUE_ATTACHMENT_INFECTED.
     *
     * 응답 detail 에 바이러스 시그니처명(파일명 포함 진단 정보)이 노출되지 않음을 함께 검증한다.
     *
     * Given  service.upload 가 AttachmentInfectedException("virus.exe") 를 throw
     * When   POST /api/v1/issues/ATLAS-1/attachments
     * Then   422, errorCode=ISSUE_ATTACHMENT_INFECTED, detail 에 "virus.exe" 미포함
     */
    @Test
    fun `POST 감염 파일 — 422 plus ISSUE_ATTACHMENT_INFECTED plus 시그니처 비노출`() {
        every {
            issueAttachmentService.upload(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                filename = "virus.exe",
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                sizeBytes = any(),
                input = any(),
            )
        } throws AttachmentInfectedException("virus.exe")

        val file = MockMultipartFile("file", "virus.exe", MediaType.APPLICATION_OCTET_STREAM_VALUE, "EICAR".toByteArray())

        mockMvc.perform(
            multipart("/api/v1/issues/ATLAS-1/attachments").file(file),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_ATTACHMENT_INFECTED"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("virus.exe"))))
    }

    // ── C-10. 503 AttachmentScanUnavailableException ──────────────────────────

    /**
     * C-10. service 가 AttachmentScanUnavailableException → 503 + ISSUE_ATTACHMENT_SCAN_UNAVAILABLE.
     *
     * Given  service.upload 가 AttachmentScanUnavailableException 을 throw
     * When   POST /api/v1/issues/ATLAS-1/attachments
     * Then   503, errorCode=ISSUE_ATTACHMENT_SCAN_UNAVAILABLE
     */
    @Test
    fun `POST 스캔 불가 — 503 plus ISSUE_ATTACHMENT_SCAN_UNAVAILABLE`() {
        every {
            issueAttachmentService.upload(
                actor = ActorId(actorUuid),
                issueKey = IssueKey("ATLAS-1"),
                filename = "file.png",
                contentType = "image/png",
                sizeBytes = any(),
                input = any(),
            )
        } throws AttachmentScanUnavailableException("clamd 연결 거부")

        val file = MockMultipartFile("file", "file.png", "image/png", "data".toByteArray())

        mockMvc.perform(
            multipart("/api/v1/issues/ATLAS-1/attachments").file(file),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_ATTACHMENT_SCAN_UNAVAILABLE"))
    }
}
