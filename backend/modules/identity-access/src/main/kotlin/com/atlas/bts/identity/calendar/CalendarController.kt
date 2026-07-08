// 개인 캘린더 조회 REST 컨트롤러 (FR-CA-01 Task 4)

package com.atlas.bts.identity.calendar

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * 개인 캘린더 조회 REST 컨트롤러 (FR-CA-01 Task 4).
 *
 * ## 엔드포인트 ([RequestMapping] `/api/v1/users`)
 * - [getMyCalendar] GET `/me/calendar?from=YYYY-MM-DD&to=YYYY-MM-DD` — 본인 담당 이슈 일정 + worklog 조회.
 *
 * ## 현재 사용자 식별 (JWT subject 전용, [com.atlas.bts.identity.web.UserProfileController] 미러)
 * [AuthenticationPrincipal] 로 주입된 [Jwt] 의 subject(UUID)로 현재 사용자를 식별한다([currentUserId]).
 * principal 이 [Jwt] 가 아니거나(PAT 등) subject 가 UUID 형식이 아니면 401 로 거부한다 — 이 컨트롤러는
 * PAT 로부터의 사용자 식별을 지원하지 않는다. Spring Security 필터 체인(`/api` 하위 전체 authenticated) +
 * 이 JWT 전용 가드의 이중 방어다.
 *
 * ## from/to 파싱
 * [DateTimeFormat] (ISO `YYYY-MM-DD`)로 [LocalDate] 바인딩을 시도하고, 형식이 잘못되면 Spring 이
 * `MethodArgumentTypeMismatchException` 을 던져 기본적으로 400 으로 응답한다(별도 핸들러 불필요).
 *
 * ## 창 검증/조립은 서비스 책임
 * `from > to` 또는 창 길이 90일 초과는 [CalendarService.getCalendar] 가 [ResponseStatusException](400)으로
 * 거부한다 — 이 컨트롤러는 파싱 이후 값 검증을 하지 않는다(BC 격리·PATCH 도메인 우회 방지와 동일 원칙 —
 * 도메인/서비스 검증 로직이 컨트롤러에 중복되지 않는다).
 *
 * ## 예외 → HTTP 매핑
 * 모듈에 전역 `@RestControllerAdvice` 가 없으므로([com.atlas.bts.identity.web.UserProfileController] 선례)
 * 이 컨트롤러는 별도 도메인 예외 핸들러를 두지 않는다 — [CalendarService] 가 던지는
 * [ResponseStatusException] 은 Spring 이 상태 코드 그대로 응답한다.
 */
@RestController
@RequestMapping("/api/v1/users")
class CalendarController(
    private val calendarService: CalendarService,
) {
    /**
     * GET `/api/v1/users/me/calendar` — 본인 담당 이슈 일정 + worklog 를 조회한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @param from 조회 시작일(포함, ISO `YYYY-MM-DD`). 형식 오류 시 400.
     * @param to 조회 종료일(포함, ISO `YYYY-MM-DD`). 형식 오류 시 400.
     * @return 200 [CalendarResponse].
     * @throws ResponseStatusException `from > to` 이거나 창 길이가 90일을 초과하면 400
     *   (`INVALID_CALENDAR_RANGE`, [CalendarService.getCalendar] 위임).
     */
    @GetMapping("/me/calendar")
    fun getMyCalendar(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): CalendarResponse {
        val userId = currentUserId(jwt)
        return calendarService.getCalendar(userId, from, to)
    }

    /**
     * JWT subject(UUID) 로 현재 사용자를 식별한다([com.atlas.bts.identity.web.UserProfileController] 와
     * 동일 원칙 — 리소스 조회보다 먼저 인증 주체를 추출한다, auth-extraction-before-resource-lookup).
     *
     * @param jwt [AuthenticationPrincipal] 로 주입된 JWT. PAT 등 미지원 인증이면 null.
     * @return 현재 사용자 UUID.
     * @throws ResponseStatusException subject 가 없거나 UUID 형식이 아니면 401.
     */
    private fun currentUserId(jwt: Jwt?): UUID =
        jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
