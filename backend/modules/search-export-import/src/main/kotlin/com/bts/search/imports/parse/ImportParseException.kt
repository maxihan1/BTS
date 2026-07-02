// CSV/JSON import 파일 파싱 실패 예외 — IMPORT_PARSE_FAILED 로 상위(ImportJobProcessor)가 변환
package com.bts.search.imports.parse

/**
 * CSV/JSON import 파일 파싱 실패 시 [ImportRowParser] 가 던지는 예외.
 *
 * 헤더 행이 없는 CSV, 필수 컬럼(Summary)이 없는 CSV, 구조가 깨진 JSON(파싱 불가·`issues` 배열
 * 부재) 등 행 단위 검증이 아닌 **파일 구조 자체**를 읽을 수 없는 상황에서 발생한다.
 * 개별 행의 데이터 값 오류(예: summary 누락)는 이 예외가 아니라 [ParsedImportRow] 의 null 필드로
 * 표현되어 이후 단계(Task 8 `IssueImportAdapter`)에서 행 단위 실패로 처리된다.
 *
 * 호출자([com.bts.search.imports.job.application.ImportJobProcessor], Task 9)가 이 예외를
 * `errorCode=IMPORT_PARSE_FAILED` 로 변환해 [com.bts.search.imports.job.domain.ImportJob] 을
 * FAILED 로 전환한다.
 *
 * @param message 실패 사유 요약. 원본 파일 내용을 포함하지 않는다(정화된 요약만 — 사용자 업로드
 *   원본 데이터를 로그/예외 메시지에 그대로 노출하지 않기 위함).
 * @param cause 근본 원인 예외(있으면). 예: Jackson `JsonParseException`.
 */
class ImportParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
