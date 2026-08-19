// 워크플로우 CRUD 통합 검증 — 생성·수정·소프트삭제·복제와 캐시 무효화·참조 가드

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.application.WorkflowCommandService
import com.bts.workflow.application.command.CreateWorkflowCommand
import com.bts.workflow.application.command.UpdateWorkflowCommand
import com.bts.workflow.application.command.WorkflowStatusSeed
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.exception.WorkflowInUseException
import com.bts.workflow.domain.exception.WorkflowKeyConflictException
import com.bts.workflow.domain.exception.WorkflowLockedException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.repository.WorkflowWriteRepository
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
 * 워크플로우 쓰기 CRUD 의 계약을 못박는다. 이 PR 이전에는 쓰기 REST 노출이 **0개**였다.
 *
 * ### 왜 상태를 함께 받아야 하는가
 * `Workflow.of()` invariant 가 「상태가 하나 이상」을 요구한다(`Workflow.kt:45`). 상태 없이 만들면
 * 만들자마자 조회가 `IllegalArgumentException` 으로 죽는다. Jira Cloud 도 새 워크플로우에 기본 상태를
 * 준다. 그래서 생성 명령이 상태 씨앗을 **필수**로 받는다.
 *
 * ### 검사 순서 — 권한이 먼저, 존재 확인이 나중
 * 컨트롤러가 권한을 먼저 묻고 서비스가 대상을 조회한다(`VersionApplicationService.kt:136-138` 관례).
 * 존재 probe 를 막기 위한 의도된 정책이다.
 */
@Testcontainers
class WorkflowCrudIntegrationTest {
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
        lateinit var cache: WorkflowCache
        lateinit var service: WorkflowCommandService

        /** 모든 권한을 허용하는 판정기. 권한 거부 경로는 어댑터 단위 테스트가 덮는다. */
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
            migrateTo("200")
            createIssueTypes()
            migrateToLatest()

            val dataSource =
                DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = WorkflowRepository(dsl)
            cache = WorkflowCache(repository, dsl)
            service = WorkflowCommandService(WorkflowWriteRepository(dsl), repository, cache, allowAll)
        }

        private fun migrateTo(target: String) {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking", "classpath:db/migration/project-workflow")
                .target(target)
                .load()
                .migrate()
        }

        private fun migrateToLatest() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking", "classpath:db/migration/project-workflow")
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

    private val actor = UUID.randomUUID()

    private fun seeds(vararg keys: String): List<WorkflowStatusSeed> =
        keys.mapIndexed { i, k -> WorkflowStatusSeed(key = k, name = k.uppercase(), category = "TODO", displayOrder = i) }

    private fun create(
        key: String,
        name: String = key,
        statuses: List<WorkflowStatusSeed> = seeds("open", "done"),
    ) = service.create(actor, CreateWorkflowCommand(key = key, name = name, description = null, statuses = statuses))

    // ── red-first 1. 이름 수정이 조회에 반영된다 ────────────────────────────────

    @Test
    fun `이름을 수정하면 조회 결과가 새 이름이다`() {
        create("rename-me", name = "옛 이름")

        service.update(actor, "rename-me", UpdateWorkflowCommand(name = "새 이름", description = null))

        assertThat(repository.findByKey("rename-me")?.name).isEqualTo("새 이름")
    }

    // ── red-first 2. 편집 후 캐시가 갱신된다 ────────────────────────────────────

    @Test
    fun `수정하면 캐시가 갱신된다`() {
        create("cache-refresh", name = "옛 이름")
        // 캐시에 옛 값을 올려 둔다.
        assertThat(cache.findByKey("cache-refresh")?.name).isEqualTo("옛 이름")

        service.update(actor, "cache-refresh", UpdateWorkflowCommand(name = "새 이름", description = null))

        assertThat(cache.findByKey("cache-refresh")?.name)
            .describedAs("쓰기 경로가 캐시를 무효화하지 않았다 — 편집이 런타임에 반영되지 않는다")
            .isEqualTo("새 이름")
    }

    // ── red-first 3. 사용 중 워크플로우 삭제는 409 ──────────────────────────────

    @Test
    fun `스킴 매핑이 참조하는 워크플로우는 삭제할 수 없다`() {
        val workflowId = create("referenced-flow")
        attachSchemeMapping(workflowId)

        assertThatThrownBy { service.delete(actor, "referenced-flow") }
            .isInstanceOf(WorkflowInUseException::class.java)
    }

    // ── 나머지 계약 ────────────────────────────────────────────────────────────

    @Test
    fun `생성한 워크플로우는 곧바로 조회된다`() {
        create("brand-new", name = "새 워크플로우")

        val found = repository.findByKey("brand-new")

        assertThat(found).isNotNull()
        assertThat(found!!.states.map { it.key }).containsExactly("open", "done")
    }

    @Test
    fun `상태 씨앗이 비면 생성이 거부된다`() {
        assertThatThrownBy { create("no-states", statuses = emptyList()) }
            .describedAs("Workflow.of() invariant 상 상태 0개 워크플로우는 조회 자체가 불가능하다")
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `같은 key 로 또 만들면 거부된다`() {
        create("dup-flow")

        assertThatThrownBy { create("dup-flow") }.isInstanceOf(WorkflowKeyConflictException::class.java)
    }

    @Test
    fun `삭제는 소프트 삭제이고 조회에서 사라진다`() {
        create("soft-delete-me")

        service.delete(actor, "soft-delete-me")

        assertThat(repository.findByKey("soft-delete-me")).isNull()
        assertThat(countRows("SELECT COUNT(*) FROM workflows WHERE key = 'soft-delete-me'"))
            .describedAs("행이 물리 삭제됐다 — 소프트 삭제여야 한다")
            .isEqualTo(1)
    }

    @Test
    fun `소프트 삭제한 key 로 다시 만들 수 있다`() {
        create("recreate-me")
        service.delete(actor, "recreate-me")

        create("recreate-me", name = "부활")

        assertThat(repository.findByKey("recreate-me")?.name).isEqualTo("부활")
    }

    @Test
    fun `없는 워크플로우 수정은 404 예외다`() {
        assertThatThrownBy {
            service.update(actor, "ghost-flow", UpdateWorkflowCommand(name = "x", description = null))
        }.isInstanceOf(WorkflowNotFoundException::class.java)
    }

    @Test
    fun `잠긴 워크플로우는 수정할 수 없다`() {
        create("locked-flow")
        lockWorkflow("locked-flow")

        assertThatThrownBy {
            service.update(actor, "locked-flow", UpdateWorkflowCommand(name = "x", description = null))
        }.isInstanceOf(WorkflowLockedException::class.java)
    }

    @Test
    fun `복제는 상태 편성을 함께 복사하고 origin 을 CUSTOM 으로 둔다`() {
        create("origin-flow", statuses = seeds("alpha", "beta", "gamma"))

        service.duplicate(actor, "origin-flow", newKey = "copied-flow", newName = "복사본")

        val copy = repository.findByKey("copied-flow")
        assertThat(copy).isNotNull()
        assertThat(copy!!.states.map { it.key }).containsExactly("alpha", "beta", "gamma")
        assertThat(originOf("copied-flow")).isEqualTo("CUSTOM")
    }

    @Test
    fun `이미 있는 key 로 복제하면 거부된다`() {
        create("src-flow")
        create("taken-flow")

        assertThatThrownBy { service.duplicate(actor, "src-flow", newKey = "taken-flow", newName = "x") }
            .isInstanceOf(WorkflowKeyConflictException::class.java)
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    private fun attachSchemeMapping(workflowId: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val schemeId =
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, "scheme-$workflowId")
                    stmt.setString(2, "참조 스킴")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            conn.prepareStatement(
                "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)" +
                    " VALUES (?, NULL, ?)",
            ).use { stmt ->
                stmt.setObject(1, schemeId)
                stmt.setObject(2, workflowId)
                stmt.executeUpdate()
            }
        }
    }

    private fun lockWorkflow(key: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { it.executeUpdate("UPDATE workflows SET is_locked = TRUE WHERE key = '$key'") }
        }
    }

    private fun originOf(key: String): String =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT origin FROM workflows WHERE key = '$key' AND deleted_at IS NULL").use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    private fun countRows(sql: String): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
