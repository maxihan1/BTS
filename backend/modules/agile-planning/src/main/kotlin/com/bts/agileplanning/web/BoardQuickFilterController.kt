// 퀵필터 REST API 컨트롤러 — CRUD + 보드 CREATE 권한 게이트 재사용 (FR-UX-01 Task 6)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.BoardQuickFilterService
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.agileplanning.web.dto.QuickFilterRequest
import com.bts.agileplanning.web.dto.QuickFilterResponse
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.util.UUID

/**
 * 퀵필터 REST API 컨트롤러 — agile-planning BC (FR-UX-01).
 *
 * 보드 상단에 노출되는, 이름 붙은 필터 조합의 CRUD 를 담당한다. 목록 조회는 이 컨트롤러가 아니라
 * `GET /api/v1/boards/{id}` 의 `BoardDetailResponse.quickFilters` 로 노출된다(T7, BROWSE 권한).
 *
 * 엔드포인트 목록.
 * - POST   `/api/v1/boards/{boardId}/quick-filters` — 생성. 권한 [IssuePermission.CREATE].
 * - PATCH  `/api/v1/boards/{boardId}/quick-filters/{filterId}` — 수정(name/query). 권한 [IssuePermission.CREATE].
 * - DELETE `/api/v1/boards/{boardId}/quick-filters/{filterId}` — 삭제. 권한 [IssuePermission.CREATE].
 *
 * ### 권한 게이트 (BoardController 패턴 재사용)
 * 세 엔드포인트 모두 동일한 순서를 따른다 — actor 추출(401, 존재 probe 차단) → 보드 메타 조회(404) →
 * CREATE 권한 판정(403) → 서비스 위임. [requireCreateAccess] 에 응집한다([BoardController] 와 동일한
 * 패턴이지만, 이 컨트롤러는 BROWSE 게이트가 필요 없어 단일 헬퍼로 단순화했다).
 *
 * ### 예외 매핑
 * [BoardExceptionHandler] 의 `assignableTypes` 에 이 클래스가 포함되어 있어야 [BoardNotFoundException],
 * [BoardAccessDeniedException], [ResponseStatusException](401/404/409/400 등)이 catch-all 로 500 변질되지
 * 않는다(리뷰 BLOCKER-B/C, memory: catch-all-exceptionhandler-swallows-responsestatusexception /
 * domain-exception-http-handler-basepackage-scope). `QuickFilterNameConflictException`(EC2, 이름 중복 409)은
 * 낙관적 락(OCC) 충돌과 문구가 겹치지 않도록 [BoardExceptionHandler] 에 전용 핸들러가 등록되어 있다.
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 트랜잭션은 [BoardQuickFilterService] 가 개시한다.
 *
 * @param service 퀵필터 CRUD 유스케이스 서비스.
 * @param boardRepository 보드 메타(projectKey) 조회용. 권한 scope 산출과 404 판정에 사용한다.
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입).
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/quick-filters")
class BoardQuickFilterController(
    private val service: BoardQuickFilterService,
    private val boardRepository: BoardRepository,
    private val permissionResolver: IssuePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 퀵필터를 생성한다.
     *
     * @param boardId path variable 소속 보드 UUID.
     * @param request 생성 요청 바디(name/query).
     * @return 201 Created + [QuickFilterResponse] + `Location` 헤더.
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     * @throws BoardAccessDeniedException 403 — CREATE 권한 미충족.
     */
    @PostMapping
    fun create(
        @PathVariable boardId: UUID,
        @Valid @RequestBody request: QuickFilterRequest,
    ): ResponseEntity<DataResponse<QuickFilterResponse>> {
        log.info("BoardQuickFilterController.create boardId={}", boardId)

        requireCreateAccess(boardId)
        val quickFilter = service.create(boardId, request.name, request.query)
        val location = URI.create("/api/v1/boards/$boardId/quick-filters/${quickFilter.id}")
        return ResponseEntity.created(location).body(DataResponse(QuickFilterResponse.from(quickFilter)))
    }

    /**
     * 퀵필터의 name/query 를 수정한다.
     *
     * OCC(낙관적 락, Optimistic Concurrency Control) 를 적용하지 않는다 — 단순 메타(spec §API,
     * last-write-wins). 보드/카드와 달리 version 컬럼이 없다.
     *
     * @param boardId path variable 소속 보드 UUID.
     * @param filterId path variable 수정 대상 퀵필터 UUID.
     * @param request 수정 요청 바디(name/query).
     * @return 200 OK + [QuickFilterResponse].
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     * @throws BoardAccessDeniedException 403 — CREATE 권한 미충족.
     */
    @PatchMapping("/{filterId}")
    fun update(
        @PathVariable boardId: UUID,
        @PathVariable filterId: UUID,
        @Valid @RequestBody request: QuickFilterRequest,
    ): ResponseEntity<DataResponse<QuickFilterResponse>> {
        log.info("BoardQuickFilterController.update boardId={} filterId={}", boardId, filterId)

        requireCreateAccess(boardId)
        val quickFilter = service.update(boardId, filterId, request.name, request.query)
        return ResponseEntity.ok(DataResponse(QuickFilterResponse.from(quickFilter)))
    }

    /**
     * 퀵필터를 삭제한다.
     *
     * @param boardId path variable 소속 보드 UUID.
     * @param filterId path variable 삭제 대상 퀵필터 UUID.
     * @return 204 No Content.
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     * @throws BoardAccessDeniedException 403 — CREATE 권한 미충족.
     */
    @DeleteMapping("/{filterId}")
    fun delete(
        @PathVariable boardId: UUID,
        @PathVariable filterId: UUID,
    ): ResponseEntity<Void> {
        log.info("BoardQuickFilterController.delete boardId={} filterId={}", boardId, filterId)

        requireCreateAccess(boardId)
        service.delete(boardId, filterId)
        return ResponseEntity.noContent().build()
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * actor 추출 → 보드 메타 조회(404) → CREATE 권한 판정을 한 순서로 수행한다.
     *
     * 퀵필터 생성/수정/삭제가 공유하는 보드 쓰기 접근 게이트다. [BoardController.loadBoardWithCreate] 와
     * 동일한 순서를 이 컨트롤러 안에 응집한다(파일 범위상 컨트롤러 간 공유 컴포넌트로 추출하지 않는다 —
     * 두 컨트롤러가 각자 이 패턴을 소유해 서로의 매핑에 영향을 주지 않는다).
     *
     * @param boardId 접근할 보드 UUID.
     * @return 인증 주체 UUID.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     * @throws BoardAccessDeniedException 403 — CREATE 권한 미충족.
     */
    private fun requireCreateAccess(boardId: UUID): UUID {
        val actor = currentActorId()
        val board = boardRepository.findById(boardId) ?: throw BoardNotFoundException()
        if (!permissionResolver.hasPermission(actor, IssuePermission.CREATE, IssueScope.Project(board.projectKey))) {
            throw BoardAccessDeniedException()
        }
        return actor
    }

    /**
     * [SecurityContextHolder] 에서 인증 주체 UUID 를 추출한다.
     *
     * actor 추출은 리소스 조회보다 먼저 수행해야 한다(미인증자의 존재 probe 차단,
     * memory: auth-extraction-before-resource-lookup 교훈). 미인증·익명·비-UUID 주체는 401 로 거부한다.
     *
     * @return 인증 주체 UUID.
     * @throws ResponseStatusException 401 — 인증이 없거나 주체가 유효한 UUID 가 아닐 때.
     */
    private fun currentActorId(): UUID {
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
}
