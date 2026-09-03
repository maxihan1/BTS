// sprints / sprint_issues 테이블 jOOQ DSL 접근 — agile-planning BC (FR-BL-02)

package com.bts.agileplanning.repository

import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.jooq.tables.records.SprintsRecord
import com.bts.agileplanning.jooq.tables.references.SPRINTS
import com.bts.agileplanning.jooq.tables.references.SPRINT_ISSUES
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * sprints / sprint_issues 테이블 CRUD 를 담당하는 Repository.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 [Transactional] 을 명시한다 (DATA.md §6).
 * soft-delete 필터: 조회 시 sprints.deleted_at IS NULL 조건 필수.
 *
 * ## no-bump 규칙
 * [assignIssue] / [unassignIssue] 는 sprint_issues 만 수정한다.
 * sprints 행의 version / updated_at 은 건드리지 않는다(no-bump).
 * [updateMeta] / [updateStatus] 는 sprints 행을 직접 수정하므로 version bump.
 *
 * ## delete-then-insert 설계 ([assignIssue])
 * UPSERT 는 사용하지 않는다.
 * 같은 issue_key 가 다른 sprint 에 있을 수 있으므로,
 * 먼저 SPRINT_ISSUES 에서 해당 issue_key 를 전역 DELETE 한 뒤 새 sprint 에 INSERT 한다.
 * 두 DML 은 동일 트랜잭션 내에서 원자적으로 실행된다.
 *
 * ## 조건부 DML — COMPLETED 가드 ([assignIssue] / [unassignIssue])
 * INSERT(또는 DELETE) 시 서브쿼리로 해당 sprint 의 status 가 'COMPLETED' 가 아닌지 확인한다.
 * 조건이 충족되지 않으면 DML 이 실행되지 않아 affected 행수 0 을 반환한다.
 * Service 계층(Task 4/5)이 0 을 받으면 E5/E2 오류 코드를 구분한다.
 *
 * @param dsl jOOQ DSLContext
 *
 * ### detekt 억제 사유
 * TooManyFunctions — sprints / sprint_issues aggregate 전체 영속성이 단일 책임(Repository 계층).
 * insert·findById·findByProject·updateMeta·updateStatus·softDelete·
 * assignIssue·unassignIssue·findIssueKeys·toDomain + 내부 헬퍼(utcNow)로 구성되며
 * 이 이상 분리하면 aggregate 경계를 파괴하는 과분할이 된다.
 * LongParameterList — updateMeta 는 Sprint 도메인 필드를 그대로 받는다.
 * Command DTO 로 감싸면 Repository 계층이 DTO 에 결합되는 계층 역전이 발생한다.
 */
@Suppress("TooManyFunctions", "LongParameterList")
@Repository
class SprintRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** COMPLETED 상태 문자열 상수 — 조건부 DML COMPLETED 가드에서 사용. */
        private const val STATUS_COMPLETED = "COMPLETED"
    }

    /** UTC 현재 시각을 반환하는 내부 헬퍼. 반복 호출 지점을 일원화한다. */
    private fun utcNow(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    // ── insert ────────────────────────────────────────────────────────────────

    /**
     * 스프린트를 삽입하고 DB 타임스탬프가 반영된 도메인 객체를 반환한다.
     *
     * @param sprint 저장할 스프린트 도메인 객체 (id 포함)
     * @return DB 타임스탬프가 반영된 최신 스프린트
     */
    @Transactional
    fun insert(sprint: Sprint): Sprint {
        log.debug("스프린트 삽입 — id={}, projectKey={}", sprint.id, sprint.projectKey)

        val now = utcNow()

        dsl.insertInto(SPRINTS)
            .set(SPRINTS.ID, sprint.id)
            .set(SPRINTS.PROJECT_KEY, sprint.projectKey)
            .set(SPRINTS.BOARD_ID, sprint.boardId)
            .set(SPRINTS.NAME, sprint.name)
            .set(SPRINTS.GOAL, sprint.goal)
            .set(SPRINTS.STATUS, sprint.status.name)
            .set(SPRINTS.START_DATE, sprint.startDate)
            .set(SPRINTS.END_DATE, sprint.endDate)
            .set(SPRINTS.VERSION, sprint.version)
            .set(SPRINTS.CREATED_AT, now)
            .set(SPRINTS.UPDATED_AT, now)
            .execute()

        return findById(sprint.id) ?: error("스프린트 INSERT 후 조회 실패 — id=${sprint.id}")
    }

    // ── acquireSprintStartLock ────────────────────────────────────────────────

    /**
     * 그 보드의 **스프린트 시작**을 직렬화하는 advisory lock 을 잡는다 (FR-BD-04 PR ⑤).
     *
     * ### 왜 필요한가
     * [SprintApplicationService.start] 는 [findActiveByBoard] 로 읽고 [updateStatus] 로 쓴다.
     * 그 사이에 잠금이 없으면 동시 요청 2건이 둘 다 「활성 스프린트 없음」을 보고 **둘 다 시작**한다.
     * 그러면 한 보드에 활성 스프린트가 둘이 되고, 보드 화면이 어느 쪽을 그릴지가 **조회 순서**에 달린다.
     *
     * ### 왜 UNIQUE 인덱스가 아닌가
     * `V506__sprint_board_id.sql` 의 부분 인덱스 `idx_sprints_board_active` 는 **일부러 UNIQUE 가 아니다** —
     * 같은 파일이 선재 다중 ACTIVE 행을 보존한다고 적는다. UNIQUE 로 승격하려면 그 행들을 먼저
     * 정리해야 하고, 정리하지 않으면 마이그레이션 자체가 실패한다. 막아야 하는 것은 「이미 있는 다중」이
     * 아니라 **새로 생기는 경쟁**이다.
     *
     * ### 계약 — 반드시 읽기 **전에** 부른다
     * `pg_advisory_xact_lock` 은 트랜잭션 종료 시 풀린다. 락 **밖에서** 읽은 값으로 판단하면 락이
     * 무력화된다(memory `advisory-lock-bigint-toctou`). 호출자는 같은 트랜잭션 안에서
     * 락 → 재조회 → 쓰기 순서를 지켜야 한다.
     *
     * ★ 그래서 전파가 `MANDATORY` 다. `REQUIRED` 로 두면 트랜잭션 **없이** 불렸을 때 자기 트랜잭션을
     * 열고 즉시 커밋해 **락이 그 자리에서 풀리는데 예외 없이 조용히 성공한다** — 계약 위반이 침묵한다
     * (형제 락 [BoardRepository.acquireProjectScrumBoardLock] 과 같은 판단).
     *
     * 잠금 공간에 `sprint-start:` 접두를 붙여 형제 락(`scrum-board:<projectKey>`)과 공간을 가른다.
     * `hashtextextended(text, int8)` 가 bigint 를 반환해 `pg_advisory_xact_lock(bigint)` 와 정합한다.
     *
     * @param boardId 대상 보드 UUID.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun acquireSprintStartLock(boardId: UUID) {
        dsl.acquireXactLockWithBudget("sprint-start:$boardId")
    }

    // ── findActiveByBoard ─────────────────────────────────────────────────────

    /**
     * 그 보드의 **ACTIVE 스프린트**를 반환한다. 없으면 `null`.
     *
     * Jira Cloud 는 활성 스프린트를 기본 1개로 제한한다
     * (*"If you want to have more than one active sprint at a time, you'll need to enable parallel
     * sprints"* · 2026-09-01 조회). BTS 도 [SprintApplicationService.start] 에서 같은 제약을 건다.
     *
     * ★ 다만 **기존 데이터에는 다중 ACTIVE 가 실재할 수 있다** — PR #182 Deviation ⑤ 가 명시적으로
     * 허용했고 V506 마이그레이션이 그 행을 깨지 않는다. 그래서 이 함수는 `created_at` 오름차순
     * `LIMIT 1` 로 **하나만** 집어 온다. 여러 건일 때 예외를 던지면 선재 데이터가 보드 조회를 통째로
     * 막는다. 정렬을 두는 이유는 여러 건일 때 **어느 것을 집는지가 호출마다 흔들리지 않게** 하기 위함이다.
     *
     * 조건 순서는 부분 인덱스 `idx_sprints_board_active (board_id) WHERE deleted_at IS NULL AND
     * status = 'ACTIVE'` 를 타도록 맞춘다.
     *
     * @param boardId 대상 보드 UUID.
     * @return ACTIVE 스프린트. 없으면 `null`.
     */
    @Transactional(readOnly = true)
    fun findActiveByBoard(boardId: UUID): Sprint? =
        dsl.selectFrom(SPRINTS)
            .where(SPRINTS.BOARD_ID.eq(boardId))
            .and(SPRINTS.DELETED_AT.isNull)
            .and(SPRINTS.STATUS.eq(SprintStatus.ACTIVE.name))
            .orderBy(SPRINTS.CREATED_AT.asc())
            .limit(1)
            .fetchOne()
            ?.let(::toDomain)

    // ── findById ──────────────────────────────────────────────────────────────

    /**
     * 스프린트 단건을 조회한다.
     *
     * soft-deleted 스프린트(deleted_at IS NOT NULL)는 반환하지 않는다.
     *
     * @param id 조회할 스프린트 UUID
     * @return 스프린트 도메인 객체, 없거나 soft-deleted 이면 null
     */
    @Transactional(readOnly = true)
    fun findById(id: UUID): Sprint? {
        return dsl.selectFrom(SPRINTS)
            .where(SPRINTS.ID.eq(id))
            .and(SPRINTS.DELETED_AT.isNull)
            .fetchOne()
            ?.let { toDomain(it) }
    }

    // ── findByProject ─────────────────────────────────────────────────────────

    /**
     * 프로젝트별 활성 스프린트 목록을 반환한다.
     *
     * soft-deleted 스프린트는 제외한다.
     * status 를 지정하면 해당 상태만 필터링한다.
     *
     * @param projectKey 조회할 프로젝트 키
     * @param status 상태 필터. null 이면 전체 상태 조회
     * @return 스프린트 목록
     */
    @Transactional(readOnly = true)
    fun findByProject(
        projectKey: String,
        status: SprintStatus? = null,
    ): List<Sprint> {
        var condition =
            SPRINTS.PROJECT_KEY.eq(projectKey)
                .and(SPRINTS.DELETED_AT.isNull)

        if (status != null) {
            condition = condition.and(SPRINTS.STATUS.eq(status.name))
        }

        return dsl.selectFrom(SPRINTS)
            .where(condition)
            .orderBy(SPRINTS.CREATED_AT.asc())
            .fetch()
            .map { toDomain(it) }
    }

    // ── updateMeta ────────────────────────────────────────────────────────────

    /**
     * 스프린트의 메타 정보(이름·목표·기간)를 갱신하고 version 을 bump 한다.
     *
     * OCC(낙관적 잠금): WHERE version = :version 조건으로 충돌을 감지한다.
     * affected 행이 0 이면(존재하지 않거나 soft-deleted 이거나 version 충돌) null 을 반환한다.
     *
     * @param id 갱신할 스프린트 UUID
     * @param name 새 스프린트 이름
     * @param goal 새 목표 설명. null 허용
     * @param startDate 새 시작일. null 이면 미지정
     * @param endDate 새 종료일. null 이면 미지정
     * @param version 현재 낙관적 잠금 버전
     * @return 갱신된 스프린트, 미존재 또는 version 충돌이면 null
     */
    @Transactional
    fun updateMeta(
        id: UUID,
        name: String,
        goal: String?,
        startDate: LocalDate?,
        endDate: LocalDate?,
        version: Long,
    ): Sprint? {
        log.debug("스프린트 메타 갱신 — id={}, name={}", id, name)

        val now = utcNow()
        val affected =
            dsl.update(SPRINTS)
                .set(SPRINTS.NAME, name)
                .set(SPRINTS.GOAL, goal)
                .set(SPRINTS.START_DATE, startDate)
                .set(SPRINTS.END_DATE, endDate)
                .set(SPRINTS.VERSION, version + 1)
                .set(SPRINTS.UPDATED_AT, now)
                .where(SPRINTS.ID.eq(id))
                .and(SPRINTS.VERSION.eq(version))
                .and(SPRINTS.DELETED_AT.isNull)
                .execute()

        if (affected == 0) return null
        return findById(id)
    }

    // ── updateStatus ──────────────────────────────────────────────────────────

    /**
     * 스프린트의 상태를 변경하고 version 을 bump 한다.
     *
     * 도메인 전환 유효성 검증(PLANNED→ACTIVE→COMPLETED)은 Service 계층(Task 4/5)에서 수행한다.
     * 여기서는 상태를 그대로 영속화한다.
     * OCC: WHERE version = :version 조건으로 충돌 감지.
     *
     * @param id 갱신할 스프린트 UUID
     * @param newStatus 새 상태
     * @param version 현재 낙관적 잠금 버전
     * @return 갱신된 스프린트, 미존재 또는 version 충돌이면 null
     */
    @Transactional
    fun updateStatus(
        id: UUID,
        newStatus: SprintStatus,
        version: Long,
    ): Sprint? {
        log.debug("스프린트 상태 갱신 — id={}, newStatus={}", id, newStatus)

        val now = utcNow()
        val affected =
            dsl.update(SPRINTS)
                .set(SPRINTS.STATUS, newStatus.name)
                .set(SPRINTS.VERSION, version + 1)
                .set(SPRINTS.UPDATED_AT, now)
                .where(SPRINTS.ID.eq(id))
                .and(SPRINTS.VERSION.eq(version))
                .and(SPRINTS.DELETED_AT.isNull)
                .execute()

        if (affected == 0) return null
        return findById(id)
    }

    // ── softDelete ────────────────────────────────────────────────────────────

    /**
     * 스프린트를 소프트 삭제한다.
     *
     * FK ON DELETE CASCADE 는 소프트 삭제 시 발동하지 않으므로,
     * sprint_issues 를 먼저 명시 DELETE 한 뒤 sprints.deleted_at 을 세팅한다.
     * 두 DML 은 동일 트랜잭션 내에서 원자적으로 실행된다.
     *
     * @param id 소프트 삭제할 스프린트 UUID
     */
    @Transactional
    fun softDelete(id: UUID) {
        log.debug("스프린트 소프트 삭제 — id={}", id)

        // sprint_issues 먼저 명시 삭제 (소프트삭제는 FK CASCADE 미발동)
        dsl.deleteFrom(SPRINT_ISSUES)
            .where(SPRINT_ISSUES.SPRINT_ID.eq(id))
            .execute()

        dsl.update(SPRINTS)
            .set(SPRINTS.DELETED_AT, utcNow())
            .where(SPRINTS.ID.eq(id))
            .and(SPRINTS.DELETED_AT.isNull)
            .execute()
    }

    // ── assignIssue ───────────────────────────────────────────────────────────

    /**
     * 이슈를 스프린트에 할당한다.
     *
     * delete-then-insert 단일 트랜잭션.
     * 1. SPRINT_ISSUES 에서 해당 issue_key 를 전역 DELETE (다른 스프린트에 있을 수 있음).
     * 2. 대상 스프린트가 COMPLETED 가 아닐 때만 INSERT 수행(조건부 DML).
     *
     * 조건부 DML: INSERT ... SELECT 에서 서브쿼리로 status 를 검사한다.
     * 대상 스프린트가 COMPLETED 이면 INSERT 가 실행되지 않아 affected 행수 0 을 반환한다.
     *
     * no-bump: sprints 행의 version / updated_at 은 변경하지 않는다.
     *
     * 멱등 재할당(같은 sprint + issue_key): 전역 DELETE 로 기존 행이 사라진 뒤 재INSERT.
     * issue_key 가 이미 이 스프린트에 있으면 DELETE 1 + INSERT 1 = 정상 처리(중복 INSERT 아님).
     *
     * UNIQUE(issue_key) 위반 예외는 잡지 않고 상위 계층(Task 4/5)으로 전파한다.
     *
     * @param sprintId 이슈를 할당할 스프린트 UUID
     * @param issueKey 할당할 이슈 키
     * @return INSERT 된 행수. 0 이면 COMPLETED 가드에 걸림(서비스가 E5 처리)
     */
    @Transactional
    fun assignIssue(
        sprintId: UUID,
        issueKey: String,
    ): Int {
        log.debug("이슈 스프린트 할당 — sprintId={}, issueKey={}", sprintId, issueKey)

        // 1단계: 해당 issue_key 를 sprint_issues 에서 전역 삭제 (다른 sprint 에 있을 수 있음)
        dsl.deleteFrom(SPRINT_ISSUES)
            .where(SPRINT_ISSUES.ISSUE_KEY.eq(issueKey))
            .execute()

        // 2단계: COMPLETED 가드 — 서브쿼리로 status 확인 후 조건부 INSERT
        return dsl.insertInto(
            SPRINT_ISSUES,
            SPRINT_ISSUES.SPRINT_ID,
            SPRINT_ISSUES.ISSUE_KEY,
            SPRINT_ISSUES.CREATED_AT,
        ).select(
            DSL.select(
                DSL.inline(sprintId),
                DSL.inline(issueKey),
                DSL.inline(utcNow()),
            ).from(SPRINTS)
                .where(SPRINTS.ID.eq(sprintId))
                .and(SPRINTS.STATUS.ne(STATUS_COMPLETED))
                .and(SPRINTS.DELETED_AT.isNull),
        ).execute()
    }

    // ── unassignIssue ─────────────────────────────────────────────────────────

    /**
     * 이슈를 스프린트에서 제거한다.
     *
     * 해당 sprint_id + issue_key 행을 DELETE 한다.
     * 조건부 DML: COMPLETED 가드 — 대상 스프린트가 COMPLETED 이면 DELETE 가 실행되지 않는다.
     * 멱등: 이미 없는 이슈 키에 대해서도 예외를 던지지 않는다(0행 DELETE = 정상).
     *
     * no-bump: sprints 행의 version / updated_at 은 변경하지 않는다.
     *
     * @param sprintId 스프린트 UUID
     * @param issueKey 제거할 이슈 키
     */
    @Transactional
    fun unassignIssue(
        sprintId: UUID,
        issueKey: String,
    ) {
        log.debug("이슈 스프린트 제거 — sprintId={}, issueKey={}", sprintId, issueKey)

        // COMPLETED 가드: SPRINT_ISSUES 에서 DELETE 시 스프린트 status 가 COMPLETED 이면 실행 안 함.
        // WHERE EXISTS 서브쿼리로 조건부 DELETE 구현.
        dsl.deleteFrom(SPRINT_ISSUES)
            .where(SPRINT_ISSUES.SPRINT_ID.eq(sprintId))
            .and(SPRINT_ISSUES.ISSUE_KEY.eq(issueKey))
            .and(
                DSL.exists(
                    DSL.selectOne()
                        .from(SPRINTS)
                        .where(SPRINTS.ID.eq(sprintId))
                        .and(SPRINTS.STATUS.ne(STATUS_COMPLETED)),
                ),
            )
            .execute()
    }

    // ── findIssueKeys ─────────────────────────────────────────────────────────

    /**
     * 스프린트에 할당된 이슈 키 목록을 created_at 오름차순으로 반환한다.
     *
     * @param sprintId 조회할 스프린트 UUID
     * @return 이슈 키 목록 (created_at 오름차순)
     */
    @Transactional(readOnly = true)
    fun findIssueKeys(sprintId: UUID): List<String> {
        return dsl.select(SPRINT_ISSUES.ISSUE_KEY)
            .from(SPRINT_ISSUES)
            .where(SPRINT_ISSUES.SPRINT_ID.eq(sprintId))
            .orderBy(SPRINT_ISSUES.CREATED_AT.asc())
            .fetch()
            .mapNotNull { it.value1() }
    }

    // ── findIssueKeysByProject ────────────────────────────────────────────────

    /**
     * 프로젝트의 활성 스프린트 전체에 걸쳐 sprint_id → issue_key 목록 매핑을 단일 쿼리로 반환한다.
     *
     * N+1 차단을 위해 스프린트마다 [findIssueKeys] 를 호출하는 대신 이 메서드를 사용한다
     * (백로그 조회 C4 요건 — 스프린트 N+1 쿼리 차단).
     *
     * soft-deleted 스프린트(deleted_at IS NOT NULL)는 JOIN 에서 제외한다.
     * 결과에 없는 sprintId 는 이슈가 없음을 의미한다(빈 목록과 동치).
     * created_at 오름차순으로 이슈 키를 정렬해 반환한다.
     *
     * @param projectKey 조회할 프로젝트 키
     * @return sprint UUID → 해당 스프린트의 이슈 키 목록(created_at 오름차순) 맵
     */
    @Transactional(readOnly = true)
    fun findIssueKeysByProject(projectKey: String): Map<UUID, List<String>> {
        return dsl.select(SPRINT_ISSUES.SPRINT_ID, SPRINT_ISSUES.ISSUE_KEY)
            .from(SPRINT_ISSUES)
            .join(SPRINTS).on(
                SPRINT_ISSUES.SPRINT_ID.eq(SPRINTS.ID)
                    .and(SPRINTS.PROJECT_KEY.eq(projectKey))
                    .and(SPRINTS.DELETED_AT.isNull),
            )
            .orderBy(SPRINT_ISSUES.SPRINT_ID, SPRINT_ISSUES.CREATED_AT.asc())
            .fetch()
            .groupBy(
                keySelector = { row -> row.value1() ?: error("sprint_issues.sprint_id null") },
                valueTransform = { row -> row.value2() ?: "" },
            )
    }

    // ── 도메인 매핑 ───────────────────────────────────────────────────────────

    /**
     * [SprintsRecord] 를 도메인 [Sprint] 로 변환한다.
     */
    private fun toDomain(record: SprintsRecord): Sprint {
        val id = record.id ?: error("sprints.id 가 null — DB 데이터 손상")
        val statusRaw = record.status ?: error("sprints.status 가 null — id=$id")
        val status =
            SprintStatus.entries.firstOrNull { it.name == statusRaw }
                ?: error("sprints.status 알 수 없는 값: $statusRaw — id=$id")

        return Sprint(
            id = id,
            projectKey = record.projectKey,
            boardId = record.boardId,
            name = record.name,
            goal = record.goal,
            status = status,
            startDate = record.startDate,
            endDate = record.endDate,
            version = record.version ?: 0L,
        )
    }
}
