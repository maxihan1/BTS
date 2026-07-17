// GlobalPermissionGrantService 가 던지는 도메인 예외 계층 (FR-PM-10 Task 6)

package com.atlas.bts.identity.permission

import java.util.UUID

/**
 * 전역 권한 부여 서비스의 모든 도메인 예외 기반 타입. HTTP 상태 매핑은 컨트롤러 담당.
 *
 * ## message 는 HTTP 응답에 실리지 않는다
 * 아래 예외들의 `message` 는 grantee id·권한코드 같은 내부 사정을 담는다(로그/디버깅용).
 * `GlobalPermissionGrantController.mapDomainException` 은 **타입으로만 분기**해 고정 snake_case
 * 코드를 내보내며 `message` 를 절대 응답에 싣지 않는다 — Guard 예외 message 가 그대로 HTTP detail 로
 * 새어 내부 사정(존재 여부/정책)을 노출한 FR-PM-04 사고의 회귀 방지다.
 *
 * @see docs/decisions/2026-07-17-global-permission-grants.md 설계 결정 ADR
 */
sealed class GlobalPermissionGrantException(message: String) : RuntimeException(message)

/**
 * 부여 대상(사용자/그룹)이 존재하지 않음 (→ 404).
 *
 * `grantee_id` 는 다형 참조라 DB FK 가 없고(ADR D-4), 무결성은 서비스의 존재 검증이 진다.
 * 이 예외는 **FK 생략의 대가를 치르는 지점**이다 — 사라지면 ADR D-4 가 거짓이 된다.
 */
class GranteeNotFoundException(
    granteeType: GranteeType,
    granteeId: UUID,
) : GlobalPermissionGrantException("grantee not found: $granteeType $granteeId")

/**
 * 같은 (permission, granteeType, granteeId) 조합이 이미 부여돼 있음 (→ 409).
 *
 * 리포지토리가 `ON CONFLICT` 를 쓰지 않아 UNIQUE 위반이 `DuplicateKeyException` 으로 전파되고
 * 서비스가 이 예외로 변환한다 — 관리자는 "이미 부여돼 있다"를 알아야 한다(ADR D-1).
 */
class DuplicateGrantException(
    permission: String,
    granteeType: GranteeType,
    granteeId: UUID,
) : GlobalPermissionGrantException("grant already exists: $permission for $granteeType $granteeId")

/**
 * 알려지지 않은 전역 권한코드 (→ 400).
 *
 * V036 의 `CHECK (permission IN (...))` 와 **이중 방어**를 이룬다(ADR D-1). 앱 겹이 없으면 미지 코드가
 * DB 까지 내려가 `DataIntegrityViolationException` → 500 으로 변질된다 — 400 이어야 할 사용자 입력 오류다.
 */
class UnknownPermissionException(
    permission: String,
) : GlobalPermissionGrantException("unknown global permission: $permission")

/**
 * 회수 대상 grant 행이 존재하지 않음 (→ 404).
 *
 * ADR D-5 — *"삭제 행 수가 0 이면 404 로 거부한다. 지웠다고 믿었는데 대상이 없었다를 조용히 성공으로
 * 만들지 않는다"*. [GlobalPermissionGrantRepository.revoke] 가 `Boolean` 을 반환하는 유일한 이유이며,
 * 그 반환값을 버리면 이 예외가 영영 발생하지 않는다.
 */
class GrantNotFoundException(
    grantId: UUID,
) : GlobalPermissionGrantException("global permission grant not found: $grantId")
