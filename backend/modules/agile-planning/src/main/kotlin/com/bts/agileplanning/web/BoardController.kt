// 보드 REST API 컨트롤러 — 생성/조회/목록/카드이동 + 권한 게이트 (FR-BD-01 Task 9)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.BoardApplicationService
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.web.dto.BoardDetailResponse
import com.bts.agileplanning.web.dto.BoardMetaResponse
import com.bts.agileplanning.web.dto.BoardResponse
import com.bts.agileplanning.web.dto.BoardSummaryResponse
import com.bts.agileplanning.web.dto.ColumnMetaResponse
import com.bts.agileplanning.web.dto.CreateBoardRequest
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.agileplanning.web.dto.MoveCardRequest
import com.bts.agileplanning.web.dto.MoveCardResponse
import com.bts.agileplanning.web.dto.UpdateBoardSwimlaneRequest
import com.bts.agileplanning.web.dto.UpdateColumnWipLimitRequest
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.util.UUID

/**
 * 보드 REST API 컨트롤러 — agile-planning BC.
 *
 * 엔드포인트 목록.
 * - POST `/api/v1/boards` — 보드 생성. 권한 [IssuePermission.CREATE] on [IssueScope.Project].
 * - GET  `/api/v1/boards/{id}` — 보드 단건 조회(컬럼+카드). 권한 [IssuePermission.BROWSE].
 * - GET  `/api/v1/boards?projectKey=` — 프로젝트별 보드 목록. 권한 [IssuePermission.BROWSE].
 * - POST `/api/v1/boards/{id}/cards/{issueKey}/move` — 카드 이동. 보드 접근 [IssuePermission.BROWSE] +
 *   이동 자체는 [com.bts.shared.board.IssueTransitionPort] 가 TRANSITION 을 강제한다.
 * - PATCH `/api/v1/boards/{id}` — 보드 스윔레인 기준 변경. 권한 [IssuePermission.CREATE] on [IssueScope.Project].
 * - PATCH `/api/v1/boards/{id}/columns/{columnId}` — 컬럼 WIP 제한 설정/해제. 권한 [IssuePermission.CREATE] on [IssueScope.Project].
 *
 * ### 권한 2단 게이트 (FR-BD-01-6)
 * - 조회/이동의 보드 접근 = BROWSE(목록 자격). 카드 노출 보안수준은 행 단위 보안필터(T4)가 별도 적용.
 * - 생성 = CREATE(이슈 생성 동급, Maxi 게이트1 확정).
 * - 권한 판정은 shared-kernel [IssuePermissionResolver] 재사용. non-null 주입(fail-closed, 빈 부재=부팅실패).
 *
 * ### 처리 순서 (존재 probe 차단, sec CONCERN-4)
 * 1. actor 추출([currentActorId]) — 미인증이면 401(리소스 조회 이전에 차단).
 * 2. 보드 메타 조회([BoardRepository.findById]) — projectKey 확보(권한 scope 산출용).
 * 3. 권한 판정 — 미충족이면 403(일반 메시지, 내부 정보 미노출).
 * 4. 본 동작([BoardApplicationService] 위임).
 *
 * 보드 단건/이동은 projectKey 가 보드에 묶여 있으므로 메타 조회가 권한 판정보다 선행한다.
 * 미인증자는 1단계에서 401 로 차단되므로 보드 존재 여부를 404 로 probe 할 수 없다.
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 트랜잭션은 [BoardApplicationService] 가 개시한다.
 *
 * @param service 보드 유스케이스 서비스.
 * @param boardRepository 보드 메타(projectKey) 조회용. 권한 scope 산출과 404 판정에 사용한다.
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입).
 */
@RestController
@RequestMapping("/api/v1/boards")
class BoardController(
    private val service: BoardApplicationService,
    private val boardRepository: BoardRepository,
    private val permissionResolver: IssuePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 보드를 생성한다.
     *
     * 권한: [IssuePermission.CREATE] on [IssueScope.Project] (요청 projectKey 기준).
     *
     * @param request 보드 생성 요청 바디(projectKey, name).
     * @return 201 Created + [BoardResponse](컬럼 포함) + `Location` 헤더.
     */
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateBoardRequest,
    ): ResponseEntity<DataResponse<BoardResponse>> {
        log.info("BoardController.create projectKey={}", request.projectKey)

        val actor = currentActorId()
        requirePermission(actor, IssuePermission.CREATE, IssueScope.Project(request.projectKey))

        val board = service.createBoard(request.projectKey, request.name)
        val location = URI.create("/api/v1/boards/${board.id}")
        return ResponseEntity.created(location).body(DataResponse(BoardResponse.from(board)))
    }

    /**
     * 보드 단건을 조회한다(컬럼 + 카드).
     *
     * 권한: [IssuePermission.BROWSE] on 보드의 프로젝트. 카드 보안수준은 행 단위 보안필터가 별도 적용.
     *
     * 필터 파라미터.
     * - `assignee`: UUID 또는 "unassigned" 센티널. 담당자 필터. 복수 허용.
     * - `label`: 문자열. 라벨 필터. 복수 허용.
     * - `component`: UUID. 컴포넌트 필터. 복수 허용.
     *
     * 필터 파싱은 [BoardFilterQueryParser.parse] 에 위임한다.
     * UUID 형식 오류 시 400 — [BoardExceptionHandler.handleResponseStatus] 가 처리한다.
     *
     * @param id path variable 보드 UUID.
     * @param assignee 담당자 필터 파라미터 목록. UUID 또는 "unassigned" 센티널.
     * @param label 라벨 필터 파라미터 목록. 문자열 그대로 사용.
     * @param component 컴포넌트 필터 파라미터 목록. UUID.
     * @return 200 OK + [BoardDetailResponse].
     * @throws BoardNotFoundException 보드 미존재 또는 soft-deleted → 404.
     * @throws BoardAccessDeniedException BROWSE 권한 미충족 → 403.
     * @throws ResponseStatusException 400 — 필터 파라미터 UUID 형식 오류.
     */
    @GetMapping("/{id}")
    fun getBoard(
        @PathVariable id: UUID,
        @RequestParam(required = false) assignee: List<String> = emptyList(),
        @RequestParam(required = false) label: List<String> = emptyList(),
        @RequestParam(required = false) component: List<String> = emptyList(),
    ): ResponseEntity<DataResponse<BoardDetailResponse>> {
        log.info("BoardController.getBoard id={} assignee={} label={} component={}", id, assignee, label, component)

        val (actor, board) = loadBoardWithBrowse(id)

        val filter = BoardFilterQueryParser.parse(assignee, label, component)
        val result = service.getBoard(id, actor, filter)
        return ResponseEntity.ok(DataResponse(BoardDetailResponse.of(board, result)))
    }

    /**
     * 프로젝트별 보드 목록을 조회한다.
     *
     * 권한: [IssuePermission.BROWSE] on [IssueScope.Project] (요청 projectKey 기준).
     *
     * @param projectKey 조회할 프로젝트 키.
     * @return 200 OK + [BoardSummaryResponse] 목록.
     */
    @GetMapping
    fun listBoards(
        @RequestParam projectKey: String,
    ): ResponseEntity<DataResponse<List<BoardSummaryResponse>>> {
        log.info("BoardController.listBoards projectKey={}", projectKey)

        val actor = currentActorId()
        requirePermission(actor, IssuePermission.BROWSE, IssueScope.Project(projectKey))

        val boards = service.listBoards(projectKey).map(BoardSummaryResponse::from)
        return ResponseEntity.ok(DataResponse(boards))
    }

    /**
     * 카드(이슈)를 다른 컬럼으로 이동한다(워크플로우 전이 위임).
     *
     * 권한: 보드 접근 [IssuePermission.BROWSE] + 이동 자체는 전이 포트가 TRANSITION 을 강제한다.
     * actor 는 body 로 받지 않고 SecurityContext 에서 추출한다(전이 포트 adapter 가 재추출).
     *
     * @param id path variable 보드 UUID.
     * @param issueKey path variable 이동할 이슈 키. 예: `"BTS-1"`.
     * @param request 이동 요청 바디(toColumnId, expectedVersion, resolutionId?).
     * @return 200 OK + [MoveCardResponse](columnId echo).
     * @throws BoardNotFoundException 보드 미존재 → 404.
     * @throws BoardAccessDeniedException BROWSE 권한 미충족 → 403.
     */
    @PostMapping("/{id}/cards/{issueKey}/move")
    fun moveCard(
        @PathVariable id: UUID,
        @PathVariable issueKey: String,
        @Valid @RequestBody request: MoveCardRequest,
    ): ResponseEntity<DataResponse<MoveCardResponse>> {
        log.info("BoardController.moveCard id={} issueKey={}", id, issueKey)

        // actor 는 SecurityContext 에서만 추출한다(body/param 으로 받지 않음 — 위조 차단, sec P1).
        // loadBoardWithBrowse 가 actor 추출 → 보드 메타 조회 → BROWSE 권한 판정 순서를 보장한다.
        val (actor, _) = loadBoardWithBrowse(id)

        // @field:NotNull 검증 통과 후이므로 non-null. !! 금지 규칙에 따라 명시 체크.
        val toColumnId =
            request.toColumnId ?: error("toColumnId 는 @NotNull 검증 통과 후 null 일 수 없습니다.")
        val expectedVersion =
            request.expectedVersion ?: error("expectedVersion 은 @NotNull 검증 통과 후 null 일 수 없습니다.")

        val result =
            service.moveCard(
                boardId = id,
                issueKey = issueKey,
                actorUserId = actor,
                toColumnId = toColumnId,
                expectedVersion = expectedVersion,
                resolutionId = request.resolutionId,
            )
        return ResponseEntity.ok(DataResponse(MoveCardResponse.of(result, toColumnId)))
    }

    /**
     * 보드의 스윔레인 기준 필드를 변경한다.
     *
     * 권한: [IssuePermission.CREATE] on 보드의 프로젝트.
     *
     * 처리 순서: actor 추출(401) → 보드 메타 조회(404) → CREATE 권한(403) → 서비스 위임.
     *
     * @param id path variable 보드 UUID.
     * @param request 스윔레인 변경 요청 바디(swimlaneField 이름 문자열).
     * @return 200 OK + [BoardMetaResponse].
     * @throws BoardNotFoundException 보드 미존재 → 404.
     * @throws BoardAccessDeniedException CREATE 권한 미충족 → 403.
     * @throws ResponseStatusException 400 — 알 수 없는 swimlaneField 값.
     */
    @PatchMapping("/{id}")
    fun updateSwimlaneField(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateBoardSwimlaneRequest,
    ): ResponseEntity<DataResponse<BoardMetaResponse>> {
        log.info("BoardController.updateSwimlaneField id={} swimlaneField={}", id, request.swimlaneField)

        val (_, board) = loadBoardWithCreate(id)
        val raw = request.swimlaneField
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "swimlaneField 는 null 일 수 없습니다.")
        val updated = service.updateSwimlaneField(id, raw)
        return ResponseEntity.ok(DataResponse(BoardMetaResponse.from(updated)))
    }

    /**
     * 보드 컬럼의 WIP 제한을 설정하거나 해제한다.
     *
     * 권한: [IssuePermission.CREATE] on 보드의 프로젝트.
     *
     * 처리 순서: actor 추출(401) → 보드 메타 조회(404) → CREATE 권한(403) → 서비스 위임.
     *
     * @param id path variable 보드 UUID.
     * @param columnId path variable 컬럼 UUID.
     * @param request WIP 제한 요청 바디(wipLimit 양수 또는 null).
     * @return 200 OK + [ColumnMetaResponse].
     * @throws BoardNotFoundException 보드 미존재 → 404.
     * @throws BoardAccessDeniedException CREATE 권한 미충족 → 403.
     * @throws ResponseStatusException 404 — 타 보드 소속 또는 미존재 컬럼.
     */
    @PatchMapping("/{id}/columns/{columnId}")
    fun updateColumnWipLimit(
        @PathVariable id: UUID,
        @PathVariable columnId: UUID,
        @Valid @RequestBody request: UpdateColumnWipLimitRequest,
    ): ResponseEntity<DataResponse<ColumnMetaResponse>> {
        log.info("BoardController.updateColumnWipLimit id={} columnId={} wipLimit={}", id, columnId, request.wipLimit)

        val (_, _) = loadBoardWithCreate(id)
        val wipLimit = request.wipLimit
        if (wipLimit != null && wipLimit < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "wipLimit 는 1 이상이어야 합니다.")
        }
        val updatedColumn = service.updateColumnWipLimit(id, columnId, wipLimit)
        return ResponseEntity.ok(DataResponse(ColumnMetaResponse.from(updatedColumn)))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * actor 추출 → 보드 메타 조회(404) → BROWSE 권한 판정을 한 순서로 수행한다.
     *
     * 단건 조회·카드 이동이 공유하는 보드 접근 게이트다. 처리 순서를 한 곳에 응집하여
     * 순서 실수(존재 probe·권한 누락) 회귀를 차단한다(sec CONCERN-4). projectKey 는 보드에 묶여 있으므로
     * scope 산출을 위해 메타 조회가 권한 판정보다 선행하며, 미인증자는 actor 추출 단계에서 401 로 차단된다.
     *
     * @param boardId 접근할 보드 UUID.
     * @return 인증 주체 UUID 와 보드 메타의 쌍.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     * @throws BoardAccessDeniedException 403 — BROWSE 권한 미충족.
     */
    private fun loadBoardWithBrowse(boardId: UUID): Pair<UUID, Board> {
        val actor = currentActorId()
        val board = boardRepository.findById(boardId) ?: throw BoardNotFoundException()
        requirePermission(actor, IssuePermission.BROWSE, IssueScope.Project(board.projectKey))
        return actor to board
    }

    /**
     * actor 추출 → 보드 메타 조회(404) → CREATE 권한 판정을 한 순서로 수행한다.
     *
     * 스윔레인 변경·WIP 제한 변경이 공유하는 보드 쓰기 접근 게이트다.
     *
     * @param boardId 접근할 보드 UUID.
     * @return 인증 주체 UUID 와 보드 메타의 쌍.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     * @throws BoardAccessDeniedException 403 — CREATE 권한 미충족.
     */
    private fun loadBoardWithCreate(boardId: UUID): Pair<UUID, Board> {
        val actor = currentActorId()
        val board = boardRepository.findById(boardId) ?: throw BoardNotFoundException()
        requirePermission(actor, IssuePermission.CREATE, IssueScope.Project(board.projectKey))
        return actor to board
    }

    /**
     * 권한을 판정하고 미충족 시 [BoardAccessDeniedException](403)을 던진다.
     *
     * fail-closed — [permissionResolver] 는 non-null 주입이므로 빈 부재 시 부팅이 실패한다.
     * 거부 메시지는 일반화되어 내부 정보(보드 존재 여부·정책)를 노출하지 않는다.
     *
     * @param actor 권한 평가 대상 행위자 UUID.
     * @param permission 검증할 이슈 권한.
     * @param scope 권한 적용 범위.
     * @throws BoardAccessDeniedException 권한 미충족 시 403.
     */
    private fun requirePermission(
        actor: UUID,
        permission: IssuePermission,
        scope: IssueScope,
    ) {
        if (!permissionResolver.hasPermission(actor, permission, scope)) {
            throw BoardAccessDeniedException()
        }
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
