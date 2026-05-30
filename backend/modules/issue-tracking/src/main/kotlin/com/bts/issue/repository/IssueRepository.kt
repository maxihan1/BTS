// IssueRepository — issues / projects 테이블 jOOQ DSL 접근. DATA.md §5, §6 준수.

package com.bts.issue.repository

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.jooq.tables.records.IssuesRecord
import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.ISSUE_TYPES
import com.bts.issue.jooq.tables.references.PROJECTS
import com.bts.shared.issue.IssueTypeId
import org.jooq.Condition
import org.jooq.DSLContext
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
 */
@Repository
@Suppress("TooManyFunctions")
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
     * @param description Markdown 설명. null 이면 변경하지 않는다.
     * @param priority 우선순위 1..5. null 이면 변경하지 않는다.
     * @param labels 라벨 목록. null 이면 변경하지 않는다.
     * @param environment 재현 환경 설명. null 이면 변경하지 않는다.
     * @param impact 영향도 1..3. null 이면 변경하지 않는다.
     * @return 업데이트된 행 수 (성공=1, 낙관락 충돌=0).
     */
    @Transactional
    fun updateFields(
        key: IssueKey,
        summary: String?,
        typeId: IssueTypeId?,
        expectedVersion: Long,
        description: String? = null,
        priority: Int? = null,
        labels: List<String>? = null,
        environment: String? = null,
        impact: Int? = null,
    ): Int {
        log.debug("updateFields key={} typeId={} expectedVersion={}", key.value, typeId?.value, expectedVersion)
        return dsl.update(ISSUES)
            .set(ISSUES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(ISSUES.VERSION, expectedVersion + 1)
            .apply { if (summary != null) set(ISSUES.SUMMARY, summary) }
            .apply { if (typeId != null) set(ISSUES.TYPE_ID, typeId.value) }
            .apply { if (description != null) set(ISSUES.DESCRIPTION, description) }
            .apply { if (priority != null) set(ISSUES.PRIORITY, priority.toShort()) }
            .apply { if (labels != null) set(ISSUES.LABELS, labels.toDbArray()) }
            .apply { if (environment != null) set(ISSUES.ENVIRONMENT, environment) }
            .apply { if (impact != null) set(ISSUES.IMPACT, impact.toShort()) }
            .where(ISSUES.KEY.eq(key.value))
            .and(ISSUES.VERSION.eq(expectedVersion))
            .and(ISSUES.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 이슈 상태를 전이한다 (낙관락).
     *
     * WHERE key=? AND version=? AND deleted_at IS NULL 조건으로 업데이트.
     * version 불일치(stale read) 시 영향 행 0 반환.
     *
     * NOTE. [com.bts.shared.workflow.TransitionPlan] 직접 import 금지 —
     * BC 격리 원칙 (CLAUDE.md §BC 격리). 호출자 ApplicationService (T10) 가 TransitionPlan 을
     * 이 메서드 파라미터로 분해해서 전달한다.
     *
     * @param key 이슈 키.
     * @param toState 전이할 목표 워크플로우 상태 키.
     * @param expectedVersion 현재 버전. DB 버전과 일치해야 업데이트가 실행된다.
     * @return 업데이트된 행 수 (성공=1, 낙관락 충돌=0).
     */
    @Transactional
    fun applyTransition(
        key: IssueKey,
        toState: String,
        expectedVersion: Long,
    ): Int {
        log.debug("applyTransition key={} toState={} expectedVersion={}", key.value, toState, expectedVersion)
        return dsl.update(ISSUES)
            .set(ISSUES.CURRENT_STATE_KEY, toState)
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
     * 활성 이슈를 key 로 조회하고, issue_types 와 1:1 JOIN 하여 type 요약을 포함한 [IssueResponse] 를 반환한다.
     *
     * issues.type_id = issue_types.id 단건 JOIN — cartesian product 위험 없음 (learnings PR#31).
     *
     * @param key 조회할 이슈 키.
     * @return type 요약(typeId/typeKey/typeName) 이 포함된 [IssueResponse]. 이슈가 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findByKeyWithType(key: IssueKey): IssueResponse? =
        dsl.select(
            ISSUES.fields().toList() +
                listOf(
                    ISSUE_TYPES.ID.`as`("type_id"),
                    ISSUE_TYPES.KEY.`as`("type_key"),
                    ISSUE_TYPES.NAME.`as`("type_name"),
                ),
        )
            .from(ISSUES)
            .join(ISSUE_TYPES).on(ISSUES.TYPE_ID.eq(ISSUE_TYPES.ID))
            .where(activeByKey(key))
            .fetchOne()
            ?.let { record ->
                val issueRecord = record.into(ISSUES)
                IssueResponse.from(
                    issue = issueRecord.toIssue(),
                    projectKey = key.projectPrefix,
                    typeId =
                        record.get("type_id", Long::class.java)
                            ?: error("issue_types.id must not be null in join result"),
                    typeKey =
                        record.get("type_key", String::class.java)
                            ?: error("issue_types.key must not be null in join result"),
                    typeName =
                        record.get("type_name", String::class.java)
                            ?: error("issue_types.name must not be null in join result"),
                )
            }

    /**
     * 프로젝트별 활성 이슈 목록을 페이지 단위로 조회하고, issue_types JOIN 으로 type 요약을 포함한다.
     *
     * count 쿼리는 ISSUES × PROJECTS join 만 사용 (ISSUE_TYPES join 은 count에 불필요).
     * content 쿼리는 ISSUES × PROJECTS × ISSUE_TYPES — 단일 이슈 당 타입이 1건이므로 cartesian 없음.
     *
     * @param projectKey 프로젝트 접두사. 예: `"BTS"`.
     * @param pageable 페이지 정보.
     * @return [Page]<[IssueResponse]> — type 요약 포함.
     */
    @Transactional(readOnly = true)
    fun listWithType(
        projectKey: String,
        pageable: Pageable,
    ): Page<IssueResponse> {
        val activeInProject =
            PROJECTS.KEY.eq(projectKey)
                .and(ISSUES.DELETED_AT.isNull)

        // count 쿼리: ISSUE_TYPES join 제외 — 불필요한 join 으로 count 왜곡 방지
        val total =
            dsl.selectCount()
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .where(activeInProject)
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
                .where(activeInProject)
                .orderBy(ISSUES.CREATED_AT.desc())
                .limit(pageable.pageSize)
                .offset(pageable.offset)
                .fetch { record ->
                    IssueResponse.from(
                        issue = record.into(ISSUES).toIssue(),
                        projectKey = projectKey,
                        typeId =
                            record.get("type_id", Long::class.java)
                                ?: error("issue_types.id must not be null in join result"),
                        typeKey =
                            record.get("type_key", String::class.java)
                                ?: error("issue_types.key must not be null in join result"),
                        typeName =
                            record.get("type_name", String::class.java)
                                ?: error("issue_types.name must not be null in join result"),
                    )
                }

        return PageImpl(content, pageable, total)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** 활성 이슈를 key 로 필터하는 jOOQ Condition. */
    private fun activeByKey(key: IssueKey): Condition = ISSUES.KEY.eq(key.value).and(ISSUES.DELETED_AT.isNull)
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
