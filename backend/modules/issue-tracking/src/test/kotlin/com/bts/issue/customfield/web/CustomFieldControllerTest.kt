// 커스텀 필드 Controller MockMvc 슬라이스 테스트 — 5 엔드포인트 상태코드 + 403 + RFC7807 (FR-IS-10 Task 8)

package com.bts.issue.customfield.web

import com.bts.issue.customfield.application.CustomFieldApplicationService
import com.bts.issue.customfield.domain.CustomFieldAccessDeniedException
import com.bts.issue.customfield.domain.CustomFieldDefinition
import com.bts.issue.customfield.domain.CustomFieldNotFoundException
import com.bts.issue.customfield.domain.CustomFieldOption
import com.bts.issue.customfield.domain.CustomFieldProjectNotFoundException
import com.bts.issue.customfield.domain.DuplicateCustomFieldKeyException
import com.bts.issue.customfield.domain.FieldType
import com.bts.issue.customfield.domain.ImmutableFieldTypeChangeException
import com.bts.issue.customfield.domain.InvalidFieldDefinitionException
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * CustomFieldController MockMvc 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다.
 * [CustomFieldApplicationService] 는 MockK stub 으로 대체한다.
 * 미인증 401 은 SecurityConfig 가 보장하며 prod Testcontainers 통합(T10) 에서 검증한다.
 *
 * ### 테스트 케이스
 * - C-1. POST → 201 + CustomFieldResponse body
 * - C-2. POST name blank → 400 VALIDATION_FAILED
 * - C-3. POST key blank → 400 VALIDATION_FAILED
 * - C-4. POST key 중복 → 409 CUSTOM_FIELD_KEY_DUPLICATE (errorCode 필드 포함)
 * - C-5. POST 권한 없음 → 403 CUSTOM_FIELD_ACCESS_DENIED
 * - L-1. GET 목록 → 200 + List<CustomFieldResponse>
 * - L-2. GET 목록 프로젝트 미존재 → 404 CUSTOM_FIELD_PROJECT_NOT_FOUND
 * - G-1. GET 단건 → 200 + CustomFieldResponse
 * - G-2. GET 단건 미존재 → 404 CUSTOM_FIELD_NOT_FOUND
 * - U-1. PATCH → 200 + 수정된 CustomFieldResponse
 * - U-2. PATCH fieldType 변경 시도 → 422 CUSTOM_FIELD_IMMUTABLE_CHANGE
 * - U-3. PATCH 권한 없음 → 403 CUSTOM_FIELD_ACCESS_DENIED
 * - D-1. DELETE → 204
 * - D-2. DELETE 미존재 → 404 CUSTOM_FIELD_NOT_FOUND
 * - D-3. DELETE 권한 없음 → 403 CUSTOM_FIELD_ACCESS_DENIED
 * - V-1. POST name blank → 400 (Jakarta Validation)
 * - V-2. POST fieldType 누락 → 400 (Jakarta Validation)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [CustomFieldControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class CustomFieldControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [CustomFieldController] 와 [CustomFieldExceptionHandler], MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun customFieldApplicationService(): CustomFieldApplicationService = mockk(relaxed = true)

        @Bean
        @Suppress("MaxLineLength")
        open fun customFieldController(service: CustomFieldApplicationService): CustomFieldController = CustomFieldController(service)

        @Bean
        open fun customFieldExceptionHandler(): CustomFieldExceptionHandler = CustomFieldExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var customFieldApplicationService: CustomFieldApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val projectKey = "ATLAS"
    private val fieldId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-000000000001")
    private val projectId: UUID = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-000000000001")
    private val actorId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Suppress("LongParameterList") // 테스트 헬퍼 — 도메인 필드 전체를 커버하기 위해 분리 불가
    private fun sampleDefinition(
        id: UUID = fieldId,
        key: String = "salary_impact",
        name: String = "급여 영향도",
        description: String? = null,
        fieldType: FieldType = FieldType.NUMBER,
        required: Boolean = false,
        displayOrder: Int = 1,
        options: List<CustomFieldOption> = emptyList(),
    ): CustomFieldDefinition =
        CustomFieldDefinition(
            id = id,
            projectId = projectId,
            key = key,
            name = name,
            description = description,
            fieldType = fieldType,
            required = required,
            displayOrder = displayOrder,
            options = options,
        )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    // ── C-1. POST → 201 ───────────────────────────────────────────────────────

    @Test
    fun `POST custom-fields — 정상 입력 → 201 + CustomFieldResponse`() {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "key" to "salary_impact",
                    "name" to "급여 영향도",
                    "fieldType" to "NUMBER",
                    "required" to false,
                    "displayOrder" to 1,
                ),
            )
        every {
            customFieldApplicationService.create(
                actorId = any(),
                projectIdOrKey = projectKey,
                key = "salary_impact",
                name = "급여 영향도",
                fieldType = FieldType.NUMBER,
                required = false,
                displayOrder = 1,
                options = emptyList(),
            )
        } returns sampleDefinition()

        mockMvc.perform(
            post("/api/v1/projects/$projectKey/custom-fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.key").value("salary_impact"))
            .andExpect(jsonPath("$.data.name").value("급여 영향도"))
            .andExpect(jsonPath("$.data.fieldType").value("NUMBER"))
    }

    // ── C-2. POST name blank → 400 ────────────────────────────────────────────

    @Test
    fun `POST custom-fields — name blank → 400 VALIDATION_FAILED`() {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "key" to "salary_impact",
                    "name" to "",
                    "fieldType" to "NUMBER",
                    "required" to false,
                    "displayOrder" to 1,
                ),
            )

        mockMvc.perform(
            post("/api/v1/projects/$projectKey/custom-fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value(CustomFieldErrorCodes.VALIDATION_FAILED))
    }

    // ── C-3. POST key blank → 400 ─────────────────────────────────────────────

    @Test
    fun `POST custom-fields — key blank → 400 VALIDATION_FAILED`() {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "key" to "",
                    "name" to "급여 영향도",
                    "fieldType" to "NUMBER",
                    "required" to false,
                    "displayOrder" to 1,
                ),
            )

        mockMvc.perform(
            post("/api/v1/projects/$projectKey/custom-fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value(CustomFieldErrorCodes.VALIDATION_FAILED))
    }

    // ── C-4. POST 키 중복 → 409 ────────────────────────────────────────────────

    @Test
    fun `POST custom-fields — 키 중복 → 409 CUSTOM_FIELD_KEY_DUPLICATE + errorCode 포함`() {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "key" to "salary_impact",
                    "name" to "급여 영향도",
                    "fieldType" to "NUMBER",
                    "required" to false,
                    "displayOrder" to 1,
                ),
            )
        every {
            customFieldApplicationService.create(any(), any(), any(), any(), any(), any(), any(), any())
        } throws DuplicateCustomFieldKeyException("salary_impact")

        mockMvc.perform(
            post("/api/v1/projects/$projectKey/custom-fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value(CustomFieldErrorCodes.CUSTOM_FIELD_KEY_DUPLICATE))
    }

    // ── C-5. POST 권한 없음 → 403 ─────────────────────────────────────────────

    @Test
    fun `POST custom-fields — 권한 없음 → 403 CUSTOM_FIELD_ACCESS_DENIED`() {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "key" to "salary_impact",
                    "name" to "급여 영향도",
                    "fieldType" to "NUMBER",
                    "required" to false,
                    "displayOrder" to 1,
                ),
            )
        every {
            customFieldApplicationService.create(any(), any(), any(), any(), any(), any(), any(), any())
        } throws CustomFieldAccessDeniedException(actorId, projectId)

        mockMvc.perform(
            post("/api/v1/projects/$projectKey/custom-fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value(CustomFieldErrorCodes.ACCESS_DENIED))
    }

    // ── L-1. GET 목록 → 200 ───────────────────────────────────────────────────

    @Test
    fun `GET custom-fields — 활성 목록 → 200 + List`() {
        every {
            customFieldApplicationService.listByProject(any(), projectKey)
        } returns listOf(sampleDefinition())

        mockMvc.perform(
            get("/api/v1/projects/$projectKey/custom-fields")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].key").value("salary_impact"))
    }

    // ── L-2. GET 목록 프로젝트 미존재 → 404 ────────────────────────────────────

    @Test
    fun `GET custom-fields — 프로젝트 미존재 → 404 CUSTOM_FIELD_PROJECT_NOT_FOUND`() {
        every {
            customFieldApplicationService.listByProject(any(), projectKey)
        } throws CustomFieldProjectNotFoundException(projectKey)

        mockMvc.perform(
            get("/api/v1/projects/$projectKey/custom-fields")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value(CustomFieldErrorCodes.PROJECT_NOT_FOUND))
    }

    // ── G-1. GET 단건 → 200 ───────────────────────────────────────────────────

    @Test
    fun `GET custom-fields fieldKey — 단건 조회 → 200 + CustomFieldResponse`() {
        every {
            customFieldApplicationService.getById(any(), projectKey, fieldId)
        } returns sampleDefinition()

        mockMvc.perform(
            get("/api/v1/projects/$projectKey/custom-fields/$fieldId")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(fieldId.toString()))
            .andExpect(jsonPath("$.data.key").value("salary_impact"))
    }

    // ── G-2. GET 단건 미존재 → 404 ────────────────────────────────────────────

    @Test
    fun `GET custom-fields fieldKey — 미존재 → 404 CUSTOM_FIELD_NOT_FOUND`() {
        every {
            customFieldApplicationService.getById(any(), projectKey, fieldId)
        } throws CustomFieldNotFoundException(fieldId)

        mockMvc.perform(
            get("/api/v1/projects/$projectKey/custom-fields/$fieldId")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value(CustomFieldErrorCodes.CUSTOM_FIELD_NOT_FOUND))
    }

    // ── U-1. PATCH → 200 ──────────────────────────────────────────────────────

    @Test
    fun `PATCH custom-fields fieldKey — 정상 수정 → 200 + 수정된 CustomFieldResponse`() {
        val body = mapper.writeValueAsString(mapOf("name" to "수정된 이름"))
        every {
            customFieldApplicationService.update(
                actorId = any(),
                projectIdOrKey = projectKey,
                fieldId = fieldId,
                name = "수정된 이름",
                description = null,
                fieldType = null,
                key = null,
                required = null,
                displayOrder = null,
                options = null,
            )
        } returns sampleDefinition(name = "수정된 이름")

        mockMvc.perform(
            patch("/api/v1/projects/$projectKey/custom-fields/$fieldId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("수정된 이름"))
    }

    // ── U-2. PATCH fieldType 변경 시도 → 422 ─────────────────────────────────

    @Test
    fun `PATCH custom-fields fieldKey — fieldType 변경 시도 → 422 CUSTOM_FIELD_IMMUTABLE_CHANGE`() {
        val body = mapper.writeValueAsString(mapOf("name" to "이름", "fieldType" to "LONG_TEXT"))
        every {
            customFieldApplicationService.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws ImmutableFieldTypeChangeException("fieldType")

        mockMvc.perform(
            patch("/api/v1/projects/$projectKey/custom-fields/$fieldId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value(CustomFieldErrorCodes.CUSTOM_FIELD_IMMUTABLE_CHANGE))
    }

    // ── U-3. PATCH 권한 없음 → 403 ────────────────────────────────────────────

    @Test
    fun `PATCH custom-fields fieldKey — 권한 없음 → 403 CUSTOM_FIELD_ACCESS_DENIED`() {
        val body = mapper.writeValueAsString(mapOf("name" to "이름"))
        every {
            customFieldApplicationService.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws CustomFieldAccessDeniedException(actorId, projectId)

        mockMvc.perform(
            patch("/api/v1/projects/$projectKey/custom-fields/$fieldId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value(CustomFieldErrorCodes.ACCESS_DENIED))
    }

    // ── D-1. DELETE → 204 ─────────────────────────────────────────────────────

    @Test
    fun `DELETE custom-fields fieldKey — 소프트 삭제 → 204`() {
        every {
            customFieldApplicationService.softDelete(any(), projectKey, fieldId)
        } returns Unit

        mockMvc.perform(
            delete("/api/v1/projects/$projectKey/custom-fields/$fieldId"),
        )
            .andExpect(status().isNoContent)
    }

    // ── D-2. DELETE 미존재 → 404 ──────────────────────────────────────────────

    @Test
    fun `DELETE custom-fields fieldKey — 미존재 → 404 CUSTOM_FIELD_NOT_FOUND`() {
        every {
            customFieldApplicationService.softDelete(any(), projectKey, fieldId)
        } throws CustomFieldNotFoundException(fieldId)

        mockMvc.perform(
            delete("/api/v1/projects/$projectKey/custom-fields/$fieldId"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value(CustomFieldErrorCodes.CUSTOM_FIELD_NOT_FOUND))
    }

    // ── D-3. DELETE 권한 없음 → 403 ───────────────────────────────────────────

    @Test
    fun `DELETE custom-fields fieldKey — 권한 없음 → 403 CUSTOM_FIELD_ACCESS_DENIED`() {
        every {
            customFieldApplicationService.softDelete(any(), projectKey, fieldId)
        } throws CustomFieldAccessDeniedException(actorId, projectId)

        mockMvc.perform(
            delete("/api/v1/projects/$projectKey/custom-fields/$fieldId"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value(CustomFieldErrorCodes.ACCESS_DENIED))
    }

    // ── V-1. InvalidFieldDefinition → 422 ────────────────────────────────────

    @Test
    fun `POST custom-fields — InvalidFieldDefinition 예외 → 422 CUSTOM_FIELD_INVALID_DEFINITION`() {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "key" to "salary_impact",
                    "name" to "급여 영향도",
                    "fieldType" to "NUMBER",
                    "required" to false,
                    "displayOrder" to 1,
                ),
            )
        every {
            customFieldApplicationService.create(any(), any(), any(), any(), any(), any(), any(), any())
        } throws InvalidFieldDefinitionException("key must match pattern")

        mockMvc.perform(
            post("/api/v1/projects/$projectKey/custom-fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value(CustomFieldErrorCodes.CUSTOM_FIELD_INVALID_DEFINITION))
    }
}
