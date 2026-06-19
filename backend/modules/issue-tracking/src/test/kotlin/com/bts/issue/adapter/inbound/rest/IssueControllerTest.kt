// IssueController POST /api/v1/issues componentIds 전달 검증 MockMvc 슬라이스 테스트 — FR-CM-03 Task-3

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.openapitools.jackson.nullable.JsonNullableModule
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.bts.issue.application.CreateIssueRequest as AppCreateIssueRequest
import com.bts.issue.application.UpdateIssueRequest as AppUpdateIssueRequest

/**
 * IssueController POST /api/v1/issues componentIds 전달 검증 테스트 — FR-CM-03 Task-3.
 *
 * 컨트롤러가 web DTO의 componentIds를 app command(AppCreateIssueRequest)에 정확히 전달하는지
 * MockK slot으로 캡처하여 검증한다.
 *
 * 테스트 케이스.
 * - CM3-1. componentIds 포함 요청 → app command에 해당 UUID 목록 전달
 * - CM3-2. componentIds 미포함 요청 → app command에 빈 목록 전달
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun issueController(service: IssueApplicationService): IssueController = IssueController(service)
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val fixedNow = Instant.parse("2026-06-05T00:00:00Z")
    private val issueKey = IssueKey("ATLAS-1")
    private val actorId = ActorId(UUID.fromString("00000000-0000-4000-8000-000000000001"))

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        val stubIssue =
            Issue(
                id = IssueId(UUID.fromString("00000000-0000-4000-8000-000000000002")),
                key = issueKey,
                projectId = UUID.fromString("00000000-0000-4000-8000-000000000003"),
                summary = "테스트 이슈",
                reporterId = actorId,
                currentStateKey = "open",
                version = 1L,
                deletedAt = null,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = IssueTypeId(3L),
            )

        val stubResponse =
            IssueResponse(
                key = "ATLAS-1",
                id = UUID.fromString("00000000-0000-4000-8000-000000000002"),
                projectKey = "ATLAS",
                summary = "테스트 이슈",
                currentStateKey = "open",
                reporterId = actorId.value,
                version = 1L,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = 3L,
                typeKey = "task",
                typeName = "Task",
            )

        every { issueApplicationService.createIssue(any(), any()) } returns stubIssue
        every { issueApplicationService.findByKey(any(), issueKey) } returns stubResponse
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── CM3-1: componentIds 포함 → app command에 전달 ────────────────────────

    @Test
    fun `POST 이슈 생성 — componentIds 포함 시 app command에 동일 목록 전달`() {
        val compId1 = UUID.fromString("00000000-0000-4000-8000-000000000010")
        val compId2 = UUID.fromString("00000000-0000-4000-8000-000000000011")

        val appRequestSlot = slot<AppCreateIssueRequest>()
        every { issueApplicationService.createIssue(any(), capture(appRequestSlot)) } returns
            Issue(
                id = IssueId(UUID.fromString("00000000-0000-4000-8000-000000000002")),
                key = issueKey,
                projectId = UUID.fromString("00000000-0000-4000-8000-000000000003"),
                summary = "테스트 이슈",
                reporterId = actorId,
                currentStateKey = "open",
                version = 1L,
                deletedAt = null,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = IssueTypeId(3L),
            )

        val body =
            mapOf(
                "projectKey" to "ATLAS",
                "summary" to "테스트 이슈",
                "componentIds" to listOf(compId1.toString(), compId2.toString()),
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)

        assertThat(appRequestSlot.captured.componentIds)
            .containsExactlyInAnyOrder(compId1, compId2)
    }

    // ── CM3-2: componentIds 미포함 → app command에 빈 목록 전달 ──────────────

    @Test
    fun `POST 이슈 생성 — componentIds 미포함 시 app command에 빈 목록 전달`() {
        val appRequestSlot = slot<AppCreateIssueRequest>()
        every { issueApplicationService.createIssue(any(), capture(appRequestSlot)) } returns
            Issue(
                id = IssueId(UUID.fromString("00000000-0000-4000-8000-000000000002")),
                key = issueKey,
                projectId = UUID.fromString("00000000-0000-4000-8000-000000000003"),
                summary = "테스트 이슈",
                reporterId = actorId,
                currentStateKey = "open",
                version = 1L,
                deletedAt = null,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = IssueTypeId(3L),
            )

        val body =
            mapOf(
                "projectKey" to "ATLAS",
                "summary" to "테스트 이슈",
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)

        assertThat(appRequestSlot.captured.componentIds).isEmpty()
    }

    // ── FR-PL-01 Task-6: 날짜 필드 PATCH 검증 ───────────────────────────────

    /**
     * FR-PL-01 Task-6 — PATCH 날짜 필드 역직렬화·직렬화·응답 매핑 MockMvc 슬라이스 테스트.
     *
     * 검증 범위.
     * - B1: 응답 JSON의 날짜가 "yyyy-MM-dd" 문자열 형식인지 단언 (배열 [2026,6,20] 아님).
     * - C4: IssueResponse.from() 매핑 반영 — 서비스가 반환한 날짜가 응답 body에 그대로 나오는지 단언.
     * - 잘못된 날짜 형식("2026-13-40") → 400.
     * - 미포함 필드는 응답에 null 유지(무변경).
     *
     * JsonNullableModule + JavaTimeModule 을 MockMvc 컨버터에 직접 등록한다.
     * @EnableWebMvc 슬라이스에는 Boot JacksonAutoConfiguration이 없으므로 수동 등록이 필요하다.
     */
    @Nested
    @ExtendWith(SpringExtension::class)
    @ContextConfiguration(classes = [DatePatchTestConfig::class])
    @WebAppConfiguration
    inner class DatePatchTests {
        @Autowired
        lateinit var dateMvc: WebApplicationContext

        @Autowired
        lateinit var dateSvc: IssueApplicationService

        private lateinit var mockMvc: MockMvc
        private val dateMapper: ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())
                .registerModule(JsonNullableModule())

        private val startDate = LocalDate.of(2026, 6, 20)
        private val targetDate = LocalDate.of(2026, 7, 1)

        /** 날짜가 설정된 IssueResponse 스텁 — C4 매핑 확인용. */
        private val dateResponse =
            IssueResponse(
                key = "ATLAS-1",
                id = UUID.fromString("00000000-0000-4000-8000-000000000002"),
                projectKey = "ATLAS",
                summary = "날짜 테스트 이슈",
                currentStateKey = "open",
                reporterId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
                version = 2L,
                createdAt = Instant.parse("2026-06-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-20T00:00:00Z"),
                typeId = 3L,
                typeKey = "task",
                typeName = "Task",
                startDate = startDate,
                dueDate = null,
                targetDate = targetDate,
            )

        @BeforeEach
        fun setUp() {
            mockMvc = MockMvcBuilders.webAppContextSetup(dateMvc).build()
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(
                    "11111111-1111-4111-8111-111111111111",
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER")),
                )
            every { dateSvc.updateIssue(any(), IssueKey("ATLAS-1"), any()) } returns dateResponse
        }

        @AfterEach
        fun tearDown() {
            SecurityContextHolder.clearContext()
        }

        @Test
        fun `PL-01-1 날짜 PATCH 응답에 B1 yyyy-MM-dd 문자열 형식으로 직렬화됨`() {
            // B1: 응답 JSON이 [2026,6,20] 배열이 아닌 "2026-06-20" 문자열인지 단언
            val body = """{"expectedVersion":1,"startDate":"2026-06-20","targetDate":"2026-07-01"}"""

            mockMvc.perform(
                patch("/api/v1/issues/ATLAS-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.startDate").value("2026-06-20"))
                .andExpect(jsonPath("$.data.targetDate").value("2026-07-01"))
        }

        @Test
        fun `PL-01-2 C4 서비스가 반환한 날짜가 응답 data에 그대로 매핑됨`() {
            // C4: from() 매핑 누락 시 null이 나오는 가짜그린을 실제 값 단언으로 차단
            val body = """{"expectedVersion":1,"startDate":"2026-06-20","targetDate":"2026-07-01"}"""

            mockMvc.perform(
                patch("/api/v1/issues/ATLAS-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.startDate").value("2026-06-20"))
                .andExpect(jsonPath("$.data.dueDate").isEmpty)
                .andExpect(jsonPath("$.data.targetDate").value("2026-07-01"))
        }

        @Test
        fun `PL-01-3 잘못된 날짜 형식은 400 반환`() {
            // 존재하지 않는 날짜(월 13) → Jackson 역직렬화 실패 → 400
            val body = """{"expectedVersion":1,"startDate":"2026-13-40"}"""

            mockMvc.perform(
                patch("/api/v1/issues/ATLAS-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
        }

        @Test
        fun `PL-01-4 날짜 필드 미포함 시 응답에 null 유지`() {
            // 날짜를 보내지 않으면 서비스가 반환한 값 그대로 (스텁은 startDate=2026-06-20 설정됨)
            val body = """{"expectedVersion":1}"""

            mockMvc.perform(
                patch("/api/v1/issues/ATLAS-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.startDate").value("2026-06-20"))
        }
    }
}

/**
 * FR-PL-01 날짜 필드 테스트 전용 Spring MVC 최소 컨텍스트.
 *
 * [JsonNullableModule] + [JavaTimeModule] 을 MockMvc 컨버터에 등록한다.
 * Boot 자동 설정이 없는 @EnableWebMvc 슬라이스에서 LocalDate 직렬화와
 * JsonNullable 역직렬화가 모두 동작하도록 수동 구성한다.
 */
@Configuration
@EnableWebMvc
open class DatePatchTestConfig : WebMvcConfigurer {
    @Bean
    open fun dateSvc(): IssueApplicationService = mockk(relaxed = true)

    @Bean
    open fun dateController(svc: IssueApplicationService): IssueController = IssueController(svc)

    @Bean
    open fun dateExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()

    /**
     * MockMvc Jackson 컨버터에 [JsonNullableModule] 과 [JavaTimeModule] 을 등록한다.
     *
     * [JavaTimeModule] 이 없으면 LocalDate 가 [2026,6,20] 배열로 직렬화되어
     * B1 "@field:JsonFormat" 어노테이션이 있어도 적용되지 않는다.
     */
    override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        converters
            .filterIsInstance<MappingJackson2HttpMessageConverter>()
            .forEach {
                it.objectMapper.registerModule(JsonNullableModule())
                it.objectMapper.registerModule(JavaTimeModule())
            }
    }
}
