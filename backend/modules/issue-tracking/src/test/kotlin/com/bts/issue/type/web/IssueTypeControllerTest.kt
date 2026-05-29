// IssueTypeController MockMvc 슬라이스 테스트 — GET 목록 조회 + POST/PATCH/DELETE CRUD (FR-IS-02)

package com.bts.issue.type.web

import com.bts.issue.type.application.IssueTypeApplicationService
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.domain.IssueTypeInUseException
import com.bts.issue.type.domain.IssueTypeKeyDuplicateException
import com.bts.issue.type.domain.IssueTypeKeyInvalidException
import com.bts.issue.type.domain.IssueTypeNotFoundException
import com.bts.issue.type.domain.IssueTypeReassignTargetInvalidException
import com.bts.issue.type.domain.IssueTypeStandardImmutableException
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant

/**
 * IssueTypeController MockMvc 슬라이스 테스트.
 *
 * FR-WF-02-10 GET read-only + FR-IS-02 CRUD 엔드포인트를 검증한다.
 * `@SpringBootApplication` 없이 최소 컨텍스트로 구성한다.
 * [IssueTypeApplicationService] 와 [IssueTypeRepository] 는 MockK stub 으로 대체한다.
 *
 * ### GET 테스트 케이스 (기존)
 * - T-1. GET /api/v1/issue-types — 5 표준 타입 반환 → 200 + 5건
 * - T-2. 응답 스키마 검증 — id/key/name/description/iconName/isStandard/hierarchyLevel 포함 (G3)
 * - T-3. isStandard = true 인 항목만 포함됨을 확인
 * - T-4. 빈 목록 반환 → 200 + 빈 배열
 *
 * ### POST 테스트 케이스
 * - C-1. 정상 입력 → 201 + IssueTypeResponse body
 * - C-2. key blank → 400 VALIDATION_FAILED
 * - C-3. name blank → 400 VALIDATION_FAILED
 * - C-4. key 중복 → 409 ISSUE_TYPE_KEY_DUPLICATE
 * - C-5. key 형식 위반 → 409 ISSUE_TYPE_KEY_INVALID
 *
 * ### PATCH 테스트 케이스
 * - U-1. 정상 수정 → 200
 * - U-2. 미존재 id → 404 ISSUE_TYPE_NOT_FOUND
 * - U-3. 표준 타입 수정 시도 → 409 ISSUE_TYPE_STANDARD_IMMUTABLE
 *
 * ### DELETE 테스트 케이스
 * - D-1. 미사용 타입 삭제 → 204
 * - D-2. reassignTo 지정 삭제 → 204
 * - D-3. 표준 타입 삭제 시도 → 409 ISSUE_TYPE_STANDARD_IMMUTABLE
 * - D-4. 사용중 + reassignTo 없음 → 409 ISSUE_TYPE_IN_USE (usageCount + schemeMappingCount 별도 필드)
 * - D-5. schemeMappingCount > 0 → 409 ISSUE_TYPE_IN_USE (reassignTo 로 해소 불가 안내)
 * - D-6. reassignTo 무효 → 409 ISSUE_TYPE_REASSIGN_TARGET_INVALID
 * - D-7. 미존재 id → 404 ISSUE_TYPE_NOT_FOUND
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueTypeControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueTypeControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueTypeController], [IssueTypeExceptionHandler] 와 MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueTypeRepository(): IssueTypeRepository = mockk(relaxed = true)

        @Bean
        open fun issueTypeApplicationService(): IssueTypeApplicationService = mockk(relaxed = true)

        @Bean
        open fun issueTypeController(
            repository: IssueTypeRepository,
            service: IssueTypeApplicationService,
        ): IssueTypeController = IssueTypeController(repository, service)

        @Bean
        open fun issueTypeExceptionHandler(): IssueTypeExceptionHandler = IssueTypeExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueTypeRepository: IssueTypeRepository

    @Autowired
    lateinit var issueTypeApplicationService: IssueTypeApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val fixedNow: Instant = Instant.parse("2026-05-27T00:00:00Z")

    private val standardTypes =
        listOf(
            issueType(1L, "epic", "Epic", "대규모 작업 단위. 여러 Story 로 분해된다.", "epic", hierarchyLevel = 1),
            issueType(2L, "story", "Story", "사용자 스토리.", "story"),
            issueType(3L, "task", "Task", "구체적인 작업 항목.", "task"),
            issueType(4L, "subtask", "Subtask", "Task 를 세분화한 하위 작업 단위.", "subtask", hierarchyLevel = -1),
            issueType(5L, "bug", "Bug", "시스템 결함 또는 예상치 못한 동작.", "bug"),
        )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    // ── GET T-1: 5 표준 타입 반환 ─────────────────────────────────────────────

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

    // ── GET T-2: 응답 스키마 검증 (G3 — hierarchyLevel 포함) ─────────────────

    @Test
    fun `GET issue-types — 응답 첫 번째 항목이 스키마(id,key,name,description,iconName,isStandard,hierarchyLevel)를 만족`() {
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
            .andExpect(jsonPath("$.data[0].hierarchyLevel").value(1))
    }

    // ── GET T-3: isStandard = true 확인 ──────────────────────────────────────

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

    // ── GET T-4: 빈 목록 반환 ────────────────────────────────────────────────

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

    // ── POST C-1: 정상 생성 → 201 ─────────────────────────────────────────────

    @Test
    fun `POST issue-types — 정상 입력이면 201 + IssueTypeResponse`() {
        val created = issueType(10L, "feature", "Feature", "기능 개발 타입", "feature")
        every { issueTypeApplicationService.create(any()) } returns created

        val body =
            mapOf(
                "key" to "feature",
                "name" to "Feature",
                "description" to "기능 개발 타입",
                "iconName" to "feature",
                "hierarchyLevel" to 0,
            )

        mockMvc.perform(
            post("/api/v1/issue-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(10))
            .andExpect(jsonPath("$.data.key").value("feature"))
            .andExpect(jsonPath("$.data.hierarchyLevel").value(0))
    }

    // ── POST C-2: key blank → 400 ─────────────────────────────────────────────

    @Test
    fun `POST issue-types — key blank 이면 400`() {
        val body = mapOf("key" to "", "name" to "Feature", "hierarchyLevel" to 0)

        mockMvc.perform(
            post("/api/v1/issue-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── POST C-3: name blank → 400 ────────────────────────────────────────────

    @Test
    fun `POST issue-types — name blank 이면 400`() {
        val body = mapOf("key" to "feature", "name" to "", "hierarchyLevel" to 0)

        mockMvc.perform(
            post("/api/v1/issue-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── POST C-4: key 중복 → 409 ISSUE_TYPE_KEY_DUPLICATE ────────────────────

    @Test
    fun `POST issue-types — key 중복이면 409 ISSUE_TYPE_KEY_DUPLICATE`() {
        every { issueTypeApplicationService.create(any()) } throws
            IssueTypeKeyDuplicateException(IssueTypeKey("feature"))

        val body = mapOf("key" to "feature", "name" to "Feature", "hierarchyLevel" to 0)

        mockMvc.perform(
            post("/api/v1/issue-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_TYPE_KEY_DUPLICATE"))
    }

    // ── POST C-5: key 형식 위반 → 409 ISSUE_TYPE_KEY_INVALID ─────────────────

    @Test
    fun `POST issue-types — key 형식 위반이면 409 ISSUE_TYPE_KEY_INVALID`() {
        every { issueTypeApplicationService.create(any()) } throws
            IssueTypeKeyInvalidException(IssueTypeKey("in"))

        val body = mapOf("key" to "INVALID KEY!", "name" to "Feature", "hierarchyLevel" to 0)

        mockMvc.perform(
            post("/api/v1/issue-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_TYPE_KEY_INVALID"))
    }

    // ── PATCH U-1: 정상 수정 → 200 ────────────────────────────────────────────

    @Test
    fun `PATCH issue-types id — 정상 수정이면 200`() {
        val updated = issueType(10L, "feature", "Feature Updated", null, null)
        // relaxed mock — update()는 Unit 반환이므로 별도 stub 불필요
        every { issueTypeRepository.findById(IssueTypeId(10L)) } returns updated

        val body = mapOf("name" to "Feature Updated", "hierarchyLevel" to 0)

        mockMvc.perform(
            patch("/api/v1/issue-types/10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("Feature Updated"))
    }

    // ── PATCH U-2: 미존재 id → 404 ISSUE_TYPE_NOT_FOUND ──────────────────────

    @Test
    fun `PATCH issue-types id — 미존재 id이면 404 ISSUE_TYPE_NOT_FOUND`() {
        every { issueTypeApplicationService.update(IssueTypeId(999L), any()) } throws
            IssueTypeNotFoundException(IssueTypeId(999L))

        val body = mapOf("name" to "Feature Updated", "hierarchyLevel" to 0)

        mockMvc.perform(
            patch("/api/v1/issue-types/999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_TYPE_NOT_FOUND"))
    }

    // ── PATCH U-3: 표준 타입 수정 시도 → 409 ISSUE_TYPE_STANDARD_IMMUTABLE ────

    @Test
    fun `PATCH issue-types id — 표준 타입이면 409 ISSUE_TYPE_STANDARD_IMMUTABLE`() {
        every { issueTypeApplicationService.update(IssueTypeId(1L), any()) } throws
            IssueTypeStandardImmutableException(typeId = IssueTypeId(1L), key = null)

        val body = mapOf("name" to "Epic Modified", "hierarchyLevel" to 1)

        mockMvc.perform(
            patch("/api/v1/issue-types/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_TYPE_STANDARD_IMMUTABLE"))
    }

    // ── DELETE D-1: 미사용 타입 삭제 → 204 ────────────────────────────────────

    @Test
    fun `DELETE issue-types id — 미사용이면 204`() {
        // relaxed mock — delete()는 Unit 반환이므로 별도 stub 불필요

        mockMvc.perform(delete("/api/v1/issue-types/10"))
            .andExpect(status().isNoContent)
    }

    // ── DELETE D-2: reassignTo 지정 삭제 → 204 ────────────────────────────────

    @Test
    fun `DELETE issue-types id reassignTo — reassignTo 지정이면 204`() {
        // relaxed mock — delete()는 Unit 반환이므로 별도 stub 불필요

        mockMvc.perform(delete("/api/v1/issue-types/10").param("reassignTo", "3"))
            .andExpect(status().isNoContent)
    }

    // ── DELETE D-3: 표준 타입 삭제 시도 → 409 ISSUE_TYPE_STANDARD_IMMUTABLE ───

    @Test
    fun `DELETE issue-types id — 표준 타입이면 409 ISSUE_TYPE_STANDARD_IMMUTABLE`() {
        every { issueTypeApplicationService.delete(IssueTypeId(1L), null) } throws
            IssueTypeStandardImmutableException(typeId = IssueTypeId(1L), key = null)

        mockMvc.perform(delete("/api/v1/issue-types/1"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_TYPE_STANDARD_IMMUTABLE"))
    }

    // ── DELETE D-4: 사용중 + reassignTo 없음 → 409 ISSUE_TYPE_IN_USE (C3) ────

    @Test
    fun `DELETE issue-types id — 사용중이고 reassignTo 없으면 409 ISSUE_TYPE_IN_USE + usageCount + schemeMappingCount`() {
        every { issueTypeApplicationService.delete(IssueTypeId(10L), null) } throws
            IssueTypeInUseException(usageCount = 5L, schemeMappingCount = 0L)

        mockMvc.perform(delete("/api/v1/issue-types/10"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_TYPE_IN_USE"))
            .andExpect(jsonPath("$.usageCount").value(5))
            .andExpect(jsonPath("$.schemeMappingCount").value(0))
    }

    // ── DELETE D-5: schemeMappingCount > 0 → 409 (reassignTo 로 해소 불가) ────

    @Test
    fun `DELETE issue-types id — schemeMappingCount 가 양수이면 409 ISSUE_TYPE_IN_USE + reassignTo 불가 안내`() {
        every { issueTypeApplicationService.delete(IssueTypeId(10L), null) } throws
            IssueTypeInUseException(usageCount = 2L, schemeMappingCount = 3L)

        mockMvc.perform(delete("/api/v1/issue-types/10"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_TYPE_IN_USE"))
            .andExpect(jsonPath("$.usageCount").value(2))
            .andExpect(jsonPath("$.schemeMappingCount").value(3))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("reassignTo")))
    }

    // ── DELETE D-6: reassignTo 무효 → 409 ISSUE_TYPE_REASSIGN_TARGET_INVALID ──

    @Test
    fun `DELETE issue-types id reassignTo — reassignTo 무효이면 409 ISSUE_TYPE_REASSIGN_TARGET_INVALID`() {
        every { issueTypeApplicationService.delete(IssueTypeId(10L), IssueTypeId(999L)) } throws
            IssueTypeReassignTargetInvalidException(
                targetId = IssueTypeId(999L),
                reason = "재할당 대상 이슈 타입이 존재하지 않거나 삭제되었습니다",
            )

        mockMvc.perform(delete("/api/v1/issue-types/10").param("reassignTo", "999"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_TYPE_REASSIGN_TARGET_INVALID"))
    }

    // ── DELETE D-7: 미존재 id → 404 ISSUE_TYPE_NOT_FOUND ─────────────────────

    @Test
    fun `DELETE issue-types id — 미존재 id이면 404 ISSUE_TYPE_NOT_FOUND`() {
        every { issueTypeApplicationService.delete(IssueTypeId(999L), null) } throws
            IssueTypeNotFoundException(IssueTypeId(999L))

        mockMvc.perform(delete("/api/v1/issue-types/999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_TYPE_NOT_FOUND"))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun issueType(
        id: Long,
        key: String,
        name: String,
        description: String?,
        iconName: String?,
        hierarchyLevel: Int = 0,
    ): IssueType =
        IssueType(
            id = IssueTypeId(id),
            key = IssueTypeKey(key),
            name = name,
            description = description,
            iconName = iconName,
            isStandard = true,
            hierarchyLevel = hierarchyLevel,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            deletedAt = null,
        )
}
