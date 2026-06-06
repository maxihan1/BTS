// IssueController securityLevelId JsonNullable 3-state 역직렬화 MockMvc 슬라이스 테스트 — TDD RED 단계

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.SecurityLevelPatch
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.openapitools.jackson.nullable.JsonNullableModule
import org.springframework.beans.factory.annotation.Autowired
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant
import java.util.UUID
import com.bts.issue.application.UpdateIssueRequest as AppUpdateIssueRequest

/**
 * PATCH /api/v1/issues/{key} 의 securityLevelId 3-state(부재/명시 null/값) 역직렬화 검증.
 *
 * Jira Cloud 방식 — JsonNullable<UUID> presence 로 무변경/해제/지정을 구분한다.
 * 컨트롤러가 [JsonNullable] → [SecurityLevelPatch] 로 매핑하는지 캡처 단언으로 검증한다.
 *
 * 케이스.
 * - 필드 부재 → [SecurityLevelPatch.Unchanged].
 * - 명시 null → [SecurityLevelPatch.Clear].
 * - 값 → [SecurityLevelPatch.Assign].
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerSecurityLevelTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueControllerSecurityLevelTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [JsonNullableModule] 을 Bean 으로 등록해 JsonNullable 역직렬화를 활성화한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig : WebMvcConfigurer {
        @org.springframework.context.annotation.Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @org.springframework.context.annotation.Bean
        open fun issueController(svc: IssueApplicationService): IssueController = IssueController(svc)

        @org.springframework.context.annotation.Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()

        /**
         * MockMvc 의 Jackson 컨버터에 [JsonNullableModule] 을 등록한다.
         *
         * 운영에서는 Spring Boot JacksonAutoConfiguration 이 JsonNullableModule Bean 을
         * ObjectMapper 에 자동 등록하지만(JacksonNullableConfiguration), @EnableWebMvc 슬라이스에는
         * 자동 등록 경로가 없으므로 컨버터의 ObjectMapper 에 직접 모듈을 등록한다.
         */
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters
                .filterIsInstance<MappingJackson2HttpMessageConverter>()
                .forEach { it.objectMapper.registerModule(JsonNullableModule()) }
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val fixedNow: Instant = Instant.parse("2026-06-06T00:00:00Z")
    private val issueKey = IssueKey("ATLAS-1")
    private val actorId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    private val sampleResponse =
        IssueResponse(
            key = "ATLAS-1",
            id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            projectKey = "ATLAS",
            summary = "요약",
            currentStateKey = "open",
            reporterId = actorId.value,
            version = 2L,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
        )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun captureRequest(): io.mockk.CapturingSlot<AppUpdateIssueRequest> {
        val captured = slot<AppUpdateIssueRequest>()
        every {
            issueApplicationService.updateIssue(any(), IssueKey("ATLAS-1"), capture(captured))
        } returns sampleResponse
        return captured
    }

    @Test
    fun `securityLevelId 필드 부재면 SecurityLevelPatch_Unchanged`() {
        val captured = captureRequest()
        val body = """{"expectedVersion":1}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk)

        assert(captured.captured.securityLevel is SecurityLevelPatch.Unchanged)
    }

    @Test
    fun `securityLevelId 명시 null 이면 SecurityLevelPatch_Clear`() {
        val captured = captureRequest()
        val body = """{"expectedVersion":1,"securityLevelId":null}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk)

        assert(captured.captured.securityLevel is SecurityLevelPatch.Clear)
    }

    @Test
    fun `securityLevelId 값이면 SecurityLevelPatch_Assign`() {
        val captured = captureRequest()
        val level = "00000000-0000-0000-0000-0000000000aa"
        val body = """{"expectedVersion":1,"securityLevelId":"$level"}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk)

        val patch = captured.captured.securityLevel
        assert(patch is SecurityLevelPatch.Assign)
        assert((patch as SecurityLevelPatch.Assign).levelId == UUID.fromString(level))
    }
}
