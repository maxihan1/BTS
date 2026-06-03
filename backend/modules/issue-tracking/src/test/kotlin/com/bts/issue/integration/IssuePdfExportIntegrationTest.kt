// FR-IS-08 PDF 내보내기 엔드투엔드 통합 테스트 — HTTP 레벨 PDF 바이너리 + 한글 텍스트 추출 + XSS 케이스

package com.bts.issue.integration

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.io.ByteArrayInputStream
import java.sql.DriverManager
import java.util.UUID

/**
 * FR-IS-08 PDF 내보내기 Testcontainers 엔드투엔드 통합 테스트.
 *
 * 실제 PostgreSQL + 전체 Spring 컨텍스트(MockMvc) 위에서 `GET /api/v1/issues/{key}/pdf`를 검증한다.
 * [TestConfig]를 재사용해 컨테이너 추가 기동 없이 동일 DB 위에서 동작한다.
 *
 * ## 검증 시나리오
 * - S1. 한글 이슈 시드 → 200 + application/pdf + `%PDF-` 시그니처 + PDFTextStripper 한글 추출.
 * - S2. XSS description 이슈 → PDF 텍스트에 `<script` 실행 가능 형태 부재 확인 (NFR1 sanitize 경유).
 * - S3. 존재하지 않는 키 → 404.
 *
 * @see TestConfig 공유 Spring 컨텍스트 (Testcontainers singleton + MockMvc + 두 BC wire)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssuePdfExportIntegrationTest {

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dataSource: DriverManagerDataSource

    private lateinit var mockMvc: MockMvc

    companion object {
        private const val PROJECT_KEY = "PDF"
        private var migrated = false
        private var seeded = false
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProject()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    // ── S1. 한글 이슈 → 200 + PDF 바이너리 + 텍스트 추출 ──────────────────────

    /**
     * S1 PDF 내보내기 정상 경로.
     *
     * Given  한글 제목 + 한글 본문 이슈 시드
     * When   GET /api/v1/issues/{key}/pdf
     * Then   200 OK + Content-Type application/pdf
     *        Content-Disposition: attachment; filename="{key}.pdf"
     *        응답 바이트가 %PDF- 시그니처로 시작
     *        PDFTextStripper 추출 텍스트에 이슈 키 + 한글 제목 + 한글 본문 포함
     */
    @Test
    fun `S1 한글 이슈 PDF 내보내기 — 200 PDF 바이너리 및 한글 텍스트 추출 성공`() {
        val koreanSummary = "한글 제목 이슈 검증용"
        val koreanDescription = "한글 본문 내용입니다. 렌더링 검증을 위한 텍스트."
        val issueKey = insertIssue(summary = koreanSummary, description = koreanDescription)

        val result = mockMvc.perform(get("/api/v1/issues/$issueKey/pdf"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
            .andExpect(
                header().string(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"$issueKey.pdf\"",
                ),
            )
            .andReturn()

        val pdfBytes = result.response.contentAsByteArray
        assertPdfSignature(pdfBytes)

        val extractedText = extractPdfText(pdfBytes)
        assert(extractedText.contains(issueKey)) {
            "PDF 텍스트에 이슈 키 '$issueKey'가 없음. 추출: $extractedText"
        }
        assert(extractedText.contains(koreanSummary)) {
            "PDF 텍스트에 한글 제목 '$koreanSummary'가 없음 (tofu 의심). 추출: $extractedText"
        }
        assert(extractedText.contains("한글 본문")) {
            "PDF 텍스트에 한글 본문 키워드가 없음 (tofu 의심). 추출: $extractedText"
        }
    }

    // ── S2. XSS description → PDF 텍스트에 script 실행 형태 부재 ────────────

    /**
     * S2 XSS 케이스 (코드리뷰 N2).
     *
     * Given  description에 `&lt;script&gt;alert(1)&lt;/script&gt;` 포함 이슈 시드
     * When   GET /api/v1/issues/{key}/pdf
     * Then   200 OK
     *        PDF 추출 텍스트에 `<script` 실행 가능 형태 미포함
     *        (descriptionHtml은 MarkdownRenderer.renderSafe() 경유 OWASP sanitize 완료)
     */
    @Test
    fun `S2 XSS description 포함 이슈 PDF — script 태그 실행 형태 텍스트 미포함`() {
        val xssDescription = "<script>alert(1)</script>악성 스크립트 삽입 시도"
        val issueKey = insertIssue(summary = "XSS 검증 이슈", description = xssDescription)

        val result = mockMvc.perform(get("/api/v1/issues/$issueKey/pdf"))
            .andExpect(status().isOk)
            .andReturn()

        val pdfBytes = result.response.contentAsByteArray
        assertPdfSignature(pdfBytes)

        val extractedText = extractPdfText(pdfBytes)
        assert(!extractedText.contains("<script")) {
            "PDF 텍스트에 실행 가능한 <script 태그가 포함됨 — sanitize 미경유 의심. 추출: $extractedText"
        }
        assert(!extractedText.contains("alert(")) {
            "PDF 텍스트에 alert( 코드가 포함됨 — sanitize 미경유 의심. 추출: $extractedText"
        }
    }

    // ── S3. 존재하지 않는 키 → 404 ──────────────────────────────────────────

    /**
     * S3 존재하지 않는 이슈 키 → 404.
     *
     * Given  DB에 없는 이슈 키
     * When   GET /api/v1/issues/{key}/pdf
     * Then   404 Not Found
     */
    @Test
    fun `S3 존재하지 않는 이슈 키 PDF 요청 — 404 반환`() {
        mockMvc.perform(get("/api/v1/issues/PDF-99999/pdf"))
            .andExpect(status().isNotFound)
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — [TestConfig] 컨테이너에 issue-tracking + project-workflow 순차 적용.
     * 이미 마이그레이션이 완료된 경우 Flyway가 no-op으로 처리한다.
     */
    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(
                TestConfig.postgres.jdbcUrl,
                TestConfig.postgres.username,
                TestConfig.postgres.password,
            )
            .placeholderReplacement(false)
            .locations(
                "classpath:db/migration/issue-tracking",
                "classpath:db/migration/project-workflow",
            )
            .load()
            .migrate()
    }

    /**
     * PDF 내보내기 전용 프로젝트(PDF) 삽입.
     * workflow scheme 배정 없이 생성한다(pdf 조회는 workflowKeyResolver 미경유).
     */
    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "PDF Export Integration Test Project")
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 테스트용 이슈를 DB에 직접 삽입하고 이슈 키를 반환한다.
     *
     * @param summary 이슈 제목 (한글 가능)
     * @param description Markdown 본문 — null이면 미삽입 (DB NULL)
     * @return 생성된 이슈 키 (예: "PDF-1")
     */
    private fun insertIssue(
        summary: String,
        description: String? = null,
    ): String {
        conn().use { conn ->
            conn.autoCommit = false

            val seq =
                conn.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            val issueKey = "$PROJECT_KEY-$seq"

            val projectId =
                conn.prepareStatement(
                    "SELECT id FROM projects WHERE key = ?",
                ).use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val taskTypeId =
                conn.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            if (description != null) {
                conn.prepareStatement(
                    "INSERT INTO issues (key, project_id, summary, description, reporter_id, current_state_key, version, type_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                ).use { stmt ->
                    stmt.setString(1, issueKey)
                    stmt.setObject(2, projectId)
                    stmt.setString(3, summary)
                    stmt.setString(4, description)
                    stmt.setObject(5, UUID.fromString("00000000-0000-0000-0000-000000000001"))
                    stmt.setString(6, "open")
                    stmt.setLong(7, taskTypeId)
                    stmt.executeUpdate()
                }
            } else {
                conn.prepareStatement(
                    "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                        "VALUES (?, ?, ?, ?, ?, 1, ?)",
                ).use { stmt ->
                    stmt.setString(1, issueKey)
                    stmt.setObject(2, projectId)
                    stmt.setString(3, summary)
                    stmt.setObject(4, UUID.fromString("00000000-0000-0000-0000-000000000001"))
                    stmt.setString(5, "open")
                    stmt.setLong(6, taskTypeId)
                    stmt.executeUpdate()
                }
            }

            conn.commit()
            return issueKey
        }
    }

    /**
     * PDF 바이너리가 `%PDF-` 시그니처로 시작하는지 검증한다.
     */
    private fun assertPdfSignature(bytes: ByteArray) {
        assert(bytes.size >= 5) { "PDF 바이트 길이가 너무 짧음: ${bytes.size}" }
        val signature = String(bytes.take(5).toByteArray(), Charsets.ISO_8859_1)
        assert(signature == "%PDF-") {
            "PDF 시그니처 불일치. 기대: %PDF-, 실제: $signature"
        }
    }

    /**
     * PDFTextStripper로 PDF 바이너리에서 텍스트를 추출한다.
     *
     * @param pdfBytes PDF 바이너리
     * @return 추출된 전체 텍스트 문자열
     */
    private fun extractPdfText(pdfBytes: ByteArray): String {
        return PDDocument.load(ByteArrayInputStream(pdfBytes)).use { doc ->
            PDFTextStripper().getText(doc)
        }
    }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
