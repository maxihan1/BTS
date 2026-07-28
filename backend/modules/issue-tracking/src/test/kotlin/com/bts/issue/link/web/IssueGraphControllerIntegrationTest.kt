// FR-LK-02 Task 3 — IssueGraphController end-to-end 통합테스트 (GET /graph 전 스택 검증)
@file:Suppress("MaxLineLength")

package com.bts.issue.link.web

import com.bts.issue.link.application.IssueGraphService
import com.bts.issue.link.application.IssueParentService
import com.bts.issue.link.application.LinkApplicationService
import com.bts.issue.link.repository.IssueGraphRepository
import com.bts.issue.link.repository.IssueLinkRepository
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermission
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * FR-LK-02 Task 3 — IssueGraphController end-to-end 통합테스트.
 *
 * 실제 Testcontainers PostgreSQL + 전체 스택(Controller → Service → Repository) 위에서 동작한다.
 *
 * ## 검증 시나리오
 * - G1. 200 GET /graph (depth 기본값=2) — nodes/edges 정확, center depth=0, edge.type 대문자
 * - G2. 200 GET /graph?depth=1 — 2-hop 이슈 제외, 1-hop 이슈만 포함
 * - G3. 200 GET /graph — parent-child 엣지 포함 (from=부모, to=자식, type=PARENT)
 * - G4. 404 GET /graph — 존재하지 않는 키 → ISSUE_NOT_FOUND
 * - G5. 400 GET /graph?depth=abc — INVALID_DEPTH (500 아님 확인)
 * - G6. 400 GET /graph?depth=0 — 범위 미달 → INVALID_DEPTH
 * - G7. 400 GET /graph?depth=4 — 범위 초과 → INVALID_DEPTH
 * - G8. 200 GET /graph — 링크/부모 없는 이슈 → nodes 1개 + edges 0개
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueGraphControllerIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueGraphControllerIntegrationTest {
    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_gk_test")
                    .withUsername("bts")
                    .withPassword("bts_gk_test")
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

        @Bean
        open fun issueLinkRepository(dsl: DSLContext): IssueLinkRepository = IssueLinkRepository(dsl)

        @Bean
        open fun issueGraphRepository(dsl: DSLContext): IssueGraphRepository = IssueGraphRepository(dsl)

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

        /** FR-PJ-04 PR-4 Task 9 — 실 ProjectArchiveGuard(공유 dsl 위). */
        @Bean
        open fun projectArchiveGuard(dsl: DSLContext): ProjectArchiveGuard {
            return ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))
        }

        /**
         * ★예전에는 `hasPermission = true` 고정 스텁이었고, KDoc 에 "권한 거부 경로는
         * [IssueLinkControllerIntegrationTest] 가 덮는다" 고 적혀 있었다. **사실이 아니었다** —
         * 그 파일은 링크 컨트롤러를 덮지 그래프 컨트롤러를 덮지 않는다. 그래서 이 경로는
         * 권한 검사가 아예 없는 채로 어떤 테스트에도 걸리지 않았다.
         *
         * 형제와 **같은 스텁**을 쓴다 — 여기서 새 스텁을 만들면 술어가 갈라진다.
         */
        @Bean
        open fun issuePermissionResolver(): IssueLinkControllerIntegrationTest.TogglableIssuePermissionResolver =
            IssueLinkControllerIntegrationTest.TogglableIssuePermissionResolver()

        @Bean
        open fun linkApplicationService(
            issueRepository: IssueRepository,
            issueLinkRepository: IssueLinkRepository,
            archiveGuard: ProjectArchiveGuard,
            permissionResolver: IssueLinkControllerIntegrationTest.TogglableIssuePermissionResolver,
        ): LinkApplicationService {
            return LinkApplicationService(
                issueRepository,
                issueLinkRepository,
                archiveGuard,
                permissionResolver,
            )
        }

        @Bean
        open fun issueParentService(
            issueRepository: IssueRepository,
            archiveGuard: ProjectArchiveGuard,
            permissionResolver: IssueLinkControllerIntegrationTest.TogglableIssuePermissionResolver,
        ): IssueParentService = IssueParentService(issueRepository, archiveGuard, permissionResolver)

        @Bean
        open fun issueGraphService(
            issueRepository: IssueRepository,
            issueLinkRepository: IssueLinkRepository,
            issueGraphRepository: IssueGraphRepository,
            permissionResolver: IssueLinkControllerIntegrationTest.TogglableIssuePermissionResolver,
        ): IssueGraphService = IssueGraphService(issueRepository, issueLinkRepository, issueGraphRepository, permissionResolver)

        @Bean
        open fun issueGraphController(issueGraphService: IssueGraphService): IssueGraphController = IssueGraphController(issueGraphService)

        @Bean
        open fun issueLinkController(
            linkApplicationService: LinkApplicationService,
            issueParentService: IssueParentService,
            issueRepository: IssueRepository,
        ): IssueLinkController = IssueLinkController(linkApplicationService, issueParentService, issueRepository)

        @Bean
        open fun linkExceptionHandler(): LinkExceptionHandler = LinkExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    /** 권한 거부를 켜고 끄는 스텁 — 테스트마다 `reset()` 으로 상태가 새지 않게 한다. */
    @Autowired
    lateinit var permissionResolver: IssueLinkControllerIntegrationTest.TogglableIssuePermissionResolver

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper =
        ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        private const val PROJECT_KEY = "GKTEST"
        private var migrated = false
        private var projectSeeded = false

        /** issue_types.id — V003 task 시드로 생성되는 기본 타입 id */
        private var taskTypeId: Long = -1L
        private var testProjectId: UUID = UUID.randomUUID()
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!projectSeeded) {
            seedProjectAndType()
            projectSeeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // 스텁은 컨텍스트 스코프라 테스트 간 공유된다 — 앞 테스트의 deny 가 새면 뒤가 조용히 통과한다.
        permissionResolver.reset()
        // 링크 API 가 2026-07-27 부터 CurrentActor 로 actor 를 추출한다 — 인증 컨텍스트 없으면 401 이다.
        // 이 파일은 그래프 조회를 검증하므로 링크 생성 헬퍼가 통과할 수 있도록 컨텍스트를 심는다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                emptyList(),
            )
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_links")
                stmt.execute("DELETE FROM issues WHERE project_id = '$testProjectId'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    // ── G1. 200 depth 기본값=2 — nodes/edges 정확 ────────────────────────────────

    @Test
    fun `G1 GET graph 200 - depth 기본값 2 nodes edges 정확`() {
        // center(depth=0) → hop1(1-hop) → hop2(2-hop)
        val centerKey = createIssue("Center")
        val hop1Key = createIssue("Hop1")
        val hop2Key = createIssue("Hop2")

        createLink(centerKey, hop1Key, "blocks") // center blocks hop1
        createLink(hop1Key, hop2Key, "blocks") // hop1 blocks hop2

        mockMvc.perform(get("/api/v1/issues/$centerKey/graph"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.center").value(centerKey))
            .andExpect(jsonPath("$.data.depth").value(2))
            .andExpect(jsonPath("$.data.truncated").value(false))
            .andExpect(jsonPath("$.data.nodes.length()").value(3))
            // center 노드는 depth=0
            .andExpect(jsonPath("$.data.nodes[?(@.key == '$centerKey')].depth").value(0))
            // edge.type 대문자
            .andExpect(jsonPath("$.data.edges[0].type").value("BLOCKS"))
    }

    // ── G2. 200 ?depth=1 — 1-hop만 포함 ─────────────────────────────────────────

    @Test
    fun `G2 GET graph 200 - depth=1 이면 2-hop 이슈 제외`() {
        val centerKey = createIssue("Center")
        val hop1Key = createIssue("Hop1")
        val hop2Key = createIssue("Hop2")

        createLink(centerKey, hop1Key, "blocks")
        createLink(hop1Key, hop2Key, "blocks")

        mockMvc.perform(get("/api/v1/issues/$centerKey/graph?depth=1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.depth").value(1))
            // 2-hop 이슈는 포함되지 않아야 한다
            .andExpect(jsonPath("$.data.nodes.length()").value(2))
            .andExpect(jsonPath("$.data.nodes[?(@.key == '$hop2Key')]").isEmpty)
    }

    // ── G3. 200 parent-child 엣지 포함 — from=부모, to=자식, type=PARENT ─────────

    @Test
    fun `G3 GET graph 200 - parent-child 엣지 from=부모 to=자식 type=PARENT`() {
        val parentKey = createIssue("Parent")
        val childKey = createIssue("Child")
        setParent(childKey, parentKey)

        // 부모를 center 로 조회
        mockMvc.perform(get("/api/v1/issues/$parentKey/graph"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nodes.length()").value(2))
            .andExpect(jsonPath("$.data.edges.length()").value(1))
            .andExpect(jsonPath("$.data.edges[0].from").value(parentKey))
            .andExpect(jsonPath("$.data.edges[0].to").value(childKey))
            .andExpect(jsonPath("$.data.edges[0].type").value("PARENT"))
    }

    // ── SEC. 권한 게이트 ─────────────────────────────────────────────────────────

    /**
     * ★중심 이슈에 VIEW 가 없으면 **404** 다.
     *
     * 403 이 아니라 404 인 이유 — 형제 `GET /api/v1/issues/{key}` 가 이미 그렇게 한다
     * (`IssueApplicationService.assertViewIssueOrNotFound`). 한쪽만 403 이면
     * 응답 코드 차이로 기밀 이슈의 **실재**가 드러난다.
     *
     * 이 게이트가 없던 동안, 본체 조회는 404·링크 목록은 403 인데 그래프만 200 으로
     * `summary` 와 `statusKey` 를 내주고 있었다.
     */
    @Test
    fun `SEC-G1 GET graph 404 - 중심 이슈 VIEW 가 없으면 미존재와 같은 응답`() {
        val secretKey = createIssue("기밀 중심")
        permissionResolver.deny(IssuePermission.VIEW, secretKey)

        mockMvc.perform(get("/api/v1/issues/$secretKey/graph"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    /**
     * ★중심만 막으면 부족하다 — **이웃도 걸러야** 한다.
     *
     * 내가 볼 수 있는 이슈에서 기밀 이슈로 링크를 걸면, 그 기밀 이슈가 BFS 이웃으로
     * 딸려 들어온다. 노드가 빠지면 [BfsTraversal.finalEdges] 가 그 엣지도 함께 지운다
     * (양 끝이 모두 방문된 엣지만 남기므로) — 그래서 관문 한 곳만 막으면 된다.
     */
    @Test
    fun `SEC-G2 GET graph 200 - VIEW 없는 이웃은 노드와 엣지에서 함께 빠진다`() {
        val centerKey = createIssue("중심")
        val visibleKey = createIssue("보이는 이웃")
        val secretKey = createIssue("기밀 이웃")

        createLink(centerKey, visibleKey, "blocks")
        createLink(centerKey, secretKey, "blocks")

        permissionResolver.deny(IssuePermission.VIEW, secretKey)

        mockMvc.perform(get("/api/v1/issues/$centerKey/graph"))
            .andExpect(status().isOk)
            // 중심 + 보이는 이웃 = 2개. 기밀 이웃은 빠진다.
            .andExpect(jsonPath("$.data.nodes.length()").value(2))
            .andExpect(jsonPath("$.data.nodes[?(@.key == '$secretKey')]").isEmpty)
            // 노드가 빠지면 그 엣지도 사라져야 한다 — summary 는 없는데 관계만 남으면 여전히 누출이다.
            .andExpect(jsonPath("$.data.edges.length()").value(1))
            .andExpect(jsonPath("$.data.edges[?(@.to == '$secretKey')]").isEmpty)
    }

    /**
     * 대조군 — 위 두 게이트가 "전부 막기" 로 무너지지 않았음을 확인한다.
     * 이 짝이 없으면 그래프를 통째로 비워도 SEC-G1·G2 가 통과한다.
     */
    @Test
    fun `SEC-G3 대조군 - VIEW 가 있으면 이웃이 그대로 들어온다`() {
        val centerKey = createIssue("대조군 중심")
        val neighborKey = createIssue("대조군 이웃")
        createLink(centerKey, neighborKey, "blocks")

        mockMvc.perform(get("/api/v1/issues/$centerKey/graph"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nodes.length()").value(2))
            .andExpect(jsonPath("$.data.edges.length()").value(1))
    }

    // ── G4. 404 — 존재하지 않는 키 → ISSUE_NOT_FOUND ─────────────────────────────

    @Test
    fun `G4 GET graph 404 - 존재하지 않는 이슈키 ISSUE_NOT_FOUND`() {
        mockMvc.perform(get("/api/v1/issues/GKTEST-9999/graph"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── G5. 400 ?depth=abc — INVALID_DEPTH (500이 아님) ──────────────────────────

    @Test
    fun `G5 GET graph 400 - depth=abc 는 INVALID_DEPTH (500 아님)`() {
        val issueKey = createIssue("이슈")

        mockMvc.perform(get("/api/v1/issues/$issueKey/graph?depth=abc"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("INVALID_DEPTH"))
    }

    // ── G6. 400 ?depth=0 — 범위 미달 ──────────────────────────────────────────────

    @Test
    fun `G6 GET graph 400 - depth=0 은 범위 미달 INVALID_DEPTH`() {
        val issueKey = createIssue("이슈")

        mockMvc.perform(get("/api/v1/issues/$issueKey/graph?depth=0"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("INVALID_DEPTH"))
    }

    // ── G7. 400 ?depth=4 — 범위 초과 ──────────────────────────────────────────────

    @Test
    fun `G7 GET graph 400 - depth=4 는 범위 초과 INVALID_DEPTH`() {
        val issueKey = createIssue("이슈")

        mockMvc.perform(get("/api/v1/issues/$issueKey/graph?depth=4"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("INVALID_DEPTH"))
    }

    // ── G8. 200 링크/부모 없는 이슈 → nodes 1개 + edges 0개 ─────────────────────

    @Test
    fun `G8 GET graph 200 - 링크 없는 이슈는 nodes 1개 edges 0개`() {
        val loneKey = createIssue("고립 이슈")

        mockMvc.perform(get("/api/v1/issues/$loneKey/graph"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nodes.length()").value(1))
            .andExpect(jsonPath("$.data.nodes[0].key").value(loneKey))
            .andExpect(jsonPath("$.data.nodes[0].depth").value(0))
            .andExpect(jsonPath("$.data.edges.length()").value(0))
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    /** 이슈를 DB 에 직접 삽입하고 이슈 키를 반환한다. */
    private fun createIssue(summary: String): String {
        var key: String? = null
        conn().use { c ->
            c.prepareStatement(
                "UPDATE projects SET key_sequence = key_sequence + 1 WHERE id = ?",
            ).use { stmt ->
                stmt.setObject(1, testProjectId)
                stmt.executeUpdate()
            }
            c.prepareStatement("SELECT key_sequence FROM projects WHERE id = ?").use { stmt ->
                stmt.setObject(1, testProjectId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    key = "$PROJECT_KEY-${rs.getInt(1)}"
                }
            }
            c.prepareStatement(
                """
                INSERT INTO issues (project_id, key, summary, reporter_id, current_state_key, type_id, version)
                VALUES (?, ?, ?, '00000000-0000-0000-0000-000000000001', 'open', ?, 1)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, testProjectId)
                stmt.setString(2, key)
                stmt.setString(3, summary)
                stmt.setLong(4, taskTypeId)
                stmt.executeUpdate()
            }
        }
        return requireNotNull(key)
    }

    /** MockMvc 를 통해 링크를 생성하고 linkId 를 반환한다. */
    private fun createLink(
        sourceKey: String,
        targetKey: String,
        linkType: String,
    ): Long {
        val result =
            mockMvc.perform(
                post("/api/v1/issues/$sourceKey/links")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(mapOf("targetKey" to targetKey, "linkType" to linkType))),
            ).andExpect(status().isCreated)
                .andReturn()
        val json = mapper.readTree(result.response.contentAsString)
        return json["data"]["id"].asLong()
    }

    /** MockMvc 를 통해 parent-child 관계를 설정한다. */
    private fun setParent(
        childKey: String,
        parentKey: String,
    ) {
        mockMvc.perform(
            patch("/api/v1/issues/$childKey/parent")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("parentKey" to parentKey))),
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

    @Suppress("NestedBlockDepth") // JDBC try-with-resources(conn→stmt→rs) 시드 보일러플레이트 — 테스트 1회성 setup
    private fun seedProjectAndType() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Graph Integration Test Project")
                stmt.executeUpdate()
            }
            c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    testProjectId = rs.getObject(1) as UUID
                }
            }
            c.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' LIMIT 1").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        taskTypeId = rs.getLong(1)
                    }
                }
            }
            if (taskTypeId == -1L) {
                c.prepareStatement(
                    "INSERT INTO issue_types (key, name, hierarchy_level) VALUES ('task', 'Task', 0) RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        taskTypeId = rs.getLong(1)
                    }
                }
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
