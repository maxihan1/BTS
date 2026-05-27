// IssueTypeController GET /api/v1/issue-types MockMvc 슬라이스 테스트 — task-27 RED

package com.bts.issue.type.web

import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.type.domain.IssueType
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
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

/**
 * IssueTypeController GET /api/v1/issue-types MockMvc 슬라이스 테스트.
 *
 * FR-WF-02-10 (IssueType read-only API) 를 검증한다.
 * `@SpringBootApplication` 없이 최소 컨텍스트로 구성한다.
 * [IssueTypeRepository] 는 MockK stub 으로 대체한다.
 *
 * **PR scope (read-only).**
 * 본 PR (FR-WF-02) 에서는 5 표준 타입 read 엔드포인트만 검증한다.
 * 커스텀 IssueType CRUD 테스트는 후속 FR-IS-02 PR scope.
 *
 * 테스트 케이스.
 * - T-1. GET /api/v1/issue-types — 5 표준 타입 반환 → 200 + 5건
 * - T-2. 응답 스키마 검증 — 첫 번째 항목이 id/key/name/description/iconName/isStandard 필드를 모두 포함
 * - T-3. isStandard = true 인 항목만 포함됨을 확인
 * - T-4. 빈 목록 반환 — 200 + 빈 배열
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueTypeControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueTypeControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueTypeController] 와 MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueTypeRepository(): IssueTypeRepository = mockk(relaxed = true)

        @Bean
        open fun issueTypeController(repository: IssueTypeRepository): IssueTypeController =
            IssueTypeController(repository)
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueTypeRepository: IssueTypeRepository

    lateinit var mockMvc: MockMvc

    private val fixedNow: Instant = Instant.parse("2026-05-27T00:00:00Z")

    private val standardTypes = listOf(
        issueType(1L, "epic", "Epic", "대규모 작업 단위. 여러 Story 로 분해된다.", "epic"),
        issueType(2L, "story", "Story", "사용자 스토리.", "story"),
        issueType(3L, "task", "Task", "구체적인 작업 항목.", "task"),
        issueType(4L, "subtask", "Subtask", "Task 를 세분화한 하위 작업 단위.", "subtask"),
        issueType(5L, "bug", "Bug", "시스템 결함 또는 예상치 못한 동작.", "bug"),
    )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    // ── T-1: 5 표준 타입 반환 ─────────────────────────────────────────────────

    @Test
    fun `GET issue-types — 5 표준 타입이 반환되면 200 + 5건`() {
        every { issueTypeRepository.findAll() } returns standardTypes

        mockMvc.perform(
            get("/api/v1/issue-types").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(5))
    }

    // ── T-2: 응답 스키마 검증 ─────────────────────────────────────────────────

    @Test
    fun `GET issue-types — 응답 첫 번째 항목이 스키마(id,key,name,description,iconName,isStandard)를 만족`() {
        every { issueTypeRepository.findAll() } returns standardTypes

        mockMvc.perform(
            get("/api/v1/issue-types").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].id").value(1))
            .andExpect(jsonPath("$.data[0].key").value("epic"))
            .andExpect(jsonPath("$.data[0].name").value("Epic"))
            .andExpect(jsonPath("$.data[0].description").value("대규모 작업 단위. 여러 Story 로 분해된다."))
            .andExpect(jsonPath("$.data[0].iconName").value("epic"))
            .andExpect(jsonPath("$.data[0].isStandard").value(true))
    }

    // ── T-3: isStandard = true 확인 ──────────────────────────────────────────

    @Test
    fun `GET issue-types — 모든 항목의 isStandard 가 true`() {
        every { issueTypeRepository.findAll() } returns standardTypes

        mockMvc.perform(
            get("/api/v1/issue-types").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].isStandard").value(true))
            .andExpect(jsonPath("$.data[1].isStandard").value(true))
            .andExpect(jsonPath("$.data[2].isStandard").value(true))
            .andExpect(jsonPath("$.data[3].isStandard").value(true))
            .andExpect(jsonPath("$.data[4].isStandard").value(true))
    }

    // ── T-4: 빈 목록 반환 ────────────────────────────────────────────────────

    @Test
    fun `GET issue-types — 빈 목록이면 200 + 빈 배열`() {
        every { issueTypeRepository.findAll() } returns emptyList()

        mockMvc.perform(
            get("/api/v1/issue-types").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun issueType(
        id: Long,
        key: String,
        name: String,
        description: String,
        iconName: String,
    ): IssueType =
        IssueType(
            id = IssueTypeId(id),
            key = IssueTypeKey(key),
            name = name,
            description = description,
            iconName = iconName,
            isStandard = true,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            deletedAt = null,
        )
}
