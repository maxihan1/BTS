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
    private val grantRepo: GlobalPermissionGrantRepository,
) : SystemPermissionResolver {
    override fun isSystemAdmin(actorId: UUID): Boolean = repo.findRolesByUser(actorId).contains(SystemRole.SYSTEM_ADMIN)

    /**
     * 전역 권한 [permission] 보유 여부 (FR-PM-10, ADR D-2).
     *
     * 판정 = grant 보유 **또는** SYSTEM_ADMIN. SYSTEM_ADMIN 은 전역 권한의 상위집합이므로 별도 grant 없이
     * 통과한다 — FR-PM-08 ADR D3 "전역 판정 = SYSTEM_ADMIN 보유 여부" 와의 연속성이며, 빈 DB 에서
     * CREATE_PROJECT 보유자가 0명이 되는 부트스트랩 공백을 막는다.
     *
     * [GlobalPermissionGrantRepository.hasGrant] 는 grant 축만 본다 — `OR isSystemAdmin` 항을 합성하는 것이
     * 이 메서드의 책임이다. 두 항 모두 미부여·판정 불가는 `false` 로 수렴한다(deny-by-default).
     *
     * ## 이 override 를 지우면 안 된다
     * 지우면 인터페이스 default 가 살아나 판정이 [isSystemAdmin] 과 같아지고, FR-PM-10 이 조용히
     * SYSTEM_ADMIN 전용으로 되돌아간다(기각된 안). fail-closed 라 장애로 드러나지도 않는다.
     * 회귀 가드는 IdentityAccessSystemPermissionResolverGlobalPermissionTest 의
     * "grant 보유 비-SYSTEM_ADMIN" 케이스이며, 그 테스트의 판별자는 행위자가 SYSTEM_ADMIN 이 아님을
     * 먼저 못박는 선단언이다.
     *
     * @param actorId 판정 대상 사용자 UUID.
     * @param permission 전역 권한코드 (SDD 12 정본 — 예 `CREATE_PROJECT`).
     * @return 보유 시 `true`. 미부여/판정 불가는 `false`.
     */
    override fun hasGlobalPermission(
        actorId: UUID,
        permission: String,
    ): Boolean = grantRepo.hasGrant(actorId, permission) || isSystemAdmin(actorId)
}
