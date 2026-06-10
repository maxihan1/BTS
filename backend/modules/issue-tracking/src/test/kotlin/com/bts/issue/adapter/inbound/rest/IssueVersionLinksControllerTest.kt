// PATCH /api/v1/issues/{key}/affects-versions 및 /fix-versions MockMvc 슬라이스 테스트 — FR-VR-03 task-5 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueLinkedVersionNotFoundException
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueVersionConflictException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
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
import java.time.Instant
import java.util.UUID

/**
 * IssueController PATCH /api/v1/issues/{key}/affects-versions 및 /fix-versions MockMvc 슬라이스 테스트.
 *
 * [IssueApplicationService] 는 MockK stub 으로 대체하고 [IssueExceptionHandler] 를 등록하여
 * 예외 → HTTP 상태 변환을 검증한다.
 *
 * 테스트 케이스.
 * - VL-1. affects-versions 정상 → 200 + affectsVersionIds 포함
 * - VL-2. fix-versions 정상 → 200 + fixVersionIds 포함
 * - VL-3. affects-versions IssueLinkedVersionNotFoundException → 422 + ISSUE_LINKED_VERSION_NOT_FOUND
 * - VL-4. fix-versions IssueLinkedVersionNotFoundException → 422 + ISSUE_LINKED_VERSION_NOT_FOUND
 * - VL-5. affects-versions IssueVersionConflictException → 409 + ISSUE_VERSION_CONFLICT
 * - VL-6. affects-versions IssueNotFoundException → 404 + ISSUE_NOT_FOUND
 * - VL-7. affects-versions expectedVersion 누락 → 400 + VALIDATION_FAILED
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueVersionLinksControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueVersionLinksControllerTest {
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun issueController(service: IssueApplicationService): IssueController = IssueController(service)

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val fixedNow: Instant = Instant.parse("2026-06-10T00:00:00Z")
    private val versionId1 = UUID.fromString("00000000-0000-4000-8000-000000000021")
    private val versionId2 = UUID.fromString("00000000-0000-4000-8000-000000000022")
    private val issueKey = IssueKey("ATLAS-1")

    private val affectsSuccessResponse =
        IssueResponse(
            key = "ATLAS-1",
            id = UUID.fromString("00000000-0000-4000-8000-000000000001"),
            projectKey = "ATLAS",
            summary = "테스트 이슈",
            currentStateKey = "open",
            reporterId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            version = 2L,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = 1L,
            typeKey = "task",
            typeName = "Task",
            affectsVersionIds = listOf(versionId1, versionId2),
        )

    private val fixSuccessResponse =
        IssueResponse(
            key = "ATLAS-1",
            id = UUID.fromString("00000000-0000-4000-8000-000000000001"),
            projectKey = "ATLAS",
            summary = "테스트 이슈",
            currentStateKey = "open",
            reporterId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            version = 2L,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = 1L,
            typeKey = "task",
            typeName = "Task",
            fixVersionIds = listOf(versionId1, versionId2),
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
        clearMocks(issueApplicationService, answers = false)
        every { issueApplicationService.changeAffectsVersions(any(), issueKey, any()) } returns affectsSuccessResponse
        every { issueApplicationService.changeFixVersions(any(), issueKey, any()) } returns fixSuccessResponse
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── VL-1: affects-versions 정상 → 200 + affectsVersionIds 포함 ───────────

    @Test
    fun `PATCH affects-versions — 정상이면 200 + affectsVersionIds 포함`() {
        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/affects-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "versionIds" to listOf(versionId1.toString(), versionId2.toString()),
                            "expectedVersion" to 1L,
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value("ATLAS-1"))
            .andExpect(jsonPath("$.data.affectsVersionIds[0]").value(versionId1.toString()))
            .andExpect(jsonPath("$.data.affectsVersionIds[1]").value(versionId2.toString()))
    }

    // ── VL-2: fix-versions 정상 → 200 + fixVersionIds 포함 ──────────────────

    @Test
    fun `PATCH fix-versions — 정상이면 200 + fixVersionIds 포함`() {
        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/fix-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "versionIds" to listOf(versionId1.toString(), versionId2.toString()),
                            "expectedVersion" to 1L,
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value("ATLAS-1"))
            .andExpect(jsonPath("$.data.fixVersionIds[0]").value(versionId1.toString()))
            .andExpect(jsonPath("$.data.fixVersionIds[1]").value(versionId2.toString()))
    }

    // ── VL-3: affects-versions IssueLinkedVersionNotFoundException → 422 ─────

    @Test
    fun `PATCH affects-versions — IssueLinkedVersionNotFoundException 이면 422 ISSUE_LINKED_VERSION_NOT_FOUND`() {
        every {
            issueApplicationService.changeAffectsVersions(any(), issueKey, any())
        } throws IssueLinkedVersionNotFoundException(versionId1)

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/affects-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("versionIds" to listOf(versionId1.toString()), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_LINKED_VERSION_NOT_FOUND"))
    }

    // ── VL-4: fix-versions IssueLinkedVersionNotFoundException → 422 ─────────

    @Test
    fun `PATCH fix-versions — IssueLinkedVersionNotFoundException 이면 422 ISSUE_LINKED_VERSION_NOT_FOUND`() {
        every {
            issueApplicationService.changeFixVersions(any(), issueKey, any())
        } throws IssueLinkedVersionNotFoundException(versionId1)

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/fix-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("versionIds" to listOf(versionId1.toString()), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_LINKED_VERSION_NOT_FOUND"))
    }

    // ── VL-5: IssueVersionConflictException → 409 ────────────────────────────

    @Test
    fun `PATCH affects-versions — IssueVersionConflictException 이면 409 ISSUE_VERSION_CONFLICT`() {
        every {
            issueApplicationService.changeAffectsVersions(any(), issueKey, any())
        } throws IssueVersionConflictException(issueKey, 1L)

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/affects-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("versionIds" to emptyList<String>(), "expectedVersion" to 99L),
                    ),
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
    }

    // ── VL-6: IssueNotFoundException → 404 ───────────────────────────────────

    @Test
    fun `PATCH affects-versions — IssueNotFoundException 이면 404 ISSUE_NOT_FOUND`() {
        every {
            issueApplicationService.changeAffectsVersions(any(), issueKey, any())
        } throws IssueNotFoundException(issueKey)

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/affects-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("versionIds" to emptyList<String>(), "expectedVersion" to 1L),
                    ),
                ),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── VL-7: expectedVersion 누락 → 400 ─────────────────────────────────────

    @Test
    fun `PATCH affects-versions — expectedVersion 누락이면 400 VALIDATION_FAILED`() {
        val body = mapOf("versionIds" to listOf(versionId1.toString()))
        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1/affects-versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }
}
