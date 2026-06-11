// 이슈 변경 이력 E2E 통합 테스트 — IssueApplicationService 전 구간 + 실 DB 이력 검증 (FR-HS-01 Task 6)

package com.bts.issue.history

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.application.AppChangeAssigneeRequest
import com.bts.issue.application.AppChangeComponentsRequest
import com.bts.issue.application.CreateIssueRequest
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.TransitionIssueRequest
import com.bts.issue.application.UpdateIssueRequest
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.user.UserLookupPort
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.web.context.WebApplicationContext
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * 이슈 변경 이력 E2E 통합 테스트 (FR-HS-01 Task 6).
 *
 * IssueApplicationService → IssueHistoryRecorder → JdbcIssueChangeHistoryRepository
 * 전 구간을 실 Testcontainers PostgreSQL 로 검증한다.
 *
 * TestConfig(singleton Testcontainers + Flyway + 기본 빈) 를 재사용하고,
 * HistoryE2EConfig 로 실 이력 빈을 추가로 wire 한다.
 *
 * ## 검증 시나리오
 * - (a) 다필드 PATCH → 1 그룹 N 아이템
 * - (b) no-op 변경 → 이력 0 (그룹 미생성)
 * - (c) 소프트 삭제 후에도 과거 이력 보존 + lifecycle=deleted 아이템
 * - (d) 라벨 박제 — type/component 는 from_label/to_label 에 이름 박제, assignee 는 label=null
 * - (e) 트랜잭션 — 이슈 변경 성공 시 이력도 같은 트랜잭션에 존재
 * - (f) changeComponents 자동 배정(FR-CM-03) → components + assignee 두 아이템 모두 기록
 * - (g) createIssue → lifecycle=created 아이템
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [
        TestConfig::class,
        IssueChangeHistoryE2EIntegrationTest.HistoryE2EConfig::class,
    ],
)
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueChangeHistoryE2EIntegrationTest {
    /**
     * 실 이력 빈을 wire 하는 보조 설정.
     *
     * TestConfig 의 historyRecorder=mockk(relaxed=true) 를
     * 실 JdbcIssueChangeHistoryRepository + IssueHistoryRecorder + IssueChangeLabelResolver 로 대체한다.
     * IssueApplicationService 도 @Primary 로 교체하여 실 historyRecorder 를 주입받는다.
     */
    @Configuration
    @Suppress("LongParameterList")
    open class HistoryE2EConfig {
        @Bean
        open fun realNamedParameterJdbcTemplate(dataSource: DriverManagerDataSource): NamedParameterJdbcTemplate =
            NamedParameterJdbcTemplate(dataSource)

        @Bean
        open fun realIssueChangeHistoryRepository(jdbc: NamedParameterJdbcTemplate): IssueChangeHistoryRepository =
            JdbcIssueChangeHistoryRepository(jdbc)

        @Bean
        open fun realIssueChangeLabelResolver(
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            componentRepository: ComponentRepository,
            versionRepository: VersionRepository,
        ): IssueChangeLabelResolver =
            IssueChangeLabelResolver(
                issueTypeRepository = issueTypeRepository,
                resolutionRepository = resolutionRepository,
                componentRepository = componentRepository,
                versionRepository = versionRepository,
            )

        @Bean
        open fun realIssueChangeDetector(): IssueChangeDetector = IssueChangeDetector()

        @Bean
        open fun realIssueHistoryRecorder(
            detector: IssueChangeDetector,
            resolver: IssueChangeLabelResolver,
            repository: IssueChangeHistoryRepository,
        ): IssueHistoryRecorder = IssueHistoryRecorder(detector, resolver, repository)

        @Bean
        open fun realComponentRepository(dsl: DSLContext): ComponentRepository = ComponentRepository(dsl)

        @Bean
        open fun realProjectLeadRepository(dsl: DSLContext): ProjectLeadRepository = ProjectLeadRepository(dsl)

        @Bean
        open fun realVersionRepositoryForHistory(dsl: DSLContext): VersionRepository = VersionRepository(dsl)

        @Bean
        @Primary
        @Suppress("LongParameterList")
        open fun issueApplicationServiceWithRealHistory(
            repo: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            eventPublisher: IssueEventPublisher,
            workflowTransitionAdapter: WorkflowTransitionAdapter,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            userLookupPort: UserLookupPort,
            componentRepository: ComponentRepository,
            projectLeadRepository: ProjectLeadRepository,
            versionRepository: VersionRepository,
            historyRecorder: IssueHistoryRecorder,
            clock: Clock,
        ): IssueApplicationService =
            IssueApplicationService(
                repo = repo,
                issueTypeRepository = issueTypeRepository,
                resolutionRepository = resolutionRepository,
                eventPublisher = eventPublisher,
                permissionResolver = com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver(),
                workflowPort = workflowTransitionAdapter,
                workflowKeyResolver = workflowKeyResolver,
                userLookupPort = userLookupPort,
                componentRepository = componentRepository,
                projectLeadRepository = projectLeadRepository,
                versionRepository = versionRepository,
                clock = clock,
                historyRecorder = historyRecorder,
            )
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    @Autowired
    lateinit var historyRepository: IssueChangeHistoryRepository

    companion object {
        private const val PROJECT_KEY = "HSTEST"

        /** 테스트 행위자 UUID */
        val ACTOR_ID: ActorId = ActorId(UUID.fromString("11111111-1111-4111-8111-111111111111"))

        /** 자동 배정 리드 UUID (f 시나리오용) */
        val ALICE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000201")

        private var bootstrapped = false

        /** 리드=Alice 컴포넌트 UUID (f 시나리오) */
        lateinit var compWithAliceLead: UUID
    }

    @BeforeAll
    fun setUpAll() {
        if (bootstrapped) return
        applyMigrations()
        seedProjectAndWorkflow()
        compWithAliceLead = insertComponent(PROJECT_KEY, "HistComp", ALICE_ID)
        bootstrapped = true
    }

    @BeforeEach
    fun cleanState() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
                stmt.execute(
                    "DELETE FROM issue_components WHERE issue_id IN " +
                        "(SELECT id FROM issues WHERE key LIKE '$PROJECT_KEY-%')",
                )
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
                stmt.execute("UPDATE projects SET lead_user_id = NULL WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    // ── (g) createIssue → lifecycle=created 아이템 ─────────────────────────────

    /**
     * (g) 이슈 생성 시 이력 그룹 1건과 lifecycle=created 아이템이 기록된다.
     *
     * Given  HSTEST 프로젝트
     * When   createIssue 호출
     * Then   이력 그룹 1건 존재
     * And    아이템 field="lifecycle", toValue="created"
     */
    @Test
    fun `createIssue 호출 후 lifecycle created 이력이 기록된다`() {
        val issue =
            issueApplicationService.createIssue(
                ACTOR_ID,
                CreateIssueRequest(
                    projectKey = PROJECT_KEY,
                    summary = "g 시나리오 생성 이슈",
                    reporterId = ACTOR_ID,
                ),
            )

        val groups = historyRepository.findByIssue(issue.id.value)
        assertThat(groups).hasSize(1)

        val createdItem = groups.first().items.find { it.field == "lifecycle" }
        assertThat(createdItem).isNotNull
        assertThat(createdItem!!.toValue).isEqualTo("created")
        assertThat(createdItem.fromValue).isNull()
    }

    // ── (a) 다필드 PATCH → 1 그룹 N 아이템 ────────────────────────────────────────

    /**
     * (a) 한 번의 updateIssue(PATCH)로 summary + priority 두 필드를 변경하면
     * 이력 그룹 1건에 아이템 2개 이상이 기록된다.
     *
     * Given  이슈 1건(createIssue 후 이력 초기화)
     * When   updateIssue(summary="변경", priority=3)
     * Then   updateIssue 호출 후 이력 그룹 총 1건 (updateIssue 분) 존재
     * And    summary 아이템 + priority 아이템 각 1개
     */
    @Test
    fun `다필드 PATCH 시 1 그룹에 N 아이템이 기록된다`() {
        val issue =
            issueApplicationService.createIssue(
                ACTOR_ID,
                CreateIssueRequest(
                    projectKey = PROJECT_KEY,
                    summary = "a 시나리오 원본 이슈",
                    reporterId = ACTOR_ID,
                ),
            )
        // createIssue 이력 초기화
        cleanHistoryOnly()

        issueApplicationService.updateIssue(
            ACTOR_ID,
            IssueKey(issue.key.value),
            UpdateIssueRequest(
                summary = "a 시나리오 수정 이슈",
                // 기본값(3)과 다른 값으로 변경해야 아이템이 생성됨
                priority = 1,
                expectedVersion = issue.version,
            ),
        )

        val groups = historyRepository.findByIssue(issue.id.value)
        assertThat(groups).hasSize(1)

        val items = groups.first().items
        val summaryItem = items.find { it.field == "summary" }
        val priorityItem = items.find { it.field == "priority" }
        assertThat(summaryItem).isNotNull
        assertThat(summaryItem!!.fromValue).isEqualTo("a 시나리오 원본 이슈")
        assertThat(summaryItem.toValue).isEqualTo("a 시나리오 수정 이슈")
        assertThat(priorityItem).isNotNull
    }

    // ── (b) no-op 변경 → 이력 0 ────────────────────────────────────────────────

    /**
     * (b) 실제로 아무 필드도 바뀌지 않는 updateIssue 호출은 이력을 기록하지 않는다.
     *
     * Given  이슈 1건
     * When   updateIssue(summary=기존값, priority=null) — no-op
     * Then   이력 그룹 0건 (그룹 미생성)
     */
    @Test
    fun `no-op 변경 시 이력이 기록되지 않는다`() {
        val issue =
            issueApplicationService.createIssue(
                ACTOR_ID,
                CreateIssueRequest(
                    projectKey = PROJECT_KEY,
                    summary = "b 시나리오 노옵 이슈",
                    reporterId = ACTOR_ID,
                ),
            )
        cleanHistoryOnly()

        // summary 를 기존값 그대로 전달 — 변경 없음
        issueApplicationService.updateIssue(
            ACTOR_ID,
            IssueKey(issue.key.value),
            UpdateIssueRequest(
                summary = "b 시나리오 노옵 이슈",
                expectedVersion = issue.version,
            ),
        )

        val groups = historyRepository.findByIssue(issue.id.value)
        assertThat(groups).isEmpty()
    }

    // ── (c) 소프트 삭제 후 이력 보존 + lifecycle=deleted ─────────────────────────

    /**
     * (c) 이슈를 소프트 삭제하면 lifecycle=deleted 이력이 기록되고,
     * 삭제 이후에도 findByIssue 로 과거 이력(생성 + 삭제) 을 모두 조회할 수 있다.
     *
     * Given  이슈 1건 (lifecycle=created 이력 기록됨)
     * When   softDeleteIssue 호출
     * Then   이력 그룹 2건 (created + deleted)
     * And    마지막 그룹 아이템 field="lifecycle", toValue="deleted"
     */
    @Test
    fun `소프트 삭제 후에도 이력이 보존되고 lifecycle deleted 아이템이 기록된다`() {
        val issue =
            issueApplicationService.createIssue(
                ACTOR_ID,
                CreateIssueRequest(
                    projectKey = PROJECT_KEY,
                    summary = "c 시나리오 삭제 이슈",
                    reporterId = ACTOR_ID,
                ),
            )

        issueApplicationService.softDeleteIssue(ACTOR_ID, IssueKey(issue.key.value))

        val groups = historyRepository.findByIssue(issue.id.value)
        assertThat(groups).hasSize(2)

        // 최신순 정렬이므로 첫 번째 그룹이 deleted
        val deletedGroup = groups.first()
        val deletedItem = deletedGroup.items.find { it.field == "lifecycle" }
        assertThat(deletedItem).isNotNull
        assertThat(deletedItem!!.toValue).isEqualTo("deleted")

        // 두 번째 그룹이 created
        val createdGroup = groups[1]
        val createdItem = createdGroup.items.find { it.field == "lifecycle" }
        assertThat(createdItem).isNotNull
        assertThat(createdItem!!.toValue).isEqualTo("created")
    }

    // ── (d) 라벨 박제 — type 이름 박제, assignee label=null ─────────────────────

    /**
     * (d) type 필드 변경 시 from_label/to_label 에 IssueType 이름이 박제된다.
     * assignee 필드 변경 시에는 label=null 을 유지한다(cross-BC 조회 금지).
     *
     * Given  이슈 1건
     * When   changeAssignee(assigneeId=UUID) 호출 (assignee 변경)
     * Then   assignee 아이템 fromLabel=null, toLabel=null
     */
    @Test
    fun `assignee 변경 이력 아이템은 label 이 null 이다`() {
        val issue =
            issueApplicationService.createIssue(
                ACTOR_ID,
                CreateIssueRequest(
                    projectKey = PROJECT_KEY,
                    summary = "d 시나리오 담당자 이슈",
                    reporterId = ACTOR_ID,
                ),
            )
        cleanHistoryOnly()

        val newAssigneeId = UUID.fromString("00000000-0000-4000-8000-000000000202")
        issueApplicationService.changeAssignee(
            ACTOR_ID,
            IssueKey(issue.key.value),
            AppChangeAssigneeRequest(
                assigneeId = newAssigneeId,
                expectedVersion = issue.version,
            ),
        )

        val groups = historyRepository.findByIssue(issue.id.value)
        assertThat(groups).hasSize(1)

        val assigneeItem = groups.first().items.find { it.field == "assignee" }
        assertThat(assigneeItem).isNotNull
        assertThat(assigneeItem!!.fromLabel).isNull()
        assertThat(assigneeItem.toLabel).isNull()
    }

    /**
     * (d-2) type 변경 이력 아이템은 fromLabel/toLabel 에 IssueType 이름이 박제된다.
     *
     * Given  task 타입 이슈 1건
     * When   updateIssue(typeId=bug 타입 ID)
     * Then   type 아이템 fromLabel="Task"(또는 한글명), toLabel=non-null
     */
    @Test
    fun `type 변경 이력 아이템에 IssueType 이름이 박제된다`() {
        val issue =
            issueApplicationService.createIssue(
                ACTOR_ID,
                CreateIssueRequest(
                    projectKey = PROJECT_KEY,
                    summary = "d2 시나리오 타입 변경 이슈",
                    reporterId = ACTOR_ID,
                ),
            )
        cleanHistoryOnly()

        // bug 타입 ID 조회 (V003 마이그레이션으로 삽입된 bug 타입)
        val bugTypeId =
            fetchIssueTypeId("bug")
                ?: return // bug 타입 없으면 스킵 (환경 의존 방어)

        issueApplicationService.updateIssue(
            ACTOR_ID,
            IssueKey(issue.key.value),
            UpdateIssueRequest(
                summary = null,
                typeId = com.bts.shared.issue.IssueTypeId(bugTypeId),
                expectedVersion = issue.version,
            ),
        )

        val groups = historyRepository.findByIssue(issue.id.value)
        assertThat(groups).hasSize(1)

        val typeItem = groups.first().items.find { it.field == "type" }
        assertThat(typeItem).isNotNull
        // IssueType 이름이 박제되었으므로 non-null
        assertThat(typeItem!!.fromLabel).isNotNull
        assertThat(typeItem.toLabel).isNotNull
    }

    // ── (e) 트랜잭션 — 이슈 변경 성공 시 이력 존재 ────────────────────────────────

    /**
     * (e) updateIssue 성공 시 이력이 반드시 같은 트랜잭션에 존재한다.
     * 즉 updateIssue 반환 직후 findByIssue 로 이력을 조회할 수 있다.
     *
     * Given  이슈 1건
     * When   updateIssue(summary="변경") 성공
     * Then   historyRepository.findByIssue 로 즉시 이력 조회 가능
     */
    @Test
    fun `updateIssue 성공 직후 이력을 즉시 조회할 수 있다`() {
        val issue =
            issueApplicationService.createIssue(
                ACTOR_ID,
                CreateIssueRequest(
                    projectKey = PROJECT_KEY,
                    summary = "e 시나리오 트랜잭션 이슈",
                    reporterId = ACTOR_ID,
                ),
            )
        cleanHistoryOnly()

        issueApplicationService.updateIssue(
            ACTOR_ID,
            IssueKey(issue.key.value),
            UpdateIssueRequest(
                summary = "e 시나리오 트랜잭션 수정",
                expectedVersion = issue.version,
            ),
        )

        // 이슈 변경과 이력 기록이 같은 트랜잭션 — 즉시 조회 가능해야 한다
        val groups = historyRepository.findByIssue(issue.id.value)
        assertThat(groups).isNotEmpty
    }

    // ── (f) changeComponents 자동 배정 → components + assignee 두 아이템 모두 기록 ──

    /**
     * (f) 미할당 이슈에 리드 보유 컴포넌트 지정 시 자동 배정이 발생하면
     * components 아이템과 assignee 아이템이 동일 그룹에 함께 기록된다.
     *
     * 자동 배정은 changeComponents 내부에서 2차 변경을 일으키므로
     * 2차 변경 아이템(assignee)이 누락 없이 기록됨을 검증한다.
     *
     * Given  미할당 이슈(version=1), ALICE_ID 리드인 컴포넌트
     * When   changeComponents(componentIds=[compWithAliceLead])
     * Then   이력 그룹 1건
     * And    components 아이템 + assignee 아이템 각 1개
     */
    @Test
    fun `changeComponents 자동 배정 시 components 와 assignee 두 아이템이 모두 기록된다`() {
        val issue =
            issueApplicationService.createIssue(
                ACTOR_ID,
                CreateIssueRequest(
                    projectKey = PROJECT_KEY,
                    summary = "f 시나리오 자동 배정 이슈",
                    reporterId = ACTOR_ID,
                ),
            )
        cleanHistoryOnly()

        issueApplicationService.changeComponents(
            ACTOR_ID,
            IssueKey(issue.key.value),
            AppChangeComponentsRequest(
                componentIds = listOf(compWithAliceLead),
                expectedVersion = issue.version,
            ),
        )

        val groups = historyRepository.findByIssue(issue.id.value)
        assertThat(groups).hasSize(1)

        val items = groups.first().items
        val componentsItem = items.find { it.field == "components" }
        val assigneeItem = items.find { it.field == "assignee" }
        assertThat(componentsItem)
            .describedAs("components 변경 아이템이 기록되어야 한다")
            .isNotNull
        assertThat(assigneeItem)
            .describedAs("자동 배정에 의한 assignee 변경 아이템이 기록되어야 한다")
            .isNotNull
        assertThat(assigneeItem!!.toValue).isEqualTo(ALICE_ID.toString())
    }

    // ── (f-2) component 라벨 박제 확인 ─────────────────────────────────────────

    /**
     * (f-2) components 아이템의 toLabel 에 컴포넌트 이름이 박제된다.
     *
     * Given  미할당 이슈 + compWithAliceLead(이름="HistComp")
     * When   changeComponents(componentIds=[compWithAliceLead])
     * Then   components 아이템 toLabel 에 "HistComp" 포함
     */
    @Test
    fun `components 변경 이력 아이템에 컴포넌트 이름이 박제된다`() {
        val issue =
            issueApplicationService.createIssue(
                ACTOR_ID,
                CreateIssueRequest(
                    projectKey = PROJECT_KEY,
                    summary = "f2 시나리오 컴포넌트 라벨 이슈",
                    reporterId = ACTOR_ID,
                ),
            )
        cleanHistoryOnly()

        issueApplicationService.changeComponents(
            ACTOR_ID,
            IssueKey(issue.key.value),
            AppChangeComponentsRequest(
                componentIds = listOf(compWithAliceLead),
                expectedVersion = issue.version,
            ),
        )

        val groups = historyRepository.findByIssue(issue.id.value)
        assertThat(groups).hasSize(1)

        val componentsItem = groups.first().items.find { it.field == "components" }
        assertThat(componentsItem).isNotNull
        assertThat(componentsItem!!.toLabel)
            .describedAs("컴포넌트 이름 'HistComp' 가 라벨에 박제되어야 한다")
            .contains("HistComp")
    }

    // ── (a-2) 상태 전이 이력 ───────────────────────────────────────────────────

    /**
     * (a-2) transitionIssue 호출 시 status 변경 이력이 기록된다.
     *
     * Given  이슈(currentStateKey="open")
     * When   transitionIssue(toStateKey="in_progress")
     * Then   이력 그룹 1건 + status 아이템(fromValue="open", toValue="in_progress")
     */
    @Test
    fun `transitionIssue 호출 시 status 변경 이력이 기록된다`() {
        val issue =
            issueApplicationService.createIssue(
                ACTOR_ID,
                CreateIssueRequest(
                    projectKey = PROJECT_KEY,
                    summary = "a2 시나리오 상태 전이 이슈",
                    reporterId = ACTOR_ID,
                ),
            )
        cleanHistoryOnly()

        issueApplicationService.transitionIssue(
            ACTOR_ID,
            IssueKey(issue.key.value),
            TransitionIssueRequest(
                toStateKey = "in_progress",
                expectedVersion = issue.version,
            ),
        )

        val groups = historyRepository.findByIssue(issue.id.value)
        assertThat(groups).hasSize(1)

        val statusItem = groups.first().items.find { it.field == "status" }
        assertThat(statusItem).isNotNull
        assertThat(statusItem!!.fromValue).isEqualTo("open")
        assertThat(statusItem.toValue).isEqualTo("in_progress")
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** 이력 테이블만 초기화한다. 이슈는 유지. */
    private fun cleanHistoryOnly() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
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
                stmt.setString(2, "History E2E Test Project")
                stmt.executeUpdate()
            }

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

            val openId = insertWorkflowState(c, wfId, "open", "Open", "TODO", 0)
            val inProgressId = insertWorkflowState(c, wfId, "in_progress", "In Progress", "IN_PROGRESS", 1)
            // open → in_progress 전이 삽입 (transitionIssue 시나리오용)
            insertWorkflowTransition(c, wfId, openId, inProgressId, "Start Work")

            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_schemes (key, name, is_default)
                    VALUES ('hstest-scheme', 'History E2E Test Scheme', false)
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
                    WHERE s.key = 'hstest-scheme'
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
                      AND s.key = 'hstest-scheme'
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }

            c.commit()
        }
    }

    private fun insertComponent(
        projectKey: String,
        name: String,
        leadUserId: UUID?,
    ): UUID {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement(
                "INSERT INTO components (project_id, name, lead_user_id) VALUES (?, ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.setObject(3, leadUserId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
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

    @Suppress("NestedBlockDepth")
    private fun fetchIssueTypeId(typeKey: String): Long? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT id FROM issue_types WHERE key = ? AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.setString(1, typeKey)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    rs.getLong(1)
                }
            }
        }

    @Suppress("LongParameterList")
    private fun insertWorkflowState(
        conn: java.sql.Connection,
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

    private fun insertWorkflowTransition(
        conn: java.sql.Connection,
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

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
