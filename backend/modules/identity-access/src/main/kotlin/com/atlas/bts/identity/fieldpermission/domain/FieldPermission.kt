// 필드 수준 권한 규칙 도메인 모델 — field_key 정규화/길이 불변식 (FR-PM-07 PR-A)

package com.atlas.bts.identity.fieldpermission.domain

import com.bts.shared.permission.FieldKind
import java.util.UUID

/**
 * 필드 권한 규칙이 허용하는 접근 수준.
 *
 * `field_permissions.access_level` 컬럼(`CHECK IN ('VIEW','EDIT')`)과 1:1 매핑된다.
 * [EDIT] 는 [VIEW] 를 포함한다(EDIT ⊃ VIEW). 포함관계의 실제 판정은
 * resolver(FR-PM-07 PR-B)에서 수행하며, 본 enum 은 값 표현만 담당한다.
 *
 * @property VIEW 읽기 허용.
 * @property EDIT 쓰기 허용(읽기 자동 포함).
 */
enum class FieldAccessLevel {
    VIEW,
    EDIT,
}

/**
 * 필드 수준 권한 규칙 도메인 모델.
 *
 * `(projectId, fieldKind, fieldKey, groupId, accessLevel)` 한 조합이 "이 프로젝트의 이 필드를
 * 이 그룹 멤버에게 이 수준으로 허용한다"는 화이트리스트 규칙 한 건이다.
 * `field_permissions` 테이블 행과 1:1 매핑되며 모든 필드는 불변(val)이다.
 *
 * ## opt-in 제한 모델
 * 어떤 필드에 규칙이 한 건도 없으면 그 필드는 모든 멤버에게 열린다(기본 허용).
 * 규칙이 한 건이라도 추가되면 그 필드는 제한 대상이 되어 명시된 그룹 멤버에게만 허용된다.
 * 이 판정은 resolver(FR-PM-07 PR-B) 책임이며, 본 모델은 규칙 한 건의 표현·검증만 담당한다.
 *
 * ## 생성 규칙
 * 신규 규칙은 반드시 [create] 팩토리를 경유해 [fieldKey] 정규화/길이 불변식을 보장해야 한다.
 * **CORE field_key 화이트리스트 검증은 여기 두지 않는다** — 어떤 키가 유효한 코어 필드인지는
 * 도메인 모델이 아니라 ApplicationService(FR-PM-07 PR-A Task 5)가 판정한다.
 *
 * ## 필드
 * - [id]: 규칙 식별자. 신규 생성 시 `null`이며 영속 계층이 채운다.
 * - [projectId]: 규칙이 속한 프로젝트. 필드 권한은 항상 프로젝트 스코프.
 * - [fieldKind]: 대상 필드 종류(CORE/CUSTOM). shared-kernel [FieldKind] 재사용.
 * - [fieldKey]: 대상 필드 키. trim 후 비어 있을 수 없으며 [MAX_FIELD_KEY_LENGTH]자 이하.
 * - [groupId]: 허용 대상 사용자 그룹. `user_groups` 삭제 시 FK CASCADE 로 규칙 동반 삭제.
 * - [accessLevel]: 허용 수준(VIEW/EDIT).
 */
data class FieldPermission(
    val id: UUID?,
    val projectId: UUID,
    val fieldKind: FieldKind,
    val fieldKey: String,
    val groupId: UUID,
    val accessLevel: FieldAccessLevel,
) {
    companion object {
        /** 필드 키 최대 길이 (`field_permissions.field_key VARCHAR(64)` 와 일치). */
        const val MAX_FIELD_KEY_LENGTH = 64

        /**
         * 정규화/검증을 거쳐 신규 [FieldPermission] 규칙을 생성한다.
         *
         * [fieldKey] 는 trim 후 비어 있지 않아야 하며 [MAX_FIELD_KEY_LENGTH]자를 넘을 수 없다.
         * CORE 키의 화이트리스트 여부는 본 팩토리가 검증하지 않는다(ApplicationService 책임).
         *
         * @param projectId 규칙이 속한 프로젝트 식별자.
         * @param fieldKind 대상 필드 종류.
         * @param fieldKey 대상 필드 키 (정규화 전 원본).
         * @param groupId 허용 대상 그룹 식별자.
         * @param accessLevel 허용 수준.
         * @return 불변식을 만족하는 신규 [FieldPermission] ([id] 는 `null`).
         * @throws IllegalArgumentException 필드 키가 비어 있거나 길이 제약을 위반한 경우.
         */
        fun create(
            projectId: UUID,
            fieldKind: FieldKind,
            fieldKey: String,
            groupId: UUID,
            accessLevel: FieldAccessLevel,
        ): FieldPermission {
            val normalizedKey = fieldKey.trim()
            require(normalizedKey.isNotEmpty()) { "필드 키는 비어 있을 수 없습니다." }
            require(normalizedKey.length <= MAX_FIELD_KEY_LENGTH) {
                "필드 키는 ${MAX_FIELD_KEY_LENGTH}자를 초과할 수 없습니다."
            }

            return FieldPermission(
                id = null,
                projectId = projectId,
                fieldKind = fieldKind,
                fieldKey = normalizedKey,
                groupId = groupId,
                accessLevel = accessLevel,
            )
        }
    }
}
