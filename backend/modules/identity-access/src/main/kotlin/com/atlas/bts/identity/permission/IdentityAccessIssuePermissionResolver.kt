// 이슈 권한 prod 판정기 — 멤버 게이트 + role_permissions 매트릭스. FR-PM-02 stub 교체.

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.issuesecurity.IssueSecurityDecider
import com.atlas.bts.identity.issuesecurity.IssueSecurityLookup
import com.atlas.bts.identity.issuesecurity.IssueSecuritySchemeRepository
import com.atlas.bts.identity.issuesecurity.MemberType
import com.atlas.bts.identity.issuesecurity.SecurityLevelMember
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
 * TRANSITION — FR-PM-04(전환 권한)에서 매트릭스로 이관한다.
 * HARD_DELETE — DATA.md §3 하드 삭제 ADR 결정 후 별도 확장.
 * 이관 시 [IssuePermission.toCodeOrNull] 매핑 추가 + 이 KDoc 정책 메모 제거.
 *
 * ## VIEW 보안 등급 게이트 (FR-PM-06 PR-B)
 * VIEW(IssueScope.Issue) 는 VIEW_ISSUE 매트릭스를 통과한 뒤 보안 등급 멤버십 게이트를 추가로 통과해야
 * 한다([passesSecurityGate]). 등급이 NULL 이면 공개라 통과하고, 등급이 지정됐으면 멤버 5타입(OR) 중
 * 하나를 충족해야 한다. 멤버가 아니면 false → 컨트롤러가 404(존재 숨김, FR-PM-05 일관).
 * 게이트는 VIEW 의 Issue 범위에만 적용한다. BROWSE 목록 필터는 [com.bts.shared.permission.IssueSecurityDirectory]
 * 가 담당한다(T10). **관리자 우회 없음(ADR §결정5)** — resolver 는 isSystemAdmin 을 호출하지 않으며
 * 멤버십이 유일 통과 수단이다.
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
    private val securityLookup: IssueSecurityLookup,
    private val securitySchemeRepo: IssueSecuritySchemeRepository,
    private val userGroupRepo: UserGroupRepository,
) : IssuePermissionResolver {
    // ReturnCount: guard-clause early return 5개(프로젝트·멤버·범위밖·매트릭스·보안게이트 단계별 거부/허용).
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
        val code = permission.toCodeOrNull()
        if (code != null && !schemeRepo.roleHasPermission(projectId, membership.role.name, code)) {
            return false // 매트릭스 미보유 → 거부
        }
        // 보안 등급 게이트 — 이슈 스코프의 **내용 접근 계열 전부**가 추가 통과해야 한다(FR-PM-06).
        //
        // ★2026-07-27 확대. 이전에는 VIEW 에만 걸려, 기밀 이슈를 **볼 수 없는** 멤버가 매트릭스에
        // EDIT_ISSUE 만 있으면 그 이슈를 수정·전환·삭제할 수 있었다(댓글 수정·삭제, 이슈 PATCH 포함).
        // 볼 수 없는 것을 바꿀 수는 더더욱 없어야 한다.
        //
        // ★매트릭스 판정(code == null) 뒤에 둔다. TRANSITION·HARD_DELETE 는 toCodeOrNull 매핑이 없어
        // 매트릭스를 건너뛰는데, 게이트를 그 앞의 조기 반환에 두면 그 둘이 게이트도 빠져나간다.
        if (scope is IssueScope.Issue && permission in SECURITY_GATED_PERMISSIONS) {
            return passesSecurityGate(actorId, scope.key, membership.role.name)
        }
        return true
    }

    /**
     * VIEW 보안 등급 게이트 — actor 가 이슈의 보안 등급 멤버(5타입 OR) 자격을 충족하는지 판정한다.
     *
     * 1. [IssueSecurityLookup] 으로 이슈의 (security_level_id, reporter_id, assignee_id) 를 읽는다.
     *    이슈가 없으면(소프트삭제·미존재) `false`(기존 404 일관).
     * 2. 등급이 NULL 이면 공개라 [IssueSecurityDecider] 가 곧장 통과시킨다(멤버 조회 불요).
     * 3. 등급이 지정됐으면 등급 멤버 목록과 actor 의 역할·그룹 소속을 모아 [IssueSecurityDecider] 에 위임한다.
     *    그룹 소속은 등급의 GROUP 멤버에 대해서만 [UserGroupRepository.isMemberOf] 를 호출해 전 그룹 순회를 피한다.
     *
     * **관리자 우회 없음** — 이 함수는 isSystemAdmin 을 호출하지 않는다.
     */
    private fun passesSecurityGate(
        actorId: UUID,
        issueKey: String,
        actorRole: String,
    ): Boolean {
        val context = securityLookup.lookup(issueKey) ?: return false // 이슈 없음 → 404 일관
        val levelId = context.securityLevelId
        val members = if (levelId == null) emptyList() else securitySchemeRepo.listMembers(levelId)
        return IssueSecurityDecider.isAllowed(
            actorId = actorId,
            securityLevelId = levelId,
            reporterId = context.reporterId,
            assigneeId = context.assigneeId,
            members = members,
            actorProjectRole = actorRole,
            actorGroupIds = actorGroupIds(actorId, members),
        )
    }

    /**
     * 등급의 GROUP 멤버 중 actor 가 실제로 소속된 그룹 id 집합을 구성한다.
     *
     * 등급에 등록된 GROUP 멤버에 대해서만 [UserGroupRepository.isMemberOf] 단건 조회를 수행해
     * 효율을 확보한다(전 그룹 순회 금지). REPORTER/ASSIGNEE/USER/PROJECT_ROLE 멤버는 무시한다.
     */
    private fun actorGroupIds(
        actorId: UUID,
        members: List<SecurityLevelMember>,
    ): Set<UUID> =
        members
            .asSequence()
            .filter { it.memberType == MemberType.GROUP }
            .mapNotNull { it.memberValue }
            .map { UUID.fromString(it) }
            .filterTo(mutableSetOf()) { groupId -> userGroupRepo.isMemberOf(groupId, actorId) }

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
 * | SET_SECURITY    | SET_ISSUE_SECURITY | FR-PM-06 매트릭스 위임     |
 * | TRANSITION      | null             | FR-PM-04 이관 예정           |
 * | HARD_DELETE     | null             | DATA.md §3 ADR 결정 후 도입  |
 *
 * `when` else 없이 8종 전부 명시 — enum 값 추가/리네임 시 컴파일 에러로 drift 차단.
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

/**
 * 보안 등급 게이트를 통과해야 하는 권한 집합 — **이슈 내용 접근을 전제하는 조작들**.
 *
 * `IssuePermission` 8종을 전수 판정한 결과다.
 *
 * | 권한 | 포함 | 근거 |
 * |---|---|---|
 * | VIEW · UPDATE · TRANSITION · SOFT_DELETE · HARD_DELETE | ✅ | 이슈 내용을 읽거나 바꾼다 |
 * | BROWSE · CREATE | ❌ | 프로젝트 스코프 — 특정 이슈의 등급과 무관하다 |
 * | SET_SECURITY | ❌ | 아래 참조 |
 *
 * ## `SET_SECURITY` 제외는 누락이 아니라 결정이다
 * 등급 변경 권한까지 게이트에 넣으면 **잘못 설정된 등급을 아무도 되돌릴 수 없다** —
 * 등급 멤버가 아니라서 못 보고, 못 보니 못 고친다. 우회로 없는 락아웃이라 운영상 치명적이다.
 * 반대급부로 `SET_SECURITY` 보유자에게 「등급을 자기가 볼 수 있는 것으로 바꾼 뒤 열람」 경로가
 * 남지만, 그 권한은 이미 매트릭스에서 높은 등급이고 확대 이전에도 동일했다.
 *
 * 이 결정은 `IdentityAccessIssuePermissionResolverTest` 의
 * 「SET_SECURITY 는 등급 게이트를 타지 않는다」 테스트가 고정한다 —
 * 없으면 다음 사람이 "빠뜨렸네" 하고 넣어 락아웃을 만든다.
 */
private val SECURITY_GATED_PERMISSIONS: Set<IssuePermission> =
    setOf(
        IssuePermission.VIEW,
        IssuePermission.UPDATE,
        IssuePermission.TRANSITION,
        IssuePermission.SOFT_DELETE,
        IssuePermission.HARD_DELETE,
    )
