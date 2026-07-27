// 댓글 저장소 — comments 테이블 jOOQ DSL 접근 + 소프트 삭제 필터 (FR-IM-01 PR3).

package com.bts.issue.comment.repository

import com.bts.issue.comment.domain.Comment
import com.bts.issue.jooq.tables.references.COMMENTS
import org.jooq.DSLContext
import org.jooq.Record
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 댓글 저장소.
 *
 * jOOQ DSLContext 를 통해 `comments` 테이블에 접근한다.
 * 조회는 `deleted_at IS NULL` 필터를 항상 적용한다 (DATA.md §1.2 #7, §3).
 *
 * ## 메서드 목록
 * - [insert] — 댓글 1건 삽입.
 * - [listByIssue] — issueId 기준 활성 댓글 목록 (`created_at` ASC).
 * - [findActive] — issueId 까지 대조한 활성 댓글 단건 조회.
 * - [updateBody] — 활성 댓글의 본문·수정 시각 갱신.
 * - [softDelete] — 활성 댓글의 `deleted_at` 기록.
 */
@Repository
class CommentRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 댓글 1건을 `comments` 테이블에 삽입한다.
     *
     * @param comment 삽입할 댓글 도메인 객체.
     */
    @Transactional
    fun insert(comment: Comment) {
        log.debug("insert comment id={} issueId={}", comment.id, comment.issueId)
        dsl.insertInto(COMMENTS)
            .set(COMMENTS.ID, comment.id)
            .set(COMMENTS.ISSUE_ID, comment.issueId)
            .set(COMMENTS.AUTHOR_ID, comment.authorId)
            .set(COMMENTS.BODY, comment.body)
            .set(COMMENTS.CREATED_AT, comment.createdAt.toOffsetDateTime())
            .set(COMMENTS.UPDATED_AT, comment.updatedAt.toOffsetDateTime())
            .execute()
    }

    /**
     * `issue_id = issueId` 인 활성 댓글 목록을 `created_at` ASC 로 반환한다.
     *
     * `deleted_at IS NULL` 필터를 항상 적용한다.
     *
     * @param issueId 조회할 이슈 UUID.
     * @return 활성 댓글 목록 (`created_at` 오름차순). 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun listByIssue(issueId: UUID): List<Comment> {
        log.debug("listByIssue issueId={}", issueId)
        return dsl.selectFrom(COMMENTS)
            .where(COMMENTS.ISSUE_ID.eq(issueId))
            .and(COMMENTS.DELETED_AT.isNull)
            .orderBy(COMMENTS.CREATED_AT.asc())
            .fetch()
            .map(::toComment)
    }

    /**
     * `id` + `issueId` 가 모두 일치하는 활성 댓글 1건을 반환한다.
     *
     * @param id      조회할 댓글 UUID.
     * @param issueId 댓글이 속해야 하는 이슈 UUID.
     * @return 활성 댓글. 없거나 다른 이슈 소속이거나 이미 삭제됐으면 null.
     */
    @Transactional(readOnly = true)
    fun findActive(
        id: UUID,
        issueId: UUID,
    ): Comment? {
        log.debug("findActive id={} issueId={}", id, issueId)
        return dsl.selectFrom(COMMENTS)
            .where(COMMENTS.ID.eq(id))
            .and(COMMENTS.ISSUE_ID.eq(issueId))
            .and(COMMENTS.DELETED_AT.isNull)
            .fetchOne()
            ?.let(::toComment)
    }

    /**
     * 활성 댓글의 본문과 수정 시각을 갱신한다.
     *
     * @param id        수정할 댓글 UUID.
     * @param body      새 본문 (raw markdown 원문).
     * @param updatedAt 새 수정 시각.
     * @return 영향 행 수. 1 = 갱신됨, 0 = 대상 없음(미존재 또는 이미 삭제됨).
     */
    @Transactional
    fun updateBody(
        id: UUID,
        body: String,
        updatedAt: Instant,
    ): Int {
        log.debug("updateBody id={}", id)
        return dsl.update(COMMENTS)
            .set(COMMENTS.BODY, body)
            .set(COMMENTS.UPDATED_AT, updatedAt.toOffsetDateTime())
            .where(COMMENTS.ID.eq(id).and(COMMENTS.DELETED_AT.isNull))
            .execute()
    }

    /**
     * 활성 댓글에 `deleted_at` 을 기록해 소프트 삭제한다.
     *
     * @param id        삭제할 댓글 UUID.
     * @param deletedAt 삭제 시각.
     * @return 영향 행 수. 1 = 삭제됨, 0 = 대상 없음(미존재 또는 이미 삭제됨).
     */
    @Transactional
    fun softDelete(
        id: UUID,
        deletedAt: Instant,
    ): Int {
        log.debug("softDelete id={}", id)
        return dsl.update(COMMENTS)
            .set(COMMENTS.DELETED_AT, deletedAt.toOffsetDateTime())
            .where(COMMENTS.ID.eq(id).and(COMMENTS.DELETED_AT.isNull))
            .execute()
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [Instant] 를 [OffsetDateTime] UTC 로 변환한다 (DB TIMESTAMPTZ 저장용).
     */
    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    /**
     * jOOQ [Record] 를 도메인 [Comment] 로 변환한다.
     *
     * TIMESTAMPTZ 컬럼([OffsetDateTime])을 [Instant] 로 변환한다 (DATA.md §4).
     */
    private fun toComment(record: Record): Comment =
        Comment(
            id = record.get(COMMENTS.ID) ?: error("comments.id must not be null after DB read"),
            issueId = record.get(COMMENTS.ISSUE_ID) ?: error("comments.issue_id must not be null after DB read"),
            authorId = record.get(COMMENTS.AUTHOR_ID) ?: error("comments.author_id must not be null after DB read"),
            body = record.get(COMMENTS.BODY) ?: error("comments.body must not be null after DB read"),
            createdAt =
                (
                    record.get(COMMENTS.CREATED_AT)
                        ?: error("comments.created_at must not be null after DB read")
                ).toInstant(),
            updatedAt =
                (
                    record.get(COMMENTS.UPDATED_AT)
                        ?: error("comments.updated_at must not be null after DB read")
                ).toInstant(),
        )
}
