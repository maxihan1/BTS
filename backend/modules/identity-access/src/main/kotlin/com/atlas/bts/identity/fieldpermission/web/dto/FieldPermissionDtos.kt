// 필드 권한 규칙 CRUD API 요청/응답 DTO (FR-PM-07 PR-A Task 5)

package com.atlas.bts.identity.fieldpermission.web.dto

import com.atlas.bts.identity.fieldpermission.domain.FieldAccessLevel
import com.atlas.bts.identity.fieldpermission.domain.FieldPermission
import com.bts.shared.permission.FieldKind
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * 필드 권한 규칙 생성 요청 바디 (FR-PM-07 PR-A Task 5).
 *
 * 형식 검증(Bean Validation)만 여기서 수행한다.
 * - CORE field_key 화이트리스트 검증, group_id 존재 검증은 ApplicationService 책임.
 * - field_key 정규화/길이 불변식은 [FieldPermission.create] 가 소유한다.
 *
 * @property fieldKind 대상 필드 종류(CORE/CUSTOM).
 * @property fieldKey 대상 필드 키(공백 불가).
 * @property groupId 허용 대상 사용자 그룹 식별자.
 * @property accessLevel 허용 수준(VIEW/EDIT).
 */
data class CreateFieldPermissionRequest(
    @field:NotNull
    val fieldKind: FieldKind?,
    @field:NotBlank
    val fieldKey: String?,
    @field:NotNull
    val groupId: UUID?,
    @field:NotNull
    val accessLevel: FieldAccessLevel?,
)

/**
 * 필드 권한 규칙 응답 DTO (FR-PM-07 PR-A Task 5).
 *
 * @property id 규칙 식별자.
 * @property fieldKind 대상 필드 종류.
 * @property fieldKey 대상 필드 키.
 * @property groupId 허용 대상 그룹 식별자.
 * @property groupName 허용 대상 그룹 이름(목록 표시용).
 * @property accessLevel 허용 수준.
 */
data class FieldPermissionResponse(
    val id: UUID,
    val fieldKind: FieldKind,
    val fieldKey: String,
    val groupId: UUID,
    val groupName: String,
    val accessLevel: FieldAccessLevel,
) {
    companion object {
        /**
         * 영속 [FieldPermission] + 그룹 이름으로 응답을 만든다.
         *
         * @param rule id 가 채워진 영속 규칙.
         * @param groupName 규칙이 가리키는 그룹의 이름.
         */
        fun from(
            rule: FieldPermission,
            groupName: String,
        ): FieldPermissionResponse =
            FieldPermissionResponse(
                id = requireNotNull(rule.id) { "영속 규칙은 id 를 가져야 한다." },
                fieldKind = rule.fieldKind,
                fieldKey = rule.fieldKey,
                groupId = rule.groupId,
                groupName = groupName,
                accessLevel = rule.accessLevel,
            )
    }
}
