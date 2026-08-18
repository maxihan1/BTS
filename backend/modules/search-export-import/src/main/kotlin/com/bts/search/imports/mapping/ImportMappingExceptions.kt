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
 *    실제 상태 전환([com.bts.search.imports.job.repository.ImportJobRepository.transitionToPending])가
 *    `false` 를 반환한 경우(반복/동시 confirm 요청 사이에 상태가 바뀐 경쟁 상황 — 교훈
 *    advisory-lock-bigint-toctou). 사전확인만으로는 검사와 사용 사이의 상태 변경을 막을 수 없으므로,
 *    실제 쓰기 직전 CAS 결과를 다시 확인해야 한다.
 */
class ImportMappingStateConflictException : RuntimeException("Import 작업이 매핑 대기(AWAITING_MAPPING) 상태가 아닙니다.")

/**
 * 확정하려는 사용자 매핑(소스 작성자 식별자 → 대상 사용자 UUID?)이 검증 규칙을 통과하지 못했을 때
 * [ImportMappingService.confirm] 이 던지는 예외 (FR-IM-02 PR-B Task 5).
 *
 * `ImportExceptionHandler`(후속 웹 계층 task, `com.bts.search.imports.web`)가 422
 * `IMPORT_USER_MAPPING_INVALID` 로 변환한다.
 *
 * 두 검증 규칙 위반에서 던져진다.
 * 1. [DUPLICATE_SOURCE_IDENTIFIER] — 정규화([UserMappingNormalizer.normalize]) 시 서로 겹치는
 *    (대소문자/공백만 다른 포함) 중복 소스 식별자를 사용자 매핑에 사용한 경우.
 * 2. [TARGET_USER_NOT_FOUND] — targetUserId 가 null 이 아닌데 실재 사용자를 찾을 수 없는 경우
 *    ([com.bts.shared.user.UserLookupPort.findDisplayNamesByIds] 결과에 없음). null targetUserId 는
 *    미매핑(폴백) 의도로 허용되며 이 검증 대상이 아니다.
 *
 * @property errors 검증 실패 사유 목록. [MappingIssue.field] 에는 문제가 된 소스 식별자를 담는다.
 */
class ImportUserMappingInvalidException(val errors: List<MappingIssue>) :
    RuntimeException("사용자 매핑 검증에 실패했습니다: ${errors.joinToString { it.code }}") {
    companion object {
        /** 정규화(trim+lowercase) 시 서로 겹치는 중복 소스 식별자를 사용자 매핑에 사용함. */
        const val DUPLICATE_SOURCE_IDENTIFIER = "DUPLICATE_SOURCE_IDENTIFIER"

        /** targetUserId 가 non-null 인데 실재 사용자(users 테이블)를 찾을 수 없음. */
        const val TARGET_USER_NOT_FOUND = "TARGET_USER_NOT_FOUND"
    }
}

/**
 * 확정하려는 값 매핑(대상 필드 + 소스 값 → 대상 값)이 검증 규칙을 통과하지 못했을 때
 * [ImportMappingService.confirm] 이 던지는 예외 (FR-IM-02 PR-C Task 5).
 *
 * `ImportExceptionHandler`(후속 웹 계층 task, `com.bts.search.imports.web`)가 422
 * `IMPORT_VALUE_MAPPING_INVALID` 로 변환한다.
 *
 * 두 검증 규칙 위반에서 던져진다.
 * 1. [TARGET_VALUE_NOT_FOUND] — FR7 필드별 비대칭 검증 위반. [ValueTargetField.TYPE]/
 *    [ValueTargetField.PRIORITY] 대상 값이 카탈로그/canonical 5 와 정규화 정확일치
 *    ([ValueMappingNormalizer.normalize], 대소문자 무시)하지 않거나, [ValueTargetField.STATUS] 대상
 *    값이 공백인 경우.
 * 2. [DUPLICATE_VALUE_MAPPING] — 정규화([ValueMappingNormalizer.normalize]) 시 서로 겹치는
 *    (대상 필드, 소스 값) 조합을 값 매핑에 중복 사용한 경우(대상 값이 같아도 위반).
 *
 * @property errors 검증 실패 사유 목록. [MappingIssue.field] 에는 문제가 된 소스 값을 담는다.
 */
class ImportValueMappingInvalidException(val errors: List<MappingIssue>) :
    RuntimeException("값 매핑 검증에 실패했습니다: ${errors.joinToString { it.code }}") {
    companion object {
        /** TYPE/PRIORITY 대상 값이 카탈로그/canonical 5 와 불일치하거나 STATUS 대상 값이 공백임. */
        const val TARGET_VALUE_NOT_FOUND = "TARGET_VALUE_NOT_FOUND"

        /** 정규화(trim+lowercase) 시 서로 겹치는 (대상 필드, 소스 값) 조합을 값 매핑에 중복 사용함. */
        const val DUPLICATE_VALUE_MAPPING = "DUPLICATE_VALUE_MAPPING"
    }
}
