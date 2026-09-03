// IssueRepository — issues / projects 테이블 jOOQ DSL 접근. DATA.md §5, §6 준수.

package com.bts.issue.repository

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.application.DatePatch
import com.bts.issue.application.EstimatePatch
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.jooq.tables.records.IssuesRecord
import com.bts.issue.jooq.tables.references.COMPONENTS
import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.ISSUE_AFFECTS_VERSIONS
import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_GROUP
import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_ITEM
import com.bts.issue.jooq.tables.references.ISSUE_COMPONENTS
import com.bts.issue.jooq.tables.references.ISSUE_FIX_VERSIONS
import com.bts.issue.jooq.tables.references.ISSUE_TYPES
import com.bts.issue.jooq.tables.references.PROJECTS
import com.bts.issue.jooq.tables.references.VERSIONS
import com.bts.issue.jooq.tables.references.WORKLOGS
import com.bts.issue.statushistory.repository.StatusChangeRow
import com.bts.issue.statushistory.repository.StatusHistoryRepository
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.search.AqlField
import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlOperator
import com.bts.shared.search.AqlSort
import com.bts.shared.search.AqlValue
import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.SortDirection
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SortField
import org.jooq.Table
import org.jooq.TableField
import org.jooq.UpdateSetMoreStep
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// ── SQL 상수 ──────────────────────────────────────────────────────────────────
// pg_advisory_xact_lock — 트랜잭션 범위 권고 락. hashtext() 로 VARCHAR → INT4 해시값 생성.
// 같은 projectKey 에 대한 동시 incrementKeySequence 호출을 직렬화한다.
private const val SQL_ADVISORY_LOCK = "SELECT pg_advisory_xact_lock(hashtext(?))"

// ── 라벨 자동완성 SQL ─────────────────────────────────────────────────────────
// UNNEST(labels) 로 배열을 행으로 전개한 뒤 COUNT(DISTINCT id) 로 라벨별 이슈 수를 집계한다.
// prefix 가 비어 있으면 ILIKE 절 없이 전체 라벨을 집계하고,
// prefix 가 있으면 앱단 이스케이프 후 ILIKE ? ESCAPE '\' 절을 추가한다.
// prefix/limit 은 반드시 바인드 파라미터(?)로 전달한다 — 문자열 concat 금지(SQL injection).
// jOOQ DSL 대신 dsl.fetch(sql, params) 를 사용하는 이유:
//   UNNEST + derived table + ILIKE ESCAPE + COUNT DISTINCT 조합은 jOOQ DSL 로 표현 시
//   PostgreSQL 특화 API 를 다수 거쳐야 해 가독성이 크게 저하된다.
//   BulkOperationWorker.kt:88 선례와 동일하게 prepared statement 바인딩으로 injection 방지.
private const val SQL_LABELS_BY_PREFIX_ALL =
    """SELECT label, COUNT(DISTINCT id) AS freq
       FROM (SELECT id, UNNEST(labels) AS label
             FROM issues
             WHERE deleted_at IS NULL) t
       GROUP BY label
       ORDER BY freq DESC, label ASC
       LIMIT ?"""

private const val SQL_LABELS_BY_PREFIX_FILTER =
    """SELECT label, COUNT(DISTINCT id) AS freq
       FROM (SELECT id, UNNEST(labels) AS label
             FROM issues
             WHERE deleted_at IS NULL) t
       WHERE label ILIKE ? ESCAPE '\'
       GROUP BY label
       ORDER BY freq DESC, label ASC
       LIMIT ?"""

/** 라벨 자동완성 기본 반환 한도. */
private const val LABEL_AUTOCOMPLETE_DEFAULT_LIMIT = 20

// ── 부모 계층 SQL ─────────────────────────────────────────────────────────────
// WITH RECURSIVE CTE 로 parent_id 체인을 따라 최상위까지 조상 UUID 를 수집한다.
// UNION (not UNION ALL) 을 사용해 cycle 발생 시 동일 행 재삽입 없이 자동 탈출 (cycle-safe).
// 실제 데이터에서 cycle 은 발생하지 않는다 — Task 6 서비스 계층이 cycle 생성을 거부한다.
// parent_id IS NOT NULL 조건: anchor 에서 자기 자신을 제외하고, 재귀 절에서 최상위 도달 시 중단.
private const val SQL_COLLECT_ANCESTORS =
    """
    WITH RECURSIVE ancestors AS (
        SELECT parent_id AS ancestor_id
        FROM issues
        WHERE id = ?
          AND parent_id IS NOT NULL
        UNION
        SELECT i.parent_id
        FROM issues i
        INNER JOIN ancestors a ON i.id = a.ancestor_id
        WHERE i.parent_id IS NOT NULL
    )
    SELECT ancestor_id FROM ancestors
    """

/**
 * 이슈 필드 부분 업데이트 요청. [IssueRepository.updateFields] 파라미터 그룹화용.
 *
 * null 필드는 변경하지 않는다.
 * description/environment 는 빈/공백 문자열이면 DB NULL 로 클리어한다 (ifBlank).
 * customFields 는 non-null 이면 병합된 최종 맵 전체를 JSONB 로 저장한다.
 * startDate/dueDate/targetDate 는 [DatePatch] 3-state 로 변경 의도를 표현한다 (FR-PL-01).
 *   - [DatePatch.Unchanged] (기본값) — 변경하지 않는다.
 *   - [DatePatch.Clear] — DB NULL 로 클리어한다.
 *   - [DatePatch.Set] — 지정 날짜로 SET 한다.
 * originalEstimate/remainingEstimate 는 [EstimatePatch] 3-state 로 변경 의도를 표현한다 (FR-TT-01).
 *   수동 추정 변경은 정식 이슈 수정 — version 증가 경로(updateFields)를 통한다.
 */
data class IssueFieldPatch(
    val summary: String? = null,
    val typeId: IssueTypeId? = null,
    val description: String? = null,
    val priority: Int? = null,
    val labels: List<String>? = null,
    val environment: String? = null,
    val impact: Int? = null,
    val customFields: Map<String, Any?>? = null,
    val startDate: DatePatch = DatePatch.Unchanged,
    val dueDate: DatePatch = DatePatch.Unchanged,
    val targetDate: DatePatch = DatePatch.Unchanged,
    val originalEstimate: EstimatePatch = EstimatePatch.Unchanged,
    val remainingEstimate: EstimatePatch = EstimatePatch.Unchanged,
)

/**
 * worklog 롤업 결과 VO (FR-TT-01, Task 3).
 *
 * [IssueRepository.recomputeTimeSpentWithDecrement] / [IssueRepository.recomputeTimeSpentSetRemaining] /
 * [IssueRepository.recomputeTimeSpent] 가 RETURNING 절로 반환하는 집계 결과.
 * no-bump(version 불변) 경로라 Issue 전체 도메인 객체가 아닌 최소 필드만 반환한다.
 *
 * @property timeSpent 재집계된 누적 작업 시간(초). 항상 0 이상.
 * @property remaining 갱신된 잔여 추정 시간(초). null 이면 미추정 상태 유지.
 */
data class RollupResult(
    val timeSpent: Int,
    val remaining: Int?,
)

/**
 * 릴리즈 노트 생성용 이슈 읽기 결과 행.
 *
 * [IssueRepository.findFixVersionIssuesForReleaseNotes] 가 단일 쿼리로 반환하는 평면 결과.
 * Service 레이어가 [com.bts.issue.version.releasenotes.ReleaseNoteIssue] 로 매핑한다.
 *
 * cartesian product 안전성 근거 (learnings PR#31 적용).
 * - `issue_fix_versions`: versionId 필터 시 이슈당 최대 1행 (복합 PK issue_id, version_id).
 * - `issue_types`: issues.type_id 1:1 FK — 이슈당 1행.
 * → 총 이슈당 1행 보장.
 *
 * @property key 이슈 전역 식별자 문자열 (예: "BTS-1").
 * @property summary 이슈 제목.
 * @property typeId 이슈 유형 BIGINT id.
 * @property typeKey 이슈 유형 키 (예: "bug", "task").
 * @property typeName 이슈 유형 표시 이름 (예: "Bug", "Task").
 * @property hierarchyLevel 이슈 유형 계층 깊이. epic=1, task/story/bug=0, subtask=-1.
 * @property resolutionId 해결 완료 UUID. 미해결 또는 resolution 미지정 시 null.
 */
data class ReleaseNoteIssueRow(
    val key: String,
    val summary: String,
    val typeId: Long,
    val typeKey: String,
    val typeName: String,
    val hierarchyLevel: Int,
    val resolutionId: UUID?,
)

/**
 * 마감일 스캔 결과 경량 projection.
 *
 * [IssueRepository.findOpenIssuesDueOn] / [IssueRepository.findOpenOverdueIssues] 가 반환하는
 * 최소 필드 집합. 스케줄러는 풀 Issue 도메인 객체가 아니라 이벤트 발행에 필요한 키 2개만 필요하다.
 *
 * projectKey 는 issues × projects JOIN 으로 projects.key 를 직접 읽는다.
 * issues.key 파싱(substringBefore) 대신 JOIN 을 선택한 이유: 스캔 쿼리는 다건 조회라
 * PROJECTS.KEY 를 한 번에 가져오는 JOIN 이 더 명시적이고 도메인 불일치 위험이 없다.
 *
 * @property issueKey 이슈 전역 식별자 문자열 (예: "BTS-1").
 * @property projectKey 소속 프로젝트 키 (예: "BTS").
 */
data class IssueDueScanItem(
    val issueKey: String,
    val projectKey: String,
)

/**
 * 이슈 Repository.
 *
 * jOOQ DSLContext 를 통해 issues / projects 테이블에 접근한다.
 * 모든 public 메서드는 @Transactional 를 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
 *
 * - [insert] — issues 테이블에 새 row 삽입 후 DB 생성 값(createdAt, updatedAt) 을 포함한 Issue 반환.
 * - [findByKey] — 활성(deleted_at IS NULL) 이슈를 key 로 조회.
 * - [findByKeyForUpdate] — [findByKey] + SELECT FOR UPDATE (비관락).
 * - [applyTransition] — currentStateKey + updatedAt + version 을 낙관락 조건 업데이트.
 * - [softDelete] — deleted_at 를 NOW() 로 설정.
 * - [list] — 프로젝트별 활성 이슈 페이지 조회.
 * - [incrementKeySequence] — pg_advisory_xact_lock 으로 동시성 제어 후 key_sequence +1 RETURNING.
 * - [findFixVersionIssuesForReleaseNotes] — 버전 UUID → fix version 연결 활성 이슈 목록 반환.
 * - [updateParent] — issues.parent_id 설정 또는 해제.
 * - [collectAncestors] — parent_id 체인 상향 수집 (WITH RECURSIVE CTE).
 */

@Repository
// LargeClass: 이슈 집계 루트의 단일 jOOQ 접근점 — 응집이 분리보다 적합
@Suppress("TooManyFunctions", "LargeClass")
class IssueRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈를 DB 에 삽입하고 DB 생성 값(id, createdAt, updatedAt, version)을 포함한 Issue 를 반환한다.
     *
     * version=1 은 도메인 생성 시 이미 고정. DB DEFAULT 를 사용하지 않고 명시적으로 삽입한다.
     */
    @Transactional
    fun insert(issue: Issue): Issue {
        log.debug("Inserting issue key={}", issue.key.value)
        val record =
            dsl.insertInto(ISSUES)
                .set(issue.toInsertRecord())
                .returning()
                .fetchOne()
                ?: error("insert returning() returned null for key=${issue.key.value}")

        return record.toIssue()
    }

    /**
     * 활성 이슈(deleted_at IS NULL)를 key 로 조회한다.
     *
     * @return 이슈가 없거나 소프트 삭제된 경우 null.
     */
    @Transactional(readOnly = true)
    fun findByKey(key: IssueKey): Issue? =
        dsl.selectFrom(ISSUES)
            .where(activeByKey(key))
            .fetchOne()
            ?.toIssue()

    /**
     * 활성 이슈를 key 로 조회하면서 비관락(SELECT FOR UPDATE) 을 획득한다.
     *
     * 호출자는 반드시 활성 트랜잭션 내에서 호출해야 한다.
     * 락은 트랜잭션 종료 시 자동 해제된다.
     *
     * @return 이슈가 없거나 소프트 삭제된 경우 null.
     */
    @Transactional
    fun findByKeyForUpdate(key: IssueKey): Issue? =
        dsl.selectFrom(ISSUES)
            .where(activeByKey(key))
            .forUpdate()
            .fetchOne()
            ?.toIssue()

    /**
     * 이슈 필드(summary, typeId, description, priority, labels, environment, impact)를 수정한다 (낙관락).
     *
     * non-null 인 필드만 SET 절에 포함한다. null 전달 시 해당 필드는 변경하지 않는다.
     * WHERE key=? AND version=? AND deleted_at IS NULL 조건으로 업데이트.
     * version 불일치(stale read) 시 영향 행 0 반환.
     *
     * @param key 이슈 키.
     * @param summary 새 이슈 제목. null 이면 변경하지 않는다.
     * @param typeId 새 이슈 유형 식별자 VO. null 이면 변경하지 않는다.
     * @param expectedVersion 현재 버전. DB 버전과 일치해야 업데이트가 실행된다.
     * @param description Markdown 설명. null 이면 변경하지 않는다. 빈/공백 문자열은 DB NULL 로 클리어한다.
     * @param priority 우선순위 1..5. null 이면 변경하지 않는다.
     * @param labels 라벨 목록. null 이면 변경하지 않는다.
     * @param environment 재현 환경 설명. null 이면 변경하지 않는다. 빈/공백 문자열은 DB NULL 로 클리어한다.
     * @param impact 영향도 1..3. null 이면 변경하지 않는다.
     * @return 업데이트된 행 수 (성공=1, 낙관락 충돌=0).
     */
    @Transactional
    @Suppress("CyclomaticComplexMethod") // jOOQ 선택적 SET 패턴 — null 필드 skip, 조건 분기가 불가피
    fun updateFields(
        key: IssueKey,
        patch: IssueFieldPatch,
        expectedVersion: Long,
    ): Int {
        log.debug("updateFields key={} typeId={} expectedVersion={}", key.value, patch.typeId?.value, expectedVersion)
        return dsl.update(ISSUES)
            .set(ISSUES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(ISSUES.VERSION, expectedVersion + 1)
            .apply { if (patch.summary != null) set(ISSUES.SUMMARY, patch.summary) }
            .apply { if (patch.typeId != null) set(ISSUES.TYPE_ID, patch.typeId.value) }
            .apply { if (patch.description != null) set(ISSUES.DESCRIPTION, patch.description.ifBlank { null }) }
            .apply { if (patch.priority != null) set(ISSUES.PRIORITY, patch.priority.toShort()) }
            .apply { if (patch.labels != null) set(ISSUES.LABELS, patch.labels.toDbArray()) }
            .apply { if (patch.environment != null) set(ISSUES.ENVIRONMENT, patch.environment.ifBlank { null }) }
            .apply { if (patch.impact != null) set(ISSUES.IMPACT, patch.impact.toShort()) }
            .apply { if (patch.customFields != null) set(ISSUES.CUSTOM_FIELDS, patch.customFields.toJsonb()) }
            // 날짜 3-state SET (FR-PL-01). applyDatePatch 헬퍼로 중복 when-분기 추출.
            .applyDatePatch(ISSUES.START_DATE, patch.startDate)
            .applyDatePatch(ISSUES.DUE_DATE, patch.dueDate)
            .applyDatePatch(ISSUES.TARGET_DATE, patch.targetDate)
            // 추정 시간 3-state SET (FR-TT-01). 수동 추정 변경은 정식 이슈 수정 — version 증가 경로.
            .applyEstimatePatch(ISSUES.ORIGINAL_ESTIMATE_SECONDS, patch.originalEstimate)
            .applyEstimatePatch(ISSUES.REMAINING_ESTIMATE_SECONDS, patch.remainingEstimate)
            .where(ISSUES.KEY.eq(key.value))
            .and(ISSUES.VERSION.eq(expectedVersion))
            .and(ISSUES.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 이슈 담당자를 갱신한다 (낙관락 OCC UPDATE).
     *
     * WHERE key=? AND version=? AND deleted_at IS NULL 조건으로 UPDATE.
     * version 불일치(stale read) 시 영향 행 0 반환.
     *
     * @param key 이슈 키.
     * @param assigneeId 새 담당자 UUID. null 이면 담당자 해제.
     * @param expectedVersion 현재 버전. DB 버전과 일치해야 업데이트가 실행된다.
     * @return 업데이트된 행 수 (성공=1, 낙관락 충돌=0).
     */
    @Transactional
    fun updateAssignee(
        key: IssueKey,
        assigneeId: UUID?,
        expectedVersion: Long,
    ): Int {
        log.debug("updateAssignee key={} assigneeId={} expectedVersion={}", key.value, assigneeId, expectedVersion)
        return dsl.update(ISSUES)
            .set(ISSUES.ASSIGNEE_ID, assigneeId)
            .set(ISSUES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(ISSUES.VERSION, expectedVersion + 1)
            .where(ISSUES.KEY.eq(key.value))
            .and(ISSUES.VERSION.eq(expectedVersion))
            .and(ISSUES.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 이슈 상태를 전환하고 resolution_id 를 함께 업데이트한다 (낙관락).
     *
     * WHERE key=? AND version=? AND deleted_at IS NULL 조건으로 업데이트.
     * version 불일치(stale read) 시 영향 행 0 반환.
     *
     * NOTE. [com.bts.shared.workflow.TransitionPlan] 직접 import 금지 —
     * BC 격리 원칙 (CLAUDE.md §BC 격리). 호출자 ApplicationService (T10) 가 TransitionPlan 을
     * 이 메서드 파라미터로 분해해서 전달한다.
     *
     * resolution_id 영속 정책 (B-4 근거).
     * - DONE 전환 시 non-null resolutionId 를 그대로 SET 한다.
     * - 비DONE 전환(resolutionId=null) 시 RESOLUTION_ID 를 NULL 로 clear 한다.
     * - 워크플로우 validator 가 DONE 진입 시 resolution 필수 불변식을 강제하므로(B7)
     *   이 메서드가 null 을 허용하는 것은 patch-merge-domain-bypass 위배 아님.
     *
     * @param key 이슈 키.
     * @param toState 전환할 목표 워크플로우 상태 키.
     * @param expectedVersion 현재 버전. DB 버전과 일치해야 업데이트가 실행된다.
     * @param resolutionId DONE 전환 시 설정할 Resolution UUID. null 이면 DB NULL 로 clear.
     * @return 업데이트된 행 수 (성공=1, 낙관락 충돌=0).
     */
    @Transactional
    fun applyTransition(
        key: IssueKey,
        toState: String,
        expectedVersion: Long,
        resolutionId: UUID?,
    ): Int {
        log.debug(
            "applyTransition key={} toState={} expectedVersion={} resolutionId={}",
            key.value,
            toState,
            expectedVersion,
            resolutionId,
        )
        return dsl.update(ISSUES)
            .set(ISSUES.CURRENT_STATE_KEY, toState)
            .set(ISSUES.RESOLUTION_ID, resolutionId)
            .set(ISSUES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(ISSUES.VERSION, expectedVersion + 1)
            .where(ISSUES.KEY.eq(key.value))
            .and(ISSUES.VERSION.eq(expectedVersion))
            .and(ISSUES.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 이슈를 소프트 삭제한다.
     *
     * deleted_at 를 현재 UTC 시각으로 설정한다.
     * 이미 삭제된 이슈는 영향 행 0 반환.
     *
     * @return 업데이트된 행 수 (성공=1, 이미 삭제됨=0).
     */
    @Transactional
    fun softDelete(key: IssueKey): Int {
        log.debug("softDelete key={}", key.value)
        return dsl.update(ISSUES)
            .set(ISSUES.DELETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(activeByKey(key))
            .execute()
    }

    /**
     * 프로젝트별 활성 이슈 목록을 페이지 단위로 조회한다.
     *
     * 정렬 기준. created_at DESC (최신순).
     *
     * @param projectKey 프로젝트 접두사 (예: "BTS").
     * @param pageable 페이지 정보. offset/limit 기반 (Spring Data Pageable).
     * @return [Page] — content + totalElements.
     */
    @Transactional(readOnly = true)
    fun list(
        projectKey: String,
        pageable: Pageable,
    ): Page<Issue> {
        val activeInProject =
            PROJECTS.KEY.eq(projectKey)
                .and(ISSUES.DELETED_AT.isNull)

        val total =
            dsl.selectCount()
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .where(activeInProject)
                .fetchOne(0, Long::class.java) ?: 0L

        val content =
            dsl.select(ISSUES.fields().toList())
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .where(activeInProject)
                .orderBy(ISSUES.CREATED_AT.desc())
                .limit(pageable.pageSize)
                .offset(pageable.offset)
                .fetch { it.into(ISSUES).toIssue() }

        return PageImpl(content, pageable, total)
    }

    /**
     * 프로젝트 키로 projects.id 를 조회한다.
     *
     * @param projectKey 프로젝트 접두사 (예: "BTS").
     * @return 프로젝트 UUID. 존재하지 않거나 소프트 삭제된 경우 null.
     */
    @Transactional(readOnly = true)
    fun findProjectIdByKey(projectKey: String): UUID? =
        dsl.select(PROJECTS.ID)
            .from(PROJECTS)
            .where(PROJECTS.KEY.eq(projectKey))
            .and(PROJECTS.DELETED_AT.isNull)
            .fetchOne(PROJECTS.ID)

    /**
     * 프로젝트의 key_sequence 를 1 증가시키고 새 값을 반환한다.
     *
     * pg_advisory_xact_lock 으로 동일 projectKey 에 대한 동시 호출을 직렬화한다.
     * 락은 현재 트랜잭션이 커밋/롤백될 때 자동 해제된다.
     *
     * @param projectKey 프로젝트 접두사 (예: "BTS").
     * @return 증가된 key_sequence 값.
     * @throws IssueProjectNotFoundException 프로젝트가 존재하지 않는 경우.
     */
    @Transactional
    fun incrementKeySequence(projectKey: String): Long {
        dsl.execute(SQL_ADVISORY_LOCK, "project:$projectKey")
        log.debug("incrementKeySequence acquired advisory lock for projectKey={}", projectKey)

        return dsl.update(PROJECTS)
            .set(PROJECTS.KEY_SEQUENCE, PROJECTS.KEY_SEQUENCE.plus(1))
            .where(PROJECTS.KEY.eq(projectKey))
            .and(PROJECTS.DELETED_AT.isNull)
            .returning(PROJECTS.KEY_SEQUENCE)
            .fetchOne()
            ?.keySequence
            ?: throw IssueProjectNotFoundException(projectKey)
    }

    /**
     * 활성 이슈를 key 로 조회하고, issue_types 와 1:1 JOIN, 부모/에픽 이슈와 self LEFT JOIN 하여
     * type 요약·부모 요약·에픽 요약을 포함한 [IssueResponse] 를 반환한다.
     *
     * issues.type_id = issue_types.id 단건 JOIN — cartesian product 위험 없음 (learnings PR#31).
     *
     * 부모 이슈 self LEFT JOIN.
     * - [PARENT_ALIAS] (`issues AS parent`) 로 issues 테이블 자기참조. 본 컬럼과 이름 충돌 차단.
     * - JOIN ON `issues.parent_id = parent.id AND parent.deleted_at IS NULL`.
     * - parent_id 가 null 이면 LEFT JOIN 결과가 모두 null → [IssueResponse.parent] = null.
     * - parent 컬럼은 [PARENT_KEY_ALIAS] / [PARENT_SUMMARY_ALIAS] 로 명시 alias 해 record 에서 읽는다.
     *
     * 에픽 이슈 self LEFT JOIN (FR-EP-01 Task 4).
     * - [EPIC_ALIAS] (`issues AS epic`) 로 issues 테이블 자기참조. parent alias 와 동형 구조.
     * - JOIN ON `issues.epic_id = epic.id AND epic.deleted_at IS NULL`.
     * - epic_id 가 null 이거나 에픽이 소프트삭제됐으면 LEFT JOIN 결과가 모두 null → [IssueResponse.epic] = null.
     * - 보안 등급 필터 적용 안 함 — parent self-join 동형, 의도적 결정 (FR-EP-01 명세).
     * - epic 컬럼은 [EPIC_KEY_ALIAS] / [EPIC_SUMMARY_ALIAS] 로 명시 alias 해 record 에서 읽는다.
     *
     * **parent/epic 는 단건 조회([findByKeyWithType]) 경로에서만 채워진다.**
     * 목록 경로([listWithType])는 N+1/비용 회피를 위해 조인 없이 parent=null, epic=null 반환한다.
     * FR-LK-01 Task 1, FR-EP-01 Task 4.
     *
     * @param key 조회할 이슈 키.
     * @return type 요약(typeId/typeKey/typeName) + 부모 요약(key/summary) + 에픽 요약(key/summary) 이 포함된 [IssueResponse].
     *   이슈가 없으면 null.
     */
    @Transactional(readOnly = true)
    // 명시 alias + LEFT JOIN × 2 + null 분기 불가피 → CyclomaticComplexity 억제
    // parent + epic 두 self-JOIN 결과 추출 분기가 불가피하게 메서드를 길게 만든다 → LongMethod 억제
    @Suppress(
        "CyclomaticComplexity",
        "LongMethod",
    )
    fun findByKeyWithType(key: IssueKey): IssueResponse? {
        // issues self LEFT JOIN — 부모 이슈 key/summary 조회. PARENT_ALIAS 로 컬럼 충돌 차단.
        val parentAlias = ISSUES.`as`(PARENT_ALIAS)
        // issues self LEFT JOIN — 에픽 이슈 key/summary 조회. EPIC_ALIAS 로 컬럼 충돌 차단. FR-EP-01 Task 4.
        val epicAlias = ISSUES.`as`(EPIC_ALIAS)
        return dsl.select(
            ISSUES.fields().toList() +
                listOf(
                    ISSUE_TYPES.ID.`as`(TYPE_ID_ALIAS),
                    ISSUE_TYPES.KEY.`as`(TYPE_KEY_ALIAS),
                    ISSUE_TYPES.NAME.`as`(TYPE_NAME_ALIAS),
                    parentAlias.KEY.`as`(PARENT_KEY_ALIAS),
                    parentAlias.SUMMARY.`as`(PARENT_SUMMARY_ALIAS),
                    epicAlias.KEY.`as`(EPIC_KEY_ALIAS),
                    epicAlias.SUMMARY.`as`(EPIC_SUMMARY_ALIAS),
                ),
        )
            .from(ISSUES)
            .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
            .leftJoin(parentAlias).on(
                ISSUES.PARENT_ID.eq(parentAlias.ID)
                    .and(parentAlias.DELETED_AT.isNull),
            )
            .leftJoin(epicAlias).on(
                ISSUES.EPIC_ID.eq(epicAlias.ID)
                    .and(epicAlias.DELETED_AT.isNull)
                    // P1-A cross-project 누출 차단: epic 이 이동/stale 데이터로 다른 프로젝트에 잔류할 때
                    // epic 없음(null)과 동일하게 처리해 누출을 방지한다.
                    .and(epicAlias.PROJECT_ID.eq(ISSUES.PROJECT_ID)),
            )
            .where(activeByKey(key))
            .fetchOne()
            ?.let { record ->
                val issueRecord = record.into(ISSUES)
                val parentKey = record.get(PARENT_KEY_ALIAS, String::class.java)
                val parentSummary = record.get(PARENT_SUMMARY_ALIAS, String::class.java)
                val parentSummaryDto =
                    if (parentKey != null && parentSummary != null) {
                        IssueResponse.ParentSummary(key = parentKey, summary = parentSummary)
                    } else {
                        null
                    }
                val epicKey = record.get(EPIC_KEY_ALIAS, String::class.java)
                val epicSummary = record.get(EPIC_SUMMARY_ALIAS, String::class.java)
                val epicSummaryDto =
                    if (epicKey != null && epicSummary != null) {
                        IssueResponse.EpicSummary(key = epicKey, summary = epicSummary)
                    } else {
                        null
                    }
                IssueResponse.from(
                    issue = issueRecord.toIssue(),
                    projectKey = key.projectPrefix,
                    typeInfo =
                        IssueResponse.IssueTypeInfo(
                            id =
                                record.get(TYPE_ID_ALIAS, Long::class.java)
                                    ?: error("issue_types.id must not be null in join result"),
                            key =
                                record.get(TYPE_KEY_ALIAS, String::class.java)
                                    ?: error("issue_types.key must not be null in join result"),
                            name =
                                record.get(TYPE_NAME_ALIAS, String::class.java)
                                    ?: error("issue_types.name must not be null in join result"),
                        ),
                    parent = parentSummaryDto,
                    epic = epicSummaryDto,
                )
            }
    }

    /**
     * 프로젝트별 활성 이슈 목록을 페이지 단위로 조회하고, issue_types JOIN 으로 type 요약을 포함한다.
     *
     * count 쿼리는 ISSUES × PROJECTS join 만 사용 (ISSUE_TYPES join 은 count에 불필요).
     * content 쿼리는 ISSUES × PROJECTS × ISSUE_TYPES — 단일 이슈 당 타입이 1건이므로 cartesian 없음.
     *
     * [access] 가 [IssueSecurityAccess.unrestricted] = true 이면 보안 등급 필터를 생략(빠른경로).
     * unrestricted = false 이면 [buildSecurityCondition] 이 반환하는 Condition 을 count/content
     * 양쪽 WHERE 에 동일하게 추가한다 — cartesian product 위험 없이 신규 JOIN 0.
     *
     * @param projectKey 프로젝트 접두사. 예: `"BTS"`.
     * @param pageable 페이지 정보.
     * @param actor 조회 행위자 UUID. 보안 등급 필터가 적용될 때 reporter/assignee 동적 조건에 사용.
     *   [access] 가 unrestricted=true 이면 무의미하다. 보안 민감 메서드이므로 기본값 없이 항상 명시 전달한다.
     * @param access actor 가 접근 가능한 보안 등급 집합. 기본값은 무제한(unrestricted=true).
     * @param filter 보드 카드 필터 조건. 기본값은 무필터([BoardCardFilter.EMPTY]).
     * @return [Page]<[IssueResponse]> — type 요약 포함.
     */
    @Transactional(readOnly = true)
    fun listWithType(
        projectKey: String,
        pageable: Pageable,
        actor: UUID,
        access: IssueSecurityAccess = UNRESTRICTED_ACCESS,
        filter: BoardCardFilter = BoardCardFilter.EMPTY,
    ): Page<IssueResponse> {
        // 활성 프로젝트 술어 + 보안 등급 필터 — listVisibleForBoard 와 동일 source.
        val baseWhere = buildActiveSecureWhere(projectKey, actor, access)

        // C1 — buildFilterCondition 단일 호출. count/content 양쪽에 동일 Condition 재사용.
        // 쿼리별 재조립 금지 — count/content drift 방지.
        val filterCondition = buildFilterCondition(filter)
        val effectiveWhere = if (filterCondition != null) baseWhere.and(filterCondition) else baseWhere

        // count 쿼리: ISSUE_TYPES join 제외 — 불필요한 join 으로 count 왜곡 방지
        val total =
            dsl.selectCount()
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .where(effectiveWhere)
                .fetchOne(0, Long::class.java) ?: 0L

        // content 쿼리: ISSUE_TYPES join 으로 type 요약 포함
        // buildTypeSelectColumns() 재사용 — listWithTypeByCursor 와 동일 컬럼 상수 공유
        val content =
            dsl.select(ISSUES.fields().toList() + buildTypeSelectColumns())
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
                .where(effectiveWhere)
                .orderBy(buildListOrderBy(pageable.sort))
                .limit(pageable.pageSize)
                .offset(pageable.offset)
                .fetch { record -> record.toIssueResponseWithType(projectKey) }

        return PageImpl(content, pageable, total)
    }

    /**
     * 이슈 목록 cursor keyset seek 조회 결과 VO (FR-API-01 Task 2).
     *
     * `limit+1` fetch 로 다음 페이지 존재 여부를 판정한다.
     * items 크기는 최대 limit 건이며, hasNext=true 이면 다음 커서 위치가 있다.
     *
     * @property items 조회된 이슈 응답 목록. 최대 limit 건.
     * @property hasNext 다음 페이지 존재 여부. limit+1 번째 행이 조회되면 true.
     */
    data class IssueCursorPage(
        val items: List<IssueResponse>,
        val hasNext: Boolean,
    )

    /**
     * 이슈 목록을 keyset cursor seek 방식으로 조회한다 (FR-API-01 Task 2).
     *
     * [listWithType] 과 동일한 [buildActiveSecureWhere] 보안 술어 + [buildFilterCondition] 필터를
     * 재사용하여 별도 보안 경로를 신설하지 않는다.
     *
     * cursor 위치: `(seekCreatedAt, seekId)` 쌍으로 지정한다.
     * `WHERE (created_at < :seekCreatedAt) OR (created_at = :seekCreatedAt AND id < :seekId)` 로
     * keyset seek 를 표현한다 (row-value 비교와 동일 의미).
     *
     * 정렬: `ORDER BY created_at DESC, id DESC` — id 는 created_at 동률 tie-break.
     * limit+1 건을 fetch 해 결과가 limit+1 이면 hasNext=true 로 판정한다.
     *
     * 레이어 결정: repository 는 inbound adapter(CursorPosition) 를 import 하지 않는다.
     * 호출자(서비스 계층)가 CursorPosition.createdAt/id 를 분해해 원시값으로 전달한다.
     *
     * @param projectKey 프로젝트 접두사. 예: `"BTS"`.
     * @param seekCreatedAt cursor 위치의 created_at. null 이면 첫 페이지(seek 없음).
     * @param seekId cursor 위치의 id. null 이면 첫 페이지. seekCreatedAt 과 항상 쌍.
     * @param limit 반환할 최대 건수. limit+1 건 fetch 로 hasNext 판정.
     * @param actor 조회 행위자 UUID. 보안 등급 필터에 사용.
     * @param access 접근 가능 보안 등급 집합. 기본값 unrestricted(빠른경로).
     * @param filter 이슈 필터 조건. 기본값 무필터.
     * @return [IssueCursorPage] — items(최대 limit 건) + hasNext.
     */
    @Transactional(readOnly = true)
    @Suppress("LongParameterList") // keyset cursor seek 파라미터 집합(seek좌표 2+limit+actor+access+filter) — VO 분리 시 레이어 오염
    fun listWithTypeByCursor(
        projectKey: String,
        seekCreatedAt: OffsetDateTime?,
        seekId: UUID?,
        limit: Int,
        actor: UUID,
        access: IssueSecurityAccess = UNRESTRICTED_ACCESS,
        filter: BoardCardFilter = BoardCardFilter.EMPTY,
    ): IssueCursorPage {
        // C1: 보안 술어 + 삭제 필터 — listWithType 과 동일 단일 source
        val baseWhere = buildActiveSecureWhere(projectKey, actor, access)
        val filterCondition = buildFilterCondition(filter)
        var effectiveWhere = if (filterCondition != null) baseWhere.and(filterCondition) else baseWhere

        // C2: keyset seek — cursor 위치 이후(오래된) 행만 반환
        if (seekCreatedAt != null && seekId != null) {
            effectiveWhere = effectiveWhere.and(buildSeekCondition(seekCreatedAt, seekId))
        }

        // buildTypeSelectColumns() / toIssueResponseWithType() — listWithType 와 공통 빌더 공유
        val fetched =
            dsl.select(ISSUES.fields().toList() + buildTypeSelectColumns())
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
                .where(effectiveWhere)
                .orderBy(ISSUES.CREATED_AT.desc(), ISSUES.ID.desc())
                .limit(limit + 1)
                .fetch { record -> record.toIssueResponseWithType(projectKey) }

        val hasNext = fetched.size > limit
        val items = if (hasNext) fetched.take(limit) else fetched
        return IssueCursorPage(items = items, hasNext = hasNext)
    }

    /**
     * 프로젝트의 가시 활성 이슈 전체를 보안 등급 필터를 적용해 비페이지로 조회한다 (FR-BD-01 보드 카드용).
     *
     * 보드는 컬럼별 카드 배치를 위해 프로젝트 이슈 "전부"를 한 번에 받아야 한다 (페이지 없음).
     * [listWithType] 의 페이지/count/type-JOIN 오버헤드 없이, **동일한 [buildSecurityCondition]
     * WHERE 술어를 재사용**해 viewer 가 볼 수 없는 보안 등급 행을 SQL 수준에서 제외한다.
     * 새 보안 판정 경로를 만들지 않음으로써 멤버 타입 누락에 의한 제목 누출을 차단한다 (FR-NT-03 교훈).
     *
     * 단일 쿼리(ISSUES × PROJECTS × ISSUE_TYPES + EPIC self LEFT JOIN) — N+1 없음.
     * **ISSUE_TYPES INNER JOIN 은 카드 유형 표시용이다** (FR-UX-14 B2). FR-BD-01 당시에는 카드가
     * 유형을 안 그려 생략했으나, 카드 밀도 요구가 생기며 [listVisibleForTimeline] 과 같은 형태로
     * 되살렸다. `issues.type_id` 가 NOT NULL + FK(V005) 라 INNER 로도 행이 사라지지 않고,
     * N:1 조인이라 행 수도 늘지 않는다(LIMIT+1 truncated 판정 불변).
     * 라벨·추정은 `ISSUES.fields()` 에 이미 포함돼 별도 조인이 없다.
     * 카드 수 폭주를 막기 위해 [BOARD_CARD_FETCH_LIMIT] 로 상한을 둔다.
     * 정렬(컬럼 내 priority 등)은 도메인 배치 로직(agile-planning) 책임이므로 여기서는 created_at DESC 안정 정렬만 한다.
     *
     * ## EPIC self LEFT JOIN (FR-EP-01 D6/D7)
     *
     * EPIC 스윔레인 그룹화를 위해 `issues AS epic` self LEFT JOIN 으로 epicKey 를 단일 쿼리에서 추출한다.
     * LEFT JOIN 이므로 에픽 없는 이슈도 결과에 포함된다 — epicKey 는 null 로 반환.
     * [Issue] 도메인 객체에는 epicKey 필드가 없으므로 fetch 람다에서 직접 추출해 [BoardIssueEntry] 에 전달한다.
     * **동일 프로젝트 조건** (`epicAlias.PROJECT_ID = issues.project_id`) 필수 —
     * 에픽이 이동·삭제 등으로 다른 프로젝트에 잔류할 때 cross-project 데이터가 누출되지 않도록 한다 (P1-A 회귀방지).
     *
     * ## truncated 감지 (LIMIT+1 기법)
     *
     * `LIMIT + 1` 건을 조회해 결과가 `LIMIT + 1` 건이면 [BOARD_CARD_FETCH_LIMIT] 초과가 확인된 것이다.
     * 이 경우 마지막 1건을 버리고 truncated=true 를 반환해 소비측이 사용자에게 신호를 전달할 수 있게 한다.
     * 정확한 총 건수 COUNT 쿼리를 추가로 실행하지 않아 오버헤드가 없다.
     *
     * @param projectKey 프로젝트 접두사. 예: `"BTS"`.
     * @param actor 조회 행위자(viewer) UUID. 보안 등급 필터의 reporter/assignee 동적 조건에 사용.
     *   보안 민감 메서드이므로 기본값 없이 항상 명시 전달한다.
     * @param access actor 가 접근 가능한 보안 등급 집합. unrestricted=true 이면 WHERE 술어 미적용(빠른경로).
     * @param filter 보드 카드 필터 조건. [BoardCardFilter.isEmpty] 이면 필터 Condition 을 추가하지 않는다.
     * @return [BoardFetchResult]. entries 는 최대 [BOARD_CARD_FETCH_LIMIT] 건(이슈 + epicKey 쌍). truncated 는 초과 여부.
     */
    @Transactional(readOnly = true)
    fun listVisibleForBoard(
        projectKey: String,
        actor: UUID,
        access: IssueSecurityAccess,
        filter: BoardCardFilter = BoardCardFilter.EMPTY,
    ): BoardFetchResult {
        // 활성 프로젝트 술어 + 보안 등급 필터 — listWithType 과 동일 source.
        var where = buildActiveSecureWhere(projectKey, actor, access)

        // 비어 있지 않은 필터만 AND 로 결합 — 빈 필터(isEmpty)면 무필터(FR-BD-01 동일 경로, EC2 회귀 보존).
        val filterCondition = buildFilterCondition(filter)
        if (filterCondition != null) {
            where = where.and(filterCondition)
        }

        // EPIC self LEFT JOIN — EPIC 스윔레인용 epicKey 추출 (FR-EP-01 D6/D7).
        // Issue 도메인 객체에는 epicKey 필드가 없으므로 record 에서 직접 추출해야 한다 (CONCERN C1).
        // 동일 프로젝트 조건 필수 — cross-project epic 이동/stale 데이터 누출 차단 (P1-A 회귀방지).
        val epicAlias = ISSUES.`as`(EPIC_ALIAS)

        // LIMIT+1 조회: 결과가 LIMIT+1 건이면 truncated=true.
        val fetched =
            dsl.select(
                ISSUES.fields().toList() +
                    listOf(
                        epicAlias.KEY.`as`(EPIC_KEY_ALIAS),
                        ISSUE_TYPES.KEY.`as`(TYPE_KEY_ALIAS),
                    ),
            )
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
                .leftJoin(epicAlias).on(
                    ISSUES.EPIC_ID.eq(epicAlias.ID)
                        .and(epicAlias.DELETED_AT.isNull)
                        .and(epicAlias.PROJECT_ID.eq(ISSUES.PROJECT_ID)),
                )
                .where(where)
                .orderBy(ISSUES.CREATED_AT.desc())
                .limit(BOARD_CARD_FETCH_LIMIT + 1)
                .fetch { record ->
                    val issue = record.into(ISSUES).toIssue()
                    val epicKey = record.get(EPIC_KEY_ALIAS, String::class.java)
                    val typeKey =
                        record.get(TYPE_KEY_ALIAS, String::class.java)
                            ?: error("issue_types.key must not be null in board join result")
                    BoardIssueEntry(issue = issue, epicKey = epicKey, typeKey = typeKey)
                }

        val truncated = fetched.size > BOARD_CARD_FETCH_LIMIT
        val entries = if (truncated) fetched.take(BOARD_CARD_FETCH_LIMIT) else fetched
        return BoardFetchResult(entries = entries, truncated = truncated)
    }

    /**
     * [listVisibleForBoard] 단건 결과 — 이슈 + epic key + type key.
     *
     * [Issue] 도메인에는 epicKey 도 타입 키도 없으므로(타입은 `typeId` 만 보유),
     * record 추출 결과를 함께 전달한다 (CONCERN C1 · FR-UX-14 B2).
     *
     * @property issue 조회된 이슈 도메인 객체. 라벨·추정은 이 안에 이미 채워져 있다.
     * @property epicKey 에픽 이슈 키. 에픽 없는 이슈는 null. 동일 프로젝트 필터 적용됨.
     * @property typeKey 이슈 타입 키(`issue_types.key`). `issues.type_id` 가 NOT NULL + FK 라 항상 존재한다.
     */
    data class BoardIssueEntry(
        val issue: Issue,
        val epicKey: String?,
        val typeKey: String,
    )

    /**
     * [listVisibleForBoard] 반환 VO.
     *
     * @property entries 조회된 이슈+epicKey 목록. 최대 [BOARD_CARD_FETCH_LIMIT] 건.
     * @property truncated LIMIT 초과 여부. true 이면 일부 이슈가 누락됐음을 의미.
     */
    data class BoardFetchResult(
        val entries: List<BoardIssueEntry>,
        val truncated: Boolean,
    )

    /**
     * 프로젝트의 날짜 설정 이슈를 보안 등급 필터를 적용해 비페이지로 조회한다 (FR-TL-01 타임라인 뷰).
     *
     * startDate/dueDate 중 하나 이상 설정된 이슈만 반환한다. 날짜가 없는 이슈는 타임라인에 표시할 기간
     * 정보가 없으므로 WHERE 절에서 제외한다.
     *
     * [listVisibleForBoard] 와 동일한 [buildActiveSecureWhere] 보안 술어를 재사용해,
     * viewer 가 볼 수 없는 보안 등급 행을 SQL 수준에서 제외한다.
     *
     * 보드와의 차이.
     * - ISSUE_TYPES JOIN 필수 — 타임라인 카드에 issueType 문자열이 필요하다 (B1).
     * - WHERE: `(start_date IS NOT NULL OR due_date IS NOT NULL)` 날짜 필터 추가.
     * - LIMIT: [TIMELINE_FETCH_LIMIT](500) + 1 로 truncated 판정 (보드는 1000).
     *
     * EPIC self LEFT JOIN 패턴은 [listVisibleForBoard] 와 동일하게 동일 프로젝트 조건을 포함해
     * cross-project epic 데이터 누출을 차단한다 (P1-A 회귀방지).
     *
     * @param projectKey 프로젝트 접두사. 예: `"BTS"`.
     * @param viewerUserId 조회 행위자(viewer) UUID. 보안 등급 필터의 reporter/assignee 동적 조건에 사용.
     * @param access actor 가 접근 가능한 보안 등급 집합. unrestricted=true 이면 WHERE 술어 미적용(빠른경로).
     * @return [TimelineFetchResult]. entries 는 최대 [TIMELINE_FETCH_LIMIT] 건. truncated 는 초과 여부.
     */
    @Transactional(readOnly = true)
    fun listVisibleForTimeline(
        projectKey: String,
        viewerUserId: UUID,
        access: IssueSecurityAccess,
    ): TimelineFetchResult {
        // 기본 보안 술어 + 날짜 필터 — 날짜 없는 이슈는 타임라인 표시 불가
        val where =
            buildActiveSecureWhere(projectKey, viewerUserId, access)
                .and(ISSUES.START_DATE.isNotNull.or(ISSUES.DUE_DATE.isNotNull))

        // EPIC self LEFT JOIN — 동일 프로젝트 조건 필수 (cross-project 누출 차단, P1-A 회귀방지)
        val epicAlias = ISSUES.`as`(EPIC_ALIAS)

        // LIMIT+1 조회: 결과가 LIMIT+1 건이면 truncated=true
        val fetched =
            dsl.select(
                ISSUES.fields().toList() +
                    listOf(
                        epicAlias.KEY.`as`(EPIC_KEY_ALIAS),
                        ISSUE_TYPES.KEY.`as`(TYPE_KEY_ALIAS),
                    ),
            )
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
                .leftJoin(epicAlias).on(
                    ISSUES.EPIC_ID.eq(epicAlias.ID)
                        .and(epicAlias.DELETED_AT.isNull)
                        .and(epicAlias.PROJECT_ID.eq(ISSUES.PROJECT_ID)),
                )
                .where(where)
                // created_at 동률 시 key(유니크)로 보조 정렬 — 500/501 경계 truncation 행 단위 결정성 (M1)
                .orderBy(ISSUES.CREATED_AT.desc(), ISSUES.KEY.asc())
                .limit(TIMELINE_FETCH_LIMIT + 1)
                .fetch { record ->
                    val issue = record.into(ISSUES).toIssue()
                    val epicKey = record.get(EPIC_KEY_ALIAS, String::class.java)
                    val typeKey =
                        record.get(TYPE_KEY_ALIAS, String::class.java)
                            ?: error("issue_types.key must not be null in timeline join result")
                    TimelineIssueEntry(issue = issue, epicKey = epicKey, typeKey = typeKey)
                }

        val truncated = fetched.size > TIMELINE_FETCH_LIMIT
        val entries = if (truncated) fetched.take(TIMELINE_FETCH_LIMIT) else fetched
        return TimelineFetchResult(entries = entries, truncated = truncated)
    }

    /**
     * [listVisibleForTimeline] 단건 결과 — 이슈 + epic key + type key.
     *
     * [Issue] 도메인에는 epicKey/typeKey 필드가 없으므로 record 추출 결과를 함께 전달한다.
     *
     * @property issue 조회된 이슈 도메인 객체.
     * @property epicKey 에픽 이슈 키. 에픽 없는 이슈 또는 cross-project epic 은 null.
     * @property typeKey 이슈 유형 키. 예: `"epic"`, `"story"`, `"task"`.
     */
    data class TimelineIssueEntry(
        val issue: Issue,
        val epicKey: String?,
        val typeKey: String,
    )

    /**
     * [listVisibleForTimeline] 반환 VO.
     *
     * @property entries 조회된 타임라인 항목 목록. 최대 [TIMELINE_FETCH_LIMIT] 건.
     * @property truncated LIMIT 초과 여부. true 이면 일부 이슈가 누락됐음을 의미.
     */
    data class TimelineFetchResult(
        val entries: List<TimelineIssueEntry>,
        val truncated: Boolean,
    )

    /**
     * "활성 프로젝트 이슈" 술어와 [buildSecurityCondition] 보안 필터를 결합한 WHERE 조건을 만든다.
     *
     * [listWithType](페이지 조회)과 [listVisibleForBoard](보드 비페이지 조회)가 동일한 보안 필터를
     * 적용하도록 **단일 source** 로 추출했다. 보안 술어 변경 시 두 경로가 자동 동기화되어,
     * 한쪽 경로 누락에 의한 보안 등급 이슈 누출을 구조적으로 차단한다.
     *
     * @param projectKey 프로젝트 접두사. 예: `"BTS"`.
     * @param actor 조회 행위자 UUID. reporter/assignee 동적 조건에 사용.
     * @param access 접근 가능 보안 등급 집합. unrestricted=true 이면 보안 술어 미적용.
     * @return `projects.key = ? AND issues.deleted_at IS NULL` + (필요 시) 보안 술어 결합 [Condition].
     */
    private fun buildActiveSecureWhere(
        projectKey: String,
        actor: UUID,
        access: IssueSecurityAccess,
    ): Condition {
        val activeInProject =
            PROJECTS.KEY.eq(projectKey)
                .and(ISSUES.DELETED_AT.isNull)
        val securityCondition = buildSecurityCondition(actor, access)
        return if (securityCondition != null) {
            activeInProject.and(securityCondition)
        } else {
            activeInProject
        }
    }

    /**
     * keyset cursor seek 조건을 반환한다 (FR-API-01 Task 2).
     *
     * `ORDER BY created_at DESC, id DESC` 정렬의 cursor 위치 다음 행 필터.
     * row-value 비교 `(created_at, id) < (seekCreatedAt, seekId)` 를 명시 OR 전개로 표현한다.
     *
     * ```
     * (created_at < :seekCreatedAt)
     * OR (created_at = :seekCreatedAt AND id < :seekId)
     * ```
     *
     * id 는 tie-break 역할 — 동일 created_at 이슈 중 id DESC 순서로 seek 경계를 정한다.
     *
     * @param seekCreatedAt cursor 위치의 created_at.
     * @param seekId cursor 위치의 id.
     * @return seek 조건 [Condition].
     */
    private fun buildSeekCondition(
        seekCreatedAt: OffsetDateTime,
        seekId: UUID,
    ): Condition =
        ISSUES.CREATED_AT.lt(seekCreatedAt)
            .or(ISSUES.CREATED_AT.eq(seekCreatedAt).and(ISSUES.ID.lt(seekId)))

    /**
     * [listWithType] / [listWithTypeByCursor] 공통 — issue_types 타입 정보 SELECT 컬럼 목록.
     *
     * 두 메서드가 동일한 alias 상수([TYPE_ID_ALIAS]/[TYPE_KEY_ALIAS]/[TYPE_NAME_ALIAS])를
     * 단일 source 로 공유한다. alias 변경 시 양쪽 SELECT + record 읽기가 자동 동기화된다.
     *
     * @return [ISSUE_TYPES.ID]/[ISSUE_TYPES.KEY]/[ISSUE_TYPES.NAME] alias 컬럼 목록.
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildTypeSelectColumns(): List<org.jooq.Field<*>> =
        listOf(
            ISSUE_TYPES.ID.`as`(TYPE_ID_ALIAS),
            ISSUE_TYPES.KEY.`as`(TYPE_KEY_ALIAS),
            ISSUE_TYPES.NAME.`as`(TYPE_NAME_ALIAS),
        ) as List<org.jooq.Field<*>>

    /**
     * [listWithType] / [listWithTypeByCursor] 공통 — jOOQ Record 를 [IssueResponse] 로 변환한다.
     *
     * record 에 `issues.*` + [buildTypeSelectColumns] alias 컬럼이 포함되어 있어야 한다.
     *
     * @param projectKey 소속 프로젝트 키.
     * @return type 요약(typeId/typeKey/typeName) 이 채워진 [IssueResponse].
     */
    private fun org.jooq.Record.toIssueResponseWithType(projectKey: String): IssueResponse =
        IssueResponse.from(
            issue = into(ISSUES).toIssue(),
            projectKey = projectKey,
            typeInfo =
                IssueResponse.IssueTypeInfo(
                    id =
                        get(TYPE_ID_ALIAS, Long::class.java)
                            ?: error("issue_types.id must not be null in type join result"),
                    key =
                        get(TYPE_KEY_ALIAS, String::class.java)
                            ?: error("issue_types.key must not be null in type join result"),
                    name =
                        get(TYPE_NAME_ALIAS, String::class.java)
                            ?: error("issue_types.name must not be null in type join result"),
                ),
        )

    /**
     * [Pageable.sort] 를 [listWithType] ORDER BY 필드 목록으로 변환한다 (FR-UX-06 Phase 5 PR18 Task 1).
     *
     * 허용 필드는 [SORTABLE_COLUMNS] 화이트리스트로 제한한다. 허용목록 외 필드가 요청되면
     * 예외를 던지지 않고 기본 정렬(`created_at DESC`)로 대체한다.
     * 정렬을 지정하지 않은 경우(`Sort.isUnsorted`)에도 동일하게 기본 정렬을 반환한다(무회귀).
     *
     * `ISSUES.ID.desc()` 를 항상 마지막 tiebreaker(전순서 보장을 위한 동률 결정 기준)로 append 한다
     * (코드리뷰 C1) — 허용 필드(예: `priority`) 값이 동률인 행이 여러 건이면 tiebreaker 없이는
     * DB 가 순서를 보장하지 않아 페이지네이션 시 행 중복/누락이 생길 수 있다.
     *
     * @param sort 클라이언트 요청 정렬 기준(`Pageable.sort`).
     * @return jOOQ ORDER BY 필드 목록. 항상 마지막 원소는 `ISSUES.ID.desc()` tiebreaker.
     */
    private fun buildListOrderBy(sort: Sort): List<SortField<*>> {
        val orders =
            sort.mapNotNull { order ->
                val field = SORTABLE_COLUMNS[order.property] ?: return@mapNotNull null
                if (order.isAscending) field.asc() else field.desc()
            }
        return if (orders.isEmpty()) {
            listOf(ISSUES.CREATED_AT.desc(), ISSUES.ID.desc())
        } else {
            orders + ISSUES.ID.desc()
        }
    }

    /**
     * [BoardCardFilter] 를 SQL WHERE 술어 [Condition] 으로 변환한다.
     *
     * 필드 내 값들은 OR, 필드 간은 AND 로 결합한다([BoardCardFilter] 규칙 동일).
     *
     * @param filter 보드 카드 필터 조건.
     * @return 필터가 비어 있으면 `null`, 아니면 모든 술어를 AND 로 묶은 [Condition].
     */
    private fun buildFilterCondition(filter: BoardCardFilter): Condition? {
        if (filter.isEmpty()) return null
        return listOfNotNull(
            buildStatusCondition(filter),
            buildAssigneeCondition(filter),
            buildLabelCondition(filter),
            buildComponentCondition(filter),
        ).reduceOrNull { acc, cond -> acc.and(cond) }
    }

    /**
     * 상태 키 필터 술어를 생성한다.
     *
     * `current_state_key` 컬럼에 대한 IN 술어 — V004 소문자 컨벤션 기준 정확 매칭.
     * 같은 필드 내 값들은 IN 으로 OR 결합된다.
     *
     * 예: `statusKeys = ["open", "in_progress"]` → `current_state_key IN ('open', 'in_progress')`.
     *
     * @param filter 보드 카드 필터.
     * @return [BoardCardFilter.statusKeys] 가 비어 있으면 `null`, 아니면 `current_state_key IN (...)` [Condition].
     * @see buildFilterCondition
     */
    private fun buildStatusCondition(filter: BoardCardFilter): Condition? {
        if (filter.statusKeys.isEmpty()) return null
        return ISSUES.CURRENT_STATE_KEY.`in`(filter.statusKeys)
    }

    /**
     * 담당자 필터 술어를 생성한다.
     *
     * `assigneeIds` IN 술어와 `includeUnassigned` IS NULL 술어를 OR 로 결합한다.
     * 두 조건 모두 비어 있으면 `null` 을 반환한다.
     */
    private fun buildAssigneeCondition(filter: BoardCardFilter): Condition? {
        if (filter.assigneeIds.isEmpty() && !filter.includeUnassigned) return null
        var cond: Condition? = null
        if (filter.assigneeIds.isNotEmpty()) {
            cond = ISSUES.ASSIGNEE_ID.`in`(filter.assigneeIds)
        }
        if (filter.includeUnassigned) {
            cond = cond?.or(ISSUES.ASSIGNEE_ID.isNull) ?: ISSUES.ASSIGNEE_ID.isNull
        }
        return cond
    }

    /**
     * 라벨 필터 술어를 생성한다.
     *
     * PG 배열 overlap 연산자 `&&` 단일 술어 — GIN 인덱스(ix_issues_labels_gin) 활용.
     * `&&` 는 jOOQ 미지원 PG 전용 연산자이므로 DSL.condition + 바인드 파라미터로 표현.
     * 값은 ISSUES.LABELS 와 동일한 DataType(text[]) 으로 바인딩 — text[] && varchar[] 타입 미스매치 방지.
     */
    private fun buildLabelCondition(filter: BoardCardFilter): Condition? {
        if (filter.labels.isEmpty()) return null
        val labelArr: Array<String?> = filter.labels.map { it as String? }.toTypedArray()
        val labelVal = DSL.`val`(labelArr, ISSUES.LABELS.dataType)
        return DSL.condition("{0} && {1}", ISSUES.LABELS, labelVal)
    }

    /**
     * 컴포넌트 필터 술어를 생성한다.
     *
     * EXISTS 서브쿼리 — JOIN 절대 금지.
     * JOIN 을 사용하면 issue × component 카테시안이 LIMIT+1 truncated 감지와 카드 중복을 오염시킨다
     * (cartesian-product-jooq-leftjoin-count 교훈).
     */
    private fun buildComponentCondition(filter: BoardCardFilter): Condition? {
        if (filter.componentIds.isEmpty()) return null
        return DSL.exists(
            DSL.selectOne()
                .from(ISSUE_COMPONENTS)
                .where(
                    ISSUE_COMPONENTS.ISSUE_ID.eq(ISSUES.ID)
                        .and(ISSUE_COMPONENTS.COMPONENT_ID.`in`(filter.componentIds)),
                ),
        )
    }

    /**
     * [IssueSecurityAccess] 로부터 SQL WHERE 술어를 생성한다.
     *
     * [access] 가 unrestricted=true 이면 `null` 을 반환(필터 미적용 빠른경로).
     * unrestricted=false 이면 다음 OR 조합 Condition 을 반환한다.
     *
     * ```
     * security_level_id IS NULL
     * OR security_level_id IN (:staticLevelIds)        -- staticLevelIds 비어있지 않을 때만
     * OR (security_level_id IN (:reporterLevelIds)     -- reporterLevelIds 비어있지 않을 때만
     *     AND reporter_id = :actor)
     * OR (security_level_id IN (:assigneeLevelIds)     -- assigneeLevelIds 비어있지 않을 때만
     *     AND assignee_id = :actor)
     * ```
     *
     * 빈 IN 집합은 조건 자체를 생략해 `IN ()` SQL 구문 오류를 방지한다(jOOQ 는 빈 IN 을 false 로
     * 처리하지만, 조건 자체를 제거함으로써 불필요한 predicate 를 줄인다).
     *
     * @param actor 조회 행위자 UUID.
     * @param access 보안 등급 접근 결과 VO.
     * @return WHERE 에 추가할 [Condition]. unrestricted=true 이면 `null`.
     */
    private fun buildSecurityCondition(
        actor: UUID,
        access: IssueSecurityAccess,
    ): Condition? {
        if (access.unrestricted) return null

        // NULL 등급 — 항상 공개
        var condition: Condition = ISSUES.SECURITY_LEVEL_ID.isNull

        // static: USER/GROUP/PROJECT_ROLE 조건으로 actor 가 멤버인 등급 — 항상 노출
        if (access.staticLevelIds.isNotEmpty()) {
            condition = condition.or(ISSUES.SECURITY_LEVEL_ID.`in`(access.staticLevelIds))
        }

        // reporter: REPORTER 조건 등급 — actor 가 reporter 일 때만 노출
        if (access.reporterLevelIds.isNotEmpty()) {
            val reporterCondition =
                ISSUES.SECURITY_LEVEL_ID.`in`(access.reporterLevelIds)
                    .and(ISSUES.REPORTER_ID.eq(actor))
            condition = condition.or(reporterCondition)
        }

        // assignee: ASSIGNEE 조건 등급 — actor 가 assignee 일 때만 노출
        if (access.assigneeLevelIds.isNotEmpty()) {
            val assigneeCondition =
                ISSUES.SECURITY_LEVEL_ID.`in`(access.assigneeLevelIds)
                    .and(ISSUES.ASSIGNEE_ID.eq(actor))
            condition = condition.or(assigneeCondition)
        }

        return condition
    }

    companion object {
        /**
         * 보드 카드 비페이지 조회([listVisibleForBoard]) 상한.
         * 보드는 프로젝트 이슈 전부를 한 번에 받지만, 카드 수 폭주로 인한 메모리/렌더 부담을 막기 위해
         * 상한을 둔다. spec NFR(보드 카드 200건) 대비 여유를 둔 값이다.
         */
        internal const val BOARD_CARD_FETCH_LIMIT = 1000

        /**
         * 타임라인 비페이지 조회([listVisibleForTimeline]) 상한 (FR-TL-01 NFR 정렬).
         * startDate/dueDate 가 있는 이슈를 한 번에 반환하되, 메모리·렌더 부담 방지를 위해 상한을 둔다.
         */
        internal const val TIMELINE_FETCH_LIMIT = 500

        /**
         * 기본 unrestricted [IssueSecurityAccess] — [listWithType] 파라미터 기본값.
         * non-prod 환경(AlwaysAllowIssueSecurityDirectory)과 동일한 빠른경로를 보장한다.
         */
        private val UNRESTRICTED_ACCESS =
            IssueSecurityAccess(
                unrestricted = true,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        /**
         * [listWithType] 정렬(`Pageable.sort`) 허용목록 — FR-UX-06 Phase 5 PR18 Task 1.
         *
         * 프론트가 보낼 수 있는 정렬 토큰 5종만 허용한다 (정렬 필드 계약 — 백엔드/프론트 공유 토큰).
         * 화이트리스트 방식으로 [Map] 조회만 사용하고 SQL 문자열 결합은 하지 않는다 — 임의 컬럼명
         * 주입을 원천 차단한다. 허용목록 외 필드는 [buildListOrderBy] 가 예외 없이 기본 정렬
         * (`created_at DESC`)로 대체한다.
         *
         * `status` (워크플로우 상태) 는 의도적으로 제외한다 — 상태는 워크플로우 순서 개념이라
         * `current_state_key` 값 사전순 정렬이 사용자에게 의미 있는 순서를 주지 않는다.
         *
         * `key` 는 포함하되 문자열(사전식) 정렬이라는 점에 주의한다 — 예: `"TPRJ-10"` 이
         * `"TPRJ-2"` 보다 사전순으로 앞에 온다(숫자순이 아님). 기본 정렬은 `createdAt` 이고
         * 실사용에서 `key` 정렬 요청 빈도는 낮을 것으로 예상되어, 별도 숫자 시퀀스 컬럼을
         * 신설하지 않고 이 한계를 KDoc 으로 명시하는 선에서 허용한다.
         */
        private val SORTABLE_COLUMNS: Map<String, Field<*>> =
            mapOf(
                "key" to ISSUES.KEY,
                "summary" to ISSUES.SUMMARY,
                "priority" to ISSUES.PRIORITY,
                "createdAt" to ISSUES.CREATED_AT,
                "updatedAt" to ISSUES.UPDATED_AT,
            )

        // ── findByKeyWithType self LEFT JOIN alias 상수 ─────────────────────────
        // ISSUES.as(PARENT_ALIAS) 로 생성된 alias 테이블을 통해 부모 이슈 self 참조.
        // 본 컬럼명(key, summary)과 충돌하지 않도록 결과 컬럼에 명시 alias 를 붙인다.
        // parent 는 단건 조회([findByKeyWithType]) 경로에서만 채움 — 목록 경로는 null.

        /** issues self JOIN 에서 부모 이슈를 참조하는 테이블 alias. */
        private const val PARENT_ALIAS = "parent"

        /** issue_types.id 결과 컬럼 alias. */
        private const val TYPE_ID_ALIAS = "type_id"

        /** issue_types.key 결과 컬럼 alias. */
        private const val TYPE_KEY_ALIAS = "type_key"

        /** issue_types.name 결과 컬럼 alias. */
        private const val TYPE_NAME_ALIAS = "type_name"

        /** 부모 이슈 key 결과 컬럼 alias. */
        private const val PARENT_KEY_ALIAS = "parent_key"

        /** 부모 이슈 summary 결과 컬럼 alias. */
        private const val PARENT_SUMMARY_ALIAS = "parent_summary"

        // ── findByKeyWithType epic self LEFT JOIN alias 상수 ────────────────────
        // ISSUES.as(EPIC_ALIAS) 로 생성된 alias 테이블을 통해 에픽 이슈 self 참조.
        // parent alias 와 동형 구조 — 컬럼명 충돌 차단을 위해 "epic_" prefix 를 사용.

        /** issues self JOIN 에서 에픽 이슈를 참조하는 테이블 alias (FR-EP-01 Task 4). */
        private const val EPIC_ALIAS = "epic"

        /** 에픽 이슈 key 결과 컬럼 alias. */
        private const val EPIC_KEY_ALIAS = "epic_key"

        /** 에픽 이슈 summary 결과 컬럼 alias. */
        private const val EPIC_SUMMARY_ALIAS = "epic_summary"

        // ── searchByAql alias 상수 (FR-SR-02) ─────────────────────────────────

        /** searchByAql 쿼리에서 issue_types.key 를 담을 컬럼 alias. */
        private const val SEARCH_PROJECT_KEY_ALIAS = "search_project_key"

        /** searchByAql 쿼리에서 priority Short 기본값 — issues.priority DEFAULT 3 과 동기화. */
        private const val DEFAULT_SEARCH_PRIORITY_SHORT: Short = 3
    }

    /**
     * 이슈 담당자를 version bump 없이 갱신한다 (자동 배정 전용).
     *
     * `UPDATE issues SET assignee_id=? WHERE id=?` — version·updated_at 변경 없음.
     * [replaceComponents] 가 이미 version +1 을 수행한 뒤 호출하는 자동 배정 side-effect 전용이다.
     *
     * 사용자가 직접 담당자를 변경하는 경로(컨트롤러 → changeAssignee)는
     * 낙관락(OCC)+version bump 가 포함된 [updateAssignee] 를 사용해야 한다.
     *
     * @param issueId 이슈 UUID.
     * @param assigneeId 자동 배정할 담당자 UUID. null 이면 아무 작업도 하지 않는다.
     */
    @Transactional
    fun setAssignee(
        issueId: UUID,
        assigneeId: UUID?,
    ) {
        if (assigneeId == null) return
        log.debug("setAssignee issueId={} assigneeId={}", issueId, assigneeId)
        dsl.update(ISSUES)
            .set(ISSUES.ASSIGNEE_ID, assigneeId)
            .where(ISSUES.ID.eq(issueId))
            .execute()
    }

    /**
     * 이슈의 컴포넌트 연결 목록을 원자적으로 교체한다 (낙관락 OCC).
     *
     * version bump 를 먼저 시도하는 이유 — 낙관락(Optimistic Concurrency Control)으로
     * 동시 편집 충돌을 직렬화한다. bump 실패(stale version) 시 DELETE/INSERT 를 건너뛰어
     * partial update 없이 즉시 0 을 반환한다.
     *
     * 실행 순서.
     * ① [bumpVersionOrZero] — version+1 UPDATE. rowcount 0 이면 즉시 0 반환.
     * ② `DELETE FROM issue_components WHERE issue_id=?` — 기존 연결 전부 삭제.
     * ③ componentIds 가 비어 있지 않으면 batch INSERT (issue_id, component_id).
     *
     * @param key 이슈 키 (낙관락 WHERE 조건).
     * @param issueId DELETE / INSERT 에 사용할 이슈 UUID.
     * @param componentIds 교체 후 최종 컴포넌트 UUID 목록. 빈 리스트면 전부 삭제.
     * @param expectedVersion 현재 버전. DB 버전과 일치해야 업데이트가 실행된다.
     * @return 성공=1, 낙관락 충돌(stale version)=0.
     */
    @Transactional
    fun replaceComponents(
        key: IssueKey,
        issueId: UUID,
        componentIds: List<UUID>,
        expectedVersion: Long,
    ): Int {
        log.debug(
            "replaceComponents key={} issueId={} componentCount={} expectedVersion={}",
            key.value,
            issueId,
            componentIds.size,
            expectedVersion,
        )
        if (bumpVersionOrZero(key, expectedVersion) == 0) return 0

        dsl.deleteFrom(ISSUE_COMPONENTS)
            .where(ISSUE_COMPONENTS.ISSUE_ID.eq(issueId))
            .execute()

        if (componentIds.isNotEmpty()) {
            val insert = dsl.insertInto(ISSUE_COMPONENTS, ISSUE_COMPONENTS.ISSUE_ID, ISSUE_COMPONENTS.COMPONENT_ID)
            componentIds.forEach { componentId -> insert.values(issueId, componentId) }
            insert.execute()
        }

        return 1
    }

    /**
     * 이슈에 연결된 활성 컴포넌트 UUID 목록을 반환한다.
     *
     * `components.deleted_at IS NULL` 필터로 소프트삭제된 컴포넌트를 제외한다.
     * ISSUE_COMPONENTS × COMPONENTS 단순 JOIN — 이슈당 단일 컬렉션이라 cartesian product 없음.
     * 여러 컬렉션(예: 버전 목록)과 동시 JOIN 하면 cartesian product 위험이 생기므로 금지.
     *
     * @param issueId 조회할 이슈 UUID.
     * @return 활성 컴포넌트 UUID 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findActiveComponentIdsByIssue(issueId: UUID): List<UUID> {
        log.debug("findActiveComponentIdsByIssue issueId={}", issueId)
        return dsl.select(ISSUE_COMPONENTS.COMPONENT_ID)
            .from(ISSUE_COMPONENTS)
            .join(COMPONENTS).on(ISSUE_COMPONENTS.COMPONENT_ID.eq(COMPONENTS.ID))
            .where(ISSUE_COMPONENTS.ISSUE_ID.eq(issueId))
            .and(COMPONENTS.DELETED_AT.isNull)
            .fetch(ISSUE_COMPONENTS.COMPONENT_ID)
            .filterNotNull()
    }

    /**
     * 이슈 생성 시 컴포넌트 연결을 삽입한다 (생성 전용, version bump·OCC·DELETE 없음).
     *
     * 신규 이슈는 version=1 계약을 유지해야 하므로 [replaceComponents] 의 version bump + DELETE 흐름을
     * 사용하지 않는다. 이 메서드는 순수 INSERT INTO issue_components(issue_id, component_id) 만 수행한다.
     *
     * [componentIds] 가 비어 있으면 아무 행도 삽입하지 않는다.
     * 사용자 편집 경로(PATCH /components)는 반드시 [replaceComponents] 를 사용해야 한다.
     *
     * @param issueId 이슈 UUID. 이미 issues 테이블에 존재해야 한다.
     * @param componentIds 연결할 컴포넌트 UUID 목록. 빈 목록이면 skip.
     */
    @Transactional
    fun insertComponents(
        issueId: UUID,
        componentIds: List<UUID>,
    ) {
        if (componentIds.isEmpty()) return
        log.debug("insertComponents issueId={} componentCount={}", issueId, componentIds.size)
        val insert = dsl.insertInto(ISSUE_COMPONENTS, ISSUE_COMPONENTS.ISSUE_ID, ISSUE_COMPONENTS.COMPONENT_ID)
        componentIds.forEach { componentId -> insert.values(issueId, componentId) }
        insert.execute()
    }

    /**
     * 이슈의 "영향받는 버전" 연결 목록을 원자적으로 교체한다 (낙관락 OCC).
     *
     * [replaceComponents] 와 동형 패턴.
     * 실행 순서.
     * ① [bumpVersionOrZero] — version+1 UPDATE. rowcount 0 이면 즉시 0 반환.
     * ② `DELETE FROM issue_affects_versions WHERE issue_id=?` — 기존 연결 전부 삭제.
     * ③ versionIds 가 비어 있지 않으면 batch INSERT (issue_id, version_id).
     *
     * CONCERN-3: affects 와 fix 를 동시에 JOIN 하지 않는다 — 이 메서드는 issue_affects_versions 만 다룬다.
     * CONCERN-4: versions.status / deleted_at 필터 미적용 — 조인 테이블 행을 그대로 유지한다.
     *
     * @param key 이슈 키 (낙관락 WHERE 조건).
     * @param issueId DELETE / INSERT 에 사용할 이슈 UUID.
     * @param versionIds 교체 후 최종 버전 UUID 목록. 빈 리스트면 전부 삭제.
     * @param expectedVersion 현재 버전. DB 버전과 일치해야 업데이트가 실행된다.
     * @return 성공=1, 낙관락 충돌(stale version)=0.
     */
    @Transactional
    fun replaceAffectsVersions(
        key: IssueKey,
        issueId: UUID,
        versionIds: List<UUID>,
        expectedVersion: Long,
    ): Int {
        log.debug(
            "replaceAffectsVersions key={} issueId={} versionCount={} expectedVersion={}",
            key.value,
            issueId,
            versionIds.size,
            expectedVersion,
        )
        return replaceVersionLinks(
            key = key,
            issueId = issueId,
            versionIds = versionIds,
            expectedVersion = expectedVersion,
            table = ISSUE_AFFECTS_VERSIONS,
            issueIdField = ISSUE_AFFECTS_VERSIONS.ISSUE_ID,
            versionIdField = ISSUE_AFFECTS_VERSIONS.VERSION_ID,
        )
    }

    /**
     * 이슈의 "수정 예정 버전" 연결 목록을 원자적으로 교체한다 (낙관락 OCC).
     *
     * [replaceAffectsVersions] 와 완전 동형 — issue_fix_versions 테이블만 다르다.
     *
     * @param key 이슈 키 (낙관락 WHERE 조건).
     * @param issueId DELETE / INSERT 에 사용할 이슈 UUID.
     * @param versionIds 교체 후 최종 버전 UUID 목록. 빈 리스트면 전부 삭제.
     * @param expectedVersion 현재 버전. DB 버전과 일치해야 업데이트가 실행된다.
     * @return 성공=1, 낙관락 충돌(stale version)=0.
     */
    @Transactional
    fun replaceFixVersions(
        key: IssueKey,
        issueId: UUID,
        versionIds: List<UUID>,
        expectedVersion: Long,
    ): Int {
        log.debug(
            "replaceFixVersions key={} issueId={} versionCount={} expectedVersion={}",
            key.value,
            issueId,
            versionIds.size,
            expectedVersion,
        )
        return replaceVersionLinks(
            key = key,
            issueId = issueId,
            versionIds = versionIds,
            expectedVersion = expectedVersion,
            table = ISSUE_FIX_VERSIONS,
            issueIdField = ISSUE_FIX_VERSIONS.ISSUE_ID,
            versionIdField = ISSUE_FIX_VERSIONS.VERSION_ID,
        )
    }

    /**
     * 이슈에 연결된 "영향받는 버전" UUID 목록을 반환한다.
     *
     * CONCERN-3: fix 버전과 동시 JOIN 금지 — 독립 단일-컬렉션 SELECT.
     * CONCERN-4: versions 테이블을 JOIN 해 deleted_at IS NULL 인 행만 반환한다.
     *            status(ARCHIVED 등)는 필터하지 않는다 — ARCHIVED 버전 링크도 반환해야 한다.
     *
     * @param issueId 조회할 이슈 UUID.
     * @return 소프트삭제되지 않은 연결 버전 UUID 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findAffectsVersionIdsByIssue(issueId: UUID): List<UUID> {
        log.debug("findAffectsVersionIdsByIssue issueId={}", issueId)
        return dsl.select(ISSUE_AFFECTS_VERSIONS.VERSION_ID)
            .from(ISSUE_AFFECTS_VERSIONS)
            .join(VERSIONS).on(ISSUE_AFFECTS_VERSIONS.VERSION_ID.eq(VERSIONS.ID))
            .where(ISSUE_AFFECTS_VERSIONS.ISSUE_ID.eq(issueId))
            .and(VERSIONS.DELETED_AT.isNull)
            .fetch(ISSUE_AFFECTS_VERSIONS.VERSION_ID)
            .filterNotNull()
    }

    /**
     * 이슈에 연결된 "수정 예정 버전" UUID 목록을 반환한다.
     *
     * CONCERN-3: affects 버전과 동시 JOIN 금지 — 독립 단일-컬렉션 SELECT.
     * CONCERN-4: versions 테이블을 JOIN 해 deleted_at IS NULL 인 행만 반환한다.
     *            status(ARCHIVED 등)는 필터하지 않는다 — ARCHIVED 버전 링크도 반환해야 한다.
     *
     * @param issueId 조회할 이슈 UUID.
     * @return 소프트삭제되지 않은 연결 버전 UUID 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findFixVersionIdsByIssue(issueId: UUID): List<UUID> {
        log.debug("findFixVersionIdsByIssue issueId={}", issueId)
        return dsl.select(ISSUE_FIX_VERSIONS.VERSION_ID)
            .from(ISSUE_FIX_VERSIONS)
            .join(VERSIONS).on(ISSUE_FIX_VERSIONS.VERSION_ID.eq(VERSIONS.ID))
            .where(ISSUE_FIX_VERSIONS.ISSUE_ID.eq(issueId))
            .and(VERSIONS.DELETED_AT.isNull)
            .fetch(ISSUE_FIX_VERSIONS.VERSION_ID)
            .filterNotNull()
    }

    /**
     * 특정 버전을 fix version 으로 가진 활성 이슈 목록을 릴리즈 노트 생성용으로 반환한다.
     *
     * 단일 쿼리로 ISSUE_FIX_VERSIONS → ISSUES(deleted_at IS NULL) → ISSUE_TYPES 를 JOIN 한다.
     *
     * cartesian product 안전성 근거 (learnings PR#31).
     * - issue_fix_versions 는 (issue_id, version_id) 복합 PK. versionId 필터 시 이슈당 최대 1행.
     * - issue_types 는 issues.type_id 1:1 FK. 이슈당 1행.
     * → 총 이슈당 1행 보장.
     *
     * 정렬은 호출자(Service/Generator) 책임이므로 raw 반환.
     *
     * @param versionId 릴리즈 노트를 생성할 버전 UUID.
     * @return fix version 연결 활성 이슈의 [ReleaseNoteIssueRow] 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findFixVersionIssuesForReleaseNotes(versionId: UUID): List<ReleaseNoteIssueRow> {
        log.debug("findFixVersionIssuesForReleaseNotes versionId={}", versionId)
        return dsl.select(
            ISSUES.KEY,
            ISSUES.SUMMARY,
            ISSUES.RESOLUTION_ID,
            ISSUE_TYPES.ID.`as`("type_id"),
            ISSUE_TYPES.KEY.`as`("type_key"),
            ISSUE_TYPES.NAME.`as`("type_name"),
            ISSUE_TYPES.HIERARCHY_LEVEL,
        )
            .from(ISSUE_FIX_VERSIONS)
            .join(ISSUES).on(ISSUE_FIX_VERSIONS.ISSUE_ID.eq(ISSUES.ID))
            .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
            .where(ISSUE_FIX_VERSIONS.VERSION_ID.eq(versionId))
            .and(ISSUES.DELETED_AT.isNull)
            .fetch { record ->
                ReleaseNoteIssueRow(
                    key =
                        record.get(ISSUES.KEY)
                            ?: error("issues.key must not be null"),
                    summary =
                        record.get(ISSUES.SUMMARY)
                            ?: error("issues.summary must not be null"),
                    resolutionId = record.get(ISSUES.RESOLUTION_ID),
                    typeId =
                        record.get("type_id", Long::class.java)
                            ?: error("issue_types.id must not be null in join result"),
                    typeKey =
                        record.get("type_key", String::class.java)
                            ?: error("issue_types.key must not be null in join result"),
                    typeName =
                        record.get("type_name", String::class.java)
                            ?: error("issue_types.name must not be null in join result"),
                    hierarchyLevel =
                        record.get(ISSUE_TYPES.HIERARCHY_LEVEL)
                            ?: error("issue_types.hierarchy_level must not be null in join result"),
                )
            }
    }

    /**
     * 이슈에 연결된 컴포넌트 행을 모두 삭제한다 (이슈 이동 전용).
     *
     * 이슈 이동 시 기존 컴포넌트 연결을 제거하고 매핑된 대상 컴포넌트를 [insertComponents] 로 재삽입한다.
     * OCC version bump 없이 issueId 기준 DELETE — 이동 서비스가 moveIssue 에서 이미 version+1 을 수행한 뒤 호출한다.
     *
     * **이동 전용.** 일반 컴포넌트 편집(PATCH /components)은 반드시 [replaceComponents] 를 사용해야 한다.
     *
     * @param issueId 컴포넌트 연결을 삭제할 이슈 UUID.
     */
    @Transactional
    fun deleteComponentsByIssueId(issueId: UUID) {
        log.debug("deleteComponentsByIssueId issueId={}", issueId)
        dsl.deleteFrom(ISSUE_COMPONENTS)
            .where(ISSUE_COMPONENTS.ISSUE_ID.eq(issueId))
            .execute()
    }

    /**
     * 이슈에 연결된 버전 행을 모두 삭제한다 (이슈 이동 전용).
     *
     * [isFixVersion] 에 따라 issue_fix_versions 또는 issue_affects_versions 테이블에서 삭제한다.
     * OCC version bump 없이 issueId 기준 DELETE.
     *
     * @param issueId 버전 연결을 삭제할 이슈 UUID.
     * @param isFixVersion true 이면 fix-version, false 이면 affects-version.
     */
    @Transactional
    fun deleteVersionsByIssueId(
        issueId: UUID,
        isFixVersion: Boolean,
    ) {
        log.debug("deleteVersionsByIssueId issueId={} isFixVersion={}", issueId, isFixVersion)
        if (isFixVersion) {
            dsl.deleteFrom(ISSUE_FIX_VERSIONS)
                .where(ISSUE_FIX_VERSIONS.ISSUE_ID.eq(issueId))
                .execute()
        } else {
            dsl.deleteFrom(ISSUE_AFFECTS_VERSIONS)
                .where(ISSUE_AFFECTS_VERSIONS.ISSUE_ID.eq(issueId))
                .execute()
        }
    }

    /**
     * 이슈에 버전 연결을 삽입한다 (이슈 이동 전용).
     *
     * 이슈 이동 시 매핑된 대상 버전을 batch INSERT 한다.
     * OCC version bump 없음 — 이동 서비스가 moveIssue 에서 이미 version+1 을 수행한 뒤 호출한다.
     *
     * @param issueId 이슈 UUID.
     * @param versionIds 삽입할 버전 UUID 목록.
     * @param isFixVersion true 이면 fix-version, false 이면 affects-version.
     */
    @Transactional
    fun insertVersionLinks(
        issueId: UUID,
        versionIds: List<UUID>,
        isFixVersion: Boolean,
    ) {
        if (versionIds.isEmpty()) return
        log.debug("insertVersionLinks issueId={} count={} isFixVersion={}", issueId, versionIds.size, isFixVersion)
        if (isFixVersion) {
            val insert = dsl.insertInto(ISSUE_FIX_VERSIONS, ISSUE_FIX_VERSIONS.ISSUE_ID, ISSUE_FIX_VERSIONS.VERSION_ID)
            versionIds.forEach { versionId -> insert.values(issueId, versionId) }
            insert.execute()
        } else {
            val insert =
                dsl.insertInto(
                    ISSUE_AFFECTS_VERSIONS,
                    ISSUE_AFFECTS_VERSIONS.ISSUE_ID,
                    ISSUE_AFFECTS_VERSIONS.VERSION_ID,
                )
            versionIds.forEach { versionId -> insert.values(issueId, versionId) }
            insert.execute()
        }
    }

    /**
     * 이슈 보안 등급을 갱신한다 (낙관락 OCC UPDATE).
     *
     * WHERE key=? AND version=? AND deleted_at IS NULL 조건으로 UPDATE.
     * version 불일치(stale read) 시 영향 행 0 반환.
     *
     * null 전달 시 DB NULL 로 클리어하여 이슈를 공개 상태로 만든다.
     *
     * @param key 이슈 키.
     * @param securityLevelId 지정할 보안 등급 UUID. null 이면 등급 해제(공개).
     * @param expectedVersion 현재 버전. DB 버전과 일치해야 업데이트가 실행된다.
     * @return 업데이트된 행 수 (성공=1, 낙관락 충돌=0).
     */
    @Transactional
    fun updateSecurityLevel(
        key: IssueKey,
        securityLevelId: UUID?,
        expectedVersion: Long,
    ): Int {
        log.debug(
            "updateSecurityLevel key={} securityLevelId={} expectedVersion={}",
            key.value,
            securityLevelId,
            expectedVersion,
        )
        return dsl.update(ISSUES)
            .set(ISSUES.SECURITY_LEVEL_ID, securityLevelId)
            .set(ISSUES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(ISSUES.VERSION, expectedVersion + 1)
            .where(ISSUES.KEY.eq(key.value))
            .and(ISSUES.VERSION.eq(expectedVersion))
            .and(ISSUES.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 이슈의 부모를 설정하거나 해제한다.
     *
     * `UPDATE issues SET parent_id = ? WHERE id = ?` — version·updated_at 변경 없음.
     * cycle-free 보장은 호출자(서비스 계층)의 책임이다 (Task 6 collectAncestors 활용).
     *
     * @param issueId 대상 이슈 UUID.
     * @param parentId 지정할 부모 이슈 UUID. null 이면 최상위로 승격(부모 해제).
     */
    @Transactional
    fun updateParent(
        issueId: UUID,
        parentId: UUID?,
    ) {
        log.debug("updateParent issueId={} parentId={}", issueId, parentId)
        dsl.update(ISSUES)
            .set(ISSUES.PARENT_ID, parentId)
            .where(ISSUES.ID.eq(issueId))
            .execute()
    }

    /**
     * 이슈의 소속 에픽을 설정하거나 해제한다 (FR-EP-01 Task 4).
     *
     * `UPDATE issues SET epic_id = ? WHERE id = ?` — version·updated_at 변경 없음.
     * [updateParent] 와 동형 구조.
     *
     * @param childId 소속 에픽을 변경할 이슈 UUID.
     * @param epicId 지정할 에픽 이슈 UUID. null 이면 에픽 연결 해제.
     */
    @Transactional
    fun updateEpic(
        childId: UUID,
        epicId: UUID?,
    ) {
        log.debug("updateEpic childId={} epicId={}", childId, epicId)
        dsl.update(ISSUES)
            .set(ISSUES.EPIC_ID, epicId)
            .where(ISSUES.ID.eq(childId))
            .execute()
    }

    /**
     * 에픽 연결을 원자적으로 설정한다 — `epic_id IS NULL` 조건부 UPDATE (P1-B TOCTOU 핫픽스).
     *
     * `UPDATE issues SET epic_id = ? WHERE id = ? AND epic_id IS NULL`
     *
     * 동시 connect 요청 2건이 모두 in-memory 검사(child.epicId == null)를 통과하더라도
     * DB 레벨 WHERE epic_id IS NULL 조건이 원자 가드 역할을 한다.
     * 먼저 UPDATE 를 커밋한 쪽만 1행을 반환하고, 경합에 진 쪽은 0행을 반환한다.
     *
     * 호출자([com.bts.issue.epic.application.IssueEpicService.connect])는
     * 0행 반환 시 [com.bts.issue.epic.domain.EpicChildAlreadyLinkedException](409)를 던진다.
     *
     * disconnect 경로는 무조건 UPDATE 이므로 이 메서드를 사용하지 않는다.
     *
     * @param childId 에픽을 연결할 자식 이슈 UUID.
     * @param epicId 연결할 에픽 이슈 UUID.
     * @return 업데이트된 행 수. 성공=1, epic_id 이미 설정됨=0.
     */
    @Transactional
    fun linkEpic(
        childId: UUID,
        epicId: UUID,
    ): Int {
        log.debug("linkEpic childId={} epicId={}", childId, epicId)
        return dsl.update(ISSUES)
            .set(ISSUES.EPIC_ID, epicId)
            .where(ISSUES.ID.eq(childId))
            .and(ISSUES.EPIC_ID.isNull)
            .execute()
    }

    /**
     * 이슈 UUID 집합을 이슈 key 로 일괄 조회한다 (FR-EP-01 D6 G2 — changelog epic 라벨 박제용).
     *
     * `WHERE id IN (...)` 단일 쿼리로 N+1 없이 처리한다.
     * soft-deleted 이슈도 포함한다 — 이슈 키는 영구 보존되므로 (DATA.md §이슈 키 영구 보존)
     * deleted_at IS NOT NULL 이어도 key 가 살아 있으며, changelog 라벨 박제 목적상 반환해야 한다.
     *
     * @param ids 조회할 이슈 UUID 집합. 빈 집합이면 빈 맵을 반환한다.
     * @return UUID → 이슈 key 문자열 맵. 조회 결과 없는 id 는 맵에 포함되지 않는다.
     */
    @Transactional(readOnly = true)
    fun findKeysByIds(ids: Set<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        log.debug("findKeysByIds ids.size={}", ids.size)
        return dsl.select(ISSUES.ID, ISSUES.KEY)
            .from(ISSUES)
            .where(ISSUES.ID.`in`(ids))
            .fetch { record ->
                val id = requireNotNull(record.get(ISSUES.ID)) { "ISSUES.ID must not be null" }
                val key = requireNotNull(record.get(ISSUES.KEY)) { "ISSUES.KEY must not be null" }
                id to key
            }
            .toMap()
    }

    /**
     * 에픽에 속한 활성 자식 이슈를 보안 등급 필터와 함께 조회한다 (FR-EP-01 Task 4).
     *
     * [buildActiveSecureWhere] 보안 술어를 재사용하여 보안 등급이 허가되지 않은 자식을
     * SQL WHERE 단계에서 푸시다운 제거한다. 단건위임/N+1 없이 단일 쿼리로 처리한다.
     *
     * 보안 핵심 (security 리뷰 C1):
     * - accessibleLevels(보안등급)만 SQL로 거른다.
     * - VIEW_ISSUE 매트릭스 검사는 Service(Task5)의 BROWSE 진입 게이트 책임이다.
     *
     * @param epicId 에픽 이슈 UUID.
     * @param actor 조회 행위자 UUID. 보안 등급 필터의 reporter/assignee 동적 조건에 사용.
     * @param access actor 가 접근 가능한 보안 등급 집합.
     * @param projectKey 에픽이 속한 프로젝트 접두사. 예: `"BTS"`. PROJECTS JOIN 필수.
     * @return 에픽에 속한 활성 이슈 목록. created_at 오름차순 정렬(안정 정렬).
     */
    @Transactional(readOnly = true)
    fun findEpicChildren(
        epicId: UUID,
        actor: UUID,
        access: IssueSecurityAccess,
        projectKey: String,
    ): List<Issue> {
        log.debug("findEpicChildren epicId={} projectKey={}", epicId, projectKey)
        val where =
            buildActiveSecureWhere(projectKey, actor, access)
                .and(ISSUES.EPIC_ID.eq(epicId))
        return dsl.select(ISSUES.fields().toList())
            .from(ISSUES)
            .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
            .where(where)
            .orderBy(ISSUES.CREATED_AT.asc())
            .fetch { it.into(ISSUES).toIssue() }
    }

    /**
     * 지정 이슈의 모든 조상 UUID 를 parent_id 체인 순서로 반환한다 (자기 자신 제외).
     *
     * `WITH RECURSIVE` CTE 로 parent_id 를 타고 올라가며 최상위(parent_id IS NULL)까지 수집한다.
     * UNION(중복 제거)을 사용해 순환(cycle) 발생 시 동일 행을 두 번 방문하면 자동으로 탈출한다.
     * 실제 데이터에서는 Task 6 서비스 계층이 cycle 생성을 거부하므로 순환이 없다.
     *
     * 반환 순서: 직계 부모부터 최상위 조상 순 (재귀 CTE 탐색 순서와 일치).
     *
     * @param issueId 조상을 수집할 대상 이슈 UUID.
     * @return 조상 UUID 목록. 최상위 이슈이면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun collectAncestors(issueId: UUID): List<UUID> {
        log.debug("collectAncestors issueId={}", issueId)
        return dsl.fetch(SQL_COLLECT_ANCESTORS.trimIndent(), issueId)
            .mapNotNull { record -> record.get("ancestor_id", UUID::class.java) }
    }

    /**
     * 이슈를 다른 프로젝트로 이동한다 (단건 원자 UPDATE).
     *
     * 호출자는 반드시 [findByKeyForUpdate] 로 비관락을 획득한 뒤 이 메서드를 호출해야 한다.
     * version 불일치(OCC 충돌) 시 영향 행 0 반환 — 호출자가 [IssueVersionConflictException] 으로 처리한다.
     *
     * ### 변경 필드
     * - `project_id` → targetProjectId
     * - `key` → newKey
     * - `current_state_key` → targetStateKey
     * - `custom_fields` → filteredCustomFields (대상 프로젝트 정의에 있는 키만 + 추가 필드)
     * - `resolution_id` → resolvedResolutionId (targetStateIsDone=false 이면 null clear, C4)
     * - `parent_id` → null (외부 부모 끊기)
     * - `updated_at` → NOW()
     * - `version` → expectedVersion + 1
     *
     * ### 불변 필드
     * - `id` 는 절대 변경하지 않는다 (이슈 고유 식별자 보존 — DATA.md §2).
     *
     * @param oldKey 이동 전 이슈 키 (WHERE 조건 + OCC version 검증에 사용).
     * @param newKey 이동 후 이슈 키.
     * @param targetProjectId 대상 프로젝트 UUID.
     * @param targetStateKey 대상 프로젝트 워크플로우 상태 키.
     * @param resolvedResolutionId DONE 전환 시 유지할 resolution UUID.
     *   null 이면 resolution_id 를 NULL 로 clear 한다 (비DONE 이동, C4).
     * @param filteredCustomFields 대상 프로젝트 정의 키만 남긴 최종 커스텀 필드 맵.
     * @param expectedVersion 낙관락 버전. DB version 과 일치해야 UPDATE 가 실행된다.
     * @return 업데이트된 행 수 (성공=1, 낙관락 충돌=0).
     */
    @Transactional
    @Suppress("LongParameterList") // issues 이동은 단일 UPDATE 에 7개 필드가 불가분 — data class 도입 불필요
    fun moveIssue(
        oldKey: IssueKey,
        newKey: IssueKey,
        targetProjectId: UUID,
        targetStateKey: String,
        resolvedResolutionId: UUID?,
        filteredCustomFields: Map<String, Any?>,
        expectedVersion: Long,
        newParentId: UUID? = null,
    ): Int {
        log.debug(
            "moveIssue oldKey={} newKey={} targetProjectId={} targetStateKey={} expectedVersion={} newParentId={}",
            oldKey.value,
            newKey.value,
            targetProjectId,
            targetStateKey,
            expectedVersion,
            newParentId,
        )
        return dsl.update(ISSUES)
            .set(ISSUES.PROJECT_ID, targetProjectId)
            .set(ISSUES.KEY, newKey.value)
            .set(ISSUES.CURRENT_STATE_KEY, targetStateKey)
            .set(ISSUES.RESOLUTION_ID, resolvedResolutionId)
            .set(ISSUES.CUSTOM_FIELDS, filteredCustomFields.toJsonb())
            .set(ISSUES.PARENT_ID, newParentId)
            // P1-A cross-project 누출 차단: 이슈가 타 프로젝트로 이동하면 소속 에픽(원본 프로젝트 잔류)
            // 연결을 끊는다. 에픽 이슈(level=1)는 epic_id 가 없고, level=0 이슈만 epic_id 를 가지므로
            // 무조건 null 초기화해도 안전하다. parentId 초기화와 동형.
            .set(ISSUES.EPIC_ID, null as UUID?)
            .set(ISSUES.UPDATED_AT, java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC))
            .set(ISSUES.VERSION, expectedVersion + 1)
            .where(ISSUES.KEY.eq(oldKey.value))
            .and(ISSUES.VERSION.eq(expectedVersion))
            .and(ISSUES.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 이슈의 직접 자식 목록을 반환한다 (서브태스크 동반 이동용).
     *
     * `parent_id = parentId AND deleted_at IS NULL` 조건으로 활성 직접 자식만 조회한다.
     * 손자(자식의 자식)는 포함하지 않는다 — 1레벨 동반 이동 모델에 맞게 parent_id 1단계만 조회한다.
     * 결과는 id 오름차순 정렬 — 비관락 획득 시 데드락 회피를 위한 일관된 순서 보장.
     *
     * @param parentId 자식 목록을 조회할 부모 이슈 UUID.
     * @return 활성 직접 자식 [Issue] 목록. 자식 없으면 빈 목록.
     */
    @Transactional(readOnly = true)
    fun findDirectChildren(parentId: UUID): List<Issue> {
        log.debug("findDirectChildren parentId={}", parentId)
        return dsl.selectFrom(ISSUES)
            .where(ISSUES.PARENT_ID.eq(parentId))
            .and(ISSUES.DELETED_AT.isNull)
            .orderBy(ISSUES.ID.asc())
            .fetch()
            .map { record -> record.toIssue() }
    }

    /**
     * worklog 합산 재집계 + remaining 자동 차감 (no-bump 원자 UPDATE, FR-TT-01).
     *
     * worklog 추가(POST) 시 호출되는 경로. time_spent = 활성 worklogs SUM, remaining = remaining - decrement.
     * remaining 이 NULL 이면 decrement 를 적용하지 않고 NULL 을 유지한다 — PG GREATEST(0,NULL)=0 함정 회피.
     * version 은 변경하지 않는다(no-bump) — 동시 이슈 편집 OCC 충돌 회피 (learnings: no-bump 원칙).
     *
     * RETURNING time_spent_seconds, remaining_estimate_seconds 로 단일 쿼리에서 결과를 반환한다.
     *
     * @param issueId 대상 이슈 UUID.
     * @param decrementSeconds remaining 에서 차감할 초(양수). time_spent 재집계와 별개로 remaining 만 차감.
     * @return [RollupResult] — 갱신된 timeSpent 와 remaining.
     * @throws IllegalStateException RETURNING 이 null 인 경우 (이슈 미존재).
     */
    @Transactional
    fun recomputeTimeSpentWithDecrement(
        issueId: UUID,
        decrementSeconds: Int,
    ): RollupResult {
        log.debug("recomputeTimeSpentWithDecrement issueId={} decrementSeconds={}", issueId, decrementSeconds)
        // time_spent: 활성 worklogs SUM 서브쿼리 (deleted_at IS NULL, DATA.md §1.2 #7)
        val timeSpentSubquery =
            DSL.select(
                DSL.coalesce(DSL.sum(WORKLOGS.TIME_SPENT_SECONDS), DSL.value(0)),
            )
                .from(WORKLOGS)
                .where(WORKLOGS.ISSUE_ID.eq(issueId))
                .and(WORKLOGS.DELETED_AT.isNull)
        // remaining: NULL 이면 NULL 유지, non-null 이면 max(0, remaining - decrement) — CASE로 NULL 분기
        val remainingExpr =
            DSL.`when`(ISSUES.REMAINING_ESTIMATE_SECONDS.isNull, DSL.`val`(null as Int?))
                .otherwise(DSL.greatest(DSL.value(0), ISSUES.REMAINING_ESTIMATE_SECONDS.minus(decrementSeconds)))
        val record =
            dsl.update(ISSUES)
                .set(ISSUES.TIME_SPENT_SECONDS, timeSpentSubquery.asField<Int>())
                .set(ISSUES.REMAINING_ESTIMATE_SECONDS, remainingExpr)
                .set(ISSUES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(ISSUES.ID.eq(issueId))
                // 소프트삭제 이슈는 갱신 대상에서 제외 — updateFields 와 동일 불변식 (DATA.md §1.2 #7).
                // WorklogService.create 는 이미 findByKey(DELETED_AT IS NULL) 로 이슈를 resolve 하므로
                // 정상 경로에서는 이 조건이 0행을 반환하지 않는다.
                // 소프트삭제 레이스 윈도우(findByKey 통과 후 동시 삭제 커밋)에서만 0행 → error().
                .and(ISSUES.DELETED_AT.isNull)
                .returningResult(ISSUES.TIME_SPENT_SECONDS, ISSUES.REMAINING_ESTIMATE_SECONDS)
                .fetchOne()
                ?: error("recomputeTimeSpentWithDecrement: issueId=$issueId 에 해당하는 이슈가 없음(소프트삭제 또는 미존재)")
        return RollupResult(
            timeSpent = record.get(ISSUES.TIME_SPENT_SECONDS) ?: 0,
            remaining = record.get(ISSUES.REMAINING_ESTIMATE_SECONDS),
        )
    }

    /**
     * worklog 합산 재집계 + remaining 직접 지정 (no-bump 원자 UPDATE, FR-TT-01).
     *
     * worklog 추가 시 "manual remaining override" 경로. time_spent = SUM, remaining = newRemaining.
     * version 은 변경하지 않는다(no-bump).
     *
     * @param issueId 대상 이슈 UUID.
     * @param newRemaining 직접 지정할 잔여 추정 시간(초). 0 이상이어야 한다(호출자 책임).
     * @return [RollupResult] — 갱신된 timeSpent 와 remaining.
     * @throws IllegalStateException RETURNING 이 null 인 경우 (이슈 미존재).
     */
    @Transactional
    fun recomputeTimeSpentSetRemaining(
        issueId: UUID,
        newRemaining: Int,
    ): RollupResult {
        log.debug("recomputeTimeSpentSetRemaining issueId={} newRemaining={}", issueId, newRemaining)
        val timeSpentSubquery =
            DSL.select(
                DSL.coalesce(DSL.sum(WORKLOGS.TIME_SPENT_SECONDS), DSL.value(0)),
            )
                .from(WORKLOGS)
                .where(WORKLOGS.ISSUE_ID.eq(issueId))
                .and(WORKLOGS.DELETED_AT.isNull)
        val record =
            dsl.update(ISSUES)
                .set(ISSUES.TIME_SPENT_SECONDS, timeSpentSubquery.asField<Int>())
                .set(ISSUES.REMAINING_ESTIMATE_SECONDS, newRemaining)
                .set(ISSUES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(ISSUES.ID.eq(issueId))
                // 소프트삭제 이슈 갱신 방지 — recomputeTimeSpentWithDecrement 와 동일 불변식.
                .and(ISSUES.DELETED_AT.isNull)
                .returningResult(ISSUES.TIME_SPENT_SECONDS, ISSUES.REMAINING_ESTIMATE_SECONDS)
                .fetchOne()
                ?: error("recomputeTimeSpentSetRemaining: issueId=$issueId 에 해당하는 이슈가 없음(소프트삭제 또는 미존재)")
        return RollupResult(
            timeSpent = record.get(ISSUES.TIME_SPENT_SECONDS) ?: 0,
            remaining = record.get(ISSUES.REMAINING_ESTIMATE_SECONDS),
        )
    }

    /**
     * worklog 합산만 재집계, remaining 미변경 (no-bump 원자 UPDATE, FR-TT-01).
     *
     * worklog 편집(PATCH) 또는 삭제(DELETE) 시 호출되는 경로. time_spent = SUM, remaining 불변.
     * version 은 변경하지 않는다(no-bump).
     *
     * @param issueId 대상 이슈 UUID.
     * @return [RollupResult] — 갱신된 timeSpent 와 현재 remaining (미변경 값).
     * @throws IllegalStateException RETURNING 이 null 인 경우 (이슈 미존재).
     */
    @Transactional
    fun recomputeTimeSpent(issueId: UUID): RollupResult {
        log.debug("recomputeTimeSpent issueId={}", issueId)
        val timeSpentSubquery =
            DSL.select(
                DSL.coalesce(DSL.sum(WORKLOGS.TIME_SPENT_SECONDS), DSL.value(0)),
            )
                .from(WORKLOGS)
                .where(WORKLOGS.ISSUE_ID.eq(issueId))
                .and(WORKLOGS.DELETED_AT.isNull)
        val record =
            dsl.update(ISSUES)
                .set(ISSUES.TIME_SPENT_SECONDS, timeSpentSubquery.asField<Int>())
                .set(ISSUES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(ISSUES.ID.eq(issueId))
                // 소프트삭제 이슈 갱신 방지 — recomputeTimeSpentWithDecrement 와 동일 불변식.
                .and(ISSUES.DELETED_AT.isNull)
                .returningResult(ISSUES.TIME_SPENT_SECONDS, ISSUES.REMAINING_ESTIMATE_SECONDS)
                .fetchOne()
                ?: error("recomputeTimeSpent: issueId=$issueId 에 해당하는 이슈가 없음(소프트삭제 또는 미존재)")
        return RollupResult(
            timeSpent = record.get(ISSUES.TIME_SPENT_SECONDS) ?: 0,
            remaining = record.get(ISSUES.REMAINING_ESTIMATE_SECONDS),
        )
    }

    /**
     * 이슈의 직접 자식 수를 반환한다 (이슈 이동 전 자식 존재 여부 확인용).
     *
     * `parent_id = issueId AND deleted_at IS NULL` 조건으로 카운트한다.
     * cartesian product 없음 — 단순 WHERE 스칼라 집계.
     *
     * @param issueId 자식 수를 조회할 이슈 UUID.
     * @return 활성 자식 이슈 수.
     */
    @Transactional(readOnly = true)
    fun countDirectChildren(issueId: UUID): Int {
        log.debug("countDirectChildren issueId={}", issueId)
        return dsl.selectCount()
            .from(ISSUES)
            .where(ISSUES.PARENT_ID.eq(issueId))
            .and(ISSUES.DELETED_AT.isNull)
            .fetchOne(0, Int::class.java) ?: 0
    }

    /**
     * 활성 이슈(deleted_at IS NULL)의 라벨을 prefix 로 필터해 빈도 순으로 반환한다.
     *
     * 라벨 배열(labels TEXT[])을 UNNEST 해 행으로 전개한 뒤 COUNT(DISTINCT id) 로
     * "라벨이 등장하는 이슈 수"를 집계한다. GIN 인덱스 대신 전체 스캔 기반 집계이므로
     * 이슈 수가 많아지면 성능을 재검토해야 한다.
     *
     * @param prefix 라벨 접두사. 빈 문자열이면 ILIKE 절을 생략해 전체 top-N 을 반환한다.
     *   '%', '_', '\' 를 ESCAPE 문자로 처리해 리터럴 매칭을 보장한다.
     * @param limit 반환할 최대 라벨 수. 기본 [LABEL_AUTOCOMPLETE_DEFAULT_LIMIT].
     * @return 빈도(이슈 수) DESC, 동률 시 라벨 ASC 순으로 정렬된 라벨 목록.
     */
    @Transactional(readOnly = true)
    fun findLabelsByPrefix(
        prefix: String,
        limit: Int = LABEL_AUTOCOMPLETE_DEFAULT_LIMIT,
    ): List<String> {
        log.debug("findLabelsByPrefix prefix='{}' limit={}", prefix, limit)
        return if (prefix.isEmpty()) {
            dsl.fetch(SQL_LABELS_BY_PREFIX_ALL, limit)
        } else {
            val escaped = escapeIlikePrefix(prefix)
            dsl.fetch(SQL_LABELS_BY_PREFIX_FILTER, "$escaped%", limit)
        }.map { record -> record.get("label", String::class.java) }
    }

    /**
     * 지정 날짜에 마감 예정인 열린 이슈 목록을 반환한다 (마감임박 스캔).
     *
     * `due_date = date AND resolution_id IS NULL AND issues.deleted_at IS NULL` 조건 +
     * `projects.deleted_at IS NULL`(소프트삭제 프로젝트 이슈는 알림 대상 제외).
     * V026 부분 인덱스(`idx_issues_due_date_open`)가 issues 필터를 가속한다.
     *
     * projectKey 는 issues × projects INNER JOIN 으로 projects.key 를 직접 읽는다.
     * issues.key 파싱(substringBefore) 대신 JOIN — 다건 스캔에서 정확성이 우선.
     *
     * @param date 조회 기준 날짜 (= due_date).
     * @return [IssueDueScanItem] 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findOpenIssuesDueOn(date: LocalDate): List<IssueDueScanItem> {
        log.debug("findOpenIssuesDueOn date={}", date)
        return dsl.select(ISSUES.KEY, PROJECTS.KEY)
            .from(ISSUES)
            .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
            .where(openIssue())
            .and(PROJECTS.DELETED_AT.isNull)
            .and(ISSUES.DUE_DATE.eq(date))
            .fetch { record ->
                IssueDueScanItem(
                    issueKey = record.get(ISSUES.KEY) ?: error("issues.key must not be null"),
                    projectKey = record.get(PROJECTS.KEY) ?: error("projects.key must not be null"),
                )
            }
    }

    /**
     * 지정 날짜 이전에 마감이 지난 열린 이슈 목록을 반환한다 (지연 스캔).
     *
     * `due_date < today AND resolution_id IS NULL AND issues.deleted_at IS NULL` 조건 +
     * `projects.deleted_at IS NULL`(소프트삭제 프로젝트 이슈는 알림 대상 제외).
     * V026 부분 인덱스(`idx_issues_due_date_open`)가 issues 필터를 가속한다.
     *
     * @param today 오늘 날짜. due_date 가 이 날보다 과거인 이슈를 반환한다.
     * @return [IssueDueScanItem] 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findOpenOverdueIssues(today: LocalDate): List<IssueDueScanItem> {
        log.debug("findOpenOverdueIssues today={}", today)
        return dsl.select(ISSUES.KEY, PROJECTS.KEY)
            .from(ISSUES)
            .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
            .where(openIssue())
            .and(PROJECTS.DELETED_AT.isNull)
            .and(ISSUES.DUE_DATE.lt(today))
            .fetch { record ->
                IssueDueScanItem(
                    issueKey = record.get(ISSUES.KEY) ?: error("issues.key must not be null"),
                    projectKey = record.get(PROJECTS.KEY) ?: error("projects.key must not be null"),
                )
            }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** 활성 이슈를 key 로 필터하는 jOOQ Condition. */
    private fun activeByKey(key: IssueKey): Condition = ISSUES.KEY.eq(key.value).and(ISSUES.DELETED_AT.isNull)

    /**
     * 열린 이슈(미해결·미삭제) 공통 필터 jOOQ Condition.
     *
     * `resolution_id IS NULL AND deleted_at IS NULL` — 두 스캔 쿼리([findOpenIssuesDueOn],
     * [findOpenOverdueIssues])에서 공유하는 "열림" 조건. V026 부분 인덱스 predicate 와 일치.
     */
    private fun openIssue(): Condition = ISSUES.RESOLUTION_ID.isNull.and(ISSUES.DELETED_AT.isNull)

    /**
     * 이슈↔버전 조인 테이블의 연결 목록을 원자적으로 교체한다 (낙관락 OCC).
     *
     * [replaceAffectsVersions] / [replaceFixVersions] 공통 구현.
     * ① [bumpVersionOrZero] → 0 이면 즉시 0 반환.
     * ② DELETE FROM <table> WHERE issue_id=?.
     * ③ versionIds 가 비어 있지 않으면 batch INSERT.
     *
     * @param key 이슈 키 (낙관락 WHERE 조건).
     * @param issueId DELETE / INSERT 에 사용할 이슈 UUID.
     * @param versionIds 교체 후 최종 버전 UUID 목록.
     * @param expectedVersion 현재 버전.
     * @param table jOOQ 조인 테이블 참조 (ISSUE_AFFECTS_VERSIONS 또는 ISSUE_FIX_VERSIONS).
     * @param issueIdField 조인 테이블의 issue_id 필드.
     * @param versionIdField 조인 테이블의 version_id 필드.
     * @return 성공=1, 낙관락 충돌=0.
     */
    @Suppress("LongParameterList") // affects / fix 두 조인 테이블을 단일 헬퍼로 공유하기 위해 필요한 jOOQ 타입 파라미터
    private fun <R : Record> replaceVersionLinks(
        key: IssueKey,
        issueId: UUID,
        versionIds: List<UUID>,
        expectedVersion: Long,
        table: Table<R>,
        issueIdField: TableField<R, UUID?>,
        versionIdField: TableField<R, UUID?>,
    ): Int {
        if (bumpVersionOrZero(key, expectedVersion) == 0) return 0
        dsl.deleteFrom(table)
            .where(issueIdField.eq(issueId))
            .execute()
        if (versionIds.isNotEmpty()) {
            val insert = dsl.insertInto(table, issueIdField, versionIdField)
            versionIds.forEach { versionId -> insert.values(issueId, versionId) }
            insert.execute()
        }
        return 1
    }

    /**
     * 낙관락 version bump 를 시도하고 영향 행 수(성공=1, 충돌=0)를 반환한다.
     *
     * [replaceComponents] 의 첫 단계로 사용한다.
     * version 불일치 시 0 을 반환하며, 호출자는 이를 확인해 후속 연산을 생략해야 한다.
     */
    private fun bumpVersionOrZero(
        key: IssueKey,
        expectedVersion: Long,
    ): Int =
        dsl.update(ISSUES)
            .set(ISSUES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(ISSUES.VERSION, expectedVersion + 1)
            .where(ISSUES.KEY.eq(key.value))
            .and(ISSUES.VERSION.eq(expectedVersion))
            .and(ISSUES.DELETED_AT.isNull)
            .execute()

    // ── rank 관련 메서드 (FR-BL-01 Task 3) ─────────────────────────────────────

    /**
     * 이슈의 rank 를 version·updated_at 증가 없이 갱신한다 (no-bump, FR-BL-01).
     *
     * rank 변경은 드래그 빈번 운영 액션이므로 "최종 수정일" noise 를 막기 위해
     * updated_at 도 갱신하지 않는다 (worklog rollup no-bump 선례 동형 — learnings: no-bump-sidecar-version).
     *
     * UPDATE issues SET rank=? WHERE key=? AND deleted_at IS NULL
     *
     * @param key 대상 이슈 키.
     * @param rank 새 LexoRank 키 문자열 (소문자 a-z, 1~50자, 끝문자 != 'a').
     */
    @Transactional
    fun updateRank(
        key: IssueKey,
        rank: String,
    ) {
        log.debug("updateRank key={} rank={}", key.value, rank)
        dsl.update(ISSUES)
            .set(ISSUES.RANK, rank)
            .where(ISSUES.KEY.eq(key.value))
            .and(ISSUES.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 여러 이슈의 rank 를 단일 UPDATE ... FROM (VALUES ...) SQL 로 일괄 갱신한다 (rebalance 성능, FR-BL-01 NFR3).
     *
     * 단건 [updateRank] 를 1K 번 반복하면 DB 왕복 1K 회가 발생해 성능 목표(500ms)를 달성하기 어렵다.
     * PostgreSQL UPDATE ... FROM (VALUES ...) 로 단일 왕복에 처리한다.
     * no-bump 원칙 동일: version·updated_at 미증가.
     *
     * UPDATE issues SET rank = v.rank
     * FROM (VALUES (key1, rank1), ...) AS v(key, rank)
     * WHERE issues.key = v.key AND issues.deleted_at IS NULL
     *
     * @param entries (이슈 키, 새 rank 문자열) 목록.
     */
    @Transactional
    fun batchUpdateRanks(entries: List<Pair<IssueKey, String>>) {
        if (entries.isEmpty()) return
        log.debug("batchUpdateRanks count={}", entries.size)

        // VALUES 테이블: (key TEXT, rank TEXT) 로 row 목록을 인라인 테이블로 표현.
        // DSL.values() 는 vararg Row2 를 받으므로 spread operator 불가피.
        val rows = entries.map { (key, rank) -> DSL.row(DSL.`val`(key.value), DSL.`val`(rank)) }

        // DSL.values() 는 vararg Row2 를 받으므로 spread operator 불가피.
        @Suppress("SpreadOperator")
        val valuesTable = DSL.values(*rows.toTypedArray()).asTable("v", "key", "rank")

        dsl.update(ISSUES)
            .set(ISSUES.RANK, DSL.field(DSL.name("v", "rank"), String::class.java))
            .from(valuesTable)
            .where(ISSUES.KEY.eq(DSL.field(DSL.name("v", "key"), String::class.java)))
            .and(ISSUES.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 이슈의 rank 를 단건 조회한다.
     *
     * @param key 대상 이슈 키.
     * @return rank 문자열. 이슈가 없거나 소프트삭제된 경우 null.
     */
    @Transactional(readOnly = true)
    fun findRankByKey(key: IssueKey): String? =
        dsl.select(ISSUES.RANK)
            .from(ISSUES)
            .where(activeByKey(key))
            .fetchOne(ISSUES.RANK)

    /**
     * rebalance 대상 이슈 목록을 (key, rank) 쌍으로 반환한다.
     *
     * ORDER BY rank NULLS LAST, created_at, id 로 결정적 순서를 보장한다.
     * rank 가 NULL 인 이슈(lazy 미부여)는 맨 뒤에 생성순으로 배치된다 (spec 결정 #11).
     * 소프트삭제 이슈는 제외한다.
     *
     * @param projectId 재배포 대상 프로젝트 UUID.
     * @return (issueKey, rank) 쌍 목록. rank NULLS LAST, 동률 시 created_at, id 오름차순.
     *   rank 가 NULL 인 행은 key to null 로 포함된다.
     */
    @Transactional(readOnly = true)
    fun findRanksForRebalance(projectId: UUID): List<Pair<String, String?>> =
        dsl.select(ISSUES.KEY, ISSUES.RANK)
            .from(ISSUES)
            .where(ISSUES.PROJECT_ID.eq(projectId))
            .and(ISSUES.DELETED_AT.isNull)
            .orderBy(ISSUES.RANK.asc().nullsLast(), ISSUES.CREATED_AT.asc(), ISSUES.ID.asc())
            .fetch { record ->
                // key 는 NOT NULL 컬럼 — DB 무결성으로 null 비발생 보장.
                // rank 는 nullable (옵션 B) — null 허용.
                val issueKey = record.get(ISSUES.KEY) ?: error("issues.key must not be null")
                val issueRank = record.get(ISSUES.RANK)
                issueKey to issueRank
            }

    /**
     * 지정 이슈가 프로젝트에 속하고 뷰어에게 가시적인 활성 이슈인지 단건으로 확인한다 (FR-BL-02 Task 8).
     *
     * listVisibleForBoard 와 동일한 buildActiveSecureWhere 보안 술어를 재사용해
     * visibility SQL 로직 중복 없이 단건 EXISTS 쿼리를 수행한다. BOARD_CARD_FETCH_LIMIT 상한 없음.
     *
     * soft-deleted 이슈, 타 프로젝트 이슈, 뷰어가 볼 수 없는 보안 등급 이슈 모두 false 를 반환한다.
     *
     * @param projectKey 이슈가 속해야 하는 프로젝트 키. 예: "BTS".
     * @param issueKey 확인할 이슈 키 문자열. 예: "BTS-42".
     * @param actor 가시성을 판단할 사용자 UUID.
     * @param access actor 의 접근 가능 보안 등급 집합.
     * @return 가시 활성 이슈이면 true, 그 외 false.
     */
    @Transactional(readOnly = true)
    fun existsVisibleIssue(
        projectKey: String,
        issueKey: String,
        actor: UUID,
        access: IssueSecurityAccess,
    ): Boolean {
        val where =
            buildActiveSecureWhere(projectKey, actor, access)
                .and(ISSUES.KEY.eq(issueKey))
        return dsl.fetchExists(
            dsl.selectOne()
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .where(where),
        )
    }

    /**
     * 주어진 이슈 키 집합 중 [viewerUserId] 에게 가시적인 활성 이슈 키만 걸러 반환한다 (FR-RP-01 리뷰 C1).
     *
     * 번다운 집계(cross-BC)에서 프로젝트 BROWSE 통과 뷰어가 이슈별 보안 등급으로 차단된 기밀 이슈의
     * estimate/worklog 를 간접 추론하지 못하도록, 집계 대상 키를 먼저 가시 집합으로 좁히는 용도다.
     *
     * [existsVisibleIssue]/[listVisibleForTimeline] 과 동일한 [buildActiveSecureWhere] 보안 술어를
     * **재사용**한다 — deleted_at·projectKey·security 술어가 이미 포함되므로 별도 필터·복제 판정 경로가
     * 필요하지 않다(보안갭 방지). soft-deleted 이슈, 타 프로젝트 이슈, 뷰어가 볼 수 없는 보안 등급 이슈는
     * 결과에서 제외된다.
     *
     * @param issueKeys 가시성을 검사할 이슈 키 집합. 빈 집합이면 빈 집합을 즉시 반환한다(빈 `IN` 절 회피).
     * @param projectKey 이슈들이 속해야 하는 프로젝트 키. 예: `"BTS"`.
     * @param viewerUserId 가시성을 판단할 viewer UUID. 보안 술어의 reporter/assignee 동적 조건에 사용.
     * @param access viewer 가 접근 가능한 보안 등급 집합. unrestricted=true 이면 보안 술어 미적용(빠른경로).
     * @return 입력 키 중 가시 활성 이슈에 해당하는 키 부분집합.
     */
    @Transactional(readOnly = true)
    fun filterVisibleIssueKeys(
        issueKeys: Set<String>,
        projectKey: String,
        viewerUserId: UUID,
        access: IssueSecurityAccess,
    ): Set<String> {
        if (issueKeys.isEmpty()) return emptySet()
        return dsl.select(ISSUES.KEY)
            .from(ISSUES)
            .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
            .where(
                buildActiveSecureWhere(projectKey, viewerUserId, access)
                    .and(ISSUES.KEY.`in`(issueKeys)),
            )
            .fetch(ISSUES.KEY)
            .filterNotNull()
            .toSet()
    }

    /**
     * CFD(Cumulative Flow Diagram, 누적 흐름도) 집계용 활성·가시 이슈 원천 메타를 조회한다 (FR-RP-03 Task 2).
     *
     * [filterVisibleIssueKeys]/[existsVisibleIssue] 와 동일한 [buildActiveSecureWhere] 보안 술어를
     * **재사용**한다 — soft-deleted 이슈, 타 프로젝트 이슈, [viewerUserId] 가 접근 불가한 보안 등급
     * 이슈는 결과에서 자동 제외된다(복제 없음, isomorphic-clone 회귀 방지).
     *
     * @param projectKey CFD 를 집계할 프로젝트 키.
     * @param viewerUserId 가시성을 판단할 viewer UUID.
     * @param access viewer 가 접근 가능한 보안 등급 집합.
     * @return 활성·가시 이슈의 [CfdIssueSourceRow] 목록.
     */
    @Transactional(readOnly = true)
    fun fetchActiveVisibleIssuesForCfd(
        projectKey: String,
        viewerUserId: UUID,
        access: IssueSecurityAccess,
    ): List<CfdIssueSourceRow> =
        dsl.select(ISSUES.ID, ISSUES.TYPE_ID, ISSUES.CURRENT_STATE_KEY, ISSUES.CREATED_AT)
            .from(ISSUES)
            .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
            .where(buildActiveSecureWhere(projectKey, viewerUserId, access))
            .fetch { record ->
                CfdIssueSourceRow(
                    issueId = record.get(ISSUES.ID) ?: error("issues.id must not be null"),
                    typeId = record.get(ISSUES.TYPE_ID) ?: error("issues.type_id must not be null"),
                    currentStateKey =
                        record.get(ISSUES.CURRENT_STATE_KEY)
                            ?: error("issues.current_state_key must not be null"),
                    createdAt =
                        (record.get(ISSUES.CREATED_AT) ?: error("issues.created_at must not be null"))
                            .toInstant(),
                )
            }

    /**
     * Cycle/Lead Time 분포 집계용 활성·가시 이슈 원천 메타를 조회한다 (FR-RP-04 Task 4).
     *
     * [fetchActiveVisibleIssuesForCfd] 를 미러하되, 분포 응답 샘플에서 이슈를 식별해 노출할 수 있도록
     * [ISSUES].KEY 를 추가로 select 한다. [filterVisibleIssueKeys]/[existsVisibleIssue] 와 동일한
     * [buildActiveSecureWhere] 보안 술어를 **재사용**한다 — soft-deleted 이슈, 타 프로젝트 이슈,
     * [viewerUserId] 가 접근 불가한 보안 등급 이슈는 결과에서 자동 제외된다
     * (복제 없음, isomorphic-clone 회귀 방지).
     *
     * @param projectKey Cycle/Lead Time 을 집계할 프로젝트 키.
     * @param viewerUserId 가시성을 판단할 viewer UUID.
     * @param access viewer 가 접근 가능한 보안 등급 집합.
     * @return 활성·가시 이슈의 [CycleTimeIssueSourceRow] 목록.
     */
    @Transactional(readOnly = true)
    fun fetchActiveVisibleIssuesForCycleTime(
        projectKey: String,
        viewerUserId: UUID,
        access: IssueSecurityAccess,
    ): List<CycleTimeIssueSourceRow> =
        dsl.select(ISSUES.ID, ISSUES.KEY, ISSUES.TYPE_ID, ISSUES.CURRENT_STATE_KEY, ISSUES.CREATED_AT)
            .from(ISSUES)
            .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
            .where(buildActiveSecureWhere(projectKey, viewerUserId, access))
            .fetch { record ->
                CycleTimeIssueSourceRow(
                    issueId = record.get(ISSUES.ID) ?: error("issues.id must not be null"),
                    issueKey = record.get(ISSUES.KEY) ?: error("issues.key must not be null"),
                    typeId = record.get(ISSUES.TYPE_ID) ?: error("issues.type_id must not be null"),
                    currentStateKey =
                        record.get(ISSUES.CURRENT_STATE_KEY)
                            ?: error("issues.current_state_key must not be null"),
                    createdAt =
                        (record.get(ISSUES.CREATED_AT) ?: error("issues.created_at must not be null"))
                            .toInstant(),
                )
            }

    /**
     * 프로젝트 요약 집계용 활성·가시 이슈 원천 메타를 조회한다 (Jira 패리티 캠페인 PR ③).
     *
     * [fetchActiveVisibleIssuesForCfd] 를 미러하되, 요약 화면이 필요로 하는 집계 축
     * (우선순위·담당자·마감일·최종수정)을 추가로 select 한다.
     * [buildActiveSecureWhere] 보안 술어를 **재사용**하므로 soft-deleted 이슈, 타 프로젝트 이슈,
     * [viewerUserId] 가 접근 불가한 보안 등급 이슈는 자동으로 제외된다(복제 없음).
     *
     * ### 분포를 SQL 로 GROUP BY 하지 않는 이유
     * status·priority·type·assignee 를 한 쿼리로 묶으면 다중 조인이 건수를 부풀리고
     * (`IssueTypeRepository` PR#31 학습), 축마다 쿼리를 나누면 같은 보안 술어를 4번 반복하게 된다.
     * 조인 없는 단일 SELECT 로 **행 하나 = 이슈 하나** 불변식을 보장하고, 집계는 서비스가 한다.
     *
     * @param projectKey 요약을 집계할 프로젝트 키.
     * @param viewerUserId 가시성을 판단할 viewer UUID.
     * @param access viewer 가 접근 가능한 보안 등급 집합.
     * @return 활성·가시 이슈의 [SummaryIssueRow] 목록.
     */
    @Transactional(readOnly = true)
    fun fetchActiveVisibleIssuesForSummary(
        projectKey: String,
        viewerUserId: UUID,
        access: IssueSecurityAccess,
    ): List<SummaryIssueRow> =
        dsl.select(
            ISSUES.ID,
            ISSUES.TYPE_ID,
            ISSUES.CURRENT_STATE_KEY,
            ISSUES.PRIORITY,
            ISSUES.ASSIGNEE_ID,
            ISSUES.DUE_DATE,
            ISSUES.CREATED_AT,
            ISSUES.UPDATED_AT,
        )
            .from(ISSUES)
            .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
            .where(buildActiveSecureWhere(projectKey, viewerUserId, access))
            .fetch { record ->
                SummaryIssueRow(
                    issueId = record.get(ISSUES.ID) ?: error("issues.id must not be null"),
                    typeId = record.get(ISSUES.TYPE_ID) ?: error("issues.type_id must not be null"),
                    currentStateKey =
                        record.get(ISSUES.CURRENT_STATE_KEY)
                            ?: error("issues.current_state_key must not be null"),
                    // priority 는 SMALLINT(Short) — NOT NULL DEFAULT 3 이지만 !! 는 쓰지 않는다.
                    priority = (record.get(ISSUES.PRIORITY) ?: error("issues.priority must not be null")).toInt(),
                    assigneeId = record.get(ISSUES.ASSIGNEE_ID),
                    dueDate = record.get(ISSUES.DUE_DATE),
                    createdAt =
                        (record.get(ISSUES.CREATED_AT) ?: error("issues.created_at must not be null"))
                            .toInstant(),
                    updatedAt =
                        (record.get(ISSUES.UPDATED_AT) ?: error("issues.updated_at must not be null"))
                            .toInstant(),
                )
            }

    /**
     * 프로젝트 스코프의 status 전환 이력을 [since] 이후로 조회한다 (Jira 패리티 캠페인 PR ③).
     *
     * [com.bts.issue.statushistory.repository.StatusHistoryRepository.fetchStatusChanges] 는
     * 이슈 id 집합을 `IN` 절로 받지만, 프로젝트 전체를 대상으로 하면 `IN` 절이 이슈 수만큼 커진다.
     * 여기서는 `issues`·`projects` 를 조인해 [buildActiveSecureWhere] 를 그대로 걸어 같은 결과를
     * 얻는다 — **이력 경로가 보안 술어를 우회하지 않는 것이 핵심**이다.
     *
     * @param projectKey 이력을 조회할 프로젝트 키.
     * @param viewerUserId 가시성을 판단할 viewer UUID.
     * @param access viewer 가 접근 가능한 보안 등급 집합.
     * @param since 조회 하한 시각(**inclusive**).
     * @return `(issueId, changedAt, groupId)` 오름차순 [StatusChangeRow] 목록.
     */
    @Transactional(readOnly = true)
    fun fetchStatusChangesSinceForProject(
        projectKey: String,
        viewerUserId: UUID,
        access: IssueSecurityAccess,
        since: OffsetDateTime,
    ): List<StatusChangeRow> =
        dsl.select(
            ISSUE_CHANGE_GROUP.ISSUE_ID,
            ISSUE_CHANGE_GROUP.CREATED_AT,
            ISSUE_CHANGE_GROUP.ID,
            ISSUE_CHANGE_ITEM.FROM_VALUE,
            ISSUE_CHANGE_ITEM.TO_VALUE,
        )
            .from(ISSUE_CHANGE_GROUP)
            .join(ISSUE_CHANGE_ITEM).on(ISSUE_CHANGE_ITEM.GROUP_ID.eq(ISSUE_CHANGE_GROUP.ID))
            .join(ISSUES).on(ISSUES.ID.eq(ISSUE_CHANGE_GROUP.ISSUE_ID))
            .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
            .where(buildActiveSecureWhere(projectKey, viewerUserId, access))
            .and(ISSUE_CHANGE_ITEM.FIELD.eq(StatusHistoryRepository.FIELD_STATUS))
            .and(ISSUE_CHANGE_GROUP.CREATED_AT.ge(since))
            .orderBy(ISSUE_CHANGE_GROUP.ISSUE_ID, ISSUE_CHANGE_GROUP.CREATED_AT, ISSUE_CHANGE_GROUP.ID)
            .fetch { record ->
                StatusChangeRow(
                    issueId =
                        record.get(ISSUE_CHANGE_GROUP.ISSUE_ID)
                            ?: error("issue_change_group.issue_id must not be null"),
                    changedAt =
                        record.get(ISSUE_CHANGE_GROUP.CREATED_AT)?.toInstant()
                            ?: error("issue_change_group.created_at must not be null"),
                    groupId =
                        record.get(ISSUE_CHANGE_GROUP.ID)
                            ?: error("issue_change_group.id must not be null"),
                    fromValue = record.get(ISSUE_CHANGE_ITEM.FROM_VALUE),
                    toValue = record.get(ISSUE_CHANGE_ITEM.TO_VALUE),
                )
            }

    /**
     * 프로젝트 스코프 활동 피드(변경 이력)를 최신순으로 조회한다 (Jira 패리티 캠페인 PR ③).
     *
     * [fetchStatusChangesSinceForProject] 와 달리 필드를 `status` 로 한정하지 않고 라벨까지 싣는다.
     * [buildActiveSecureWhere] 를 재사용하므로 가시성 필터가 자동으로 걸린다.
     *
     * ### LIMIT 이 걸리는 단위 — **행이 아니라 그룹**
     * 행(변경 항목)에 `LIMIT` 을 걸면 경계에 걸친 그룹의 항목 일부만 실려 「담당자만 바꿨다」처럼
     * 사실과 다른 줄이 화면에 뜬다. 그래서 최신 그룹 id 를 서브쿼리로 [limit] 개 먼저 고르고,
     * 그 그룹들의 항목을 **전부** 가져온다. 반환 행 수는 [limit] 보다 클 수 있다.
     *
     * @param projectKey 활동을 조회할 프로젝트 키.
     * @param viewerUserId 가시성을 판단할 viewer UUID.
     * @param access viewer 가 접근 가능한 보안 등급 집합.
     * @param limit 조회할 최대 **변경 그룹** 수.
     * @return `created_at DESC, group id DESC` 정렬된 [ProjectActivityRow] 목록.
     */
    @Transactional(readOnly = true)
    fun fetchProjectActivity(
        projectKey: String,
        viewerUserId: UUID,
        access: IssueSecurityAccess,
        limit: Int,
    ): List<ProjectActivityRow> {
        // 1단계 — 가시 이슈의 최신 변경 그룹 id 를 limit 개 고른다(보안 술어는 여기서 건다).
        val latestGroupIds =
            dsl.select(ISSUE_CHANGE_GROUP.ID)
                .from(ISSUE_CHANGE_GROUP)
                .join(ISSUES).on(ISSUES.ID.eq(ISSUE_CHANGE_GROUP.ISSUE_ID))
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .where(buildActiveSecureWhere(projectKey, viewerUserId, access))
                .orderBy(ISSUE_CHANGE_GROUP.CREATED_AT.desc(), ISSUE_CHANGE_GROUP.ID.desc())
                .limit(limit)
                .fetch(ISSUE_CHANGE_GROUP.ID)
                .filterNotNull()

        // jOOQ 빈 IN 절 방어 — 변경 이력이 하나도 없는 프로젝트.
        if (latestGroupIds.isEmpty()) return emptyList()

        // 2단계 — 고른 그룹의 항목을 전부 가져온다(그룹이 잘리지 않는다).
        return dsl.select(
            ISSUE_CHANGE_GROUP.ID,
            ISSUE_CHANGE_GROUP.ISSUE_ID,
            ISSUE_CHANGE_GROUP.ISSUE_KEY,
            ISSUE_CHANGE_GROUP.ACTOR_ID,
            ISSUE_CHANGE_GROUP.CREATED_AT,
            ISSUE_CHANGE_ITEM.FIELD,
            ISSUE_CHANGE_ITEM.FROM_VALUE,
            ISSUE_CHANGE_ITEM.TO_VALUE,
            ISSUE_CHANGE_ITEM.FROM_LABEL,
            ISSUE_CHANGE_ITEM.TO_LABEL,
        )
            .from(ISSUE_CHANGE_GROUP)
            .join(ISSUE_CHANGE_ITEM).on(ISSUE_CHANGE_ITEM.GROUP_ID.eq(ISSUE_CHANGE_GROUP.ID))
            .where(ISSUE_CHANGE_GROUP.ID.`in`(latestGroupIds))
            .orderBy(ISSUE_CHANGE_GROUP.CREATED_AT.desc(), ISSUE_CHANGE_GROUP.ID.desc(), ISSUE_CHANGE_ITEM.ID)
            .fetch { record ->
                ProjectActivityRow(
                    groupId =
                        record.get(ISSUE_CHANGE_GROUP.ID)
                            ?: error("issue_change_group.id must not be null"),
                    issueId =
                        record.get(ISSUE_CHANGE_GROUP.ISSUE_ID)
                            ?: error("issue_change_group.issue_id must not be null"),
                    issueKey =
                        record.get(ISSUE_CHANGE_GROUP.ISSUE_KEY)
                            ?: error("issue_change_group.issue_key must not be null"),
                    // red seam — 아직 현재 키를 읽지 않는다(N1).
                    currentIssueKey =
                        record.get(ISSUE_CHANGE_GROUP.ISSUE_KEY)
                            ?: error("issue_change_group.issue_key must not be null"),
                    actorId = record.get(ISSUE_CHANGE_GROUP.ACTOR_ID),
                    createdAt =
                        record.get(ISSUE_CHANGE_GROUP.CREATED_AT)?.toInstant()
                            ?: error("issue_change_group.created_at must not be null"),
                    field =
                        record.get(ISSUE_CHANGE_ITEM.FIELD)
                            ?: error("issue_change_item.field must not be null"),
                    fromValue = record.get(ISSUE_CHANGE_ITEM.FROM_VALUE),
                    toValue = record.get(ISSUE_CHANGE_ITEM.TO_VALUE),
                    fromLabel = record.get(ISSUE_CHANGE_ITEM.FROM_LABEL),
                    toLabel = record.get(ISSUE_CHANGE_ITEM.TO_LABEL),
                )
            }
    }

    /**
     * ILIKE ESCAPE '\' 에서 안전하게 사용하기 위해 prefix 의 와일드카드 문자를 이스케이프한다.
     *
     * PostgreSQL ILIKE ESCAPE '\' 규칙.
     * - '\' 자체를 먼저 이스케이프해야 뒤에 오는 '%'/'_' 이스케이프가 이중으로 적용되지 않는다.
     * - '%' → '\%', '_' → '\_'
     *
     * @param prefix 원본 prefix 문자열.
     * @return 와일드카드가 리터럴화된 문자열 (뒤에 '%' 를 붙이기 전).
     */
    private fun escapeIlikePrefix(prefix: String): String =
        prefix
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    /**
     * AQL AST 를 jOOQ Condition 으로 재귀 변환하고 visibility 보안 술어를 최상위 AND 로 결합해
     * 이슈를 검색한다 (FR-SR-02 Task 5).
     *
     * ### 보안 불변식
     *
     * 최종 WHERE = `buildActiveSecureWhere(projectKey, actor, access) AND (AST Condition)`.
     * 사용자 AST 는 보안 술어 **밖에서 감쌀 수 없다** — OR/NOT 은 사용자 AST 내부에만 작용하므로
     * visibility 우회가 구조적으로 불가능하다.
     *
     * ### label 연산자 전략
     *
     * label 컬럼은 PostgreSQL TEXT[] 배열이다.
     * - `=` / `IN` → overlap(`&&`) — GIN 인덱스 활용.
     * - `~` (CONTAINS) → EXISTS(unnest ILIKE) — 배열 원소 부분 일치.
     * - `!=` → NOT overlap.
     * - `NOT_IN` → NOT overlap.
     *
     * ### priority 연산자 제약
     *
     * priority 는 SMALLINT(1..5). `~` 연산자는 어댑터([IssueSearchAdapter]) 에서 사전 거부한다.
     *
     * @param projectKey 검색 대상 프로젝트 키.
     * @param ast AQL 파서가 생성한 AST 루트 노드.
     * @param sort ORDER BY 절 정렬 기준 목록. 빈 목록이면 기본 정렬(created_at DESC).
     * @param actor 검색 요청 행위자 UUID. 보안 술어 조건 평가에 사용.
     * @param access actor 의 접근 가능 보안 등급 집합.
     * @param page 요청 페이지 번호(0-base).
     * @param size 요청 페이지 크기.
     * @return [IssueSearchPage] — 검색 결과 이슈 목록 + 총 건수 + 페이지 정보.
     */
    @Transactional(readOnly = true)
    @Suppress("LongParameterList") // AQL 검색 파라미터 집합 — IssueSearchQuery 커맨드 객체 파라미터화 불가(jOOQ 레이어)
    fun searchByAql(
        projectKey: String,
        ast: AqlNode,
        sort: List<AqlSort>,
        actor: UUID,
        access: IssueSecurityAccess,
        page: Int,
        size: Int,
    ): IssueSearchPage {
        // 보안 술어 최상위 AND — 사용자 AST 는 이 안에서만 작동하므로 우회 불가
        val secureWhere = buildActiveSecureWhere(projectKey, actor, access)
        val astCondition = buildAstCondition(ast)
        val effectiveWhere = secureWhere.and(astCondition)

        // 정렬 검증 먼저 — buildOrderBy 는 DB 호출 없는 순수 검증+필드 빌드.
        // early-return 뒤로 두면 total==0 일 때 검증을 건너뛰어 동일 잘못된 sort 가
        // 데이터 cardinality 에 따라 예외 여부가 달라지는 C1 계약 위반이 된다(B1 버그).
        val orderByFields = buildOrderBy(sort)

        // count 쿼리 — ISSUE_TYPES join 제외(불필요, cartesian product 방지)
        val total =
            dsl.selectCount()
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .where(effectiveWhere)
                .fetchOne(0, Long::class.java) ?: 0L

        if (total == 0L) {
            return IssueSearchPage.empty(page, size)
        }

        // content 쿼리 — ISSUE_TYPES join 으로 typeKey 포함
        val items =
            dsl.select(
                ISSUES.fields().toList() +
                    listOf(
                        ISSUE_TYPES.KEY.`as`(TYPE_KEY_ALIAS),
                        PROJECTS.KEY.`as`(SEARCH_PROJECT_KEY_ALIAS),
                    ),
            )
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
                .where(effectiveWhere)
                .orderBy(orderByFields)
                .limit(size)
                .offset(page.toLong() * size)
                .fetch { record ->
                    val issueRecord = record.into(ISSUES)
                    val priority = (issueRecord.priority ?: DEFAULT_SEARCH_PRIORITY_SHORT).toInt()
                    IssueSearchHit(
                        key = issueRecord.key ?: error("issues.key must not be null"),
                        summary = issueRecord.summary ?: error("issues.summary must not be null"),
                        typeKey =
                            record.get(TYPE_KEY_ALIAS, String::class.java)
                                ?: error("issue_types.key must not be null in join"),
                        currentStateKey =
                            issueRecord.currentStateKey
                                ?: error("issues.current_state_key must not be null"),
                        assigneeId = issueRecord.assigneeId,
                        priority = priority,
                        priorityName = com.bts.issue.domain.IssuePriority.fromNumber(priority).displayName,
                        projectKey =
                            record.get(SEARCH_PROJECT_KEY_ALIAS, String::class.java)
                                ?: error("projects.key must not be null in join"),
                        updatedAt =
                            (issueRecord.updatedAt ?: error("issues.updated_at must not be null"))
                                .toInstant(),
                    )
                }

        return IssueSearchPage(items = items, total = total, page = page, size = size)
    }

    /**
     * AQL AST 루트 노드를 재귀 순회하여 jOOQ [Condition] 으로 변환한다.
     *
     * AND/OR 은 이진 노드로 좌·우를 재귀 변환 후 결합한다.
     * NOT 은 단항 노드로 자식 Condition 을 [DSL.not] 으로 부정한다.
     * [AqlNode.Comparison] 은 필드별 SQL 전략을 적용한다.
     *
     * @param node 변환할 AST 노드.
     * @return jOOQ [Condition].
     */
    private fun buildAstCondition(node: AqlNode): Condition =
        when (node) {
            is AqlNode.And -> buildAstCondition(node.left).and(buildAstCondition(node.right))
            is AqlNode.Or -> buildAstCondition(node.left).or(buildAstCondition(node.right))
            is AqlNode.Not -> DSL.not(buildAstCondition(node.child))
            is AqlNode.Comparison -> buildComparisonCondition(node.field, node.op, node.values)
        }

    /**
     * 단일 비교 노드를 jOOQ [Condition] 으로 변환한다.
     *
     * 필드별 SQL 전략은 명세(FR-2, FR-3) 를 따른다.
     * SQL 문자열 결합 절대 금지 — 모든 값은 jOOQ 바인드 파라미터로 전달한다.
     *
     * @param field AQL 필드 식별자.
     * @param op AQL 비교 연산자.
     * @param values 비교 값 목록.
     * @return jOOQ [Condition].
     */
    @Suppress("CyclomaticComplexity") // 필드×연산자 매트릭스 — 분리 시 가독성 저하
    private fun buildComparisonCondition(
        field: AqlField,
        op: AqlOperator,
        values: List<AqlValue>,
    ): Condition {
        val fieldName = field.value.lowercase()
        return when (fieldName) {
            "status" -> buildStatusAqlCondition(op, values)
            "summary" -> buildSummaryCondition(op, values)
            "label" -> buildLabelAqlCondition(op, values)
            "priority" -> buildPriorityCondition(op, values)
            "text" -> buildTextSearchCondition(values.first().asString())
            else -> throw IllegalArgumentException("지원하지 않는 필드입니다: $fieldName")
        }
    }

    /**
     * `text ~` AQL 필드 → FTS + trigram 하이브리드 조건을 생성한다.
     *
     * ## 전략 (ADR docs/decisions/2026-06-26-fr-sr-04-korean-fts.md)
     *
     * 두 경로를 OR 로 결합한다.
     *
     * 1. **FTS 경로** — `issues.search_vector @@ plainto_tsquery('simple', ?)`.
     *    V032 STORED generated tsvector(summary + description 결합) + GIN 인덱스 활용.
     *    `simple` 설정은 조사 분리 없이 토큰화한다(zero-dep, SDD 10.2).
     *
     * 2. **trigram 경로** — `DSL.lower(ISSUES.SUMMARY).like(lowerPattern, '\\') OR ...DESCRIPTION...`.
     *    `DSL.lower(col).like(pattern.lowercase(), '\\')` 는 `lower("col") like ? escape '\'` 를 렌더한다.
     *    V031(`gin(lower(summary) gin_trgm_ops)`)·V032(`gin(lower(description) gin_trgm_ops)`) 표현식 인덱스와
     *    표현식이 정확히 일치해 Bitmap Index Scan 으로 실행된다.
     *    **주의**: `likeIgnoreCase` 는 native ILIKE(`~~*`)를 렌더하며, `~~*` 는 bare 컬럼 연산자라
     *    `lower(col)` 표현식 인덱스를 사용하지 못해 Seq Scan 이 발생한다 (B1 수정 근거, EXPLAIN 실측).
     *    조사 변형("이슈를"↔"이슈")·부분 문자열 매칭을 보완한다.
     *
     * ## SQL injection 방지
     *
     * 사용자 입력은 모두 jOOQ 바인드 파라미터([DSL.`val`] 바인드 사용, 위험한 [DSL.inline] 미사용)로
     * 전달한다. `%`/`_`/`\` 는 [escapeIlikePrefix]로 리터럴화한다.
     *
     * ## 빈/공백 검색어 (G5)
     *
     * 빈 문자열 또는 공백만 있는 검색어는 [DSL.falseCondition]을 반환해 결과 0을 보장한다.
     * `plainto_tsquery('simple', '')` 는 예외를 발생시키지 않지만 빈 tsquery 로 전 행 매칭되는
     * 의도치 않은 동작을 방지하기 위해 명시적으로 거부한다.
     *
     * @param strValue 사용자가 입력한 검색어 원본.
     * @return [DSL.falseCondition] (빈/공백) 또는 FTS OR trigram 복합 [Condition].
     */
    private fun buildTextSearchCondition(strValue: String): Condition {
        if (strValue.isBlank()) {
            return DSL.falseCondition()
        }
        val escaped = escapeIlikePrefix(strValue)
        val likePattern = "%$escaped%"
        val ftsCondition =
            DSL.condition(
                "issues.search_vector @@ plainto_tsquery('simple', {0})",
                DSL.`val`(strValue),
            )
        // DSL.lower(col).like(pattern.lowercase(), '\\') 는 lower("col") like ? escape '\' 를 렌더한다.
        // gin(lower(col) gin_trgm_ops) 표현식 인덱스(V031 summary / V032 description)와 표현식이 정확히 일치.
        // likeIgnoreCase 는 native ILIKE(~~*) 를 렌더해 표현식 인덱스를 사용하지 못한다 (B1 수정).
        val lowerPattern = likePattern.lowercase()
        val summaryTrigram = DSL.lower(ISSUES.SUMMARY).like(lowerPattern, '\\')
        val descriptionTrigram = DSL.lower(ISSUES.DESCRIPTION).like(lowerPattern, '\\')
        return ftsCondition.or(summaryTrigram).or(descriptionTrigram)
    }

    /**
     * status 필드 조건을 생성한다.
     *
     * status 는 current_state_key(text) — 정확 매칭. `=`/`!=`/`IN`/`NOT_IN` 지원.
     */
    private fun buildStatusAqlCondition(
        op: AqlOperator,
        values: List<AqlValue>,
    ): Condition {
        val strValues = values.map { it.asString() }
        return when (op) {
            AqlOperator.EQ -> ISSUES.CURRENT_STATE_KEY.eq(strValues.first())
            AqlOperator.NEQ -> ISSUES.CURRENT_STATE_KEY.ne(strValues.first())
            AqlOperator.IN -> ISSUES.CURRENT_STATE_KEY.`in`(strValues)
            AqlOperator.NOT_IN -> ISSUES.CURRENT_STATE_KEY.notIn(strValues)
            AqlOperator.CONTAINS ->
                throw IllegalArgumentException("status 필드에는 ~ 연산자를 사용할 수 없습니다.")
        }
    }

    /**
     * summary 필드 조건을 생성한다.
     *
     * summary 는 TEXT — `~` 는 lower(col) LIKE %v%, `=` 는 정확 매칭.
     * SQL injection 방지: `~` 와일드카드는 [escapeIlikePrefix] 이스케이프 후 [DSL.lower] + 바인드 파라미터로 처리한다.
     * `DSL.lower(col).like(pattern.lowercase())` 는 `lower("col") like ?` 를 렌더해
     * idx_issues_summary_trgm(`gin(lower(summary) gin_trgm_ops)`) 표현식 인덱스를 사용한다 (B1 수정).
     */
    private fun buildSummaryCondition(
        op: AqlOperator,
        values: List<AqlValue>,
    ): Condition {
        val strValue = values.first().asString()
        return when (op) {
            // DSL.lower(col).like(pattern.lowercase()) 로 gin(lower(summary) gin_trgm_ops) 표현식 인덱스 사용.
            // likeIgnoreCase 는 native ILIKE(~~*) 를 렌더해 표현식 인덱스를 사용하지 못한다 (B1 수정).
            AqlOperator.CONTAINS ->
                DSL.lower(ISSUES.SUMMARY).like(
                    "%${escapeIlikePrefix(strValue)}%".lowercase(),
                    '\\',
                )
            AqlOperator.EQ -> ISSUES.SUMMARY.eq(strValue)
            AqlOperator.NEQ -> ISSUES.SUMMARY.ne(strValue)
            AqlOperator.IN -> ISSUES.SUMMARY.`in`(values.map { it.asString() })
            AqlOperator.NOT_IN -> ISSUES.SUMMARY.notIn(values.map { it.asString() })
        }
    }

    /**
     * label 필드 조건을 생성한다.
     *
     * label 은 PostgreSQL TEXT[] 배열이므로 일반 스칼라 비교가 불가능하다.
     * - `=`/`IN`: overlap(`&&`) — "해당 라벨을 하나 이상 가짐".
     * - `!=`/`NOT_IN`: NOT overlap.
     * - `~`: EXISTS(unnest ILIKE) — 배열 원소 중 부분 일치.
     * 모든 값은 jOOQ DSL.val + dataType 바인딩으로 SQL injection 방지.
     */
    private fun buildLabelAqlCondition(
        op: AqlOperator,
        values: List<AqlValue>,
    ): Condition {
        val strValues = values.map { it.asString() }
        return when (op) {
            AqlOperator.EQ,
            AqlOperator.IN,
            -> {
                // overlap(&&): 배열 중 하나 이상 일치
                val labelArr = strValues.map { it as String? }.toTypedArray()
                val labelVal = DSL.`val`(labelArr, ISSUES.LABELS.dataType)
                DSL.condition("{0} && {1}", ISSUES.LABELS, labelVal)
            }
            AqlOperator.NEQ,
            AqlOperator.NOT_IN,
            -> {
                // NOT overlap
                val labelArr = strValues.map { it as String? }.toTypedArray()
                val labelVal = DSL.`val`(labelArr, ISSUES.LABELS.dataType)
                DSL.not(DSL.condition("{0} && {1}", ISSUES.LABELS, labelVal))
            }
            AqlOperator.CONTAINS -> {
                // EXISTS(unnest ILIKE) — 배열 원소 중 부분 일치.
                // ISSUES.LABELS 는 TEXT[] 배열이므로 unnest 로 전개한 뒤 ILIKE 를 적용한다.
                // jOOQ 인자 바인딩: {0} = 테이블 참조(issues.labels), {1} = 패턴 문자열 바인드.
                // SQL injection 방지: 패턴 값은 {1} jOOQ 바인드 파라미터로만 전달하고,
                //   와일드카드(% _)는 escapeIlikePrefix 로 리터럴화한 뒤 % 를 직접 추가한다.
                val pattern = "%${escapeIlikePrefix(strValues.first())}%"
                DSL.condition(
                    "EXISTS (SELECT 1 FROM unnest({0}) AS _lbl WHERE _lbl ILIKE {1})",
                    ISSUES.LABELS,
                    DSL.`val`(pattern),
                )
            }
        }
    }

    /**
     * priority 필드 조건을 생성한다.
     *
     * priority 는 SMALLINT(1..5) — `=`/`!=`/`IN`/`NOT_IN` 지원. `~` 는 어댑터에서 거부됨.
     * 값은 [AqlValue.Num] 정수 또는 [AqlValue.Str] 문자열(파서가 숫자 문자열로 전달하는 경우 대비).
     */
    private fun buildPriorityCondition(
        op: AqlOperator,
        values: List<AqlValue>,
    ): Condition {
        val shortValues = values.map { it.asShort() }
        return when (op) {
            AqlOperator.EQ -> ISSUES.PRIORITY.eq(shortValues.first())
            AqlOperator.NEQ -> ISSUES.PRIORITY.ne(shortValues.first())
            AqlOperator.IN -> ISSUES.PRIORITY.`in`(shortValues)
            AqlOperator.NOT_IN -> ISSUES.PRIORITY.notIn(shortValues)
            AqlOperator.CONTAINS ->
                throw IllegalArgumentException("priority 필드에는 ~ 연산자를 사용할 수 없습니다.")
        }
    }

    /**
     * [AqlSort] 목록을 jOOQ ORDER BY 필드 목록으로 변환한다.
     *
     * 빈 목록이면 기본 정렬(created_at DESC) 을 반환한다.
     * 지원 정렬 필드는 화이트리스트로 제한한다(SQL injection 방지).
     *
     * @param sort AQL 정렬 기준 목록.
     * @return jOOQ SortField 목록.
     */
    private fun buildOrderBy(sort: List<AqlSort>): List<org.jooq.SortField<*>> {
        if (sort.isEmpty()) return listOf(ISSUES.CREATED_AT.desc())
        return sort.map { aqlSort ->
            val jooqField =
                when (aqlSort.field.value.lowercase()) {
                    "status" -> ISSUES.CURRENT_STATE_KEY
                    "summary" -> ISSUES.SUMMARY
                    "priority" -> ISSUES.PRIORITY
                    "created_at" -> ISSUES.CREATED_AT
                    "updated_at" -> ISSUES.UPDATED_AT
                    else ->
                        throw IllegalArgumentException(
                            "정렬 불가 필드: ${aqlSort.field.value}",
                        )
                }
            if (aqlSort.direction == SortDirection.DESC) jooqField.desc() else jooqField.asc()
        }
    }
}

// ── file-level 확장 함수 ────────────────────────────────────────────────────────

/**
 * 도메인 [Issue] 를 insert 용 데이터 컨테이너로 변환한다.
 *
 * jOOQ UpdatableRecord 의 attach 없이 필드값만 추출하는 용도로 사용한다.
 * IssuesRecord 는 생성자에서 필요한 값만 받아 detached record 로 생성한다.
 */
private fun Issue.toInsertRecord(): IssuesRecord =
    IssuesRecord(
        id = id.value,
        key = key.value,
        projectId = projectId,
        summary = summary,
        reporterId = reporterId.value,
        currentStateKey = currentStateKey,
        version = version,
        typeId = typeId.value,
        description = description,
        priority = priority.toShort(),
        labels = labels.toDbArray(),
        environment = environment,
        impact = impact?.toShort(),
        assigneeId = assigneeId?.value,
        securityLevelId = securityLevelId,
        customFields = customFields.toJsonb(),
        parentId = parentId,
        // epic_id 는 insert 시 의도적으로 미영속한다.
        // 에픽 연결은 전용 connect 엔드포인트(IssueEpicService.connect → linkEpic)로만 이루어지며,
        // clone 시에도 에픽 복사를 금지한다 (Issue.create default null, clone 동형).
        startDate = startDate,
        dueDate = dueDate,
        targetDate = targetDate,
        // 추정 시간 3컬럼 (FR-TT-01, V027)
        originalEstimateSeconds = originalEstimateSeconds,
        timeSpentSeconds = timeSpentSeconds,
        remainingEstimateSeconds = remainingEstimateSeconds,
        // LexoRank 정렬 키 (FR-BL-01, V029). nullable 컬럼 (옵션 B, lazy 부여).
        // 신규 이슈는 rank=NULL 로 생성하고 드래그(rerank) 시 rank 를 부여한다 (spec 결정 #5).
        // toIssue 에서 rank(nullable String?) 로 역매핑된다.
        rank = rank,
    )

/**
 * jOOQ [IssuesRecord] 를 도메인 [Issue] 로 변환한다.
 *
 * DB 는 TIMESTAMPTZ 를 OffsetDateTime 으로 반환한다.
 * Instant 로 변환해 도메인 타입과 일치시킨다.
 *
 * labels: PostgreSQL text[] → Array<String?>? — null 요소는 filterNotNull 로 방어.
 * priority/impact: DB Short → 도메인 Int 타입 변환.
 */
private fun IssuesRecord.toIssue(): Issue {
    val recordId = id ?: error("issues.id must not be null after insert/select")
    return Issue(
        id = IssueId(recordId),
        key = IssueKey(key),
        projectId = projectId,
        summary = summary,
        reporterId = ActorId(reporterId),
        currentStateKey = currentStateKey,
        version = version ?: error("issues.version must not be null"),
        deletedAt = deletedAt?.toInstant(),
        createdAt = createdAt?.toInstant() ?: error("issues.created_at must not be null"),
        updatedAt = updatedAt?.toInstant() ?: error("issues.updated_at must not be null"),
        typeId = IssueTypeId(typeId ?: error("issues.type_id must not be null")),
        description = description,
        priority = (priority ?: DEFAULT_PRIORITY_SHORT).toInt(),
        labels = labels?.filterNotNull() ?: emptyList(),
        environment = environment,
        impact = impact?.toInt(),
        assigneeId = assigneeId?.let { ActorId(it) },
        resolutionId = resolutionId,
        securityLevelId = securityLevelId,
        customFields = customFields.toCustomFieldsMap(),
        // epic_id 컬럼 매핑 — null = 에픽 미연결 (FR-EP-01, V028)
        epicId = epicId,
        // parent_id 컬럼 매핑 — null = 최상위 이슈 (FR-LK-01, V021)
        parentId = parentId,
        // 일정 필드 3컬럼 — null = 미지정 (FR-PL-01, V025)
        startDate = startDate,
        dueDate = dueDate,
        targetDate = targetDate,
        // 추정 시간 3컬럼 — V027 (FR-TT-01). time_spent 는 NOT NULL DEFAULT 0 이지만 jOOQ 는 Int? 로 생성.
        originalEstimateSeconds = originalEstimateSeconds,
        timeSpentSeconds = timeSpentSeconds ?: 0,
        remainingEstimateSeconds = remainingEstimateSeconds,
        // LexoRank 정렬 키 — V029 (FR-BL-01). nullable (옵션 B, lazy 부여). 미부여 이슈는 null.
        rank = rank,
    )
}

/** DB DEFAULT 3 과 동기화된 priority Short 기본값 상수. DB NOT NULL DEFAULT 3 보장 방어용. */
private const val DEFAULT_PRIORITY_SHORT: Short = 3

/**
 * 도메인 [List]<[String]> 을 PostgreSQL text[] 에 저장하기 위한 [Array]<[String]?> 로 변환한다.
 *
 * 빈 리스트는 빈 배열로 변환한다 (DB DEFAULT '{}' 와 동일).
 * null 요소 없이 String 만 포함하므로 typed null-array 사용.
 */
private fun List<String>.toDbArray(): Array<String?> = map { it as String? }.toTypedArray()

// ── AqlValue 헬퍼 (FR-SR-02) ─────────────────────────────────────────────────

/**
 * [AqlValue] 를 문자열로 추출한다.
 *
 * [AqlValue.Str] 은 직접 반환, [AqlValue.Num] 은 정수를 문자열로 변환한다.
 * status / label / summary 필드에 사용한다.
 */
private fun AqlValue.asString(): String =
    when (this) {
        is AqlValue.Str -> value
        is AqlValue.Num -> value.toString()
    }

/** Short 변환 허용 범위 상수 — PostgreSQL SMALLINT 와 동일. */
private const val SHORT_RANGE_MIN: Int = Short.MIN_VALUE.toInt()
private const val SHORT_RANGE_MAX: Int = Short.MAX_VALUE.toInt()

/**
 * [AqlValue] 를 SMALLINT 에 맞는 [Short] 로 추출한다.
 *
 * priority 필드(SMALLINT 1..5)에 사용한다.
 * [AqlValue.Str] 는 정수로 파싱한다 — 파서가 숫자 토큰을 Str 로 전달하는 경우 대비.
 *
 * ### 범위 가드 (defense-in-depth)
 *
 * 파서([com.bts.search.aql.AqlParser])가 Short 범위 밖 값을 1차 차단하므로
 * 정상 경로에서는 이 함수에 위반 값이 도달하지 않는다.
 * 직접 AST 조립·미래 파서 변경 등을 대비해 silent wrap 대신 [IllegalArgumentException]을 던진다.
 *
 * @throws IllegalArgumentException 값을 정수로 파싱할 수 없거나 Short 범위($SHORT_RANGE_MIN..$SHORT_RANGE_MAX) 밖인 경우.
 */
internal fun AqlValue.asShort(): Short {
    val intVal: Int =
        when (this) {
            is AqlValue.Num -> value
            is AqlValue.Str ->
                value.toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "priority 값은 정수여야 합니다: $value",
                    )
        }
    require(intVal in SHORT_RANGE_MIN..SHORT_RANGE_MAX) {
        "priority 값 $intVal 이 Short 허용 범위(${SHORT_RANGE_MIN}..${SHORT_RANGE_MAX})를 벗어났습니다." +
            " 파서가 이미 차단하나 2차 방어로 거부합니다."
    }
    return intVal.toShort()
}

// ── JSONB ↔ Map 변환 헬퍼 ────────────────────────────────────────────────────

/**
 * custom_fields JSONB 직렬화/역직렬화에 사용하는 ObjectMapper 싱글턴.
 *
 * `ObjectMapper` 는 스레드 안전하므로 파일 레벨 val 로 공유한다.
 * Spring Bean 주입 없이 file-level 에서 사용하는 확장 함수에서 접근한다.
 */
private val CUSTOM_FIELDS_MAPPER = ObjectMapper()

/** custom_fields Map 타입 레퍼런스 — TypeReference 재사용으로 객체 생성 절감. */
private val CUSTOM_FIELDS_TYPE_REF = object : TypeReference<Map<String, Any?>>() {}

/**
 * 도메인 [Map]<[String], [Any]?> 를 jOOQ [JSONB] 로 직렬화한다.
 *
 * 빈 맵은 `'{}'` JSON 으로 직렬화된다 (DB DEFAULT `'{}'::jsonb` 와 일치).
 */
private fun Map<String, Any?>.toJsonb(): JSONB = JSONB.jsonb(CUSTOM_FIELDS_MAPPER.writeValueAsString(this))

/**
 * jOOQ [JSONB]? 를 도메인 [Map]<[String], [Any]?> 로 역직렬화한다.
 *
 * null 또는 빈 JSON 이면 빈 맵을 반환한다.
 * PostgreSQL JSONB 의 기본값 `'{}'` 은 역직렬화 후 빈 맵이 된다.
 */
private fun JSONB?.toCustomFieldsMap(): Map<String, Any?> {
    val json = this?.data()
    if (json.isNullOrBlank() || json == "{}") return emptyMap()
    return CUSTOM_FIELDS_MAPPER.readValue(json, CUSTOM_FIELDS_TYPE_REF)
}

/**
 * jOOQ UPDATE 체인에 [DatePatch] 3-state 를 적용하는 헬퍼 (FR-PL-01).
 *
 * [DatePatch.Set] → `SET field = value`, [DatePatch.Clear] → `SET field = NULL`,
 * [DatePatch.Unchanged] → SET 절 추가 없음.
 *
 * 반환값: 동일 [UpdateSetMoreStep] 인스턴스 (jOOQ 체인 연속 가능).
 */
private fun UpdateSetMoreStep<IssuesRecord>.applyDatePatch(
    field: TableField<IssuesRecord, LocalDate?>,
    patch: DatePatch,
): UpdateSetMoreStep<IssuesRecord> {
    when (patch) {
        is DatePatch.Set -> set(field, patch.value)
        DatePatch.Clear -> set(field, null as LocalDate?)
        DatePatch.Unchanged -> Unit
    }
    return this
}

/**
 * jOOQ UPDATE 체인에 [EstimatePatch] 3-state 를 적용하는 헬퍼 (FR-TT-01).
 *
 * [EstimatePatch.Set] → `SET field = value`, [EstimatePatch.Clear] → `SET field = NULL`,
 * [EstimatePatch.Unchanged] → SET 절 추가 없음.
 *
 * [applyDatePatch] 와 완전 동형 — 타입만 Int? 로 다르다.
 * 반환값: 동일 [UpdateSetMoreStep] 인스턴스 (jOOQ 체인 연속 가능).
 */
private fun UpdateSetMoreStep<IssuesRecord>.applyEstimatePatch(
    field: TableField<IssuesRecord, Int?>,
    patch: EstimatePatch,
): UpdateSetMoreStep<IssuesRecord> {
    when (patch) {
        is EstimatePatch.Set -> set(field, patch.value)
        EstimatePatch.Clear -> set(field, null as Int?)
        EstimatePatch.Unchanged -> Unit
    }
    return this
}
