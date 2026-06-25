// 컨트롤러+파서 슬라이스 통합 테스트 — 파서→IssueSearchPort 계약 정합(ast/sort capture) 검증

package com.bts.search.integration

import com.bts.search.web.SearchController
import com.bts.search.web.SearchExceptionHandler
import com.bts.shared.search.AqlField
import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlOperator
import com.bts.shared.search.AqlValue
import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import com.bts.shared.search.SortDirection
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
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
 * SearchController + AqlParser 슬라이스 통합 테스트 — Task 7 (FR-SR-02).
 *
 * ## 목적
 *
 * 실제 AQL 파서([com.bts.search.aql.AqlLexer] / [com.bts.search.aql.AqlParser])가 동작하며,
 * [IssueSearchPort]에 전달되는 [IssueSearchQuery]의 [IssueSearchQuery.ast] / [IssueSearchQuery.sort]
 * 내용을 slot capture로 검증한다.
 *
 * T6 [com.bts.search.web.SearchControllerTest]는 MockK `any()`로 포트를 stub해
 * ast/sort 정합을 검증하지 않는다. 본 테스트가 그 gap을 보완한다.
 *
 * ## 검증 케이스
 *
 * - S1 단순 검색 — `status = open` → ast: [AqlNode.Comparison](EQ), IssueSearchPort 1회 호출
 * - S2 불리언 조합 — `summary ~ "버그" AND (label = urgent OR priority = 1)` → ast: And/Or 트리 정합
 * - S3 정렬 — `status = open ORDER BY priority DESC` → sort: [AqlSort](priority, DESC) 정합
 * - S5 문법 오류 — `status = = open` → 400, IssueSearchPort 미호출
 * - 미지원 필드 — `foobar = 1` → 400 `SEARCH_UNKNOWN_FIELD`, IssueSearchPort 미호출
 * - 페이지네이션 응답 형식 — raw Page(content/totalElements/page/size)
 *
 * ## 설계 결정
 *
 * - DB / Testcontainers 불필요 — MockMvc + fake [IssueSearchPort] 빈(T5가 실DB 담당).
 * - cross-BC 실DB visibility(S4)는 T5의 IssueSearchRepositoryIntegrationTest가 담당.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [SearchAqlSliceTest.TestMvcConfig::class])
@WebAppConfiguration
class SearchAqlSliceTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * 실 파서와 컨트롤러를 조립하고, [IssueSearchPort]만 MockK fake으로 대체한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun fakeIssueSearchPort(): IssueSearchPort = mockk(relaxed = false)

        @Bean
        open fun searchController(port: IssueSearchPort): SearchController = SearchController(port)

        @Bean
        open fun searchExceptionHandler(): SearchExceptionHandler = SearchExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var fakePort: IssueSearchPort

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000099")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        clearMocks(fakePort)
        setAuthenticated(actorId)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── S1 단순 검색 — ast: Comparison(EQ) 정합 ─────────────────────────────

    /**
     * Given 인증된 사용자, When `status = open` 전송, Then
     * [IssueSearchPort]에 전달된 ast가 [AqlNode.Comparison](field=status, op=EQ, value="open")이고,
     * 응답이 200 raw Page(content/totalElements).
     */
    @Test
    fun `S1 - status = open ast가 Comparison EQ로 포트에 전달된다`() {
        val querySlot = slot<IssueSearchQuery>()
        every { fakePort.search(capture(querySlot)) } returns singleHitPage()

        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("status = open")),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.totalElements").value(1))

        val capturedQuery = querySlot.captured
        assertThat(capturedQuery.projectKey).isEqualTo("PROJ")
        assertThat(capturedQuery.viewerUserId).isEqualTo(actorId)
        assertThat(capturedQuery.sort).isEmpty()

        val ast = capturedQuery.ast
        assertThat(ast).isInstanceOf(AqlNode.Comparison::class.java)
        val comparison = ast as AqlNode.Comparison
        assertThat(comparison.field).isEqualTo(AqlField("status"))
        assertThat(comparison.op).isEqualTo(AqlOperator.EQ)
        assertThat(comparison.values).containsExactly(AqlValue.Str("open"))
    }

    // ── S2 불리언 조합 — ast: And/Or 트리 정합 ──────────────────────────────

    /**
     * Given 인증된 사용자,
     * When `summary ~ "버그" AND (label = urgent OR priority = 1)` 전송,
     * Then ast가 And(CONTAINS, Or(EQ, EQ)) 트리를 정확히 반영한다.
     *
     * 우선순위(AND > OR)와 괄호가 AST에 정확히 반영되는지 검증한다.
     */
    @Test
    fun `S2 - 불리언 조합 AND OR ast가 정확한 트리로 포트에 전달된다`() {
        val querySlot = slot<IssueSearchQuery>()
        every { fakePort.search(capture(querySlot)) } returns singleHitPage()

        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("""summary ~ "버그" AND (label = urgent OR priority = 1)""")),
        ).andExpect(status().isOk)

        val ast = querySlot.captured.ast

        // 최상위: And
        assertThat(ast).isInstanceOf(AqlNode.And::class.java)
        val and = ast as AqlNode.And

        // And.left: summary ~ "버그" (CONTAINS)
        assertThat(and.left).isInstanceOf(AqlNode.Comparison::class.java)
        val summaryComp = and.left as AqlNode.Comparison
        assertThat(summaryComp.field).isEqualTo(AqlField("summary"))
        assertThat(summaryComp.op).isEqualTo(AqlOperator.CONTAINS)
        assertThat(summaryComp.values).containsExactly(AqlValue.Str("버그"))

        // And.right: (label = urgent OR priority = 1) → Or
        assertThat(and.right).isInstanceOf(AqlNode.Or::class.java)
        val or = and.right as AqlNode.Or

        // Or.left: label = urgent
        assertThat(or.left).isInstanceOf(AqlNode.Comparison::class.java)
        val labelComp = or.left as AqlNode.Comparison
        assertThat(labelComp.field).isEqualTo(AqlField("label"))
        assertThat(labelComp.op).isEqualTo(AqlOperator.EQ)
        assertThat(labelComp.values).containsExactly(AqlValue.Str("urgent"))

        // Or.right: priority = 1 (Num)
        assertThat(or.right).isInstanceOf(AqlNode.Comparison::class.java)
        val priorityComp = or.right as AqlNode.Comparison
        assertThat(priorityComp.field).isEqualTo(AqlField("priority"))
        assertThat(priorityComp.op).isEqualTo(AqlOperator.EQ)
        assertThat(priorityComp.values).containsExactly(AqlValue.Num(1))
    }

    // ── S3 정렬 — sort: AqlSort(priority, DESC) 정합 ─────────────────────────

    /**
     * Given 인증된 사용자,
     * When `status = open ORDER BY priority DESC` 전송,
     * Then [IssueSearchQuery.sort]에 [AqlSort](field=priority, direction=DESC)가 1건 담긴다.
     */
    @Test
    fun `S3 - ORDER BY priority DESC sort가 AqlSort DESC로 포트에 전달된다`() {
        val querySlot = slot<IssueSearchQuery>()
        every { fakePort.search(capture(querySlot)) } returns singleHitPage()

        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("status = open ORDER BY priority DESC")),
        ).andExpect(status().isOk)

        val sort = querySlot.captured.sort
        assertThat(sort).hasSize(1)
        assertThat(sort[0].field).isEqualTo(AqlField("priority"))
        assertThat(sort[0].direction).isEqualTo(SortDirection.DESC)

        // ast도 정상적으로 Comparison이어야 한다
        val ast = querySlot.captured.ast
        assertThat(ast).isInstanceOf(AqlNode.Comparison::class.java)
        val comparison = ast as AqlNode.Comparison
        assertThat(comparison.field).isEqualTo(AqlField("status"))
        assertThat(comparison.op).isEqualTo(AqlOperator.EQ)
    }

    // ── S5 문법 오류 — 400, IssueSearchPort 미호출 ───────────────────────────

    /**
     * Given 인증된 사용자,
     * When `status = = open`(이중 연산자 — 문법 오류) 전송,
     * Then 400 + errorCode=SEARCH_SYNTAX_ERROR + position 포함,
     * [IssueSearchPort]는 한 번도 호출되지 않아야 한다.
     */
    @Test
    fun `S5 - 문법 오류 400 반환 IssueSearchPort 미호출`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("status = = open")),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_SYNTAX_ERROR"))
            .andExpect(jsonPath("$.position").exists())

        verify(exactly = 0) { fakePort.search(any()) }
    }

    // ── 미지원 필드 — 400, IssueSearchPort 미호출 ────────────────────────────

    /**
     * Given 인증된 사용자,
     * When `foobar = 1`(알 수 없는 필드) 전송,
     * Then 400 + errorCode=SEARCH_UNKNOWN_FIELD,
     * [IssueSearchPort]는 한 번도 호출되지 않아야 한다.
     */
    @Test
    fun `미지원 필드 400 SEARCH_UNKNOWN_FIELD IssueSearchPort 미호출`() {
        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("foobar = 1")),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("SEARCH_UNKNOWN_FIELD"))

        verify(exactly = 0) { fakePort.search(any()) }
    }

    // ── 페이지네이션 응답 형식 — page/size/totalElements ─────────────────────

    /**
     * Given 인증된 사용자, page=1, size=10,
     * When `status = open` 전송,
     * Then 응답 raw Page에 content / totalElements / pageable.pageNumber / pageable.pageSize 포함.
     *
     * [IssueSearchQuery.page]/[IssueSearchQuery.size]가 포트에 그대로 전달되는지도 확인한다.
     */
    @Test
    fun `페이지네이션 응답 raw Page 형식 content totalElements pageable 포함`() {
        val querySlot = slot<IssueSearchQuery>()
        every { fakePort.search(capture(querySlot)) } returns
            IssueSearchPage(items = listOf(sampleHit()), total = 42L, page = 1, size = 10)

        mockMvc.perform(
            post("/api/v1/search/aql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "projectKey" to "PROJ",
                            "query" to "status = open",
                            "page" to 1,
                            "size" to 10,
                        ),
                    ),
                ),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.totalElements").value(42))
            .andExpect(jsonPath("$.pageable.pageNumber").value(1))
            .andExpect(jsonPath("$.pageable.pageSize").value(10))

        val captured = querySlot.captured
        assertThat(captured.page).isEqualTo(1)
        assertThat(captured.size).isEqualTo(10)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** 요청 바디 JSON 문자열을 생성한다. page/size는 기본값(0/50) 사용. */
    private fun requestBody(query: String): String =
        mapper.writeValueAsString(
            mapOf(
                "projectKey" to "PROJ",
                "query" to query,
                "page" to 0,
                "size" to 50,
            ),
        )

    /** 결과 1건짜리 [IssueSearchPage]를 반환한다. */
    private fun singleHitPage(): IssueSearchPage {
        return IssueSearchPage(items = listOf(sampleHit()), total = 1L, page = 0, size = 50)
    }

    /** 테스트용 [IssueSearchHit] 샘플. */
    private fun sampleHit(): IssueSearchHit =
        IssueSearchHit(
            key = "PROJ-1",
            summary = "슬라이스 테스트 이슈",
            typeKey = "task",
            currentStateKey = "open",
            assigneeId = null,
            priority = 3,
            priorityName = "Medium",
            projectKey = "PROJ",
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    /** [SecurityContextHolder]에 UUID 기반 인증 주체를 주입한다. */
    private fun setAuthenticated(userId: UUID) {
        val auth =
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }
}
