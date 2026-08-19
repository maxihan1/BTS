// IssueStatusUsagePort 구현 — issues 단일 테이블 스칼라 count (읽기 전용)

package com.bts.workflow.adapter.outbound

import com.bts.workflow.application.port.IssueStatusUsagePort
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

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
    private val deletedAtField = DSL.field("deleted_at")

    @Transactional(readOnly = true)
    override fun countIssuesInStatus(statusKey: String): Long {
        log.debug("countIssuesInStatus statusKey={}", statusKey)
        return dsl
            .selectCount()
            .from(issuesTable)
            .where(stateKeyField.eq(statusKey))
            .and(deletedAtField.isNull)
            .fetchOne(0, Long::class.java)
            ?: 0L
    }
}
