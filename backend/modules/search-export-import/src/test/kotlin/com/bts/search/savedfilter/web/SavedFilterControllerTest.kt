// SavedFilterController MockMvc 슬라이스 테스트 — CRUD + actor 게이트 + 에러 매핑 (FR-SR-03)

package com.bts.search.savedfilter.web

import com.bts.search.savedfilter.application.SavedFilterConflictException
import com.bts.search.savedfilter.application.SavedFilterDuplicateNameException
import com.bts.search.savedfilter.application.SavedFilterForbiddenException
import com.bts.search.savedfilter.application.SavedFilterNotFoundException
import com.bts.search.savedfilter.application.SavedFilterService
import com.bts.search.savedfilter.application.SavedFilterValidationException
import com.bts.search.savedfilter.domain.SavedFilter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

/**
 * [SavedFilterController] MockMvc 슬라이스 테스트.
 *
 * 서비스([SavedFilterService])는 mockk로 대체하고, 컨트롤러의 actor 추출·상태 매핑만 검증한다.
 * 예외→HTTP 매핑은 [SavedFilterExceptionHandler]를 controllerAdvice로 등록해 확인한다.
 */
class SavedFilterControllerTest {
    private val service = mockk<SavedFilterService>(relaxed = false)
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private lateinit var mockMvc: MockMvc

    private val actorId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(SavedFilterController(service))
                .setControllerAdvice(SavedFilterExceptionHandler())
                .build()
        val auth = UsernamePasswordAuthenticationToken(actorId.toString(), "n/a", emptyList())
        SecurityContextHolder.getContext().authentication = auth
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

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

    @Test
    fun `POST 필터 생성 201`() {
        every { service.create(actorId, "내 필터", "status = Open", "ATL") } returns sampleFilter()
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/filters")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"내 필터","aqlQuery":"status = Open","projectKey":"ATL"}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("내 필터"))
            .andExpect(jsonPath("$.isOwner").value(true))
    }

    @Test
    fun `GET 목록 200`() {
        every { service.listByOwner(actorId) } returns listOf(sampleFilter(), sampleFilter(name = "다른 필터"))
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/filters"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `GET 단건 200`() {
        val f = sampleFilter()
        every { service.getByIdForOwner(f.id!!, actorId) } returns f
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/filters/${f.id}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(f.id.toString()))
    }

    @Test
    fun `PUT 수정 200`() {
        val id = UUID.randomUUID()
        every { service.update(id, actorId, "수정", "status = Done", 0) } returns
            sampleFilter(id = id, name = "수정")
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .put("/api/v1/filters/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"수정","aqlQuery":"status = Done","version":0}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("수정"))
    }

    @Test
    fun `DELETE 삭제 204`() {
        val id = UUID.randomUUID()
        every { service.delete(id, actorId) } returns Unit
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/v1/filters/$id"),
            ).andExpect(status().isNoContent)
        verify { service.delete(id, actorId) }
    }

    @Test
    fun `미인증 401`() {
        SecurityContextHolder.clearContext()
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/filters"),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `잘못된 AQL 400`() {
        every { service.create(any(), any(), any(), any()) } throws SavedFilterValidationException("AQL 구문 오류")
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/filters")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"x","aqlQuery":"@@@","projectKey":"ATL"}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `이름 중복 409`() {
        every { service.create(any(), any(), any(), any()) } throws SavedFilterDuplicateNameException("내 필터")
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/filters")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"내 필터","aqlQuery":"status = Open","projectKey":"ATL"}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `비소유자 수정 403`() {
        val id = UUID.randomUUID()
        every { service.update(any(), any(), any(), any(), any()) } throws SavedFilterForbiddenException(id)
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .put("/api/v1/filters/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"x","aqlQuery":"status = Open","version":0}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `비가시 단건 404`() {
        val id = UUID.randomUUID()
        every { service.getByIdForOwner(id, actorId) } throws SavedFilterNotFoundException(id)
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/filters/$id"),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `OCC 충돌 409`() {
        val id = UUID.randomUUID()
        every { service.update(any(), any(), any(), any(), any()) } throws SavedFilterConflictException(id)
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .put("/api/v1/filters/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"x","aqlQuery":"status = Open","version":0}"""),
            ).andExpect(status().isConflict)
    }
}
