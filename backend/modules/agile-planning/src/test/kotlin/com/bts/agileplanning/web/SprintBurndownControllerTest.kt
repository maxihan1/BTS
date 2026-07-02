// SprintBurndownController MockMvc 슬라이스 HTTP 통합 테스트 — 200/422/401 RED 명세 (FR-RP-01 Task 5)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.SprintBurndownResult
import com.bts.agileplanning.application.SprintBurndownService
import com.bts.agileplanning.application.SprintDatesRequiredException
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.domain.burndown.BurndownPoint
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
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
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.LocalDate
import java.util.UUID

/**
 * SprintBurndownController MockMvc 슬라이스 HTTP 통합 테스트.
 *
 * [SprintBurndownService] 는 MockK stub 으로 대체한다.
 * Spring Security 컨텍스트는 [SecurityContextHolder] 에 직접 UUID 기반 Authentication 을 주입한다
 * ([SprintControllerTest]·[TimelineControllerTest] 와 동일 패턴).
 *
 * ### 검증 케이스
 * - GET-1(양성). GET /api/v1/sprints/{id}/burndown 정상 → 200 + DataResponse 봉투,
 *   미래 일자 point 는 remainingSeconds/completedSeconds null, idealSeconds/scopeSeconds non-null.
 * - GET-2(음성). service 가 [SprintDatesRequiredException] 을 던지면 422.
 * - GET-3(음성). 비인증이면 401 + 서비스 미호출(존재 probe 차단).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [SprintBurndownControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class SprintBurndownControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [SprintBurndownController], [SprintExceptionHandler] 와 MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig : WebMvcConfigurer {
        /**
         * production(Spring Boot) 직렬화와 동일하게 LocalDate 를 ISO 문자열로 내보낸다.
         *
         * @EnableWebMvc 슬라이스는 Spring Boot 의 Jackson 자동설정을 상속하지 않아 기본 ObjectMapper 가
         * LocalDate 를 `[2026,7,1]` 배열(WRITE_DATES_AS_TIMESTAMPS)로 직렬화한다([TimelineControllerTest] 선례).
         */
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters.filterIsInstance<MappingJackson2HttpMessageConverter>().forEach {
                it.objectMapper
                    .registerModule(JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }
        }

        @Bean
        open fun sprintBurndownService(): SprintBurndownService = mockk(relaxed = true)

        @Bean
        open fun sprintBurndownController(service: SprintBurndownService): SprintBurndownController {
            return SprintBurndownController(service)
        }

        @Bean
        open fun sprintExceptionHandler(): SprintExceptionHandler = SprintExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var sprintBurndownService: SprintBurndownService

    lateinit var mockMvc: MockMvc

    private val actorId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val sprintId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(sprintBurndownService)
        val auth =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }

    /** 1일차(과거, 산출됨)와 종료일(미래, 미산출) 두 지점을 담은 샘플 결과. */
    private fun sampleResult(): SprintBurndownResult =
        SprintBurndownResult(
            sprintId = sprintId,
            projectKey = "BTS",
            status = SprintStatus.ACTIVE,
            startDate = LocalDate.of(2026, 7, 1),
            endDate = LocalDate.of(2026, 7, 14),
            totalScopeSeconds = 57_600L,
            points =
                listOf(
                    BurndownPoint(
                        date = LocalDate.of(2026, 7, 1),
                        remainingSeconds = 57_600L,
                        idealSeconds = 57_600L,
                        completedSeconds = 0L,
                        scopeSeconds = 57_600L,
                    ),
                    BurndownPoint(
                        date = LocalDate.of(2026, 7, 14),
                        remainingSeconds = null,
                        idealSeconds = 0L,
                        completedSeconds = null,
                        scopeSeconds = 57_600L,
                    ),
                ),
        )

    // ── GET-1(양성). 정상 → 200 + 미래일 null 형식 ───────────────────────────

    @Test
    fun `GET burndown 정상이면 200과 미래일 null을 포함한 points를 반환한다`() {
        every { sprintBurndownService.getBurndown(actorId, sprintId) } returns sampleResult()

        mockMvc.perform(get("/api/v1/sprints/$sprintId/burndown"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sprintId").value(sprintId.toString()))
            .andExpect(jsonPath("$.data.projectKey").value("BTS"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.startDate").value("2026-07-01"))
            .andExpect(jsonPath("$.data.endDate").value("2026-07-14"))
            .andExpect(jsonPath("$.data.totalScopeSeconds").value(57_600))
            .andExpect(jsonPath("$.data.points.length()").value(2))
            .andExpect(jsonPath("$.data.points[0].date").value("2026-07-01"))
            .andExpect(jsonPath("$.data.points[0].remainingSeconds").value(57_600))
            .andExpect(jsonPath("$.data.points[0].idealSeconds").value(57_600))
            .andExpect(jsonPath("$.data.points[0].completedSeconds").value(0))
            .andExpect(jsonPath("$.data.points[0].scopeSeconds").value(57_600))
            .andExpect(jsonPath("$.data.points[1].remainingSeconds").value(nullValue()))
            .andExpect(jsonPath("$.data.points[1].completedSeconds").value(nullValue()))
            .andExpect(jsonPath("$.data.points[1].idealSeconds").value(0))
            .andExpect(jsonPath("$.data.points[1].scopeSeconds").value(57_600))
    }

    // ── GET-2(음성). 기간 미설정 → 422 ────────────────────────────────────────

    @Test
    fun `GET burndown 기간 미설정이면 422를 반환한다`() {
        every {
            sprintBurndownService.getBurndown(actorId, sprintId)
        } throws SprintDatesRequiredException("스프린트 기간이 설정되지 않았습니다.")

        mockMvc.perform(get("/api/v1/sprints/$sprintId/burndown"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("AGILE_SPRINT_DATES_REQUIRED"))
    }

    // ── GET-3(음성). 비인증 → 401 + 서비스 미호출 ─────────────────────────────

    @Test
    fun `GET burndown 비인증이면 401을 반환하고 서비스가 호출되지 않는다`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/sprints/$sprintId/burndown"))
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { sprintBurndownService.getBurndown(any(), any()) }
    }
}
