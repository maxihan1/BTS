// 스프린트 CRUD + 이슈 할당 애플리케이션 서비스 — agile-planning BC (FR-BL-02)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.openapitools.jackson.nullable.JsonNullable
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * 스프린트 CRUD 및 이슈 할당 위임 애플리케이션 서비스.
 *
 * cross-BC 통신은 shared-kernel 포트(IssuePermissionResolver, BoardIssueLookupPort)만 사용한다.
 * issue-tracking / identity-access 내부를 직접 import 하지 않는다.
 *
 * ## 권한 판정 순서
 * 1. actor 추출(컨트롤러 책임)
 * 2. sprint 메타 조회로 projectKey 확보 (404 — 미존재)
 * 3. IssuePermissionResolver 권한 판정 (403 — 미충족)
 * 4. 동작 수행
 *
 * create 만 리소스가 아직 없으므로 body projectKey 로 scope 를 구성한다. 그래서 create 는 body 가
 * 지정한 boardId 의 소속을 [resolveTargetBoard] 로 별도 검증한다 — 권한이 본 값과 보드가 다른 값이면
 * 타 프로젝트 보드에 스프린트가 붙는다.
 *
 * ## no-bump 규칙
 * assignIssue / unassignIssue 는 sprint_issues 만 수정한다. sprints.version 은 no-bump.
 *
 * @param permissionResolver cross-BC 권한 판정 포트 (fail-closed, non-null 주입)
 * @param sprintRepository sprints / sprint_issues jOOQ repository
 * @param boardIssueLookupPort 이슈 단건 가시성 확인 포트 (issue-tracking 구현). default=fail-closed false.
 * @param boardRepository boards jOOQ repository — 요청이 지정한 보드의 소속 검증에만 쓴다
 *   (읽기 경로 [BacklogApplicationService] 가 같은 목적으로 같은 repository 를 주입받는다).
 *
 * ### detekt 억제 사유
 * TooManyFunctions — Sprint aggregate 유스케이스(CRUD + 상태전환 + 이슈 할당/해제)를
 * 단일 Application Service 에 응집한다. 분리 시 유스케이스 경계가 흩어져 권한 판정 순서를
 * 각 서비스에서 중복 관리해야 하는 과분할이 된다.
 * LongParameterList — create/update 메서드는 Sprint 도메인 필드를 그대로 받는다.
 * Command DTO 로 감싸면 컨트롤러 레이어와 결합이 생기므로 억제를 선택한다.
 */
@Suppress("TooManyFunctions", "LongParameterList")
@Service
@Transactional
class SprintApplicationService(
    private val permissionResolver: IssuePermissionResolver,
    private val sprintRepository: SprintRepository,
    private val boardIssueLookupPort: BoardIssueLookupPort,
    private val boardApplicationService: BoardApplicationService,
    private val boardRepository: BoardRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** assignIssue repo 반환값: COMPLETED 가드에 의해 INSERT 가 실행되지 않았음을 나타내는 상수. */
        private const val ASSIGN_BLOCKED_BY_COMPLETED = 0
    }

    // ── create ────────────────────────────────────────────────────────────────

    /**
     * 스프린트를 생성하고 영속한다.
     *
     * 생성 시에는 리소스(sprint)가 아직 없으므로 body 의 projectKey 로 권한 scope 를 구성한다.
     * 다른 모든 메서드는 sprint 를 먼저 조회해 projectKey 를 확보한다.
     *
     * 권한을 projectKey 로만 판정하므로 **body 가 지정한 보드가 그 프로젝트 것인지**를 따로 확인해야
     * 한다 — 그 판단은 [resolveTargetBoard] 가 갖는다(이유는 해당 KDoc).
     *
     * @param actorId 행위자 UUID.
     * @param projectKey 스프린트를 생성할 프로젝트 키. 권한 scope 이자 보드 소속의 기준이다.
     * @param name 스프린트 이름.
     * @param goal 스프린트 목표. null 허용.
     * @param startDate 시작일. null 이면 미지정.
     * @param endDate 종료일. null 이면 미지정.
     * @param boardId 스프린트를 붙일 보드 UUID. **HTTP 입력이다**(`CreateSprintRequest.boardId`).
     *   null 이면 [projectKey] 의 스크럼 보드로 폴백한다(없으면 만든다).
     * @return 생성된 스프린트 도메인 객체.
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
     * @throws ResponseStatusException 404 — [boardId] 가 [projectKey] 의 활성 보드가 아님.
     */
    @Transactional
    fun create(
        actorId: UUID,
        projectKey: String,
        name: String,
        goal: String?,
        startDate: LocalDate?,
        endDate: LocalDate?,
        boardId: UUID? = null,
    ): Sprint {
        log.debug("스프린트 생성 시작 — projectKey={}, name={}", projectKey, name)
        requirePermission(actorId, IssuePermission.CREATE, IssueScope.Project(projectKey))

        val targetBoardId = resolveTargetBoard(projectKey, boardId)

        val sprint =
            Sprint(
                id = UUID.randomUUID(),
                projectKey = projectKey,
                boardId = targetBoardId,
                name = name,
                goal = goal,
                status = SprintStatus.PLANNED,
                startDate = startDate,
                endDate = endDate,
                version = 0L,
            )
        val saved = sprintRepository.insert(sprint)
        log.debug("스프린트 생성 완료 — sprintId={}", saved.id)
        return saved
    }

    // ── update ────────────────────────────────────────────────────────────────

    /**
     * 스프린트 메타 정보(이름·목표·기간)를 부분 갱신한다 — partial update (3-state).
     *
     * 각 필드의 [JsonNullable] presence 로 변경 의도를 구분한다.
     * - absent(undefined, 미전송): 기존 값 유지.
     * - present null: 해당 값을 null 로 클리어.
     * - present 값: 해당 값으로 설정.
     *
     * sprint 조회로 기존 값을 확보한 뒤 각 필드를 머지하고 도메인 불변 계약(name 비공백, 기간 비역전)을
     * Sprint 생성자(init require)로 검증한다. 위반 시 [IllegalArgumentException] 이 발생하며
     * [SprintExceptionHandler] 가 400 으로 변환한다.
     *
     * @param actorId 행위자 UUID.
     * @param sprintId 갱신할 스프린트 UUID.
     * @param name 새 이름. absent 이면 기존 이름 유지, present 이면 non-blank 강제(도메인 init).
     * @param goal 새 목표. absent=무변경, present null=클리어, present 값=설정.
     * @param startDate 시작일. absent=무변경, present null=해제, present 값=설정.
     * @param endDate 종료일. absent=무변경, present null=해제, present 값=설정.
     * @param version 낙관적 잠금 버전.
     * @return 갱신된 스프린트.
     * @throws SprintNotFoundException 404 — 스프린트 미존재 또는 soft-deleted.
     * @throws SprintDateLockedException 400 — COMPLETED 스프린트에 기간을 실어 보냄.
     * @throws SprintVersionConflictException 409 — OCC 버전 충돌(스프린트 존재하나 version 불일치).
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
     */
    @Transactional
    fun update(
        actorId: UUID,
        sprintId: UUID,
        name: JsonNullable<String>,
        goal: JsonNullable<String?>,
        startDate: JsonNullable<LocalDate?>,
        endDate: JsonNullable<LocalDate?>,
        version: Long,
    ): Sprint {
        val existing = loadSprintWithPermission(actorId, sprintId, IssuePermission.CREATE)
        requireDatesEditable(existing, startDate, endDate)

        val mergedName = if (name.isPresent) name.get() else existing.name
        val mergedGoal = if (goal.isPresent) goal.get() else existing.goal
        val mergedStartDate = if (startDate.isPresent) startDate.get() else existing.startDate
        val mergedEndDate = if (endDate.isPresent) endDate.get() else existing.endDate

        // 도메인 불변 계약 검증 (name 비공백, startDate <= endDate). 위반 시 IllegalArgumentException.
        Sprint(
            id = existing.id,
            projectKey = existing.projectKey,
            boardId = existing.boardId,
            name = mergedName,
            goal = mergedGoal,
            status = existing.status,
            startDate = mergedStartDate,
            endDate = mergedEndDate,
            version = existing.version,
        )

        return sprintRepository.updateMeta(
            id = sprintId,
            name = mergedName,
            goal = mergedGoal,
            startDate = mergedStartDate,
            endDate = mergedEndDate,
            version = version,
        ) ?: resolveOccNull(sprintId, existing)
    }

    // ── softDelete ────────────────────────────────────────────────────────────

    /**
     * 스프린트를 소프트 삭제한다.
     *
     * sprint 조회로 projectKey 를 확보한 뒤 CREATE 권한을 판정한다.
     *
     * @param actorId 행위자 UUID.
     * @param sprintId 소프트 삭제할 스프린트 UUID.
     * @throws SprintNotFoundException 404 — 스프린트 미존재 또는 soft-deleted.
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
     */
    @Transactional
    fun softDelete(
        actorId: UUID,
        sprintId: UUID,
    ) {
        loadSprintWithPermission(actorId, sprintId, IssuePermission.CREATE)
        log.debug("스프린트 소프트 삭제 — sprintId={}", sprintId)
        sprintRepository.softDelete(sprintId)
    }

    // ── list ──────────────────────────────────────────────────────────────────

    /**
     * 프로젝트별 스프린트 목록을 반환한다.
     *
     * BROWSE 권한을 먼저 판정한다. projectKey 는 요청 파라미터를 사용한다(목록 조회라 리소스 부재).
     *
     * @param actorId 행위자 UUID.
     * @param projectKey 조회할 프로젝트 키.
     * @param statusFilter 상태 필터. null 이면 전체 상태.
     * @return 스프린트 목록.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족.
     */
    @Transactional(readOnly = true)
    fun list(
        actorId: UUID,
        projectKey: String,
        statusFilter: SprintStatus?,
    ): List<Sprint> {
        requirePermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        return sprintRepository.findByProject(projectKey, statusFilter)
    }

    // ── get ───────────────────────────────────────────────────────────────────

    /**
     * 스프린트 단건을 조회한다.
     *
     * sprint 조회로 projectKey 를 확보한 뒤 BROWSE 권한을 판정한다.
     *
     * @param actorId 행위자 UUID.
     * @param sprintId 조회할 스프린트 UUID.
     * @return 스프린트 도메인 객체.
     * @throws SprintNotFoundException 404 — 스프린트 미존재 또는 soft-deleted.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족.
     */
    @Transactional(readOnly = true)
    fun get(
        actorId: UUID,
        sprintId: UUID,
    ): Sprint = loadSprintWithPermission(actorId, sprintId, IssuePermission.BROWSE)

    // ── start ─────────────────────────────────────────────────────────────────

    /**
     * 스프린트를 시작(PLANNED -> ACTIVE)한다.
     *
     * 도메인 Sprint.start() 에 전환 유효성을 위임한 뒤, **보드당 활성 스프린트 1개**를 강제한다.
     *
     * ### 판정 순서 — FSM 이 보드 가드보다 앞이다
     * ACTIVE 스프린트를 다시 start 하면 [findActiveByBoard] 가 **자기 자신**을 찾는다. 가드를
     * [Sprint.start] 앞에 두면 「전환 불가」가 「이미 활성이 있다」로 뒤바뀌어 원인이 흐려진다.
     * 그래서 FSM 을 먼저 통과시킨다 — 여기 도달한 스프린트는 PLANNED 였음이 보장된다.
     *
     * ### 선행 결정 무효화 (2026-09-01)
     * `docs/plan/product/agile-planning.md §3.2` 의 Deviation(PR #182) ⑤ 「동시 ACTIVE 다중 허용」을
     * 뒤집는다. Jira Cloud 기본이 보드당 1개다(`docs/adr/2026-09-01-board-type-and-active-sprint.md` D5).
     * **기존 다중 활성 행은 깨지 않는다** — 가드는 이 시점에만 걸고 V506 인덱스도 UNIQUE 가 아니다.
     *
     * @param actorId 행위자 UUID.
     * @param sprintId 시작할 스프린트 UUID.
     * @return ACTIVE 상태의 갱신된 스프린트.
     * @throws SprintNotFoundException 404 — 스프린트 미존재 또는 soft-deleted.
     * @throws SprintAlreadyActiveException 409 — 같은 보드에 이미 ACTIVE 스프린트가 있음.
     * @throws SprintVersionConflictException 409 — OCC 버전 충돌.
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
     * @throws com.bts.agileplanning.domain.InvalidSprintTransitionException 409 — 허용되지 않는 전환.
     */
    @Transactional
    fun start(
        actorId: UUID,
        sprintId: UUID,
    ): Sprint {
        val sprint = loadSprintWithPermission(actorId, sprintId, IssuePermission.CREATE)
        val started = sprint.start()
        if (sprintRepository.findActiveByBoard(sprint.boardId) != null) {
            throw SprintAlreadyActiveException()
        }
        return sprintRepository.updateStatus(sprintId, started.status, sprint.version)
            ?: resolveOccNull(sprintId, sprint)
    }

    // ── complete ──────────────────────────────────────────────────────────────

    /**
     * 스프린트를 완료(ACTIVE -> COMPLETED)한다.
     *
     * 도메인 Sprint.complete() 에 전환 유효성을 위임한다.
     *
     * @param actorId 행위자 UUID.
     * @param sprintId 완료할 스프린트 UUID.
     * @return COMPLETED 상태의 갱신된 스프린트.
     * @throws SprintNotFoundException 404 — 스프린트 미존재 또는 soft-deleted.
     * @throws SprintVersionConflictException 409 — OCC 버전 충돌.
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
     * @throws com.bts.agileplanning.domain.InvalidSprintTransitionException 409 — 허용되지 않는 전환.
     */
    @Transactional
    fun complete(
        actorId: UUID,
        sprintId: UUID,
    ): Sprint {
        val sprint = loadSprintWithPermission(actorId, sprintId, IssuePermission.CREATE)
        val completed = sprint.complete()
        return sprintRepository.updateStatus(sprintId, completed.status, sprint.version)
            ?: resolveOccNull(sprintId, sprint)
    }

    // ── assignIssue ───────────────────────────────────────────────────────────

    /**
     * 이슈를 스프린트에 할당한다.
     *
     * 권한 판정 순서.
     * 1. sprint 조회로 projectKey 확보 (404).
     * 2. UPDATE 권한 판정 (403).
     * 3. BoardIssueLookupPort.isVisibleIssue 단건 가시성 검증 (false -> 404, probe 차단).
     * 4. repo.assignIssue 호출. affected=0 이면 COMPLETED 가드에 걸린 것이므로 409 (E5).
     *
     * @param actorId 행위자 UUID.
     * @param sprintId 이슈를 할당할 스프린트 UUID.
     * @param issueKey 할당할 이슈 키.
     * @throws SprintNotFoundException 404 — 스프린트 미존재 또는 soft-deleted.
     * @throws ResponseStatusException 403 — UPDATE 권한 미충족.
     * @throws ResponseStatusException 404 — 이슈가 가시적이지 않음 (probe 차단).
     * @throws SprintCompletedAssignException 409 — 스프린트가 COMPLETED 상태 (E5).
     * @throws SprintIssueConflictException 409 — 동시 할당 UNIQUE(issue_key) 제약 위반.
     */
    @Transactional
    fun assignIssue(
        actorId: UUID,
        sprintId: UUID,
        issueKey: String,
    ) {
        val sprint = loadSprintWithPermission(actorId, sprintId, IssuePermission.UPDATE)

        val visible = boardIssueLookupPort.isVisibleIssue(sprint.projectKey, issueKey, actorId)
        if (!visible) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "이슈를 찾을 수 없습니다.")
        }

        val affected = tryAssignIssue(sprintId, issueKey)
        if (affected == ASSIGN_BLOCKED_BY_COMPLETED) {
            throw SprintCompletedAssignException()
        }
        log.debug("이슈 스프린트 할당 완료 — sprintId={}, issueKey={}", sprintId, issueKey)
    }

    // ── unassignIssue ─────────────────────────────────────────────────────────

    /**
     * 이슈를 스프린트에서 제거한다.
     *
     * repo.unassignIssue 는 멱등이며 COMPLETED 가드를 자체 처리한다.
     *
     * @param actorId 행위자 UUID.
     * @param sprintId 이슈를 제거할 스프린트 UUID.
     * @param issueKey 제거할 이슈 키.
     * @throws SprintNotFoundException 404 — 스프린트 미존재 또는 soft-deleted.
     * @throws ResponseStatusException 403 — UPDATE 권한 미충족.
     */
    @Transactional
    fun unassignIssue(
        actorId: UUID,
        sprintId: UUID,
        issueKey: String,
    ) {
        loadSprintWithPermission(actorId, sprintId, IssuePermission.UPDATE)
        log.debug("이슈 스프린트 제거 — sprintId={}, issueKey={}", sprintId, issueKey)
        sprintRepository.unassignIssue(sprintId, issueKey)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 스프린트를 붙일 보드를 정한다 — 지정 보드의 **소속 검증**이 이 함수의 존재 이유다.
     *
     * ### 왜 검증하는가
     * [boardId] 는 내부 인자가 아니라 **HTTP 입력**이다(`CreateSprintRequest.boardId`). 그런데 권한은
     * body 의 [projectKey] 로만 판정된다([create] 는 리소스가 아직 없어 그럴 수밖에 없다). 두 값이
     * 서로를 검사하지 않으면, 프로젝트 A 에 CREATE 만 가진 행위자가 프로젝트 B 의 보드 UUID
     * (`?board=<uuid>` 로 URL 에 노출된다)를 실어 **B 의 보드에 스프린트를 매달 수 있다** —
     * B 의 보드 헤더가 남의 스프린트 이름으로 덮이고, B 의 스크럼 카드가 사라지며,
     * `findActiveByBoard` 가 그 스프린트를 집어 **B 가 자기 스프린트를 영구히 시작 못 한다**.
     *
     * `sprints.board_id` FK 는 `boards(id)` 만 걸려 있어(V506) **프로젝트 일치를 DB 가 막지 못한다.**
     * `(project_key, board_id)` 불변식(DATA.md §1.2)은 여기서만 지켜진다.
     *
     * ### 읽기 경로와 같은 규약
     * [BacklogApplicationService.resolveBoardScope] 와 술어·상태 코드를 맞춘다. 두 경로가 다른 규약을
     * 쓰면 그 차이가 다음 결함이 된다.
     * - 술어는 같다 — `deleted_at IS NULL`([BoardRepository.findById] 가 건다) + `project_key` 일치.
     *   조회 방식만 다르다(쓰기 경로는 지정 보드 1건만 필요해 단건 조회를 쓴다).
     *
     * ### 🛑 단, 종류 술어는 **쓰기 경로에만** 있다 (비대칭 · FR-BD-04 PR ⑤ · Maxi 확정 2026-09-02)
     *
     * 여기는 `boardType == SCRUM` 을 요구하지만 [BacklogApplicationService.resolveBoardScope] 는
     * 요구하지 않는다. **이 비대칭은 의도적이다** — 안 적으면 위 「같은 규약」 문단이 거짓이 된다.
     *
     * - **왜 막나.** 칸반 보드에 매단 스프린트는 [BoardApplicationService.getBoard] 가 SCRUM 일 때만
     *   활성 스프린트를 조회하므로 **어느 보드 화면에도 영원히 안 나타난다.** 사용자에게는
     *   「시작했는데 아무 일도 안 일어남」이다. 오늘 이것을 가리는 것은 백로그 스위처가 스크럼만
     *   노출하는 것 하나뿐이라, 프론트 필터가 유일한 방어선이었다.
     * - **왜 읽기 경로는 안 막나.** 기존 데이터가 이미 칸반 보드 소속 스프린트를 갖는다
     *   (`apps/web/src/mocks/board-handlers.test.ts` 의 회귀 가드가 「보드 E2E 전량이 그 시드를 쓴다」를
     *   명시한다). 읽기까지 막으면 그 데이터가 통째로 404 가 된다. `V506__sprint_board_id.sql` 이
     *   선재 다중 ACTIVE 행을 보존한 것과 같은 판단이다 — **새로 만드는 것만 막고 있는 것은 둔다.**
     * - 상태 코드는 **404** 다(스펙 E8). 403 이면 「그 UUID 는 존재한다」가 새어 나간다
     *   (memory `permission-assert-before-existence-makes-403-lie`).
     * - 지정이 틀렸을 때 기본 보드로 **조용히 대체하지 않는다**(편차 E7). 사용자가 의도한 것과
     *   다른 보드에 스프린트가 생기고 그 사실을 모르게 된다.
     *
     * 예외 타입은 [ResponseStatusException] 이다. `BoardNotFoundException` 은
     * [com.bts.agileplanning.web.BoardExceptionHandler] 가 `assignableTypes` 로
     * `BoardController` 계열에만 걸려 있어, `SprintController` 요청에서 던지면 catch-all 이 500 으로 바꾼다.
     *
     * @param projectKey 권한을 판정한 프로젝트 키. 보드 소속의 기준이다.
     * @param boardId 요청이 지정한 보드 UUID. null 이면 스크럼 보드로 폴백한다(없으면 만든다).
     * @return 스프린트를 붙일 보드 UUID.
     * @throws ResponseStatusException 404 — 지정 보드가 [projectKey] 의 활성 보드가 아닐 때
     *   (미존재 · 소프트 삭제 · 타 프로젝트 소속을 구분하지 않는다).
     */
    private fun resolveTargetBoard(
        projectKey: String,
        boardId: UUID?,
    ): UUID {
        // 보드를 안 준 요청은 그 프로젝트의 스크럼 보드에 붙인다(없으면 만든다).
        if (boardId == null) return boardApplicationService.ensureScrumBoard(projectKey)
        return boardRepository
            .findById(boardId)
            // 종류 술어는 쓰기 경로에만 있다 — 위 KDoc 「비대칭」 절이 사유를 적는다.
            ?.takeIf { it.projectKey == projectKey && it.boardType == BoardType.SCRUM }
            ?.id
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "보드를 찾을 수 없습니다.")
    }

    /**
     * COMPLETED 스프린트의 기간 변경을 거부한다 (FR-BL-02 FR-2).
     *
     * ### 근거 — Jira Cloud 원문 (계획 문서 J1)
     * *"You can change its name, goal, and start and end dates. **You can only edit the name and goal
     * for a complete sprint.**"*
     * (2026-09-01 조회 · Cloud company-managed ·
     * `support.atlassian.com/jira-software-cloud/docs/edit-a-sprint-in-a-company-managed-project/`)
     *
     * 프론트의 입력 비활성만으로 흉내내지 않고 여기서 거부한다 — 화면을 거치지 않는 PATCH 가 남는다.
     *
     * ### 값이 아니라 presence 로 판정하는 이유
     * [JsonNullable] 3-상태에서 **absent 는 「그 필드를 안 건드린다」**이므로 통과시킨다. 그래야 완료
     * 스프린트의 이름·목표 편집이 살아 있다. present 는 값이든 explicit null 이든 「기간을 바꾸겠다」는
     * 의사 표시라 거부한다. 반대로 값 비교(기존과 같으면 통과)로 완화하면 클라이언트가 현재 값을 그대로
     * 실어 보내는 것만으로 잠금을 통과해 규칙이 사실상 사라진다.
     *
     * @param existing 조회된 기존 스프린트. 상태 판정의 기준이다.
     * @param startDate 요청의 시작일 필드. presence 만 본다.
     * @param endDate 요청의 종료일 필드. presence 만 본다.
     * @throws SprintDateLockedException 400 — COMPLETED 스프린트에 기간이 present 로 실려 옴.
     */
    private fun requireDatesEditable(
        existing: Sprint,
        startDate: JsonNullable<LocalDate?>,
        endDate: JsonNullable<LocalDate?>,
    ) {
        if (existing.status != SprintStatus.COMPLETED) return
        if (!startDate.isPresent && !endDate.isPresent) return
        log.debug("완료 스프린트의 기간 변경 거부 — sprintId={}", existing.id)
        throw SprintDateLockedException()
    }

    /**
     * [SprintRepository.assignIssue] 를 호출하고 UNIQUE(issue_key) 제약 위반을 [SprintIssueConflictException] 으로 변환한다.
     *
     * jOOQ 는 Spring PersistenceExceptionTranslator 가 개입하지 않을 경우
     * [DataIntegrityViolationException] 대신 [org.jooq.exception.IntegrityConstraintViolationException]
     * 을 직접 던진다. 두 케이스를 모두 처리한다.
     * (메모리 jooq-exception-translator-409-dependency — UNIQUE 409 변환기 의존)
     *
     * SwallowedException — catch 목적이 409 도메인 예외 변환이므로 원 예외를 재던지지 않는 것이 의도된 설계.
     * ThrowsCount — UNIQUE 위반 두 경로(Spring/jOOQ)를 명시 catch 해 두 번 throw 하는 것이 의도된 설계.
     *
     * @param sprintId 이슈를 할당할 스프린트 UUID.
     * @param issueKey 할당할 이슈 키.
     * @return INSERT 된 행수. 0 이면 COMPLETED 가드.
     * @throws SprintIssueConflictException 409 — UNIQUE(issue_key) 제약 위반.
     */
    @Suppress("SwallowedException", "ThrowsCount")
    private fun tryAssignIssue(
        sprintId: UUID,
        issueKey: String,
    ): Int =
        try {
            sprintRepository.assignIssue(sprintId, issueKey)
        } catch (ex: DataIntegrityViolationException) {
            // Spring PersistenceExceptionTranslator 가 개입한 경우
            log.warn("이슈 할당 UNIQUE 제약 위반(Spring) — sprintId={}", sprintId)
            throw SprintIssueConflictException()
        } catch (ex: org.jooq.exception.IntegrityConstraintViolationException) {
            // translator 미개입 시 jOOQ 가 직접 던지는 제약 위반
            log.warn("이슈 할당 UNIQUE 제약 위반(jOOQ) — sprintId={}", sprintId)
            throw SprintIssueConflictException()
        }

    /**
     * updateMeta / updateStatus 가 null 을 반환했을 때 존재 재확인 후 404 또는 409 를 결정한다.
     *
     * repo 의 WHERE version=:version 조건 때문에 affected=0 은 두 가지 원인이 가능하다.
     * 1. 스프린트 자체가 삭제됨 또는 미존재 → 404.
     * 2. 스프린트는 존재하나 client 버전이 오래됨(OCC 충돌) → 409.
     *
     * lock 후 재조회로 TOCTOU 없이 원인을 판별한다.
     * (메모리 advisory-lock-bigint-toctou — lock 획득 후 재조회 패턴 적용)
     *
     * @param sprintId 재확인할 스프린트 UUID.
     * @param loadedSprint updateMeta/updateStatus 전에 이미 조회한 스프린트.
     *   version 불일치 판단의 기준은 loadedSprint.version 이 아니라 존재 여부다.
     * @throws SprintNotFoundException 404 — 스프린트가 실제로 없거나 soft-deleted 됐을 때.
     * @throws SprintVersionConflictException 409 — 스프린트는 존재하나 version 이 달라진 경우.
     */
    private fun resolveOccNull(
        sprintId: UUID,
        @Suppress("UNUSED_PARAMETER") loadedSprint: Sprint,
    ): Nothing {
        val current = sprintRepository.findById(sprintId)
        if (current == null) {
            throw SprintNotFoundException()
        } else {
            throw SprintVersionConflictException()
        }
    }

    /**
     * sprint 조회(404) -> 권한 판정(403) 순서를 한 곳에 응집한다.
     *
     * 순서 오류(존재 probe, 권한 누락) 회귀를 차단한다.
     * create 는 리소스 부재 상황이라 이 헬퍼를 사용하지 않는다.
     *
     * @param actorId 행위자 UUID.
     * @param sprintId 접근할 스프린트 UUID.
     * @param permission 검증할 권한.
     * @return 조회된 스프린트 도메인 객체.
     * @throws SprintNotFoundException 404 — 스프린트 미존재 또는 soft-deleted.
     * @throws ResponseStatusException 403 — 권한 미충족.
     */
    private fun loadSprintWithPermission(
        actorId: UUID,
        sprintId: UUID,
        permission: IssuePermission,
    ): Sprint {
        val sprint = sprintRepository.findById(sprintId) ?: throw SprintNotFoundException()
        requirePermission(actorId, permission, IssueScope.Project(sprint.projectKey))
        return sprint
    }

    /**
     * 권한을 판정하고 미충족 시 403 을 던진다.
     *
     * fail-closed — permissionResolver 는 non-null 주입이므로 빈 부재 시 부팅이 실패한다.
     * 거부 메시지는 일반화되어 내부 정보를 노출하지 않는다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID.
     * @param permission 검증할 권한.
     * @param scope 권한 적용 범위.
     * @throws ResponseStatusException 403 — 권한 미충족.
     */
    private fun requirePermission(
        actorId: UUID,
        permission: IssuePermission,
        scope: IssueScope,
    ) {
        if (!permissionResolver.hasPermission(actorId, permission, scope)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
        }
    }
}
