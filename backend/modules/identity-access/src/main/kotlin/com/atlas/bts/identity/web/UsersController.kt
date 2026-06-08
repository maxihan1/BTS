// 활성 사용자 목록/검색 + 관리자 로컬 계정 생성 엔드포인트 (FR-IS-03 Task 4/6 · FR-AU-05 Task 4)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.credential.CreateLocalAccountService
import com.atlas.bts.identity.credential.UsernameTakenException
import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import com.atlas.bts.identity.web.dto.CreateUserRequest
import com.atlas.bts.identity.web.dto.CreateUserResponse
import com.atlas.bts.identity.web.dto.UserSummaryResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
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
    private val createLocalAccountService: CreateLocalAccountService,
) {
    companion object {
        /** typeahead 결과 상한 — 매직넘버 방지 상수화 */
        const val MAX_RESULTS = 50

        /** username 중복 에러 응답 code (FR-AU-05 Task 4) */
        const val ERROR_USERNAME_TAKEN = "USERNAME_TAKEN"
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

    /**
     * 관리자 로컬 계정 생성 (FR-AU-05 Task 4) — POST /api/v1/users.
     *
     * ## 권한 (이중 가드, DEVELOPMENT.md §1.4)
     * SecurityFilterChain 의 /api 하위 authenticated 필터 + PreAuthorize hasRole SYSTEM_ADMIN.
     * SYSTEM_ADMIN 이 아닌 인증 주체(일반 JWT 또는 PAT 의 ROLE_PAT)는 403 으로 차단되며,
     * Spring Security 기본 403 응답은 권한 상세(SYSTEM_ADMIN 등)를 본문에 노출하지 않는다 (CONCERN-5).
     *
     * ## 입력 검증
     * [CreateUserRequest] 의 jakarta validation 위반은 Spring MVC 기본 핸들러가 400 으로 변환한다.
     * email 이 빈 문자열이면 null 로 정규화하여 서비스에 전달한다.
     *
     * ## 임시 비밀번호 평문 수명 (CONCERN-3 / DEVELOPMENT.md §1.1)
     * [CreateLocalAccountService.create] 가 반환한 CharArray 를 응답 직렬화 직전 단 한 번
     * String 으로 변환해 [CreateUserResponse.temporaryPassword] 에 담는다.
     * 평문 비밀번호는 로그에 출력하지 않는다.
     *
     * @param req 생성할 계정 정보 (username, email, displayName)
     * @return 201 Created 와 함께 id, username, temporaryPassword 를 반환한다.
     *   username 중복 시 409 와 code USERNAME_TAKEN 을 반환한다.
     */
    @PostMapping("/api/v1/users")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    fun createUser(
        @Valid @RequestBody req: CreateUserRequest,
    ): ResponseEntity<*> {
        return try {
            val created =
                createLocalAccountService.create(
                    username = req.username,
                    email = req.email?.ifBlank { null },
                    displayName = req.displayName,
                )
            val response =
                CreateUserResponse(
                    id = created.user.id,
                    username = created.user.username,
                    // CharArray → String 1회 변환 (CONCERN-3). 직렬화 직전 단일 변환.
                    temporaryPassword = String(created.temporaryPassword),
                )
            ResponseEntity.status(HttpStatus.CREATED).body(response)
        } catch (e: UsernameTakenException) {
            // 도메인 중복 예외를 409 인라인 응답으로 변환 (PasswordController 패턴, RestControllerAdvice 부재).
            // 빈 catch 금지 — 명시적 HTTP 매핑. 예외 message 는 응답에 노출하지 않는다 (일반 메시지 사용).
            logger.info("로컬 계정 생성 username 중복 거부", e)
            usernameTakenResponse()
        }
    }
}

/** UsersController 전용 로거 — 평문 비밀번호/PII 미출력 (DEVELOPMENT.md §1.1/§1.2) */
private val logger = org.slf4j.LoggerFactory.getLogger(UsersController::class.java)

/**
 * 409 Conflict — username 중복 에러 응답 헬퍼.
 *
 * 본문은 code USERNAME_TAKEN 과 일반 message 로 구성하며, 예외 message(중복 username 포함)는
 * 노출하지 않는다 (정보 누출 방지, fr-pm-04-guard-exception-message-http-leak).
 */
private fun usernameTakenResponse(): ResponseEntity<Map<String, String>> =
    ResponseEntity.status(HttpStatus.CONFLICT).body(
        mapOf(
            "code" to UsersController.ERROR_USERNAME_TAKEN,
            "message" to "이미 사용 중인 username 입니다.",
        ),
    )
