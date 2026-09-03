// 보드 CRUD + 카드 이동 위임 애플리케이션 서비스 — agile-planning BC (FR-BD-01)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardCardPlacement
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.BoardNameInvalidException
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SwimlaneField
import com.bts.agileplanning.repository.BoardQuickFilterRepository
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.SprintRepository
import com.bts.agileplanning.web.BoardNotFoundException
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.board.BoardIssuePage
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
import java.time.Instant
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
 * @property activeSprint 스크럼 보드의 활성 스프린트. **칸반 보드는 항상 `null`** 이고, 스크럼이라도
 *   시작된 스프린트가 없으면 `null` 이다. 클라이언트는 `null` 이면 「스프린트를 시작하세요」 빈 상태를
 *   그린다(FR-BD-04 D6, PR ③).
 */
data class BoardPlacementResult(
    val columns: List<PlacedColumn>,
    val truncated: Boolean,
    val unplacedCount: Int,
    val quickFilters: List<QuickFilter> = emptyList(),
    val activeSprint: Sprint? = null,
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
 * ## 보드 부분 갱신 / 삭제 (FR-BD-01-2)
 * [updateBoard] 는 이름과 스윔레인 기준 필드를 **한 트랜잭션**에서 갱신한다. 기존 보드를 [Board.copy] 로
 * 재구성해 도메인 불변 계약(name 비공백)에 검증을 맡기고, 스윔레인 문자열 파싱도 같은 경계 안에서 한다.
 * [softDelete] 는 boards.deleted_at 만 채우고 이슈는 남긴다. 둘 다 repository 의 null/false 를
 * [BoardNotFoundException](404)으로 승격한다. 권한 판정은 컨트롤러 책임이다.
 *
 * ## 카드 이동
 * toColumnId → 컬럼의 state_key 도출 → [IssueTransitionPort.transition] 위임.
 * E8: issueKey 가 보드 project_key 소속이 아니거나 형식이 올바르지 않으면 거부.
 * E3(같은 컬럼) no-op 판단은 전환 포트에 위임한다 — 클라이언트(프론트 D6)가 드래그 원위치 감지로 요청 미발생 처리.
 *
 * @param workflowStateCatalog default 워크플로우 상태 조회 포트 (project-workflow 구현)
 * @param boardIssueLookupPort 프로젝트 이슈 목록 조회 포트 (issue-tracking 구현)
 * @param issueTransitionPort 전환 위임 포트 — fail-closed (issue-tracking 구현)
 * @param boardRepository boards/board_columns jOOQ repository
 * @param boardQuickFilterRepository board_quick_filters jOOQ repository (FR-UX-01 Task 7)
 * @param sprintRepository sprints/sprint_issues jOOQ repository — 스크럼 보드 카드 필터용 (FR-BD-04 D4).
 *   같은 BC 이므로 포트를 거치지 않는다. [BoardIssueLookupPort] 는 shared-kernel 이라 스프린트를 모르고,
 *   거기에 스프린트를 알리면 토폴로지 변경(T3)이 된다 — 재료가 이 BC 안에 이미 있어 그럴 이유가 없다.
 */
@Service
// 보드 CRUD(생성·조회·목록·갱신·삭제) + 카드 이동 + WIP + 스크럼 분기가 한 Aggregate 의 유스케이스라
// 쪼개면 트랜잭션 경계가 갈린다. 형제 BoardController 도 같은 이유로 억제한다.
@Suppress("TooManyFunctions")
class BoardApplicationService(
    private val workflowStateCatalog: WorkflowStateCatalog,
    private val boardIssueLookupPort: BoardIssueLookupPort,
    private val issueTransitionPort: IssueTransitionPort,
    private val boardRepository: BoardRepository,
    private val boardQuickFilterRepository: BoardQuickFilterRepository,
    private val sprintRepository: SprintRepository,
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
        boardType: BoardType = BoardType.KANBAN,
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
                boardType = boardType,
                columns = columns,
                createdAt = java.time.Instant.now(),
                updatedAt = java.time.Instant.now(),
            )

        val saved = boardRepository.insert(board)
        log.debug("보드 생성 완료 — boardId={}, columns={}", saved.id, saved.columns.size)
        return saved
    }

    /**
     * 그 프로젝트의 스크럼 보드 id 를 반환하고, **없으면 만든다.**
     *
     * ### 왜 필요한가
     * 스프린트는 이제 보드에 매달린다(`ADR 2026-09-01` D2). 그런데 백로그 화면은 아직 보드를
     * 지정하지 않고 스프린트를 만든다(PR ③ 에서 붙는다). 그 사이에도 스프린트 생성이 동작해야
     * 하므로, 보드를 안 준 요청은 여기서 붙을 자리를 찾는다.
     *
     * V506 백필이 **스프린트를 이미 가진 프로젝트**마다 스크럼 보드를 만들어 뒀다. 여기서 새로
     * 만드는 경우는 **마이그레이션 이후 첫 스프린트를 만드는 프로젝트**, 또는 **스크럼 보드를
     * 소프트 삭제한 프로젝트**다([findScrumBoardIdByProject] 가 `deleted_at IS NULL` 로 거른다).
     *
     * ### 경쟁 — 락을 먼저 잡는다
     * read-then-insert 라 잠금이 없으면 동시 요청 2건이 보드를 2개 만든다. 그러면 조회가
     * `created_at` 오래된 쪽만 집어 **늦은 보드에 붙은 스프린트가 백로그에서 갈린다**. 그래서
     * [BoardRepository.acquireProjectScrumBoardLock] 을 **조회보다 먼저** 잡는다 — 락 밖에서 읽은 값으로
     * 판단하면 락이 무력화된다(memory `advisory-lock-bigint-toctou`).
     *
     * ★ 이 메서드는 「프로젝트당 스크럼 보드 1개」를 **강제하지 않는다.** ADR D6 이 다수 보드를
     * 지원하므로 사용자가 두 번째 스크럼 보드를 직접 만들 수 있다. 그때 스프린트가 어디에 붙는지는
     * 스펙 엣지 케이스 **E-6** 이 다룬다 — 여기서 막는 것은 암묵 생성의 경쟁뿐이다.
     *
     * @param projectKey 대상 프로젝트 키.
     * @return 스크럼 보드 UUID.
     * @throws ResponseStatusException 422 — 워크플로우 스킴 미할당이라 컬럼을 시드할 수 없을 때
     *   ([createBoard] 의 기존 경로를 그대로 쓴다. 새 오류 경로를 만들지 않는다).
     */
    @Transactional
    fun ensureScrumBoard(projectKey: String): UUID {
        // ★ 순서가 계약이다 — 락 → 조회 → 삽입. 조회를 먼저 하면 락이 아무것도 막지 못한다.
        boardRepository.acquireProjectScrumBoardLock(projectKey)
        boardRepository.findScrumBoardIdByProject(projectKey)?.let { return it }
        log.debug("스크럼 보드 부재 — 신설한다. projectKey={}", projectKey)

        // ★ [createBoard] 를 재사용하지 않고 **컬럼 0개**로 만든다. 이유가 둘이다.
        //
        // ① [createBoard] 는 워크플로우 스킴이 없으면 422 를 던진다. 사용자가 **명시적으로** 보드를
        //    만들 때는 그것이 옳다(왜 안 되는지 알려야 한다). 그러나 여기는 **스프린트를 만들다가
        //    딸려 오는** 암묵 생성이라, 같은 422 를 내면 스킴 없는 프로젝트에서 스프린트 생성이
        //    막힌다 — 이 PR 이전에는 되던 동작이므로 명백한 회귀다.
        // ② 컬럼 시드는 `ProjectKey.of(projectKey)` 를 거치는데 그 정규식(`^[A-Z][A-Z0-9]{1,9}$`)이
        //    `sprints.project_key`(VARCHAR(64), 검증 없음)보다 **좁다**. 카탈로그를 여기서 부르면
        //    스프린트 생성 경로에 **없던 검증**이 끼어들어 기존 데이터·요청이 400 으로 죽는다.
        //
        // 컬럼은 조회 시 자가 치유가 채운다 — V506 백필도 같은 전제 위에 서 있다.
        val now = Instant.now()
        val board =
            Board(
                id = UUID.randomUUID(),
                projectKey = projectKey,
                name = "$projectKey 스크럼 보드",
                boardType = BoardType.SCRUM,
                columns = emptyList(),
                createdAt = now,
                updatedAt = now,
            )
        return boardRepository.insert(board).id
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
     * ## 스크럼 보드 분기 (FR-BD-04 D4)
     * [BoardType.SCRUM] 이면 그 보드의 **활성 스프린트에 할당된 이슈만** 남겨 배치한다. 활성 스프린트가
     * 없으면 카드가 0건이다(빈 보드). [BoardType.KANBAN] 은 **지금과 완전히 같다** — 분기 앞에서
     * 스프린트를 조회하지 않으므로 쿼리도 늘지 않는다(NFR-1).
     *
     * 필터는 [BoardCardPlacement.placeCards] **호출 전**에 건다. [BoardCardPlacement] 는 I/O 없는
     * 순수 함수라는 계약(NFR-2)이 있어 스프린트를 알게 하지 않는다.
     *
     * ## 컬럼 0개 보드 자가 치유
     * V506 백필이 복제할 칸반 보드를 못 찾았거나 [ensureScrumBoard] 가 암묵 생성한 보드는 컬럼이 없다.
     * 조회 시 워크플로우 카탈로그에서 시드해 **영속**한다 — 매번 즉석 생성하면 컬럼 UUID 가 흔들려
     * 카드 이동(`toColumnId`)이 깨진다. 시드할 수 없으면(스킴 미할당 등) **빈 보드 그대로 둔다**.
     * 새 오류 경로를 만들지 않는다 — 조회를 지금보다 나쁘게 만들지 않는 것이 우선이다.
     *
     * ★ [Transactional] 에 `readOnly` 를 두지 않는 이유가 자가 치유다. readOnly 트랜잭션은 JDBC 커넥션을
     * read-only 로 잡아 시드 INSERT 가 실패한다. jOOQ 라 더티체킹 flush 비용이 없어 제거 비용은 없다.
     *
     * @return [BoardPlacementResult] — 배치 결과 + truncated + unplacedCount + quickFilters + activeSprint.
     * @throws ResponseStatusException 404 — 보드 미존재 또는 soft-deleted.
     */
    @Transactional
    fun getBoard(
        boardId: UUID,
        viewerUserId: UUID,
        filter: BoardCardFilter = BoardCardFilter.EMPTY,
    ): BoardPlacementResult {
        val board =
            healColumnsIfEmpty(
                boardRepository.findById(boardId)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "보드를 찾을 수 없습니다: boardId=$boardId"),
            )

        val isScrum = board.boardType == BoardType.SCRUM

        // ★ 칸반은 스프린트를 조회하지 않는다(NFR-1). 스크럼만 이 두 줄을 탄다.
        val activeSprint = if (isScrum) sprintRepository.findActiveByBoard(boardId) else null
        val sprintIssueKeys = activeSprint?.let { sprintRepository.findIssueKeys(it.id) }.orEmpty()

        val page = fetchBoardIssues(board, viewerUserId, filter, isScrum, sprintIssueKeys)

        val issues =
            if (isScrum) {
                // 🛑 **이 사후 필터를 지우지 마라.** 포트 계약([BoardIssueLookupPort] 3-인자 KDoc ·
                //    CONCERN-1)이 「default 구현은 filter 를 **무시하고** 2-인자로 위임한다」를 명시적으로
                //    허용한다. 즉 filter 는 `truncated` 정확성을 위한 **최적화**이고, 카드 정확성은
                //    계약상 소비측 책임이다. 지우면 filter 를 드롭하는 구현(포트 default, 또는 override 를
                //    빠뜨린 미래 adapter)에서 스크럼 보드가 **다른 스프린트·백로그 이슈까지 그린다** —
                //    이 PR 이 고치는 결함보다 나쁜 회귀다. 죽은 코드가 아니라 계약이 요구하는 안전망이다.
                val keySet = sprintIssueKeys.toSet()
                page.issues.filter { it.key in keySet }
            } else {
                page.issues
            }

        val placed = BoardCardPlacement.placeCards(board.columns, issues)
        val quickFilters = boardQuickFilterRepository.findByBoardId(boardId)
        return BoardPlacementResult(
            columns = placed.columns,
            truncated = page.truncated,
            unplacedCount = placed.unplacedCount,
            quickFilters = quickFilters,
            activeSprint = activeSprint,
        )
    }

    /**
     * 보드에 그릴 이슈를 포트에서 가져온다 (FR-BD-04 PR ④).
     *
     * ### 스프린트 술어를 LIMIT **앞**으로 미는 자리
     *
     * 포트 구현은 `created_at DESC` 로 `BOARD_CARD_FETCH_LIMIT + 1` 건을 자른다. 스프린트를
     * 조회 **뒤** Kotlin 에서만 거르면 활성 스프린트 이슈가 오래됐을 때 그 창 밖으로 밀려
     * **경고 없이 증발한다** — 사용자에게는 정상 시작한 스프린트가 빈 보드다.
     * [BoardCardFilter.issueKeys] 로 실어 보내 SQL 이 자르기 전에 거르게 한다.
     *
     * `filter.copy` 인 것이 중요하다. 새 VO 를 만들면 호출자가 건 담당자·라벨·퀵필터가 조용히
     * 사라진다 — 스프린트 술어는 그것들과 **AND** 로 결합돼야 한다.
     *
     * ### 🛑 스프린트 이슈 키가 0건이면 포트를 호출하지 않는다
     *
     * [BoardCardFilter.issueKeys] 의 빈 목록은 「해당 이슈 없음」이 아니라 **「필터 미적용」**이다
     * (다른 필터 필드와 같은 규약). 그대로 넘기면 프로젝트 이슈를 전량 조회하고 `truncated` 까지
     * 참으로 올라와, 빈 스크럼 보드에 「일부가 누락됐다」가 뜬다. 「활성 스프린트 없음」과
     * 「활성 스프린트가 비었음」은 화면상 둘 다 빈 보드이므로 같은 분기로 단락시킨다.
     *
     * 빈 페이지를 지역값으로 돌려 `placeCards` · `quickFilters` 경로는 그대로 태운다 —
     * 빈 보드의 응답 **형태**는 바뀌지 않는다(컬럼은 여전히 나온다).
     *
     * @param board 조회 대상 보드.
     * @param viewerUserId 조회자 UUID. visibility 필터 기준.
     * @param filter 호출자가 건 보드 카드 필터.
     * @param isScrum 스크럼 보드인지. 칸반이면 [sprintIssueKeys] 는 항상 비어 있다.
     * @param sprintIssueKeys 활성 스프린트에 담긴 이슈 키. 스크럼이 아니거나 활성 스프린트가
     *   없거나 비었으면 빈 목록이다.
     * @return 포트가 돌려준 페이지. 위 단락 조건이면 빈 페이지(`truncated = false`).
     */
    private fun fetchBoardIssues(
        board: Board,
        viewerUserId: UUID,
        filter: BoardCardFilter,
        isScrum: Boolean,
        sprintIssueKeys: List<String>,
    ): BoardIssuePage {
        if (isScrum && sprintIssueKeys.isEmpty()) {
            return BoardIssuePage(issues = emptyList(), truncated = false)
        }
        return boardIssueLookupPort.listVisibleIssuesByProject(
            projectKey = board.projectKey,
            viewerUserId = viewerUserId,
            filter = if (isScrum) filter.copy(issueKeys = sprintIssueKeys) else filter,
        )
    }

    /**
     * 컬럼이 0개인 보드를 워크플로우 카탈로그로 채우고 다시 읽는다. 채울 수 없으면 받은 보드를 그대로 준다.
     *
     * [ensureScrumBoard] 와 같은 이유로 `ProjectKey` 규격을 **먼저 확인**한다 — 그 정규식이
     * `sprints.project_key`(VARCHAR(64), 무검증)보다 좁아, 규격 밖 키로 만들어진 보드를 조회할 때
     * [ProjectKey.of] 가 던지면 **읽기만 하던 요청이 500 으로 죽는다**.
     */
    private fun healColumnsIfEmpty(board: Board): Board {
        // 규격 확인이 여기 붙어 있는 이유는 위 KDoc 참조 — 규격 밖 키로 만들어진 보드의 조회를 죽이지 않는다.
        if (board.columns.isNotEmpty() || !ProjectKey.REGEX.matches(board.projectKey)) return board

        val states = workflowStateCatalog.listStates(ProjectKey.of(board.projectKey), null)
        return if (states.isEmpty()) {
            board
        } else {
            boardRepository.seedColumns(board.id, BoardCardPlacement.seedColumns(states))
            log.debug("컬럼 0개 보드 자가 치유 — boardId={}", board.id)
            boardRepository.findById(board.id) ?: board
        }
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
     * E3 no-op 판단 책임은 이 서비스에 없다. 같은 컬럼으로의 이동은 전환 포트([IssueTransitionPort])에
     * 위임하며, 워크플로우 정책(self-transition 허용 여부)을 존중한다.
     * 클라이언트(프론트엔드 D6)가 드래그 원위치를 감지해 요청 자체를 발생시키지 않는 것이 정석이다.
     * 이전에 있던 client-supplied currentStateKey 기반 no-op 분기는 client 신뢰 위험이 있어 제거됐다.
     *
     * @param boardId 이동 대상 보드 UUID.
     * @param issueKey 이동할 이슈 키. 예: `"BTS-1"`. 형식 = `"PROJECT_KEY-NUMBER"`.
     * @param actorUserId 전환 행위자 UUID. 컨트롤러가 SecurityContext 에서 추출해 전달한다
     *   (body/param 으로 받지 않음 — 위조 차단, sec codereview-fix P1).
     * @param toColumnId 이동 대상 컬럼 UUID.
     * @param expectedVersion 낙관적 락(OCC) 기대 버전.
     * @param resolutionId DONE 카테고리 전환 시 필요한 해결 방안 ID. 불필요하면 null.
     * @return 전환 결과 VO.
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
                // Task 5 가 이 줄을 toStateKey 수용으로 바꾼다(R6·R7). 지금은 무회귀 유지 —
                // 컬럼의 첫 상태가 곧 오늘의 유일한 상태다.
                toStateKey = targetColumn.legacyStateKey
                    ?: throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "AGILE_COLUMN_HAS_NO_STATE: 상태가 매핑되지 않은 컬럼으로는 이동할 수 없습니다.",
                    ),
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
     * 보드의 이름과 스윔레인 기준 필드를 **한 트랜잭션**에서 부분 갱신한다.
     *
     * [name] / [swimlaneField] 는 각각 null 이면 「미전송」이라 건드리지 않는다. 두 필드가 함께 오면
     * 한 번의 호출 = 한 트랜잭션이므로 뒤쪽 갱신이 실패해도 앞선 이름 갱신이 남지 않는다. 컨트롤러가
     * 두 서비스 메서드를 순차 호출하던 이전 구조는 각 메서드가 자기 트랜잭션을 열어, 응답은 400 인데
     * `boards.name` 만 새 값으로 커밋된 상태를 남겼다(리뷰 지적 1).
     *
     * 검증은 모든 쓰기보다 앞선다. 기존 보드를 [Board.copy] 로 재구성해 이름 불변 계약의 판정을
     * 도메인에 맡기고([BoardNameInvalidException]), 스윔레인 문자열도 쓰기 전에 enum 으로 파싱한다 —
     * 서비스가 같은 검사를 복제하면 도메인 init 이 dead code 가 된다.
     *
     * 권한 판정은 이 서비스가 하지 않는다 — 컨트롤러의 `loadBoardWithCreate` 가
     * actor 추출(401) → 메타 조회(404) → CREATE(403) 순서를 담당한다.
     *
     * @param boardId 갱신할 보드 UUID.
     * @param name 새 보드 이름. null 이면 이름을 건드리지 않는다. 공백만으로 이루어질 수 없다.
     * @param swimlaneField 새 스윔레인 기준 필드 이름. null 이면 건드리지 않는다. 예: `"ASSIGNEE"`.
     * @return 갱신된 보드. 두 필드를 함께 갱신하면 마지막 갱신 결과가 두 변경을 모두 반영한다.
     * @throws BoardNameInvalidException 400 — [name] 이 공백뿐일 때(도메인 불변 계약 위반).
     * @throws ResponseStatusException 400 — 알 수 없는 [swimlaneField] 값.
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     */
    @Transactional
    fun updateBoard(
        boardId: UUID,
        name: String?,
        swimlaneField: String?,
    ): Board {
        log.debug("보드 부분 갱신 — boardId={}, name={}, swimlaneField={}", boardId, name, swimlaneField)

        val existing = boardOrNotFound(boardRepository.findById(boardId))
        // 도메인 정규화 — copy 가 init 불변식을 다시 돌린다(PATCH 의 애그리게이트 우회 차단).
        val desired =
            existing.copy(
                name = name ?: existing.name,
                swimlaneField = swimlaneField?.let(::parseSwimlaneField) ?: existing.swimlaneField,
            )

        val renamed =
            if (name == null) {
                existing
            } else {
                boardOrNotFound(boardRepository.updateName(boardId, desired.name))
            }
        return if (swimlaneField == null) {
            renamed
        } else {
            boardOrNotFound(boardRepository.updateSwimlaneField(boardId, desired.swimlaneField))
        }
    }

    /**
     * boards repository 의 조회/갱신 결과 null 을 404 로 승격한다.
     *
     * boards 의 모든 읽기·쓰기가 `deleted_at IS NULL` 을 걸므로 null 은 「없음 또는 이미 삭제됨」이다.
     * 조회와 갱신 사이에 다른 트랜잭션이 soft-delete 한 경우(TOCTOU)도 같은 경로로 들어온다.
     *
     * @param board repository 결과. null 이면 보드가 없거나 이미 삭제됐다.
     * @return null 이 아닌 보드.
     * @throws BoardNotFoundException 404 — [board] 가 null 일 때.
     */
    private fun boardOrNotFound(board: Board?): Board = board ?: throw BoardNotFoundException()

    /**
     * 스윔레인 기준 필드 문자열을 [SwimlaneField] enum 으로 파싱한다.
     *
     * 쓰기보다 먼저 호출돼야 한다 — 파싱 실패가 첫 쓰기 뒤에 나면 롤백에만 의존하게 된다.
     *
     * @param raw 스윔레인 기준 필드 이름 문자열. 예: `"NONE"`, `"ASSIGNEE"`, `"PRIORITY"`.
     * @return 대응하는 [SwimlaneField] 값.
     * @throws ResponseStatusException 400 — 알 수 없는 값.
     */
    private fun parseSwimlaneField(raw: String): SwimlaneField =
        SwimlaneField.entries.firstOrNull { it.name == raw }
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "AGILE_VALIDATION_FAILED: 알 수 없는 swimlaneField 값입니다: $raw",
            )

    /**
     * 보드를 소프트 삭제한다 — boards.deleted_at 만 채우고 이슈는 남긴다.
     *
     * [BoardRepository.softDelete] 가 false 를 반환하면 보드가 없거나 이미 삭제된 상태이므로 404 로
     * 승격한다. 존재 판정과 삭제가 한 UPDATE 안에서 끝나므로 선행 조회를 두지 않는다.
     *
     * 권한 판정은 [updateBoard] 와 마찬가지로 컨트롤러 책임이다.
     *
     * @param boardId 소프트 삭제할 보드 UUID.
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 이미 soft-deleted.
     */
    @Transactional
    fun softDelete(boardId: UUID) {
        log.debug("보드 소프트 삭제 — boardId={}", boardId)
        if (!boardRepository.softDelete(boardId)) {
            throw BoardNotFoundException()
        }
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
