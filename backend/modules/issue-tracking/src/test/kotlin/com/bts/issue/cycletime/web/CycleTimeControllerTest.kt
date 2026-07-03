// CycleTimeController MockMvc 슬라이스 테스트 — actor 게이트·창 해석·검증·에러 매핑 (FR-RP-04 Task 6)

package com.bts.issue.cycletime.web

import com.bts.issue.cycletime.application.CycleTimeService
import com.bts.issue.cycletime.domain.CycleTimeMetric
import com.bts.issue.cycletime.domain.CycleTimeResult
import com.bts.issue.cycletime.domain.CycleTimeSample
import com.bts.issue.cycletime.domain.CycleTimeStats
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * [CycleTimeController] MockMvc 슬라이스 테스트 (FR-RP-04 Task 6).
 *
 * [CycleTimeService] 는 mockk stub 으로 대체한다. [CycleTimeExceptionHandler] 를 controllerAdvice 로
 * 등록해 예외→HTTP 매핑을 검증한다. [com.bts.issue.cfd.web.CfdControllerTest] 미러.
 *
 * ### JavaTimeModule 등록
 * standalone MockMvc 의 메시지 컨버터에 JavaTimeModule 을 등록한 [ObjectMapper] 를 명시 지정한다.
 * 미등록 시 [LocalDate] 가 배열로 직렬화되어 응답 검증이 가짜로 실패/통과할 수 있다.
 *
 * ### 고정 Clock
 * 창 기본값(오늘 기준) 검증을 결정적으로 만들기 위해 [Clock.fixed] 를 컨트롤러에 주입한다.
 *
 * ## 테스트 케이스
 * - (a) 미인증 → 401
 * - (b) from/to 파싱 불가 → 400
 * - (c) from > to → 400
 * - (d) 창 길이 > 180일 → 400
 * - (e) from/to 생략 → 고정 Clock 기준 from=오늘-29, to=오늘 로 service 호출(slot 캡처 검증)
 * - (f) 정상 조회 → 200 + JSON 구조(projectKey/from/to echo, cycleTime/leadTime 통계+표본)
 * - (g) [IssueAccessDeniedException] → 403
 * - (h) 빈 결과(count=0) → count 0·samples []·통계 null 직렬화
 */
class CycleTimeControllerTest {
    private val service = mockk<CycleTimeService>()
    private lateinit var mockMvc: MockMvc

    private val actorUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        val objectMapper =
            ObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(CycleTimeController(service, fixedClock))
                .setControllerAdvice(CycleTimeExceptionHandler())
                .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
                .build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(actorUuid.toString(), null, emptyList())
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    private fun sampleMetric(): CycleTimeMetric =
        CycleTimeMetric(
            stats = CycleTimeStats(count = 1, min = 100, max = 100, avg = 100, p25 = 100, p50 = 100, p75 = 100, p90 = 100),
            samples = listOf(CycleTimeSample(issueKey = "TPRJ-1", seconds = 100)),
        )

    private fun sampleResult(
        from: LocalDate,
        to: LocalDate,
    ): CycleTimeResult =
        CycleTimeResult(
            projectKey = "TPRJ",
            from = from,
            to = to,
            cycleTime = sampleMetric(),
            leadTime = sampleMetric(),
        )

    private fun emptyMetric(): CycleTimeMetric =
        CycleTimeMetric(
            stats = CycleTimeStats(count = 0, min = null, max = null, avg = null, p25 = null, p50 = null, p75 = null, p90 = null),
            samples = emptyList(),
        )

    private fun emptyResult(
        from: LocalDate,
        to: LocalDate,
    ): CycleTimeResult =
        CycleTimeResult(
            projectKey = "TPRJ",
            from = from,
            to = to,
            cycleTime = emptyMetric(),
            leadTime = emptyMetric(),
        )

    // ── (a) 미인증 → 401 ──────────────────────────────────────────────────────

    @Test
    fun `미인증 — 401 Unauthorized`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/projects/TPRJ/cycle-time"))
            .andExpect(status().isUnauthorized)
    }

    // ── (b) from/to 파싱 불가 → 400 ───────────────────────────────────────────

    @Test
    fun `from 파싱 불가 — 400 Bad Request`() {
        mockMvc.perform(get("/api/v1/projects/TPRJ/cycle-time").param("from", "bad"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `to 파싱 불가 — 400 Bad Request`() {
        mockMvc.perform(get("/api/v1/projects/TPRJ/cycle-time").param("to", "bad"))
            .andExpect(status().isBadRequest)
    }

    // ── (c) from > to → 400 ───────────────────────────────────────────────────

    @Test
    fun `from 이 to 보다 늦음 — 400 Bad Request`() {
        mockMvc.perform(
            get("/api/v1/projects/TPRJ/cycle-time")
                .param("from", "2026-07-10")
                .param("to", "2026-07-01"),
        ).andExpect(status().isBadRequest)
    }

    // ── (d) 창 길이 > 180일 → 400 ─────────────────────────────────────────────

    @Test
    fun `창 길이 180일 초과 — 400 Bad Request`() {
        mockMvc.perform(
            get("/api/v1/projects/TPRJ/cycle-time")
                .param("from", "2026-01-01")
                .param("to", "2026-07-01"),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `창 길이 정확히 180일 — 200 OK 경계값`() {
        val from = LocalDate.parse("2026-01-03")
        val to = LocalDate.parse("2026-07-01")
        every { service.getCycleTime(ActorId(actorUuid), "TPRJ", from, to) } returns sampleResult(from, to)

        mockMvc.perform(
            get("/api/v1/projects/TPRJ/cycle-time")
                .param("from", "2026-01-03")
                .param("to", "2026-07-01"),
        ).andExpect(status().isOk)
    }

    // ── (e) from/to 생략 → 고정 Clock 기준 기본창 30일 ────────────────────────

    @Test
    fun `from,to 생략 — 고정 Clock 기준 기본창 30일로 service 호출`() {
        val today = LocalDate.now(fixedClock)
        val expectedFrom = today.minusDays(29)
        val fromSlot = slot<LocalDate>()
        val toSlot = slot<LocalDate>()
        every {
            service.getCycleTime(ActorId(actorUuid), "TPRJ", capture(fromSlot), capture(toSlot))
        } returns sampleResult(expectedFrom, today)

        mockMvc.perform(get("/api/v1/projects/TPRJ/cycle-time"))
            .andExpect(status().isOk)

        assertThat(fromSlot.captured).isEqualTo(expectedFrom)
        assertThat(toSlot.captured).isEqualTo(today)
    }

    @Test
    fun `to만 생략 — from 은 그대로, to=오늘`() {
        val today = LocalDate.now(fixedClock)
        val givenFrom = LocalDate.parse("2026-06-01")
        every { service.getCycleTime(ActorId(actorUuid), "TPRJ", givenFrom, today) } returns
            sampleResult(givenFrom, today)

        mockMvc.perform(get("/api/v1/projects/TPRJ/cycle-time").param("from", "2026-06-01"))
            .andExpect(status().isOk)
    }

    @Test
    fun `from만 생략 — from=to-29, to 는 그대로`() {
        val givenTo = LocalDate.parse("2026-06-30")
        val expectedFrom = givenTo.minusDays(29)
        every { service.getCycleTime(ActorId(actorUuid), "TPRJ", expectedFrom, givenTo) } returns
            sampleResult(expectedFrom, givenTo)

        mockMvc.perform(get("/api/v1/projects/TPRJ/cycle-time").param("to", "2026-06-30"))
            .andExpect(status().isOk)
    }

    // ── (f) 정상 조회 → 200 + JSON 구조 ────────────────────────────────────────

    @Test
    fun `정상 조회 — 200 OK plus 응답 구조 검증`() {
        val from = LocalDate.parse("2026-06-01")
        val to = LocalDate.parse("2026-06-05")
        every { service.getCycleTime(ActorId(actorUuid), "TPRJ", from, to) } returns sampleResult(from, to)

        mockMvc.perform(
            get("/api/v1/projects/TPRJ/cycle-time")
                .param("from", "2026-06-01")
                .param("to", "2026-06-05"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.projectKey").value("TPRJ"))
            .andExpect(jsonPath("$.data.from").value("2026-06-01"))
            .andExpect(jsonPath("$.data.to").value("2026-06-05"))
            .andExpect(jsonPath("$.data.cycleTime.count").value(1))
            .andExpect(jsonPath("$.data.cycleTime.min").value(100))
            .andExpect(jsonPath("$.data.cycleTime.max").value(100))
            .andExpect(jsonPath("$.data.cycleTime.avg").value(100))
            .andExpect(jsonPath("$.data.cycleTime.p25").value(100))
            .andExpect(jsonPath("$.data.cycleTime.p50").value(100))
            .andExpect(jsonPath("$.data.cycleTime.p75").value(100))
            .andExpect(jsonPath("$.data.cycleTime.p90").value(100))
            .andExpect(jsonPath("$.data.cycleTime.samples").isArray)
            .andExpect(jsonPath("$.data.cycleTime.samples[0].issueKey").value("TPRJ-1"))
            .andExpect(jsonPath("$.data.cycleTime.samples[0].seconds").value(100))
            .andExpect(jsonPath("$.data.leadTime.count").value(1))
            .andExpect(jsonPath("$.data.leadTime.samples[0].issueKey").value("TPRJ-1"))
    }

    // ── (g) IssueAccessDeniedException → 403 ──────────────────────────────────

    @Test
    fun `권한 없는 프로젝트 — 403 Forbidden`() {
        val today = LocalDate.now(fixedClock)
        val expectedFrom = today.minusDays(29)
        every { service.getCycleTime(ActorId(actorUuid), "SECRET", expectedFrom, today) } throws
            IssueAccessDeniedException(
                actor = ActorId(actorUuid),
                permission = IssuePermission.BROWSE,
                scope = IssueScope.Project("SECRET"),
            )

        mockMvc.perform(get("/api/v1/projects/SECRET/cycle-time"))
            .andExpect(status().isForbidden)
    }

    // ── (h) 빈 결과(count=0) — 통계 null 직렬화 ─────────────────────────────────

    @Test
    fun `빈 결과 — count 0 이고 samples 빈 배열, 통계 필드 null`() {
        val from = LocalDate.parse("2026-06-01")
        val to = LocalDate.parse("2026-06-05")
        every { service.getCycleTime(ActorId(actorUuid), "TPRJ", from, to) } returns emptyResult(from, to)

        mockMvc.perform(
            get("/api/v1/projects/TPRJ/cycle-time")
                .param("from", "2026-06-01")
                .param("to", "2026-06-05"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.cycleTime.count").value(0))
            .andExpect(jsonPath("$.data.cycleTime.min").value(nullValue()))
            .andExpect(jsonPath("$.data.cycleTime.samples").isEmpty)
            .andExpect(jsonPath("$.data.leadTime.count").value(0))
            .andExpect(jsonPath("$.data.leadTime.samples").isEmpty)
    }
}
