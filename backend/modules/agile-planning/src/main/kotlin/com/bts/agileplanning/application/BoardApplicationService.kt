// 보드 CRUD + 카드 이동 위임 애플리케이션 서비스 — agile-planning BC (FR-BD-01)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardCardPlacement
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.domain.SwimlaneField
import com.bts.agileplanning.repository.BoardQuickFilterRepository
import com.bts.agileplanning.repository.BoardRepository
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.board.BoardTransitionCommand
import com.bts.shared.board.BoardTransitionResult
import com.bts.shared.board.IssueTransitionPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * [BoardApplicationService.getBoard] 반환 VO.
 *
 * 카드 배치 결과([columns])와 함께 카드 수 신호([truncated], [unplacedCount])를 담아
 * 컨트롤러가 [com.bts.agileplanning.web.dto.BoardDetailResponse] 로 조립할 수 있도록 한다.
 *
 * @property columns 카드가 배치된 컬럼 목록.
 * @property truncated BOARD_CARD_FETCH_LIMIT 초과로 이슈 일부가 누락됐으면 true.
 * @property unplacedCount 어느 컬럼에도 매핑되지 않아 보드에서 제외된 이슈 수 (E2 상황).
 * @property quickFilters 보드에 저장된 퀵필터 도메인 목록(created_at ASC, FR-UX-01 Task 7). 기본값은 빈 목록.
 */
data class BoardPlacementResult(
    val columns: List<PlacedColumn>,
    val truncated: Boolean,
    val unplacedCount: Int,
    val quickFilters: List<QuickFilter> = emptyList(),
)

/**
 * 보드 CRUD 및 카드 이동 위임 애플리케이션 서비스.
 *
 * cross-BC 통신은 shared-kernel 포트([WorkflowStateCatalog], [BoardIssueLookupPort], [IssueTransitionPort])만
 * 사용한다. issue-tracking / project-workflow / identity-access 내부를 직접 import 하지 않는다.
 *
 * ## 보드 생성
 * [WorkflowStateCatalog.listStates] 로 default 워크플로우 상태 조회 →
 * [BoardCardPlacement.seedColumns] 로 컬럼 시드 → boards/board_columns 영속.
 * E1: 스킴 미할당(빈 상태 목록) → 422.
 *
 * ## 보드 조회
 * boards/board_columns 로드 → [BoardIssueLookupPort.listVisibleIssuesByProject] 로 카드 조회 →
 * [BoardCardPlacement.placeCards] 로 배치.
 *
 * ## 카드 이동
 * toColumnId → 컬럼의 state_key 도출 → [IssueTransitionPort.transition] 위임.
 * E8: issueKey 가 보드 project_key 소속이 아니거나 형식이 올바르지 않으면 거부.
 * E3(같은 컬럼) no-op 판단은 전이 포트에 위임한다 — 클라이언트(프론트 D6)가 드래그 원위치 감지로 요청 미발생 처리.
 *
 * @param workflowStateCatalog default 워크플로우 상태 조회 포트 (project-workflow 구현)
 * @param boardIssueLookupPort 프로젝트 이슈 목록 조회 포트 (issue-tracking 구현)
 * @param issueTransitionPort 전이 위임 포트 — fail-closed (issue-tracking 구현)
 * @param boardRepository boards/board_columns jOOQ repository
 * @param boardQuickFilterRepository board_quick_filters jOOQ repository (FR-UX-01 Task 7)
 */
@Service
class BoardApplicationService(
    private val workflowStateCatalog: WorkflowStateCatalog,
    private val boardIssueLookupPort: BoardIssueLookupPort,
    private val issueTransitionPort: IssueTransitionPort,
    private val boardRepository: BoardRepository,
    private val boardQuickFilterRepository: BoardQuickFilterRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 보드를 생성하고 영속한다.
     *
     * default 워크플로우 상태를 컬럼으로 시드한다.
     * E1: 워크플로우 스킴이 없어 빈 상태 목록이 반환되면 422 를 던진다.
     *
     * @param projectKey 보드를 생성할 프로젝트 키. 예: `"BTS"`.
     * @param name 보드 표시 이름.
     * @return 생성된 보드 도메인 객체(컬럼 포함).
     * @throws ResponseStatusException 422 — 워크플로우 스킴 미할당(E1).
     */
    @Transactional
    fun createBoard(
        projectKey: String,
        name: String,
    ): Board {
        log.debug("보드 생성 시작 — projectKey={}, name={}", projectKey, name)

        val states = workflowStateCatalog.listStates(ProjectKey.of(projectKey), null)
        if (states.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "AGILE_BOARD_WORKFLOW_NOT_ASSIGNED: 프로젝트($projectKey)에 워크플로우 스킴이 할당되지 않아 보드를 생성할 수 없습니다.",
            )
        }

        val columns = BoardCardPlacement.seedColumns(states)
        val board =
            Board(
                id = UUID.randomUUID(),
                projectKey = projectKey,
                name = name,
                columns = columns,
                createdAt = java.time.Instant.now(),
                updatedAt = java.time.Instant.now(),
            )

        val saved = boardRepository.insert(board)
        log.debug("보드 생성 완료 — boardId={}, columns={}", saved.id, saved.columns.size)
        return saved
    }

    /**
     * 보드 단건을 조회하고 카드를 컬럼에 배치한다.
     *
     * [boardIssueLookupPort.listVisibleIssuesByProject] 의 3-인자 메서드로 viewer 기준 가시 이슈를 조회하며
     * [filter] 를 그대로 전달한다. [BoardCardPlacement.placeCards] 로 컬럼에 배치한다.
     * [BoardPlacementResult.truncated] 가 true 이면 BOARD_CARD_FETCH_LIMIT 초과로 일부 이슈가 누락됐다.
     * [BoardPlacementResult.unplacedCount] 가 0 초과이면 컬럼에 매핑되지 않는 이슈가 있었다(E2 상황).
     * [boardQuickFilterRepository.findByBoardId] 로 보드에 저장된 퀵필터 목록(created_at ASC)을 함께
     * 조회해 [BoardPlacementResult.quickFilters] 에 포함한다(FR-UX-01 Task 7).
     *
     * @param boardId 조회할 보드 UUID.
     * @param viewerUserId 보드를 조회하는 사용자 UUID. visibility 필터 기준.
     * @param filter 보드 카드 필터 조건. 기본값 [BoardCardFilter.EMPTY] 이면 무필터와 동일.
     * @return [BoardPlacementResult] — 배치 결과 + truncated + unplacedCount + quickFilters.
     * @throws ResponseStatusException 404 — 보드 미존재 또는 soft-deleted.
     */
    @Transactional(readOnly = true)
    fun getBoard(
        boardId: UUID,
        viewerUserId: UUID,
        filter: BoardCardFilter = BoardCardFilter.EMPTY,
    ): BoardPlacementResult {
        val board =
            boardRepository.findById(boardId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "보드를 찾을 수 없습니다: boardId=$boardId")

        val page =
            boardIssueLookupPort.listVisibleIssuesByProject(
                projectKey = board.projectKey,
                viewerUserId = viewerUserId,
                filter = filter,
            )
        val placed = BoardCardPlacement.placeCards(board.columns, page.issues)
        val quickFilters = boardQuickFilterRepository.findByBoardId(boardId)
        return BoardPlacementResult(
            columns = placed.columns,
            truncated = page.truncated,
            unplacedCount = placed.unplacedCount,
            quickFilters = quickFilters,
        )
    }

    /**
     * 프로젝트별 활성 보드 목록을 반환한다.
     *
     * @param projectKey 조회할 프로젝트 키.
     * @return 보드 목록 (컬럼 미포함, 목록용).
     */
    @Transactional(readOnly = true)
    fun listBoards(projectKey: String): List<Board> {
        return boardRepository.findAllByProjectKey(projectKey)
    }

    /**
     * 카드(이슈)를 다른 컬럼으로 이동한다.
     *
     * toColumnId → 대상 컬럼 state_key 도출 → [IssueTransitionPort.transition] 위임.
     *
     * E8: issueKey 의 프로젝트 접두사가 보드의 projectKey 와 다르거나 형식이 올바르지 않으면 거부한다.
     *
     * ### E3 (같은 컬럼 no-op) 정책
     * E3 no-op 판단 책임은 이 서비스에 없다. 같은 컬럼으로의 이동은 전이 포트([IssueTransitionPort])에
     * 위임하며, 워크플로우 정책(self-transition 허용 여부)을 존중한다.
     * 클라이언트(프론트엔드 D6)가 드래그 원위치를 감지해 요청 자체를 발생시키지 않는 것이 정석이다.
     * 이전에 있던 client-supplied currentStateKey 기반 no-op 분기는 client 신뢰 위험이 있어 제거됐다.
     *
     * @param boardId 이동 대상 보드 UUID.
     * @param issueKey 이동할 이슈 키. 예: `"BTS-1"`. 형식 = `"PROJECT_KEY-NUMBER"`.
     * @param actorUserId 전이 행위자 UUID. 컨트롤러가 SecurityContext 에서 추출해 전달한다
     *   (body/param 으로 받지 않음 — 위조 차단, sec codereview-fix P1).
     * @param toColumnId 이동 대상 컬럼 UUID.
     * @param expectedVersion 낙관적 락(OCC) 기대 버전.
     * @param resolutionId DONE 카테고리 전이 시 필요한 해결 방안 ID. 불필요하면 null.
     * @return 전이 결과 VO.
     * @throws ResponseStatusException 404 — 보드/컬럼 미존재.
     * @throws ResponseStatusException 400 — E8 보드-이슈 프로젝트 정합 위반.
     */
    @Transactional
    @Suppress("LongParameterList") // 카드 이동 커맨드 필드를 VO 없이 직접 받음 — 호출부 명료성 우선
    fun moveCard(
        boardId: UUID,
        issueKey: String,
        actorUserId: UUID,
        toColumnId: UUID,
        expectedVersion: Long,
        resolutionId: UUID?,
    ): BoardTransitionResult {
        log.debug("카드 이동 — boardId={}, issueKey={}, toColumnId={}", boardId, issueKey, toColumnId)

        val board =
            boardRepository.findById(boardId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "보드를 찾을 수 없습니다: boardId=$boardId")

        // E8: issueKey 형식 + 프로젝트 정합 검증
        validateIssueProject(issueKey, board.projectKey)

        val targetColumn =
            board.columns.find { it.id == toColumnId }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "컬럼을 찾을 수 없습니다: columnId=$toColumnId")

        return issueTransitionPort.transition(
            BoardTransitionCommand(
                actorUserId = actorUserId,
                issueKey = issueKey,
                toStateKey = targetColumn.stateKey,
                expectedVersion = expectedVersion,
                resolutionId = resolutionId,
            ),
        )
    }

    /**
     * 보드 컬럼의 WIP 제한을 갱신하고 갱신된 컬럼을 반환한다.
     *
     * [boardRepository.updateColumnWipLimit] 가 null 을 반환하면 보드 또는 컬럼이 존재하지 않거나
     * 타 보드 소속이므로 404 를 던진다.
     *
     * @param boardId 갱신 대상 보드 UUID.
     * @param columnId 갱신 대상 컬럼 UUID.
     * @param wipLimit 새로운 WIP 제한. null 이면 해제.
     * @return 갱신된 컬럼 도메인 객체.
     * @throws ResponseStatusException 404 — 보드/컬럼 미존재 또는 타 보드 소속.
     */
    @Transactional
    fun updateColumnWipLimit(
        boardId: UUID,
        columnId: UUID,
        wipLimit: Int?,
    ): BoardColumn {
        log.debug("WIP 제한 갱신 — boardId={}, columnId={}, wipLimit={}", boardId, columnId, wipLimit)
        return boardRepository.updateColumnWipLimit(boardId, columnId, wipLimit)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "AGILE_BOARD_NOT_FOUND: 컬럼을 찾을 수 없습니다: boardId=$boardId, columnId=$columnId",
            )
    }

    /**
     * 보드의 스윔레인 기준 필드를 갱신하고 갱신된 보드를 반환한다.
     *
     * [swimlaneFieldRaw] 를 [SwimlaneField] enum 으로 파싱한다. 알 수 없는 값이면 400 을 던진다.
     * [boardRepository.updateSwimlaneField] 가 null 을 반환하면 보드가 존재하지 않으므로 404 를 던진다.
     *
     * @param boardId 갱신 대상 보드 UUID.
     * @param swimlaneFieldRaw 스윔레인 기준 필드 이름 문자열. 예: `"NONE"`, `"ASSIGNEE"`, `"PRIORITY"`.
     * @return 갱신된 보드 도메인 객체.
     * @throws ResponseStatusException 400 — 알 수 없는 [swimlaneFieldRaw] 값.
     * @throws ResponseStatusException 404 — 보드 미존재 또는 soft-deleted.
     */
    @Transactional
    fun updateSwimlaneField(
        boardId: UUID,
        swimlaneFieldRaw: String,
    ): Board {
        log.debug("스윔레인 필드 갱신 — boardId={}, swimlaneField={}", boardId, swimlaneFieldRaw)
        val swimlaneField =
            SwimlaneField.entries.firstOrNull { it.name == swimlaneFieldRaw }
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "AGILE_VALIDATION_FAILED: 알 수 없는 swimlaneField 값입니다: $swimlaneFieldRaw",
                )
        return boardRepository.updateSwimlaneField(boardId, swimlaneField)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "AGILE_BOARD_NOT_FOUND: 보드를 찾을 수 없습니다: boardId=$boardId",
            )
    }

    /**
     * issueKey 가 보드의 projectKey 소속이며 올바른 형식인지 검증한다.
     *
     * E8: 다른 프로젝트 이슈 이동 시도 방지 + 형식 fast-fail.
     * issueKey 형식 = `"PROJECT_KEY-NUMBER"` (예: `"BTS-1"`, `"ATLAS-42"`).
     *
     * 검증 조건.
     * 1. `issueKey.startsWith("$boardProjectKey-")` — `"BTSX-1"` 같은 prefix 충돌 차단.
     * 2. 하이픈 뒤가 숫자만으로 구성 — `"BTS-abc"` 같은 형식 오류 차단.
     *
     * 실제 격리 가드는 downstream [IssueTransitionPort](transitionIssue)가 권한(TRANSITION) 강제로
     * 보장한다. 이 메서드는 fast-fail 정합 체크 역할이다.
     *
     * @throws ResponseStatusException 400 — E8 보드-이슈 프로젝트 불일치 또는 형식 오류.
     */
    private fun validateIssueProject(
        issueKey: String,
        boardProjectKey: String,
    ) {
        val prefix = "$boardProjectKey-"
        val numberPart = issueKey.removePrefix(prefix)
        val valid =
            issueKey.startsWith(prefix) &&
                numberPart.isNotEmpty() &&
                numberPart.all { it.isDigit() }
        if (!valid) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "AGILE_BOARD_ISSUE_PROJECT_MISMATCH: 이슈($issueKey)는 보드 프로젝트($boardProjectKey)에 속하지 않거나 형식이 올바르지 않습니다.",
            )
        }
    }
}
