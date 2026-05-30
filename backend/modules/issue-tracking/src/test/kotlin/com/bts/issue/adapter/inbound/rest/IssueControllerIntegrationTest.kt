// FR-IS-04 본문·메타 PATCH/GET 엔드투엔드 통합 테스트 — backfill·OCC·XSS·merge-patch·유효성 검증

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.flywaydb.core.Flyway
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.DriverManager
import java.util.UUID

/**
 * FR-IS-04 본문·메타 필드 엔드투엔드 통합 테스트.
 *
 * 실제 Testcontainers PostgreSQL + 전체 스택(issue-tracking + project-workflow BC) 위에서 동작한다.
 * [TestConfig] 를 재사용하므로 컨테이너 추가 기동이 없다.
 *
 * ## 검증 시나리오
 * - (a) 기존 이슈(V006 backfill) GET → priority=3(Medium), labels=[] 확인.
 * - (b) PATCH description(Markdown) → GET descriptionHtml에 렌더·XSS 차단 확인.
 *   description 원본 Markdown 보존 확인.
 * - (c) merge-patch — priority/labels/environment/impact 개별 PATCH(부재=무변경).
 *   description ""→DB NULL 클리어. labels []→전체 제거.
 * - (d) priority=6 또는 0 → 400. impact 범위 밖(0, 4) → 400.
 * - (e) expectedVersion 불일치 → 409 VERSION_CONFLICT.
 * - (f) priorityName/impactName 응답 노출 확인.
 *
 * @see IssueControllerTransitionIntegrationTest.TestConfig 공유 Spring 컨텍스트
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueControllerIntegrationTest {
    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dataSource: DriverManagerDataSource

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        private const val PROJECT_KEY = "MDIT"
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

    // ── (a) V006 backfill — priority=3, labels=[] ────────────────────────────

    /**
     * (a) V006 backfill 검증.
     *
     * Given  MDIT 프로젝트에 이슈 1건 삽입 (priority/labels 미지정 — DB DEFAULT)
     * When   GET /api/v1/issues/{key}
     * Then   priority=3(Medium), labels=[] 확인 (V006 backfill + DB DEFAULT 값)
     */
    @Test
    fun `a GET 이슈 — priority 3 기본값 및 labels 빈 배열 backfill 확인`() {
        val key = insertIssue("backfill 확인 이슈")

        mockMvc.perform(get("/api/v1/issues/$key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priority").value(3))
            .andExpect(jsonPath("$.data.priorityName").value("Medium"))
            .andExpect(jsonPath("$.data.labels").isArray)
            .andExpect(jsonPath("$.data.labels.length()").value(0))
    }

    // ── (b) PATCH description(Markdown) → GET descriptionHtml XSS 차단 ──────

    /**
     * (b) Markdown 렌더 + XSS 차단 검증.
     *
     * Given  이슈 1건 생성
     * When   PATCH description = "## 재현\n<script>alert(1)</script>"
     * Then   GET descriptionHtml 에 <h2> 존재, script 실행코드 0건, 원본 description 보존
     */
    @Test
    fun `b PATCH description Markdown 렌더 후 GET descriptionHtml XSS 차단 및 원본 보존`() {
        val key = insertIssue("XSS 차단 검증 이슈")

        val markdownInput = "## 재현\n<script>alert(1)</script>"
        val patchBody =
            mapOf(
                "description" to markdownInput,
                "expectedVersion" to 1L,
            )

        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.description").value(markdownInput))

        mockMvc.perform(get("/api/v1/issues/$key"))
            .andExpect(status().isOk)
            // 원본 Markdown 보존
            .andExpect(jsonPath("$.data.description").value(markdownInput))
            // <h2> 태그 존재
            .andExpect(jsonPath("$.data.descriptionHtml").value(containsString("<h2>")))
            // <script 태그 부재 — XSS 차단
            .andExpect(jsonPath("$.data.descriptionHtml").value(not(containsString("<script"))))
            // alert( 코드 부재
            .andExpect(jsonPath("$.data.descriptionHtml").value(not(containsString("alert("))))
    }

    // ── (c) merge-patch 개별 필드 — 부재=무변경, ""=클리어, []=전체제거 ─────────

    /**
     * (c-1) priority 단독 PATCH — labels/environment/impact 무변경 확인.
     */
    @Test
    fun `c1 PATCH priority 단독 변경 — 나머지 필드 무변경`() {
        val key = insertIssue("priority PATCH 검증 이슈")

        val patchBody =
            mapOf(
                "priority" to 1,
                "expectedVersion" to 1L,
            )

        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priority").value(1))
            .andExpect(jsonPath("$.data.priorityName").value("Highest"))
            // labels는 변경 없음
            .andExpect(jsonPath("$.data.labels.length()").value(0))
            // impact null 유지
            .andExpect(jsonPath("$.data.impact").doesNotExist())
    }

    /**
     * (c-2) labels PATCH — 값 설정 후 [] 로 전체 제거.
     */
    @Test
    fun `c2 PATCH labels 설정 후 빈 배열로 전체 제거`() {
        val key = insertIssue("labels PATCH 검증 이슈")

        // labels 설정
        val setBody =
            mapOf(
                "labels" to listOf("bug", "urgent"),
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(setBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.labels.length()").value(2))

        // [] 로 전체 제거
        val clearBody =
            mapOf(
                "labels" to emptyList<String>(),
                "expectedVersion" to 2L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(clearBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.labels.length()").value(0))
    }

    /**
     * (c-3) environment PATCH — 값 설정 후 "" 로 DB NULL 클리어.
     */
    @Test
    fun `c3 PATCH environment 설정 후 빈 문자열로 DB NULL 클리어`() {
        val key = insertIssue("environment PATCH 검증 이슈")

        // environment 설정
        val setBody =
            mapOf(
                "environment" to "macOS 14 + Chrome 124",
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(setBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.environment").value("macOS 14 + Chrome 124"))

        // "" 로 클리어
        val clearBody =
            mapOf(
                "environment" to "",
                "expectedVersion" to 2L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(clearBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.environment").doesNotExist())
    }

    /**
     * (c-4) description "" → DB NULL 클리어.
     */
    @Test
    fun `c4 PATCH description 빈 문자열로 DB NULL 클리어`() {
        val key = insertIssue("description 클리어 검증 이슈")

        // description 설정
        val setBody =
            mapOf(
                "description" to "# 초기 설명",
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(setBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.description").value("# 초기 설명"))

        // "" 로 클리어
        val clearBody =
            mapOf(
                "description" to "",
                "expectedVersion" to 2L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(clearBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.description").doesNotExist())
    }

    /**
     * (c-5) impact PATCH 단독 변경.
     */
    @Test
    fun `c5 PATCH impact 단독 변경 — priorityName 무변경`() {
        val key = insertIssue("impact PATCH 검증 이슈")

        val patchBody =
            mapOf(
                "impact" to 2,
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.impact").value(2))
            .andExpect(jsonPath("$.data.impactName").value("Medium"))
            // priority는 무변경
            .andExpect(jsonPath("$.data.priority").value(3))
            .andExpect(jsonPath("$.data.priorityName").value("Medium"))
    }

    // ── (d) 범위 밖 priority/impact → 400 ──────────────────────────────────────

    /**
     * (d-1) priority=6 → 400 VALIDATION_FAILED.
     */
    @Test
    fun `d1 PATCH priority 6 은 400 반환`() {
        val key = insertIssue("priority 상한 초과 검증 이슈")

        val patchBody =
            mapOf(
                "priority" to 6,
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    /**
     * (d-2) priority=0 → 400 VALIDATION_FAILED.
     */
    @Test
    fun `d2 PATCH priority 0 은 400 반환`() {
        val key = insertIssue("priority 하한 미달 검증 이슈")

        val patchBody =
            mapOf(
                "priority" to 0,
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    /**
     * (d-3) impact=0 → 400 VALIDATION_FAILED.
     */
    @Test
    fun `d3 PATCH impact 0 은 400 반환`() {
        val key = insertIssue("impact 하한 미달 검증 이슈")

        val patchBody =
            mapOf(
                "impact" to 0,
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    /**
     * (d-4) impact=4 → 400 VALIDATION_FAILED.
     */
    @Test
    fun `d4 PATCH impact 4 는 400 반환`() {
        val key = insertIssue("impact 상한 초과 검증 이슈")

        val patchBody =
            mapOf(
                "impact" to 4,
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    // ── (e) expectedVersion 불일치 → 409 VERSION_CONFLICT ──────────────────────

    /**
     * (e) OCC(낙관적 잠금) 충돌.
     *
     * Given  이슈 version=1, PATCH expectedVersion=99
     * Then   409 + errorCode=VERSION_CONFLICT
     */
    @Test
    fun `e PATCH expectedVersion 불일치 시 409 VERSION_CONFLICT`() {
        val key = insertIssue("OCC 충돌 검증 이슈")

        val patchBody =
            mapOf(
                "priority" to 2,
                "expectedVersion" to 99L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
    }

    // ── (f) priorityName/impactName 응답 노출 확인 ──────────────────────────────

    /**
     * (f) priorityName/impactName 응답 직렬화 확인.
     *
     * Given  priority=1, impact=3 으로 설정
     * When   GET /api/v1/issues/{key}
     * Then   priorityName="Highest", impactName="Low" 응답 확인
     */
    @Test
    fun `f GET 응답에 priorityName 및 impactName 노출`() {
        val key = insertIssue("priorityName impactName 응답 검증 이슈")

        val patchBody =
            mapOf(
                "priority" to 1,
                "impact" to 3,
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isOk)

        mockMvc.perform(get("/api/v1/issues/$key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priority").value(1))
            .andExpect(jsonPath("$.data.priorityName").value("Highest"))
            .andExpect(jsonPath("$.data.impact").value(3))
            .andExpect(jsonPath("$.data.impactName").value("Low"))
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — issue-tracking + project-workflow 두 BC 를 순차 적용.
     *
     * [TestConfig] 의 postgres 컨테이너에 대해 Flyway 를 실행한다.
     * [IssueControllerTransitionIntegrationTest] 와 동일 컨테이너이므로
     * 이미 마이그레이션이 완료된 경우 Flyway 가 no-op 으로 처리한다.
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
     * MDIT 프로젝트 삽입.
     *
     * software-scheme 배정은 TRANSITION 프로젝트가 이미 같은 컨테이너에 seeded.
     * MDIT 프로젝트는 전이가 불필요하므로 workflow scheme 배정 없이 생성한다.
     * PATCH/GET 경로는 workflowKeyResolver 를 호출하지 않으므로 scheme 없어도 무방하다.
     */
    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Markdown Integration Test Project")
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 테스트용 이슈 삽입 후 이슈 키 반환.
     *
     * priority/labels/environment/impact 를 명시하지 않아 V006 DB DEFAULT 가 적용된다.
     * (priority DEFAULT 3, labels DEFAULT '{}')
     */
    private fun insertIssue(summary: String): String {
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

            conn.commit()
            return issueKey
        }
    }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
