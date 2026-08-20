// PostAction E2E 통합 테스트 — 실 컨트롤러/서비스/repo + Testcontainers

package com.bts.workflow.postaction

import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.engine.DefaultWorkflowPostActionFactory
import com.bts.workflow.postaction.web.PostActionController
import com.bts.workflow.postaction.web.PostActionExceptionHandler
import com.bts.workflow.scheme.web.WorkflowSchemeExceptionHandler
import com.bts.workflow.testsupport.insertWorkflowStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
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
 * - 미존재 transitionKey 404
 * - 비-http url 검증 실패 400
 * - 미지원 type 400
 * - 신 컬럼(`from_status_id`/`to_status_id`)만 채운 전환 · GLOBAL · INITIAL 에 규칙을 붙일 수 있다
 *
 * 주의: post-action 은 전환 실행 시 DB 직접 조회(WorkflowCache 비캐시 대상)이므로
 * WorkflowCache mock 및 캐시 무효화 검증이 불필요하다.
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

        private const val WORKFLOW_KEY = "simple"
        private const val TRANSITION_KEY = "todo__doing"

        // 전환 키의 정본은 도메인의 계산 프로퍼티다. 리터럴로 적으면 key 형식이 바뀌어도
        // 이 테스트가 눈치채지 못한다 — 두 목록이 서로를 검사하게 둔다.
        private val MODERN_NORMAL_KEY =
            WorkflowTransition("doing", "done", "Finish", kind = TransitionKind.NORMAL).key
        private val GLOBAL_KEY =
            WorkflowTransition(null, "done", "긴급 종료", kind = TransitionKind.GLOBAL).key
        private val INITIAL_KEY =
            WorkflowTransition(null, "todo", "이슈 생성", kind = TransitionKind.INITIAL).key
        private const val ACTOR_UUID = "11111111-1111-1111-1111-111111111111"
        private val basePath =
            "/api/v1/workflows/$WORKFLOW_KEY/transitions/$TRANSITION_KEY/post-actions"

        @BeforeAll
        @JvmStatic
        @Suppress("LongMethod")
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

            // simple 워크플로우 시드 (todo/doing/done 3상태 + 전환)
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflows (key, name)
                        VALUES ('$WORKFLOW_KEY', '단순 워크플로우')
                        ON CONFLICT (key) WHERE deleted_at IS NULL DO NOTHING
                        """.trimIndent(),
                    )
                }

                val workflowId: String =
                    conn.prepareStatement("SELECT id FROM workflows WHERE key='$WORKFLOW_KEY'").use { ps ->
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getString("id")
                        }
                    }

                insertWorkflowStatus(conn, java.util.UUID.fromString(workflowId), "todo", "To Do", "TODO", 1)
                insertWorkflowStatus(
                    conn,
                    java.util.UUID.fromString(workflowId),
                    "doing",
                    "In Progress",
                    "IN_PROGRESS",
                    2,
                )
                insertWorkflowStatus(conn, java.util.UUID.fromString(workflowId), "done", "Done", "DONE", 3)

                val todoId: String =
                    conn.prepareStatement(
                        "SELECT id FROM workflow_states WHERE workflow_id='$workflowId'::uuid AND key='todo'",
                    ).use { ps ->
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getString("id")
                        }
                    }

                val doingId: String =
                    conn.prepareStatement(
                        "SELECT id FROM workflow_states WHERE workflow_id='$workflowId'::uuid AND key='doing'",
                    ).use { ps ->
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getString("id")
                        }
                    }

                conn.prepareStatement(
                    """
                    INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name)
                    VALUES ('$workflowId'::uuid, '$todoId'::uuid, '$doingId'::uuid, 'Start')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).use { it.execute() }

                seedModernTransitions(conn, java.util.UUID.fromString(workflowId))
            }

            // 컴포넌트 조립
            val dataSource =
                org.springframework.jdbc.datasource.DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            val objectMapper = ObjectMapper().registerKotlinModule()

            val postActionRepository = PostActionRepository(dsl, objectMapper)
            val factory = DefaultWorkflowPostActionFactory()
            val resolver = PostActionTransitionResolver(dsl)

            val service = PostActionAdminService(postActionRepository, factory, resolver)

            // permissionResolver — relaxed mock (always allow)
            val permissionResolver: WorkflowSchemePermissionResolver = mockk(relaxed = true)

            val controller = PostActionController(service, permissionResolver)

            mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(
                        PostActionExceptionHandler(),
                        WorkflowSchemeExceptionHandler(),
                    )
                    .build()
        }

        /** 각 테스트에서 SecurityContext 에 UUID principal 을 설정하는 헬퍼. */
        private fun withActor(block: () -> Unit) {
            val auth =
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    ACTOR_UUID,
                    null,
                    emptyList(),
                )
            SecurityContextHolder.getContext().authentication = auth
            try {
                block()
            } finally {
                SecurityContextHolder.clearContext()
            }
        }

        /**
         * 신 컬럼(`from_status_id`·`to_status_id`)만 채운 전환 3종을 심는다.
         *
         * 전환 정의 CRUD(`WorkflowWriteRepository.insertTransition`)가 만드는 행이 정확히 이 모양이다 —
         * 구 컬럼은 손대지 않으므로 NULL 로 남는다. 위의 'Start' 전환은 반대로 구 컬럼만 채운
         * 종전 세대 행이라, 두 세대가 한 워크플로우 안에 나란히 있는 상태를 만든다.
         */
        private fun seedModernTransitions(
            conn: Connection,
            workflowId: UUID,
        ) {
            val todo = statusCompositionId(conn, workflowId, "todo")
            val doing = statusCompositionId(conn, workflowId, "doing")
            val done = statusCompositionId(conn, workflowId, "done")

            insertModernTransition(conn, workflowId, TransitionKind.NORMAL, "Finish", doing, done)
            insertModernTransition(conn, workflowId, TransitionKind.GLOBAL, "긴급 종료", null, done)
            insertModernTransition(conn, workflowId, TransitionKind.INITIAL, "이슈 생성", null, todo)
        }

        /** 워크플로우에 편성된 상태 1개의 `workflow_statuses.id`. 전환의 신 컬럼이 가리키는 값이다. */
        private fun statusCompositionId(
            conn: Connection,
            workflowId: UUID,
            stateKey: String,
        ): UUID =
            conn.prepareStatement(
                "SELECT ws.id FROM workflow_statuses ws" +
                    " JOIN statuses s ON s.id = ws.status_id AND s.deleted_at IS NULL" +
                    " WHERE ws.workflow_id = ? AND s.key = ?",
            ).use { stmt ->
                stmt.setObject(1, workflowId)
                stmt.setString(2, stateKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "workflow_statuses 에 '$stateKey' 편성이 없다" }
                    rs.getObject(1) as UUID
                }
            }

        /** 구 컬럼을 비운 채 전환 1행. INITIAL 만 표시 순서 0 이다 (V207 ⑨ 와 같은 규칙). */
        @Suppress("LongParameterList")
        private fun insertModernTransition(
            conn: Connection,
            workflowId: UUID,
            kind: TransitionKind,
            name: String,
            fromStatusId: UUID?,
            toStatusId: UUID,
        ) {
            conn.prepareStatement(
                "INSERT INTO workflow_transitions" +
                    " (workflow_id, kind, name, from_status_id, to_status_id, display_order)" +
                    " VALUES (?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, workflowId)
                stmt.setString(2, kind.name)
                stmt.setString(3, name)
                stmt.setObject(4, fromStatusId)
                stmt.setObject(5, toStatusId)
                stmt.setInt(6, if (kind == TransitionKind.INITIAL) 0 else 1)
                stmt.execute()
            }
        }

        /** 전환 하나에 post-action 을 만들고(201) 같은 경로로 다시 읽는다(200 · 1건). */
        private fun assertPostActionRoundTrip(transitionKey: String) {
            val path = "/api/v1/workflows/$WORKFLOW_KEY/transitions/$transitionKey/post-actions"
            val body = """{"type":"SET_FIELD","config":{"field":"assignee","value":"me"},"displayOrder":0}"""

            mockMvc.perform(
                post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isCreated)

            mockMvc.perform(get(path))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("SET_FIELD"))
        }
    }

    // ── POST 201 ──────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @Suppress("MaxLineLength")
    fun `POST - CALL_WEBHOOK 생성 201`() {
        withActor {
            val body = """{"type":"CALL_WEBHOOK","config":{"url":"https://hook.example.com","method":"POST"},"displayOrder":0}"""

            mockMvc.perform(
                post(basePath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.type").value("CALL_WEBHOOK"))
                .andExpect(jsonPath("$.data.id").isNotEmpty)
        }
    }

    // ── GET 200 ───────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    fun `GET - 목록 조회 200`() {
        withActor {
            mockMvc.perform(get(basePath))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").isArray)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("CALL_WEBHOOK"))
        }
    }

    // ── PUT 200 ───────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @Suppress("MaxLineLength")
    fun `PUT - 수정 200`() {
        withActor {
            val listResult =
                mockMvc.perform(get(basePath))
                    .andExpect(status().isOk)
                    .andReturn()
            val id =
                ObjectMapper().readTree(listResult.response.contentAsString)
                    .get("data").get(0).get("id").asText()

            val body =
                """{"type":"CALL_WEBHOOK","config":{"url":"https://updated.example.com","method":"PUT"},"displayOrder":5}"""

            mockMvc.perform(
                put("$basePath/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.displayOrder").value(5))
                .andExpect(jsonPath("$.data.config.url").value("https://updated.example.com"))
        }
    }

    // ── DELETE 204 ────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    fun `DELETE - 삭제 204 + 이후 GET 은 빈 목록`() {
        withActor {
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
    }

    // ── 미존재 transitionKey 404 ──────────────────────────────────────────────

    @Test
    @Order(50)
    fun `미존재 transitionKey - GET 404`() {
        withActor {
            mockMvc.perform(get("/api/v1/workflows/$WORKFLOW_KEY/transitions/open__nonexistent/post-actions"))
                .andExpect(status().isNotFound)
        }
    }

    // ── transitionKey 형식 오류 404 ───────────────────────────────────────────

    @Test
    @Order(60)
    fun `transitionKey 형식 오류 - GET 404`() {
        withActor {
            mockMvc.perform(get("/api/v1/workflows/$WORKFLOW_KEY/transitions/malformed/post-actions"))
                .andExpect(status().isNotFound)
        }
    }

    // ── 검증 실패 400 ─────────────────────────────────────────────────────────

    @Test
    @Order(70)
    fun `비-http url - POST 400`() {
        withActor {
            val body = """{"type":"CALL_WEBHOOK","config":{"url":"ftp://bad.com","method":"POST"},"displayOrder":0}"""

            mockMvc.perform(
                post(basePath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isBadRequest)
        }
    }

    @Test
    @Order(80)
    fun `미지원 type - POST 400`() {
        withActor {
            val body = """{"type":"UNKNOWN_TYPE","config":{},"displayOrder":0}"""

            mockMvc.perform(
                post(basePath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isBadRequest)
        }
    }

    // ── 신 컬럼만 채운 전환에도 규칙을 붙일 수 있다 (P1 #2) ────────────────────
    //
    // 전환 정의 CRUD 가 만드는 행은 구 컬럼이 NULL 이다. 구 컬럼으로만 찾던 종전 resolver 는
    // 이 세 전환을 한 건도 못 잡아 전부 404 였다 — 새로 만든 전환에 규칙을 붙일 방법이 없었다.

    @Test
    @Order(90)
    fun `신 컬럼만 채운 NORMAL 전환에 post-action 을 붙인다`() {
        withActor { assertPostActionRoundTrip(MODERN_NORMAL_KEY) }
    }

    @Test
    @Order(100)
    fun `GLOBAL 전환에 post-action 을 붙인다`() {
        withActor { assertPostActionRoundTrip(GLOBAL_KEY) }
    }

    @Test
    @Order(110)
    fun `INITIAL 전환에 post-action 을 붙인다`() {
        withActor { assertPostActionRoundTrip(INITIAL_KEY) }
    }

    // ── 구 컬럼만 채운 전환은 폴백으로 계속 해석된다 ──────────────────────────

    @Test
    @Order(120)
    fun `구 컬럼만 채운 전환도 여전히 해석된다`() {
        withActor {
            mockMvc.perform(get(basePath))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.length()").value(0))
        }
    }
}
