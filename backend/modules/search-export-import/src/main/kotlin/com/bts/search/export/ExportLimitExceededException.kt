// 동기 Export 행 상한 초과 예외 — resultCount/limit 정보를 응답 본문에 포함

package com.bts.search.export

/**
 * 동기 Export 행 상한 초과 예외.
 *
 * [ExportService]가 count-first 단계에서 첫 페이지의 [IssueSearchPage.total][com.bts.shared.search.IssueSearchPage.total]이
 * [ExportService.MAX_ROWS]를 초과하면 즉시 던진다.
 * 추가 페이지 순회 없이 1회 조회 후 거부한다.
 *
 * ExportController의 ExceptionHandler가 이 예외를 400 SEARCH_EXPORT_LIMIT_EXCEEDED로 변환하며,
 * [resultCount]와 [limit]를 ProblemDetail extension property로 포함한다.
 *
 * @property resultCount 조회된 전체 이슈 수. [IssueSearchPage.total]에서 가져온다.
 * @property limit 허용된 최대 행 수. [ExportService.MAX_ROWS]와 동일한 값.
 */
class ExportLimitExceededException(
    val resultCount: Long,
    val limit: Long,
) : RuntimeException(
        "Export 상한 초과: resultCount=$resultCount, limit=$limit. " +
            "대용량 Export는 FR-EX-02(비동기 Export)를 사용하십시오.",
    )
