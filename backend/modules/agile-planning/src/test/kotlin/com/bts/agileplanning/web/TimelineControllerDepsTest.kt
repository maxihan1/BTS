// TimelineController /deps 엔드포인트 MockMvc HTTP 통합 테스트 — S1/S2/S3/S4 분별 시드 (FR-TL-02 Task 5)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.TimelineApplicationService
import com.bts.agileplanning.application.TimelineDepsResult
import com.bts.shared.timeline.TimelineDepEdge
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
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
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

/**
 * TimelineController `/deps` 엔드포인트 MockMvc 슬라이스 HTTP 통합 테스트.
 *
 * [TimelineApplicationService] 는 MockK stub 으로 대체한다.
 * Spring Security 컨텍스트는 [SecurityContextHolder] 에 직접 UUID 기반 Authentication 을 주입한다.
 * 전체 부팅을 하지 않아 빠르고 BC 격리가 보장된다.
 *
 * ### 검증 케이스 (양성 + 음성 분별 시드 — vacuous 방지)
 * - S1(양성). GET /api/v1/timeline/deps?project=BTS 정상 → 200 + DataResponse 봉투 + deps/truncated 구조.
 * - S2(음성). project 쿼리 파라미터 누락 → 400.
 * - S3(음성). 비인증 → 401 + 서비스 미호출(존재 probe 차단).
 * - S4(음성). BROWSE 권한 거부 → 403 + AGILE_ACCESS_DENIED.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TimelineControllerDepsTest.TestMvcConfig::class])
@WebAppConfiguration
class TimelineControllerDepsTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [TimelineController] 와 [TimelineExceptionHandler] 를 등록한다.
     * [TimelineApplicationService] 는 MockK stub 빈이다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig : WebMvcConfigurer {
        /**
         * production(Spring Boot) 직렬화와 동일하게 날짜를 ISO 문자열로 내보낸다.
         *
         * @EnableWebMvc 슬라이스는 Spring Boot 의 Jackson 자동설정을 상속하지 않아 LocalDate 가
         * `[2026,7,20]` 배열로 직렬화된다. extendMessageConverters 로 기존 컨버터를 보존한 채
         * JavaTimeModule + 타임스탬프 비활성만 보강한다 — configure 교체 시 에러 봉투 직렬화가 깨진다.
         */
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters.filterIsInstance<MappingJackson2HttpMessageConverter>().forEach {
                it.objectMapper
                    .registerModule(JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }
        }

        @Bean
        open fun timelineApplicationService(): TimelineApplicationService = mockk(relaxed = true)

        @Bean
        open fun timelineController(service: TimelineApplicationService): TimelineController =
            TimelineController(service)

        @Bean
        open fun timelineExceptionHandler(): TimelineExceptionHandler = TimelineExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var timelineApplicationService: TimelineApplicationService

    lateinit var mockMvc: MockMvc

    private val actorId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val projectKey = "BTS"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(timelineApplicationService)
        val auth =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }

    // ── S1(양성): GET /deps 정상 → 200 + 봉투 구조 ───────────────────────────

    @Test
    fun `GET timeline deps 정상이면 200 과 DataResponse 봉투에 deps 와 truncated 를 반환한다`() {
        val result =
            TimelineDepsResult(
                edges =
                    listOf(
                        TimelineDepEdge(blockerKey = "BTS-1", blockedKey = "BTS-2"),
                    ),
                truncated = false,
            )
        every { timelineApplicationService.getDeps(actorId, projectKey) } returns result

        mockMvc.perform(get("/api/v1/timeline/deps").param("project", projectKey))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deps").isArray)
            .andExpect(jsonPath("$.data.deps.length()").value(1))
            .andExpect(jsonPath("$.data.deps[0].blockerKey").value("BTS-1"))
            .andExpect(jsonPath("$.data.deps[0].blockedKey").value("BTS-2"))
            .andExpect(jsonPath("$.data.truncated").value(false))
    }

    // ── S2(음성): project 파라미터 누락 → 400 ────────────────────────────────

    @Test
    fun `project 쿼리 파라미터 없이 호출하면 500 이 아닌 400 을 반환한다`() {
        mockMvc.perform(get("/api/v1/timeline/deps"))
            .andExpect(status().isBadRequest)
    }

    // ── S3(음성): 비인증 → 401 + 서비스 미호출 ──────────────────────────────

    @Test
    fun `비인증이면 401 을 반환하고 서비스가 호출되지 않는다`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/timeline/deps").param("project", projectKey))
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { timelineApplicationService.getDeps(any(), any()) }
    }

    // ── S4(음성): BROWSE 권한 거부 → 403 ─────────────────────────────────────

    @Test
    fun `BROWSE 권한 거부 시 403 과 AGILE_ACCESS_DENIED 를 반환한다`() {
        every {
            timelineApplicationService.getDeps(actorId, projectKey)
        } throws ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")

        mockMvc.perform(get("/api/v1/timeline/deps").param("project", projectKey))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("AGILE_ACCESS_DENIED"))
    }
}
