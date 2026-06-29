// 이슈 Export 요청 DTO — POST /api/v1/search/export 요청 바디 (FR-EX-01 Task 5)

package com.bts.search.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 이슈 Export 요청 DTO.
 *
 * `POST /api/v1/search/export` 요청 바디를 매핑한다.
 * Jakarta Validation 어노테이션은 1차 방어를 담당한다.
 * Hibernate Validator 없는 환경에서도 [com.bts.search.web.ExportController.validateRequest]가
 * 동일 제약을 명시적으로 강제한다.
 *
 * ### 검증 규칙
 *
 * - [projectKey] — 빈 값 금지, 영문자·숫자·하이픈만 허용(패턴 검증).
 *   Content-Disposition 헤더 인젝션 방어를 위해 허용 문자를 엄격히 제한한다.
 * - [query] — 빈 값 금지, 최대 2000자. 파서 도달 전 길이 거부(NFR-2 DoS 방어).
 * - [format] — nullable. null이면 [com.bts.search.export.ExportFormat.CSV]로 기본 처리된다.
 *   유효하지 않은 문자열(예: "PDF")은 수동 파싱에서 [com.bts.search.web.SearchValidationException]으로 거부한다.
 * - [columns] — nullable. null이면 전체 9컬럼을 사용한다.
 *   지원하지 않는 컬럼 이름은 [com.bts.search.export.ExportColumn.parse]에서 [com.bts.search.web.SearchValidationException]으로 거부한다.
 *
 * @property projectKey 검색 대상 프로젝트 키. 빈 값 금지, 영숫자·하이픈만 허용.
 * @property query AQL 쿼리 문자열. 빈 값 금지, 최대 2000자.
 * @property format 파일 형식("CSV" 또는 "XLSX"). null이면 CSV 기본값.
 * @property columns 출력 컬럼 이름 목록. null 또는 빈 목록이면 전체 9컬럼.
 */
data class ExportRequest(
    @field:NotBlank(message = "projectKey는 필수입니다.")
    @field:Pattern(
        regexp = "[A-Za-z0-9\\-]+",
        message = "projectKey는 영문자·숫자·하이픈만 허용됩니다.",
    )
    val projectKey: String = "",
    @field:NotBlank(message = "query는 필수입니다.")
    @field:Size(max = 2000, message = "query는 최대 2000자까지 허용됩니다.")
    val query: String = "",
    val format: String? = null,
    val columns: List<String>? = null,
)
