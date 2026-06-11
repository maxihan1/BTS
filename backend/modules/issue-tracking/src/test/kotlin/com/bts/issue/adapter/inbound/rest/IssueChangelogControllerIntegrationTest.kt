// GET /api/v1/issues/{key}/changelog 엔드포인트 HTTP 통합 테스트 — FR-HS-02 Task B3
@file:Suppress("MaxLineLength")

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.application.IssueChangelogService
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.history.JdbcIssueChangeHistoryRepository
import com.bts.shared.user.UserLookupPort
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.DriverManager
import java.util.UUID

/**
 * GET /api/v1/issues/{key}/changelog HTTP 통합 테스트.
 *
 * 실제 Testcontainers PostgreSQL + [TestConfig] 전체 스택 위에서 검증한다.
 * [TestConfig] 를 재사용하고, [ChangelogControllerConfig] 로 changelog 전용 빈을 추가 wire 한다.
 *
 * ## 검증 시나리오
 * - S1. 이슈 + 변경 그룹 2건 삽입 → 200 + content 2건 + items 포함
 * - S2. content[].items[] 필드 구조 — field/fromValue/toValue/fromLabel/toLabel 포함
 * - S3. actorId/actorName/createdAt(ISO-8601) 포함
 * - S4. VIEW 권한 없음/미존재/소프트삭제 → 404
 * - S5. 페이지 경계 — size=1 로 요청 시 totalPages > 1
 *
 * ## 인증 패턴
 * [TestConfig] 의 AlwaysAllowIssuePermissionResolver 로 권한을 일괄 허용한 뒤,
 * S4 에서는 소프트 삭제(deleted_at 셋)로 404 를 유발한다.
 *
 * @see TestConfig 공유 Spring 컨텍스트 (Testcontainers singleton + MockMvc + 두 BC wire)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [
        TestConfig::class,
        IssueChangelogControllerIntegrationTest.ChangelogControllerConfig::class,
    ],
)
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueChangelogControllerIntegrationTest {
    /**
     * changelog 전용 빈 추가 구성.
     *
     * [TestConfig] 가 historyRecorder=mockk(relaxed=true) 로 등록하므로
     * JdbcIssueChangeHistoryRepository 는 별도로 wire 한다.
     * IssueController 에 changelogService 를 주입하기 위해 @Primary 로 교체한다.
     */
    @Configuration
    open class ChangelogControllerConfig {
        @Bean
        open fun changelogNamedParameterJdbcTemplate(dataSource: DriverManagerDataSource): NamedParameterJdbcTemplate =
            NamedParameterJdbcTemplate(dataSource)

        @Bean
        open fun changelogIssueChangeHistoryRepository(
            jdbc: NamedParameterJdbcTemplate,
        ): IssueChangeHistoryRepository = JdbcIssueChangeHistoryRepository(jdbc)

        @Bean
        open fun changelogUserLookupPort(): UserLookupPort =
            object : UserLookupPort {
                override fun exists(userId: UUID): Boolean = true

                override fun findDisplayNamesByIds(ids: Set<UUID>): Map<UUID, String> =
                    ids.associateWith { "테스터" }
            }

        @Bean
        open fun issueChangelogService(
            issueApplicationService: com.bts.issue.application.IssueApplicationService,
            historyRepository: IssueChangeHistoryRepository,
            userLookupPort: UserLookupPort,
        ): IssueChangelogService =
            IssueChangelogService(
                issueApplicationService = issueApplicationService,
                changeHistoryRepository = historyRepository,
                userLookupPort = userLookupPort,
            )

        /**
         * IssueController 를 changelogService 포함 버전으로 교체한다.
         *
         * TestConfig 의 issueController 빈보다 @Primary 로 우선 적용한다.
         * changelog 엔드포인트는 changelogService 가 non-null 이어야 동작하므로
         * 실 서비스를 주입한다.
         */
        @Bean
        @Primary
        open fun issueControllerWithChangelog(
            service: com.bts.issue.application.IssueApplicationService,
            pdfRenderer: com.bts.issue.pdf.IssuePdfRenderer,
            changelogService: IssueChangelogService,
        ): IssueController = IssueController(service, pdfRenderer, changelogService)
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dataSource: DriverManagerDataSource

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())

    companion object {
        private const val PROJECT_KEY = "CHLOG"
        private val ACTOR_UUID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
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
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                ACTOR_UUID.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        cleanIssuesAndHistory()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── S1. 정상 경로 — 200 + content 2건 ─────────────────────────────────────

    /**
     * S1 변경 이력 정상 조회.
     *
     * Given  이슈 1건 + 변경 그룹 2건 삽입
     * When   GET /api/v1/issues/{key}/changelog?page=0&size=20
     * Then   200 OK + content 배열 2건 + totalElements=2
     */
    @Test
    fun `S1 이슈 changelog 조회 — 200 + 그룹 2건 반환`() {
        val issueKey = insertIssue(PROJECT_KEY, "S1 이슈")
        val issueId = fetchIssueId(issueKey)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "summary", "이전 제목", "새 제목", null, null)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "priority", "3", "1", null, null)

        mockMvc.perform(
            get("/api/v1/issues/$issueKey/changelog")
                .param("page", "0")
                .param("size", "20"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.totalElements").value(2))
    }

    // ── S2. items 필드 구조 — field/fromValue/toValue/fromLabel/toLabel 포함 ──────

    /**
     * S2 items 필드 구조 검증.
     *
     * Given  이슈 1건 + 변경 그룹 1건(summary 아이템, fromLabel/toLabel null)
     * When   GET /api/v1/issues/{key}/changelog
     * Then   content[0].items[0].field="summary"
     *        content[0].items[0].fromValue="이전 제목"
     *        content[0].items[0].toValue="새 제목"
     *        NON_NULL 정책으로 fromLabel/toLabel 키 미포함 (Zod nullish 정합)
     */
    @Test
    fun `S2 items 필드 구조 — field fromValue toValue 포함, null 라벨은 NON_NULL 로 제외`() {
        val issueKey = insertIssue(PROJECT_KEY, "S2 이슈")
        val issueId = fetchIssueId(issueKey)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "summary", "이전 제목", "새 제목", null, null)

        val result =
            mockMvc.perform(get("/api/v1/issues/$issueKey/changelog"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[0].items[0].field").value("summary"))
                .andExpect(jsonPath("$.content[0].items[0].fromValue").value("이전 제목"))
                .andExpect(jsonPath("$.content[0].items[0].toValue").value("새 제목"))
                .andReturn()

        val body = result.response.contentAsString
        val responseNode = mapper.readTree(body)
        val item = responseNode.path("content").get(0).path("items").get(0)
        // NON_NULL 정책 — fromLabel/toLabel null 이면 키 자체가 응답에서 제거된다.
        assert(!item.has("fromLabel")) {
            "fromLabel 이 null 이면 NON_NULL 정책으로 응답 JSON 에 키가 없어야 하지만 있음."
        }
        assert(!item.has("toLabel")) {
            "toLabel 이 null 이면 NON_NULL 정책으로 응답 JSON 에 키가 없어야 하지만 있음."
        }
    }

    // ── S3. actorId/actorName/createdAt ISO-8601 포함 ──────────────────────────

    /**
     * S3 actorId/actorName/createdAt 필드 검증.
     *
     * Given  이슈 1건 + 변경 그룹 1건(actorId=ACTOR_UUID)
     * When   GET /api/v1/issues/{key}/changelog
     * Then   content[0].actorId == ACTOR_UUID.toString()
     *        content[0].actorName 은 non-null (stub 표시명 "테스터" 반환)
     *        content[0].createdAt 은 ISO-8601 문자열
     */
    @Test
    fun `S3 actorId actorName createdAt 필드 검증 — actorId UUID 형식, actorName non-null, createdAt ISO-8601`() {
        val issueKey = insertIssue(PROJECT_KEY, "S3 이슈")
        val issueId = fetchIssueId(issueKey)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "priority", "3", "1", null, null)

        val result =
            mockMvc.perform(get("/api/v1/issues/$issueKey/changelog"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[0].actorId").value(ACTOR_UUID.toString()))
                .andExpect(jsonPath("$.content[0].actorName").value("테스터"))
                .andReturn()

        val body = result.response.contentAsString
        val responseNode = mapper.readTree(body)
        val createdAt = responseNode.path("content").get(0).path("createdAt").asText()
        // ISO-8601 형식 검증 — Instant 가 "Z" 접미사를 포함하는지 확인
        assert(createdAt.endsWith("Z") || createdAt.contains("+")) {
            "createdAt 이 ISO-8601 형식이어야 하지만 실제 값: $createdAt"
        }
    }

    // ── S4. 미존재/소프트삭제 → 404 ────────────────────────────────────────────

    /**
     * S4a 존재하지 않는 이슈 키 → 404.
     *
     * Given  DB에 없는 이슈 키
     * When   GET /api/v1/issues/CHLOG-99999/changelog
     * Then   404 Not Found
     */
    @Test
    fun `S4a 존재하지 않는 이슈 changelog 요청 — 404 반환`() {
        mockMvc.perform(get("/api/v1/issues/CHLOG-99999/changelog"))
            .andExpect(status().isNotFound)
    }

    /**
     * S4b 소프트 삭제된 이슈 → 404.
     *
     * Given  이슈 삽입 후 deleted_at 셋
     * When   GET /api/v1/issues/{key}/changelog
     * Then   404 Not Found (단건 조회와 동일한 404 동작)
     */
    @Test
    fun `S4b 소프트 삭제된 이슈 changelog 요청 — 404 반환`() {
        val issueKey = insertIssue(PROJECT_KEY, "S4b 소프트 삭제 이슈")
        softDeleteIssue(issueKey)

        mockMvc.perform(get("/api/v1/issues/$issueKey/changelog"))
            .andExpect(status().isNotFound)
    }

    // ── S5. 페이지 경계 — size=1 → totalPages > 1 ─────────────────────────────

    /**
     * S5 페이지 경계 검증.
     *
     * Given  이슈 1건 + 변경 그룹 3건 삽입
     * When   GET /api/v1/issues/{key}/changelog?page=0&size=1
     * Then   totalPages > 1 (3건 / size=1 = 3 페이지)
     *        content.length() == 1
     */
    @Test
    fun `S5 size=1 페이지 요청 시 totalPages 가 그룹 수만큼 분할된다`() {
        val issueKey = insertIssue(PROJECT_KEY, "S5 페이지 경계 이슈")
        val issueId = fetchIssueId(issueKey)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "summary", "원본1", "수정1", null, null)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "priority", "3", "2", null, null)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "summary", "수정1", "수정2", null, null)

        mockMvc.perform(
            get("/api/v1/issues/$issueKey/changelog")
                .param("page", "0")
                .param("size", "1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.totalPages").value(3))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — [TestConfig] 컨테이너에 issue-tracking + project-workflow 순차 적용.
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
     * CHLOG 프로젝트를 삽입한다. 이미 존재하면 무시.
     */
    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Changelog Controller Integration Test Project")
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 각 테스트마다 이슈/이력 테이블을 초기화한다.
     */
    private fun cleanIssuesAndHistory() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    /**
     * 테스트용 이슈를 삽입하고 이슈 키를 반환한다.
     */
    private fun insertIssue(
        projectKey: String,
        summary: String,
    ): String {
        return conn().use { conn ->
            conn.autoCommit = false

            val seq =
                conn.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            val issueKey = "$projectKey-$seq"
            val projectId = fetchProjectId(conn, projectKey)
            val taskTypeId = fetchTaskTypeId(conn)

            conn.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "VALUES (?, ?, ?, ?, 'open', 1, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, ACTOR_UUID)
                stmt.setLong(5, taskTypeId)
                stmt.executeUpdate()
            }

            conn.commit()
            issueKey
        }
    }

    /**
     * 이슈를 소프트 삭제한다 (deleted_at 셋).
     */
    private fun softDeleteIssue(issueKey: String) {
        conn().use { c ->
            c.prepareStatement(
                "UPDATE issues SET deleted_at = NOW() WHERE key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 변경 그룹 1건과 아이템 1건을 직접 삽입한다.
     *
     * @param issueId 이슈 UUID
     * @param issueKey 이슈 키 (스냅샷)
     * @param actorId 변경 행위자 UUID. null=시스템.
     * @param field 변경 필드명
     * @param fromValue 변경 전 값
     * @param toValue 변경 후 값
     * @param fromLabel 변경 전 라벨 (null 허용)
     * @param toLabel 변경 후 라벨 (null 허용)
     */
    @Suppress("LongParameterList")
    private fun insertChangeGroup(
        issueId: UUID,
        issueKey: String,
        actorId: UUID?,
        field: String,
        fromValue: String?,
        toValue: String?,
        fromLabel: String?,
        toLabel: String?,
    ) {
        conn().use { c ->
            c.autoCommit = false

            val groupId =
                c.prepareStatement(
                    "INSERT INTO issue_change_group (issue_id, issue_key, actor_id) VALUES (?, ?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setObject(1, issueId)
                    stmt.setString(2, issueKey)
                    stmt.setObject(3, actorId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            c.prepareStatement(
                "INSERT INTO issue_change_item (group_id, field, from_value, to_value, from_label, to_label) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setLong(1, groupId)
                stmt.setString(2, field)
                stmt.setString(3, fromValue)
                stmt.setString(4, toValue)
                stmt.setString(5, fromLabel)
                stmt.setString(6, toLabel)
                stmt.executeUpdate()
            }

            c.commit()
        }
    }

    private fun fetchIssueId(issueKey: String): UUID =
        conn().use { c ->
            c.prepareStatement("SELECT id FROM issues WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "이슈 $issueKey 가 없습니다." }
                    rs.getObject(1) as UUID
                }
            }
        }

    private fun fetchProjectId(
        conn: java.sql.Connection,
        projectKey: String,
    ): UUID =
        conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
            stmt.setString(1, projectKey)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
            }
        }

    private fun fetchTaskTypeId(conn: java.sql.Connection): Long =
        conn.prepareStatement(
            "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                rs.getLong(1)
            }
        }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
