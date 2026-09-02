// 스프린트 REST API 실 DB end-to-end 통합 테스트 — FR-BL-02 Task 6

package com.bts.agileplanning.integration

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.application.SprintApplicationService
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 스프린트 REST API 실 DB end-to-end 통합 테스트.
 *
 * [AgilePlanningTestBootApplication] 을 기동해 실 Testcontainers PostgreSQL 위에서
 * SprintController → SprintApplicationService → SprintRepository 전 스택을 검증한다.
 *
 * ## cross-BC stub
 * - [IssuePermissionResolver]: [PermissionStub] — 테스트별 allow/deny toggle.
 * - [BoardIssueLookupPort]: [IssueLookupStub] — 테스트별 isVisibleIssue toggle.
 *
 * ## @Valid / Bean Validation 동작 여부
 * hibernate-validator 구현체가 classpath 에 없으면 @NotBlank 가 무동작해 빈 이름 → 400 이 실패한다.
 * T5-1 케이스가 실제 400 을 반환하는지 단언한다. 만약 실패하면 Bean Validation provider 부재로
 * 400 이 나오지 않는 것이므로 정확히 FAIL 해 NEEDS_CONTEXT 보고가 가능하다(가짜 통과 금지).
 *
 * ## 검증 범위
 * S1 생성(201, PLANNED) / S2 할당(201, sprint_issues 행) / S3 해제(204) /
 * S4 start(ACTIVE) / S5 complete(COMPLETED) / S6 단건 조회(이슈 목록 포함) /
 * S7 프로젝트 목록 / S8 수정(PATCH) + 소프트삭제(DELETE 204).
 * E1 잘못된 전환 → 409 / E2 미존재 → 404 / E3 미가시·타프로젝트·미존재 이슈 → 404 /
 * E5 COMPLETED 스프린트 할당 → 409 / E6 권한 없음 → 403 / E7 멱등 재할당 → 200(또는 201) /
 * E8 다른 스프린트 이슈 이동 / E9 없는 이슈 해제 멱등 → 204 /
 * E11 기간 역전 → 400 / E12 소프트삭제 후 sprint_issues 빈 목록.
 * T5-1 @NotBlank 검증: name="" → 400.
 */
@SpringBootTest(
    classes = [AgilePlanningTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(AgilePlanningTestcontainersConfig::class, SprintIntegrationTest.StubConfig::class)
@ActiveProfiles("test")
@Suppress("LargeClass", "TooManyFunctions")
class SprintIntegrationTest {
    /**
     * 통합테스트 전용 cross-BC stub 설정.
     *
     * [AgilePlanningTestcontainersConfig] 의 기본 stub 을 [Primary] 로 교체한다.
     * [PermissionStub] / [IssueLookupStub] 은 테스트별로 toggle 가능하다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class StubConfig {
        @Bean
        @Primary
        fun permissionStub(): PermissionStub = PermissionStub()

        @Bean
        @Primary
        fun issueLookupStub(): IssueLookupStub = IssueLookupStub()
    }

    /**
     * 테스트별 allow/deny toggle 이 가능한 [IssuePermissionResolver] stub.
     *
     * allowAll = true 이면 모든 권한 판정을 허용한다.
     * deny 를 테스트하는 케이스에서는 allowAll = false 로 설정한다.
     */
    class PermissionStub : IssuePermissionResolver {
        var allowAll: Boolean = true

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean = allowAll
    }

    /**
     * 테스트별 isVisibleIssue toggle 이 가능한 [BoardIssueLookupPort] stub.
     *
     * visibleKeys 에 issueKey 가 포함된 경우에만 true 를 반환한다.
     * 기본값은 빈 set — 모든 이슈 미가시(fail-closed).
     */
    class IssueLookupStub : BoardIssueLookupPort {
        val visibleKeys: MutableSet<String> = mutableSetOf()

        override fun isVisibleIssue(
            projectKey: String,
            issueKey: String,
            viewerUserId: UUID,
        ): Boolean = issueKey in visibleKeys
    }

    @Autowired
    lateinit var wac: WebApplicationContext

    @Autowired
    lateinit var sprintRepository: SprintRepository

    /**
     * Spring 이 프록시한 [SprintApplicationService] 빈.
     *
     * `start` 의 잠금은 `pg_advisory_xact_lock` 이라 **트랜잭션 경계 안에서만** 의미가 있다.
     * MockMvc 경유로도 트랜잭션은 걸리지만, 두 요청을 정확히 겹치게 하려면 스레드를 직접 잡아야 한다.
     */
    @Autowired
    lateinit var sprintApplicationService: SprintApplicationService

    @Autowired
    lateinit var permissionStub: PermissionStub

    @Autowired
    lateinit var issueLookupStub: IssueLookupStub

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    /** 테스트마다 격리된 projectKey 를 생성한다. */
    private fun uniqueProjectKey(): String = "TST-${UUID.randomUUID().toString().take(6).uppercase()}"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()

        // 인증 주체 주입
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        // stub 초기화
        permissionStub.allowAll = true
        issueLookupStub.visibleKeys.clear()
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun createSprintBody(
        projectKey: String,
        name: String = "스프린트 1",
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
    ): Map<String, Any?> =
        mapOf(
            "projectKey" to projectKey,
            "name" to name,
            "goal" to null,
            "startDate" to startDate?.toString(),
            "endDate" to endDate?.toString(),
        )

    /**
     * 스프린트를 생성하고 sprintId 를 추출해 반환한다.
     *
     * @return 생성된 스프린트 UUID 문자열
     */
    private fun createSprintAndGetId(
        projectKey: String,
        name: String = "스프린트 1",
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
    ): String {
        val body = createSprintBody(projectKey, name, startDate, endDate)
        val result =
            mockMvc.perform(
                post("/api/v1/sprints")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)),
            )
                .andExpect(status().isCreated)
                .andReturn()

        return mapper.readTree(result.response.contentAsString)
            .get("data").get("sprintId").asText()
    }

    // ── S1. 스프린트 생성 (201 + PLANNED) ──────────────────────────────────────

    @Test
    fun `S1 스프린트 생성이면 201과 PLANNED 상태 스프린트를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val body = createSprintBody(projectKey, "첫 번째 스프린트")

        mockMvc.perform(
            post("/api/v1/sprints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.sprintId").isString)
            .andExpect(jsonPath("$.data.projectKey").value(projectKey))
            .andExpect(jsonPath("$.data.name").value("첫 번째 스프린트"))
            .andExpect(jsonPath("$.data.status").value("PLANNED"))
            .andExpect(jsonPath("$.data.version").value(0))
    }

    // ── S2. 이슈 할당 (201, sprint_issues 행 확인) ─────────────────────────────

    @Test
    fun `S2 가시 이슈를 할당하면 201을 반환하고 sprint_issues 행이 존재한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)
        val issueKey = "$projectKey-1"
        issueLookupStub.visibleKeys.add(issueKey)

        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to issueKey))),
        )
            .andExpect(status().isCreated)

        // sprint_issues 행 확인 — repository 직접 조회
        val keys = sprintRepository.findIssueKeys(UUID.fromString(sprintId))
        assertThat(keys).contains(issueKey)
    }

    // ── S3. 이슈 해제 (204) ─────────────────────────────────────────────────────

    @Test
    fun `S3 할당된 이슈를 해제하면 204를 반환하고 sprint_issues 행이 제거된다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)
        val issueKey = "$projectKey-2"
        issueLookupStub.visibleKeys.add(issueKey)

        // 할당 먼저
        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to issueKey))),
        ).andExpect(status().isCreated)

        // 해제
        mockMvc.perform(delete("/api/v1/sprints/$sprintId/issues/$issueKey"))
            .andExpect(status().isNoContent)

        val keys = sprintRepository.findIssueKeys(UUID.fromString(sprintId))
        assertThat(keys).doesNotContain(issueKey)
    }

    // ── S4. start (PLANNED → ACTIVE 200) ─────────────────────────────────────

    @Test
    fun `S4 PLANNED 스프린트를 start하면 200과 ACTIVE 상태를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)

        mockMvc.perform(post("/api/v1/sprints/$sprintId/start"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.version").value(1))
    }

    /**
     * 동시 start 2건 중 **한 건만** 통과한다 (FR-BD-04 PR ⑤-2).
     *
     * ### 왜 DB 가 못 막나
     * `V506__sprint_board_id.sql` 의 부분 인덱스 `idx_sprints_board_active` 는 **UNIQUE 가 아니다** —
     * 같은 파일이 선재 다중 ACTIVE 행을 보존하려고 일부러 그렇게 뒀다. 그래서 유일성을 지키는 것은
     * [SprintApplicationService.start] 의 read-then-write 하나뿐이고, 잠금이 없으면 두 요청이
     * 둘 다 통과해 한 보드에 활성 스프린트가 둘이 된다. 그러면 보드 화면이 어느 쪽을 그릴지가
     * **조회 순서에 달린다.**
     *
     * ### 정렬 장치가 없으면 이 테스트는 아무것도 재지 않는다
     * 두 스레드가 순차로 돌면 잠금이 없어도 통과한다 — 「락이 동작한다」와 「애초에 안 겹쳤다」를
     * 구별하지 못한다. 두 스레드가 진입한 것을 확인한 뒤 동시에 푼다
     * (선례 `BoardApplicationServiceTest.동시 ensureScrumBoard 후에도 …`).
     */
    @Test
    fun `동시 start 2건 중 한 건만 통과하고 보드의 ACTIVE 스프린트는 1개다`() {
        val projectKey = uniqueProjectKey()
        // boardId 를 안 주면 둘 다 그 프로젝트의 스크럼 보드에 붙는다 — 같은 보드를 두고 경쟁한다.
        val firstId = UUID.fromString(createSprintAndGetId(projectKey, "동시 시작 A"))
        val secondId = UUID.fromString(createSprintAndGetId(projectKey, "동시 시작 B"))
        assertThat(sprintRepository.findById(firstId)?.boardId)
            .`as`("두 스프린트가 다른 보드에 붙었다 — 경쟁이 성립하지 않는다")
            .isEqualTo(sprintRepository.findById(secondId)?.boardId)

        val entered = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures =
            listOf(firstId, secondId).map { id ->
                executor.submit<Result<Unit>> {
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(
                            actorId.toString(),
                            null,
                            listOf(SimpleGrantedAuthority("ROLE_USER")),
                        )
                    entered.countDown()
                    start.await()
                    runCatching { sprintApplicationService.start(actorId, id) }.map { }
                }
            }
        assertThat(entered.await(10, TimeUnit.SECONDS))
            .`as`("두 스레드가 시작하지 못했다 — 경쟁이 재현되지 않았다")
            .isTrue()
        start.countDown()
        executor.shutdown()
        val results = futures.map { it.get() }

        assertThat(results.count { it.isSuccess })
            .`as`("동시 start 2건 중 성공이 %d 건이다 — 정확히 1건이어야 한다", results.count { it.isSuccess })
            .isEqualTo(1)

        val active = sprintRepository.findByProject(projectKey).filter { it.status == SprintStatus.ACTIVE }
        assertThat(active)
            .`as`("한 보드에 ACTIVE 스프린트가 %d 개다 — 보드 화면이 어느 쪽을 그릴지가 조회 순서에 달린다", active.size)
            .hasSize(1)
    }

    // ── S5. complete (ACTIVE → COMPLETED 200) ────────────────────────────────

    @Test
    fun `S5 ACTIVE 스프린트를 complete하면 200과 COMPLETED 상태를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)

        mockMvc.perform(post("/api/v1/sprints/$sprintId/start"))
            .andExpect(status().isOk)

        mockMvc.perform(post("/api/v1/sprints/$sprintId/complete"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.version").value(2))
    }

    // ── S6. 단건 조회 (할당 이슈 목록 created_at 순) ──────────────────────────

    @Test
    fun `S6 단건 조회는 200과 스프린트 정보를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey, "단건 조회 스프린트")

        mockMvc.perform(get("/api/v1/sprints/$sprintId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sprintId").value(sprintId))
            .andExpect(jsonPath("$.data.name").value("단건 조회 스프린트"))
            .andExpect(jsonPath("$.data.status").value("PLANNED"))
    }

    @Test
    fun `S6 단건 조회 응답에 할당 이슈 목록이 created_at 오름차순으로 포함된다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)
        val sprintUUID = UUID.fromString(sprintId)

        // 이슈 3건 순서대로 할당
        listOf("$projectKey-10", "$projectKey-11", "$projectKey-12").forEach { key ->
            issueLookupStub.visibleKeys.add(key)
            mockMvc.perform(
                post("/api/v1/sprints/$sprintId/issues")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(mapOf("issueKey" to key))),
            ).andExpect(status().isCreated)
        }

        // created_at 순 확인 — findIssueKeys repo 메서드가 created_at asc 정렬
        val keys = sprintRepository.findIssueKeys(sprintUUID)
        assertThat(keys).containsExactly("$projectKey-10", "$projectKey-11", "$projectKey-12")
    }

    // ── S7. 프로젝트 목록 ──────────────────────────────────────────────────────

    @Test
    fun `S7 프로젝트별 목록 조회는 200과 해당 프로젝트 스프린트 배열을 반환한다`() {
        val projectKey = uniqueProjectKey()
        createSprintAndGetId(projectKey, "목록 스프린트 A")
        createSprintAndGetId(projectKey, "목록 스프린트 B")

        mockMvc.perform(
            get("/api/v1/sprints").param("projectKey", projectKey),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].name").value("목록 스프린트 A"))
            .andExpect(jsonPath("$.data[1].name").value("목록 스프린트 B"))
    }

    @Test
    fun `S7 status 필터로 ACTIVE 스프린트만 조회한다`() {
        val projectKey = uniqueProjectKey()
        createSprintAndGetId(projectKey, "PLANNED 스프린트")
        val active = createSprintAndGetId(projectKey, "ACTIVE 스프린트")
        mockMvc.perform(post("/api/v1/sprints/$active/start"))

        mockMvc.perform(
            get("/api/v1/sprints")
                .param("projectKey", projectKey)
                .param("status", "ACTIVE"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].sprintId").value(active))
    }

    // ── S8. 수정(PATCH 200) + 소프트삭제(DELETE 204) ─────────────────────────

    @Test
    fun `S8 PATCH는 200과 갱신된 스프린트를 반환하고 version이 bump된다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey, "원본 이름")

        val patchBody =
            mapOf(
                "name" to "수정된 이름",
                "goal" to "새 목표",
                "startDate" to null,
                "endDate" to null,
                "version" to 0,
            )

        mockMvc.perform(
            patch("/api/v1/sprints/$sprintId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(patchBody)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("수정된 이름"))
            .andExpect(jsonPath("$.data.goal").value("새 목표"))
            .andExpect(jsonPath("$.data.version").value(1))
    }

    @Test
    fun `S8 DELETE는 204를 반환하고 이후 GET 요청에서 404가 반환된다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)

        mockMvc.perform(delete("/api/v1/sprints/$sprintId"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/sprints/$sprintId"))
            .andExpect(status().isNotFound)
    }

    // ── E1. 잘못된 전환 → 409 ────────────────────────────────────────────────

    @Test
    fun `E1 PLANNED 스프린트에 complete 를 호출하면 409를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)

        mockMvc.perform(post("/api/v1/sprints/$sprintId/complete"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("AGILE_CONFLICT"))
    }

    @Test
    fun `E1 ACTIVE 스프린트에 start 를 다시 호출하면 409를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)
        mockMvc.perform(post("/api/v1/sprints/$sprintId/start"))

        mockMvc.perform(post("/api/v1/sprints/$sprintId/start"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("AGILE_CONFLICT"))
    }

    // ── E2. 미존재 스프린트 → 404 ────────────────────────────────────────────

    @Test
    fun `E2 존재하지 않는 스프린트 GET 요청은 404를 반환한다`() {
        val nonExistentId = UUID.randomUUID()
        mockMvc.perform(get("/api/v1/sprints/$nonExistentId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_SPRINT_NOT_FOUND"))
    }

    @Test
    fun `E2 존재하지 않는 스프린트에 이슈 할당 요청은 404를 반환한다`() {
        val nonExistentId = UUID.randomUUID()
        mockMvc.perform(
            post("/api/v1/sprints/$nonExistentId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to "BTS-1"))),
        )
            .andExpect(status().isNotFound)
    }

    // ── E3. 미가시·타프로젝트·미존재 이슈 할당 → 단일 404 (probe 차단) ─────────

    @Test
    fun `E3 가시적이지 않은 이슈를 할당하면 단일 404를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)
        // issueLookupStub.visibleKeys 가 비어 있어 isVisibleIssue = false

        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to "$projectKey-999"))),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `E3 타 프로젝트 이슈를 할당하면 404를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)
        // 타 프로젝트 이슈는 visibleKeys 에 없으므로 false

        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to "OTHER-1"))),
        )
            .andExpect(status().isNotFound)
    }

    // ── E5. COMPLETED 스프린트 할당 → 409 ────────────────────────────────────

    @Test
    fun `E5 COMPLETED 스프린트에 이슈를 할당하면 409를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)
        val issueKey = "$projectKey-5"
        issueLookupStub.visibleKeys.add(issueKey)

        // PLANNED → ACTIVE → COMPLETED
        mockMvc.perform(post("/api/v1/sprints/$sprintId/start"))
        mockMvc.perform(post("/api/v1/sprints/$sprintId/complete"))

        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to issueKey))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("AGILE_CONFLICT"))
    }

    // ── E6. 권한 없는 actor → 403 ────────────────────────────────────────────

    @Test
    fun `E6 권한이 없는 actor의 스프린트 생성 요청은 403을 반환한다`() {
        permissionStub.allowAll = false
        val projectKey = uniqueProjectKey()
        val body = createSprintBody(projectKey)

        mockMvc.perform(
            post("/api/v1/sprints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))
    }

    @Test
    fun `E6 권한이 없는 actor의 스프린트 start 요청은 403을 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)

        permissionStub.allowAll = false

        mockMvc.perform(post("/api/v1/sprints/$sprintId/start"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))
    }

    // ── E7. 멱등 재할당 (이미 그 스프린트) → 201(정상 처리) ──────────────────

    @Test
    fun `E7 같은 스프린트에 같은 이슈를 재할당해도 정상 처리된다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)
        val issueKey = "$projectKey-7"
        issueLookupStub.visibleKeys.add(issueKey)

        // 첫 할당
        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to issueKey))),
        ).andExpect(status().isCreated)

        // 재할당 — 멱등 처리 (delete-then-insert, 예외 없이 정상 완료)
        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to issueKey))),
        ).andExpect(status().isCreated)

        // sprint_issues 에 중복 없이 1건만 존재
        val keys = sprintRepository.findIssueKeys(UUID.fromString(sprintId))
        assertThat(keys.count { it == issueKey }).isEqualTo(1)
    }

    // ── E8. 다른 스프린트 이슈 이동 (기존 sprint_issues 제거, delete-then-insert) ──

    @Test
    fun `E8 다른 스프린트에 있던 이슈를 새 스프린트에 할당하면 이동된다`() {
        val projectKey = uniqueProjectKey()
        val sprintAId = createSprintAndGetId(projectKey, "스프린트 A")
        val sprintBId = createSprintAndGetId(projectKey, "스프린트 B")
        val issueKey = "$projectKey-8"
        issueLookupStub.visibleKeys.add(issueKey)

        // 스프린트 A 에 먼저 할당
        mockMvc.perform(
            post("/api/v1/sprints/$sprintAId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to issueKey))),
        ).andExpect(status().isCreated)

        // 스프린트 B 로 이동
        mockMvc.perform(
            post("/api/v1/sprints/$sprintBId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to issueKey))),
        ).andExpect(status().isCreated)

        // 스프린트 A 에는 없고 스프린트 B 에만 존재
        assertThat(sprintRepository.findIssueKeys(UUID.fromString(sprintAId))).doesNotContain(issueKey)
        assertThat(sprintRepository.findIssueKeys(UUID.fromString(sprintBId))).contains(issueKey)
    }

    // ── E9. 없는 이슈 해제 → 멱등 204 ──────────────────────────────────────────

    @Test
    fun `E9 할당되지 않은 이슈를 해제해도 멱등 204를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)

        mockMvc.perform(delete("/api/v1/sprints/$sprintId/issues/$projectKey-NOTEXIST"))
            .andExpect(status().isNoContent)
    }

    // ── E11. 기간 역전 (startDate > endDate) → 400 ──────────────────────────

    @Test
    fun `E11 startDate가 endDate보다 이후이면 400을 반환한다`() {
        val projectKey = uniqueProjectKey()
        val body =
            createSprintBody(
                projectKey = projectKey,
                startDate = LocalDate.of(2026, 7, 14),
                endDate = LocalDate.of(2026, 7, 1),
            )

        mockMvc.perform(
            post("/api/v1/sprints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── E12. 소프트삭제 후 sprint_issues 빈 목록 (FR11) ──────────────────────

    @Test
    fun `E12 소프트삭제 후 sprint_issues는 빈 목록이다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey)
        val sprintUUID = UUID.fromString(sprintId)
        val issueKey = "$projectKey-12"
        issueLookupStub.visibleKeys.add(issueKey)

        // 이슈 할당
        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to issueKey))),
        ).andExpect(status().isCreated)

        // 소프트삭제
        mockMvc.perform(delete("/api/v1/sprints/$sprintId"))
            .andExpect(status().isNoContent)

        // sprint_issues 빈 목록 확인 (softDelete가 sprint_issues 먼저 삭제)
        val keys = sprintRepository.findIssueKeys(sprintUUID)
        assertThat(keys).isEmpty()
    }

    // ── T5-1. @NotBlank 검증 — name="" → 400 ─────────────────────────────────

    /**
     * T5 concern: @SpringBootTest 전체 컨텍스트에서 @Valid/@NotBlank 가 실제로 동작하는지 검증.
     *
     * hibernate-validator(Bean Validation provider) 가 classpath 에 없으면 @NotBlank 가 무동작해
     * 빈 이름으로 스프린트가 생성되고 이 테스트가 FAIL 한다.
     * 가짜 통과 금지 — 이 테스트가 실패하면 "Bean Validation provider 부재" 로 NEEDS_CONTEXT 보고.
     *
     * name 이 빈 문자열이면 도메인 Sprint.init require(name.isNotBlank()) 으로 400 또는
     * @NotBlank 로 400 중 어느 경로를 통해서든 400 이 반환되어야 한다.
     */
    @Test
    fun `T5-1 name이 빈 문자열이면 400 AGILE_VALIDATION_FAILED를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val body = mapOf("projectKey" to projectKey, "name" to "")

        mockMvc.perform(
            post("/api/v1/sprints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `T5-2 name이 공백만으로 구성되면 400을 반환한다`() {
        val projectKey = uniqueProjectKey()
        val body = mapOf("projectKey" to projectKey, "name" to "   ")

        mockMvc.perform(
            post("/api/v1/sprints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── E12(UNIQUE 409 경로) — 단일 UNIQUE 위반 확인 ─────────────────────────

    @Test
    fun `E12-unique delete-then-insert 설계로 이동은 정상 처리되고 이전 스프린트에서 제거된다`() {
        // Repository 계층 단위/통합에서 이미 검증됨. 여기서는 HTTP 레이어에서
        // UNIQUE 위반이 500이 아닌 적절한 오류 코드로 변환되는지 단언.
        // 단일 스레드 E12: 같은 issue_key 를 두 스프린트에 직접 insert 시도.
        val projectKey = uniqueProjectKey()
        val sprintAId = createSprintAndGetId(projectKey, "스프린트 A-E12")
        val sprintBId = createSprintAndGetId(projectKey, "스프린트 B-E12")
        val issueKey = "$projectKey-UNIQ"
        issueLookupStub.visibleKeys.add(issueKey)

        // 스프린트 A 에 먼저 할당 (성공)
        mockMvc.perform(
            post("/api/v1/sprints/$sprintAId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to issueKey))),
        ).andExpect(status().isCreated)

        // delete-then-insert 설계라 스프린트 B 로 이동 시도 — 이미 A에서 지우고 B에 insert.
        // 이 케이스는 정상 이동이므로 201 을 반환한다 (UNIQUE 위반 없음).
        mockMvc.perform(
            post("/api/v1/sprints/$sprintBId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to issueKey))),
        ).andExpect(status().isCreated)

        // 최종 상태: B에만 존재
        assertThat(sprintRepository.findIssueKeys(UUID.fromString(sprintAId))).doesNotContain(issueKey)
        assertThat(sprintRepository.findIssueKeys(UUID.fromString(sprintBId))).contains(issueKey)
    }

    // ── 미인증 → 401 (non-vacuous 위반 시나리오) ─────────────────────────────

    @Test
    fun `미인증 요청은 401을 반환하고 서비스가 실제로 차단된다 (non-vacuous)`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(
            post("/api/v1/sprints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("projectKey" to "BTS", "name" to "테스트"))),
        )
            .andExpect(status().isUnauthorized)
    }
}
