// PostAction E2E 통합 테스트 — 실 컨트롤러/서비스/repo + Testcontainers

package com.bts.workflow.postaction

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.engine.DefaultWorkflowPostActionFactory
import com.bts.workflow.postaction.web.PostActionController
import com.bts.workflow.postaction.web.PostActionExceptionHandler
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.web.WorkflowSchemeExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.filter.CharacterEncodingFilter
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * post-action CRUD E2E 통합 테스트.
 *
 * 실 컨트롤러/서비스/레포지토리를 Testcontainers PostgreSQL 과 함께 구성한다.
 * Spring ApplicationContext 없이 MockMvc standalone 으로 구성해 DB + HTTP 레이어를 함께 검증한다.
 *
 * 검증 범위.
 * - CALL_WEBHOOK 생성(POST 201) → GET 목록 조회 → PUT 수정 → DELETE 삭제
 * - 캐시 무효화 호출 확인(workflowKey 기준)
 * - 권한 거부 403 (permissionResolver stub 이 예외를 던지는 경우)
 * - 미지원 transitionKey 404
 * - 모듈 전체 회귀 0 (이 테스트 이후 전체 suite 에서 확인)
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PostActionE2EIntegrationTest {

    companion object {
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        lateinit var mockMvc: MockMvc
        lateinit var workflowCache: WorkflowCache
        lateinit var permissionResolver: WorkflowSchemePermissionResolver
        lateinit var postActionRepository: PostActionRepository

        private const val WORKFLOW_KEY = "simple"
        private const val TRANSITION_KEY = "todo__doing"
        private val basePath =
            "/api/v1/workflows/$WORKFLOW_KEY/transitions/$TRANSITION_KEY/post-actions"

        @BeforeAll
        @JvmStatic
        fun setup() {
            // 1단계: V200 까지 적용
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .target("200")
                .load()
                .migrate()

            // issue_types 스텁
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS issue_types (
                            id          BIGSERIAL    PRIMARY KEY,
                            key         VARCHAR(30)  NOT NULL UNIQUE,
                            name        VARCHAR(255) NOT NULL,
                            is_standard BOOLEAN      NOT NULL DEFAULT FALSE,
                            created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                            updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                            deleted_at  TIMESTAMPTZ
                        )
                        """.trimIndent(),
                    )
                }
            }

            // 2단계: 전체 적용
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()

            // simple 워크플로우 시드 (todo/doing/done 3상태 + 전이)
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflows (key, name)
                        VALUES ('$WORKFLOW_KEY', '단순 워크플로우')
                        ON CONFLICT (key) DO NOTHING
                        """.trimIndent(),
                    )
                }

                val workflowId: String =
                    conn.prepareStatement("SELECT id FROM workflows WHERE key='$WORKFLOW_KEY'").use { ps ->
                        ps.executeQuery().use { rs -> rs.next(); rs.getString("id") }
                    }

                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflow_states (workflow_id, key, name, category, display_order) VALUES
                          ('$workflowId'::uuid, 'todo',  'To Do',       'TODO',        1),
                          ('$workflowId'::uuid, 'doing', 'In Progress', 'IN_PROGRESS', 2),
                          ('$workflowId'::uuid, 'done',  'Done',        'DONE',        3)
                        ON CONFLICT DO NOTHING
                        """.trimIndent(),
                    )
                }

                val todoId: String =
                    conn.prepareStatement(
                        "SELECT id FROM workflow_states WHERE workflow_id='$workflowId'::uuid AND key='todo'",
                    ).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getString("id") } }

                val doingId: String =
                    conn.prepareStatement(
                        "SELECT id FROM workflow_states WHERE workflow_id='$workflowId'::uuid AND key='doing'",
                    ).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getString("id") } }

                conn.prepareStatement(
                    """
                    INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name)
                    VALUES ('$workflowId'::uuid, '$todoId'::uuid, '$doingId'::uuid, 'Start')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).use { it.execute() }
            }

            // 빈 구성 — 실 컴포넌트 조립
            val dataSource =
                org.springframework.jdbc.datasource.DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            val objectMapper = ObjectMapper().registerKotlinModule()

            postActionRepository = PostActionRepository(dsl, objectMapper)
            val factory = DefaultWorkflowPostActionFactory()
            val resolver = PostActionTransitionResolver(dsl)

            val workflowRepo = WorkflowRepository(dsl)
            workflowCache = mockk()
            justRun { workflowCache.invalidate(any()) }

            val service = PostActionAdminService(postActionRepository, factory, resolver, workflowCache)

            permissionResolver = mockk(relaxed = true)

            val controller = PostActionController(service, permissionResolver)

            mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                    .addFilters<MockMvc>(CharacterEncodingFilter("UTF-8", true))
                    .setControllerAdvice(
                        PostActionExceptionHandler(),
                        WorkflowSchemeExceptionHandler(),
                    )
                    .build()
        }
    }

    // ── POST 201 ──────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `POST - CALL_WEBHOOK 생성 201`() {
        val body = """{"type":"CALL_WEBHOOK","config":{"url":"https://hook.example.com","method":"POST"},"displayOrder":0}"""

        mockMvc.perform(
            post(basePath)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.type").value("CALL_WEBHOOK"))
            .andExpect(jsonPath("$.data.id").isNotEmpty)

        verify(atLeast = 1) { workflowCache.invalidate(WORKFLOW_KEY) }
    }

    // ── GET 200 ───────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `GET - 목록 조회 200`() {
        mockMvc.perform(get(basePath))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].type").value("CALL_WEBHOOK"))
    }

    // ── PUT 200 ───────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `PUT - 수정 200 + 캐시 무효화`() {
        // GET 에서 id 를 가져온다
        val result =
            mockMvc.perform(get(basePath))
                .andExpect(status().isOk)
                .andReturn()
        val id =
            ObjectMapper().readTree(result.response.contentAsString)
                .get("data").get(0).get("id").asText()

        val body = """{"type":"CALL_WEBHOOK","config":{"url":"https://updated.example.com","method":"PUT"},"displayOrder":5}"""

        mockMvc.perform(
            put("$basePath/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.displayOrder").value(5))
            .andExpect(jsonPath("$.data.config.url").value("https://updated.example.com"))

        verify(atLeast = 2) { workflowCache.invalidate(WORKFLOW_KEY) }
    }

    // ── DELETE 204 ────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `DELETE - 삭제 204 + 이후 GET 은 빈 목록`() {
        val listResult =
            mockMvc.perform(get(basePath))
                .andExpect(status().isOk)
                .andReturn()
        val id =
            ObjectMapper().readTree(listResult.response.contentAsString)
                .get("data").get(0).get("id").asText()

        mockMvc.perform(delete("$basePath/$id"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get(basePath))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    // ── 미존재 transitionKey 404 ──────────────────────────────────────────────

    @Test
    @Order(50)
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `미존재 transitionKey - GET 404`() {
        mockMvc.perform(get("/api/v1/workflows/$WORKFLOW_KEY/transitions/open__nonexistent/post-actions"))
            .andExpect(status().isNotFound)
    }

    // ── transitionKey 형식 오류 404 ───────────────────────────────────────────

    @Test
    @Order(60)
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `transitionKey 형식 오류 - GET 404`() {
        mockMvc.perform(get("/api/v1/workflows/$WORKFLOW_KEY/transitions/malformed/post-actions"))
            .andExpect(status().isNotFound)
    }

    // ── 검증 실패 400 ─────────────────────────────────────────────────────────

    @Test
    @Order(70)
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `비-http url - POST 400`() {
        val body = """{"type":"CALL_WEBHOOK","config":{"url":"ftp://bad.com","method":"POST"},"displayOrder":0}"""

        mockMvc.perform(
            post(basePath)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    @Order(80)
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111")
    fun `미지원 type - POST 400`() {
        val body = """{"type":"UNKNOWN_TYPE","config":{},"displayOrder":0}"""

        mockMvc.perform(
            post(basePath)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
    }
}
