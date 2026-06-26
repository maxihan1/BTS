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

/**
 * 공유로 열람은 가능하지만 소유자가 아니어서 수정/삭제 권한이 없음.
 *
 * HTTP 403 신호. 비소유자가 자신에게 공유된(가시) 필터를 수정/삭제하려 할 때 서비스가 발생시킨다(EC4).
 * 비가시(공유되지 않은) 필터는 존재 은닉을 위해 [SavedFilterNotFoundException](404)으로 분기한다(EC5).
 *
 * 이 예외의 message 에는 식별자가 포함되므로 HTTP detail 로 직접 노출하지 않는다 —
 * 응답 매핑은 T8 핸들러([com.bts.search.savedfilter.web.SavedFilterExceptionHandler])가
 * 일반 메시지로 치환한다.
 *
 * @param id 수정/삭제가 거부된 필터 식별자.
 */
class SavedFilterForbiddenException(
    id: UUID,
) : RuntimeException("저장된 필터를 수정/삭제할 권한이 없습니다: $id")
