// IssueStatusUsagePort 구현 — issues 단일 테이블 스칼라 count (읽기 전용)

package com.bts.workflow.adapter.outbound

import com.bts.workflow.application.port.IssueStatusUsagePort
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * `issues.current_state_key` 로 사용량을 센다.
 *
 * issue-tracking 의 테이블이지만 **읽기 전용 스칼라 count** 만 한다 —
 * `IssueTypeUsageAdapter` 와 같은 동적 참조 패턴이라 BC 격리를 깨지 않는다.
 * 소프트 삭제된 이슈는 세지 않는다.
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
}
