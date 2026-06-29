// ExportController MockMvc 슬라이스 테스트 — POST /api/v1/search/export 케이스 전체 (FR-EX-01 Task 5)

package com.bts.search.web

import com.bts.search.aql.AqlErrorCode
import com.bts.search.aql.AqlSyntaxException
import com.bts.search.export.ExportFormat
import com.bts.search.export.ExportLimitExceededException
import com.bts.search.export.ExportResult
import com.bts.search.export.ExportService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * ExportController MockMvc 슬라이스 테스트.
 *
 * [ExportService]를 MockK로 교체해 컨트롤러·DTO·예외핸들러 계층만 검증한다.
 * Spring Security 컨텍스트는 [SecurityContextHolder]에 직접 UUID 기반 Authentication을 주입한다.
 *
 * ### 검증 케이스 (9개)
 *
 * - C1 CSV 200 — text/csv;charset=UTF-8 + Content-Disposition + BOM
 * - C2 XLSX 200 — xlsx contentType + Content-Disposition
 * - C3 상한초과 400 — SEARCH_EXPORT_LIMIT_EXCEEDED + resultCount/limit property
 * - C4 AQL 문법오류 400
 * - C5 format 오타("PDF") 400 — 허용값 메시지(CSV/XLSX 포함)
 * - C6 columns 미지원 400 — SEARCH_VALIDATION_FAILED
 * - C7 projectKey/query 누락 400 — 수동 검증
 * - C8 SecurityException → 403 SEARCH_ACCESS_DENIED
 * - C9 projectKey CRLF → 400 (헤더 인젝션 방어)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ExportControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ExportControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [ExportController], [ExportExceptionHandler]와 MockK stub Bean을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun mockExportService(): ExportService = mockk(relaxed = true)

        @Bean
        open fun exportController(service: ExportService): ExportController = ExportController(service)

        @Bean
        open fun exportExceptionHandler(): ExportExceptionHandler = ExportExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var mockExportService: ExportService

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(mockExportService)
        setAuthenticated(actorId)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── C1 CSV 200 ────────────────────────────────────────────────────────────

    /**
     * C1 — CSV export → 200 OK + text/csv;charset=UTF-8 + attachment + BOM(EF BB BF).
     */
    @Test
    fun `C1 - CSV export 200 text-csv charset-UTF-8 BOM Content-Disposition`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val csvBytes = bom + "Key,Summary\r\n".toByteArray(Charsets.UTF_8)
        every { mockExportService.export(any(), any(), any(), any(), any()) } returns
            ExportResult("PROJ-issues-20260101-000000.csv", ExportFormat.CSV.contentType, csvBytes)

        val mvcResult =
            mockMvc.perform(
                post("/api/v1/search/export")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(exportBody()),
            )
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Type", containsString("UTF-8")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andReturn()

        val bytes = mvcResult.response.contentAsByteArray
        assertThat(bytes[0]).isEqualTo(0xEF.toByte())
        assertThat(bytes[1]).isEqualTo(0xBB.toByte())
        assertThat(bytes[2]).isEqualTo(0xBF.toByte())
    }

    // ── C2 XLSX 200 ───────────────────────────────────────────────────────────

    /**
     * C2 — XLSX export → 200 OK + xlsx contentType + attachment.
     *
     * XLSX는 ZIP 아카이브이므로 magic bytes는 PK(0x50 0x4B)로 시작한다.
     */
    @Test
    fun `C2 - XLSX export 200 xlsx contentType Content-Disposition`() {
        val xlsxMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        every { mockExportService.export(any(), any(), any(), any(), any()) } returns
            ExportResult("PROJ-issues-20260101-000000.xlsx", ExportFormat.XLSX.contentType, xlsxMagic)

        mockMvc.perform(
            post("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody(format = "XLSX")),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", containsString("spreadsheetml.sheet")))
            .andExpect(header().string("Content-Disposition", containsString("attachment")))
            .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
    }

    // ── C3 상한초과 400 SEARCH_EXPORT_LIMIT_EXCEEDED ──────────────────────────

    /**
     * C3 — 상한초과(10001건) → 400 + SEARCH_EXPORT_LIMIT_EXCEEDED + resultCount/limit property.
     *
     * ProblemDetail 봉투의 resultCount/limit extension property 단언까지 포함한다.
     */
    @Test
    fun `C3 - 상한초과 400 SEARCH_EXPORT_LIMIT_EXCEEDED resultCount limit property 단언`() {
        every { mockExportService.export(any(), any(), any(), any(), any()) } throws
            ExportLimitExceededException(resultCount = 10001L, limit = 10000L)

        mockMvc.perform(
            post("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_EXPORT_LIMIT_EXCEEDED"))
            .andExpect(jsonPath("$.resultCount").value(10001))
            .andExpect(jsonPath("$.limit").value(10000))
            .andExpect(jsonPath("$.detail").isString)
    }

    // ── C4 AQL 문법오류 400 ───────────────────────────────────────────────────

    /**
     * C4 — ExportService가 AqlSyntaxException → 400.
     *
     * ExportExceptionHandler가 AqlSyntaxException을 400으로 변환하는지 검증한다.
     * ExportService는 mock이므로 실제 AQL 파싱 없이 예외를 직접 던진다.
     */
    @Test
    fun `C4 - AQL 문법오류 400 SEARCH_SYNTAX_ERROR`() {
        every { mockExportService.export(any(), any(), any(), any(), any()) } throws
            AqlSyntaxException("문법 오류", position = 5, errorCode = AqlErrorCode.SEARCH_SYNTAX_ERROR)

        mockMvc.perform(
            post("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_SYNTAX_ERROR"))
    }

    // ── C5 format 오타 400 + 허용값 메시지 ─────────────────────────────────────

    /**
     * C5 — format="PDF" → 400 + detail 메시지에 허용값(CSV/XLSX) 포함.
     *
     * ExportController.validateRequest() 내 format 파싱 실패를
     * SearchValidationException → ExportExceptionHandler → 400으로 변환하는지 검증한다.
     */
    @Test
    fun `C5 - format 오타 PDF 400 허용값 메시지 CSV-XLSX 포함`() {
        mockMvc.perform(
            post("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody(format = "PDF")),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail", containsString("CSV")))
            .andExpect(jsonPath("$.detail", containsString("XLSX")))
    }

    // ── C6 columns 미지원 400 SEARCH_VALIDATION_FAILED ────────────────────────

    /**
     * C6 — columns=["INVALID_COL"] → 400 SEARCH_VALIDATION_FAILED.
     *
     * ExportColumn.parse가 SearchValidationException을 던지고
     * ExportExceptionHandler가 400 SEARCH_VALIDATION_FAILED로 변환하는지 검증한다.
     */
    @Test
    fun `C6 - columns 미지원 400 SEARCH_VALIDATION_FAILED`() {
        mockMvc.perform(
            post("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody(columns = listOf("INVALID_COL"))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_VALIDATION_FAILED"))
    }

    // ── C7 projectKey/query 누락 400 (수동 검증) ──────────────────────────────

    /**
     * C7 — projectKey 또는 query 누락 → 400.
     *
     * @field:NotBlank(Hibernate Validator 있을 때) 또는 validateRequest()(없을 때) 중
     * 어느 경로든 400을 반환해야 한다. 두 필드 모두 검증한다.
     */
    @Test
    fun `C7 - projectKey 또는 query 누락 400 수동 검증`() {
        // projectKey 누락 시도
        mockMvc.perform(
            post("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("query" to "status = open", "format" to "CSV"))),
        ).andExpect(status().isBadRequest)

        // query 누락 시도
        mockMvc.perform(
            post("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("projectKey" to "PROJ", "format" to "CSV"))),
        ).andExpect(status().isBadRequest)
    }

    // ── C8 SecurityException → 403 SEARCH_ACCESS_DENIED ──────────────────────

    /**
     * C8 — ExportService가 SecurityException(BROWSE 권한 없음) → 403 SEARCH_ACCESS_DENIED.
     *
     * ExportExceptionHandler가 SecurityException을 403으로 변환하는지 검증한다(비-vacuous).
     */
    @Test
    fun `C8 - SecurityException 403 SEARCH_ACCESS_DENIED`() {
        every { mockExportService.export(any(), any(), any(), any(), any()) } throws
            SecurityException("BROWSE 권한 없음")

        mockMvc.perform(
            post("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody()),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_ACCESS_DENIED"))
    }

    // ── C9 projectKey CRLF → 400 (헤더 인젝션 방어) ──────────────────────────

    /**
     * C9 — projectKey에 CRLF 포함 → 400.
     *
     * projectKey는 영숫자+하이픈만 허용된다. CRLF·공백·특수문자 포함 시
     * validateRequest()의 패턴 검증이 SearchValidationException → 400으로 거부한다.
     * 이로써 Content-Disposition 헤더 인젝션 공격 경로를 차단한다.
     */
    @Test
    fun `C9 - projectKey CRLF 400 헤더 인젝션 방어`() {
        mockMvc.perform(
            post("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody(projectKey = "PROJ\r\nX-Injected: evil")),
        )
            .andExpect(status().isBadRequest)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Export 요청 JSON 바디를 생성하는 헬퍼.
     *
     * 파라미터가 없으면 기본값(PROJ/status=open/CSV)을 사용하며,
     * 특정 필드만 변경해 테스트를 간결하게 유지한다.
     *
     * @param projectKey 요청 projectKey. 기본값 "PROJ".
     * @param query 요청 query. 기본값 "status = open".
     * @param format 요청 format 문자열. 기본값 "CSV".
     * @param columns 요청 columns 목록. null이면 필드 미포함.
     */
    private fun exportBody(
        projectKey: String = "PROJ",
        query: String = "status = open",
        format: String = "CSV",
        columns: List<String>? = null,
    ): String =
        mapper.writeValueAsString(
            buildMap {
                put("projectKey", projectKey)
                put("query", query)
                put("format", format)
                if (columns != null) put("columns", columns)
            },
        )

    /**
     * [SecurityContextHolder]에 UUID 기반 인증 주체를 주입한다.
     *
     * @param userId 인증된 사용자 UUID.
     */
    private fun setAuthenticated(userId: UUID) {
        val auth =
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }
}
