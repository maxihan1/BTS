// 필드 수준 권한 prod 판정기 — field_permissions 규칙 + actor 그룹 멤버십 (FR-PM-07 PR-A Task 4)

package com.atlas.bts.identity.fieldpermission

import com.atlas.bts.identity.fieldpermission.repository.FieldPermissionRepository
import com.atlas.bts.identity.group.UserGroupRepository
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [FieldPermissionResolver] prod 구현체 (FR-PM-07 PR-A Task 4).
 *
 * ## 판정 알고리즘 (배치 2회 + 순수 판정)
 * 1. [FieldPermissionRepository.findByProject] 1회 — 프로젝트의 모든 필드 권한 규칙.
 * 2. [UserGroupRepository.findGroupIdsByUser] 1회 — actor 가 속한 그룹 집합(N+1 회피).
 * 3. [FieldVisibilityDecider] 로 candidates 의 visible/editable 부분집합을 순수 계산.
 *
 * I/O(1·2)와 판정(3)을 분리해, 판정 규칙은 decider 단위 테스트로, 결선·거부 ground-truth 는
 * 본 클래스의 prod 통합테스트(`IdentityAccessFieldPermissionResolverTest`)로 검증한다.
 *
 * ## 포트 계약 준수 (spec §6)
 * - **opt-in (EC1)** — 규칙이 없는 필드는 항상 포함. decider 가 처리한다.
 * - **EDIT ⊃ VIEW (S7/EC3)** — editable 은 항상 visible 의 부분집합. decider 가 보장한다.
 * - **관리자 우회 없음 (S5/EC10)** — 본 클래스는 isSystemAdmin/PROJECT_ADMIN role 을
 *   **절대 참조하지 않는다**. 멤버십 리포·스킴 리포를 생성자에 두지 않음으로써 우회 경로 자체를
 *   구조적으로 차단한다. actor 의 그룹 멤버십만이 제한 필드를 통과하는 유일한 수단이다.
 *
 * ## @Profile 배타성
 * `@Profile("prod")` — issue-tracking 의 `AlwaysAllowFieldPermissionResolver` 는 `@Profile("!prod")`
 * 이므로 두 Bean 이 동시에 활성화되지 않는다. prod 외 환경에서는 stub 이 자동 선택된다.
 *
 * @see FieldPermissionResolver
 * @see FieldVisibilityDecider
 */
@Component
@Profile("prod")
class IdentityAccessFieldPermissionResolver(
    private val fieldPermissionRepo: FieldPermissionRepository,
    private val userGroupRepo: UserGroupRepository,
) : FieldPermissionResolver {
    override fun visibleFields(
        actorId: UUID,
        projectId: UUID,
        candidates: Set<FieldRef>,
    ): Set<FieldRef> {
        if (candidates.isEmpty()) return emptySet()
        val projectRules = fieldPermissionRepo.findByProject(projectId)
        val actorGroupIds = userGroupRepo.findGroupIdsByUser(actorId).toSet()
        return FieldVisibilityDecider.visibleFields(candidates, projectRules, actorGroupIds)
    }

    override fun editableFields(
        actorId: UUID,
        projectId: UUID,
        candidates: Set<FieldRef>,
    ): Set<FieldRef> {
        if (candidates.isEmpty()) return emptySet()
        val projectRules = fieldPermissionRepo.findByProject(projectId)
        val actorGroupIds = userGroupRepo.findGroupIdsByUser(actorId).toSet()
        return FieldVisibilityDecider.editableFields(candidates, projectRules, actorGroupIds)
    }
}
