// 저장된 필터 서비스 예외 계층 — HTTP 매핑은 T5 핸들러가 담당 (FR-SR-03)

package com.bts.search.savedfilter.application

import java.util.UUID

/**
 * AQL 구문 오류 또는 도메인 검증 실패.
 *
 * HTTP 400 신호. T5 예외 핸들러([com.bts.search.savedfilter.web.SavedFilterExceptionHandler])가 매핑한다.
 *
 * @param message 사람이 읽을 수 있는 오류 설명.
 * @param cause 원인 예외(AqlLexException / AqlSyntaxException).
 */
class SavedFilterValidationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 같은 owner 내 필터 이름 중복.
 *
 * HTTP 409 신호. `UNIQUE(owner_id, name)` 제약 위반 시 서비스가 발생시킨다.
 *
 * @param name 중복된 필터 이름.
 * @param cause 원인 예외(Spring DuplicateKeyException 또는 jOOQ DataAccessException). 디버그 추적 보존.
 */
class SavedFilterDuplicateNameException(
    name: String,
    cause: Throwable? = null,
) : RuntimeException("이미 사용 중인 필터 이름입니다: $name", cause)

/**
 * 요청한 필터가 존재하지 않거나 요청자가 접근할 수 없음 (존재 은닉).
 *
 * HTTP 404 신호. 비소유자 접근 시에도 403 대신 404를 사용하여 존재 여부를 노출하지 않는다.
 *
 * @param id 조회 대상 필터 식별자.
 */
class SavedFilterNotFoundException(
    id: UUID,
) : RuntimeException("저장된 필터를 찾을 수 없습니다: $id")

/**
 * 요청자가 해당 필터의 소유자가 아님 (수정/삭제 시).
 *
 * HTTP 403 신호. get 경로는 [SavedFilterNotFoundException]으로 존재 은닉하며,
 * update/delete 경로에서만 이 예외가 사용된다.
 *
 * @param id 접근 거부된 필터 식별자.
 */
class SavedFilterForbiddenException(
    id: UUID,
) : RuntimeException("저장된 필터 수정 권한이 없습니다: $id")

/**
 * 낙관적 동시성 제어(OCC) 충돌 — 다른 요청이 먼저 필터를 수정함.
 *
 * HTTP 409 신호. repo.update 가 0행(null 반환)일 때 서비스가 발생시킨다.
 * 클라이언트는 최신 버전을 재조회 후 재시도해야 한다.
 *
 * @param id OCC 충돌이 발생한 필터 식별자.
 */
class SavedFilterConflictException(
    id: UUID,
) : RuntimeException("저장된 필터가 다른 요청으로 수정되었습니다. 최신 버전을 재조회 후 재시도하세요: $id")
