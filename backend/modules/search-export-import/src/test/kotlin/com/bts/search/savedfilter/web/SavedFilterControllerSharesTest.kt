// SavedFilterController + SavedFilterSearchController 공유/가시성 MockMvc 슬라이스 테스트 (FR-SR-03 PR2)

package com.bts.search.savedfilter.web

import com.bts.search.savedfilter.application.SavedFilterForbiddenException
import com.bts.search.savedfilter.application.SavedFilterNotFoundException
import com.bts.search.savedfilter.application.SavedFilterService
import com.bts.search.savedfilter.application.SavedFilterWithShares
import com.bts.search.savedfilter.domain.SavedFilter
import com.bts.search.savedfilter.domain.SavedFilterShare
import com.bts.search.savedfilter.domain.ShareType
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

/**
 * [SavedFilterController] 공유/가시성 관련 MockMvc 슬라이스 테스트 (PR2).
 *
 * 검증 범위:
 * - (a) POST/PUT 바디 shares 파싱+검증(알 수 없는 shareType → 400, 500 아님).
 * - (b) 읽기 응답(list/get/shared)에 shares+isOwner 포함.
 * - (c) 가시-비소유 GET /{id} → 200 + shares (getVisibleById 경유).
 * - (d) GET /filters/shared 200 + page/size 검증.
 * - (e) 가시-비소유 PUT/DELETE → 403, 비가시 → 404.
 * - (f) /{id}/search 가시성(getVisibleById 경유).
 *
 * 서비스([SavedFilterService])와 [IssueSearchPort]는 mockk로 대체하고,
 * 예외→HTTP 매핑은 [SavedFilterExceptionHandler]를 controllerAdvice로 등록해 확인한다.
 */
@Suppress("LargeClass")
class SavedFilterControllerSharesTest {
    private val service = mockk<SavedFilterService>(relaxed = false)
    private val issueSearchPort = mockk<IssueSearchPort>(relaxed = false)
    private lateinit var mockMvc: MockMvc

    /** actor 1: 소유자. */
    private val actorId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    /** actor 2: 비소유자(다른 owner). */
    private val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    SavedFilterController(service),
                    SavedFilterSearchController(service, issueSearchPort),
                ).setControllerAdvice(SavedFilterExceptionHandler())
                .build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(actorId.toString(), "n/a", emptyList())
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun sampleFilter(
        id: UUID = UUID.randomUUID(),
        owner: UUID = actorId,
        name: String = "내 필터",
    ): SavedFilter =
        SavedFilter(
            id = id,
            ownerId = owner,
            name = name,
            aqlQuery = "status = Open",
            projectKey = "ATL",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-26T00:00:00Z"),
            version = 0,
        )

    private fun withShares(
        filter: SavedFilter,
        shares: List<SavedFilterShare> = emptyList(),
    ): SavedFilterWithShares = SavedFilterWithShares(filter, shares)

    // ── (a) POST/PUT 바디 shares 파싱·검증 ────────────────────────────────────

    /**
     * 알 수 없는 shareType → ShareType.from 이 IllegalArgumentException → handleIllegalArgument → 400.
     * 500이 아님을 명시 단언한다(catch-all 삼킴 방지).
     */
    @Test
    fun `POST 알 수 없는 shareType 400 (500 아님)`() {
        val body =
            """{"name":"x","aqlQuery":"status = Open","projectKey":"ATL","shares":[{"shareType":"BAD"}]}"""
        mockMvc
            .perform(
                post("/api/v1/filters")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
    }

    /**
     * shares null → shareType 검증 실패 → 400.
     * shareType이 null인 ShareRequest → toDomain() 에서 IllegalArgumentException → 400.
     */
    @Test
    fun `POST shareType null → 400`() {
        val body =
            """{"name":"x","aqlQuery":"status = Open","projectKey":"ATL","shares":[{"shareType":null}]}"""
        mockMvc
            .perform(
                post("/api/v1/filters")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
    }

    /**
     * PUT 알 수 없는 shareType → 400 (500 아님).
     */
    @Test
    fun `PUT 알 수 없는 shareType 400 (500 아님)`() {
        val id = UUID.randomUUID()
        val body =
            """{"name":"x","aqlQuery":"status = Open","version":0,"shares":[{"shareType":"BAD","targetId":"ATL"}]}"""
        mockMvc
            .perform(
                put("/api/v1/filters/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
    }

    // ── (b) 읽기 응답에 shares + isOwner ─────────────────────────────────────

    /**
     * GET 목록 응답 — shares 배열과 isOwner 필드가 각 항목에 포함된다.
     */
    @Test
    fun `GET 목록 응답에 shares와 isOwner 포함`() {
        val share = SavedFilterShare.create(ShareType.AUTHENTICATED, null)
        val filter = sampleFilter()
        every { service.listOwnedWithShares(actorId) } returns listOf(withShares(filter, listOf(share)))

        mockMvc
            .perform(get("/api/v1/filters"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].shares").isArray)
            .andExpect(jsonPath("$[0].shares[0].shareType").value("AUTHENTICATED"))
            .andExpect(jsonPath("$[0].isOwner").value(true))
    }

    /**
     * GET 단건 응답 — shares 배열과 isOwner 필드 포함.
     */
    @Test
    fun `GET 단건 응답에 shares와 isOwner 포함`() {
        val filter = sampleFilter()
        val share = SavedFilterShare.create(ShareType.PROJECT, "ATL")
        every { service.getVisibleById(filter.id!!, actorId) } returns withShares(filter, listOf(share))

        mockMvc
            .perform(get("/api/v1/filters/${filter.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shares").isArray)
            .andExpect(jsonPath("$.shares[0].shareType").value("PROJECT"))
            .andExpect(jsonPath("$.shares[0].targetId").value("ATL"))
            .andExpect(jsonPath("$.isOwner").value(true))
    }

    /**
     * GET shared 응답 — shares 배열 포함.
     */
    @Test
    fun `GET shared 응답에 shares 포함`() {
        val filter = sampleFilter(owner = otherId)
        val share = SavedFilterShare.create(ShareType.GROUP, "group-1")
        every { service.listSharedWith(actorId, 0, DEFAULT_PAGE_SIZE) } returns
            listOf(withShares(filter, listOf(share)))

        mockMvc
            .perform(get("/api/v1/filters/shared"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].shares[0].shareType").value("GROUP"))
            .andExpect(jsonPath("$[0].isOwner").value(false))
    }

    // ── (c) 가시-비소유 GET /{id} → 200 (B1) ─────────────────────────────────

    /**
     * B1 — getVisibleById 경유 검증.
     * actor는 소유자가 아니지만 필터가 공유되어 가시 → 200, isOwner=false.
     */
    @Test
    fun `가시-비소유 GET id → 200 + isOwner false (getVisibleById 경유)`() {
        val filter = sampleFilter(owner = otherId)
        every { service.getVisibleById(filter.id!!, actorId) } returns withShares(filter, emptyList())

        mockMvc
            .perform(get("/api/v1/filters/${filter.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isOwner").value(false))
            .andExpect(jsonPath("$.shares").isArray)
    }

    // ── (d) GET /filters/shared 200 + page/size ───────────────────────────────

    /**
     * 기본 page=0, size=20 → 200.
     */
    @Test
    fun `GET shared 기본 파라미터 200`() {
        every { service.listSharedWith(actorId, 0, DEFAULT_PAGE_SIZE) } returns emptyList()

        mockMvc
            .perform(get("/api/v1/filters/shared"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }

    /**
     * page=2, size=10 → service 호출 파라미터 전달.
     */
    @Test
    fun `GET shared page=2 size=10 → 200`() {
        every { service.listSharedWith(actorId, 2, 10) } returns emptyList()

        mockMvc
            .perform(get("/api/v1/filters/shared?page=2&size=10"))
            .andExpect(status().isOk)
    }

    /**
     * page=-1 → 400 DoS 방어.
     */
    @Test
    fun `GET shared page 음수 → 400`() {
        mockMvc
            .perform(get("/api/v1/filters/shared?page=-1"))
            .andExpect(status().isBadRequest)
    }

    /**
     * size=0 → 400 (최솟값 1).
     */
    @Test
    fun `GET shared size 0 → 400`() {
        mockMvc
            .perform(get("/api/v1/filters/shared?size=0"))
            .andExpect(status().isBadRequest)
    }

    /**
     * size=101 → 400 (최댓값 100).
     */
    @Test
    fun `GET shared size 101 → 400`() {
        mockMvc
            .perform(get("/api/v1/filters/shared?size=101"))
            .andExpect(status().isBadRequest)
    }

    // ── (e) 가시-비소유 PUT/DELETE → 403, 비가시 → 404 ────────────────────────

    /**
     * 가시-비소유 PUT → SavedFilterForbiddenException → 403.
     */
    @Test
    fun `가시-비소유 PUT → 403`() {
        val id = UUID.randomUUID()
        every {
            service.update(any(), any(), any(), any(), any(), any())
        } throws SavedFilterForbiddenException(id)

        mockMvc
            .perform(
                put("/api/v1/filters/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"x","aqlQuery":"status = Open","version":0}"""),
            ).andExpect(status().isForbidden)
    }

    /**
     * 가시-비소유 DELETE → SavedFilterForbiddenException → 403.
     */
    @Test
    fun `가시-비소유 DELETE → 403`() {
        val id = UUID.randomUUID()
        every { service.delete(any(), any()) } throws SavedFilterForbiddenException(id)

        mockMvc
            .perform(delete("/api/v1/filters/$id"))
            .andExpect(status().isForbidden)
    }

    /**
     * 비가시 PUT → SavedFilterNotFoundException → 404 (존재 은닉).
     */
    @Test
    fun `비가시 PUT → 404 존재은닉`() {
        val id = UUID.randomUUID()
        every {
            service.update(any(), any(), any(), any(), any(), any())
        } throws SavedFilterNotFoundException(id)

        mockMvc
            .perform(
                put("/api/v1/filters/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"x","aqlQuery":"status = Open","version":0}"""),
            ).andExpect(status().isNotFound)
    }

    /**
     * 가시-비소유 403 응답 — detail에 id가 노출되지 않음을 확인한다.
     * (교훈 fr-pm-04-guard-exception-message-http-leak).
     */
    @Test
    fun `403 응답 detail에 필터 id 미노출`() {
        val id = UUID.fromString("aaaabbbb-aaaa-bbbb-aaaa-bbbbaaaabbbb")
        every { service.delete(any(), any()) } throws SavedFilterForbiddenException(id)

        mockMvc
            .perform(delete("/api/v1/filters/$id"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("이 작업을 수행할 권한이 없습니다."))
    }

    // ── (f) /{id}/search 가시성(getVisibleById 경유) ──────────────────────────

    /**
     * 가시-비소유 search → getVisibleById 경유 → 200.
     */
    @Test
    fun `가시-비소유 search → 200 (getVisibleById 경유)`() {
        val filter = sampleFilter(owner = otherId)
        every { service.getVisibleById(filter.id!!, actorId) } returns withShares(filter, emptyList())
        every { issueSearchPort.search(any()) } returns IssueSearchPage.empty(0, 20)

        mockMvc
            .perform(get("/api/v1/filters/${filter.id}/search"))
            .andExpect(status().isOk)
    }

    /**
     * 비가시 search → getVisibleById가 SavedFilterNotFoundException → 404.
     */
    @Test
    fun `비가시 search → 404`() {
        val id = UUID.randomUUID()
        every { service.getVisibleById(id, actorId) } throws SavedFilterNotFoundException(id)

        mockMvc
            .perform(get("/api/v1/filters/$id/search"))
            .andExpect(status().isNotFound)
    }

    private companion object {
        /** GET /shared 기본 page 크기. */
        const val DEFAULT_PAGE_SIZE = 20
    }
}
