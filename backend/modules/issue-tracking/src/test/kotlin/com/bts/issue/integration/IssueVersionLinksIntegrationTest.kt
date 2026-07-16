// PATCH /api/v1/issues/{key}/affects-versions 및 /fix-versions 전 구간 통합 테스트 — S1~S12 + EC7 + S6b

package com.bts.issue.integration

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.application.AppChangeVersionsRequest
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueLinkedVersionNotFoundException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * PATCH /api/v1/issues/{key}/affects-versions 및 /fix-versions 전 구간 Testcontainers 통합 테스트.
 *
 * 컨트롤러 → 서비스 → 리포지토리 → 실 Postgres 전 구간을 검증한다.
 * 단위 mock이 못 잡는 결선·매핑 결함(특히 422 IssueLinkedVersionNotFoundException → IssueExceptionHandler 매핑)을
 * 실증한다 (CONCERN-2 도메인예외 HTTP 핸들러 스코프).
 *
 * [TestConfig] (Testcontainers singleton + Flyway migrate + Spring 빈 구성) 를 재사용한다.
 * VRTEST 프로젝트와 버전 픽스처를 독립적으로 시드한다.
 *
 * ## 검증 시나리오
 * - S1. Fix 연결 정상 — version bump 확인
 * - S2. Affects 연결 정상 — RELEASED 버전 허용
 * - S3. 전체 교체(replace 의미) — v1.3 → v1.4
 * - S4. 빈 목록 전체 해제 — 200 + affectsVersionIds 빈 배열
 * - S5. ARCHIVED 버전 연결 허용 — 200 (API 허용 정책)
 * - S6. 타 프로젝트 버전 → 422 ISSUE_LINKED_VERSION_NOT_FOUND (CONCERN-2 실증)
 * - S6b. 타 프로젝트 버전 서비스 직접 호출 — validateVersions 양성 단언 회귀 가드(FR-AT-07 PR-B T6)
 * - S7. 소프트 삭제된 버전 → 422 ISSUE_LINKED_VERSION_NOT_FOUND
 * - S8. 낙관락 충돌 → 409 VERSION_CONFLICT (CONCERN-2 실증)
 * - S9. 없는 이슈 → 404 ISSUE_NOT_FOUND
 * - S11. 중복 versionId 정규화 → distinct
 * - S12. 단건 조회에 affectsVersionIds/fixVersionIds 노출
 * - EC7. affects와 fix에 같은 버전 — 독립 허용
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class, IssueVersionLinksIntegrationTest.VersionLinksTestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueVersionLinksIntegrationTest {
    /**
     * 버전 연결 통합 테스트 보조 설정.
     *
     * 1. 실 VersionRepository 빈 등록 — TestConfig의 mockk(relaxed=true) 대신 실 DB 사용.
     * 2. @Primary IssueApplicationService — 실 versionRepository를 주입한 재조립.
     * 3. @Primary IssuePermissionResolver — DENY_ACTOR_UUID 거부, 나머지 허용.
     *
     * IssueComponentsIntegrationTest 패턴 동형 (조인 테이블 FK ON DELETE CASCADE 메모리 준수).
     */
    @Configuration
    @Suppress("LongParameterList")
    open class VersionLinksTestConfig {
        @Bean
        open fun realVersionRepositoryForLinks(dsl: DSLContext): VersionRepository = VersionRepository(dsl)

        @Bean
        open fun realProjectLeadRepositoryForLinks(dsl: DSLContext): ProjectLeadRepository = ProjectLeadRepository(dsl)

        @Bean
        @Primary
        open fun conditionalPermissionResolverForVersionLinks(): IssuePermissionResolver =
            object : IssuePermissionResolver {
                override fun hasPermission(
                    actorId: UUID,
                    permission: IssuePermission,
                    scope: IssueScope,
                ): Boolean = actorId != DENY_ACTOR_UUID
            }

        @Bean
        @Primary
        @Suppress("LongParameterList")
        open fun issueApplicationServiceWithRealVersions(
            repo: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            eventPublisher: IssueEventPublisher,
            permissionResolver: IssuePermissionResolver,
            workflowTransitionAdapter: WorkflowTransitionAdapter,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            userLookupPort: UserLookupPort,
            realVersionRepositoryForLinks: VersionRepository,
            realProjectLeadRepositoryForLinks: com.bts.issue.project.repository.ProjectLeadRepository,
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
                componentRepository = io.mockk.mockk(relaxed = true),
                projectLeadRepository = realProjectLeadRepositoryForLinks,
                versionRepository = realVersionRepositoryForLinks,
                clock = clock,
                historyRecorder = io.mockk.mockk(relaxed = true),
            )
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    /** S6b — validateVersions 를 서비스 계층에서 직접 호출해 예외 필드까지 단언하기 위한 빈 (양성 단언). */
    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    companion object {
        /** 권한 거부 시뮬레이션용 액터 UUID */
        val DENY_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000099")

        private const val PROJECT_KEY = "VRTEST"
        private const val OTHER_PROJECT_KEY = "VROTHER"
        private var migrated = false
        private var seeded = false

        /** VRTEST 프로젝트의 UNRELEASED 버전 UUID (S1 fix 연결용) */
        lateinit var versionUnreleased: UUID

        /** VRTEST 프로젝트의 RELEASED 버전 UUID (S2 affects 연결용) */
        lateinit var versionReleased: UUID

        /** VRTEST 프로젝트의 두 번째 UNRELEASED 버전 UUID (S3 교체용) */
        lateinit var versionUnreleased2: UUID

        /** VRTEST 프로젝트의 ARCHIVED 버전 UUID (S5 연결 허용용) */
        lateinit var versionArchived: UUID

        /** VROTHER 프로젝트 소속 버전 UUID (S6 타 프로젝트 422용) */
        lateinit var versionOtherProject: UUID
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProjectsAndVersions()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        conn().use { c ->
            c.createStatement().use { stmt ->
                // ON DELETE CASCADE 덕분에 issues 행 삭제 시 조인 테이블 고아 행도 자동 정리된다.
                stmt.execute(
                    "DELETE FROM issue_affects_versions WHERE issue_id IN " +
                        "(SELECT id FROM issues WHERE key LIKE '$PROJECT_KEY-%')",
                )
                stmt.execute(
                    "DELETE FROM issue_fix_versions WHERE issue_id IN " +
                        "(SELECT id FROM issues WHERE key LIKE '$PROJECT_KEY-%')",
                )
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── S1. Fix 연결 정상 — version bump 확인 ─────────────────────────────────

    /**
     * S1 Fix Version 연결 해피패스.
     *
     * Given  컴포넌트 미연결 이슈 (version=1)
     * When   PATCH /{key}/fix-versions { versionIds: [versionUnreleased], expectedVersion: 1 }
     * Then   200 + data.fixVersionIds 크기 1 + data.version = 2
     * Also   issue_fix_versions에 1행 존재
     */
    @Test
    fun `S1 Fix 연결 정상 - 200 및 fixVersionIds 포함과 version 증가 확인`() {
        val key = insertIssue("S1 Fix 연결 이슈")

        val body =
            mapOf(
                "versionIds" to listOf(versionUnreleased.toString()),
                "expectedVersion" to 1L,
            )

        mockMvc.perform(
            patch("/api/v1/issues/$key/fix-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.fixVersionIds.length()").value(1))
            .andExpect(jsonPath("$.data.fixVersionIds[0]").value(versionUnreleased.toString()))
            .andExpect(jsonPath("$.data.version").value(2))

        val rowCount = countFixVersionRows(key)
        assert(rowCount == 1) { "issue_fix_versions 행 수가 1이어야 하지만 $rowCount 입니다." }
    }

    // ── S2. Affects 연결 정상 — RELEASED 버전 허용 ────────────────────────────

    /**
     * S2 Affects Version 연결 — RELEASED 버전.
     *
     * Given  이슈 (version=1), VRTEST 프로젝트의 RELEASED 버전
     * When   PATCH /{key}/affects-versions { versionIds: [versionReleased], expectedVersion: 1 }
     * Then   200 + data.affectsVersionIds 크기 1
     */
    @Test
    fun `S2 Affects 연결 정상 - RELEASED 버전 허용 및 affectsVersionIds 포함 확인`() {
        val key = insertIssue("S2 Affects 연결 이슈")

        val body =
            mapOf(
                "versionIds" to listOf(versionReleased.toString()),
                "expectedVersion" to 1L,
            )

        mockMvc.perform(
            patch("/api/v1/issues/$key/affects-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.affectsVersionIds.length()").value(1))
            .andExpect(jsonPath("$.data.affectsVersionIds[0]").value(versionReleased.toString()))

        val rowCount = countAffectsVersionRows(key)
        assert(rowCount == 1) { "issue_affects_versions 행 수가 1이어야 하지만 $rowCount 입니다." }
    }

    // ── S3. 전체 교체(replace 의미) ────────────────────────────────────────────

    /**
     * S3 전체 교체 — [versionUnreleased] → [versionUnreleased2].
     *
     * Given  fixVersions = [versionUnreleased] (version=2)
     * When   PATCH { versionIds: [versionUnreleased2], expectedVersion: 2 }
     * Then   fixVersions = [versionUnreleased2], versionUnreleased 연결 해제됨
     */
    @Test
    fun `S3 전체 교체 - versionUnreleased 해제 후 versionUnreleased2 연결 확인`() {
        val key = insertIssue("S3 전체 교체 이슈")
        patchFixVersions(key, listOf(versionUnreleased), 1L)

        mockMvc.perform(
            patch("/api/v1/issues/$key/fix-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("versionIds" to listOf(versionUnreleased2.toString()), "expectedVersion" to 2L),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.fixVersionIds.length()").value(1))
            .andExpect(jsonPath("$.data.fixVersionIds[0]").value(versionUnreleased2.toString()))

        val rowCount = countFixVersionRows(key)
        assert(rowCount == 1) { "교체 후 issue_fix_versions 행이 1이어야 하지만 $rowCount 입니다." }
    }

    // ── S4. 빈 목록 전체 해제 ─────────────────────────────────────────────────

    /**
     * S4 빈 목록 전체 해제.
     *
     * Given  affectsVersions = [versionReleased] (version=2)
     * When   PATCH { versionIds: [], expectedVersion: 2 }
     * Then   200 + affectsVersionIds = [] + issue_affects_versions 행 0
     */
    @Test
    fun `S4 빈 목록 전체 해제 - 200 및 affectsVersionIds 빈 배열과 행 0 확인`() {
        val key = insertIssue("S4 전체 해제 이슈")
        patchAffectsVersions(key, listOf(versionReleased), 1L)

        mockMvc.perform(
            patch("/api/v1/issues/$key/affects-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("versionIds" to emptyList<String>(), "expectedVersion" to 2L),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.affectsVersionIds.length()").value(0))

        val rowCount = countAffectsVersionRows(key)
        assert(rowCount == 0) { "전체 해제 후 issue_affects_versions 행이 0이어야 하지만 $rowCount 입니다." }
    }

    // ── S5. ARCHIVED 버전 연결 허용 ───────────────────────────────────────────

    /**
     * S5 ARCHIVED 버전 연결 허용 (EC3).
     *
     * Given  versionArchived (status='ARCHIVED', deleted_at IS NULL)
     * When   PATCH .../affects-versions { versionIds: [versionArchived], expectedVersion: 1 }
     * Then   200 OK — ARCHIVED 상태이지만 deleted_at=null이므로 연결 가능
     */
    @Test
    fun `S5 ARCHIVED 버전 연결 허용 - 200 확인`() {
        val key = insertIssue("S5 ARCHIVED 연결 이슈")

        mockMvc.perform(
            patch("/api/v1/issues/$key/affects-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("versionIds" to listOf(versionArchived.toString()), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.affectsVersionIds.length()").value(1))
            .andExpect(jsonPath("$.data.affectsVersionIds[0]").value(versionArchived.toString()))
    }

    // ── S6. 타 프로젝트 버전 → 422 ISSUE_LINKED_VERSION_NOT_FOUND ─────────────

    /**
     * S6 타 프로젝트 버전 → 422 (CONCERN-2 실증).
     *
     * IssueLinkedVersionNotFoundException → IssueExceptionHandler → 422 ISSUE_LINKED_VERSION_NOT_FOUND
     * 매핑이 basePackages(com.bts.issue.adapter.inbound.rest) 안에서 정상 작동함을 실증한다.
     *
     * Given  VROTHER 프로젝트 소속 versionOtherProject
     * When   VRTEST 이슈에 versionOtherProject 연결 시도
     * Then   422 + errorCode = ISSUE_LINKED_VERSION_NOT_FOUND
     */
    @Test
    fun `S6 타 프로젝트 버전 - 422 ISSUE_LINKED_VERSION_NOT_FOUND 실증`() {
        val key = insertIssue("S6 타 프로젝트 버전 이슈")

        mockMvc.perform(
            patch("/api/v1/issues/$key/fix-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("versionIds" to listOf(versionOtherProject.toString()), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_LINKED_VERSION_NOT_FOUND"))

        val rowCount = countFixVersionRows(key)
        assert(rowCount == 0) { "422 응답 시 변경 없어야 하지만 $rowCount 행이 있습니다." }
    }

    // ── S6b. 타 프로젝트 버전 — 서비스 직접 호출 양성 단언 (FR-AT-07 PR-B T6 회귀 가드) ──

    /**
     * S6b 타 프로젝트 버전을 fixVersions 로 지정하면 그 versionId 를 담은 예외로 거부된다.
     *
     * S6(HTTP 계층)과 달리 컨트롤러를 우회해 [IssueApplicationService.changeFixVersions] 를 직접 호출하고
     * 예외의 [IssueLinkedVersionNotFoundException.versionId] 필드까지 단언한다.
     * "실패했다"만 단언하면 vacuous — validateVersions 를 통째로 지워도 이슈 부재/OCC 등 다른 사유로
     * 실패하기만 하면 통과해버린다. "어느 versionId 때문에 실패했는가"를 못박아야 이 가드가 진짜로
     * validateVersions 를 지킨다.
     *
     * Given  VROTHER 프로젝트 소속 versionOtherProject, VRTEST 소속 이슈(version=1)
     * When   issueApplicationService.changeFixVersions(actor, key, AppChangeVersionsRequest([versionOtherProject], 1))
     * Then   IssueLinkedVersionNotFoundException 발생 + ex.versionId == versionOtherProject
     * Also   이슈의 fixVersionIds 는 변경되지 않음 (DB 재조회로 확인)
     */
    @Test
    fun `타 프로젝트 버전을 fixVersions 로 지정하면 그 versionId 를 담은 예외로 거부된다`() {
        val key = insertIssue("S6b 서비스 직접 호출 이슈")
        val actor = ActorId(UUID.fromString("11111111-1111-4111-8111-111111111111"))

        val ex =
            shouldThrow<IssueLinkedVersionNotFoundException> {
                issueApplicationService.changeFixVersions(
                    actor,
                    IssueKey(key),
                    AppChangeVersionsRequest(listOf(versionOtherProject), 1L),
                )
            }
        ex.versionId shouldBe versionOtherProject

        val rowCount = countFixVersionRows(key)
        assert(rowCount == 0) { "예외 발생 시 issue_fix_versions 행이 0이어야 하지만 $rowCount 입니다." }
    }

    // ── S7. 소프트 삭제된 버전 → 422 ─────────────────────────────────────────

    /**
     * S7 소프트 삭제된 버전 → 422 ISSUE_LINKED_VERSION_NOT_FOUND.
     *
     * deleted_at != null 인 버전은 validateVersions 에서 findById(id, projectId) 가
     * deleted_at IS NULL 조건으로 조회하므로 IssueLinkedVersionNotFoundException 이 발생한다.
     */
    @Test
    fun `S7 소프트삭제 버전 - 422 ISSUE_LINKED_VERSION_NOT_FOUND`() {
        val key = insertIssue("S7 소프트삭제 버전 이슈")
        val deletedVersionId = insertVersion(PROJECT_KEY, "v-deleted", "UNRELEASED")
        softDeleteVersion(deletedVersionId)

        mockMvc.perform(
            patch("/api/v1/issues/$key/affects-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("versionIds" to listOf(deletedVersionId.toString()), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_LINKED_VERSION_NOT_FOUND"))
    }

    // ── S8. 낙관락 충돌 → 409 VERSION_CONFLICT ────────────────────────────────

    /**
     * S8 낙관락 충돌 (CONCERN-2 실증).
     *
     * IssueVersionConflictException → IssueExceptionHandler → 409 VERSION_CONFLICT
     * 매핑이 basePackages 안에서 정상 작동함을 실증한다.
     *
     * Given  이슈 version=1
     * When   PATCH { expectedVersion: 99 } (stale)
     * Then   409 + errorCode = VERSION_CONFLICT
     */
    @Test
    fun `S8 낙관락 충돌 - 409 VERSION_CONFLICT 실증`() {
        val key = insertIssue("S8 낙관락 이슈")

        mockMvc.perform(
            patch("/api/v1/issues/$key/fix-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("versionIds" to listOf(versionUnreleased.toString()), "expectedVersion" to 99L),
                    ),
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))

        val rowCount = countFixVersionRows(key)
        assert(rowCount == 0) { "409 응답 시 변경 없어야 하지만 $rowCount 행이 있습니다." }
    }

    // ── S9. 없는 이슈 → 404 ISSUE_NOT_FOUND ──────────────────────────────────

    /**
     * S9 없는 이슈 → 404 ISSUE_NOT_FOUND.
     *
     * When   PATCH /api/v1/issues/VRTEST-99999/affects-versions
     * Then   404 + errorCode = ISSUE_NOT_FOUND
     */
    @Test
    fun `S9 없는 이슈 - 404 ISSUE_NOT_FOUND`() {
        mockMvc.perform(
            patch("/api/v1/issues/VRTEST-99999/affects-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("versionIds" to listOf(versionUnreleased.toString()), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── S11. 중복 versionId 정규화 ────────────────────────────────────────────

    /**
     * S11 중복 versionId 정규화.
     *
     * When   PATCH { versionIds: [versionUnreleased, versionUnreleased], expectedVersion: 1 }
     * Then   200 + fixVersionIds = [versionUnreleased] (distinct) + 중복 행 없음
     */
    @Test
    fun `S11 중복 versionId 정규화 - 200 및 fixVersionIds distinct 확인`() {
        val key = insertIssue("S11 중복 정규화 이슈")

        mockMvc.perform(
            patch("/api/v1/issues/$key/fix-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "versionIds" to
                                listOf(
                                    versionUnreleased.toString(),
                                    versionUnreleased.toString(),
                                ),
                            "expectedVersion" to 1L,
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.fixVersionIds.length()").value(1))
            .andExpect(jsonPath("$.data.fixVersionIds[0]").value(versionUnreleased.toString()))

        val rowCount = countFixVersionRows(key)
        assert(rowCount == 1) { "중복 정규화 후 issue_fix_versions 행이 1이어야 하지만 $rowCount 입니다." }
    }

    // ── S12. 단건 조회에 affectsVersionIds/fixVersionIds 노출 ─────────────────

    /**
     * S12 단건 조회 응답 노출.
     *
     * Given  이슈에 affects=[versionReleased], fix=[versionUnreleased]
     * When   GET /api/v1/issues/{key}
     * Then   응답에 affectsVersionIds: [versionReleased], fixVersionIds: [versionUnreleased] 포함
     */
    @Test
    fun `S12 단건 조회에 affectsVersionIds 및 fixVersionIds 노출 확인`() {
        val key = insertIssue("S12 단건 조회 이슈")
        patchAffectsVersions(key, listOf(versionReleased), 1L)
        patchFixVersions(key, listOf(versionUnreleased), 2L)

        mockMvc.perform(get("/api/v1/issues/$key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.affectsVersionIds.length()").value(1))
            .andExpect(jsonPath("$.data.affectsVersionIds[0]").value(versionReleased.toString()))
            .andExpect(jsonPath("$.data.fixVersionIds.length()").value(1))
            .andExpect(jsonPath("$.data.fixVersionIds[0]").value(versionUnreleased.toString()))
    }

    // ── EC7. affects와 fix에 같은 버전 — 독립 허용 ───────────────────────────

    /**
     * EC7 affects와 fix에 같은 버전 — 독립 허용.
     *
     * Given  이슈에 affects=[versionReleased] 연결 상태
     * When   같은 versionReleased를 fix-versions에도 연결
     * Then   200 OK 두 엔드포인트 모두 성공 — affects와 fix는 독립 테이블
     */
    @Test
    fun `EC7 affects와 fix에 같은 버전 독립 허용 - 두 엔드포인트 모두 200 확인`() {
        val key = insertIssue("EC7 교차 버전 이슈")
        patchAffectsVersions(key, listOf(versionReleased), 1L)
        patchFixVersions(key, listOf(versionReleased), 2L)

        mockMvc.perform(get("/api/v1/issues/$key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.affectsVersionIds.length()").value(1))
            .andExpect(jsonPath("$.data.affectsVersionIds[0]").value(versionReleased.toString()))
            .andExpect(jsonPath("$.data.fixVersionIds.length()").value(1))
            .andExpect(jsonPath("$.data.fixVersionIds[0]").value(versionReleased.toString()))
    }

    // ── private helpers ──────────────────────────────────────────────────────

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
    private fun seedProjectsAndVersions() {
        conn().use { c ->
            c.autoCommit = false

            // VRTEST 프로젝트
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Version Links Integration Test Project")
                stmt.executeUpdate()
            }

            // VROTHER 프로젝트 (타 프로젝트 422 시나리오용)
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, OTHER_PROJECT_KEY)
                stmt.setString(2, "Other Project for Version Link Test")
                stmt.executeUpdate()
            }

            c.commit()
        }

        versionUnreleased = insertVersion(PROJECT_KEY, "v1.3", "UNRELEASED")
        versionReleased = insertVersion(PROJECT_KEY, "v1.2", "RELEASED")
        versionUnreleased2 = insertVersion(PROJECT_KEY, "v1.4", "UNRELEASED")
        versionArchived = insertVersion(PROJECT_KEY, "v1.0", "ARCHIVED")
        versionOtherProject = insertVersion(OTHER_PROJECT_KEY, "vX", "UNRELEASED")
    }

    private fun insertVersion(
        projectKey: String,
        name: String,
        status: String,
    ): UUID {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement(
                "INSERT INTO versions (project_id, name, status) VALUES (?, ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.setString(3, status)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }
        }
    }

    private fun softDeleteVersion(versionId: UUID) {
        conn().use { c ->
            c.prepareStatement(
                "UPDATE versions SET deleted_at = NOW() WHERE id = ?",
            ).use { stmt ->
                stmt.setObject(1, versionId)
                stmt.executeUpdate()
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

    private fun insertIssue(summary: String): String =
        conn().use { c ->
            c.autoCommit = false

            val seq =
                c.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            val issueKey = "$PROJECT_KEY-$seq"
            val projectId = fetchProjectId(PROJECT_KEY)

            val taskTypeId =
                c.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            c.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "VALUES (?, ?, ?, ?, ?, 1, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, UUID.fromString("00000000-0000-0000-0000-000000000001"))
                stmt.setString(5, "open")
                stmt.setLong(6, taskTypeId)
                stmt.executeUpdate()
            }

            c.commit()
            issueKey
        }

    private fun patchAffectsVersions(
        key: String,
        versionIds: List<UUID>,
        expectedVersion: Long,
    ) {
        mockMvc.perform(
            patch("/api/v1/issues/$key/affects-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "versionIds" to versionIds.map { it.toString() },
                            "expectedVersion" to expectedVersion,
                        ),
                    ),
                ),
        ).andExpect(status().isOk)
    }

    private fun patchFixVersions(
        key: String,
        versionIds: List<UUID>,
        expectedVersion: Long,
    ) {
        mockMvc.perform(
            patch("/api/v1/issues/$key/fix-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "versionIds" to versionIds.map { it.toString() },
                            "expectedVersion" to expectedVersion,
                        ),
                    ),
                ),
        ).andExpect(status().isOk)
    }

    private fun countAffectsVersionRows(issueKey: String): Int =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM issue_affects_versions iav " +
                    "JOIN issues i ON iav.issue_id = i.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun countFixVersionRows(issueKey: String): Int =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM issue_fix_versions ifv " +
                    "JOIN issues i ON ifv.issue_id = i.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
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
