// 전환 정의 CRUD 3종의 HTTP 계약 — 다중 전환 · 종류 검증 · 소속 대조 · 캐시 무효화 (FR-WF-05 C1)

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.application.WorkflowApplicationService
import com.bts.workflow.application.WorkflowCommandService
import com.bts.workflow.application.command.CreateWorkflowCommand
import com.bts.workflow.application.command.WorkflowStatusSeed
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.repository.WorkflowWriteRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * 전환 정의 CRUD(`POST`·`PUT`·`DELETE /api/v1/workflows/{key}/transitions`)의 HTTP 계약.
 *
 * ### 왜 목이 아니라 실제 DB 인가
 * 이 PR 이 여는 것은 「같은 상태쌍에 전환을 여럿」(C1)이고, 그것을 막고 있던 것은 **DB 유니크
 * 제약**이다(V207 ①). 리포지토리를 목으로 세우면 그 제약이 사라졌는지 아무것도 확인하지 못한 채
 * 초록이 된다. 전환의 출발·도착이 `workflow_statuses` 를 제대로 가리키는지도 마찬가지다.
 *
 * ### 검사 순서 — 없는 워크플로우는 404, 있는데 권한이 없으면 403
 * 두 가지를 **각각** 단언한다. 순서가 뒤집히면 오타가 권한 문제로 보이거나 그 반대가 된다
 * (MEMORY `permission-assert-before-existence-makes-403-lie`).
 */
@Testcontainers
class TransitionCrudMvcTest {
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

        lateinit var repository: WorkflowRepository
        lateinit var cache: WorkflowCache
        lateinit var commandService: WorkflowCommandService

        /** 판정을 테스트 중에 뒤집을 수 있는 권한 리졸버. 403 과 404 를 갈라 단언하려면 필요하다. */
        val permissionResolver = SwitchablePermissionResolver()

        @BeforeAll
        @JvmStatic
        fun setup() {
            migrate("200")
            createIssueTypes()
            migrate(null)

            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = WorkflowRepository(dsl)
            cache = WorkflowCache(repository, dsl)
            commandService =
                WorkflowCommandService(WorkflowWriteRepository(dsl), repository, cache, permissionResolver)
        }

        private fun migrate(target: String?) {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking", "classpath:db/migration/project-workflow")
                .let { if (target == null) it else it.target(target) }
                .load()
                .migrate()
        }

        /** V201 의 스킴 매핑이 `issue_types` 를 FK 참조한다. 그 테이블의 정본은 issue-tracking 이다. */
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

    private val actor: UUID = UUID.randomUUID()
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        permissionResolver.allow = true
        val controller =
            WorkflowController(
                mockk<WorkflowApplicationService>(relaxed = true),
                commandService,
                cache,
                permissionResolver,
            )
        mockMvc =
            MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(WorkflowExceptionHandler())
                .build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(actor.toString(), null, emptyList())
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── C1. 같은 상태쌍에 이름이 다른 전환 둘 ──────────────────────────────────

    @Test
    fun `POST 로 같은 상태쌍에 이름이 다른 전환을 하나 더 만든다`() {
        createWorkflow("multi-edge", "open", "done")

        createTransition("multi-edge", body(from = "open", to = "done", name = "완료"))
        createTransition("multi-edge", body(from = "open", to = "done", name = "조건부 승인"))

        val transitions = requireNotNull(repository.findByKey("multi-edge")).transitions
        assertThat(transitions.map { it.name })
            .describedAs("같은 상태쌍에 전환을 여럿 둘 수 있어야 한다 (V207 ① 유니크 해제)")
            .containsExactlyInAnyOrder("완료", "조건부 승인")
        assertThat(transitions.map { it.id }.toSet()).hasSize(2)
        assertThat(transitions.map { it.key }.toSet())
            .describedAs("key 는 하위호환 계산값이라 같아도 된다 — identity 는 id 다")
            .containsExactly("open__done")
    }

    // ── E3·E4. 종류와 출발지의 조합 ────────────────────────────────────────────

    @Test
    fun `kind=GLOBAL 인데 fromStatusKey 를 보내면 400`() {
        createWorkflow("global-edge", "open", "done")

        mockMvc.perform(postTransition("global-edge", body(from = "open", to = "done", name = "전역", kind = "GLOBAL")))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `kind=NORMAL 인데 fromStatusKey 가 없으면 400`() {
        createWorkflow("normal-edge", "open", "done")

        mockMvc.perform(postTransition("normal-edge", body(to = "done", name = "출발지 없음")))
            .andExpect(status().isBadRequest)
    }

    // ── E5·E2. 최초 전환 ───────────────────────────────────────────────────────

    @Test
    fun `INITIAL 전환 DELETE 는 409`() {
        createWorkflow("initial-edge", "open", "done")
        val initialId = createTransition("initial-edge", body(to = "open", name = "이슈 생성", kind = "INITIAL"))

        mockMvc.perform(delete("/api/v1/workflows/initial-edge/transitions/$initialId"))
            .andExpect(status().isConflict)
    }

    @Test
    fun `INITIAL 전환을 두 개째 만들면 409`() {
        createWorkflow("initial-dup", "open", "done")
        createTransition("initial-dup", body(to = "open", name = "이슈 생성", kind = "INITIAL"))

        mockMvc.perform(postTransition("initial-dup", body(to = "done", name = "또 다른 생성", kind = "INITIAL")))
            .andExpect(status().isConflict)
    }

    // ── E9. 소속 대조 ──────────────────────────────────────────────────────────

    @Test
    fun `다른 워크플로우의 transitionId 로 PUT 하면 404`() {
        createWorkflow("owner-flow", "open", "done")
        createWorkflow("stranger-flow", "open", "done")
        val transitionId = createTransition("owner-flow", body(from = "open", to = "done", name = "완료"))

        // 비-공허 짝. 주인 경로가 200 이어야 아래 404 가 「매핑 부재」가 아니라 소속 대조의 결과다.
        mockMvc.perform(putTransition("owner-flow", transitionId, body(from = "open", to = "done", name = "완료됨")))
            .andExpect(status().isOk)

        mockMvc.perform(putTransition("stranger-flow", transitionId, body(from = "open", to = "done", name = "탈취")))
            .andExpect(status().isNotFound)
    }

    // ── E7. 소프트 삭제된 워크플로우 ───────────────────────────────────────────

    @Test
    fun `소프트 삭제된 워크플로우의 전환 생성은 404`() {
        createWorkflow("erased-flow", "open", "done")
        commandService.delete(actor, "erased-flow")

        mockMvc.perform(postTransition("erased-flow", body(from = "open", to = "done", name = "완료")))
            .andExpect(status().isNotFound)
    }

    // ── 검사 순서. 없으면 404 · 있는데 권한이 없으면 403 ───────────────────────

    @Test
    fun `권한 없는 액터는 403 — 존재하지 않는 워크플로우는 404`() {
        createWorkflow("guarded-flow", "open", "done")

        // 있는 워크플로우인데 권한이 없으면 403.
        permissionResolver.allow = false
        mockMvc.perform(postTransition("guarded-flow", body(from = "open", to = "done", name = "완료")))
            .andExpect(status().isForbidden)

        // 없는 워크플로우는 404. 403 이면 오타가 권한 문제로 보인다.
        permissionResolver.allow = true
        mockMvc.perform(postTransition("ghost-flow", body(from = "open", to = "done", name = "완료")))
            .andExpect(status().isNotFound)
    }

    // ── N5. 쓰기 3종 모두 캐시를 무효화한다 ────────────────────────────────────

    @Test
    fun `전환 CRUD 3종이 워크플로우 캐시를 무효화한다`() {
        createWorkflow("cached-flow", "open", "done")
        assertThat(cache.findByKey("cached-flow")?.transitions).isEmpty()

        val transitionId = createTransition("cached-flow", body(from = "open", to = "done", name = "완료"))
        assertThat(cache.findByKey("cached-flow")?.transitions)
            .describedAs("생성이 캐시에 반영되지 않았다 — 편집이 런타임 전환 계산에 닿지 않는다")
            .hasSize(1)

        mockMvc.perform(putTransition("cached-flow", transitionId, body(from = "open", to = "done", name = "완료됨")))
            .andExpect(status().isOk)
        assertThat(cache.findByKey("cached-flow")?.transitions?.map { it.name })
            .describedAs("수정이 캐시에 반영되지 않았다")
            .containsExactly("완료됨")

        mockMvc.perform(delete("/api/v1/workflows/cached-flow/transitions/$transitionId"))
            .andExpect(status().isNoContent)
        assertThat(cache.findByKey("cached-flow")?.transitions)
            .describedAs("삭제가 캐시에 반영되지 않았다")
            .isEmpty()
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    private fun createWorkflow(
        key: String,
        vararg statusKeys: String,
    ) {
        commandService.create(
            actor,
            CreateWorkflowCommand(
                key = key,
                name = key,
                description = null,
                statuses =
                    statusKeys.mapIndexed { index, statusKey ->
                        WorkflowStatusSeed(statusKey, statusKey.uppercase(), "TODO", index)
                    },
            ),
        )
    }

    /** 전환을 만들고 그 id 를 준다. 201 이 아니면 그 자리에서 실패한다. */
    private fun createTransition(
        workflowKey: String,
        payload: String,
    ): UUID {
        val response =
            mockMvc.perform(postTransition(workflowKey, payload))
                .andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString
        return UUID.fromString(mapper.readTree(response).path("data").path("id").asText())
    }

    private fun postTransition(
        workflowKey: String,
        payload: String,
    ) = post("/api/v1/workflows/$workflowKey/transitions")
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload)

    private fun putTransition(
        workflowKey: String,
        transitionId: UUID,
        payload: String,
    ) = put("/api/v1/workflows/$workflowKey/transitions/$transitionId")
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload)

    /** 전환 정의 요청 바디. 보내지 않은 필드는 **키 자체를 빼서** 생략과 null 을 구분한다. */
    private fun body(
        to: String,
        name: String,
        from: String? = null,
        kind: String? = null,
    ): String {
        val fields = mutableMapOf<String, Any?>("toStatusKey" to to, "name" to name)
        if (from != null) fields["fromStatusKey"] = from
        if (kind != null) fields["kind"] = kind
        return mapper.writeValueAsString(fields)
    }
}

/** 판정을 뒤집을 수 있는 권한 리졸버. 거부는 운영 리졸버와 같은 예외로 낸다. */
class SwitchablePermissionResolver : WorkflowDefinitionPermissionResolver {
    var allow: Boolean = true

    override fun requirePermission(
        actorId: UUID,
        permission: WorkflowDefinitionPermission,
    ) {
        if (!allow) throw WorkflowDefinitionAccessDeniedException(actorId, permission)
    }
}
