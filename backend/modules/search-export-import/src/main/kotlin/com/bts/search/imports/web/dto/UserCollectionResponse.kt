// Import 사용자 매핑 수집(collect) 응답 DTO — 소스 식별자별 추천 사용자 정보 (FR-IM-02 PR-B Task 8)

package com.bts.search.imports.web.dto

import com.bts.search.imports.mapping.UserCollectionEntry
import com.bts.search.imports.mapping.UserCollectionResult
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * `POST /api/v1/imports/{jobId}/mapping/users` 응답 바디.
 *
 * [com.bts.search.imports.mapping.ImportMappingService.collectUsers]가 반환하는
 * [UserCollectionResult]를 그대로 직렬화한다 — 저장 없이 수집·추천 결과만 노출한다.
 *
 * @property users 소스 작성자 식별자별 추천 사용자 정보 목록.
 */
data class UserCollectionResponse(
    val users: List<UserEntryItem>,
) {
    /**
     * [UserCollectionEntry] 직렬화 형태.
     *
     * @property sourceIdentifier 정규화된 소스 작성자 식별자(이메일).
     * @property suggestedUserId 추천 BTS 사용자 UUID. 실재 사용자를 찾지 못하면 null.
     * @property suggestedDisplayName [suggestedUserId]의 표시명. [suggestedUserId]가 null이면 함께 null.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class UserEntryItem(
        val sourceIdentifier: String,
        val suggestedUserId: UUID?,
        val suggestedDisplayName: String?,
    )

    companion object {
        /**
         * [UserCollectionResult]를 [UserCollectionResponse]로 변환한다.
         *
         * @param result [com.bts.search.imports.mapping.ImportMappingService.collectUsers] 반환 결과.
         * @return 변환된 응답 DTO.
         */
        fun from(result: UserCollectionResult): UserCollectionResponse =
            UserCollectionResponse(
                users = result.users.map { it.toItem() },
            )

        private fun UserCollectionEntry.toItem(): UserEntryItem =
            UserEntryItem(
                sourceIdentifier = sourceIdentifier,
                suggestedUserId = suggestedUserId,
                suggestedDisplayName = suggestedDisplayName,
            )
    }
}
