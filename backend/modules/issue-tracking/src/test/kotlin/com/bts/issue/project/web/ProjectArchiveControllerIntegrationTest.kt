// POST /api/v1/projects/{projectIdOrKey}/archive · /unarchive 통합 테스트 — S6/S8/EC-2 + 권한 비-vacuous (FR-PJ-04 PR-4 Task 5)

package com.bts.issue.project.web

import com.bts.issue.CrossBcPortTestConfig
import com.bts.issue.IssueTrackingApplication
import com.bts.issue.project.archive.ArchiveTestPermissionConfig
import com.bts.issue.project.archive.ControllableComponentPermissionResolver
import com.bts.shared.permission.ComponentPermissionResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID

/**
 * POST /api/v1/projects/{projectIdOrKey}/archive · /unarchive 통합 테스트 (FR-PJ-04 PR-4 Task 5).
 *
 * [IssueTrackingApplication] 풀부팅 + Testcontainers PostgreSQL + [ArchiveTestPermissionConfig] 제어형
 * fake resolver. HTTP → 컨트롤러 → 서비스 → repository → 실 PostgreSQL 전 스택 관통 검증
 * ([ProjectRequire2faControllerIntegrationTest] 동형 구조).
 *
 * ## 검증 시나리오
 * - S6/PJ4-1. admin 이 활성 프로젝트를 아카이브 → 200 + DB `archived_at` NOT NULL 영속
 * - S8/PJ4-2. admin 이 아카이브된 프로젝트를 해제 → 200 + DB `archived_at` NULL 영속(unarchive 는
 *   아카이브 상태에서도 동작 — D-UNARCHIVE, guard 우회)
 * - EC-2. 재아카이브/재해제 모두 200(멱등, 409 아님)
 * - 권한. 비관리자 → 403(C2 비-vacuous — 같은 actor 를 admin 으로 승격하면 200 으로 뒤집힘을 함께
 *   확인해 "컨트롤러가 없어도 403" 부류의 우연한 통과가 아님을 증명)
 * - 미인증 → 401
 * - 미존재 프로젝트 → 404
 *
 * ## SecurityContext 설정 방식
 * MOCK 환경에서 [SecurityContextHolder] 에 인증 토큰을 직접 주입해 인증/미인증을 시뮬레이션한다
 * (memory: identity-access-prod-randomport-boot-recipe).
 *
 * ## 왜 [PrimaryArchiveComponentPermissionResolverConfig] 가 추가로 필요한가 (실측 발견)
 * 이 테스트는 [IssueTrackingApplication] 을 풀부팅하므로 [ArchiveTestPermissionConfig] 의 fake 빈과
 * non-prod stub([com.bts.issue.component.adapter.AlwaysAllowComponentPermissionResolver],
 * `@Profile("!prod")`)이 컨텍스트에 공존해 [ComponentPermissionResolver] 후보가 2개가 된다.
 * `ComponentApplicationService` 등 파라미터명이 두 빈 이름 중 어느 쪽과도 일치하지 않는 기존
 * 소비처는 `NoUniqueBeanDefinitionException` 으로 컨텍스트 부팅 자체가 실패한다(실측 확인 —
 * `archiveComponentPermissionResolver` 라는 빈 이름은 [ProjectArchiveService] 전용으로 고안된 것이라
 * 다른 소비처의 파라미터명과는 우연히도 일치하지 않는다). `@Primary` 로 fake 를 전 소비처에 대해
 * 명시 우선시켜 해소한다 — [ArchiveTestPermissionConfig.kt] 자체는 수정하지 않는다(Task 5 파일
 * 범위 밖, T4 산출물).
 */
@SpringBootTest(
    classes = [
        IssueTrackingApplication::class,
        ArchiveTestPermissionConfig::class,
        PrimaryArchiveComponentPermissionResolverConfig::class,
        CrossBcPortTestConfig::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectArchiveControllerIntegrationTest {
    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    /**
     * [ArchiveTestPermissionConfig] 가 제공하는 제어형 fake — `grantAdmin`/`revokeAdmin` 으로
     * 비-vacuous 403 판별자를 구성한다(C2).
     */
    @Autowired
    lateinit var permissionResolver: ControllableComponentPermissionResolver

    private lateinit var mockMvc: MockMvc

    private val adminActorId: UUID = ArchiveTestPermissionConfig.ADMIN_ACTOR_ID
    private val regularActorId: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

    private val testProjectKey: String = "ARCHTEST"

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_archive_int_test")
                .withUsername("bts")
                .withPassword("bts_archive_test")
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
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, 'Archive Test Project') " +
                    "ON CONFLICT (key) DO NOTHING",
            ).use {
                it.setString(1, testProjectKey)
                it.executeUpdate()
            }
            // 매 테스트 격리 — archived_at 을 활성 상태로 초기화
            conn.prepareStatement("UPDATE projects SET archived_at = NULL WHERE key = ?").use {
                it.setString(1, testProjectKey)
                it.executeUpdate()
            }
        }
        setAdminAuth()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        // grantAdmin 으로 승격한 경우 다음 테스트로 새지 않도록 원복 (PER_CLASS 공유 컨텍스트)
        permissionResolver.revokeAdmin(regularActorId)
    }

    // ── S6/PJ4-1 — archive → 200 + DB 영속 ───────────────────────────────────

    @Test
    fun `S6 admin 이 활성 프로젝트를 아카이브하면 200 이고 archived_at 이 DB 에 영속된다`() {
        mockMvc.perform(post("/api/v1/projects/$testProjectKey/archive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.projectKey").value(testProjectKey))

        assertThat(fetchArchivedAt(testProjectKey)).isNotNull()
    }

    // ── EC-2 — archive 멱등 200 ──────────────────────────────────────────────

    @Test
    fun `EC-2 이미 아카이브된 프로젝트를 재아카이브해도 409 가 아니라 200 이다`() {
        mockMvc.perform(post("/api/v1/projects/$testProjectKey/archive")).andExpect(status().isOk)

        mockMvc.perform(post("/api/v1/projects/$testProjectKey/archive"))
            .andExpect(status().isOk)

        assertThat(fetchArchivedAt(testProjectKey)).isNotNull()
    }

    // ── S8/PJ4-2 — unarchive → 200 + DB 영속(NULL) ───────────────────────────

    @Test
    fun `S8 admin 이 아카이브된 프로젝트를 해제하면 200 이고 archived_at 이 DB 에서 NULL 로 영속된다`() {
        archiveInDb(testProjectKey)
        assertThat(fetchArchivedAt(testProjectKey)).isNotNull()

        mockMvc.perform(post("/api/v1/projects/$testProjectKey/unarchive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.projectKey").value(testProjectKey))

        assertThat(fetchArchivedAt(testProjectKey)).isNull()
    }

    // ── EC-2 대칭 — unarchive 멱등 200 (이미 활성) ───────────────────────────

    @Test
    fun `EC-2 이미 활성인 프로젝트를 재해제해도 409 가 아니라 200 이다`() {
        mockMvc.perform(post("/api/v1/projects/$testProjectKey/unarchive"))
            .andExpect(status().isOk)

        assertThat(fetchArchivedAt(testProjectKey)).isNull()
    }

    // ── 권한 — 비admin 403 (C2 non-vacuous: 같은 actor 를 admin 으로 승격해 200 으로 뒤집음) ─

    @Test
    fun `권한 비admin 의 archive 시도는 403 이고 같은 actor 를 admin 으로 승격하면 200 으로 뒤집힌다`() {
        setRegularAuth()

        mockMvc.perform(post("/api/v1/projects/$testProjectKey/archive"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_ARCHIVE_FORBIDDEN"))
        // side effect 없음 — 거부된 요청이 실제로 DB 를 바꾸지 않았다
        assertThat(fetchArchivedAt(testProjectKey)).isNull()

        permissionResolver.grantAdmin(regularActorId)

        mockMvc.perform(post("/api/v1/projects/$testProjectKey/archive"))
            .andExpect(status().isOk)
        assertThat(fetchArchivedAt(testProjectKey)).isNotNull()
    }

    @Test
    fun `권한 비admin 의 unarchive 시도는 403 이고 아카이브 상태는 그대로 유지된다`() {
        archiveInDb(testProjectKey)
        setRegularAuth()

        mockMvc.perform(post("/api/v1/projects/$testProjectKey/unarchive"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_PROJECT_ARCHIVE_FORBIDDEN"))

        assertThat(fetchArchivedAt(testProjectKey)).isNotNull()
    }

    // ── 미인증 → 401 ──────────────────────────────────────────────────────────

    @Test
    fun `미인증 요청 시 401 반환`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(post("/api/v1/projects/$testProjectKey/archive"))
            .andExpect(status().isUnauthorized)
    }

    // ── 미존재 프로젝트 → 404 ────────────────────────────────────────────────

    @Test
    fun `미존재 프로젝트 키로 archive 요청 시 404 반환`() {
        mockMvc.perform(post("/api/v1/projects/NONEXISTENT_ARCH/archive"))
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

    private fun archiveInDb(projectKey: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE projects SET archived_at = now() WHERE key = ?").use {
                it.setString(1, projectKey)
                it.executeUpdate()
            }
        }
    }

    private fun fetchArchivedAt(projectKey: String): OffsetDateTime? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT archived_at FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, projectKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "프로젝트 $projectKey 를 찾을 수 없음" }
                    rs.getObject("archived_at", OffsetDateTime::class.java)
                }
            }
        }
}

/**
 * [ArchiveTestPermissionConfig] 의 제어형 fake 를 풀부팅 컨텍스트 전체에서 결정적으로 선택되게
 * 만드는 `@Primary` 위임 빈 설정.
 *
 * [ArchiveTestPermissionConfig.archiveComponentPermissionResolver] 와 non-prod stub
 * `AlwaysAllowComponentPermissionResolver` 가 공존하면 [ComponentPermissionResolver] 후보가 2개가
 * 되어, 파라미터명이 두 빈 이름 중 어느 쪽과도 일치하지 않는 기존 소비처(예:
 * `ComponentApplicationService`)의 빈 생성이 `NoUniqueBeanDefinitionException` 으로 실패한다(실측).
 * `@Primary` 는 이름 매칭보다 우선 적용되므로, 이 하나의 위임 빈만으로 컨텍스트 전체의 모호성이
 * 해소된다. [ArchiveTestPermissionConfig.kt] 자체는 수정하지 않는다(Task 5 파일 범위 밖).
 */
@TestConfiguration
class PrimaryArchiveComponentPermissionResolverConfig {
    /**
     * [ControllableComponentPermissionResolver] 단일 빈(컨텍스트에 유일 — 타입 자체는 모호하지 않음)을
     * 그대로 위임하는 `@Primary` [ComponentPermissionResolver] 빈.
     *
     * @param delegate [ArchiveTestPermissionConfig] 가 제공하는 제어형 fake.
     * @return `@Primary` 로 표시된 동일 인스턴스.
     */
    @Bean
    @Primary
    fun primaryComponentPermissionResolver(
        delegate: ControllableComponentPermissionResolver,
    ): ComponentPermissionResolver = delegate
}
