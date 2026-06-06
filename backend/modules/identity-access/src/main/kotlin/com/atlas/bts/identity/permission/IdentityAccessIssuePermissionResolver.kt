// 이슈 권한 prod 판정기 — 멤버 게이트 + role_permissions 매트릭스. FR-PM-02 stub 교체.

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [IssuePermissionResolver] prod 구현체 (FR-PM-02).
 *
 * ## 판정 알고리즘
 * 1. [resolveProjectId] — scope → projectId 해석. null이면 프로젝트 미존재(false).
 * 2. 멤버 게이트 — [ProjectMembershipRepository.findByProjectAndUser] 결과가 null(비멤버)이면 false.
 * 3. [IssuePermission.toCodeOrNull] — 매트릭스 위임 대상(BROWSE/VIEW/CREATE/UPDATE/SOFT_DELETE)이면
 *    권한 코드 반환, 범위 밖이면 null.
 * 4. code == null(범위 밖) → 멤버이므로 true (임시 정책, 잔여 항목 이관 예정).
 *    code != null → [PermissionSchemeRepository.roleHasPermission] 매트릭스 판정.
 *
 * ## 멤버 게이트 의도
 * AlwaysAllow("인증되면 누구나")보다 "비멤버 완전 차단"이 명확히 안전하다.
 * deny-by-default 하한 역할을 수행하며, 범위 밖 권한(TRANSITION/HARD_DELETE)도
 * 비멤버는 거부된다.
 *
 * ## 범위 밖 임시 정책 (잔여 항목 이관 예정)
 * BROWSE/VIEW — FR-PM-05(가시성 제어)에서 role_permissions 매트릭스로 이관 완료.
 *   (BROWSE → BROWSE_PROJECT, VIEW → VIEW_ISSUE)
 * TRANSITION — FR-PM-04(전이 권한)에서 매트릭스로 이관한다.
 * HARD_DELETE — DATA.md §3 하드 삭제 ADR 결정 후 별도 확장.
 * 이관 시 [IssuePermission.toCodeOrNull] 매핑 추가 + 이 KDoc 정책 메모 제거.
 *
 * ## @Profile 배타성
 * `@Profile("prod")` — `AlwaysAllowIssuePermissionResolver`는 `@Profile("!prod")`이므로
 * 두 Bean이 동시에 활성화되지 않는다. prod 외 환경에서는 stub이 자동 선택된다.
 * (결정 G1 — FR-AU-12 stub 교체를 본 PR이 흡수)
 *
 * @see IssuePermissionResolver
 * @see PermissionSchemeRepository
 */
@Component
@Profile("prod")
class IdentityAccessIssuePermissionResolver(
    private val projectDirectory: ProjectDirectory,
    private val membershipRepo: ProjectMembershipRepository,
    private val schemeRepo: PermissionSchemeRepository,
) : IssuePermissionResolver {
    // ReturnCount: guard-clause early return 4개(프로젝트·멤버·범위밖·매트릭스 단계별 거부/허용).
    // DEVELOPMENT.md §2.3 Early return 권장 정책에 부합 — 전역 임계 완화 대신 국소 Suppress.
    @Suppress("ReturnCount")
    override fun hasPermission(
        actorId: UUID,
        permission: IssuePermission,
        scope: IssueScope,
    ): Boolean {
        val projectId = resolveProjectId(scope) ?: return false // 프로젝트 없음 → 거부
        val membership =
            membershipRepo.findByProjectAndUser(projectId, actorId)
                ?: return false // 비멤버 → 거부
        val code = permission.toCodeOrNull() ?: return true // 범위 밖 → 멤버 통과
        return schemeRepo.roleHasPermission(projectId, membership.role.name, code)
    }

    /**
     * IssueScope → projectId 해석.
     *
     * - [IssueScope.Global] — 본 FR 미사용. null 반환(거부).
     * - [IssueScope.Project] — key를 [ProjectDirectory.resolveKeyToId]로 직접 해석.
     * - [IssueScope.Issue] — issueKey prefix("ATLAS-1" → "ATLAS")를 파싱해 해석.
     *   issueKey prefix == projectKey 불변식은 issue-tracking BC 보장
     *   (DATA.md §1.1 이슈 키 영속성 + IssueKeyPolicy).
     */
    private fun resolveProjectId(scope: IssueScope): UUID? =
        when (scope) {
            is IssueScope.Global -> null
            is IssueScope.Project -> projectDirectory.resolveKeyToId(scope.key)
            is IssueScope.Issue -> projectDirectory.resolveKeyToId(scope.key.substringBefore('-'))
        }
}

/**
 * [IssuePermission] → permission_code 매핑.
 *
 * | IssuePermission | permission_code  | 비고                        |
 * |-----------------|------------------|-----------------------------|
 * | BROWSE          | BROWSE_PROJECT   | FR-PM-05 매트릭스 위임       |
 * | VIEW            | VIEW_ISSUE       | FR-PM-05 매트릭스 위임       |
 * | CREATE          | CREATE_ISSUE     | FR-PM-02 매트릭스 위임       |
 * | UPDATE          | EDIT_ISSUE       | FR-PM-02 매트릭스 위임       |
 * | SOFT_DELETE     | DELETE_ISSUE     | FR-PM-02 매트릭스 위임       |
 * | TRANSITION      | null             | FR-PM-04 이관 예정           |
 * | HARD_DELETE     | null             | DATA.md §3 ADR 결정 후 도입  |
 *
 * `when` else 없이 7종 전부 명시 — enum 값 추가/리네임 시 컴파일 에러로 drift 차단.
 */
private fun IssuePermission.toCodeOrNull(): String? =
    when (this) {
        IssuePermission.BROWSE -> "BROWSE_PROJECT"
        IssuePermission.VIEW -> "VIEW_ISSUE"
        IssuePermission.CREATE -> "CREATE_ISSUE"
        IssuePermission.UPDATE -> "EDIT_ISSUE"
        IssuePermission.SOFT_DELETE -> "DELETE_ISSUE"
        IssuePermission.SET_SECURITY -> "SET_ISSUE_SECURITY"
        IssuePermission.TRANSITION, IssuePermission.HARD_DELETE -> null
    }
