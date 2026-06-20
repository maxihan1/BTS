// WorklogAggregateController MockMvc 슬라이스 통합 테스트 (FR-TT-02 Task 3)

package com.bts.issue.worklog.aggregate.web

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.worklog.aggregate.application.WorklogAggregateService
import com.bts.issue.worklog.aggregate.domain.AggregateGranularity
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateBucket
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateDimension
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateResult
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * WorklogAggregateController MockMvc 슬라이스 테스트 (FR-TT-02 Task 3).
 *
 * [WorklogAggregateService] 는 MockK stub 으로 대체한다.
 * [WorklogAggregateExceptionHandler] 를 컨텍스트에 등록하여 예외→HTTP 변환을 검증한다.
 *
 * 시드 패턴: WorklogAggregateRepositoryTest 의 by=issue 시나리오를 참고하여
 * 빈 배열 200 가짜그린을 방지하기 위해 최소 1개 버킷을 포함한 stub 결과를 사용한다.
 *
 * ## 테스트 케이스
 * - WA-1.  GET /aggregate?by=issue     → 200 + 응답 구조 검증 (buckets/total/by/granularity/from/to)
 * - WA-2.  GET /aggregate?by=user      → 200
 * - WA-3.  GET /aggregate?by=period    → 200 + granularity 필드 포함
 * - WA-4.  project 누락                → 400
 * - WA-5.  by 무효 값                  → 400
 * - WA-6.  granularity 무효 값         → 400
 * - WA-7.  from 형식 무효              → 400
 * - WA-8.  to 형식 무효                → 400
 * - WA-9.  from > to                   → 400
 * - WA-10. by=issue + granularity=month (C6) → 200, 응답 granularity=null (관대 처리)
 * - WA-11. 미인증                      → 401 (SecurityContext 없음)
 * - WA-12. nil-UUID actor              → 401 (존재 probe 방지 — 리소스 조회 앞)
 * - WA-13. 비-UUID actor               → 401 (존재 probe 방지)
 * - WA-14. 권한 없는 프로젝트           → 403 (누출 차단)
 * - WA-15. worklog 없음                → 200 + buckets=[], totalTimeSpentSeconds=0
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [WorklogAggregateControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class WorklogAggregateControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [WorklogAggregateController], [WorklogAggregateExceptionHandler], MockK stub Bean 을 등록한다.
     * ObjectMapper 에 JavaTimeModule 을 등록하여 Instant ISO 직렬화가 동작하도록 한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun objectMapper(): ObjectMapper =
            ObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        @Bean
        open fun worklogAggregateService(): WorklogAggregateService = mockk(relaxed = true)

        @Bean
        open fun worklogAggregateController(service: WorklogAggregateService): WorklogAggregateController =
            WorklogAggregateController(service)

        @Bean
        open fun worklogAggregateExceptionHandler(): WorklogAggregateExceptionHandler {
            return WorklogAggregateExceptionHandler()
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var worklogAggregateService: WorklogAggregateService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    lateinit var mockMvc: MockMvc

    private val actorUuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val nilUuid = UUID.fromString("00000000-0000-0000-0000-000000000000")

    /** 빈 배열 가짜그린 방지 — 1건 버킷 포함. */
    private val sampleBucket =
        WorklogAggregateBucket(
            key = "TPRJ-1",
            label = "TPRJ-1",
            timeSpentSeconds = 3600L,
            worklogCount = 2,
        )

    private val sampleResultByIssue =
        WorklogAggregateResult(
            buckets = listOf(sampleBucket),
            totalTimeSpentSeconds = 3600L,
        )

    private val sampleResultEmpty =
        WorklogAggregateResult(
            buckets = emptyList(),
            totalTimeSpentSeconds = 0L,
        )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorUuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    @AfterEach
    fun tearDown() {
        clearMocks(worklogAggregateService)
        SecurityContextHolder.clearContext()
    }

    // ── WA-1. GET by=issue → 200 + 응답 구조 ─────────────────────────────────

    /**
     * WA-1. by=issue → 200 OK + 응답 구조 전체 검증.
     *
     * Given  service.aggregate 가 sampleResultByIssue 반환
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=issue
     * Then   200, data.buckets 배열(1건), data.totalTimeSpentSeconds, data.by=issue, data.granularity=null
     */
    @Test
    fun `GET by=issue — 200 OK plus 응답 구조 검증`() {
        every {
            worklogAggregateService.aggregate(
                actorId = ActorId(actorUuid),
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )
        } returns sampleResultByIssue

        mockMvc.perform(get("/api/v1/worklogs/aggregate").param("project", "TPRJ").param("by", "issue"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.buckets").isArray)
            .andExpect(jsonPath("$.data.buckets[0].key").value("TPRJ-1"))
            .andExpect(jsonPath("$.data.buckets[0].label").value("TPRJ-1"))
            .andExpect(jsonPath("$.data.buckets[0].timeSpentSeconds").value(3600))
            .andExpect(jsonPath("$.data.buckets[0].worklogCount").value(2))
            .andExpect(jsonPath("$.data.totalTimeSpentSeconds").value(3600))
            .andExpect(jsonPath("$.data.by").value("issue"))
            .andExpect(jsonPath("$.data.granularity").doesNotExist())
    }

    // ── WA-2. GET by=user → 200 ───────────────────────────────────────────────

    /**
     * WA-2. by=user → 200 OK.
     *
     * Given  service.aggregate 가 sampleResultByIssue(재사용) 반환
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=user
     * Then   200 OK
     */
    @Test
    fun `GET by=user — 200 OK`() {
        val userBucket =
            WorklogAggregateBucket(
                key = actorUuid.toString(),
                label = "Alice",
                timeSpentSeconds = 7200L,
                worklogCount = 2,
            )
        val userResult = WorklogAggregateResult(buckets = listOf(userBucket), totalTimeSpentSeconds = 7200L)

        every {
            worklogAggregateService.aggregate(
                actorId = ActorId(actorUuid),
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.USER,
                granularity = null,
                from = null,
                to = null,
            )
        } returns userResult

        mockMvc.perform(get("/api/v1/worklogs/aggregate").param("project", "TPRJ").param("by", "user"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.buckets").isArray)
            .andExpect(jsonPath("$.data.by").value("user"))
    }

    // ── WA-3. GET by=period → 200 + granularity 포함 ────────────────────────

    /**
     * WA-3. by=period&granularity=day → 200 OK + 응답 granularity=day.
     *
     * Given  service.aggregate 가 기간 버킷 1건 포함한 결과 반환
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=period&granularity=day
     * Then   200, data.by=period, data.granularity=day
     */
    @Test
    fun `GET by=period granularity=day — 200 OK plus granularity 포함`() {
        val periodBucket =
            WorklogAggregateBucket(
                key = "2026-06-01",
                label = "2026-06-01",
                timeSpentSeconds = 3600L,
                worklogCount = 1,
            )
        val periodResult = WorklogAggregateResult(buckets = listOf(periodBucket), totalTimeSpentSeconds = 3600L)

        every {
            worklogAggregateService.aggregate(
                actorId = ActorId(actorUuid),
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.PERIOD,
                granularity = AggregateGranularity.DAY,
                from = null,
                to = null,
            )
        } returns periodResult

        mockMvc.perform(
            get("/api/v1/worklogs/aggregate")
                .param("project", "TPRJ")
                .param("by", "period")
                .param("granularity", "day"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.by").value("period"))
            .andExpect(jsonPath("$.data.granularity").value("day"))
    }

    // ── WA-4. project 누락 → 400 ──────────────────────────────────────────────

    /**
     * WA-4. project 파라미터 누락 → 400 Bad Request.
     *
     * When   GET /api/v1/worklogs/aggregate?by=issue (project 없음)
     * Then   400
     */
    @Test
    fun `GET project 누락 — 400 Bad Request`() {
        mockMvc.perform(get("/api/v1/worklogs/aggregate").param("by", "issue"))
            .andExpect(status().isBadRequest)
    }

    // ── WA-5. by 무효 값 → 400 ────────────────────────────────────────────────

    /**
     * WA-5. by 파라미터에 유효하지 않은 값 → 400 Bad Request.
     *
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=invalid
     * Then   400
     */
    @Test
    fun `GET by 무효 값 — 400 Bad Request`() {
        mockMvc.perform(get("/api/v1/worklogs/aggregate").param("project", "TPRJ").param("by", "invalid"))
            .andExpect(status().isBadRequest)
    }

    // ── WA-6. granularity 무효 값 → 400 ──────────────────────────────────────

    /**
     * WA-6. granularity 에 유효하지 않은 값 → 400 Bad Request.
     *
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=period&granularity=invalid
     * Then   400
     */
    @Test
    fun `GET granularity 무효 값 — 400 Bad Request`() {
        mockMvc.perform(
            get("/api/v1/worklogs/aggregate")
                .param("project", "TPRJ")
                .param("by", "period")
                .param("granularity", "invalid"),
        )
            .andExpect(status().isBadRequest)
    }

    // ── WA-7. from 형식 무효 → 400 ────────────────────────────────────────────

    /**
     * WA-7. from 파라미터에 YYYY-MM-DD 형식이 아닌 값 → 400 Bad Request.
     *
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=issue&from=not-a-date
     * Then   400
     */
    @Test
    fun `GET from 형식 무효 — 400 Bad Request`() {
        mockMvc.perform(
            get("/api/v1/worklogs/aggregate")
                .param("project", "TPRJ")
                .param("by", "issue")
                .param("from", "not-a-date"),
        )
            .andExpect(status().isBadRequest)
    }

    // ── WA-8. to 형식 무효 → 400 ──────────────────────────────────────────────

    /**
     * WA-8. to 파라미터에 YYYY-MM-DD 형식이 아닌 값 → 400 Bad Request.
     *
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=issue&to=not-a-date
     * Then   400
     */
    @Test
    fun `GET to 형식 무효 — 400 Bad Request`() {
        mockMvc.perform(
            get("/api/v1/worklogs/aggregate")
                .param("project", "TPRJ")
                .param("by", "issue")
                .param("to", "not-a-date"),
        )
            .andExpect(status().isBadRequest)
    }

    // ── WA-9. from > to → 400 ────────────────────────────────────────────────

    /**
     * WA-9. from > to → 400 Bad Request.
     *
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=issue&from=2026-06-30&to=2026-06-01
     * Then   400
     */
    @Test
    fun `GET from 이 to 보다 늦음 — 400 Bad Request`() {
        mockMvc.perform(
            get("/api/v1/worklogs/aggregate")
                .param("project", "TPRJ")
                .param("by", "issue")
                .param("from", "2026-06-30")
                .param("to", "2026-06-01"),
        )
            .andExpect(status().isBadRequest)
    }

    // ── WA-10. C6: by=issue + granularity=month → 200, granularity=null ──────

    /**
     * WA-10. C6 — by=issue 에 granularity=month 동반 시 200 + 응답 granularity 누락(null).
     *
     * 스펙: by≠period 이면 granularity 는 무시하고 응답에 포함하지 않는다.
     * 400 아님 — 관대 처리.
     *
     * Given  service.aggregate 가 sampleResultByIssue 반환 (granularity=null 로 호출)
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=issue&granularity=month
     * Then   200, data.granularity 키 없음
     */
    @Test
    fun `GET by=issue granularity=month C6 — 200 OK plus granularity 누락`() {
        every {
            worklogAggregateService.aggregate(
                actorId = ActorId(actorUuid),
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )
        } returns sampleResultByIssue

        mockMvc.perform(
            get("/api/v1/worklogs/aggregate")
                .param("project", "TPRJ")
                .param("by", "issue")
                .param("granularity", "month"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.granularity").doesNotExist())
    }

    // ── WA-11. 미인증 → 401 ───────────────────────────────────────────────────

    /**
     * WA-11. SecurityContext 없음(미인증) → 401 Unauthorized.
     *
     * actor 추출이 리소스 조회보다 먼저이므로 project 가 존재해도 401 을 반환.
     *
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=issue (SecurityContext cleared)
     * Then   401
     */
    @Test
    fun `미인증 — 401 Unauthorized`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/worklogs/aggregate").param("project", "TPRJ").param("by", "issue"))
            .andExpect(status().isUnauthorized)
    }

    // ── WA-12. nil-UUID actor → 401 ───────────────────────────────────────────

    /**
     * WA-12. SecurityContext 에 nil-UUID(00000000-...) 주체 → 401 Unauthorized.
     *
     * actor 추출 시 nil-UUID 는 ActorId require 가드에서 거부된다.
     * 리소스 조회보다 먼저 처리되어 존재 probe 를 차단한다.
     *
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=issue (nilUuid 주체)
     * Then   401
     */
    @Test
    fun `nil-UUID actor — 401 Unauthorized`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                nilUuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        mockMvc.perform(get("/api/v1/worklogs/aggregate").param("project", "TPRJ").param("by", "issue"))
            .andExpect(status().isUnauthorized)
    }

    // ── WA-13. 비-UUID actor → 401 ────────────────────────────────────────────

    /**
     * WA-13. SecurityContext 에 UUID 형식이 아닌 주체 → 401 Unauthorized.
     *
     * UUID.fromString 파싱 실패 시 CurrentActor.current() 가 401 을 던진다.
     * 리소스 조회보다 먼저 처리된다.
     *
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=issue (비-UUID 주체)
     * Then   401
     */
    @Test
    fun `비-UUID actor — 401 Unauthorized`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "not-a-uuid-at-all",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        mockMvc.perform(get("/api/v1/worklogs/aggregate").param("project", "TPRJ").param("by", "issue"))
            .andExpect(status().isUnauthorized)
    }

    // ── WA-14. 권한 없는 프로젝트 → 403 ─────────────────────────────────────

    /**
     * WA-14. 권한 없는 프로젝트 → 403 Forbidden (누출 차단).
     *
     * Given  service.aggregate 가 IssueAccessDeniedException throw
     * When   GET /api/v1/worklogs/aggregate?project=SECRET&by=issue
     * Then   403 (프로젝트 존재 여부 누출 없음)
     */
    @Test
    fun `권한 없는 프로젝트 — 403 Forbidden`() {
        every {
            worklogAggregateService.aggregate(
                actorId = ActorId(actorUuid),
                projectKey = "SECRET",
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )
        } throws
            IssueAccessDeniedException(
                actor = ActorId(actorUuid),
                permission = IssuePermission.BROWSE,
                scope = IssueScope.Project("SECRET"),
            )

        mockMvc.perform(get("/api/v1/worklogs/aggregate").param("project", "SECRET").param("by", "issue"))
            .andExpect(status().isForbidden)
    }

    // ── WA-15. worklog 없음 → 200 + buckets=[], totalTimeSpentSeconds=0 ──────

    /**
     * WA-15. 해당 프로젝트에 worklog 없음 → 200 OK + 빈 배열 + total=0.
     *
     * Given  service.aggregate 가 빈 결과 반환
     * When   GET /api/v1/worklogs/aggregate?project=TPRJ&by=issue
     * Then   200, data.buckets=[], data.totalTimeSpentSeconds=0
     */
    @Test
    fun `worklog 없음 — 200 OK plus buckets 빈 배열 plus totalTimeSpentSeconds=0`() {
        every {
            worklogAggregateService.aggregate(
                actorId = ActorId(actorUuid),
                projectKey = "TPRJ",
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )
        } returns sampleResultEmpty

        mockMvc.perform(get("/api/v1/worklogs/aggregate").param("project", "TPRJ").param("by", "issue"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.buckets").isArray)
            .andExpect(jsonPath("$.data.buckets").isEmpty)
            .andExpect(jsonPath("$.data.totalTimeSpentSeconds").value(0))
    }
}
