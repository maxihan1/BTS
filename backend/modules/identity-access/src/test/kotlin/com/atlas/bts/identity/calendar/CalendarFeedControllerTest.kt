// 캘린더 피드 관리 컨트롤러 단위 테스트 — me-scope JWT 위임·PAT 403·발급 URL 조립 (FR-CA-02 Task 5)

package com.atlas.bts.identity.calendar

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant
import java.util.UUID

/**
 * [CalendarFeedController] 단위 테스트 (FR-CA-02 Task 5).
 *
 * FR-CA-01 [CalendarController] 관례를 따라 @WebMvcTest 보안 슬라이스(다수 @MockBean 필요·취약) 대신
 * 컨트롤러 로직을 직접 검증한다. 실제 SecurityFilterChain(PAT 필터로 인한 jwt=null·미인증 401)은
 * Task 7 통합 테스트가 prod 부팅으로 커버한다.
 *
 * 검증.
 *  - 정상 JWT → 서비스 위임 + 상태코드(201/200/204) + 발급 응답 URL 조립.
 *  - PAT(비-JWT principal → jwt=null) → 403 `calendar_feed_requires_interactive_login`, 서비스 미호출.
 */
class CalendarFeedControllerTest {
    private val service = mockk<CalendarFeedService>(relaxed = true)
    private val controller = CalendarFeedController(service, FEED_BASE_URL)

    private fun jwtOf(userId: UUID): Jwt =
        Jwt
            .withTokenValue("token")
            .header("alg", "none")
            .subject(userId.toString())
            .build()

    @Test
    fun `issueFeed 는 정상 JWT 로 201 과 발급 URL·원문 토큰을 반환한다`() {
        val userId = UUID.randomUUID()
        val createdAt = Instant.parse("2026-07-09T10:00:00Z")
        every { service.issue(userId) } returns IssuedCalendarFeed(rawToken = "abcdef0123", createdAt = createdAt)

        val response = controller.issueFeed(jwtOf(userId))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body as IssueFeedResponse
        assertThat(body.feedUrl).isEqualTo("$FEED_BASE_URL/ical/feed/abcdef0123.ics")
        assertThat(body.token).isEqualTo("abcdef0123")
        assertThat(body.createdAt).isEqualTo(createdAt)
        verify(exactly = 1) { service.issue(userId) }
    }

    @Test
    fun `issueFeed 는 PAT(jwt=null) 에 403 을 반환하고 서비스를 호출하지 않는다`() {
        val response = controller.issueFeed(null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        @Suppress("UNCHECKED_CAST")
        val body = response.body as Map<String, Any?>
        assertThat(body["error"]).isEqualTo("calendar_feed_requires_interactive_login")
        verify(exactly = 0) { service.issue(any()) }
    }

    @Test
    fun `getFeedStatus 는 정상 JWT 로 200 과 발급 상태를 반환한다`() {
        val userId = UUID.randomUUID()
        val createdAt = Instant.parse("2026-07-09T10:00:00Z")
        every { service.status(userId) } returns CalendarFeedStatus(enabled = true, createdAt = createdAt)

        val response = controller.getFeedStatus(jwtOf(userId))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body as FeedStatusResponse
        assertThat(body.enabled).isTrue()
        assertThat(body.createdAt).isEqualTo(createdAt)
    }

    @Test
    fun `getFeedStatus 는 PAT 에 403 을 반환한다`() {
        val response = controller.getFeedStatus(null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        verify(exactly = 0) { service.status(any()) }
    }

    @Test
    fun `revokeFeed 는 정상 JWT 로 204 를 반환하고 서비스를 호출한다`() {
        val userId = UUID.randomUUID()

        val response = controller.revokeFeed(jwtOf(userId))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        verify(exactly = 1) { service.revoke(userId) }
    }

    @Test
    fun `revokeFeed 는 PAT 에 403 을 반환하고 서비스를 호출하지 않는다`() {
        val response = controller.revokeFeed(null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        verify(exactly = 0) { service.revoke(any()) }
    }

    private companion object {
        private const val FEED_BASE_URL = "https://bts.example.com"
    }
}
