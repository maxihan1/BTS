// WorkflowDraftController MockMvc 슬라이스 테스트 — 6 엔드포인트의 상태 코드와 응답 본문

package com.bts.workflow.web

import com.bts.workflow.application.DraftView
import com.bts.workflow.application.PublishPreview
import com.bts.workflow.application.WorkflowDraftService
import com.bts.workflow.application.WorkflowPublishService
import com.bts.workflow.domain.DraftStateDto
import com.bts.workflow.domain.DraftTransitionDto
import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.domain.exception.WorkflowInvalidRequestException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowPublishMappingRequiredException
import com.bts.workflow.domain.exception.WorkflowVersionConflictException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 테스트 행위자. `CurrentActor` 가 UUID 형식을 요구하므로 실제 UUID 를 쓴다. */
private const val ACTOR_ID = "11111111-2222-3333-4444-555555555555"

private const val KEY = "wf-draft-mvc"

private val draftService: WorkflowDraftService = mockk()
private val publishService: WorkflowPublishService = mockk()

/**
 * [WorkflowDraftController] REST 슬라이스 테스트.
 *
 * ### 왜 이 테스트가 필요한가
 * 이 표면은 컨트롤러 148줄 + 예외 핸들러 106줄이 **테스트 0건**으로 들어왔다. 형제 표면은 전부
 * 슬라이스 테스트를 가진다(`WorkflowControllerMvcTest` · `ValidatorControllerTest` ·
 * `WorkflowSchemeExceptionHandlerTest`). 그 공백 때문에 서비스 테스트 이름이 「409 다」인데
 * 실제 단언은 도메인 예외였고, **HTTP 상태 매핑을 아무도 보지 않았다.**
 *
 * 여기서 보는 것은 서비스 로직이 아니라 **경계의 계약**이다 — 어떤 도메인 예외가 어떤 상태 코드와
 * 어떤 본문 모양으로 나가는가. 서비스는 목이다.
 *
 * ### 핸들러 두 벌을 함께 등록한다
 * `WorkflowExceptionHandler`(BC 전역 폴백)와 `WorkflowPublishExceptionHandler`(발행 전용)가
 * 함께 있어야 실제 응답이 재현된다. 둘 중 하나만 두면 어느 쪽이 잡는지가 테스트에서만 달라진다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [WorkflowDraftControllerMvcTest.TestMvcConfig::class])
@WebAppConfiguration
class WorkflowDraftControllerMvcTest {
    /**
     * 테스트 전용 Spring MVC + Security 최소 컨텍스트.
     *
     * ### Jackson Kotlin 모듈을 손수 등록한다
     * `@EnableWebMvc` 의 기본 컨버터는 `KotlinModule` 을 모른다. 그러면 `PublishRequest.baseVersion`
     * 처럼 **기본값 없는 non-null `Long`** 이 빠진 본문에서 조용히 0 으로 채워져, 슬라이스가
     * 운영과 다른 계약을 말한다(운영은 Boot 가 그 모듈을 자동 등록한다).
     * 이 테스트가 보는 것이 경계의 계약이므로 컨버터도 운영과 같아야 한다.
     */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    open class TestMvcConfig : WebMvcConfigurer {
        override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
            converters.removeIf { it is MappingJackson2HttpMessageConverter }
            converters.add(0, MappingJackson2HttpMessageConverter(ObjectMapper().registerKotlinModule()))
        }

        @Bean
        open fun workflowDraftService(): WorkflowDraftService = draftService

        @Bean
        open fun workflowPublishService(): WorkflowPublishService = publishService

        @Bean
        open fun workflowDraftController(
            draft: WorkflowDraftService,
            publish: WorkflowPublishService,
        ): WorkflowDraftController = WorkflowDraftController(draft, publish)

        @Bean
        open fun workflowExceptionHandler(): WorkflowExceptionHandler = WorkflowExceptionHandler()

        @Bean
        open fun publishExceptionHandler(): WorkflowPublishExceptionHandler = WorkflowPublishExceptionHandler()

        @Bean
        open fun testSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
            http
                .csrf { it.disable() }
                .authorizeHttpRequests { it.anyRequest().permitAll() }
                .build()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        clearMocks(draftService, publishService)
        val securityConfigurer = SecurityMockMvcConfigurers.springSecurity()
        @Suppress("MaxLineLength")
        mockMvc =
            MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(securityConfigurer)
                .build()
    }

    private fun definition() =
        WorkflowDraftDefinition(
            key = KEY,
            name = "초안",
            states = listOf(DraftStateDto(key = "open", name = "열림", category = "TODO", displayOrder = 0)),
            transitions = listOf(DraftTransitionDto(from = null, to = "open", name = "이슈 생성", kind = "INITIAL")),
        )

    // ── 조회 ──────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `GET draft — 200 이고 baseVersion 과 exists 를 함께 싣는다`() {
        every { draftService.get(any(), KEY) } returns
            DraftView(definition = definition(), baseVersion = 7, exists = true)

        mockMvc.perform(get("/api/v1/workflows/$KEY/draft").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.baseVersion").value(7))
            .andExpect(jsonPath("$.data.exists").value(true))
            .andExpect(jsonPath("$.data.definition.states[0].key").value("open"))
    }

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `GET draft — 없는 워크플로우는 404 다`() {
        every { draftService.get(any(), KEY) } throws WorkflowNotFoundException(KEY)

        mockMvc.perform(get("/api/v1/workflows/$KEY/draft").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound)
    }

    // ── 저장 ──────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `PUT draft — 204 이고 본문의 baseVersion 이 서비스로 전달된다`() {
        every { draftService.save(any(), KEY, any(), 5) } returns Unit

        mockMvc.perform(
            put("/api/v1/workflows/$KEY/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("definition" to definition(), "baseVersion" to 5))),
        ).andExpect(status().isNoContent)
    }

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `PUT draft — invariant 를 어긴 정의는 400 이고 봉투가 이 BC 표준이다`() {
        every { draftService.save(any(), KEY, any(), any()) } throws
            WorkflowInvalidRequestException(KEY, "상태가 하나도 없다")

        mockMvc.perform(
            put("/api/v1/workflows/$KEY/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("definition" to definition(), "baseVersion" to 0))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_INVALID_REQUEST"))
    }

    // ── 폐기 ──────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `DELETE draft — 지운 초안이 있으면 204 다`() {
        every { draftService.discard(any(), KEY) } returns true

        mockMvc.perform(delete("/api/v1/workflows/$KEY/draft"))
            .andExpect(status().isNoContent)
    }

    /**
     * ★ 컨트롤러 KDoc · `WorkflowDraftService.discard` KDoc · `WorkflowDraftRepository` KDoc
     * **세 곳이 404 라고 적는다.** 실제로는 `WorkflowInvalidRequestException` 을 던져 400 이 나가고,
     * 프론트 표는 그 코드를 「요청 내용이 올바르지 않습니다」라는 범용 문구로 옮긴다 —
     * 「초안이 없다」가 「요청이 잘못됐다」로 보인다.
     */
    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `DELETE draft — 초안이 없으면 404 다`() {
        every { draftService.discard(any(), KEY) } returns false

        mockMvc.perform(delete("/api/v1/workflows/$KEY/draft"))
            .andExpect(status().isNotFound)
    }

    // ── 미리보기 ──────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `POST publish preview — 빠지는 상태와 상태별 건수를 응답에 싣는다`() {
        every { publishService.preview(any(), KEY) } returns
            PublishPreview(
                baseVersion = 3,
                currentVersion = 3,
                removedStatusKeys = listOf("done"),
                pendingIssueCounts = mapOf("done" to 4L),
            )

        mockMvc.perform(post("/api/v1/workflows/$KEY/publish/preview").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.removedStatusKeys[0]").value("done"))
            .andExpect(jsonPath("$.data.pendingIssueCounts.done").value(4))
    }

    // ── 발행 ──────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `POST publish — 200 이고 회차를 돌려준다`() {
        every { publishService.publish(any(), KEY, 2) } returns 5

        mockMvc.perform(
            post("/api/v1/workflows/$KEY/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"baseVersion":2}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.versionNo").value(5))
    }

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `POST publish — 버전 충돌은 409 이고 전용 코드를 쓴다`() {
        every { publishService.publish(any(), KEY, 2) } throws
            WorkflowVersionConflictException(KEY, 2, 3)

        mockMvc.perform(
            post("/api/v1/workflows/$KEY/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"baseVersion":2}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_VERSION_CONFLICT"))
    }

    /**
     * ★ 이 본문 모양이 이 테스트의 이유다 — 409 가 **두 가지**를 뜻하므로 화면이 코드로 갈라야 하고,
     * 이관 모달을 그릴 재료(`pendingIssueCounts`)가 표준 봉투 **밖에** 실린다.
     * 서비스 테스트는 도메인 예외까지만 보므로 이 형태를 확인하지 못한다.
     */
    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `POST publish — 이관 필요는 409 이고 상태별 잔여 건수를 본문에 싣는다`() {
        every { publishService.publish(any(), KEY, 0) } throws
            WorkflowPublishMappingRequiredException(KEY, mapOf("done" to 3L))

        mockMvc.perform(
            post("/api/v1/workflows/$KEY/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"baseVersion":0}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_PUBLISH_MAPPING_REQUIRED"))
            .andExpect(jsonPath("$.pendingIssueCounts.done").value(3))
    }

    /**
     * ★ `PublishRequest.baseVersion` 은 기본값 없는 non-null Long 이라 빠뜨리면 Jackson 이
     * `HttpMessageNotReadableException` 을 던진다. 그것을 잡는 advice 가 없으면 응답이 이 BC 표준
     * 봉투가 아니라 Boot 기본 오류 본문으로 나가고, 프론트 파서가 실패해 한국어 매핑이 도달 불가가 된다.
     * 형제 `ValidatorExceptionHandler`·`PostActionExceptionHandler` 가 정확히 이 이유로 그 예외를 잡는다.
     */
    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `POST publish — baseVersion 이 빠진 본문도 이 BC 표준 봉투로 답한다`() {
        mockMvc.perform(
            post("/api/v1/workflows/$KEY/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").exists())
    }

    // ── 기본값 복원 ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ACTOR_ID)
    fun `POST reset-to-default — 한 번의 호출이 정의와 앵커를 함께 돌려준다`() {
        every { draftService.resetToDefault(any(), KEY, 1) } returns
            DraftView(definition = definition(), baseVersion = 1, exists = true)

        mockMvc.perform(
            post("/api/v1/workflows/$KEY/reset-to-default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"baseVersion":1}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.exists").value(true))
            .andExpect(jsonPath("$.data.baseVersion").value(1))
    }
}
