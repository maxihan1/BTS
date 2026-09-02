// BulkOperationController MockMvc 슬라이스 테스트 — 인증 주체 actor 결선 / POST 접수 202 / GET 조회 200·403·404·401 / POST 가용 전환 200·400

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
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.shared.workflow.AvailableTransitionView
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
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
 * 테스트 케이스 21건.
 * - P-1. POST 정상(BULK_EDIT) → 202 + {bulkOperationId, status:"PENDING", totalCount}
 * - P-2. POST issueKeys 빈 목록 → service가 IllegalArgumentException → 400
 * - P-3. POST issueKeys 1000 초과 → service가 IllegalArgumentException → 400
 * - P-4. POST operationType 미허용 enum 문자열 → HttpMessageNotReadableException → 400 + 계약 형태
 * - P-5. POST 빈 본문 {} (필수 필드 누락) → MethodArgumentNotValidException → 400 + 계약 형태
 * - G-1. GET 작업 본인 actor → 200 + BulkOperationResponse
 * - G-2. GET 타인 actor → 404 (존재 숨김 — 미존재와 같은 본문)
 * - G-3. GET 없는 id → 404
 * - AT-1. POST bulk-transitions/available 정상 → 200 + data.transitions(TransitionItem 형태) + data.unresolvedIssueKeys
 * - AT-2. POST bulk-transitions/available 교집합 전환 없음 → 200 + data.transitions 빈 배열
 * - AT-3. POST bulk-transitions/available issueKeys 빈 배열 → 400 + ISSUE_BULK_VALIDATION_FAILED
 * - AT-4. POST bulk-transitions/available issueKeys 1000 초과 → 400 + ISSUE_BULK_VALIDATION_FAILED
 * - AT-5. POST bulk-transitions/available 응답 항목이 transitionId·kind 를 싣는다 (ADR 2026-08-18 §D3)
 * - A2. GET 컨트롤러 밖에서 발행자 UUID 로 큐잉된 STATUS_MIGRATION 작업도 본인이면 200
 * - A3. GET 미인증 → 401 이고 저장소를 조회하지 않는다 (존재 probe 차단)
 * - A4. POST 접수 actor 가 인증 주체 UUID 다
 * - A5. POST 가용 전환 조회 actor 가 인증 주체 UUID 다
 * - A6. GET 미인증 401 이 catch-all 로 500 이 되지 않는다
 * - A7. POST bulk-update 미인증 → 401 (본문이 유효하면 인증이 먼저)
 * - A8. POST bulk-transitions/available 미인증 → 401 이고 400 검증보다 앞선다
 * - A9. GET 경로변수가 UUID 가 아니면 400 (catch-all 500 이 아니라)
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

    /**
     * 테스트에서 공통으로 사용하는 인증 주체 겸 작업 owner UUID.
     *
     * 과거 컨트롤러 sentinel(`00000000-…-0001`) 을 **일부러 쓰지 않는다** — sentinel 을 그대로 두면
     * actor 를 하드코딩한 구현에서도 우연히 일치해 소유자 판정이 초록으로 보인다.
     */
    private val actorUuid: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")

    /** 다른 actor UUID — GET 403 검증용. */
    private val otherActorUuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000099")

    /** 테스트용 작업 UUID. */
    private val operationUuid: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-000000000001")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // 세 목은 Spring 싱글턴 Bean 이라 호출 기록과 등록된 답이 클래스 수명 내내 쌓인다.
        // 한 테스트 안에서만 비우면 다음에 verify 를 쓰는 사람이 같은 함정을 다시 밟고,
        // capture 스텁이 남아 뒤 테스트의 슬롯을 오염시킨다. 매 테스트 시작에 통째로 비운다.
        clearMocks(
            bulkOperationApplicationService,
            bulkOperationRepository,
            bulkAvailableTransitionsService,
        )
        // 이 슬라이스에는 Security 필터 체인이 없다 — CurrentActor 가 읽을 인증 주체를 직접 넣는다
        // (형제 IssueControllerTest 와 같은 방식).
        authenticateAs(actorUuid)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    /** [SecurityContextHolder] 에 [uuid] 를 주체 이름으로 갖는 인증을 넣는다. */
    private fun authenticateAs(uuid: UUID) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(uuid.toString(), null, emptyList())
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

    // ── G-2: GET 타인 actor → 404 (존재 숨김) ────────────────────────────────

    @Test
    fun `GET bulk-operations 작업 id — 타인 actor이면 404 이고 미존재와 구별되지 않는다`() {
        val fixedNow = Instant.parse("2026-06-02T00:00:00Z")
        // operation.actorId 는 otherActorUuid — 인증 주체(actorUuid) 와 다름.
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

        // FR-PM-05 존재 숨김 — 403 과 404 를 가르면 그 차이가 「이 id 는 존재한다」를 알려 준다.
        // 상태 코드만 재면 부족하다. 본문이 미존재 응답과 **같은 모양**이어야 숨김이 성립한다.
        mockMvc.perform(
            get("/api/v1/bulk-operations/$operationUuid"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value(BulkErrorCodes.BULK_NOT_FOUND))
            // 소유자 정보가 새면 숨김이 무의미하다 — actor UUID 도 「권한」이라는 낱말도 없어야 한다.
            .andExpect(jsonPath("$.detail").value(not(containsString(otherActorUuid.toString()))))
            .andExpect(jsonPath("$.detail").value(not(containsString("권한"))))
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
            // G-2 와 같은 errorCode 여야 한다. 여기서 갈리면 존재 숨김이 코드 축에서 뚫린다.
            .andExpect(jsonPath("$.errorCode").value(BulkErrorCodes.BULK_NOT_FOUND))
    }

    // ── AT-1: POST bulk-transitions/available 정상 → 200 + data.transitions + unresolvedIssueKeys ──

    @Test
    fun `POST bulk-transitions available — 정상 요청이면 200 + 교집합 전환 목록과 unresolved 반환`() {
        val transitionView =
            AvailableTransitionView(
                fromStateKey = "open",
                toStateKey = "in_progress",
                name = "시작",
                key = "open__in_progress",
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
                // 엔진이 도메인 게터 결과를 그대로 실어 보내는 값 — fromStateKey 와 어긋난 채로 들어온다.
                key = "GLOBAL__done",
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

    // ── A2: GET 컨트롤러 밖에서 큐잉된 STATUS_MIGRATION — 발행자 본인이면 200 ─────────────

    @Test
    fun `GET bulk-operations 작업 id — 컨트롤러 밖에서 발행자 UUID 로 큐잉된 이관 작업도 본인이면 200`() {
        val fixedNow = Instant.parse("2026-06-02T00:00:00Z")
        // WorkflowStatusMigrationAdapter.enqueueStatusMigration 이 만드는 모양 —
        // actorId 가 컨트롤러 sentinel 이 아니라 **실제 발행자 UUID** 이고 items 는 비어 있다.
        // 이 한 건이 「이관 진행률 폴링이 100% 403」의 재현이다.
        val operation =
            BulkOperation(
                id = BulkOperationId(operationUuid),
                actorId = actorUuid,
                type = BulkOperationType.STATUS_MIGRATION,
                status = BulkOperationStatus.PENDING,
                payload =
                    BulkOperationPayload.StatusMigration(
                        mappings = mapOf("legacy_open" to "open"),
                        projectKeys = setOf("ATLAS"),
                    ),
                items = emptyList(),
                totalCount = 0,
                processedCount = 0,
                succeededCount = 0,
                failedCount = 0,
                createdAt = fixedNow,
                updatedAt = fixedNow,
            )

        every { bulkOperationRepository.findById(BulkOperationId(operationUuid)) } returns operation
        every { bulkOperationRepository.findItemsByOperationId(BulkOperationId(operationUuid)) } returns emptyList()

        mockMvc.perform(
            get("/api/v1/bulk-operations/$operationUuid"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.operationType").value("STATUS_MIGRATION"))
    }

    // ── A3: GET 미인증 → 401 이고 저장소 조회보다 먼저 ───────────────────────────────────

    @Test
    fun `GET bulk-operations 작업 id — 미인증이면 401 이고 저장소를 조회하지 않는다`() {
        // 호출 기록 비우기는 @BeforeEach 가 한다 — 목이 싱글턴이라 안 비우면
        // 아래 verify 가 남의 테스트 호출까지 센다.
        SecurityContextHolder.clearContext()

        mockMvc.perform(
            get("/api/v1/bulk-operations/$operationUuid"),
        )
            .andExpect(status().isUnauthorized)

        // 인증 판정이 조회보다 앞서야 미인증자가 404·403 차이로 작업 존재를 probe 하지 못한다.
        verify(exactly = 0) { bulkOperationRepository.findById(any()) }
    }

    // ── A4: POST 접수 actor 가 인증 주체다 ──────────────────────────────────────────────

    @Test
    fun `POST bulk-update — 접수 actor 가 인증 주체 UUID 다`() {
        val actorSlot = slot<ActorId>()
        every {
            bulkOperationApplicationService.submit(capture(actorSlot), any<BulkUpdateRequest>())
        } returns BulkOperationId(operationUuid)

        val body =
            mapOf(
                "operationType" to "BULK_EDIT",
                "issueKeys" to listOf("ATLAS-1"),
                "editPayload" to mapOf("priority" to 3),
                "transitionPayload" to null,
            )

        mockMvc.perform(
            post("/api/v1/issues/bulk-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isAccepted)

        // 고정 sentinel 로 접수하면 감사 추적이 거짓이 되고, 이후 누구나 남의 작업을 조회하게 된다.
        assertThat(actorSlot.captured).isEqualTo(ActorId(actorUuid))
    }

    // ── A5: 가용 전환 조회 actor 가 인증 주체다 ─────────────────────────────────────────

    @Test
    fun `POST bulk-transitions available — 조회 actor 가 인증 주체 UUID 다`() {
        val actorSlot = slot<ActorId>()
        every {
            bulkAvailableTransitionsService.availableCommonTransitions(capture(actorSlot), any())
        } returns
            BulkAvailableTransitionsResult(
                transitions = emptyList(),
                unresolvedIssueKeys = emptyList(),
            )

        val body = mapOf("issueKeys" to listOf("ATLAS-1"))

        mockMvc.perform(
            post("/api/v1/issues/bulk-transitions/available")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)

        // 고정 sentinel 은 권한 필터를 무조건 통과해 호출자에게 허용되지 않은 전환까지 실어 보낸다.
        assertThat(actorSlot.captured).isEqualTo(ActorId(actorUuid))
    }

    // ── A6: 401 이 catch-all 로 500 이 되지 않는다 ──────────────────────────────────────

    @Test
    fun `GET bulk-operations 작업 id — 미인증 401 이 catch-all 500 으로 변질되지 않는다`() {
        SecurityContextHolder.clearContext()

        // BulkOperationExceptionHandler 는 @ExceptionHandler(Exception::class) catch-all 을 갖는다.
        // @RestControllerAdvice 가 Spring 의 ResponseStatusExceptionResolver 보다 먼저 실행되므로
        // ResponseStatusException 전용 핸들러가 없으면 401 이 500 으로 바뀐다 (IssueExceptionHandler B1 과 같은 결함).
        mockMvc.perform(
            get("/api/v1/bulk-operations/$operationUuid"),
        )
            .andExpect(status().isUnauthorized)
            // 상수가 아니라 문자열 리터럴로 대조한다 — 아직 없는 상수를 참조하면 파일이 컴파일되지 않아
            // 「컴파일 red」가 「행위 red」를 가린다. 리터럴이면 코드 이름이 바뀌어도 여기서 걸린다.
            .andExpect(jsonPath("$.errorCode").value("ISSUE_BULK_UNAUTHENTICATED"))
    }

    // ── A7: POST 접수 미인증 → 401 ──────────────────────────────────────────────

    @Test
    fun `POST bulk-update — 미인증이면 401 이고 서비스를 부르지 않는다`() {
        SecurityContextHolder.clearContext()

        val body =
            mapOf(
                "operationType" to "BULK_EDIT",
                "issueKeys" to listOf("ATLAS-1"),
                "editPayload" to mapOf("priority" to 3),
                "transitionPayload" to null,
            )

        mockMvc.perform(
            post("/api/v1/issues/bulk-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { bulkOperationApplicationService.submit(any(), any<BulkUpdateRequest>()) }
    }

    // ── A8: POST 가용 전환 미인증 → 401 이 400 검증보다 먼저 ────────────────────

    @Test
    fun `POST bulk-transitions available — 미인증이면 검증 400 보다 401 이 먼저다`() {
        SecurityContextHolder.clearContext()

        // issueKeys 가 비어 있어 require 가 400 을 던질 본문이다.
        // 인증이 뒤에 있으면 미인증자가 400 본문으로 상한 값과 검증 규칙을 읽어 간다.
        val body = mapOf("issueKeys" to emptyList<String>())

        mockMvc.perform(
            post("/api/v1/issues/bulk-transitions/available")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { bulkAvailableTransitionsService.availableCommonTransitions(any(), any()) }
    }

    // ── A9: 경로변수 UUID 형식 오류 → 400 ───────────────────────────────────────

    @Test
    fun `GET bulk-operations 작업 id — UUID 가 아니면 400 이고 500 이 아니다`() {
        // MethodArgumentTypeMismatchException 은 IllegalArgumentException 도
        // ResponseStatusException 도 아니라, 전용 핸들러가 없으면 catch-all 이 500 으로 바꾼다.
        mockMvc.perform(
            get("/api/v1/bulk-operations/not-a-uuid"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value(BulkErrorCodes.VALIDATION_FAILED))
    }
}
