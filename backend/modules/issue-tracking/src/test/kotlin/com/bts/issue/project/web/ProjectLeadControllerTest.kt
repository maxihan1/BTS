// ProjectLeadController MockMvc 슬라이스 테스트 — PATCH /lead 지정·해제·에러 경로 (FR-CM-04 Task 4)

package com.bts.issue.project.web

import com.bts.issue.project.application.ProjectLeadApplicationService
import com.bts.issue.project.application.ProjectLeadResult
import com.bts.issue.project.domain.ProjectLeadNotFoundException
import com.bts.issue.project.domain.ProjectLeadProjectNotFoundException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * ProjectLeadController MockMvc 슬라이스 테스트.
 *
 * Spring Boot 전체를 띄우지 않고 최소 컨텍스트로 구성한다.
 * [ProjectLeadApplicationService] 는 MockK stub 으로 대체한다.
 *
 * ### 테스트 케이스
 * - S8: PATCH /lead leadUserId 지정 → 200 + {projectId, leadUserId}
 * - S9: PATCH /lead leadUserId null → 200 + {projectId, leadUserId: null}
 * - E1: 미존재 프로젝트 → 404 PROJECT_NOT_FOUND (500 아님)
 * - E2: 미존재 user → 422 PROJECT_LEAD_NOT_FOUND (500 아님)
 * - EC5: 잘못된 UUID body → 400
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ProjectLeadControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ProjectLeadControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [ProjectLeadController], [ProjectLeadExceptionHandler] 와 MockK stub Bean 을 등록한다.
     * `@SpringBootApplication` 없이 최소 컨텍스트로 구성한다.
     * [com.bts.issue.type.web.IssueTypeControllerTest.TestMvcConfig] 선례와 동일한 구조를 따른다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun projectLeadApplicationService(): ProjectLeadApplicationService = mockk(relaxed = true)

        @Bean
        open fun projectLeadController(service: ProjectLeadApplicationService): ProjectLeadController =
            ProjectLeadController(service)

        @Bean
        open fun projectLeadExceptionHandler(): ProjectLeadExceptionHandler = ProjectLeadExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var projectLeadApplicationService: ProjectLeadApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val projectId: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val leadUserId: UUID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    private val projectIdOrKey = "ATLAS"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    // ── S8: leadUserId 지정 → 200 + {projectId, leadUserId} ──────────────────

    @Test
    fun `PATCH lead — leadUserId 지정이면 200 + projectId, leadUserId 반환`() {
        every {
            projectLeadApplicationService.changeLead(any(), eq(projectIdOrKey), eq(leadUserId))
        } returns ProjectLeadResult(projectId = projectId, leadUserId = leadUserId)

        val body = mapOf("leadUserId" to leadUserId.toString())

        mockMvc.perform(
            patch("/api/v1/projects/$projectIdOrKey/lead")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.projectId").value(projectId.toString()))
            .andExpect(jsonPath("$.data.leadUserId").value(leadUserId.toString()))
    }

    // ── S9: leadUserId null → 200, 리드 해제 ────────────────────────────────

    @Test
    fun `PATCH lead — leadUserId null이면 200 + leadUserId null 반환`() {
        every {
            projectLeadApplicationService.changeLead(any(), eq(projectIdOrKey), null)
        } returns ProjectLeadResult(projectId = projectId, leadUserId = null)

        val body = """{"leadUserId": null}"""

        mockMvc.perform(
            patch("/api/v1/projects/$projectIdOrKey/lead")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.projectId").value(projectId.toString()))
            .andExpect(jsonPath("$.data.leadUserId").doesNotExist())
    }

    // ── E1: 미존재 프로젝트 → 404 PROJECT_NOT_FOUND (500 아님) ────────────────

    @Test
    fun `PATCH lead — 미존재 프로젝트이면 404 PROJECT_NOT_FOUND`() {
        every {
            projectLeadApplicationService.changeLead(any(), eq("NOTEXIST"), any())
        } throws ProjectLeadProjectNotFoundException("NOTEXIST")

        val body = mapOf("leadUserId" to leadUserId.toString())

        mockMvc.perform(
            patch("/api/v1/projects/NOTEXIST/lead")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("PROJECT_NOT_FOUND"))
    }

    // ── E2: 미존재 user → 422 PROJECT_LEAD_NOT_FOUND (500 아님) ──────────────

    @Test
    fun `PATCH lead — 미존재 user이면 422 PROJECT_LEAD_NOT_FOUND`() {
        every {
            projectLeadApplicationService.changeLead(any(), eq(projectIdOrKey), eq(leadUserId))
        } throws ProjectLeadNotFoundException(leadUserId)

        val body = mapOf("leadUserId" to leadUserId.toString())

        mockMvc.perform(
            patch("/api/v1/projects/$projectIdOrKey/lead")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("PROJECT_LEAD_NOT_FOUND"))
    }

    // ── EC5: 잘못된 UUID body → 400 ──────────────────────────────────────────

    @Test
    fun `PATCH lead — 잘못된 UUID body이면 400`() {
        val body = """{"leadUserId": "not-a-valid-uuid"}"""

        mockMvc.perform(
            patch("/api/v1/projects/$projectIdOrKey/lead")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
    }
}
