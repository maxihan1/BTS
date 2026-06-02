// 컴포넌트 단건 응답 DTO — Component 도메인 객체를 REST 응답으로 매핑 (FR-CM-01)

package com.bts.issue.component.web.dto

import com.bts.issue.component.domain.Component
import java.util.UUID

/**
 * 컴포넌트 단건 응답 DTO.
 *
 * [Component] 도메인 객체를 REST 응답용으로 매핑한다.
 * DB 저장 후에만 응답에 포함되므로 [id] 는 null 불가.
 *
 * @property id 컴포넌트 UUID.
 * @property projectId 소속 프로젝트 UUID.
 * @property name 컴포넌트 이름.
 * @property description 선택적 설명. null 허용.
 * @property leadUserId 리드 사용자 UUID. null 이면 미지정.
 */
data class ComponentResponse(
    val id: UUID,
    val projectId: UUID,
    val name: String,
    val description: String?,
    val leadUserId: UUID?,
) {
    companion object {
        /**
         * [Component] 도메인 객체를 [ComponentResponse] 로 변환한다.
         *
         * @param component 변환 대상 도메인 객체. [Component.id] 는 non-null 이어야 한다.
         * @throws IllegalArgumentException [component.id] 가 null 인 경우.
         */
        fun from(component: Component): ComponentResponse {
            val id =
                requireNotNull(component.id) { "Component.id must not be null for response mapping" }
            return ComponentResponse(
                id = id,
                projectId = component.projectId,
                name = component.name,
                description = component.description,
                leadUserId = component.leadUserId,
            )
        }
    }
}
