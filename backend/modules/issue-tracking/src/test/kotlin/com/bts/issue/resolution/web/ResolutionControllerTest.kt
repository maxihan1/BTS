// ResolutionController MockMvc 슬라이스 테스트 — GET /api/v1/resolutions (FR-IS-07 Task B4)

package com.bts.issue.resolution.web

import com.bts.issue.resolution.application.ResolutionApplicationService
import com.bts.issue.resolution.domain.Resolution
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * ResolutionController MockMvc 슬라이스 테스트.
 *
 * [SpringBootApplication] 없이 최소 컨텍스트로 구성한다.
 * [ResolutionApplicationService] 는 MockK stub 으로 대체한다.
 *
 * ### 테스트 케이스
 * - T-1. GET /api/v1/resolutions — 5 표준 Resolution 반환 → 200 + 5건
 * - T-2. 응답 스키마 검증 — id/key/name/description/displayOrder/isStandard 포함, displayOrder asc 정렬
 * - T-3. 빈 목록 반환 → 200 + 빈 배열
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ResolutionControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ResolutionControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [ResolutionController] 와 MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun resolutionApplicationService(): ResolutionApplicationService = mockk(relaxed = true)

        @Bean
        @Suppress("MaxLineLength")
        open fun resolutionController(service: ResolutionApplicationService): ResolutionController = ResolutionController(service)
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var resolutionApplicationService: ResolutionApplicationService

    lateinit var mockMvc: MockMvc

    private val fixedNow: Instant = Instant.parse("2026-06-03T00:00:00Z")

    private val standardResolutions =
        listOf(
            resolution(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "fixed",
                "Fixed",
                "이슈가 정상적으로 수정·해결되었다.",
                1,
            ),
            resolution(
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                "wontfix",
                "Won't Fix",
                "의도적으로 수정하지 않기로 결정하였다.",
                2,
            ),
            resolution(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "duplicate",
                "Duplicate",
                "동일한 내용의 이슈가 이미 존재한다.",
                3,
            ),
            resolution(
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "cannotreproduce",
                "Cannot Reproduce",
                "보고된 현상을 재현할 수 없다.",
                4,
            ),
            resolution(UUID.fromString("55555555-5555-4555-8555-555555555555"), "done", "Done", "작업이 완료되었다.", 5),
        )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    // ── T-1: 5 표준 Resolution 반환 ───────────────────────────────────────────

    @Test
    fun `GET resolutions — 5 표준 Resolution 이 반환되면 200 + 5건`() {
        every { resolutionApplicationService.listActive() } returns standardResolutions

        mockMvc.perform(
            get("/api/v1/resolutions").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(5))
    }

    // ── T-2: 응답 스키마 검증 (displayOrder asc 정렬) ────────────────────────

    @Test
    fun `GET resolutions — 응답 첫 번째 항목이 스키마(id,key,name,description,displayOrder,isStandard)를 만족`() {
        every { resolutionApplicationService.listActive() } returns standardResolutions

        mockMvc.perform(
            get("/api/v1/resolutions").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].id").value("11111111-1111-4111-8111-111111111111"))
            .andExpect(jsonPath("$.data[0].key").value("fixed"))
            .andExpect(jsonPath("$.data[0].name").value("Fixed"))
            .andExpect(jsonPath("$.data[0].description").value("이슈가 정상적으로 수정·해결되었다."))
            .andExpect(jsonPath("$.data[0].displayOrder").value(1))
            .andExpect(jsonPath("$.data[0].isStandard").value(true))
    }

    @Test
    fun `GET resolutions — displayOrder asc 정렬 확인(첫번째 1 마지막 5)`() {
        every { resolutionApplicationService.listActive() } returns standardResolutions

        mockMvc.perform(
            get("/api/v1/resolutions").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].displayOrder").value(1))
            .andExpect(jsonPath("$.data[4].displayOrder").value(5))
    }

    // ── T-3: 빈 목록 반환 ────────────────────────────────────────────────────

    @Test
    fun `GET resolutions — 빈 목록이면 200 + 빈 배열`() {
        every { resolutionApplicationService.listActive() } returns emptyList()

        mockMvc.perform(
            get("/api/v1/resolutions").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun resolution(
        id: UUID,
        key: String,
        name: String,
        description: String?,
        displayOrder: Int,
    ): Resolution =
        Resolution(
            id = id,
            key = key,
            name = name,
            description = description,
            displayOrder = displayOrder,
            isStandard = true,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            deletedAt = null,
        )
}
