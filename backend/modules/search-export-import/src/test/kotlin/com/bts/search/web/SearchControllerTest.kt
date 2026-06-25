// SearchController MockMvc 슬라이스 테스트 — POST /api/v1/search/aql 케이스 전체 (FR-SR-02 Task 6)

package com.bts.search.web

import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * SearchController MockMvc 슬라이스 테스트.
 *
 * [IssueSearchPort]를 MockK로 교체해 컨트롤러·DTO·예외핸들러 계층만 검증한다.
 * 실제 AQL 파서([com.bts.search.aql.AqlLexer]/[com.bts.search.aql.AqlParser])는 실동작한다.
 *
 * Spring Security 컨텍스트는 [SecurityContextHolder]에 직접 UUID 기반 Authentication을 주입한다
 * (SprintControllerTest 동일 패턴 — [AgileControllerTest][com.bts.agileplanning.web.SprintControllerTest]).
 *
 * ### 검증 케이스
 *
 * - C1 정상 200 (raw Page 형식 — content/totalElements)
 * - C2 문법 오류 400 (`SEARCH_SYNTAX_ERROR` + position)
 * - C3 미지원 필드 400 (`SEARCH_UNKNOWN_FIELD`)
 * - C4 후속예정 필드 400 (`SEARCH_FIELD_NOT_YET_SUPPORTED`)
 * - C5 미인증 401
 * - C6 size > 100 → 400 (`SEARCH_VALIDATION_FAILED`, 클램프 아님)
 * - C7 빈 query → 400
 * - C8 query > 2000자 → 400 (`SEARCH_VALIDATION_FAILED`)
 * - C9 BROWSE 없음(port가 SecurityException) → 403
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [SearchControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class SearchControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [SearchController], [SearchExceptionHandler]와 MockK stub Bean을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun mockIssueSearchPort(): IssueSearchPort = mockk(relaxed = true)

        @Bean
        open fun searchController(port: IssueSearchPort): SearchController = SearchController(port)

        @Bean
        open fun searchExceptionHandler(): SearchExceptionHandler = SearchExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var mockPort: IssueSearchPort

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(mockPort)
        setAuthenticated(actorId)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── C1 정상 200 ───────────────────────────────────────────────────────────

    /**
     * C1 — 유효한 AQL 쿼리, 인증된 사용자 → 200 OK, raw Page(content/totalElements).
     */
    @Test
    fun `C1 - 유효한 AQL 쿼리 200 OK raw Page 반환`() {
        val hit = sampleHit()
        every { mockPort.search(any()) } returns
            IssueSearchPage(items = listOf(hit), total = 1L, page = 0, size = 50)

        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "status = open",
                            "page" to 0,
                            "size" to 50,
                        ),
                    ),
                ),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.content[0].key").value(hit.key))
            .andExpect(jsonPath("$.content[0].summary").value(hit.summary))
            .andExpect(jsonPath("$.content[0].typeKey").value(hit.typeKey))
            .andExpect(jsonPath("$.content[0].currentStateKey").value(hit.currentStateKey))
            .andExpect(jsonPath("$.content[0].priority").value(hit.priority))
            .andExpect(jsonPath("$.content[0].priorityName").value(hit.priorityName))
            .andExpect(jsonPath("$.content[0].projectKey").value(hit.projectKey))
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    // ── C2 문법 오류 400 ──────────────────────────────────────────────────────

    /**
     * C2 — AQL 문법 오류(이중 연산자) → 400 + SEARCH_SYNTAX_ERROR + position 포함.
     *
     * `status = = open`은 파서가 두 번째 `=` 위치에서 오류를 내야 한다.
     */
    @Test
    fun `C2 - AQL 문법 오류 400 SEARCH_SYNTAX_ERROR position 포함`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "status = = open",
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_SYNTAX_ERROR"))
            .andExpect(jsonPath("$.position").exists())
    }

    // ── C3 미지원 필드 (오타) 400 ────────────────────────────────────────────

    /**
     * C3 — 오타 필드(foobar) → 400 + SEARCH_UNKNOWN_FIELD.
     */
    @Test
    fun `C3 - 오타 필드 400 SEARCH_UNKNOWN_FIELD`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "foobar = 1",
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_UNKNOWN_FIELD"))
    }

    // ── C4 후속예정 필드 400 ──────────────────────────────────────────────────

    /**
     * C4 — 후속 예정 필드(assignee) → 400 + SEARCH_FIELD_NOT_YET_SUPPORTED.
     */
    @Test
    fun `C4 - 후속예정 필드 400 SEARCH_FIELD_NOT_YET_SUPPORTED`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "assignee = someone",
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_FIELD_NOT_YET_SUPPORTED"))
    }

    // ── C5 미인증 401 ─────────────────────────────────────────────────────────

    /**
     * C5 — 미인증(익명) 요청 → 401.
     *
     * actor 추출은 AQL 파싱과 리소스 조회 이전에 수행되므로 쿼리 자체가 유효해도 401이어야 한다.
     */
    @Test
    fun `C5 - 미인증 요청 401`() {
        setAnonymous()

        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "status = open",
                        ),
                    ),
                ),
        ).andExpect(status().isUnauthorized)
    }

    // ── C6 size > 100 → 400 ──────────────────────────────────────────────────

    /**
     * C6 — size = 101 (상한 초과) → 400 + SEARCH_VALIDATION_FAILED.
     *
     * 조용한 클램프가 아니라 거부(400)해야 한다. @field:Max(100) Bean Validation으로 강제된다.
     */
    @Test
    fun `C6 - size 101 초과 400 SEARCH_VALIDATION_FAILED`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "status = open",
                            "size" to 101,
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_VALIDATION_FAILED"))
    }

    // ── C7 빈 query → 400 ────────────────────────────────────────────────────

    /**
     * C7 — 공백만 있는 query 문자열 → 400.
     *
     * @field:NotBlank로 DTO 레벨에서 거부된다.
     */
    @Test
    fun `C7 - 빈 query 400`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "   ",
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
    }

    // ── C8 query > 2000자 → 400 ───────────────────────────────────────────────

    /**
     * C8 — query 길이 2001자 → 400 + SEARCH_VALIDATION_FAILED.
     *
     * @field:Size(max = 2000) Bean Validation으로 파서 도달 전에 거부된다.
     */
    @Test
    fun `C8 - query 2001자 초과 400 SEARCH_VALIDATION_FAILED`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "a".repeat(2001),
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_VALIDATION_FAILED"))
    }

    // ── C9 BROWSE 없음 → 403 ─────────────────────────────────────────────────

    /**
     * C9 — [IssueSearchPort]가 SecurityException(BROWSE 권한 없음)을 던짐 → 403.
     *
     * 프로젝트 존재 여부를 노출하지 않기 위해 SecurityException을 403으로 변환한다.
     */
    @Test
    fun `C9 - BROWSE 없음 SecurityException 403`() {
        every { mockPort.search(any()) } throws SecurityException("BROWSE 권한이 없습니다.")

        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "status = open",
                        ),
                    ),
                ),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_ACCESS_DENIED"))
    }

    // ── B1 렉서 오류 400 (회귀 테스트 - 현재 500 재현) ─────────────────────────

    /**
     * B1-a — 닫히지 않은 따옴표 → 렉서가 AqlLexException → 400.
     * 현재 AqlLexException 이 SearchExceptionHandler 에 매핑 없어 catch-all 500 으로 변질.
     */
    @Test
    fun `B1a - 닫히지 않은 따옴표 400 SEARCH_SYNTAX_ERROR`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to """summary ~ "abc""",
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_SYNTAX_ERROR"))
            .andExpect(jsonPath("$.position").exists())
    }

    /**
     * B1-b — @bad (인식 불가 문자) → 렉서 AqlLexException → 400.
     */
    @Test
    fun `B1b - 인식 불가 문자 400 SEARCH_SYNTAX_ERROR`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "status = @bad",
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_SYNTAX_ERROR"))
            .andExpect(jsonPath("$.position").exists())
    }

    // ── B2 숫자 범위 오류 400 (회귀 테스트 - 현재 500 재현) ──────────────────────

    /**
     * B2-a — Int 범위 초과 숫자 → 파서 NumberFormatException → 400.
     * 현재 toInt() 가 NumberFormatException → catch-all 500 으로 변질.
     */
    @Test
    fun `B2a - Int 범위 초과 숫자 400 SEARCH_SYNTAX_ERROR`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "priority = 99999999999",
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_SYNTAX_ERROR"))
            .andExpect(jsonPath("$.position").exists())
    }

    /**
     * B2-b — Short 범위 초과 숫자(40000) → 파서에서 거부 → 400.
     * 현재 repository asShort() 에서 wrap → 결과 오염 / 거부 미작동.
     */
    @Test
    fun `B2b - Short 범위 초과 숫자 400 SEARCH_SYNTAX_ERROR`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "priority = 40000",
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_SYNTAX_ERROR"))
            .andExpect(jsonPath("$.position").exists())
    }

    // ── status CONTAINS 오류 400 (회귀 테스트 - 현재 500 재현) ─────────────────

    /**
     * status ~ x — status 는 CONTAINS 불허 — 파서에서 400 이어야 한다.
     * 현재 파싱을 통과해 repository IllegalArgument → 500.
     */
    @Test
    fun `status CONTAINS 연산자 400 SEARCH_SYNTAX_ERROR`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "status ~ open",
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_SYNTAX_ERROR"))
    }

    // ── ORDER BY 미지원 필드 400 (회귀 테스트 - 현재 500 재현) ──────────────────

    /**
     * ORDER BY foobar — 미지원 정렬 필드 → 파서에서 400 이어야 한다.
     * 현재 검증 없이 통과해 repository IllegalArgument → 500.
     */
    @Test
    fun `ORDER BY 미지원 필드 400 SEARCH_UNKNOWN_FIELD`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "status = open ORDER BY foobar",
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_UNKNOWN_FIELD"))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun sampleHit(): IssueSearchHit =
        IssueSearchHit(
            key = "PROJ-1",
            summary = "테스트 이슈",
            typeKey = "bug",
            currentStateKey = "open",
            assigneeId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            priority = 2,
            priorityName = "High",
            projectKey = "PROJ",
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    /**
     * [SecurityContextHolder]에 UUID 기반 인증 주체를 주입한다.
     *
     * @param userId 인증된 사용자 UUID.
     */
    private fun setAuthenticated(userId: UUID) {
        val auth =
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }

    /**
     * [SecurityContextHolder]에 익명 인증 토큰을 주입한다 (미인증 시나리오).
     */
    private fun setAnonymous() {
        val anonAuth =
            AnonymousAuthenticationToken(
                "anonymousKey",
                "anonymousUser",
                listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")),
            )
        SecurityContextHolder.getContext().authentication = anonAuth
    }
}
