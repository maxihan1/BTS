// IssueMoveController MockMvc 슬라이스 테스트 — FR-MV-01 Task 8 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.CustomFieldPreviewSection
import com.bts.issue.application.IssueMoveService
import com.bts.issue.application.MovePreview
import com.bts.issue.application.MovePreviewService
import com.bts.issue.application.MoveResult
import com.bts.issue.application.ResourceMappingSection
import com.bts.issue.application.VersionMappingSection
import com.bts.issue.application.WorkflowPreviewSection
import com.bts.issue.component.domain.Component
import com.bts.issue.customfield.domain.CustomFieldDefinition
import com.bts.issue.customfield.domain.FieldType
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.InvalidTargetMappingException
import com.bts.issue.domain.InvalidTargetStateException
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueHasSubtasksException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.MappingKind
import com.bts.issue.domain.MoveSameProjectException
import com.bts.issue.domain.RequiredFieldMissingException
import com.bts.issue.version.domain.Version
import com.bts.issue.version.domain.VersionStatus
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.time.LocalDate
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
 * - MV-P-6. preview — Version/Component/CustomField 채워진 응답이 정식 DTO 형태로 직렬화 (C2)
 * - MV-M-1. move 정상 → 200 + issueKey/previousKey
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

    private val samplePreview =
        MovePreview(
            version = 1L,
            workflow =
                WorkflowPreviewSection(
                    compatible = true,
                    targetStates = emptyList(),
                    suggestedStateKey = "open",
                ),
            components =
                ResourceMappingSection(
                    current = emptyList(),
                    target = emptyList(),
                    autoMapping = emptyMap(),
                ),
            affectsVersions =
                VersionMappingSection(
                    current = emptyList(),
                    target = emptyList(),
                    autoMapping = emptyMap(),
                ),
            fixVersions =
                VersionMappingSection(
                    current = emptyList(),
                    target = emptyList(),
                    autoMapping = emptyMap(),
                ),
            customFields =
                CustomFieldPreviewSection(
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
        every { issueMoveService.move(any(), sourceKey, any()) } returns MoveResult(newKey, emptyList())
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

    // ── MV-P-6: preview — 도메인 내부 필드 노출 없이 정식 DTO 직렬화 (C2 RED) ──────

    @Test
    fun `POST move-preview — Version·Component·CustomField 채워진 preview는 deletedAt 미노출·날짜 ISO 직렬화`() {
        val projectId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val versionId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        val componentId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        val fieldId = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")

        val targetVersion =
            Version(
                id = versionId,
                projectId = projectId,
                name = "v1.0",
                description = null,
                startDate = LocalDate.of(2026, 6, 17),
                releaseDate = LocalDate.of(2026, 12, 31),
                status = VersionStatus.UNRELEASED,
                releasedAt = null,
                deletedAt = Instant.parse("2026-06-01T00:00:00Z"),
            )

        val targetComponent =
            Component(
                id = componentId,
                projectId = projectId,
                name = "backend",
                description = null,
                leadUserId = null,
                deletedAt = Instant.parse("2026-05-01T00:00:00Z"),
            )

        val targetField =
            CustomFieldDefinition(
                id = fieldId,
                projectId = projectId,
                key = "priority",
                name = "Priority",
                description = null,
                fieldType = FieldType.SHORT_TEXT,
                required = true,
                displayOrder = 1,
                options = emptyList(),
            )

        val richPreview =
            MovePreview(
                version = 2L,
                workflow =
                    WorkflowPreviewSection(
                        compatible = false,
                        targetStates = emptyList(),
                        suggestedStateKey = null,
                    ),
                components =
                    ResourceMappingSection(
                        current = emptyList(),
                        target = listOf(targetComponent),
                        autoMapping = emptyMap(),
                    ),
                affectsVersions =
                    VersionMappingSection(
                        current = emptyList(),
                        target = listOf(targetVersion),
                        autoMapping = emptyMap(),
                    ),
                fixVersions =
                    VersionMappingSection(
                        current = emptyList(),
                        target = emptyList(),
                        autoMapping = emptyMap(),
                    ),
                customFields =
                    CustomFieldPreviewSection(
                        removed = emptyList(),
                        requiredMissing = listOf(targetField),
                    ),
            )

        every { movePreviewService.preview(any(), sourceKey, any()) } returns richPreview

        mockMvc.perform(
            post("/api/v1/issues/ATLAS-1/move/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetProjectKey" to "DEST"))),
        )
            .andExpect(status().isOk)
            // Version: deletedAt 미노출, startDate ISO "yyyy-MM-dd" 형식
            .andExpect(jsonPath("$.data.affectsVersions.target[0].deletedAt").doesNotExist())
            .andExpect(jsonPath("$.data.affectsVersions.target[0].startDate").value("2026-06-17"))
            // Component: deletedAt 미노출
            .andExpect(jsonPath("$.data.components.target[0].deletedAt").doesNotExist())
            // CustomField: id 존재
            .andExpect(jsonPath("$.data.customFields.requiredMissing[0].id").exists())
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

    // ── MV-P-4: preview — body 없음 → 400 ──────────────────────────────────────

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

    // ── MV-M-1: move 정상 → 200 + issueKey, previousKey ─────────────────────────

    @Test
    fun `POST move — 정상이면 200 + issueKey, previousKey`() {
        val body =
            mapOf(
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

        val body =
            mapOf(
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

        val body =
            mapOf(
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

        val body =
            mapOf(
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

        val body =
            mapOf(
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

        val body =
            mapOf(
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

        val body =
            mapOf(
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

        val body =
            mapOf(
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
