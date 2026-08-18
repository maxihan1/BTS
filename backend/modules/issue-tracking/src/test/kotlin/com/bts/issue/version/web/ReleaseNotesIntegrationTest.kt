// FR-VR-04 Task 4 — ReleaseNotesController end-to-end 통합테스트 (S1~S7)
@file:Suppress("MaxLineLength")

package com.bts.issue.version.web

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.project.repository.ProjectLookupRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.version.adapter.AlwaysAllowVersionPermissionResolver
import com.bts.issue.version.application.VersionApplicationService
import com.bts.issue.version.releasenotes.ReleaseNotesService
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.permission.VersionPermissionResolver
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * FR-VR-04 Task 4 — ReleaseNotesController end-to-end 통합테스트.
 *
 * 실제 Testcontainers PostgreSQL + 전체 스택(Controller→Service→Repository) 위에서 동작한다.
 * Spring AOP @Transactional 이 실제로 동작하도록 @EnableTransactionManagement 포함.
 *
 * ## 검증 시나리오
 * - S1. GET 200 — ReleaseNotesResponse(메타 + markdown), 타입별 그룹 본문 확인.
 * - S2. GET 200 — fix version 이슈 0건 → issueCount=0, 빈 안내 markdown.
 * - S3. GET 200 — resolution 설정 이슈 → markdown에 resolution 표시.
 * - S4. GET 404 — 버전 미존재 → errorCode=VERSION_NOT_FOUND.
 * - S5. GET 404 — 프로젝트 미존재 → errorCode=PROJECT_NOT_FOUND.
 * - S7a. GET 200 — ARCHIVED 버전 → 정상 응답.
 * - S7b. GET 404 — soft-delete 버전 → VERSION_NOT_FOUND.
 *
 * ## 401 미인증 (이 통합테스트 범위 외)
 * 통합 컨텍스트는 Spring Security 필터 없는 경량 WebMvc 스택이라 401 을 재현할 수 없다.
 * 미인증 401 은 SecurityConfig 가 /api 하위 경로를 authenticated 로 묶어 버전
 * 엔드포인트까지 런타임 보장한다.
 *
 * ## VersionExceptionHandler 스코프 검증
 * [VersionExceptionHandler] 는 `basePackages = ["com.bts.issue.version.web"]` 로 한정된다.
 * [ReleaseNotesController] 가 동일 패키지(`com.bts.issue.version.web`)에 위치하므로
 * 도메인 예외(VersionNotFoundException / VersionProjectNotFoundException)가 500이 아닌
 * 올바른 404 + errorCode 로 응답함을 S4/S5 시나리오가 검증한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ReleaseNotesIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseNotesIntegrationTest {
    // ── Spring Bean 구성 — 최소 필요 컴포넌트만 명시적 등록 ─────────────────────
    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig : WebMvcConfigurer {
        /**
         * @EnableWebMvc 기본 Jackson 컨버터는 Instant 를 epoch timestamp 로 직렬화한다.
         * 기존 컨버터를 교체하지 않고(= Spring 의 ProblemDetail 믹스인·errorCode 직렬화 보존)
         * 매퍼 설정만 보강해 Instant(generatedAt) 가 ISO-8601 로 나오게 한다.
         * Spring Boot 의 기본 Jackson 설정과 동등 — prod 직렬화 형식을 통합테스트가 검증한다.
         */
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters.filterIsInstance<MappingJackson2HttpMessageConverter>().forEach { converter ->
                converter.objectMapper
                    .registerModule(JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }
        }

        companion object {
            /** JVM 단위 singleton Testcontainers — singleton pattern */
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_rn_test")
                    .withUsername("bts")
                    .withPassword("bts_rn_test")
                    .apply { start() }
        }

        @Bean
        open fun dataSource(): DriverManagerDataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )

        @Bean
        open fun transactionManager(dataSource: DriverManagerDataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        // ── 버전 BC 빈 ─────────────────────────────────────────────────────────

        @Bean
        open fun versionRepository(dsl: DSLContext): VersionRepository = VersionRepository(dsl)

        @Bean
        open fun projectLookupRepository(dsl: DSLContext): ProjectLookupRepository = ProjectLookupRepository(dsl)

        @Bean
        open fun projectLookup(repository: ProjectLookupRepository): ProjectLookup = ProjectLookup(repository)

        @Bean
        @Profile("test")
        open fun versionPermissionResolver(): VersionPermissionResolver = AlwaysAllowVersionPermissionResolver()

        @Bean
        open fun clock(): Clock = FIXED_CLOCK

        /** FR-PJ-04 PR-4 Task 9 — 실 ProjectArchiveGuard(공유 dsl 위). */
        @Bean
        open fun projectArchiveGuard(dsl: DSLContext): ProjectArchiveGuard {
            return ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))
        }

        @Bean
        open fun versionApplicationService(
            permissionResolver: VersionPermissionResolver,
            projectLookup: ProjectLookup,
            repo: VersionRepository,
            archiveGuard: ProjectArchiveGuard,
            clock: Clock,
        ): VersionApplicationService =
            VersionApplicationService(
                permissionResolver = permissionResolver,
                projectLookup = projectLookup,
                repo = repo,
                archiveGuard = archiveGuard,
                clock = clock,
            )

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

        @Bean
        open fun resolutionRepository(dsl: DSLContext): ResolutionRepository = ResolutionRepository(dsl)

        @Bean
        open fun releaseNotesService(
            projectLookup: ProjectLookup,
            projectLookupRepository: ProjectLookupRepository,
            versionRepository: VersionRepository,
            issueRepository: IssueRepository,
            resolutionRepository: ResolutionRepository,
        ): ReleaseNotesService =
            ReleaseNotesService(
                projectLookup = projectLookup,
                projectLookupRepository = projectLookupRepository,
                versionRepository = versionRepository,
                issueRepository = issueRepository,
                resolutionRepository = resolutionRepository,
                clock = clock(),
            )

        @Bean
        open fun releaseNotesController(service: ReleaseNotesService): ReleaseNotesController {
            return ReleaseNotesController(service)
        }

        @Bean
        open fun versionController(service: VersionApplicationService): VersionController = VersionController(service)

        @Bean
        open fun versionExceptionHandler(): VersionExceptionHandler = VersionExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper =
        ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        private const val PROJECT_KEY = "RNTEST"
        private var initialized = false

        /** 결정론적 Clock — generatedAt 검증용. */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC)
    }

    @BeforeAll
    fun setUpAll() {
        if (!initialized) {
            applyMigrations()
            seedProject()
            initialized = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        conn().use { c ->
            c.createStatement().use { stmt ->
                // 버전과 이슈 연결 테이블 초기화 (프로젝트는 유지)
                stmt.execute(
                    "DELETE FROM issue_fix_versions WHERE version_id IN " +
                        "(SELECT id FROM versions WHERE project_id = " +
                        "(SELECT id FROM projects WHERE key = '$PROJECT_KEY'))",
                )
                stmt.execute(
                    "DELETE FROM issues WHERE project_id = " +
                        "(SELECT id FROM projects WHERE key = '$PROJECT_KEY')",
                )
                stmt.execute(
                    "DELETE FROM versions WHERE project_id = " +
                        "(SELECT id FROM projects WHERE key = '$PROJECT_KEY')",
                )
            }
        }
    }

    // ── S1. GET 200 — 이슈 2건, 타입별 그룹 ────────────────────────────────────

    /**
     * S1 GET 200 해피패스.
     *
     * Given  RNTEST/v1.0 버전 + Bug 이슈 1건 + Story 이슈 1건이 fix version 연결
     * When   GET /api/v1/projects/RNTEST/versions/{id}/release-notes
     * Then   200 + versionId/versionName/issueCount=2/markdown 포함
     */
    @Test
    fun `S1 GET 200 - 메타데이터와 markdown 응답 확인`() {
        val versionId = createVersion("v1.0", releaseDate = "2026-06-10")
        val bugTypeId = issueTypeId("Bug")
        val storyTypeId = issueTypeId("Story")
        val issue1 = createIssue("RNTEST-1", "버그 수정", bugTypeId)
        val issue2 = createIssue("RNTEST-2", "스토리 완료", storyTypeId)
        linkFixVersion(issue1, versionId)
        linkFixVersion(issue2, versionId)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions/$versionId/release-notes"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.versionId").value(versionId.toString()))
            .andExpect(jsonPath("$.data.versionName").value("v1.0"))
            .andExpect(jsonPath("$.data.projectKey").value(PROJECT_KEY))
            .andExpect(jsonPath("$.data.issueCount").value(2))
            .andExpect(jsonPath("$.data.generatedAt").value("2026-06-10T12:00:00Z"))
            .andExpect(jsonPath("$.data.markdown").isNotEmpty)
            .andExpect(jsonPath("$.data.markdown").value(org.hamcrest.Matchers.containsString("RNTEST-1")))
            .andExpect(jsonPath("$.data.markdown").value(org.hamcrest.Matchers.containsString("RNTEST-2")))
    }

    // ── S2. GET 200 — fix version 이슈 0건 ─────────────────────────────────────

    /**
     * S2 GET 200 이슈 0건.
     *
     * Given  버전에 연결된 fix version 이슈가 없을 때
     * When   GET .../release-notes
     * Then   200 + issueCount=0 + "포함된 이슈가 없습니다." markdown
     */
    @Test
    fun `S2 GET 200 - fix version 이슈 0건이면 issueCount=0 안내 markdown`() {
        val versionId = createVersion("v2.0-empty")

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions/$versionId/release-notes"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.issueCount").value(0))
            .andExpect(
                jsonPath("$.data.markdown").value(
                    org.hamcrest.Matchers.containsString("포함된 이슈가 없습니다."),
                ),
            )
    }

    // ── S3. GET 200 — resolution 표시 ──────────────────────────────────────────

    /**
     * S3 GET 200 resolution 표시.
     *
     * Given  resolution(Fixed) 설정 이슈가 fix version 연결
     * When   GET .../release-notes
     * Then   200 + markdown에 "(Fixed)" 포함
     */
    @Test
    fun `S3 GET 200 - resolution 있는 이슈는 markdown에 resolution 표시`() {
        val versionId = createVersion("v3.0-resolved")
        val bugTypeId = issueTypeId("Bug")
        val resolutionId = ensureResolution("Fixed")
        val issue = createIssue("RNTEST-10", "해결된 버그", bugTypeId, resolutionId = resolutionId)
        linkFixVersion(issue, versionId)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions/$versionId/release-notes"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.markdown").value(org.hamcrest.Matchers.containsString("(Fixed)")))
    }

    // ── S4. GET 404 — 버전 미존재 ──────────────────────────────────────────────

    /**
     * S4 GET 404 VERSION_NOT_FOUND.
     *
     * Given  존재하지 않는 versionId
     * When   GET .../release-notes
     * Then   404 + errorCode=VERSION_NOT_FOUND
     *
     * VersionExceptionHandler 스코프 검증 — basePackages="com.bts.issue.version.web" 가
     * ReleaseNotesController 도 커버하므로 500이 아닌 404 응답 확인.
     */
    @Test
    fun `S4 GET 404 - 버전 미존재 VERSION_NOT_FOUND`() {
        val nonExistentId = UUID.randomUUID()

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions/$nonExistentId/release-notes"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("VERSION_NOT_FOUND"))
    }

    // ── S5. GET 404 — 프로젝트 미존재 ──────────────────────────────────────────

    /**
     * S5 GET 404 PROJECT_NOT_FOUND.
     *
     * Given  존재하지 않는 projectIdOrKey "NOPROJECT"
     * When   GET .../release-notes
     * Then   404 + errorCode=PROJECT_NOT_FOUND
     *
     * VersionExceptionHandler 스코프 검증 — 동일하게 500이 아닌 404 확인.
     */
    @Test
    fun `S5 GET 404 - 프로젝트 미존재 PROJECT_NOT_FOUND`() {
        val randomVersionId = UUID.randomUUID()

        mockMvc.perform(get("/api/v1/projects/NOPROJECT/versions/$randomVersionId/release-notes"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("PROJECT_NOT_FOUND"))
    }

    // ── S7a. GET 200 — ARCHIVED 버전 ────────────────────────────────────────────

    /**
     * S7a GET 200 ARCHIVED 버전.
     *
     * Given  ARCHIVED 상태 버전
     * When   GET .../release-notes
     * Then   200 — 릴리즈 노트는 읽기 동작이므로 ARCHIVED 상태 무관 허용
     */
    @Test
    fun `S7a GET 200 - ARCHIVED 버전은 정상 응답`() {
        val versionId = createVersion("v7-archived")
        patchStatus(versionId, "ARCHIVED")

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions/$versionId/release-notes"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.versionStatus").value("ARCHIVED"))
    }

    // ── S7b. GET 404 — soft-delete 버전 ────────────────────────────────────────

    /**
     * S7b GET 404 soft-delete 버전.
     *
     * Given  소프트 삭제된(deleted_at 설정) 버전
     * When   GET .../release-notes
     * Then   404 + errorCode=VERSION_NOT_FOUND
     */
    @Test
    fun `S7b GET 404 - soft-delete 버전은 VERSION_NOT_FOUND`() {
        val versionId = createVersion("v7-deleted")
        softDeleteVersion(versionId)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions/$versionId/release-notes"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("VERSION_NOT_FOUND"))
    }

    // ── private helpers ─────────────────────────────────────────────────────

    /**
     * 헬퍼: POST 로 버전을 생성하고 생성된 id(UUID)를 반환한다.
     */
    private fun createVersion(
        name: String,
        releaseDate: String? = null,
    ): UUID {
        val body =
            buildMap<String, Any?> {
                put("name", name)
                if (releaseDate != null) put("releaseDate", releaseDate)
            }

        val result =
            mockMvc
                .perform(
                    post("/api/v1/projects/$PROJECT_KEY/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)),
                )
                .andExpect(status().isCreated)
                .andReturn()

        val json = mapper.readTree(result.response.contentAsString)
        return UUID.fromString(json["data"]["id"].asText())
    }

    /**
     * 헬퍼: PATCH /{id}/status 로 버전 상태를 전환한다.
     */
    private fun patchStatus(
        id: UUID,
        status: String,
    ) {
        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/versions/$id/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("status" to status))),
        ).andExpect(status().isOk)
    }

    /**
     * 헬퍼: 버전을 soft-delete 한다(deleted_at 직접 설정).
     */
    private fun softDeleteVersion(versionId: UUID) {
        conn().use { c ->
            c.prepareStatement("UPDATE versions SET deleted_at = NOW() WHERE id = ?").use { stmt ->
                stmt.setObject(1, versionId)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 헬퍼: 이슈를 생성하고 id 를 반환한다.
     *
     * workflow_current_state_key 기본값 — issue_tracking 모듈 기본 세팅.
     */
    private fun createIssue(
        issueKey: String,
        summary: String,
        issueTypeId: Long,
        resolutionId: UUID? = null,
    ): UUID {
        val issueId = UUID.randomUUID()
        val projectId = projectId()
        conn().use { c ->
            c.prepareStatement(
                """
                INSERT INTO issues (id, project_id, key, summary, type_id, resolution_id,
                    reporter_id, current_state_key)
                VALUES (?, ?, ?, ?, ?, ?, '00000000-0000-0000-0000-000000000001', 'open')
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, issueId)
                stmt.setObject(2, projectId)
                stmt.setString(3, issueKey)
                stmt.setString(4, summary)
                stmt.setLong(5, issueTypeId)
                stmt.setObject(6, resolutionId)
                stmt.executeUpdate()
            }
        }
        return issueId
    }

    /**
     * 헬퍼: fix version 연결을 생성한다.
     */
    private fun linkFixVersion(
        issueId: UUID,
        versionId: UUID,
    ) {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO issue_fix_versions (issue_id, version_id) VALUES (?, ?)",
            ).use { stmt ->
                stmt.setObject(1, issueId)
                stmt.setObject(2, versionId)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 헬퍼: 이슈 타입 id 를 typeName 으로 조회한다.
     */
    private fun issueTypeId(typeName: String): Long {
        conn().use { c ->
            c.prepareStatement("SELECT id FROM issue_types WHERE name = ? AND deleted_at IS NULL").use { stmt ->
                stmt.setString(1, typeName)
                val rs = stmt.executeQuery()
                check(rs.next()) { "이슈 타입 '$typeName' 미존재" }
                return rs.getLong("id")
            }
        }
    }

    /**
     * 헬퍼: resolution 을 보장하고 id 를 반환한다.
     * 없으면 INSERT, 있으면 SELECT.
     */
    private fun ensureResolution(name: String): UUID {
        conn().use { c ->
            c.prepareStatement("SELECT id FROM resolutions WHERE name = ? AND deleted_at IS NULL").use { stmt ->
                stmt.setString(1, name)
                val rs = stmt.executeQuery()
                if (rs.next()) return rs.getObject("id") as UUID
            }
            val newId = UUID.randomUUID()
            c.prepareStatement(
                "INSERT INTO resolutions (id, key, name, description, display_order) VALUES (?, ?, ?, '', 999)",
            ).use { stmt ->
                stmt.setObject(1, newId)
                stmt.setString(2, name.lowercase())
                stmt.setString(3, name)
                stmt.executeUpdate()
            }
            return newId
        }
    }

    /**
     * 헬퍼: 프로젝트 id 를 조회한다.
     */
    private fun projectId(): UUID {
        conn().use { c ->
            c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                val rs = stmt.executeQuery()
                check(rs.next()) { "프로젝트 $PROJECT_KEY 미존재" }
                return rs.getObject("id") as UUID
            }
        }
    }

    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(
                TestConfig.postgres.jdbcUrl,
                TestConfig.postgres.username,
                TestConfig.postgres.password,
            )
            .placeholderReplacement(false)
            .locations("classpath:db/migration/issue-tracking")
            .load()
            .migrate()
    }

    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Release Notes Integration Test Project")
                stmt.executeUpdate()
            }
        }
    }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
