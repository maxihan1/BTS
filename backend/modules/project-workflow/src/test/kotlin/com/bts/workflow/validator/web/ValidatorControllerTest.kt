// ValidatorController MockMvc 슬라이스 테스트 — 권한 Guard 선행(존재 probe 차단) + CRUD REST 계약

package com.bts.workflow.validator.web

import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.engine.DefaultWorkflowValidatorFactory
import com.bts.workflow.engine.WorkflowValidatorFactory
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.validator.ValidatorAdminService
import com.bts.workflow.validator.ValidatorNotFoundException
import com.bts.workflow.validator.ValidatorRow
import com.bts.workflow.validator.ValidatorTypeNotEditableException
import com.bts.workflow.validator.ValidatorValidationException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
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
import java.util.UUID

/**
 * ValidatorController MockMvc 슬라이스 테스트.
 *
 * 검증 범위.
 * - 4개 엔드포인트(GET/POST/PUT/DELETE) 가 권한 없으면 **리소스 조회 전에** 403 을 낸다.
 * - 실재하는 전환과 실재하지 않는 전환의 403 응답이 **구별되지 않는다** (존재 probe 차단).
 * - 403 본문에 actorId·permission·scope 가 실리지 않는다.
 * - POST 성공 201 + `{data:...}` 봉투 · DELETE 성공 204 무본문.
 * - GET 성공 200 목록 봉투 · PUT 성공 200 수정 행 · 응답에 `transitionId` 키가 아예 없다.
 * - POST 요청 바디의 `config`·`displayOrder` 가 서비스 인자로 **그대로** 간다.
 * - 에러 계약 4행 전부 — 403 밖의 3행(400 INVALID · 400 TYPE_NOT_EDITABLE · 404 NOT_FOUND).
 * - GET 목록의 각 행이 `phase` 를 함께 준다 — 소비자가 `type → phase` 표를 만들 이유를 없앤다.
 * - 인스턴스화가 실패하는 행은 `phase = null` 이고 **목록 전체는 200** 이다.
 *
 * ### 팩토리만 실물이다 (mock 이 아니다)
 * `phase` 의 진실 출처는 각 구현체의 `override val phase` 이고 팩토리가 그 인스턴스를 만든다.
 * 팩토리를 mock 으로 두면 phase 가 「던지라고 시킨 값」이 되어 두 테스트가 공허해진다.
 * [DefaultWorkflowValidatorFactory] 실물을 쓰고 그 의존 2개(권한 resolver · SpEL 평가기)만 mock 이다
 * — `ValidatorAdminServiceTest` 와 같은 관례다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ValidatorControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class ValidatorControllerTest {
    /** MockMvc 슬라이스용 최소 컨텍스트 — 컨트롤러 + 예외 핸들러 + mock 협력자. */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    open class TestMvcConfig {
        /** validator 관리 서비스 mock. */
        @Bean
        open fun validatorAdminService(): ValidatorAdminService = mockk()

        /** 권한 평가 포트 mock. */
        @Bean
        open fun permissionResolver(): WorkflowSchemePermissionResolver = mockk(relaxed = true)

        /** SpEL 평가기 — strict mock. 인스턴스 생성만 하고 평가하지 않는다는 계약의 감시자다. */
        @Bean
        open fun spelEvaluator(): SpelEvaluator = mockk()

        /** 워크플로우 권한 resolver — `permission-check` 인스턴스에 주입될 뿐 호출되지 않는다. */
        @Bean
        open fun workflowPermissionResolver(): PermissionResolver = mockk()

        /**
         * 실물 validator 팩토리 — `phase` 가 구현체에서 오는지를 이 테스트가 실제로 확인하게 한다.
         *
         * @param resolver 권한 평가 outbound port mock.
         * @param evaluator SpEL 평가기 mock.
         */
        @Bean
        open fun validatorFactory(
            resolver: PermissionResolver,
            evaluator: SpelEvaluator,
        ): WorkflowValidatorFactory = DefaultWorkflowValidatorFactory(resolver, evaluator)

        /**
         * 테스트 대상 컨트롤러.
         *
         * @param svc validator 관리 서비스 mock.
         * @param resolver 권한 평가 포트 mock.
         * @param factory 실물 validator 팩토리.
         */
        @Bean
        open fun validatorController(
            svc: ValidatorAdminService,
            resolver: WorkflowSchemePermissionResolver,
            factory: WorkflowValidatorFactory,
        ): ValidatorController = ValidatorController(svc, resolver, factory)

        /** validator 패키지 전용 예외 핸들러. */
        @Bean
        open fun validatorExceptionHandler(): ValidatorExceptionHandler = ValidatorExceptionHandler()
    }

    @Autowired
    private lateinit var wac: WebApplicationContext

    @Autowired
    private lateinit var service: ValidatorAdminService

    @Autowired
    private lateinit var permissionResolver: WorkflowSchemePermissionResolver

    private lateinit var mockMvc: MockMvc

    private val mapper = ObjectMapper().registerKotlinModule()

    private val workflowKey = "software-default"
    private val existingTransitionKey = "in_progress__done"
    private val missingTransitionKey = "in_progress__nowhere"
    private val validatorId: UUID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001")
    private val permissionValidatorId: UUID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000002")
    private val brokenConfigId: UUID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000003")
    private val unknownTypeId: UUID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000004")
    private val transitionId: UUID = UUID.fromString("ffffffff-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
        clearMocks(service, permissionResolver)
    }

    // ── 권한 Guard — 4개 엔드포인트 전부 403 (리소스 조회 전) ─────────────────────

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `실재하는 전환에 권한 없이 GET 하면 403`() {
        denyPermission()
        stubTransitionExistence()

        mockMvc.perform(get(basePath(existingTransitionKey)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_SCHEME_ACCESS_DENIED"))

        verify(exactly = 0) { service.listForTransition(any(), any()) }
    }

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `실재하지 않는 전환에 권한 없이 GET 해도 같은 403 본문`() {
        denyPermission()
        stubTransitionExistence()

        val onExisting = mockMvc.perform(get(basePath(existingTransitionKey))).andReturn().response
        val onMissing = mockMvc.perform(get(basePath(missingTransitionKey))).andReturn().response

        // 비공개는 한 케이스로 증명되지 않는다 — 두 응답이 status·본문 모두 구별 불가여야 성립한다.
        // 권한 검사가 전환 해석 뒤로 밀리면 미실재 쪽만 404 가 되어 이 대조가 깨진다.
        assertThat(onMissing.status).isEqualTo(HttpStatus.FORBIDDEN.value())
        assertThat(onMissing.status).isEqualTo(onExisting.status)
        assertThat(onMissing.contentAsString).isEqualTo(onExisting.contentAsString)
        verify(exactly = 0) { service.listForTransition(any(), any()) }
    }

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `POST 는 권한 없으면 403`() {
        denyPermission()

        mockMvc.perform(
            post(basePath(existingTransitionKey))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody())),
        )
            .andExpect(status().isForbidden)

        verify(exactly = 0) { service.create(any(), any(), any(), any(), any()) }
    }

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `PUT 은 권한 없으면 403`() {
        denyPermission()

        mockMvc.perform(
            put("${basePath(existingTransitionKey)}/$validatorId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody())),
        )
            .andExpect(status().isForbidden)

        verify(exactly = 0) { service.update(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `DELETE 는 권한 없으면 403`() {
        denyPermission()

        mockMvc.perform(delete("${basePath(existingTransitionKey)}/$validatorId"))
            .andExpect(status().isForbidden)

        verify(exactly = 0) { service.delete(any(), any(), any()) }
    }

    @Test
    @WithMockUser(username = DENIED_ACTOR)
    fun `403 본문에 actorId · permission · scope 가 없다`() {
        denyPermission()

        val body =
            mockMvc.perform(get(basePath(existingTransitionKey)))
                .andExpect(status().isForbidden)
                .andReturn()
                .response
                .contentAsString

        assertThat(body).contains("WORKFLOW_SCHEME_ACCESS_DENIED")
        assertThat(body).doesNotContain(DENIED_ACTOR)
        assertThat(body).doesNotContain("MANAGE_SCHEME")
        assertThat(body).doesNotContain("Global")
    }

    // ── 에러 계약 — 4행 중 403 을 뺀 나머지 3행 ────────────────────────────────
    //
    // 이 3행은 **권한을 허용해야 비로소 도달한다.** 거부 상태에서는 403 이 먼저 나가고
    // 서비스 stub 은 한 번도 실행되지 않는다 — 위 Guard 테스트들이 정확히 그 상태다.
    //
    // 코드는 상수를 import 하지 않고 **문자열을 직접** 쓴다. 상수를 참조하면 상수가 바뀔 때
    // 테스트가 따라 바뀌어 계약이 아니라 코드를 검사하게 된다. 서비스 테스트가 Kotlin 예외
    // 타입만 단언하므로 status·코드 계약은 여기 말고는 아무도 보지 않는다.

    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `검증 실패는 400 WORKFLOW_VALIDATOR_INVALID`() {
        allowPermission()
        every {
            service.create(workflowKey, existingTransitionKey, "RequiredField", mapOf("field" to "resolution"), 0)
        } throws ValidatorValidationException("필수 config 키 누락: 'field'")

        mockMvc.perform(
            post(basePath(existingTransitionKey))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody())),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_VALIDATOR_INVALID"))
    }

    /**
     * 편집 불가 type 을 **PUT** 으로 검증한다 — 기존 행의 type 을 바꿔 넣는 경로가 더 놓치기 쉽고
     * (plan C1), 같은 핸들러에 다른 엔드포인트로 닿는 것도 함께 확인된다.
     */
    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `편집 불가 타입은 400 WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE`() {
        allowPermission()
        val body =
            mapOf(
                "type" to "CustomExpression",
                "config" to mapOf("expression" to "issue.priority == 'HIGH'"),
                "displayOrder" to 0,
            )
        every {
            service.update(
                workflowKey,
                existingTransitionKey,
                validatorId,
                "CustomExpression",
                mapOf("expression" to "issue.priority == 'HIGH'"),
                0,
            )
        } throws ValidatorTypeNotEditableException("CustomExpression")

        mockMvc.perform(
            put("${basePath(existingTransitionKey)}/$validatorId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE"))
    }

    /**
     * 미존재 404 — 「있긴 한데 네 것이 아니다」까지 묶는 IDOR 차단 표면이다.
     *
     * 매핑이 400 이나 200 으로 바뀌면 화면은 「없음」과 「잘못 보냄」을 구별하지 못한다.
     */
    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `미존재는 404 WORKFLOW_VALIDATOR_NOT_FOUND`() {
        allowPermission()
        stubTransitionExistence()

        mockMvc.perform(get(basePath(missingTransitionKey)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("WORKFLOW_VALIDATOR_NOT_FOUND"))
    }

    // ── 성공 경로 ─────────────────────────────────────────────────────────────

    /**
     * GET 200 의 봉투·행 모양과 `transitionId` **미노출**을 함께 지킨다.
     *
     * 부재 판정에 `doesNotExist()` 를 쓰지 않는다 — 그 매처는 값이 `null` 이어도 통과하므로
     * 「키 없음」과 「값이 null」을 구별하지 못한다. `transitionId: null` 도 필드 노출이다.
     * `path()` 는 없는 키에 MissingNode 를 주므로 트리를 직접 보면 둘이 갈린다
     * (같은 이유로 phase 테스트도 트리를 본다).
     *
     * phase 는 행마다 다른 값을 쓴다 — 두 행이 같으면 응답이 상수 하나를 실어도 통과한다.
     */
    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `GET 200 은 목록 봉투와 transitionId 미노출을 지킨다`() {
        allowPermission()
        every { service.listForTransition(workflowKey, existingTransitionKey) } returns
            listOf(sampleRow(), permissionCheckRow())

        val body =
            mockMvc.perform(get(basePath(existingTransitionKey)))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        val data = mapper.readTree(body).path("data")
        assertThat(data.isArray).isTrue()
        assertThat(data.size()).isEqualTo(2)
        assertThat(data.path(0).path("transitionId").isMissingNode).isTrue()
        assertThat(data.path(1).path("transitionId").isMissingNode).isTrue()
        assertThat(data.path(0).path("id").asText()).isEqualTo(validatorId.toString())
        assertThat(data.path(0).path("type").asText()).isEqualTo("RequiredField")
        assertThat(data.path(0).path("config").path("field").asText()).isEqualTo("resolution")
        assertThat(data.path(0).path("phase").asText()).isEqualTo("EXECUTION")
        assertThat(data.path(1).path("id").asText()).isEqualTo(permissionValidatorId.toString())
        // displayOrder 는 0 이 아닌 행으로 잰다 — MissingNode 의 asInt() 도 0 이라 0 은 공허하다.
        assertThat(data.path(1).path("displayOrder").asInt()).isEqualTo(1)
        assertThat(data.path(1).path("phase").asText()).isEqualTo("AVAILABILITY")
    }

    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `POST 성공은 201 과 DataEnvelope 를 준다`() {
        allowPermission()
        every {
            service.create(workflowKey, existingTransitionKey, "RequiredField", any(), 0)
        } returns sampleRow()

        mockMvc.perform(
            post(basePath(existingTransitionKey))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody())),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(validatorId.toString()))
            .andExpect(jsonPath("$.data.type").value("RequiredField"))
            .andExpect(jsonPath("$.data.config.field").value("resolution"))
            .andExpect(jsonPath("$.data.displayOrder").value(0))
            .andExpect(jsonPath("$.data.transitionId").doesNotExist())
    }

    /**
     * 요청 바디가 서비스 인자로 **그대로** 가는지를 인자 쪽에서 직접 읽는다.
     *
     * 응답 단언만으로는 증명되지 않는다 — `$.data.config` 는 요청이 아니라 **stub 반환값**에서
     * 나오므로 컨트롤러가 `request.config` 대신 `emptyMap()` 을 넘겨도 초록이다. 그래서 stub 을
     * `any()` 가 아니라 실제 맵으로 못박고 [verify] 로 인자를 다시 읽는다.
     *
     * `displayOrder` 는 DTO 기본값 0 과 **다른** 7 을 보낸다 — 0 이면 Jackson 바인딩이 끊겨
     * 기본값이 들어가도 매처가 맞아 버린다.
     */
    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `POST 는 요청 바디의 config 와 displayOrder 를 그대로 서비스에 넘긴다`() {
        allowPermission()
        every {
            service.create(workflowKey, existingTransitionKey, "RequiredField", mapOf("field" to "resolution"), 7)
        } returns sampleRow().copy(displayOrder = 7)

        mockMvc.perform(
            post(basePath(existingTransitionKey))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody(displayOrder = 7))),
        )
            .andExpect(status().isCreated)

        verify(exactly = 1) {
            service.create(workflowKey, existingTransitionKey, "RequiredField", mapOf("field" to "resolution"), 7)
        }
    }

    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `PUT 200 은 수정된 행을 DataEnvelope 로 준다`() {
        allowPermission()
        val body =
            mapOf(
                "type" to "RequiredField",
                "config" to mapOf("field" to "fixVersion"),
                "displayOrder" to 3,
            )
        every {
            service.update(
                workflowKey,
                existingTransitionKey,
                validatorId,
                "RequiredField",
                mapOf("field" to "fixVersion"),
                3,
            )
        } returns sampleRow().copy(config = mapOf("field" to "fixVersion"), displayOrder = 3)

        mockMvc.perform(
            put("${basePath(existingTransitionKey)}/$validatorId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(validatorId.toString()))
            .andExpect(jsonPath("$.data.config.field").value("fixVersion"))
            .andExpect(jsonPath("$.data.displayOrder").value(3))
            .andExpect(jsonPath("$.data.phase").value("EXECUTION"))
    }

    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `DELETE 성공은 204 무본문`() {
        allowPermission()
        justRun { service.delete(workflowKey, existingTransitionKey, validatorId) }

        val response =
            mockMvc.perform(delete("${basePath(existingTransitionKey)}/$validatorId"))
                .andExpect(status().isNoContent)
                .andReturn()
                .response

        assertThat(response.contentAsString).isEmpty()
        verify(exactly = 1) { service.delete(workflowKey, existingTransitionKey, validatorId) }
    }

    // ── phase — 응답이 진실 출처다 (소비자가 `type → phase` 표를 만들 이유를 없앤다) ──

    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `GET 200 은 각 행의 phase 를 함께 준다`() {
        allowPermission()
        every { service.listForTransition(workflowKey, existingTransitionKey) } returns
            listOf(sampleRow(), permissionCheckRow())

        // 두 행의 phase 가 서로 **달라야** 성립한다 — 같은 값이면 상수를 박아도 초록이 된다.
        mockMvc.perform(get(basePath(existingTransitionKey)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].type").value("RequiredField"))
            .andExpect(jsonPath("$.data[0].phase").value("EXECUTION"))
            .andExpect(jsonPath("$.data[1].type").value("permission-check"))
            .andExpect(jsonPath("$.data[1].phase").value("AVAILABILITY"))
    }

    @Test
    @WithMockUser(username = ALLOWED_ACTOR)
    fun `인스턴스화가 실패하는 행은 phase 가 null 이고 목록은 200 이다`() {
        allowPermission()
        every { service.listForTransition(workflowKey, existingTransitionKey) } returns
            listOf(brokenConfigRow(), unknownTypeRow(), sampleRow())

        val body =
            mockMvc.perform(get(basePath(existingTransitionKey)))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        // `doesNotExist()` 는 「키 없음」과 「값이 null」을 구별하지 못한다 — 트리를 직접 본다.
        // `path()` 는 없는 키에 MissingNode(isNull=false) 를 주므로 둘이 갈린다.
        val data = mapper.readTree(body).path("data")
        assertThat(data.size()).isEqualTo(3)
        assertThat(data.path(0).path("type").asText()).isEqualTo("RequiredField")
        assertThat(data.path(0).path("phase").isNull).isTrue()
        assertThat(data.path(1).path("type").asText()).isEqualTo("NoSuchValidator")
        assertThat(data.path(1).path("phase").isNull).isTrue()
        assertThat(data.path(2).path("phase").asText()).isEqualTo("EXECUTION")
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private fun basePath(transitionKey: String): String {
        return "/api/v1/workflows/$workflowKey/transitions/$transitionKey/validators"
    }

    /**
     * 생성/수정 요청 바디.
     *
     * @param displayOrder 보낼 표시 순서. 기본 0 은 [ValidatorRequest] 기본값과 같으므로,
     *   바인딩이 끊긴 것을 잡아야 하는 자리에서는 **0 이 아닌 값**을 넘겨야 한다.
     */
    private fun requestBody(displayOrder: Int = 0): Map<String, Any> =
        mapOf(
            "type" to "RequiredField",
            "config" to mapOf("field" to "resolution"),
            "displayOrder" to displayOrder,
        )

    private fun sampleRow(): ValidatorRow =
        ValidatorRow(
            id = validatorId,
            transitionId = transitionId,
            type = "RequiredField",
            config = mapOf("field" to "resolution"),
            displayOrder = 0,
        )

    /**
     * AVAILABILITY 페이즈 행 — [sampleRow] (EXECUTION) 와 짝이 되어 phase 가 상수가 아님을 가른다.
     *
     * 4종 중 `RequiredField` 만 EXECUTION 을 override 하고 나머지는 SPI 기본값 AVAILABILITY 를
     * 상속한다. 즉 **type 문자열만으로는 phase 를 알 수 없다** — 그것이 이 짝의 근거다.
     */
    private fun permissionCheckRow(): ValidatorRow =
        ValidatorRow(
            id = permissionValidatorId,
            transitionId = transitionId,
            type = "permission-check",
            config = mapOf("permission" to "TRANSITION_ISSUE"),
            displayOrder = 1,
        )

    /** 손으로 넣은 깨진 config 행 — `RequiredField` 인데 필수 키 `field` 가 없다. */
    private fun brokenConfigRow(): ValidatorRow =
        ValidatorRow(
            id = brokenConfigId,
            transitionId = transitionId,
            type = "RequiredField",
            config = emptyMap(),
            displayOrder = 2,
        )

    /** 팩토리 분기에서 사라진 type 행 — 두 번째 실패 모드다. */
    private fun unknownTypeRow(): ValidatorRow =
        ValidatorRow(
            id = unknownTypeId,
            transitionId = transitionId,
            type = "NoSuchValidator",
            config = emptyMap(),
            displayOrder = 3,
        )

    private fun denyPermission() {
        every {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        } throws
            WorkflowSchemeAccessDeniedException(
                actorId = UUID.fromString(DENIED_ACTOR),
                permission = WorkflowSchemePermission.MANAGE_SCHEME,
                scope = WorkflowSchemeScope.Global,
            )
    }

    private fun allowPermission() {
        justRun {
            permissionResolver.requirePermission(
                any(),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
    }

    /**
     * 전환 실재 여부를 서비스 계층에 심는다 — 실재 키는 목록을 주고, 미실재 키는 404 를 던진다.
     *
     * 권한 검사가 먼저 돌면 이 stub 은 한 번도 불리지 않고 두 응답이 같아진다.
     */
    private fun stubTransitionExistence() {
        every { service.listForTransition(workflowKey, existingTransitionKey) } returns listOf(sampleRow())
        every { service.listForTransition(workflowKey, missingTransitionKey) } throws
            ValidatorNotFoundException("전환 미존재 — transitionKey='$missingTransitionKey'")
    }

    private companion object {
        const val DENIED_ACTOR = "22222222-2222-2222-2222-222222222222"
        const val ALLOWED_ACTOR = "11111111-1111-1111-1111-111111111111"
    }
}
