// 이슈 보안 등급 도메인 모델 — 스킴 소속 등급, name/description 검증 (FR-PM-06)

package com.atlas.bts.identity.issuesecurity

import java.time.Instant
import java.util.UUID

/**
 * 이슈 보안 등급 도메인 모델.
 *
 * 하나의 보안 스킴([IssueSecurityScheme]) 아래에 속하는 등급으로, "임원만"/"내부용" 같은
 * 가시성 분류를 표현한다. `issue_security_levels` 테이블 행과 1:1 매핑되며, 모든 필드는 불변(val)이다.
 * 같은 스킴 내에서 [name]은 유니크하고, [isDefault]는 스킴당 최대 하나만 참이다(DB 부분 유니크 인덱스).
 *
 * ## 생성 규칙
 * 신규 등급은 반드시 [create] 팩토리를 경유해 불변식([name] 정규화/길이, [description] 길이)을
 * 보장해야 한다. 서비스 계층은 이 팩토리를 우회하지 않는다.
 *
 * ## 필드
 * - [id]: 등급 식별자. 신규 생성 시 `null`이며 영속 계층이 채운다.
 * - [schemeId]: 소속 스킴 식별자.
 * - [name]: 등급 이름. 앞뒤 공백 trim, 비어 있을 수 없으며 최대 [MAX_NAME_LENGTH]자.
 * - [description]: 등급 설명. 빈 문자열/공백은 `null`로 정규화되며 최대 [MAX_DESCRIPTION_LENGTH]자.
 * - [isDefault]: 스킴 기본 등급 여부.
 * - [createdAt]: 최초 생성 시각. 신규 생성 시 `null`이며 영속 계층이 채운다.
 *
 * @see docs/decisions/2026-06-06-issue-security-level-scheme-model.md 설계 결정 ADR
 */
data class IssueSecurityLevel(
    val id: UUID?,
    val schemeId: UUID,
    val name: String,
    val description: String?,
    val isDefault: Boolean,
    val createdAt: Instant?,
) {
    companion object {
        /** 등급 이름 최대 길이. */
        const val MAX_NAME_LENGTH = 255

        /** 등급 설명 최대 길이. */
        const val MAX_DESCRIPTION_LENGTH = 500

        /**
         * 정규화/검증을 거쳐 신규 [IssueSecurityLevel]을 생성한다.
         *
         * [name]은 trim 후 비어 있지 않아야 하며 [MAX_NAME_LENGTH]자를 넘을 수 없다.
         * [description]은 trim 후 빈 값이면 `null`로 정규화되고 [MAX_DESCRIPTION_LENGTH]자를 넘을 수 없다.
         *
         * @param schemeId 소속 스킴 식별자.
         * @param name 등급 이름 (정규화 전 원본).
         * @param description 등급 설명 (정규화 전 원본, nullable).
         * @param isDefault 스킴 기본 등급 여부.
         * @return 불변식을 만족하는 신규 [IssueSecurityLevel] ([id]/타임스탬프는 `null`).
         * @throws IllegalArgumentException 이름이 비어 있거나 길이 제약을 위반한 경우.
         */
        fun create(
            schemeId: UUID,
            name: String,
            description: String?,
            isDefault: Boolean,
        ): IssueSecurityLevel {
            val normalizedName = name.trim()
            require(normalizedName.isNotEmpty()) { "보안 등급 이름은 비어 있을 수 없습니다." }
            require(normalizedName.length <= MAX_NAME_LENGTH) {
                "보안 등급 이름은 ${MAX_NAME_LENGTH}자를 초과할 수 없습니다."
            }

            val normalizedDescription = description?.trim()?.takeIf { it.isNotEmpty() }
            require((normalizedDescription?.length ?: 0) <= MAX_DESCRIPTION_LENGTH) {
                "보안 등급 설명은 ${MAX_DESCRIPTION_LENGTH}자를 초과할 수 없습니다."
            }

            return IssueSecurityLevel(
                id = null,
                schemeId = schemeId,
                name = normalizedName,
                description = normalizedDescription,
                isDefault = isDefault,
                createdAt = null,
            )
        }
    }
}
