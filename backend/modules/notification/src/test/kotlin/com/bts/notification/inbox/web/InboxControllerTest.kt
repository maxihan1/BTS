// InboxController MockMvc 슬라이스 테스트 — 5 엔드포인트·상태코드·직렬화·미인증·404·400 시나리오

package com.bts.notification.inbox.web

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import com.bts.notification.inbox.application.InboxItemNotFoundException
import com.bts.notification.inbox.application.InboxService
import com.bts.notification.repository.InboxQuery
import com.bts.notification.repository.InboxTab
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.web.config.EnableSpringDataWebSupport
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
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
 * InboxController MockMvc 슬라이스 테스트.
 *
 * [InboxService] 는 MockK stub 으로 대체한다.
 * Spring Security 컨텍스트는 [SecurityContextHolder] 에 UUID 기반 Authentication 을 직접 주입한다.
 *
 * advice scoping: [InboxExceptionHandler] 만 등록 — NotificationExceptionHandler 는 다른 패키지(격리).
 * 각 에러 케이스에 errorCode 단언을 포함해 가짜 그린을 차단한다.
 *
 * 테스트 케이스.
 * - LIST-1.  GET /inbox 정상 → 200 + Page 구조(content/totalElements)
 * - LIST-2.  GET /inbox tab=UNREAD 바인딩 → service 에 InboxQuery(tab=UNREAD) 전달 확인
 * - LIST-3.  GET /inbox senderId=유효-UUID → 200 + service 정상 호출
 * - LIST-4.  GET /inbox senderId=비-UUID → 400 + errorCode=NOTIF_INBOX_INVALID
 * - LIST-5.  GET /inbox from=비-ISO → 400 + errorCode=NOTIF_INBOX_INVALID
 * - UNREAD-1. GET /inbox/unread-count → 200 + count 필드
 * - PATCH-R-1. PATCH /inbox/{id}/read 정상 → 204
 * - PATCH-R-2. PATCH /inbox/{id}/read 타인/부재 → 404 + errorCode=NOTIF_INBOX_NOT_FOUND (500 아님)
 * - PATCH-R-3. PATCH /inbox/{id}/read malformed JSON → 400 + errorCode=NOTIF_INBOX_INVALID
 * - PATCH-A-1. PATCH /inbox/{id}/archive 정상 → 204
 * - PATCH-A-2. PATCH /inbox/{id}/archive 타인/부재 → 404 + errorCode=NOTIF_INBOX_NOT_FOUND (500 아님)
 * - READ_ALL-1. POST /inbox/read-all ids 없음 → 200 + updated
 * - READ_ALL-2. POST /inbox/read-all ids 지정 → 200 + updated
 * - AUTH-1.  미인증 요청 → 401
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [InboxControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class InboxControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [InboxController], [InboxExceptionHandler] 와 MockK stub 빈을 등록한다.
     * [EnableSpringDataWebSupport] 로 [org.springframework.data.domain.Pageable] 바인딩을 활성화한다.
     */
    @Configuration
    @EnableWebMvc
    @EnableSpringDataWebSupport
    open class TestMvcConfig {
        @Bean
        open fun inboxService(): InboxService = mockk(relaxed = true)

        @Bean
        open fun inboxController(service: InboxService): InboxController = InboxController(service)

        @Bean
        open fun inboxExceptionHandler(): InboxExceptionHandler = InboxExceptionHandler()
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var service: InboxService

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val actorId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val notifId: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")
    private val senderId: UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001")
    private val fixedNow: Instant = Instant.parse("2026-06-25T10:00:00Z")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        setAuth(actorId)
    }

    @AfterEach
    fun tearDown() {
        clearMocks(service)
    }

    // ── GET /api/v1/users/me/inbox ─────────────────────────────────────────────

    /** LIST-1. 정상 목록 조회 → 200 + Page 구조(content/totalElements). */
    @Test
    fun `GET inbox 정상 요청 시 200 과 Page 구조 반환`() {
        val notif = buildNotification()
        val page = PageImpl(listOf(notif), PageRequest.of(0, 20), 1)
        every { service.listInbox(actorId, any(), any()) } returns page

        mockMvc.perform(get("/api/v1/users/me/inbox"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.content[0].id").value(notifId.toString()))
            .andExpect(jsonPath("$.content[0].eventType").value("issue.created"))
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    /** LIST-2. tab=UNREAD 쿼리 파라미터 → InboxQuery(tab=UNREAD) 전달 확인. */
    @Test
    fun `GET inbox tab=UNREAD 전달 시 service 에 UNREAD 탭 InboxQuery 전달`() {
        val page = PageImpl(emptyList<Notification>(), PageRequest.of(0, 20), 0)
        val capturedQuery = io.mockk.slot<InboxQuery>()
        every { service.listInbox(actorId, capture(capturedQuery), any()) } returns page

        mockMvc.perform(
            get("/api/v1/users/me/inbox")
                .param("tab", "UNREAD"),
        )
            .andExpect(status().isOk)

        org.assertj.core.api.Assertions.assertThat(capturedQuery.captured.tab).isEqualTo(InboxTab.UNREAD)
    }

    /** LIST-3. senderId=유효 UUID → 200 + service 정상 호출. */
    @Test
    fun `GET inbox senderId 유효 UUID 시 200 반환`() {
        val page = PageImpl(emptyList<Notification>(), PageRequest.of(0, 20), 0)
        every { service.listInbox(actorId, any(), any()) } returns page

        mockMvc.perform(
            get("/api/v1/users/me/inbox")
                .param("senderId", senderId.toString()),
        )
            .andExpect(status().isOk)
    }

    /** LIST-4. senderId=비-UUID → 400 + errorCode=NOTIF_INBOX_INVALID. */
    @Test
    fun `GET inbox senderId 가 비-UUID 이면 400 반환 — NOTIF_INBOX_INVALID errorCode`() {
        mockMvc.perform(
            get("/api/v1/users/me/inbox")
                .param("senderId", "not-a-uuid"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_INBOX_INVALID"))
    }

    /** LIST-5. from=비-ISO 형식 → 400 + errorCode=NOTIF_INBOX_INVALID. */
    @Test
    fun `GET inbox from 이 비-ISO 형식이면 400 반환 — NOTIF_INBOX_INVALID errorCode`() {
        mockMvc.perform(
            get("/api/v1/users/me/inbox")
                .param("from", "not-a-date"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_INBOX_INVALID"))
    }

    // ── GET /api/v1/users/me/inbox/unread-count ────────────────────────────────

    /** UNREAD-1. 미읽음 카운트 → 200 + count 필드. */
    @Test
    fun `GET inbox unread-count 요청 시 200 과 count 반환`() {
        every { service.unreadCount(actorId) } returns 7L

        mockMvc.perform(get("/api/v1/users/me/inbox/unread-count"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(7))
    }

    // ── PATCH /api/v1/users/me/inbox/{id}/read ────────────────────────────────

    /** PATCH-R-1. 읽음 변경 정상 → 204. */
    @Test
    fun `PATCH inbox 읽음 변경 정상 시 204 반환`() {
        justRun { service.markRead(actorId, notifId, true) }

        mockMvc.perform(
            patch("/api/v1/users/me/inbox/$notifId/read")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("read" to true))),
        )
            .andExpect(status().isNoContent)
    }

    /** PATCH-R-2. 타인/부재 → 404 + errorCode=NOTIF_INBOX_NOT_FOUND (500 아님 — 명시 단언). */
    @Test
    fun `PATCH inbox 읽음 변경 시 타인_부재이면 404 반환 — NOTIF_INBOX_NOT_FOUND errorCode (500 아님)`() {
        every { service.markRead(actorId, notifId, true) } throws InboxItemNotFoundException()

        mockMvc.perform(
            patch("/api/v1/users/me/inbox/$notifId/read")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("read" to true))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_INBOX_NOT_FOUND"))
    }

    /** PATCH-R-3. malformed JSON body → 400 + errorCode=NOTIF_INBOX_INVALID. */
    @Test
    fun `PATCH inbox 읽음 변경 시 malformed JSON 이면 400 반환 — NOTIF_INBOX_INVALID errorCode`() {
        mockMvc.perform(
            patch("/api/v1/users/me/inbox/$notifId/read")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-valid-json"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_INBOX_INVALID"))
    }

    // ── PATCH /api/v1/users/me/inbox/{id}/archive ─────────────────────────────

    /** PATCH-A-1. 보관 변경 정상 → 204. */
    @Test
    fun `PATCH inbox 보관 변경 정상 시 204 반환`() {
        justRun { service.markArchive(actorId, notifId, true) }

        mockMvc.perform(
            patch("/api/v1/users/me/inbox/$notifId/archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("archived" to true))),
        )
            .andExpect(status().isNoContent)
    }

    /** PATCH-A-2. 타인/부재 → 404 + errorCode=NOTIF_INBOX_NOT_FOUND (500 아님 — 명시 단언). */
    @Test
    fun `PATCH inbox 보관 변경 시 타인_부재이면 404 반환 — NOTIF_INBOX_NOT_FOUND errorCode (500 아님)`() {
        every { service.markArchive(actorId, notifId, false) } throws InboxItemNotFoundException()

        mockMvc.perform(
            patch("/api/v1/users/me/inbox/$notifId/archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("archived" to false))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("NOTIF_INBOX_NOT_FOUND"))
    }

    // ── POST /api/v1/users/me/inbox/read-all ─────────────────────────────────

    /** READ_ALL-1. ids 없이 전체 읽음 → 200 + updated 건수. */
    @Test
    fun `POST inbox read-all ids 없이 전체 읽음 처리 시 200 과 updated 반환`() {
        every { service.readAll(actorId, null) } returns 5

        mockMvc.perform(
            post("/api/v1/users/me/inbox/read-all")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.updated").value(5))
    }

    /** READ_ALL-2. ids 지정 읽음 → 200 + updated 건수. */
    @Test
    fun `POST inbox read-all ids 지정 시 200 과 updated 반환`() {
        val targetId = UUID.fromString("dddddddd-0000-0000-0000-000000000001")
        every { service.readAll(actorId, listOf(targetId)) } returns 1

        mockMvc.perform(
            post("/api/v1/users/me/inbox/read-all")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("ids" to listOf(targetId.toString())))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.updated").value(1))
    }

    // ── 인증 ──────────────────────────────────────────────────────────────────

    /** AUTH-1. 미인증 요청 → 401. */
    @Test
    fun `미인증 요청 시 401 반환`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/users/me/inbox"))
            .andExpect(status().isUnauthorized)

        // 이후 테스트를 위해 인증 복원
        setAuth(actorId)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification =
        Notification(
            id = notifId,
            recipientUserId = actorId,
            eventType = NotificationEventType.ISSUE_CREATED,
            channel = Channel.IN_APP,
            issueKey = "ATLAS-1",
            title = "테스트 알림",
            body = "알림 본문",
            payload = null,
            status = NotificationStatus.SENT,
            dedupKey = "test-dedup-key",
            readAt = null,
            createdAt = fixedNow,
            archivedAt = null,
            actorUserId = senderId,
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
