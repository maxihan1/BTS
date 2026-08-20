// BulkOperationController MockMvc 슬라이스 테스트 — POST 접수 202 / GET 조회 200·403·404 / POST 가용 전환 200·400

package com.bts.issue.bulk.web

import com.bts.issue.bulk.application.BulkAvailableTransitionsResult
import com.bts.issue.bulk.application.BulkAvailableTransitionsService
import com.bts.issue.bulk.application.BulkOperationApplicationService
import com.bts.issue.bulk.application.BulkUpdateRequest
import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.BulkOperationStatus
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.IssueKey
import com.bts.shared.workflow.AvailableTransitionView
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * BulkOperationController MockMvc 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다.
 * [BulkOperationApplicationService], [BulkOperationRepository], [BulkAvailableTransitionsService] 는 MockK stub 으로 대체한다.
 *
 * 테스트 케이스 12건.
 * - P-1. POST 정상(BULK_EDIT) → 202 + {bulkOperationId, status:"PENDING", totalCount}
 * - P-2. POST issueKeys 빈 목록 → service가 IllegalArgumentException → 400
 * - P-3. POST issueKeys 1000 초과 → service가 IllegalArgumentException → 400
 * - P-4. POST operationType 미허용 enum 문자열 → HttpMessageNotReadableException → 400 + 계약 형태
 * - P-5. POST 빈 본문 {} (필수 필드 누락) → MethodArgumentNotValidException → 400 + 계약 형태
 * - G-1. GET 작업 본인 actor → 200 + BulkOperationResponse
 * - G-2. GET 타인 actor → 403
 * - G-3. GET 없는 id → 404
 * - AT-1. POST bulk-transitions/available 정상 → 200 + data.transitions(TransitionItem 형태) + data.unresolvedIssueKeys
 * - AT-2. POST bulk-transitions/available 교집합 전환 없음 → 200 + data.transitions 빈 배열
 * - AT-3. POST bulk-transitions/available issueKeys 빈 배열 → 400 + ISSUE_BULK_VALIDATION_FAILED
 * - AT-4. POST bulk-transitions/available issueKeys 1000 초과 → 400 + ISSUE_BULK_VALIDATION_FAILED
 * - AT-5. POST bulk-transitions/available 응답 항목이 transitionId·kind 를 싣는다 (ADR 2026-08-18 §D3)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [BulkOperationControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class BulkOperationControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [BulkOperationController] 와 MockK stub Bean 을 등록한다.
     * [BulkOperationExceptionHandler] 도 등록하여 IllegalArgumentException→400 매핑을 검증한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun bulkOperationApplicationService(): BulkOperationApplicationService = mockk(relaxed = true)

        @Bean
        open fun bulkOperationRepository(): BulkOperationRepository = mockk(relaxed = true)

        @Bean
        open fun bulkAvailableTransitionsService(): BulkAvailableTransitionsService = mockk(relaxed = true)

        @Bean
        open fun bulkOperationController(
            service: BulkOperationApplicationService,
            repo: BulkOperationRepository,
            bulkAvailableTransitionsService: BulkAvailableTransitionsService,
        ): BulkOperationController = BulkOperationController(service, repo, bulkAvailableTransitionsService)

        @Bean
        open fun bulkOperationExceptionHandler(): BulkOperationExceptionHandler = BulkOperationExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var bulkOperationApplicationService: BulkOperationApplicationService

    @Autowired
    lateinit var bulkOperationRepository: BulkOperationRepository

    @Autowired
    lateinit var bulkAvailableTransitionsService: BulkAvailableTransitionsService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** 테스트에서 공통으로 사용하는 작업 actor UUID (SYSTEM_ACTOR_UUID와 동일). */
    private val actorUuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    /** 다른 actor UUID — GET 403 검증용. */
    private val otherActorUuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000099")

    /** 테스트용 작업 UUID. */
    private val operationUuid: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-000000000001")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    // ── P-1: POST 정상 → 202 + {bulkOperationId, status, totalCount} ──────────

    @Test
    fun `POST bulk-update — BULK_EDIT 정상 요청이면 202 + 접수 응답 반환`() {
        val returnedId = BulkOperationId(operationUuid)
        every { bulkOperationApplicationService.submit(any(), any<BulkUpdateRequest>()) } returns returnedId

        val body =
            mapOf(
                "operationType" to "BULK_EDIT",
                "issueKeys" to listOf("ATLAS-1", "ATLAS-2"),
                "editPayload" to mapOf("priority" to 3),
                "transitionPayload" to null,
            )

        mockMvc.perform(
            post("/api/v1/issues/bulk-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.data.bulkOperationId").value(operationUuid.toString()))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.totalCount").value(2))
    }

    // ── P-2: POST issueKeys 빈 목록 → 400 ────────────────────────────────────

    @Test
    fun `POST bulk-update — issueKeys 빈 목록이면 service가 IllegalArgumentException → 400`() {
        every {
            bulkOperationApplicationService.submit(any(), any<BulkUpdateRequest>())
        } throws IllegalArgumentException("issueKeys must not be empty")

        val body =
            mapOf(
                "operationType" to "BULK_EDIT",
                "issueKeys" to emptyList<String>(),
                "editPayload" to mapOf("priority" to 3),
                "transitionPayload" to null,
            )

        mockMvc.perform(
            post("/api/v1/issues/bulk-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── P-3: POST issueKeys 1000 초과 → 400 ──────────────────────────────────

    @Test
    fun `POST bulk-update — issueKeys 1000 초과이면 service가 IllegalArgumentException → 400`() {
        every {
            bulkOperationApplicationService.submit(any(), any<BulkUpdateRequest>())
        } throws IllegalArgumentException("issueKeys must not exceed 1000")

        val body =
            mapOf(
                "operationType" to "BULK_EDIT",
                "issueKeys" to (1..1001).map { "ATLAS-$it" },
                "editPayload" to mapOf("priority" to 3),
                "transitionPayload" to null,
            )

        mockMvc.perform(
            post("/api/v1/issues/bulk-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── P-4: POST operationType 미허용 enum 문자열 → 400 ─────────────────────

    @Test
    fun `POST bulk-update — operationType이 미허용 enum 문자열이면 400 + 계약 형태 응답`() {
        val body =
            mapOf(
                "operationType" to "GARBAGE",
                "issueKeys" to listOf("ATLAS-1"),
                "editPayload" to null,
                "transitionPayload" to null,
            )

        mockMvc.perform(
            post("/api/v1/issues/bulk-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value(BulkErrorCodes.VALIDATION_FAILED))
            .andExpect(jsonPath("$.timestamp").exists())
    }

    // ── P-5: POST 빈 본문 {} (필수 필드 누락) → 400 ──────────────────────────

    @Test
    fun `POST bulk-update — 빈 본문 이면 MethodArgumentNotValidException → 400 + 계약 형태 응답`() {
        mockMvc.perform(
            post("/api/v1/issues/bulk-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value(BulkErrorCodes.VALIDATION_FAILED))
            .andExpect(jsonPath("$.timestamp").exists())
    }

    // ── G-1: GET 작업 본인 actor → 200 + BulkOperationResponse ───────────────

    @Test
    fun `GET bulk-operations 작업 id — 본인 actor이면 200 + BulkOperationResponse`() {
        val fixedNow = Instant.parse("2026-06-02T00:00:00Z")
        val operation =
            BulkOperation(
                id = BulkOperationId(operationUuid),
                actorId = actorUuid,
                type = BulkOperationType.BULK_EDIT,
                status = BulkOperationStatus.PENDING,
                payload = BulkOperationPayload.Edit(priority = 3, impact = null),
                items = emptyList(),
                totalCount = 2,
                processedCount = 0,
                succeededCount = 0,
                failedCount = 0,
                createdAt = fixedNow,
                updatedAt = fixedNow,
            )
        val items =
            listOf(
                BulkOperationItem(issueKey = IssueKey("ATLAS-1"), status = ItemStatus.PENDING),
                BulkOperationItem(issueKey = IssueKey("ATLAS-2"), status = ItemStatus.PENDING),
            )

        every { bulkOperationRepository.findById(BulkOperationId(operationUuid)) } returns operation
        every { bulkOperationRepository.findItemsByOperationId(BulkOperationId(operationUuid)) } returns items

        mockMvc.perform(
            get("/api/v1/bulk-operations/$operationUuid"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(operationUuid.toString()))
            .andExpect(jsonPath("$.data.operationType").value("BULK_EDIT"))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.totalCount").value(2))
            .andExpect(jsonPath("$.data.processedCount").value(0))
            .andExpect(jsonPath("$.data.succeededCount").value(0))
            .andExpect(jsonPath("$.data.failedCount").value(0))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].issueKey").value("ATLAS-1"))
            .andExpect(jsonPath("$.data.items[0].status").value("PENDING"))
            .andExpect(jsonPath("$.data.payload.priority").value(3))
    }

    // ── G-2: GET 타인 actor → 403 ────────────────────────────────────────────

    @Test
    fun `GET bulk-operations 작업 id — 타인 actor이면 403`() {
        val fixedNow = Instant.parse("2026-06-02T00:00:00Z")
        // operation.actorId 는 otherActorUuid — SYSTEM_ACTOR_UUID(actorUuid) 와 다름.
        val operation =
            BulkOperation(
                id = BulkOperationId(operationUuid),
                actorId = otherActorUuid,
                type = BulkOperationType.BULK_EDIT,
                status = BulkOperationStatus.PENDING,
                payload = BulkOperationPayload.Edit(priority = 3, impact = null),
                items = emptyList(),
                totalCount = 2,
                processedCount = 0,
                succeededCount = 0,
                failedCount = 0,
                createdAt = fixedNow,
                updatedAt = fixedNow,
            )

        every { bulkOperationRepository.findById(BulkOperationId(operationUuid)) } returns operation

        mockMvc.perform(
            get("/api/v1/bulk-operations/$operationUuid"),
        )
            .andExpect(status().isForbidden)
    }

    // ── G-3: GET 없는 id → 404 ───────────────────────────────────────────────

    @Test
    fun `GET bulk-operations 작업 id — 없는 id이면 404`() {
        val missingUuid = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
        every { bulkOperationRepository.findById(BulkOperationId(missingUuid)) } returns null

        mockMvc.perform(
            get("/api/v1/bulk-operations/$missingUuid"),
        )
            .andExpect(status().isNotFound)
    }

    // ── AT-1: POST bulk-transitions/available 정상 → 200 + data.transitions + unresolvedIssueKeys ──

    @Test
    fun `POST bulk-transitions available — 정상 요청이면 200 + 교집합 전환 목록과 unresolved 반환`() {
        val transitionView =
            AvailableTransitionView(
                fromStateKey = "open",
                toStateKey = "in_progress",
                name = "시작",
            )
        val result =
            BulkAvailableTransitionsResult(
                transitions = listOf(transitionView),
                unresolvedIssueKeys = listOf("ATLAS-99"),
            )
        every { bulkAvailableTransitionsService.availableCommonTransitions(any(), any()) } returns result

        val body = mapOf("issueKeys" to listOf("ATLAS-1", "ATLAS-3", "ATLAS-99"))

        mockMvc.perform(
            post("/api/v1/issues/bulk-transitions/available")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.transitions.length()").value(1))
            .andExpect(jsonPath("$.data.transitions[0].fromStateKey").value("open"))
            .andExpect(jsonPath("$.data.transitions[0].toStateKey").value("in_progress"))
            .andExpect(jsonPath("$.data.transitions[0].name").value("시작"))
            .andExpect(jsonPath("$.data.transitions[0].key").value("open__in_progress"))
            .andExpect(jsonPath("$.data.unresolvedIssueKeys.length()").value(1))
            .andExpect(jsonPath("$.data.unresolvedIssueKeys[0]").value("ATLAS-99"))
    }

    // ── AT-2: POST bulk-transitions/available 교집합 없음 → 200 + 빈 transitions ───────────────

    @Test
    fun `POST bulk-transitions available — 교집합 전환 없으면 200 + 빈 transitions`() {
        val result =
            BulkAvailableTransitionsResult(
                transitions = emptyList(),
                unresolvedIssueKeys = emptyList(),
            )
        every { bulkAvailableTransitionsService.availableCommonTransitions(any(), any()) } returns result

        val body = mapOf("issueKeys" to listOf("ATLAS-1", "ATLAS-3"))

        mockMvc.perform(
            post("/api/v1/issues/bulk-transitions/available")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.transitions.length()").value(0))
            .andExpect(jsonPath("$.data.unresolvedIssueKeys.length()").value(0))
    }

    // ── AT-3: POST bulk-transitions/available issueKeys 빈 배열 → 400 + ISSUE_BULK_VALIDATION_FAILED ──

    @Test
    fun `POST bulk-transitions available — issueKeys 빈 배열이면 400 + ISSUE_BULK_VALIDATION_FAILED`() {
        val body = mapOf("issueKeys" to emptyList<String>())

        mockMvc.perform(
            post("/api/v1/issues/bulk-transitions/available")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value(BulkErrorCodes.VALIDATION_FAILED))
    }

    // ── AT-4: POST bulk-transitions/available issueKeys 1000 초과 → 400 + ISSUE_BULK_VALIDATION_FAILED ──

    @Test
    fun `POST bulk-transitions available — issueKeys 1000 초과이면 400 + ISSUE_BULK_VALIDATION_FAILED`() {
        val body = mapOf("issueKeys" to (1..1001).map { "ATLAS-$it" })

        mockMvc.perform(
            post("/api/v1/issues/bulk-transitions/available")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value(BulkErrorCodes.VALIDATION_FAILED))
    }

    // ── AT-5: 응답 항목이 전환 1급 식별자(transitionId)·종류(kind)를 싣는다 ─────────────────────

    @Test
    fun `POST bulk-transitions available — 응답 항목이 transitionId 와 kind 를 싣는다`() {
        val transitionUuid = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val globalView =
            AvailableTransitionView(
                fromStateKey = "open",
                toStateKey = "done",
                name = "즉시 완료",
                toCategory = "DONE",
                transitionId = transitionUuid,
                kind = "GLOBAL",
            )
        every { bulkAvailableTransitionsService.availableCommonTransitions(any(), any()) } returns
            BulkAvailableTransitionsResult(
                transitions = listOf(globalView),
                unresolvedIssueKeys = emptyList(),
            )

        val body = mapOf("issueKeys" to listOf("ATLAS-1"))

        mockMvc.perform(
            post("/api/v1/issues/bulk-transitions/available")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            // 409 AMBIGUOUS_TRANSITION 재요청은 후보 id 를 되실어 보내는 왕복이다 —
            // 목록 응답에 id 가 없으면 그 왕복이 API 로 성립하지 않는다 (ADR 2026-08-18 §D3).
            .andExpect(jsonPath("$.data.transitions[0].transitionId").value(transitionUuid.toString()))
            .andExpect(jsonPath("$.data.transitions[0].kind").value("GLOBAL"))
            // 하위호환 key 는 도메인 게터(`WorkflowTransition.key`) 결과를 그대로 실어야 한다.
            // GLOBAL 은 `KIND__to` 규칙이라 "GLOBAL__done" 이다 — 엔진이 fromStateKey 에 채워 넣은
            // 현재 상태(open)로 재조립하면 같은 전환을 두 이름으로 부르게 된다.
            .andExpect(jsonPath("$.data.transitions[0].key").value("GLOBAL__done"))
    }
}
