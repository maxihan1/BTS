// IssueRepository — issues / projects 테이블 jOOQ DSL 접근. DATA.md §5, §6 준수.

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.PROJECTS
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
 * - [list] — 프로젝트별 활성 이슈 커서 기반 페이지 조회.
 * - [incrementKeySequence] — pg_advisory_xact_lock 으로 동시성 제어 후 key_sequence +1 RETURNING.
 */
@Repository
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
                .set(ISSUES.ID, issue.id.value)
                .set(ISSUES.KEY, issue.key.value)
                .set(ISSUES.PROJECT_ID, issue.projectId)
                .set(ISSUES.SUMMARY, issue.summary)
                .set(ISSUES.REPORTER_ID, issue.reporterId.value)
                .set(ISSUES.CURRENT_STATE_KEY, issue.currentStateKey)
                .set(ISSUES.VERSION, issue.version)
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
    fun findByKey(key: IssueKey): Issue? {
        return dsl.selectFrom(ISSUES)
            .where(ISSUES.KEY.eq(key.value))
            .and(ISSUES.DELETED_AT.isNull)
            .fetchOne()
            ?.toIssue()
    }

    /**
     * 활성 이슈를 key 로 조회하면서 비관락(SELECT FOR UPDATE) 을 획득한다.
     *
     * 호출자는 반드시 활성 트랜잭션 내에서 호출해야 한다.
     * 락은 트랜잭션 종료 시 자동 해제된다.
     *
     * @return 이슈가 없거나 소프트 삭제된 경우 null.
     */
    @Transactional
    fun findByKeyForUpdate(key: IssueKey): Issue? {
        return dsl.selectFrom(ISSUES)
            .where(ISSUES.KEY.eq(key.value))
            .and(ISSUES.DELETED_AT.isNull)
            .forUpdate()
            .fetchOne()
            ?.toIssue()
    }

    /**
     * 이슈 상태를 전이한다 (낙관락).
     *
     * WHERE key=? AND version=? AND deleted_at IS NULL 조건으로 업데이트.
     * version 불일치(stale read) 시 영향 행 0 반환.
     *
     * NOTE. [com.bts.workflow.domain.dto.TransitionPlan] (project-workflow BC) 직접 import 금지 —
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
            .where(ISSUES.KEY.eq(key.value))
            .and(ISSUES.DELETED_AT.isNull)
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
        val total =
            dsl.selectCount()
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .where(PROJECTS.KEY.eq(projectKey))
                .and(ISSUES.DELETED_AT.isNull)
                .fetchOne(0, Long::class.java) ?: 0L

        val content =
            dsl.select(ISSUES.fields().toList())
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .where(PROJECTS.KEY.eq(projectKey))
                .and(ISSUES.DELETED_AT.isNull)
                .orderBy(ISSUES.CREATED_AT.desc())
                .limit(pageable.pageSize)
                .offset(pageable.offset)
                .fetch { it.into(ISSUES).toIssue() }

        return PageImpl(content, pageable, total)
    }

    /**
     * 프로젝트의 key_sequence 를 1 증가시키고 새 값을 반환한다.
     *
     * pg_advisory_xact_lock 으로 동일 projectKey 에 대한 동시 호출을 직렬화한다.
     * 락은 현재 트랜잭션이 커밋/롤백될 때 자동 해제된다.
     *
     * @param projectKey 프로젝트 접두사 (예: "BTS").
     * @return 증가된 key_sequence 값.
     * @throws IllegalStateException 프로젝트가 존재하지 않는 경우.
     */
    @Transactional
    fun incrementKeySequence(projectKey: String): Long {
        // pg_advisory_xact_lock — 트랜잭션 범위 권고 락 (같은 projectKey 에 대한 동시 실행 직렬화)
        // hashtext() 는 PostgreSQL 내장 함수로 VARCHAR → INT4 해시값 생성
        dsl.execute("SELECT pg_advisory_xact_lock(hashtext(?))", "project:$projectKey")
        log.debug("incrementKeySequence acquired advisory lock for projectKey={}", projectKey)

        return dsl.update(PROJECTS)
            .set(PROJECTS.KEY_SEQUENCE, PROJECTS.KEY_SEQUENCE.plus(1))
            .where(PROJECTS.KEY.eq(projectKey))
            .and(PROJECTS.DELETED_AT.isNull)
            .returning(PROJECTS.KEY_SEQUENCE)
            .fetchOne()
            ?.keySequence
            ?: error("Project not found for key=$projectKey")
    }
}

// ── private 확장 함수 — IssuesRecord → Issue 도메인 변환 ──────────────────────────

/**
 * jOOQ [com.bts.issue.jooq.tables.records.IssuesRecord] 를 도메인 [Issue] 로 변환한다.
 *
 * DB 는 TIMESTAMPTZ 를 OffsetDateTime 으로 반환한다.
 * Instant 로 변환해 도메인 타입과 일치시킨다.
 */
private fun com.bts.issue.jooq.tables.records.IssuesRecord.toIssue(): Issue {
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
    )
}
