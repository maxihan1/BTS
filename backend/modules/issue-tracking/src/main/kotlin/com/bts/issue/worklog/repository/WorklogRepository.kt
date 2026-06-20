// 워크로그 저장소 — worklogs 테이블 jOOQ DSL 접근 + 소프트 삭제 + time_spent SUM 집계 (FR-TT-01).

package com.bts.issue.worklog.repository

import com.bts.issue.jooq.tables.references.WORKLOGS
import com.bts.issue.worklog.domain.Worklog
import org.jooq.DSLContext
import org.jooq.Record
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 워크로그 저장소.
 *
 * jOOQ DSLContext 를 통해 `worklogs` 테이블에 접근한다.
 * 소프트 삭제 — `deleted_at` 설정. 모든 조회/SUM 은 `deleted_at IS NULL` 필터를 적용한다
 * (DATA.md §1.2 #7, §3).
 *
 * ## 메서드 목록
 * - [insert] — 워크로그 1건 삽입.
 * - [findByIssueId] — issueId 기준 활성 워크로그 목록 (`started_at` DESC).
 * - [findById] — id 기준 단건 조회.
 * - [update] — 소요 시간·시작 시각·코멘트 수정 및 `updated_at` 갱신.
 * - [softDelete] — `deleted_at` 설정 (소프트 삭제).
 */
@Repository
class WorklogRepository(
    private val dsl: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 워크로그 1건을 `worklogs` 테이블에 삽입한다.
     *
     * @param worklog 삽입할 워크로그 도메인 객체.
     */
    @Transactional
    fun insert(worklog: Worklog) {
        log.debug("insert worklog id={} issueId={}", worklog.id, worklog.issueId)
        dsl.insertInto(WORKLOGS)
            .set(WORKLOGS.ID, worklog.id)
            .set(WORKLOGS.ISSUE_ID, worklog.issueId)
            .set(WORKLOGS.AUTHOR_ID, worklog.authorId)
            .set(WORKLOGS.TIME_SPENT_SECONDS, worklog.timeSpentSeconds)
            .set(WORKLOGS.STARTED_AT, worklog.startedAt.toOffsetDateTime())
            .set(WORKLOGS.COMMENT, worklog.comment)
            .set(WORKLOGS.CREATED_AT, worklog.createdAt.toOffsetDateTime())
            .set(WORKLOGS.UPDATED_AT, worklog.updatedAt.toOffsetDateTime())
            .execute()
    }

    /**
     * `issue_id = issueId` 인 활성 워크로그 목록을 `started_at` DESC 로 반환한다.
     *
     * `deleted_at IS NULL` 필터를 항상 적용한다.
     *
     * @param issueId 조회할 이슈 UUID.
     * @return 활성 워크로그 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findByIssueId(issueId: UUID): List<Worklog> {
        log.debug("findByIssueId issueId={}", issueId)
        return dsl.selectFrom(WORKLOGS)
            .where(WORKLOGS.ISSUE_ID.eq(issueId))
            .and(WORKLOGS.DELETED_AT.isNull)
            .orderBy(WORKLOGS.STARTED_AT.desc())
            .fetch()
            .map(::toWorklog)
    }

    /**
     * `id = id` 인 활성 워크로그를 단건 반환한다.
     *
     * `deleted_at IS NULL` 필터를 적용한다.
     *
     * @param id 조회할 워크로그 UUID.
     * @return 워크로그, 없거나 소프트 삭제 상태면 null.
     */
    @Transactional(readOnly = true)
    fun findById(id: UUID): Worklog? {
        log.debug("findById id={}", id)
        return dsl.selectFrom(WORKLOGS)
            .where(WORKLOGS.ID.eq(id))
            .and(WORKLOGS.DELETED_AT.isNull)
            .fetchOne()
            ?.let(::toWorklog)
    }

    /**
     * 워크로그의 소요 시간·시작 시각·코멘트를 수정하고 `updated_at` 을 갱신한다.
     *
     * `deleted_at IS NULL` 조건으로 소프트 삭제된 항목은 수정되지 않는다.
     *
     * @param id               수정할 워크로그 UUID.
     * @param timeSpentSeconds 새 소요 시간(초).
     * @param startedAt        새 시작 시각.
     * @param comment          새 코멘트 (null 허용).
     * @return 수정된 행 수 (1 = 성공, 0 = 미존재 또는 소프트 삭제).
     */
    @Transactional
    fun update(
        id: UUID,
        timeSpentSeconds: Int,
        startedAt: Instant,
        comment: String?,
    ): Int {
        log.debug("update id={} timeSpentSeconds={}", id, timeSpentSeconds)
        return dsl.update(WORKLOGS)
            .set(WORKLOGS.TIME_SPENT_SECONDS, timeSpentSeconds)
            .set(WORKLOGS.STARTED_AT, startedAt.toOffsetDateTime())
            .set(WORKLOGS.COMMENT, comment)
            .set(WORKLOGS.UPDATED_AT, now())
            .where(WORKLOGS.ID.eq(id))
            .and(WORKLOGS.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 워크로그를 소프트 삭제한다 — `deleted_at` 과 `updated_at` 을 현재 시각으로 설정.
     *
     * 이미 소프트 삭제된 항목은 영향받지 않는다.
     *
     * @param id 소프트 삭제할 워크로그 UUID.
     * @return 1행 수정 성공(소프트 삭제) true / 미존재 또는 이미 삭제 0행이면 false.
     */
    @Transactional
    fun softDelete(id: UUID): Boolean {
        log.debug("softDelete id={}", id)
        val rows =
            dsl.update(WORKLOGS)
                .set(WORKLOGS.DELETED_AT, now())
                .set(WORKLOGS.UPDATED_AT, now())
                .where(WORKLOGS.ID.eq(id))
                .and(WORKLOGS.DELETED_AT.isNull)
                .execute()
        return rows > 0
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 현재 시각을 [OffsetDateTime] UTC 로 반환한다.
     *
     * 주입된 [Clock] 을 사용해 테스트에서 시각을 제어 가능하도록 한다.
     */
    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)

    /**
     * [Instant] 를 [OffsetDateTime] UTC 로 변환한다 (DB TIMESTAMPTZ 저장용).
     */
    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    /**
     * jOOQ [Record] 를 도메인 [Worklog] 로 변환한다.
     *
     * TIMESTAMPTZ 컬럼([OffsetDateTime])을 [Instant] 로 변환한다 (DATA.md §4).
     */
    private fun toWorklog(record: Record): Worklog =
        Worklog(
            id = record.get(WORKLOGS.ID) ?: error("worklogs.id must not be null after DB read"),
            issueId = record.get(WORKLOGS.ISSUE_ID) ?: error("worklogs.issue_id must not be null after DB read"),
            authorId = record.get(WORKLOGS.AUTHOR_ID) ?: error("worklogs.author_id must not be null after DB read"),
            timeSpentSeconds =
                record.get(WORKLOGS.TIME_SPENT_SECONDS)
                    ?: error("worklogs.time_spent_seconds must not be null after DB read"),
            startedAt =
                (
                    record.get(WORKLOGS.STARTED_AT)
                        ?: error("worklogs.started_at must not be null after DB read")
                ).toInstant(),
            comment = record.get(WORKLOGS.COMMENT),
            createdAt =
                (
                    record.get(WORKLOGS.CREATED_AT)
                        ?: error("worklogs.created_at must not be null after DB read")
                ).toInstant(),
            updatedAt =
                (
                    record.get(WORKLOGS.UPDATED_AT)
                        ?: error("worklogs.updated_at must not be null after DB read")
                ).toInstant(),
        )
}
