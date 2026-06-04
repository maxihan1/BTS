// LabelController MockMvc 슬라이스 테스트 — GET /api/v1/labels 자동완성 (FR-IS-09 Task 3)

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.LabelApplicationService
import com.bts.issue.domain.ActorId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/**
 * LabelController MockMvc 슬라이스 테스트.
 *
 * [LabelApplicationService]는 MockK stub으로 대체한다.
 * @SpringBootApplication 없이 최소 컨텍스트로 구성한다.
 *
 * ### 테스트 케이스
 * - L-1. GET /api/v1/labels?q=bac → service mock ["backend"] → 200 + {"data":["backend"]}
 * - L-2. GET /api/v1/labels (q 생략) → service.completeLabels(actor, null) 호출 검증
 * - L-3. service가 [] 반환 → 200 + {"data":[]}
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [LabelControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class LabelControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [LabelController]와 MockK stub Bean을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun labelApplicationService(): LabelApplicationService = mockk(relaxed = true)

        @Bean
        open fun labelController(service: LabelApplicationService): LabelController = LabelController(service)
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var labelApplicationService: LabelApplicationService

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    // ── L-1: q 파라미터 있음 → 200 + 결과 목록 ────────────────────────────────

    @Test
    fun `GET labels q=bac — service가 backend 반환하면 200 + data 배열`() {
        every { labelApplicationService.completeLabels(any<ActorId>(), "bac") } returns listOf("backend")

        mockMvc.perform(
            get("/api/v1/labels")
                .param("q", "bac")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0]").value("backend"))
    }

    // ── L-2: q 생략 → service.completeLabels(actor, null) 호출 ───────────────

    @Test
    fun `GET labels q 생략 — service에 null로 위임`() {
        every { labelApplicationService.completeLabels(any<ActorId>(), null) } returns emptyList()

        mockMvc.perform(
            get("/api/v1/labels").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)

        verify(exactly = 1) { labelApplicationService.completeLabels(any<ActorId>(), null) }
    }

    // ── L-3: service가 [] 반환 → 200 + 빈 배열 ──────────────────────────────

    @Test
    fun `GET labels — service가 빈 목록 반환하면 200 + 빈 data 배열`() {
        every { labelApplicationService.completeLabels(any<ActorId>(), any()) } returns emptyList()

        mockMvc.perform(
            get("/api/v1/labels")
                .param("q", "xyz")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(0))
    }
}
