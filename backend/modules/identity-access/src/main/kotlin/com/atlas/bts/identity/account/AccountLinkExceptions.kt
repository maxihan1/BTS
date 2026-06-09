// 계정 연결(account linking) 도메인 예외 모음 — account 패키지 고유 이름 (FR-AU-08)

package com.atlas.bts.identity.account

/**
 * 계정 연결 시 외부 IdP bind(자격증명 검증)에 실패했을 때 던지는 도메인 예외 (FR-AU-08).
 *
 * HTTP 401 의미. 컨트롤러 계층(후속 Task)이 상태 매핑한다.
 * 메시지는 사용자명/DN 등 식별 정보를 노출하지 않는다(계정 열거 0).
 *
 * 이름은 account 패키지 내 고유다 — 동명 예외 교차패키지 상태코드 변질 방지
 * (duplicate-exception-name-cross-package-status 선례).
 */
class AccountLinkAuthException(
    cause: Throwable? = null,
) : RuntimeException("연결 대상 계정 인증에 실패했습니다.", cause)

/**
 * 연결하려는 외부 계정이 이미 다른 사용자에게 연결되어 있을 때 던지는 도메인 예외 (FR-AU-08).
 *
 * HTTP 409 의미. 어느 user 에게 연결돼 있는지는 노출하지 않는다(계정 열거 0).
 */
class AccountLinkConflictException :
    RuntimeException("이미 다른 계정에 연결된 외부 계정입니다.")

/**
 * 마지막 남은 로그인 수단을 해제하려 할 때 던지는 도메인 예외 (FR-AU-08).
 *
 * HTTP 409 의미. 해제 후 로그인 가능한 수단(활성 provider 링크 + 로컬 비밀번호)이
 * 하나도 남지 않으면 영구 락(login 불가)이 되므로 거부한다.
 */
class AccountLinkLastMethodException :
    RuntimeException("마지막 로그인 수단은 해제할 수 없습니다.")

/**
 * 해제 대상 링크가 본인 소유로 존재하지 않을 때 던지는 도메인 예외 (FR-AU-08).
 *
 * HTTP 404 의미. 타인 소유(소유 불일치)와 미존재를 구분하지 않는다(존재 probe 방지).
 */
class AccountLinkNotFoundException :
    RuntimeException("연결을 찾을 수 없습니다.")
