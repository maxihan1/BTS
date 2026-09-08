// 전환 런타임 통합 테스트 — GET /transitions → POST /transition → 재조회의 전체 흐름을 실제 Postgres로 검증

package com.bts.issue.integration

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest
import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.testsupport.insertWorkflowStatus
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
import org.springframework.http.MediaType
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.DriverManager
import java.util.UUID

/**
 * 이슈 전환 런타임 통합 테스트.
 *
 * "전환이 런타임에 실제로 동작하는가?" 라는 질문을 mock 없이 검증한다.
 * 단위 테스트는 WorkflowTransitionPort 를 mock 해서 통과했지만,
 * 실제 Postgres + 실제 워크플로우 시드를 사용하는 갭을 이 테스트가 메운다.
 * (메모: issue-transition-backend-gap)
 *
 * ## 시나리오 (GET transitions → POST transition → 재조회)
 *
 * ### S1. 가용 전환 조회 후 첫 번째 전환 실행 → 이슈 상태 갱신 확인
 * Given   RUNTIME_IT 프로젝트에 이슈 1건 삽입 (currentStateKey = software-default 시작 상태 = "open")
 * When    GET /api/v1/issues/{key}/transitions 호출
 * Then    200 OK + transitions 배열 비어있지 않음 (시드된 워크플로우 기준)
 * When    transitions[0].toStateKey 로 POST /api/v1/issues/{key}/transition 실행
 * Then    200 OK + data.currentStateKey == transitions[0].toStateKey
 * When    GET /api/v1/issues/{key}/transitions 재호출
 * Then    200 OK + 새 상태 기준 전환 목록 반환 (이전 상태 기준과 다름)
 *
 * ### S2. 전환 후 재조회 시 currentStateKey 갱신 확인 (상태 영속성)
 * Given   이슈 currentStateKey = "open"
 * When    open → in_progress 전환 실행
 * Then    GET /api/v1/issues/{key} 재조회 시 currentStateKey == "in_progress"
 *
 * ## 설계 원칙 — workflow-robust 단언
 * 하드코딩된 상태값 의존 최소화.
 * GET transitions 응답에서 첫 번째 가용 전환을 동적으로 선택해 실행한다.
 * 워크플로우 시드 변경에 견고하게 대응한다.
 *
 * ## 컨텍스트 공유
 * TestConfig (Testcontainers singleton + Flyway migrate + Spring 빈 구성) 를
 * [IssueControllerTransitionIntegrationTest] 에서 직접 재사용한다.
 * 동일 컨테이너를 공유하므로 별도 컨테이너 기동 비용이 없다.
 * 단, 이 테스트는 별개의 RUNTIME_IT 프로젝트와 software-scheme 배정을 독립적으로 시드한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueTransitionRuntimeIntegrationTest {
    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dataSource: DriverManagerDataSource

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        /** 이 통합 테스트 전용 프로젝트 키 — IssueControllerTransitionIntegrationTest 와 충돌 없음. 정규식 ^[A-Z][A-Z0-9]{1,9}$ 준수. */
        private const val PROJECT_KEY = "RUNTIMEIT"
        private var bootstrapped = false
    }

    @BeforeAll
    fun setUpAll() {
        if (!bootstrapped) {
            // Flyway 마이그레이션 — TestConfig 의 @BeforeAll 실행 순서 보장 불가 시에도 안전하게 동작하도록
            // 이 테스트가 자체적으로 마이그레이션을 실행한다. Flyway 의 repeatability 덕분에 중복 실행 무해.
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

        // 각 테스트 독립성 보장 — issues 행 초기화 + key_sequence 초기화
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

    // ── S1. 가용 전환 조회 → 첫 번째 전환 실행 → 새 상태 기준 전환 갱신 ────────

    /**
     * S1 — GET transitions → POST transition → GET transitions(재조회) 전체 흐름.
     *
     * Given   RUNTIME_IT 프로젝트에 이슈 삽입 (currentStateKey = "open")
     * When    GET /api/v1/issues/{key}/transitions
     * Then    200 OK + transitions 배열 비어있지 않음
     * When    transitions[0].toStateKey 로 POST .../transition 실행
     * Then    200 OK + data.currentStateKey == transitions[0].toStateKey
     * When    GET /api/v1/issues/{key}/transitions 재호출
     * Then    200 OK + 새 상태 기준 전환 배열 반환
     *
     * ## workflow-robust 설계
     * 전환 대상 상태를 하드코딩하지 않는다.
     * 첫 번째 조회 결과의 transitions[0].toStateKey 를 동적으로 사용한다.
     * 이렇게 하면 software-default.yaml 시드가 변경돼도 단언이 깨지지 않는다.
     */
    @Test
    fun `GET transitions 후 첫 번째 전환을 실행하면 상태가 갱신되고 가용 전환도 새 상태 기준으로 갱신된다`() {
        val issueKey = insertIssue(PROJECT_KEY, "전환 흐름 통합 검증 이슈", "open")

        // Step 1: 가용 전환 조회
        val transitionsResult =
            mockMvc.perform(
                get("/api/v1/issues/$issueKey/transitions"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.transitions").isArray)
                .andReturn()

        val responseBody = transitionsResult.response.contentAsString
        val root = mapper.readTree(responseBody)
        val transitionsNode = root.path("data").path("transitions")

        // 시드된 워크플로우에서 open 상태 기준 전환이 1건 이상 있어야 한다
        assert(transitionsNode.size() > 0) {
            "open 상태에서 이동 가능한 전환이 없습니다. 워크플로우 시드를 확인하세요. response=$responseBody"
        }

        // Step 2: transitions[0].toStateKey 로 전환 실행
        val targetStateKey = transitionsNode.get(0).path("toStateKey").asText()
        assert(targetStateKey.isNotBlank()) {
            "transitions[0].toStateKey 가 비어있습니다. response=$responseBody"
        }

        val transitionBody = mapOf("toStatusKey" to targetStateKey, "expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(transitionBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.currentStateKey").value(targetStateKey))

        // Step 3: 전환 후 가용 전환 재조회 — 새 상태 기준 전환 배열 반환
        val reQueryResult =
            mockMvc.perform(
                get("/api/v1/issues/$issueKey/transitions"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.transitions").isArray)
                .andReturn()

        val reQueryBody = reQueryResult.response.contentAsString
        val reQueryRoot = mapper.readTree(reQueryBody)
        val reQueryTransitions = reQueryRoot.path("data").path("transitions")

        // 전환 후 조회한 전환 목록은 targetStateKey 에서 출발하는 것들이어야 한다
        // (open 에서 in_progress 로 전환 후, in_progress 에서 출발하는 전환 목록)
        for (i in 0 until reQueryTransitions.size()) {
            val fromStateKey = reQueryTransitions.get(i).path("fromStateKey").asText()
            assert(fromStateKey == targetStateKey) {
                "전환 후 transitions[$i].fromStateKey 가 '$targetStateKey' 여야 하지만 '$fromStateKey' 입니다. " +
                    "response=$reQueryBody"
            }
        }
    }

    // ── S2. 전환 후 이슈 단건 재조회 시 currentStateKey 영속성 확인 ───────────

    /**
     * S2 — POST transition 실행 후 GET issue 단건 재조회 시 currentStateKey 가 DB에 영속됨을 확인한다.
     *
     * Given   RUNTIME_IT 프로젝트에 이슈 삽입 (currentStateKey = "open")
     * When    open → in_progress 전환 실행 (POST .../transition)
     * Then    200 OK + data.currentStateKey == "in_progress"
     * When    GET /api/v1/issues/{key} 단건 재조회
     * Then    200 OK + currentStateKey == "in_progress" (DB 영속 확인)
     *
     * ## 단언 근거
     * software-default 워크플로우에 open → in_progress ("Start Work") 전환이 정의되어 있다.
     * IssueControllerTransitionIntegrationTest.seedWorkflowsAndSchemes() 이 시드한 동일 워크플로우를 사용한다.
     */
    @Test
    fun `전환 실행 후 이슈 단건 재조회 시 currentStateKey 가 영속된다`() {
        val issueKey = insertIssue(PROJECT_KEY, "상태 영속성 검증 이슈", "open")

        // open → in_progress 전환 실행 (software-default 에 정의된 전환)
        val body = mapOf("toStatusKey" to "in_progress", "expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.currentStateKey").value("in_progress"))

        // GET 단건 재조회 — DB 영속 확인
        mockMvc.perform(
            get("/api/v1/issues/$issueKey"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.currentStateKey").value("in_progress"))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * RUNTIME_IT 프로젝트를 생성하고 software-scheme(default mapping → software-default workflow)을 배정한다.
     *
     * IssueControllerTransitionIntegrationTest.seedWorkflowsAndSchemes() 가 이미 software-scheme 과
     * software-default workflow 를 시드해 두므로, 여기서는 프로젝트 삽입 + 스킴 배정만 수행한다.
     *
     * 선행 조건: IssueControllerTransitionIntegrationTest 의 @BeforeAll 이 먼저 실행되어
     * workflows / workflow_schemes / workflow_scheme_issue_type_mappings 가 시드되어 있어야 한다.
     * TestConfig 의 Testcontainers singleton 컨테이너를 공유하므로 선행 조건은 자동으로 충족된다
     * (Spring ApplicationContext 공유로 IssueControllerTransitionIntegrationTest.setUpAll() 이
     * 동일 JVM 세션에서 먼저 실행됨).
     *
     * 단, 실행 순서 보장이 불가능한 경우에 대비해 ON CONFLICT DO NOTHING 을 사용한다.
     *
     * LongMethod: 프로젝트·스킴 배정 픽스처를 순서대로 삽입해야 하므로 함수 분리보다 인라인이 적합하다. PRE_EXISTING.
     */
    @Suppress("LongMethod")
    private fun seedProjectAndScheme() {
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.autoCommit = false

            // 1. RUNTIME_IT 프로젝트 삽입
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Runtime Integration Test Project")
                stmt.executeUpdate()
            }

            // 2. software-scheme 이 존재하지 않을 경우를 대비한 fallback 시드
            //    (IssueControllerTransitionIntegrationTest 의 seedWorkflowsAndSchemes 가 미실행된 경우)
            conn.createStatement().use { stmt ->
                // software-default workflow 가 없으면 삽입
                stmt.execute(
                    """
                    INSERT INTO workflows (key, name)
                    VALUES ('software-default', '소프트웨어 개발 기본 워크플로우')
                    ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL DO NOTHING
                    """.trimIndent(),
                )
            }

            // software-default workflow id 조회
            val wfId: UUID =
                conn.prepareStatement(
                    "SELECT id FROM workflows WHERE key = 'software-default'",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            // workflow_states 가 없으면 시드 (open, in_progress, in_review, done)
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

            // workflow_transitions 가 없으면 시드
            fun insertTransitionIfAbsent(
                fromId: UUID,
                toId: UUID,
                name: String,
            ) {
                conn.prepareStatement(
                    "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) " +
                        "SELECT ?, ?, ?, ? WHERE NOT EXISTS (" +
                        "SELECT 1 FROM workflow_transitions " +
                        "WHERE workflow_id = ? AND from_state_id = ? AND to_state_id = ?)",
                ).use { stmt ->
                    stmt.setObject(1, wfId)
                    stmt.setObject(2, fromId)
                    stmt.setObject(3, toId)
                    stmt.setString(4, name)
                    stmt.setObject(5, wfId)
                    stmt.setObject(6, fromId)
                    stmt.setObject(7, toId)
                    stmt.executeUpdate()
                }
            }

            insertTransitionIfAbsent(openId, inProgressId, "Start Work")
            insertTransitionIfAbsent(inProgressId, inReviewId, "Submit for Review")
            insertTransitionIfAbsent(inReviewId, doneId, "Approve")

            // 3. software-scheme 삽입
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_schemes (key, name, is_default)
                    VALUES ('software-scheme', 'Software Scheme', true)
                    ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL DO NOTHING
                    """.trimIndent(),
                )
            }

            // 4. software-scheme default mapping → software-default workflow
            // uq_scheme_issue_type 은 NULL != NULL 로 동작해 issue_type_id IS NULL 중복을 감지하지 못함.
            // ix_scheme_default_mapping partial index (scheme_id WHERE issue_type_id IS NULL) 기준으로 ON CONFLICT 처리.
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

            // 5. RUNTIME_IT 프로젝트에 software-scheme 배정
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

    /**
     * 테스트용 이슈를 DB에 직접 삽입하고 이슈 키를 반환한다.
     *
     * Service layer 를 우회하므로 workflowKeyResolver 호출 없이 지정한 currentStateKey 를 그대로 삽입한다.
     */
    private fun insertIssue(
        projectKey: String,
        summary: String,
        currentStateKey: String,
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
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            val issueKey = "$projectKey-$seq"

            val projectId =
                conn.prepareStatement(
                    "SELECT id FROM projects WHERE key = ?",
                ).use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            // task 타입 id 조회 — V003 seed 에 의해 항상 존재, V005에서 type_id NOT NULL 추가됨
            val taskTypeId =
                conn.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입이 없습니다. V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            conn.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "VALUES (?, ?, ?, ?, ?, 1, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, UUID.fromString("00000000-0000-0000-0000-000000000001"))
                stmt.setString(5, currentStateKey)
                stmt.setLong(6, taskTypeId)
                stmt.executeUpdate()
            }

            conn.commit()
            return issueKey
        }
    }
}
