// FR-VR-01 Task 7 + FR-VR-02 Task 4 — VersionController end-to-end 통합테스트 (S1~S9 + 상태 전이)
@file:Suppress("MaxLineLength")

package com.bts.issue.version.web

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.project.repository.ProjectLookupRepository
import com.bts.issue.version.adapter.AlwaysAllowVersionPermissionResolver
import com.bts.issue.version.application.VersionApplicationService
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * FR-VR-01 Task 7 — VersionController end-to-end 통합테스트.
 *
 * 실제 Testcontainers PostgreSQL + 전체 스택(Controller→Service→Repository) 위에서 동작한다.
 * Spring AOP @Transactional 이 실제로 동작하도록 @EnableTransactionManagement 포함.
 *
 * ## 검증 시나리오
 * - S1. POST 201 (날짜 有)
 * - S2. POST 201 (날짜 無)
 * - S3. POST 201 (releaseDate가 startDate 보다 앞 — 날짜 순서 미강제 확인)
 * - S4. GET 목록 200 (소프트 삭제 제외, name 정렬)
 * - S5. GET 단건 200
 * - S6. PATCH name·description 200
 * - S7. PATCH /dates 200 (날짜 지정)
 * - S8. PATCH /dates 200 (날짜 해제 — null 전달)
 * - S9. DELETE 204
 * - S10. 404 — 존재하지 않는 프로젝트 / 버전
 * - S11. 409 — 중복 이름
 * - S12. 400 — name 공백 (Jakarta @NotBlank 검증 실패)
 *
 * ## 401 미인증 (이 통합테스트 범위 외)
 * 통합 컨텍스트는 Spring Security 필터 없는 경량 WebMvc 스택이라 401 을 재현할 수 없다.
 * 미인증 401 은 SecurityConfig 가 /api 하위 경로를 authenticated 로 묶어 버전
 * 엔드포인트까지 런타임 보장하며(스펙 S12/FR-5), 필터 체인 동작은 identity-access
 * 모듈 테스트가 검증한다. 실 권한 판정은 FR-PM-03 이연.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [VersionControllerIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VersionControllerIntegrationTest {
    // ── Spring Bean 구성 — 최소 필요 컴포넌트만 명시적 등록 ─────────────────────
    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            /** JVM 단위 singleton Testcontainers — singleton pattern */
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_vr_test")
                    .withUsername("bts")
                    .withPassword("bts_vr_test")
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
        open fun clock(): Clock = VersionControllerIntegrationTest.FIXED_CLOCK

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
        private const val PROJECT_KEY = "VRTEST"
        private var migrated = false
        private var seeded = false

        /** 결정론적 Clock — release 전이 시각 검증용. */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC)
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
                stmt.execute("DELETE FROM versions WHERE project_id = (SELECT id FROM projects WHERE key = '$PROJECT_KEY')")
            }
        }
    }

    // ── S1. POST 201 — 날짜 有 ────────────────────────────────────────────────

    /**
     * S1 POST 201 날짜 有.
     *
     * Given  VRTEST 프로젝트 존재
     * When   POST /api/v1/projects/VRTEST/versions { name, description, startDate, releaseDate }
     * Then   201 + data.name / data.startDate / data.releaseDate 확인
     */
    @Test
    fun `S1 POST 201 날짜 有 - name, description, startDate, releaseDate 응답 확인`() {
        val body =
            mapOf(
                "name" to "v1.0",
                "description" to "첫 릴리스",
                "startDate" to "2026-01-01",
                "releaseDate" to "2026-03-31",
            )

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_KEY/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.name").value("v1.0"))
            .andExpect(jsonPath("$.data.description").value("첫 릴리스"))
            .andExpect(jsonPath("$.data.startDate").value("2026-01-01"))
            .andExpect(jsonPath("$.data.releaseDate").value("2026-03-31"))
            .andExpect(jsonPath("$.data.id").isNotEmpty)
    }

    // ── S2. POST 201 — 날짜 無 ────────────────────────────────────────────────

    /**
     * S2 POST 201 날짜 無.
     *
     * Given  VRTEST 프로젝트 존재
     * When   POST { name } (날짜 미포함)
     * Then   201 + data.startDate / data.releaseDate 없음
     */
    @Test
    fun `S2 POST 201 날짜 無 - startDate releaseDate null 응답 확인`() {
        val body = mapOf("name" to "v2.0")

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_KEY/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.name").value("v2.0"))
            .andExpect(jsonPath("$.data.startDate").doesNotExist())
            .andExpect(jsonPath("$.data.releaseDate").doesNotExist())
    }

    // ── S3. POST 201 — releaseDate < startDate 날짜 순서 미강제 ───────────────

    /**
     * S3 POST 201 날짜 역순.
     *
     * Given  VRTEST 프로젝트 존재
     * When   POST { name, startDate, releaseDate } releaseDate < startDate
     * Then   201 — 날짜 선후 관계를 강제하지 않음 (Jira 기본 동작)
     */
    @Test
    fun `S3 POST 201 날짜 역순 - releaseDate가 startDate 앞이어도 201`() {
        val body =
            mapOf(
                "name" to "v3.0-reversed",
                "startDate" to "2026-06-01",
                "releaseDate" to "2026-01-01",
            )

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_KEY/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.name").value("v3.0-reversed"))
    }

    // ── S4. GET 목록 200 — 소프트 삭제 제외, name 정렬 ────────────────────────

    /**
     * S4 GET 목록 200.
     *
     * Given  버전 2건 삽입 후 1건 소프트 삭제
     * When   GET /api/v1/projects/VRTEST/versions
     * Then   200 + 활성 1건만 반환 (name 정렬)
     */
    @Test
    fun `S4 GET 목록 200 - 소프트 삭제 제외 활성 버전만 반환`() {
        val id1 = createVersion("Alpha-v1")
        createVersion("Zeta-v1")

        mockMvc.perform(delete("/api/v1/projects/$PROJECT_KEY/versions/$id1"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("Zeta-v1"))
    }

    // ── S5. GET 단건 200 ─────────────────────────────────────────────────────

    /**
     * S5 GET 단건 200.
     *
     * Given  버전 1건 삽입
     * When   GET /api/v1/projects/VRTEST/versions/{id}
     * Then   200 + data.id, data.name 확인
     */
    @Test
    fun `S5 GET 단건 200 - id, name 응답 확인`() {
        val id = createVersion("Release-Candidate")

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(id.toString()))
            .andExpect(jsonPath("$.data.name").value("Release-Candidate"))
    }

    // ── S6. PATCH name·description 200 ───────────────────────────────────────

    /**
     * S6 PATCH name·description 200.
     *
     * Given  버전 1건 삽입 (name="Old", description="원본")
     * When   PATCH /{id} { name: "New", description: "변경됨" }
     * Then   200 + data.name = "New", data.description = "변경됨"
     */
    @Test
    fun `S6 PATCH name description 200 - VersionRepository update end-to-end 검증`() {
        val id = createVersion("Old", description = "원본")

        val body = mapOf("name" to "New", "description" to "변경됨")

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/versions/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("New"))
            .andExpect(jsonPath("$.data.description").value("변경됨"))
    }

    // ── S7. PATCH /dates 200 — 날짜 지정 ─────────────────────────────────────

    /**
     * S7 PATCH /dates 200 날짜 지정.
     *
     * Given  버전 1건 (날짜 無)
     * When   PATCH /{id}/dates { startDate: "2026-01-01", releaseDate: "2026-06-30" }
     * Then   200 + data.startDate = "2026-01-01", data.releaseDate = "2026-06-30"
     */
    @Test
    fun `S7 PATCH dates 200 - 날짜 지정 확인`() {
        val id = createVersion("Dated-Version")

        val body =
            mapOf(
                "startDate" to "2026-01-01",
                "releaseDate" to "2026-06-30",
            )

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/versions/$id/dates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.startDate").value("2026-01-01"))
            .andExpect(jsonPath("$.data.releaseDate").value("2026-06-30"))
    }

    // ── S8. PATCH /dates 200 — 날짜 해제 ─────────────────────────────────────

    /**
     * S8 PATCH /dates 200 날짜 해제.
     *
     * Given  버전 1건 (startDate, releaseDate 있음)
     * When   PATCH /{id}/dates { startDate: null, releaseDate: null }
     * Then   200 + data.startDate 없음, data.releaseDate 없음
     */
    @Test
    fun `S8 PATCH dates 200 - 날짜 null 로 해제 확인`() {
        val id = createVersion("Undated-Version", startDate = "2026-01-01", releaseDate = "2026-12-31")

        val body =
            mapOf(
                "startDate" to null,
                "releaseDate" to null,
            )

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/versions/$id/dates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.startDate").doesNotExist())
            .andExpect(jsonPath("$.data.releaseDate").doesNotExist())
    }

    // ── S9. DELETE 204 ────────────────────────────────────────────────────────

    /**
     * S9 DELETE 204.
     *
     * Given  버전 1건 삽입
     * When   DELETE /{id}
     * Then   204 No Content, 이후 GET 단건 → 404
     */
    @Test
    fun `S9 DELETE 204 - 이후 단건 GET 404 확인`() {
        val id = createVersion("ToDelete")

        mockMvc.perform(delete("/api/v1/projects/$PROJECT_KEY/versions/$id"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions/$id"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("VERSION_NOT_FOUND"))
    }

    // ── S10. 404 — 존재하지 않는 프로젝트 / 버전 ─────────────────────────────

    /**
     * S10a 404 PROJECT_NOT_FOUND.
     *
     * Given  존재하지 않는 projectKey "NOPROJECT"
     * When   POST /api/v1/projects/NOPROJECT/versions
     * Then   404 + errorCode = PROJECT_NOT_FOUND
     */
    @Test
    fun `S10a 존재하지 않는 프로젝트로 POST - 404 PROJECT_NOT_FOUND`() {
        val body = mapOf("name" to "X")

        mockMvc.perform(
            post("/api/v1/projects/NOPROJECT/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("PROJECT_NOT_FOUND"))
    }

    /**
     * S10b 404 VERSION_NOT_FOUND.
     *
     * Given  존재하지 않는 버전 UUID
     * When   GET /api/v1/projects/VRTEST/versions/{randomId}
     * Then   404 + errorCode = VERSION_NOT_FOUND
     */
    @Test
    fun `S10b 존재하지 않는 버전 GET - 404 VERSION_NOT_FOUND`() {
        val randomId = UUID.randomUUID()

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions/$randomId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("VERSION_NOT_FOUND"))
    }

    // ── S11. 409 — 중복 이름 ──────────────────────────────────────────────────

    /**
     * S11 409 VERSION_NAME_DUPLICATE.
     *
     * Given  "v1.0" 버전 이미 존재
     * When   POST { name: "v1.0" } 다시 요청
     * Then   409 + errorCode = VERSION_NAME_DUPLICATE
     */
    @Test
    fun `S11 중복 이름 POST - 409 VERSION_NAME_DUPLICATE`() {
        createVersion("v1.0-dup")

        val body = mapOf("name" to "v1.0-dup")

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_KEY/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_NAME_DUPLICATE"))
    }

    // ── S13. 409 — 활성 동명으로 PATCH rename ────────────────────────────────

    /**
     * S13 409 VERSION_NAME_DUPLICATE — PATCH rename 중복.
     *
     * Given  "v-alpha" 버전과 "v-beta" 버전이 각각 존재
     * When   PATCH "v-beta" 의 name 을 "v-alpha" 로 rename
     * Then   409 + errorCode = VERSION_NAME_DUPLICATE
     */
    @Test
    fun `S13 활성 동명으로 PATCH rename - 409 VERSION_NAME_DUPLICATE`() {
        createVersion("v-alpha")
        val betaId = createVersion("v-beta")

        val body = mapOf("name" to "v-alpha")

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/versions/$betaId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_NAME_DUPLICATE"))
    }

    // ── S12. 400 — name @NotBlank 검증 실패 ──────────────────────────────────

    /**
     * S12 400 VALIDATION_FAILED.
     *
     * Given  name 필드 공백
     * When   POST { name: "" }
     * Then   400 + errorCode = VALIDATION_FAILED
     */
    @Test
    fun `S12 name 공백 POST - 400 VALIDATION_FAILED`() {
        val body = mapOf("name" to "")

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_KEY/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    // ── FR-VR-02: 상태 전이 (S1~S7) ────────────────────────────────────────

    /**
     * FR-VR-02 S1 — PATCH /status RELEASED → 200, status=RELEASED, releasedAt!=null.
     *
     * Given  UNRELEASED 버전
     * When   PATCH /{id}/status { status: "RELEASED" }
     * Then   200 + data.status=RELEASED, data.releasedAt=고정 시각
     */
    @Test
    fun `FR-VR-02 S1 PATCH status RELEASED - 200 status=RELEASED releasedAt 설정`() {
        val id = createVersion("vr02-s1")

        val body = mapOf("status" to "RELEASED")

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/versions/$id/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("RELEASED"))
            .andExpect(jsonPath("$.data.releasedAt").value("2026-06-10T12:00:00Z"))
    }

    /**
     * FR-VR-02 S2 — PATCH /status UNRELEASED (from RELEASED) → 200, releasedAt=null.
     *
     * Given  RELEASED 상태 버전
     * When   PATCH /{id}/status { status: "UNRELEASED" }
     * Then   200 + data.status=UNRELEASED, data.releasedAt 없음
     */
    @Test
    fun `FR-VR-02 S2 PATCH status UNRELEASED from RELEASED - 200 releasedAt null`() {
        val id = createVersion("vr02-s2")
        patchStatus(id, "RELEASED")

        val body = mapOf("status" to "UNRELEASED")

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/versions/$id/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("UNRELEASED"))
            .andExpect(jsonPath("$.data.releasedAt").doesNotExist())
    }

    /**
     * FR-VR-02 S3 — PATCH /status ARCHIVED → 200.
     *
     * Given  UNRELEASED 버전
     * When   PATCH /{id}/status { status: "ARCHIVED" }
     * Then   200 + data.status=ARCHIVED
     */
    @Test
    fun `FR-VR-02 S3 PATCH status ARCHIVED from UNRELEASED - 200`() {
        val id = createVersion("vr02-s3")

        val body = mapOf("status" to "ARCHIVED")

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/versions/$id/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
    }

    /**
     * FR-VR-02 S5 — PATCH /status RELEASED (from ARCHIVED) → 409 VERSION_TRANSITION_NOT_ALLOWED.
     *
     * Given  ARCHIVED 상태 버전
     * When   PATCH /{id}/status { status: "RELEASED" }
     * Then   409 + errorCode=VERSION_TRANSITION_NOT_ALLOWED
     */
    @Test
    fun `FR-VR-02 S5 PATCH status RELEASED from ARCHIVED - 409 VERSION_TRANSITION_NOT_ALLOWED`() {
        val id = createVersion("vr02-s5")
        patchStatus(id, "ARCHIVED")

        val body = mapOf("status" to "RELEASED")

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/versions/$id/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_TRANSITION_NOT_ALLOWED"))
    }

    /**
     * FR-VR-02 S6 — PATCH /{id} rename on ARCHIVED → 409 VERSION_TRANSITION_NOT_ALLOWED.
     *
     * Given  ARCHIVED 상태 버전
     * When   PATCH /{id} { name: "new-name" }
     * Then   409 + errorCode=VERSION_TRANSITION_NOT_ALLOWED
     */
    @Test
    fun `FR-VR-02 S6 PATCH rename on ARCHIVED - 409 VERSION_TRANSITION_NOT_ALLOWED`() {
        val id = createVersion("vr02-s6")
        patchStatus(id, "ARCHIVED")

        val body = mapOf("name" to "vr02-s6-renamed")

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/versions/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_TRANSITION_NOT_ALLOWED"))
    }

    /**
     * FR-VR-02 EC3 — PATCH /status "FOO" → 400 VALIDATION_FAILED.
     *
     * Given  유효한 버전
     * When   PATCH /{id}/status { status: "FOO" } (잘못된 enum 문자열)
     * Then   400 + errorCode=VALIDATION_FAILED
     */
    @Test
    fun `FR-VR-02 EC3 PATCH status invalid enum value - 400 VALIDATION_FAILED`() {
        val id = createVersion("vr02-ec3")

        val body = mapOf("status" to "FOO")

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/versions/$id/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    /**
     * FR-VR-02 S7 — GET 목록/단건 응답에 status, releasedAt 포함.
     *
     * Given  RELEASED 상태로 전이된 버전
     * When   GET /{id}
     * Then   200 + data.status=RELEASED, data.releasedAt!=null
     */
    @Test
    fun `FR-VR-02 S7 GET 단건 응답에 status releasedAt 포함`() {
        val id = createVersion("vr02-s7")
        patchStatus(id, "RELEASED")

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("RELEASED"))
            .andExpect(jsonPath("$.data.releasedAt").value("2026-06-10T12:00:00Z"))
    }

    /**
     * FR-VR-02 S7(목록) — GET 목록 응답에 status 포함.
     *
     * Given  UNRELEASED 신규 버전
     * When   GET 목록
     * Then   200 + 각 항목에 data[*].status=UNRELEASED
     */
    @Test
    fun `FR-VR-02 S7 GET 목록 응답에 status 포함`() {
        createVersion("vr02-s7-list")

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/versions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].status").value("UNRELEASED"))
    }

    // ── private helpers ─────────────────────────────────────────────────────

    /**
     * 헬퍼: POST 로 버전을 생성하고 생성된 id(UUID)를 반환한다.
     */
    private fun createVersion(
        name: String,
        description: String? = null,
        startDate: String? = null,
        releaseDate: String? = null,
    ): UUID {
        val body =
            buildMap<String, Any?> {
                put("name", name)
                if (description != null) put("description", description)
                if (startDate != null) put("startDate", startDate)
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
     * 헬퍼: PATCH /{id}/status 로 버전 상태를 전이한다.
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
                stmt.setString(2, "Version Integration Test Project")
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
