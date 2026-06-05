// WorkflowSchemeExceptionHandler — RFC 7807 ProblemDetail 매핑 11건 MockMvc 슬라이스 테스트

package com.bts.workflow.scheme.web

import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.scheme.exception.IssueTypeNotFoundException
import com.bts.workflow.scheme.exception.MappingDefaultDuplicateException
import com.bts.workflow.scheme.exception.MappingDuplicateException
import com.bts.workflow.scheme.exception.SchemeInUseException
import com.bts.workflow.scheme.exception.SchemeKeyInvalidException
import com.bts.workflow.scheme.exception.SchemeStandardFieldLockedException
import com.bts.workflow.scheme.exception.SchemeStandardNotDeletableException
import com.bts.workflow.scheme.exception.TypeStandardNotDeletableException
import com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException
import com.bts.workflow.scheme.exception.WorkflowSchemeNotFoundException
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * [WorkflowSchemeExceptionHandler] RFC 7807 ProblemDetail 매핑 11건 검증.
 *
 * MockMvc 최소 컨텍스트 — `@SpringBootApplication` 없이 수행.
 * 각 테스트 케이스는 stub 컨트롤러가 예외를 던지고,
 * handler 가 올바른 HTTP 상태 + errorCode + type URI 를 반환하는지 검증한다.
 *
 * 검증 항목.
 * - SCHEME_KEY_INVALID      → 400
 * - SCHEME_NOT_FOUND        → 404
 * - SCHEME_STANDARD_NOT_DELETABLE → 403
 * - SCHEME_IN_USE           → 409 (body usedByProjects 포함)
 * - MAPPING_DUPLICATE       → 409
 * - MAPPING_DEFAULT_DUPLICATE → 409
 * - WORKFLOW_NOT_FOUND      → 404
 * - ISSUE_TYPE_NOT_FOUND    → 404
 * - TYPE_STANDARD_NOT_DELETABLE → 403
 * - WORKFLOW_SCHEME_NO_DEFAULT → 500
 * - SCHEME_STANDARD_FIELD_LOCKED → 403
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [WorkflowSchemeExceptionHandlerTest.TestMvcConfig::class])
@WebAppConfiguration
class WorkflowSchemeExceptionHandlerTest {
    @Autowired
    private lateinit var wac: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
    }

    // ── 400 SCHEME_KEY_INVALID ────────────────────────────────────────────────

    @Test
    fun `SCHEME_KEY_INVALID — 400 Bad Request + errorCode`() {
        mockMvc.get("/test/scheme-key-invalid")
            .andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("SCHEME_KEY_INVALID") }
                jsonPath("$.type") { value("https://bts.example.com/problems/scheme-key-invalid") }
            }
    }

    // ── 404 SCHEME_NOT_FOUND ──────────────────────────────────────────────────

    @Test
    fun `SCHEME_NOT_FOUND — 404 Not Found + errorCode`() {
        mockMvc.get("/test/scheme-not-found")
            .andExpect {
                status { isNotFound() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("SCHEME_NOT_FOUND") }
                jsonPath("$.type") { value("https://bts.example.com/problems/scheme-not-found") }
            }
    }

    // ── 403 SCHEME_STANDARD_NOT_DELETABLE ─────────────────────────────────────

    @Test
    fun `SCHEME_STANDARD_NOT_DELETABLE — 403 Forbidden + errorCode`() {
        mockMvc.get("/test/scheme-standard-not-deletable")
            .andExpect {
                status { isForbidden() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("SCHEME_STANDARD_NOT_DELETABLE") }
                jsonPath("$.type") { value("https://bts.example.com/problems/scheme-standard-not-deletable") }
            }
    }

    // ── 409 SCHEME_IN_USE ─────────────────────────────────────────────────────

    @Test
    fun `SCHEME_IN_USE — 409 Conflict + errorCode + usedByProjects body`() {
        mockMvc.get("/test/scheme-in-use")
            .andExpect {
                status { isConflict() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("SCHEME_IN_USE") }
                jsonPath("$.type") { value("https://bts.example.com/problems/scheme-in-use") }
                jsonPath("$.usedByProjects") { isArray() }
            }
    }

    // ── 409 MAPPING_DUPLICATE ─────────────────────────────────────────────────

    @Test
    fun `MAPPING_DUPLICATE — 409 Conflict + errorCode`() {
        mockMvc.get("/test/mapping-duplicate")
            .andExpect {
                status { isConflict() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("MAPPING_DUPLICATE") }
                jsonPath("$.type") { value("https://bts.example.com/problems/mapping-duplicate") }
            }
    }

    // ── 409 MAPPING_DEFAULT_DUPLICATE ─────────────────────────────────────────

    @Test
    fun `MAPPING_DEFAULT_DUPLICATE — 409 Conflict + errorCode`() {
        mockMvc.get("/test/mapping-default-duplicate")
            .andExpect {
                status { isConflict() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("MAPPING_DEFAULT_DUPLICATE") }
                jsonPath("$.type") { value("https://bts.example.com/problems/mapping-default-duplicate") }
            }
    }

    // ── 404 WORKFLOW_NOT_FOUND ────────────────────────────────────────────────

    @Test
    fun `WORKFLOW_NOT_FOUND — 404 Not Found + errorCode`() {
        mockMvc.get("/test/workflow-not-found")
            .andExpect {
                status { isNotFound() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("WORKFLOW_NOT_FOUND") }
                jsonPath("$.type") { value("https://bts.example.com/problems/workflow-not-found") }
            }
    }

    // ── 404 ISSUE_TYPE_NOT_FOUND ──────────────────────────────────────────────

    @Test
    fun `ISSUE_TYPE_NOT_FOUND — 404 Not Found + errorCode`() {
        mockMvc.get("/test/issue-type-not-found")
            .andExpect {
                status { isNotFound() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("ISSUE_TYPE_NOT_FOUND") }
                jsonPath("$.type") { value("https://bts.example.com/problems/issue-type-not-found") }
            }
    }

    // ── 403 TYPE_STANDARD_NOT_DELETABLE ───────────────────────────────────────

    @Test
    fun `TYPE_STANDARD_NOT_DELETABLE — 403 Forbidden + errorCode`() {
        mockMvc.get("/test/type-standard-not-deletable")
            .andExpect {
                status { isForbidden() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("TYPE_STANDARD_NOT_DELETABLE") }
                jsonPath("$.type") { value("https://bts.example.com/problems/type-standard-not-deletable") }
            }
    }

    // ── 500 WORKFLOW_SCHEME_NO_DEFAULT ────────────────────────────────────────

    @Test
    fun `WORKFLOW_SCHEME_NO_DEFAULT — 500 Internal Server Error + errorCode`() {
        mockMvc.get("/test/workflow-scheme-no-default")
            .andExpect {
                status { isInternalServerError() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("WORKFLOW_SCHEME_NO_DEFAULT") }
                jsonPath("$.type") { value("https://bts.example.com/problems/workflow-scheme-no-default") }
            }
    }

    // ── 403 SCHEME_STANDARD_FIELD_LOCKED ─────────────────────────────────────

    @Test
    fun `SCHEME_STANDARD_FIELD_LOCKED — 403 Forbidden + errorCode`() {
        mockMvc.get("/test/scheme-standard-field-locked")
            .andExpect {
                status { isForbidden() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("SCHEME_STANDARD_FIELD_LOCKED") }
                jsonPath("$.type") { value("https://bts.example.com/problems/scheme-standard-field-locked") }
            }
    }

    // ── 403 WORKFLOW_SCHEME_ACCESS_DENIED ─────────────────────────────────────

    @Test
    fun `WORKFLOW_SCHEME_ACCESS_DENIED — 403 Forbidden + errorCode`() {
        mockMvc.get("/test/workflow-scheme-access-denied")
            .andExpect {
                status { isForbidden() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errorCode") { value("WORKFLOW_SCHEME_ACCESS_DENIED") }
                jsonPath("$.type") { value("https://bts.example.com/problems/workflow-scheme-access-denied") }
                // detail 이 고정 일반 메시지와 정확히 일치 → actor UUID/scope 등 내부 식별자 미노출 보장(C1 누출 방지).
                jsonPath("$.detail") { value("워크플로우 스킴 작업 권한이 없습니다.") }
            }
    }

    // ── Test context configuration ────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    class TestMvcConfig {
        @Bean
        fun stubController(): StubExceptionController = StubExceptionController()

        @Bean
        fun workflowSchemeExceptionHandler(): WorkflowSchemeExceptionHandler = WorkflowSchemeExceptionHandler()
    }

    /**
     * 테스트 전용 stub 컨트롤러. 각 예외를 직접 던져서 handler 검증에 사용한다.
     */
    @RestController
    class StubExceptionController {
        @GetMapping("/test/scheme-key-invalid")
        fun schemeKeyInvalid(): Nothing = throw SchemeKeyInvalidException(key = "INVALID_KEY!")

        @GetMapping("/test/scheme-not-found")
        fun schemeNotFound(): Nothing = throw WorkflowSchemeNotFoundException(key = "missing-scheme")

        @GetMapping("/test/scheme-standard-not-deletable")
        fun schemeStandardNotDeletable(): Nothing = throw SchemeStandardNotDeletableException(key = "software-scheme")

        @GetMapping("/test/scheme-in-use")
        fun schemeInUse(): Nothing = throw SchemeInUseException(usedByProjects = listOf(1L, 2L))

        @GetMapping("/test/mapping-duplicate")
        fun mappingDuplicate(): Nothing = throw MappingDuplicateException(schemeKey = "team-a", issueTypeKey = "bug")

        @GetMapping("/test/mapping-default-duplicate")
        fun mappingDefaultDuplicate(): Nothing = throw MappingDefaultDuplicateException(schemeKey = "team-a")

        @GetMapping("/test/workflow-not-found")
        fun workflowNotFound(): Nothing = throw WorkflowNotFoundException(workflowKey = "missing-wf")

        @GetMapping("/test/issue-type-not-found")
        fun issueTypeNotFound(): Nothing = throw IssueTypeNotFoundException(issueTypeKey = "unknown-type")

        @GetMapping("/test/type-standard-not-deletable")
        fun typeStandardNotDeletable(): Nothing = throw TypeStandardNotDeletableException(issueTypeKey = "bug")

        @GetMapping("/test/workflow-scheme-no-default")
        fun workflowSchemeNoDefault(): Nothing = throw WorkflowSchemeNoDefaultException(schemeKey = "team-a")

        @GetMapping("/test/scheme-standard-field-locked")
        fun schemeStandardFieldLocked(): Nothing = throw SchemeStandardFieldLockedException(key = "software-scheme", field = "name")

        @GetMapping("/test/workflow-scheme-access-denied")
        fun workflowSchemeAccessDenied(): Nothing =
            throw WorkflowSchemeAccessDeniedException(
                actorId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
                permission = WorkflowSchemePermission.MANAGE_SCHEME,
                scope = WorkflowSchemeScope.Global,
            )
    }
}
