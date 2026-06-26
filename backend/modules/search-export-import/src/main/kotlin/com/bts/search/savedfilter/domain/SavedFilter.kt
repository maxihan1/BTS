// 저장된 AQL 필터 도메인 모델 — 이름·쿼리·프로젝트키 불변식 보장 (FR-SR-03)

package com.bts.search.savedfilter.domain

import java.time.Instant
import java.util.UUID

/**
 * 저장된 AQL 필터 도메인 모델.
 *
 * 사용자가 이름을 붙여 저장한 AQL(Atlas Query Language) 필터를 나타낸다.
 * `saved_filters` 테이블 행과 1:1 매핑되며, 모든 필드는 불변(val)이다.
 *
 * ## 생성 규칙
 * 신규 필터는 반드시 [create] 팩토리를 경유해 불변식(이름/쿼리/프로젝트키 검증)을
 * 보장해야 한다. 서비스 계층은 이 팩토리를 우회하지 않는다.
 *
 * ## 필드
 * - [id]: 필터 식별자. 신규 생성 시 `null`이며 영속 계층이 채운다.
 * - [ownerId]: 소유자 사용자 UUID.
 * - [name]: 필터 이름. 앞뒤 공백 trim, 비어 있을 수 없으며 최대 [MAX_NAME_LENGTH]자.
 * - [aqlQuery]: 저장된 AQL 쿼리 문자열. 비어 있을 수 없으며 최대 [MAX_AQL_LENGTH]자.
 * - [projectKey]: 필터가 적용되는 프로젝트 키. 비어 있을 수 없다. 영속 후 변경 불가.
 * - [createdAt]: 최초 생성 시각. 신규 생성 시 `null`이며 영속 계층이 채운다.
 * - [updatedAt]: 마지막 변경 시각. 신규 생성 시 `null`이며 영속 계층이 채운다.
 * - [version]: 낙관적 동시성 제어(OCC) 버전. 신규 생성 시 0, 영속 계층이 갱신한다.
 *
 * @see docs/decisions/2026-06-26-fr-sr-03-saved-filters.md 설계 결정 ADR
 */
data class SavedFilter(
    val id: UUID?,
    val ownerId: UUID,
    val name: String,
    val aqlQuery: String,
    val projectKey: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val version: Long,
) {
    companion object {
        /** 필터 이름 최대 길이. */
        const val MAX_NAME_LENGTH = 100

        /** AQL 쿼리 문자열 최대 길이. */
        const val MAX_AQL_LENGTH = 2000

        /**
         * 검증을 거쳐 신규 [SavedFilter]를 생성한다.
         *
         * [name]은 trim 후 비어 있지 않아야 하며 [MAX_NAME_LENGTH]자를 넘을 수 없다.
         * [aqlQuery]는 비어 있지 않아야 하며 [MAX_AQL_LENGTH]자를 넘을 수 없다.
         * [projectKey]는 비어 있지 않아야 한다.
         *
         * @param ownerId 소유자 사용자 UUID.
         * @param name 필터 이름 (정규화 전 원본).
         * @param aqlQuery 저장할 AQL 쿼리 문자열.
         * @param projectKey 필터가 적용되는 프로젝트 키.
         * @return 불변식을 만족하는 신규 [SavedFilter] ([id]/타임스탬프는 `null`, version=0).
         * @throws IllegalArgumentException 이름·쿼리·프로젝트키가 빈 값이거나 길이 제약을 위반한 경우.
         */
        fun create(
            ownerId: UUID,
            name: String,
            aqlQuery: String,
            projectKey: String,
        ): SavedFilter {
            val normalizedName = name.trim()
            require(normalizedName.isNotEmpty()) { "필터 이름은 비어 있을 수 없습니다." }
            require(normalizedName.length <= MAX_NAME_LENGTH) {
                "필터 이름은 ${MAX_NAME_LENGTH}자를 초과할 수 없습니다."
            }

            require(aqlQuery.isNotBlank()) { "AQL 쿼리는 비어 있을 수 없습니다." }
            require(aqlQuery.length <= MAX_AQL_LENGTH) {
                "AQL 쿼리는 ${MAX_AQL_LENGTH}자를 초과할 수 없습니다."
            }

            require(projectKey.isNotBlank()) { "프로젝트 키는 비어 있을 수 없습니다." }

            return SavedFilter(
                id = null,
                ownerId = ownerId,
                name = normalizedName,
                aqlQuery = aqlQuery,
                projectKey = projectKey,
                createdAt = null,
                updatedAt = null,
                version = 0L,
            )
        }
    }
}
