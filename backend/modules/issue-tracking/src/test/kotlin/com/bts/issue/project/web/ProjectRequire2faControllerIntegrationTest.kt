// PATCH /api/v1/projects/{key}/require-2fa SYSTEM_ADMIN 전용 통합 테스트 (FR-MF-04 Task 3)

package com.bts.issue.project.web

import com.bts.issue.IssueTrackingApplication
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import com.bts.workflow.scheme.application.port.IssueTypeUsagePort
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * PATCH /api/v1/projects/{projectIdOrKey}/require-2fa 통합 테스트 (FR-MF-04 Task 3).
 *
 * [IssueTrackingApplication] 풀부팅 + Testcontainers PostgreSQL + [Require2faTestPermissionConfig] fake resolver.
 * HTTP → 컨트롤러 → 서비스 → repository → 실 PostgreSQL 전 스택 관통 검증.
 *
 * ## 검증 시나리오
 * - I1. SYSTEM_ADMIN (fake resolver true) → 200 + DB require_2fa=true 영속 확인
 * - I2. 비관리자 (fake resolver false) → 403 ISSUE_REQUIRE_2FA_FORBIDDEN
 * - I3. 미인증 → 401 (SecurityConfig가 api 하위 경로 authenticated 보장)
 * - I4. 미존재 프로젝트 키 → 404
 *
 * ## SecurityContext 설정 방식
 * IssueTrackingApplication 의 SecurityConfig 가 api 하위를 authenticated 로 보호하므로,
 * MOCK 환경에서도 SecurityContextHolder 에 UsernamePasswordAuthenticationToken 을 주입하여
 * 인증/미인증을 시뮬레이션한다.
 * (memory: identity-access-prod-randomport-boot-recipe)
 *
 * ## SystemPermissionResolver 빈 제공
 * [Require2faTestPermissionConfig] 가 fake resolver 를 제공한다.
 * NonProdAllowSystemAdminResolver(@Profile("!prod")) 와 다르게,
 * 이 테스트에서는 ADMIN_ACTOR_ID 만 true 반환하는 ground-truth resolver 를 사용한다.
 * (memory: crossbc-resolver-nullable-fail-open — fail-open 방지.)
 */
@SpringBootTest(
    classes = [
        IssueTrackingApplication::class,
        Require2faTestPermissionConfig::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectRequire2faControllerIntegrationTest {
    @MockBean
    lateinit var workflowTransitionPort: WorkflowTransitionPort

    @MockBean
    lateinit var workflowKeyResolver: WorkflowKeyResolver

    @MockBean
    lateinit var userLookupPort: UserLookupPort

    @MockBean
    lateinit var issueTypeUsagePort: IssueTypeUsagePort

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** Require2faTestPermissionConfig.ADMIN_ACTOR_ID 와 동일하게 유지 */
    private val adminActorId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    /** SYSTEM_ADMIN 이 아닌 일반 사용자 */
    private val regularActorId: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    private var testProjectKey: String = "R2FATEST"

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_r2fa_int_test")
                .withUsername("bts")
                .withPassword("bts_r2fa_test")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // 테스트용 프로젝트 삽입 (require_2fa = false 기본값)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, 'Require2FA Test Project') ON CONFLICT (key) DO NOTHING",
            ).use {
                it.setString(1, testProjectKey)
                it.executeUpdate()
            }
        }
        setAdminAuth()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        // require_2fa 를 false 로 초기화 (다음 테스트와 격리)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE projects SET require_2fa = false WHERE key = ?").use {
                it.setString(1, testProjectKey)
                it.executeUpdate()
            }
        }
    }

    // ── I1. SYSTEM_ADMIN → 200 + DB 영속 확인 ────────────────────────────────────

    @Test
    fun `I1 SYSTEM_ADMIN 이 require_2fa true 로 토글 시 200 반환되고 DB 에 영속된다`() {
        val body = mapOf("requireTwoFactor" to true)

        mockMvc.perform(
            patch("/api/v1/projects/$testProjectKey/require-2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.projectKey").value(testProjectKey))
            .andExpect(jsonPath("$.data.requireTwoFactor").value(true))

        // DB 영속 확인
        val actual = fetchRequire2fa(testProjectKey)
        assertThat(actual).isTrue()
    }

    @Test
    fun `I1b SYSTEM_ADMIN 이 require_2fa false 로 토글 시 200 반환되고 DB 에 false 영속된다`() {
        // 먼저 true 로 설정
        setRequire2faInDb(testProjectKey, value = true)

        val body = mapOf("requireTwoFactor" to false)

        mockMvc.perform(
            patch("/api/v1/projects/$testProjectKey/require-2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requireTwoFactor").value(false))

        val actual = fetchRequire2fa(testProjectKey)
        assertThat(actual).isFalse()
    }

    // ── I2. 비관리자 → 403 ───────────────────────────────────────────────────────

    @Test
    fun `I2 비관리자가 require_2fa 토글 시 403 ISSUE_REQUIRE_2FA_FORBIDDEN 반환`() {
        setRegularAuth()

        val body = mapOf("requireTwoFactor" to true)

        mockMvc.perform(
            patch("/api/v1/projects/$testProjectKey/require-2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_REQUIRE_2FA_FORBIDDEN"))
    }

    // ── I3. 미인증 → 401 ─────────────────────────────────────────────────────────

    @Test
    fun `I3 미인증 요청 시 401 반환`() {
        SecurityContextHolder.clearContext()

        val body = mapOf("requireTwoFactor" to true)

        mockMvc.perform(
            patch("/api/v1/projects/$testProjectKey/require-2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── I4. 미존재 프로젝트 → 404 ────────────────────────────────────────────────

    @Test
    fun `I4 미존재 프로젝트 키로 요청 시 404 반환`() {
        val body = mapOf("requireTwoFactor" to true)

        mockMvc.perform(
            patch("/api/v1/projects/NONEXISTENT_XYZ/require-2fa")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun setAdminAuth() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                adminActorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    private fun setRegularAuth() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                regularActorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    private fun fetchRequire2fa(projectKey: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT require_2fa FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, projectKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "프로젝트 $projectKey 를 찾을 수 없음" }
                    rs.getBoolean("require_2fa")
                }
            }
        }

    private fun setRequire2faInDb(
        projectKey: String,
        value: Boolean,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE projects SET require_2fa = ? WHERE key = ?").use {
                it.setBoolean(1, value)
                it.setString(2, projectKey)
                it.executeUpdate()
            }
        }
    }
}
