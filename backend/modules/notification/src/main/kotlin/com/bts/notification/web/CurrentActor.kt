// notification BC web 레이어 공유 인증 헬퍼 — SecurityContext 에서 actor UUID 추출

package com.bts.notification.web

import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * [SecurityContextHolder] 에서 인증 주체 UUID 를 추출하는 공유 헬퍼.
 *
 * notification BC 내 컨트롤러가 공통으로 사용한다.
 * 중복 복사를 방지하기 위해 패키지-레벨 함수로 선언한다.
 *
 * ## 인증 실패 조건
 * 아래 중 하나라도 해당하면 401(UNAUTHORIZED) 를 던진다.
 * - 인증 객체가 없음 (null)
 * - [AnonymousAuthenticationToken] 인 경우
 * - `isAuthenticated == false` 인 경우
 * - `authentication.name` 이 유효한 UUID 형식이 아닌 경우
 *
 * actor 추출은 반드시 리소스 조회보다 먼저 수행해야 한다.
 * 미인증자가 리소스 존재 여부를 probe 하지 못하도록 차단한다
 * (memory: auth-extraction-before-resource-lookup 교훈).
 *
 * @return 인증 주체 UUID
 * @throws ResponseStatusException HTTP 401 — 인증이 없거나 주체가 유효한 UUID 가 아닐 때
 */
internal fun currentActorId(): UUID {
    val authentication =
        SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
    return try {
        UUID.fromString(authentication.name)
    } catch (e: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required", e)
    }
}
