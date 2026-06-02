// BulkOperationController MockMvc 슬라이스 테스트 — POST 접수 202 / GET 조회 200·403·404

package com.bts.issue.bulk.web

import com.bts.issue.bulk.application.BulkEditPayload
import com.bts.issue.bulk.application.BulkOperationApplicationService
import com.bts.issue.bulk.application.BulkUpdateRequest
import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.domain.BulkOperationStatus
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.IssueKey
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
 * [BulkOperationApplicationService], [BulkOperationRepository] 는 MockK stub 으로 대체한다.
 *
 * 테스트 케이스 6건.
 * - P-1. POST 정상(BULK_EDIT) → 202 + {bulkOperationId, status:"PENDING", totalCount}
 * - P-2. POST issueKeys 빈 목록 → service가 IllegalArgumentException → 400
 * - P-3. POST issueKeys 1000 초과 → service가 IllegalArgumentException → 400
 * - G-1. GET 작업 본인 actor → 200 + BulkOperationResponse
 * - G-2. GET 타인 actor → 403
 * - G-3. GET 없는 id → 404
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
        open fun bulkOperationController(
            service: BulkOperationApplicationService,
            repo: BulkOperationRepository,
        ): BulkOperationController = BulkOperationController(service, repo)

        @Bean
        open fun bulkOperationExceptionHandler(): BulkOperationExceptionHandler = BulkOperationExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var bulkOperationApplicationService: BulkOperationApplicationService

    @Autowired
    lateinit var bulkOperationRepository: BulkOperationRepository

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

        val body = mapOf(
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

        val body = mapOf(
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

        val body = mapOf(
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

    // ── G-1: GET 작업 본인 actor → 200 + BulkOperationResponse ───────────────

    @Test
    fun `GET bulk-operations 작업 id — 본인 actor이면 200 + BulkOperationResponse`() {
        val fixedNow = Instant.parse("2026-06-02T00:00:00Z")
        val operation = BulkOperation(
            id = BulkOperationId(operationUuid),
            actorId = actorUuid,
            type = BulkOperationType.BULK_EDIT,
            status = BulkOperationStatus.PENDING,
            items = emptyList(),
            totalCount = 2,
            processedCount = 0,
            succeededCount = 0,
            failedCount = 0,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )
        val items = listOf(
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
    }

    // ── G-2: GET 타인 actor → 403 ────────────────────────────────────────────

    @Test
    fun `GET bulk-operations 작업 id — 타인 actor이면 403`() {
        val fixedNow = Instant.parse("2026-06-02T00:00:00Z")
        // operation.actorId 는 otherActorUuid — SYSTEM_ACTOR_UUID(actorUuid) 와 다름.
        val operation = BulkOperation(
            id = BulkOperationId(operationUuid),
            actorId = otherActorUuid,
            type = BulkOperationType.BULK_EDIT,
            status = BulkOperationStatus.PENDING,
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
}
