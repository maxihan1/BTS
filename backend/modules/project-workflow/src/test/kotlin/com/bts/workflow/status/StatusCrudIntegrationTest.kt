// 전역 상태 카탈로그 CRUD 통합 검증 — 키 불변·이름 유일·참조 가드·소프트삭제

package com.bts.workflow.status

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.status.application.StatusCommandService
import com.bts.workflow.status.application.command.CreateStatusCommand
import com.bts.workflow.status.application.command.UpdateStatusCommand
import com.bts.workflow.status.domain.exception.StatusInUseException
import com.bts.workflow.status.domain.exception.StatusKeyConflictException
import com.bts.workflow.status.domain.exception.StatusNameConflictException
import com.bts.workflow.status.domain.exception.StatusNotFoundException
import com.bts.workflow.status.domain.exception.StatusProtectedException
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
 * 전역 상태 카탈로그의 쓰기 계약을 못박는다.
 *
 * ### 키 불변이 이 카탈로그의 핵심 계약이다
 * `issues.current_state_key` · `board_columns.state_key` 등이 **FK 없이 문자열로** 참조한다
 * (`V203` 주석). key 가 바뀌면 그 참조들이 조용히 끊긴다. 그래서 수정 명령에 `key` 자리가 아예 없다.
 *
 * ### 삭제는 두 겹으로 막힌다
 * `workflow_statuses.status_id` 가 **FK RESTRICT** 라 DB 가 먼저 막고, 그 예외를 409 로 번역한다.
 * 소프트 삭제라 DB 가 개입하지 않는 경우를 대비해 애플리케이션도 참조를 센다.
 */
@Testcontainers
class StatusCrudIntegrationTest {
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
        lateinit var statusRepository: StatusRepository
        lateinit var cache: WorkflowCache
        lateinit var service: StatusCommandService

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
            statusRepository = StatusRepository(dsl)
            cache = WorkflowCache(WorkflowRepository(dsl), dsl)
            service = StatusCommandService(statusRepository, cache, allowAll)
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

    private val actor = UUID.randomUUID()

    private fun create(
        key: String,
        name: String = key,
        category: String = "TODO",
    ) = service.create(actor, CreateStatusCommand(key = key, name = name, description = null, category = category))

    // ── red-first. key 불변 ────────────────────────────────────────────────────

    @Test
    fun `수정 명령에는 key 자리가 없다`() {
        // 컴파일 타임 계약이다. UpdateStatusCommand 에 key 필드가 생기면 이 테스트가 깨진다 —
        // 「받지 않는다」를 런타임 검증이 아니라 타입으로 못박는 것이 본질 차단이다.
        val fields = UpdateStatusCommand::class.java.declaredFields.map { it.name }

        assertThat(fields)
            .describedAs("상태 key 는 이슈·보드가 문자열로 참조한다. 수정 API 가 key 를 받으면 그 참조가 조용히 끊긴다")
            .doesNotContain("key")
        assertThat(fields).contains("name", "description", "category")
    }

    @Test
    fun `이름과 카테고리는 고칠 수 있고 key 는 그대로다`() {
        val id = create("mutable-status", name = "옛 이름", category = "TODO")

        // ★ 이름은 카탈로그 **전역**에서 유일하다. 테스트마다 다른 이름을 써야 서로 충돌하지 않는다 —
        //   실제로 한 번 충돌시켜 보고 알았다. 이 전역성이 카탈로그의 본질이다.
        service.update(actor, id, UpdateStatusCommand(name = "고쳐진 이름", description = "설명", category = "DONE"))

        val found = statusRepository.findLiveById(id)!!
        assertThat(found.key).isEqualTo("mutable-status")
        assertThat(found.name).isEqualTo("고쳐진 이름")
        assertThat(found.category).isEqualTo("DONE")
    }

    // ── 이름 유일 (E13) ────────────────────────────────────────────────────────

    @Test
    fun `이름이 대소문자만 다른 상태는 만들 수 없다`() {
        create("first-blocked", name = "Blocked")

        assertThatThrownBy { create("second-blocked", name = "BLOCKED") }
            .describedAs("uq_statuses_lower_name — 같은 이름이 둘이면 전환을 걸 때 어느 쪽인지 구분할 수 없다")
            .isInstanceOf(StatusNameConflictException::class.java)
    }

    // ── 참조 가드 (E12) ────────────────────────────────────────────────────────

    @Test
    fun `워크플로우가 쓰고 있는 상태는 지울 수 없다`() {
        val id = create("referenced-status")
        attachToWorkflow(id, "wf-using-status")

        assertThatThrownBy { service.delete(actor, id) }
            .isInstanceOf(StatusInUseException::class.java)
    }

    @Test
    fun `아무 워크플로우도 안 쓰는 상태는 지울 수 있다`() {
        val id = create("orphan-status")

        service.delete(actor, id)

        assertThat(statusRepository.findLiveById(id)).isNull()
    }

    // ── 소프트 삭제 (E15 · E17) ────────────────────────────────────────────────

    @Test
    fun `삭제는 소프트 삭제이고 행은 남는다`() {
        val id = create("soft-status")

        service.delete(actor, id)

        assertThat(statusRepository.findLiveById(id)).isNull()
        assertThat(countRows("SELECT COUNT(*) FROM statuses WHERE key = 'soft-status'")).isEqualTo(1)
    }

    @Test
    fun `소프트 삭제한 key 로 다시 만들 수 있다`() {
        val id = create("revive-status", name = "지워질 상태")
        service.delete(actor, id)

        val newId = create("revive-status", name = "되살아난 상태")

        assertThat(newId).isNotEqualTo(id)
        assertThat(statusRepository.findLiveById(newId)?.name).isEqualTo("되살아난 상태")
    }

    @Test
    fun `소프트 삭제된 상태는 목록에 없다`() {
        val id = create("hidden-status")
        service.delete(actor, id)

        assertThat(statusRepository.findAllLive().map { it.key }).doesNotContain("hidden-status")
    }

    // ── key 중복 · 부재 ────────────────────────────────────────────────────────

    @Test
    fun `살아 있는 상태와 같은 key 로는 만들 수 없다`() {
        create("dup-status")

        assertThatThrownBy { create("dup-status", name = "다른 이름") }
            .isInstanceOf(StatusKeyConflictException::class.java)
    }

    @Test
    fun `없는 상태를 수정하면 404 예외다`() {
        assertThatThrownBy {
            service.update(actor, UUID.randomUUID(), UpdateStatusCommand("x", null, "TODO"))
        }.isInstanceOf(StatusNotFoundException::class.java)
    }

    @Test
    fun `시스템 예약 상태는 지울 수 없다`() {
        val id = create("system-status")
        markSystem(id)

        assertThatThrownBy { service.delete(actor, id) }
            .describedAs("statuses.is_system 은 「사용자가 지울 수 없음」을 뜻한다(V203 주석)")
            .isInstanceOf(StatusProtectedException::class.java)
    }

    // ── 캐시 무효화 ────────────────────────────────────────────────────────────

    @Test
    fun `상태를 고치면 그 상태를 쓰는 워크플로우 캐시가 무효화된다`() {
        val id = create("cached-status", name = "옛 이름")
        attachToWorkflow(id, "wf-cache-check")
        assertThat(cache.findByKey("wf-cache-check")?.states?.map { it.name }).contains("옛 이름")

        service.update(actor, id, UpdateStatusCommand(name = "새 이름", description = null, category = "TODO"))

        assertThat(cache.findByKey("wf-cache-check")?.states?.map { it.name })
            .describedAs("상태 변경이 워크플로우 캐시에 반영되지 않았다 — 역조회 무효화가 빠졌다")
            .contains("새 이름")
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    private fun attachToWorkflow(
        statusId: UUID,
        workflowKey: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val workflowId =
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES (?, ?)" +
                        " ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL" +
                        " DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, workflowKey)
                    stmt.setString(2, workflowKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            conn.prepareStatement(
                "INSERT INTO workflow_statuses (workflow_id, status_id, display_order) VALUES (?, ?, 0)" +
                    " ON CONFLICT (workflow_id, status_id) DO NOTHING",
            ).use { stmt ->
                stmt.setObject(1, workflowId)
                stmt.setObject(2, statusId)
                stmt.executeUpdate()
            }
        }
    }

    private fun markSystem(statusId: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE statuses SET is_system = TRUE WHERE id = ?").use { stmt ->
                stmt.setObject(1, statusId)
                stmt.executeUpdate()
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
