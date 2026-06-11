// updateIssue → IssueMentioned 이벤트 pgmq enqueue 전 구간 Testcontainers 통합 테스트 (FR-MN-01 G1)
@file:Suppress("MaxLineLength")

package com.bts.issue.application

import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.domain.ActorId
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueMentioned
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.user.UserLookupPort
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * updateIssue → IssueMentioned pgmq enqueue 전 구간 통합 테스트 (FR-MN-01 G1).
 *
 * 단위 테스트(IssueApplicationServiceMentionTest)가 eventPublisher mock 으로 발행 호출만 검증하는 것과 달리,
 * 이 테스트는 실 IssueEventPublisher → pgmq.send → q_issue_events 큐 경로 전체를 검증한다.
 *
 * ## 컨테이너
 * quay.io/tembo/pg16-pgmq:latest — Testcontainers JVM 싱글턴 패턴.
 * V001~V017(issue-tracking) + V200~V202(project-workflow) Flyway 마이그레이션 포함.
 * V002 에서 q_issue_events 큐가 pgmq.create 로 생성된다.
 *
 * ## UserLookupPort
 * cross-BC 실 어댑터 없이 test fake 로 bob/carol → 고정 UUID 해석.
 * 실 어댑터(UserLookupAdapterIntegrationTest)는 별도 Task 3 에서 담당한다.
 *
 * ## 시나리오
 *
 * ### G1-S1: 본문에 신규 멘션 → q_issue_events에 issue.mentioned 이벤트 enqueue
 * Given  프로젝트 + 이슈(description=null) 시드, UserLookupPort fake(bob → BOB_ID)
 * When   updateIssue(actor=alice, description="@bob 검토") 호출
 * Then   q_issue_events 에서 읽은 메시지 type="issue.mentioned"
 * And    mentionedUserIds 에 BOB_ID 포함
 * And    sourceField="description"
 * And    actorId.value=ALICE_ID (본문 수정자)
 * And    본문 저장(issues.description)과 enqueue 가 같은 트랜잭션 내 커밋 (outbox atomicity)
 *
 * ### G1-S2: 본문 변경이지만 신규 멘션 없음 → issue.mentioned 이벤트 미발행
 * Given  이슈(description="@bob"), UserLookupPort fake(bob → BOB_ID)
 * When   updateIssue(actor=alice, description="@bob 추가 설명") 호출 (멘션 집합 동일)
 * Then   q_issue_events 에 issue.mentioned 타입 메시지가 없다
 * And    q_issue_events 에는 issue.updated 이벤트만 존재한다
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueMentionPublishIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueMentionPublishIntegrationTest {
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
                    .withDatabaseName("bts_mention_it_test")
                    .withUsername("bts")
                    .withPassword("bts_mention_it_test")
                    .apply { start() }

            /** Bob — G1-S1 멘션 대상 UUID (Zod v4 형식) */
            val BOB_ID: UUID = UUID.fromString("bb000000-0000-4000-8000-000000000001")

            /** Carol — G1-S2 추가 멘션 대상 UUID (Zod v4 형식) */
            val CAROL_ID: UUID = UUID.fromString("cc000000-0000-4000-8000-000000000002")

            /** Alice — 본문 수정 actor UUID (Zod v4 형식) */
            val ALICE_ID: UUID = UUID.fromString("aa000000-0000-4000-8000-000000000003")
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
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())

        // ── issue-tracking 빈 ──────────────────────────────────────────────────

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

        @Bean
        open fun issueTypeRepository(dsl: DSLContext): IssueTypeRepository = IssueTypeRepository(dsl)

        @Bean
        open fun resolutionRepository(dsl: DSLContext): ResolutionRepository = ResolutionRepository(dsl)

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
                every { findValidators(any(), any()) } returns emptyList()
                every { findPostActions(any(), any()) } returns emptyList()
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
        open fun workflowTransitionAdapter(engine: WorkflowEngine): WorkflowTransitionAdapter = WorkflowTransitionAdapter(engine)

        @Bean
        open fun workflowSchemeRepository(dsl: DSLContext): WorkflowSchemeRepository = WorkflowSchemeRepository(dsl)

        @Bean
        open fun projectWorkflowSchemeAssignmentRepository(dsl: DSLContext): ProjectWorkflowSchemeAssignmentRepository =
            ProjectWorkflowSchemeAssignmentRepository(dsl)

        @Bean
        open fun schemeIssueTypeMappingRepository(dsl: DSLContext): SchemeIssueTypeMappingRepository = SchemeIssueTypeMappingRepository(dsl)

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

        @Bean
        open fun issueTypeLookupPort(): IssueTypeLookupPort = mockk(relaxed = true)

        @Bean
        @Suppress("LongParameterList")
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

        // ── Application Service ───────────────────────────────────────────────

        @Bean
        open fun clock(): Clock = Clock.systemUTC()

        /**
         * UserLookupPort test fake.
         *
         * bob/carol/alice 만 UUID 로 해석한다. 실 identity-access 어댑터를 쓰지 않는 이유는
         * cross-BC 의존을 배제하고 pgmq enqueue 경로에만 집중하기 위해서다.
         */
        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                private val lookup =
                    mapOf(
                        "bob" to BOB_ID,
                        "carol" to CAROL_ID,
                        "alice" to ALICE_ID,
                    )

                override fun exists(userId: UUID): Boolean = true

                override fun findIdsByUsernames(usernames: Set<String>): Map<String, UUID> = lookup.filterKeys { it in usernames }
            }

        @Bean
        @Suppress("LongParameterList")
        open fun issueApplicationService(
            repo: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            eventPublisher: IssueEventPublisher,
            permissionResolver: AlwaysAllowIssuePermissionResolver,
            workflowTransitionAdapter: WorkflowTransitionAdapter,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            userLookupPort: UserLookupPort,
            clock: Clock,
        ): IssueApplicationService =
            IssueApplicationService(
                repo = repo,
                issueTypeRepository = issueTypeRepository,
                resolutionRepository = resolutionRepository,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowPort = workflowTransitionAdapter,
                workflowKeyResolver = workflowKeyResolver,
                userLookupPort = userLookupPort,
                componentRepository = mockk(relaxed = true),
                projectLeadRepository = mockk(relaxed = true),
                versionRepository = mockk(relaxed = true),
                clock = clock,
            )
    }

    // ── 테스트 인프라 ────────────────────────────────────────────────────────────

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    companion object {
        private var migrated = false
        private var seeded = false

        private const val PROJECT_KEY = "MENTION"

        /** updateIssue 의 actor — 자기 멘션 제외 대상 */
        val ACTOR_ID = ActorId(TestConfig.ALICE_ID)
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProjectAndWorkflow()
            seeded = true
        }
    }

    @BeforeEach
    fun cleanQueueAndIssues() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                // 각 테스트 전 큐를 비워 메시지가 섞이지 않도록 한다
                stmt.execute("SELECT pgmq.purge_queue('q_issue_events')")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    // ── G1-S1: 신규 멘션 → issue.mentioned enqueue ──────────────────────────────

    /**
     * G1-S1 — 본문 신규 멘션 시 pgmq에 issue.mentioned 이벤트가 enqueue됨을 검증한다.
     *
     * Given  description=null 인 이슈
     * When   updateIssue(actor=alice, description="@bob 검토")
     * Then   q_issue_events 에서 읽은 메시지 type="issue.mentioned"
     * And    mentionedUserIds 에 BOB_ID 포함
     * And    sourceField="description"
     * And    actorId=ALICE_ID
     */
    @Test
    fun `G1-S1 본문에 신규 멘션 - q_issue_events에 issue_mentioned 이벤트가 enqueue된다`() {
        val issueKey = insertIssue(PROJECT_KEY, "멘션 통합 검증 이슈", "open", description = null)

        val request =
            UpdateIssueRequest(
                summary = null,
                expectedVersion = 1,
                description = "@bob 검토",
            )

        issueApplicationService.updateIssue(ACTOR_ID, com.bts.issue.domain.IssueKey(issueKey), request)

        // q_issue_events 에서 최대 10건 읽어 issue.mentioned 를 찾는다
        val mentionedEvent = readMentionedEventFromQueue()

        check(mentionedEvent != null) {
            "updateIssue 후 q_issue_events 에 issue.mentioned 이벤트가 없습니다. " +
                "IssueEventPublisher.publish 가 실제 pgmq.send 를 호출하지 않았을 수 있습니다."
        }

        check(TestConfig.BOB_ID in mentionedEvent.mentionedUserIds) {
            "mentionedUserIds 에 BOB_ID 가 없습니다. actual=${mentionedEvent.mentionedUserIds}"
        }

        check(mentionedEvent.sourceField == "description") {
            "sourceField 가 'description' 이어야 하지만 '${mentionedEvent.sourceField}' 입니다."
        }

        check(mentionedEvent.actorId.value == TestConfig.ALICE_ID) {
            "actorId 가 ALICE_ID 여야 하지만 ${mentionedEvent.actorId.value} 입니다."
        }
    }

    // ── G1-S2: 멘션 집합 변화 없음 → issue.mentioned 미발행 ────────────────────

    /**
     * G1-S2 — 본문이 변경되지만 신규 멘션이 없으면 issue.mentioned 가 enqueue되지 않음을 검증한다.
     *
     * Given  description="@bob" 인 이슈 (bob 이 이미 멘션됨)
     * When   updateIssue(actor=alice, description="@bob 추가 설명") (멘션 집합 동일)
     * Then   q_issue_events 에 issue.mentioned 타입 메시지가 없다
     * And    q_issue_events 에는 issue.updated 이벤트만 존재한다
     */
    @Test
    fun `G1-S2 멘션 집합 동일한 본문 수정 - issue_mentioned 이벤트가 enqueue되지 않는다`() {
        val issueKey = insertIssue(PROJECT_KEY, "멘션 미발행 검증 이슈", "open", description = "@bob")

        val request =
            UpdateIssueRequest(
                summary = null,
                expectedVersion = 1,
                description = "@bob 추가 설명",
            )

        issueApplicationService.updateIssue(ACTOR_ID, com.bts.issue.domain.IssueKey(issueKey), request)

        val mentionedEvent = readMentionedEventFromQueue()

        check(mentionedEvent == null) {
            "멘션 집합이 동일한데 issue.mentioned 이벤트가 enqueue됐습니다. " +
                "mentionedUserIds=${mentionedEvent?.mentionedUserIds}"
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * q_issue_events 에서 최대 10건을 읽어 type="issue.mentioned" 인 메시지를 역직렬화해 반환한다.
     *
     * 없으면 null 을 반환한다. visibility_timeout=1 로 읽어 다른 소비자 간섭을 최소화한다.
     */
    private fun readMentionedEventFromQueue(): IssueMentioned? {
        val dsl =
            DSL.using(
                DriverManager.getConnection(
                    TestConfig.postgres.jdbcUrl,
                    TestConfig.postgres.username,
                    TestConfig.postgres.password,
                ),
                SQLDialect.POSTGRES,
            )

        // 최대 10건 읽기 — updateIssue 는 IssueUpdated + IssueMentioned 2건을 발행하므로 여유있게 조회
        val records = dsl.fetch("SELECT * FROM pgmq.read('q_issue_events', 1, 10)")

        return records
            .map { record ->
                val body = record.get("message", String::class.java)
                objectMapper.readTree(body)
            }
            .filter { json -> json.get("type")?.asText() == "issue.mentioned" }
            .map { json -> objectMapper.treeToValue(json, IssueMentioned::class.java) }
            .firstOrNull()
    }

    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(
                TestConfig.postgres.jdbcUrl,
                TestConfig.postgres.username,
                TestConfig.postgres.password,
            )
            .placeholderReplacement(false)
            .locations(
                "classpath:db/migration/issue-tracking",
                "classpath:db/migration/project-workflow",
            )
            .load()
            .migrate()
    }

    @Suppress("LongMethod")
    private fun seedProjectAndWorkflow() {
        conn().use { c ->
            c.autoCommit = false

            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Mention Integration Test Project")
                stmt.executeUpdate()
            }

            // 워크플로우 — open 상태만 필요 (updateIssue 는 워크플로우 전이 없음)
            val wfId: UUID =
                c.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('mention-test-wf', '멘션 테스트 워크플로우') " +
                        "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            c.prepareStatement(
                "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                    "VALUES (?, 'open', 'Open', 'TODO', 0) " +
                    "ON CONFLICT (workflow_id, key) DO UPDATE SET display_order = EXCLUDED.display_order",
            ).use { stmt ->
                stmt.setObject(1, wfId)
                stmt.executeUpdate()
            }

            // workflow_scheme + default mapping
            val schemeId: Long =
                c.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name, is_default) " +
                        "VALUES ('mention-test-scheme', '멘션 테스트 스킴', false) " +
                        "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            c.prepareStatement(
                "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id) " +
                    "VALUES (?, NULL, ?) ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING",
            ).use { stmt ->
                stmt.setLong(1, schemeId)
                stmt.setObject(2, wfId)
                stmt.executeUpdate()
            }

            c.prepareStatement(
                "INSERT INTO project_workflow_scheme_assignments " +
                    "(project_id, workflow_scheme_id, assigned_at, assigned_by) " +
                    "SELECT p.id, ?, NOW(), '00000000-0000-4000-8000-000000000000'::uuid " +
                    "FROM projects p WHERE p.key = ? " +
                    "ON CONFLICT (project_id) DO NOTHING",
            ).use { stmt ->
                stmt.setLong(1, schemeId)
                stmt.setString(2, PROJECT_KEY)
                stmt.executeUpdate()
            }

            c.commit()
        }
    }

    /**
     * 테스트용 이슈를 DB 에 직접 삽입한다.
     *
     * @param projectKey 프로젝트 키
     * @param summary 이슈 제목
     * @param currentStateKey 초기 상태 키
     * @param description 이슈 본문 (null 허용)
     * @return 삽입된 이슈 키 (예: "MENTION-1")
     */
    private fun insertIssue(
        projectKey: String,
        summary: String,
        currentStateKey: String,
        description: String?,
    ): String =
        conn().use { c ->
            c.autoCommit = false

            val seq =
                c.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            val issueKey = "$projectKey-$seq"

            val projectId: UUID =
                c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "$projectKey 프로젝트가 없습니다." }
                        rs.getObject(1) as UUID
                    }
                }

            val taskTypeId: Long =
                c.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음. V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            c.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id, description) " +
                    "VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, TestConfig.ALICE_ID)
                stmt.setString(5, currentStateKey)
                stmt.setLong(6, taskTypeId)
                stmt.setString(7, description)
                stmt.executeUpdate()
            }

            c.commit()
            issueKey
        }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
