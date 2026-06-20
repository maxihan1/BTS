// 보드 CRUD + 카드 이동 위임 애플리케이션 서비스 — agile-planning BC (FR-BD-01)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardCardPlacement
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.agileplanning.repository.BoardRepository
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
 * E3: 같은 컬럼(현재 상태 = 대상 state_key) → no-op(전이 없이 현재 반환).
 * E8: issueKey 가 보드 project_key 소속이 아니면 거부.
 *
 * @param workflowStateCatalog default 워크플로우 상태 조회 포트 (project-workflow 구현)
 * @param boardIssueLookupPort 프로젝트 이슈 목록 조회 포트 (issue-tracking 구현)
 * @param issueTransitionPort 전이 위임 포트 — fail-closed (issue-tracking 구현)
 * @param boardRepository boards/board_columns jOOQ repository
 */
@Service
class BoardApplicationService(
    private val workflowStateCatalog: WorkflowStateCatalog,
    private val boardIssueLookupPort: BoardIssueLookupPort,
    private val issueTransitionPort: IssueTransitionPort,
    private val boardRepository: BoardRepository,
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
     * [boardIssueLookupPort.listVisibleIssuesByProject] 로 viewer 기준 가시 이슈를 조회한 뒤
     * [BoardCardPlacement.placeCards] 로 컬럼에 배치한다.
     *
     * @param boardId 조회할 보드 UUID.
     * @param viewerUserId 보드를 조회하는 사용자 UUID. visibility 필터 기준.
     * @return 컬럼 + 배치된 카드 목록.
     * @throws ResponseStatusException 404 — 보드 미존재 또는 soft-deleted.
     */
    @Transactional(readOnly = true)
    fun getBoard(
        boardId: UUID,
        viewerUserId: UUID,
    ): List<PlacedColumn> {
        val board =
            boardRepository.findById(boardId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "보드를 찾을 수 없습니다: boardId=$boardId")

        val issues =
            boardIssueLookupPort.listVisibleIssuesByProject(
                projectKey = board.projectKey,
                viewerUserId = viewerUserId,
            )
        return BoardCardPlacement.placeCards(board.columns, issues)
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
     * E3: 현재 상태 = 대상 state_key(같은 컬럼)이면 전이 없이 현재 상태를 반환한다.
     * E8: issueKey 의 프로젝트 접두사가 보드의 projectKey 와 다르면 거부한다.
     *
     * @param boardId 이동 대상 보드 UUID.
     * @param issueKey 이동할 이슈 키. 예: `"BTS-1"`.
     * @param actorUserId 전이 행위자 UUID. 컨트롤러가 SecurityContext 에서 추출해 전달한다
     *   (body/param 으로 받지 않음 — 위조 차단, sec codereview-fix P1).
     * @param toColumnId 이동 대상 컬럼 UUID.
     * @param expectedVersion 낙관적 락(OCC) 기대 버전.
     * @param resolutionId DONE 카테고리 전이 시 필요한 해결 방안 ID. 불필요하면 null.
     * @param currentStateKey 현재 이슈 상태 키. E3 no-op 판정용. null 이면 no-op 판정 생략.
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
        currentStateKey: String? = null,
    ): BoardTransitionResult {
        log.debug("카드 이동 — boardId={}, issueKey={}, toColumnId={}", boardId, issueKey, toColumnId)

        val board =
            boardRepository.findById(boardId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "보드를 찾을 수 없습니다: boardId=$boardId")

        // E8: issueKey 프로젝트 접두사 정합 검증 (issueKey = "BTS-1" → "BTS")
        validateIssueProject(issueKey, board.projectKey)

        val targetColumn =
            board.columns.find { it.id == toColumnId }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "컬럼을 찾을 수 없습니다: columnId=$toColumnId")

        // E3: 같은 컬럼(현재 상태 = 대상 state_key) → no-op
        if (currentStateKey != null && currentStateKey == targetColumn.stateKey) {
            log.debug("E3 no-op — issueKey={}, stateKey={}", issueKey, currentStateKey)
            return BoardTransitionResult(
                issueKey = issueKey,
                currentStateKey = currentStateKey,
                version = expectedVersion,
            )
        }

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
     * issueKey 의 프로젝트 접두사가 보드의 projectKey 와 일치하는지 검증한다.
     *
     * E8: 다른 프로젝트 이슈 이동 시도 방지.
     * issueKey 형식 = "PROJECTKEY-N" (예: "BTS-1", "ATLAS-42").
     *
     * @throws ResponseStatusException 400 — 프로젝트 불일치.
     */
    private fun validateIssueProject(
        issueKey: String,
        boardProjectKey: String,
    ) {
        val issueProjectKey = issueKey.substringBefore("-")
        if (issueProjectKey != boardProjectKey) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "AGILE_BOARD_ISSUE_PROJECT_MISMATCH: 이슈($issueKey)는 보드 프로젝트($boardProjectKey)에 속하지 않습니다.",
            )
        }
    }
}
