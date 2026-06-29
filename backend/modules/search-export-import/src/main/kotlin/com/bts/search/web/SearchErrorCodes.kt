// search BC API 에러 코드 상수 — SEARCH_ 접두사 (FR-SR-02 NFR-5, FR-EX-01 Task 5)

package com.bts.search.web

/**
 * search-export-import BC 에러 코드 상수.
 *
 * 모든 코드는 `SEARCH_` 접두사를 사용한다(BTS 에러 코드 규칙 §6).
 * [SearchExceptionHandler]와 [ExportExceptionHandler]가 ProblemDetail 응답의 `errorCode` 필드에 설정한다.
 *
 * ### 코드 목록
 *
 * | 상수 | HTTP | 의미 |
 * |---|---|---|
 * | [SEARCH_SYNTAX_ERROR] | 400 | AQL 문법 오류. position 포함 |
 * | [SEARCH_UNKNOWN_FIELD] | 400 | 오타 등 인식할 수 없는 필드 |
 * | [SEARCH_FIELD_NOT_YET_SUPPORTED] | 400 | 후속 PR 지원 예정 필드 |
 * | [SEARCH_VALIDATION_FAILED] | 400 | DTO Bean Validation 실패 |
 * | [SEARCH_EXPORT_LIMIT_EXCEEDED] | 400/job | 동기 1만 초과 / 비동기 워커 10만 초과 |
 * | [SEARCH_ACCESS_DENIED] | 403 | BROWSE 권한 없음 |
 * | [SEARCH_UNAUTHENTICATED] | 401 | 미인증 |
 * | [SEARCH_NOT_FOUND] | 404 | 리소스 없음 또는 소유권 없음 |
 * | [SEARCH_INTERNAL_ERROR] | 500 | 분류되지 않은 서버 오류 |
 */
object SearchErrorCodes {
    /** AQL 일반 문법 오류. 오류 위치(0-base 컬럼)가 RFC 7807 extension으로 제공된다. */
    const val SEARCH_SYNTAX_ERROR = "SEARCH_SYNTAX_ERROR"

    /** 인식할 수 없는 필드명(오타). 예: `foobar = 1`. */
    const val SEARCH_UNKNOWN_FIELD = "SEARCH_UNKNOWN_FIELD"

    /**
     * 현재 미지원이나 후속 PR에서 지원 예정인 필드.
     *
     * 예: `assignee`, `reporter`, `component`, `project`.
     * 사용자에게 "곧 지원 예정" 안내가 가능하도록 별도 코드로 구분한다.
     */
    const val SEARCH_FIELD_NOT_YET_SUPPORTED = "SEARCH_FIELD_NOT_YET_SUPPORTED"

    /** DTO Bean Validation(@field:NotBlank/@field:Max 등) 실패. */
    const val SEARCH_VALIDATION_FAILED = "SEARCH_VALIDATION_FAILED"

    /**
     * Export 행 상한 초과.
     *
     * - 동기 Export([com.bts.search.export.ExportService]): 1만 행 초과 시 즉시 예외.
     *   ProblemDetail extension property 로 `resultCount`(실제 건수)와 `limit`(허용 상한)을 포함한다.
     * - 비동기 Export 워커([com.bts.search.export.job.application.ExportJobProcessor]):
     *   10만 행 초과 시 `markFailed(SEARCH_EXPORT_LIMIT_EXCEEDED)` 로 작업을 FAILED 로 전환한다.
     *   대용량 Export 는 FR-EX-02 비동기 Export 를 사용하도록 안내한다.
     */
    const val SEARCH_EXPORT_LIMIT_EXCEEDED = "SEARCH_EXPORT_LIMIT_EXCEEDED"

    /** BROWSE 권한 없음. 프로젝트 존재 여부를 노출하지 않기 위해 일반 메시지만 제공한다. */
    const val SEARCH_ACCESS_DENIED = "SEARCH_ACCESS_DENIED"

    /** 미인증. 세션 만료 등 인증 정보가 없는 경우. */
    const val SEARCH_UNAUTHENTICATED = "SEARCH_UNAUTHENTICATED"

    /**
     * 비동기 Export 결과 파일 오브젝트 스토리지(MinIO) 업로드 실패.
     *
     * [ExportJobProcessor] 가 MinIO 업로드 중 예외를 감지했을 때 export_jobs.error_code 에 기록된다.
     * 클라이언트는 재시도 또는 재요청을 안내받는다.
     */
    const val SEARCH_EXPORT_STORAGE_ERROR = "SEARCH_EXPORT_STORAGE_ERROR"

    /**
     * 비동기 Export 작업 다운로드 준비 미완료 (COMPLETED 아님).
     *
     * `GET /api/v1/search/export-jobs/{id}/download` 요청 시 작업이 아직 PENDING/RUNNING/FAILED 인 경우.
     * 클라이언트는 GET /{id} 로 폴링하여 COMPLETED 상태를 확인 후 재시도한다.
     */
    const val SEARCH_EXPORT_NOT_READY = "SEARCH_EXPORT_NOT_READY"

    /**
     * 요청한 리소스를 찾을 수 없음.
     *
     * Export 작업이 존재하지 않거나 요청자가 소유하지 않은 경우.
     * 소유권 오류도 동일 코드로 반환하여 존재 자체를 노출하지 않는다(존재 은닉).
     */
    const val SEARCH_NOT_FOUND = "SEARCH_NOT_FOUND"

    /** 분류되지 않은 서버 내부 오류. 상세는 서버 로그에만 기록한다. */
    const val SEARCH_INTERNAL_ERROR = "SEARCH_INTERNAL_ERROR"
}
