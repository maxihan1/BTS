// 워크플로우↔상태 편성 통합 검증 — 추가·제거·순서변경과 그 가드

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.application.WorkflowCommandService
import com.bts.workflow.application.WorkflowStatusCompositionService
import com.bts.workflow.application.command.CreateWorkflowCommand
import com.bts.workflow.application.command.WorkflowStatusSeed
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.exception.WorkflowStatusCompositionException
import com.bts.workflow.domain.exception.WorkflowStatusInUseException
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.repository.WorkflowStatusCompositionRepository
import com.bts.workflow.repository.WorkflowWriteRepository
import com.bts.workflow.status.repository.StatusRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
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
            workflowService = WorkflowCommandService(writeRepository, repository, cache, allowAll)
            service =
                WorkflowStatusCompositionService(
                    WorkflowStatusCompositionRepository(dsl),
                    writeRepository,
                    statusRepository,
                    IssueStatusUsageStub,
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

        override fun countIssuesInStatus(statusKey: String): Long = if (statusKey in usedKeys) 1L else 0L
    }

    private val actor = UUID.randomUUID()

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
