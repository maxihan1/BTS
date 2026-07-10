// 익명 iCal 피드 컨트롤러 단위 테스트 — 200 text/calendar·404 수렴·캐시 헤더 (FR-CA-02 Task 6)

package com.atlas.bts.identity.calendar

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * [IcalFeedController] 단위 테스트 (FR-CA-02 Task 6).
 *
 * 실제 SecurityFilterChain permitAll(익명 GET 200·비-GET 401/405)은 Task 7 통합 테스트가
 * prod 부팅으로 커버한다. 여기서는 컨트롤러 로직(200 본문/헤더·404 수렴)만 검증한다.
 *
 * 검증.
 *  - 유효 토큰 → 200 + `text/calendar` + `Cache-Control: private, no-cache` + 직렬화 본문.
 *  - 무효/취소 토큰(서비스 null) → 404(존재 여부 probe 최소화 — 단일 수렴).
 */
class IcalFeedControllerTest {
    private val service = mockk<CalendarFeedService>()
    private val controller = IcalFeedController(service, FEED_BASE_URL)

    @Test
    fun `유효 토큰이면 200 과 text_calendar 본문·캐시 헤더를 반환한다`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"
        every { service.generateFeed("validtoken", FEED_BASE_URL) } returns ics

        val response = controller.feed("validtoken")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isEqualTo(ics)
        assertThat(response.headers.contentType?.type).isEqualTo("text")
        assertThat(response.headers.contentType?.subtype).isEqualTo("calendar")
        assertThat(response.headers.cacheControl).isEqualTo("private, no-cache")
    }

    @Test
    fun `무효·취소 토큰이면 404 로 수렴한다`() {
        every { service.generateFeed("missing", FEED_BASE_URL) } returns null

        assertThatThrownBy { controller.feed("missing") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    private companion object {
        private const val FEED_BASE_URL = "https://bts.example.com"
    }
}
