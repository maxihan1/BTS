// Export 매핑층 end-to-end 통합 테스트 — 실 ExportService + fake IssueSearchPort (FR-EX-01 Task 6)

package com.bts.search.integration

import com.bts.search.export.ExportService
import com.bts.search.web.ExportController
import com.bts.search.web.ExportExceptionHandler
import com.bts.shared.search.AqlField
import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlOperator
import com.bts.shared.search.AqlValue
import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * ExportService + ExportController + ExportExceptionHandler 매핑층 통합 테스트.
 *
 * ## 목적
 *
 * 실제 [ExportService]를 Spring 컨텍스트에 올리고 [IssueSearchPort]만 MockK fake으로 대체한다.
 * HTTP 요청부터 파일 응답·예외 응답까지 전체 스택(파서 → 서비스 → writers → 컨트롤러 → 핸들러)을
 * 연결한 상태에서 검증한다.
 *
 * ## 데이터 레벨 visibility 비재증명 (vacuous 방지)
 *
 * 미가시 이슈가 결과에서 제외되는 visibility 보안 술어는 이 테스트에서 재증명하지 않는다.
 * search-export-import 모듈은 issue-tracking Gradle 의존이 없어 실 어댑터가 존재하지 않으므로
 * mock으로 이 동작을 검증하면 vacuous green이 된다.
 *
 * 데이터 레벨 visibility는 다음 테스트가 담당한다.
 * - FR-SR-02 issue-tracking `IssueSearchAdapterTest` (BROWSE 권한 없음 → SecurityException)
 * - Testcontainers 실DB 통합 테스트 (visibility SQL 술어 end-to-end)
 *
 * 본 테스트는 export가 동일 [IssueSearchPort]를 동일 [IssueSearchQuery.viewerUserId]로
 * 호출함을 slot capture로 구조적으로 증명한다 — visibility 보안 규칙이 export 경로에서도
 * 구조적으로 상속됨을 보장한다.
 *
 * ## ArchUnit BC 격리 커버
 *
 * [com.bts.search.architecture.SearchBcArchTest]는 `com.bts.search..` 전체를 스캔하므로
 * export 패키지(`com.bts.search.export..`)도 기존 BC 격리 룰 7개 적용 대상에 자동 포함된다.
 * `searchProductionClassCountIsAtLeastOne` 카운트 가드가 vacuous 방어 역할을 수행하므로
 * 이 파일에 별도 ArchUnit 테스트를 추가하지 않는다.
 *
 * ## 검증 케이스
 *
 * - I1 CSV BOM + row 파싱 — fake 2건 시드 → EF BB BF + 헤더/데이터 행 파싱
 * - I2 XLSX POI round-trip — XSSFWorkbook으로 헤더·데이터 셀 재읽기
 * - I3 상한초과(total=10001) → 400 SEARCH_EXPORT_LIMIT_EXCEEDED + resultCount/limit property
 * - I4 formula injection e2e — summary="=EVIL()" → CSV 셀 `'` prefix
 * - I5 fake 포트 SecurityException → 403 SEARCH_ACCESS_DENIED (매핑층 전파)
 * - I6 export가 동일 viewerUserId/AST로 포트 호출 — slot capture로 보안 구조적 상속 증명
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ExportIntegrationTest.TestMvcConfig::class])
@WebAppConfiguration
class ExportIntegrationTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * 실 [ExportService], [ExportController], [ExportExceptionHandler]를 조립하고
     * [IssueSearchPort]만 MockK fake으로 대체한다.
     * [Clock]은 결정적 파일명을 위해 고정 인스턴스를 주입한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        /** IssueSearchPort MockK fake — visibility 어댑터 없이 포트 계약만 제공. */
        @Bean
        open fun fakeIssueSearchPort(): IssueSearchPort = mockk(relaxed = false)

        /** 파일명 timestamp 결정성을 위한 고정 Clock (UTC 2024-03-15T10:30:45Z). */
        @Bean
        open fun fixedClock(): Clock = Clock.fixed(Instant.parse("2024-03-15T10:30:45Z"), ZoneOffset.UTC)

        /** 실제 ExportService — fake 포트와 고정 Clock을 주입한다. */
        @Bean
        open fun exportService(
            port: IssueSearchPort,
            clock: Clock,
        ): ExportService = ExportService(port, clock)

        /** 실제 ExportController. */
        @Bean
        open fun exportController(exportService: ExportService): ExportController = ExportController(exportService)

        /** 실제 ExportExceptionHandler — ExportController 전용 스코프. */
        @Bean
        open fun exportExceptionHandler(): ExportExceptionHandler = ExportExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var fakePort: IssueSearchPort

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** 인증된 테스트 사용자 ID — I6 slot capture의 기준값. */
    private val actorId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(fakePort)
        setAuthenticated(actorId)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── I1 CSV BOM + row 파싱 ─────────────────────────────────────────────────

    /**
     * I1 — fake 포트 2건 시드 → CSV export → BOM(EF BB BF) + 헤더/데이터 행 파싱.
     *
     * ExportService → CsvExportWriter 경로가 실제로 연결됨을 검증한다.
     * 한글 summary("테스트 이슈 1")를 시드해 UTF-8 인코딩 round-trip도 확인한다.
     */
    @Test
    fun `I1 - CSV export BOM EF BB BF 선두 바이트 헤더 및 데이터 행 파싱`() {
        every { fakePort.search(any()) } returns twoHitPage()

        val mvcResult =
            mockMvc.perform(
                post("/api/v1/search/export")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(exportBody()),
            )
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn()

        val bytes = mvcResult.response.contentAsByteArray

        // BOM 선두 3바이트 검증
        assertThat(bytes[0]).isEqualTo(0xEF.toByte())
        assertThat(bytes[1]).isEqualTo(0xBB.toByte())
        assertThat(bytes[2]).isEqualTo(0xBF.toByte())

        // BOM을 제거한 뒤 UTF-8 디코딩 + CRLF 행 분리
        val csvText = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')
        val lines = csvText.split("\r\n").filter { it.isNotEmpty() }

        // 헤더 + 2 데이터 행 이상 존재
        assertThat(lines).hasSizeGreaterThanOrEqualTo(3)
        // 헤더 행에 표준 라벨 포함
        assertThat(lines[0]).contains("Key").contains("Summary")
        // 첫 데이터 행에 시드 값 포함 (한글 UTF-8 round-trip 검증)
        assertThat(lines[1]).contains("TEST-1").contains("테스트 이슈 1")
        // 두 번째 데이터 행 존재
        assertThat(lines[2]).contains("TEST-2")
    }

    // ── I2 XLSX POI round-trip ────────────────────────────────────────────────

    /**
     * I2 — fake 포트 2건 시드 → XLSX export → POI [XSSFWorkbook]으로 헤더/데이터 셀 재읽기.
     *
     * ExportService → XlsxExportWriter → Apache POI 경로가 실제로 연결됨을 검증한다.
     * 모든 셀이 STRING 타입임도 확인한다.
     */
    @Test
    fun `I2 - XLSX export POI round-trip 헤더 및 데이터 셀 재읽기 검증`() {
        every { fakePort.search(any()) } returns twoHitPage()

        val mvcResult =
            mockMvc.perform(
                post("/api/v1/search/export")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(exportBody(format = "XLSX")),
            )
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Type", containsString("spreadsheetml.sheet")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn()

        val bytes = mvcResult.response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val headerRow = sheet.getRow(0)
            val dataRow = sheet.getRow(1)

            // 헤더 행 — 첫 두 셀 검증
            assertThat(headerRow.getCell(0).stringCellValue).isEqualTo("Key")
            assertThat(headerRow.getCell(1).stringCellValue).isEqualTo("Summary")

            // 데이터 행 — key/summary 검증
            assertThat(dataRow.getCell(0).stringCellValue).isEqualTo("TEST-1")
            assertThat(dataRow.getCell(1).stringCellValue).isEqualTo("테스트 이슈 1")
        }
    }

    // ── I3 상한초과 → 400 SEARCH_EXPORT_LIMIT_EXCEEDED ───────────────────────

    /**
     * I3 — fake 포트가 total=10001 반환 → 400 + SEARCH_EXPORT_LIMIT_EXCEEDED + resultCount/limit property.
     *
     * ExportService count-first 경계값 검증 + ExportExceptionHandler ProblemDetail 봉투 검증.
     * 추가 포트 순회 없이 1회 조회 후 즉시 거부됨을 verify로 확인한다.
     */
    @Test
    fun `I3 - 상한초과 10001건 400 SEARCH_EXPORT_LIMIT_EXCEEDED resultCount limit property`() {
        every { fakePort.search(any()) } returns
            IssueSearchPage(items = emptyList(), total = 10001L, page = 0, size = 100)

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

        // count-first: 상한 초과 시 1회 조회 후 즉시 거부 — 추가 페이지 순회 없음
        io.mockk.verify(exactly = 1) { fakePort.search(any()) }
    }

    // ── I4 formula injection e2e `'` prefix ──────────────────────────────────

    /**
     * I4 — summary="=EVIL()" 시드 → CSV 셀에 `'` prefix 방어 end-to-end.
     *
     * ExportCellSanitizer → CsvExportWriter 경로가 실제로 연결됨을 검증한다.
     * `=`으로 시작하는 셀이 `'=EVIL()`으로 변환되어 스프레드시트 formula injection이 차단된다.
     */
    @Test
    fun `I4 - formula injection summary 등호시작 CSV 셀 단일인용부호 prefix 방어`() {
        val injectionHit =
            sampleHit(
                key = "INJECT-1",
                summary = "=EVIL()",
            )
        every { fakePort.search(any()) } returns
            IssueSearchPage(items = listOf(injectionHit), total = 1L, page = 0, size = 100)

        val mvcResult =
            mockMvc.perform(
                post("/api/v1/search/export")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(exportBody()),
            )
                .andExpect(status().isOk)
                .andReturn()

        val bytes = mvcResult.response.contentAsByteArray
        val csvText = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')

        // sanitize 적용 후 셀 값이 `'=EVIL()`이어야 한다
        assertThat(csvText).contains("'=EVIL()")
        // 원본 `=EVIL()`이 필드 구분자 뒤에 그대로 노출되지 않아야 한다
        assertThat(csvText).doesNotContain(",=EVIL()")
    }

    // ── I5 SecurityException → 403 SEARCH_ACCESS_DENIED ─────────────────────

    /**
     * I5 — fake 포트가 [SecurityException] 던짐 → 403 SEARCH_ACCESS_DENIED.
     *
     * BROWSE 권한 없는 요청이 ExportController → ExportExceptionHandler → 403으로 변환되는
     * 매핑층 end-to-end를 검증한다. 단위 테스트(ExportControllerTest C8)와 달리
     * 실 ExportService를 통해 포트 호출까지 이어지는 전체 경로를 검증한다.
     */
    @Test
    fun `I5 - fake 포트 SecurityException 403 SEARCH_ACCESS_DENIED 매핑층 전파`() {
        every { fakePort.search(any()) } throws SecurityException("BROWSE 권한 없음")

        mockMvc.perform(
            post("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody()),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_ACCESS_DENIED"))
    }

    // ── I6 viewerUserId/AST slot capture — 보안 구조적 상속 증명 ─────────────

    /**
     * I6 — export가 동일 viewerUserId + AST로 [IssueSearchPort]를 호출하는 것을 slot capture로 증명.
     *
     * ## 보안 구조적 상속
     *
     * export 경로에서 [IssueSearchPort]에 전달되는 [IssueSearchQuery.viewerUserId]가
     * 인증된 actor([actorId])와 동일함을 slot capture로 검증한다.
     * [IssueSearchPort] 구현체(issue-tracking `IssueSearchAdapter`)는 이 viewerUserId로
     * visibility 보안 술어를 평가하므로, export가 검색과 동일한 security gate를 통과함이
     * 구조적으로 보장된다.
     *
     * ## AQL 파싱 정합
     *
     * "status = open" 쿼리가 [AqlNode.Comparison](field=status, op=EQ, value=Str("open"))으로
     * 파싱되어 포트에 전달됨을 확인한다.
     */
    @Test
    fun `I6 - export가 동일 viewerUserId AST로 IssueSearchPort 호출 보안 구조적 상속 증명`() {
        val querySlot = slot<IssueSearchQuery>()
        every { fakePort.search(capture(querySlot)) } returns twoHitPage()

        mockMvc.perform(
            post("/api/v1/search/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody()),
        ).andExpect(status().isOk)

        val capturedQuery = querySlot.captured

        // viewerUserId는 인증된 actorId와 동일해야 한다 — visibility 보안 술어 기준
        assertThat(capturedQuery.viewerUserId).isEqualTo(actorId)
        assertThat(capturedQuery.projectKey).isEqualTo("TEST")

        // "status = open" → Comparison(status, EQ, [Str("open")]) AST 정합
        assertThat(capturedQuery.ast).isInstanceOf(AqlNode.Comparison::class.java)
        val comparison = capturedQuery.ast as AqlNode.Comparison
        assertThat(comparison.field).isEqualTo(AqlField("status"))
        assertThat(comparison.op).isEqualTo(AqlOperator.EQ)
        assertThat(comparison.values).containsExactly(AqlValue.Str("open"))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Export 요청 JSON 바디를 생성하는 헬퍼.
     *
     * 기본값(TEST/status=open/CSV)을 사용하며, 특정 필드만 변경해 테스트를 간결하게 유지한다.
     *
     * @param projectKey 요청 projectKey. 기본값 "TEST".
     * @param query 요청 AQL query 문자열. 기본값 "status = open".
     * @param format 요청 format 문자열. 기본값 "CSV".
     * @param columns 요청 columns 목록. null이면 필드 미포함(전체 9컬럼).
     */
    private fun exportBody(
        projectKey: String = "TEST",
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
     * 2건짜리 [IssueSearchPage]를 반환한다.
     *
     * I1/I2/I6에서 공통으로 사용한다.
     * total=2, PAGE_SIZE=100이므로 ExportService는 1 페이지만 조회한다.
     */
    private fun twoHitPage(): IssueSearchPage =
        IssueSearchPage(
            items =
                listOf(
                    sampleHit(key = "TEST-1", summary = "테스트 이슈 1"),
                    sampleHit(key = "TEST-2", summary = "테스트 이슈 2"),
                ),
            total = 2L,
            page = 0,
            size = 100,
        )

    /**
     * 테스트용 [IssueSearchHit] 샘플을 생성한다.
     *
     * @param key 이슈 키. 기본값 "TEST-1".
     * @param summary 이슈 summary. formula injection 테스트에서 "=EVIL()"처럼 변경한다.
     */
    private fun sampleHit(
        key: String = "TEST-1",
        summary: String = "테스트 이슈 1",
    ): IssueSearchHit =
        IssueSearchHit(
            key = key,
            summary = summary,
            typeKey = "task",
            currentStateKey = "open",
            assigneeId = null,
            priority = 3,
            priorityName = "Medium",
            projectKey = "TEST",
            updatedAt = Instant.parse("2024-03-15T10:00:00Z"),
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
