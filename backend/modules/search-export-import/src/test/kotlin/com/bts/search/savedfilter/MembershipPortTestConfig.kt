// 테스트 전용 멤버십 포트 stub 설정 — userId별 그룹/프로젝트 집합을 시드 맵 기반으로 반환 (FR-SR-03 Task 9)

package com.bts.search.savedfilter

import com.bts.shared.membership.GroupMembershipPort
import com.bts.shared.membership.ProjectMembershipPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.UUID

/**
 * GroupMembershipPort / ProjectMembershipPort 테스트 전용 stub 설정.
 *
 * 각 테스트가 [groupMemberships] / [projectMemberships] 맵을 직접 조작해
 * userId별 멤버십을 시드한다. 미시드 userId 는 emptySet 반환(fail-closed).
 *
 * ## C11 방지
 * userId별로 다른 집합을 반환하므로, userId 무관 동일집합 stub이
 * 음성 케이스를 가짜통과시키는 문제(vacuous green)가 발생하지 않는다.
 *
 * ## 사용 패턴
 * ```kotlin
 * // @BeforeEach
 * membershipConfig.projectMemberships[bob] = setOf("ATL")
 * membershipConfig.groupMemberships[carol] = setOf("g-devs")
 * ```
 *
 * ## fail-closed 계약
 * 미시드 userId 는 emptySet 을 반환한다.
 * allow-all default 를 두지 않아 공유 가시성 누출을 방지한다.
 */
@TestConfiguration
open class MembershipPortTestConfig {
    /** userId → 그룹 ID 집합 시드 맵. 테스트가 @BeforeEach 에서 clear() 후 재시드한다. */
    val groupMemberships: MutableMap<UUID, Set<String>> = mutableMapOf()

    /** userId → 프로젝트 키 집합 시드 맵. 테스트가 @BeforeEach 에서 clear() 후 재시드한다. */
    val projectMemberships: MutableMap<UUID, Set<String>> = mutableMapOf()

    /**
     * userId-aware GroupMembershipPort stub 빈.
     * [groupMemberships] 맵에서 userId 로 조회하며, 미등록 userId 는 emptySet(fail-closed).
     */
    @Bean
    open fun groupMembershipPort(): GroupMembershipPort =
        object : GroupMembershipPort {
            override fun groupIdsOf(userId: UUID): Set<String> = groupMemberships[userId] ?: emptySet()
        }

    /**
     * userId-aware ProjectMembershipPort stub 빈.
     * [projectMemberships] 맵에서 userId 로 조회하며, 미등록 userId 는 emptySet(fail-closed).
     */
    @Bean
    open fun projectMembershipPort(): ProjectMembershipPort =
        object : ProjectMembershipPort {
            override fun projectKeysOf(userId: UUID): Set<String> = projectMemberships[userId] ?: emptySet()
        }
}
