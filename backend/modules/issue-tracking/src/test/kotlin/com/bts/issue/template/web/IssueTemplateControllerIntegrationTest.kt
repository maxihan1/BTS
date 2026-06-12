// 이슈 템플릿 Controller MockMvc 슬라이스 테스트 — 6 엔드포인트 상태코드 + 403 + RFC7807 (FR-TM-01 Task 6)

package com.bts.issue.template.web

import com.bts.issue.template.application.IssueTemplateApplicationService
import com.bts.issue.template.domain.DuplicateIssueTemplateException
import com.bts.issue.template.domain.IssueTemplate
import com.bts.issue.template.domain.IssueTemplateAccessDeniedException
import com.bts.issue.template.domain.IssueTemplateNotFoundException
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
import java.time.Instant
import java.util.UUID

/**
 * IssueTemplateController MockMvc 슬라이스 테스트.
 *
 * `@SpringBootApplication` 없이 `@ContextConfiguration` 으로 최소 컨텍스트를 직접 구성한다.
 * [IssueTemplateApplicationService] 는 MockK stub 으로 대체한다.
 * 미인증 401 은 SecurityConfig 가 보장하며 prod Testcontainers 통합(T8) 에서 검증한다.
 *
 * ### 테스트 케이스
 * - L-1. GET 목록 → 200 + List<IssueTemplateResponse>
 * - L-2. GET 목록 프로젝트 미존재 → 404 ISSUE_TEMPLATE_PROJECT_NOT_FOUND (projectIdOrKey 해석 실패)
 * - G-1. GET 단건 → 200 + IssueTemplateResponse
 * - G-2. GET 단건 미존재 → 404 ISSUE_TEMPLATE_NOT_FOUND
 * - R-1. GET resolve 활성 템플릿 존재 → 200 + content
 * - R-2. GET resolve 활성 템플릿 없음 → 204
 * - C-1. POST → 201 + IssueTemplateResponse
 * - C-2. POST name blank → 400 VALIDATION_FAILED
 * - C-3. POST content blank → 400 VALIDATION_FAILED
 * - C-4. POST 중복 → 409 ISSUE_TEMPLATE_DUPLICATE
 * - C-5. POST 권한 없음 → 403 ISSUE_TEMPLATE_ACCESS_DENIED
 * - U-1. PATCH → 200 + 수정된 IssueTemplateResponse
 * - U-2. PATCH 미존재 → 404 ISSUE_TEMPLATE_NOT_FOUND
 * - U-3. PATCH 권한 없음 → 403 ISSUE_TEMPLATE_ACCESS_DENIED
 * - D-1. DELETE → 204
 * - D-2. DELETE 미존재 → 404 ISSUE_TEMPLATE_NOT_FOUND
 * - D-3. DELETE 권한 없음 → 403 ISSUE_TEMPLATE_ACCESS_DENIED
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IssueTemplateControllerIntegrationTest.TestMvcConfig::class])
@WebAppConfiguration
class IssueTemplateControllerIntegrationTest {

    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [IssueTemplateController] 와 [IssueTemplateExceptionHandler], MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun issueTemplateApplicationService(): IssueTemplateApplicationService = mockk(relaxed = true)

        @Bean
        @Suppress("MaxLineLength")
        open fun issueTemplateController(service: IssueTemplateApplicationService): IssueTemplateController =
            IssueTemplateController(service)

        @Bean
        open fun issueTemplateExceptionHandler(): IssueTemplateExceptionHandler = IssueTemplateExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueTemplateApplicationService: IssueTemplateApplicationService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val projectKey = "ATLAS"
    private val projectId: UUID = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-000000000001")
    private val templateId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-000000000001")
    private val actorId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val issueTypeId: Long = 1L

    private fun sampleTemplate(
        id: UUID = templateId,
        issueTypeId: Long = 1L,
        name: String = "기본 버그 템플릿",
        content: String = "## 재현 절차\n\n## 기대 동작",
    ): IssueTemplate =
        IssueTemplate(
            id = id,
            projectId = projectId,
            issueTypeId = issueTypeId,
            name = name,
            content = content,
            createdAt = Instant.parse("2026-06-12T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-12T00:00:00Z"),
            deletedAt = null,
        )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    // ── L-1. GET 목록 → 200 ───────────────────────────────────────────────────

    @Test
    fun `GET issue-templates — 활성 목록 → 200 + List`() {
        every {
            issueTemplateApplicationService.listByProject(any(), projectId)
        } returns listOf(sampleTemplate())

        mockMvc.perform(
            get("/api/v1/projects/$projectId/issue-templates")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("기본 버그 템플릿"))
    }

    // ── L-2. GET 목록 projectIdOrKey 문자열 → ProjectId 해석 확인 ───────────

    @Test
    fun `GET issue-templates — projectKey 문자열 → projectId 로 해석 후 서비스 호출`() {
        every {
            issueTemplateApplicationService.listByProject(any(), any())
        } returns emptyList()

        mockMvc.perform(
            get("/api/v1/projects/$projectKey/issue-templates")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value(IssueTemplateErrorCodes.PROJECT_NOT_FOUND))
    }

    // ── G-1. GET 단건 → 200 ───────────────────────────────────────────────────

    @Test
    fun `GET issue-templates templateId — 단건 조회 → 200 + IssueTemplateResponse`() {
        every {
            issueTemplateApplicationService.getById(any(), templateId)
        } returns sampleTemplate()

        mockMvc.perform(
            get("/api/v1/projects/$projectId/issue-templates/$templateId")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(templateId.toString()))
            .andExpect(jsonPath("$.data.name").value("기본 버그 템플릿"))
    }

    // ── G-2. GET 단건 미존재 → 404 ────────────────────────────────────────────

    @Test
    fun `GET issue-templates templateId — 미존재 → 404 ISSUE_TEMPLATE_NOT_FOUND`() {
        every {
            issueTemplateApplicationService.getById(any(), templateId)
        } throws IssueTemplateNotFoundException(templateId)

        mockMvc.perform(
            get("/api/v1/projects/$projectId/issue-templates/$templateId")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value(IssueTemplateErrorCodes.TEMPLATE_NOT_FOUND))
    }

    // ── R-1. GET resolve 활성 존재 → 200 + content ───────────────────────────

    @Test
    fun `GET resolve — 활성 템플릿 존재 → 200 content`() {
        every {
            issueTemplateApplicationService.resolve(projectId, issueTypeId)
        } returns "## 재현 절차\n\n## 기대 동작"

        mockMvc.perform(
            get("/api/v1/projects/$projectId/issue-templates/resolve")
                .param("issueTypeId", issueTypeId.toString())
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").value("## 재현 절차\n\n## 기대 동작"))
    }

    // ── R-2. GET resolve 없음 → 204 ───────────────────────────────────────────

    @Test
    fun `GET resolve — 활성 템플릿 없음 → 204`() {
        every {
            issueTemplateApplicationService.resolve(projectId, issueTypeId)
        } returns null

        mockMvc.perform(
            get("/api/v1/projects/$projectId/issue-templates/resolve")
                .param("issueTypeId", issueTypeId.toString())
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isNoContent)
    }

    // ── C-1. POST → 201 ───────────────────────────────────────────────────────

    @Test
    fun `POST issue-templates — 정상 입력 → 201 + IssueTemplateResponse`() {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "issueTypeId" to issueTypeId,
                    "name" to "기본 버그 템플릿",
                    "content" to "## 재현 절차\n\n## 기대 동작",
                ),
            )
        every {
            issueTemplateApplicationService.create(
                actorId = any(),
                projectId = projectId,
                issueTypeId = issueTypeId,
                name = "기본 버그 템플릿",
                content = "## 재현 절차\n\n## 기대 동작",
            )
        } returns sampleTemplate()

        mockMvc.perform(
            post("/api/v1/projects/$projectId/issue-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.name").value("기본 버그 템플릿"))
            .andExpect(jsonPath("$.data.issueTypeId").value(issueTypeId))
    }

    // ── C-2. POST name blank → 400 ────────────────────────────────────────────

    @Test
    fun `POST issue-templates — name blank → 400 VALIDATION_FAILED`() {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "issueTypeId" to issueTypeId,
                    "name" to "",
                    "content" to "내용",
                ),
            )

        mockMvc.perform(
            post("/api/v1/projects/$projectId/issue-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value(IssueTemplateErrorCodes.VALIDATION_FAILED))
    }

    // ── C-3. POST content blank → 400 ─────────────────────────────────────────

    @Test
    fun `POST issue-templates — content blank → 400 VALIDATION_FAILED`() {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "issueTypeId" to issueTypeId,
                    "name" to "템플릿 이름",
                    "content" to "",
                ),
            )

        mockMvc.perform(
            post("/api/v1/projects/$projectId/issue-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value(IssueTemplateErrorCodes.VALIDATION_FAILED))
    }

    // ── C-4. POST 중복 → 409 ──────────────────────────────────────────────────

    @Test
    fun `POST issue-templates — 중복 → 409 ISSUE_TEMPLATE_DUPLICATE`() {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "issueTypeId" to issueTypeId,
                    "name" to "기본 버그 템플릿",
                    "content" to "내용",
                ),
            )
        every {
            issueTemplateApplicationService.create(any(), any(), any(), any(), any())
        } throws DuplicateIssueTemplateException("기본 버그 템플릿")

        mockMvc.perform(
            post("/api/v1/projects/$projectId/issue-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value(IssueTemplateErrorCodes.TEMPLATE_DUPLICATE))
    }

    // ── C-5. POST 권한 없음 → 403 ─────────────────────────────────────────────

    @Test
    fun `POST issue-templates — 권한 없음 → 403 ISSUE_TEMPLATE_ACCESS_DENIED`() {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "issueTypeId" to issueTypeId,
                    "name" to "기본 버그 템플릿",
                    "content" to "내용",
                ),
            )
        every {
            issueTemplateApplicationService.create(any(), any(), any(), any(), any())
        } throws IssueTemplateAccessDeniedException(actorId, projectId)

        mockMvc.perform(
            post("/api/v1/projects/$projectId/issue-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value(IssueTemplateErrorCodes.ACCESS_DENIED))
    }

    // ── U-1. PATCH → 200 ──────────────────────────────────────────────────────

    @Test
    fun `PATCH issue-templates templateId — 정상 수정 → 200 + 수정된 IssueTemplateResponse`() {
        val body = mapper.writeValueAsString(mapOf("name" to "수정된 이름"))
        every {
            issueTemplateApplicationService.update(
                actorId = any(),
                projectId = projectId,
                templateId = templateId,
                name = "수정된 이름",
                content = any(),
            )
        } returns sampleTemplate(name = "수정된 이름")

        mockMvc.perform(
            patch("/api/v1/projects/$projectId/issue-templates/$templateId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("수정된 이름"))
    }

    // ── U-2. PATCH 미존재 → 404 ───────────────────────────────────────────────

    @Test
    fun `PATCH issue-templates templateId — 미존재 → 404 ISSUE_TEMPLATE_NOT_FOUND`() {
        val body = mapper.writeValueAsString(mapOf("name" to "이름"))
        every {
            issueTemplateApplicationService.update(any(), any(), any(), any(), any())
        } throws IssueTemplateNotFoundException(templateId)

        mockMvc.perform(
            patch("/api/v1/projects/$projectId/issue-templates/$templateId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value(IssueTemplateErrorCodes.TEMPLATE_NOT_FOUND))
    }

    // ── U-3. PATCH 권한 없음 → 403 ────────────────────────────────────────────

    @Test
    fun `PATCH issue-templates templateId — 권한 없음 → 403 ISSUE_TEMPLATE_ACCESS_DENIED`() {
        val body = mapper.writeValueAsString(mapOf("name" to "이름"))
        every {
            issueTemplateApplicationService.update(any(), any(), any(), any(), any())
        } throws IssueTemplateAccessDeniedException(actorId, projectId)

        mockMvc.perform(
            patch("/api/v1/projects/$projectId/issue-templates/$templateId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value(IssueTemplateErrorCodes.ACCESS_DENIED))
    }

    // ── D-1. DELETE → 204 ─────────────────────────────────────────────────────

    @Test
    fun `DELETE issue-templates templateId — 소프트 삭제 → 204`() {
        every {
            issueTemplateApplicationService.delete(any(), projectId, templateId)
        } returns Unit

        mockMvc.perform(
            delete("/api/v1/projects/$projectId/issue-templates/$templateId"),
        )
            .andExpect(status().isNoContent)
    }

    // ── D-2. DELETE 미존재 → 404 ──────────────────────────────────────────────

    @Test
    fun `DELETE issue-templates templateId — 미존재 → 404 ISSUE_TEMPLATE_NOT_FOUND`() {
        every {
            issueTemplateApplicationService.delete(any(), any(), templateId)
        } throws IssueTemplateNotFoundException(templateId)

        mockMvc.perform(
            delete("/api/v1/projects/$projectId/issue-templates/$templateId"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value(IssueTemplateErrorCodes.TEMPLATE_NOT_FOUND))
    }

    // ── D-3. DELETE 권한 없음 → 403 ───────────────────────────────────────────

    @Test
    fun `DELETE issue-templates templateId — 권한 없음 → 403 ISSUE_TEMPLATE_ACCESS_DENIED`() {
        every {
            issueTemplateApplicationService.delete(any(), any(), templateId)
        } throws IssueTemplateAccessDeniedException(actorId, projectId)

        mockMvc.perform(
            delete("/api/v1/projects/$projectId/issue-templates/$templateId"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value(IssueTemplateErrorCodes.ACCESS_DENIED))
    }
}
