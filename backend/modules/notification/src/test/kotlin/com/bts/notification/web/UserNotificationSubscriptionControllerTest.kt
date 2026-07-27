// UserNotificationSubscriptionController MockMvc 슬라이스 테스트 — 구독 매트릭스 GET/PATCH (FR-NT-04 Task 5)

package com.bts.notification.web

import com.bts.notification.application.SubscriptionCell
import com.bts.notification.application.SubscriptionPatchEntry
import com.bts.notification.application.UserSubscriptionService
import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * UserNotificationSubscriptionController MockMvc 슬라이스 테스트.
 *
 * [UserSubscriptionService] 는 MockK stub 으로 대체한다.
 * Spring Security 컨텍스트는 [SecurityContextHolder] 에 직접 UUID 기반 Authentication 을 주입해 사용한다.
 *
 * ### 테스트 케이스
 * - GET-1. GET /api/v1/users/me/notifications — 인증 사용자 → 200 + subscriptions 20개
 * - GET-2. GET — 셀 형식 {eventType: wireValue, channel: name, enabled: bool}
 * - GET-3. GET — 이력 없으면 전부 enabled=true
 * - PATCH-1. PATCH body 정상 → 200 + 반영된 매트릭스
 * - PATCH-2. PATCH channel="SLACK" → 400 (EC1)
 * - PATCH-3. PATCH eventType 미지원 문자열 → 400 (EC2)
 * - PATCH-4. PATCH channel 미지원 문자열 → 400
 * - PATCH-5. PATCH subscriptions 101개 (상한 초과) → 400
 * - PATCH-6. PATCH subscriptions 빈 배열(0개) → 200 (EC10 no-op)
 * - AUTH-1. 미인증 GET → 401 (EC6)
 * - AUTH-2. 미인증 PATCH → 401 (EC6)
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [UserNotificationSubscriptionControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class UserNotificationSubscriptionControllerTest {
    /**
     * 테스트 전용 Spring MVC 최소 컨텍스트.
     *
     * [UserNotificationSubscriptionController], [NotificationExceptionHandler] 와 MockK stub Bean 을 등록한다.
     */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun userSubscriptionService(): UserSubscriptionService = mockk(relaxed = true)

        @Bean
        @Suppress("MaxLineLength")
        open fun userNotificationSubscriptionController(service: UserSubscriptionService): UserNotificationSubscriptionController =
            UserNotificationSubscriptionController(service)

        @Bean
        open fun notificationExceptionHandler(): NotificationExceptionHandler = NotificationExceptionHandler()
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var userSubscriptionService: UserSubscriptionService

    lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val actorId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        val auth =
            UsernamePasswordAuthenticationToken(
                actorId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }

    // ── GET-1. 인증 사용자 → 200 + subscriptions 20개 ───────────────────────────

    @Test
    fun `GET users me notifications — 인증 사용자면 200과 22개 셀 반환`() {
        every { userSubscriptionService.getMatrix(actorId) } returns fullMatrix(allEnabled = true)

        mockMvc.perform(get("/api/v1/users/me/notifications").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.subscriptions.length()").value(22))
    }

    // ── GET-2. 셀 형식 검증 ────────────────────────────────────────────────────

    @Test
    fun `GET users me notifications — 셀에 eventType wireValue + channel name + enabled 포함`() {
        every { userSubscriptionService.getMatrix(actorId) } returns fullMatrix(allEnabled = true)

        mockMvc.perform(get("/api/v1/users/me/notifications").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.subscriptions[0].eventType").value("issue.created"))
            .andExpect(jsonPath("$.data.subscriptions[0].channel").value("IN_APP"))
            .andExpect(jsonPath("$.data.subscriptions[0].enabled").value(true))
    }

    // ── GET-3. 이력 없으면 전부 enabled=true ──────────────────────────────────

    @Test
    fun `GET users me notifications — 이력 없으면 모든 셀 enabled=true`() {
        every { userSubscriptionService.getMatrix(actorId) } returns fullMatrix(allEnabled = true)

        mockMvc.perform(get("/api/v1/users/me/notifications").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.subscriptions[0].enabled").value(true))
            .andExpect(jsonPath("$.data.subscriptions[19].enabled").value(true))
    }

    // ── PATCH-1. 정상 요청 → 200 + 반영된 매트릭스 ──────────────────────────────

    @Test
    fun `PATCH users me notifications — 정상 요청이면 200과 반영된 매트릭스 반환`() {
        val patchedMatrix = fullMatrix(allEnabled = true).toMutableList()
        // issue.commented + EMAIL 셀은 false
        val commentedEmailIdx =
            patchedMatrix.indexOfFirst {
                it.eventType == NotificationEventType.ISSUE_COMMENTED && it.channel == Channel.EMAIL
            }
        if (commentedEmailIdx >= 0) {
            patchedMatrix[commentedEmailIdx] =
                patchedMatrix[commentedEmailIdx].copy(enabled = false)
        }

        val entriesSlot = slot<List<SubscriptionPatchEntry>>()
        every { userSubscriptionService.patch(actorId, capture(entriesSlot)) } returns patchedMatrix

        val body =
            mapOf(
                "subscriptions" to
                    listOf(
                        mapOf("eventType" to "issue.commented", "channel" to "EMAIL", "enabled" to false),
                    ),
            )

        mockMvc.perform(
            patch("/api/v1/users/me/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.subscriptions.length()").value(22))

        verify { userSubscriptionService.patch(actorId, any()) }
    }

    // ── PATCH-2. channel=SLACK → 400 (EC1) ──────────────────────────────────

    @Test
    fun `PATCH users me notifications channel이 SLACK이면 400`() {
        every { userSubscriptionService.patch(actorId, any()) } throws
            IllegalArgumentException("설정 불가능한 채널: SLACK")

        val body =
            mapOf(
                "subscriptions" to
                    listOf(
                        mapOf("eventType" to "issue.commented", "channel" to "SLACK", "enabled" to false),
                    ),
            )

        mockMvc.perform(
            patch("/api/v1/users/me/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── PATCH-3. eventType 미지원 문자열 → 400 (EC2) ─────────────────────────

    @Test
    fun `PATCH users me notifications eventType이 미지원 문자열이면 400`() {
        val body =
            mapOf(
                "subscriptions" to
                    listOf(
                        mapOf("eventType" to "not.a.valid.event", "channel" to "EMAIL", "enabled" to false),
                    ),
            )

        mockMvc.perform(
            patch("/api/v1/users/me/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── PATCH-4. channel 미지원 문자열 → 400 ────────────────────────────────

    @Test
    fun `PATCH users me notifications channel이 미지원 문자열이면 400`() {
        val body =
            mapOf(
                "subscriptions" to
                    listOf(
                        mapOf("eventType" to "issue.commented", "channel" to "INVALID_CHANNEL", "enabled" to false),
                    ),
            )

        mockMvc.perform(
            patch("/api/v1/users/me/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── PATCH-5. subscriptions 상한 초과 → 400 ──────────────────────────────

    @Test
    fun `PATCH users me notifications subscriptions가 상한 초과이면 400`() {
        val oversizedBody =
            mapOf(
                "subscriptions" to
                    (1..101).map { i ->
                        mapOf("eventType" to "issue.commented", "channel" to "EMAIL", "enabled" to (i % 2 == 0))
                    },
            )

        mockMvc.perform(
            patch("/api/v1/users/me/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(oversizedBody)),
        )
            .andExpect(status().isBadRequest)
    }

    // ── PATCH-6. subscriptions 빈 배열(0개) — 여전히 허용 (EC10 no-op) ──────

    @Test
    fun `PATCH users me notifications subscriptions가 빈 배열이면 200`() {
        every { userSubscriptionService.patch(actorId, emptyList()) } returns fullMatrix(allEnabled = true)

        val emptyBody = mapOf("subscriptions" to emptyList<Any>())

        mockMvc.perform(
            patch("/api/v1/users/me/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(emptyBody)),
        )
            .andExpect(status().isOk)
    }

    // ── AUTH-1. 미인증 GET → 401 ─────────────────────────────────────────────

    @Test
    fun `GET users me notifications 미인증이면 401`() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/v1/users/me/notifications").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized)
    }

    // ── AUTH-2. 미인증 PATCH → 401 ───────────────────────────────────────────

    @Test
    fun `PATCH users me notifications 미인증이면 401`() {
        SecurityContextHolder.clearContext()

        val body = mapOf("subscriptions" to emptyList<Any>())

        mockMvc.perform(
            patch("/api/v1/users/me/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * 10 eventType × {IN_APP, EMAIL} = 20셀 전체 매트릭스를 생성하는 헬퍼.
     *
     * @param allEnabled 전체 셀의 enabled 초기값
     * @return 20개 [SubscriptionCell] 목록
     */
    private fun fullMatrix(allEnabled: Boolean): List<SubscriptionCell> =
        NotificationEventType.entries.flatMap { eventType ->
            listOf(Channel.IN_APP, Channel.EMAIL).map { channel ->
                SubscriptionCell(eventType = eventType, channel = channel, enabled = allEnabled)
            }
        }
}
