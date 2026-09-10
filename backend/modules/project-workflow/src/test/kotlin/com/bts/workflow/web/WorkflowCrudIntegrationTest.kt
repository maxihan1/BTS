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
import com.bts.workflow.domain.exception.WorkflowInvalidRequestException
import com.bts.workflow.domain.exception.WorkflowKeyConflictException
import com.bts.workflow.domain.exception.WorkflowLockedException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.repository.WorkflowWriteRepository
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
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

        /** 소유 프로젝트 UUID — V209 는 FK 가 없으므로(cross-BC) `projects` 행 없이도 실린다. */
        val ATLAS_ID: UUID = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000a1")

        /** `ATLAS` 하나만 아는 프로젝트 조회 스텁. 나머지 키는 없는 프로젝트로 취급한다. */
        private val projectLookup =
            object : ProjectLookupPort {
                override fun findIdByKey(projectKey: ProjectKey): UUID? {
                    return if (projectKey.value == "ATLAS") ATLAS_ID else null
                }

                override fun findKeyById(projectId: UUID): ProjectKey? {
                    return if (projectId == ATLAS_ID) ProjectKey("ATLAS") else null
                }
            }

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
            service =
                WorkflowCommandService(WorkflowWriteRepository(dsl), repository, cache, allowAll, projectLookup)
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
        keys.mapIndexed { i, k ->
            WorkflowStatusSeed(key = k, name = k.uppercase(), category = "TODO", displayOrder = i)
        }

    private fun create(
        key: String,
        name: String = key,
        statuses: List<WorkflowStatusSeed> = seeds("open", "done"),
    ) = service.create(
        actor,
        CreateWorkflowCommand(
            key = key,
            name = name,
            description = null,
            statuses = statuses,
            projectKey = null,
        ),
    )

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
            // ★ IllegalArgumentException 이 아니라 전용 예외다 — 전역 advice 가 그 타입을 잡으면
            //   다른 BC 의 require() 실패까지 400 으로 둔갑한다(코드리뷰 렌즈 2 지적).
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
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

        service.duplicate(actor, "origin-flow", newKey = "copied-flow", newName = "복사본", targetProjectKey = null)

        val copy = repository.findByKey("copied-flow")
        assertThat(copy).isNotNull()
        assertThat(copy!!.states.map { it.key }).containsExactly("alpha", "beta", "gamma")
        assertThat(originOf("copied-flow")).isEqualTo("CUSTOM")
    }

    // ── FR-WF-08 소유 프로젝트 ────────────────────────────────────────────────
    // ★ 복제가 주 사용 경로다. 사본이 소유를 못 실으면 전역 사본이 되어 프로젝트 관리자가 결국
    // 못 고친다 — Jira 가 권장 우회로로 명시하는 그 경로가 그 자리에서 죽는다.

    @Test
    fun `복제 사본은 지목한 프로젝트를 소유로 싣는다`() {
        create("owned-src")

        service.duplicate(actor, "owned-src", newKey = "owned-copy", newName = "사본", targetProjectKey = "ATLAS")

        assertThat(projectIdOf("owned-copy")).isEqualTo(ATLAS_ID)
        // 원본은 전역 그대로다 — 복제가 원본 소유를 옮기면 안 된다.
        assertThat(projectIdOf("owned-src")).isNull()
    }

    @Test
    fun `프로젝트를 지목하지 않은 복제는 전역 사본이다`() {
        create("global-src")

        service.duplicate(actor, "global-src", newKey = "global-copy", newName = "사본", targetProjectKey = null)

        assertThat(projectIdOf("global-copy")).isNull()
    }

    @Test
    fun `전역에 같은 key 가 있어도 프로젝트 사본은 만들어진다`() {
        // V209 가 key 유일성을 소유별로 갈랐다. 소유를 무시하고 물으면 전역에 있다는 이유로
        // 프로젝트 사본이 409 로 막힌다 — 「전역 템플릿을 같은 이름으로 내 프로젝트에」가 안 된다.
        create("shared-key")

        service.duplicate(actor, "shared-key", newKey = "shared-key", newName = "내 사본", targetProjectKey = "ATLAS")

        assertThat(projectIdOf("shared-key")).isNull()
        assertThat(countRows("SELECT COUNT(*) FROM workflows WHERE key = 'shared-key' AND deleted_at IS NULL"))
            .isEqualTo(2)
    }

    @Test
    fun `없는 프로젝트로 복제하면 거부된다`() {
        create("nowhere-src")

        assertThatThrownBy {
            service.duplicate(actor, "nowhere-src", newKey = "nowhere-copy", newName = "x", targetProjectKey = "GHOST")
        }.isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    @Test
    fun `이미 있는 key 로 복제하면 거부된다`() {
        create("src-flow")
        create("taken-flow")

        assertThatThrownBy {
            service.duplicate(actor, "src-flow", newKey = "taken-flow", newName = "x", targetProjectKey = null)
        }.isInstanceOf(WorkflowKeyConflictException::class.java)
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    private fun attachSchemeMapping(workflowId: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val schemeId =
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, "sch-" + workflowId.toString().take(8))
                    stmt.setString(2, "참조 스킴")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        // workflow_schemes.id 는 BIGSERIAL 이다(V201:24). workflows 만 UUID 다.
                        rs.getLong(1)
                    }
                }
            conn.prepareStatement(
                "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)" +
                    " VALUES (?, NULL, ?)",
            ).use { stmt ->
                stmt.setLong(1, schemeId)
                stmt.setObject(2, workflowId)
                stmt.executeUpdate()
            }
        }
    }

    private fun lockWorkflow(key: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE workflows SET is_locked = TRUE WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeUpdate()
            }
        }
    }

    /** 소유 프로젝트를 DB 에서 직접 읽는다 — 도메인을 거치면 매핑 누락이 가려진다. */
    private fun projectIdOf(key: String): UUID? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT project_id FROM workflows WHERE key = ? AND deleted_at IS NULL ORDER BY project_id NULLS FIRST",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID?
                }
            }
        }

    private fun originOf(key: String): String =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT origin FROM workflows WHERE key = ? AND deleted_at IS NULL").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
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
