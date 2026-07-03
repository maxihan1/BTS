// 퀵필터 REST API 요청/응답 DTO — agile-planning BC (FR-UX-01 Task 6)

package com.bts.agileplanning.web.dto

import com.bts.agileplanning.domain.QuickFilter
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * 퀵필터 name 필드의 최대 글자 수.
 *
 * [com.bts.agileplanning.domain.QuickFilter] 의 동일 상한과 일치시킨다. DTO 검증은 1차 방어일 뿐이며
 * 도메인 생성자가 다시 검증한다(memory: patch-merge-domain-bypass — DTO 검증만으로 도메인 불변식 우회 금지).
 */
private const val MAX_NAME_LENGTH = 50

/**
 * 퀵필터 생성/수정 공용 요청 바디.
 *
 * `POST /api/v1/boards/{boardId}/quick-filters` 와 `PATCH .../quick-filters/{filterId}` 가 공유한다.
 *
 * @property name 칩에 표시될 이름. 공백 불가, 50자 이하.
 * @property query [com.bts.agileplanning.web.BoardFilterQueryParser] 형식의 필터 조건 문자열. 공백 문자열은
 *   이 DTO 단계에서 400 으로 거부된다(EC1 1차 방어). 비어 있지 않지만 파싱 결과 조건이 0개인 경우(예:
 *   인식되지 않는 파라미터 키만 있는 경우)는
 *   [com.bts.agileplanning.application.BoardQuickFilterService] 가 EC1 로 재검증해 400 을 던진다.
 */
data class QuickFilterRequest(
    @field:NotBlank
    @field:Size(max = MAX_NAME_LENGTH)
    val name: String,
    @field:NotBlank
    val query: String,
)

/**
 * 퀵필터 응답 DTO.
 *
 * `GET /api/v1/boards/{id}` 의 `BoardDetailResponse.quickFilters` 목록 항목으로도 재사용된다(T7).
 *
 * @property filterId 퀵필터 UUID.
 * @property name 칩에 표시될 이름.
 * @property query 정규화되어 저장된 필터 조건 문자열([com.bts.agileplanning.web.BoardFilterQueryParser.serialize] 결과).
 */
data class QuickFilterResponse(
    val filterId: UUID,
    val name: String,
    val query: String,
) {
    companion object {
        /** 도메인 [QuickFilter] 를 [QuickFilterResponse] 로 변환한다. */
        fun from(quickFilter: QuickFilter): QuickFilterResponse =
            QuickFilterResponse(
                filterId = quickFilter.id,
                name = quickFilter.name,
                query = quickFilter.query,
            )
    }
}
