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
 *
 * ## 왜 모든 `WHERE` 에 `deleted_at IS NULL` 이 들어가는가
 * DEVELOPMENT.md §1.2 #7 — "`DELETE` 는 항상 `WHERE` + 소프트 삭제 우선". 이 규칙에는
 * 이 저장소에서만 성립하는 **두 번째 효과**가 있다.
 *
 * 1. **삭제된 행의 부활 차단.** 필터가 없으면 [updateBody] 가 이미 삭제된 댓글의 본문을
 *    갱신하고, [softDelete] 가 최초 삭제 시각을 나중 시각으로 덮어쓴다. 감사 관점에서
 *    "언제 지워졌나"가 소실된다.
 * 2. **서비스의 404 판정 근거.** 쓰기 메서드는 영향 행 수(`Int`)를 그대로 반환하고,
 *    서비스는 `0` 을 보고 [com.bts.issue.comment.domain.CommentNotFoundException] 을 던진다.
 *    즉 `0` 이 곧 "대상 없음"이라는 계약이므로, 필터가 빠지면 이미 삭제된 댓글에도 `1` 이
 *    돌아가 404 판정이 통째로 무력화된다. 조회(`SELECT`)와 판정(`UPDATE`)을 분리해
 *    "먼저 조회 → 있으면 수정"으로 짜지 않는 이유도 같다 — 그 사이에 삭제가 끼어들면
 *    TOCTOU(검사-사용 시점 불일치)가 생기지만, 조건을 `WHERE` 안에 넣으면 판정과 갱신이
 *    한 문장에서 원자적으로 일어난다.
 *
 * 이 두 성질은 `CommentRepositoryTest` 의 뮤테이션(각 `WHERE` 에서 `deleted_at IS NULL` 을
 * 하나씩 제거)으로 실제 검증됐다 — 제거할 때마다 대응 테스트가 정확히 1건씩 실패한다.
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
     * ## 왜 `id` 만으로 찾지 않고 `issueId` 를 함께 받는가
     * 댓글은 Issue 애그리거트의 자식이라 단독 조회 창구가 없고 항상 이슈를 경유한다
     * (`/issues/{key}/comments/{commentId}`). 그런데 `commentId` 는 전역 UUID 이므로,
     * `id` 만으로 조회하면 **자기가 볼 수 있는 아무 이슈 키**에 남의 이슈 댓글 id 를 붙인
     * 경로가 그대로 통과한다 — 이슈 단위 권한 검사를 우회하는 경로 위조다.
     * 소속을 `WHERE` 에서 대조하면 위조 경로는 "그 이슈에 그런 댓글 없음"(null → 404)이 되고,
     * "id 는 존재하지만 다른 이슈 소속"이라는 사실도 응답에서 구분되지 않는다.
     *
     * `deleted_at IS NULL` 을 포함하는 이유는 클래스 KDoc 참조.
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
     * `WHERE` 의 `deleted_at IS NULL` 이 "삭제된 댓글 부활 차단" 과 "서비스의 404 판정 근거"
     * 두 역할을 겸한다 — 클래스 KDoc 참조.
     *
     * @param id        수정할 댓글 UUID.
     * @param issueId   댓글이 속해야 하는 이슈 UUID. 클래스 KDoc 참조.
     * @param body      새 본문 (raw markdown 원문).
     * @param updatedAt 새 수정 시각.
     * @return 영향 행 수. 1 = 갱신됨, 0 = 대상 없음(미존재 · 이미 삭제됨 · 다른 이슈 소속).
     */
    @Transactional
    fun updateBody(
        id: UUID,
        issueId: UUID,
        body: String,
        updatedAt: Instant,
    ): Int {
        log.debug("updateBody id={} issueId={}", id, issueId)
        return dsl.update(COMMENTS)
            .set(COMMENTS.BODY, body)
            .set(COMMENTS.UPDATED_AT, updatedAt.toOffsetDateTime())
            .where(COMMENTS.ID.eq(id))
            .and(COMMENTS.ISSUE_ID.eq(issueId))
            .and(COMMENTS.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 활성 댓글에 `deleted_at` 을 기록해 소프트 삭제한다.
     *
     * 물리 `DELETE` 가 아닌 `UPDATE` 다 — DEVELOPMENT.md §1.2 #7 "소프트 삭제 우선,
     * 하드 삭제는 ADR 필수". `WHERE` 의 `deleted_at IS NULL` 은 재삭제를 막아 최초 삭제 시각을
     * 보존하고, 동시에 반환 `0` 을 서비스의 404 판정 근거로 만든다 — 클래스 KDoc 참조.
     *
     * @param id        삭제할 댓글 UUID.
     * @param issueId   댓글이 속해야 하는 이슈 UUID. 클래스 KDoc 참조.
     * @param deletedAt 삭제 시각.
     * @return 영향 행 수. 1 = 삭제됨, 0 = 대상 없음(미존재 · 이미 삭제됨 · 다른 이슈 소속).
     */
    @Transactional
    fun softDelete(
        id: UUID,
        issueId: UUID,
        deletedAt: Instant,
    ): Int {
        log.debug("softDelete id={} issueId={}", id, issueId)
        return dsl.update(COMMENTS)
            .set(COMMENTS.DELETED_AT, deletedAt.toOffsetDateTime())
            .where(COMMENTS.ID.eq(id))
            .and(COMMENTS.ISSUE_ID.eq(issueId))
            .and(COMMENTS.DELETED_AT.isNull)
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
