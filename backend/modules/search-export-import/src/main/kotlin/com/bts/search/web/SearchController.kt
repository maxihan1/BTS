// AQL 이슈 검색 REST 컨트롤러 — POST /api/v1/search/aql (FR-SR-02 FR-4)

package com.bts.search.web

import com.bts.search.aql.AqlLexer
import com.bts.search.aql.AqlParser
import com.bts.search.config.BEARER_AUTH_SCHEME
import com.bts.search.web.dto.AqlSearchHit
import com.bts.search.web.dto.AqlSearchPageResponse
import com.bts.search.web.dto.AqlSearchRequest
import com.bts.search.web.dto.toAqlEnvelope
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * AQL 이슈 검색 REST 컨트롤러.
 *
 * 엔드포인트.
 * - `POST /api/v1/search/aql` — AQL 텍스트 쿼리로 이슈를 검색한다.
 *
 * ### 처리 흐름
 *
 * 1. actor 추출 — [SecurityContextHolder]에서 인증 주체를 UUID로 변환한다.
 *    미인증·익명·비-UUID 주체는 401을 던진다(리소스 조회보다 먼저 수행하여 probe 차단).
 * 2. DTO Validation — [AqlSearchRequest] @Valid로 1차 방어.
 * 3. AQL 파싱 — [AqlLexer] + [AqlParser]로 쿼리 문자열을 AST로 변환한다.
 *    구문 오류 시 [com.bts.search.aql.AqlSyntaxException]을 던지고 [SearchExceptionHandler]가 400으로 변환한다.
 * 4. 검색 위임 — [IssueSearchPort.search]에 [IssueSearchQuery]를 전달한다.
 *    visibility 보안 술어 AND 결합 및 BROWSE 권한 게이트는 구현체(issue-tracking 어댑터)가 담당한다.
 * 5. 응답 변환 — `IssueSearchPage` → [AqlSearchPageResponse]<[AqlSearchHit]> 봉투로 반환.
 *
 * ### 트랜잭션
 *
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 트랜잭션은 [IssueSearchPort] 구현체가 담당한다.
 *
 * ### BC 격리
 *
 * issue-tracking 내부 패키지를 직접 import하지 않는다. [IssueSearchPort](shared-kernel)만 참조한다.
 * actor 추출도 [com.bts.issue.adapter.inbound.rest.CurrentActor]를 재사용하지 않고
 * 이 모듈에서 직접 [SecurityContextHolder]를 통해 추출한다.
 *
 * @param issueSearchPort AQL 검색 실행 포트(issue-tracking 어댑터가 런타임 주입).
 */
@Tag(name = "Search", description = "AQL 텍스트 쿼리 기반 이슈 검색")
@RestController
class SearchController(
    private val issueSearchPort: IssueSearchPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * AQL 텍스트 쿼리로 이슈를 검색한다.
     *
     * @param request 검색 요청 바디. [AqlSearchRequest] Jakarta Validation 적용.
     * @return 200 OK + envelope. 형식: `{ "data": [...], "meta": { "page": { "number", "size",
     *   "totalElements", "totalPages" } } }`.
     * @throws ResponseStatusException(401) 미인증 — actor 추출 실패 시.
     * @throws com.bts.search.aql.AqlSyntaxException(400) AQL 문법/필드 오류 — [SearchExceptionHandler]가 400으로 변환.
     * @throws SecurityException(403) BROWSE 권한 없음 — [SearchExceptionHandler]가 403으로 변환.
     */
    @Operation(
        operationId = "searchAql",
        summary = "AQL 쿼리로 이슈 검색",
        description =
            "AQL(Atlas Query Language) 텍스트 쿼리로 이슈를 검색한다. " +
                "project 멤버에게 보이는 이슈만 반환된다(visibility 보안 술어 자동 적용).",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "검색 성공. 결과가 없으면 data 가 빈 배열인 200 을 반환한다.",
                // 제네릭 erasure 방어(EC5): ResponseEntity<AqlSearchPageResponse<AqlSearchHit>> 의
                // 실제 타입 파라미터가 바이트코드 레벨에서 소거되므로 springdoc 이 스키마를 추론 불가.
                // implementation 을 명시해 components/schemas 에 AqlSearchPageResponse 를 강제 등록한다.
                content = [Content(schema = Schema(implementation = AqlSearchPageResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 — AQL 문법 오류 또는 필드 검증 실패.",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 — 유효한 Bearer 토큰 없음.",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "접근 거부 — BROWSE 권한 없음.",
                content = [Content(schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PostMapping("/api/v1/search/aql")
    fun search(
        @Valid @RequestBody request: AqlSearchRequest,
    ): ResponseEntity<AqlSearchPageResponse<AqlSearchHit>> {
        // actor 추출은 리소스 조회/파싱보다 먼저 수행한다(probe 차단 — 교훈 auth-extraction-before-resource-lookup).
        val actorId = currentActorId()
        log.info(
            "SearchController.search projectKey={} page={} size={} actor={}",
            request.projectKey,
            request.page,
            request.size,
            actorId,
        )

        // Bean Validation 어노테이션(@field:NotBlank 등)에 Hibernate Validator 구현체가 없는 환경에서도
        // 동작하도록 명시적 검증을 추가한다. @field:* 어노테이션은 문서화·표준 선언 목적으로 유지한다.
        validateRequest(request)

        val tokens = AqlLexer(request.query).tokenize()
        val parseResult = AqlParser(tokens).parse()

        val query =
            IssueSearchQuery(
                projectKey = request.projectKey,
                ast = parseResult.ast,
                sort = parseResult.sort,
                viewerUserId = actorId,
                page = request.page,
                size = request.size,
            )

        val searchPage = issueSearchPort.search(query)
        val envelope = searchPage.toAqlEnvelope(requestedSize = request.size) { AqlSearchHit.from(it) }

        return ResponseEntity.ok(envelope)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [AqlSearchRequest] 필드를 명시적으로 검증한다.
     *
     * Bean Validation(@field:NotBlank 등)을 선언적으로 두되,
     * Hibernate Validator 없이도 동작하도록 명시적 검증을 추가한다.
     * 실제 검증 오류는 [MethodArgumentNotValidException] 대신 [ResponseStatusException]을 던지며
     * [SearchExceptionHandler.handleResponseStatus]가 400으로 변환한다.
     *
     * @param request 검증할 요청 DTO.
     * @throws ResponseStatusException(400) 검증 실패 시.
     */
    @Suppress("ThrowsCount") // 검증 항목별 단일 throw — 분리하면 더 복잡해진다
    private fun validateRequest(request: AqlSearchRequest) {
        if (request.projectKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "projectKey는 필수입니다.")
        }
        if (request.query.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "query는 필수입니다.")
        }
        if (request.query.length > MAX_QUERY_LENGTH) {
            throw SearchValidationException("query는 최대 ${MAX_QUERY_LENGTH}자까지 허용됩니다.")
        }
        if (request.page < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다.")
        }
        if (request.size < MIN_PAGE_SIZE || request.size > MAX_PAGE_SIZE) {
            throw SearchValidationException("size는 ${MIN_PAGE_SIZE}~${MAX_PAGE_SIZE} 사이여야 합니다.")
        }
    }

    /**
     * [SecurityContextHolder]에서 인증 주체를 UUID로 추출한다.
     *
     * 미인증·익명·비-UUID·nil-UUID 주체는 401 [ResponseStatusException]을 던진다.
     * issue-tracking [com.bts.issue.adapter.inbound.rest.CurrentActor]와 동일한 로직이지만
     * BC 격리 원칙상 issue-tracking 내부를 직접 import할 수 없어 이 모듈에서 자체 구현한다.
     *
     * @return 인증된 사용자의 UUID.
     * @throws ResponseStatusException(401) 미인증 또는 UUID 변환 실패 시.
     */
    private fun currentActorId(): UUID {
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

    companion object {
        /** 쿼리 문자열 최대 길이 — DoS 방어(NFR-2). */
        private const val MAX_QUERY_LENGTH = 2000

        /** 페이지 크기 최솟값. */
        private const val MIN_PAGE_SIZE = 1

        /** 페이지 크기 최댓값 — GET /issues와 동일 방향의 거부(클램프 아님). */
        private const val MAX_PAGE_SIZE = 100
    }
}
