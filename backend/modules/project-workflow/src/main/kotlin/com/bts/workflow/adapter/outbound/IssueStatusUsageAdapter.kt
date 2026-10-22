// IssueStatusUsagePort 구현 — issues 단일 테이블 스칼라 count (읽기 전용)

package com.bts.workflow.adapter.outbound

import com.bts.shared.issue.StatusMigrationTypeFilter
import com.bts.workflow.application.port.IssueStatusUsagePort
import com.bts.workflow.application.port.IssueTypeStatusCount
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * `issues.current_state_key` 로 사용량을 센다. 범위는 `issues.project_id` 로 좁힌다.
 *
 * issue-tracking 의 테이블이지만 **읽기 전용 스칼라 count** 만 한다 —
 * `IssueTypeUsageAdapter` 와 같은 동적 참조 패턴이라 BC 격리를 깨지 않는다.
 * 소프트 삭제된 이슈는 세지 않는다.
 *
 * 프로젝트 스코프에 JOIN 이 없는 이유는 `issues.project_id` 가 이미 UUID 이고
 * (`V001__issues_initial.sql`), 부르는 쪽이 그 id 집합을 완성해 넘겨주기 때문이다.
 */
@Component
class IssueStatusUsageAdapter(
    private val dsl: DSLContext,
) : IssueStatusUsagePort {
    private val log = LoggerFactory.getLogger(javaClass)

    // issue-tracking 테이블 — jOOQ codegen 범위 밖. 동적 참조가 이 BC 의 관례다.
    private val issuesTable = DSL.table("issues")
    private val stateKeyField = DSL.field("current_state_key", String::class.java)
    private val projectIdField = DSL.field("project_id", UUID::class.java)
    private val deletedAtField = DSL.field("deleted_at")
    private val typeIdField = DSL.field("type_id", Long::class.java)

    /**
     * [projectIds] 안의 살아 있는 이슈 중 [statusKey] 를 쓰는 것을 센다.
     *
     * ### 빈 스코프는 조기에 0 이다
     * 빈 집합을 그대로 `IN` 으로 흘리면 「전체」로 읽힐 여지가 생긴다 — 그러면 스킴이 아직 안 붙은
     * 워크플로우가 **남의 프로젝트 이슈 때문에** 발행을 못 한다. 스코프가 비었다는 것은
     * 「셀 대상이 없다」는 뜻이므로 여기서 끊는다. DB 왕복도 함께 준다.
     */
    @Transactional(readOnly = true)
    override fun countIssuesInStatus(
        statusKey: String,
        projectIds: Set<UUID>,
    ): Long {
        if (projectIds.isEmpty()) {
            log.debug("countIssuesInStatus 스코프가 비어 0 을 돌려준다. statusKey={}", statusKey)
            return 0L
        }
        log.debug("countIssuesInStatus statusKey={} projectCount={}", statusKey, projectIds.size)
        return dsl
            .selectCount()
            .from(issuesTable)
            .where(stateKeyField.eq(statusKey))
            .and(projectIdField.`in`(projectIds))
            .and(deletedAtField.isNull)
            .fetchOne(0, Long::class.java)
            ?: 0L
    }

    /**
     * [scopes] 의 (프로젝트, 이슈 타입) 범위 안에서 [statusKey] 를 쓰는 살아 있는 이슈를 센다.
     *
     * 같은 필터를 쓰는 프로젝트(= 같은 스킴)를 묶어 OR 항을 스킴 수만큼만 만든다 — issue-tracking 워커의
     * `BulkOperationRepository.statusMigrationTargets` 와 같은 모양이다. 빈 스코프는 [countIssuesInStatus]
     * 와 같은 이유로 조기에 0 이다(빈 OR 이 「조건 없음」으로 풀리면 전체를 센다).
     */
    @Transactional(readOnly = true)
    override fun countIssuesInStatusWithin(
        statusKey: String,
        scopes: Map<UUID, StatusMigrationTypeFilter>,
    ): Long {
        if (scopes.isEmpty()) return 0L
        val inScope =
            scopes.entries
                .groupBy({ it.value }, { it.key })
                .map { (filter, projectIds) -> projectIdField.`in`(projectIds).and(typeCondition(filter)) }
        return dsl
            .selectCount()
            .from(issuesTable)
            .where(stateKeyField.eq(statusKey))
            .and(deletedAtField.isNull)
            .and(DSL.or(inScope))
            .fetchOne(0, Long::class.java)
            ?: 0L
    }

    /** `issues.type_id` 는 NOT NULL(V005)이라 `NOT IN` 이 NULL 에 삼켜지지 않는다. */
    private fun typeCondition(filter: StatusMigrationTypeFilter): Condition =
        when {
            filter.exceptListed && filter.issueTypeIds.isEmpty() -> DSL.noCondition()
            filter.exceptListed -> typeIdField.notIn(filter.issueTypeIds)
            filter.issueTypeIds.isEmpty() -> DSL.falseCondition()
            else -> typeIdField.`in`(filter.issueTypeIds)
        }

    /** 빈 스코프는 [countIssuesInStatus] 와 같은 이유로 조기에 끊는다. */
    @Transactional(readOnly = true)
    override fun countByTypeAndStatus(projectIds: Set<UUID>): List<IssueTypeStatusCount> {
        if (projectIds.isEmpty()) return emptyList()
        val count = DSL.count()
        return dsl
            .select(typeIdField, stateKeyField, count)
            .from(issuesTable)
            .where(projectIdField.`in`(projectIds))
            .and(deletedAtField.isNull)
            .groupBy(typeIdField, stateKeyField)
            .fetch { IssueTypeStatusCount(it.get(typeIdField), it.get(stateKeyField), it.get(count).toLong()) }
    }
}
