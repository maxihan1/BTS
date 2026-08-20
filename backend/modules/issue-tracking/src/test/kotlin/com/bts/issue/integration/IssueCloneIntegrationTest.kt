// 이슈 클론 런타임 통합 테스트 — POST /issues/{key}/clone 의 복사/리셋/키발급을 실제 Postgres로 검증 (FR-IS-06)

package com.bts.issue.integration

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.testsupport.insertWorkflowStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.flywaydb.core.Flyway
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.DriverManager
import java.sql.Types
import java.util.UUID

/**
 * 이슈 클론 런타임 통합 테스트.
 *
 * "클론이 실제 DB + 실제 워크플로우 위에서 동작하는가?" 를 mock 없이 검증한다.
 * 단위 테스트(IssueApplicationServiceCloneTest)는 repo/workflow 를 mock 했으므로,
 * 실제 Postgres 영속 + 키 발급 + 워크플로우 초기상태 결정 갭을 이 테스트가 메운다.
 *
 * ## 시나리오
 * - CI-1. 옵션 미지정 클론 → 201 + 새 키 + 복사 필드 영속(summary/description/priority/labels/environment/impact/assignee)
 *          + currentStateKey=초기상태 + version=1.
 * - CI-2. includeAssignee=false → 클론본 assigneeId=null.
 * - CI-3. summaryOverride → 클론본 summary 덮어쓰기, 나머지 복사.
 * - CI-4. 연속 클론 → key_sequence 가 매번 증가해 서로 다른 키 발급.
 *
 * ## 컨텍스트 공유
 * [TestConfig] (Testcontainers singleton + Flyway migrate + Spring 빈 구성) 를 재사용한다.
 * CLONEIT 프로젝트와 software-scheme 배정을 독립적으로 시드한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueCloneIntegrationTest {
    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        /** 이 통합 테스트 전용 프로젝트 키. 정규식 ^[A-Z][A-Z0-9]{1,9}$ 준수. */
        private const val PROJECT_KEY = "CLONEIT"
        private const val SOURCE_ASSIGNEE = "00000000-0000-0000-0000-0000000000aa"
        private var bootstrapped = false
    }

    @BeforeAll
    fun setUpAll() {
        if (!bootstrapped) {
            Flyway.configure()
                .dataSource(TestConfig.postgres.jdbcUrl, TestConfig.postgres.username, TestConfig.postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()
            seedProjectAndScheme()
            bootstrapped = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── CI-1. 옵션 미지정 클론 → 복사 필드 영속 + 새 키/초기상태/version=1 ────────

    @Test
    fun `클론하면 복사 대상 필드가 영속되고 키·상태·version 은 새로 시작한다`() {
        val sourceKey = insertRichIssue("결제 버그", "재현 절차", 2, listOf("payment", "urgent"), "prod", 1, SOURCE_ASSIGNEE)

        mockMvc.perform(
            post("/api/v1/issues/$sourceKey/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/v1/issues/$PROJECT_KEY-2"))
            .andExpect(jsonPath("$.data.key").value("$PROJECT_KEY-2"))
            .andExpect(jsonPath("$.data.summary").value("결제 버그"))
            .andExpect(jsonPath("$.data.description").value("재현 절차"))
            .andExpect(jsonPath("$.data.priority").value(2))
            .andExpect(jsonPath("$.data.labels", containsInAnyOrder("payment", "urgent")))
            .andExpect(jsonPath("$.data.environment").value("prod"))
            .andExpect(jsonPath("$.data.impact").value(1))
            .andExpect(jsonPath("$.data.assigneeId").value(SOURCE_ASSIGNEE))
            .andExpect(jsonPath("$.data.currentStateKey").value("open"))
            .andExpect(jsonPath("$.data.version").value(1))

        // DB 영속 재확인 — GET 단건 재조회
        mockMvc.perform(get("/api/v1/issues/$PROJECT_KEY-2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.summary").value("결제 버그"))
            .andExpect(jsonPath("$.data.assigneeId").value(SOURCE_ASSIGNEE))
    }

    // ── CI-2. includeAssignee=false → 클론본 미할당 ──────────────────────────────

    @Test
    fun `includeAssignee=false 면 클론본 담당자가 비어 영속된다`() {
        val sourceKey = insertRichIssue("담당자 있는 이슈", null, 3, emptyList(), null, null, SOURCE_ASSIGNEE)

        mockMvc.perform(
            post("/api/v1/issues/$sourceKey/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("includeAssignee" to false))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.assigneeId").isEmpty)
    }

    // ── CI-3. summaryOverride → 제목 덮어쓰기 ────────────────────────────────────

    @Test
    fun `summaryOverride 를 주면 클론본 제목이 덮어써진다`() {
        val sourceKey = insertRichIssue("원본 제목", "본문", 3, emptyList(), null, null, null)

        mockMvc.perform(
            post("/api/v1/issues/$sourceKey/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("summaryOverride" to "복제 제목"))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.summary").value("복제 제목"))
            .andExpect(jsonPath("$.data.description").value("본문"))
    }

    // ── CI-4. 연속 클론 → key_sequence 증가 ──────────────────────────────────────

    @Test
    fun `같은 원본을 두 번 클론하면 서로 다른 새 키가 발급된다`() {
        val sourceKey = insertRichIssue("연속 클론 원본", null, 3, emptyList(), null, null, null)

        mockMvc.perform(post("/api/v1/issues/$sourceKey/clone").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.key").value("$PROJECT_KEY-2"))

        mockMvc.perform(post("/api/v1/issues/$sourceKey/clone").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.key").value("$PROJECT_KEY-3"))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 모든 복사 대상 필드를 채운 원본 이슈를 DB에 직접 삽입하고 이슈 키를 반환한다.
     *
     * Service layer 를 우회하므로 지정한 currentStateKey("open") 를 그대로 삽입한다.
     */
    @Suppress("LongParameterList")
    private fun insertRichIssue(
        summary: String,
        description: String?,
        priority: Int,
        labels: List<String>,
        environment: String?,
        impact: Int?,
        assigneeId: String?,
    ): String {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
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
                conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
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
                        check(rs.next()) { "task 타입이 없습니다. V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            val labelsArray = conn.createArrayOf("text", labels.toTypedArray())

            conn.prepareStatement(
                "INSERT INTO issues " +
                    "(key, project_id, summary, reporter_id, current_state_key, version, type_id, " +
                    " description, priority, labels, environment, impact, assignee_id) " +
                    "VALUES (?, ?, ?, ?, 'open', 1, ?, ?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, UUID.fromString("00000000-0000-0000-0000-000000000001"))
                stmt.setLong(5, taskTypeId)
                stmt.setString(6, description)
                stmt.setInt(7, priority)
                stmt.setArray(8, labelsArray)
                stmt.setString(9, environment)
                if (impact != null) stmt.setInt(10, impact) else stmt.setNull(10, Types.SMALLINT)
                stmt.setObject(11, assigneeId?.let { UUID.fromString(it) }, Types.OTHER)
                stmt.executeUpdate()
            }

            conn.commit()
            return issueKey
        }
    }

    /**
     * CLONEIT 프로젝트를 생성하고 software-scheme(default mapping → software-default workflow)을 배정한다.
     *
     * IssueControllerTransitionIntegrationTest.seedWorkflowsAndSchemes() 가 같은 JVM singleton 컨테이너에
     * software-scheme / software-default workflow / states / transitions 를 시드해 두므로, 여기서는
     * 미실행 대비 fallback 시드 + 프로젝트 삽입 + 스킴 배정만 수행한다. 모두 ON CONFLICT 로 멱등.
     */
    @Suppress("LongMethod")
    private fun seedProjectAndScheme() {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false

            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Clone Integration Test Project")
                stmt.executeUpdate()
            }

            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflows (key, name)
                    VALUES ('software-default', '소프트웨어 개발 기본 워크플로우')
                    ON CONFLICT (key) WHERE deleted_at IS NULL DO NOTHING
                    """.trimIndent(),
                )
            }

            val wfId: UUID =
                conn.prepareStatement("SELECT id FROM workflows WHERE key = 'software-default'").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            fun insertStateIfAbsent(
                key: String,
                name: String,
                category: String,
                displayOrder: Int,
            ): UUID = insertWorkflowStatus(conn, wfId, key, name, category, displayOrder)

            val openId = insertStateIfAbsent("open", "Open", "TODO", 0)
            val inProgressId = insertStateIfAbsent("in_progress", "In Progress", "IN_PROGRESS", 1)
            val inReviewId = insertStateIfAbsent("in_review", "In Review", "IN_PROGRESS", 2)
            val doneId = insertStateIfAbsent("done", "Done", "DONE", 3)

            fun insertTransitionIfAbsent(
                fromId: UUID,
                toId: UUID,
                name: String,
            ) {
                conn.prepareStatement(
                    "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) " +
                        "VALUES (?, ?, ?, ?) ON CONFLICT (workflow_id, from_state_id, to_state_id) DO NOTHING",
                ).use { stmt ->
                    stmt.setObject(1, wfId)
                    stmt.setObject(2, fromId)
                    stmt.setObject(3, toId)
                    stmt.setString(4, name)
                    stmt.executeUpdate()
                }
            }

            insertTransitionIfAbsent(openId, inProgressId, "Start Work")
            insertTransitionIfAbsent(inProgressId, inReviewId, "Submit for Review")
            insertTransitionIfAbsent(inReviewId, doneId, "Approve")

            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_schemes (key, name, is_default)
                    VALUES ('software-scheme', 'Software Scheme', true)
                    ON CONFLICT (key) DO NOTHING
                    """.trimIndent(),
                )
            }

            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                    SELECT s.id, NULL, '$wfId'
                    FROM workflow_schemes s
                    WHERE s.key = 'software-scheme'
                    ON CONFLICT (scheme_id) WHERE issue_type_id IS NULL DO NOTHING
                    """.trimIndent(),
                )
            }

            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                    SELECT p.id, s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                    FROM projects p, workflow_schemes s
                    WHERE p.key = '$PROJECT_KEY'
                      AND s.key = 'software-scheme'
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }

            conn.commit()
        }
    }
}
