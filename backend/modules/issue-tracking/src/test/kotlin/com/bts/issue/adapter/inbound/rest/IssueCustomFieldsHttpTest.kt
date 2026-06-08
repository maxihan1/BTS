// FR-IS-10 커스텀 필드 검증 422 HTTP 회귀 테스트 — CustomFieldValidationException → 422 매핑

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.customfield.domain.CustomFieldValidationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/**
 * FR-IS-10 BLOCKER 1 — CustomFieldValidationException 이 IssueExceptionHandler 를 통해
 * HTTP 422 로 응답되는지 검증하는 MockMvc 슬라이스 테스트.
 *
 * 테스트 케이스 (4건).
 * - CF-E1. 미정의 키 → 422 + CUSTOM_FIELD_VALIDATION_FAILED
 * - CF-E2. required 필드 누락 → 422 + CUSTOM_FIELD_VALIDATION_FAILED
 * - CF-E3. 선택지 위반 → 422 + CUSTOM_FIELD_VALIDATION_FAILED
 * - CF-E4. 타입 불일치 → 422 + CUSTOM_FIELD_VALIDATION_FAILED
 * - CF-SEC. detail 에 내부 사유 노출 금지 (일반 메시지만 응답)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueCustomFieldsHttpTest.TestConfig::class])
@WebAppConfiguration
class IssueCustomFieldsHttpTest {
    @Configuration
    @EnableWebMvc
    open class TestConfig {
        @Bean
        open fun stubController(): StubCustomFieldExceptionController = StubCustomFieldExceptionController()

        @Bean
        open fun issueExceptionHandler(): IssueExceptionHandler = IssueExceptionHandler()
    }

    /**
     * 각 E1~E4 시나리오에서 CustomFieldValidationException 을 던지는 더미 컨트롤러.
     *
     * IssueExceptionHandler 가 com.bts.issue.adapter.inbound.rest 패키지를 basePackages 로 등록하므로
     * 이 패키지에 위치한 컨트롤러의 예외를 잡을 수 있다.
     */
    @RestController
    @RequestMapping("/test/custom-fields")
    class StubCustomFieldExceptionController {
        /** E1: 미정의 키 */
        @GetMapping("/undefined-key")
        fun throwUndefinedKey(): Nothing =
            throw CustomFieldValidationException("unknown_field", "field is not defined in this project")

        /** E2: required 필드 누락 */
        @GetMapping("/required-missing")
        fun throwRequiredMissing(): Nothing =
            throw CustomFieldValidationException("priority", "field is required but missing or null")

        /** E3: 선택지 위반 */
        @GetMapping("/invalid-option")
        fun throwInvalidOption(): Nothing =
            throw CustomFieldValidationException("status_tag", "value 'unknown' is not in defined options [low, medium, high]")

        /** E4: 타입 불일치 */
        @GetMapping("/type-mismatch")
        fun throwTypeMismatch(): Nothing =
            throw CustomFieldValidationException("due_date", "expected String (YYYY-MM-DD) but got Integer")
    }

    @org.springframework.beans.factory.annotation.Autowired
    lateinit var webApplicationContext: WebApplicationContext

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    @Test
    fun `CF-E1 미정의 키 전달 시 422 + CUSTOM_FIELD_VALIDATION_FAILED`() {
        mockMvc.perform(get("/test/custom-fields/undefined-key").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.errorCode").value("CUSTOM_FIELD_VALIDATION_FAILED"))
    }

    @Test
    fun `CF-E2 required 필드 누락 시 422 + CUSTOM_FIELD_VALIDATION_FAILED`() {
        mockMvc.perform(get("/test/custom-fields/required-missing").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.errorCode").value("CUSTOM_FIELD_VALIDATION_FAILED"))
    }

    @Test
    fun `CF-E3 선택지 위반 시 422 + CUSTOM_FIELD_VALIDATION_FAILED`() {
        mockMvc.perform(get("/test/custom-fields/invalid-option").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.errorCode").value("CUSTOM_FIELD_VALIDATION_FAILED"))
    }

    @Test
    fun `CF-E4 타입 불일치 시 422 + CUSTOM_FIELD_VALIDATION_FAILED`() {
        mockMvc.perform(get("/test/custom-fields/type-mismatch").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.errorCode").value("CUSTOM_FIELD_VALIDATION_FAILED"))
    }

    @Test
    fun `CF-SEC detail 에 내부 사유가 노출되지 않고 일반 메시지만 응답된다`() {
        mockMvc.perform(get("/test/custom-fields/undefined-key").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.detail").value("커스텀 필드 값이 유효하지 않습니다."))
    }
}
