// IssueImportAdapter 통합 테스트 — 행 원자성/권한 위임/이메일·이름 매핑 (FR-IM-01 Task 8, 실 repo+시드)

package com.bts.issue.adapter.outbound.imports

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.IssueImportStatusService
import com.bts.issue.component.application.ComponentApplicationService
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.project.repository.ProjectLookupRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.application.VersionApplicationService
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportResult
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.VersionPermission
import com.bts.shared.permission.VersionPermissionResolver
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
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
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.web.context.WebApplicationContext
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * [IssueImportAdapter] 전 구간 Testcontainers 통합 테스트 (FR-IM-01 Task 8).
 *
 * 어댑터 → [IssueApplicationService] → 실 repository → 실 PostgreSQL 전 구간을 검증한다.
 * mockk 로는 표면화되지 않는 트랜잭션 경계(행 원자성)·권한 위임·jOOQ 매핑을 실증한다.
 *
 * ## 검증 시나리오
 * - S1. 코어 필드 + typeName 매칭(대소문자무시) + priority/labels/assignee 반영
 * - S2. reporterEmail 매칭 → reporterId=매칭 사용자 / 미매칭 → requesterUserId 폴백
 * - S3. assigneeEmail 매칭 → assigneeId=매칭 사용자 / 미매칭 → assigneeId=null
 * - S4. typeName 미매칭 → Task 폴백 + warning
 * - S5. componentNames 일부 미발견 → 매칭분만 연결 + 미발견 warning
 * - S6. CREATE_ISSUE 권한 없는 requester → Failure(FORBIDDEN), 이슈 미생성
 * - S7. dryRun=true → 이슈/이벤트 미생성, 검증 결과만 반환
 * - S8(CONCERN #1). update 강제 실패(priority 범위 밖) → 이슈 롤백 + IssueCreated 이벤트 미발행
 * - S9(CONCERN C1). dryRun 은 update-유발 행(priority/assignee)에 대해 UPDATE 권한도 미러 예측한다 —
 *   실제 경로(executeImport)가 priority/labels 지정 시 updateIssue, assignee 매칭 시 changeAssignee 를
 *   호출해 IssuePermission.UPDATE 를 검증하므로, dryRun 도 동일 조건에서 UPDATE 를 확인해야
 *   "dryRun 성공 → 실제 실행 시 FORBIDDEN" 불일치를 막는다.
 */
@Suppress("LargeClass") // Task 4 — 컴포넌트/버전 자동생성+상태 반영 시나리오 10건 추가로 임계 초과, 분리 실익 없음
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class, IssueImportAdapterTest.ImportTestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueImportAdapterTest {
    /**
     * import 어댑터 통합 테스트 보조 설정.
     *
     * 실 [ComponentRepository] + 선택적 거부 [IssuePermissionResolver] + fixture [UserLookupPort] 로
     * [TestConfig] 의 stub 들을 대체하고, 이를 사용하는 [IssueApplicationService] 를 [Primary] 로 재조립한다.
     */
    @Configuration
    @Suppress("LongParameterList")
    open class ImportTestConfig {
        // [TestConfig.dslContext] 는 DSL.using(rawDataSource, ...) 로 구성돼 jOOQ 가 Spring 트랜잭션 동기화를
        // 거치지 않고 매 쿼리마다 DriverManagerDataSource.getConnection() 을 직접 호출해 autoCommit=true 인
        // 별도 커넥션을 매번 새로 연다(jOOQ DataSourceConnectionProvider 는 Spring-aware 조회를 하지 않음).
        // 그 결과 createIssue 의 INSERT 가 즉시 커밋돼버려 이후 setRollbackOnly() 가 무력화된다
        // (행 원자성 CONCERN #1 RED 단계에서 실제로 표면화됨 — 다른 기존 통합 테스트는 실패 지점이 항상
        // insert 이전(validate)이라 이 gap 을 드러낸 적이 없었다). TransactionAwareDataSourceProxy 로 감싸
        // jOOQ 의 getConnection() 이 DataSourceUtils 를 경유해 스레드 바인딩된 트랜잭션 커넥션을 재사용하도록
        // 강제한다 — Spring Boot 의 실제 jOOQ 자동구성(JooqAutoConfiguration)이 프로덕션에서 하는 것과 동일한
        // 보정을 이 테스트 컨텍스트에도 적용한다.
        @Bean
        @Primary
        open fun importTransactionAwareDslContext(dataSource: DriverManagerDataSource): DSLContext =
            DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)

        @Bean
        open fun importComponentRepository(dsl: DSLContext): ComponentRepository = ComponentRepository(dsl)

        // 실 repository 필요 — mockk(relaxed=true) 는 UUID? 반환 메서드에도 nil UUID 를 fabricate 해
        // ActorId(nil UUID) 로 이어져 resolveDefaultAssignee 가 예기치 않게 실패한다(AutoAssignTestConfig 동형 함정).
        @Bean
        open fun importProjectLeadRepository(dsl: DSLContext): ProjectLeadRepository = ProjectLeadRepository(dsl)

        @Bean
        open fun importVersionRepository(dsl: DSLContext): VersionRepository = VersionRepository(dsl)

        @Bean
        @Primary
        open fun importUserLookupPort(): UserLookupPort = FakeImportUserLookupPort()

        @Bean
        @Primary
        open fun importPermissionResolver(): IssuePermissionResolver =
            SelectiveDenyPermissionResolver(
                denied =
                    setOf(
                        FORBIDDEN_REQUESTER_ID to IssuePermission.CREATE,
                        UPDATE_DENIED_REQUESTER_ID to IssuePermission.UPDATE,
                        TRANSITION_DENIED_REQUESTER_ID to IssuePermission.TRANSITION,
                    ),
            )

        // ── Task 4 — 컴포넌트/버전 자동생성 + 상태 반영 보조 빈 ────────────────────

        @Bean
        open fun importProjectLookupRepository(dsl: DSLContext): ProjectLookupRepository = ProjectLookupRepository(dsl)

        @Bean
        open fun importProjectLookup(repository: ProjectLookupRepository): ProjectLookup = ProjectLookup(repository)

        /** 컴포넌트 CREATE 는 [AUTO_CREATE_ALLOWED_REQUESTER_ID] 만 허용, 그 외(NORMAL_REQUESTER_ID 포함)는 기본 거부. */
        @Bean
        open fun importComponentPermissionResolver(): ComponentPermissionResolver =
            SelectiveAllowComponentPermissionResolver(allowed = setOf(AUTO_CREATE_ALLOWED_REQUESTER_ID))

        /** 버전 CREATE 도 컴포넌트와 동형 — [AUTO_CREATE_ALLOWED_REQUESTER_ID] 만 허용, 기본 거부. */
        @Bean
        open fun importVersionPermissionResolver(): VersionPermissionResolver =
            SelectiveAllowVersionPermissionResolver(allowed = setOf(AUTO_CREATE_ALLOWED_REQUESTER_ID))

        @Bean
        open fun importComponentApplicationService(
            permissionResolver: ComponentPermissionResolver,
            projectLookup: ProjectLookup,
            userLookupPort: UserLookupPort,
            repo: ComponentRepository,
        ): ComponentApplicationService =
            ComponentApplicationService(
                permissionResolver = permissionResolver,
                projectLookup = projectLookup,
                userLookupPort = userLookupPort,
                repo = repo,
            )

        @Bean
        open fun importVersionApplicationService(
            permissionResolver: VersionPermissionResolver,
            projectLookup: ProjectLookup,
            repo: VersionRepository,
            clock: Clock,
        ): VersionApplicationService =
            VersionApplicationService(
                permissionResolver = permissionResolver,
                projectLookup = projectLookup,
                repo = repo,
                clock = clock,
            )

        // 고정 상태 목록만 반환하는 테스트 전용 WorkflowStateCatalog — project-workflow BC 의 실제
        // 스킴 결선(WorkflowResolverImpl 등)은 이 어댑터 테스트의 책임 범위 밖이다(BC 격리).
        // IssueImportStatusService 자체의 상태 매칭 로직은 IssueImportStatusServiceTest(mockk)가
        // 이미 단위 검증했으므로, 여기서는 어댑터의 호출·OCC 버전 스레딩·경고 강등만 검증한다.
        @Bean
        open fun importWorkflowStateCatalog(): WorkflowStateCatalog =
            FixedStatesWorkflowStateCatalog(
                states =
                    listOf(
                        WorkflowStateView(key = "open", name = "Open"),
                        WorkflowStateView(key = "in_progress", name = "In Progress"),
                        WorkflowStateView(key = "done", name = "Done", isDone = true, category = "DONE"),
                    ),
            )

        @Bean
        open fun importIssueImportStatusService(
            issueRepository: IssueRepository,
            workflowStateCatalog: WorkflowStateCatalog,
            permissionResolver: IssuePermissionResolver,
            issueTypeRepository: IssueTypeRepository,
        ): IssueImportStatusService =
            IssueImportStatusService(
                issueRepository = issueRepository,
                workflowStateCatalog = workflowStateCatalog,
                permissionResolver = permissionResolver,
                issueTypeRepository = issueTypeRepository,
            )

        @Bean
        @Primary
        open fun issueApplicationServiceForImport(
            repo: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            eventPublisher: IssueEventPublisher,
            permissionResolver: IssuePermissionResolver,
            workflowTransitionAdapter: WorkflowTransitionAdapter,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            userLookupPort: UserLookupPort,
            componentRepository: ComponentRepository,
            projectLeadRepository: ProjectLeadRepository,
            versionRepository: VersionRepository,
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
                componentRepository = componentRepository,
                projectLeadRepository = projectLeadRepository,
                versionRepository = versionRepository,
                clock = clock,
                historyRecorder = mockk(relaxed = true),
            )

        @Bean
        @Suppress("LongParameterList")
        open fun issueImportAdapter(
            issueApplicationService: IssueApplicationService,
            issueRepository: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            componentRepository: ComponentRepository,
            userLookupPort: UserLookupPort,
            permissionResolver: IssuePermissionResolver,
            componentApplicationService: ComponentApplicationService,
            versionApplicationService: VersionApplicationService,
            versionRepository: VersionRepository,
            componentPermissionResolver: ComponentPermissionResolver,
            versionPermissionResolver: VersionPermissionResolver,
            issueImportStatusService: IssueImportStatusService,
        ): IssueImportAdapter =
            IssueImportAdapter(
                issueApplicationService = issueApplicationService,
                issueRepository = issueRepository,
                issueTypeRepository = issueTypeRepository,
                componentRepository = componentRepository,
                userLookupPort = userLookupPort,
                permissionResolver = permissionResolver,
                componentApplicationService = componentApplicationService,
                versionApplicationService = versionApplicationService,
                versionRepository = versionRepository,
                componentPermissionResolver = componentPermissionResolver,
                versionPermissionResolver = versionPermissionResolver,
                issueImportStatusService = issueImportStatusService,
            )
    }

    /** (actor, permission) 조합을 지정해 거부하고 그 외에는 모두 허용하는 테스트 전용 resolver. */
    private class SelectiveDenyPermissionResolver(
        private val denied: Set<Pair<UUID, IssuePermission>>,
    ) : IssuePermissionResolver {
        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean = (actorId to permission) !in denied
    }

    /**
     * actorId 가 [allowed] 집합에 있을 때만 컴포넌트 CREATE 를 허용하고 그 외에는 모두 거부하는
     * 테스트 전용 resolver — 기본 거부(NORMAL_REQUESTER_ID 등 기존 시나리오의 actor 는 모두 미포함이라
     * 기존 S1~S9 는 영향받지 않는다).
     */
    private class SelectiveAllowComponentPermissionResolver(
        private val allowed: Set<UUID>,
    ) : ComponentPermissionResolver {
        override fun hasPermission(
            actorId: UUID,
            permission: ComponentPermission,
            projectId: UUID,
        ): Boolean = actorId in allowed
    }

    /** [SelectiveAllowComponentPermissionResolver] 와 동형 — 버전 CREATE 전용. */
    private class SelectiveAllowVersionPermissionResolver(
        private val allowed: Set<UUID>,
    ) : VersionPermissionResolver {
        override fun hasPermission(
            actorId: UUID,
            permission: VersionPermission,
            projectId: UUID,
        ): Boolean = actorId in allowed
    }

    /**
     * 고정 상태 목록만 반환하는 테스트 전용 [WorkflowStateCatalog].
     *
     * project-workflow BC 의 실제 스킴 결선은 이 어댑터 테스트의 책임 범위 밖이다(BC 격리) —
     * [ImportTestConfig.importWorkflowStateCatalog] KDoc 참조.
     *
     * `open` 필수 — [WorkflowStateCatalog.listStates] 의 인터페이스 레벨 `@Transactional` 을
     * [TestConfig] 의 `@EnableTransactionManagement(proxyTargetClass = true)` 가 CGLIB 서브클래싱
     * 으로 감싸려 시도하는데, Kotlin 클래스는 기본 final 이라 `open` 없이는 Enhancer 가 실패한다.
     */
    private open class FixedStatesWorkflowStateCatalog(
        private val states: List<WorkflowStateView>,
    ) : WorkflowStateCatalog {
        override fun listStates(
            projectKey: ProjectKey,
            issueTypeKey: IssueTypeKey?,
        ): List<WorkflowStateView> = states
    }

    /** 고정 fixture 이메일→UUID 매핑을 제공하는 테스트 전용 [UserLookupPort]. */
    private class FakeImportUserLookupPort : UserLookupPort {
        override fun exists(userId: UUID): Boolean = true

        override fun resolveByEmails(emails: Set<String>): Map<String, UUID> = FIXTURES.filterKeys { it in emails }

        companion object {
            private val FIXTURES =
                mapOf(
                    ALICE_EMAIL to ALICE_ID,
                    BOB_EMAIL to BOB_ID,
                )
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueImportAdapter: IssueImportAdapter

    @Autowired
    lateinit var issueRepository: IssueRepository

    companion object {
        private const val PROJECT_KEY = "IMPORT"

        /** CREATE_ISSUE 권한을 보유한 정상 요청자. */
        val NORMAL_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000201")

        /** [SelectiveDenyPermissionResolver] 가 CREATE_ISSUE 를 거부하도록 지정한 요청자. */
        val FORBIDDEN_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000202")

        /**
         * [SelectiveDenyPermissionResolver] 가 EDIT_ISSUE(UPDATE) 만 거부하도록 지정한 요청자
         * (CREATE_ISSUE 는 보유). S9(CONCERN C1) — dryRun 이 update-유발 행에 대해 UPDATE 권한도
         * 미러 예측하는지 검증한다.
         */
        val UPDATE_DENIED_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000205")

        /**
         * [SelectiveAllowComponentPermissionResolver]/[SelectiveAllowVersionPermissionResolver] 가
         * 컴포넌트/버전 CREATE 를 허용하도록 지정한 요청자(S10/S12/S16/S18). 그 외 모든 actor(
         * NORMAL_REQUESTER_ID 포함)는 기본 거부 — 기존 S5(컴포넌트 미발견 skip) 시나리오 불변.
         */
        val AUTO_CREATE_ALLOWED_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000206")

        /** [SelectiveDenyPermissionResolver] 가 TRANSITION 만 거부하도록 지정한 요청자(S15/S19). */
        val TRANSITION_DENIED_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000207")

        /** reporterEmail 매칭 fixture. */
        val ALICE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000203")
        const val ALICE_EMAIL = "alice@example.com"

        /** assigneeEmail 매칭 fixture. */
        val BOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000204")
        const val BOB_EMAIL = "bob@example.com"

        /** S17 — 사전 시드된 기존 버전 이름(자동생성 권한과 무관하게 이름 매칭만으로 링크된다). */
        const val EXISTING_VERSION_NAME = "v1.0-existing"

        private var migrated = false
        private var seeded = false

        /** S5 — 매칭 대상 컴포넌트. */
        lateinit var backendComponentId: UUID

        /** S17 — 매칭 대상 버전(사전 시드). */
        lateinit var existingVersionId: UUID
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProjectAndComponents()
            seeded = true
        }
    }

    @BeforeEach
    fun cleanState() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    "DELETE FROM issue_components WHERE issue_id IN " +
                        "(SELECT id FROM issues WHERE key LIKE '$PROJECT_KEY-%')",
                )
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
                stmt.execute("SELECT pgmq.purge_queue('q_issue_events')")
            }
        }
    }

    // ── S1. 코어 필드 + typeName 매칭 + priority/labels/assignee 반영 ─────────────

    @Test
    fun `S1 코어 필드 cmd - 이슈 생성 성공과 issueKey 반환 및 priority labels assignee typeName 반영`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S1 코어 필드 임포트 테스트",
                typeName = "bug",
                priority = 1,
                assigneeEmail = BOB_EMAIL,
                labels = listOf("backend", "urgent"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.issueKey.startsWith("$PROJECT_KEY-")) {
            "issueKey 가 $PROJECT_KEY- 로 시작해야 하지만 ${result.issueKey} 입니다."
        }

        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved) { "생성된 이슈를 DB 에서 찾을 수 없습니다: ${result.issueKey}" }
        assert(saved.priority == 1) { "priority 가 1 이어야 하지만 ${saved.priority} 입니다." }
        assert(saved.labels == listOf("backend", "urgent")) {
            "labels 가 [backend, urgent] 여야 하지만 ${saved.labels} 입니다."
        }
        assert(saved.assigneeId?.value == BOB_ID) {
            "assigneeId 가 Bob 이어야 하지만 ${saved.assigneeId?.value} 입니다."
        }
        assert(saved.reporterId.value == NORMAL_REQUESTER_ID) {
            "reporterEmail 미지정이므로 reporterId 가 requesterUserId 여야 하지만 ${saved.reporterId.value} 입니다."
        }

        val typeKey = fetchTypeKey(result.issueKey)
        assert(typeKey == "bug") { "typeName='bug' 매칭이므로 typeKey='bug' 여야 하지만 $typeKey 입니다." }
    }

    // ── S2. reporterEmail 매칭/미매칭 ────────────────────────────────────────────

    @Test
    fun `S2 reporterEmail 매칭 - reporterId가 매칭된 사용자로 설정된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S2 reporter 매칭 테스트",
                reporterEmail = ALICE_EMAIL,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.reporterId.value == ALICE_ID) {
            "reporterEmail 매칭이므로 reporterId 가 Alice 여야 하지만 ${saved.reporterId.value} 입니다."
        }
    }

    @Test
    fun `S2 reporterEmail 미매칭 - reporterId가 requesterUserId로 폴백된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S2 reporter 미매칭 테스트",
                reporterEmail = "unknown@example.com",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.reporterId.value == NORMAL_REQUESTER_ID) {
            "reporterEmail 미매칭이므로 reporterId 가 requesterUserId 로 폴백돼야 하지만 ${saved.reporterId.value} 입니다."
        }
    }

    // ── S3. assigneeEmail 매칭/미매칭 ────────────────────────────────────────────

    @Test
    fun `S3 assigneeEmail 매칭 - assigneeId가 매칭된 사용자로 설정된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S3 assignee 매칭 테스트",
                assigneeEmail = BOB_EMAIL,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.assigneeId?.value == BOB_ID) {
            "assigneeEmail 매칭이므로 assigneeId 가 Bob 이어야 하지만 ${saved.assigneeId?.value} 입니다."
        }
    }

    @Test
    fun `S3 assigneeEmail 미매칭 - assigneeId가 null로 유지된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S3 assignee 미매칭 테스트",
                assigneeEmail = "unknown@example.com",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.assigneeId == null) {
            "assigneeEmail 미매칭이므로 assigneeId 가 null 이어야 하지만 ${saved.assigneeId?.value} 입니다."
        }
    }

    // ── S4. typeName 미매칭 → Task 폴백 + warning ────────────────────────────────

    @Test
    fun `S4 typeName 미매칭 - Task 타입으로 폴백하고 warning을 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S4 typeName 미매칭 테스트",
                typeName = "Improvement",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.isNotEmpty()) { "typeName 미매칭 warning 이 있어야 하지만 비어 있습니다." }

        val typeKey = fetchTypeKey(result.issueKey)
        assert(typeKey == "task") { "typeName 미매칭이므로 Task 로 폴백돼야 하지만 typeKey=$typeKey 입니다." }
    }

    // ── S5. componentNames 일부 미발견 → 매칭분만 연결 + warning ──────────────────

    @Test
    fun `S5 componentNames 일부 미발견 - 매칭된 컴포넌트만 연결하고 미발견 이름은 warning으로 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S5 component 매칭 테스트",
                componentNames = listOf("backend", "Nonexistent"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("Nonexistent") }) {
            "미발견 컴포넌트 이름에 대한 warning 이 있어야 하지만 ${result.warnings} 입니다."
        }

        val connectedComponentIds = fetchComponentIds(result.issueKey)
        assert(connectedComponentIds == setOf(backendComponentId)) {
            "매칭된 backend 컴포넌트만 연결돼야 하지만 $connectedComponentIds 입니다."
        }
    }

    // ── S6. CREATE_ISSUE 권한 없는 requester → Failure(FORBIDDEN), 이슈 미생성 ────

    @Test
    fun `S6 CREATE_ISSUE 권한 없는 requester - Failure FORBIDDEN 이고 이슈가 생성되지 않는다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = FORBIDDEN_REQUESTER_ID,
                summary = "S6 권한 없음 테스트",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
        assert(countImportIssues() == 0) { "권한 거부 시 이슈가 생성되지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
    }

    // ── S7. dryRun=true → 이슈/이벤트 미생성, 검증 결과만 반환 ───────────────────

    @Test
    fun `S7 dryRun true - 이슈를 생성하지 않고 검증 결과만 반환한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S7 dryRun 테스트",
                priority = 2,
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.issueKey == IssueImportAdapter.DRY_RUN_MARKER) {
            "dryRun 은 마커 값을 반환해야 하지만 ${result.issueKey} 입니다."
        }
        assert(countImportIssues() == 0) { "dryRun 은 이슈를 생성하지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
        assert(fetchKeySequence() == 0L) { "dryRun 은 key_sequence 를 증가시키지 않아야 하지만 ${fetchKeySequence()} 입니다." }
    }

    // ── S8(CONCERN #1). update 강제 실패 → 이슈 롤백 + IssueCreated 이벤트 미발행 ──

    /**
     * 행 원자성 — create 는 성공하지만 후속 updateIssue(priority 범위 밖=99)가
     * IllegalArgumentException 을 던지는 경우, 트랜잭션 전체가 롤백돼야 한다.
     *
     * Given priority=99(1..5 범위 밖)
     * When  importIssue 호출
     * Then  Failure(VALIDATION) 반환
     * And   이슈가 DB 에 남아있지 않음(create 롤백)
     * And   q_issue_events 큐에 IssueCreated 메시지가 없음(pgmq.send 도 같은 트랜잭션이라 함께 롤백)
     */
    @Test
    fun `S8 update 강제 실패시 이슈가 롤백되고 IssueCreated 이벤트가 발행되지 않는다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S8 행원자성 테스트",
                priority = 99,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.VALIDATION) {
            "reasonCode 가 VALIDATION 이어야 하지만 ${result.reasonCode} 입니다."
        }
        assert(countImportIssues() == 0) {
            "update 실패 시 create 까지 롤백되어 이슈가 없어야 하지만 ${countImportIssues()} 개 존재합니다."
        }
        assert(readIssueEventsQueue().isEmpty()) {
            "롤백된 트랜잭션의 IssueCreated 이벤트는 q_issue_events 에 존재하지 않아야 합니다."
        }
    }

    // ── S9(C1). dryRun — update-유발 행은 UPDATE 권한도 미러 예측 ────────────────

    /**
     * priority 지정 행은 실제 경로(executeImport)에서 updateIssue(IssuePermission.UPDATE)를
     * 호출한다. UPDATE 권한이 없는 [UPDATE_DENIED_REQUESTER_ID] 로 dryRun 하면 실제 실행과
     * 동일하게 FORBIDDEN 이어야 한다(수정 전에는 CREATE 만 확인해 Success 를 오보 — RED).
     */
    @Test
    fun `S9 dryRun priority 지정 - UPDATE 권한 없는 actor는 FORBIDDEN을 반환한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S9 update 권한 없음 - priority",
                priority = 2,
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
        assert(countImportIssues() == 0) { "dryRun 은 이슈를 생성하지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
    }

    /**
     * labels 지정 행도 priority 와 동일하게 updateIssue 경로를 유발하므로 UPDATE 권한 없는
     * actor 는 dryRun 에서도 FORBIDDEN 이어야 한다.
     */
    @Test
    fun `S9 dryRun labels 지정 - UPDATE 권한 없는 actor는 FORBIDDEN을 반환한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S9 update 권한 없음 - labels",
                labels = listOf("urgent"),
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
    }

    /**
     * assigneeEmail 매칭 행은 실제 경로에서 changeAssignee(IssuePermission.UPDATE)를 호출한다.
     * UPDATE 권한 없는 actor 는 dryRun 에서도 FORBIDDEN 이어야 한다.
     */
    @Test
    fun `S9 dryRun assignee 매칭 - UPDATE 권한 없는 actor는 FORBIDDEN을 반환한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S9 update 권한 없음 - assignee",
                assigneeEmail = BOB_EMAIL,
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
    }

    /**
     * assigneeEmail 이 미매칭이면 실제 경로에서 changeAssignee 를 호출하지 않으므로(resolution.assigneeId
     * == null) UPDATE 를 유발하지 않는다. UPDATE 권한이 없어도 Success 여야 한다.
     */
    @Test
    fun `S9 dryRun assignee 미매칭 - UPDATE 권한 없어도 Success이다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S9 update 권한 없음 - assignee 미매칭",
                assigneeEmail = "unknown@example.com",
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.issueKey == IssueImportAdapter.DRY_RUN_MARKER)
    }

    /**
     * summary/description 만 있는 행(priority/labels/assignee 모두 없음)은 실제 경로에서
     * createIssue 만 호출하고 updateIssue/changeAssignee 는 유발하지 않는다. UPDATE 권한이
     * 없어도 CREATE 만으로 Success 여야 한다(실제 경로와 일치).
     */
    @Test
    fun `S9 dryRun summary description만 - UPDATE 미유발 행은 UPDATE 권한 없어도 Success이다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S9 update 권한 없음 - update 미유발",
                description = "본문만 있는 행",
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.issueKey == IssueImportAdapter.DRY_RUN_MARKER) {
            "dryRun 은 마커 값을 반환해야 하지만 ${result.issueKey} 입니다."
        }
        assert(countImportIssues() == 0) { "dryRun 은 이슈를 생성하지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
    }

    // ── S10. 컴포넌트 자동생성 — CREATE 권한 있으면 create 후 연결 ──────────────────

    @Test
    fun `S10 컴포넌트 미존재 CREATE 권한 있음 - 자동생성 후 이슈에 연결된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = AUTO_CREATE_ALLOWED_REQUESTER_ID,
                summary = "S10 컴포넌트 자동생성 테스트",
                componentNames = listOf("AutoComponent"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val createdComponentId = findComponentIdOrNull(PROJECT_KEY, "AutoComponent")
        checkNotNull(createdComponentId) { "CREATE 권한이 있으므로 컴포넌트가 자동생성돼야 합니다." }
        assert(fetchComponentIds(result.issueKey) == setOf(createdComponentId)) {
            "자동생성된 컴포넌트가 이슈에 연결돼야 하지만 ${fetchComponentIds(result.issueKey)} 입니다."
        }
    }

    // ── S11. 컴포넌트 자동생성 — CREATE 권한 없으면 create 미호출 + 경고 ───────────

    @Test
    fun `S11 컴포넌트 미존재 CREATE 권한 없음 - create 미호출 경고만 남기고 이슈는 생성된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S11 컴포넌트 자동생성 권한없음 테스트",
                componentNames = listOf("NoPermComponent"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "이슈 생성 자체는 성공해야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("NoPermComponent") }) {
            "생성 권한 없음 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(findComponentIdOrNull(PROJECT_KEY, "NoPermComponent") == null) {
            "CREATE 권한이 없으므로 컴포넌트가 생성되지 않아야 합니다."
        }
        assert(fetchComponentIds(result.issueKey).isEmpty()) {
            "연결된 컴포넌트가 없어야 하지만 ${fetchComponentIds(result.issueKey)} 입니다."
        }
    }

    // ── S12. 버전 자동생성 — CREATE 권한 있으면 create 후 fix/affects 연결 ─────────

    @Test
    fun `S12 fix affects 버전 미존재 CREATE 권한 있음 - 자동생성 후 각각 연결된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = AUTO_CREATE_ALLOWED_REQUESTER_ID,
                summary = "S12 버전 자동생성 테스트",
                fixVersionNames = listOf("AutoFixVersion"),
                affectsVersionNames = listOf("AutoAffectsVersion"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val fixVersionId = findVersionIdOrNull(PROJECT_KEY, "AutoFixVersion")
        val affectsVersionId = findVersionIdOrNull(PROJECT_KEY, "AutoAffectsVersion")
        checkNotNull(fixVersionId) { "CREATE 권한이 있으므로 fix 버전이 자동생성돼야 합니다." }
        checkNotNull(affectsVersionId) { "CREATE 권한이 있으므로 affects 버전이 자동생성돼야 합니다." }
        assert(fetchFixVersionIds(result.issueKey) == setOf(fixVersionId)) {
            "자동생성된 fix 버전이 연결돼야 하지만 ${fetchFixVersionIds(result.issueKey)} 입니다."
        }
        assert(fetchAffectsVersionIds(result.issueKey) == setOf(affectsVersionId)) {
            "자동생성된 affects 버전이 연결돼야 하지만 ${fetchAffectsVersionIds(result.issueKey)} 입니다."
        }
    }

    // ── S13. 버전 자동생성 — CREATE 권한 없으면 create 미호출 + 경고 ───────────────

    @Test
    fun `S13 fix 버전 미존재 CREATE 권한 없음 - create 미호출 경고만 남기고 이슈는 생성된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S13 버전 자동생성 권한없음 테스트",
                fixVersionNames = listOf("NoPermVersion"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "이슈 생성 자체는 성공해야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("NoPermVersion") }) {
            "생성 권한 없음 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(findVersionIdOrNull(PROJECT_KEY, "NoPermVersion") == null) {
            "CREATE 권한이 없으므로 버전이 생성되지 않아야 합니다."
        }
        assert(fetchFixVersionIds(result.issueKey).isEmpty()) {
            "연결된 fix 버전이 없어야 하지만 ${fetchFixVersionIds(result.issueKey)} 입니다."
        }
    }

    // ── S14~S16. statusName 반영 — Applied/NoMatch/NoPermission (best-effort) ────

    @Test
    fun `S14 statusName 매칭 - 상태가 direct-set 되고 이슈가 새 상태로 저장된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S14 상태 반영 테스트",
                statusName = "In Progress",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.currentStateKey == "in_progress") {
            "statusName 매칭이므로 in_progress 로 direct-set 돼야 하지만 ${saved.currentStateKey} 입니다."
        }
    }

    @Test
    fun `S15 statusName 미매칭 - 경고를 남기고 시작 상태를 유지한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S15 상태 미매칭 테스트",
                statusName = "Nonexistent Status",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("Nonexistent Status") }) {
            "상태 미매칭 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.currentStateKey == "open") {
            "미매칭이므로 시작 상태(open)를 유지해야 하지만 ${saved.currentStateKey} 입니다."
        }
    }

    @Test
    fun `S16 statusName 지정 - TRANSITION 권한 없으면 경고를 남기고 시작 상태를 유지한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = TRANSITION_DENIED_REQUESTER_ID,
                summary = "S16 상태 권한없음 테스트",
                statusName = "In Progress",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.isNotEmpty()) { "TRANSITION 권한없음 경고가 있어야 하지만 비어 있습니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.currentStateKey == "open") {
            "TRANSITION 권한이 없으므로 시작 상태(open)를 유지해야 하지만 ${saved.currentStateKey} 입니다."
        }
    }

    // ── S17~S20(C1 확장). dryRun — 컴포넌트/버전 자동생성·링크 미러 ────────────────

    /**
     * dryRun 은 CREATE 권한이 있어도 실제 [com.bts.issue.component.application.ComponentApplicationService.create]
     * 를 호출하지 않는다 — 권한 확인만 수행한다(★ 최우선 제약).
     */
    @Test
    fun `S17 dryRun 컴포넌트 미존재 CREATE 권한 있음 - 권한만 확인하고 실제 생성은 하지 않는다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = AUTO_CREATE_ALLOWED_REQUESTER_ID,
                summary = "S17 dryRun 컴포넌트 미생성 테스트",
                componentNames = listOf("DryRunComponent"),
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(findComponentIdOrNull(PROJECT_KEY, "DryRunComponent") == null) {
            "dryRun 은 실제 컴포넌트를 생성하지 않아야 합니다."
        }
    }

    /**
     * fix/affects 버전 링크는 [com.bts.issue.application.IssueApplicationService.changeFixVersions]/
     * [com.bts.issue.application.IssueApplicationService.changeAffectsVersions] 를 통해 UPDATE 권한을
     * 요구한다(§UPDATE 미러 확장, CONCERN C1). 이미 존재하는 버전 이름을 사용해 CREATE 권한과
     * 무관하게(매칭이라 자동생성 불필요) UPDATE 권한 부재만 분리 검증한다.
     */
    @Test
    fun `S18 dryRun fixVersionNames 기존 버전 매칭 - UPDATE 권한 없는 actor는 FORBIDDEN을 반환한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S18 dryRun 버전링크 UPDATE 권한없음 테스트",
                fixVersionNames = listOf(EXISTING_VERSION_NAME),
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
    }

    /**
     * 미매칭+CREATE 권한 있는 버전 이름도(실제 생성 없이) 센티널로 대체돼 "링크를 유발한다" 는
     * 판정에 반영된다 — UPDATE 권한까지 있으면 FORBIDDEN 으로 잘못 예측하지 않고 Success 여야 한다.
     * dryRun 이므로 실제 [com.bts.issue.version.application.VersionApplicationService.create] 는
     * 호출되지 않는다.
     */
    @Test
    fun `S19 dryRun fixVersionNames 미존재 CREATE UPDATE 권한 모두 있음 - Success이고 실제 생성은 없다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = AUTO_CREATE_ALLOWED_REQUESTER_ID,
                summary = "S19 dryRun 버전링크 권한있음 테스트",
                fixVersionNames = listOf("DryRunSentinelVersion"),
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(findVersionIdOrNull(PROJECT_KEY, "DryRunSentinelVersion") == null) {
            "dryRun 은 실제 버전을 생성하지 않아야 합니다."
        }
    }

    /**
     * statusName 반영은 [com.bts.issue.application.IssueImportStatusService] 가 best-effort 로
     * 처리하므로(권한 없어도 예외 없음) TRANSITION 권한 미리보기도 FORBIDDEN 이 아닌 경고로만
     * 남는다 — dryRun 도 Success 를 유지한다.
     */
    @Test
    fun `S20 dryRun statusName 지정 - TRANSITION 권한 없어도 Success이고 경고를 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = TRANSITION_DENIED_REQUESTER_ID,
                summary = "S20 dryRun 상태 권한없음 테스트",
                statusName = "In Progress",
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.isNotEmpty()) { "TRANSITION 권한없음 경고가 있어야 하지만 비어 있습니다." }
    }

    // ── private helpers ───────────────────────────────────────────────────────

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
    private fun seedProjectAndComponents() {
        conn().use { c ->
            c.autoCommit = false
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Import Adapter Integration Test Project")
                stmt.executeUpdate()
            }

            // software-default 워크플로우 + open 상태 (createIssue 의 resolveStart 가 필요로 함)
            val wfId =
                c.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('software-default', '소프트웨어 개발 기본 워크플로우') " +
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

            // import-scheme 독립 생성 — 다른 통합 테스트의 scheme 과 충돌 방지(싱글톤 DB 공유)
            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_schemes (key, name, is_default)
                    VALUES ('import-scheme', 'Import Adapter Test Scheme', false)
                    ON CONFLICT (key) DO NOTHING
                    """.trimIndent(),
                )
            }

            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                    SELECT s.id, NULL, '$wfId'
                    FROM workflow_schemes s
                    WHERE s.key = 'import-scheme'
                      AND NOT EXISTS (
                        SELECT 1 FROM workflow_scheme_issue_type_mappings m
                        WHERE m.scheme_id = s.id AND m.issue_type_id IS NULL
                      )
                    """.trimIndent(),
                )
            }

            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                    SELECT p.id, s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                    FROM projects p, workflow_schemes s
                    WHERE p.key = '$PROJECT_KEY'
                      AND s.key = 'import-scheme'
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }

            c.commit()
        }

        backendComponentId = insertComponent(PROJECT_KEY, "Backend")
        existingVersionId = insertVersion(PROJECT_KEY, EXISTING_VERSION_NAME)
    }

    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun insertComponent(
        projectKey: String,
        name: String,
    ): UUID {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement(
                "INSERT INTO components (project_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) return rs.getObject(1) as UUID
                }
            }
            fetchComponentId(projectId, name)
        }
    }

    private fun fetchComponentId(
        projectId: UUID,
        name: String,
    ): UUID =
        conn().use { c ->
            c.prepareStatement("SELECT id FROM components WHERE project_id = ? AND name = ?").use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$name 컴포넌트가 없습니다." }
                    rs.getObject(1) as UUID
                }
            }
        }

    /** S10/S11/S17 — 자동생성 실행 후(또는 이미 존재 시) id 조회, 미존재면 null(생성 안 됨 검증용). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun findComponentIdOrNull(
        projectKey: String,
        name: String,
    ): UUID? {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement("SELECT id FROM components WHERE project_id = ? AND name = ?").use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) as UUID else null }
            }
        }
    }

    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun insertVersion(
        projectKey: String,
        name: String,
    ): UUID {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement(
                "INSERT INTO versions (project_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) return rs.getObject(1) as UUID
                }
            }
            checkNotNull(findVersionIdOrNull(projectKey, name)) { "$name 버전이 없습니다." }
        }
    }

    /** S12/S13/S19 — 자동생성 실행 후(또는 이미 존재 시) id 조회, 미존재면 null(생성 안 됨 검증용). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun findVersionIdOrNull(
        projectKey: String,
        name: String,
    ): UUID? {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement("SELECT id FROM versions WHERE project_id = ? AND name = ?").use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) as UUID else null }
            }
        }
    }

    private fun fetchProjectId(projectKey: String): UUID =
        conn().use { c ->
            c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, projectKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$projectKey 프로젝트가 없습니다." }
                    rs.getObject(1) as UUID
                }
            }
        }

    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchTypeKey(issueKey: String): String? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT it.key FROM issues i JOIN issue_types it ON i.type_id = it.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    rs.getString(1)
                }
            }
        }

    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchComponentIds(issueKey: String): Set<UUID> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT ic.component_id FROM issue_components ic JOIN issues i ON ic.issue_id = i.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    val ids = mutableSetOf<UUID>()
                    while (rs.next()) ids += rs.getObject(1) as UUID
                    ids
                }
            }
        }

    /** S12/S18 — 이슈에 연결된 "영향받는 버전" id 목록. */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchAffectsVersionIds(issueKey: String): Set<UUID> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT iav.version_id FROM issue_affects_versions iav " +
                    "JOIN issues i ON iav.issue_id = i.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    val ids = mutableSetOf<UUID>()
                    while (rs.next()) ids += rs.getObject(1) as UUID
                    ids
                }
            }
        }

    /** S12 — 이슈에 연결된 "수정 예정 버전" id 목록. */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchFixVersionIds(issueKey: String): Set<UUID> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT ifv.version_id FROM issue_fix_versions ifv " +
                    "JOIN issues i ON ifv.issue_id = i.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    val ids = mutableSetOf<UUID>()
                    while (rs.next()) ids += rs.getObject(1) as UUID
                    ids
                }
            }
        }

    private fun countImportIssues(): Int =
        conn().use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM issues WHERE key LIKE '$PROJECT_KEY-%'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun fetchKeySequence(): Long =
        conn().use { c ->
            c.prepareStatement("SELECT key_sequence FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$PROJECT_KEY 프로젝트가 없습니다." }
                    rs.getLong(1)
                }
            }
        }

    /** q_issue_events 큐에서 최대 10건을 읽어 반환한다(visibility_timeout=1초). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun readIssueEventsQueue(): List<String> =
        conn().use { c ->
            c.prepareStatement("SELECT message FROM pgmq.read(?, ?, ?)").use { stmt ->
                stmt.setString(1, "q_issue_events")
                stmt.setInt(2, 1)
                stmt.setInt(3, 10)
                stmt.executeQuery().use { rs ->
                    val messages = mutableListOf<String>()
                    while (rs.next()) messages += rs.getString(1)
                    messages
                }
            }
        }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
