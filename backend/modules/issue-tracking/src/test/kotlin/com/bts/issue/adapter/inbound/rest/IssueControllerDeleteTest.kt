// IssueController DELETE /api/v1/issues/{key} MockMvc 슬라이스 테스트 — task-16 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/**
 * IssueController DELETE /api/v1/issues/{key} MockMvc 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 최소 컨텍스트로 구성한다.
 * [IssueApplicationService] 는 MockK stub 으로 대체한다.
 * [IssueExceptionHandler] 를 컨텍스트에 등록하여 예외 → ProblemDetail 변환을 검증한다.
 *
 * 테스트 케이스 2건.
 * - D-1. DELETE /{key} 정상 → 204 No Content (body 없음)
 * - D-2. DELETE /{key} 미존재 → 404 + ProblemDetail (ISSUE_NOT_FOUND)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerDeleteTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueControllerDeleteTest {

    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueController], [IssueExceptionHandler], MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun issueController(service: IssueApplicationService): IssueController =
            IssueController(service)

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    // ── D-1: DELETE /{key} 정상 → 204 No Content ─────────────────────────────

    @Test
    fun `DELETE 이슈 삭제 — 정상 요청이면 204 No Content`() {
        justRun { issueApplicationService.softDeleteIssue(any(), IssueKey("ATLAS-1")) }

        mockMvc.perform(delete("/api/v1/issues/ATLAS-1"))
            .andExpect(status().isNoContent)
    }

    // ── D-2: DELETE /{key} 미존재 → 404 ProblemDetail ────────────────────────

    @Test
    fun `DELETE 이슈 삭제 — 이슈가 없으면 404 ProblemDetail ISSUE_NOT_FOUND`() {
        every {
            issueApplicationService.softDeleteIssue(any(), IssueKey("ATLAS-999"))
        } throws IssueNotFoundException(IssueKey("ATLAS-999"))

        mockMvc.perform(delete("/api/v1/issues/ATLAS-999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }
}
