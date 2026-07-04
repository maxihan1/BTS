// FR-IM-02 Import 사용자 매핑 UI collectUsers 응답 — 소스 식별자별 추천 사용자 정보 (Task 5)
package com.bts.search.imports.mapping

import java.util.UUID

/**
 * [ImportMappingService.collectUsers] 의 결과.
 *
 * @property users 이 Import 작업의 원본 파일을 전량 스캔해 수집한(distinct) 소스 작성자 식별자별
 *   추천 사용자 정보 목록. [UserCollectionEntry.sourceIdentifier] 기준 오름차순 정렬이다.
 */
data class UserCollectionResult(
    val users: List<UserCollectionEntry>,
)

/**
 * [UserCollectionResult] 의 개별 항목 — 소스 작성자 식별자 하나에 대한 추천 매핑 정보.
 *
 * @property sourceIdentifier [UserMappingNormalizer.normalize] 로 정규화된 소스 작성자 식별자(이메일).
 *   [UserMappingNormalizer.collectIdentifiers] 가 원본 행에서 수집한 값을 그대로 담는다.
 * @property suggestedUserId [com.bts.shared.user.UserLookupPort.resolveByEmails] 로 해석된 추천 BTS
 *   사용자 UUID. 실재 사용자를 찾지 못하면 null — 매핑 UI 가 사용자에게 수동 선택을 요구한다.
 * @property suggestedDisplayName [suggestedUserId] 의 표시명
 *   ([com.bts.shared.user.UserLookupPort.findDisplayNamesByIds]). [suggestedUserId] 가 null 이면 함께 null.
 */
data class UserCollectionEntry(
    val sourceIdentifier: String,
    val suggestedUserId: UUID?,
    val suggestedDisplayName: String?,
)
