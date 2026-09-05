// 이슈 워처 저장소 — issue_watchers 테이블 jOOQ DSL 접근 (FR-WT-01).

package com.bts.issue.watcher.repository

import com.bts.issue.jooq.tables.references.ISSUE_WATCHERS
import org.jooq.DSLContext
import org.jooq.Record
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 워처(이슈 관심 등록) 데이터 행.
 *
 * @property userId 관심 등록 사용자 UUID.
 * @property createdAt 관심 등록 시각 ([Instant]).
 */
data class WatcherRow(
    val userId: UUID,
    val createdAt: Instant,
)

/**
 * 이슈 워처 저장소.
 *
 * jOOQ DSLContext 를 통해 `issue_watchers` 테이블에 접근한다.
 * 소프트 삭제 없음 — 워처 해제는 물리 DELETE.
 * 모든 public 메서드는 `@Transactional` 을 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
 *
 * ## 메서드 목록
 * - [add] — 워처 1건 추가. `ON CONFLICT DO NOTHING` 으로 멱등 보장.
 * - [remove] — 워처 1건 삭제. 존재하지 않으면 false (예외 없음).
 * - [listByIssue] — issueId 에 속한 워처 목록을 `created_at` 오름차순, 동률 시 `user_id` 오름차순으로 반환.
 * - [countByIssue] — issueId 의 워처 수 반환.
 * - [existsForUser] — 특정 (issueId, userId) 쌍의 워처 등록 여부 반환.
 */
@Repository
class IssueWatcherRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 워처 1건을 `issue_watchers` 에 추가한다.
     *
     * `INSERT … ON CONFLICT (issue_id, user_id) DO NOTHING` 으로 복합 PK 충돌 시
     * 오류 없이 조용히 무시한다 (멱등 보장).
     * `created_at` 은 DB 기본값 `now()` 를 사용한다.
     *
     * @param issueId 관심 등록 대상 이슈 UUID.
     * @param userId  관심 등록 사용자 UUID.
     */
    @Transactional
    fun add(
        issueId: UUID,
        userId: UUID,
    ) {
        log.debug("add watcher issueId={} userId={}", issueId, userId)
        dsl.insertInto(ISSUE_WATCHERS)
            .set(ISSUE_WATCHERS.ISSUE_ID, issueId)
            .set(ISSUE_WATCHERS.USER_ID, userId)
            .onConflictDoNothing()
            .execute()
    }

    /**
     * 워처 여러 건을 **한 문장**으로 추가한다 (FR-MN-03).
     *
     * ## 왜 [add] 반복이 아닌가
     * [add] 는 1인당 INSERT 1회다. 기존 호출자는 reporter·assignee **최대 2명**이라 그 형태가
     * 문제되지 않았는데, 멘션 자동 watcher 는 `MentionTargetResolver.MAX_MENTIONS_PER_EVENT`(50)
     * 까지 가므로 한 트랜잭션에서 최대 50 왕복이 된다. 다중 VALUES 로 1회에 끝낸다.
     *
     * `ON CONFLICT (issue_id, user_id) DO NOTHING` 은 다중 VALUES 에도 그대로 걸리므로
     * 멱등은 [add] 와 동일하다. 중복 userId 는 호출 전에 distinct 처리된다는 가정을 두지 않고
     * 여기서 한 번 더 접는다 — 같은 문장 안의 중복은 ON CONFLICT 가 아니라 **문장 자체**가 거부한다.
     *
     * @param issueId 관심 등록 대상 이슈 UUID.
     * @param userIds 관심 등록 사용자 UUID 목록. 비어 있으면 아무것도 하지 않는다.
     */
    @Transactional
    fun addAll(
        issueId: UUID,
        userIds: List<UUID>,
    ) {
        val distinct = userIds.distinct()
        if (distinct.isEmpty()) return
        log.debug("addAll watchers issueId={} count={}", issueId, distinct.size)
        dsl.insertInto(ISSUE_WATCHERS, ISSUE_WATCHERS.ISSUE_ID, ISSUE_WATCHERS.USER_ID)
            .apply { distinct.forEach { userId -> values(issueId, userId) } }
            .onConflictDoNothing()
            .execute()
    }

    /**
     * 워처 1건을 `issue_watchers` 에서 물리 삭제한다.
     *
     * 해당 행이 존재하지 않아도 예외 없이 false 를 반환한다 (멱등 보장).
     *
     * @param issueId 대상 이슈 UUID.
     * @param userId  삭제할 사용자 UUID.
     * @return 1행 삭제 성공 true / 이미 없어서 0행이면 false.
     */
    @Transactional
    fun remove(
        issueId: UUID,
        userId: UUID,
    ): Boolean {
        log.debug("remove watcher issueId={} userId={}", issueId, userId)
        val rows =
            dsl.deleteFrom(ISSUE_WATCHERS)
                .where(ISSUE_WATCHERS.ISSUE_ID.eq(issueId))
                .and(ISSUE_WATCHERS.USER_ID.eq(userId))
                .execute()
        return rows > 0
    }

    /**
     * `issue_id = issueId` 인 워처 목록을 `created_at` 오름차순, 동률 시 `user_id` 오름차순으로 반환한다.
     *
     * @param issueId 조회할 이슈 UUID.
     * @return 해당 이슈의 [WatcherRow] 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun listByIssue(issueId: UUID): List<WatcherRow> {
        log.debug("listByIssue issueId={}", issueId)
        return dsl.selectFrom(ISSUE_WATCHERS)
            .where(ISSUE_WATCHERS.ISSUE_ID.eq(issueId))
            .orderBy(ISSUE_WATCHERS.CREATED_AT.asc(), ISSUE_WATCHERS.USER_ID.asc())
            .fetch()
            .map(::toWatcherRow)
    }

    /**
     * `issue_id = issueId` 인 워처 수를 반환한다.
     *
     * @param issueId 조회할 이슈 UUID.
     * @return 워처 수.
     */
    @Transactional(readOnly = true)
    fun countByIssue(issueId: UUID): Int {
        log.debug("countByIssue issueId={}", issueId)
        return dsl.fetchCount(
            dsl.selectFrom(ISSUE_WATCHERS)
                .where(ISSUE_WATCHERS.ISSUE_ID.eq(issueId)),
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * jOOQ [Record] 를 도메인 [WatcherRow] 로 변환한다.
     *
     * TIMESTAMPTZ 컬럼([java.time.OffsetDateTime])을 [Instant] 로 변환한다 (DATA.md §4).
     */
    private fun toWatcherRow(record: Record): WatcherRow =
        WatcherRow(
            userId =
                record.get(ISSUE_WATCHERS.USER_ID)
                    ?: error("issue_watchers.user_id must not be null after DB read"),
            createdAt =
                (
                    record.get(ISSUE_WATCHERS.CREATED_AT)
                        ?: error("issue_watchers.created_at must not be null after DB read")
                ).toInstant(),
        )

    /**
     * `(issue_id, user_id)` 쌍의 워처 등록 여부를 반환한다.
     *
     * @param issueId 조회할 이슈 UUID.
     * @param userId  조회할 사용자 UUID.
     * @return 등록되어 있으면 true, 없으면 false.
     */
    @Transactional(readOnly = true)
    fun existsForUser(
        issueId: UUID,
        userId: UUID,
    ): Boolean {
        log.debug("existsForUser issueId={} userId={}", issueId, userId)
        return dsl.fetchExists(
            dsl.selectFrom(ISSUE_WATCHERS)
                .where(ISSUE_WATCHERS.ISSUE_ID.eq(issueId))
                .and(ISSUE_WATCHERS.USER_ID.eq(userId)),
        )
    }
}
