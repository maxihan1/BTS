// 익명 iCal 구독 피드 REST 컨트롤러 — GET /ical/feed/{token}.ics, 404 수렴 (FR-CA-02 Task 6)

package com.atlas.bts.identity.calendar

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 익명 iCal 구독 피드 REST 컨트롤러 (FR-CA-02 Task 6).
 *
 * ## 비인증 GET 전용 (SecurityConfig permitAll)
 * `GET /ical/feed/{token}.ics` 는 외부 캘린더 앱이 Authorization 헤더 없이 주기 폴링하는 경로다.
 * [com.atlas.bts.identity.config.SecurityConfig] 의 `ICAL_FEED_PATH` 매처가 이 경로를 GET-only·단일
 * 세그먼트로 permitAll 등록한다(FR-DB-03 defense-in-depth 동형). 인증 주체를 참조하지 않고 URL 토큰만으로
 * 소유자를 식별한다.
 *
 * ## 404 수렴 (열거·probe 최소화)
 * 무효/취소/존재한 적 없는 토큰은 [CalendarFeedService.generateFeed] 가 null 을 반환하며, 모두 **404**
 * 로 수렴한다. 토큰 포맷 검증(@Pattern 등)은 두지 않는다 — malformed 토큰이 400 이라는 별도 probe 축을
 * 만들지 않기 위함이다. 토큰은 256비트 불투명 값이라 열거가 비현실적이며, 원문은 로깅하지 않는다.
 *
 * ## feedUrl 베이스
 * 이슈 VEVENT `URL` 조립용 베이스는 [CalendarFeedController] 와 동일하게 `bts.auth.issuer-uri` 를 쓴다.
 */
@RestController
class IcalFeedController(
    private val calendarFeedService: CalendarFeedService,
    @Value("\${bts.auth.issuer-uri:http://localhost:8080}") private val feedBaseUrl: String,
) {
    /**
     * GET `/ical/feed/{token}.ics` — 토큰 소유자의 담당 이슈/worklog 를 RFC 5545(.ics)로 반환한다.
     *
     * @param token URL 경로의 불투명 토큰(hex). 서비스가 SHA-256 해시로만 조회한다(원문 미로깅).
     * @return 200 `text/calendar` + `Cache-Control: private, no-cache` / 404(무효·취소·미존재 토큰).
     */
    @GetMapping("/ical/feed/{token}.ics", produces = ["text/calendar;charset=UTF-8"])
    fun feed(
        @PathVariable token: String,
    ): ResponseEntity<String> {
        val body =
            calendarFeedService.generateFeed(token, feedBaseUrl)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
            .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
            .body(body)
    }
}
