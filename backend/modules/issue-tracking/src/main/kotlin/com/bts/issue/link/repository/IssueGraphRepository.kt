// 그래프 시각화용 이슈 read 저장소 — 부모/자식 단건·다건 조회

package com.bts.issue.link.repository

import com.bts.issue.jooq.tables.references.ISSUES
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 이슈 그래프 시각화용 읽기 전용 저장소.
 *
 * `issues.parent_id` self-join 을 통해 부모/자식 관계를 조회한다.
 * jOOQ DSLContext 를 통해 `issues` 테이블에 접근하며, 소프트삭제(`deleted_at IS NULL`) 필터를
 * 쿼리 단에 포함한다.
 *
 * 모든 public 메서드는 `@Transactional(readOnly = true)` 를 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
 *
 * ## 메서드 목록
 * - [findParent] — childId 이슈의 부모를 단건 조회. 부모 없거나 소프트삭제면 null.
 * - [findChildren] — parentId 의 자식 목록을 key ASC 정렬로 반환. 소프트삭제 제외.
 */
@Repository
class IssueGraphRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [childId] 이슈의 부모를 [GraphNeighborRow] 로 반환한다.
     *
     * ## 쿼리 로직
     * `issues child LEFT JOIN issues parent ON child.parent_id = parent.id` self-join 으로
     * 부모 행을 가져온다. 다음 두 경우에 null 을 반환한다.
     * - `child.parent_id IS NULL` — 부모가 설정되지 않은 루트 이슈.
     * - 부모의 `deleted_at IS NOT NULL` — 부모가 소프트삭제됨.
     *
     * ## cartesian product 안전성
     * child:parent = N:1(또는 N:0) 이므로 행 폭증 없음.
     *
     * @param childId 자식 이슈의 내부 UUID.
     * @return 부모 [GraphNeighborRow]. 부모 없거나 소프트삭제면 null.
     */
    @Transactional(readOnly = true)
    fun findParent(childId: UUID): GraphNeighborRow? {
        log.debug("findParent childId={}", childId)
        val child = ISSUES.`as`("child")
        val parent = ISSUES.`as`("parent")
        return dsl.select(
            parent.ID,
            parent.KEY,
            parent.SUMMARY,
            parent.CURRENT_STATE_KEY,
        )
            .from(child)
            .join(parent).on(child.PARENT_ID.eq(parent.ID))
            .where(child.ID.eq(childId))
            .and(parent.DELETED_AT.isNull)
            .fetchOne { record ->
                GraphNeighborRow(
                    id = record.get(parent.ID) ?: error("issues.id must not be null"),
                    key = record.get(parent.KEY) ?: error("issues.key must not be null"),
                    summary = record.get(parent.SUMMARY) ?: error("issues.summary must not be null"),
                    statusKey = record.get(parent.CURRENT_STATE_KEY)
                        ?: error("issues.current_state_key must not be null"),
                )
            }
    }

    /**
     * [parentId] 의 자식 이슈 목록을 key ASC 정렬로 반환한다.
     *
     * ## 쿼리 로직
     * `issues.parent_id = parentId AND issues.deleted_at IS NULL` 조건으로
     * 소프트삭제되지 않은 자식 행만 가져온다.
     *
     * @param parentId 부모 이슈의 내부 UUID.
     * @return key ASC 정렬된 자식 [GraphNeighborRow] 목록. 자식이 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findChildren(parentId: UUID): List<GraphNeighborRow> {
        log.debug("findChildren parentId={}", parentId)
        return dsl.select(
            ISSUES.ID,
            ISSUES.KEY,
            ISSUES.SUMMARY,
            ISSUES.CURRENT_STATE_KEY,
        )
            .from(ISSUES)
            .where(ISSUES.PARENT_ID.eq(parentId))
            .and(ISSUES.DELETED_AT.isNull)
            .orderBy(ISSUES.KEY.asc())
            .fetch { record ->
                GraphNeighborRow(
                    id = record.get(ISSUES.ID) ?: error("issues.id must not be null"),
                    key = record.get(ISSUES.KEY) ?: error("issues.key must not be null"),
                    summary = record.get(ISSUES.SUMMARY) ?: error("issues.summary must not be null"),
                    statusKey = record.get(ISSUES.CURRENT_STATE_KEY)
                        ?: error("issues.current_state_key must not be null"),
                )
            }
    }
}
