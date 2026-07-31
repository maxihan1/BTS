// IssueController POST /api/v1/issues MockMvc 슬라이스 테스트 — T13 RED

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.AssigneeIntent
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.openapitools.jackson.nullable.JsonNullableModule
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant
import java.util.UUID
import com.bts.issue.application.CreateIssueRequest as AppCreateIssueRequest

/**
 * IssueController POST /api/v1/issues MockMvc 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다.
 * [IssueApplicationService] 는 MockK stub 으로 대체한다.
 *
 * 테스트 케이스 4건.
 * - C-1. projectKey blank → 400
 * - C-2. summary blank → 400
 * - C-3. summary 200자 초과 → 400
 * - C-4. 정상 입력 → 201 + IssueResponse body + Location 헤더
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerCreateTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueControllerCreateTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueController] 와 MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig : WebMvcConfigurer {
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun issueController(service: IssueApplicationService): IssueController = IssueController(service)

        /**
         * `assigneeId` 의 `JsonNullable<UUID>` 역직렬화를 위해 [JsonNullableModule] 을 등록한다 (FR-UX-09 B1).
         *
         * 이 슬라이스는 `agile-planning` 의 `JacksonNullableConfiguration` 을 스캔하지 않아
         * 자동 등록 경로가 없다. 등록하지 않으면 `assigneeId` 를 보내는 순간
         * `HttpMessageConversionException` 이 난다 (`IssueControllerSecurityLevelTest:78-82` 동일 선례).
         */
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters
                .filterIsInstance<MappingJackson2HttpMessageConverter>()
                .forEach { it.objectMapper.registerModule(JsonNullableModule()) }
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // CurrentActor 결선(FR-PM-06 PR-B) 이후 컨트롤러가 인증 주체를 요구하므로 SecurityContext 를 주입한다.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "11111111-1111-4111-8111-111111111111",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── C-1: projectKey blank → 400 ──────────────────────────────────────────

    @Test
    fun `POST 이슈 생성 — projectKey blank 이면 400`() {
        val body =
            mapOf(
                "projectKey" to "",
                "summary" to "유효한 요약",
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── C-2: summary blank → 400 ─────────────────────────────────────────────

    @Test
    fun `POST 이슈 생성 — summary blank 이면 400`() {
        val body =
            mapOf(
                "projectKey" to "ATLAS",
                "summary" to "",
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── C-3: summary 200자 초과 → 400 ────────────────────────────────────────

    @Test
    fun `POST 이슈 생성 — summary 200자 초과이면 400`() {
        val body =
            mapOf(
                "projectKey" to "ATLAS",
                "summary" to "A".repeat(201),
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── C-4: 정상 입력 → 201 + body + Location 헤더 ───────────────────────────

    @Test
    fun `POST 이슈 생성 — 정상 입력이면 201 + IssueResponse + Location 헤더`() {
        val fixedNow = Instant.parse("2026-05-26T00:00:00Z")
        val issueKey = IssueKey("ATLAS-1")
        val actorId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

        val stubIssue =
            Issue(
                id = IssueId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                key = issueKey,
                projectId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
                summary = "정상 요약",
                reporterId = actorId,
                currentStateKey = "open",
                version = 1L,
                deletedAt = null,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = IssueTypeId(3L),
            )

        val stubResponse =
            IssueResponse(
                key = "ATLAS-1",
                id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                projectKey = "ATLAS",
                summary = "정상 요약",
                currentStateKey = "open",
                reporterId = actorId.value,
                version = 1L,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = 3L,
                typeKey = "task",
                typeName = "Task",
            )

        every { issueApplicationService.createIssue(any(), any()) } returns stubIssue
        // 컨트롤러는 createIssue 후 findByKey 를 호출하여 type 요약 포함 응답을 얻는다.
        every { issueApplicationService.findByKey(any(), issueKey) } returns stubResponse

        val body =
            mapOf(
                "projectKey" to "ATLAS",
                "summary" to "정상 요약",
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.key").value("ATLAS-1"))
            .andExpect(jsonPath("$.data.summary").value("정상 요약"))
            .andExpect(header().string("Location", "/api/v1/issues/ATLAS-1"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // FR-UX-09 B1 — priority / labels Jakarta 검증 (S5·E8)
    //
    // 어노테이션 문구·상한은 UpdateIssueRequest:75-82 와 동일하게 맞춘다.
    // ★상한만 막고 경계를 안 보면 off-by-one 을 놓친다 — 통과해야 하는 경계도 함께 단언한다.
    // ──────────────────────────────────────────────────────────────────────

    /** 정상 생성 경로가 반환할 Issue stub. */
    private fun capturedStubIssue(): Issue {
        val fixedNow = Instant.parse("2026-05-26T00:00:00Z")
        return Issue(
            id = IssueId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
            key = IssueKey("ATLAS-1"),
            projectId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            summary = "정상 요약",
            reporterId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            currentStateKey = "open",
            version = 1L,
            deletedAt = null,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            typeId = IssueTypeId(3L),
        )
    }

    /** 정상 생성 경로 stub. 400 이 아님을 확인하는 경계 테스트에서 서비스까지 도달하므로 필요하다. */
    private fun stubSuccessfulCreate() {
        val fixedNow = Instant.parse("2026-05-26T00:00:00Z")
        val issueKey = IssueKey("ATLAS-1")
        val actorId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val stubIssue = capturedStubIssue()
        val stubResponse =
            IssueResponse(
                key = "ATLAS-1",
                id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                projectKey = "ATLAS",
                summary = "정상 요약",
                currentStateKey = "open",
                reporterId = actorId.value,
                version = 1L,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = 3L,
                typeKey = "task",
                typeName = "Task",
            )
        every { issueApplicationService.createIssue(any(), any()) } returns stubIssue
        every { issueApplicationService.findByKey(any(), issueKey) } returns stubResponse
    }

    private fun postCreate(extra: Map<String, Any?>) =
        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("projectKey" to "ATLAS", "summary" to "유효한 요약") + extra,
                    ),
                ),
        )

    @Test
    fun `POST 이슈 생성 — priority 가 0 이면 400`() {
        postCreate(mapOf("priority" to 0)).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST 이슈 생성 — priority 가 6 이면 400`() {
        postCreate(mapOf("priority" to 6)).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST 이슈 생성 — labels 가 21개이면 400`() {
        postCreate(mapOf("labels" to (1..21).map { "label$it" })).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST 이슈 생성 — label 하나가 51자이면 400`() {
        postCreate(mapOf("labels" to listOf("A".repeat(51)))).andExpect(status().isBadRequest)
    }

    // ── 경계 통과 (양성 대조군) ────────────────────────────────────────────

    @Test
    fun `POST 이슈 생성 — priority 경계값 1 과 5 는 400 이 아니다`() {
        stubSuccessfulCreate()
        postCreate(mapOf("priority" to 1)).andExpect(status().isCreated)
        postCreate(mapOf("priority" to 5)).andExpect(status().isCreated)
    }

    @Test
    fun `POST 이슈 생성 — labels 경계값 20개 와 50자 는 400 이 아니다`() {
        stubSuccessfulCreate()
        postCreate(mapOf("labels" to (1..20).map { "label$it" })).andExpect(status().isCreated)
        postCreate(mapOf("labels" to listOf("A".repeat(50)))).andExpect(status().isCreated)
    }

    // ──────────────────────────────────────────────────────────────────────
    // FR-UX-09 B1 — 컨트롤러 배선 (FR3 · FR6')
    //
    // ★C1 — 응용 계층은 JsonNullable 을 보면 안 된다. 컨트롤러가 AssigneeIntent 로 변환한다.
    // ──────────────────────────────────────────────────────────────────────

    /** createIssue 에 전달된 application 계층 요청을 포착하도록 stub 을 재설정한다. */
    private fun captureAppRequest(): CapturingSlot<AppCreateIssueRequest> {
        stubSuccessfulCreate()
        val slot = slot<AppCreateIssueRequest>()
        val captured = capturedStubIssue()
        every { issueApplicationService.createIssue(any(), capture(slot)) } returns captured
        return slot
    }

    @Test
    fun `POST 이슈 생성 — assigneeId 키를 생략하면 AssigneeIntent Auto 로 전달된다`() {
        val slot = captureAppRequest()

        postCreate(emptyMap()).andExpect(status().isCreated)

        assertInstanceOf(AssigneeIntent.Auto::class.java, slot.captured.assignee)
    }

    @Test
    fun `POST 이슈 생성 — assigneeId 를 명시 null 로 보내면 AssigneeIntent None 으로 전달된다`() {
        val slot = captureAppRequest()

        postCreate(mapOf("assigneeId" to null)).andExpect(status().isCreated)

        assertInstanceOf(AssigneeIntent.None::class.java, slot.captured.assignee)
    }

    @Test
    fun `POST 이슈 생성 — assigneeId 에 값을 주면 AssigneeIntent User 로 전달된다`() {
        val slot = captureAppRequest()
        val assignee = UUID.fromString("00000000-0000-0000-0000-0000000000cc")

        postCreate(mapOf("assigneeId" to assignee.toString())).andExpect(status().isCreated)

        val intent = slot.captured.assignee
        assertInstanceOf(AssigneeIntent.User::class.java, intent)
        assertEquals(assignee, (intent as AssigneeIntent.User).userId)
    }

    // ★D-5 — REST 생성 경로만 IssueAssigned 를 발행한다. 컨트롤러가 true 를 넘겨야 한다.
    @Test
    fun `POST 이슈 생성 — 컨트롤러는 notifyAssignment true 로 전달한다`() {
        val slot = captureAppRequest()

        postCreate(emptyMap()).andExpect(status().isCreated)

        assertEquals(true, slot.captured.notifyAssignment)
    }

    @Test
    fun `POST 이슈 생성 — priority 와 labels 가 그대로 전달된다`() {
        val slot = captureAppRequest()

        postCreate(mapOf("priority" to 1, "labels" to listOf("urgent"))).andExpect(status().isCreated)

        assertEquals(1, slot.captured.priority)
        assertEquals(listOf("urgent"), slot.captured.labels)
    }
}
