// SecurityContext에서 인증 주체 UUID를 추출하는 공통 헬퍼 — 두 필터 컨트롤러 공유 (FR-SR-03)

package com.bts.search.savedfilter.web

import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * [SecurityContextHolder]에서 인증된 사용자의 UUID를 추출하는 공통 헬퍼.
 *
 * [SavedFilterController]와 [SavedFilterSearchController]가 공유한다.
 * 미인증·익명·비-UUID·nil-UUID 주체는 401 [ResponseStatusException]을 던진다.
 * 리소스 조회보다 먼저 호출하여 존재 probe를 차단한다(교훈 auth-extraction-before-resource-lookup).
 *
 * BC 격리 — issue-tracking의 CurrentActor를 직접 import할 수 없어 search BC가 자체 구현한다
 * ([com.bts.search.web.SearchController]와 동일 로직).
 */
object SavedFilterActorExtractor {
    /**
     * 인증 주체를 UUID로 추출한다.
     *
     * @return 인증된 사용자의 UUID.
     * @throws ResponseStatusException 401 미인증 또는 UUID 변환 실패 시.
     */
    fun extract(): UUID {
        val authentication: Authentication =
            SecurityContextHolder.getContext().authentication
                ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
        return try {
            val uuid = UUID.fromString(authentication.name)
            require(uuid != UUID(0L, 0L)) { "nil UUID는 actor로 허용되지 않습니다." }
            uuid
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required", e)
        }
    }
}
