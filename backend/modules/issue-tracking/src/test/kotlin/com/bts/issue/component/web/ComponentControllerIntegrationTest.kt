// FR-CM-01 Task 7 — ComponentController end-to-end 통합테스트 (S1~S10 전 스택 검증)
@file:Suppress("MaxLineLength")

package com.bts.issue.component.web

import com.bts.issue.component.adapter.AlwaysAllowComponentPermissionResolver
import com.bts.issue.component.application.ComponentApplicationService
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.project.repository.ProjectLookupRepository
import com.bts.shared.permission.ComponentPermissionResolver
import com.bts.shared.user.UserLookupPort
import com.fasterxml.jackson.databind.ObjectMapper
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
import java.util.UUID

/**
 * FR-CM-01 Task 7 — ComponentController end-to-end 통합테스트.
 *
 * 실제 Testcontainers PostgreSQL + 전체 스택(Controller→Service→Repository) 위에서 동작한다.
 * Spring AOP @Transactional 이 실제로 동작하도록 @EnableTransactionManagement 포함.
 *
 * ## 검증 시나리오
 * - S1. POST 201 (리드 有)
 * - S2. POST 201 (리드 無)
 * - S3. GET 목록 200 (소프트 삭제 제외)
 * - S4. GET 단건 200
 * - S5. PATCH name·description 200 — ComponentRepository.update end-to-end 검증
 * - S6. PATCH /lead 200 (지정)
 * - S7. PATCH /lead 200 (해제)
 * - S8. DELETE 204
 * - S9. 404 — 존재하지 않는 프로젝트 / 컴포넌트
 * - S10. 409 중복 이름
 * - S11. 422 미존재 리드
 * - S12. 400 — name 공백 (Jakarta @NotBlank 검증 실패)
 *
 * ## 401 미인증 (이 통합테스트 범위 외)
 * 통합 컨텍스트는 Spring Security 필터 없는 경량 WebMvc 스택이라 401 을 재현할 수 없다.
 * 미인증 401 은 SecurityConfig 가 /api 하위 경로를 authenticated 로 묶어 컴포넌트
 * 엔드포인트까지 런타임 보장하며(스펙 S10/FR-5), 필터 체인 동작은 identity-access
 * 모듈 테스트가 검증한다. 실 권한 판정은 FR-PM-03 이연.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ComponentControllerIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComponentControllerIntegrationTest {
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
                    .withDatabaseName("bts_cm_test")
                    .withUsername("bts")
                    .withPassword("bts_cm_test")
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

        // ── 컴포넌트 BC 빈 ─────────────────────────────────────────────────────

        @Bean
        open fun componentRepository(dsl: DSLContext): ComponentRepository = ComponentRepository(dsl)

        @Bean
        open fun projectLookupRepository(dsl: DSLContext): ProjectLookupRepository = ProjectLookupRepository(dsl)

        @Bean
        open fun projectLookup(repository: ProjectLookupRepository): ProjectLookup = ProjectLookup(repository)

        /**
         * 테스트 환경 UserLookupPort — [KNOWN_LEAD_ID] 만 exists=true.
         * S11(422) 시나리오는 [UNKNOWN_LEAD_ID] 전달로 재현한다.
         */
        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                private val known = setOf(KNOWN_LEAD_ID)

                override fun exists(userId: UUID): Boolean = userId in known
            }

        @Bean
        @Profile("test")
        open fun componentPermissionResolver(): ComponentPermissionResolver = AlwaysAllowComponentPermissionResolver()

        /** FR-PJ-04 PR-4 Task 9 — 실 ProjectArchiveGuard(공유 dsl 위). */
        @Bean
        open fun projectArchiveGuard(dsl: DSLContext): ProjectArchiveGuard =
            ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))

        @Bean
        open fun componentApplicationService(
            permissionResolver: ComponentPermissionResolver,
            projectLookup: ProjectLookup,
            userLookupPort: UserLookupPort,
            repo: ComponentRepository,
            archiveGuard: ProjectArchiveGuard,
        ): ComponentApplicationService =
            ComponentApplicationService(
                permissionResolver = permissionResolver,
                projectLookup = projectLookup,
                userLookupPort = userLookupPort,
                repo = repo,
                archiveGuard = archiveGuard,
            )

        @Bean
        open fun componentController(service: ComponentApplicationService): ComponentController = ComponentController(service)

        @Bean
        open fun componentExceptionHandler(): ComponentExceptionHandler = ComponentExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper =
        ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        /** UserLookupPort.exists = true 인 리드 UUID */
        val KNOWN_LEAD_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")

        /** UserLookupPort.exists = false 인 미존재 리드 UUID (S11용) */
        val UNKNOWN_LEAD_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000099")

        private const val PROJECT_KEY = "CMTEST"
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
                stmt.execute("DELETE FROM components WHERE project_id = (SELECT id FROM projects WHERE key = '$PROJECT_KEY')")
            }
        }
    }

    // ── S1. POST 201 — 리드 有 ────────────────────────────────────────────────

    /**
     * S1 POST 201 리드 有.
     *
     * Given  CMTEST 프로젝트 존재, 리드 KNOWN_LEAD_ID 존재
     * When   POST /api/v1/projects/CMTEST/components { name, description, leadUserId }
     * Then   201 + data.name / data.leadUserId 확인
     */
    @Test
    fun `S1 POST 201 리드 有 - name, description, leadUserId 응답 확인`() {
        val body =
            mapOf(
                "name" to "Backend",
                "description" to "백엔드 영역",
                "leadUserId" to KNOWN_LEAD_ID.toString(),
            )

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_KEY/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.name").value("Backend"))
            .andExpect(jsonPath("$.data.description").value("백엔드 영역"))
            .andExpect(jsonPath("$.data.leadUserId").value(KNOWN_LEAD_ID.toString()))
            .andExpect(jsonPath("$.data.id").isNotEmpty)
    }

    // ── S2. POST 201 — 리드 無 ────────────────────────────────────────────────

    /**
     * S2 POST 201 리드 無.
     *
     * Given  CMTEST 프로젝트 존재
     * When   POST { name } (leadUserId 미포함)
     * Then   201 + data.leadUserId 없음
     */
    @Test
    fun `S2 POST 201 리드 無 - leadUserId null 응답 확인`() {
        val body = mapOf("name" to "Frontend")

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_KEY/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.name").value("Frontend"))
            .andExpect(jsonPath("$.data.leadUserId").doesNotExist())
    }

    // ── S3. GET 목록 200 — 소프트 삭제 제외, name 정렬 ───────────────────────

    /**
     * S3 GET 목록 200.
     *
     * Given  컴포넌트 2건 삽입 후 1건 소프트 삭제
     * When   GET /api/v1/projects/CMTEST/components
     * Then   200 + 활성 1건만 반환 (name 정렬)
     */
    @Test
    fun `S3 GET 목록 200 - 소프트 삭제 제외 활성 컴포넌트만 반환`() {
        // 컴포넌트 2건 생성
        val id1 = createComponent("Alpha")
        createComponent("Zeta")

        // id1 소프트 삭제
        mockMvc.perform(delete("/api/v1/projects/$PROJECT_KEY/components/$id1"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/components"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("Zeta"))
    }

    // ── S4. GET 단건 200 ─────────────────────────────────────────────────────

    /**
     * S4 GET 단건 200.
     *
     * Given  컴포넌트 1건 삽입
     * When   GET /api/v1/projects/CMTEST/components/{id}
     * Then   200 + data.id, data.name 확인
     */
    @Test
    fun `S4 GET 단건 200 - id, name 응답 확인`() {
        val id = createComponent("Database")

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/components/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(id.toString()))
            .andExpect(jsonPath("$.data.name").value("Database"))
    }

    // ── S5. PATCH name·description 200 — ComponentRepository.update end-to-end ─

    /**
     * S5 PATCH name·description 200.
     *
     * Given  컴포넌트 1건 삽입 (name="Old", description="원본")
     * When   PATCH /{id} { name: "New", description: "변경됨" }
     * Then   200 + data.name = "New", data.description = "변경됨"
     *
     * 이 시나리오가 ComponentRepository.update() end-to-end 커버리지를 제공한다.
     */
    @Test
    fun `S5 PATCH name description 200 - ComponentRepository update end-to-end 검증`() {
        val id = createComponent("Old", description = "원본")

        val body = mapOf("name" to "New", "description" to "변경됨")

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/components/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("New"))
            .andExpect(jsonPath("$.data.description").value("변경됨"))
    }

    // ── S5b. PATCH — null/생략 = 무변경 (sentinel 검증) ─────────────────────

    /**
     * S5b PATCH name 만 변경, description 생략 시 기존 description 유지.
     *
     * Given  컴포넌트 (name="Orig", description="보존")
     * When   PATCH /{id} { name: "Renamed" } (description 생략)
     * Then   200 + data.name = "Renamed", data.description = "보존"
     */
    @Test
    fun `S5b PATCH name 만 변경 - description 생략 시 기존 값 유지`() {
        val id = createComponent("Orig", description = "보존")

        val body = mapOf("name" to "Renamed")

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/components/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("Renamed"))
            .andExpect(jsonPath("$.data.description").value("보존"))
    }

    // ── S6. PATCH /lead 200 — 리드 지정 ─────────────────────────────────────

    /**
     * S6 PATCH /lead 200 리드 지정.
     *
     * Given  컴포넌트 1건 (리드 無)
     * When   PATCH /{id}/lead { leadUserId: KNOWN_LEAD_ID }
     * Then   200 + data.leadUserId = KNOWN_LEAD_ID
     */
    @Test
    fun `S6 PATCH lead 200 - 리드 지정 확인`() {
        val id = createComponent("WithLead")

        val body = mapOf("leadUserId" to KNOWN_LEAD_ID.toString())

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/components/$id/lead")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.leadUserId").value(KNOWN_LEAD_ID.toString()))
    }

    // ── S7. PATCH /lead 200 — 리드 해제 ─────────────────────────────────────

    /**
     * S7 PATCH /lead 200 리드 해제.
     *
     * Given  컴포넌트 1건 (리드 KNOWN_LEAD_ID)
     * When   PATCH /{id}/lead { leadUserId: null }
     * Then   200 + data.leadUserId 없음
     */
    @Test
    fun `S7 PATCH lead 200 - 리드 null 로 해제 확인`() {
        val id = createComponent("Release", leadUserId = KNOWN_LEAD_ID)

        val body = mapOf("leadUserId" to null)

        mockMvc.perform(
            patch("/api/v1/projects/$PROJECT_KEY/components/$id/lead")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.leadUserId").doesNotExist())
    }

    // ── S8. DELETE 204 ────────────────────────────────────────────────────────

    /**
     * S8 DELETE 204.
     *
     * Given  컴포넌트 1건 삽입
     * When   DELETE /{id}
     * Then   204 No Content, 이후 GET 단건 → 404
     */
    @Test
    fun `S8 DELETE 204 - 이후 단건 GET 404 확인`() {
        val id = createComponent("ToDelete")

        mockMvc.perform(delete("/api/v1/projects/$PROJECT_KEY/components/$id"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/components/$id"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("COMPONENT_NOT_FOUND"))
    }

    // ── S9. 404 — 존재하지 않는 프로젝트 ──────────────────────────────────────

    /**
     * S9a 404 PROJECT_NOT_FOUND.
     *
     * Given  존재하지 않는 projectKey "NOPROJECT"
     * When   POST /api/v1/projects/NOPROJECT/components
     * Then   404 + errorCode = PROJECT_NOT_FOUND
     */
    @Test
    fun `S9a 존재하지 않는 프로젝트로 POST - 404 PROJECT_NOT_FOUND`() {
        val body = mapOf("name" to "X")

        mockMvc.perform(
            post("/api/v1/projects/NOPROJECT/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("PROJECT_NOT_FOUND"))
    }

    /**
     * S9b 404 COMPONENT_NOT_FOUND.
     *
     * Given  존재하지 않는 컴포넌트 UUID
     * When   GET /api/v1/projects/CMTEST/components/{randomId}
     * Then   404 + errorCode = COMPONENT_NOT_FOUND
     */
    @Test
    fun `S9b 존재하지 않는 컴포넌트 GET - 404 COMPONENT_NOT_FOUND`() {
        val randomId = UUID.randomUUID()

        mockMvc.perform(get("/api/v1/projects/$PROJECT_KEY/components/$randomId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("COMPONENT_NOT_FOUND"))
    }

    // ── S10. 409 — 중복 이름 ──────────────────────────────────────────────────

    /**
     * S10 409 COMPONENT_NAME_DUPLICATE.
     *
     * Given  "Auth" 컴포넌트 이미 존재
     * When   POST { name: "Auth" } 다시 요청
     * Then   409 + errorCode = COMPONENT_NAME_DUPLICATE
     */
    @Test
    fun `S10 중복 이름 POST - 409 COMPONENT_NAME_DUPLICATE`() {
        createComponent("Auth")

        val body = mapOf("name" to "Auth")

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_KEY/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("COMPONENT_NAME_DUPLICATE"))
    }

    // ── S11. 422 — 미존재 리드 ────────────────────────────────────────────────

    /**
     * S11 422 COMPONENT_LEAD_NOT_FOUND.
     *
     * Given  UNKNOWN_LEAD_ID 는 UserLookupPort.exists = false
     * When   POST { name: "X", leadUserId: UNKNOWN_LEAD_ID }
     * Then   422 + errorCode = COMPONENT_LEAD_NOT_FOUND
     */
    @Test
    fun `S11 미존재 리드 POST - 422 COMPONENT_LEAD_NOT_FOUND`() {
        val body =
            mapOf(
                "name" to "LeadTest",
                "leadUserId" to UNKNOWN_LEAD_ID.toString(),
            )

        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_KEY/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("COMPONENT_LEAD_NOT_FOUND"))
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
            post("/api/v1/projects/$PROJECT_KEY/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    // ── private helpers ─────────────────────────────────────────────────────

    /**
     * 헬퍼: POST 로 컴포넌트를 생성하고 생성된 id(UUID)를 반환한다.
     */
    private fun createComponent(
        name: String,
        description: String? = null,
        leadUserId: UUID? = null,
    ): UUID {
        val body =
            buildMap<String, Any?> {
                put("name", name)
                if (description != null) put("description", description)
                if (leadUserId != null) put("leadUserId", leadUserId.toString())
            }

        val result =
            mockMvc
                .perform(
                    post("/api/v1/projects/$PROJECT_KEY/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)),
                )
                .andExpect(status().isCreated)
                .andReturn()

        val json = mapper.readTree(result.response.contentAsString)
        return UUID.fromString(json["data"]["id"].asText())
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
                stmt.setString(2, "Component Integration Test Project")
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
