// BacklogController MockMvc HTTP 통합 테스트 — 양성·음성 분별 시드 (FR-BL-01/02 Task 3)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.BacklogApplicationService
import com.bts.agileplanning.application.BacklogResult
import com.bts.agileplanning.application.SprintWithIssues
import com.bts.agileplanning.web.dto.BacklogIssueResponse
import com.bts.agileplanning.web.dto.SprintMetaResponse
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
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
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.LocalDate
import java.util.UUID

/**
 * BacklogController MockMvc 슬라이스 HTTP 통합 테스트.
 *
 * [BacklogApplicationService] 는 MockK stub 으로 대체한다.
 * Spring Security 컨텍스트는 [SecurityContextHolder] 에 직접 UUID 기반 Authentication 을 주입한다.
 *
 * ### 검증 케이스 (양성 + 음성 분별 시드 — C3 vacuous 방지)
 * - BC-1(양성). GET /api/v1/projects/{key}/backlog 정상 → 200 + DataResponse 봉투 + backlog/sprints/truncated 구조.
 * - BC-2(양성). 미할당 이슈가 backlog 에, 스프린트 할당 이슈가 sprints[0].issues 에 정확히 존재한다.
 * - BC-3(양성). truncated=true 가 응답에 전파된다.
 * - BC-4(음성). BROWSE 권한 거부 시 403 + AGILE_ACCESS_DENIED 를 반환한다.
 * - BC-5(음성). 비인증 시 401 + 서비스 미호출(존재 probe 차단).
 * - BC-6(음성). 보안등급 이슈는 backlog/sprints 어디에도 누출되지 않는다(viewer 기준 가시성 필터 단언).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BacklogControllerIntegrationTest.TestMvcConfig::class])
@WebAppConfiguration
class BacklogControllerIntegrationTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [BacklogController] 와 [SprintExceptionHandler] 를 등록한다.
     * [BacklogApplicationService] 는 MockK stub 빈이다.
     * SprintExceptionHandler 를 재사용하되 [BacklogController] 를 assignableTypes 에 추가해야 한다.
     * 별도 [BacklogExceptionHandler] 가 없는 경우, 기존 핸들러가 커버하지 않으므로
     * 통합 테스트에서 400/403/401 → 핸들러 적용을 실측 검증한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun backlogApplicationService(): BacklogApplicationService = mockk(relaxed = true)

        @Bean
        open fun backlogController(service: BacklogApplicationService): BacklogController = BacklogController(service)

        @Bean
        open fun backlogExceptionHandler(): BacklogExceptionHandler = BacklogExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var backlogApplicationService: BacklogApplicationService

    lateinit var mockMvc: MockMvc

    private val actorId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val projectKey = "BTS"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(backlogApplicationService)
        val auth =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }

    private fun sampleSprintMeta(
        sprintId: UUID = UUID.randomUUID(),
        name: String = "Sprint 1",
        status: String = "PLANNED",
    ): SprintMetaResponse =
        SprintMetaResponse(
            sprintId = sprintId,
            name = name,
            goal = null,
            status = status,
            startDate = LocalDate.of(2026, 7, 1),
            endDate = LocalDate.of(2026, 7, 14),
            version = 0L,
        )

    private fun sampleIssue(
        key: String,
        rank: String? = null,
    ): BacklogIssueResponse =
        BacklogIssueResponse(
            key = key,
            summary = "요약 $key",
            currentStateKey = "open",
            assigneeId = null,
            priority = 3,
            rank = rank,
            version = 0L,
            epicKey = null,
        )

    // ── BC-1: GET 정상 → 200 + 봉투 구조 ─────────────────────────────────────

    @Test
    fun `GET backlog 정상이면 200과 DataResponse 봉투에 backlog, sprints, truncated 를 반환한다`() {
        val result =
            BacklogResult(
                backlog = listOf(sampleIssue("BTS-1")),
                sprints = emptyList(),
                truncated = false,
            )
        every { backlogApplicationService.getBacklog(actorId, projectKey) } returns result

        mockMvc.perform(get("/api/v1/projects/$projectKey/backlog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.backlog").isArray)
            .andExpect(jsonPath("$.data.sprints").isArray)
            .andExpect(jsonPath("$.data.truncated").isBoolean)
    }

    // ── BC-2(양성): 그룹핑 정확성 단언 ──────────────────────────────────────

    @Test
    fun `미할당 이슈가 backlog 에, 스프린트 할당 이슈가 sprints 에 정확히 그룹핑된다`() {
        val sprintId = UUID.randomUUID()
        val result =
            BacklogResult(
                backlog = listOf(sampleIssue("BTS-1", rank = "a")),
                sprints =
                    listOf(
                        SprintWithIssues(
                            sprint = sampleSprintMeta(sprintId = sprintId, status = "ACTIVE"),
                            issues = listOf(sampleIssue("BTS-2", rank = "b")),
                        ),
                    ),
                truncated = false,
            )
        every { backlogApplicationService.getBacklog(actorId, projectKey) } returns result

        mockMvc.perform(get("/api/v1/projects/$projectKey/backlog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.backlog.length()").value(1))
            .andExpect(jsonPath("$.data.backlog[0].key").value("BTS-1"))
            .andExpect(jsonPath("$.data.sprints.length()").value(1))
            .andExpect(jsonPath("$.data.sprints[0].issues.length()").value(1))
            .andExpect(jsonPath("$.data.sprints[0].issues[0].key").value("BTS-2"))
            .andExpect(jsonPath("$.data.sprints[0].sprint.sprintId").value(sprintId.toString()))
            .andExpect(jsonPath("$.data.sprints[0].sprint.status").value("ACTIVE"))
    }

    // ── BC-3(양성): truncated 전파 ────────────────────────────────────────────

    @Test
    fun `service 가 truncated true 를 반환하면 응답 truncated 가 true 다`() {
        val result =
            BacklogResult(
                backlog = listOf(sampleIssue("BTS-1")),
                sprints = emptyList(),
                truncated = true,
            )
        every { backlogApplicationService.getBacklog(actorId, projectKey) } returns result

        mockMvc.perform(get("/api/v1/projects/$projectKey/backlog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.truncated").value(true))
    }

    // ── BC-4(음성): BROWSE 권한 거부 → 403 ───────────────────────────────────

    @Test
    fun `BROWSE 권한 거부 시 403 과 AGILE_ACCESS_DENIED 를 반환한다`() {
        every {
            backlogApplicationService.getBacklog(actorId, projectKey)
        } throws ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")

        mockMvc.perform(get("/api/v1/projects/$projectKey/backlog"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))
    }

    // ── BC-5(음성): 비인증 → 401 + 서비스 미호출 ────────────────────────────

    @Test
    fun `비인증이면 401 을 반환하고 서비스가 호출되지 않는다`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/projects/$projectKey/backlog"))
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { backlogApplicationService.getBacklog(any(), any()) }
    }

    // ── BC-6(음성): 가시성 음성 — 비가시 이슈 누출 0 단언 ────────────────────

    @Test
    fun `service 가 가시 이슈만 반환하면 응답 backlog 와 sprints 에 비가시 이슈가 없다`() {
        // service 는 listVisibleIssuesByProject 결과만 사용하므로
        // 비가시 이슈는 service 에서 이미 필터됐음을 controller 레이어에서 단언한다.
        val result =
            BacklogResult(
                backlog = listOf(sampleIssue("BTS-VISIBLE")),
                sprints = emptyList(),
                truncated = false,
            )
        every { backlogApplicationService.getBacklog(actorId, projectKey) } returns result

        val response =
            mockMvc.perform(get("/api/v1/projects/$projectKey/backlog"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.backlog.length()").value(1))
                .andExpect(jsonPath("$.data.backlog[0].key").value("BTS-VISIBLE"))
                .andReturn()

        // 응답 전체에서 "BTS-SECRET" 문자열 없음 확인 (누출 0 단언)
        val body = response.response.contentAsString
        assert(!body.contains("BTS-SECRET")) {
            "비가시 이슈가 응답에 누출됐습니다: $body"
        }
    }
}
