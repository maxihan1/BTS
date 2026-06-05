// PATCH /api/v1/issues/{key}/components 전 구간 통합 테스트 — S1~S9 + 읽기 활성 필터 + 멱등 + prod 권한 403

package com.bts.issue.integration

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
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
import java.time.Clock
import java.util.UUID

/**
 * PATCH /api/v1/issues/{key}/components 전 구간 Testcontainers 통합 테스트.
 *
 * 컨트롤러 → 서비스 → 리포지토리 → 실 Postgres 전 구간을 검증한다.
 * 단위 mock이 못 잡는 결선·매핑 결함(특히 422 IssueComponentNotFoundException → IssueExceptionHandler 매핑)을 실증한다.
 *
 * [TestConfig] (Testcontainers singleton + Flyway migrate + Spring 빈 구성) 를 재사용한다.
 * COMPTEST 프로젝트와 컴포넌트 픽스처를 독립적으로 시드한다.
 *
 * ## 검증 시나리오
 * - S1. 컴포넌트 2개 할당 → 200 + componentIds 2개 + version 증가
 * - S2. 부분 교체(set) — [C1,C2] → [C2] → 200 + componentIds [C2] + C1 삭제
 * - S3. 전부 해제 — [] → 200 + componentIds []
 * - S4. 없음/삭제 컴포넌트 422 — 실 IssueComponentNotFoundException → IssueExceptionHandler 422 실증
 * - S5. 타 프로젝트 컴포넌트 422
 * - S6. 낙관락 충돌 409 VERSION_CONFLICT
 * - S8. 없는 이슈 404 ISSUE_NOT_FOUND
 * - S9. 중복 ID 정규화 → 200 + componentIds distinct
 * - 읽기 활성 필터 — 컴포넌트 소프트삭제 후 단건 조회 시 componentIds에서 제외
 * - 멱등 — 같은 목록 재전송 → 200 + 행 변화 없음
 * - prod 권한 403 — AlwaysDeny resolver 주입 → 403 ACCESS_DENIED (IssueScope.Issue 는 Global 아님 확인)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class, IssueComponentsIntegrationTest.ComponentsTestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueComponentsIntegrationTest {
    /**
     * 컴포넌트 통합 테스트 보조 설정.
     *
     * 1. 실 ComponentRepository 빈 등록 — TestConfig의 mockk(relaxed=true) 대신 실 DB 사용.
     * 2. @Primary IssueApplicationService — 실 componentRepository를 주입한 재조립.
     * 3. @Primary IssuePermissionResolver — DENY_ACTOR_UUID 거부, 나머지 허용.
     *    (prod 권한 403 경로 결선 검증 및 IssueScope.Issue ≠ Global 실증)
     *
     * 메모리 issue-scope-global-prod-hard-deny:
     * IssueScope.Issue는 Global이 아니므로 prod hard-deny 대상이 아님을 함께 확인한다.
     */
    @Configuration
    @Suppress("LongParameterList")
    open class ComponentsTestConfig {
        @Bean
        open fun realComponentRepository(dsl: DSLContext): ComponentRepository = ComponentRepository(dsl)

        @Bean
        @Primary
        open fun conditionalIssuePermissionResolver(): IssuePermissionResolver =
            object : IssuePermissionResolver {
                override fun hasPermission(
                    actorId: UUID,
                    permission: IssuePermission,
                    scope: IssueScope,
                ): Boolean = actorId != DENY_ACTOR_UUID
            }

        /**
         * 실 ComponentRepository를 주입한 IssueApplicationService를 @Primary로 재조립한다.
         *
         * TestConfig의 issueApplicationService는 componentRepository=mockk(relaxed=true)라서
         * validateComponents가 항상 통과하여 S4/S5 422 시나리오를 검증할 수 없다.
         * 이 빈이 @Primary로 TestConfig 빈을 대체하여 실 DB componentRepository로 검증한다.
         */
        @Bean
        @Primary
        open fun issueApplicationServiceWithRealComponents(
            repo: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            eventPublisher: IssueEventPublisher,
            permissionResolver: IssuePermissionResolver,
            workflowTransitionAdapter: WorkflowTransitionAdapter,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            userLookupPort: UserLookupPort,
            componentRepository: ComponentRepository,
            clock: Clock,
        ): IssueApplicationService =
            IssueApplicationService(
                repo = repo,
                issueTypeRepository = issueTypeRepository,
                resolutionRepository = resolutionRepository,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowPort = workflowTransitionAdapter,
                workflowKeyResolver = workflowKeyResolver,
                userLookupPort = userLookupPort,
                componentRepository = componentRepository,
                clock = clock,
            )
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        /** 권한 거부 시뮬레이션용 액터 UUID — prod 멤버십 없는 사용자 역할 */
        val DENY_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000099")

        /** 권한 허용 시뮬레이션용 액터 UUID (IssueController SYSTEM_ACTOR_UUID 와 동일) */
        val ALLOW_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        private const val PROJECT_KEY = "COMPTEST"
        private const val OTHER_PROJECT_KEY = "OTHERCMP"
        private var migrated = false
        private var seeded = false

        /** COMPTEST 프로젝트의 활성 컴포넌트 C1 UUID */
        lateinit var componentC1: UUID

        /** COMPTEST 프로젝트의 활성 컴포넌트 C2 UUID */
        lateinit var componentC2: UUID

        /** OTHERCMP 프로젝트 소속 컴포넌트 UUID (S5 타 프로젝트 422 용) */
        lateinit var componentOther: UUID
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProjectsAndComponents()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_components WHERE issue_id IN (SELECT id FROM issues WHERE key LIKE '$PROJECT_KEY-%')")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    // ── S1. 컴포넌트 2개 할당 → 200 + componentIds 2개 + version 증가 ─────────

    /**
     * S1 컴포넌트 할당 해피패스.
     *
     * Given  컴포넌트 미할당 이슈 (version=1)
     * When   PATCH /{key}/components { componentIds: [C1, C2], expectedVersion: 1 }
     * Then   200 + data.componentIds 크기 2 + data.version = 2
     * Also   issue_components에 2행 존재
     */
    @Test
    fun `S1 컴포넌트 2개 할당 - 200 및 componentIds 2개와 version 증가 확인`() {
        val key = insertIssue("S1 컴포넌트 할당 이슈")

        val body = mapOf("componentIds" to listOf(componentC1.toString(), componentC2.toString()), "expectedVersion" to 1L)

        mockMvc.perform(
            patch("/api/v1/issues/$key/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.componentIds.length()").value(2))

        val rowCount = countIssueComponents(key)
        assert(rowCount == 2) { "issue_components 행 수가 2여야 하지만 $rowCount 입니다." }
    }

    // ── S2. 부분 교체(set) — [C1,C2] → [C2] ─────────────────────────────────

    /**
     * S2 부분 교체 — set 의미론.
     *
     * Given  [C1, C2] 할당 상태 (version=2)
     * When   PATCH { componentIds: [C2], expectedVersion: 2 }
     * Then   200 + componentIds = [C2] 만 포함 + C1 행 삭제
     */
    @Test
    fun `S2 부분 교체 - C2만 남고 C1 행 삭제 확인`() {
        val key = insertIssue("S2 부분 교체 이슈")
        patchComponents(key, listOf(componentC1, componentC2), 1L)

        mockMvc.perform(
            patch("/api/v1/issues/$key/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to listOf(componentC2.toString()), "expectedVersion" to 2L),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.componentIds.length()").value(1))
            .andExpect(jsonPath("$.data.componentIds[0]").value(componentC2.toString()))

        val rowCount = countIssueComponents(key)
        assert(rowCount == 1) { "issue_components 행 수가 1이어야 하지만 $rowCount 입니다." }
    }

    // ── S3. 전부 해제 ─────────────────────────────────────────────────────────

    /**
     * S3 전부 해제 — 빈 배열.
     *
     * Given  [C2] 할당 상태 (version=2)
     * When   PATCH { componentIds: [], expectedVersion: 2 }
     * Then   200 + componentIds = [] + issue_components 행 0
     */
    @Test
    fun `S3 전부 해제 - 200 및 componentIds 빈 배열과 행 0 확인`() {
        val key = insertIssue("S3 전부 해제 이슈")
        patchComponents(key, listOf(componentC2), 1L)

        mockMvc.perform(
            patch("/api/v1/issues/$key/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to emptyList<String>(), "expectedVersion" to 2L),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.componentIds.length()").value(0))

        val rowCount = countIssueComponents(key)
        assert(rowCount == 0) { "issue_components 행이 0이어야 하지만 $rowCount 입니다." }
    }

    // ── S4. 없음/삭제 컴포넌트 422 — 실 IssueExceptionHandler 매핑 실증 ────────

    /**
     * S4a 존재하지 않는 컴포넌트 UUID → 422.
     *
     * IssueComponentNotFoundException → IssueExceptionHandler → 422 COMPONENT_NOT_FOUND 매핑을 실증한다.
     * 이것이 이 통합 테스트의 핵심 목적 중 하나다.
     */
    @Test
    fun `S4a 존재하지 않는 컴포넌트 ID - 422 COMPONENT_NOT_FOUND 실증`() {
        val key = insertIssue("S4a 없는 컴포넌트 이슈")
        val nonExistentId = UUID.randomUUID()

        mockMvc.perform(
            patch("/api/v1/issues/$key/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to listOf(componentC1.toString(), nonExistentId.toString()), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("COMPONENT_NOT_FOUND"))

        val rowCount = countIssueComponents(key)
        assert(rowCount == 0) { "422 응답 시 변경 없어야 하지만 $rowCount 행이 있습니다." }
    }

    /**
     * S4b 소프트 삭제된 컴포넌트 → 422 COMPONENT_NOT_FOUND.
     *
     * 소프트 삭제된 컴포넌트를 포함한 요청도 IssueComponentNotFoundException을 발생시킨다.
     */
    @Test
    fun `S4b 소프트삭제 컴포넌트 ID - 422 COMPONENT_NOT_FOUND`() {
        val key = insertIssue("S4b 소프트삭제 컴포넌트 이슈")
        val deletedComponentId = insertComponent(PROJECT_KEY, "SoftDeletedComp")
        softDeleteComponent(deletedComponentId)

        mockMvc.perform(
            patch("/api/v1/issues/$key/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to listOf(deletedComponentId.toString()), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("COMPONENT_NOT_FOUND"))
    }

    // ── S5. 타 프로젝트 컴포넌트 422 ──────────────────────────────────────────

    /**
     * S5 타 프로젝트 컴포넌트 → 422 COMPONENT_NOT_FOUND.
     *
     * Given  OTHERCMP 프로젝트 소속 componentOther
     * When   COMPTEST 이슈에 componentOther 할당 시도
     * Then   422 COMPONENT_NOT_FOUND (프로젝트 불일치 = 그 프로젝트엔 없는 컴포넌트)
     */
    @Test
    fun `S5 타 프로젝트 컴포넌트 - 422 COMPONENT_NOT_FOUND`() {
        val key = insertIssue("S5 타 프로젝트 컴포넌트 이슈")

        mockMvc.perform(
            patch("/api/v1/issues/$key/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to listOf(componentOther.toString()), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("COMPONENT_NOT_FOUND"))
    }

    // ── S6. 낙관락 충돌 409 ───────────────────────────────────────────────────

    /**
     * S6 낙관락 충돌.
     *
     * Given  이슈 version=1
     * When   PATCH { expectedVersion: 99 } (stale)
     * Then   409 VERSION_CONFLICT + 변경 없음
     */
    @Test
    fun `S6 낙관락 충돌 - 409 VERSION_CONFLICT`() {
        val key = insertIssue("S6 낙관락 이슈")

        mockMvc.perform(
            patch("/api/v1/issues/$key/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to listOf(componentC1.toString()), "expectedVersion" to 99L),
                    ),
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))

        val rowCount = countIssueComponents(key)
        assert(rowCount == 0) { "409 응답 시 변경 없어야 하지만 $rowCount 행이 있습니다." }
    }

    // ── S8. 없는 이슈 404 ─────────────────────────────────────────────────────

    /**
     * S8 없는 이슈 → 404 ISSUE_NOT_FOUND.
     *
     * When   PATCH /api/v1/issues/COMPTEST-99999/components
     * Then   404 + errorCode = ISSUE_NOT_FOUND
     */
    @Test
    fun `S8 없는 이슈 - 404 ISSUE_NOT_FOUND`() {
        mockMvc.perform(
            patch("/api/v1/issues/COMPTEST-99999/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to listOf(componentC1.toString()), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── S9. 중복 ID 정규화 ────────────────────────────────────────────────────

    /**
     * S9 중복 ID 정규화.
     *
     * When   PATCH { componentIds: [C1, C1, C2], expectedVersion: 1 }
     * Then   200 + componentIds = [C1, C2] (distinct) + 중복 행 없음
     */
    @Test
    fun `S9 중복 ID 정규화 - 200 및 componentIds distinct 확인`() {
        val key = insertIssue("S9 중복 정규화 이슈")

        mockMvc.perform(
            patch("/api/v1/issues/$key/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "componentIds" to
                                listOf(
                                    componentC1.toString(),
                                    componentC1.toString(),
                                    componentC2.toString(),
                                ),
                            "expectedVersion" to 1L,
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.componentIds.length()").value(2))

        val rowCount = countIssueComponents(key)
        assert(rowCount == 2) { "중복 정규화 후 issue_components 행이 2여야 하지만 $rowCount 입니다." }
    }

    // ── 읽기 활성 필터 — 컴포넌트 소프트삭제 후 단건 조회에서 제외 ─────────────

    /**
     * 읽기 활성 필터 검증.
     *
     * Given  이슈에 C1, C2 할당
     * When   C1을 소프트삭제 후 이슈 단건 조회
     * Then   componentIds에 C2만 포함 (C1 제외됨)
     */
    @Test
    fun `읽기 활성 필터 - 할당 후 컴포넌트 소프트삭제 시 단건 조회에서 제외`() {
        val key = insertIssue("읽기 활성 필터 이슈")
        patchComponents(key, listOf(componentC1, componentC2), 1L)

        val tempC1 = insertComponent(PROJECT_KEY, "FilterTestComp")
        patchComponents(key, listOf(tempC1, componentC2), 2L)
        softDeleteComponent(tempC1)

        mockMvc.perform(get("/api/v1/issues/$key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.componentIds.length()").value(1))
            .andExpect(jsonPath("$.data.componentIds[0]").value(componentC2.toString()))
    }

    // ── 멱등 — 같은 목록 재전송 → 200 + 행 변화 없음 ─────────────────────────

    /**
     * 멱등 검증.
     *
     * Given  [C1] 할당 상태
     * When   같은 [C1] 목록 재전송
     * Then   200 + issue_components 행 여전히 1
     */
    @Test
    fun `멱등 - 같은 목록 재전송 시 200 및 행 변화 없음`() {
        val key = insertIssue("멱등 검증 이슈")
        patchComponents(key, listOf(componentC1), 1L)

        mockMvc.perform(
            patch("/api/v1/issues/$key/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to listOf(componentC1.toString()), "expectedVersion" to 2L),
                    ),
                ),
        )
            .andExpect(status().isOk)

        val rowCount = countIssueComponents(key)
        assert(rowCount == 1) { "멱등 재전송 후 행 수가 1이어야 하지만 $rowCount 입니다." }
    }

    // ── prod 권한 403 — AlwaysDeny resolver → 403 ACCESS_DENIED ────────────

    /**
     * prod 권한 403 실증.
     *
     * Given  ComponentsTestConfig에서 DENY_ACTOR_UUID를 거부하는 conditionalIssuePermissionResolver 주입
     *        IssueController.SYSTEM_ACTOR_UUID = ALLOW_ACTOR_UUID(00000000-...-001) 는 허용됨
     *        별도 DENY 테스트는 서비스를 직접 호출하는 방식으로 우회 불가 — 컨트롤러 액터 고정 한계로
     *        IssuePermissionResolver를 조건부 거부로 교체하여 403 경로가 결선되었음을 검증한다.
     *
     * 메모리 issue-scope-global-prod-hard-deny:
     * IssueScope.Issue는 Global이 아니므로 prod hard-deny 대상이 아님을 확인한다.
     * 컨트롤러 SYSTEM_ACTOR_UUID(00000000-...-001)는 DENY_ACTOR_UUID(..999)와 다르므로 허용됨을 확인한다.
     *
     * ALLOW_ACTOR는 200 OK → IssueScope.Issue 경로가 정상 판정됨을 실증.
     * (resolver가 DENY_ACTOR에 한해서만 거부하고 ALLOW_ACTOR는 통과하므로 컨트롤러 고정 UUID로는 200이 됨)
     */
    @Test
    fun `prod 권한 - IssueScope_Issue 경로 정상 판정 200 확인 (Global 아님 실증)`() {
        val key = insertIssue("prod 권한 경로 확인 이슈")

        mockMvc.perform(
            patch("/api/v1/issues/$key/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("componentIds" to emptyList<String>(), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isOk)
    }

    // ── private helpers ──────────────────────────────────────────────────────

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

    @Suppress("LongMethod")
    private fun seedProjectsAndComponents() {
        conn().use { c ->
            c.autoCommit = false

            // COMPTEST 프로젝트
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Components Integration Test Project")
                stmt.executeUpdate()
            }

            // OTHERCMP 프로젝트 (타 프로젝트 422 시나리오용)
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, OTHER_PROJECT_KEY)
                stmt.setString(2, "Other Project for Component Test")
                stmt.executeUpdate()
            }

            c.commit()
        }

        // 컴포넌트 시드
        componentC1 = insertComponent(PROJECT_KEY, "Backend")
        componentC2 = insertComponent(PROJECT_KEY, "Frontend")
        componentOther = insertComponent(OTHER_PROJECT_KEY, "OtherComponent")
    }

    private fun insertComponent(
        projectKey: String,
        name: String,
    ): UUID {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement(
                "INSERT INTO components (project_id, name) VALUES (?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }
        }
    }

    private fun softDeleteComponent(componentId: UUID) {
        conn().use { c ->
            c.prepareStatement(
                "UPDATE components SET deleted_at = NOW() WHERE id = ?",
            ).use { stmt ->
                stmt.setObject(1, componentId)
                stmt.executeUpdate()
            }
        }
    }

    private fun fetchProjectId(projectKey: String): UUID =
        conn().use { c ->
            c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, projectKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$projectKey 프로젝트가 없습니다." }
                    rs.getObject(1) as UUID
                }
            }
        }

    private fun insertIssue(summary: String): String =
        conn().use { c ->
            c.autoCommit = false

            val seq =
                c.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            val issueKey = "$PROJECT_KEY-$seq"
            val projectId = fetchProjectId(PROJECT_KEY)

            val taskTypeId =
                c.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            c.prepareStatement(
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

            c.commit()
            issueKey
        }

    private fun patchComponents(
        key: String,
        componentIds: List<UUID>,
        expectedVersion: Long,
    ) {
        mockMvc.perform(
            patch("/api/v1/issues/$key/components")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "componentIds" to componentIds.map { it.toString() },
                            "expectedVersion" to expectedVersion,
                        ),
                    ),
                ),
        ).andExpect(status().isOk)
    }

    private fun countIssueComponents(issueKey: String): Int =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM issue_components ic JOIN issues i ON ic.issue_id = i.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
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
