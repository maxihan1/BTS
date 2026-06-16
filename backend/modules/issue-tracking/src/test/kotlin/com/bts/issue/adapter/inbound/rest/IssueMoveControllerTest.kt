// IssueMoveController MockMvc 슬라이스 테스트 — FR-MV-01 Task 8 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueMoveRequest
import com.bts.issue.application.IssueMoveService
import com.bts.issue.application.MovePreview
import com.bts.issue.application.MovePreviewService
import com.bts.issue.application.CustomFieldPreviewSection
import com.bts.issue.application.ResourceMappingSection
import com.bts.issue.application.VersionMappingSection
import com.bts.issue.application.WorkflowPreviewSection
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.InvalidTargetMappingException
import com.bts.issue.domain.InvalidTargetStateException
import com.bts.issue.domain.IssueHasSubtasksException
import com.bts.issue.domain.MappingKind
import com.bts.issue.domain.MoveSameProjectException
import com.bts.issue.domain.RequiredFieldMissingException
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * POST /api/v1/issues/{key}/move/preview 와 POST /api/v1/issues/{key}/move MockMvc 슬라이스 테스트.
 *
 * [MovePreviewService] 와 [IssueMoveService] 는 MockK stub 으로 대체하고,
 * [IssueExceptionHandler] 를 등록하여 예외 → HTTP 상태 변환을 검증한다.
 *
 * 테스트 케이스.
 * - MV-P-1. preview 정상 → 200 + MovePreview 응답 DTO
 * - MV-P-2. preview — 이슈 미존재 → 404 ISSUE_NOT_FOUND
 * - MV-P-3. preview — 권한 없음 → 403 ACCESS_DENIED
 * - MV-P-4. preview — body 없음(잘못된 JSON) → 400 VALIDATION_FAILED
 * - MV-P-5. preview — targetProjectKey 빈값 → 400 VALIDATION_FAILED
 * - MV-M-1. move 정상 → 200 + issueKey/previousKey/currentStateKey
 * - MV-M-2. move — OCC 충돌 → 409 VERSION_CONFLICT
 * - MV-M-3. move — 같은 프로젝트 이동 → 422 MOVE_SAME_PROJECT
 * - MV-M-4. move — 서브태스크 존재 → 422 ISSUE_HAS_SUBTASKS
 * - MV-M-5. move — 유효하지 않은 대상 상태 → 422 INVALID_TARGET_STATE
 * - MV-M-6. move — 매핑 대상 미존재 → 422 INVALID_TARGET_MAPPING
 * - MV-M-7. move — 필수 커스텀필드 누락 → 422 REQUIRED_FIELD_MISSING
 * - MV-M-8. move — JSON body 형식 오류 → 400 VALIDATION_FAILED
 * - MV-M-9. move — 권한 없음 → 403 ACCESS_DENIED
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueMoveControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueMoveControllerTest {

    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun movePreviewService(): MovePreviewService = mockk(relaxed = true)

        @Bean
        open fun issueMoveService(): IssueMoveService = mockk(relaxed = true)

        @Bean
        open fun issueMoveController(
            previewService: MovePreviewService,
            moveService: IssueMoveService,
        ): IssueMoveController = IssueMoveController(previewService, moveService)

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var movePreviewService: MovePreviewService

    @Autowired
    lateinit var issueMoveService: IssueMoveService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val actorId = ActorId(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val sourceKey = IssueKey("ATLAS-1")
    private val newKey = IssueKey("DEST-1")

    private val samplePreview = MovePreview(
        workflow = WorkflowPreviewSection(
            compatible = true,
            targetStates = emptyList(),
            suggestedStateKey = "open",
        ),
        components = ResourceMappingSection(
            current = emptyList(),
            target = emptyList(),
            autoMapping = emptyMap(),
        ),
        affectsVersions = VersionMappingSection(
            current = emptyList(),
            target = emptyList(),
            autoMapping = emptyMap(),
        ),
        fixVersions = VersionMappingSection(
            current = emptyList(),
            target = emptyList(),
            autoMapping = emptyMap(),
        ),
        customFields = CustomFieldPreviewSection(
            removed = emptyList(),
            requiredMissing = emptyList(),
        ),
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
        clearMocks(movePreviewService, issueMoveService, answers = false)
        every { movePreviewService.preview(any(), sourceKey, any()) } returns samplePreview
        every { issueMoveService.move(any(), sourceKey, any()) } returns newKey
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── MV-P-1: preview 정상 → 200 + 응답 DTO ───────────────────────────────────

    @Test
    fun `POST move-preview — 정상이면 200 + MovePreview 응답`() {
        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetProjectKey" to "DEST"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.workflow.compatible").value(true))
            .andExpect(jsonPath("$.data.workflow.suggestedStateKey").value("open"))
            .andExpect(jsonPath("$.data.components").exists())
            .andExpect(jsonPath("$.data.customFields").exists())
    }

    // ── MV-P-2: preview — 이슈 미존재 → 404 ────────────────────────────────────

    @Test
    fun `POST move-preview — 이슈 미존재면 404 ISSUE_NOT_FOUND`() {
        every { movePreviewService.preview(any(), sourceKey, any()) } throws IssueNotFoundException(sourceKey)

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetProjectKey" to "DEST"))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_NOT_FOUND"))
    }

    // ── MV-P-3: preview — 권한 없음 → 403 ──────────────────────────────────────

    @Test
    fun `POST move-preview — 권한 없으면 403 ACCESS_DENIED`() {
        every {
            movePreviewService.preview(any(), sourceKey, any())
        } throws IssueAccessDeniedException(actorId, IssuePermission.UPDATE, IssueScope.Project("ATLAS"))

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetProjectKey" to "DEST"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
    }

    // ── MV-P-4: preview — body 없음(HTTP body 미전송) → 400 ─────────────────────

    @Test
    fun `POST move-preview — body 없이 보내면 400 VALIDATION_FAILED`() {
        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move/preview")
                .contentType(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    // ── MV-P-5: preview — targetProjectKey 빈값 → 400 ──────────────────────────

    @Test
    fun `POST move-preview — targetProjectKey 가 빈값이면 400 VALIDATION_FAILED`() {
        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetProjectKey" to ""))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    // ── MV-M-1: move 정상 → 200 + issueKey/previousKey/currentStateKey ───────────

    @Test
    fun `POST move — 정상이면 200 + issueKey, previousKey, currentStateKey`() {
        val body = mapOf(
            "targetProjectKey" to "DEST",
            "expectedVersion" to 1L,
            "targetStateKey" to "open",
            "targetStateIsDone" to false,
            "componentMapping" to emptyMap<String, String?>(),
            "affectsVersionMapping" to emptyMap<String, String?>(),
            "fixVersionMapping" to emptyMap<String, String?>(),
            "customFieldValues" to emptyMap<String, Any?>(),
        )

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.issueKey").value("DEST-1"))
            .andExpect(jsonPath("$.data.previousKey").value("ATLAS-1"))
    }

    // ── MV-M-2: move — OCC 충돌 → 409 VERSION_CONFLICT ─────────────────────────

    @Test
    fun `POST move — OCC 충돌이면 409 VERSION_CONFLICT`() {
        every {
            issueMoveService.move(any(), sourceKey, any())
        } throws IssueVersionConflictException(sourceKey, 2L)

        val body = mapOf(
            "targetProjectKey" to "DEST",
            "expectedVersion" to 1L,
            "targetStateIsDone" to false,
            "componentMapping" to emptyMap<String, String?>(),
            "affectsVersionMapping" to emptyMap<String, String?>(),
            "fixVersionMapping" to emptyMap<String, String?>(),
            "customFieldValues" to emptyMap<String, Any?>(),
        )

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
    }

    // ── MV-M-3: move — 같은 프로젝트 이동 → 422 MOVE_SAME_PROJECT ────────────────

    @Test
    fun `POST move — 같은 프로젝트이면 422 MOVE_SAME_PROJECT`() {
        every {
            issueMoveService.move(any(), sourceKey, any())
        } throws MoveSameProjectException("ATLAS")

        val body = mapOf(
            "targetProjectKey" to "ATLAS",
            "expectedVersion" to 1L,
            "targetStateIsDone" to false,
            "componentMapping" to emptyMap<String, String?>(),
            "affectsVersionMapping" to emptyMap<String, String?>(),
            "fixVersionMapping" to emptyMap<String, String?>(),
            "customFieldValues" to emptyMap<String, Any?>(),
        )

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("MOVE_SAME_PROJECT"))
    }

    // ── MV-M-4: move — 서브태스크 존재 → 422 ISSUE_HAS_SUBTASKS ──────────────────

    @Test
    fun `POST move — 서브태스크 존재하면 422 ISSUE_HAS_SUBTASKS`() {
        every {
            issueMoveService.move(any(), sourceKey, any())
        } throws IssueHasSubtasksException()

        val body = mapOf(
            "targetProjectKey" to "DEST",
            "expectedVersion" to 1L,
            "targetStateIsDone" to false,
            "componentMapping" to emptyMap<String, String?>(),
            "affectsVersionMapping" to emptyMap<String, String?>(),
            "fixVersionMapping" to emptyMap<String, String?>(),
            "customFieldValues" to emptyMap<String, Any?>(),
        )

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("ISSUE_HAS_SUBTASKS"))
    }

    // ── MV-M-5: move — 유효하지 않은 대상 상태 → 422 INVALID_TARGET_STATE ─────────

    @Test
    fun `POST move — 유효하지 않은 대상 상태이면 422 INVALID_TARGET_STATE`() {
        every {
            issueMoveService.move(any(), sourceKey, any())
        } throws InvalidTargetStateException(sourceStatusKey = "open", targetStateKey = null)

        val body = mapOf(
            "targetProjectKey" to "DEST",
            "expectedVersion" to 1L,
            "targetStateIsDone" to false,
            "componentMapping" to emptyMap<String, String?>(),
            "affectsVersionMapping" to emptyMap<String, String?>(),
            "fixVersionMapping" to emptyMap<String, String?>(),
            "customFieldValues" to emptyMap<String, Any?>(),
        )

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("INVALID_TARGET_STATE"))
    }

    // ── MV-M-6: move — 매핑 대상 미존재 → 422 INVALID_TARGET_MAPPING ─────────────

    @Test
    fun `POST move — 매핑 대상 미존재이면 422 INVALID_TARGET_MAPPING`() {
        val unknownId = UUID.fromString("99999999-9999-4999-8999-999999999999")
        every {
            issueMoveService.move(any(), sourceKey, any())
        } throws InvalidTargetMappingException(kind = MappingKind.COMPONENT, unknownIds = setOf(unknownId))

        val body = mapOf(
            "targetProjectKey" to "DEST",
            "expectedVersion" to 1L,
            "targetStateIsDone" to false,
            "componentMapping" to emptyMap<String, String?>(),
            "affectsVersionMapping" to emptyMap<String, String?>(),
            "fixVersionMapping" to emptyMap<String, String?>(),
            "customFieldValues" to emptyMap<String, Any?>(),
        )

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("INVALID_TARGET_MAPPING"))
    }

    // ── MV-M-7: move — 필수 커스텀필드 누락 → 422 REQUIRED_FIELD_MISSING ──────────

    @Test
    fun `POST move — 필수 커스텀필드 누락이면 422 REQUIRED_FIELD_MISSING`() {
        every {
            issueMoveService.move(any(), sourceKey, any())
        } throws RequiredFieldMissingException(missingKeys = setOf("priority"))

        val body = mapOf(
            "targetProjectKey" to "DEST",
            "expectedVersion" to 1L,
            "targetStateIsDone" to false,
            "componentMapping" to emptyMap<String, String?>(),
            "affectsVersionMapping" to emptyMap<String, String?>(),
            "fixVersionMapping" to emptyMap<String, String?>(),
            "customFieldValues" to emptyMap<String, Any?>(),
        )

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("REQUIRED_FIELD_MISSING"))
    }

    // ── MV-M-8: move — JSON body 형식 오류 → 400 VALIDATION_FAILED ──────────────

    @Test
    fun `POST move — JSON body 형식 오류이면 400 VALIDATION_FAILED`() {
        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{bad json}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    // ── MV-M-9: move — 권한 없음 → 403 ACCESS_DENIED ───────────────────────────

    @Test
    fun `POST move — 권한 없으면 403 ACCESS_DENIED`() {
        every {
            issueMoveService.move(any(), sourceKey, any())
        } throws IssueAccessDeniedException(actorId, IssuePermission.UPDATE, IssueScope.Project("ATLAS"))

        val body = mapOf(
            "targetProjectKey" to "DEST",
            "expectedVersion" to 1L,
            "targetStateIsDone" to false,
            "componentMapping" to emptyMap<String, String?>(),
            "affectsVersionMapping" to emptyMap<String, String?>(),
            "fixVersionMapping" to emptyMap<String, String?>(),
            "customFieldValues" to emptyMap<String, Any?>(),
        )

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
    }
}
