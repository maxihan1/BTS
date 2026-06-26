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
 * ## 역할
 * 필터 공유 가시성(FR-SR-03) 판정에서 "사용자가 어떤 그룹에 속하는가"를 cross-BC 로 제공한다.
 * 소비 측(search-export-import BC)은 이 포트만으로 그룹 멤버십을 확인한다.
 *
 * ## 의존 테이블/컬럼 (identity-access 자기 BC, V015 마이그레이션)
 * - `group_memberships(group_id UUID, user_id UUID)` — 사용자-그룹 N:M 멤버십.
 * - 역방향 조회는 `ix_group_memberships_user`(user_id 인덱스)를 활용한다.
 *
 * [UserGroupRepository] 는 그룹 중심(listMemberIds/isMemberOf)이라, 사용자→그룹 역방향 조회는
 * 이 어댑터가 자기 테이블을 [NamedParameterJdbcTemplate] 바인딩으로 직접 수행한다.
 *
 * ## SQL 인젝션 방어
 * 사용자 입력(userId)은 전부 `:userId` prepared-statement 바인딩으로만 전달하며 SQL 문자열
 * 결합은 하지 않는다 (DEVELOPMENT.md §1.1-3).
 *
 * ## fail-closed
 * 멤버십이 없으면 **빈 Set**(그룹 0개)을 반환한다. 빈 Set 은 "어느 그룹에도 속하지 않음"이며
 * allow-all 이 아니다 — 공유 가시성 누출을 막는 fail-closed 방향이다.
 *
 * @see GroupMembershipPort 동일 fail-closed 계약을 정의하는 cross-BC 포트
 */
@Component
class GroupMembershipAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : GroupMembershipPort {
    /**
     * 사용자([userId])가 속한 그룹 id 집합을 canonical UUID 문자열(`UUID.toString()`, 소문자)로 반환한다.
     *
     * 반환 형식은 saved_filter_shares.target_id(GROUP) 저장 형식과 일치한다 — 형식 drift 시
     * 조용한 매칭 실패가 발생하므로 `UUID.toString()` canonical 표기를 보장한다(통합테스트 단언).
     *
     * 멤버십이 없으면 빈 Set 을 반환한다(fail-closed 방향, allow-all 아님).
     *
     * @param userId 조회 대상 사용자 UUID
     * @return 사용자가 속한 그룹 id 문자열 집합. 빈 Set 은 "멤버십 0개"를 의미한다.
     */
    @Transactional(readOnly = true)
    override fun groupIdsOf(userId: UUID): Set<String> =
        jdbc.query(SQL_GROUP_IDS_OF, mapOf("userId" to userId)) { rs, _ ->
            rs.getObject("group_id", UUID::class.java).toString()
        }.toSet()

    private companion object {
        /** 사용자가 속한 모든 그룹 id — user_id 인덱스(ix_group_memberships_user) 활용. */
        const val SQL_GROUP_IDS_OF = """
            SELECT group_id
            FROM group_memberships
            WHERE user_id = :userId
        """
    }
}
