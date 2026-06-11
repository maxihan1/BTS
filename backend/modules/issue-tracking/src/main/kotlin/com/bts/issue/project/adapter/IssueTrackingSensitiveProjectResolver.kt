// SensitiveProjectResolver issue-tracking adapter — projects.require_2fa jOOQ 조회 (FR-MF-04)

package com.bts.issue.project.adapter

import com.bts.issue.jooq.tables.references.PROJECTS
import com.bts.shared.permission.SensitiveProjectResolver
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [SensitiveProjectResolver] issue-tracking adapter.
 *
 * issue-tracking BC 가 소유한 `projects` 테이블의 `require_2fa` 컬럼을 jOOQ 로 조회한다.
 * identity-access BC 가 이 포트를 소비하여 MFA 강제 여부를 판정한다(FR-MF-04).
 *
 * 빈 집합은 DB 조회 없이 즉시 `false` 를 반환해 불필요한 쿼리를 차단한다.
 * soft-deleted 프로젝트(`deleted_at IS NOT NULL`)는 민감 프로젝트로 간주하지 않는다.
 */
@Repository
class IssueTrackingSensitiveProjectResolver(
    private val dsl: DSLContext,
) : SensitiveProjectResolver {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 주어진 프로젝트 id 집합 중 민감 프로젝트(require_2fa=true, deleted_at IS NULL)가
     * 하나라도 있는지 EXISTS 서브쿼리로 판정한다.
     *
     * 빈 집합은 쿼리 없이 즉시 `false`.
     *
     * @param projectIds 판정 대상 프로젝트 UUID 집합.
     * @return 민감 프로젝트가 하나라도 포함되면 `true`, 없으면 `false`.
     */
    @Transactional(readOnly = true)
    override fun anyRequiresMfa(projectIds: Set<UUID>): Boolean {
        if (projectIds.isEmpty()) {
            log.debug("IssueTrackingSensitiveProjectResolver.anyRequiresMfa projectIds=empty → false (no query)")
            return false
        }

        log.debug("IssueTrackingSensitiveProjectResolver.anyRequiresMfa projectIds.size={}", projectIds.size)

        return dsl.fetchExists(
            DSL.selectOne()
                .from(PROJECTS)
                .where(PROJECTS.ID.`in`(projectIds))
                .and(PROJECTS.REQUIRE_2FA.isTrue)
                .and(PROJECTS.DELETED_AT.isNull),
        )
    }
}
