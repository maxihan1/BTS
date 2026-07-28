// 컨트롤러 권한 게이트 공용 지원 — 401/403 응답 상수 · actor(JWT/PAT) 추출 · SYSTEM_ADMIN 게이트

package com.atlas.bts.identity.web.support

import com.bts.shared.permission.SystemPermissionResolver
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/*
 * identity-access 웹 계층 컨트롤러들이 공유하던 권한 게이트 3종의 단일 정의 지점.
 *
 * 6개 컨트롤러(FieldPermissionController · GlobalPermissionGrantController ·
 * IssueSecuritySchemeController · ProjectMemberController · ProjectSecuritySchemeController ·
 * UserGroupController)가 각자 `private companion object` / `private fun` 으로 같은 본문을
 * 복제하고 있었다. 사본이 늘수록 한 곳만 고쳐 게이트가 갈라질 위험이 커지므로 여기로 모은다.
 *
 * ## 여기에 두는 것과 두지 않는 것
 * - 둔다. actor 추출 실패 401 · 전역 비관리자 403 · JWT/PAT actor 추출 · SYSTEM_ADMIN 게이트.
 * - 두지 않는다. `PAT_FORBIDDEN_RESPONSE`(AuthController · MfaController · TrustedDeviceController ·
 *   PersonalAccessTokenController · AccountLinkController · SsoAccountLinkController ·
 *   LinkableProvidersController · CalendarFeedController). 이름만 비슷할 뿐 **별개 상수**다 —
 *   error 코드가 `session_management_requires_interactive_login` / `account_linking_...` /
 *   `calendar_feed_...` 3종으로 갈리고 CalendarFeedController 는 타입도 다르다. 합치면 프론트가
 *   분기하는 오류코드가 뭉개진다.
 * - 두지 않는다. MyIssuePermissionController · MyProjectPermissionController 의 `resolveActorId`.
 *   이름만 같고 인자(HttpServletRequest 동반) · 반환(non-null) · 실패처리(예외 throw)가 전부 다르다.
 *
 * ## shared-kernel 로 올리지 않는 이유
 * 반환 타입이 웹 계층 타입([ResponseEntity])이고, 타 모듈(search-export-import · notification)의
 * 동명 함수는 null 이 아니라 예외를 던지는 계약이라 공유해도 쓸 곳이 없다.
 */

/** actor 추출 실패(JWT/PAT 파싱 오류) 공용 401 응답. */
internal val UNAUTHORIZED_RESPONSE: ResponseEntity<Map<String, String>> =
    ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "unauthorized"))

/** 전역 관리자가 아닌 행위자에 대한 공용 403 응답 — 내부 구조를 담지 않는 일반 메시지. */
internal val FORBIDDEN_RESPONSE: ResponseEntity<Map<String, String>> =
    ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "forbidden"))

/**
 * JWT 또는 PAT SecurityContext 에서 actor UUID 를 추출한다.
 *
 * - jwt != null → JWT subject 를 UUID 로 파싱.
 * - jwt == null → SecurityContext principal(String)을 UUID 로 파싱(PAT 경로,
 *   `PatAuthenticationFilter` 가 `pat.userId.toString()` 을 principal 로 설정한다).
 * - 파싱 실패 → null (호출 측 401).
 */
@Suppress("ReturnCount")
internal fun resolveActorId(jwt: Jwt?): UUID? {
    if (jwt != null) {
        return runCatching { UUID.fromString(jwt.subject) }.getOrNull()
    }
    val authentication = SecurityContextHolder.getContext().authentication
    val rawPrincipal = authentication?.principal as? String ?: return null
    return runCatching { UUID.fromString(rawPrincipal) }.getOrNull()
}

/**
 * SYSTEM_ADMIN 게이트 — 통과면 null, 막으면 그대로 반환할 에러 응답.
 *
 * actor 추출을 **리소스 조회보다 먼저** 수행하므로, 미인증자가 404(없는 리소스) vs 401 로
 * 리소스 존재 여부를 probe 할 수 없다(auth-extraction-before-resource-lookup 교훈).
 *
 * 확장 함수로 둔 이유. 판정 자체는 [SystemPermissionResolver] 가 소유하고 이 함수는 그 결과를
 * HTTP 로 옮기기만 한다. 별도 Bean 으로 만들면 3개 컨트롤러 생성자와 그 슬라이스 테스트 설정이
 * 함께 바뀌고, 슬라이스가 이 Bean 을 mock 으로 갈아끼우는 순간 가드 검증이 조용히 죽는다.
 *
 * @param jwt `@AuthenticationPrincipal` 로 주입된 JWT (PAT 경로면 null).
 * ReturnCount 억제 — 거부 사유 2종(401/403)은 각각 즉시 반환해야 한다. 하나로 접으면 권한 판정
 * 결과를 변수로 들고 다니다 분기를 놓치는 fail-open 통로가 된다(추출 전 6개 컨트롤러의 억제 사유
 * 그대로 이관).
 *
 * @return 통과면 null, actor 추출 실패면 [UNAUTHORIZED_RESPONSE], 비관리자면 [FORBIDDEN_RESPONSE].
 */
@Suppress("ReturnCount")
internal fun SystemPermissionResolver.requireSystemAdmin(jwt: Jwt?): ResponseEntity<Map<String, String>>? {
    val actorId = resolveActorId(jwt) ?: return UNAUTHORIZED_RESPONSE
    if (!isSystemAdmin(actorId)) return FORBIDDEN_RESPONSE
    return null
}
