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
