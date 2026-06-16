// issue_key_redirects 테이블 jOOQ DSL 접근 — append-only insert + 체인 순회 (DATA.md §1.1)

package com.bts.issue.repository

import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueKeyRedirect
import com.bts.issue.jooq.tables.references.ISSUE_KEY_REDIRECTS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * `issue_key_redirects` 테이블에 대한 jOOQ Repository.
 *
 * **append-only** — [insert] 만 허용하며 UPDATE/DELETE 메서드를 제공하지 않는다 (DATA.md §1.1).
 * DB 에는 UPDATE/DELETE 트리거가 설정되어 있어 애플리케이션 우회 시도도 차단된다.
 *
 * **체인 순회** — 이슈가 A→B, B→C 순으로 이동하면 테이블에 두 행이 삽입된다.
 * [findCurrentKey] 는 while 루프로 최종 키까지 순회하며, 무한 루프 방어를 위해
 * [MAX_HOPS] 를 초과하면 순회를 중단하고 마지막으로 얻은 키를 반환한다.
 */
@Repository
class IssueKeyRedirectRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * `issue_key_redirects` 에 리다이렉트 행을 삽입한다.
     *
     * **append-only** — old_key 는 PK 이므로 동일한 oldKey 에 대한 중복 호출은
     * DB PK 위반(중복 키) 예외를 발생시킨다.
     *
     * @param oldKey 이전 이슈 키
     * @param newKey 이동 후 이슈 키
     * @throws org.jooq.exception.DataAccessException DB PK 위반 시
     */
    @Transactional
    fun insert(
        oldKey: IssueKey,
        newKey: IssueKey,
    ) {
        log.info("issue_key_redirects insert: {} -> {}", oldKey.value, newKey.value)
        dsl.insertInto(ISSUE_KEY_REDIRECTS)
            .set(ISSUE_KEY_REDIRECTS.OLD_KEY, oldKey.value)
            .set(ISSUE_KEY_REDIRECTS.NEW_KEY, newKey.value)
            .execute()
    }

    /**
     * [startKey] 로 시작하는 리다이렉트 체인을 순회해 최종 이슈 키를 반환한다.
     *
     * - [startKey] 가 `issue_key_redirects` 에 없으면 `null` 반환.
     * - 단계마다 `new_key` 를 다음 `old_key` 로 사용해 체인을 따라간다.
     * - [MAX_HOPS] 초과 시 순회를 중단하고 마지막으로 얻은 키를 반환한다 (무한 루프 방어).
     *
     * @param startKey 조회를 시작할 이슈 키 (이전 키)
     * @return 최종 이슈 키. [startKey] 가 리다이렉트 대상이 아니면 `null`.
     */
    @Transactional(readOnly = true)
    fun findCurrentKey(startKey: IssueKey): IssueKey? {
        var current = startKey
        var hops = 0

        while (hops < MAX_HOPS) {
            val nextKeyValue =
                dsl.select(ISSUE_KEY_REDIRECTS.NEW_KEY)
                    .from(ISSUE_KEY_REDIRECTS)
                    .where(ISSUE_KEY_REDIRECTS.OLD_KEY.eq(current.value))
                    .fetchOne(ISSUE_KEY_REDIRECTS.NEW_KEY)
                    ?: break

            current = IssueKey(nextKeyValue)
            hops++
        }

        if (hops >= MAX_HOPS) {
            log.warn("issue_key_redirects chain traversal hit MAX_HOPS={} for startKey={}", MAX_HOPS, startKey.value)
        }

        return if (hops == 0) null else current
    }

    /**
     * [startKey] 에 대한 [IssueKeyRedirect] 도메인 객체를 단건 조회한다.
     * 체인 순회 없이 직접 연결된 next 키만 반환한다.
     *
     * @param startKey 조회할 이전 이슈 키
     * @return [IssueKeyRedirect] 또는 `null` (매핑 없음)
     */
    @Transactional(readOnly = true)
    fun findByOldKey(startKey: IssueKey): IssueKeyRedirect? =
        dsl.selectFrom(ISSUE_KEY_REDIRECTS)
            .where(ISSUE_KEY_REDIRECTS.OLD_KEY.eq(startKey.value))
            .fetchOne()
            ?.let { r ->
                IssueKeyRedirect(
                    oldKey = IssueKey(requireNotNull(r.oldKey) { "old_key must not be null" }),
                    newKey = IssueKey(requireNotNull(r.newKey) { "new_key must not be null" }),
                    redirectedAt = requireNotNull(r.redirectedAt) { "redirected_at must not be null" },
                )
            }

    companion object {
        /**
         * 체인 순회 최대 hop 수.
         *
         * 실제 데이터에서 이슈가 50회 이상 이동하는 경우는 없다.
         * 사이클이 삽입된 경우(DB 트리거 차단 전 직접 조작 등) 무한 루프를 방지한다.
         *
         * DATA.md §1.1 — old_key PK 로 인해 동일 키의 중복 리다이렉트는 원천 차단되나,
         * 간접 사이클(A→B→C→A)은 DB 레벨에서 차단되지 않으므로 애플리케이션 레벨에서 방어한다.
         */
        const val MAX_HOPS = 50
    }
}
