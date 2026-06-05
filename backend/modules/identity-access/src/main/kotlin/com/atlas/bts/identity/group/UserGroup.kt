// 전역 사용자 그룹 도메인 모델 — name 정규화/검증 불변식 (FR-PM-09)

package com.atlas.bts.identity.group

import java.time.Instant
import java.util.UUID

data class UserGroup(
    val id: UUID?,
    val name: String,
    val description: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    companion object {
        fun create(
            name: String,
            description: String?,
        ): UserGroup {
            val normalizedName = name.trim()
            require(normalizedName.isNotEmpty()) { "그룹 이름은 비어 있을 수 없습니다." }
            require(normalizedName.length <= 255) { "그룹 이름은 255자를 초과할 수 없습니다." }

            val normalizedDescription = description?.trim()?.takeIf { it.isNotEmpty() }
            require((normalizedDescription?.length ?: 0) <= 500) {
                "그룹 설명은 500자를 초과할 수 없습니다."
            }

            return UserGroup(
                id = null,
                name = normalizedName,
                description = normalizedDescription,
                createdAt = null,
                updatedAt = null,
            )
        }
    }
}
