// 워크플로우↔상태 편성 통합 검증 — 추가·제거·순서변경과 그 가드

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.application.WorkflowCommandService
import com.bts.workflow.application.WorkflowStatusCompositionService
import com.bts.workflow.application.WorkflowStatusReferencedByTransitionException
import com.bts.workflow.application.command.CreateWorkflowCommand
import com.bts.workflow.application.command.TransitionDefinitionCommand
import com.bts.workflow.application.command.WorkflowStatusSeed
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.exception.WorkflowStatusCompositionException
import com.bts.workflow.domain.exception.WorkflowStatusInUseException
import com.bts.workflow.jooq.tables.WorkflowTransitions.Companion.WORKFLOW_TRANSITIONS
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.repository.WorkflowStatusCompositionRepository
import com.bts.workflow.repository.WorkflowWriteRepository
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.status.repository.StatusRepository
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.flywaydb.core.Flyway
import org.hamcrest.Matchers.containsString
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * 워크플로우에 상태를 넣고 빼고 순서를 바꾸는 계약.
 *
 * ### 순서 변경은 「전체 집합 일치」를 요구한다
 * 부분 목록을 받으면 빠진 상태의 순서가 **암묵적으로** 정해진다. 그 규칙을 사람이 기억해야 하는 순간
 * 화면과 서버가 갈라진다. 그래서 요청이 그 워크플로우의 상태 전부를 정확히 담아야 한다.
 *
 * ### 마지막 상태는 뺄 수 없다
 * `Workflow.of()` invariant 가 「상태 하나 이상」을 요구한다. 다 빼면 그 워크플로우는
 * 조회 자체가 `IllegalArgumentException` 으로 죽어 **되살릴 방법이 화면에 없다**.
 *
 * ### 이슈가 쓰는 상태는 뺄 수 없다
 * 빼면 이슈가 「워크플로우에 없는 상태」에 남는다. 판정은 `IssueStatusUsagePort` 가 하고,
 * 그 어댑터는 `IssueTypeUsageAdapter` 와 같은 방식으로 다른 BC 테이블을 **읽기만** 한다.
 *
 * ### 전환이 가리키는 상태는 뺄 수 없다
 * `workflow_transitions.from_status_id`·`to_status_id` 는 `ON DELETE CASCADE`(V207 ③)다. 편성을 떼면
 * 그 전환이 **하드 삭제**되고 매달린 validator·post-action 까지 FK CASCADE 로 함께 사라진다 —
 * 소프트 삭제도 감사 로그도 없어 되살릴 방법이 없다(`DATA.md §1.2`).
 *
 * 그래서 이 아래 두 테스트는 전환을 **실제로 심는다.** 전환이 0건이면 CASCADE 가 발화하지 않아
 * 가드를 지워도 초록이다 — 그것이 이 파일의 종전 상태였다.
 *
 * ### 가드가 막았다는 사실이 HTTP 로도 전달돼야 한다
 * 서비스가 예외를 던지는 것만으로는 화면이 아무것도 못 한다. 매핑이 없으면 그 예외는 advice 를
 * 통과해 컨테이너까지 올라가 **500** 이 되고, 프론트는 「막힌 이유」와 「서버 고장」을 구별할 수 없다.
 * 그래서 마지막 제거 가드 테스트는 계층을 건너뛰지 않고 컨트롤러 + advice 를 통과시켜
 * **상태 코드와 본문**을 잰다.
 */
@Testcontainers
class WorkflowStatusCompositionIntegrationTest {
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

        lateinit var dsl: DSLContext
        lateinit var repository: WorkflowRepository
        lateinit var statusRepository: StatusRepository
        lateinit var workflowService: WorkflowCommandService
        lateinit var service: WorkflowStatusCompositionService

        private val allowAll =
            object : WorkflowDefinitionPermissionResolver {
                override fun requirePermission(
                    actorId: UUID,
                    permission: WorkflowDefinitionPermission,
                ) = Unit
            }

        @BeforeAll
        @JvmStatic
        fun setup() {
            migrate("200")
            createIssueTypes()
            migrate(null)

            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = WorkflowRepository(dsl)
            statusRepository = StatusRepository(dsl)
            val cache = WorkflowCache(repository, dsl)
            val writeRepository = WorkflowWriteRepository(dsl)
            workflowService =
                WorkflowCommandService(
                    writeRepository,
                    repository,
                    cache,
                    allowAll,
                    mockk(relaxed = true),
                )
            service =
                WorkflowStatusCompositionService(
                    WorkflowStatusCompositionRepository(dsl),
                    writeRepository,
                    repository,
                    statusRepository,
                    IssueStatusUsageStub,
                    ProjectWorkflowSchemeAssignmentRepository(dsl),
                    cache,
                    allowAll,
                )
        }

        private fun migrate(target: String?) {
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking", "classpath:db/migration/project-workflow")
                .apply { if (target != null) target(target) }
                .load()
                .migrate()
        }

        private fun createIssueTypes() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS issue_types (" +
                            "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                            "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                            "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                            "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                    )
                }
            }
        }
    }

    /**
     * 이슈 사용량 stub. 테스트가 명시적으로 심은 키만 「쓰이는 중」으로 본다.
     *
     * `AlwaysAllow` 처럼 **항상 0** 을 돌려주는 stub 은 쓰지 않는다 — 그러면 제거 가드가
     * 도달 불가가 되어 가짜 그린이 된다(MEMORY `unreachable-state-fixture-is-fake-green`).
     */
    object IssueStatusUsageStub : com.bts.workflow.application.port.IssueStatusUsagePort {
        val usedKeys: MutableSet<String> = mutableSetOf()

        /**
         * 마지막 호출이 받은 프로젝트 스코프.
         *
         * 인자를 버리면 서비스가 `emptySet()` 을 넘겨도 전 테스트가 초록이다 — 그러면 운영에서
         * 어댑터가 늘 0 을 돌려주어 「이슈가 쓰는 상태는 못 뺀다」 가드가 조용히 fail-open 이 된다.
         */
        var lastProjectIds: Set<UUID> = emptySet()

        override fun countIssuesInStatus(
            statusKey: String,
            projectIds: Set<UUID>,
        ): Long {
            lastProjectIds = projectIds
            return if (statusKey in usedKeys) 1L else 0L
        }
    }

    private val actor = UUID.randomUUID()

    private lateinit var mockMvc: MockMvc

    /**
     * HTTP 계약을 재기 위한 최소 조립.
     *
     * 이 컨트롤러에 실제로 붙는 advice 는 [WorkflowStatusCompositionExceptionHandler] 하나뿐이다
     * (`assignableTypes` 로 좁혀져 있다). 그러니 여기서도 그것만 등록한다 — 없는 advice 를 더 끼우면
     * 배포와 다른 결과가 나온다.
     */
    @BeforeEach
    fun setUpMockMvc() {
        mockMvc =
            MockMvcBuilders.standaloneSetup(WorkflowStatusCompositionController(service))
                .setControllerAdvice(WorkflowStatusCompositionExceptionHandler())
                .build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(actor.toString(), null, emptyList())
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun makeWorkflow(
        key: String,
        vararg statusKeys: String,
    ): UUID =
        workflowService.create(
            actor,
            CreateWorkflowCommand(
                key = key,
                name = key,
                description = null,
                statuses =
                    statusKeys.mapIndexed { i, s ->
                        WorkflowStatusSeed(key = s, name = "$key-$s", category = "TODO", displayOrder = i)
                    },
                projectKey = null,
            ),
        )

    private fun statusIdOf(key: String): UUID = statusRepository.findAllLive().first { it.key == key }.id

    // ── 추가 (S5) ──────────────────────────────────────────────────────────────

    @Test
    fun `상태를 넣으면 조회 결과에 나타난다`() {
        makeWorkflow("compose-add", "c1", "c2")
        // 카탈로그는 전역이라 다른 워크플로우가 만든 상태를 그대로 가져다 쓸 수 있다 — 그것이 2단 구조의 요점이다.
        makeWorkflow("compose-add-donor", "c-extra")

        service.addStatus(actor, "compose-add", statusIdOf("c-extra"), displayOrder = 9)

        assertThat(repository.findByKey("compose-add")!!.states.map { it.key }).contains("c-extra")
    }

    @Test
    fun `이미 편성된 상태를 또 넣어도 중복되지 않는다`() {
        makeWorkflow("compose-idem", "i1", "i2")
        val id = statusIdOf("i1")

        service.addStatus(actor, "compose-idem", id, displayOrder = 5)

        assertThat(repository.findByKey("compose-idem")!!.states.count { it.key == "i1" }).isEqualTo(1)
    }

    // ── 순서 변경 (E10 · E11) ──────────────────────────────────────────────────

    @Test
    fun `순서를 바꾸면 조회 결과의 순서가 바뀐다`() {
        makeWorkflow("compose-order", "o1", "o2", "o3")
        val reversed = listOf(statusIdOf("o3"), statusIdOf("o2"), statusIdOf("o1"))

        service.reorder(actor, "compose-order", reversed)

        assertThat(repository.findByKey("compose-order")!!.states.map { it.key })
            .containsExactly("o3", "o2", "o1")
    }

    @Test
    fun `순서 요청이 일부 상태를 빠뜨리면 거부된다`() {
        makeWorkflow("compose-partial", "p1", "p2", "p3")

        assertThatThrownBy { service.reorder(actor, "compose-partial", listOf(statusIdOf("p1"))) }
            .describedAs("부분 목록을 받으면 빠진 상태의 순서가 암묵적으로 정해진다 — 화면과 서버가 갈라진다")
            .isInstanceOf(WorkflowStatusCompositionException::class.java)
    }

    @Test
    fun `순서 요청에 그 워크플로우에 없는 상태가 섞이면 거부된다`() {
        makeWorkflow("compose-alien", "a1", "a2")
        makeWorkflow("compose-alien-donor", "a-outsider")
        val ids = listOf(statusIdOf("a1"), statusIdOf("a2"), statusIdOf("a-outsider"))

        assertThatThrownBy { service.reorder(actor, "compose-alien", ids) }
            .isInstanceOf(WorkflowStatusCompositionException::class.java)
    }

    // ── 제거 (E5 · invariant) ──────────────────────────────────────────────────

    @Test
    fun `상태를 빼면 조회 결과에서 사라진다`() {
        makeWorkflow("compose-remove", "r1", "r2")

        service.removeStatus(actor, "compose-remove", statusIdOf("r2"))

        assertThat(repository.findByKey("compose-remove")!!.states.map { it.key }).containsExactly("r1")
    }

    @Test
    fun `마지막 남은 상태는 뺄 수 없다`() {
        makeWorkflow("compose-last", "last-one")

        assertThatThrownBy { service.removeStatus(actor, "compose-last", statusIdOf("last-one")) }
            .describedAs("상태 0개 워크플로우는 Workflow.of() invariant 상 조회 자체가 죽는다 — 화면에서 되살릴 방법이 없다")
            .isInstanceOf(WorkflowStatusCompositionException::class.java)
    }

    @Test
    fun `이슈가 쓰고 있는 상태는 뺄 수 없다`() {
        makeWorkflow("compose-used", "u1", "u2")
        IssueStatusUsageStub.usedKeys += "u2"

        try {
            assertThatThrownBy { service.removeStatus(actor, "compose-used", statusIdOf("u2")) }
                .isInstanceOf(WorkflowStatusInUseException::class.java)
        } finally {
            IssueStatusUsageStub.usedKeys -= "u2"
        }
    }

    /**
     * ★ 제거 가드도 **프로젝트 스코프를 실제로 넘겨야** 한다.
     *
     * 서비스가 `emptySet()` 을 넘기면 어댑터가 늘 0 을 돌려주어 위 「이슈가 쓰고 있는 상태는 뺄 수
     * 없다」가 운영에서 조용히 fail-open 이 된다. 스텁은 값을 테스트가 정하므로 그 사고를 못 잡는다 —
     * 잡히는 자리는 **인자**뿐이다.
     */
    @Test
    fun `서비스가 그 워크플로우의 프로젝트 id 집합을 포트에 그대로 넘긴다`() {
        val workflowId = makeWorkflow("compose-scope", "sc1", "sc2")
        val attached = attachProjects(workflowId)
        IssueStatusUsageStub.lastProjectIds = emptySet()

        service.removeStatus(actor, "compose-scope", statusIdOf("sc2"))

        assertThat(IssueStatusUsageStub.lastProjectIds)
            .describedAs("미끼 프로젝트가 섞이거나 빈 집합이 되면 제거 가드의 근거가 바뀐다")
            .containsExactlyInAnyOrderElementsOf(attached)
    }

    // ── 제거 가드 · 전환 CASCADE (V207 ③) ─────────────────────────────────────

    @Test
    fun `전환이 출발지로 쓰는 상태는 뺄 수 없고 그 전환은 살아남는다`() {
        val workflowId = makeWorkflow("compose-txn-from", "tf1", "tf2")
        workflowService.createTransition(
            actor,
            "compose-txn-from",
            TransitionDefinitionCommand(fromStatusKey = "tf1", toStatusKey = "tf2", name = "작업 시작", kind = "NORMAL"),
        )

        val thrown = catchThrowable { service.removeStatus(actor, "compose-txn-from", statusIdOf("tf1")) }

        assertThat(transitionCount(workflowId))
            .describedAs("from_status_id 의 ON DELETE CASCADE 가 전환을 하드 삭제하면 되살릴 방법이 없다")
            .isEqualTo(1)
        assertThat(thrown)
            .describedAs("무엇이 막는지 알아야 다음 행동을 정한다 — 막은 전환 이름이 메시지에 있어야 한다")
            .isInstanceOf(WorkflowStatusReferencedByTransitionException::class.java)
            .hasMessageContaining("작업 시작")
    }

    @Test
    fun `최초 전환이 도착지로 쓰는 상태는 뺄 수 없고 그 전환은 살아남는다`() {
        val workflowId = makeWorkflow("compose-txn-initial", "ti1", "ti2")
        workflowService.createTransition(
            actor,
            "compose-txn-initial",
            TransitionDefinitionCommand(fromStatusKey = null, toStatusKey = "ti1", name = "이슈 생성", kind = "INITIAL"),
        )

        val thrown = catchThrowable { service.removeStatus(actor, "compose-txn-initial", statusIdOf("ti1")) }

        assertThat(transitionCount(workflowId))
            .describedAs("INITIAL 이 사라지면 WorkflowKeyResolverImpl 이 displayOrder 폴백으로 내려가 진입 상태가 조용히 바뀐다")
            .isEqualTo(1)
        assertThat(thrown)
            .describedAs("출발지 없는 전환도 도착지로 편성을 가리킨다 — to_status_id 쪽 CASCADE 도 같이 막아야 한다")
            .isInstanceOf(WorkflowStatusReferencedByTransitionException::class.java)
            .hasMessageContaining("이슈 생성")
    }

    @Test
    fun `전환이 가리키는 상태를 HTTP 로 빼려 하면 409 와 막은 전환 이름이 온다`() {
        makeWorkflow("compose-txn-http", "th1", "th2")
        workflowService.createTransition(
            actor,
            "compose-txn-http",
            TransitionDefinitionCommand(fromStatusKey = "th1", toStatusKey = "th2", name = "검토 요청", kind = "NORMAL"),
        )

        mockMvc.perform(delete("/api/v1/workflows/compose-txn-http/statuses/${statusIdOf("th1")}"))
            // 500 이 나가면 프론트는 「서버 고장」과 구별하지 못해 재시도 안내를 띄울 수 없다.
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_STATUS_REFERENCED_BY_TRANSITION"))
            // 이름이 없으면 사용자는 편집기에서 어느 선을 먼저 지워야 할지 모른다.
            .andExpect(jsonPath("$.error.message", containsString("검토 요청")))
    }

    // ── 좌표는 이 PR 범위가 아니다 (C7) ────────────────────────────────────────

    @Test
    fun `편성 변경은 layout 좌표를 건드리지 않는다`() {
        makeWorkflow("compose-layout", "l1", "l2")
        setLayout("compose-layout", "l1", 12.5f, 34.5f)

        service.reorder(actor, "compose-layout", listOf(statusIdOf("l2"), statusIdOf("l1")))

        assertThat(layoutOf("compose-layout", "l1"))
            .describedAs("layout_x/y 는 로드맵 PR 9(xyflow) 범위다 — 이 PR 은 display_order 만 다룬다")
            .isEqualTo(12.5f to 34.5f)
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    /**
     * 이 워크플로우를 default 매핑으로 가리키는 스킴에 활성 프로젝트 2건을 붙인다.
     *
     * 다른 워크플로우를 가리키는 미끼 스킴·프로젝트도 함께 심는다 — 없으면 「전체 프로젝트」와
     * 「그 워크플로우의 프로젝트」가 같은 결과가 되어 스코프 단언이 공허해진다.
     *
     * @return 이 워크플로우에 붙은 프로젝트 id 목록.
     */
    private fun attachProjects(workflowId: UUID): List<UUID> {
        val scheme = insertScheme()
        insertDefaultMapping(scheme, workflowId)
        val attached = listOf(insertProject(), insertProject())
        attached.forEach { insertAssignment(it, scheme) }

        val decoyScheme = insertScheme()
        insertDefaultMapping(decoyScheme, insertBareWorkflow())
        insertAssignment(insertProject(), decoyScheme)

        return attached
    }

    private fun insertScheme(): Long {
        val key = "compose-scope-${UUID.randomUUID().toString().take(8)}"
        dsl.execute("INSERT INTO workflow_schemes (key, name) VALUES (?, ?)", key, "편성 스코프 $key")
        return dsl.fetchOne("SELECT id FROM workflow_schemes WHERE key = ?", key)
            ?.get("id", Long::class.java)
            ?: error("workflow_schemes INSERT 실패")
    }

    /** `issue_type_id` NULL = 그 스킴의 default 워크플로우. */
    private fun insertDefaultMapping(
        schemeId: Long,
        workflowId: UUID,
    ) {
        dsl.execute(
            "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)" +
                " VALUES (?, NULL, ?)",
            schemeId,
            workflowId,
        )
    }

    private fun insertProject(): UUID {
        val id = UUID.randomUUID()
        // projects.key 는 ^[A-Z][A-Z0-9]{1,9}$ 를 요구한다 — 앞자리를 문자로 고정한다.
        val key = "C" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        dsl.execute("INSERT INTO projects (id, key, name) VALUES (?, ?, ?)", id, key, "편성 스코프 $key")
        return id
    }

    private fun insertAssignment(
        projectId: UUID,
        schemeId: Long,
    ) {
        dsl.execute(
            "INSERT INTO project_workflow_scheme_assignments" +
                " (project_id, workflow_scheme_id, assigned_by) VALUES (?, ?, ?)",
            projectId,
            schemeId,
            actor,
        )
    }

    /** 미끼 스킴이 가리킬 워크플로우. FK 만 만족시키면 된다. */
    private fun insertBareWorkflow(): UUID {
        val id = UUID.randomUUID()
        val key = "wf-decoy-${id.toString().take(8)}"
        dsl.execute("INSERT INTO workflows (id, key, name) VALUES (?, ?, ?)", id, key, "미끼 워크플로우")
        return id
    }

    /**
     * 그 워크플로우에 남아 있는 전환 행 수.
     *
     * 읽기 경로(`WorkflowRepository`)가 아니라 **테이블을 직접 센다** — 재려는 것이 CASCADE 하드 삭제
     * 그 자체이고, 읽기 경로는 상태가 사라지면 전환도 함께 감춰 손실을 숨긴다.
     */
    private fun transitionCount(workflowId: UUID): Int =
        dsl.fetchCount(WORKFLOW_TRANSITIONS, WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(workflowId))

    private fun setLayout(
        workflowKey: String,
        statusKey: String,
        x: Float,
        y: Float,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "UPDATE workflow_statuses SET layout_x = ?, layout_y = ?" +
                    " WHERE workflow_id = (SELECT id FROM workflows WHERE key = ? AND deleted_at IS NULL)" +
                    "   AND status_id = (SELECT id FROM statuses WHERE key = ? AND deleted_at IS NULL)",
            ).use { stmt ->
                stmt.setFloat(1, x)
                stmt.setFloat(2, y)
                stmt.setString(3, workflowKey)
                stmt.setString(4, statusKey)
                stmt.executeUpdate()
            }
        }
    }

    private fun layoutOf(
        workflowKey: String,
        statusKey: String,
    ): Pair<Float, Float> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT layout_x, layout_y FROM workflow_statuses" +
                    " WHERE workflow_id = (SELECT id FROM workflows WHERE key = ? AND deleted_at IS NULL)" +
                    "   AND status_id = (SELECT id FROM statuses WHERE key = ? AND deleted_at IS NULL)",
            ).use { stmt ->
                stmt.setString(1, workflowKey)
                stmt.setString(2, statusKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getFloat(1) to rs.getFloat(2)
                }
            }
        }
}
