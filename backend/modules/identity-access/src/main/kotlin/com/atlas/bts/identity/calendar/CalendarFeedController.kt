// 캘린더 피드 토큰 관리 REST 컨트롤러 — me-scope JWT 발급/조회/취소, PAT 403 (FR-CA-02 Task 5)

package com.atlas.bts.identity.calendar

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 캘린더 피드 토큰 관리 REST 컨트롤러 (FR-CA-02 Task 5).
 *
 * ## 엔드포인트 ([RequestMapping] `/api/v1/users/me/calendar/feed`)
 * - [issueFeed] POST — 구독 URL 발급/재발급(rotate). 응답에 원문 토큰 1회 노출.
 * - [getFeedStatus] GET — 발급 여부/시각 조회(원문·해시 미노출).
 * - [revokeFeed] DELETE — 구독 취소(하드 삭제).
 *
 * ## me-scope + PAT 차단 (403, 세션 관리 선례)
 * 자격증명(구독 URL) 관리이므로 **정식 대화형 로그인(JWT)만** 허용한다. PAT 인증 시 principal 이
 * [Jwt] 타입이 아니라 [AuthenticationPrincipal] `Jwt?` 바인딩이 null 이 되고, **403 Forbidden** +
 * `calendar_feed_requires_interactive_login` 을 반환한다([com.atlas.bts.identity.web.AuthController] 의
 * 세션 관리 PAT 차단과 동일 원칙 — PAT 는 인증됐으나 자격증명 관리는 금지, 401 이 아닌 403 이 의미상 정확).
 * 미인증 요청은 Spring Security 필터가 401 로 거부하므로 이 컨트롤러에 도달하지 않는다.
 *
 * ## feedUrl 베이스
 * 피드는 백엔드가 `/ical/feed/{token}.ics` 로 직접 서빙하므로, 발급 URL 베이스는 백엔드 자신의 외부
 * 주소인 `bts.auth.issuer-uri` 를 재사용한다(별도 `app.base-url` 신설 없이 prod HTTPS 값 재사용).
 */
@RestController
@RequestMapping("/api/v1/users/me/calendar/feed")
class CalendarFeedController(
    private val calendarFeedService: CalendarFeedService,
    @Value("\${bts.auth.issuer-uri:http://localhost:8080}") private val feedBaseUrl: String,
) {
    /**
     * POST — 캘린더 피드 토큰을 발급/재발급한다. 응답에 원문 토큰이 박힌 구독 URL 을 1회 노출한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 비-JWT 이면 null → 403.
     * @return 201 [IssueFeedResponse] / 403 PAT.
     */
    @PostMapping
    fun issueFeed(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = meUserId(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        val issued = calendarFeedService.issue(userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            IssueFeedResponse(
                feedUrl = "$feedBaseUrl/ical/feed/${issued.rawToken}.ics",
                token = issued.rawToken,
                createdAt = issued.createdAt,
            ),
        )
    }

    /**
     * GET — 캘린더 피드 발급 상태를 조회한다(원문 토큰/해시 미노출).
     *
     * @param jwt 인증 JWT principal. PAT 등 비-JWT 이면 null → 403.
     * @return 200 [FeedStatusResponse] / 403 PAT.
     */
    @GetMapping
    fun getFeedStatus(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = meUserId(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        val status = calendarFeedService.status(userId)
        return ResponseEntity.ok(FeedStatusResponse(enabled = status.enabled, createdAt = status.createdAt))
    }

    /**
     * DELETE — 캘린더 피드 토큰을 취소(하드 삭제)한다. 취소 즉시 구독 URL 은 404 로 수렴한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 비-JWT 이면 null → 403.
     * @return 204 No Content / 403 PAT.
     */
    @DeleteMapping
    fun revokeFeed(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val userId = meUserId(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        calendarFeedService.revoke(userId)
        return ResponseEntity.noContent().build<Unit>()
    }

    /**
     * JWT subject(UUID)로 현재 사용자를 식별한다. PAT(비-JWT → jwt=null) 또는 subject 가 UUID 형식이
     * 아니면 null 을 반환한다(호출 측이 403 반환 — 세션 관리 `resolveJwtClaims` 선례와 동일 원칙).
     */
    private fun meUserId(jwt: Jwt?): UUID? = jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private companion object {
        /** PAT/비-JWT principal 에 대한 고정 403 응답(자격증명 관리는 대화형 로그인 전용). */
        private val PAT_FORBIDDEN_RESPONSE: ResponseEntity<*> =
            ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "calendar_feed_requires_interactive_login"))
    }
}
