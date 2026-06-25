// AQL 검색 요청 DTO — POST /api/v1/search/aql 요청 바디 (FR-SR-02 FR-4)

package com.bts.search.web.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * AQL 이슈 검색 요청 DTO.
 *
 * `POST /api/v1/search/aql` 요청 바디를 매핑한다.
 * 모든 필드는 Jakarta Validation으로 1차 방어를 수행한 뒤 [com.bts.search.aql.AqlParser]에 전달된다.
 * Hibernate Validator 없는 환경에서도 [com.bts.search.web.SearchController.validateRequest]가
 * 명시적으로 동일한 제약을 강제한다.
 *
 * ### 검증 규칙
 *
 * - [projectKey] — 빈 값 금지. MVP는 단일 프로젝트 스코프 필수(FR-4).
 * - [query] — 빈 값 금지, 최대 2000자. 파서 도달 전 길이 거부(NFR-2 DoS 방어).
 * - [page] — 0 이상(0-base 페이지 번호).
 * - [size] — 1 이상 100 이하. 100 초과 시 클램프하지 않고 400으로 거부한다(GET /issues 동일 방향).
 *
 * ### page/size를 body에 두는 이유
 *
 * 검색 쿼리가 body에 있어 자족적이므로 Pageable 리졸버(쿼리 파라미터) 대신 body에 포함한다.
 * Spring Pageable 리졸버를 사용하지 않고 수동으로 검증을 적용한다.
 *
 * @property projectKey 검색 대상 프로젝트 키. 빈 값 금지.
 * @property query AQL 쿼리 문자열. 빈 값 금지, 최대 2000자.
 * @property page 0-base 페이지 번호. 기본값 0.
 * @property size 페이지 크기(1..100). 기본값 50.
 */
data class AqlSearchRequest(
    @field:NotBlank(message = "projectKey는 필수입니다.")
    val projectKey: String = "",
    @field:NotBlank(message = "query는 필수입니다.")
    @field:Size(max = 2000, message = "query는 최대 2000자까지 허용됩니다.")
    val query: String = "",
    @field:Min(value = 0, message = "page는 0 이상이어야 합니다.")
    val page: Int = 0,
    @field:Min(value = 1, message = "size는 1 이상이어야 합니다.")
    @field:Max(value = 100, message = "size는 최대 100까지 허용됩니다. 100 초과는 거부됩니다.")
    val size: Int = 50,
)
