// ProjectSummaryController MockMvc 슬라이스 테스트 — actor 게이트 · limit 검증 · 에러 매핑 · 응답 계약
// ktlint(140)와 detekt(120)의 한도가 달라 그 사이 길이의 픽스처 한 줄이 서로 다른 요구를 받는다.
// 테스트 픽스처는 파일 단위로 억제하는 것이 저장소 관례다(같은 모듈에 21건).
@file:Suppress("MaxLineLength")

package com.bts.issue.summary.web

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.history.IssueChangeItem
import com.bts.issue.statushistory.StatusCategory
import com.bts.issue.summary.application.ProjectSummaryService
import com.bts.issue.summary.domain.AssigneeSlice
import com.bts.issue.summary.domain.PrioritySlice
import com.bts.issue.summary.domain.ProjectActivityEntry
import com.bts.issue.summary.domain.ProjectSummary
import com.bts.issue.summary.domain.RecentCounts
import com.bts.issue.summary.domain.StatusSlice
import com.bts.issue.summary.domain.TypeSlice
import com.bts.issue.summary.domain.UpcomingCounts
import com.bts.issue.summary.domain.WindowCount
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
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
import java.time.Instant
import java.util.UUID

/**
 * [ProjectSummaryController] MockMvc 슬라이스 테스트 (Jira 패리티 캠페인 PR ③).
 *
 * [ProjectSummaryService] 는 mockk stub 으로 대체하고 [ProjectSummaryExceptionHandler] 를
 * controllerAdvice 로 등록해 예외→HTTP 매핑을 검증한다([com.bts.issue.cfd.web.CfdControllerTest] 동형).
 *
 * ### JavaTimeModule 등록
 * standalone MockMvc 기본 컨버터는 [Instant] 를 숫자로 직렬화한다. 미등록 시 응답 검증이
 * 가짜로 통과하거나 실패하므로 [JavaTimeModule] 을 명시 등록한다.
 *
 * ## 테스트 케이스
 * - 미인증 → 401. **service 를 호출하지 않는다** — actor 게이트가 최상단이다.
 * - [IssueAccessDeniedException] → 403 (프로젝트 존재 여부를 흘리지 않는다).
 * - 요약 200 — 카드 4종 + 분포 4종이 계약대로 실린다.
 * - 활동 200 — 이슈 키·라벨·항목 배열이 실린다.
 * - limit 기본값 20, 상한 50, 하한 1 — 범위 밖은 400.
 */
class ProjectSummaryControllerTest {
    private val service = mockk<ProjectSummaryService>()
    private lateinit var mockMvc: MockMvc

    private val actorUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val assigneeUuid = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        val objectMapper =
            ObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(ProjectSummaryController(service))
                .setControllerAdvice(ProjectSummaryExceptionHandler())
                .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
                .build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(actorUuid.toString(), null, emptyList())
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── actor 게이트 ─────────────────────────────────────────────────────────

    @Test
    fun `요약 - 미인증이면 401 이고 service 를 호출하지 않는다`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/projects/TPRJ/summary"))
            .andExpect(status().isUnauthorized)

        // actor 추출이 최상단이라야 미인증자가 파라미터 오류로 존재를 probe 하지 못한다.
        verify(exactly = 0) { service.getSummary(any(), any()) }
    }

    @Test
    fun `활동 - 미인증이면 401 이고 service 를 호출하지 않는다`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/projects/TPRJ/activity"))
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { service.getActivity(any(), any(), any()) }
    }

    @Test
    fun `요약 - BROWSE 권한이 없으면 403 이다`() {
        every { service.getSummary(any(), "TPRJ") } throws
            IssueAccessDeniedException(
                ActorId(actorUuid),
                IssuePermission.BROWSE,
                IssueScope.Project("TPRJ"),
            )

        mockMvc.perform(get("/api/v1/projects/TPRJ/summary"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `활동 - BROWSE 권한이 없으면 403 이다`() {
        every { service.getActivity(any(), "TPRJ", any()) } throws
            IssueAccessDeniedException(
                ActorId(actorUuid),
                IssuePermission.BROWSE,
                IssueScope.Project("TPRJ"),
            )

        mockMvc.perform(get("/api/v1/projects/TPRJ/activity"))
            .andExpect(status().isForbidden)
    }

    // ── 요약 응답 계약 ────────────────────────────────────────────────────────

    @Test
    fun `요약 - 카드 4종과 분포 4종을 계약대로 싣는다`() {
        every { service.getSummary(ActorId(actorUuid), "TPRJ") } returns sampleSummary()

        mockMvc.perform(get("/api/v1/projects/TPRJ/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.projectKey").value("TPRJ"))
            .andExpect(jsonPath("$.data.recent.windowDays").value(7))
            .andExpect(jsonPath("$.data.recent.completed.current").value(12))
            .andExpect(jsonPath("$.data.recent.completed.previous").value(8))
            .andExpect(jsonPath("$.data.recent.updated.current").value(34))
            .andExpect(jsonPath("$.data.recent.created.current").value(18))
            .andExpect(jsonPath("$.data.upcoming.due").value(5))
            .andExpect(jsonPath("$.data.upcoming.overdue").value(2))
            .andExpect(jsonPath("$.data.statusOverview[0].statusKey").value("doing"))
            .andExpect(jsonPath("$.data.statusOverview[0].statusName").value("진행 중"))
            .andExpect(jsonPath("$.data.statusOverview[0].category").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.statusOverview[0].count").value(9))
            .andExpect(jsonPath("$.data.priorityBreakdown[0].priority").value(1))
            // 형제 3종(statusName·typeName·assigneeName)처럼 표시명을 함께 싣는다 — 프론트가
            // 1~5 라벨 맵을 손으로 다시 쓰면 IssuePriority 와 서로를 확인하지 못한다.
            .andExpect(jsonPath("$.data.priorityBreakdown[0].priorityName").value("Highest"))
            .andExpect(jsonPath("$.data.typesOfWork[0].typeKey").value("story"))
            .andExpect(jsonPath("$.data.typesOfWork[0].typeName").value("스토리"))
            .andExpect(jsonPath("$.data.teamWorkload[0].assigneeId").value(assigneeUuid.toString()))
            .andExpect(jsonPath("$.data.teamWorkload[0].assigneeName").value("홍길동"))
    }

    @Test
    fun `요약 - 미할당 버킷은 assigneeId 와 assigneeName 이 null 로 실린다`() {
        every { service.getSummary(ActorId(actorUuid), "TPRJ") } returns
            sampleSummary(teamWorkload = listOf(AssigneeSlice(null, null, 4)))

        mockMvc.perform(get("/api/v1/projects/TPRJ/summary"))
            .andExpect(status().isOk)
            // 미할당은 값이 없는 게 아니라 「담당자 없음」이라는 정보다 — 키를 지우면 안 된다.
            .andExpect(jsonPath("$.data.teamWorkload[0].assigneeId").doesNotExist())
            .andExpect(jsonPath("$.data.teamWorkload[0].count").value(4))
    }

    /**
     * 우선순위가 1~5 밖이면 표시명 키만 빠지고 응답은 200 이다.
     *
     * `IssuePriority.fromNumber` 는 범위 밖에서 throw 한다. DB 는 SMALLINT 라 제약이 무너지면
     * 6 이 들어올 수 있는데, 그때 요약 화면 전체가 500 이 되면 안 된다 — 라벨 하나를 못 붙이는
     * 것과 화면이 죽는 것은 전혀 다른 사고다.
     */
    @Test
    fun `요약 - 범위 밖 우선순위는 표시명만 빠지고 200 이다`() {
        every { service.getSummary(ActorId(actorUuid), "TPRJ") } returns
            sampleSummary(priorityBreakdown = listOf(PrioritySlice(9, 1)))

        mockMvc.perform(get("/api/v1/projects/TPRJ/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priorityBreakdown[0].priority").value(9))
            .andExpect(jsonPath("$.data.priorityBreakdown[0].priorityName").doesNotExist())
    }

    // ── 활동 응답 계약 ────────────────────────────────────────────────────────

    @Test
    fun `활동 - 이슈 키와 변경 항목 배열을 싣는다`() {
        every { service.getActivity(ActorId(actorUuid), "TPRJ", 20) } returns
            listOf(
                ProjectActivityEntry(
                    issueKey = "TPRJ-1",
                    actorId = assigneeUuid,
                    actorName = "홍길동",
                    createdAt = Instant.parse("2026-09-03T09:00:00Z"),
                    items =
                        listOf(
                            IssueChangeItem("status", "doing", "done", "진행 중", "완료"),
                        ),
                ),
            )

        mockMvc.perform(get("/api/v1/projects/TPRJ/activity"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.entries[0].issueKey").value("TPRJ-1"))
            .andExpect(jsonPath("$.data.entries[0].actorName").value("홍길동"))
            .andExpect(jsonPath("$.data.entries[0].createdAt").value("2026-09-03T09:00:00Z"))
            .andExpect(jsonPath("$.data.entries[0].items[0].field").value("status"))
            .andExpect(jsonPath("$.data.entries[0].items[0].toLabel").value("완료"))
    }

    // ── limit 검증 ────────────────────────────────────────────────────────────

    @Test
    fun `활동 - limit 을 생략하면 20 으로 호출한다`() {
        val limitSlot = slot<Int>()
        every { service.getActivity(any(), "TPRJ", capture(limitSlot)) } returns emptyList()

        mockMvc.perform(get("/api/v1/projects/TPRJ/activity"))
            .andExpect(status().isOk)

        assertThat(limitSlot.captured).isEqualTo(20)
    }

    @Test
    fun `활동 - limit 상한 50 은 통과한다`() {
        val limitSlot = slot<Int>()
        every { service.getActivity(any(), "TPRJ", capture(limitSlot)) } returns emptyList()

        mockMvc.perform(get("/api/v1/projects/TPRJ/activity").param("limit", "50"))
            .andExpect(status().isOk)

        assertThat(limitSlot.captured).isEqualTo(50)
    }

    @Test
    fun `활동 - limit 이 상한을 넘으면 400 이고 service 를 호출하지 않는다`() {
        mockMvc.perform(get("/api/v1/projects/TPRJ/activity").param("limit", "51"))
            .andExpect(status().isBadRequest)

        // 상한을 조용히 잘라 주면 호출자가 자기 요청이 무시된 걸 모른다.
        verify(exactly = 0) { service.getActivity(any(), any(), any()) }
    }

    @Test
    fun `활동 - limit 이 0 이하면 400 이다`() {
        mockMvc.perform(get("/api/v1/projects/TPRJ/activity").param("limit", "0"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `활동 - limit 이 숫자가 아니면 400 이다`() {
        mockMvc.perform(get("/api/v1/projects/TPRJ/activity").param("limit", "many"))
            .andExpect(status().isBadRequest)
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private fun sampleSummary(
        teamWorkload: List<AssigneeSlice> = listOf(AssigneeSlice(assigneeUuid, "홍길동", 7)),
        priorityBreakdown: List<PrioritySlice> = listOf(PrioritySlice(1, 3)),
    ): ProjectSummary =
        ProjectSummary(
            projectKey = "TPRJ",
            recent =
                RecentCounts(
                    windowDays = 7,
                    completed = WindowCount(12, 8),
                    updated = WindowCount(34, 34),
                    created = WindowCount(18, 12),
                ),
            upcoming = UpcomingCounts(windowDays = 7, due = 5, overdue = 2),
            statusOverview = listOf(StatusSlice("doing", "진행 중", StatusCategory.IN_PROGRESS, 9)),
            priorityBreakdown = priorityBreakdown,
            typesOfWork = listOf(TypeSlice("story", "스토리", 22)),
            teamWorkload = teamWorkload,
        )
}
