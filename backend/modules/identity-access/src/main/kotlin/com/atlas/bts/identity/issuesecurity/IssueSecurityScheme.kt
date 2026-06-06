// 이슈 보안 스킴 도메인 모델 — 등급들을 묶는 전역 단위, name/description 검증 (FR-PM-06)

package com.atlas.bts.identity.issuesecurity

import java.time.Instant
import java.util.UUID

/**
 * 이슈 보안 스킴 도메인 모델.
 *
 * 여러 보안 등급([IssueSecurityLevel])을 묶는 전역 단위로, [name]은 시스템 전역에서 유니크하다.
 * `issue_security_schemes` 테이블 행과 1:1 매핑되며, 모든 필드는 불변(val)이다.
 *
 * ## 생성 규칙
 * 신규 스킴은 반드시 [create] 팩토리를 경유해 불변식([name] 정규화/길이, [description] 길이)을
 * 보장해야 한다. 서비스 계층은 이 팩토리를 우회하지 않는다.
 *
 * ## 필드
 * - [id]: 스킴 식별자. 신규 생성 시 `null`이며 영속 계층이 채운다.
 * - [name]: 스킴 이름. 앞뒤 공백 trim, 비어 있을 수 없으며 최대 [MAX_NAME_LENGTH]자.
 * - [description]: 스킴 설명. 빈 문자열/공백은 `null`로 정규화되며 최대 [MAX_DESCRIPTION_LENGTH]자.
 * - [createdAt]: 최초 생성 시각. 신규 생성 시 `null`이며 영속 계층이 채운다.
 * - [updatedAt]: 마지막 변경 시각. 신규 생성 시 `null`이며 영속 계층이 채운다.
 *
 * @see docs/decisions/2026-06-06-issue-security-level-scheme-model.md 설계 결정 ADR
 */
data class IssueSecurityScheme(
    val id: UUID?,
    val name: String,
    val description: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    companion object {
        /** 스킴 이름 최대 길이. */
        const val MAX_NAME_LENGTH = 255

        /** 스킴 설명 최대 길이. */
        const val MAX_DESCRIPTION_LENGTH = 500

        /**
         * 정규화/검증을 거쳐 신규 [IssueSecurityScheme]을 생성한다.
         *
         * [name]은 trim 후 비어 있지 않아야 하며 [MAX_NAME_LENGTH]자를 넘을 수 없다.
         * [description]은 trim 후 빈 값이면 `null`로 정규화되고 [MAX_DESCRIPTION_LENGTH]자를 넘을 수 없다.
         *
         * @param name 스킴 이름 (정규화 전 원본).
         * @param description 스킴 설명 (정규화 전 원본, nullable).
         * @return 불변식을 만족하는 신규 [IssueSecurityScheme] ([id]/타임스탬프는 `null`).
         * @throws IllegalArgumentException 이름이 비어 있거나 길이 제약을 위반한 경우.
         */
        fun create(
            name: String,
            description: String?,
        ): IssueSecurityScheme {
            val normalizedName = name.trim()
            require(normalizedName.isNotEmpty()) { "보안 스킴 이름은 비어 있을 수 없습니다." }
            require(normalizedName.length <= MAX_NAME_LENGTH) {
                "보안 스킴 이름은 ${MAX_NAME_LENGTH}자를 초과할 수 없습니다."
            }

            val normalizedDescription = description?.trim()?.takeIf { it.isNotEmpty() }
            require((normalizedDescription?.length ?: 0) <= MAX_DESCRIPTION_LENGTH) {
                "보안 스킴 설명은 ${MAX_DESCRIPTION_LENGTH}자를 초과할 수 없습니다."
            }

            return IssueSecurityScheme(
                id = null,
                name = normalizedName,
                description = normalizedDescription,
                createdAt = null,
                updatedAt = null,
            )
        }
    }
}
