// ExportJobController MockMvc 슬라이스 테스트 — POST/GET/다운로드 케이스 전체 (FR-EX-02 Task 10)

package com.bts.search.web

import com.bts.search.aql.AqlErrorCode
import com.bts.search.aql.AqlSyntaxException
import com.bts.search.export.job.application.ExportJobService
import com.bts.search.export.job.domain.ExportJob
import com.bts.search.export.job.domain.ExportJobId
import com.bts.search.export.job.domain.ExportJobStatus
import com.bts.search.export.job.repository.ExportJobRepository
import com.bts.search.export.job.storage.ExportObjectStoragePort
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

/**
 * ExportJobController MockMvc 슬라이스 테스트.
 *
 * ExportJobService / ExportJobRepository / ExportObjectStoragePort 를 MockK 로 교체해
 * 컨트롤러·DTO·예외핸들러 계층만 검증한다.
 *
 * ### 검증 케이스 (12개)
 *
 * - C1 POST → 202 + {jobId, status:"PENDING"}
 * - C2 POST AQL 문법 오류 → 400 SEARCH_SYNTAX_ERROR
 * - C3 GET /{id} 본인 → 200 폴링 DTO
 * - C4 GET /{id} 타인 → 404 (존재 은닉)
 * - C5 GET /{id}/download COMPLETED 본인 → 200 + Content-Disposition attachment
 * - C6 GET /{id}/download 미완료 → 409 SEARCH_EXPORT_NOT_READY
 * - C7 GET /{id}/download 타인/없음 → 404
 * - C8 미인증 → 401 SEARCH_UNAUTHENTICATED (401→500 변질 차단 회귀 가드)
 * - C9 Content-Disposition 인젝션 방어 — storage openStream 파일명 sanitize
 * - C10 POST validation 오류 → 400
 * - C11 POST projectKey CRLF → 400 (헤더 인젝션 방어)
 * - C12 GET /{id}/download 미완료(RUNNING) → 409 SEARCH_EXPORT_NOT_READY
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ExportJobControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ExportJobControllerTest {

    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun mockExportJobService(): ExportJobService = mockk(relaxed = true)

        @Bean
        open fun mockExportJobRepository(): ExportJobRepository = mockk(relaxed = true)

        @Bean
        open fun mockExportObjectStoragePort(): ExportObjectStoragePort = mockk(relaxed = true)

        @Bean
        open fun exportJobController(
            service: ExportJobService,
            repo: ExportJobRepository,
            storage: ExportObjectStoragePort,
        ): ExportJobController = ExportJobController(service, repo, storage)

        @Bean
        open fun exportJobExceptionHandler(): ExportJobExceptionHandler = ExportJobExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var mockExportJobService: ExportJobService

    @Autowired
    private lateinit var mockExportJobRepository: ExportJobRepository

    @Autowired
    private lateinit var mockExportObjectStoragePort: ExportObjectStoragePort

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(mockExportJobService, mockExportJobRepository, mockExportObjectStoragePort)
        setAuthenticated(actorId)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── C1 POST → 202 PENDING ─────────────────────────────────────────────────

    @Test
    fun `C1 - POST export-jobs 202 jobId status PENDING`() {
        val jobId = ExportJobId(UUID.randomUUID())
        every { mockExportJobService.submit(any(), actorId) } returns jobId

        mockMvc.perform(
            post("/api/v1/search/export-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody()),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.jobId").value(jobId.value.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    // ── C2 AQL 문법 오류 → 400 SEARCH_SYNTAX_ERROR ────────────────────────────

    @Test
    fun `C2 - AQL 문법오류 400 SEARCH_SYNTAX_ERROR`() {
        every { mockExportJobService.submit(any(), any()) } throws
            AqlSyntaxException("문법 오류", position = 5, errorCode = AqlErrorCode.SEARCH_SYNTAX_ERROR)

        mockMvc.perform(
            post("/api/v1/search/export-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_SYNTAX_ERROR"))
    }

    // ── C3 GET /{id} 본인 → 200 폴링 DTO ────────────────────────────────────

    @Test
    fun `C3 - GET export-jobs id 본인 200 폴링 DTO`() {
        val jobId = ExportJobId(UUID.randomUUID())
        val job = makePendingJob(jobId, actorId)
        every { mockExportJobRepository.findByIdForRequester(jobId, actorId) } returns job

        mockMvc.perform(get("/api/v1/search/export-jobs/${jobId.value}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value(jobId.value.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.format").value("CSV"))
            .andExpect(jsonPath("$.downloadReady").value(false))
    }

    // ── C4 GET /{id} 타인 → 404 (존재 은닉) ──────────────────────────────────

    @Test
    fun `C4 - GET export-jobs id 타인 404 존재 은닉`() {
        val jobId = ExportJobId(UUID.randomUUID())
        every { mockExportJobRepository.findByIdForRequester(jobId, actorId) } returns null

        mockMvc.perform(get("/api/v1/search/export-jobs/${jobId.value}"))
            .andExpect(status().isNotFound)
    }

    // ── C5 GET /{id}/download COMPLETED 본인 → 200 stream ───────────────────

    @Test
    fun `C5 - GET download COMPLETED 본인 200 Content-Disposition attachment`() {
        val jobId = ExportJobId(UUID.randomUUID())
        val job = makeCompletedJob(jobId, actorId, objectKey = "PROJ/${jobId.value}.csv")
        every { mockExportJobRepository.findByIdForRequester(jobId, actorId) } returns job
        every { mockExportObjectStoragePort.openStream("PROJ/${jobId.value}.csv") } returns
            ByteArrayInputStream("Key,Summary\r\n".toByteArray())

        mockMvc.perform(get("/api/v1/search/export-jobs/${jobId.value}/download"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", containsString("attachment")))
    }

    // ── C6 GET /{id}/download PENDING → 409 SEARCH_EXPORT_NOT_READY ─────────

    @Test
    fun `C6 - GET download PENDING 409 SEARCH_EXPORT_NOT_READY`() {
        val jobId = ExportJobId(UUID.randomUUID())
        val job = makePendingJob(jobId, actorId)
        every { mockExportJobRepository.findByIdForRequester(jobId, actorId) } returns job

        mockMvc.perform(get("/api/v1/search/export-jobs/${jobId.value}/download"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_EXPORT_NOT_READY"))
    }

    // ── C7 GET /{id}/download 타인 → 404 ────────────────────────────────────

    @Test
    fun `C7 - GET download 타인 또는 없음 404`() {
        val jobId = ExportJobId(UUID.randomUUID())
        every { mockExportJobRepository.findByIdForRequester(jobId, actorId) } returns null

        mockMvc.perform(get("/api/v1/search/export-jobs/${jobId.value}/download"))
            .andExpect(status().isNotFound)
    }

    // ── C8 미인증 → 401 SEARCH_UNAUTHENTICATED ────────────────────────────────

    @Test
    fun `C8 - 미인증 401 SEARCH_UNAUTHENTICATED 401→500 변질 차단`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(
            post("/api/v1/search/export-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody()),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_UNAUTHENTICATED"))
    }

    // ── C9 Content-Disposition 인젝션 방어 ───────────────────────────────────

    @Test
    fun `C9 - download 파일명 CRLF 포함 projectKey sanitize 적용`() {
        val jobId = ExportJobId(UUID.randomUUID())
        // projectKey 에 CRLF 가 포함된 악성 job (objectKey 에 반영돼 Content-Disposition 인젝션 시도)
        val evilJob =
            makeCompletedJob(
                jobId,
                actorId,
                objectKey = "evil\r\nX-Injected-Header: pwned/${jobId.value}.csv",
                projectKey = "evil\r\nX-Injected",
            )
        every { mockExportJobRepository.findByIdForRequester(jobId, actorId) } returns evilJob
        every {
            mockExportObjectStoragePort.openStream(any())
        } returns ByteArrayInputStream("bytes".toByteArray())

        // sanitize 후 Content-Disposition 헤더에 개행 문자가 없어야 한다
        val result =
            mockMvc.perform(get("/api/v1/search/export-jobs/${jobId.value}/download"))
                .andExpect(status().isOk)
                .andReturn()

        val contentDisposition = result.response.getHeader("Content-Disposition") ?: ""
        assert(!contentDisposition.contains('\r') && !contentDisposition.contains('\n')) {
            "Content-Disposition 에 헤더 인젝션 문자(CRLF) 가 포함되면 안 됩니다: $contentDisposition"
        }
    }

    // ── C10 POST validation 오류 → 400 ───────────────────────────────────────

    @Test
    fun `C10 - POST projectKey 없음 400`() {
        mockMvc.perform(
            post("/api/v1/search/export-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("query" to "status = open", "format" to "CSV"))),
        )
            .andExpect(status().isBadRequest)
    }

    // ── C11 POST projectKey CRLF → 400 (헤더 인젝션 방어) ────────────────────

    @Test
    fun `C11 - POST projectKey CRLF 400 헤더 인젝션 방어`() {
        mockMvc.perform(
            post("/api/v1/search/export-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(projectKey = "PROJ\r\nX-Injected: evil")),
        )
            .andExpect(status().isBadRequest)
    }

    // ── C12 GET download RUNNING → 409 SEARCH_EXPORT_NOT_READY ──────────────

    @Test
    fun `C12 - GET download RUNNING 409 SEARCH_EXPORT_NOT_READY`() {
        val jobId = ExportJobId(UUID.randomUUID())
        val runningJob =
            makePendingJob(jobId, actorId).copy(
                status = ExportJobStatus.RUNNING,
                startedAt = Instant.now(),
            )
        every { mockExportJobRepository.findByIdForRequester(jobId, actorId) } returns runningJob

        mockMvc.perform(get("/api/v1/search/export-jobs/${jobId.value}/download"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_EXPORT_NOT_READY"))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun submitBody(
        projectKey: String = "PROJ",
        query: String = "status = open",
        format: String = "CSV",
    ): String =
        mapper.writeValueAsString(
            mapOf("projectKey" to projectKey, "query" to query, "format" to format),
        )

    private fun setAuthenticated(userId: UUID) {
        val auth =
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }

    private fun makePendingJob(
        id: ExportJobId,
        requesterUserId: UUID,
    ): ExportJob =
        ExportJob(
            id = id,
            projectKey = "PROJ",
            query = "status = OPEN",
            format = "CSV",
            columns = emptyList(),
            requesterUserId = requesterUserId,
            status = ExportJobStatus.PENDING,
            progress = 0,
            rowCount = null,
            resultObjectKey = null,
            errorCode = null,
            expiresAt = null,
            createdAt = Instant.now(),
            startedAt = null,
            completedAt = null,
        )

    private fun makeCompletedJob(
        id: ExportJobId,
        requesterUserId: UUID,
        objectKey: String,
        projectKey: String = "PROJ",
    ): ExportJob =
        ExportJob(
            id = id,
            projectKey = projectKey,
            query = "status = OPEN",
            format = "CSV",
            columns = emptyList(),
            requesterUserId = requesterUserId,
            status = ExportJobStatus.COMPLETED,
            progress = 100,
            rowCount = 100L,
            resultObjectKey = objectKey,
            errorCode = null,
            expiresAt = Instant.now().plusSeconds(86_400),
            createdAt = Instant.now(),
            startedAt = Instant.now(),
            completedAt = Instant.now(),
        )
}
