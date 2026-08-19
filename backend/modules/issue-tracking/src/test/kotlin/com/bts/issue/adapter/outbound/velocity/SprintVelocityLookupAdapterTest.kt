// SprintVelocityLookupAdapter Testcontainers 통합 테스트 — 실 WorkflowStateCatalogImpl 조립 + 스프린트별 commitment/completed 집계 (FR-RP-02 Task 3)
@file:Suppress("MaxLineLength")

package com.bts.issue.adapter.outbound.velocity

import com.bts.issue.testsupport.insertWorkflowStatus
import com.bts.issue.adapter.outbound.velocity.repository.SprintVelocityQueryRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.velocity.VelocityContribution
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.adapter.inbound.WorkflowResolverImpl
import com.bts.workflow.scheme.adapter.inbound.WorkflowStateCatalogImpl
import com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver
import com.bts.workflow.scheme.adapter.outbound.JdbcProjectLookupAdapter
import com.bts.workflow.scheme.adapter.outbound.WorkflowSchemeEventPublisher
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.application.port.IssueTypeLookupPort
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * [SprintVelocityLookupAdapter] Testcontainers 통합 테스트 (FR-RP-02 Task 3).
 *
 * 실 Testcontainers PostgreSQL + 실 [WorkflowStateCatalogImpl] 조립으로 end-to-end 검증한다.
 * `WorkflowStateCatalog.listStates` 는 `Propagation.MANDATORY` 이므로, adapter bean 을 Spring
 * AOP 프록시(`@EnableTransactionManagement(proxyTargetClass = true)`)를 통해 호출해 활성
 * 트랜잭션 안에서 실행되도록 한다 ([IssueMoveIntegrationTest]/[IssueEpicProgressControllerIntegrationTest] 선례).
 *
 * ## 검증 시나리오
 * - S1. 스프린트별 commitment(전체 추정합)/completed(DONE 추정합) 정상 집계
 * - S2. ★ 비-vacuous 보안 — 동일 데이터에 대해 필터 unrestricted/restricted 두 번 호출해 결과가
 *   달라짐을 실측한다(고정 AlwaysUnrestricted 주입식 vacuous 금지).
 * - S3. 스킴 미할당(매핑 없음) 이슈 타입 → completed 미포함·commitment 포함, 예외 전파 없음(500 없음)
 * - S4. NULL 추정치는 0, 소프트 삭제 이슈는 완전히 제외
 * - S5. 이슈 키가 빈 스프린트 → (0, 0)
 * - S6. 전체 이슈 키 합집합이 비어있으면 모든 스프린트가 (0, 0) (조기 반환 경로)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [SprintVelocityLookupAdapterTest.TestConfig::class])
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SprintVelocityLookupAdapterTest {
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        companion object {
            @JvmStatic
            val postgres: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                )
                    .withDatabaseName("bts_velocity_test")
                    .withUsername("bts")
                    .withPassword("bts_velocity_test")
                    .apply { start() }

            val securityDirectory = SwitchableSecurityDirectory()
        }

        @Bean
        open fun dataSource(): DriverManagerDataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

        @Bean
        open fun transactionManager(dataSource: DriverManagerDataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

        @Bean
        open fun objectMapper(): ObjectMapper = ObjectMapper()

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

        @Bean
        open fun issueTypeRepository(dsl: DSLContext): IssueTypeRepository = IssueTypeRepository(dsl)

        @Bean
        open fun securityDirectory(): IssueSecurityDirectory = TestConfig.securityDirectory

        // ── project-workflow 빈 조립 (IssueMoveIntegrationTest / IssueEpicProgressControllerIntegrationTest 선례) ─

        @Bean
        open fun workflowRepository(dsl: DSLContext): WorkflowRepository = WorkflowRepository(dsl)

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
        open fun workflowStateCatalogImpl(workflowResolver: WorkflowResolverImpl): WorkflowStateCatalogImpl =
            WorkflowStateCatalogImpl(workflowResolver)

        @Bean
        open fun isolatedWorkflowStateLookup(workflowStateCatalog: WorkflowStateCatalogImpl): IsolatedWorkflowStateLookup =
            IsolatedWorkflowStateLookup(workflowStateCatalog)

        // FR-RP-02 Task 6 — jOOQ 쿼리가 SprintVelocityQueryRepository 로 추출되어 어댑터에 주입된다.
        @Bean
        open fun sprintVelocityQueryRepository(dsl: DSLContext): SprintVelocityQueryRepository = SprintVelocityQueryRepository(dsl)

        @Bean
        open fun sprintVelocityLookupAdapter(
            queryRepository: SprintVelocityQueryRepository,
            securityDirectory: IssueSecurityDirectory,
            issueRepository: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            workflowStateLookup: IsolatedWorkflowStateLookup,
        ): SprintVelocityLookupAdapter =
            SprintVelocityLookupAdapter(
                queryRepository = queryRepository,
                securityDirectory = securityDirectory,
                issueRepository = issueRepository,
                issueTypeRepository = issueTypeRepository,
                workflowStateLookup = workflowStateLookup,
            )
    }

    @Autowired
    lateinit var issueRepository: IssueRepository

    @Autowired
    lateinit var adapter: SprintVelocityLookupAdapter

    companion object {
        private const val PROJECT_KEY = "VELOC"
        private var migrated = false
        private var seeded = false

        private var testProjectId: UUID = UUID.randomUUID()

        /** issue_types.id — veloc-scheme 에 명시적 매핑이 있는 타입 (hierarchyLevel=0). */
        private var storyTypeId: Long = -1L

        /** issue_types.id — veloc-scheme 에 매핑이 전혀 없는 타입(default mapping 도 없음, S3 검증용). */
        private var gapTypeId: Long = -1L

        private const val DONE_KEY = "done"
        private const val OPEN_KEY = "open"
        private const val IN_PROGRESS_KEY = "in_progress"

        /** 기밀 이슈에 부여할 보안 등급 (S2 검증용). */
        private val restrictedLevelId: UUID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")

        private val viewerUuid: UUID = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedAll()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        TestConfig.securityDirectory.reset()
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues WHERE project_id = '$testProjectId'")
            }
        }
    }

    // ── S1. 스프린트별 commitment/completed 집계 ──────────────────────────────

    /**
     * Given  sprint1: 이슈 A(DONE, 5h) + 이슈 B(open, 3h). sprint2: 이슈 C(DONE, 2h).
     * When   fetchVelocitySource 를 두 스프린트 모두 포함해 호출.
     * Then   sprint1: commitment=8h, completed=5h. sprint2: commitment=2h, completed=2h.
     */
    @Test
    fun `S1 스프린트별 commitment completed 집계 - DONE 이슈만 completed 포함`() {
        val hourInSeconds = 3600
        val sprint1 = UUID.randomUUID()
        val sprint2 = UUID.randomUUID()

        val issueA = insertIssue(1L, storyTypeId, DONE_KEY, 5 * hourInSeconds)
        val issueB = insertIssue(2L, storyTypeId, OPEN_KEY, 3 * hourInSeconds)
        val issueC = insertIssue(3L, storyTypeId, DONE_KEY, 2 * hourInSeconds)

        val result =
            adapter.fetchVelocitySource(
                issueKeysBySprint =
                    mapOf(
                        sprint1 to setOf(issueA.key.value, issueB.key.value),
                        sprint2 to setOf(issueC.key.value),
                    ),
                projectKey = PROJECT_KEY,
                viewerUserId = viewerUuid,
            )

        assertThat(result[sprint1]?.commitmentSeconds).isEqualTo((8 * hourInSeconds).toLong())
        assertThat(result[sprint1]?.completedSeconds).isEqualTo((5 * hourInSeconds).toLong())
        assertThat(result[sprint2]?.commitmentSeconds).isEqualTo((2 * hourInSeconds).toLong())
        assertThat(result[sprint2]?.completedSeconds).isEqualTo((2 * hourInSeconds).toLong())
    }

    // ── S2. ★ 비-vacuous 보안 — 필터 유무로 결과가 달라짐 ─────────────────────

    /**
     * 같은 시드 데이터에 대해 accessibleLevels 를 unrestricted → restricted 로 전환해 두 번 호출한다.
     * 필터가 없으면(unrestricted) 기밀 이슈(10h, DONE)가 집계에 포함되어 값이 크고,
     * 필터가 있으면(restricted) 기밀 이슈가 SQL 푸시다운으로 제외되어 값이 작아진다.
     * 고정 AlwaysUnrestricted 주입식 vacuous 를 피하고, 필터 자체가 결과를 바꾼다는 것을 실측한다.
     *
     * Given  공개 이슈(DONE, 4h) + 기밀 이슈(DONE, 10h, securityLevelId=restrictedLevelId)
     * When   1) unrestricted access 로 호출  2) restricted access 로 호출
     * Then   1) commitment=completed=14h  2) commitment=completed=4h (기밀 이슈 제외)
     */
    @Test
    fun `S2 비-vacuous 보안 - 필터 유무로 결과가 달라짐 (기밀 이슈 제외)`() {
        val hourInSeconds = 3600
        val sprint1 = UUID.randomUUID()

        val visibleIssue = insertIssue(1L, storyTypeId, DONE_KEY, 4 * hourInSeconds)
        val secretIssue =
            insertIssue(2L, storyTypeId, DONE_KEY, 10 * hourInSeconds, securityLevelId = restrictedLevelId)
        val keys = setOf(visibleIssue.key.value, secretIssue.key.value)

        TestConfig.securityDirectory.access = SwitchableSecurityDirectory.UNRESTRICTED
        val unrestrictedResult =
            adapter.fetchVelocitySource(mapOf(sprint1 to keys), PROJECT_KEY, viewerUuid)
        assertThat(unrestrictedResult[sprint1]?.commitmentSeconds).isEqualTo((14 * hourInSeconds).toLong())
        assertThat(unrestrictedResult[sprint1]?.completedSeconds).isEqualTo((14 * hourInSeconds).toLong())

        TestConfig.securityDirectory.access = SwitchableSecurityDirectory.RESTRICTED
        val restrictedResult =
            adapter.fetchVelocitySource(mapOf(sprint1 to keys), PROJECT_KEY, viewerUuid)
        assertThat(restrictedResult[sprint1]?.commitmentSeconds).isEqualTo((4 * hourInSeconds).toLong())
        assertThat(restrictedResult[sprint1]?.completedSeconds).isEqualTo((4 * hourInSeconds).toLong())
    }

    // ── S3. 스킴 미할당 타입 — completed 미포함, commitment 포함, 예외 없음 ────

    /**
     * gapTypeId 는 veloc-scheme 에 명시 매핑도 default mapping 도 없다 — WorkflowResolverImpl 이
     * WorkflowSchemeNoDefaultException 을 던지고, adapter 는 이를 catch 해 빈 상태맵으로 폴백한다
     * (500 없음). 빈 상태맵이므로 currentStateKey 가 DONE_KEY 여도 completed 에 포함되지 않는다.
     *
     * Given  gapType 이슈(currentStateKey="done", 4h) — 스킴 매핑 없음
     * When   fetchVelocitySource 호출
     * Then   예외 없이 commitment=4h, completed=0 (미완료 취급)
     */
    @Test
    fun `S3 스킴 미할당 타입 - completed 미포함 commitment 포함 (예외 전파 없음)`() {
        val hourInSeconds = 3600
        val sprint1 = UUID.randomUUID()
        val gapIssue = insertIssue(1L, gapTypeId, DONE_KEY, 4 * hourInSeconds)

        val result =
            adapter.fetchVelocitySource(mapOf(sprint1 to setOf(gapIssue.key.value)), PROJECT_KEY, viewerUuid)

        assertThat(result[sprint1]?.commitmentSeconds).isEqualTo((4 * hourInSeconds).toLong())
        assertThat(result[sprint1]?.completedSeconds).isZero()
    }

    // ── S4. NULL 추정치=0, 소프트 삭제 이슈 제외 ───────────────────────────────

    /**
     * Given  추정치 NULL 이슈(DONE) + 소프트 삭제된 이슈(DONE, 5h)
     * When   fetchVelocitySource 호출
     * Then   commitment=0, completed=0 — NULL 은 0 합산, 소프트 삭제 이슈는 완전히 제외.
     */
    @Test
    fun `S4 NULL 추정치는 0, 소프트삭제 이슈는 완전히 제외`() {
        val hourInSeconds = 3600
        val sprint1 = UUID.randomUUID()
        val nullEstimateIssue = insertIssue(1L, storyTypeId, DONE_KEY, originalEstimateSeconds = null)
        val deletedIssue = insertIssue(2L, storyTypeId, DONE_KEY, 5 * hourInSeconds)
        softDeleteIssue(deletedIssue.id.value)

        val result =
            adapter.fetchVelocitySource(
                mapOf(sprint1 to setOf(nullEstimateIssue.key.value, deletedIssue.key.value)),
                PROJECT_KEY,
                viewerUuid,
            )

        assertThat(result[sprint1]?.commitmentSeconds).isZero()
        assertThat(result[sprint1]?.completedSeconds).isZero()
    }

    // ── S5. 이슈 키가 빈 스프린트 → (0, 0) ─────────────────────────────────────

    /**
     * Given  이슈가 있는 스프린트 1개 + 이슈 키가 빈 스프린트 1개
     * When   fetchVelocitySource 를 둘 다 포함해 호출
     * Then   빈 스프린트는 commitment=0, completed=0.
     */
    @Test
    fun `S5 이슈 키가 빈 스프린트는 commitment completed 모두 0`() {
        val sprintWithIssues = UUID.randomUUID()
        val emptySprint = UUID.randomUUID()
        val issue = insertIssue(1L, storyTypeId, DONE_KEY, 3600)

        val result =
            adapter.fetchVelocitySource(
                mapOf(sprintWithIssues to setOf(issue.key.value), emptySprint to emptySet()),
                PROJECT_KEY,
                viewerUuid,
            )

        assertThat(result[emptySprint]?.commitmentSeconds).isZero()
        assertThat(result[emptySprint]?.completedSeconds).isZero()
    }

    // ── S6. 전체 합집합이 비어있으면 모든 스프린트가 (0, 0) — 조기 반환 ────────

    /**
     * Given  두 스프린트 모두 이슈 키가 빈 집합
     * When   fetchVelocitySource 호출
     * Then   두 스프린트 모두 (0, 0) — jOOQ 빈 `IN` 절 조회 없이 조기 반환.
     */
    @Test
    fun `S6 전체 이슈키 합집합이 비어있으면 모든 스프린트가 0,0`() {
        val sprint1 = UUID.randomUUID()
        val sprint2 = UUID.randomUUID()

        val result =
            adapter.fetchVelocitySource(mapOf(sprint1 to emptySet(), sprint2 to emptySet()), PROJECT_KEY, viewerUuid)

        assertThat(result).isEqualTo(
            mapOf(
                sprint1 to VelocityContribution(commitmentSeconds = 0L, completedSeconds = 0L),
                sprint2 to VelocityContribution(commitmentSeconds = 0L, completedSeconds = 0L),
            ),
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 이슈 1건을 VELOC 프로젝트에 삽입한다.
     *
     * @param seqNum 이슈 키 시퀀스 번호 (VELOC-N). 테스트 메서드 내에서 고유해야 한다.
     * @param typeId issue_types.id.
     * @param stateKey 현재 워크플로우 상태 키.
     * @param originalEstimateSeconds 추정 시간(초). null 이면 NULL 로 저장된다.
     * @param securityLevelId 보안 등급. null 이면 공개 등급.
     */
    private fun insertIssue(
        seqNum: Long,
        typeId: Long,
        stateKey: String,
        originalEstimateSeconds: Int? = null,
        securityLevelId: UUID? = null,
    ): Issue =
        issueRepository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of(PROJECT_KEY, seqNum),
                projectId = testProjectId,
                typeId = IssueTypeId(typeId),
                summary = "벨로시티 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = stateKey,
                securityLevelId = securityLevelId,
            ).copy(originalEstimateSeconds = originalEstimateSeconds),
        )

    /** 이슈를 소프트 삭제한다 (deleted_at 설정). */
    private fun softDeleteIssue(issueId: UUID) {
        getConnection().use { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE id = ?::uuid").use { stmt ->
                stmt.setString(1, issueId.toString())
                stmt.executeUpdate()
            }
        }
    }

    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(TestConfig.postgres.jdbcUrl, TestConfig.postgres.username, TestConfig.postgres.password)
            .placeholderReplacement(false)
            .locations(
                "classpath:db/migration/issue-tracking",
                "classpath:db/migration/project-workflow",
            )
            .load()
            .migrate()
    }

    /**
     * 전체 시드: 프로젝트 + 이슈 타입(story/gap) + 워크플로우 + 스킴(story 명시 매핑만, default 없음).
     *
     * gapTypeId 는 의도적으로 매핑을 제공하지 않는다 — S3 이 WorkflowSchemeNoDefaultException
     * catch 폴백을 검증하기 위함이다 ([IssueEpicProgressControllerIntegrationTest] S3 시드 전략과 동형).
     */
    private fun seedAll() {
        getConnection().use { conn ->
            conn.autoCommit = false
            seedProject(conn)
            seedIssueTypes(conn)
            seedWorkflowAndScheme(conn)
            conn.commit()
        }
    }

    private fun seedProject(conn: Connection) {
        conn.prepareStatement(
            "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
        ).use { stmt ->
            stmt.setString(1, PROJECT_KEY)
            stmt.setString(2, "Velocity Test Project")
            stmt.executeUpdate()
        }
        conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
            stmt.setString(1, PROJECT_KEY)
            stmt.executeQuery().use { rs ->
                rs.next()
                testProjectId = rs.getObject(1) as UUID
            }
        }
    }

    private fun seedIssueTypes(conn: Connection) {
        storyTypeId = findOrInsertType(conn, "velocity-story", "Velocity Story", 0)
        gapTypeId = findOrInsertType(conn, "velocity-gap", "Velocity Gap", 0)
    }

    @Suppress("NestedBlockDepth")
    private fun findOrInsertType(
        conn: Connection,
        key: String,
        name: String,
        hierarchyLevel: Int,
    ): Long {
        var id = -1L
        conn.prepareStatement("SELECT id FROM issue_types WHERE key = ? LIMIT 1").use { stmt ->
            stmt.setString(1, key)
            stmt.executeQuery().use { rs ->
                if (rs.next()) id = rs.getLong(1)
            }
        }
        if (id == -1L) {
            conn.prepareStatement(
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

    /**
     * veloc-workflow(open/in_progress/done) + veloc-scheme(story 타입 명시 매핑만, default 없음) 시드.
     * gapType 은 매핑이 전혀 없어 resolveExistingFor 호출 시 WorkflowSchemeNoDefaultException 이 발생한다.
     */
    @Suppress("NestedBlockDepth")
    private fun seedWorkflowAndScheme(conn: Connection) {
        val wfId = insertWorkflow(conn, "veloc-workflow", "벨로시티 테스트 워크플로우")
        insertState(conn, wfId, OPEN_KEY, "Open", "TODO", 0)
        insertState(conn, wfId, IN_PROGRESS_KEY, "In Progress", "IN_PROGRESS", 1)
        insertState(conn, wfId, DONE_KEY, "Done", "DONE", 2)

        val schemeId: Long =
            conn.prepareStatement(
                """
                INSERT INTO workflow_schemes (key, name, is_default)
                VALUES ('veloc-scheme', 'Velocity Test Scheme', false)
                ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id
                """.trimIndent(),
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }

        // story 타입 명시 매핑만 존재 — default mapping 없음 (gapType 은 매핑 부재)
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                SELECT $schemeId, t.id, '$wfId'
                FROM issue_types t WHERE t.key = 'velocity-story'
                ON CONFLICT (scheme_id, issue_type_id) DO UPDATE SET workflow_id = '$wfId'
                """.trimIndent(),
            )
        }

        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                SELECT p.id, $schemeId, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                FROM projects p WHERE p.key = '$PROJECT_KEY'
                ON CONFLICT (project_id) DO UPDATE SET workflow_scheme_id = $schemeId
                """.trimIndent(),
            )
        }
    }

    private fun insertWorkflow(
        conn: Connection,
        key: String,
        name: String,
    ): UUID =
        conn.prepareStatement(
            "INSERT INTO workflows (key, name) VALUES (?, ?) ON CONFLICT (key) WHERE deleted_at IS NULL DO UPDATE SET name = EXCLUDED.name RETURNING id",
        ).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, name)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
            }
        }

    @Suppress("LongParameterList")
    private fun insertState(
        conn: Connection,
        wfId: UUID,
        key: String,
        name: String,
        category: String,
        displayOrder: Int,
    ): UUID =
        insertWorkflowStatus(conn, wfId, key, name, category, displayOrder)

    private fun getConnection(): Connection =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}

/**
 * accessibleLevels 반환값을 테스트별로 제어하는 [IssueSecurityDirectory] stub.
 *
 * 필터 로직 자체는 정본 `buildActiveSecureWhere` SQL 술어가 담당한다 — 이 stub 은 access 값만 전달한다
 * ([SprintBurndownLookupAdapterIntegrationTest.StubSecurityDirectory] 선례와 동일 패턴).
 */
class SwitchableSecurityDirectory : IssueSecurityDirectory {
    var access: IssueSecurityAccess = UNRESTRICTED

    override fun levelBelongsToProjectScheme(
        levelId: UUID,
        projectKey: String,
    ): Boolean = true

    override fun accessibleLevels(
        actorId: UUID,
        projectKey: String,
    ): IssueSecurityAccess = access

    fun reset() {
        access = UNRESTRICTED
    }

    companion object {
        /** 필터 미적용 빠른경로 — 모든 보안 등급 열람 가능. */
        val UNRESTRICTED =
            IssueSecurityAccess(
                unrestricted = true,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        /** unrestricted=false + staticLevelIds=empty — 보안 등급이 부여된 이슈를 전부 제외. */
        val RESTRICTED =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )
    }
}
