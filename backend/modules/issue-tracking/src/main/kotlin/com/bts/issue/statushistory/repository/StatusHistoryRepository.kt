// 상태 전환 이력 배치 조회 jOOQ 리포지토리 — issue_change_group/item JOIN (CFD·cycletime 공유)

package com.bts.issue.statushistory.repository

import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_GROUP
import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_ITEM
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 상태 전환 이력을 여러 이슈에 대해 배치 조회하는 jOOQ 리포지토리.
 *
 * `issue_change_group`(변경 그룹) 과 `issue_change_item`(개별 필드 변경) 을 조인해
 * [FIELD_STATUS] 인 항목만 추출한다. 상태 이력을 재구성하는 기능(CFD, cycle time/lead time 등)은
 * 이슈의 상태 전환 이력만 필요하므로 assignee/summary 등 다른 필드 변경은 대상에서 제외한다.
 *
 * **jOOQ 화이트리스트 (ArchUnit 룰 2).** `com.bts.issue.jooq..` 생성 코드는 `..repository..`
 * 패키지에서만 접촉 가능하므로, 이 클래스는 `com.bts.issue.statushistory.repository` 패키지에 위치한다.
 *
 * **신규 인덱스 없음.** 기존 `idx_issue_change_group_issue(issue_id, created_at DESC, id DESC)`,
 * `idx_issue_change_item_group(group_id)`, `idx_issue_change_item_field(field)` 인덱스로 충분하다.
 *
 * @param dsl jOOQ DSLContext.
 */
@Repository
class StatusHistoryRepository(
    private val dsl: DSLContext,
) {
    /**
     * 여러 이슈의 상태 전환 이력을 한 번에 조회한다.
     *
     * `(issueId, changedAt, groupId)` 오름차순으로 정렬해 반환한다 — 같은 시각에 여러 그룹이
     * 기록된 경우 groupId 로 tie-break 한다.
     *
     * [issueIds] 가 비어 있으면 jOOQ 빈 IN 절 실행 없이 즉시 빈 리스트를 반환한다.
     *
     * @param issueIds 조회할 이슈 UUID 집합.
     * @return 상태 전환 이력 행 목록. 이력이 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun fetchStatusChanges(issueIds: Set<UUID>): List<StatusChangeRow> {
        if (issueIds.isEmpty()) return emptyList()

        return dsl
            .select(
                ISSUE_CHANGE_GROUP.ISSUE_ID,
                ISSUE_CHANGE_GROUP.CREATED_AT,
                ISSUE_CHANGE_GROUP.ID,
                ISSUE_CHANGE_ITEM.FROM_VALUE,
                ISSUE_CHANGE_ITEM.TO_VALUE,
            ).from(ISSUE_CHANGE_GROUP)
            .join(ISSUE_CHANGE_ITEM)
            .on(ISSUE_CHANGE_ITEM.GROUP_ID.eq(ISSUE_CHANGE_GROUP.ID))
            .where(ISSUE_CHANGE_GROUP.ISSUE_ID.`in`(issueIds))
            .and(ISSUE_CHANGE_ITEM.FIELD.eq(FIELD_STATUS))
            .orderBy(ISSUE_CHANGE_GROUP.ISSUE_ID, ISSUE_CHANGE_GROUP.CREATED_AT, ISSUE_CHANGE_GROUP.ID)
            .fetch { record ->
                StatusChangeRow(
                    issueId =
                        record.get(ISSUE_CHANGE_GROUP.ISSUE_ID)
                            ?: error("issue_change_group.issue_id must not be null"),
                    changedAt =
                        record.get(ISSUE_CHANGE_GROUP.CREATED_AT)?.toInstant()
                            ?: error("issue_change_group.created_at must not be null"),
                    groupId =
                        record.get(ISSUE_CHANGE_GROUP.ID)
                            ?: error("issue_change_group.id must not be null"),
                    fromValue = record.get(ISSUE_CHANGE_ITEM.FROM_VALUE),
                    toValue = record.get(ISSUE_CHANGE_ITEM.TO_VALUE),
                )
            }
    }

    internal companion object {
        /**
         * status 전환만 대상으로 하는 `issue_change_item.field` 값.
         *
         * 같은 술어를 쓰는
         * [com.bts.issue.repository.IssueRepository.fetchStatusChangesSinceForProject] 가
         * 이 상수를 참조한다 — 복제하면 한쪽만 바뀌었을 때 다른 쪽이 조용히 0건을 반환한다.
         */
        const val FIELD_STATUS = "status"
    }
}
