// IssueController POST /issues/{key}/transition 통합 테스트 — production 시나리오 회귀 가드
// 우회 seed(transitionName=toStateKey) 해제 + software-default.yaml 정렬 (Task 5)

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.engine.WorkflowDefinitionRepository
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.engine.WorkflowPostActionFactory
import com.bts.workflow.engine.WorkflowValidatorFactory
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import com.bts.workflow.scheme.adapter.inbound.WorkflowResolverImpl
import com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver
import com.bts.workflow.scheme.adapter.outbound.JdbcProjectLookupAdapter
import com.bts.workflow.scheme.adapter.outbound.WorkflowSchemeEventPublisher
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.application.port.IssueTypeLookupPort
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
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
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
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
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * IssueController POST /api/v1/issues/{key}/transition 통합 테스트.
 *
 * 실제 Testcontainers PostgreSQL + 두 BC(issue-tracking, project-workflow) 전체 스택 wire.
 * Spring AOP @Transactional 이 실제로 동작하도록 @EnableTransactionManagement 포함 컨텍스트 사용.
 *
 * ## 검증 시나리오 (Task 5 — production 시나리오 회귀 가드)
 * - S1. happy path — open → in_progress 200 OK, software-default.yaml 정렬 시드
 * - S3. invalid transition — open → in_review 미정의 → 409 TRANSITION_NOT_ALLOWED
 * - S4. version conflict — expectedVersion=1 / DB version=2 → 409 VERSION_CONFLICT
 * - IT-2. 프로젝트에 기본 워크플로우 배정 없음 → 422 WORKFLOW_NOT_CONFIGURED
 * - IT-3. 전이 후 IssueResponse.currentStateKey 소문자 확인
 *
 * ## 우회 seed 해제 (Task 5 변형 TDD)
 * PR #27 시점의 통합 테스트는 transitionName=toStateKey 우회 seed 로 production 함정을 가렸음.
 * 본 파일은 그 우회를 제거하고 software-default.yaml 실제 transition name ("Start Work" 등) 으로 정렬.
 * Task 1~4 GREEN (f1e99d9, 2392cd7, 9d77156, 9c35c1f) 이 이미 적용된 상태에서 PASS = BLOCKER 1 fix 완료 증거.
 *
 * ## 마이그레이션 전략
 * issue-tracking V001~V004 + project-workflow V200~V202 를 같은 컨테이너에 순차 적용.
 * project-workflow Flyway 위치: classpath:db/migration/project-workflow.
 *
 * ## 트랜잭션 정책
 * WorkflowKeyResolverImpl + WorkflowTransitionAdapter 는 Propagation.MANDATORY.
 * IssueApplicationService 클래스 레벨 @Transactional(REQUIRED) 이 부모 트랜잭션 제공.
 * Spring AOP 프록시를 통해 @Transactional 이 실제로 동작한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueControllerTransitionIntegrationTest {
    // ── Spring Bean 구성 — 최소 필요 컴포넌트만 명시적 등록 ───────────────────────
    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            /** JVM 단위 singleton Testcontainers — singleton pattern (learnings 2026-05-21) */
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_it_test")
                    .withUsername("bts")
                    .withPassword("bts_it_test")
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

        // ── issue-tracking 빈 ──────────────────────────────────────────────────

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

        @Bean
        open fun issueEventPublisher(
            dsl: DSLContext,
            objectMapper: ObjectMapper,
        ): IssueEventPublisher = IssueEventPublisher(dsl, objectMapper)

        @Bean
        @Profile("test")
        open fun alwaysAllowIssuePermissionResolver() = AlwaysAllowIssuePermissionResolver()

        // ── project-workflow 빈 ───────────────────────────────────────────────

        @Bean
        open fun workflowRepository(dsl: DSLContext): WorkflowRepository = WorkflowRepository(dsl)

        @Bean
        open fun workflowCache(
            workflowRepo: WorkflowRepository,
            dsl: DSLContext,
        ): WorkflowCache = WorkflowCache(workflowRepo, dsl)

        @Bean
        open fun workflowDefinitionRepository(): WorkflowDefinitionRepository =
            mockk<WorkflowDefinitionRepository> {
                every { findValidators(any()) } returns emptyList()
                every { findPostActions(any()) } returns emptyList()
            }

        @Bean
        open fun workflowValidatorFactory(): WorkflowValidatorFactory = mockk(relaxed = true)

        @Bean
        open fun workflowPostActionFactory(): WorkflowPostActionFactory = mockk(relaxed = true)

        @Bean
        open fun workflowEngine(
            cache: WorkflowCache,
            validatorFactory: WorkflowValidatorFactory,
            postActionFactory: WorkflowPostActionFactory,
            definitionRepo: WorkflowDefinitionRepository,
        ): WorkflowEngine = WorkflowEngine(cache, validatorFactory, postActionFactory, definitionRepo)

        @Bean
        open fun workflowTransitionAdapter(engine: WorkflowEngine): WorkflowTransitionAdapter {
            return WorkflowTransitionAdapter(engine)
        }

        @Bean
        open fun workflowSchemeRepository(dsl: DSLContext): WorkflowSchemeRepository = WorkflowSchemeRepository(dsl)

        @Bean
        open fun projectWorkflowSchemeAssignmentRepository(dsl: DSLContext): ProjectWorkflowSchemeAssignmentRepository =
            ProjectWorkflowSchemeAssignmentRepository(dsl)

        @Bean
        open fun schemeIssueTypeMappingRepository(dsl: DSLContext): SchemeIssueTypeMappingRepository {
            return SchemeIssueTypeMappingRepository(dsl)
        }

        @Bean
        open fun workflowSchemeEventPublisher(
            dsl: DSLContext,
            objectMapper: ObjectMapper,
        ): WorkflowSchemeEventPublisher = WorkflowSchemeEventPublisher(dsl, objectMapper)

        @Bean
        open fun alwaysAllowWorkflowSchemePermissionResolver(): AlwaysAllowWorkflowSchemePermissionResolver =
            AlwaysAllowWorkflowSchemePermissionResolver()

        @Bean
        open fun jdbcProjectLookupAdapter(dsl: DSLContext): JdbcProjectLookupAdapter = JdbcProjectLookupAdapter(dsl)

        /**
         * PRE_EXISTING: FR-WF-02 issueTypeLookupPort 도입 시 이 TestConfiguration 갱신 누락.
         * 전이(transition) 통합 테스트는 스킴 매핑 뷰를 조회하지 않으므로
         * issueTypeLookupPort 는 relaxed mock 으로 대체한다.
         */
        @Bean
        open fun issueTypeLookupPort(): IssueTypeLookupPort = mockk(relaxed = true)

        // WorkflowSchemeApplicationService 생성자 파라미터 수 == 7 (FR-WF-02 issueTypeLookupPort 추가).
        // @TestConfiguration Bean 메서드는 분리 불가한 단일 구성 단위이므로 Suppress 처리.
        @Suppress("LongParameterList")
        @Bean
        open fun workflowSchemeApplicationService(
            schemeRepo: WorkflowSchemeRepository,
            assignmentRepo: ProjectWorkflowSchemeAssignmentRepository,
            mappingRepo: SchemeIssueTypeMappingRepository,
            eventPublisher: WorkflowSchemeEventPublisher,
            permissionResolver: AlwaysAllowWorkflowSchemePermissionResolver,
            workflowRepo: WorkflowRepository,
            issueTypeLookupPort: IssueTypeLookupPort,
        ): WorkflowSchemeApplicationService =
            WorkflowSchemeApplicationService(
                schemeRepo = schemeRepo,
                assignmentRepo = assignmentRepo,
                mappingRepo = mappingRepo,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowRepo = workflowRepo,
                issueTypeLookupPort = issueTypeLookupPort,
            )

        @Bean
        open fun workflowResolverImpl(
            projectLookup: JdbcProjectLookupAdapter,
            assignmentRepo: ProjectWorkflowSchemeAssignmentRepository,
            mappingRepo: SchemeIssueTypeMappingRepository,
            workflowRepo: WorkflowRepository,
            schemeAS: WorkflowSchemeApplicationService,
        ): WorkflowResolverImpl =
            WorkflowResolverImpl(
                projectLookup = projectLookup,
                assignmentRepo = assignmentRepo,
                mappingRepo = mappingRepo,
                workflowRepo = workflowRepo,
                schemeAS = schemeAS,
            )

        @Bean
        open fun workflowKeyResolverImpl(workflowResolver: WorkflowResolverImpl): WorkflowKeyResolverImpl =
            WorkflowKeyResolverImpl(workflowResolver = workflowResolver)

        // ── Application Service + Controller ──────────────────────────────────

        @Bean
        open fun clock(): Clock = Clock.systemUTC()

        // IssueApplicationService 생성자 파라미터 수 == 6. @TestConfiguration Bean 메서드이므로 Suppress 처리.
        @Suppress("LongParameterList")
        @Bean
        open fun issueApplicationService(
            repo: IssueRepository,
            eventPublisher: IssueEventPublisher,
            permissionResolver: AlwaysAllowIssuePermissionResolver,
            workflowTransitionAdapter: WorkflowTransitionAdapter,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            clock: Clock,
        ): IssueApplicationService =
            IssueApplicationService(
                repo = repo,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowPort = workflowTransitionAdapter,
                workflowKeyResolver = workflowKeyResolver,
                clock = clock,
            )

        @Bean
        open fun issueController(service: IssueApplicationService): com.bts.issue.adapter.inbound.rest.IssueController =
            com.bts.issue.adapter.inbound.rest.IssueController(service)

        @Bean
        open fun issueExceptionHandler(): com.bts.issue.adapter.inbound.rest.IssueExceptionHandler =
            com.bts.issue.adapter.inbound.rest.IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dataSource: DriverManagerDataSource

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        private var migrated = false
        private var seeded = false

        /** 정상 시나리오용 프로젝트 (software-scheme 자동 배정 예정) */
        private const val NORMAL_PROJECT_KEY = "TRANSITION"

        /** EC-2 시나리오용 프로젝트 (no-default-scheme 배정 — 422 유발) */
        private const val NO_DEFAULT_PROJECT_KEY = "NODEFAULT"
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedWorkflowsAndSchemes()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()

        // 각 테스트 독립성 보장 — issues 행 초기화 + key_sequence 초기화
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues")
                stmt.execute(
                    "UPDATE projects SET key_sequence = 0 " +
                        "WHERE key IN ('$NORMAL_PROJECT_KEY', '$NO_DEFAULT_PROJECT_KEY')",
                )
            }
        }
    }

    // ── S1. happy path — open → in_progress, software-default.yaml 정렬 시드 ────

    /**
     * S1 production 시나리오 회귀 가드.
     *
     * Given  TRANSITION 프로젝트에 이슈 1건 삽입 (currentStateKey = "open")
     * When   POST /api/v1/issues/TRANSITION-1/transition { toStatusKey: "in_progress", expectedVersion: 1 }
     * Then   200 OK + data.currentStateKey == "in_progress", version == 2
     *        software-default.yaml 의 "Start Work" 전이 name 과 (from,to) 2-tuple 매칭으로 성공.
     *        우회 seed (transitionName=toStateKey) 없이도 정상 동작함을 검증.
     */
    @Test
    fun `POST issues key transition succeeds open to in_progress with auto-resolved workflow`() {
        val issueKey = insertIssue(NORMAL_PROJECT_KEY, "in_progress 전이 검증용 이슈", "open")

        val body = mapOf("toStatusKey" to "in_progress", "expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.currentStateKey").value("in_progress"))
    }

    // ── IT-2. 프로젝트에 기본 워크플로우 없음 → 422 ─────────────────────────────

    /**
     * Given  NODEFAULT 프로젝트 — no-default-scheme 배정 (default mapping 없음)
     * When   POST /api/v1/issues/NODEFAULT-1/transition { toStatusKey: "in_progress" }
     * Then   422 Unprocessable Entity + errorCode WORKFLOW_NOT_CONFIGURED
     */
    @Test
    fun `POST issues key transition returns 422 when project has no workflow default mapping`() {
        val issueKey = insertIssue(NO_DEFAULT_PROJECT_KEY, "422 검증용 이슈", "open")

        val body = mapOf("toStatusKey" to "in_progress", "expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("WORKFLOW_NOT_CONFIGURED"))
    }

    // ── IT-3. 전이 후 currentStateKey 소문자 확인 ────────────────────────────────

    /**
     * Given  TRANSITION 프로젝트에 이슈 1건 삽입 (currentStateKey = "open")
     * When   POST /api/v1/issues/TRANSITION-1/transition { toStatusKey: "in_progress" }
     * Then   IssueResponse.currentStateKey 가 소문자 "in_progress" (T7 V004 migration 검증)
     */
    @Test
    fun `IssueResponse currentStateKey is lowercase after transition`() {
        val issueKey = insertIssue(NORMAL_PROJECT_KEY, "소문자 상태 키 검증용 이슈", "open")

        val body = mapOf("toStatusKey" to "in_progress", "expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.currentStateKey").value("in_progress"))
    }

    // ── S3. invalid transition — open → in_review 미정의 → 409 ──────────────────

    /**
     * S3 production 시나리오 회귀 가드.
     *
     * Given  TRANSITION 프로젝트에 이슈 1건 삽입 (currentStateKey = "open")
     * When   POST /api/v1/issues/TRANSITION-1/transition { toStatusKey: "in_review", expectedVersion: 1 }
     * Then   409 Conflict + errorCode == "TRANSITION_NOT_ALLOWED"
     *        software-default.yaml 에 from=open, to=in_review 전이가 정의되지 않음.
     *        (from,to) 2-tuple 매칭이 실패하여 전이 거부됨을 검증.
     */
    @Test
    fun `POST issues key transition returns 409 when transition is not defined in workflow`() {
        val issueKey = insertIssue(NORMAL_PROJECT_KEY, "전이 미정의 검증용 이슈", "open")

        val body = mapOf("toStatusKey" to "in_review", "expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("TRANSITION_NOT_ALLOWED"))
    }

    // ── S4. version conflict — expectedVersion 불일치 → 409 ───────────────────

    /**
     * S4 production 시나리오 회귀 가드.
     *
     * Given  TRANSITION 프로젝트에 이슈 1건 삽입 (currentStateKey = "open", version=1)
     *        DB 버전을 2로 직접 업데이트 (낙관락 충돌 시뮬레이션)
     * When   POST /api/v1/issues/TRANSITION-1/transition { toStatusKey: "in_progress", expectedVersion: 1 }
     * Then   409 Conflict + errorCode == "VERSION_CONFLICT"
     *        DB version=2 / client expectedVersion=1 불일치로 낙관락 충돌.
     */
    @Test
    fun `POST issues key transition returns 409 when version conflict occurs`() {
        val issueKey = insertIssue(NORMAL_PROJECT_KEY, "버전 충돌 검증용 이슈", "open")

        // DB 버전을 2로 강제 업데이트 — 낙관락 충돌 유발
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        ).use { conn ->
            conn.prepareStatement("UPDATE issues SET version = 2 WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeUpdate()
            }
        }

        val body = mapOf("toStatusKey" to "in_progress", "expectedVersion" to 1)

        mockMvc.perform(
            post("/api/v1/issues/$issueKey/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — issue-tracking + project-workflow 두 BC를 단일 pass로 적용.
     *
     * issue-tracking V001~V004 는 classpath:db/migration/issue-tracking.
     * project-workflow V200~V202 는 classpath:db/migration/project-workflow.
     *
     * 단계별 적용 이유: issue-tracking V001~V002 의 projects/issues 테이블이
     * project-workflow V201 (workflow_scheme_issue_type_mappings) 이전에 존재해야 한다.
     * V003 (issue_types) 도 project-workflow V201 이전에 적용되어야 FK 참조가 성립한다.
     *
     * Flyway 기본 동작: 여러 locations 를 지정하면 모든 위치의 마이그레이션을 버전 순서로 정렬 후 적용.
     * V001~V004 + V200~V202 는 버전 범위가 겹치지 않으므로 단일 migrate() 호출로 처리 가능.
     */
    private fun applyMigrations() {
        val url = TestConfig.postgres.jdbcUrl
        val user = TestConfig.postgres.username
        val pass = TestConfig.postgres.password

        Flyway.configure()
            .dataSource(url, user, pass)
            .placeholderReplacement(false)
            .locations(
                "classpath:db/migration/issue-tracking",
                "classpath:db/migration/project-workflow",
            )
            .load()
            .migrate()
    }

    /**
     * 테스트 픽스처 seed.
     *
     * 1. projects 2건 (TRANSITION, NODEFAULT)
     * 2. software-default workflow + states + transitions
     * 3. software-scheme default mapping → software-default workflow
     * 4. NODEFAULT 프로젝트 — no-default-scheme 배정 (default mapping 없음)
     *
     * LongMethod: 워크플로우·스킴·프로젝트 픽스처 삽입 순서를 한 곳에서 관리해야 하므로 함수 분리보다 인라인이 적합하다.
     */
    @Suppress("LongMethod")
    private fun seedWorkflowsAndSchemes() {
        val url = TestConfig.postgres.jdbcUrl
        val user = TestConfig.postgres.username
        val pass = TestConfig.postgres.password

        DriverManager.getConnection(url, user, pass).use { conn ->
            conn.autoCommit = false

            // 1. 프로젝트 2건 삽입
            insertProject(conn, NORMAL_PROJECT_KEY, "Transition Test Project")
            insertProject(conn, NO_DEFAULT_PROJECT_KEY, "No Default Project")

            // 2. software-default workflow
            val wfId =
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('software-default', '소프트웨어 개발 기본 워크플로우') " +
                        "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val openId = insertState(conn, wfId, "open", "Open", "TODO", 0)
            val inProgressId = insertState(conn, wfId, "in_progress", "In Progress", "IN_PROGRESS", 1)
            val inReviewId = insertState(conn, wfId, "in_review", "In Review", "IN_PROGRESS", 2)
            val doneId = insertState(conn, wfId, "done", "Done", "DONE", 3)

            // software-default.yaml 실제 transition name 사용 — 우회 seed 해제 (Task 5)
            // WorkflowEngine 이 (from, to) 2-tuple 로 매칭하므로 name 은 사람 친화 라벨.
            // 우회: transitionName=toStateKey 인위 맞춤 제거됨 (PR #17 c040e2d 함정 해소).
            insertTransition(conn, wfId, openId, inProgressId, "Start Work")
            insertTransition(conn, wfId, inProgressId, inReviewId, "Submit for Review")
            insertTransition(conn, wfId, inReviewId, doneId, "Approve")

            // 3. software-scheme + default mapping → software-default
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                    SELECT s.id, NULL, '$wfId'
                    FROM workflow_schemes s
                    WHERE s.key = 'software-scheme'
                    ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING
                    """.trimIndent(),
                )
            }

            // 4. no-default-scheme 생성 (default mapping 없음)
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_schemes (key, name, is_default)
                    VALUES ('no-default-scheme', 'No Default Scheme', false)
                    ON CONFLICT (key) DO NOTHING
                    """.trimIndent(),
                )
            }

            // NODEFAULT 프로젝트에 no-default-scheme 배정
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                    SELECT p.id, s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                    FROM projects p, workflow_schemes s
                    WHERE p.key = '$NO_DEFAULT_PROJECT_KEY'
                      AND s.key = 'no-default-scheme'
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }

            conn.commit()
        }
    }

    private fun insertProject(
        conn: Connection,
        key: String,
        name: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
        ).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, name)
            stmt.executeUpdate()
        }
    }

    // 워크플로우 상태 INSERT helper: wfId + key + name + category + displayOrder = 6 파라미터 필수.
    // 테스트 헬퍼 함수이므로 분리보다 인라인 유지가 더 명확하다. Suppress 처리.
    @Suppress("LongParameterList")
    private fun insertState(
        conn: Connection,
        wfId: UUID,
        key: String,
        name: String,
        category: String,
        displayOrder: Int,
    ): UUID =
        conn.prepareStatement(
            "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (workflow_id, key) " +
                "DO UPDATE SET display_order = EXCLUDED.display_order RETURNING id",
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setString(2, key)
            stmt.setString(3, name)
            stmt.setString(4, category)
            stmt.setInt(5, displayOrder)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
            }
        }

    private fun insertTransition(
        conn: Connection,
        wfId: UUID,
        fromId: UUID,
        toId: UUID,
        transitionName: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT (workflow_id, from_state_id, to_state_id) DO NOTHING",
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setObject(2, fromId)
            stmt.setObject(3, toId)
            stmt.setString(4, transitionName)
            stmt.executeUpdate()
        }
    }

    /**
     * 테스트용 이슈를 DB에 직접 삽입하고 이슈 키를 반환한다.
     *
     * TRANSITION 프로젝트의 key_sequence 를 수동 증가하여 이슈 키를 생성한다.
     * Service layer 를 우회하므로 workflowKeyResolver 호출 없이 지정한 currentStateKey 를 그대로 삽입한다.
     */
    private fun insertIssue(
        projectKey: String,
        summary: String,
        currentStateKey: String,
    ): String {
        val url = TestConfig.postgres.jdbcUrl
        val user = TestConfig.postgres.username
        val pass = TestConfig.postgres.password

        DriverManager.getConnection(url, user, pass).use { conn ->
            conn.autoCommit = false

            // key_sequence 증가 후 새 번호 획득
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

            // projects.id 조회
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

            conn.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version) " +
                    "VALUES (?, ?, ?, ?, ?, 1)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, UUID.fromString("00000000-0000-0000-0000-000000000001"))
                stmt.setString(5, currentStateKey)
                stmt.executeUpdate()
            }

            conn.commit()
            return issueKey
        }
    }
}
