// 필드 수준 권한 판정 cross-BC 포트(공용) — prod 실판정은 FR-PM-07 PR-B(identity-access)에서 채운다.

package com.bts.shared.permission

import java.util.UUID

enum class FieldKind {
    CORE,
    CUSTOM,
}

data class FieldRef(val kind: FieldKind, val key: String)

interface FieldPermissionResolver {
    fun visibleFields(
        actorId: UUID,
        projectId: UUID,
        candidates: Set<FieldRef>,
    ): Set<FieldRef>

    fun editableFields(
        actorId: UUID,
        projectId: UUID,
        candidates: Set<FieldRef>,
    ): Set<FieldRef>
}
