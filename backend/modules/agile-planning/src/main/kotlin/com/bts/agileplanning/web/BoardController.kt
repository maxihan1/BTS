// 보드 REST API 컨트롤러 — 생성/조회/목록/카드이동 + 권한 게이트 (FR-BD-01 Task 9)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.BoardApplicationService
import com.bts.agileplanning.application.WipLimitChange
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.web.dto.BoardColumnResponse
import com.bts.agileplanning.web.dto.BoardDetailResponse
import com.bts.agileplanning.web.dto.BoardMetaResponse
import com.bts.agileplanning.web.dto.BoardResponse
import com.bts.agileplanning.web.dto.BoardSummaryResponse
import com.bts.agileplanning.web.dto.ColumnMetaResponse
import com.bts.agileplanning.web.dto.ColumnStateResponse
import com.bts.agileplanning.web.dto.CreateBoardRequest
import com.bts.agileplanning.web.dto.CreateColumnRequest
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.agileplanning.web.dto.DeleteColumnResponse
import com.bts.agileplanning.web.dto.MoveCardRequest
import com.bts.agileplanning.web.dto.MoveCardResponse
import com.bts.agileplanning.web.dto.ReplaceColumnStatesRequest
import com.bts.agileplanning.web.dto.UpdateBoardRequest
import com.bts.agileplanning.web.dto.UpdateColumnRequest
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import jakarta.validation.Valid
import org.openapitools.jackson.nullable.JsonNullable
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
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
 * - GET  `/api/v1/boards/{id}` — 보드 단건 조회(컬럼+카드+퀵필터). 권한 [IssuePermission.BROWSE].
 * - GET  `/api/v1/boards?projectKey=` — 프로젝트별 보드 목록. 권한 [IssuePermission.BROWSE].
 * - POST `/api/v1/boards/{id}/cards/{issueKey}/move` — 카드 이동. 보드 접근 [IssuePermission.BROWSE] +
 *   이동 자체는 [com.bts.shared.board.IssueTransitionPort] 가 TRANSITION 을 강제한다.
 * - PATCH `/api/v1/boards/{id}` — 보드 이름·스윔레인 기준 부분 갱신. 권한 [IssuePermission.CREATE].
 * - DELETE `/api/v1/boards/{id}` — 보드 소프트 삭제(이슈는 남는다).
 *   권한 [IssuePermission.SOFT_DELETE] on [IssueScope.Project].
 * - PATCH `/api/v1/boards/{id}/columns/{columnId}` — 컬럼 WIP 제한 설정/해제.
 *   권한 [IssuePermission.CREATE] on [IssueScope.Project].
 *
 * ### 권한 2단 게이트 (FR-BD-01-6)
 * - 조회/이동의 보드 접근 = BROWSE(목록 자격). 카드 노출 보안수준은 행 단위 보안필터(T4)가 별도 적용.
 * - 생성·수정 = CREATE(이슈 생성 동급, Maxi 게이트1 확정).
 * - 삭제 = SOFT_DELETE. BTS 에 per-board 관리자 개념이 없어 프로젝트 스코프 권한으로 근사한다(FR-BD-01-2b).
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
 * TooManyFunctions: 보드 REST 표면 7개 + 권한 게이트 helper 로 함수 수가 임계치를 넘는다. helper 를 합치면
 * 권한코드별 게이트(BROWSE/CREATE/SOFT_DELETE)가 한 함수에 섞여 순서·권한코드 실수를 막는 응집이 깨지므로
 * 클래스 단위로 억제한다.
 *
 * @param service 보드 유스케이스 서비스.
 * @param boardRepository 보드 메타(projectKey) 조회용. 권한 scope 산출과 404 판정에 사용한다.
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입).
 */
@Suppress("TooManyFunctions")
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
     * @param request 보드 생성 요청 바디(projectKey, name, 선택 boardType).
     * @return 201 Created + [BoardResponse](컬럼 포함) + `Location` 헤더.
     */
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateBoardRequest,
    ): ResponseEntity<DataResponse<BoardResponse>> {
        log.info("BoardController.create projectKey={} boardType={}", request.projectKey, request.boardType)

        val actor = currentActorId()
        requirePermission(actor, IssuePermission.CREATE, IssueScope.Project(request.projectKey))

        // 권한 판정 뒤에 파싱한다 — 앞에 두면 권한 없는 호출자가 허용값 밖 요청으로 400/403 을
        // 구분해 프로젝트 존재를 떠볼 수 있다(probe). 미지정은 KANBAN 이다.
        val boardType = BoardType.from(request.boardType)
        val board = service.createBoard(request.projectKey, request.name, boardType)
        // 컬럼 상태의 이름·카테고리(R11)는 워크플로우 카탈로그에만 있다. `listStates` 는
        // MANDATORY 라 컨트롤러가 직접 못 부르므로 서비스의 읽기 메서드를 거친다.
        val states = service.listWorkflowStates(board.projectKey)
        val location = URI.create("/api/v1/boards/${board.id}")
        return ResponseEntity.created(location).body(DataResponse(BoardResponse.from(board, states)))
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
     * 응답에는 보드에 저장된 퀵필터 목록(created_at ASC)도 함께 포함한다(FR-UX-01 Task 7).
     *
     * 응답의 `canDelete` 는 [IssuePermission.SOFT_DELETE] 판정 결과다(FR-BD-01-2d). BROWSE 와 권한코드가
     * 달라 [loadBoardWithBrowse] 의 판정을 재사용할 수 없으므로 상세 조회 1건당 권한 판정이 1회 늘어난다.
     * 목록 응답에는 싣지 않으므로 증가분은 상세 조회에 한정된다.
     *
     * @param id path variable 보드 UUID.
     * @param assignee 담당자 필터 파라미터 목록. UUID 또는 "unassigned" 센티널.
     * @param label 라벨 필터 파라미터 목록. 문자열 그대로 사용.
     * @param component 컴포넌트 필터 파라미터 목록. UUID.
     * @return 200 OK + [BoardDetailResponse](quickFilters·canDelete 포함).
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
        val canDelete =
            permissionResolver.hasPermission(actor, IssuePermission.SOFT_DELETE, IssueScope.Project(board.projectKey))
        return ResponseEntity.ok(
            DataResponse(BoardDetailResponse.of(board, result, result.quickFilters, canDelete)),
        )
    }

    /**
     * 프로젝트별 보드 목록을 조회한다.
     *
     * 권한: [IssuePermission.BROWSE] on [IssueScope.Project] (요청 projectKey 기준).
     * [IssuePermission.SOFT_DELETE] 는 게이트가 아니라 표시용 파생값이다 — 미보유여도 200 이다.
     *
     * @param projectKey 조회할 프로젝트 키.
     * @return 200 OK + [BoardSummaryResponse] 목록. 각 항목의 canDelete 는 프로젝트 스코프 판정
     *   **1회**의 결과를 함께 쓴다(보드 수와 무관하게 권한 조회는 늘지 않는다).
     */
    @GetMapping
    fun listBoards(
        @RequestParam projectKey: String,
    ): ResponseEntity<DataResponse<List<BoardSummaryResponse>>> {
        log.info("BoardController.listBoards projectKey={}", projectKey)

        val actor = currentActorId()
        requirePermission(actor, IssuePermission.BROWSE, IssueScope.Project(projectKey))

        // 프로젝트 스코프 근사(편차 X3)라 판정 1회로 N건을 덮는다.
        // per-board 관리자가 도입되면 이 줄과 LIST-6 을 반드시 함께 고쳐야 한다 —
        // 안 그러면 전 보드가 첫 보드의 답을 받는다.
        //
        // ★LIST-6·LIST-7 은 **호출 횟수 축만** 잡는다. per-board 관리자가 resolver 쪽에
        //   IssueScope.Board 를 신설하면서 이 줄을 안 고치면, 여기는 여전히 Project 로 1회만
        //   물으므로 calls 는 [BROWSE, SOFT_DELETE] 그대로이고 LIST-4/6/7 이 전부 초록인 채
        //   전 보드가 프로젝트 답을 받는다. 즉 그 도입이 이 줄을 **반드시 지나가지는 않는다** —
        //   실질 방어선은 이 주석이다. T3 계획은 이 한계를 물려받아 스코프 타입 축 단언을
        //   함께 세울 것.
        val canDelete =
            permissionResolver.hasPermission(actor, IssuePermission.SOFT_DELETE, IssueScope.Project(projectKey))

        val boards = service.listBoards(projectKey).map { BoardSummaryResponse.from(it, canDelete) }
        return ResponseEntity.ok(DataResponse(boards))
    }

    /**
     * 카드(이슈)를 다른 컬럼으로 이동한다(워크플로우 전환 위임).
     *
     * 권한: 보드 접근 [IssuePermission.BROWSE] + 이동 자체는 전환 포트가 TRANSITION 을 강제한다.
     * actor 는 body 로 받지 않고 SecurityContext 에서 추출한다(전환 포트 adapter 가 재추출).
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
        val expectedVersion =
            request.expectedVersion ?: error("expectedVersion 은 @NotNull 검증 통과 후 null 일 수 없습니다.")

        // toColumnId / toStateKey 는 그대로 넘긴다 — 「둘 중 정확히 하나」 판정은 서비스가 진다(R7).
        // 여기서도 재검하면 같은 규칙이 두 곳이 되고, 언젠가 갈린다.
        val result =
            service.moveCard(
                boardId = id,
                issueKey = issueKey,
                actorUserId = actor,
                toColumnId = request.toColumnId,
                toStateKey = request.toStateKey,
                expectedVersion = expectedVersion,
                resolutionId = request.resolutionId,
            )
        return ResponseEntity.ok(DataResponse(MoveCardResponse.of(result)))
    }

    /**
     * 보드의 이름과 스윔레인 기준 필드를 부분 갱신한다(FR-BD-01-2a).
     *
     * 권한: [IssuePermission.CREATE] on 보드의 프로젝트.
     *
     * 처리 순서: actor 추출(401) → 보드 메타 조회(404) → CREATE 권한(403) → 서비스 **1회** 위임.
     *
     * ### 바디 규칙 (3-state)
     * - 미전송 필드는 건드리지 않는다.
     * - **둘 다 미전송이면 400.** 아무것도 바꾸지 않는 요청이 조용히 200 을 받지 않게 한다 — 이 규칙이
     *   `@NotBlank` 시절의 400 을 승계한다.
     * - 명시 null 은 400. 보드는 이름도 스윔레인도 「해제」 의미가 없다.
     * - 공백 이름은 컨트롤러가 막지 않는다. 도메인 [com.bts.agileplanning.domain.Board] 의 init 이
     *   [com.bts.agileplanning.domain.BoardNameInvalidException] 을 던지고
     *   [BoardExceptionHandler.handleBoardNameInvalid] 가 400 으로 바꾼다. 컨트롤러가 선차단하면
     *   그 불변식이 dead code 가 된다.
     *
     * ### 원자성
     * 두 필드가 함께 와도 [BoardApplicationService.updateBoard] 에 **한 번만** 위임한다. 필드별로 나눠
     * 호출하면 서비스 메서드마다 트랜잭션이 열려, 뒤쪽이 400 을 던져도 앞선 이름 갱신은 이미 커밋된
     * 상태가 남는다(리뷰 지적 1). 컨트롤러는 트랜잭션 경계를 갖지 않으므로 분할을 되돌릴 수 없다.
     *
     * @param id path variable 보드 UUID.
     * @param request 부분 갱신 요청 바디(name·swimlaneField, 둘 다 선택).
     * @return 200 OK + [BoardMetaResponse]. 갱신 결과가 두 변경을 모두 반영한다.
     * @throws BoardNotFoundException 보드 미존재 → 404.
     * @throws BoardAccessDeniedException CREATE 권한 미충족 → 403.
     * @throws ResponseStatusException 400 — 갱신 필드 부재, 명시 null, 알 수 없는 swimlaneField 값.
     * @throws com.bts.agileplanning.domain.BoardNameInvalidException 400 — 공백 이름(도메인 불변식 위반).
     */
    @PatchMapping("/{id}")
    fun updateBoard(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateBoardRequest,
    ): ResponseEntity<DataResponse<BoardMetaResponse>> {
        log.info(
            "BoardController.updateBoard id={} namePresent={} swimlaneFieldPresent={}",
            id,
            request.name.isPresent,
            request.swimlaneField.isPresent,
        )

        loadBoardWithCreate(id)

        val name = presentValueOrNull(request.name, "name")
        val swimlaneField = presentValueOrNull(request.swimlaneField, "swimlaneField")
        if (name == null && swimlaneField == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "name 또는 swimlaneField 중 하나는 전송해야 합니다.",
            )
        }

        val updated = service.updateBoard(id, name, swimlaneField)
        return ResponseEntity.ok(DataResponse(BoardMetaResponse.from(updated)))
    }

    /**
     * 보드를 소프트 삭제한다(FR-BD-01-2b). 이슈는 남는다.
     *
     * 권한: [IssuePermission.SOFT_DELETE] on 보드의 프로젝트.
     *
     * ```
     * DELETE /api/v1/boards/{id}
     *   ├─ actor 추출 ─────────────────────────── 없음 → 401
     *   ├─ loadBoardWithSoftDelete(id)
     *   │    ├─ findById (deleted_at IS NULL) ─── 없음 → 404
     *   │    └─ hasPermission(SOFT_DELETE) ────── 거부 → 403
     *   └─ service.softDelete(id) ─────────────── deleted_at = now() → 204
     * ```
     *
     * 순서가 뒤집히면 403 이 「그 보드는 존재한다」를 누설한다. 로컬 개발자는 항상 권한을 가지므로
     * 그 뒤집힘이 눈에 보이지 않는다 — 순서는 [loadBoardWithSoftDelete] 한 곳에 응집해 둔다.
     *
     * @param id path variable 보드 UUID.
     * @return 204 No Content.
     * @throws BoardNotFoundException 보드 미존재 또는 이미 soft-deleted → 404.
     * @throws BoardAccessDeniedException SOFT_DELETE 권한 미충족 → 403.
     * @throws ResponseStatusException 401 — 미인증.
     */
    @DeleteMapping("/{id}")
    fun deleteBoard(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        log.info("BoardController.deleteBoard id={}", id)

        loadBoardWithSoftDelete(id)
        service.softDelete(id)
        return ResponseEntity.noContent().build()
    }

    /**
     * 보드 컬럼의 이름과 WIP 제한을 부분 갱신한다 (R9 · J24 · J29).
     *
     * 권한: [IssuePermission.CREATE] on 보드의 프로젝트. 컬럼 구성 변경은 같은 무게의 조작이라
     * 기존 게이트를 그대로 승계한다 — 새 권한 축을 만들지 않는다.
     *
     * 처리 순서: actor 추출(401) → 보드 메타 조회(404) → CREATE 권한(403) → 요청 해석 → 서비스 위임.
     *
     * ★**「최소 1필드」와 「명시 null」을 서비스 호출 전에 판정한다.** 검증이 쓰기보다 앞서야
     * 400 응답과 커밋된 상태가 어긋나지 않는다 — `updateBoard` 가 같은 순서를 쓴다.
     *
     * @param id path variable 보드 UUID.
     * @param columnId path variable 컬럼 UUID.
     * @param request 컬럼 부분 갱신 바디. 두 필드 모두 부재면 400.
     * @return 200 OK + [ColumnMetaResponse].
     * @throws BoardNotFoundException 보드 미존재 → 404.
     * @throws BoardAccessDeniedException CREATE 권한 미충족 → 403.
     * @throws ResponseStatusException 400 — 빈 바디 · 공백 이름 · 명시 null 이름 · wipLimit 0 이하.
     * @throws ResponseStatusException 404 — 타 보드 소속 또는 미존재 컬럼.
     */
    @PatchMapping("/{id}/columns/{columnId}")
    fun updateColumn(
        @PathVariable id: UUID,
        @PathVariable columnId: UUID,
        @Valid @RequestBody request: UpdateColumnRequest,
    ): ResponseEntity<DataResponse<ColumnMetaResponse>> {
        log.info(
            "BoardController.updateColumn id={} columnId={} namePresent={} wipLimitPresent={}",
            id,
            columnId,
            request.name.isPresent,
            request.wipLimit.isPresent,
        )

        val (_, board) = loadBoardWithCreate(id)

        if (!request.name.isPresent && !request.wipLimit.isPresent) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "name 또는 wipLimit 중 하나는 전송해야 합니다.")
        }

        // presentValueOrNull 은 명시 null 을 400 으로 바꾼다 — 컬럼 이름은 해제할 수 없다.
        val name = presentValueOrNull(request.name, "name")
        if (name != null && name.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "name 은 공백일 수 없습니다.")
        }

        val wipLimit = resolveWipLimitChange(request.wipLimit)

        val updatedColumn = service.updateColumn(id, columnId, name, wipLimit)
        val catalog = ColumnStateResponse.catalog(service.listWorkflowStates(board.projectKey))
        return ResponseEntity.ok(DataResponse(ColumnMetaResponse.from(updatedColumn, catalog)))
    }

    /**
     * `wipLimit` 요청 필드를 갱신 의도로 옮긴다.
     *
     * 부재는 [WipLimitChange.Unchanged], 전송은 [WipLimitChange.Set] 이다 — **명시 null 도 전송**이고
     * 그 뜻은 해제다(J29). 양수 검증은 여기서 한다: jakarta `@Positive` 가 nullable 에서 0 을
     * 통과시켜 애너테이션으로는 못 막는다.
     */
    private fun resolveWipLimitChange(field: JsonNullable<Int?>): WipLimitChange {
        if (!field.isPresent) return WipLimitChange.Unchanged
        val value = field.get()
        if (value != null && value < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "wipLimit 는 1 이상이어야 합니다.")
        }
        return WipLimitChange.Set(value)
    }

    /**
     * 보드에 컬럼을 추가한다 (R9 · J2).
     *
     * 권한: [IssuePermission.CREATE] on 보드의 프로젝트 — 기존 `PATCH /{id}/columns/{columnId}` 의
     * [loadBoardWithCreate] 게이트를 **승계**한다. 컬럼 구성 변경은 같은 무게의 조작이다.
     *
     * `stateKeys` 를 비워 보내면 상태 0개 컬럼이 만들어진다(E1) — 지라의 「컬럼 먼저, 상태는 드래그로」
     * 흐름이 그것을 요구한다.
     *
     * @param id path variable 보드 UUID.
     * @param request 컬럼 생성 요청(name 필수 · stateKeys · displayOrder 선택).
     * @return 201 Created + [BoardColumnResponse].
     */
    @PostMapping("/{id}/columns")
    fun createColumn(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreateColumnRequest,
    ): ResponseEntity<DataResponse<BoardColumnResponse>> {
        log.info("BoardController.createColumn id={} states={}", id, request.stateKeys)

        val (_, board) = loadBoardWithCreate(id)
        val name = request.name ?: error("name 은 @NotBlank 검증 통과 후 null 일 수 없습니다.")
        val created = service.createColumn(id, name, request.stateKeys, request.displayOrder)
        val catalog = ColumnStateResponse.catalog(service.listWorkflowStates(board.projectKey))
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(DataResponse(BoardColumnResponse.from(created, catalog)))
    }

    /**
     * 컬럼이 담는 상태 집합을 통째로 교체한다 (R9).
     *
     * `PUT` 인 이유는 집합 **전체**를 받아야 X1(한 상태는 한 컬럼에만) 위반을 한 요청 안에서
     * 판정할 수 있어서다. 다른 컬럼이 쓰는 상태가 섞이면 409 이고 어느 컬럼인지 알려준다(E7).
     *
     * @param id path variable 보드 UUID.
     * @param columnId path variable 컬럼 UUID.
     * @param request 새 상태 집합.
     * @return 200 OK + [ColumnMetaResponse](갱신된 states·category 반영).
     */
    @PutMapping("/{id}/columns/{columnId}/states")
    fun replaceColumnStates(
        @PathVariable id: UUID,
        @PathVariable columnId: UUID,
        @Valid @RequestBody request: ReplaceColumnStatesRequest,
    ): ResponseEntity<DataResponse<ColumnMetaResponse>> {
        log.info("BoardController.replaceColumnStates id={} columnId={} states={}", id, columnId, request.stateKeys)

        val (_, board) = loadBoardWithCreate(id)
        val updated = service.replaceColumnStates(id, columnId, request.stateKeys)
        val catalog = ColumnStateResponse.catalog(service.listWorkflowStates(board.projectKey))
        return ResponseEntity.ok(DataResponse(ColumnMetaResponse.from(updated, catalog)))
    }

    /**
     * 컬럼을 삭제한다 (R10 · J5).
     *
     * 담긴 상태는 미매핑으로 돌아가고 **이슈는 손대지 않는다** — 미매핑 목록은 보드 조회의
     * `unmappedStates` 가 이미 준다.
     *
     * 204 가 아니라 200 인 이유는 **폭발 반경**을 알려야 하기 때문이다(ceo 리뷰 CONCERN-3).
     * 이슈는 남지만 사용자가 보기엔 카드가 증발하고, 몇 장인지 모르면 되돌릴 판단을 할 수 없다.
     *
     * @param id path variable 보드 UUID.
     * @param columnId path variable 컬럼 UUID.
     * @return 200 OK + [DeleteColumnResponse](사라지는 카드 수).
     */
    @DeleteMapping("/{id}/columns/{columnId}")
    fun deleteColumn(
        @PathVariable id: UUID,
        @PathVariable columnId: UUID,
    ): ResponseEntity<DataResponse<DeleteColumnResponse>> {
        log.info("BoardController.deleteColumn id={} columnId={}", id, columnId)

        // 카드 수는 **요청자가 보는 기준**으로 센다 — 행 단위 보안 필터가 사람마다 다른 수를 준다.
        val (actor, _) = loadBoardWithCreate(id)
        val removed = service.deleteColumn(id, columnId, actor)
        return ResponseEntity.ok(DataResponse(DeleteColumnResponse(removedCardCount = removed)))
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
     * actor 추출 → 보드 메타 조회(404) → SOFT_DELETE 권한 판정을 한 순서로 수행한다.
     *
     * 삭제 전용 게이트다. [loadBoardWithCreate] 와 동형이며 권한코드만 다르다 — 수정 권한으로 삭제까지
     * 열리지 않게 코드를 분리한다. 존재 검사가 권한 판정보다 **먼저**여야 미보유자에게 돌아가는 403 이
     * 보드 존재를 누설하지 않는다.
     *
     * @param boardId 삭제할 보드 UUID.
     * @return 인증 주체 UUID 와 보드 메타의 쌍.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     * @throws BoardAccessDeniedException 403 — SOFT_DELETE 권한 미충족.
     */
    private fun loadBoardWithSoftDelete(boardId: UUID): Pair<UUID, Board> {
        val actor = currentActorId()
        val board = boardRepository.findById(boardId) ?: throw BoardNotFoundException()
        requirePermission(actor, IssuePermission.SOFT_DELETE, IssueScope.Project(board.projectKey))
        return actor to board
    }

    /**
     * [JsonNullable] 의 3-state 를 「미전송 → null · 전송 → 비-null 값」 2-state 로 좁힌다.
     *
     * presence 만 보고 통과시키면 명시 null(`{"name":null}`)이 그대로 흘러 500 이 된다.
     * 보드에는 「필드 해제」 의미가 없으므로 present-null 은 400 으로 거부한다. 그 결과 반환 null 은
     * 오직 「미전송」만 뜻하므로, 호출부가 부분 갱신 여부를 한 번의 서비스 위임으로 표현할 수 있다.
     *
     * @param field 요청 바디의 [JsonNullable] 필드.
     * @param fieldName 응답 사유에 쓸 필드 이름.
     * @return 전송된 비-null 값. 미전송이면 null.
     * @throws ResponseStatusException 400 — 명시 null 이 전송됐을 때.
     */
    private fun presentValueOrNull(
        field: JsonNullable<String?>,
        fieldName: String,
    ): String? {
        if (!field.isPresent) return null
        return field.get()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$fieldName 는 null 일 수 없습니다.")
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
