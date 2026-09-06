// 스프린트 번다운/번업 REST API 실 DB end-to-end 통합 테스트 — FR-RP-01 Task 6

package com.bts.agileplanning.integration

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.burndown.BurndownSource
import com.bts.shared.burndown.IssueCompletion
import com.bts.shared.burndown.SprintBurndownLookupPort
import com.bts.shared.burndown.WorklogContribution
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 스프린트 번다운(Burndown)/번업(Burnup) REST API 실 DB end-to-end 통합 테스트 (FR-RP-01 Task 6).
 *
 * [AgilePlanningTestBootApplication] 을 기동해 실 Testcontainers PostgreSQL 위에서
 * `SprintBurndownController` → `SprintBurndownService` → `SprintRepository`/`BurndownCalculator` 전 스택을
 * 검증한다. [SprintIntegrationTest] 와 동일한 base/config([AgilePlanningTestBootApplication],
 * [AgilePlanningTestcontainersConfig])를 재사용하고, actor 주입·cross-BC stub 패턴도 그대로 따른다.
 *
 * ## webEnvironment 선택 — MOCK (명세 상 RANDOM_PORT 표기에서 조정)
 * agile-planning 모듈은 `spring-webmvc`/`spring-web`만 의존하고 임베디드 서블릿 컨테이너
 * (Tomcat/Jetty/Undertow) 의존성이 없다(build.gradle.kts 확인). `webEnvironment = RANDOM_PORT` 는
 * `ServletWebServerFactory` 빈 부재로 컨텍스트 기동 자체가 실패한다 — 이 모듈의 기존 통합 테스트
 * ([SprintIntegrationTest] 포함 전체)도 모두 MOCK 만 사용한다. 이 파일의 수정 범위가 테스트 파일
 * 1개로 한정되어 build.gradle.kts 에 서블릿 컨테이너 의존성을 추가할 수 없으므로, 실제 기동 가능한
 * MOCK 환경을 사용한다 — [MockMvcBuilders.webAppContextSetup] 은 webEnvironment 모드와 무관하게
 * 동일한 [WebApplicationContext] 기반으로 동작해 요구된 검증(actor 주입/cross-BC stub/200·401·403·404·422)을
 * 동일하게 수행한다.
 *
 * ## cross-BC stub
 * - [IssuePermissionResolver]: [PermissionStub] — 테스트별 allow/deny toggle(403 검증).
 * - [BoardIssueLookupPort]: [IssueLookupStub] — 이슈 할당 시드에 필요한 가시성 stub.
 * - [SprintBurndownLookupPort]: [BurndownPortStub] — 통제된 [BurndownSource] 반환(BC 격리 — 실
 *   issue-tracking 어댑터는 이 모듈 테스트 클래스패스에 없다. jOOQ 어댑터 자체는 Task 3
 *   `SprintBurndownLookupAdapterIntegrationTest` 에서 별도 검증됨).
 *
 * ## 결정성 확보
 * 시드 스프린트의 start_date/end_date 를 과거(2020-01-01~2020-01-03)로 고정한다.
 * `SprintBurndownService` 의 clock 은 default `Clock.systemUTC()`(실시간)이므로, today(현재 실행 시각) 는
 * 항상 이 과거 구간보다 뒤에 있어 asOf = end 로 고정되고 전 구간 Actual/Completed 가 산출되어
 * 실행 시점에 무관하게 결정적이다 (memory: authcontroller-revokesession-timebomb 교훈).
 *
 * ## 검증 범위 (스펙 S1/S4)
 * - 200 해피패스: 3일 스프린트(scope=57,600초=16h)에 이슈 3개 할당, 2일차(2020-01-02)에 worklog 6h 기록.
 *   1일차 remaining=16h(변화 없음), 2일차 remaining=10h(감소), ideal 은 1일차=scope→종료일=0 선형,
 *   scope 라인은 전 구간 평탄.
 * - S1b 개수 축 대조군: 같은 원천 데이터라도 「추정」 탭이 `NONE`(V509 기본값)이면 세로축이
 *   **이슈 개수**다(부채 177 task-35). S1 은 PATCH 로 `REMAINING_AND_SPENT` 를 저장한 뒤 시간 축을
 *   재고, 이 쌍이 「저장된 설정이 실제 차트에 닿는가」를 실 DB·실 HTTP 로 판정한다.
 * - 401 미인증 / 403 BROWSE 권한 없음 / 404 미존재 스프린트 / 422 start·end 미설정.
 */
@SpringBootTest(
    classes = [AgilePlanningTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@Import(AgilePlanningTestcontainersConfig::class, SprintBurndownIntegrationTest.StubConfig::class)
@ActiveProfiles("test")
class SprintBurndownIntegrationTest {
    /**
     * 통합테스트 전용 cross-BC stub 설정.
     *
     * [AgilePlanningTestcontainersConfig] 의 기본 stub 을 [Primary] 로 교체한다([SprintIntegrationTest]
     * 의 `StubConfig` 와 동일 패턴).
     */
    @TestConfiguration(proxyBeanMethods = false)
    class StubConfig {
        @Bean
        @Primary
        fun permissionStub(): PermissionStub = PermissionStub()

        @Bean
        @Primary
        fun issueLookupStub(): IssueLookupStub = IssueLookupStub()

        @Bean
        @Primary
        fun burndownPortStub(): BurndownPortStub = BurndownPortStub()
    }

    /** 테스트별 allow/deny toggle 이 가능한 [IssuePermissionResolver] stub. */
    class PermissionStub : IssuePermissionResolver {
        var allowAll: Boolean = true

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean = allowAll
    }

    /** 테스트별 isVisibleIssue toggle 이 가능한 [BoardIssueLookupPort] stub — 이슈 할당 시드용. */
    class IssueLookupStub : BoardIssueLookupPort {
        val visibleKeys: MutableSet<String> = mutableSetOf()

        override fun isVisibleIssue(
            projectKey: String,
            issueKey: String,
            viewerUserId: UUID,
        ): Boolean = issueKey in visibleKeys
    }

    /**
     * 테스트별로 통제된 [BurndownSource] 를 반환하는 [SprintBurndownLookupPort] stub.
     *
     * 실 issue-tracking jOOQ 어댑터([com.bts.issue.adapter.outbound.burndown.SprintBurndownLookupAdapter])는
     * 이 모듈의 테스트 클래스패스에 없다(BC 격리) — 어댑터 자체 검증은 Task 3 의 별도 통합테스트가 담당한다.
     */
    class BurndownPortStub : SprintBurndownLookupPort {
        var source: BurndownSource = emptySource()

        override fun fetchBurndownSource(
            issueKeys: Set<String>,
            projectKey: String,
            viewerUserId: UUID,
        ): BurndownSource = source

        companion object {
            /** 두 축 모두 빈 값 — 포트의 fail-safe 기본과 같은 모양이다. */
            fun emptySource(): BurndownSource =
                BurndownSource(
                    totalOriginalEstimateSeconds = 0,
                    worklogEntries = emptyList(),
                    visibleIssueCount = 0,
                    issueCompletions = emptyList(),
                )
        }
    }

    @Autowired
    lateinit var wac: WebApplicationContext

    @Autowired
    lateinit var permissionStub: PermissionStub

    @Autowired
    lateinit var issueLookupStub: IssueLookupStub

    @Autowired
    lateinit var burndownPortStub: BurndownPortStub

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    /** 테스트마다 격리된 projectKey 를 생성한다. */
    private fun uniqueProjectKey(): String = "BRN-${UUID.randomUUID().toString().take(6).uppercase()}"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()

        // 인증 주체 주입 — SprintIntegrationTest 와 동일 패턴
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        // stub 초기화
        permissionStub.allowAll = true
        issueLookupStub.visibleKeys.clear()
        burndownPortStub.source = BurndownPortStub.emptySource()
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /**
     * 스프린트를 생성하고 sprintId 를 추출해 반환한다.
     *
     * @return 생성된 스프린트 UUID 문자열.
     */
    private fun createSprintAndGetId(
        projectKey: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): String {
        val body =
            mapOf(
                "projectKey" to projectKey,
                "name" to "번다운 테스트 스프린트",
                "goal" to null,
                "startDate" to startDate?.toString(),
                "endDate" to endDate?.toString(),
            )
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

    /**
     * 스프린트가 속한 보드 UUID 를 조회 응답에서 읽는다.
     *
     * 스프린트 생성 응답이 `boardId` 를 싣는다(FR-BD-04) — DB 를 직접 뒤지지 않고 HTTP 로만 오간다.
     */
    private fun boardIdOf(sprintId: String): String {
        val result =
            mockMvc.perform(get("/api/v1/sprints/$sprintId"))
                .andExpect(status().isOk)
                .andReturn()
        return mapper.readTree(result.response.contentAsString).get("data").get("boardId").asText()
    }

    /** 「추정」 탭의 시간 추적을 실제 PATCH 엔드포인트로 저장한다 — 화면이 하는 것과 같은 경로다. */
    private fun saveTimeTracking(
        boardId: String,
        timeTracking: String,
    ) {
        mockMvc.perform(
            patch("/api/v1/boards/$boardId/estimation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("timeTracking" to timeTracking))),
        ).andExpect(status().isOk)
    }

    /** [issueKey] 를 가시 처리한 뒤 [sprintId] 에 할당한다. */
    private fun assignIssue(
        sprintId: String,
        issueKey: String,
    ) {
        issueLookupStub.visibleKeys.add(issueKey)
        mockMvc.perform(
            post("/api/v1/sprints/$sprintId/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("issueKey" to issueKey))),
        ).andExpect(status().isCreated)
    }

    // ── S1. 200 해피패스 — 수치 정확성 ─────────────────────────────────────────

    @Test
    fun `S1 시드한 스프린트의 번다운을 조회하면 200과 정확한 시계열을 반환한다`() {
        val projectKey = uniqueProjectKey()
        val start = LocalDate.of(2020, 1, 1)
        val end = LocalDate.of(2020, 1, 3)
        val sprintId = createSprintAndGetId(projectKey, start, end)

        // 이슈 3개 할당 (추정 8h/4h/4h = 16h 는 burndownPortStub 의 통제된 스코프로 대체)
        listOf("$projectKey-1", "$projectKey-2", "$projectKey-3").forEach { assignIssue(sprintId, it) }

        // ★시간 축은 「추정」 탭에 REMAINING_AND_SPENT 를 저장해야 나온다(task-35).
        //   `boards.time_tracking` 의 DB 기본값 'NONE' 은 **개수 축**이다 — 아래 S1b 가 그 짝이다.
        saveTimeTracking(boardIdOf(sprintId), "REMAINING_AND_SPENT")

        // scope=57,600초(16h). 2020-01-02 에 21,600초(6h) worklog 기록.
        burndownPortStub.source =
            BurndownSource(
                totalOriginalEstimateSeconds = 57_600,
                worklogEntries =
                    listOf(
                        WorklogContribution(
                            startedOnUtcDate = LocalDate.of(2020, 1, 2),
                            timeSpentSeconds = 21_600,
                            startedAt = Instant.parse("2020-01-02T09:00:00Z"),
                        ),
                    ),
                visibleIssueCount = 3,
                issueCompletions =
                    listOf(
                        IssueCompletion(
                            issueKey = "$projectKey-1",
                            completedAt = Instant.parse("2020-01-02T09:00:00Z"),
                        ),
                    ),
            )

        mockMvc.perform(get("/api/v1/sprints/$sprintId/burndown"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.unit").value("SECONDS"))
            .andExpect(jsonPath("$.data.sprintId").value(sprintId))
            .andExpect(jsonPath("$.data.projectKey").value(projectKey))
            .andExpect(jsonPath("$.data.startDate").value("2020-01-01"))
            .andExpect(jsonPath("$.data.endDate").value("2020-01-03"))
            .andExpect(jsonPath("$.data.totalScopeSeconds").value(57_600))
            .andExpect(jsonPath("$.data.points.length()").value(3))
            // 1일차(start) — worklog 이전, remaining=scope 그대로
            .andExpect(jsonPath("$.data.points[0].date").value("2020-01-01"))
            .andExpect(jsonPath("$.data.points[0].remainingSeconds").value(57_600))
            .andExpect(jsonPath("$.data.points[0].idealSeconds").value(57_600))
            .andExpect(jsonPath("$.data.points[0].completedSeconds").value(0))
            .andExpect(jsonPath("$.data.points[0].scopeSeconds").value(57_600))
            // 2일차 — 6h(21,600초) worklog 반영, remaining 16h→10h 로 감소
            .andExpect(jsonPath("$.data.points[1].date").value("2020-01-02"))
            .andExpect(jsonPath("$.data.points[1].remainingSeconds").value(36_000))
            .andExpect(jsonPath("$.data.points[1].idealSeconds").value(28_800))
            .andExpect(jsonPath("$.data.points[1].completedSeconds").value(21_600))
            .andExpect(jsonPath("$.data.points[1].scopeSeconds").value(57_600))
            // 3일차(end) — 추가 worklog 없음, remaining/completed 유지, ideal 은 0으로 종결
            .andExpect(jsonPath("$.data.points[2].date").value("2020-01-03"))
            .andExpect(jsonPath("$.data.points[2].remainingSeconds").value(36_000))
            .andExpect(jsonPath("$.data.points[2].idealSeconds").value(0))
            .andExpect(jsonPath("$.data.points[2].completedSeconds").value(21_600))
            .andExpect(jsonPath("$.data.points[2].scopeSeconds").value(57_600))
    }

    // ── S1b. 개수 축 — 추정 탭을 그대로 둔 보드(기본값 NONE) ──────────────────

    /**
     * **S1 의 대조군** — 같은 원천 데이터인데 「추정」 탭을 만지지 않은 보드는 **개수 축**으로 그려진다
     * (부채 177 task-35 · 스펙 S2).
     *
     * ★이 쌍이 실 DB·실 HTTP 로 재는 것은 「저장된 설정이 차트에 닿는가」다. S1 은 PATCH 로 저장한
     * `REMAINING_AND_SPENT` 를, 여기는 V509 의 기본값 `NONE` 을 탄다 — 두 요청의 차이는 그 한 줄뿐이다.
     */
    @Test
    fun `S1b 추정 탭이 NONE 이면 같은 데이터를 개수 축으로 그린다`() {
        val projectKey = uniqueProjectKey()
        val start = LocalDate.of(2020, 1, 1)
        val end = LocalDate.of(2020, 1, 3)
        val sprintId = createSprintAndGetId(projectKey, start, end)
        listOf("$projectKey-1", "$projectKey-2", "$projectKey-3").forEach { assignIssue(sprintId, it) }

        // 설정을 저장하지 않는다 — DB 기본값 'NONE' 그대로다.
        burndownPortStub.source =
            BurndownSource(
                totalOriginalEstimateSeconds = 57_600,
                worklogEntries =
                    listOf(
                        WorklogContribution(
                            startedOnUtcDate = LocalDate.of(2020, 1, 2),
                            timeSpentSeconds = 21_600,
                            startedAt = Instant.parse("2020-01-02T09:00:00Z"),
                        ),
                    ),
                visibleIssueCount = 3,
                issueCompletions =
                    listOf(
                        IssueCompletion(
                            issueKey = "$projectKey-1",
                            completedAt = Instant.parse("2020-01-02T09:00:00Z"),
                        ),
                    ),
            )

        mockMvc.perform(get("/api/v1/sprints/$sprintId/burndown"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.unit").value("ISSUE_COUNT"))
            .andExpect(jsonPath("$.data.totalScopeSeconds").value(3))
            .andExpect(jsonPath("$.data.points.length()").value(3))
            // 1일차 — 완료 이전이라 3개 그대로.
            .andExpect(jsonPath("$.data.points[0].remainingSeconds").value(3))
            .andExpect(jsonPath("$.data.points[0].scopeSeconds").value(3))
            // 2일차 — 이슈 하나 완료로 2개.
            .andExpect(jsonPath("$.data.points[1].remainingSeconds").value(2))
            .andExpect(jsonPath("$.data.points[1].completedSeconds").value(1))
            // 3일차 — 추가 완료 없음. ideal 은 0 으로 종결.
            .andExpect(jsonPath("$.data.points[2].remainingSeconds").value(2))
            .andExpect(jsonPath("$.data.points[2].idealSeconds").value(0))
    }

    // ── 401. 미인증 ──────────────────────────────────────────────────────────

    @Test
    fun `번다운 조회는 미인증이면 401을 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 3))

        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/sprints/$sprintId/burndown"))
            .andExpect(status().isUnauthorized)
    }

    // ── 403. BROWSE 권한 없음 ───────────────────────────────────────────────

    @Test
    fun `번다운 조회는 BROWSE 권한이 없으면 403을 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 3))

        permissionStub.allowAll = false

        mockMvc.perform(get("/api/v1/sprints/$sprintId/burndown"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))
    }

    // ── 404. 미존재 스프린트 ────────────────────────────────────────────────

    @Test
    fun `번다운 조회는 존재하지 않는 스프린트면 404를 반환한다`() {
        val nonExistentId = UUID.randomUUID()

        mockMvc.perform(get("/api/v1/sprints/$nonExistentId/burndown"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("AGILE_SPRINT_NOT_FOUND"))
    }

    // ── 422. start/end 미설정 스프린트 ─────────────────────────────────────

    @Test
    fun `번다운 조회는 start end가 미설정이면 422를 반환한다`() {
        val projectKey = uniqueProjectKey()
        val sprintId = createSprintAndGetId(projectKey, startDate = null, endDate = null)

        mockMvc.perform(get("/api/v1/sprints/$sprintId/burndown"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("AGILE_SPRINT_DATES_REQUIRED"))
    }
}
