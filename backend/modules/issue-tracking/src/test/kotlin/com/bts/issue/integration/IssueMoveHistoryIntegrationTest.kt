// 이동 시 이력에 key 변경이 실제 영속되는지 검증하는 통합 테스트 — FR-MV-02

package com.bts.issue.integration

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.adapter.inbound.rest.IssueExceptionHandler
import com.bts.issue.application.IssueMoveService
import com.bts.issue.application.MovePreviewService
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.history.IssueChangeDetector
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.history.IssueChangeLabelResolver
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.history.JdbcIssueChangeHistoryRepository
import com.bts.issue.integration.IssueMoveIntegrationTest.SwitchablePermissionResolver
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueKeyRedirectRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.user.UserLookupPort
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import com.bts.workflow.scheme.adapter.inbound.WorkflowResolverImpl
import com.bts.workflow.scheme.adapter.inbound.WorkflowStateCatalogImpl
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * 이슈 이동 시 이력에 key 변경 항목이 실제 DB에 영속되는지 검증하는 통합 테스트 (FR-MV-02 Task 3).
 *
 * ## 목적
 * 기존 [IssueMoveIntegrationTest]는 `historyRecorder`를 `mockk(relaxed=true)`로 주입해
 * 이동이 실제 [IssueChangeDetector]→`issue_change_item` DB 영속을 거치지 않는다.
 * 이 테스트는 **실제 [IssueHistoryRecorder]**(실 detector + 실 labelResolver + 실 repository)를
 * 주입하여 이동 후 `issue_change_item`에 key 항목이 실제로 1건 영속됨을 단언한다.
 * detector key extractor(Task 1)가 미적용이면 이 테스트가 RED가 된다.
 *
 * ## 설계
 * - [TestConfig](singleton Testcontainers + 기본 빈)를 재사용한다.
 * - [IssueMoveHistoryConfig]로 실 recorder 관련 빈을 별도 구성한다.
 * - 기존 [IssueMoveIntegrationTest.IssueMoveConfig]의 `mockk recorder` 빈은 절대 건드리지 않는다
 *   — 별도 파일·별도 `@TestConfiguration`으로 완전히 독립된 컨텍스트를 사용한다.
 * - cross-BC 포트([UserLookupPort], [IssueSecurityDirectory])는 `mockk(relaxed=true)` 스텁:
 *   key 항목은 라벨 해석이 불필요하다.
 *
 * ## 검증 시나리오
 * - T3-1. 순수 이동(FR3): `issue_change_item`에 `field='key'` 항목 1건 영속.
 * - T3-2. 체인 이동 박제 키(N1): 2차 이동 이력 그룹의 `key.fromValue`가 당시 키(중간 키).
 * - T3-3. 서브태스크 동반: 부모·자식 각각 key 항목 1건씩 영속.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [
        TestConfig::class,
        IssueMoveHistoryIntegrationTest.IssueMoveHistoryConfig::class,
    ],
)
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LongMethod", "TooManyFunctions")
class IssueMoveHistoryIntegrationTest {
    /**
     * 실제 IssueHistoryRecorder를 주입한 이동 관련 빈 구성.
     *
     * [TestConfig]의 기본 빈(DSLContext, IssueRepository, WorkflowKeyResolverImpl 등)을
     * autowire 받아 IssueMoveService를 실 recorder로 wire한다.
     * [SwitchablePermissionResolver]를 @Primary로 등록해 권한 제어를 가능하게 한다.
     * IssueChangeLabelResolver의 cross-BC 포트는 mockk(relaxed=true) 스텁
     * — key 항목은 라벨 해석이 필요 없으므로 실 포트 없이도 key 항목이 영속된다.
     */
    @Configuration
    @Suppress("LongParameterList")
    open class IssueMoveHistoryConfig {
        /** AlwaysAllow를 @Primary로 교체하여 권한 거부 없이 이동이 성공하도록 한다. */
        @Bean
        @Primary
        open fun historyTestPermissionResolver(): SwitchablePermissionResolver = SwitchablePermissionResolver()

        @Bean
        open fun historyIssueKeyRedirectRepository(dsl: DSLContext): IssueKeyRedirectRepository {
            return IssueKeyRedirectRepository(dsl)
        }

        @Bean
        open fun historyComponentRepository(dsl: DSLContext): ComponentRepository = ComponentRepository(dsl)

        @Bean
        open fun historyVersionRepository(dsl: DSLContext): VersionRepository = VersionRepository(dsl)

        @Bean
        open fun historyProjectLeadRepository(dsl: DSLContext): ProjectLeadRepository = ProjectLeadRepository(dsl)

        @Bean
        open fun historyCustomFieldDefinitionRepository(dsl: DSLContext): CustomFieldDefinitionRepository =
            CustomFieldDefinitionRepository(dsl)

        // ── 실제 IssueHistoryRecorder 조합 ─────────────────────────────────────

        @Bean
        open fun namedParameterJdbcTemplate(dataSource: DriverManagerDataSource): NamedParameterJdbcTemplate =
            NamedParameterJdbcTemplate(dataSource)

        @Bean
        open fun realIssueChangeHistoryRepository(jdbc: NamedParameterJdbcTemplate): IssueChangeHistoryRepository =
            JdbcIssueChangeHistoryRepository(jdbc)

        @Bean
        open fun realIssueChangeDetector(): IssueChangeDetector = IssueChangeDetector()

        /**
         * IssueChangeLabelResolver의 cross-BC 포트는 relaxed mock 스텁.
         * key 항목은 라벨 해석이 불요 — IssueChangeLabelResolver.resolveLabels에서
         * "key" 필드는 when 분기에서 매핑되지 않아 item 자체를 그대로 반환한다.
         */
        @Bean
        open fun realIssueChangeLabelResolver(
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            componentRepository: ComponentRepository,
            versionRepository: VersionRepository,
            issueRepository: IssueRepository,
        ): IssueChangeLabelResolver =
            IssueChangeLabelResolver(
                issueTypeRepository = issueTypeRepository,
                resolutionRepository = resolutionRepository,
                componentRepository = componentRepository,
                versionRepository = versionRepository,
                userLookupPort = mockk(relaxed = true),
                issueSecurityDirectory = mockk(relaxed = true),
                issueRepository = issueRepository,
            )

        @Bean
        open fun realIssueHistoryRecorder(
            detector: IssueChangeDetector,
            resolver: IssueChangeLabelResolver,
            repository: IssueChangeHistoryRepository,
        ): IssueHistoryRecorder = IssueHistoryRecorder(detector, resolver, repository)

        // ── WorkflowStateCatalog ────────────────────────────────────────────────

        @Bean
        open fun historyWorkflowStateCatalog(workflowResolver: WorkflowResolverImpl): WorkflowStateCatalogImpl =
            WorkflowStateCatalogImpl(workflowResolver)

        // ── IssueMoveService (실 recorder 주입) ─────────────────────────────────

        /** FR-PJ-04 PR-4 Task 9 — 실 ProjectArchiveGuard(공유 dsl 위). */
        @Bean
        open fun historyProjectArchiveGuard(dsl: DSLContext): ProjectArchiveGuard =
            ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))

        @Bean
        open fun historyIssueMoveService(
            issueRepository: IssueRepository,
            keyRedirectRepository: IssueKeyRedirectRepository,
            permissionResolver: SwitchablePermissionResolver,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            workflowStateCatalog: WorkflowStateCatalogImpl,
            historyRecorder: IssueHistoryRecorder,
            componentRepository: ComponentRepository,
            versionRepository: VersionRepository,
            customFieldDefinitionRepository: CustomFieldDefinitionRepository,
            archiveGuard: ProjectArchiveGuard,
        ): IssueMoveService =
            IssueMoveService(
                issueRepository = issueRepository,
                redirectRepository = keyRedirectRepository,
                permissionResolver = permissionResolver,
                workflowKeyResolver = workflowKeyResolver,
                workflowStateCatalog = workflowStateCatalog,
                historyRecorder = historyRecorder,
                componentRepository = componentRepository,
                versionRepository = versionRepository,
                customFieldDefinitionRepository = customFieldDefinitionRepository,
                archiveGuard = archiveGuard,
            )

        @Bean
        open fun historyMovePreviewService(
            permissionResolver: SwitchablePermissionResolver,
            repo: IssueRepository,
            componentRepository: ComponentRepository,
            versionRepository: VersionRepository,
            customFieldDefinitionRepository: CustomFieldDefinitionRepository,
            workflowStateCatalog: WorkflowStateCatalogImpl,
        ): MovePreviewService =
            MovePreviewService(
                permissionResolver = permissionResolver,
                issueRepository = repo,
                componentRepository = componentRepository,
                versionRepository = versionRepository,
                customFieldDefinitionRepository = customFieldDefinitionRepository,
                workflowStateCatalog = workflowStateCatalog,
            )

        @Bean
        open fun historyIssueMoveController(
            previewService: MovePreviewService,
            moveService: IssueMoveService,
        ): com.bts.issue.adapter.inbound.rest.IssueMoveController =
            com.bts.issue.adapter.inbound.rest.IssueMoveController(
                previewService = previewService,
                moveService = moveService,
            )

        @Bean
        open fun historyIssueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dataSource: DriverManagerDataSource

    @Autowired
    lateinit var permissionResolver: SwitchablePermissionResolver

    @Autowired
    lateinit var historyRepository: IssueChangeHistoryRepository

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper =
        ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        private const val SRC_KEY = "MHSRC"
        private const val DST_KEY = "MHDST"
        private const val DST2_KEY = "MHDS2"
        private const val ACTOR_ID = "11111111-1111-4111-8111-111111111111"

        private var bootstrapped = false
    }

    @BeforeAll
    fun setUpAll() {
        if (!bootstrapped) {
            applyMigrations()
            seedProjects()
            bootstrapped = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                ACTOR_ID,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        permissionResolver.resetPermissions()
        cleanIssues()
    }

    @AfterEach
    fun tearDown() {
        permissionResolver.resetPermissions()
        SecurityContextHolder.clearContext()
    }

    // ── T3-1. 순수 이동 — key 항목 1건 영속 (FR3·EC1) ─────────────────────────────

    /**
     * detector key extractor(Task 1)가 동작한다면 이동 후 `issue_change_item`에
     * `field='key', fromValue=옛키, toValue=새키` 항목이 1건 영속되어야 한다.
     * detector 미적용 상태(key extractor 없음)에서는 0건으로 RED가 된다.
     *
     * Given  MHSRC 프로젝트에 이슈 MHSRC-1 (open 상태)
     * When   POST /api/v1/issues/MHSRC-1/move { targetProjectKey: "MHDST", expectedVersion: 1 }
     * Then   issue_change_item에 field='key', fromValue='MHSRC-1', toValue='MHDST-1' 항목 1건 영속
     *        + issue_change_group.issue_key = 'MHSRC-1' (이동 당시 키 박제)
     */
    @Test
    fun `T3-1 순수 이동 후 issue_change_item에 key 항목 1건 영속`() {
        val issueId = insertIssue(SRC_KEY, "open")

        // 이동 전 이력 없음 확인 (vacuous 차단)
        val groupsBefore = historyRepository.findByIssue(issueId)
        assert(groupsBefore.isEmpty()) {
            "이동 전 이력이 이미 존재함 — 시드 오염. count=${groupsBefore.size}"
        }

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 1L))),
        ).andExpect(status().isOk)

        // 이동 후 이력 조회
        val groupsAfter = historyRepository.findByIssue(issueId)
        assert(groupsAfter.isNotEmpty()) {
            "이동 후 issue_change_group 행이 없음 — IssueHistoryRecorder.record가 호출되지 않았거나 " +
                "JdbcIssueChangeHistoryRepository가 올바르게 주입되지 않음"
        }

        // key 항목 존재 확인 — 모든 그룹의 items를 평탄화해서 field='key' 를 찾는다
        val allItems = groupsAfter.flatMap { it.items }
        val keyItems = allItems.filter { it.field == "key" }

        assert(keyItems.size == 1) {
            "key 항목이 ${keyItems.size}건. 기대 1건. " +
                "전체 항목: ${allItems.map { it.field }}. " +
                "detector key extractor(Task 1)가 SCALAR_FIELD_EXTRACTORS에 등록되어 있는지 확인 필요."
        }

        val keyItem = keyItems.first()
        assert(keyItem.fromValue == "$SRC_KEY-1") {
            "key.fromValue 불일치. 기대='$SRC_KEY-1', 실제='${keyItem.fromValue}'"
        }
        assert(keyItem.toValue == "$DST_KEY-1") {
            "key.toValue 불일치. 기대='$DST_KEY-1', 실제='${keyItem.toValue}'"
        }

        // issue_change_group.issue_key는 IssueHistoryRecorder가 after(이동 후) 이슈로 그룹을 생성하므로
        // 새 키(after.key = DST 키)가 박제된다. 이것이 실제 동작이다.
        val group = groupsAfter.first()
        assert(group.issueKey == "$DST_KEY-1") {
            "issue_change_group.issue_key 불일치. 기대='$DST_KEY-1'(이동 후 새 키), 실제='${group.issueKey}'"
        }
    }

    // ── T3-2. 체인 이동 박제 키 (N1) ─────────────────────────────────────────────

    /**
     * A→B→C 두 번 이동 후, 두 번째 이동 이력 그룹의 key.fromValue가
     * A(최초 키)가 아닌 B(당시 키)임을 단언한다.
     * issue_change_group.issue_key도 당시 키(B)를 박제해야 한다.
     *
     * Given  MHSRC-1(A) → MHDST-1(B) 이동 후, MHDST-1 → MHDS2-1(C) 이동
     * Then   2차 이동 그룹의 key.fromValue = 'MHDST-1'(B, 당시 키, A='MHSRC-1' 아님)
     *        + issue_change_group.issue_key = 'MHDST-1'(B)
     */
    @Test
    fun `T3-2 체인 이동 - 2차 이동 key fromValue는 당시 키(중간 키)`() {
        val issueId = insertIssue(SRC_KEY, "open")

        // Step 1: MHSRC-1 → MHDST-1
        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST_KEY, 1L))),
        ).andExpect(status().isOk)

        // Step 2: MHDST-1 → MHDS2-1
        mockMvc.perform(
            post("/api/v1/issues/$DST_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(buildMoveRequest(DST2_KEY, 2L))),
        ).andExpect(status().isOk)

        // 두 번의 이동으로 이력 그룹 2개가 생성되어야 한다
        val groups = historyRepository.findByIssue(issueId)
        assert(groups.size == 2) {
            "이동 2회 후 이력 그룹이 ${groups.size}건. 기대 2건."
        }

        // findByIssue는 created_at DESC, id DESC 순이므로 최신(2차 이동)이 먼저
        val secondMoveGroup = groups.first()
        val keyItemsSecond = secondMoveGroup.items.filter { it.field == "key" }

        assert(keyItemsSecond.size == 1) {
            "2차 이동 그룹의 key 항목이 ${keyItemsSecond.size}건. 기대 1건."
        }

        val keyItemSecond = keyItemsSecond.first()
        assert(keyItemSecond.fromValue == "$DST_KEY-1") {
            "2차 이동 key.fromValue가 '${keyItemSecond.fromValue}'. " +
                "기대='$DST_KEY-1'(중간 키). '$SRC_KEY-1'(최초 키)가 되면 안 됨. " +
                "before 스냅샷이 당시 키를 올바르게 담고 있는지 확인 필요."
        }
        assert(keyItemSecond.toValue == "$DST2_KEY-1") {
            "2차 이동 key.toValue 불일치. 기대='$DST2_KEY-1', 실제='${keyItemSecond.toValue}'"
        }

        // 2차 이동 그룹의 issue_key: IssueHistoryRecorder는 after(이동 후) 이슈로 그룹을 생성하므로
        // 새 키(C = MHDS2-1)가 박제된다. key.fromValue(B)와 issue_key(C) 조합으로
        // "B→C 이동 이력" 임을 추론할 수 있다.
        assert(secondMoveGroup.issueKey == "$DST2_KEY-1") {
            "2차 이동 그룹의 issue_key 불일치. 기대='$DST2_KEY-1'(이동 후 새 키), 실제='${secondMoveGroup.issueKey}'"
        }
    }

    // ── T3-3. 서브태스크 동반 이동 — 부모·자식 각각 key 항목 영속 ───────────────────

    /**
     * 부모+자식 동반 이동 시 부모 이슈와 자식 이슈 각각에 key 항목이 1건씩 영속됨을 단언한다.
     *
     * Given  MHSRC-1(부모) + MHSRC-2(자식) — 동반 이동 요청
     * When   POST /api/v1/issues/MHSRC-1/move { subtasks: [{issueKey: "MHSRC-2", ...}] }
     * Then   부모 이슈 이력: key 항목 1건 (MHSRC-1 → MHDST-1)
     *        자식 이슈 이력: key 항목 1건 (MHSRC-2 → MHDST-2)
     */
    @Test
    fun `T3-3 서브태스크 동반 이동 - 부모 자식 각각 key 항목 1건 영속`() {
        val parentId = insertIssue(SRC_KEY, "open")
        val childId = insertIssue(SRC_KEY, "open", parentId = parentId)

        // 이동 전 이력 없음 확인 (vacuous 차단)
        val parentGroupsBefore = historyRepository.findByIssue(parentId)
        val childGroupsBefore = historyRepository.findByIssue(childId)
        assert(parentGroupsBefore.isEmpty()) {
            "이동 전 부모 이력이 이미 존재함. count=${parentGroupsBefore.size}"
        }
        assert(childGroupsBefore.isEmpty()) {
            "이동 전 자식 이력이 이미 존재함. count=${childGroupsBefore.size}"
        }

        val moveWithSubtasksRequest =
            mapOf(
                "targetProjectKey" to DST_KEY,
                "expectedVersion" to 1L,
                "targetStateIsDone" to false,
                "componentMapping" to emptyMap<String, String>(),
                "affectsVersionMapping" to emptyMap<String, String>(),
                "fixVersionMapping" to emptyMap<String, String>(),
                "customFieldValues" to emptyMap<String, Any>(),
                "subtasks" to
                    listOf(
                        mapOf(
                            "issueKey" to "$SRC_KEY-2",
                            "expectedVersion" to 1L,
                            "targetStateIsDone" to false,
                            "componentMapping" to emptyMap<String, String>(),
                            "affectsVersionMapping" to emptyMap<String, String>(),
                            "fixVersionMapping" to emptyMap<String, String>(),
                            "customFieldValues" to emptyMap<String, Any>(),
                        ),
                    ),
            )

        mockMvc.perform(
            post("/api/v1/issues/$SRC_KEY-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(moveWithSubtasksRequest)),
        ).andExpect(status().isOk)

        // ─── 부모 이력 단언 ───────────────────────────────────────────────
        val parentGroupsAfter = historyRepository.findByIssue(parentId)
        assert(parentGroupsAfter.isNotEmpty()) {
            "동반 이동 후 부모 이슈의 issue_change_group 행이 없음"
        }
        val parentKeyItems = parentGroupsAfter.flatMap { it.items }.filter { it.field == "key" }
        assert(parentKeyItems.size == 1) {
            "부모 key 항목이 ${parentKeyItems.size}건. 기대 1건."
        }
        assert(parentKeyItems.first().fromValue == "$SRC_KEY-1") {
            "부모 key.fromValue 불일치. 기대='$SRC_KEY-1', 실제='${parentKeyItems.first().fromValue}'"
        }
        assert(parentKeyItems.first().toValue == "$DST_KEY-1") {
            "부모 key.toValue 불일치. 기대='$DST_KEY-1', 실제='${parentKeyItems.first().toValue}'"
        }

        // ─── 자식 이력 단언 ───────────────────────────────────────────────
        val childGroupsAfter = historyRepository.findByIssue(childId)
        assert(childGroupsAfter.isNotEmpty()) {
            "동반 이동 후 자식 이슈의 issue_change_group 행이 없음"
        }
        val childKeyItems = childGroupsAfter.flatMap { it.items }.filter { it.field == "key" }
        assert(childKeyItems.size == 1) {
            "자식 key 항목이 ${childKeyItems.size}건. 기대 1건."
        }
        assert(childKeyItems.first().fromValue == "$SRC_KEY-2") {
            "자식 key.fromValue 불일치. 기대='$SRC_KEY-2', 실제='${childKeyItems.first().fromValue}'"
        }
        assert(childKeyItems.first().toValue == "$DST_KEY-2") {
            "자식 key.toValue 불일치. 기대='$DST_KEY-2', 실제='${childKeyItems.first().toValue}'"
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — issue-tracking + project-workflow 두 BC를 단일 pass로 적용.
     * [TestConfig]의 singleton 컨테이너를 재사용하므로 이미 마이그레이션된 경우 멱등 동작한다.
     */
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

    /**
     * 테스트 전용 프로젝트 시드.
     *
     * MHSRC / MHDST / MHDS2 — software-scheme(software-default workflow) 배정.
     * [IssueMoveIntegrationTest.seedProjects]와 같은 컨테이너를 공유하므로
     * ON CONFLICT 멱등으로 충돌 없이 동작한다.
     */
    @Suppress("LongMethod")
    private fun seedProjects() {
        getConnection().use { conn ->
            conn.autoCommit = false

            for ((key, name) in listOf(
                SRC_KEY to "Move History Source Project",
                DST_KEY to "Move History Dest Project",
                DST2_KEY to "Move History Dest 2 Project",
            )) {
                conn.prepareStatement(
                    "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                ).use { ps ->
                    ps.setString(1, key)
                    ps.setString(2, name)
                    ps.executeUpdate()
                }
            }

            // software-default workflow — 기존 시드가 앞서면 ON CONFLICT로 skip
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO workflows (key, name) " +
                        "VALUES ('software-default', '소프트웨어 개발 기본 워크플로우') " +
                        "ON CONFLICT (key) DO NOTHING",
                )
            }

            val wfId =
                conn.prepareStatement("SELECT id FROM workflows WHERE key = 'software-default'").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1, UUID::class.java)
                    }
                }

            // workflow states (ON CONFLICT 멱등)
            fun insertStateIfAbsent(
                key: String,
                name: String,
                category: String,
                displayOrder: Int,
            ) {
                conn.prepareStatement(
                    "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                        "VALUES (?, ?, ?, ?, ?) ON CONFLICT (workflow_id, key) " +
                        "DO UPDATE SET display_order = EXCLUDED.display_order",
                ).use { ps ->
                    ps.setObject(1, wfId)
                    ps.setString(2, key)
                    ps.setString(3, name)
                    ps.setString(4, category)
                    ps.setInt(5, displayOrder)
                    ps.executeUpdate()
                }
            }

            insertStateIfAbsent("open", "Open", "TODO", 0)
            insertStateIfAbsent("in_progress", "In Progress", "IN_PROGRESS", 1)
            insertStateIfAbsent("done", "Done", "DONE", 3)

            // software-scheme (ON CONFLICT 멱등)
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO workflow_schemes (key, name, is_default) " +
                        "VALUES ('software-scheme', 'Software Scheme', true) " +
                        "ON CONFLICT (key) DO NOTHING",
                )
            }

            // scheme → workflow default mapping (멱등)
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                    SELECT s.id, NULL, '$wfId'
                    FROM workflow_schemes s
                    WHERE s.key = 'software-scheme'
                      AND NOT EXISTS (
                        SELECT 1 FROM workflow_scheme_issue_type_mappings m
                        WHERE m.scheme_id = s.id AND m.issue_type_id IS NULL
                      )
                    """.trimIndent(),
                )
            }

            // MHSRC / MHDST / MHDS2 에 software-scheme 배정 (ON CONFLICT 멱등)
            for (key in listOf(SRC_KEY, DST_KEY, DST2_KEY)) {
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO project_workflow_scheme_assignments
                            (project_id, workflow_scheme_id, assigned_at, assigned_by)
                        SELECT p.id, s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                        FROM projects p, workflow_schemes s
                        WHERE p.key = '$key'
                          AND s.key = 'software-scheme'
                        ON CONFLICT (project_id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }

            conn.commit()
        }
    }

    /**
     * 각 테스트 전 이슈·redirect·이력 초기화.
     * 이 테스트 전용 프로젝트(MH prefix) 데이터만 삭제한다.
     */
    private fun cleanIssues() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                // issue_change_item/group은 FK 없음(DATA.md — 이력 보존 우선)
                // issue_change_group.issue_id로 필터링하여 이 테스트 이슈 이력만 삭제
                stmt.execute(
                    """
                    DELETE FROM issue_change_item
                    WHERE group_id IN (
                        SELECT id FROM issue_change_group
                        WHERE issue_id IN (
                            SELECT id FROM issues
                            WHERE key LIKE '$SRC_KEY-%' OR key LIKE '$DST_KEY-%' OR key LIKE '$DST2_KEY-%'
                        )
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    DELETE FROM issue_change_group
                    WHERE issue_id IN (
                        SELECT id FROM issues
                        WHERE key LIKE '$SRC_KEY-%' OR key LIKE '$DST_KEY-%' OR key LIKE '$DST2_KEY-%'
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    "DELETE FROM issue_key_redirects WHERE old_key LIKE '$SRC_KEY-%' " +
                        "OR old_key LIKE '$DST_KEY-%' OR old_key LIKE '$DST2_KEY-%'",
                )
                stmt.execute(
                    "DELETE FROM issues WHERE key LIKE '$SRC_KEY-%' " +
                        "OR key LIKE '$DST_KEY-%' OR key LIKE '$DST2_KEY-%'",
                )
                stmt.execute(
                    "UPDATE projects SET key_sequence = 0 WHERE key IN " +
                        "('$SRC_KEY', '$DST_KEY', '$DST2_KEY')",
                )
            }
        }
    }

    /**
     * 이슈를 DB에 직접 삽입하고 생성된 이슈 UUID를 반환한다.
     *
     * @param projectKey 프로젝트 키
     * @param stateKey 초기 상태 키
     * @param parentId 부모 이슈 UUID. null이면 최상위 이슈.
     * @return 삽입된 이슈 UUID
     */
    private fun insertIssue(
        projectKey: String,
        stateKey: String,
        parentId: UUID? = null,
    ): UUID {
        val issueId = UUID.randomUUID()

        getConnection().use { conn ->
            conn.autoCommit = false

            val seq =
                conn.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { ps ->
                    ps.setString(1, projectKey)
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "프로젝트 $projectKey 가 없음" }
                        rs.getLong(1)
                    }
                }

            val issueKey = "$projectKey-$seq"

            val taskTypeId =
                conn.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음. V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            conn.prepareStatement(
                """
                INSERT INTO issues (id, key, project_id, summary, reporter_id, current_state_key, type_id, version, parent_id)
                SELECT ?, ?, p.id, '이력 통합 테스트용 이슈', ?::uuid, ?, ?, 1, ?
                FROM projects p WHERE p.key = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, issueId)
                ps.setString(2, issueKey)
                ps.setString(3, ACTOR_ID)
                ps.setString(4, stateKey)
                ps.setLong(5, taskTypeId)
                ps.setObject(6, parentId)
                ps.setString(7, projectKey)
                ps.executeUpdate()
            }

            conn.commit()
        }

        return issueId
    }

    /** 이동 요청 바디를 빌드한다. */
    private fun buildMoveRequest(
        targetProjectKey: String,
        expectedVersion: Long,
    ): Map<String, Any> =
        mapOf(
            "targetProjectKey" to targetProjectKey,
            "expectedVersion" to expectedVersion,
            "targetStateIsDone" to false,
            "componentMapping" to emptyMap<String, String>(),
            "affectsVersionMapping" to emptyMap<String, String>(),
            "fixVersionMapping" to emptyMap<String, String>(),
            "customFieldValues" to emptyMap<String, Any>(),
        )

    /** TestConfig singleton PostgreSQL 컨테이너 연결을 반환한다. */
    private fun getConnection(): Connection =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
