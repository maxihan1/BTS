// IssueController POST /api/v1/issues componentIds 전달 검증 MockMvc 슬라이스 테스트 — FR-CM-03 Task-3

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.adapter.inbound.rest.cursor.CursorCodec
import com.bts.issue.application.ChangelogCursorCodec
import com.bts.issue.application.ChangelogGroupView
import com.bts.issue.application.CursorPage
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.IssueChangelogService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.openapitools.jackson.nullable.JsonNullableModule
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.web.config.EnableSpringDataWebSupport
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import com.bts.issue.application.CreateIssueRequest as AppCreateIssueRequest

/**
 * IssueController POST /api/v1/issues componentIds 전달 검증 테스트 — FR-CM-03 Task-3.
 *
 * 컨트롤러가 web DTO의 componentIds를 app command(AppCreateIssueRequest)에 정확히 전달하는지
 * MockK slot으로 캡처하여 검증한다.
 *
 * 테스트 케이스.
 * - CM3-1. componentIds 포함 요청 → app command에 해당 UUID 목록 전달
 * - CM3-2. componentIds 미포함 요청 → app command에 빈 목록 전달
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun issueController(service: IssueApplicationService): IssueController = IssueController(service)
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val fixedNow = Instant.parse("2026-06-05T00:00:00Z")
    private val issueKey = IssueKey("ATLAS-1")
    private val actorId = ActorId(UUID.fromString("00000000-0000-4000-8000-000000000001"))

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

        val stubIssue =
            Issue(
                id = IssueId(UUID.fromString("00000000-0000-4000-8000-000000000002")),
                key = issueKey,
                projectId = UUID.fromString("00000000-0000-4000-8000-000000000003"),
                summary = "테스트 이슈",
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
                id = UUID.fromString("00000000-0000-4000-8000-000000000002"),
                projectKey = "ATLAS",
                summary = "테스트 이슈",
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

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── CM3-1: componentIds 포함 → app command에 전달 ────────────────────────

    @Test
    fun `POST 이슈 생성 — componentIds 포함 시 app command에 동일 목록 전달`() {
        val compId1 = UUID.fromString("00000000-0000-4000-8000-000000000010")
        val compId2 = UUID.fromString("00000000-0000-4000-8000-000000000011")

        val appRequestSlot = slot<AppCreateIssueRequest>()
        every { issueApplicationService.createIssue(any(), capture(appRequestSlot)) } returns
            Issue(
                id = IssueId(UUID.fromString("00000000-0000-4000-8000-000000000002")),
                key = issueKey,
                projectId = UUID.fromString("00000000-0000-4000-8000-000000000003"),
                summary = "테스트 이슈",
                reporterId = actorId,
                currentStateKey = "open",
                version = 1L,
                deletedAt = null,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = IssueTypeId(3L),
            )

        val body =
            mapOf(
                "projectKey" to "ATLAS",
                "summary" to "테스트 이슈",
                "componentIds" to listOf(compId1.toString(), compId2.toString()),
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)

        assertThat(appRequestSlot.captured.componentIds)
            .containsExactlyInAnyOrder(compId1, compId2)
    }

    // ── CM3-2: componentIds 미포함 → app command에 빈 목록 전달 ──────────────

    @Test
    fun `POST 이슈 생성 — componentIds 미포함 시 app command에 빈 목록 전달`() {
        val appRequestSlot = slot<AppCreateIssueRequest>()
        every { issueApplicationService.createIssue(any(), capture(appRequestSlot)) } returns
            Issue(
                id = IssueId(UUID.fromString("00000000-0000-4000-8000-000000000002")),
                key = issueKey,
                projectId = UUID.fromString("00000000-0000-4000-8000-000000000003"),
                summary = "테스트 이슈",
                reporterId = actorId,
                currentStateKey = "open",
                version = 1L,
                deletedAt = null,
                createdAt = fixedNow,
                updatedAt = fixedNow,
                typeId = IssueTypeId(3L),
            )

        val body =
            mapOf(
                "projectKey" to "ATLAS",
                "summary" to "테스트 이슈",
            )

        mockMvc.perform(
            post("/api/v1/issues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)

        assertThat(appRequestSlot.captured.componentIds).isEmpty()
    }
}

/**
 * FR-PL-01 Task-6 — PATCH 날짜 필드 역직렬화·직렬화·응답 매핑 MockMvc 슬라이스 테스트.
 *
 * 검증 범위.
 * - B1: 응답 JSON의 날짜가 "yyyy-MM-dd" 문자열 형식인지 단언 (배열 [2026,6,20] 아님).
 * - C4: IssueResponse.from() 매핑 반영 — 서비스가 반환한 날짜가 응답 body에 그대로 나오는지 단언.
 * - 잘못된 날짜 형식("2026-13-40") → 400.
 * - 미포함 필드는 응답에 null 유지(무변경).
 *
 * [DatePatchTestConfig] 를 독립 컨텍스트로 사용한다 — [IssueControllerTest] 와 컨텍스트 공유 없음.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerDatePatchTest.DatePatchTestConfig::class])
@WebAppConfiguration
class IssueControllerDatePatchTest {
    /**
     * FR-PL-01 날짜 필드 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [JsonNullableModule] + [JavaTimeModule] 을 MockMvc 컨버터에 등록한다.
     * Boot 자동 설정이 없는 @EnableWebMvc 슬라이스에서 LocalDate 직렬화와
     * JsonNullable 역직렬화가 모두 동작하도록 수동 구성한다.
     */
    @Configuration
    @EnableWebMvc
    open class DatePatchTestConfig : WebMvcConfigurer {
        @Bean
        open fun dateSvc(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun dateController(svc: IssueApplicationService): IssueController = IssueController(svc)

        @Bean
        open fun dateExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()

        /**
         * MockMvc Jackson 컨버터에 [JsonNullableModule] 과 [JavaTimeModule] 을 등록한다.
         *
         * [JavaTimeModule] 이 없으면 LocalDate 가 [2026,6,20] 배열로 직렬화되어
         * B1 "@field:JsonFormat" 어노테이션이 있어도 적용되지 않는다.
         */
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters
                .filterIsInstance<MappingJackson2HttpMessageConverter>()
                .forEach {
                    it.objectMapper.registerModule(JsonNullableModule())
                    it.objectMapper.registerModule(JavaTimeModule())
                }
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    private lateinit var mockMvc: MockMvc

    private val startDate: LocalDate = LocalDate.of(2026, 6, 20)
    private val targetDate: LocalDate = LocalDate.of(2026, 7, 1)

    /** 날짜가 설정된 IssueResponse 스텁 — C4 매핑 확인용. */
    private val dateResponse =
        IssueResponse(
            key = "ATLAS-1",
            id = UUID.fromString("00000000-0000-4000-8000-000000000002"),
            projectKey = "ATLAS",
            summary = "날짜 테스트 이슈",
            currentStateKey = "open",
            reporterId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
            version = 2L,
            createdAt = Instant.parse("2026-06-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-20T00:00:00Z"),
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
            startDate = startDate,
            dueDate = null,
            targetDate = targetDate,
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
        every { issueApplicationService.updateIssue(any(), IssueKey("ATLAS-1"), any()) } returns dateResponse
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `PL-01-1 날짜 PATCH 응답에 B1 yyyy-MM-dd 문자열 형식으로 직렬화됨`() {
        // B1: 응답 JSON이 [2026,6,20] 배열이 아닌 "2026-06-20" 문자열인지 단언
        val body = """{"expectedVersion":1,"startDate":"2026-06-20","targetDate":"2026-07-01"}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.startDate").value("2026-06-20"))
            .andExpect(jsonPath("$.data.targetDate").value("2026-07-01"))
    }

    @Test
    fun `PL-01-2 C4 서비스가 반환한 날짜가 응답 data에 그대로 매핑됨`() {
        // C4: from() 매핑 누락 시 null이 나오는 가짜그린을 실제 값 단언으로 차단
        val body = """{"expectedVersion":1,"startDate":"2026-06-20","targetDate":"2026-07-01"}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.startDate").value("2026-06-20"))
            .andExpect(jsonPath("$.data.dueDate").isEmpty)
            .andExpect(jsonPath("$.data.targetDate").value("2026-07-01"))
    }

    @Test
    fun `PL-01-3 잘못된 날짜 형식은 400 반환`() {
        // 존재하지 않는 날짜(월 13) → Jackson 역직렬화 실패 → 400
        val body = """{"expectedVersion":1,"startDate":"2026-13-40"}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `PL-01-4 날짜 필드 미포함 시 응답에 null 유지`() {
        // 날짜를 보내지 않으면 서비스가 반환한 값 그대로 (스텁은 startDate=2026-06-20 설정됨)
        val body = """{"expectedVersion":1}"""

        mockMvc.perform(
            patch("/api/v1/issues/ATLAS-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.startDate").value("2026-06-20"))
    }
}

/**
 * FR-API-01 Task 3 — IssueController cursor 모드 / offset 무회귀 / 충돌 / round-trip / limit>100 MockMvc 슬라이스 테스트.
 *
 * 테스트 케이스.
 * - CURSOR-1: cursor 모드 — envelope(data[], meta.page.next, meta.page.limit) 응답 구조 검증
 * - CURSOR-2: offset 모드 무회귀 — 기존 Page 구조(content/totalElements) 그대로 반환
 * - CURSOR-3: cursor + page 동시 지정 → [PaginationModeConflictException] throw
 * - CURSOR-4: next round-trip — 1페이지 next 를 2페이지 cursor 에 주입 → 마지막 next=null. 중복/누락 0 검증
 * - CURSOR-5: limit > 100 → 400
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerCursorModeTest.CursorTestConfig::class])
@WebAppConfiguration
class IssueControllerCursorModeTest {
    /**
     * cursor 모드 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueExceptionHandler] 등록 — [ResponseStatusException](400) → 400 변환 포함.
     * [JavaTimeModule] 등록 — [Instant] 직렬화를 ISO 문자열로(타임스탬프 배열 비활성).
     */
    @Configuration
    @EnableWebMvc
    @EnableSpringDataWebSupport
    open class CursorTestConfig : WebMvcConfigurer {
        @Bean
        open fun cursorSvc(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun cursorCtrl(svc: IssueApplicationService): IssueController = IssueController(svc)

        @Bean
        open fun cursorExHandler(): IssueExceptionHandler = IssueExceptionHandler()

        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters
                .filterIsInstance<MappingJackson2HttpMessageConverter>()
                .forEach { converter ->
                    converter.objectMapper.registerModule(JavaTimeModule())
                    converter.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    private lateinit var mockMvc: MockMvc

    private val actorUuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val issue1Id = UUID.fromString("00000000-0000-4000-8000-000000000101")
    private val issue2Id = UUID.fromString("00000000-0000-4000-8000-000000000102")
    private val issue3Id = UUID.fromString("00000000-0000-4000-8000-000000000103")
    private val createdAt1 = Instant.parse("2026-06-01T10:00:00Z")
    private val createdAt2 = Instant.parse("2026-06-01T09:00:00Z")
    private val createdAt3 = Instant.parse("2026-06-01T08:00:00Z")

    private fun makeIssueResponse(
        key: String,
        id: UUID,
        createdAt: Instant,
    ): IssueResponse =
        IssueResponse(
            key = key,
            id = id,
            projectKey = "ATLAS",
            summary = "테스트 이슈 $key",
            currentStateKey = "open",
            reporterId = actorUuid,
            version = 1L,
            createdAt = createdAt,
            updatedAt = createdAt,
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
        )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorUuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── CURSOR-1: cursor 모드 — envelope 응답 구조 검증 ──────────────────────────

    @Test
    fun `CURSOR-1 cursor 모드 — envelope 응답 구조 검증`() {
        val issue = makeIssueResponse("ATLAS-1", issue1Id, createdAt1)
        val nextToken = CursorCodec.encode(createdAt1.atOffset(ZoneOffset.UTC), issue1Id)
        every {
            issueApplicationService.listIssuesByCursor(any(), any(), any(), any(), any())
        } returns CursorPage(items = listOf(issue), next = nextToken)

        mockMvc.perform(
            get("/api/v1/issues")
                .param("projectKey", "ATLAS")
                .param("cursor", "")
                .param("limit", "50"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].key").value("ATLAS-1"))
            .andExpect(jsonPath("$.meta.page.next").value(nextToken))
            .andExpect(jsonPath("$.meta.page.limit").value(50))
    }

    // ── CURSOR-2: offset 모드 무회귀 ─────────────────────────────────────────────

    @Test
    fun `CURSOR-2 offset 모드 무회귀 — 기존 Page 구조 그대로 반환`() {
        val issue = makeIssueResponse("ATLAS-1", issue1Id, createdAt1)
        val page = PageImpl(listOf(issue), PageRequest.of(0, 20), 1L)
        every { issueApplicationService.listIssues(any(), any(), any(), any()) } returns page

        mockMvc.perform(
            get("/api/v1/issues")
                .param("projectKey", "ATLAS")
                .param("page", "0")
                .param("size", "20"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.content[0].key").value("ATLAS-1"))
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    // ── CURSOR-3: cursor+page 동시 → PaginationModeConflictException ─────────────

    @Test
    fun `CURSOR-3 cursor와 page 동시 지정 시 PaginationModeConflictException throw`() {
        mockMvc.perform(
            get("/api/v1/issues")
                .param("projectKey", "ATLAS")
                .param("cursor", "")
                .param("page", "0"),
        ).andExpect { result ->
            assertThat(result.resolvedException).isInstanceOf(PaginationModeConflictException::class.java)
        }
    }

    // ── CURSOR-4: next round-trip ─────────────────────────────────────────────────

    @Test
    fun `CURSOR-4 next round-trip — 1페이지 next 를 2페이지 cursor 주입 후 중복 없이 마지막 next null`() {
        val issue1 = makeIssueResponse("ATLAS-1", issue1Id, createdAt1)
        val issue2 = makeIssueResponse("ATLAS-2", issue2Id, createdAt2)
        val issue3 = makeIssueResponse("ATLAS-3", issue3Id, createdAt3)

        // 페이지 1의 마지막 item(issue2) 기준으로 next 토큰 생성 (실제 서비스 로직과 동일 방식)
        val nextToken = CursorCodec.encode(createdAt2.atOffset(ZoneOffset.UTC), issue2Id)

        every {
            issueApplicationService.listIssuesByCursor(any(), any(), any(), any(), any())
        } returnsMany
            listOf(
                CursorPage(items = listOf(issue1, issue2), next = nextToken),
                CursorPage(items = listOf(issue3), next = null),
            )

        // 1페이지 조회 (cursor 빈 문자열 = 첫 페이지)
        val page1Result =
            mockMvc.perform(
                get("/api/v1/issues")
                    .param("projectKey", "ATLAS")
                    .param("cursor", "")
                    .param("limit", "2"),
            )
                .andExpect(status().isOk)
                .andReturn()

        val tree1 = ObjectMapper().readTree(page1Result.response.contentAsString)
        val dataNode1 = tree1["data"]
        assertThat(dataNode1.size()).isEqualTo(2)
        assertThat(dataNode1[0]["key"].asText()).isEqualTo("ATLAS-1")
        assertThat(dataNode1[1]["key"].asText()).isEqualTo("ATLAS-2")
        val page1Next = tree1["meta"]["page"]["next"].asText()
        assertThat(page1Next).isEqualTo(nextToken)

        // 2페이지 조회 — next round-trip: 1페이지 next 토큰을 cursor 파라미터로 주입
        val page2Result =
            mockMvc.perform(
                get("/api/v1/issues")
                    .param("projectKey", "ATLAS")
                    .param("cursor", page1Next)
                    .param("limit", "2"),
            )
                .andExpect(status().isOk)
                .andReturn()

        val tree2 = ObjectMapper().readTree(page2Result.response.contentAsString)
        val dataNode2 = tree2["data"]
        assertThat(dataNode2.size()).isEqualTo(1)
        assertThat(dataNode2[0]["key"].asText()).isEqualTo("ATLAS-3")
        // 마지막 페이지: next=null
        assertThat(tree2["meta"]["page"]["next"].isNull).isTrue()

        // 중복/누락 검증: 두 페이지의 key 집합이 서로소(교집합 = 공집합)
        val page1Keys = (0 until dataNode1.size()).map { dataNode1[it]["key"].asText() }.toSet()
        val page2Keys = (0 until dataNode2.size()).map { dataNode2[it]["key"].asText() }.toSet()
        assertThat(page1Keys.intersect(page2Keys)).isEmpty()
    }

    // ── CURSOR-5: limit > 100 → 400 ───────────────────────────────────────────────

    @Test
    fun `CURSOR-5 limit 100 초과 시 400 반환`() {
        mockMvc.perform(
            get("/api/v1/issues")
                .param("projectKey", "ATLAS")
                .param("cursor", "")
                .param("limit", "101"),
        ).andExpect(status().isBadRequest)
    }
}

/**
 * FR-API-01 Task 5 — IssueController changelog cursor 모드 / offset 무회귀 / 충돌 / round-trip MockMvc 슬라이스 테스트.
 *
 * 테스트 케이스.
 * - CHANGELOG-CURSOR-1: changelog cursor 모드 — envelope(data[], meta.page.next, meta.page.limit) 응답 구조 검증
 * - CHANGELOG-CURSOR-2: changelog offset 모드 무회귀 — 기존 Page 구조(content/totalElements) 그대로 반환
 * - CHANGELOG-CURSOR-3: cursor + page 동시 지정 → [PaginationModeConflictException] throw
 * - CHANGELOG-CURSOR-4: next round-trip — 1페이지 next 를 2페이지 cursor 에 주입 → 마지막 next=null
 * - CHANGELOG-CURSOR-5: changelog cursor limit > 100 → 400
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueControllerChangelogCursorModeTest.ChangelogCursorTestConfig::class])
@WebAppConfiguration
class IssueControllerChangelogCursorModeTest {
    /**
     * changelog cursor 모드 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueApplicationService] + [IssueChangelogService] 모두 mockk 으로 제공.
     * [IssueExceptionHandler] 등록 — PaginationModeConflictException(400), ResponseStatusException(400) 변환.
     * [JavaTimeModule] 등록 — [Instant] 직렬화를 ISO 문자열로(타임스탬프 배열 비활성).
     */
    @Configuration
    @EnableWebMvc
    @EnableSpringDataWebSupport
    open class ChangelogCursorTestConfig : WebMvcConfigurer {
        @Bean
        open fun changelogCursorSvc(): IssueApplicationService = mockk(relaxed = true)

        @Bean
        open fun changelogCursorChangelogSvc(): IssueChangelogService = mockk(relaxed = true)

        @Bean
        open fun changelogCursorCtrl(
            svc: IssueApplicationService,
            changelogSvc: IssueChangelogService,
        ): IssueController = IssueController(service = svc, changelogService = changelogSvc)

        @Bean
        open fun changelogCursorExHandler(): IssueExceptionHandler = IssueExceptionHandler()

        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters
                .filterIsInstance<MappingJackson2HttpMessageConverter>()
                .forEach { converter ->
                    converter.objectMapper.registerModule(JavaTimeModule())
                    converter.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var changelogService: IssueChangelogService

    private lateinit var mockMvc: MockMvc

    private val actorUuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val createdAt1 = Instant.parse("2026-06-01T10:00:00Z")
    private val createdAt2 = Instant.parse("2026-06-01T09:00:00Z")
    private val createdAt3 = Instant.parse("2026-06-01T08:00:00Z")
    private val groupId1 = 1001L
    private val groupId2 = 1002L

    private fun makeChangelogView(createdAt: Instant): ChangelogGroupView =
        ChangelogGroupView(actorId = actorUuid, actorName = "Test User", createdAt = createdAt, items = emptyList())

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorUuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── CHANGELOG-CURSOR-1: changelog cursor 모드 — envelope 응답 구조 검증 ──────

    @Test
    fun `CHANGELOG-CURSOR-1 changelog cursor 모드 — envelope 응답 구조 검증`() {
        val view = makeChangelogView(createdAt1)
        val nextToken = ChangelogCursorCodec.encode(createdAt1.atOffset(ZoneOffset.UTC), groupId1)
        every {
            changelogService.findChangelogByCursor(any(), IssueKey("ATLAS-1"), any(), any())
        } returns CursorPage(items = listOf(view), next = nextToken)

        mockMvc.perform(
            get("/api/v1/issues/ATLAS-1/changelog")
                .param("cursor", "")
                .param("limit", "50"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.meta.page.next").value(nextToken))
            .andExpect(jsonPath("$.meta.page.limit").value(50))
    }

    // ── CHANGELOG-CURSOR-2: offset 모드 무회귀 ──────────────────────────────────

    @Test
    fun `CHANGELOG-CURSOR-2 changelog offset 모드 무회귀 — 기존 Page 구조 그대로 반환`() {
        val view = makeChangelogView(createdAt1)
        every { changelogService.findChangelog(any(), IssueKey("ATLAS-1"), any()) } returns
            PageImpl(listOf(view), PageRequest.of(0, 20), 1L)

        mockMvc.perform(
            get("/api/v1/issues/ATLAS-1/changelog")
                .param("page", "0")
                .param("size", "20"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    // ── CHANGELOG-CURSOR-3: cursor+page 동시 지정 → PaginationModeConflictException ─

    @Test
    fun `CHANGELOG-CURSOR-3 cursor와 page 동시 지정 시 PaginationModeConflictException throw`() {
        mockMvc.perform(
            get("/api/v1/issues/ATLAS-1/changelog")
                .param("cursor", "")
                .param("page", "0"),
        ).andExpect { result ->
            assertThat(result.resolvedException).isInstanceOf(PaginationModeConflictException::class.java)
        }
    }

    // ── CHANGELOG-CURSOR-4: next round-trip ──────────────────────────────────────

    @Test
    fun `CHANGELOG-CURSOR-4 next round-trip — 1페이지 next 를 2페이지 cursor 주입 후 마지막 next null`() {
        val view1 = makeChangelogView(createdAt1)
        val view2 = makeChangelogView(createdAt2)
        val view3 = makeChangelogView(createdAt3)
        val nextToken = ChangelogCursorCodec.encode(createdAt2.atOffset(ZoneOffset.UTC), groupId2)

        every {
            changelogService.findChangelogByCursor(any(), IssueKey("ATLAS-1"), any(), any())
        } returnsMany
            listOf(
                CursorPage(items = listOf(view1, view2), next = nextToken),
                CursorPage(items = listOf(view3), next = null),
            )

        // 1페이지 조회
        val page1Result =
            mockMvc.perform(
                get("/api/v1/issues/ATLAS-1/changelog")
                    .param("cursor", "")
                    .param("limit", "2"),
            )
                .andExpect(status().isOk)
                .andReturn()

        val tree1 = ObjectMapper().readTree(page1Result.response.contentAsString)
        val page1Next = tree1["meta"]["page"]["next"].asText()
        assertThat(page1Next).isEqualTo(nextToken)

        // 2페이지 조회 — next round-trip
        val page2Result =
            mockMvc.perform(
                get("/api/v1/issues/ATLAS-1/changelog")
                    .param("cursor", page1Next)
                    .param("limit", "2"),
            )
                .andExpect(status().isOk)
                .andReturn()

        val tree2 = ObjectMapper().readTree(page2Result.response.contentAsString)
        assertThat(tree2["meta"]["page"]["next"].isNull).isTrue()
    }

    // ── CHANGELOG-CURSOR-5: limit > 100 → 400 ────────────────────────────────────

    @Test
    fun `CHANGELOG-CURSOR-5 changelog cursor limit 100 초과 시 400 반환`() {
        mockMvc.perform(
            get("/api/v1/issues/ATLAS-1/changelog")
                .param("cursor", "")
                .param("limit", "101"),
        ).andExpect(status().isBadRequest)
    }
}
