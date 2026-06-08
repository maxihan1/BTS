// 필드 수준 권한 visible/editable 판정 순수 함수(I/O 없음, 호출자가 규칙·그룹 주입) (FR-PM-07 PR-A Task 4)

package com.atlas.bts.identity.fieldpermission

import com.atlas.bts.identity.fieldpermission.domain.FieldAccessLevel
import com.atlas.bts.identity.fieldpermission.domain.FieldPermission
import com.bts.shared.permission.FieldRef
import java.util.UUID

/**
 * 필드 수준 권한의 visible/editable 부분집합을 계산하는 순수 함수.
 *
 * 판정에 필요한 모든 사실(프로젝트 규칙 목록·actor 그룹 집합·후보 필드)을 인자로 주입받으며,
 * 이 객체는 DB·네트워크 등 어떤 I/O 도 수행하지 않는다(조회는 호출자=resolver 책임). 그 덕에
 * 결정 로직을 격리해 단위 테스트로 망라할 수 있다(선례: `IssueSecurityDecider`).
 *
 * ## opt-in 제한 모델 (spec §6, EC1)
 * 어떤 필드에 규칙이 0건이면 그 필드는 제한 대상이 아니므로 모든 actor 에게 포함된다.
 * 규칙이 1건이라도 있으면 그 필드는 제한 대상이 되어, 명시된 그룹 멤버에게만 허용된다.
 *
 * ## EDIT ⊃ VIEW (spec §6, S7/EC3)
 * - **visible** — (VIEW ∪ EDIT) 규칙의 group_id 중 하나라도 actor 그룹에 속하면 포함.
 *   즉 EDIT 규칙만 있고 VIEW 규칙이 없어도 열람 가능하다.
 * - **editable** — EDIT 규칙의 group_id 중 하나라도 actor 그룹에 속하면 포함.
 *   editable 은 항상 visible 의 부분집합이다(EDIT ⊆ VIEW∪EDIT).
 *
 * ## 관리자 우회 없음 (spec §6, S5/EC10)
 * 이 함수에는 isSystemAdmin/PROJECT_ADMIN role 단락 경로가 전혀 없다.
 * actor 의 그룹 멤버십(OR)만이 제한 필드를 통과하는 유일한 수단이다.
 *
 * ## 그룹 OR (spec §6, EC2)
 * actor 가 여러 그룹에 속할 때, 해당 필드 규칙 중 actor 그룹과 교집합이 비지 않으면 통과한다.
 */
object FieldVisibilityDecider {
    /**
     * [candidates] 중 actor 가 열람 가능한 필드의 부분집합을 반환한다.
     *
     * @param candidates 판정 대상 필드 집합.
     * @param projectRules 해당 프로젝트의 모든 필드 권한 규칙(필드별·그룹별 행).
     * @param actorGroupIds actor 가 소속된 그룹 식별자 집합.
     * @return 규칙이 없거나(opt-in) VIEW∪EDIT 그룹 교집합이 있는 필드의 부분집합.
     */
    fun visibleFields(
        candidates: Set<FieldRef>,
        projectRules: List<FieldPermission>,
        actorGroupIds: Set<UUID>,
    ): Set<FieldRef> {
        val byField = projectRules.groupBy { FieldRef(it.fieldKind, it.fieldKey) }
        return candidates.filterTo(mutableSetOf()) { field ->
            val rules = byField[field]
            // 규칙 없음 → opt-in 으로 항상 포함.
            rules == null || rules.any { actorGroupIds.contains(it.groupId) }
        }
    }

    /**
     * [candidates] 중 actor 가 편집 가능한 필드의 부분집합을 반환한다.
     *
     * 규칙이 없는 필드는 opt-in 으로 항상 포함되고, 규칙이 있는 필드는 EDIT 규칙의 group_id 와
     * actor 그룹의 교집합이 있을 때만 포함된다. 반환 집합은 [visibleFields] 의 부분집합이다.
     *
     * @param candidates 판정 대상 필드 집합.
     * @param projectRules 해당 프로젝트의 모든 필드 권한 규칙.
     * @param actorGroupIds actor 가 소속된 그룹 식별자 집합.
     * @return 규칙이 없거나(opt-in) EDIT 그룹 교집합이 있는 필드의 부분집합.
     */
    fun editableFields(
        candidates: Set<FieldRef>,
        projectRules: List<FieldPermission>,
        actorGroupIds: Set<UUID>,
    ): Set<FieldRef> {
        val byField = projectRules.groupBy { FieldRef(it.fieldKind, it.fieldKey) }
        return candidates.filterTo(mutableSetOf()) { field ->
            val rules = byField[field]
            // 규칙 없음 → opt-in 으로 항상 포함. 규칙 있음 → EDIT 행의 그룹 교집합만.
            rules == null ||
                rules.any { it.accessLevel == FieldAccessLevel.EDIT && actorGroupIds.contains(it.groupId) }
        }
    }
}
