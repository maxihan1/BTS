<!-- ADR: 계정 잠금 정책 위치 — BTS 측 자체 lockout (LDAP 서버 의존 X) -->

# ADR: LockoutPolicy — BTS 측 자체 lockout 결정

> 날짜: 2026-05-20
> 상태: 결정됨
> 연관: FR-AU-02, LdapProvider, LockoutPolicy, user_external_accounts

## 맥락

LDAP 인증 실패 시 계정 잠금을 LDAP 서버 측에 위임할지, BTS 애플리케이션 측에서 직접 관리할지 결정해야 한다.

## 옵션 비교

### 옵션 A: LDAP 서버 측 lockout (미채택)

LDAP 서버(OpenLDAP ppolicy overlay)가 잠금을 처리. BTS 는 LDAP 서버의 오류 응답을 파싱.

**단점**:
- 사내 모든 LDAP 서버가 ppolicy overlay 를 지원한다는 보장 없음.
- LDAP 서버 설정 의존 — 배포 환경마다 다를 수 있음.
- 다중 Provider (SAML, OIDC) 도입 시 일관성 없음 (각 IdP 마다 다른 lockout 정책).
- BTS 관리자가 잠금 상태를 UI 에서 확인하기 어려움.

### 옵션 B: BTS 측 자체 lockout (채택)

`user_external_accounts` 테이블의 `failed_attempts` + `locked_until` 컬럼으로 BTS 가 직접 잠금 관리. `LockoutPolicy` VO 를 `authn_providers.config` JSONB 에 저장.

**장점**:
- Provider 독립 — LDAP/SAML/OIDC 모든 Provider 에 동일 정책 적용 가능.
- BTS 관리자가 DB 에서 잠금 상태 직접 확인/해제 가능.
- LDAP 서버 설정 의존 없음 — 어떤 LDAP 서버와도 호환.
- `locked_until` 만료 후 자동 해제 — 별도 배치 작업 불필요.

## 결정

**옵션 B: BTS 측 자체 lockout** 을 채택한다.

## 구현 세부사항

- `LockoutPolicy(maxAttempts=5, lockoutMinutes=15, scope=PER_USER_PER_PROVIDER)` 기본값.
- 인증 실패 시 `ExternalAccountRepository.incrementFailedAttempts()` 호출.
- `failed_attempts >= maxAttempts` 도달 시 `markLockedUntil(now + lockoutMinutes)` 호출.
- 인증 시도 시 `lockedUntil > now` 이면 즉시 `ACCOUNT_LOCKED` 반환 (LDAP 서버 호출 없음).
- 인증 성공 시 `updateLastLoginAt()` 에서 `failed_attempts = 0` + `locked_until = NULL` 자동 reset.

## 향후 고려사항

- `LockoutScope.GLOBAL`: 동일 사용자가 여러 Provider 에서 연속 실패 시 모든 Provider 잠금. FR-AU-06 다중 Provider 도입 시 검토.
- 관리자 잠금 해제 UI: `locked_until` 를 NULL 로 설정하는 관리자 API. 별도 FR.
