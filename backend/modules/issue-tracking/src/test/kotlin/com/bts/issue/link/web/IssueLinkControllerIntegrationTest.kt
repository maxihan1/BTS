// FR-LK-01 Task 7 — IssueLinkController end-to-end 통합테스트 (링크 CRUD + 부모 설정/해제 전 스택 검증)
@file:Suppress("MaxLineLength")

package com.bts.issue.link.web

import com.bts.issue.link.application.IssueParentService
import com.bts.issue.link.application.LinkApplicationService
import com.bts.issue.link.repository.IssueLinkRepository
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
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
 * FR-LK-01 Task 7 — IssueLinkController end-to-end 통합테스트.
 *
 * 실제 Testcontainers PostgreSQL + 전체 스택(Controller → Service → Repository) 위에서 동작한다.
 *
 * ## 검증 시나리오
 * - S1. POST /links 201 — 링크 생성 성공
 * - S2. POST /links 422 — self 참조 (LINK_SELF_REFERENCE)
 * - S3. POST /links 409 — 중복 링크 (DUPLICATE_LINK)
 * - S4. POST /links 404 — target 이슈 미존재 (ISSUE_NOT_FOUND)
 * - S5. POST /links 409 — blocks 순환 탐지 (LINK_CYCLE)
 * - S6. POST /links 400 — linkType 공백 (Bean Validation)
 * - S7. POST /links 400 — 잘못된 linkType "foo"
 * - S8. GET /links 200 — outward/inward 읽기모델
 * - S9. DELETE /links/{linkId} 204 — 링크 해제 성공
 * - S10. DELETE /links/{linkId} 404 — 미존재 linkId (LINK_NOT_FOUND)
 * - S11. PATCH /parent 200 — 부모 설정 성공
 * - S12. PATCH /parent 200 null — 부모 해제
 * - S13. PATCH /parent 422 — self 참조 (PARENT_SELF_REFERENCE)
 * - S14. PATCH /parent 409 — 순환 탐지 (PARENT_CYCLE)
 * - S15. PATCH /parent 404 — parent 이슈 미존재
 * - S16. base 이슈 미존재 → 404 (모든 엔드포인트)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueLinkControllerIntegrationTest.TestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueLinkControllerIntegrationTest {
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
                    .withDatabaseName("bts_lk_test")
                    .withUsername("bts")
                    .withPassword("bts_lk_test")
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
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())

        @Bean
        open fun issueLinkRepository(dsl: DSLContext): IssueLinkRepository = IssueLinkRepository(dsl)

        @Bean
        open fun issueRepository(dsl: DSLContext): IssueRepository = IssueRepository(dsl)

        /** FR-PJ-04 PR-4 Task 9 — 실 ProjectArchiveGuard(공유 dsl 위). */
        @Bean
        open fun projectArchiveGuard(dsl: DSLContext): ProjectArchiveGuard {
            return ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))
        }

        /**
         * 이슈 권한 resolver 스텁 — 테스트가 `deny` 로 특정 (권한, 이슈키) 조합을 막을 수 있다.
         *
         * 기본은 전부 허용이다. 기존 19개 시나리오는 권한을 다루지 않으므로 그대로 통과해야 하고,
         * 새로 추가한 거부 시나리오만 명시적으로 `deny` 를 건다.
         */
        @Bean
        open fun issuePermissionResolver(): TogglableIssuePermissionResolver = TogglableIssuePermissionResolver()

        @Bean
        open fun linkApplicationService(
            issueRepository: IssueRepository,
            issueLinkRepository: IssueLinkRepository,
            archiveGuard: ProjectArchiveGuard,
            permissionResolver: TogglableIssuePermissionResolver,
        ): LinkApplicationService {
            return LinkApplicationService(
                issueRepository,
                issueLinkRepository,
                archiveGuard,
                permissionResolver,
            )
        }

        @Bean
        open fun issueParentService(
            issueRepository: IssueRepository,
            archiveGuard: ProjectArchiveGuard,
            permissionResolver: TogglableIssuePermissionResolver,
        ): IssueParentService = IssueParentService(issueRepository, archiveGuard, permissionResolver)

        @Bean
        open fun issueLinkController(
            linkApplicationService: LinkApplicationService,
            issueParentService: IssueParentService,
            issueRepository: IssueRepository,
        ): IssueLinkController = IssueLinkController(linkApplicationService, issueParentService, issueRepository)

        @Bean
        open fun linkExceptionHandler(): LinkExceptionHandler = LinkExceptionHandler()
    }

    /**
     * (권한, 이슈키) 단위로 거부를 켤 수 있는 [IssuePermissionResolver] 스텁.
     *
     * 기본 허용인 이유 — 이 파일의 기존 19개 시나리오는 권한을 다루지 않는다. 기본을 거부로 두면
     * 그 전부를 고쳐야 해서, 이번 봉합이 만든 회귀와 원래 있던 결함이 뒤섞인다.
     */
    class TogglableIssuePermissionResolver : IssuePermissionResolver {
        private val denied = mutableSetOf<Pair<IssuePermission, String>>()

        fun deny(
            permission: IssuePermission,
            issueKey: String,
        ) {
            denied += permission to issueKey
        }

        fun reset() = denied.clear()

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            val key = (scope as? IssueScope.Issue)?.key ?: return true
            return (permission to key) !in denied
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var permissionResolver: TogglableIssuePermissionResolver

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper =
        ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        private const val PROJECT_KEY = "LKTEST"

        /** 컨트롤러가 CurrentActor 로 읽는 테스트 actor UUID. */
        private val TEST_ACTOR_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        private var migrated = false
        private var projectSeeded = false

        /** issue_types.id — V003 task 시드로 생성되는 기본 타입 id (BIGSERIAL 시작값 1) */
        private var taskTypeId: Long = -1L
        private var testProjectId: UUID = UUID.randomUUID()
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!projectSeeded) {
            seedProjectAndType()
            projectSeeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // 컨트롤러가 CurrentActor.current() 로 actor 를 추출한다 — 인증 컨텍스트가 없으면 401 이다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(TEST_ACTOR_ID.toString(), null, emptyList())
        permissionResolver.reset()
        conn().use { c ->
            c.createStatement().use { stmt ->
                // 링크 + 이슈 초기화 (CASCADE 로 issue_links도 삭제)
                stmt.execute("DELETE FROM issue_links")
                stmt.execute("DELETE FROM issues WHERE project_id = '$testProjectId'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    // ── S1. POST /links 201 — 링크 생성 성공 ─────────────────────────────────

    @Test
    fun `S1 POST links 201 - blocks 링크 생성 성공`() {
        val sourceKey = createIssue("소스 이슈")
        val targetKey = createIssue("타겟 이슈")

        mockMvc.perform(
            post("/api/v1/issues/$sourceKey/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetKey" to targetKey, "linkType" to "blocks"))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.linkType").value("BLOCKS"))
            .andExpect(jsonPath("$.data.direction").value("OUTWARD"))
            .andExpect(jsonPath("$.data.label").value("blocks"))
            .andExpect(jsonPath("$.data.otherIssue.key").value(targetKey))
    }

    // ── SEC. 권한 게이트 (2026-07-27 봉합 — 이전에는 검사가 0건이었다) ──────────

    /**
     * 링크 API 는 2026-07-27 이전까지 **`IssuePermission` 검사가 하나도 없었다.**
     * `SecurityConfig` 의 `.authenticated()` 만 통과하면 자기가 멤버가 아닌 프로젝트의,
     * 심지어 볼 수 없는 기밀 이슈에도 링크를 걸고 지울 수 있었다.
     *
     * 잠복한 이유는 컨트롤러 KDoc 의 *"created_by 를 저장하지 않으므로 actor 추출이 불필요하다"* 였다 —
     * 「기록 안 함」을 「검사 안 해도 됨」의 근거로 쓴 문장이다.
     */
    @Test
    fun `SEC1 POST links 403 - source 이슈 UPDATE 권한이 없으면 거부`() {
        val sourceKey = createIssue("SEC1 소스")
        val targetKey = createIssue("SEC1 타겟")
        permissionResolver.deny(IssuePermission.UPDATE, sourceKey)

        mockMvc.perform(
            post("/api/v1/issues/$sourceKey/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetKey" to targetKey, "linkType" to "blocks"))),
        )
            .andExpect(status().isForbidden)
    }

    /**
     * ★양끝 검사. source 만 보면 **볼 수 없는 이슈를 target 으로 지목**해
     * 「없음(404)」과 「이미 링크됨(409)」의 차이로 실재를 확인할 수 있다.
     */
    @Test
    fun `SEC2 POST links 403 - target 이슈 UPDATE 권한이 없으면 거부`() {
        val sourceKey = createIssue("SEC2 소스")
        val targetKey = createIssue("SEC2 타겟")
        permissionResolver.deny(IssuePermission.UPDATE, targetKey)

        mockMvc.perform(
            post("/api/v1/issues/$sourceKey/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetKey" to targetKey, "linkType" to "blocks"))),
        )
            .andExpect(status().isForbidden)
    }

    /**
     * ★권한을 **리소스 조회보다 먼저** 건다는 것이 판별자다.
     * 존재하지 않는 이슈 키로 요청해도 404 가 아니라 403 이어야 한다 —
     * 그렇지 않으면 응답 코드 차이로 이슈 실재를 열거당한다.
     */
    @Test
    fun `SEC3 DELETE links 403 - 권한 검사가 리소스 조회보다 먼저다`() {
        val missingKey = "LKTEST-9999"
        permissionResolver.deny(IssuePermission.UPDATE, missingKey)

        mockMvc.perform(delete("/api/v1/issues/$missingKey/links/1"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `SEC4 GET links 403 - VIEW 권한이 없으면 거부`() {
        val issueKey = createIssue("SEC4 이슈")
        permissionResolver.deny(IssuePermission.VIEW, issueKey)

        mockMvc.perform(get("/api/v1/issues/$issueKey/links"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `SEC5 PATCH parent 403 - child UPDATE 권한이 없으면 거부`() {
        val childKey = createIssue("SEC5 자식")
        val parentKey = createIssue("SEC5 부모")
        permissionResolver.deny(IssuePermission.UPDATE, childKey)

        mockMvc.perform(
            patch("/api/v1/issues/$childKey/parent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("parentKey" to parentKey))),
        )
            .andExpect(status().isForbidden)
    }

    /** 부모 설정도 양끝 검사 — parent 쪽 권한만 없어도 거부한다. */
    @Test
    fun `SEC6 PATCH parent 403 - parent UPDATE 권한이 없으면 거부`() {
        val childKey = createIssue("SEC6 자식")
        val parentKey = createIssue("SEC6 부모")
        permissionResolver.deny(IssuePermission.UPDATE, parentKey)

        mockMvc.perform(
            patch("/api/v1/issues/$childKey/parent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("parentKey" to parentKey))),
        )
            .andExpect(status().isForbidden)
    }

    /**
     * 대조군 — 게이트가 "전부 403" 으로 무너지지 않았음을 확인한다.
     * 이 단언이 없으면 권한을 과하게 걸어도 위 6건이 통과해 초록으로 보인다.
     */
    @Test
    fun `SEC7 대조군 - 권한이 있으면 링크 생성이 성공한다`() {
        val sourceKey = createIssue("SEC7 소스")
        val targetKey = createIssue("SEC7 타겟")

        mockMvc.perform(
            post("/api/v1/issues/$sourceKey/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetKey" to targetKey, "linkType" to "blocks"))),
        )
            .andExpect(status().isCreated)
    }

    // ── S2. POST /links 422 — self 참조 ─────────────────────────────────────

    @Test
    fun `S2 POST links 422 - 자기 자신 링크는 LINK_SELF_REFERENCE`() {
        val issueKey = createIssue("셀프 이슈")

        mockMvc.perform(
            post("/api/v1/issues/$issueKey/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetKey" to issueKey, "linkType" to "blocks"))),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("LINK_SELF_REFERENCE"))
    }

    // ── S3. POST /links 409 — 중복 링크 ──────────────────────────────────────

    @Test
    fun `S3 POST links 409 - 동일 조합 중복 시 DUPLICATE_LINK`() {
        val sourceKey = createIssue("A 이슈")
        val targetKey = createIssue("B 이슈")
        createLink(sourceKey, targetKey, "blocks")

        mockMvc.perform(
            post("/api/v1/issues/$sourceKey/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetKey" to targetKey, "linkType" to "blocks"))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("DUPLICATE_LINK"))
    }

    // ── S4. POST /links 404 — target 이슈 미존재 ──────────────────────────────

    @Test
    fun `S4 POST links 404 - target 이슈 미존재 시 ISSUE_NOT_FOUND`() {
        val sourceKey = createIssue("소스만 있는 이슈")

        mockMvc.perform(
            post("/api/v1/issues/$sourceKey/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetKey" to "LKTEST-9999", "linkType" to "blocks"))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── S5. POST /links 409 — blocks 순환 탐지 ────────────────────────────────

    @Test
    fun `S5 POST links 409 - blocks 순환 탐지 시 LINK_CYCLE`() {
        val keyA = createIssue("A 이슈")
        val keyB = createIssue("B 이슈")
        createLink(keyA, keyB, "blocks") // A blocks B

        mockMvc.perform(
            post("/api/v1/issues/$keyB/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetKey" to keyA, "linkType" to "blocks"))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("LINK_CYCLE"))
    }

    // ── S6. POST /links 400 — linkType 공백 ───────────────────────────────────

    @Test
    fun `S6 POST links 400 - linkType 공백 시 Bean Validation 400`() {
        val issueKey = createIssue("이슈")

        mockMvc.perform(
            post("/api/v1/issues/$issueKey/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetKey" to "LKTEST-2", "linkType" to ""))),
        )
            .andExpect(status().isBadRequest)
    }

    // ── S7. POST /links 400 — 잘못된 linkType ────────────────────────────────

    @Test
    fun `S7 POST links 400 - 알 수 없는 linkType 은 400`() {
        val issueKey = createIssue("이슈")

        mockMvc.perform(
            post("/api/v1/issues/$issueKey/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetKey" to "LKTEST-2", "linkType" to "foo"))),
        )
            .andExpect(status().isBadRequest)
    }

    // ── S8. GET /links 200 — outward/inward 읽기모델 ─────────────────────────

    @Test
    fun `S8 GET links 200 - outward 와 inward 분리 응답`() {
        val keyA = createIssue("A")
        val keyB = createIssue("B")
        createLink(keyA, keyB, "blocks")

        // A 입장: outward=[blocks B], inward=[]
        mockMvc.perform(get("/api/v1/issues/$keyA/links"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.outward.length()").value(1))
            .andExpect(jsonPath("$.data.outward[0].linkType").value("BLOCKS"))
            .andExpect(jsonPath("$.data.outward[0].direction").value("OUTWARD"))
            .andExpect(jsonPath("$.data.outward[0].label").value("blocks"))
            .andExpect(jsonPath("$.data.outward[0].otherIssue.key").value(keyB))
            .andExpect(jsonPath("$.data.outward[0].otherIssue.statusKey").isNotEmpty)
            .andExpect(jsonPath("$.data.inward.length()").value(0))

        // B 입장: inward=[is blocked by A], outward=[]
        mockMvc.perform(get("/api/v1/issues/$keyB/links"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.inward.length()").value(1))
            .andExpect(jsonPath("$.data.inward[0].linkType").value("BLOCKS"))
            .andExpect(jsonPath("$.data.inward[0].direction").value("INWARD"))
            .andExpect(jsonPath("$.data.inward[0].label").value("is blocked by"))
            .andExpect(jsonPath("$.data.outward.length()").value(0))
    }

    // ── S9. DELETE /links/{linkId} 204 ───────────────────────────────────────

    @Test
    fun `S9 DELETE links 204 - 링크 해제 성공`() {
        val keyA = createIssue("A")
        val keyB = createIssue("B")
        val linkId = createLink(keyA, keyB, "relates")

        mockMvc.perform(delete("/api/v1/issues/$keyA/links/$linkId"))
            .andExpect(status().isNoContent)
    }

    // ── S10. DELETE /links/{linkId} 404 — 미존재 linkId ─────────────────────

    @Test
    fun `S10 DELETE links 404 - 미존재 linkId 시 LINK_NOT_FOUND`() {
        val issueKey = createIssue("이슈")

        mockMvc.perform(delete("/api/v1/issues/$issueKey/links/99999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("LINK_NOT_FOUND"))
    }

    // ── S11. PATCH /parent 200 — 부모 설정 ───────────────────────────────────

    @Test
    fun `S11 PATCH parent 200 - 부모 설정 성공`() {
        val childKey = createIssue("자식 이슈")
        val parentKey = createIssue("부모 이슈")

        mockMvc.perform(
            patch("/api/v1/issues/$childKey/parent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("parentKey" to parentKey))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value(childKey))
            .andExpect(jsonPath("$.data.parent.key").value(parentKey))
    }

    // ── S12. PATCH /parent null — 부모 해제 ──────────────────────────────────

    @Test
    fun `S12 PATCH parent null 200 - 부모 해제 성공`() {
        val childKey = createIssue("자식 이슈")
        val parentKey = createIssue("부모 이슈")

        // 부모 설정 후
        mockMvc.perform(
            patch("/api/v1/issues/$childKey/parent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("parentKey" to parentKey))),
        ).andExpect(status().isOk)

        // 해제
        mockMvc.perform(
            patch("/api/v1/issues/$childKey/parent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"parentKey": null}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value(childKey))
            .andExpect(jsonPath("$.data.parent").doesNotExist())
    }

    // ── S13. PATCH /parent 422 — self 참조 ───────────────────────────────────

    @Test
    fun `S13 PATCH parent 422 - 자기 자신 부모 지정 시 PARENT_SELF_REFERENCE`() {
        val issueKey = createIssue("셀프 이슈")

        mockMvc.perform(
            patch("/api/v1/issues/$issueKey/parent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("parentKey" to issueKey))),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("PARENT_SELF_REFERENCE"))
    }

    // ── S14. PATCH /parent 409 — 순환 탐지 ───────────────────────────────────

    @Test
    fun `S14 PATCH parent 409 - 순환 계층 형성 시 PARENT_CYCLE`() {
        val keyA = createIssue("A")
        val keyB = createIssue("B")

        // A의 부모를 B로 설정
        mockMvc.perform(
            patch("/api/v1/issues/$keyA/parent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("parentKey" to keyB))),
        ).andExpect(status().isOk)

        // B의 부모를 A로 설정하면 순환
        mockMvc.perform(
            patch("/api/v1/issues/$keyB/parent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("parentKey" to keyA))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("PARENT_CYCLE"))
    }

    // ── S15. PATCH /parent 404 — parent 이슈 미존재 ──────────────────────────

    @Test
    fun `S15 PATCH parent 404 - parent 이슈 미존재 시 ISSUE_NOT_FOUND`() {
        val childKey = createIssue("자식 이슈")

        mockMvc.perform(
            patch("/api/v1/issues/$childKey/parent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("parentKey" to "LKTEST-9999"))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── S16. base 이슈 미존재 → 404 ──────────────────────────────────────────

    @Test
    fun `S16 base 이슈 미존재 - GET links 404 ISSUE_NOT_FOUND`() {
        mockMvc.perform(get("/api/v1/issues/LKTEST-9999/links"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    @Test
    fun `S16b base 이슈 미존재 - DELETE links 404 ISSUE_NOT_FOUND`() {
        mockMvc.perform(delete("/api/v1/issues/LKTEST-9999/links/1"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    @Test
    fun `S16c base 이슈 미존재 - PATCH parent 404 ISSUE_NOT_FOUND`() {
        mockMvc.perform(
            patch("/api/v1/issues/LKTEST-9999/parent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("parentKey" to "LKTEST-1"))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** 이슈를 DB에 직접 삽입하고 이슈 키를 반환한다. */
    private fun createIssue(summary: String): String {
        var key: String? = null
        conn().use { c ->
            c.prepareStatement(
                """
                UPDATE projects SET key_sequence = key_sequence + 1 WHERE id = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, testProjectId)
                stmt.executeUpdate()
            }
            c.prepareStatement("SELECT key_sequence FROM projects WHERE id = ?").use { stmt ->
                stmt.setObject(1, testProjectId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    key = "$PROJECT_KEY-${rs.getInt(1)}"
                }
            }
            c.prepareStatement(
                """
                INSERT INTO issues (project_id, key, summary, reporter_id, current_state_key, type_id, version)
                VALUES (?, ?, ?, '00000000-0000-0000-0000-000000000001', 'open', ?, 1)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, testProjectId)
                stmt.setString(2, key)
                stmt.setString(3, summary)
                stmt.setLong(4, taskTypeId)
                stmt.executeUpdate()
            }
        }
        return requireNotNull(key)
    }

    /** MockMvc를 통해 링크를 생성하고 linkId를 반환한다. */
    private fun createLink(
        sourceKey: String,
        targetKey: String,
        linkType: String,
    ): Long {
        val result =
            mockMvc.perform(
                post("/api/v1/issues/$sourceKey/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(mapOf("targetKey" to targetKey, "linkType" to linkType))),
            ).andExpect(status().isCreated)
                .andReturn()
        val json = mapper.readTree(result.response.contentAsString)
        return json["data"]["id"].asLong()
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

    @Suppress("NestedBlockDepth") // JDBC try-with-resources(conn→stmt→rs) 시드 보일러플레이트 — 테스트 1회성 setup
    private fun seedProjectAndType() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Link Integration Test Project")
                stmt.executeUpdate()
            }
            c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    testProjectId = rs.getObject(1) as UUID
                }
            }
            // V003 시드로 삽입된 task 타입 id 조회
            c.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' LIMIT 1").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        taskTypeId = rs.getLong(1)
                    }
                }
            }
            // task 타입이 없으면 직접 삽입
            if (taskTypeId == -1L) {
                c.prepareStatement(
                    "INSERT INTO issue_types (key, name, hierarchy_level) VALUES ('task', 'Task', 0) RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        taskTypeId = rs.getLong(1)
                    }
                }
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
