// 이슈 링크 저장소 — issue_links 테이블 jOOQ DSL 접근. DATA.md §5, §6 준수.

package com.bts.issue.link.repository

import com.bts.issue.jooq.tables.references.ISSUE_LINKS
import com.bts.issue.link.domain.IssueLink
import com.bts.issue.link.domain.LinkType
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// ── SQL 상수 — existsBlocksPath 재귀 CTE ──────────────────────────────────────
// WITH RECURSIVE 로 blocks 그래프에서 fromId → toId 도달 가능성을 탐색한다.
// jOOQ 3.19 withRecursive DSL 로도 표현 가능하나, UUID 타입 파라미터를 직접 바인딩하면
// 가독성과 안전성(SQL injection 없음)이 더 높아 parameter-binding 방식 사용.
// (DATA.md §5 예외 조건: jOOQ DSL 미지원 PG 전용 구문이 아닌 경우에도, 재귀 CTE에 대해서는
//  prepared statement 바인딩으로 injection 방지를 유지한다 — 문자열 결합 금지.)
private const val SQL_EXISTS_BLOCKS_PATH =
    """
    WITH RECURSIVE blocks_path(current_id) AS (
        SELECT target_id
        FROM issue_links
        WHERE source_id = ?
          AND link_type = 'blocks'
        UNION ALL
        SELECT il.target_id
        FROM issue_links il
        JOIN blocks_path bp ON il.source_id = bp.current_id
        WHERE il.link_type = 'blocks'
    )
    SELECT EXISTS (SELECT 1 FROM blocks_path WHERE current_id = ?)
    """

/**
 * 이슈 링크 저장소.
 *
 * jOOQ DSLContext 를 통해 `issue_links` 테이블에 접근한다.
 * 모든 public 메서드는 `@Transactional` 을 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
 *
 * ## 메서드 목록
 * - [insert] — 새 링크를 삽입하고 DB 생성 id 가 채워진 [IssueLink] 반환.
 * - [findBySourceId] — source_id 기준 링크 목록 반환.
 * - [findByTargetId] — target_id 기준 링크 목록 반환.
 * - [existsLink] — (sourceId, targetId, linkType) 중복 여부 확인.
 * - [deleteById] — 링크 id 로 행 삭제. 삭제 성공 true / 존재하지 않으면 false.
 * - [existsBlocksPath] — blocks 그래프 재귀 CTE 로 도달 가능성 탐색.
 *
 * ## 소프트 삭제 없음
 * `issue_links` 는 관계 테이블이라 링크 해제 = 행 물리 삭제 (DATA.md §3).
 * 상대 이슈의 `deleted_at` 필터는 Task 5 서비스 책임이다.
 */
@Repository
class IssueLinkRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 링크를 `issue_links` 테이블에 삽입하고 DB 생성 id 가 채워진 [IssueLink] 를 반환한다.
     *
     * UNIQUE 제약(source_id, target_id, link_type) 위반 시 DataAccessException 으로 전파된다.
     * 중복 사전 검사는 상위 서비스(Task 5) 책임이다.
     *
     * @param link 영속화할 [IssueLink]. id 는 null 이어야 한다.
     * @return DB 생성 id 가 포함된 [IssueLink].
     */
    @Transactional
    fun insert(link: IssueLink): IssueLink {
        log.debug("Inserting issue link source={} target={} type={}", link.sourceId, link.targetId, link.linkType)
        val record =
            dsl.insertInto(ISSUE_LINKS)
                .set(ISSUE_LINKS.SOURCE_ID, link.sourceId)
                .set(ISSUE_LINKS.TARGET_ID, link.targetId)
                .set(ISSUE_LINKS.LINK_TYPE, link.linkType.code)
                .returning(ISSUE_LINKS.ID)
                .fetchOne()
                ?: error("insert returning() returned null for source=${link.sourceId} target=${link.targetId}")
        return link.copy(id = record.id)
    }

    /**
     * `source_id = issueId` 인 모든 링크를 반환한다.
     *
     * 상대 이슈의 `deleted_at` 필터는 포함하지 않는다 — Task 5 서비스 책임.
     *
     * @param issueId 링크 출발 이슈 UUID.
     * @return source 가 [issueId] 인 [IssueLink] 리스트. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findBySourceId(issueId: UUID): List<IssueLink> {
        return dsl.selectFrom(ISSUE_LINKS)
            .where(ISSUE_LINKS.SOURCE_ID.eq(issueId))
            .fetch()
            .map(::toIssueLink)
    }

    /**
     * `target_id = issueId` 인 모든 링크를 반환한다.
     *
     * 상대 이슈의 `deleted_at` 필터는 포함하지 않는다 — Task 5 서비스 책임.
     *
     * @param issueId 링크 도착 이슈 UUID.
     * @return target 이 [issueId] 인 [IssueLink] 리스트. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findByTargetId(issueId: UUID): List<IssueLink> {
        return dsl.selectFrom(ISSUE_LINKS)
            .where(ISSUE_LINKS.TARGET_ID.eq(issueId))
            .fetch()
            .map(::toIssueLink)
    }

    /**
     * (sourceId, targetId, linkType) 조합의 링크가 이미 존재하는지 확인한다.
     *
     * 서비스 계층에서 중복 링크 생성 시도를 사전 차단할 때 사용한다.
     *
     * @param sourceId 링크 출발 이슈 UUID.
     * @param targetId 링크 도착 이슈 UUID.
     * @param linkType 확인할 링크 유형.
     * @return 링크가 이미 존재하면 true, 없으면 false.
     */
    @Transactional(readOnly = true)
    fun existsLink(
        sourceId: UUID,
        targetId: UUID,
        linkType: LinkType,
    ): Boolean {
        return dsl.fetchExists(
            dsl.selectOne()
                .from(ISSUE_LINKS)
                .where(ISSUE_LINKS.SOURCE_ID.eq(sourceId))
                .and(ISSUE_LINKS.TARGET_ID.eq(targetId))
                .and(ISSUE_LINKS.LINK_TYPE.eq(linkType.code)),
        )
    }

    /**
     * 링크 id 로 행을 삭제한다.
     *
     * 물리 삭제(관계 해제) — `issue_links` 는 소프트 삭제가 없다 (DATA.md §3).
     *
     * @param linkId 삭제할 링크의 BIGINT id.
     * @return 1행 삭제 성공 true / 이미 없어서 0행이면 false.
     */
    @Transactional
    fun deleteById(linkId: Long): Boolean {
        log.debug("Deleting issue link id={}", linkId)
        val rows =
            dsl.deleteFrom(ISSUE_LINKS)
                .where(ISSUE_LINKS.ID.eq(linkId))
                .execute()
        return rows > 0
    }

    /**
     * blocks 그래프에서 [fromId] 에서 [toId] 로의 도달 가능성을 재귀 CTE 로 탐색한다.
     *
     * `link_type = 'blocks'` 인 엣지만 따라간다. 순환 탐지(links 링크 추가 전 역방향 경로 확인)에 사용한다.
     *
     * ## 재귀 CTE 방식
     * jOOQ DSL `withRecursive` 대신 parameter-binding(`?`) prepared statement 를 사용한다.
     * UUID 파라미터를 `?` 로 바인딩하므로 SQL injection 위험이 없으며 (DATA.md §5 예외 규정 준수),
     * [SQL_EXISTS_BLOCKS_PATH] 상수가 전체 쿼리를 관리한다.
     *
     * @param fromId 탐색 시작 이슈 UUID.
     * @param toId 도달 목표 이슈 UUID.
     * @return [fromId] 에서 [toId] 로 도달 가능하면 true, 불가능하면 false.
     */
    @Transactional(readOnly = true)
    fun existsBlocksPath(
        fromId: UUID,
        toId: UUID,
    ): Boolean {
        log.debug("Checking blocks path from={} to={}", fromId, toId)
        return dsl.fetchValue(SQL_EXISTS_BLOCKS_PATH.trimIndent(), fromId, toId) as? Boolean ?: false
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * jOOQ [IssueLinksRecord] 를 도메인 [IssueLink] 로 변환한다.
     */
    private fun toIssueLink(record: org.jooq.Record): IssueLink {
        val tbl = ISSUE_LINKS
        return IssueLink(
            id = record.get(tbl.ID),
            sourceId = record.get(tbl.SOURCE_ID) ?: error("source_id must not be null"),
            targetId = record.get(tbl.TARGET_ID) ?: error("target_id must not be null"),
            linkType = LinkType.fromCode(record.get(tbl.LINK_TYPE) ?: error("link_type must not be null")),
        )
    }
}
