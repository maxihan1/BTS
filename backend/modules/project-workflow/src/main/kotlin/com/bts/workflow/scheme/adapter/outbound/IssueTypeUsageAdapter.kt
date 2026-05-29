// IssueTypeUsagePort 구현 — workflow_scheme_issue_type_mappings 단일 테이블 스칼라 count

package com.bts.workflow.scheme.adapter.outbound

import com.bts.workflow.scheme.application.port.IssueTypeUsagePort
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [IssueTypeUsagePort] 구현체.
 *
 * project-workflow BC 가 소유한 `workflow_scheme_issue_type_mappings` 테이블에서
 * 특정 이슈 타입을 참조하는 매핑 수를 단일 스칼라 COUNT 쿼리로 반환한다.
 *
 * ## BC 격리 예외 — adapter/outbound 위치 근거
 * 데이터 소유자(project-workflow BC)가 port adapter 를 구현하는 패턴.
 * 방향 선례: [com.bts.issue.port.outbound.IssuePermissionResolver] (issue-tracking BC 정의)
 * 와 동일하게, port 는 소비 BC(issue-tracking) 에, adapter 는 데이터 소유 BC(project-workflow) 에 위치한다.
 *
 * ## cartesian product 회피
 * 다중 LEFT JOIN + count 는 cartesian product 위험이 있다 (learnings 2026-05-26 PR#31).
 * 본 구현은 단일 테이블 `selectCount().from(...).where(issue_type_id.eq(...))` 를 사용한다.
 *
 * ## ADR 참조
 * - `docs/adr/2026-05-29-issue-type-cross-bc-introduction.md`
 * - `docs/adr/workflow-bc-cross-bc-port.md`
 *
 * @param dsl jOOQ DSLContext (SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점).
 * @see IssueTypeUsagePort
 */
@Service
class IssueTypeUsageAdapter(private val dsl: DSLContext) : IssueTypeUsagePort {
    private val log = LoggerFactory.getLogger(javaClass)

    // V004 테이블 — jOOQ codegen 범위 밖. SchemeIssueTypeMappingRepository 와 동일 동적 참조 패턴.
    private val mappingsTable = DSL.table("workflow_scheme_issue_type_mappings")
    private val issueTypeIdField = DSL.field("issue_type_id", Long::class.java)

    /**
     * `workflow_scheme_issue_type_mappings` 에서 [issueTypeId] 를 참조하는 매핑 수를 반환한다.
     *
     * @param issueTypeId 조회 대상 이슈 타입 PK.
     * @return 참조 중인 스킴 매핑 건수. 참조 없으면 0.
     */
    @Transactional(readOnly = true)
    override fun countSchemeMappings(issueTypeId: Long): Long {
        log.debug("countSchemeMappings issueTypeId={}", issueTypeId)
        return dsl
            .selectCount()
            .from(mappingsTable)
            .where(issueTypeIdField.eq(issueTypeId))
            .fetchOne(0, Long::class.java)
            ?: 0L
    }
}
