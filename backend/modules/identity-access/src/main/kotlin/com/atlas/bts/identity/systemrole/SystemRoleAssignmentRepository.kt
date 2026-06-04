// system_role_assignments 테이블 접근 인터페이스 (FR-PM-08 Task 2)

package com.atlas.bts.identity.systemrole

import java.util.UUID

/**
 * `system_role_assignments` 테이블 접근 인터페이스 (FR-PM-08 Task 2).
 *
 * 사용자에게 전역 [SystemRole]을 부여하고 조회한다.
 * 프로젝트 단위 멤버십(`project_memberships`)과는 별개의 축으로,
 * 프로젝트 식별자를 가지지 않는다 (docs/decisions/2026-06-04-system-admin-role.md D1).
 *
 * 트랜잭션 경계는 상위 서비스가 소유한다. 본 인터페이스 구현체는 자체 트랜잭션을 열지 않는다.
 *
 * 구현체: [JdbcSystemRoleAssignmentRepository].
 */
interface SystemRoleAssignmentRepository {

    /**
     * 사용자에게 전역 [role]을 부여한다.
     *
     * 같은 `(userId, role)` 조합이 이미 존재하면 `ON CONFLICT DO NOTHING`으로
     * 아무 변화 없이 멱등하게 반환한다 (FR-PM-08 EC4). 예외를 던지지 않는다.
     *
     * @param userId 역할을 부여할 사용자 식별자(`users.id`)
     * @param role 부여할 전역 시스템 역할
     */
    fun assign(userId: UUID, role: SystemRole)

    /**
     * 사용자에게 부여된 전역 시스템 역할 집합을 반환한다.
     *
     * @param userId 조회할 사용자 식별자
     * @return 부여된 [SystemRole] 집합. 없으면 빈 집합.
     */
    fun findRolesByUser(userId: UUID): Set<SystemRole>

    /**
     * 해당 전역 역할을 가진 사용자가 한 명이라도 존재하는지 여부를 반환한다.
     *
     * @param role 존재 여부를 확인할 전역 시스템 역할
     * @return 한 건이라도 부여되어 있으면 true, 아니면 false
     */
    fun existsByRole(role: SystemRole): Boolean
}
