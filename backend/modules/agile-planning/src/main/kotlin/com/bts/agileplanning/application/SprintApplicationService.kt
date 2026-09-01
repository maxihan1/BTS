// 스프린트 CRUD + 이슈 할당 애플리케이션 서비스 — agile-planning BC (FR-BL-02)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
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
 * create 만 리소스가 아직 없으므로 body projectKey 로 scope 를 구성한다.
 *
 * ## no-bump 규칙
 * assignIssue / unassignIssue 는 sprint_issues 만 수정한다. sprints.version 은 no-bump.
 *
 * @param permissionResolver cross-BC 권한 판정 포트 (fail-closed, non-null 주입)
 * @param sprintRepository sprints / sprint_issues jOOQ repository
 * @param boardIssueLookupPort 이슈 단건 가시성 확인 포트 (issue-tracking 구현). default=fail-closed false.
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
     * @param actorId 행위자 UUID.
     * @param projectKey 스프린트를 생성할 프로젝트 키.
     * @param name 스프린트 이름.
     * @param goal 스프린트 목표. null 허용.
     * @param startDate 시작일. null 이면 미지정.
     * @param endDate 종료일. null 이면 미지정.
     * @return 생성된 스프린트 도메인 객체.
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
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

        // 보드를 안 준 요청은 그 프로젝트의 스크럼 보드에 붙인다(없으면 만든다).
        // 백로그 화면은 아직 보드를 지정하지 않으므로 이 경로가 기본이다 — PR ③ 에서 명시 지정이 붙는다.
        val targetBoardId = boardId ?: boardApplicationService.ensureScrumBoard(projectKey)

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
