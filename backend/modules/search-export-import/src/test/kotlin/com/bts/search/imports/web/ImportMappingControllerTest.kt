// ImportMappingController MockMvc 슬라이스 테스트 — analyze/validate/confirm 3 엔드포인트 (FR-IM-02 PR-A Task 8)

package com.bts.search.imports.web

import com.bts.search.imports.job.application.ImportAccessDeniedException
import com.bts.search.imports.job.application.ImportAnalysisResult
import com.bts.search.imports.job.application.ImportFileTooLargeException
import com.bts.search.imports.job.application.ImportJobService
import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.mapping.ImportMappingInvalidException
import com.bts.search.imports.mapping.ImportMappingService
import com.bts.search.imports.mapping.ImportMappingStateConflictException
import com.bts.search.imports.mapping.ImportUserMappingInvalidException
import com.bts.search.imports.mapping.MappingIssue
import com.bts.search.imports.mapping.MappingValidationResult
import com.bts.search.imports.mapping.MappingValidator
import com.bts.search.imports.mapping.UserCollectionEntry
import com.bts.search.imports.mapping.UserCollectionResult
import com.bts.search.imports.web.dto.FieldMappingEntry
import com.bts.search.imports.web.dto.MappingConfirmRequest
import com.bts.search.imports.web.dto.MappingValidateRequest
import com.bts.search.imports.web.dto.UserMappingEntry
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * [ImportMappingController] MockMvc 슬라이스 테스트.
 *
 * ⚠️ [MockMvcBuilders.webAppContextSetup]으로 실제 Spring 컨텍스트 스캔을 태운다 — standalone
 * `setControllerAdvice`를 쓰면 [ImportExceptionHandler]의 `assignableTypes` 스코프가 무시된 채
 * advice가 항상 적용되어 스코프 누락이 가짜로 초록불이 될 수 있다(BLOCKER-2, 교훈
 * domain-exception-http-handler-basepackage-scope).
 *
 * [ImportJobService](analyze)와 [ImportMappingService](validate/confirm)를 MockK로 교체해
 * 컨트롤러·DTO·예외핸들러 계층만 검증한다.
 *
 * ### 검증 케이스
 * - C1 POST /imports/analyze multipart → 200 + 분석결과(jobId,status,format,sourceFields,sampleRows,targetFields)
 * - C2 POST /imports/analyze 미인증 → 401 IMPORT_UNAUTHENTICATED
 * - C3 POST /imports/analyze 권한없음 → 403 IMPORT_ACCESS_DENIED
 * - C4 POST /imports/analyze 파일 초과 → 413 IMPORT_FILE_TOO_LARGE
 * - C5 POST /imports/analyze projectKey CRLF → 400 (헤더 인젝션 방어)
 * - C6 POST /imports/{jobId}/mapping/validate → 200 + {valid,errors,warnings}
 * - C7 POST /imports/{jobId}/mapping/validate 미인증 → 401 (actor 추출 우선, 서비스 미호출 검증)
 * - C8 POST /imports/{jobId}/mapping/validate 타인/없음 → 404 IMPORT_NOT_FOUND
 * - C9 POST /imports/{jobId}/mapping/validate 상태충돌 → 409 IMPORT_MAPPING_STATE_CONFLICT
 * - C10 POST /imports/{jobId}/mapping → 200 + {jobId,status=PENDING}
 * - C11 POST /imports/{jobId}/mapping 검증실패 → 422 IMPORT_MAPPING_INVALID
 * - C12 POST /imports/{jobId}/mapping 타인/없음 → 404 IMPORT_NOT_FOUND
 * - C13 POST /imports/{jobId}/mapping 상태충돌 → 409 IMPORT_MAPPING_STATE_CONFLICT
 * - C14 POST /imports/{jobId}/mapping 미인증 → 401
 * - C15 POST /imports/{jobId}/mapping/users → 200 + UserCollectionResponse(users)
 * - C16 POST /imports/{jobId}/mapping/users 미인증 → 401 (actor 추출 우선, 서비스 미호출 검증)
 * - C17 POST /imports/{jobId}/mapping/users 타인/없음 → 404 IMPORT_NOT_FOUND
 * - C18 POST /imports/{jobId}/mapping/users 상태충돌 → 409 IMPORT_MAPPING_STATE_CONFLICT
 * - C19 POST /imports/{jobId}/mapping userMappings → 서비스에 Pair 목록으로 전달 확인
 * - C20 POST /imports/{jobId}/mapping 사용자매핑 검증실패 → 422 IMPORT_USER_MAPPING_INVALID
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ImportMappingControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ImportMappingControllerTest {
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun mockImportJobService(): ImportJobService = mockk(relaxed = true)

        @Bean
        open fun mockImportMappingService(): ImportMappingService = mockk(relaxed = true)

        @Bean
        open fun importMappingController(
            importJobService: ImportJobService,
            importMappingService: ImportMappingService,
        ): ImportMappingController = ImportMappingController(importJobService, importMappingService)

        @Bean
        open fun importExceptionHandler(): ImportExceptionHandler = ImportExceptionHandler()
    }

    @Autowired
    @Suppress("VarCouldBeVal") // Spring @Autowired lateinit은 val 불가 — detekt VarCouldBeVal 오탐
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var mockImportJobService: ImportJobService

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var mockImportMappingService: ImportMappingService

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(mockImportJobService, mockImportMappingService)
        setAuthenticated(actorId)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── C1 POST /imports/analyze → 200 분석결과 ──────────────────────────────

    @Test
    fun `C1 - POST imports analyze 200 jobId status AWAITING_MAPPING sourceFields sampleRows targetFields`() {
        val job = makeAwaitingMappingJob(actorId)
        val result =
            ImportAnalysisResult(
                job = job,
                sourceFields = listOf("제목", "설명"),
                sampleRows = listOf(listOf("이슈1", "설명1")),
            )
        every { mockImportJobService.analyze(any()) } returns result

        mockMvc.perform(analyzeRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value(job.id.value.toString()))
            .andExpect(jsonPath("$.status").value("AWAITING_MAPPING"))
            .andExpect(jsonPath("$.format").value("CSV"))
            .andExpect(jsonPath("$.sourceFields[0].name").value("제목"))
            .andExpect(jsonPath("$.sourceFields[1].name").value("설명"))
            .andExpect(jsonPath("$.sampleRows[0][0]").value("이슈1"))
            .andExpect(jsonPath("$.targetFields").isArray)
            .andExpect(jsonPath("$.targetFields.length()").value(11))
            .andExpect(jsonPath("$.targetFields[0].key").value("summary"))
            .andExpect(jsonPath("$.targetFields[0].required").value(true))
    }

    // ── C2 POST /imports/analyze 미인증 → 401 ─────────────────────────────────

    @Test
    fun `C2 - POST imports analyze 미인증 401 IMPORT_UNAUTHENTICATED`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(analyzeRequest())
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_UNAUTHENTICATED"))
        verify(exactly = 0) { mockImportJobService.analyze(any()) }
    }

    // ── C3 POST /imports/analyze 권한없음 → 403 ───────────────────────────────

    @Test
    fun `C3 - POST imports analyze 권한없음 403 IMPORT_ACCESS_DENIED`() {
        every { mockImportJobService.analyze(any()) } throws ImportAccessDeniedException("no CREATE permission")

        mockMvc.perform(analyzeRequest())
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_ACCESS_DENIED"))
    }

    // ── C4 POST /imports/analyze 파일 초과 → 413 ──────────────────────────────

    @Test
    fun `C4 - POST imports analyze 파일 초과 413 IMPORT_FILE_TOO_LARGE`() {
        every { mockImportJobService.analyze(any()) } throws
            ImportFileTooLargeException(sizeBytes = 60_000_000L, maxBytes = 50_000_000L)

        mockMvc.perform(analyzeRequest())
            .andExpect(status().isPayloadTooLarge)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_FILE_TOO_LARGE"))
    }

    // ── C5 POST /imports/analyze projectKey CRLF → 400 ────────────────────────

    @Test
    fun `C5 - POST imports analyze projectKey CRLF 400 헤더 인젝션 방어`() {
        mockMvc.perform(
            multipart("/api/v1/imports/analyze")
                .file(csvFile())
                .param("projectKey", "PROJ\r\nX-Injected: evil")
                .param("format", "CSV"),
        )
            .andExpect(status().isBadRequest)
        verify(exactly = 0) { mockImportJobService.analyze(any()) }
    }

    // ── C6 POST /mapping/validate → 200 ───────────────────────────────────────

    @Test
    fun `C6 - POST imports jobId mapping validate 200 valid errors warnings`() {
        val jobId = ImportJobId(UUID.randomUUID())
        val validationResult =
            MappingValidationResult(
                valid = false,
                errors = listOf(MappingIssue(MappingValidator.SUMMARY_NOT_MAPPED, "summary 미매핑")),
                warnings = listOf(MappingIssue(MappingValidator.SOURCE_FIELD_IGNORED, "비고 무시됨", "비고")),
            )
        every {
            mockImportMappingService.validate(jobId, actorId, mapOf("제목" to "summary"))
        } returns validationResult

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(validateRequestBody())),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.errors[0].code").value(MappingValidator.SUMMARY_NOT_MAPPED))
            .andExpect(jsonPath("$.warnings[0].field").value("비고"))
    }

    // ── C7 POST /mapping/validate 미인증 → 401 (actor 추출 우선) ──────────────

    @Test
    fun `C7 - POST imports jobId mapping validate 미인증 401 actor 추출 우선 서비스 미호출`() {
        SecurityContextHolder.clearContext()
        val jobId = ImportJobId(UUID.randomUUID())

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(validateRequestBody())),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_UNAUTHENTICATED"))
        verify(exactly = 0) { mockImportMappingService.validate(any(), any(), any()) }
    }

    // ── C8 POST /mapping/validate 타인/없음 → 404 ─────────────────────────────

    @Test
    fun `C8 - POST imports jobId mapping validate 타인 404 존재 은닉 IMPORT_NOT_FOUND`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { mockImportMappingService.validate(jobId, actorId, any()) } throws
            ResponseStatusException(HttpStatus.NOT_FOUND, "Import 작업을 찾을 수 없습니다.")

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(validateRequestBody())),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_NOT_FOUND"))
    }

    // ── C9 POST /mapping/validate 상태충돌 → 409 ──────────────────────────────

    @Test
    fun `C9 - POST imports jobId mapping validate 상태충돌 409 IMPORT_MAPPING_STATE_CONFLICT`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { mockImportMappingService.validate(jobId, actorId, any()) } throws
            ImportMappingStateConflictException()

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(validateRequestBody())),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_MAPPING_STATE_CONFLICT"))
    }

    // ── C10 POST /mapping → 200 jobId status PENDING ──────────────────────────

    @Test
    fun `C10 - POST imports jobId mapping 200 jobId status PENDING`() {
        val jobId = ImportJobId(UUID.randomUUID())
        val confirmedJob = makePendingJob(jobId, actorId)
        every {
            mockImportMappingService.confirm(jobId, actorId, mapOf("제목" to "summary"), false)
        } returns confirmedJob

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(confirmRequestBody())),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value(jobId.value.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    // ── C11 POST /mapping 검증실패 → 422 ──────────────────────────────────────

    @Test
    fun `C11 - POST imports jobId mapping 검증실패 422 IMPORT_MAPPING_INVALID`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { mockImportMappingService.confirm(jobId, actorId, any(), any()) } throws
            ImportMappingInvalidException(
                listOf(MappingIssue(MappingValidator.SUMMARY_NOT_MAPPED, "summary 미매핑")),
            )

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(confirmRequestBody())),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_MAPPING_INVALID"))
    }

    // ── C12 POST /mapping 타인/없음 → 404 ─────────────────────────────────────

    @Test
    fun `C12 - POST imports jobId mapping 타인 404 존재 은닉 IMPORT_NOT_FOUND`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { mockImportMappingService.confirm(jobId, actorId, any(), any()) } throws
            ResponseStatusException(HttpStatus.NOT_FOUND, "Import 작업을 찾을 수 없습니다.")

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(confirmRequestBody())),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_NOT_FOUND"))
    }

    // ── C13 POST /mapping 상태충돌 → 409 ──────────────────────────────────────

    @Test
    fun `C13 - POST imports jobId mapping 상태충돌 409 IMPORT_MAPPING_STATE_CONFLICT`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { mockImportMappingService.confirm(jobId, actorId, any(), any()) } throws
            ImportMappingStateConflictException()

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(confirmRequestBody())),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_MAPPING_STATE_CONFLICT"))
    }

    // ── C14 POST /mapping 미인증 → 401 ────────────────────────────────────────

    @Test
    fun `C14 - POST imports jobId mapping 미인증 401 IMPORT_UNAUTHENTICATED`() {
        SecurityContextHolder.clearContext()
        val jobId = ImportJobId(UUID.randomUUID())

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(confirmRequestBody())),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_UNAUTHENTICATED"))
        verify(exactly = 0) { mockImportMappingService.confirm(any(), any(), any(), any()) }
    }

    // ── C15 POST /mapping/users → 200 UserCollectionResponse ──────────────────

    @Test
    fun `C15 - POST imports jobId mapping users 200 UserCollectionResponse`() {
        val jobId = ImportJobId(UUID.randomUUID())
        val collectionResult =
            UserCollectionResult(
                users =
                    listOf(
                        UserCollectionEntry(
                            sourceIdentifier = "alice@example.com",
                            suggestedUserId = actorId,
                            suggestedDisplayName = "Alice",
                        ),
                        UserCollectionEntry(
                            sourceIdentifier = "bob@example.com",
                            suggestedUserId = null,
                            suggestedDisplayName = null,
                        ),
                    ),
            )
        every {
            mockImportMappingService.collectUsers(jobId, actorId, mapOf("제목" to "summary"))
        } returns collectionResult

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(validateRequestBody())),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.users[0].sourceIdentifier").value("alice@example.com"))
            .andExpect(jsonPath("$.users[0].suggestedUserId").value(actorId.toString()))
            .andExpect(jsonPath("$.users[0].suggestedDisplayName").value("Alice"))
            .andExpect(jsonPath("$.users[1].sourceIdentifier").value("bob@example.com"))
            .andExpect(jsonPath("$.users[1].suggestedUserId").doesNotExist())
    }

    // ── C16 POST /mapping/users 미인증 → 401 (actor 추출 우선) ─────────────────

    @Test
    fun `C16 - POST imports jobId mapping users 미인증 401 actor 추출 우선 서비스 미호출`() {
        SecurityContextHolder.clearContext()
        val jobId = ImportJobId(UUID.randomUUID())

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(validateRequestBody())),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_UNAUTHENTICATED"))
        verify(exactly = 0) { mockImportMappingService.collectUsers(any(), any(), any()) }
    }

    // ── C17 POST /mapping/users 타인/없음 → 404 ────────────────────────────────

    @Test
    fun `C17 - POST imports jobId mapping users 타인 404 존재 은닉 IMPORT_NOT_FOUND`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { mockImportMappingService.collectUsers(jobId, actorId, any()) } throws
            ResponseStatusException(HttpStatus.NOT_FOUND, "Import 작업을 찾을 수 없습니다.")

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(validateRequestBody())),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_NOT_FOUND"))
    }

    // ── C18 POST /mapping/users 상태충돌 → 409 ─────────────────────────────────

    @Test
    fun `C18 - POST imports jobId mapping users 상태충돌 409 IMPORT_MAPPING_STATE_CONFLICT`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { mockImportMappingService.collectUsers(jobId, actorId, any()) } throws
            ImportMappingStateConflictException()

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(validateRequestBody())),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_MAPPING_STATE_CONFLICT"))
    }

    // ── C19 POST /mapping userMappings → 서비스에 Pair 목록 전달 확인 ──────────

    @Test
    fun `C19 - POST imports jobId mapping userMappings 서비스에 Pair 목록으로 전달`() {
        val jobId = ImportJobId(UUID.randomUUID())
        val confirmedJob = makePendingJob(jobId, actorId)
        val targetUserId = UUID.randomUUID()
        every {
            mockImportMappingService.confirm(
                jobId,
                actorId,
                mapOf("제목" to "summary"),
                false,
                listOf("alice@example.com" to targetUserId, "bob@example.com" to null),
            )
        } returns confirmedJob

        val requestBody =
            MappingConfirmRequest(
                fieldMappings = listOf(FieldMappingEntry("제목", "summary")),
                dryRun = false,
                userMappings =
                    listOf(
                        UserMappingEntry("alice@example.com", targetUserId),
                        UserMappingEntry("bob@example.com", null),
                    ),
            )

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value(jobId.value.toString()))
        verify(exactly = 1) {
            mockImportMappingService.confirm(
                jobId,
                actorId,
                mapOf("제목" to "summary"),
                false,
                listOf("alice@example.com" to targetUserId, "bob@example.com" to null),
            )
        }
    }

    // ── C20 POST /mapping 사용자매핑 검증실패 → 422 IMPORT_USER_MAPPING_INVALID ─

    @Test
    fun `C20 - POST imports jobId mapping 사용자매핑 검증실패 422 IMPORT_USER_MAPPING_INVALID`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { mockImportMappingService.confirm(jobId, actorId, any(), any(), any()) } throws
            ImportUserMappingInvalidException(
                listOf(
                    MappingIssue(
                        ImportUserMappingInvalidException.DUPLICATE_SOURCE_IDENTIFIER,
                        "정규화 시 중복되는 사용자 매핑 소스 식별자입니다: alice@example.com",
                        "alice@example.com",
                    ),
                ),
            )

        mockMvc.perform(
            post("/api/v1/imports/${jobId.value}/mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(confirmRequestBody())),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("IMPORT_USER_MAPPING_INVALID"))
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private fun csvFile(): MockMultipartFile =
        MockMultipartFile(
            "file",
            "issues.csv",
            "text/csv",
            "제목,설명\n이슈1,설명1\n".toByteArray(),
        )

    private fun analyzeRequest(
        projectKey: String = "PROJ",
        format: String = "CSV",
    ) = multipart("/api/v1/imports/analyze")
        .file(csvFile())
        .param("projectKey", projectKey)
        .param("format", format)

    private fun validateRequestBody(): MappingValidateRequest =
        MappingValidateRequest(fieldMappings = listOf(FieldMappingEntry("제목", "summary")))

    private fun confirmRequestBody(): MappingConfirmRequest =
        MappingConfirmRequest(fieldMappings = listOf(FieldMappingEntry("제목", "summary")), dryRun = false)

    private fun setAuthenticated(userId: UUID) {
        val auth =
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }

    private fun makeAwaitingMappingJob(
        requesterUserId: UUID,
        projectKey: String = "PROJ",
        format: String = "CSV",
    ): ImportJob =
        ImportJob(
            id = ImportJobId(UUID.randomUUID()),
            projectKey = projectKey,
            format = format,
            sourceObjectKey = "$projectKey/${UUID.randomUUID()}.${format.lowercase()}",
            dryRun = false,
            requesterUserId = requesterUserId,
            status = ImportJobStatus.AWAITING_MAPPING,
            progress = 0,
            totalRows = null,
            succeededRows = 0,
            failedRows = 0,
            errorCode = null,
            errorLogObjectKey = null,
            expiresAt = Instant.now().plusSeconds(86_400),
            createdAt = Instant.now(),
            startedAt = null,
            completedAt = null,
        )

    private fun makePendingJob(
        id: ImportJobId,
        requesterUserId: UUID,
        projectKey: String = "PROJ",
    ): ImportJob =
        ImportJob(
            id = id,
            projectKey = projectKey,
            format = "CSV",
            sourceObjectKey = "$projectKey/${id.value}.csv",
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
}
