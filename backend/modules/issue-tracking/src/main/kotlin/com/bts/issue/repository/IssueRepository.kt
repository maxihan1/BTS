// IssueRepository — issues / projects 테이블 jOOQ DSL 접근. DATA.md §5, §6 준수.

package com.bts.issue.repository

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.jooq.tables.records.IssuesRecord
import com.bts.issue.jooq.tables.references.COMPONENTS
import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.ISSUE_AFFECTS_VERSIONS
import com.bts.issue.jooq.tables.references.ISSUE_COMPONENTS
import com.bts.issue.jooq.tables.references.ISSUE_FIX_VERSIONS
import com.bts.issue.jooq.tables.references.ISSUE_TYPES
import com.bts.issue.jooq.tables.references.PROJECTS
import com.bts.issue.jooq.tables.references.VERSIONS
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
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
     * 이슈 상태를 전이하고 resolution_id 를 함께 업데이트한다 (낙관락).
     *
     * WHERE key=? AND version=? AND deleted_at IS NULL 조건으로 업데이트.
     * version 불일치(stale read) 시 영향 행 0 반환.
     *
     * NOTE. [com.bts.shared.workflow.TransitionPlan] 직접 import 금지 —
     * BC 격리 원칙 (CLAUDE.md §BC 격리). 호출자 ApplicationService (T10) 가 TransitionPlan 을
     * 이 메서드 파라미터로 분해해서 전달한다.
     *
     * resolution_id 영속 정책 (B-4 근거).
     * - DONE 전이 시 non-null resolutionId 를 그대로 SET 한다.
     * - 비DONE 전이(resolutionId=null) 시 RESOLUTION_ID 를 NULL 로 clear 한다.
     * - 워크플로우 validator 가 DONE 진입 시 resolution 필수 불변식을 강제하므로(B7)
     *   이 메서드가 null 을 허용하는 것은 patch-merge-domain-bypass 위배 아님.
     *
     * @param key 이슈 키.
     * @param toState 전이할 목표 워크플로우 상태 키.
     * @param expectedVersion 현재 버전. DB 버전과 일치해야 업데이트가 실행된다.
     * @param resolutionId DONE 전이 시 설정할 Resolution UUID. null 이면 DB NULL 로 clear.
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
     * 활성 이슈를 key 로 조회하고, issue_types 와 1:1 JOIN, 부모 이슈와 self LEFT JOIN 하여
     * type 요약과 부모 요약을 포함한 [IssueResponse] 를 반환한다.
     *
     * issues.type_id = issue_types.id 단건 JOIN — cartesian product 위험 없음 (learnings PR#31).
     *
     * 부모 이슈 self LEFT JOIN.
     * - [PARENT_ALIAS] (`issues AS parent`) 로 issues 테이블 자기참조. 본 컬럼과 이름 충돌 차단.
     * - JOIN ON `issues.parent_id = parent.id AND parent.deleted_at IS NULL`.
     * - parent_id 가 null 이면 LEFT JOIN 결과가 모두 null → [IssueResponse.parent] = null.
     * - parent 컬럼은 [PARENT_KEY_ALIAS] / [PARENT_SUMMARY_ALIAS] 로 명시 alias 해 record 에서 읽는다.
     *
     * **parent 는 단건 조회([findByKeyWithType]) 경로에서만 채워진다.**
     * 목록 경로([listWithType])는 N+1/비용 회피를 위해 조인 없이 parent=null 반환한다. FR-LK-01 Task 1.
     *
     * @param key 조회할 이슈 키.
     * @return type 요약(typeId/typeKey/typeName) + 부모 요약(key/summary) 이 포함된 [IssueResponse].
     *   이슈가 없으면 null.
     */
    @Transactional(readOnly = true)
    @Suppress("CyclomaticComplexity") // 명시 alias + LEFT JOIN + null 분기 불가피
    fun findByKeyWithType(key: IssueKey): IssueResponse? {
        // issues self LEFT JOIN — 부모 이슈 key/summary 조회. PARENT_ALIAS 로 컬럼 충돌 차단.
        val parentAlias = ISSUES.`as`(PARENT_ALIAS)
        return dsl.select(
            ISSUES.fields().toList() +
                listOf(
                    ISSUE_TYPES.ID.`as`(TYPE_ID_ALIAS),
                    ISSUE_TYPES.KEY.`as`(TYPE_KEY_ALIAS),
                    ISSUE_TYPES.NAME.`as`(TYPE_NAME_ALIAS),
                    parentAlias.KEY.`as`(PARENT_KEY_ALIAS),
                    parentAlias.SUMMARY.`as`(PARENT_SUMMARY_ALIAS),
                ),
        )
            .from(ISSUES)
            .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
            .leftJoin(parentAlias).on(
                ISSUES.PARENT_ID.eq(parentAlias.ID)
                    .and(parentAlias.DELETED_AT.isNull),
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
     * @return [Page]<[IssueResponse]> — type 요약 포함.
     */
    @Transactional(readOnly = true)
    fun listWithType(
        projectKey: String,
        pageable: Pageable,
        actor: UUID,
        access: IssueSecurityAccess = UNRESTRICTED_ACCESS,
    ): Page<IssueResponse> {
        val activeInProject =
            PROJECTS.KEY.eq(projectKey)
                .and(ISSUES.DELETED_AT.isNull)

        // 보안 등급 WHERE 술어 — unrestricted=true 이면 null(필터 미적용).
        val securityCondition = buildSecurityCondition(actor, access)

        val baseWhere =
            if (securityCondition != null) {
                activeInProject.and(securityCondition)
            } else {
                activeInProject
            }

        // count 쿼리: ISSUE_TYPES join 제외 — 불필요한 join 으로 count 왜곡 방지
        val total =
            dsl.selectCount()
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .where(baseWhere)
                .fetchOne(0, Long::class.java) ?: 0L

        // content 쿼리: ISSUE_TYPES join 으로 type 요약 포함
        val content =
            dsl.select(
                ISSUES.fields().toList() +
                    listOf(
                        ISSUE_TYPES.ID.`as`("type_id"),
                        ISSUE_TYPES.KEY.`as`("type_key"),
                        ISSUE_TYPES.NAME.`as`("type_name"),
                    ),
            )
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
                .where(baseWhere)
                .orderBy(ISSUES.CREATED_AT.desc())
                .limit(pageable.pageSize)
                .offset(pageable.offset)
                .fetch { record ->
                    IssueResponse.from(
                        issue = record.into(ISSUES).toIssue(),
                        projectKey = projectKey,
                        typeInfo =
                            IssueResponse.IssueTypeInfo(
                                id =
                                    record.get("type_id", Long::class.java)
                                        ?: error("issue_types.id must not be null in join result"),
                                key =
                                    record.get("type_key", String::class.java)
                                        ?: error("issue_types.key must not be null in join result"),
                                name =
                                    record.get("type_name", String::class.java)
                                        ?: error("issue_types.name must not be null in join result"),
                            ),
                    )
                }

        return PageImpl(content, pageable, total)
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
     * @param resolvedResolutionId DONE 전이 시 유지할 resolution UUID.
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

    // ── private helpers ───────────────────────────────────────────────────────

    /** 활성 이슈를 key 로 필터하는 jOOQ Condition. */
    private fun activeByKey(key: IssueKey): Condition = ISSUES.KEY.eq(key.value).and(ISSUES.DELETED_AT.isNull)

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
        // parent_id 컬럼 매핑 — null = 최상위 이슈 (FR-LK-01, V021)
        parentId = parentId,
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
