// 그룹 멤버십 가시성 보안 포트(GroupMembershipPort)의 identity-access 구현 — FR-SR-03 PR2 Task 4

package com.atlas.bts.identity.group

import com.bts.shared.membership.GroupMembershipPort
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [GroupMembershipPort] 의 identity-access 구현 (FR-SR-03 PR2 Task 4).
 *
 * group_memberships(자기 BC 테이블)에서 사용자가 속한 그룹 id 를 직접 조회한다.
 */
@Component
class GroupMembershipAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : GroupMembershipPort {
    /**
     * 사용자([userId])가 속한 그룹 id 집합을 canonical UUID 문자열로 반환한다.
     *
     * 미소속이면 빈 Set(fail-closed 방향, allow-all 아님)을 반환한다.
     */
    @Transactional(readOnly = true)
    override fun groupIdsOf(userId: UUID): Set<String> =
        jdbc.query(
            "SELECT group_id FROM group_memberships WHERE user_id = :userId",
            mapOf("userId" to userId),
        ) { rs, _ ->
            rs.getObject("group_id", UUID::class.java).toString()
        }.toSet()
}
