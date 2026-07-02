// 스프린트 벨로시티 REST API 실 DB end-to-end 통합 테스트 — FR-RP-02 Task 5

package com.bts.agileplanning.integration

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.velocity.SprintVelocityLookupPort
import com.bts.shared.velocity.VelocityContribution
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.LocalDate
import java.util.UUID

/**
 * 스프린트 벨로시티(Velocity) REST API 실 DB end-to-end 통합 테스트 (FR-RP-02 Task 5).
 *
 * [AgilePlanningTestBootApplication] 을 기동해 실 Testcontainers PostgreSQL 위에서
 * `SprintVelocityController` → `SprintVelocityService` → `SprintRepository` 전 스택을 검증한다.
 * [SprintBurndownIntegrationTest] 와 동일한 base/config([AgilePlanningTestBootApplication],
 * [AgilePlanningTestcontainersConfig])를 재사용하고, actor 주입·cross-BC stub 패턴도 그대로 따른다.
 *
 * ## webEnvironment 선택 — MOCK
 * [SprintBurndownIntegrationTest] 와 동일한 사유로 MOCK 환경을 사용한다(이 모듈은 임베디드
 * 서블릿 컨테이너 의존성이 없다).
 *
 * ## cross-BC stub
 * - [IssuePermissionResolver]: [PermissionStub] — 테스트별 allow/deny toggle(403 검증).
 * - [SprintVelocityLookupPort]: [VelocityPortStub] — 통제된 [VelocityContribution] map 반환.
 *   입력(issueKeysBySprint)과 무관하게 sprintId 로만 결정되는 고정 응답을 돌려준다(BC 격리 —
 *   실 issue-tracking 어댑터는 이 모듈 테스트 클래스패스에 없다).
 *
 * ## 검증 범위
 * - S1 200 해피패스 — 스프린트별 commitment/completed·시간순 오름차순·평균.
 * - S2 COMPLETED 스프린트 0개 — 빈 리스트·평균 0.
 * - S4 BROWSE 권한 없음 — 403.
 * - S5 미인증 — 401.
 * - E6 limit 파라미터가 조회 개수에 반영.
 * - E7 스프린트 기간(start/end) 미설정이어도 200 + null 날짜.
 */
@SpringBootTest(
    classes = [AgilePlanningTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(AgilePlanningTestcontainersConfig::class, SprintVelocityIntegrationTest.StubConfig::class)
@ActiveProfiles("test")
class SprintVelocityIntegrationTest {
    /**
     * 통합테스트 전용 cross-BC stub 설정.
     *
     * [AgilePlanningTestcontainersConfig] 의 기본 stub 을 [Primary] 로 교체한다
     * ([SprintBurndownIntegrationTest] 의 `StubConfig` 와 동일 패턴).
     */
    @TestConfiguration(proxyBeanMethods = false)
    class StubConfig {
        @Bean
        @Primary
        fun permissionStub(): PermissionStub = PermissionStub()

        @Bean
        @Primary
        fun velocityPortStub(): VelocityPortStub = VelocityPortStub()
    }

    /** 테스트별 allow/deny toggle 이 가능한 [IssuePermissionResolver] stub. */
    class PermissionStub : IssuePermissionResolver {
        var allowAll: Boolean = true

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean = allowAll
    }

    /**
     * 테스트별로 통제된 [VelocityContribution] map 을 반환하는 [SprintVelocityLookupPort] stub.
     *
     * 실 issue-tracking jOOQ 어댑터는 이 모듈의 테스트 클래스패스에 없다(BC 격리) — 어댑터 자체
     * 검증은 별도 통합테스트가 담당한다. 입력 인자와 무관하게 sprintId 로만 결정되는 고정 응답을
     * 돌려주므로 이슈 실 시딩 없이도 결정적인 값 검증이 가능하다.
     */
    class VelocityPortStub : SprintVelocityLookupPort {
        var contributions: Map<UUID, VelocityContribution> = emptyMap()

        override fun fetchVelocitySource(
            issueKeysBySprint: Map<UUID, Set<String>>,
            projectKey: String,
            viewerUserId: UUID,
        ): Map<UUID, VelocityContribution> = contributions
    }

    @Autowired
    lateinit var wac: WebApplicationContext

    @Autowired
    lateinit var permissionStub: PermissionStub

    @Autowired
    lateinit var velocityPortStub: VelocityPortStub

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    /** 테스트마다 격리된 projectKey 를 생성한다. */
    private fun uniqueProjectKey(): String = "VEL-${UUID.randomUUID().toString().take(6).uppercase()}"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()

        // 인증 주체 주입 — SprintBurndownIntegrationTest 와 동일 패턴
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        // stub 초기화
        permissionStub.allowAll = true
        velocityPortStub.contributions = emptyMap()
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /**
     * 스프린트를 생성하고 sprintId 를 추출해 반환한다.
     *
     * @return 생성된 스프린트 UUID 문자열.
     */
    private fun createSprintAndGetId(
        projectKey: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): String {
        val body =
            mapOf(
                "projectKey" to projectKey,
                "name" to "벨로시티 테스트 스프린트",
                "goal" to null,
                "startDate" to startDate?.toString(),
                "endDate" to endDate?.toString(),
            )
        val result =
            mockMvc.perform(
                post("/api/v1/sprints")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)),
            )
                .andExpect(status().isCreated)
                .andReturn()

        return mapper.readTree(result.response.contentAsString)
            .get("data").get("sprintId").asText()
    }

    /** [sprintId] 를 PLANNED -> ACTIVE -> COMPLETED 로 전이한다. */
    private fun completeSprint(sprintId: String) {
        mockMvc.perform(post("/api/v1/sprints/$sprintId/start")).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/sprints/$sprintId/complete")).andExpect(status().isOk)
    }

    // ── S1. 200 해피패스 — 수치 정확성 ─────────────────────────────────────────

    @Test
    fun `S1 완료 스프린트별 벨로시티를 시간순으로 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprint1 = createSprintAndGetId(projectKey, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14))
        completeSprint(sprint1)
        val sprint2 = createSprintAndGetId(projectKey, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 28))
        completeSprint(sprint2)

        velocityPortStub.contributions =
            mapOf(
                UUID.fromString(sprint1) to VelocityContribution(commitmentSeconds = 36_000, completedSeconds = 28_800),
                UUID.fromString(sprint2) to VelocityContribution(commitmentSeconds = 72_000, completedSeconds = 54_000),
            )

        mockMvc.perform(get("/api/v1/projects/$projectKey/velocity"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.projectKey").value(projectKey))
            .andExpect(jsonPath("$.data.sprints.length()").value(2))
            .andExpect(jsonPath("$.data.sprints[0].sprintId").value(sprint1))
            .andExpect(jsonPath("$.data.sprints[0].commitmentSeconds").value(36_000))
            .andExpect(jsonPath("$.data.sprints[0].completedSeconds").value(28_800))
            .andExpect(jsonPath("$.data.sprints[1].sprintId").value(sprint2))
            .andExpect(jsonPath("$.data.sprints[1].commitmentSeconds").value(72_000))
            .andExpect(jsonPath("$.data.sprints[1].completedSeconds").value(54_000))
            .andExpect(jsonPath("$.data.averageCommitmentSeconds").value(54_000))
            .andExpect(jsonPath("$.data.averageCompletedSeconds").value(41_400))
    }

    // ── S2. COMPLETED 스프린트 0개 ──────────────────────────────────────────────

    @Test
    fun `S2 완료 스프린트가 없으면 빈 리스트와 평균 0을 반환한다`() {
        val projectKey = uniqueProjectKey()
        createSprintAndGetId(projectKey, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 14)) // PLANNED 상태로 유지

        mockMvc.perform(get("/api/v1/projects/$projectKey/velocity"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sprints.length()").value(0))
            .andExpect(jsonPath("$.data.averageCommitmentSeconds").value(0))
            .andExpect(jsonPath("$.data.averageCompletedSeconds").value(0))
    }

    // ── S4. BROWSE 권한 없음 ───────────────────────────────────────────────────

    @Test
    fun `S4 벨로시티 조회는 BROWSE 권한이 없으면 403을 반환한다`() {
        val projectKey = uniqueProjectKey()
        permissionStub.allowAll = false

        mockMvc.perform(get("/api/v1/projects/$projectKey/velocity"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))
    }

    // ── S5. 미인증 ──────────────────────────────────────────────────────────────

    @Test
    fun `S5 벨로시티 조회는 미인증이면 401을 반환한다`() {
        val projectKey = uniqueProjectKey()
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/projects/$projectKey/velocity"))
            .andExpect(status().isUnauthorized)
    }

    // ── E6. limit 파라미터 반영 ──────────────────────────────────────────────────

    @Test
    fun `E6 limit 파라미터가 조회 개수에 반영된다`() {
        val projectKey = uniqueProjectKey()
        val sprintIds =
            (1..3).map { month ->
                val id = createSprintAndGetId(projectKey, LocalDate.of(2026, month, 1), LocalDate.of(2026, month, 14))
                completeSprint(id)
                id
            }
        velocityPortStub.contributions =
            sprintIds.associate { UUID.fromString(it) to VelocityContribution(commitmentSeconds = 1_000, completedSeconds = 900) }

        mockMvc.perform(get("/api/v1/projects/$projectKey/velocity").param("limit", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sprints.length()").value(2))
            .andExpect(jsonPath("$.data.sprints[0].sprintId").value(sprintIds[1]))
            .andExpect(jsonPath("$.data.sprints[1].sprintId").value(sprintIds[2]))
    }

    // ── E7. 기간(start/end) 미설정 ──────────────────────────────────────────────

    @Test
    fun `E7 스프린트 기간이 미설정이어도 200과 null 날짜를 포함해 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey, startDate = null, endDate = null)
        completeSprint(sprintId)

        val result =
            mockMvc.perform(get("/api/v1/projects/$projectKey/velocity"))
                .andExpect(status().isOk)
                .andReturn()

        val sprintNode = mapper.readTree(result.response.contentAsString).get("data").get("sprints").get(0)
        assertThat(sprintNode.get("sprintId").asText()).isEqualTo(sprintId)
        assertThat(sprintNode.get("startDate").isNull).isTrue()
        assertThat(sprintNode.get("endDate").isNull).isTrue()
    }
}
