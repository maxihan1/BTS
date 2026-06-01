// 활성 사용자 목록 및 검색 엔드포인트 — 담당자 셀렉터 typeahead 재료 공급 (FR-IS-03 Task 4/6)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import com.atlas.bts.identity.web.dto.UserSummaryResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 활성 사용자 목록 / 검색 엔드포인트 (FR-IS-03 Task 4 — 담당자 셀렉터).
 *
 * ## 인증 요구사항
 * 이 엔드포인트는 PII(이메일, 표시 이름)를 노출하므로 반드시 인증된 사용자만 접근 가능하다.
 * SecurityFilterChain(`authorizeHttpRequests` — `/api/` 하위 전체 authenticated 요구)과
 * `@PreAuthorize("isAuthenticated()")` 이중 가드로 우회를 방지한다 (DEVELOPMENT.md §1.4).
 * 미인증 요청은 401 을 반환한다.
 *
 * ## 검색 동작
 * - [query] 없음: 전체 사용자 반환 (상한 [MAX_RESULTS] 건).
 * - [query] 있음: username 또는 display_name 에 ILIKE 부분일치 필터 적용.
 *
 * ## PII 로그 금지
 * email / displayName 을 로그에 출력하지 않는다 (DEVELOPMENT.md §1.2).
 *
 * ## 페이지네이션
 * typeahead 용도 — [MAX_RESULTS] 상한으로 충분하다. 전체 페이지네이션은 후속 FR 에서 구현한다.
 */
@RestController
class UsersController(
    private val userRepository: UserRepository,
) {
    companion object {
        /** typeahead 결과 상한 — 매직넘버 방지 상수화 */
        const val MAX_RESULTS = 50
    }

    /**
     * 활성 사용자 목록 조회 / 검색 / id 다건 조회.
     *
     * ## 인증 필수 (PII 노출 엔드포인트)
     * 인증되지 않은 요청은 SecurityFilterChain 에서 401 을 반환한다.
     * `@PreAuthorize("isAuthenticated()")` 는 이중 가드 역할을 한다.
     *
     * ## 모드 우선순위
     * 1. [ids] 파라미터 있음 → id 다건 조회 모드 ([query] 무시). 미존재 id 조용히 제외.
     * 2. [ids] 없음 → [query] 검색 / 전체 목록 모드 (기존 동작).
     *
     * ## id 개수 상한
     * [ids] 가 [MAX_RESULTS](50) 초과 시 400 Bad Request 반환 (과도 조회 방지).
     *
     * ## PII 로그 금지
     * email / displayName 을 로그에 출력하지 않는다 (DEVELOPMENT.md §1.2).
     *
     * @param query username 또는 display_name 부분일치 검색어 (선택). null 이면 전체 반환.
     * @param ids 쉼표 구분 UUID 목록 (선택). 있으면 ids 모드 우선.
     * @return 최대 [MAX_RESULTS] 건의 사용자 요약 목록
     */
    @GetMapping("/api/v1/users")
    @PreAuthorize("isAuthenticated()")
    fun listUsers(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) ids: String?,
    ): ResponseEntity<List<UserSummaryResponse>> {
        return if (ids != null) fetchByIds(ids) else fetchByQuery(query)
    }

    private fun fetchByQuery(query: String?): ResponseEntity<List<UserSummaryResponse>> {
        val users = userRepository.findAll(query = query, limit = MAX_RESULTS).map(::toSummaryResponse)
        return ResponseEntity.ok(users)
    }

    /**
     * ids 모드: 쉼표 구분 UUID 문자열 파싱 → findByIds 조회.
     *
     * 파싱 불가 항목은 무시. 개수 상한 초과 시 400 반환.
     */
    private fun fetchByIds(ids: String): ResponseEntity<List<UserSummaryResponse>> {
        val tokens = ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val parsedIds = tokens.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (parsedIds.size > MAX_RESULTS) {
            return ResponseEntity.badRequest().build()
        }
        val users = userRepository.findByIds(parsedIds).map(::toSummaryResponse)
        return ResponseEntity.ok(users)
    }

    private fun toSummaryResponse(user: User): UserSummaryResponse =
        UserSummaryResponse(
            id = user.id,
            username = user.username,
            displayName = user.displayName,
            email = user.email,
        )
}
