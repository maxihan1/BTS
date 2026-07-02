// ImportController MockMvc 슬라이스 테스트 — POST 접수/GET 폴링/GET 에러로그 (FR-IM-01 PR1 Task 11)

package com.bts.search.imports.web

import com.bts.search.imports.job.application.ImportAccessDeniedException
import com.bts.search.imports.job.application.ImportErrorLogResult
import com.bts.search.imports.job.application.ImportFileTooLargeException
import com.bts.search.imports.job.application.ImportJobService
import com.bts.search.imports.job.application.ImportUnsupportedFormatException
import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

/**
 * [ImportController] MockMvc 슬라이스 테스트.
 *
 * [ImportJobService] 를 MockK 로 교체해 컨트롤러·DTO·예외핸들러 계층만 검증한다.
 * 읽기/쓰기 로직 전체가 [ImportJobService] 에 위임되므로(Export 의 repo/storage 직접 주입 방식과 달리),
 * 이 슬라이스는 서비스 한 개만 mock 하면 된다.
 *
 * ### 검증 케이스
 * - C1 POST /api/v1/imports multipart → 202 + jobId
 * - C2 POST 권한 없음 → 403 IMPORT_ACCESS_DENIED
 * - C3 POST 파일 초과 → 413 IMPORT_FILE_TOO_LARGE
 * - C4 POST 미지원 format → 400 IMPORT_UNSUPPORTED_FORMAT
 * - C5 POST projectKey 없음 → 400
 * - C6 POST projectKey CRLF → 400 (헤더 인젝션 방어)
 * - C7 GET /{jobId} 본인 → 200 폴링 DTO
 * - C8 GET /{jobId} 타인/없음 → 404 IMPORT_NOT_FOUND (존재 은닉)
 * - C9 GET /{jobId}/errors 준비됨 → 200 text/csv + Content-Disposition attachment
 * - C10 GET /{jobId}/errors 미완료/타인 → 404 IMPORT_NOT_FOUND
 * - C11 미인증 → 401 IMPORT_UNAUTHENTICATED (401→500 변질 차단 회귀 가드)
 * - C12 GET /{jobId}/errors 파일명 CRLF sanitize (Content-Disposition 인젝션 방어)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ImportControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ImportControllerTest {
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun mockImportJobService(): ImportJobService = mockk(relaxed = true)

        @Bean
        open fun importController(service: ImportJobService): ImportController = ImportController(service)

        @Bean
        open fun importExceptionHandler(): ImportExceptionHandler = ImportExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var mockImportJobService: ImportJobService

    private lateinit var mockMvc: MockMvc
    private val actorId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(mockImportJobService)
        setAuthenticated(actorId)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── C1 POST → 202 jobId ───────────────────────────────────────────────────

    @Test
    fun `C1 - POST imports 202 jobId status PENDING`() {
        val job = makePendingJob(actorId)
        every { mockImportJobService.accept(any()) } returns job

        mockMvc.perform(uploadRequest())
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.jobId").value(job.id.value.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    // ── C2 POST 권한 없음 → 403 ───────────────────────────────────────────────

    @Test
    fun `C2 - POST 권한 없음 403 IMPORT_ACCESS_DENIED`() {
        every { mockImportJobService.accept(any()) } throws ImportAccessDeniedException("no CREATE permission")

        mockMvc.perform(uploadRequest())
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_ACCESS_DENIED"))
    }

    // ── C3 POST 파일 초과 → 413 ───────────────────────────────────────────────

    @Test
    fun `C3 - POST 파일 초과 413 IMPORT_FILE_TOO_LARGE`() {
        every { mockImportJobService.accept(any()) } throws
            ImportFileTooLargeException(sizeBytes = 60_000_000L, maxBytes = 50_000_000L)

        mockMvc.perform(uploadRequest())
            .andExpect(status().isPayloadTooLarge)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_FILE_TOO_LARGE"))
    }

    // ── C4 POST 미지원 format → 400 ───────────────────────────────────────────

    @Test
    fun `C4 - POST 미지원 format 400 IMPORT_UNSUPPORTED_FORMAT`() {
        every { mockImportJobService.accept(any()) } throws ImportUnsupportedFormatException("XML")

        mockMvc.perform(uploadRequest(format = "XML"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_UNSUPPORTED_FORMAT"))
    }

    // ── C5 POST projectKey 없음 → 400 ─────────────────────────────────────────

    @Test
    fun `C5 - POST projectKey 없음 400`() {
        mockMvc.perform(
            multipart("/api/v1/imports")
                .file(csvFile())
                .param("projectKey", "")
                .param("format", "CSV"),
        )
            .andExpect(status().isBadRequest)
    }

    // ── C6 POST projectKey CRLF → 400 (헤더 인젝션 방어) ──────────────────────

    @Test
    fun `C6 - POST projectKey CRLF 400 헤더 인젝션 방어`() {
        mockMvc.perform(
            multipart("/api/v1/imports")
                .file(csvFile())
                .param("projectKey", "PROJ\r\nX-Injected: evil")
                .param("format", "CSV"),
        )
            .andExpect(status().isBadRequest)
    }

    // ── C7 GET /{jobId} 본인 → 200 폴링 DTO ──────────────────────────────────

    @Test
    fun `C7 - GET imports jobId 본인 200 폴링 DTO`() {
        val job = makePendingJob(actorId)
        every { mockImportJobService.getForRequester(job.id, actorId) } returns job

        mockMvc.perform(get("/api/v1/imports/${job.id.value}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value(job.id.value.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.dryRun").value(false))
            .andExpect(jsonPath("$.errorLogReady").value(false))
    }

    // ── C8 GET /{jobId} 타인/없음 → 404 (존재 은닉) ──────────────────────────

    @Test
    fun `C8 - GET imports jobId 타인 404 존재 은닉 IMPORT_NOT_FOUND`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { mockImportJobService.getForRequester(jobId, actorId) } returns null

        mockMvc.perform(get("/api/v1/imports/${jobId.value}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_NOT_FOUND"))
    }

    // ── C9 GET /{jobId}/errors 준비됨 → 200 text/csv ─────────────────────────

    @Test
    fun `C9 - GET imports jobId errors 준비됨 200 Content-Disposition attachment text-csv`() {
        val jobId = ImportJobId(UUID.randomUUID())
        val job = makeCompletedJob(jobId, actorId, projectKey = "PROJ")
        val stream = ByteArrayInputStream("row,reason\n1,VALIDATION\n".toByteArray())
        every { mockImportJobService.getErrorLog(jobId, actorId) } returns ImportErrorLogResult(job, stream)

        mockMvc.perform(get("/api/v1/imports/${jobId.value}/errors"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", containsString("attachment")))
            .andExpect(header().string("Content-Type", containsString("text/csv")))
    }

    // ── C10 GET /{jobId}/errors 미완료/타인 → 404 ────────────────────────────

    @Test
    fun `C10 - GET imports jobId errors 미완료 또는 타인 404 IMPORT_NOT_FOUND`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { mockImportJobService.getErrorLog(jobId, actorId) } throws
            ResponseStatusException(HttpStatus.NOT_FOUND)

        mockMvc.perform(get("/api/v1/imports/${jobId.value}/errors"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_NOT_FOUND"))
    }

    // ── C11 미인증 → 401 (401→500 변질 차단) ─────────────────────────────────

    @Test
    fun `C11 - 미인증 401 IMPORT_UNAUTHENTICATED 401→500 변질 차단`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(uploadRequest())
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_UNAUTHENTICATED"))
    }

    // ── C12 GET /{jobId}/errors 파일명 CRLF sanitize ─────────────────────────

    @Test
    fun `C12 - GET imports jobId errors 파일명 CRLF projectKey sanitize 적용`() {
        val jobId = ImportJobId(UUID.randomUUID())
        val evilJob = makeCompletedJob(jobId, actorId, projectKey = "evil\r\nX-Injected")
        val stream = ByteArrayInputStream("bytes".toByteArray())
        every { mockImportJobService.getErrorLog(jobId, actorId) } returns ImportErrorLogResult(evilJob, stream)

        val result =
            mockMvc.perform(get("/api/v1/imports/${jobId.value}/errors"))
                .andExpect(status().isOk)
                .andReturn()

        val contentDisposition = result.response.getHeader("Content-Disposition") ?: ""
        assert(!contentDisposition.contains('\r') && !contentDisposition.contains('\n')) {
            "Content-Disposition 에 헤더 인젝션 문자(CRLF) 가 포함되면 안 됩니다: $contentDisposition"
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private fun csvFile(): MockMultipartFile =
        MockMultipartFile(
            "file",
            "issues.csv",
            "text/csv",
            "summary\nTest issue\n".toByteArray(),
        )

    private fun uploadRequest(
        projectKey: String = "PROJ",
        format: String = "CSV",
    ) = multipart("/api/v1/imports")
        .file(csvFile())
        .param("projectKey", projectKey)
        .param("format", format)

    private fun setAuthenticated(userId: UUID) {
        val auth =
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }

    private fun makePendingJob(requesterUserId: UUID): ImportJob =
        ImportJob(
            id = ImportJobId(UUID.randomUUID()),
            projectKey = "PROJ",
            format = "CSV",
            sourceObjectKey = "PROJ/${UUID.randomUUID()}.csv",
            dryRun = false,
            requesterUserId = requesterUserId,
            status = ImportJobStatus.PENDING,
            progress = 0,
            totalRows = null,
            succeededRows = 0,
            failedRows = 0,
            errorCode = null,
            errorLogObjectKey = null,
            expiresAt = null,
            createdAt = Instant.now(),
            startedAt = null,
            completedAt = null,
        )

    private fun makeCompletedJob(
        id: ImportJobId,
        requesterUserId: UUID,
        projectKey: String,
    ): ImportJob =
        ImportJob(
            id = id,
            projectKey = projectKey,
            format = "CSV",
            sourceObjectKey = "$projectKey/${id.value}.csv",
            dryRun = false,
            requesterUserId = requesterUserId,
            status = ImportJobStatus.COMPLETED,
            progress = 100,
            totalRows = 10L,
            succeededRows = 9L,
            failedRows = 1L,
            errorCode = null,
            errorLogObjectKey = "$projectKey/${id.value}-errors.csv",
            expiresAt = Instant.now().plusSeconds(86_400),
            createdAt = Instant.now(),
            startedAt = Instant.now(),
            completedAt = Instant.now(),
        )
}
