// 이슈 첨부 파일 저장소 — issue_attachments 테이블 jOOQ DSL 접근. 하드 삭제 전용 (FR-AC-01).

package com.bts.issue.attachment.repository

import com.bts.issue.attachment.domain.Attachment
import com.bts.issue.jooq.tables.references.ISSUE_ATTACHMENTS
import org.jooq.DSLContext
import org.jooq.Record
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 이슈 첨부 파일 저장소.
 *
 * jOOQ DSLContext 를 통해 `issue_attachments` 테이블에 접근한다.
 * 소프트 삭제 없음 — 첨부 제거는 물리 DELETE (DATA.md §3, FR-AC-01 ADR).
 * 모든 public 메서드는 `@Transactional` 을 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
 *
 * ## 메서드 목록
 * - [insert] — `issue_attachments` 에 첨부 메타데이터 1행 삽입.
 * - [findByIssueId] — issueId 에 속한 첨부 목록을 `created_at` 내림차순으로 반환.
 * - [findById] — UUID 로 첨부 1건 조회. 없으면 null.
 * - [deleteById] — UUID 로 행 물리 삭제. 삭제 성공 시 true, 존재하지 않으면 false.
 */
@Repository
class AttachmentRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 첨부 메타데이터 1건을 `issue_attachments` 에 삽입한다.
     *
     * `created_at` 은 [Attachment.createdAt] 값을 그대로 사용한다.
     * DB 기본값(`now()`)에 의존하지 않으므로 테스트에서 시각을 제어할 수 있다.
     *
     * @param attachment 삽입할 [Attachment]. `id` 는 호출자가 미리 생성한 UUID.
     */
    @Transactional
    fun insert(attachment: Attachment) {
        log.debug(
            "Inserting attachment id={} issueId={} filename={}",
            attachment.id,
            attachment.issueId,
            attachment.filename,
        )
        dsl.insertInto(ISSUE_ATTACHMENTS)
            .set(ISSUE_ATTACHMENTS.ID, attachment.id)
            .set(ISSUE_ATTACHMENTS.ISSUE_ID, attachment.issueId)
            .set(ISSUE_ATTACHMENTS.FILENAME, attachment.filename)
            .set(ISSUE_ATTACHMENTS.CONTENT_TYPE, attachment.contentType)
            .set(ISSUE_ATTACHMENTS.SIZE_BYTES, attachment.sizeBytes)
            .set(ISSUE_ATTACHMENTS.STORAGE_KEY, attachment.storageKey)
            .set(ISSUE_ATTACHMENTS.UPLOADED_BY, attachment.uploadedBy)
            .set(ISSUE_ATTACHMENTS.CREATED_AT, OffsetDateTime.ofInstant(attachment.createdAt, ZoneOffset.UTC))
            .execute()
    }

    /**
     * `issue_id = issueId` 인 첨부 목록을 `created_at` 내림차순으로 반환한다.
     *
     * @param issueId 조회할 이슈 UUID.
     * @return 해당 이슈의 [Attachment] 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findByIssueId(issueId: UUID): List<Attachment> {
        log.debug("findByIssueId issueId={}", issueId)
        return dsl.selectFrom(ISSUE_ATTACHMENTS)
            .where(ISSUE_ATTACHMENTS.ISSUE_ID.eq(issueId))
            .orderBy(ISSUE_ATTACHMENTS.CREATED_AT.desc())
            .fetch()
            .map(::toAttachment)
    }

    /**
     * UUID 로 첨부 1건을 조회한다.
     *
     * @param id 조회할 첨부 UUID.
     * @return 해당 [Attachment], 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findById(id: UUID): Attachment? {
        log.debug("findById id={}", id)
        return dsl.selectFrom(ISSUE_ATTACHMENTS)
            .where(ISSUE_ATTACHMENTS.ID.eq(id))
            .fetchOne()
            ?.let(::toAttachment)
    }

    /**
     * UUID 로 첨부 행을 물리 삭제한다.
     *
     * 소프트 삭제 없음 — 이 메서드는 행을 완전히 제거한다 (DATA.md §3).
     *
     * @param id 삭제할 첨부 UUID.
     * @return 1행 삭제 성공 true / 이미 없어서 0행이면 false.
     */
    @Transactional
    fun deleteById(id: UUID): Boolean {
        log.debug("deleteById id={}", id)
        val rows = dsl.deleteFrom(ISSUE_ATTACHMENTS)
            .where(ISSUE_ATTACHMENTS.ID.eq(id))
            .execute()
        return rows > 0
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * jOOQ [Record] 를 도메인 [Attachment] 로 변환한다.
     *
     * TIMESTAMPTZ 컬럼([OffsetDateTime])을 [java.time.Instant] 로 변환한다 (DATA.md §4).
     */
    private fun toAttachment(record: Record): Attachment {
        val t = ISSUE_ATTACHMENTS
        return Attachment(
            id = record.get(t.ID) ?: error("issue_attachments.id must not be null after DB read"),
            issueId = record.get(t.ISSUE_ID) ?: error("issue_attachments.issue_id must not be null after DB read"),
            filename = record.get(t.FILENAME) ?: error("issue_attachments.filename must not be null after DB read"),
            contentType = record.get(t.CONTENT_TYPE)
                ?: error("issue_attachments.content_type must not be null after DB read"),
            sizeBytes = record.get(t.SIZE_BYTES)
                ?: error("issue_attachments.size_bytes must not be null after DB read"),
            storageKey = record.get(t.STORAGE_KEY)
                ?: error("issue_attachments.storage_key must not be null after DB read"),
            uploadedBy = record.get(t.UPLOADED_BY)
                ?: error("issue_attachments.uploaded_by must not be null after DB read"),
            createdAt = (record.get(t.CREATED_AT)
                ?: error("issue_attachments.created_at must not be null after DB read")).toInstant(),
        )
    }
}
