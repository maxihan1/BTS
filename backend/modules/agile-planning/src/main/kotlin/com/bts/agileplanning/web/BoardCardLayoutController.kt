// 카드 레이아웃 탭 REST 컨트롤러 — 뷰별 PATCH (부채 177 Task 8 · J17·J18)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.CardLayoutSettingsService
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 카드 레이아웃 탭 REST 컨트롤러 — agile-planning BC (부채 177 Task 8 · J17·J18).
 *
 * 엔드포인트는 하나다.
 * - PATCH `/api/v1/boards/{boardId}/card-layout` — 요청에 담긴 뷰의 구성 교체.
 *
 * ### 왜 [BoardController] 가 아니라 별도 파일인가 (스펙 C-1 · 부채 157)
 * `BoardController.kt` 는 이미 651줄에 엔드포인트 11개다. 설정 4탭을 거기 얹으면 800줄이 된다 —
 * Task 7 이 `BoardRepository.kt` 를 안 키우려고 리포지터리를 뺀 것과 **같은 이유가 컨트롤러에도
 * 그대로 적용된다.** 이 BC 에 선례가 셋 있다([BoardQuickFilterController] ·
 * [SprintBurndownController] · [SprintVelocityController]) — 경로 규칙과 게이트 순서를 그대로 따랐다.
 *
 * ### 권한 게이트 — 편차 X8
 * 보드 **단위** 권한 모델이 없어 프로젝트 권한으로 갈음한다. 게이트는
 * `hasPermission(actor, CREATE, IssueScope.Project(projectKey))` 로 설정 4탭이 **모두 같다**
 * (부채 177 Task 29 가 통일했다). 화면이 `permissions.CREATE` 하나로 편집 UI 를 열기 때문에
 * (`apps/web/src/routes/projects.$projectKey.board.settings.tsx:103`), 이 탭만 다른 권한코드를
 * 요구하면 CREATE 만 가진 사용자가 편집 UI 를 보고 403 을 맞는다. 멤버십 role 을 직접 조회하지 않고
 * 권한코드 + resolver 창구만 쓴다(FR-PM-07).
 *
 * ★순서는 **actor 추출(401) → 보드 조회(404) → 권한 판정(403)** 이다. 뒤집으면 403 이 「그 보드는
 * 있다」를 누설한다. 로컬 개발자는 항상 권한을 가져 그 뒤집힘이 눈에 안 보인다 —
 * [BoardController.loadBoardWithSoftDelete] 와 같은 순서를 이 컨트롤러 안에 응집한다.
 *
 * ### 예외 매핑
 * [BoardExceptionHandler] 의 `assignableTypes` 가 이 컨트롤러를 포함한다(부채 177 Task 29).
 * 그래서 404/403 은 [BoardNotFoundException]·[BoardAccessDeniedException] 으로 던지고,
 * 401 과 [CardLayoutSettingsService] 의 400 은 [ResponseStatusException] 계열로 던진다 —
 * 둘 다 그 advice 가 RFC 7807 봉투(`AGILE_*` errorCode)로 바꾼다.
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 트랜잭션은 [CardLayoutSettingsService] 가 개시한다.
 *
 * @param service 카드 레이아웃 저장 유스케이스.
 * @param boardRepository 보드 메타(projectKey) 조회용. 권한 scope 산출과 404 판정에 쓴다.
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입).
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/card-layout")
class BoardCardLayoutController(
    private val service: CardLayoutSettingsService,
    private val boardRepository: BoardRepository,
    private val permissionResolver: IssuePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 요청에 담긴 뷰의 카드 레이아웃을 교체한다.
     *
     * 요청에 없는 뷰는 그대로 남는다(J18). 응답은 저장 후 **다시 읽은** 전체 구성이라
     * 화면이 두 뷰를 한 번에 다시 그릴 수 있다(N1 — 별도 GET 왕복을 만들지 않는다).
     *
     * @param boardId path variable 대상 보드 UUID.
     * @param request 뷰별 필드 키 목록.
     * @return 200 OK + 저장 후 전체 구성.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws BoardNotFoundException 404 — 보드 미존재/soft-deleted.
     * @throws BoardAccessDeniedException 403 — 권한 미충족.
     * @throws com.bts.agileplanning.application.CardLayoutInvalidException 400 — 뷰당 4개 이상 ·
     *   미지원 필드 키 · 미지원 뷰 · 칸반 보드에 백로그 뷰.
     */
    @PatchMapping
    fun replaceCardLayout(
        @PathVariable boardId: UUID,
        @Valid @RequestBody request: CardLayoutRequest,
    ): ResponseEntity<DataResponse<CardLayoutResponse>> {
        log.info("BoardCardLayoutController.replaceCardLayout boardId={} views={}", boardId, request.cardLayout.keys)

        requireCardLayoutWriteAccess(boardId)
        val saved = service.replaceCardLayout(boardId, request.cardLayout)
        return ResponseEntity.ok(DataResponse(CardLayoutResponse(saved)))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * actor 추출(401) → 보드 조회(404) → CREATE 권한 판정(403)을 한 순서로 수행한다.
     *
     * @param boardId 접근할 보드 UUID.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     * @throws BoardAccessDeniedException 403 — CREATE 권한 미충족.
     */
    private fun requireCardLayoutWriteAccess(boardId: UUID) {
        val actor = currentActorId()
        val board = boardRepository.findById(boardId) ?: throw BoardNotFoundException()
        val allowed =
            permissionResolver.hasPermission(
                actor,
                IssuePermission.CREATE,
                IssueScope.Project(board.projectKey),
            )
        if (!allowed) {
            throw BoardAccessDeniedException()
        }
    }

    /**
     * [SecurityContextHolder] 에서 인증 주체 UUID 를 추출한다.
     *
     * actor 추출은 리소스 조회보다 **먼저** 한다 — 미인증자의 존재 probe 를 막는다
     * ([BoardQuickFilterController.currentActorId] 와 같은 구현).
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

/**
 * 카드 레이아웃 PATCH 요청 바디.
 *
 * 값 집합(뷰 키·필드 키)은 Bean Validation 으로 강제하지 않는다. 파싱과 판정을
 * [com.bts.agileplanning.application.CardLayoutViewScope] · `CardLayoutFieldKey` 한 곳에 모아
 * 허용값이 늘 때 두 자리를 고치지 않게 한다(`CreateBoardRequest.boardType` 과 같은 판단).
 *
 * @property cardLayout `viewScope → 필드 키 목록`. 순서가 곧 카드에서의 자리다.
 *   **비어 있으면 400** — 아무 뷰도 담지 않은 PATCH 는 성공처럼 보이는 무동작이다.
 */
data class CardLayoutRequest(
    @field:NotEmpty(message = "cardLayout 은 최소 한 뷰를 담아야 합니다.")
    val cardLayout: Map<String, List<String>>,
)

/**
 * 카드 레이아웃 응답 바디.
 *
 * @property cardLayout 저장 후 `viewScope → 필드 키 목록`. 구성이 없는 뷰는 키가 아예 없다 —
 *   빈 구성은 「현행 카드를 그린다」는 뜻이다(V509 ③).
 */
data class CardLayoutResponse(
    val cardLayout: Map<String, List<String>>,
)
