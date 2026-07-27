// GET /api/v1/issues/{key}/changelog 엔드포인트 HTTP 통합 테스트 — FR-HS-02 Task B3
@file:Suppress("MaxLineLength")

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.IssueChangelogService
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.history.JdbcIssueChangeHistoryRepository
import com.bts.issue.pdf.IssuePdfRenderer
import com.bts.issue.pdf.IssuePdfTemplate
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
import com.bts.shared.user.UserLookupPort
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.web.config.EnableSpringDataWebSupport
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * GET /api/v1/issues/{key}/changelog HTTP 통합 테스트.
 *
 * 실제 Testcontainers PostgreSQL + 전용 [ChangelogTestConfig] 전체 스택 위에서 검증한다.
 * [TestConfig] 의 singleton Testcontainers postgres 를 재사용하되,
 * Spring 컨텍스트는 [ChangelogTestConfig] 에서 독자적으로 구성한다.
 * 이유: TestConfig 와 동일 컨텍스트에서 IssueController 빈 2개를 등록하면
 * Spring MVC URL 매핑 충돌이 발생하므로 독립 컨텍스트로 분리한다.
 *
 * ## 검증 시나리오
 * - S1. 이슈 + 변경 그룹 2건 삽입 → 200 + content 2건 + items 포함
 * - S2. content[].items[] 필드 구조 — field/fromValue/toValue/fromLabel/toLabel 포함
 * - S3. actorId/actorName/createdAt(ISO-8601) 포함
 * - S4. VIEW 권한 없음/미존재/소프트삭제 → 404
 * - S5. 페이지 경계 — size=1 로 요청 시 totalPages > 1
 *
 * ## 인증 패턴
 * [ChangelogTestConfig] 의 AlwaysAllowIssuePermissionResolver 로 권한을 일괄 허용한 뒤,
 * S4 에서는 소프트 삭제(deleted_at 셋)로 404 를 유발한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueChangelogControllerIntegrationTest.ChangelogTestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueChangelogControllerIntegrationTest {
    /**
     * changelog 전용 Spring 컨텍스트.
     *
     * TestConfig 의 postgres singleton 을 재사용하여 별도 컨테이너 기동 없이 동일 DB 위에서 동작한다.
     * IssueController 를 changelogService 포함 버전으로 단일 등록한다.
     *
     * LongParameterList: TestConfiguration Bean 메서드는 분리 불가한 단일 구성 단위이므로 Suppress 처리.
     */
    @Configuration
    @EnableWebMvc
    @EnableSpringDataWebSupport
    @EnableTransactionManagement(proxyTargetClass = true)
    @Suppress("LongParameterList")
    open class ChangelogTestConfig : WebMvcConfigurer {
        @Bean
        open fun dataSource(): DriverManagerDataSource =
            DriverManagerDataSource(
                TestConfig.postgres.jdbcUrl,
                TestConfig.postgres.username,
                TestConfig.postgres.password,
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

        /**
         * @EnableWebMvc 기본 Jackson 컨버터는 Instant 를 epoch timestamp 로 직렬화한다.
         * 기존 컨버터를 교체하지 않고(= Spring 의 ProblemDetail 믹스인·errorCode 직렬화 보존)
         * 매퍼 설정만 보강해 Instant(createdAt) 가 ISO-8601 로 나오게 한다.
         * Spring Boot 의 기본 Jackson 설정과 동등 — prod 직렬화 형식을 통합테스트가 검증한다(ReleaseNotesIntegrationTest 선례).
         */
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters.filterIsInstance<MappingJackson2HttpMessageConverter>().forEach { converter ->
                converter.objectMapper
                    .registerModule(JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }
        }

        @Bean
        open fun namedParameterJdbcTemplate(dataSource: DriverManagerDataSource): NamedParameterJdbcTemplate =
            NamedParameterJdbcTemplate(dataSource)

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

        @Bean
        open fun issueChangeHistoryRepository(jdbc: NamedParameterJdbcTemplate): IssueChangeHistoryRepository =
            JdbcIssueChangeHistoryRepository(jdbc)

        @Bean
        open fun clock(): Clock = Clock.systemUTC()

        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                override fun exists(userId: UUID): Boolean = true

                override fun findDisplayNamesByIds(ids: Set<UUID>): Map<UUID, String> = ids.associateWith { "테스터" }
            }

        @Bean
        open fun issueApplicationService(
            repo: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            eventPublisher: IssueEventPublisher,
            permissionResolver: AlwaysAllowIssuePermissionResolver,
            userLookupPort: UserLookupPort,
            clock: Clock,
        ): IssueApplicationService =
            IssueApplicationService(
                repo = repo,
                issueTypeRepository = issueTypeRepository,
                resolutionRepository = resolutionRepository,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowPort = mockk<WorkflowTransitionAdapter>(relaxed = true),
                workflowKeyResolver = mockk<WorkflowKeyResolverImpl>(relaxed = true),
                userLookupPort = userLookupPort,
                componentRepository = mockk(relaxed = true),
                projectLeadRepository = mockk(relaxed = true),
                versionRepository = mockk(relaxed = true),
                clock = clock,
                historyRecorder = mockk(relaxed = true),
            )

        /**
         * description CORE 필드만 안 보이게 하는 stub resolver.
         *
         * 단건 [com.bts.issue.adapter.inbound.rest.IssueResponse.maskInvisible] 가
         * `field_permissions` 규칙으로 특정 필드를 가리는 prod 동작을 통합테스트 레벨에서 시뮬레이션한다.
         * 이 resolver 가 주입되면 changelog 도 description 변경 item 의 값을 마스킹해야 한다(코드리뷰 P1).
         */
        @Bean
        open fun fieldPermissionResolver(): FieldPermissionResolver =
            object : FieldPermissionResolver {
                override fun visibleFields(
                    actorId: UUID,
                    projectId: UUID,
                    candidates: Set<FieldRef>,
                ): Set<FieldRef> = candidates.filterNot { it.key == "description" }.toSet()

                override fun editableFields(
                    actorId: UUID,
                    projectId: UUID,
                    candidates: Set<FieldRef>,
                ): Set<FieldRef> = candidates
            }

        @Bean
        open fun issueChangelogService(
            issueApplicationService: IssueApplicationService,
            historyRepository: IssueChangeHistoryRepository,
            userLookupPort: UserLookupPort,
            issueRepository: IssueRepository,
            fieldPermissionResolver: FieldPermissionResolver,
        ): IssueChangelogService =
            IssueChangelogService(
                issueApplicationService = issueApplicationService,
                changeHistoryRepository = historyRepository,
                userLookupPort = userLookupPort,
                issueRepository = issueRepository,
                fieldPermissionResolver = fieldPermissionResolver,
                // 이 테스트의 이력 항목에는 댓글 변경(`comment:` 접두사)이 없어 조회가 일어나지 않는다.
                commentRepository = mockk(relaxed = true),
            )

        @Bean
        open fun issuePdfTemplate(): IssuePdfTemplate = IssuePdfTemplate()

        @Bean
        open fun issuePdfRenderer(template: IssuePdfTemplate): IssuePdfRenderer = IssuePdfRenderer(template)

        @Bean
        open fun issueController(
            service: IssueApplicationService,
            pdfRenderer: IssuePdfRenderer,
            changelogService: IssueChangelogService,
        ): IssueController = IssueController(service, pdfRenderer, changelogService)

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var dataSource: DriverManagerDataSource

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())

    companion object {
        private const val PROJECT_KEY = "CHLOG"
        private val ACTOR_UUID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        private var migrated = false
        private var seeded = false
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProject()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                ACTOR_UUID.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        cleanIssuesAndHistory()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── S1. 정상 경로 — 200 + content 2건 ─────────────────────────────────────

    /**
     * S1 변경 이력 정상 조회.
     *
     * Given  이슈 1건 + 변경 그룹 2건 삽입
     * When   GET /api/v1/issues/{key}/changelog?page=0&size=20
     * Then   200 OK + content 배열 2건 + totalElements=2
     */
    @Test
    fun `S1 이슈 changelog 조회 — 200 + 그룹 2건 반환`() {
        val issueKey = insertIssue(PROJECT_KEY, "S1 이슈")
        val issueId = fetchIssueId(issueKey)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "summary", "이전 제목", "새 제목", null, null)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "priority", "3", "1", null, null)

        mockMvc.perform(
            get("/api/v1/issues/$issueKey/changelog")
                .param("page", "0")
                .param("size", "20"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.totalElements").value(2))
    }

    // ── S2. items 필드 구조 — field/fromValue/toValue/fromLabel/toLabel 포함 ──────

    /**
     * S2 items 필드 구조 검증.
     *
     * Given  이슈 1건 + 변경 그룹 1건(summary 아이템, fromLabel/toLabel null)
     * When   GET /api/v1/issues/{key}/changelog
     * Then   content[0].items[0].field="summary"
     *        content[0].items[0].fromValue="이전 제목"
     *        content[0].items[0].toValue="새 제목"
     *        NON_NULL 정책으로 fromLabel/toLabel 키 미포함 (Zod nullish 정합)
     */
    @Test
    fun `S2 items 필드 구조 — field fromValue toValue 포함, null 라벨은 NON_NULL 로 제외`() {
        val issueKey = insertIssue(PROJECT_KEY, "S2 이슈")
        val issueId = fetchIssueId(issueKey)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "summary", "이전 제목", "새 제목", null, null)

        val result =
            mockMvc.perform(get("/api/v1/issues/$issueKey/changelog"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[0].items[0].field").value("summary"))
                .andExpect(jsonPath("$.content[0].items[0].fromValue").value("이전 제목"))
                .andExpect(jsonPath("$.content[0].items[0].toValue").value("새 제목"))
                .andReturn()

        val body = result.response.contentAsString
        val responseNode = mapper.readTree(body)
        val item = responseNode.path("content").get(0).path("items").get(0)
        // NON_NULL 정책 — fromLabel/toLabel null 이면 키 자체가 응답에서 제거된다.
        assert(!item.has("fromLabel")) {
            "fromLabel 이 null 이면 NON_NULL 정책으로 응답 JSON 에 키가 없어야 하지만 있음."
        }
        assert(!item.has("toLabel")) {
            "toLabel 이 null 이면 NON_NULL 정책으로 응답 JSON 에 키가 없어야 하지만 있음."
        }
    }

    // ── S3. actorId/actorName/createdAt ISO-8601 포함 ──────────────────────────

    /**
     * S3 actorId/actorName/createdAt 필드 검증.
     *
     * Given  이슈 1건 + 변경 그룹 1건(actorId=ACTOR_UUID)
     * When   GET /api/v1/issues/{key}/changelog
     * Then   content[0].actorId == ACTOR_UUID.toString()
     *        content[0].actorName 은 non-null (stub 표시명 "테스터" 반환)
     *        content[0].createdAt 은 ISO-8601 문자열
     */
    @Test
    fun `S3 actorId actorName createdAt 필드 검증 — actorId UUID 형식, actorName non-null, createdAt ISO-8601`() {
        val issueKey = insertIssue(PROJECT_KEY, "S3 이슈")
        val issueId = fetchIssueId(issueKey)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "priority", "3", "1", null, null)

        val result =
            mockMvc.perform(get("/api/v1/issues/$issueKey/changelog"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[0].actorId").value(ACTOR_UUID.toString()))
                .andExpect(jsonPath("$.content[0].actorName").value("테스터"))
                .andReturn()

        val body = result.response.contentAsString
        val responseNode = mapper.readTree(body)
        val createdAt = responseNode.path("content").get(0).path("createdAt").asText()
        // ISO-8601 형식 검증 — Instant 가 "Z" 접미사를 포함하는지 확인
        assert(createdAt.endsWith("Z") || createdAt.contains("+")) {
            "createdAt 이 ISO-8601 형식이어야 하지만 실제 값: $createdAt"
        }
    }

    // ── S4. 미존재/소프트삭제 → 404 ────────────────────────────────────────────

    /**
     * S4a 존재하지 않는 이슈 키 → 404.
     *
     * Given  DB에 없는 이슈 키
     * When   GET /api/v1/issues/CHLOG-99999/changelog
     * Then   404 Not Found
     */
    @Test
    fun `S4a 존재하지 않는 이슈 changelog 요청 — 404 반환`() {
        mockMvc.perform(get("/api/v1/issues/CHLOG-99999/changelog"))
            .andExpect(status().isNotFound)
    }

    /**
     * S4b 소프트 삭제된 이슈 → 404.
     *
     * Given  이슈 삽입 후 deleted_at 셋
     * When   GET /api/v1/issues/{key}/changelog
     * Then   404 Not Found (단건 조회와 동일한 404 동작)
     */
    @Test
    fun `S4b 소프트 삭제된 이슈 changelog 요청 — 404 반환`() {
        val issueKey = insertIssue(PROJECT_KEY, "S4b 소프트 삭제 이슈")
        softDeleteIssue(issueKey)

        mockMvc.perform(get("/api/v1/issues/$issueKey/changelog"))
            .andExpect(status().isNotFound)
    }

    // ── S5. 페이지 경계 — size=1 → totalPages > 1 ─────────────────────────────

    /**
     * S5 페이지 경계 검증.
     *
     * Given  이슈 1건 + 변경 그룹 3건 삽입
     * When   GET /api/v1/issues/{key}/changelog?page=0&size=1
     * Then   totalPages > 1 (3건 / size=1 = 3 페이지)
     *        content.length() == 1
     */
    @Test
    fun `S5 size=1 페이지 요청 시 totalPages 가 그룹 수만큼 분할된다`() {
        val issueKey = insertIssue(PROJECT_KEY, "S5 페이지 경계 이슈")
        val issueId = fetchIssueId(issueKey)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "summary", "원본1", "수정1", null, null)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "priority", "3", "2", null, null)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "summary", "수정1", "수정2", null, null)

        mockMvc.perform(
            get("/api/v1/issues/$issueKey/changelog")
                .param("page", "0")
                .param("size", "1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.totalPages").value(3))
    }

    // ── S6. 필드 수준 마스킹 — description 안 보임 actor 는 값이 마스킹된다 (코드리뷰 P1) ──

    /**
     * S6 필드 수준 마스킹 — description 변경 item 값 차단.
     *
     * Given  description 을 안 보이게 하는 [ChangelogTestConfig.fieldPermissionResolver] +
     *        description 변경 그룹 1건(fromValue/toValue/fromLabel/toLabel 셋) + priority 변경 그룹 1건
     * When   GET /api/v1/issues/{key}/changelog
     * Then   description item 은 field 만 남고 fromValue/toValue/fromLabel/toLabel 키가 응답에서 제거(NON_NULL)
     *        priority item 의 값은 그대로 노출
     *
     * 단건 [com.bts.issue.adapter.inbound.rest.IssueResponse.maskInvisible] 와 동일하게
     * "필드는 보이되 값 가림" — 변경이 있었다는 사실(field)은 남기되 민감 값만 차단한다.
     */
    @Test
    fun `S6 description 안 보임 actor — description item 값 마스킹, priority item 값은 노출`() {
        val issueKey = insertIssue(PROJECT_KEY, "S6 마스킹 이슈")
        val issueId = fetchIssueId(issueKey)
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "description", "민감 이전", "민감 이후", "이전 라벨", "이후 라벨")
        insertChangeGroup(issueId, issueKey, ACTOR_UUID, "priority", "3", "1", null, null)

        val result =
            mockMvc.perform(
                get("/api/v1/issues/$issueKey/changelog")
                    .param("page", "0")
                    .param("size", "20"),
            )
                .andExpect(status().isOk)
                .andReturn()

        val responseNode = mapper.readTree(result.response.contentAsString)
        val content = responseNode.path("content")

        // 그룹은 createdAt DESC 정렬 — 어느 그룹이 description/priority 인지 field 로 식별한다.
        val items = (0 until content.size()).flatMap { gi -> content.get(gi).path("items").toList() }
        val descriptionItem = items.first { it.path("field").asText() == "description" }
        val priorityItem = items.first { it.path("field").asText() == "priority" }

        // description item — 값/라벨 4종이 NON_NULL 로 응답에서 제거(마스킹)되어야 한다.
        assert(!descriptionItem.has("fromValue")) { "description fromValue 가 마스킹되지 않고 노출됨." }
        assert(!descriptionItem.has("toValue")) { "description toValue 가 마스킹되지 않고 노출됨." }
        assert(!descriptionItem.has("fromLabel")) { "description fromLabel 이 마스킹되지 않고 노출됨." }
        assert(!descriptionItem.has("toLabel")) { "description toLabel 이 마스킹되지 않고 노출됨." }
        // field 자체는 남아 변경 사실을 보존한다.
        assert(descriptionItem.has("field")) { "description item 의 field 키까지 사라지면 단건과 비대칭." }

        // priority item — 마스킹 대상 아님(보임) 이므로 값이 그대로 노출되어야 한다.
        assert(priorityItem.path("fromValue").asText() == "3") {
            "priority fromValue 가 노출되어야 하지만 실제: ${priorityItem.path("fromValue").asText()}"
        }
        assert(priorityItem.path("toValue").asText() == "1") {
            "priority toValue 가 노출되어야 하지만 실제: ${priorityItem.path("toValue").asText()}"
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 — [TestConfig] 컨테이너에 issue-tracking + project-workflow 순차 적용.
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
     * CHLOG 프로젝트를 삽입한다. 이미 존재하면 무시.
     */
    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Changelog Controller Integration Test Project")
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 각 테스트마다 이슈/이력 테이블을 초기화한다.
     */
    private fun cleanIssuesAndHistory() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    /**
     * 테스트용 이슈를 삽입하고 이슈 키를 반환한다.
     */
    private fun insertIssue(
        projectKey: String,
        summary: String,
    ): String {
        return conn().use { conn ->
            conn.autoCommit = false

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
            val projectId = fetchProjectId(conn, projectKey)
            val taskTypeId = fetchTaskTypeId(conn)

            conn.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "VALUES (?, ?, ?, ?, 'open', 1, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, ACTOR_UUID)
                stmt.setLong(5, taskTypeId)
                stmt.executeUpdate()
            }

            conn.commit()
            issueKey
        }
    }

    /**
     * 이슈를 소프트 삭제한다 (deleted_at 셋).
     */
    private fun softDeleteIssue(issueKey: String) {
        conn().use { c ->
            c.prepareStatement(
                "UPDATE issues SET deleted_at = NOW() WHERE key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 변경 그룹 1건과 아이템 1건을 직접 삽입한다.
     *
     * @param issueId 이슈 UUID
     * @param issueKey 이슈 키 (스냅샷)
     * @param actorId 변경 행위자 UUID. null=시스템.
     * @param field 변경 필드명
     * @param fromValue 변경 전 값
     * @param toValue 변경 후 값
     * @param fromLabel 변경 전 라벨 (null 허용)
     * @param toLabel 변경 후 라벨 (null 허용)
     */
    @Suppress("LongParameterList")
    private fun insertChangeGroup(
        issueId: UUID,
        issueKey: String,
        actorId: UUID?,
        field: String,
        fromValue: String?,
        toValue: String?,
        fromLabel: String?,
        toLabel: String?,
    ) {
        conn().use { c ->
            c.autoCommit = false

            val groupId =
                c.prepareStatement(
                    "INSERT INTO issue_change_group (issue_id, issue_key, actor_id) VALUES (?, ?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setObject(1, issueId)
                    stmt.setString(2, issueKey)
                    stmt.setObject(3, actorId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            c.prepareStatement(
                "INSERT INTO issue_change_item (group_id, field, from_value, to_value, from_label, to_label) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setLong(1, groupId)
                stmt.setString(2, field)
                stmt.setString(3, fromValue)
                stmt.setString(4, toValue)
                stmt.setString(5, fromLabel)
                stmt.setString(6, toLabel)
                stmt.executeUpdate()
            }

            c.commit()
        }
    }

    private fun fetchIssueId(issueKey: String): UUID =
        conn().use { c ->
            c.prepareStatement("SELECT id FROM issues WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "이슈 $issueKey 가 없습니다." }
                    rs.getObject(1) as UUID
                }
            }
        }

    private fun fetchProjectId(
        conn: java.sql.Connection,
        projectKey: String,
    ): UUID =
        conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
            stmt.setString(1, projectKey)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
            }
        }

    private fun fetchTaskTypeId(conn: java.sql.Connection): Long =
        conn.prepareStatement(
            "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                rs.getLong(1)
            }
        }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
