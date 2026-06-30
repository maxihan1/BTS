// AQL 검색 결과 페이지 envelope DTO — { data, meta.page } 표준 응답 형식 (FR-API-02)

package com.bts.search.web.dto

/**
 * AQL 이슈 검색 결과 페이지 envelope 응답.
 *
 * 표준 응답 형식: `{ "data": [...], "meta": { "page": { ... } } }`.
 * Spring `Page<T>`의 raw 직렬화(`content`/`totalElements` 최상위) 대신
 * 모든 목록 API와 동일한 봉투 형식으로 통일한다.
 *
 * @param T 목록 아이템 타입. 현재는 [AqlSearchHit]만 사용한다.
 * @property data 검색 결과 목록. 0건이면 빈 리스트([emptyList]).
 * @property meta 페이지네이션 메타데이터.
 */
data class AqlSearchPageResponse<T>(
    val data: List<T>,
    val meta: PageMeta,
)

/**
 * 페이지네이션 메타 봉투.
 *
 * @property page 현재 페이지 정보.
 */
data class PageMeta(
    val page: PageInfo,
)

/**
 * 현재 페이지의 구체적 수치.
 *
 * @property number 0-based 페이지 번호.
 * @property size 페이지 크기(요청한 size와 동일).
 * @property totalElements 전체 결과 건수.
 * @property totalPages 전체 페이지 수. `ceil(totalElements / size)`로 산출한다.
 */
data class PageInfo(
    val number: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
