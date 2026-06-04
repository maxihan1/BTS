// 전역 SYSTEM_ADMIN 판정기 — system_role_assignments 조회 위임 (FR-PM-08 Task 3)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.systemrole.SystemRole
import com.atlas.bts.identity.systemrole.SystemRoleAssignmentRepository
import com.bts.shared.permission.SystemPermissionResolver
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [SystemPermissionResolver] 구현체 (FR-PM-08).
 *
 * [SystemRoleAssignmentRepository.findRolesByUser] 로 사용자의 전역 역할을 조회하여
 * [SystemRole.SYSTEM_ADMIN] 보유 여부로 판정한다. 역할이 없으면 빈 집합이 반환되어
 * `false` 로 귀결된다(deny-by-default).
 *
 * 이 포트는 FR-PM-04(전역 관리자 전용 기능)가 소비한다.
 *
 * ## @Profile 분리 없음 (ADR D4 정정)
 * [IdentityAccessIssuePermissionResolver] 는 `@Profile("prod")` + 개발용 AlwaysAllow stub
 * 으로 분리되지만, 본 판정기는 그 패턴을 따르지 않는다. scope → projectId 해석 같은
 * 복잡한 권한 평가가 없고 `system_role_assignments` 단순 DB 조회만 수행하므로,
 * 모든 프로파일에서 동일한 실제 판정을 적용하는 것이 안전하고 명확하다.
 * AlwaysAllow stub 을 두지 않는다.
 *
 * @see SystemPermissionResolver
 * @see SystemRoleAssignmentRepository
 */
@Component
class IdentityAccessSystemPermissionResolver(
    private val repo: SystemRoleAssignmentRepository,
) : SystemPermissionResolver {
    override fun isSystemAdmin(actorId: UUID): Boolean = repo.findRolesByUser(actorId).contains(SystemRole.SYSTEM_ADMIN)
}
