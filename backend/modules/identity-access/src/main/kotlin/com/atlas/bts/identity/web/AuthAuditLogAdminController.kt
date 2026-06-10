// 관리자 전역 인증 감사 로그 조회 엔드포인트 — 필터/페이지네이션, SYSTEM_ADMIN 전용 (FR-AU-10 D6/D7)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.audit.AuthAuditLogAdminEntry
import com.atlas.bts.identity.audit.AuthAuditLogAdminQueryRepository
import com.atlas.bts.identity.audit.AuthAuditLogSearchCriteria
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.web.dto.AuthAuditLogEntryResponse
import com.atlas.bts.identity.web.dto.AuthAuditLogPageResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

/**
 * 관리자 전역 인증 감사 로그 조회 (FR-AU-10 D6/D7) — GET /api/v1/admin/auth-audit-logs.
 *
 * self-service findRecent(user-scoped)로는 불가한 **전역** 조회를 제공한다.
 *
 * ## 권한 (이중 가드, DEVELOPMENT.md §1.4)
 * SecurityFilterChain 의 api 하위 authenticated 필터 + PreAuthorize hasRole SYSTEM_ADMIN.
 * SYSTEM_ADMIN 이 아닌 인증 주체(일반 JWT / PAT 의 ROLE_PAT)는 403 으로 차단되며,
 * Spring Security 기본 403 응답은 권한 상세(SYSTEM_ADMIN 등)를 본문에 노출하지 않는다.
 * admin 경로는 SecurityConfig 의 api 하위 authenticated 규칙에 이미 포함된다(별도 등록 불요).
 *
 * ## 입력 검증 → 400 (정보 누출 방지)
 * 파라미터 파싱/범위 위반은 인라인으로 검사해 400 + 일반 메시지를 반환한다(예외 message·사용자 입력 미노출).
 * - eventType: [AuthEventType] enum name 미일치 → 400.
 * - from/to: ISO-8601 [Instant.parse] 실패 → 400.
 * - page < 0, size !in 1..[MAX_PAGE_SIZE] → 400.
 * - userId 의 UUID 바인딩 실패는 Spring MVC 가 400 으로 처리한다(컨트롤러 진입 전).
 *
 * 전역 `@ControllerAdvice` 부재(모듈 컨벤션) — 인라인 [ResponseEntity] 400 으로 catch-all 변질 위험을 원천 차단한다.
 *
 * ## PII (NFR-1)
 * ip/userAgent 는 응답에는 포함하되(감사 목적) 로그로 출력하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AuthAuditLogAdminController(
    private val repository: AuthAuditLogAdminQueryRepository,
) {
    /**
     * 감사 로그 전역 조회 (필터 + 페이지네이션).
     *
     * @param eventType [AuthEventType] enum name(예: LOGIN_FAILURE). 미일치 시 400.
     * @param userId 행위 주체 UUID. 바인딩 실패는 Spring 이 400 처리.
     * @param from ISO-8601 시각 하한(포함, 예: 2026-06-01T00:00:00Z). 파싱 실패 시 400.
     * @param to ISO-8601 시각 상한(포함). 파싱 실패 시 400.
     * @param page 0-based 페이지(기본 0). 음수 400.
     * @param size 페이지 크기(기본 [DEFAULT_PAGE_SIZE]). 1..[MAX_PAGE_SIZE] 벗어나면 400.
     *
     * LongParameterList 억제 — 모두 명세상 독립 query parameter 이며 임의 그룹핑은 가독성을 해친다(AuthController login 선례).
     * ReturnCount 억제 — 파라미터별 검증 실패 시 400 guard early return 이 중첩 if 보다 가독성 우수(DEVELOPMENT.md §2.3).
     */
    @Suppress("LongParameterList", "ReturnCount")
    @GetMapping("/auth-audit-logs")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    fun search(
        @RequestParam(required = false) eventType: String?,
        @RequestParam(required = false) userId: UUID?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "$DEFAULT_PAGE_SIZE") size: Int,
    ): ResponseEntity<*> {
        if (page < 0 || size !in 1..MAX_PAGE_SIZE) return badRequest()

        // 파싱 결과: outer null = 검증 실패(400), Boxed value = 정상(부재는 box 내부 null).
        val parsedEventType = parseEventType(eventType) ?: return badRequest()
        val parsedFrom = parseInstant(from) ?: return badRequest()
        val parsedTo = parseInstant(to) ?: return badRequest()

        val criteria =
            AuthAuditLogSearchCriteria(
                eventType = parsedEventType.value,
                userId = userId,
                from = parsedFrom.value,
                to = parsedTo.value,
                page = page,
                size = size,
            )

        val result = repository.search(criteria)
        val totalPages = if (size > 0) ceil(result.totalElements.toDouble() / size).toInt() else 0

        return ResponseEntity.ok(
            AuthAuditLogPageResponse(
                items = result.items.map(::toResponse),
                page = page,
                size = size,
                totalElements = result.totalElements,
                totalPages = totalPages,
            ),
        )
    }

    /**
     * eventType 파싱. 부재 → value 가 null 인 Box(정상), enum 미일치 → null(400).
     */
    private fun parseEventType(raw: String?): Box<AuthEventType?>? =
        if (raw == null) {
            Box(null)
        } else {
            AuthEventType.entries.firstOrNull { it.name == raw }?.let { Box(it) }
        }

    /**
     * ISO-8601 시각 파싱. 부재 → value 가 null 인 Box(정상), 파싱 실패 → null(400).
     */
    private fun parseInstant(raw: String?): Box<Instant?>? =
        if (raw == null) {
            Box(null)
        } else {
            runCatching { Instant.parse(raw) }.getOrNull()?.let { Box(it) }
        }

    private fun toResponse(entry: AuthAuditLogAdminEntry): AuthAuditLogEntryResponse =
        AuthAuditLogEntryResponse(
            id = entry.id,
            userId = entry.userId,
            username = entry.username,
            displayName = entry.displayName,
            eventType = entry.eventType.name,
            providerId = entry.providerId,
            ipAddress = entry.ipAddress,
            userAgent = entry.userAgent,
            metadata = entry.metadata,
            createdAt = entry.createdAt,
        )

    /**
     * 400 Bad Request — 일반 메시지(예외 message·사용자 입력 미노출, 정보 누출 방지).
     * 어떤 파라미터가 문제인지 본문에 드러내지 않는다(account/event 열거 방지).
     */
    private fun badRequest(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "invalid_query_parameter"))

    /**
     * 부재(정상 null)와 검증 실패(외부 null)를 구분하기 위한 단순 래퍼.
     * 파싱 함수가 null 을 반환하면 400, value 가 null 인 Box 를 반환하면 정상(필터 미적용)을 의미한다.
     */
    private data class Box<out T>(val value: T)

    private companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100
    }
}
