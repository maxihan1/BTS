// IssueController GET /{key}/pdf 엔드포인트 MockMvc 슬라이스 테스트

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.pdf.IssuePdfRenderer
import com.bts.issue.pdf.IssuePdfTemplate
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * IssueController GET /api/v1/issues/{key}/pdf MockMvc 슬라이스 테스트.
 *
 * [IssueApplicationService]와 [IssuePdfRenderer]는 MockK stub으로 대체한다.
 * [IssueExceptionHandler]를 컨텍스트에 등록하여 IssueNotFoundException → 404 변환을 검증한다.
 *
 * 테스트 케이스.
 * - P-1. GET /{key}/pdf — 존재하는 키이면 200 + application/pdf + Content-Disposition attachment
 * - P-2. GET /{key}/pdf — 응답 본문이 %PDF- 시그니처 바이트로 시작함
 * - P-3. GET /{key}/pdf — 존재하지 않는 키이면 404 (IssueExceptionHandler 경유)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerPdfTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueControllerPdfTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueController], [IssueExceptionHandler], MockK stub Bean을 등록한다.
     * [IssuePdfTemplate]과 [IssuePdfRenderer]는 직접 등록하여 생성자 배선 충족.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun issuePdfRenderer(): IssuePdfRenderer = mockk(relaxed = true)

        @Bean
        open fun issueController(
            service: IssueApplicationService,
            pdfRenderer: IssuePdfRenderer,
        ): IssueController = IssueController(service, pdfRenderer)

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    @Autowired
    lateinit var issuePdfRenderer: IssuePdfRenderer

    lateinit var mockMvc: MockMvc

    private val fixedNow: Instant = Instant.parse("2026-06-03T00:00:00Z")
    private val issueId = UUID.fromString("00000000-0000-4000-8000-000000000002")

    /** 테스트용 이슈 응답 픽스처. */
    private val sampleResponse =
        IssueResponse(
            key = "ATLAS-1",
            id = issueId,
            projectKey = "ATLAS",
            summary = "PDF 테스트 이슈",
            currentStateKey = "open",
            reporterId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
            version = 1L,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
        )

    /** PDF 시그니처 바이트 — 실제 PDF 파일은 항상 이 시퀀스로 시작한다. */
    private val pdfSignatureBytes = "%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1)

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    @AfterEach
    fun tearDown() {
        clearMocks(issueApplicationService, issuePdfRenderer)
        SecurityContextHolder.clearContext()
    }

    // ── P-1. 200 + application/pdf + Content-Disposition ─────────────────────

    /**
     * P-1. 존재하는 이슈 키 → 200 OK, Content-Type: application/pdf, Content-Disposition attachment.
     *
     * Given  ATLAS-1 이슈가 존재하고 renderer가 PDF 바이트를 반환함
     * When   GET /api/v1/issues/ATLAS-1/pdf
     * Then   200 OK, Content-Type=application/pdf, Content-Disposition=attachment; filename="ATLAS-1.pdf"
     */
    @Test
    fun `GET pdf — 존재하는 키이면 200 plus application pdf plus Content-Disposition attachment`() {
        every { issueApplicationService.findByKey(any(), IssueKey("ATLAS-1")) } returns sampleResponse
        every { issuePdfRenderer.render(sampleResponse) } returns pdfSignatureBytes

        mockMvc.perform(
            get("/api/v1/issues/ATLAS-1/pdf"),
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"ATLAS-1.pdf\""))
    }

    // ── P-2. 응답 본문 PDF 시그니처 ───────────────────────────────────────────

    /**
     * P-2. 응답 본문이 renderer가 반환한 바이트와 일치한다.
     *
     * Given  renderer가 %PDF- 시그니처 바이트를 반환함
     * When   GET /api/v1/issues/ATLAS-1/pdf
     * Then   응답 바디 == pdfSignatureBytes
     */
    @Test
    fun `GET pdf — 응답 본문이 renderer가 반환한 바이트와 일치한다`() {
        every { issueApplicationService.findByKey(any(), IssueKey("ATLAS-1")) } returns sampleResponse
        every { issuePdfRenderer.render(sampleResponse) } returns pdfSignatureBytes

        mockMvc.perform(
            get("/api/v1/issues/ATLAS-1/pdf"),
        )
            .andExpect(status().isOk)
            .andExpect(content().bytes(pdfSignatureBytes))
    }

    // ── P-3. 존재하지 않는 키 → 404 ──────────────────────────────────────────

    /**
     * P-3. 존재하지 않는 이슈 키 → 404.
     *
     * Given  service가 IssueNotFoundException을 throw함
     * When   GET /api/v1/issues/ATLAS-99/pdf
     * Then   404 Not Found (IssueExceptionHandler 경유)
     */
    @Test
    fun `GET pdf — 존재하지 않는 키이면 404`() {
        every {
            issueApplicationService.findByKey(any(), IssueKey("ATLAS-99"))
        } throws IssueNotFoundException(IssueKey("ATLAS-99"))

        mockMvc.perform(
            get("/api/v1/issues/ATLAS-99/pdf"),
        )
            .andExpect(status().isNotFound)
    }
}
