// IssueSearchPage → AqlSearchPageResponse envelope 변환 확장함수 (FR-API-02)

package com.bts.search.web.dto

import com.bts.shared.search.IssueSearchPage
import kotlin.math.ceil

/**
 * [IssueSearchPage]를 표준 envelope 응답으로 변환한다.
 *
 * 컨트롤러의 매핑 로직을 단일 호출로 위임해 컨트롤러 본문을 단순화한다.
 * `data`는 [transform]을 통해 검색 결과를 HTTP 응답 DTO로 변환한 목록이다.
 * 결과가 0건이면 `data`는 빈 리스트([emptyList])를 반환하며 null이 되지 않는다.
 *
 * @param T 변환 후 아이템 타입.
 * @param requestedSize 요청한 페이지 크기. `meta.page.size` 및 `totalPages` 계산에 사용된다.
 * @param transform 개별 검색 히트를 응답 DTO로 변환하는 함수.
 * @return 표준 envelope 응답.
 */
fun <T> IssueSearchPage.toAqlEnvelope(
    requestedSize: Int,
    transform: (com.bts.shared.search.IssueSearchHit) -> T,
): AqlSearchPageResponse<T> {
    val data = items.map(transform)
    val totalPages =
        if (requestedSize > 0) {
            ceil(total.toDouble() / requestedSize).toInt()
        } else {
            0
        }
    return AqlSearchPageResponse(
        data = data,
        meta =
            PageMeta(
                page =
                    PageInfo(
                        number = page,
                        size = requestedSize,
                        totalElements = total,
                        totalPages = totalPages,
                    ),
            ),
    )
}
