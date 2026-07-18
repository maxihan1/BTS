// FR-EP-01 Task 6 — IssueEpicController end-to-end 통합 테스트 (Testcontainers + 전 스택)
@file:Suppress("MaxLineLength")

package com.bts.issue.epic.web

import com.bts.issue.epic.application.IssueEpicService
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.workflow.WorkflowStateCatalog
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * FR-EP-01 Task 6 — IssueEpicController end-to-end 통합 테스트.
 *
 * 실제 Testcontainers PostgreSQL + 전체 스택(Controller → Service → Repository) 위에서 동작한다.
 *
 * ## 보안 C1 단언 방침
 * 테스트 환경의 [IssuePermissionResolver]는 AlwaysAllow stub이므로 보안 등급(securityLevel)에 따른
 * VIEW 제한을 E2E로 단언할 수 없다. 보안 등급 필터(accessibleLevels)는
 * [IssueRepository.findEpicChildren]의 단위 테스트(IssueRepositorySecurityTest)에서 커버되며,
 * BROWSE 게이트 + accessibleLevels 결합 검증은 IssueEpicServiceTest(MockK)에서 커버된다.
 * 이 통합 테스트에서는 소프트삭제 자식 미노출 + 정상 자식 노출을 단언한다 (S7).
 *
 * ## 403 검증 방침
 * 테스트 환경 stub([AlwaysAllowPermissionResolver])이 항상 true를 반환하므로
 * 403(UPDATE 권한 없음)을 전 스택 통합으로 유도할 수 없다.
 * 403 경로는 IssueEpicServiceTest(MockK 단위)에서 checkUpdatePermission → IssueAccessDeniedException으로 커버된다.
 * 예외 핸들러의 403 매핑은 S9에서 EpicChildExceptionHandler.handleAccessDenied 호출 경로를 통해
 * MockMvc 슬라이스(WatcherExceptionHandler 동형)로 검증할 수 있으나 별도 슬라이스 테스트 파일이 없다.
 *
 * ## 검증 시나리오
 * - S1. POST /epic-children 201 — 연결 성공 + EpicChildSummaryResponse
 * - S2. POST /epic-children 409 — 이미 연결됨 (ISSUE_EPIC_CHILD_ALREADY_LINKED)
 * - S3. POST /epic-children 422 — 자기 참조 (ISSUE_EPIC_CHILD_SELF_REFERENCE)
 * - S4. POST /epic-children 422 — 대상이 에픽이 아님 (ISSUE_EPIC_TARGET_NOT_EPIC)
 * - S5. POST /epic-children 422 — child 유형 오류 (ISSUE_EPIC_CHILD_INVALID_TYPE)
 * - S6. POST /epic-children 422 — 다른 프로젝트 (ISSUE_EPIC_CHILD_CROSS_PROJECT)
 * - S7. GET /epic-children 200 — 자식 목록 + 소프트삭제 자식 미포함 단언
 * - S8. DELETE /epic-children/{childKey} 204 — 연결 해제 성공
 * - S9. POST /epic-children 404 — epic 미존재 (ISSUE_EPIC_OR_CHILD_NOT_FOUND)
 * - S10. POST /epic-children 400 — childKey 공백 (Bean Validation)
 * - S11. 미인증 요청 → 401 (CurrentActor 게이트)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueEpicControllerIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueEpicControllerIntegrationTest {
    /**
     * 테스트 환경 항상-허용 [IssuePermissionResolver] stub.
     * prod 구현(identity-access)이 없는 통합 테스트 컨텍스트용 fail-safe 대역.
     */
    private class AlwaysAllowPermissionResolver : IssuePermissionResolver {
        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean = true
    }

    /**
     * 테스트 환경 항상-비제한 [IssueSecurityDirectory] stub.
     * accessibleLevels = unrestricted 를 반환하여 보안 등급 SQL 푸시다운을 비활성화한다.
     */
    private class AlwaysAllowSecurityDirectory : IssueSecurityDirectory {
        override fun levelBelongsToProjectScheme(
            levelId: UUID,
            projectKey: String,
        ): Boolean = true

        override fun accessibleLevels(
            actorId: UUID,
            projectKey: String,
        ): IssueSecurityAccess =
            IssueSecurityAccess(
                unrestricted = true,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )
    }

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_ep_test")
                    .withUsername("bts")
                    .withPassword("bts_ep_test")
                    .apply { start() }
        }

        @Bean
        open fun dataSource(): DriverManagerDataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )

        @Bean
        open fun transactionManager(dataSource: DriverManagerDataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext {
            return DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

        @Bean
        open fun issueTypeRepository(dsl: DSLContext): IssueTypeRepository = IssueTypeRepository(dsl)

        /**
         * IssueHistoryRecorder — epicId 변경 이력 기록에 사용되나,
         * 이 통합 테스트는 이력 기록 자체보다 HTTP 계약 검증이 목적이므로 relaxed mock 사용.
         * 실제 이력 기록은 IssueMoveHistoryIntegrationTest 패턴에서 커버됨.
         */
        @Bean
        open fun issueHistoryRecorder(): IssueHistoryRecorder = mockk(relaxed = true)

        @Bean
        open fun permissionResolver(): IssuePermissionResolver = AlwaysAllowPermissionResolver()

        @Bean
        open fun securityDirectory(): IssueSecurityDirectory = AlwaysAllowSecurityDirectory()

        @Bean
        open fun workflowStateCatalog(): WorkflowStateCatalog = mockk(relaxed = true)

        /** FR-PJ-04 PR-4 Task 9 — 실 ProjectArchiveGuard(공유 dsl 위). */
        @Bean
        open fun projectArchiveGuard(dsl: DSLContext): ProjectArchiveGuard =
            ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))

        @Bean
        open fun issueEpicService(
            permissionResolver: IssuePermissionResolver,
            securityDirectory: IssueSecurityDirectory,
            issueRepository: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            issueHistoryRecorder: IssueHistoryRecorder,
            workflowStateCatalog: WorkflowStateCatalog,
            archiveGuard: ProjectArchiveGuard,
        ): IssueEpicService =
            IssueEpicService(
                permissionResolver,
                securityDirectory,
                issueRepository,
                issueTypeRepository,
                issueHistoryRecorder,
                workflowStateCatalog,
                archiveGuard,
            )

        @Bean
        open fun issueEpicController(
            service: IssueEpicService,
            issueRepository: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
        ): IssueEpicController = IssueEpicController(service, issueRepository, issueTypeRepository)

        @Bean
        open fun epicChildExceptionHandler(): EpicChildExceptionHandler = EpicChildExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper =
        ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        private const val PROJECT_KEY = "EPTEST"
        private const val OTHER_PROJECT_KEY = "EPOTHER"
        private var migrated = false
        private var seeded = false

        /** issue_types.id — epic 타입 (hierarchyLevel=1) */
        private var epicTypeId: Long = -1L

        /** issue_types.id — task 타입 (hierarchyLevel=0) */
        private var taskTypeId: Long = -1L

        /** issue_types.id — epic 이 아닌 hierarchyLevel=1 타입이 없으면 epicTypeId 와 구분. subtask(hierarchyLevel=-1) */
        private var subtaskTypeId: Long = -1L

        private var testProjectId: UUID = UUID.randomUUID()
        private var otherProjectId: UUID = UUID.randomUUID()

        /** 테스트 행위자 UUID. */
        val actorUuid: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProjectsAndTypes()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // 인증 컨텍스트 설정
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorUuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        // 이전 테스트 데이터 정리
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues WHERE project_id IN ('$testProjectId', '$otherProjectId')")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key IN ('$PROJECT_KEY', '$OTHER_PROJECT_KEY')")
            }
        }
    }

    // ── S1. POST /epic-children 201 — 연결 성공 ──────────────────────────────

    /**
     * S1. epic에 task 자식 연결 → 201 Created + EpicChildSummaryResponse.
     *
     * Given  epic 이슈(EPTEST-1)와 task 이슈(EPTEST-2) 존재
     * When   POST /api/v1/issues/EPTEST-1/epic-children body={childKey: "EPTEST-2"}
     * Then   201 Created, data.key=EPTEST-2, data.summary, data.typeKey, data.currentStateKey 포함
     */
    @Test
    fun `S1 POST epic-children 201 - 에픽 자식 연결 성공`() {
        val epicKey = createIssue("에픽 이슈", epicTypeId)
        val childKey = createIssue("자식 이슈", taskTypeId)

        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to childKey))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.key").value(childKey))
            .andExpect(jsonPath("$.data.summary").value("자식 이슈"))
            .andExpect(jsonPath("$.data.typeKey").isNotEmpty)
            .andExpect(jsonPath("$.data.currentStateKey").value("open"))
    }

    // ── S2. POST /epic-children 409 — 이미 연결됨 ───────────────────────────

    /**
     * S2. 이미 에픽에 연결된 자식을 다시 연결 → 409 Conflict.
     *
     * Given  EPTEST-2가 EPTEST-1에 이미 연결됨
     * When   POST /api/v1/issues/EPTEST-1/epic-children body={childKey: "EPTEST-2"}
     * Then   409, errorCode=ISSUE_EPIC_CHILD_ALREADY_LINKED
     */
    @Test
    fun `S2 POST epic-children 409 - 이미 연결된 자식 ISSUE_EPIC_CHILD_ALREADY_LINKED`() {
        val epicKey = createIssue("에픽", epicTypeId)
        val childKey = createIssue("자식", taskTypeId)
        // 첫 연결
        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to childKey))),
        ).andExpect(status().isCreated)

        // 중복 연결
        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to childKey))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_EPIC_CHILD_ALREADY_LINKED"))
    }

    // ── S3. POST /epic-children 422 — 자기 참조 ─────────────────────────────

    /**
     * S3. 에픽 자신을 자식으로 연결 → 422 Unprocessable Entity.
     *
     * Given  EPTEST-1(epic) 존재
     * When   POST /api/v1/issues/EPTEST-1/epic-children body={childKey: "EPTEST-1"}
     * Then   422, errorCode=ISSUE_EPIC_CHILD_SELF_REFERENCE
     */
    @Test
    fun `S3 POST epic-children 422 - 자기 참조 ISSUE_EPIC_CHILD_SELF_REFERENCE`() {
        val epicKey = createIssue("에픽", epicTypeId)

        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to epicKey))),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_EPIC_CHILD_SELF_REFERENCE"))
    }

    // ── S4. POST /epic-children 422 — 대상이 에픽이 아님 ────────────────────

    /**
     * S4. epic 자리에 task(hierarchyLevel=0)를 지정 → 422.
     *
     * Given  EPTEST-1(task), EPTEST-2(task) 존재
     * When   POST /api/v1/issues/EPTEST-1/epic-children body={childKey: "EPTEST-2"}
     *         (EPTEST-1 이 task 이므로 epic 이 아님)
     * Then   422, errorCode=ISSUE_EPIC_TARGET_NOT_EPIC
     */
    @Test
    fun `S4 POST epic-children 422 - 대상이 에픽이 아님 ISSUE_EPIC_TARGET_NOT_EPIC`() {
        val notEpicKey = createIssue("task 이슈 — epic 아님", taskTypeId)
        val childKey = createIssue("자식 이슈", taskTypeId)

        mockMvc.perform(
            post("/api/v1/issues/$notEpicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to childKey))),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_EPIC_TARGET_NOT_EPIC"))
    }

    // ── S5. POST /epic-children 422 — child 유형 오류 ───────────────────────

    /**
     * S5. child가 subtask(hierarchyLevel=-1 또는 != 0) → 422.
     *
     * Given  EPTEST-1(epic), EPTEST-2(subtask) 존재
     * When   POST /api/v1/issues/EPTEST-1/epic-children body={childKey: "EPTEST-2"}
     * Then   422, errorCode=ISSUE_EPIC_CHILD_INVALID_TYPE
     */
    @Test
    fun `S5 POST epic-children 422 - 잘못된 자식 유형 ISSUE_EPIC_CHILD_INVALID_TYPE`() {
        val epicKey = createIssue("에픽", epicTypeId)
        val subtaskKey = createIssue("서브태스크", subtaskTypeId)

        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to subtaskKey))),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_EPIC_CHILD_INVALID_TYPE"))
    }

    // ── S6. POST /epic-children 422 — 다른 프로젝트 ─────────────────────────

    /**
     * S6. epic과 child가 다른 프로젝트 → 422.
     *
     * Given  EPTEST-1(epic, testProject), EPOTHER-1(task, otherProject)
     * When   POST /api/v1/issues/EPTEST-1/epic-children body={childKey: "EPOTHER-1"}
     * Then   422, errorCode=ISSUE_EPIC_CHILD_CROSS_PROJECT
     */
    @Test
    fun `S6 POST epic-children 422 - 다른 프로젝트 ISSUE_EPIC_CHILD_CROSS_PROJECT`() {
        val epicKey = createIssue("에픽", epicTypeId, testProjectId, PROJECT_KEY)
        val crossChildKey = createIssue("타 프로젝트 자식", taskTypeId, otherProjectId, OTHER_PROJECT_KEY)

        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to crossChildKey))),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_EPIC_CHILD_CROSS_PROJECT"))
    }

    // ── S7. GET /epic-children 200 — 자식 목록 ───────────────────────────────

    /**
     * S7. 에픽 자식 목록 조회 → 200 + 활성 자식만 포함.
     *
     * Given  EPTEST-1(epic)에 EPTEST-2(task) 연결, EPTEST-3(task) 소프트삭제
     * When   GET /api/v1/issues/EPTEST-1/epic-children
     * Then   200, children 배열에 EPTEST-2 포함, 소프트삭제된 EPTEST-3 미포함
     *
     * ## 보안 C1 단언
     * 보안 등급 필터(securityLevel)는 AlwaysAllow stub 환경에서 E2E 단언 불가.
     * 보안 등급 필터는 IssueRepository.findEpicChildren 단위 테스트(IssueRepositorySecurityTest)에서 커버.
     * 이 테스트는 소프트삭제 자식 미노출을 단언한다 (buildActiveSecureWhere의 deleted_at IS NULL 조건).
     */
    @Test
    fun `S7 GET epic-children 200 - 자식 목록 소프트삭제 미포함 단언`() {
        val epicKey = createIssue("에픽", epicTypeId)
        val activeChildKey = createIssue("활성 자식", taskTypeId)
        val softDeletedChildKey = createIssue("소프트삭제 자식", taskTypeId)

        // 활성 자식 연결
        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to activeChildKey))),
        ).andExpect(status().isCreated)

        // 소프트삭제 자식 연결 후 삭제
        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to softDeletedChildKey))),
        ).andExpect(status().isCreated)
        softDeleteIssue(softDeletedChildKey)

        // 목록 조회
        mockMvc.perform(get("/api/v1/issues/$epicKey/epic-children"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.children").isArray)
            .andExpect(jsonPath("$.data.children.length()").value(1))
            .andExpect(jsonPath("$.data.children[0].key").value(activeChildKey))
    }

    // ── S8. DELETE /epic-children/{childKey} 204 ─────────────────────────────

    /**
     * S8. 에픽 자식 연결 해제 → 204 No Content.
     *
     * Given  EPTEST-1(epic)에 EPTEST-2 연결 상태
     * When   DELETE /api/v1/issues/EPTEST-1/epic-children/EPTEST-2
     * Then   204, 이후 GET 목록에서 제거됨
     */
    @Test
    fun `S8 DELETE epic-children 204 - 연결 해제 성공`() {
        val epicKey = createIssue("에픽", epicTypeId)
        val childKey = createIssue("자식", taskTypeId)

        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to childKey))),
        ).andExpect(status().isCreated)

        mockMvc.perform(delete("/api/v1/issues/$epicKey/epic-children/$childKey"))
            .andExpect(status().isNoContent)

        // 해제 후 목록에서 제거됨
        mockMvc.perform(get("/api/v1/issues/$epicKey/epic-children"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.children.length()").value(0))
    }

    // ── S9. POST /epic-children 404 — epic 미존재 ────────────────────────────

    /**
     * S9. 존재하지 않는 epic 키 → 404.
     *
     * Given  존재하지 않는 EPTEST-9999
     * When   POST /api/v1/issues/EPTEST-9999/epic-children body={childKey: "EPTEST-1"}
     * Then   404, errorCode=ISSUE_EPIC_OR_CHILD_NOT_FOUND
     */
    @Test
    fun `S9 POST epic-children 404 - 에픽 미존재 ISSUE_EPIC_OR_CHILD_NOT_FOUND`() {
        val childKey = createIssue("자식", taskTypeId)

        mockMvc.perform(
            post("/api/v1/issues/EPTEST-9999/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to childKey))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_EPIC_OR_CHILD_NOT_FOUND"))
    }

    // ── S10. POST /epic-children 400 — childKey 공백 ─────────────────────────

    /**
     * S10. childKey 공백 body → 400 Bad Request (Bean Validation).
     *
     * Given  body={childKey: ""}
     * When   POST /api/v1/issues/EPTEST-1/epic-children
     * Then   400 Bad Request
     */
    @Test
    fun `S10 POST epic-children 400 - childKey 공백 Bean Validation`() {
        val epicKey = createIssue("에픽", epicTypeId)

        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to ""))),
        )
            .andExpect(status().isBadRequest)
    }

    // ── S11. 미인증 요청 → 401 ────────────────────────────────────────────────

    /**
     * S11. SecurityContext 미인증 상태로 요청 → 401 Unauthorized.
     *
     * CurrentActor.current()가 SecurityContext 미인증 시 401 ResponseStatusException을 던지며,
     * EpicChildExceptionHandler.handleResponseStatus가 이를 전파한다.
     *
     * Given  SecurityContext 클리어 (인증 없음)
     * When   POST /api/v1/issues/EPTEST-1/epic-children
     * Then   401 Unauthorized
     */
    @Test
    fun `S11 미인증 요청 - 401 Unauthorized`() {
        SecurityContextHolder.clearContext()

        val epicKey = createIssue("에픽", epicTypeId)

        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to "EPTEST-99"))),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── S12. GET /epic-children 400 — malformed path key ─────────────────────

    /**
     * S12. 형식 위반 경로 키 → 400 Bad Request (C1 회귀 방지).
     *
     * IssueKey("bad_key") 생성 시 init 블록의 require() 가 IllegalArgumentException 을 throw한다.
     * EpicChildExceptionHandler 가 이를 ISSUE_EPIC_VALIDATION_FAILED 400 으로 변환해야 한다.
     * 핸들러 미등록 시 Spring 이 500 을 반환한다 (C1 버그).
     *
     * Given  형식 위반 경로 키 "bad_key"
     * When   GET /api/v1/issues/bad_key/epic-children
     * Then   400, errorCode=ISSUE_EPIC_VALIDATION_FAILED
     */
    @Test
    fun `S12 GET epic-children 400 - 형식 위반 path key IllegalArgumentException C1`() {
        mockMvc.perform(get("/api/v1/issues/bad_key/epic-children"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_EPIC_VALIDATION_FAILED"))
    }

    // ── S13. POST /epic-children 400 — malformed body childKey ───────────────

    /**
     * S13. 형식 위반 body childKey → 400 Bad Request (C1 회귀 방지).
     *
     * @NotBlank 는 통과("abc" 는 blank 아님)하지만
     * IssueKey("abc") 생성 시 init 블록의 require() 가 IllegalArgumentException 을 throw한다.
     * EpicChildExceptionHandler 가 이를 ISSUE_EPIC_VALIDATION_FAILED 400 으로 변환해야 한다.
     *
     * Given  body childKey="abc" (소문자 — IssueKey 형식 위반)
     * When   POST /api/v1/issues/EPTEST-1/epic-children body={childKey: "abc"}
     * Then   400, errorCode=ISSUE_EPIC_VALIDATION_FAILED
     */
    @Test
    fun `S13 POST epic-children 400 - 형식 위반 body childKey IllegalArgumentException C1`() {
        val epicKey = createIssue("에픽", epicTypeId)

        mockMvc.perform(
            post("/api/v1/issues/$epicKey/epic-children")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("childKey" to "abc"))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_EPIC_VALIDATION_FAILED"))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 이슈를 DB에 직접 삽입하고 이슈 키를 반환한다.
     *
     * @param summary 이슈 제목.
     * @param typeId 이슈 타입 BIGSERIAL id.
     * @param projectId 소속 프로젝트 UUID (기본: testProjectId).
     * @param projectKey 프로젝트 키 접두사 (기본: PROJECT_KEY).
     * @return 생성된 이슈 키 문자열.
     */
    @Suppress("NestedBlockDepth") // JDBC try-with-resources(conn→stmt→rs) 시드 보일러플레이트 — 테스트 1회성 setup
    private fun createIssue(
        summary: String,
        typeId: Long,
        projectId: UUID = testProjectId,
        projectKey: String = PROJECT_KEY,
    ): String {
        var key: String? = null
        conn().use { c ->
            c.prepareStatement(
                "UPDATE projects SET key_sequence = key_sequence + 1 WHERE id = ?",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.executeUpdate()
            }
            c.prepareStatement("SELECT key_sequence FROM projects WHERE id = ?").use { stmt ->
                stmt.setObject(1, projectId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    key = "$projectKey-${rs.getInt(1)}"
                }
            }
            c.prepareStatement(
                """
                INSERT INTO issues (project_id, key, summary, reporter_id, current_state_key, type_id, version)
                VALUES (?, ?, ?, '00000000-0000-0000-0000-000000000001', 'open', ?, 1)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, key)
                stmt.setString(3, summary)
                stmt.setLong(4, typeId)
                stmt.executeUpdate()
            }
        }
        return requireNotNull(key)
    }

    /** 이슈를 소프트 삭제한다 (deleted_at 설정). */
    private fun softDeleteIssue(key: String) {
        conn().use { c ->
            c.prepareStatement(
                "UPDATE issues SET deleted_at = NOW() WHERE key = ?",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.executeUpdate()
            }
        }
    }

    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(
                TestConfig.postgres.jdbcUrl,
                TestConfig.postgres.username,
                TestConfig.postgres.password,
            )
            .placeholderReplacement(false)
            .locations("classpath:db/migration/issue-tracking")
            .load()
            .migrate()
    }

    private fun seedProjectsAndTypes() {
        seedProjects()
        seedIssueTypes()
    }

    /** 기본 프로젝트와 cross-project 검증용 타 프로젝트를 삽입한다. */
    @Suppress("NestedBlockDepth") // JDBC try-with-resources(conn→stmt→rs) 시드 보일러플레이트 — 1회성 setup
    private fun seedProjects() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Epic Integration Test Project")
                stmt.executeUpdate()
            }
            c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    testProjectId = rs.getObject(1) as UUID
                }
            }
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, OTHER_PROJECT_KEY)
                stmt.setString(2, "Other Project")
                stmt.executeUpdate()
            }
            c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, OTHER_PROJECT_KEY)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    otherProjectId = rs.getObject(1) as UUID
                }
            }
        }
    }

    /**
     * epic/task/subtask 이슈 타입을 시드한다.
     *
     * V003/V005 마이그레이션이 이미 표준 타입을 삽입하므로 ON CONFLICT 없이 조회 우선 + 부재 시 삽입한다.
     */
    @Suppress("NestedBlockDepth") // JDBC try-with-resources(conn→stmt→rs) 시드 보일러플레이트 — 1회성 setup
    private fun seedIssueTypes() {
        conn().use { c ->
            epicTypeId = findOrInsertType(c, "epic", "Epic", 1)
            taskTypeId = findOrInsertType(c, "task", "Task", 0)
            subtaskTypeId = findOrInsertType(c, "subtask", "Subtask", -1)
        }
    }

    /**
     * 이슈 타입을 조회하거나 없으면 삽입하고 id 를 반환한다.
     *
     * @param c JDBC 커넥션.
     * @param key 이슈 타입 키.
     * @param name 이슈 타입 표시 이름.
     * @param hierarchyLevel 계층 레벨.
     * @return issue_types.id.
     */
    @Suppress("NestedBlockDepth") // JDBC try-with-resources — 1회성 setup
    private fun findOrInsertType(
        c: java.sql.Connection,
        key: String,
        name: String,
        hierarchyLevel: Int,
    ): Long {
        var id = -1L
        c.prepareStatement("SELECT id FROM issue_types WHERE key = ? LIMIT 1").use { stmt ->
            stmt.setString(1, key)
            stmt.executeQuery().use { rs ->
                if (rs.next()) id = rs.getLong(1)
            }
        }
        if (id == -1L) {
            c.prepareStatement(
                "INSERT INTO issue_types (key, name, hierarchy_level) VALUES (?, ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, name)
                stmt.setInt(3, hierarchyLevel)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    id = rs.getLong(1)
                }
            }
        }
        return id
    }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
