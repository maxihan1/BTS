// IssueMoveController 서브태스크 동반 이동 HTTP 통합 테스트 — FR-MV-01 Task 6

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueMoveService
import com.bts.issue.application.MovePreviewService
import com.bts.issue.application.MoveResult
import com.bts.issue.application.MovedNode
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IncompleteSubtaskMappingException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.SubtaskHasOwnSubtasksException
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
import java.util.UUID

/**
 * IssueMoveController 서브태스크 동반 이동 HTTP 슬라이스 테스트.
 *
 * [IssueMoveControllerTest] 가 단건 경로(#153) 시나리오를 담당하므로,
 * 이 파일은 서브태스크 동반 이동(FR-MV-01 subtask) 신규 시나리오만 검증한다.
 *
 * 검증 시나리오.
 * - CI-1: move 동반 이동 → 200 + movedSubtasks 정확
 * - CI-2: move 다단계(손자 존재) → 422 SUBTASK_HAS_OWN_SUBTASKS
 * - CI-3: move 불완전 매핑(자식 일부 누락) → 422 INCOMPLETE_SUBTASK_MAPPING
 * - CI-4: move 자식 없는 단건 경로 → 200 movedSubtasks 빈 배열(회귀)
 * - CI-5: move subtasks 배열의 issueKey 빈값 → 400 VALIDATION_FAILED
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueMoveControllerIntegrationTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueMoveControllerIntegrationTest {
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
    lateinit var issueMoveService: IssueMoveService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val actorId = ActorId(UUID.fromString("22222222-2222-4222-8222-222222222222"))
    private val srcKey = IssueKey("MSRC-1")
    private val dstKey = IssueKey("MDST-1")
    private val dstChildKey = IssueKey("MDST-2")
    private val srcChildKey = "MSRC-2"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorId.value.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        clearMocks(issueMoveService, answers = false)
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── CI-1: 동반 이동 → 200 + movedSubtasks 정확 ──────────────────────────────

    /**
     * CI-1 — subtasks 포함 move 성공 시 200 + movedSubtasks 배열 반환.
     *
     * Given  issueMoveService.move → MoveResult(루트 새 키, 자식 MovedNode 1개)
     * When   POST /api/v1/issues/MSRC-1/move with subtasks=[MSRC-2]
     * Then   200 + data.issueKey="MDST-1" + data.movedSubtasks[0].previousKey="MSRC-2"
     *              + data.movedSubtasks[0].issueKey="MDST-2"
     */
    @Test
    fun `CI-1 동반 이동 POST move는 200과 movedSubtasks`() {
        every { issueMoveService.move(any(), srcKey, any()) } returns
            MoveResult(
                newKey = dstKey,
                movedSubtasks =
                    listOf(
                        MovedNode(previousKey = IssueKey(srcChildKey), newKey = dstChildKey),
                    ),
            )

        val body =
            buildMoveBody(
                subtasks =
                    listOf(
                        mapOf(
                            "issueKey" to srcChildKey,
                            "expectedVersion" to 1L,
                            "targetStateKey" to null,
                            "targetStateIsDone" to false,
                            "componentMapping" to emptyMap<String, String?>(),
                            "affectsVersionMapping" to emptyMap<String, String?>(),
                            "fixVersionMapping" to emptyMap<String, String?>(),
                            "customFieldValues" to emptyMap<String, Any?>(),
                        ),
                    ),
            )

        mockMvc.perform(
            post("/api/v1/issues/MSRC-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.issueKey").value("MDST-1"))
            .andExpect(jsonPath("$.data.previousKey").value("MSRC-1"))
            .andExpect(jsonPath("$.data.movedSubtasks").isArray)
            .andExpect(jsonPath("$.data.movedSubtasks[0].previousKey").value("MSRC-2"))
            .andExpect(jsonPath("$.data.movedSubtasks[0].issueKey").value("MDST-2"))
    }

    // ── CI-2: 다단계(손자 존재) → 422 SUBTASK_HAS_OWN_SUBTASKS ──────────────────

    /**
     * CI-2 — 자식이 또 자식을 가지면 422 SUBTASK_HAS_OWN_SUBTASKS.
     *
     * Given  issueMoveService.move → SubtaskHasOwnSubtasksException
     * When   POST /api/v1/issues/MSRC-1/move with subtasks=[MSRC-2]
     * Then   422 + errorCode=SUBTASK_HAS_OWN_SUBTASKS
     */
    @Test
    fun `CI-2 다단계는 422 SUBTASK_HAS_OWN_SUBTASKS`() {
        every { issueMoveService.move(any(), srcKey, any()) } throws
            SubtaskHasOwnSubtasksException(childKeys = setOf(srcChildKey))

        val body =
            buildMoveBody(
                subtasks =
                    listOf(
                        mapOf(
                            "issueKey" to srcChildKey,
                            "expectedVersion" to 1L,
                            "targetStateIsDone" to false,
                            "componentMapping" to emptyMap<String, String?>(),
                            "affectsVersionMapping" to emptyMap<String, String?>(),
                            "fixVersionMapping" to emptyMap<String, String?>(),
                            "customFieldValues" to emptyMap<String, Any?>(),
                        ),
                    ),
            )

        mockMvc.perform(
            post("/api/v1/issues/MSRC-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("SUBTASK_HAS_OWN_SUBTASKS"))
    }

    // ── CI-3: 불완전 매핑(자식 일부 누락) → 422 INCOMPLETE_SUBTASK_MAPPING ─────────

    /**
     * CI-3 — 실제 자식 집합과 제공 자식 집합이 다르면 422 INCOMPLETE_SUBTASK_MAPPING.
     *
     * Given  issueMoveService.move → IncompleteSubtaskMappingException
     * When   POST /api/v1/issues/MSRC-1/move with subtasks=[MSRC-2] (MSRC-3 누락)
     * Then   422 + errorCode=INCOMPLETE_SUBTASK_MAPPING
     */
    @Test
    fun `CI-3 불완전 매핑은 422 INCOMPLETE_SUBTASK_MAPPING`() {
        every { issueMoveService.move(any(), srcKey, any()) } throws
            IncompleteSubtaskMappingException(
                expected = setOf("MSRC-2", "MSRC-3"),
                provided = setOf("MSRC-2"),
            )

        val body =
            buildMoveBody(
                subtasks =
                    listOf(
                        mapOf(
                            "issueKey" to srcChildKey,
                            "expectedVersion" to 1L,
                            "targetStateIsDone" to false,
                            "componentMapping" to emptyMap<String, String?>(),
                            "affectsVersionMapping" to emptyMap<String, String?>(),
                            "fixVersionMapping" to emptyMap<String, String?>(),
                            "customFieldValues" to emptyMap<String, Any?>(),
                        ),
                    ),
            )

        mockMvc.perform(
            post("/api/v1/issues/MSRC-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("INCOMPLETE_SUBTASK_MAPPING"))
    }

    // ── CI-4: 자식 없는 단건 경로 → 200 movedSubtasks 빈 배열(회귀) ────────────────

    /**
     * CI-4 — subtasks 빈 배열이면 단건 경로 회귀 보존.
     *
     * Given  issueMoveService.move → MoveResult(새 키, 빈 movedSubtasks)
     * When   POST /api/v1/issues/MSRC-1/move with subtasks=[]
     * Then   200 + data.movedSubtasks = 빈 배열
     */
    @Test
    fun `CI-4 자식 없는 단건 경로 200 movedSubtasks 빈 배열 회귀`() {
        every { issueMoveService.move(any(), srcKey, any()) } returns
            MoveResult(newKey = dstKey, movedSubtasks = emptyList())

        val body = buildMoveBody(subtasks = emptyList())

        mockMvc.perform(
            post("/api/v1/issues/MSRC-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.issueKey").value("MDST-1"))
            .andExpect(jsonPath("$.data.previousKey").value("MSRC-1"))
            .andExpect(jsonPath("$.data.movedSubtasks").isArray)
            .andExpect(jsonPath("$.data.movedSubtasks").isEmpty)
    }

    // ── CI-5: subtasks 배열의 issueKey 빈값 → 400 VALIDATION_FAILED ─────────────

    /**
     * CI-5 — subtasks 배열의 issueKey 가 빈값이면 400 VALIDATION_FAILED.
     *
     * Given  body.subtasks[0].issueKey = ""
     * When   POST /api/v1/issues/MSRC-1/move
     * Then   400 + errorCode=VALIDATION_FAILED
     */
    @Test
    fun `CI-5 subtasks 배열의 issueKey 빈값이면 400 VALIDATION_FAILED`() {
        val body =
            buildMoveBody(
                subtasks =
                    listOf(
                        mapOf(
                            "issueKey" to "",
                            "expectedVersion" to 1L,
                            "targetStateIsDone" to false,
                            "componentMapping" to emptyMap<String, String?>(),
                            "affectsVersionMapping" to emptyMap<String, String?>(),
                            "fixVersionMapping" to emptyMap<String, String?>(),
                            "customFieldValues" to emptyMap<String, Any?>(),
                        ),
                    ),
            )

        mockMvc.perform(
            post("/api/v1/issues/MSRC-1/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    /**
     * move 요청 바디를 빌드하는 헬퍼.
     *
     * @param subtasks 서브태스크 매핑 목록
     */
    private fun buildMoveBody(subtasks: List<Map<String, Any?>>): Map<String, Any?> =
        mapOf(
            "targetProjectKey" to "MDST",
            "expectedVersion" to 1L,
            "targetStateKey" to null,
            "targetStateIsDone" to false,
            "componentMapping" to emptyMap<String, String?>(),
            "affectsVersionMapping" to emptyMap<String, String?>(),
            "fixVersionMapping" to emptyMap<String, String?>(),
            "customFieldValues" to emptyMap<String, Any?>(),
            "subtasks" to subtasks,
        )
}
