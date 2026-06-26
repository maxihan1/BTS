// 사용자가 멤버인 프로젝트 키 집합을 cross-BC read-only로 조회하는 ProjectMembershipPort 구현체

package com.atlas.bts.identity.project

import com.bts.shared.membership.ProjectMembershipPort
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [ProjectMembershipPort] identity-access BC 구현체 (FR-SR-03 PR2 Task 5).
 *
 * project_memberships(identity-access)와 projects(issue-tracking)를 JOIN 해
 * 사용자가 멤버로 속한 활성 프로젝트의 키 집합을 반환한다.
 *
 * @param jdbc projects/project_memberships read-only 조회용 템플릿
 */
@Component
@Transactional(readOnly = true)
class ProjectMembershipAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : ProjectMembershipPort {

    /**
     * [userId] 가 멤버로 속한 프로젝트 중 소프트삭제되지 않은 프로젝트의 키 집합을 반환한다.
     *
     * 멤버십이 없으면 빈 Set 을 반환한다(fail-closed).
     */
    override fun projectKeysOf(userId: UUID): Set<String> =
        jdbc.query(
            """
            SELECT p.key
            FROM project_memberships m
            JOIN projects p ON m.project_id = p.id
            WHERE m.user_id = :userId
              AND p.deleted_at IS NULL
            """,
            mapOf("userId" to userId),
        ) { rs, _ -> rs.getString("key") }
            .toSet()
}
