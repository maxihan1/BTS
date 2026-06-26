// 저장된 필터 + 공유 목록 read model — 응답 조립용 묶음 (FR-SR-03 PR2)

package com.bts.search.savedfilter.application

import com.bts.search.savedfilter.domain.SavedFilter
import com.bts.search.savedfilter.domain.SavedFilterShare

/**
 * 저장된 필터와 그 공유 목록을 함께 담는 application 계층 read model.
 *
 * 단건 조회/목록 조회 시 서비스가 필터 본문과 [shareRepository][SavedFilterShareRepository]에서
 * 조회한 공유 목록을 한 번에 묶어 반환한다. web 계층이 이 묶음을 응답 DTO로 변환한다.
 *
 * 공유가 없는 필터는 [shares]가 빈 리스트다.
 *
 * @param filter 저장된 필터 도메인 객체(영속된 상태 — id/타임스탬프 채워짐).
 * @param shares 해당 필터의 공유 대상 목록. 공유가 없으면 빈 리스트.
 */
data class SavedFilterWithShares(
    val filter: SavedFilter,
    val shares: List<SavedFilterShare>,
)
