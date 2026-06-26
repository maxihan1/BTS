// 저장된 필터 공유 대상 도메인 모델 — 공유 종류·대상 식별자 불변식 + 컬렉션 정규화 (FR-SR-03)

package com.bts.search.savedfilter.domain

/**
 * 저장된 필터의 단일 공유 대상 도메인 모델.
 *
 * 하나의 공유 규칙(공유 종류 + 대상 식별자)을 나타낸다.
 * 모든 필드는 불변(val)이며, 반드시 [create] 팩토리를 통해 생성해 불변식을 보장한다.
 *
 * ## 불변식
 * - [ShareType.PROJECT] / [ShareType.GROUP]: [targetId]가 비어 있지 않아야 한다.
 * - [ShareType.AUTHENTICATED]: [targetId]가 반드시 `null`이어야 한다.
 *
 * ## 컬렉션 정규화
 * 여러 공유 대상을 한 번에 저장할 때는 [normalize]를 경유해 중복 제거 및 상한 검사를 적용한다.
 *
 * @param shareType 공유 종류.
 * @param targetId 공유 대상 식별자. [ShareType.AUTHENTICATED]이면 `null`.
 */
data class SavedFilterShare(
    val shareType: ShareType,
    val targetId: String?,
) {
    companion object {
        /** 필터 하나에 등록할 수 있는 공유 대상 최대 개수(dedupe 후 기준). */
        const val MAX_SHARES_PER_FILTER = 50

        /**
         * 불변식을 검증하고 새 [SavedFilterShare]를 생성한다.
         *
         * [shareType]이 [ShareType.PROJECT] 또는 [ShareType.GROUP]이면 [targetId]는
         * 비어 있지 않아야 한다(trim 후 blank 금지).
         * [shareType]이 [ShareType.AUTHENTICATED]이면 [targetId]는 반드시 `null`이어야 한다.
         *
         * [targetId]는 trim되어 저장된다.
         *
         * @param shareType 공유 종류.
         * @param targetId 공유 대상 식별자 (정규화 전 원본). [ShareType.AUTHENTICATED]는 `null`.
         * @return 불변식을 만족하는 [SavedFilterShare].
         * @throws IllegalArgumentException 불변식 위반 시.
         */
        fun create(shareType: ShareType, targetId: String?): SavedFilterShare {
            return when (shareType) {
                ShareType.PROJECT, ShareType.GROUP -> {
                    val normalized = targetId?.trim()
                    require(!normalized.isNullOrBlank()) {
                        "${shareType.name} 공유에는 targetId가 반드시 필요합니다."
                    }
                    SavedFilterShare(shareType = shareType, targetId = normalized)
                }
                ShareType.AUTHENTICATED -> {
                    require(targetId == null) {
                        "AUTHENTICATED 공유에는 targetId가 있을 수 없습니다."
                    }
                    SavedFilterShare(shareType = shareType, targetId = null)
                }
            }
        }

        /**
         * 공유 대상 목록을 정규화한다.
         *
         * 1. 동일한 `(shareType, targetId)` 쌍은 중복 제거(dedupe)한다.
         * 2. dedupe 후 개수가 [MAX_SHARES_PER_FILTER]를 초과하면 [IllegalArgumentException]을 던진다.
         *
         * dedupe가 상한 검사보다 먼저 수행되므로, 동일 항목 60개는 dedupe 후 1개가 되어 통과한다.
         *
         * @param shares 정규화할 공유 대상 목록.
         * @return 중복이 제거된 공유 대상 목록.
         * @throws IllegalArgumentException dedupe 후 [MAX_SHARES_PER_FILTER] 초과 시.
         */
        fun normalize(shares: List<SavedFilterShare>): List<SavedFilterShare> {
            val deduped = shares.distinct()
            require(deduped.size <= MAX_SHARES_PER_FILTER) {
                "공유 대상은 최대 ${MAX_SHARES_PER_FILTER}개까지 등록할 수 있습니다. " +
                    "현재: ${deduped.size}개."
            }
            return deduped
        }
    }
}
