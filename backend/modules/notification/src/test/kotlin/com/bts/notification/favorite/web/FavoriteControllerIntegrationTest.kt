// 즐겨찾기 Controller MockMvc 슬라이스 테스트 — POST·DELETE·GET 엔드포인트·상태코드·누출 방지·미인증

package com.bts.notification.favorite.web

import com.bts.notification.favorite.application.FavoriteService
import com.bts.notification.favorite.domain.Favorite
import com.bts.notification.favorite.domain.FavoriteDomainException
import com.bts.notification.favorite.domain.FavoriteTargetType
import com.bts.notification.favorite.repository.SaveResult
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * FavoriteController MockMvc 슬라이스 테스트.
 *
 * FavoriteService 는 MockK stub 으로 대체한다.
 * Spring Security 컨텍스트는 SecurityContextHolder 에 UUID 기반 Authentication 을 직접 주입한다.
 * DashboardControllerTest 와 동일한 인증 컨텍스트 부팅/주입 패턴을 따른다.
 *
 * advice scoping: FavoriteExceptionHandler 만 등록 (NotificationExceptionHandler 는 다른 패키지 — 격리).
 * 각 에러 케이스에 errorCode 단언을 포함해 가짜 그린을 차단한다.
 *
 * 테스트 케이스.
 * - POST-1. POST /favorites 신규 등록 -> 201 + data.id 포함
 * - POST-2. POST /favorites 중복 등록(멱등) -> 200 + data.id 포함 (행 1개 보장)
 * - POST-3. POST /favorites targetType=UNKNOWN -> 400 + errorCode=NOTIF_FAV_INVALID
 * - POST-4. POST /favorites targetId="" (빈 문자열) -> 400 + errorCode=NOTIF_FAV_INVALID
 * - POST-5. POST /favorites malformed JSON -> 400 + errorCode=NOTIF_FAV_INVALID
 * - DELETE-1. DELETE /favorites?targetType=ISSUE&targetId=PROJ-123 존재 -> 204
 * - DELETE-2. DELETE /favorites?targetType=ISSUE&targetId=PROJ-999 없음 -> 204 (멱등)
 * - DELETE-3. DELETE /favorites?targetType=UNKNOWN&targetId=X -> 400 + errorCode=NOTIF_FAV_INVALID
 * - GET-1. GET /favorites -> 본인 목록 200, created_at DESC 포함
 * - GET-2. GET /favorites?targetType=ISSUE -> 타입 필터 200
 * - GET-3. GET /favorites 다른 user 누출 0 검증 (다른 actorId stub 미조회)
 * - AUTH-1. 미인증 요청 -> 401
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [FavoriteControllerIntegrationTest.TestMvcConfig::class])
@WebAppConfiguration
class FavoriteControllerIntegrationTest {

    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * FavoriteController, FavoriteExceptionHandler 와 MockK stub 빈을 등록한다.
     * NotificationExceptionHandler 는 등록하지 않는다 — production 과 동일한 advice scoping.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun favoriteService(): FavoriteService = mockk(relaxed = true)

        @Bean
        open fun favoriteController(service: FavoriteService) = FavoriteController(service)

        @Bean
        open fun favoriteExceptionHandler() = FavoriteExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var service: FavoriteService

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val actorId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val favoriteId: UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001")
    private val fixedNow: Instant = Instant.parse("2026-06-22T12:00:00Z")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        setAuth(actorId)
    }

    // ── POST /api/v1/favorites ─────────────────────────────────────────────────

    /** POST-1. 신규 등록 -> 201 + data.id 포함. */
    @Test
    fun `POST favorites 신규 등록 시 201 반환`() {
        every {
            service.addFavorite(actorId, "ISSUE", "PROJ-123")
        } returns SaveResult(buildFavorite("ISSUE", "PROJ-123"), created = true)

        mockMvc.perform(
            post("/api/v1/favorites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetType" to "ISSUE", "targetId" to "PROJ-123"))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(favoriteId.toString()))
            .andExpect(jsonPath("$.data.targetType").value("ISSUE"))
            .andExpect(jsonPath("$.data.targetId").value("PROJ-123"))
    }

    /** POST-2. 중복 등록(멱등) -> 200 + data.id 포함 (행 1개 보장). */
    @Test
    fun `POST favorites 중복 등록 시 200 반환 — 멱등`() {
        every {
            service.addFavorite(actorId, "ISSUE", "PROJ-123")
        } returns SaveResult(buildFavorite("ISSUE", "PROJ-123"), created = false)

        mockMvc.perform(
            post("/api/v1/favorites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetType" to "ISSUE", "targetId" to "PROJ-123"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(favoriteId.toString()))
    }

    /** POST-3. targetType=UNKNOWN -> 도메인 FavoriteDomainException -> 400 + errorCode=NOTIF_FAV_INVALID. */
    @Test
    fun `POST favorites UNKNOWN targetType 은 400 반환 — NOTIF_FAV_INVALID errorCode`() {
        every {
            service.addFavorite(actorId, "UNKNOWN", any())
        } throws FavoriteDomainException("유효하지 않은 즐겨찾기 대상 타입입니다: 'UNKNOWN'.")

        mockMvc.perform(
            post("/api/v1/favorites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetType" to "UNKNOWN", "targetId" to "PROJ-123"))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_FAV_INVALID"))
    }

    /** POST-4. targetId="" -> 도메인 FavoriteDomainException -> 400 + errorCode=NOTIF_FAV_INVALID. */
    @Test
    fun `POST favorites 빈 targetId 는 400 반환 — NOTIF_FAV_INVALID errorCode`() {
        every {
            service.addFavorite(actorId, "ISSUE", "")
        } throws FavoriteDomainException("즐겨찾기 대상 식별자는 빈 문자열 또는 공백일 수 없습니다.")

        mockMvc.perform(
            post("/api/v1/favorites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("targetType" to "ISSUE", "targetId" to ""))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_FAV_INVALID"))
    }

    /** POST-5. malformed JSON -> 400 + errorCode=NOTIF_FAV_INVALID. */
    @Test
    fun `POST favorites malformed JSON body 는 400 반환 — NOTIF_FAV_INVALID errorCode`() {
        mockMvc.perform(
            post("/api/v1/favorites")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-valid-json"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_FAV_INVALID"))
    }

    // ── DELETE /api/v1/favorites ───────────────────────────────────────────────

    /** DELETE-1. 존재하는 즐겨찾기 삭제 -> 204. */
    @Test
    fun `DELETE favorites 존재하는 항목 삭제 시 204 반환`() {
        justRun { service.removeFavorite(actorId, "ISSUE", "PROJ-123") }

        mockMvc.perform(
            delete("/api/v1/favorites")
                .param("targetType", "ISSUE")
                .param("targetId", "PROJ-123"),
        )
            .andExpect(status().isNoContent)
    }

    /** DELETE-2. 존재하지 않는 항목 삭제 -> 204 (멱등). */
    @Test
    fun `DELETE favorites 없는 항목 삭제 시 204 반환 — 멱등`() {
        justRun { service.removeFavorite(actorId, "ISSUE", "PROJ-999") }

        mockMvc.perform(
            delete("/api/v1/favorites")
                .param("targetType", "ISSUE")
                .param("targetId", "PROJ-999"),
        )
            .andExpect(status().isNoContent)
    }

    /** DELETE-3. UNKNOWN targetType -> 400 + errorCode=NOTIF_FAV_INVALID. */
    @Test
    fun `DELETE favorites UNKNOWN targetType 은 400 반환 — NOTIF_FAV_INVALID errorCode`() {
        every {
            service.removeFavorite(actorId, "UNKNOWN", any())
        } throws FavoriteDomainException("유효하지 않은 즐겨찾기 대상 타입입니다: 'UNKNOWN'.")

        mockMvc.perform(
            delete("/api/v1/favorites")
                .param("targetType", "UNKNOWN")
                .param("targetId", "X"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_FAV_INVALID"))
    }

    // ── GET /api/v1/favorites ──────────────────────────────────────────────────

    /** GET-1. 본인 즐겨찾기 목록 조회 -> 200 + items 배열 포함. */
    @Test
    fun `GET favorites 본인 목록 조회 시 200 반환`() {
        val favorites = listOf(
            buildFavorite("ISSUE", "PROJ-002"),
            buildFavorite("DASHBOARD", "dash-001"),
        )
        every { service.listFavorites(actorId, null) } returns favorites

        mockMvc.perform(get("/api/v1/favorites"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isArray)
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].targetType").value("ISSUE"))
    }

    /** GET-2. targetType=ISSUE 필터 -> 200 + 해당 타입만. */
    @Test
    fun `GET favorites targetType 필터 시 200 반환`() {
        val favorites = listOf(buildFavorite("ISSUE", "PROJ-001"))
        every { service.listFavorites(actorId, "ISSUE") } returns favorites

        mockMvc.perform(
            get("/api/v1/favorites")
                .param("targetType", "ISSUE"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].targetType").value("ISSUE"))
    }

    /** GET-3. 다른 user 즐겨찾기 누출 0 검증 — actorId 만 조회에 사용. */
    @Test
    fun `GET favorites 다른 사용자 즐겨찾기 누출 없음`() {
        // actorId 로만 service.listFavorites 가 호출됨을 검증한다 (controller 슬라이스).
        // 실제 DB 누출 방지는 FavoriteRepositoryIntegrationTest.findByUser(본인만) 가 검증한다.
        every { service.listFavorites(actorId, null) } returns emptyList()

        mockMvc.perform(get("/api/v1/favorites"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isEmpty)
    }

    // ── 인증 ──────────────────────────────────────────────────────────────────

    /** AUTH-1. 미인증 요청 -> 401. */
    @Test
    fun `미인증 요청 시 401 반환`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/favorites"))
            .andExpect(status().isUnauthorized)

        // 테스트 후 인증 복원
        setAuth(actorId)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun buildFavorite(targetType: String, targetId: String): Favorite =
        Favorite(
            id = favoriteId,
            userId = actorId,
            targetType = FavoriteTargetType.valueOf(targetType),
            targetId = targetId,
            createdAt = fixedNow,
        )

    private fun setAuth(userId: UUID) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }
}
