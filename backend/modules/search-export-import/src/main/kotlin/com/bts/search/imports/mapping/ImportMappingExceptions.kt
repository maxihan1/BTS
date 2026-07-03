// FR-IM-02 매핑 검증/확정 실패 예외 — 422 검증실패(ImportMappingInvalidException)·409 상태충돌(ImportMappingStateConflictException)
package com.bts.search.imports.mapping

/**
 * 필드 매핑이 [MappingValidator.validate] 검증 규칙을 통과하지 못했을 때
 * [ImportMappingService.confirm] 이 던지는 예외.
 *
 * `ImportExceptionHandler`(Task 8, `com.bts.search.imports.web`)가 422 `IMPORT_MAPPING_INVALID` 로
 * 변환한다.
 *
 * @property errors 검증 실패 사유 목록. [MappingValidationResult.errors] 를 그대로 담는다 — 어느
 *   대상 필드가 왜 실패했는지 클라이언트가 그대로 렌더링할 수 있도록 손실 없이 전파한다.
 */
class ImportMappingInvalidException(val errors: List<MappingIssue>) :
    RuntimeException("필드 매핑 검증에 실패했습니다: ${errors.joinToString { it.code }}")

/**
 * Import 작업이 [com.bts.search.imports.job.domain.ImportJobStatus.AWAITING_MAPPING] 상태가 아니어서
 * 매핑 검증/확정을 진행할 수 없을 때 [ImportMappingService] 가 던지는 예외.
 *
 * `ImportExceptionHandler`(Task 8)가 409 `IMPORT_MAPPING_STATE_CONFLICT` 로 변환한다.
 *
 * 두 경로에서 던져진다.
 * 1. **사전확인** — [ImportMappingService.validate]/[ImportMappingService.confirm] 진입 시점에
 *    조회한 작업 상태가 이미 AWAITING_MAPPING 이 아닌 경우.
 * 2. **CAS 실패(TOCTOU 방지)** — [ImportMappingService.confirm] 이 사전확인을 통과한 뒤에도,
 *    실제 상태 전이([com.bts.search.imports.job.repository.ImportJobRepository.transitionToPending])가
 *    `false` 를 반환한 경우(반복/동시 confirm 요청 사이에 상태가 바뀐 경쟁 상황 — 교훈
 *    advisory-lock-bigint-toctou). 사전확인만으로는 검사와 사용 사이의 상태 변경을 막을 수 없으므로,
 *    실제 쓰기 직전 CAS 결과를 다시 확인해야 한다.
 */
class ImportMappingStateConflictException : RuntimeException("Import 작업이 매핑 대기(AWAITING_MAPPING) 상태가 아닙니다.")
