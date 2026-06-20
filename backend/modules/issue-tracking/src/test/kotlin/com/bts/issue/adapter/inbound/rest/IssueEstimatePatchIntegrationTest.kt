// 이슈 추정 필드(originalEstimate/remainingEstimate) PATCH + IssueResponse 노출 통합 테스트 (FR-TT-01 Task-6)

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.openapitools.jackson.nullable.JsonNullableModule
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
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
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.sql.DriverManager
import java.util.UUID

/**
 * 이슈 추정 필드 PATCH + GET 응답 노출 통합 테스트 (FR-TT-01 Task-6).
 *
 * 실제 Testcontainers PostgreSQL + 전체 스택 위에서 동작한다.
 * [TestConfig] 를 재사용하므로 컨테이너 추가 기동이 없다.
 *
 * ## 검증 시나리오
 * - EP-1. PATCH {originalEstimateSeconds:36000, remainingEstimateSeconds:18000} → 200,
 *         GET 이슈가 두 값 반환.
 * - EP-2. PATCH {remainingEstimateSeconds: null} → Clear, GET 이 null 반환.
 * - EP-3. PATCH 본문에 추정 필드 없음(Unchanged) → 기존 값 유지.
 * - EP-4. GET 이슈 응답에 timeSpentSeconds 노출(기본 0).
 * - EP-5. PATCH {originalEstimateSeconds: -1} → 400.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class, IssueEstimatePatchIntegrationTest.JsonNullableConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueEstimatePatchIntegrationTest {
    /**
     * JsonNullable 역직렬화 활성화를 위한 WebMvcConfigurer 보조 설정.
     *
     * [TestConfig] 는 @EnableWebMvc 를 포함하므로 컨텍스트의 Jackson 컨버터는 Spring 이 별도 생성한다.
     * JsonNullable 역직렬화(필드 부재 vs. 명시 null 구분)가 동작하려면 해당 컨버터의 ObjectMapper 에
     * [JsonNullableModule] 이 등록되어야 한다 — [IssueControllerSecurityLevelTest] 와 동일한 패턴.
     *
     * Spring Boot 운영 환경에서는 JacksonAutoConfiguration 이 자동으로 등록하지만
     * @EnableWebMvc 슬라이스에는 자동 등록 경로가 없으므로 명시 등록한다.
     */
    @Configuration
    open class JsonNullableConfig : WebMvcConfigurer {
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters
                .filterIsInstance<MappingJackson2HttpMessageConverter>()
                .forEach { it.objectMapper.registerModule(JsonNullableModule()) }
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dataSource: DriverManagerDataSource

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        /** 이 통합 테스트 전용 프로젝트 키. 다른 통합 테스트 프로젝트와 충돌 없음. */
        private const val PROJECT_KEY = "ESTPATCH"
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
        // CurrentActor 추출(FR-PM-06 PR-B) — 컨트롤러가 인증 주체를 요구하므로 SecurityContext 주입.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── EP-1. originalEstimate + remainingEstimate 설정 후 GET 확인 ─────────────

    /**
     * EP-1.
     *
     * Given  이슈 1건 삽입 (추정 값 없음)
     * When   PATCH {originalEstimateSeconds: 36000, remainingEstimateSeconds: 18000, expectedVersion: 1}
     * Then   200 OK, GET 에서 두 값이 그대로 반환됨.
     */
    @Test
    fun `EP-1 PATCH 추정 설정 후 GET 에서 두 값이 반환된다`() {
        val key = insertIssue("추정 설정 테스트 이슈")

        val patchBody =
            mapOf(
                "originalEstimateSeconds" to 36000,
                "remainingEstimateSeconds" to 18000,
                "expectedVersion" to 1L,
            )

        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.originalEstimateSeconds").value(36000))
            .andExpect(jsonPath("$.data.remainingEstimateSeconds").value(18000))

        mockMvc.perform(get("/api/v1/issues/$key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.originalEstimateSeconds").value(36000))
            .andExpect(jsonPath("$.data.remainingEstimateSeconds").value(18000))
    }

    // ── EP-2. remainingEstimate null(Clear) → GET null ───────────────────────────

    /**
     * EP-2.
     *
     * Given  이슈에 remainingEstimateSeconds=18000 설정
     * When   PATCH {remainingEstimateSeconds: null, expectedVersion: 2}
     * Then   200 OK, GET 에서 remainingEstimateSeconds 가 null 반환.
     */
    @Test
    fun `EP-2 remainingEstimate null PATCH 후 GET 에서 null 이다`() {
        val key = insertIssue("추정 해제 테스트 이슈")

        // 먼저 값 설정
        val setBody =
            mapOf(
                "originalEstimateSeconds" to 36000,
                "remainingEstimateSeconds" to 18000,
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(setBody)),
        ).andExpect(status().isOk)

        // remainingEstimateSeconds 를 null(Clear) 로 패치 — JsonNullable 명시 null
        val clearBody = """{"remainingEstimateSeconds": null, "expectedVersion": 2}"""
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(clearBody),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.remainingEstimateSeconds").doesNotExist())

        mockMvc.perform(get("/api/v1/issues/$key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.remainingEstimateSeconds").doesNotExist())
    }

    // ── EP-3. 추정 필드 부재(Unchanged) → 기존 값 유지 ──────────────────────────

    /**
     * EP-3.
     *
     * Given  이슈에 originalEstimateSeconds=36000 설정
     * When   PATCH 본문에 추정 필드 없음 (summary 만 변경)
     * Then   200 OK, GET 에서 originalEstimateSeconds=36000 유지.
     */
    @Test
    fun `EP-3 추정 필드 부재 시 기존 값이 유지된다`() {
        val key = insertIssue("추정 유지 테스트 이슈")

        // 먼저 original 설정
        val setBody =
            mapOf(
                "originalEstimateSeconds" to 36000,
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(setBody)),
        ).andExpect(status().isOk)

        // 추정 필드 없이 summary 만 변경
        val unchangedBody =
            mapOf(
                "summary" to "수정된 제목",
                "expectedVersion" to 2L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(unchangedBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.originalEstimateSeconds").value(36000))

        mockMvc.perform(get("/api/v1/issues/$key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.originalEstimateSeconds").value(36000))
    }

    // ── EP-4. GET 응답에 timeSpentSeconds 노출(기본 0) ───────────────────────────

    /**
     * EP-4.
     *
     * Given  이슈 1건 삽입 (작업 기록 없음)
     * When   GET /api/v1/issues/{key}
     * Then   timeSpentSeconds=0 반환.
     */
    @Test
    fun `EP-4 GET 응답에 timeSpentSeconds 가 0 으로 노출된다`() {
        val key = insertIssue("timeSpent 노출 확인 이슈")

        mockMvc.perform(get("/api/v1/issues/$key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.timeSpentSeconds").value(0))
    }

    // ── EP-5. 음수 originalEstimateSeconds → 400 ─────────────────────────────────

    /**
     * EP-5.
     *
     * When   PATCH {originalEstimateSeconds: -1, expectedVersion: 1}
     * Then   400 Bad Request.
     */
    @Test
    fun `EP-5 음수 originalEstimateSeconds 는 400 을 반환한다`() {
        val key = insertIssue("음수 추정 검증 이슈")

        val invalidBody =
            mapOf(
                "originalEstimateSeconds" to -1,
                "expectedVersion" to 1L,
            )
        mockMvc.perform(
            patch("/api/v1/issues/$key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(invalidBody)),
        ).andExpect(status().isBadRequest)
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

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

    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Estimate Patch Integration Test Project")
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 테스트용 이슈를 삽입하고 이슈 키를 반환한다.
     *
     * 추정 필드(original/remaining/timeSpent) 는 DB DEFAULT(NULL/0) 가 적용된다.
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
