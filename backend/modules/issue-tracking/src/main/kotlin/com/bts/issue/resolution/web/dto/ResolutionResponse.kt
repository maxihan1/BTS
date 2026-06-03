// ResolutionController 응답 DTO — Resolution 단건 표현 (FR-IS-07 Task B4)

package com.bts.issue.resolution.web.dto

import com.bts.issue.resolution.domain.Resolution
import java.util.UUID

/**
 * Resolution 단건 응답 DTO.
 *
 * [Resolution] 도메인 객체를 REST 응답용으로 매핑한다.
 * id 는 UUID. findAllActive 결과는 항상 id 가 존재하므로 null 불가.
 *
 * @property id DB PK (UUID).
 * @property key URL-safe 소문자 슬러그. 예: `"fixed"`.
 * @property name 표시 이름.
 * @property description 선택적 설명. null 허용.
 * @property displayOrder 목록 표시 순서. 1부터 시작.
 * @property isStandard 표준 Resolution 여부.
 */
data class ResolutionResponse(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String?,
    val displayOrder: Int,
    val isStandard: Boolean,
) {
    companion object {
        /**
         * [Resolution] 도메인 객체를 [ResolutionResponse] 로 변환한다.
         *
         * @param resolution 변환 대상 도메인 객체. [Resolution.id] 는 null 이 아니어야 한다.
         * @throws IllegalArgumentException [resolution.id] 가 null 인 경우.
         */
        fun from(resolution: Resolution): ResolutionResponse {
            requireNotNull(resolution.id) { "Resolution.id must not be null for response mapping" }
            return ResolutionResponse(
                id = resolution.id,
                key = resolution.key,
                name = resolution.name,
                description = resolution.description,
                displayOrder = resolution.displayOrder,
                isStandard = resolution.isStandard,
            )
        }
    }
}
