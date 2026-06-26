// SavedFilterSearchController MockMvc 슬라이스 테스트 — 저장 필터 실행(viewer 권한) (FR-SR-03)

package com.bts.search.savedfilter.web

import com.bts.search.savedfilter.application.SavedFilterNotFoundException
import com.bts.search.savedfilter.application.SavedFilterService
import com.bts.search.savedfilter.domain.SavedFilter
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

/**
 * [SavedFilterSearchController] MockMvc 슬라이스 테스트.
 *
 * 저장된 필터를 viewer 권한으로 재실행하는 경로를 검증한다.
 * 서비스·[IssueSearchPort]는 mockk로 대체하고, [IssueSearchQuery] 구성(viewerUserId=actor,
 * projectKey=필터값)을 slot으로 캡처해 단언한다.
 */
class SavedFilterSearchControllerTest {
    private val service = mockk<SavedFilterService>()
    private val issueSearchPort = mockk<IssueSearchPort>()
    private lateinit var mockMvc: MockMvc

    private val actorId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(SavedFilterSearchController(service, issueSearchPort))
                .setControllerAdvice(SavedFilterExceptionHandler())
                .build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(actorId.toString(), "n/a", emptyList())
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    private fun filter(id: UUID): SavedFilter =
        SavedFilter(
            id = id,
            ownerId = actorId,
            name = "내 필터",
            aqlQuery = "status = Open",
            projectKey = "ATL",
            createdAt = Instant.parse("2026-06-26T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-26T00:00:00Z"),
            version = 0,
        )

    @Test
    fun `저장 필터 실행 200 + viewer 권한 쿼리 구성`() {
        val id = UUID.randomUUID()
        every { service.getByIdForOwner(id, actorId) } returns filter(id)
        val querySlot = slot<IssueSearchQuery>()
        every { issueSearchPort.search(capture(querySlot)) } returns IssueSearchPage.empty(0, 20)

        mockMvc
            .perform(MockMvcRequestBuilders.get("/api/v1/filters/$id/search"))
            .andExpect(status().isOk)

        verify { issueSearchPort.search(any()) }
        assertThat(querySlot.captured.viewerUserId).isEqualTo(actorId)
        assertThat(querySlot.captured.projectKey).isEqualTo("ATL")
    }

    @Test
    fun `비가시 필터 실행 404`() {
        val id = UUID.randomUUID()
        every { service.getByIdForOwner(id, actorId) } throws SavedFilterNotFoundException(id)
        mockMvc
            .perform(MockMvcRequestBuilders.get("/api/v1/filters/$id/search"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `미인증 실행 401`() {
        SecurityContextHolder.clearContext()
        mockMvc
            .perform(MockMvcRequestBuilders.get("/api/v1/filters/${UUID.randomUUID()}/search"))
            .andExpect(status().isUnauthorized)
    }
}
