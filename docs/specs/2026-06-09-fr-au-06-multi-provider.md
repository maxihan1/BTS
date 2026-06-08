<!-- FR-AU-06 다중 Provider 동시 활성화 기술 스펙 — username/password 계열 Provider 선택+fallback 인증 + 로그인 화면 동적 목록 -->

# FR-AU-06 다중 Provider 동시 활성화 — 스펙

> BC. identity-access | type. auth | 담당. security-engineer
> 스코프 확정(Maxi 2026-06-09). 옵션 1 — "서버는 하나, 로그인 방법 종류(LDAP·Local·SAML·OIDC)는 동시에 켜고 화면이 동적으로 보여준다." 같은 type 여러 인스턴스(LdapTemplate 동적 wiring) **제외 → 후속 FR**. 인증 동작 = 선택우선 + 자동 fallback.
> 선행. FR-AU-01(SPI/ProviderRegistry) · FR-AU-02(LDAP) · FR-AU-03(SAML) · FR-AU-04(OIDC) · FR-AU-05(Local) · FR-AU-09(세션/JWT) 모두 완료.

## 배경 — 현재 동작의 실제 갭

| # | 현행 | 위치 | 문제 |
|---|---|---|---|
| G1 | `AuthController.login`이 **항상 `Credential.UsernamePassword`만 생성** | `web/AuthController.kt:103` | `findFor(UsernamePassword)` → `LocalProvider.supports`(UsernamePassword)만 매칭. **LdapProvider는 `Credential.LdapBind`만 supports**(LdapProvider.kt:75) → username/password 로그인으로 LDAP 진입 불가. LDAP 사용자는 사실상 로그인 불가 |
| G2 | `LoginRequest.provider` 필드 **무시** | `web/AuthController.kt:103-108` | 사용자가 Provider를 골라도 백엔드가 안 씀 |
| G3 | fallback 없음 | `spi/ProviderRegistry.kt:52` `findFor` | 첫 매칭 1개만 시도. 차선 Provider 시도 안 함 |
| G4 | 로그인 드롭다운 **하드코딩** `z.enum(['local','ldap-corp'])` | `apps/web/src/auth/LoginForm.tsx:46` | DB·백엔드 목록 미반영. SAML/OIDC는 이미 동적(`fetchSamlIdps`/`fetchOidcProviders`) |
| G5 | `/api/v1/auth/providers`가 코드 Bean(`ProviderRegistry.all()`)만 사용 | `web/ProvidersController.kt:38` | 운영자가 DB `authn_providers.enabled=false`로 꺼도 목록에 노출. `sort_order` 미반영 |

> **핵심**. G1은 단순 미배선이 아니라 LDAP 로그인을 막는 실질 버그다. FR-AU-06의 1순위.

## 인프라 현황 (이미 존재 — 재사용)

- `authn_providers(enabled BOOLEAN, sort_order INTEGER)` 컬럼 + 부분 인덱스 `idx_authn_providers_type_enabled (type, enabled) WHERE enabled=true` — `V002:8-22`. 최신 마이그레이션 V019 → 신규 시 **V020**.
- SAML/OIDC 동적 목록 선례 — `SamlIdpController.listIdps()`(`findAllEnabled()` → `{idps:[{registrationId,displayName}]}`, permitAll), `OidcProviderController`(동형). 프론트 `api/saml.ts`·`api/oidc.ts`가 Zod 스키마 1:1 정합으로 소비.
- `ProviderRegistry`(코드 Bean 수집) · `ProviderType(priority)`(LDAP 80 / LOCAL 70 / PAT 60 / OIDC 50 / SAML 40 / OAUTH 30) · `Credential` sealed(UsernamePassword / LdapBind / ...).

## 사용자 시나리오 (Given-When-Then)

### S1 — Local 사용자 명시 선택 로그인 (외부 협력사)
- **Given** authn_providers에 LOCAL·LDAP 모두 활성. 외부 협력사 bob은 Local 계정만 보유(FR-AU-05).
- **When** 로그인 화면에서 "로컬 계정" 선택 + bob/비밀번호 입력 → `POST /api/v1/auth/login {provider:"local", username, password}`.
- **Then** `CompositeAuthenticationManager`가 LOCAL Provider용 `UsernamePassword` 생성 → `LocalProvider.authenticate` → 200 + 세션/JWT 발급. LDAP은 시도조차 안 함.

### S2 — LDAP 사용자 명시 선택 로그인 (정직원)
- **Given** 정직원 alice는 LDAP 디렉토리 계정 보유.
- **When** "회사 계정(LDAP)" 선택 + alice/비밀번호 → `{provider:"ldap", ...}`.
- **Then** LDAP Provider용 `LdapBind` 생성 → `LdapProvider.authenticate`(bind 검증 + JIT 프로비저닝) → 200. **(G1 해소)**

### S3 — 자동(미선택) fallback
- **Given** 사용자가 Provider를 명시 선택하지 않음(드롭다운 "자동").
- **When** `{provider:"auto"}`(또는 provider 생략) + username/password.
- **Then** username/password 계열 **활성** Provider를 `sort_order`(없으면 priority) 우선순위 순(LDAP→LOCAL)으로 순회. 각 Provider의 supports에 맞는 Credential 생성·`authenticate` 시도. **첫 Success 반환**. 전부 Failure면 401 `invalid_credentials`.

### S4 — 로그인 화면 동적 목록
- **Given** 운영자가 authn_providers에서 LDAP을 `enabled=false`로 비활성화.
- **When** 로그인 화면 진입 → `GET /api/v1/auth/providers`.
- **Then** 응답에 LDAP 미포함(LOCAL만 + "자동"). 드롭다운이 응답 기반으로 렌더(하드코딩 제거). **(G4·G5 해소)**

### S5 — SAML/OIDC 공존 (회귀 없음)
- **Given** SAML/OIDC IdP 활성.
- **When** 로그인 화면 진입.
- **Then** username/password 드롭다운(LOCAL/LDAP/자동) + 기존 SAML/OIDC 버튼이 함께 표시. SSO는 리다이렉트 흐름 그대로(본 작업 미변경).

## 기능 요구사항 (FR)

- **FR-06-01** `POST /api/v1/auth/login`은 `provider` 값을 해석한다. `"local"`/`"ldap"`(또는 providerId) → 해당 type Provider 단일 인증. `"auto"` 또는 미지정 → fallback chain.
- **FR-06-02** 명시 선택 인증. provider type에 맞는 Credential 생성 — LOCAL→`UsernamePassword`, LDAP→`LdapBind`. 지정 Provider가 비활성/미존재면 401 `invalid_credentials`(존재 여부 누출 금지).
- **FR-06-03** 자동 fallback. username/password 계열(supports가 UsernamePassword 또는 LdapBind인) **활성** Provider를 `sort_order` 오름차순(동률 시 priority 내림차순, EC-25 관례 유지) 순으로 시도, 첫 `AuthnResult.Success` 반환.
- **FR-06-04** fallback 중 한 Provider가 `ProviderUnavailableException`(LDAP 503) → 그 Provider만 건너뛰고 다음 시도. 모든 후보가 unavailable이면 503, 후보 일부라도 정상 시도되어 모두 Failure면 401.
- **FR-06-05** `CompositeAuthenticationManager`는 `@Service`(@Transactional 시 ArchUnit 가드) 단일 책임 컴포넌트로 분리. `AuthController.login`은 이 컴포넌트에 위임(컨트롤러 @Transactional 0건, learning #91 준수).
- **FR-06-06** `GET /api/v1/auth/providers`는 username/password 계열 Provider를 **코드 Bean ∩ DB authn_providers** 기준으로 반환한다. type별 DB row가 있으면 `enabled`/`sort_order` 반영, 없으면 기본(enabled=true, sort_order=priority 파생). 비활성 Provider 제외. `sort_order` 오름차순 정렬. 응답에 **"자동(auto)" 항목 포함**(드롭다운 기본값).
- **FR-06-07** 프론트 `LoginForm`의 provider 드롭다운은 `GET /api/v1/auth/providers`로 동적 렌더. 하드코딩 `z.enum` 제거. 기본 선택 = "자동". SAML/OIDC 버튼은 기존 유지.
- **FR-06-08** E2E — S1(Local 선택) / S2(LDAP 선택) / S3(자동 fallback) / S4(비활성 제외) 시나리오.

## 비기능 요구사항 (NFR)

- **NFR-06-01 보안 — 계정 열거 방지**. 명시 선택·자동 모두 실패 시 동일한 401 `invalid_credentials`. provider 존재/비활성 여부를 응답·타이밍으로 구분 노출 금지.
- **NFR-06-02 보안 — password CharArray 수명**. fallback에서 Provider마다 재시도하므로, 각 `authenticate` 호출은 자신의 CharArray를 wipe(§1.1). Composite는 시도마다 **새 CharArray 사본**을 만들고, 전체 종료 시 원본 평문 문자열 참조 제거.
- **NFR-06-03 fallback 범위 한정**. 자동 fallback은 **username/password 계열만**. SAML/OIDC(supports=false, 리다이렉트)는 fallback chain 비포함.
- **NFR-06-04 로깅**. password 절대 미기록(§1.1). fallback 시도 Provider type은 로깅 가능(PII 무관).
- **NFR-06-05 회귀 0**. 기존 FR-AU-02/03/04/05/09 테스트 + login E2E 그대로 통과. SSO @Order 체인 무변경.

## API 인터페이스 (REST)

### `POST /api/v1/auth/login` (변경 — body 해석 추가)
```jsonc
// 요청
{ "provider": "local" | "ldap" | "auto", "username": "...", "password": "..." }
// provider 생략 시 "auto"로 간주(하위호환)
// 응답 — 기존과 동일: 200 TokenResponse{accessToken} + Set-Cookie refresh / 401 {error:"invalid_credentials"} / 503
```
> `LoginRequest.provider`는 현재 `String`(필수, AuthController.kt:434-438). 본 작업에서 의미 부여 + "auto"/생략 허용.

### `GET /api/v1/auth/providers` (변경 — DB enabled/sort_order 반영 + auto 항목)
```jsonc
// 응답 (기존 ProvidersResponse 구조 유지, 내용만 동적화)
{ "providers": [
  { "id": "auto",  "type": "AUTO",  "displayName": "자동", "priority": <max+1>, "available": true },
  { "id": "ldap",  "type": "LDAP",  "displayName": "회사 계정", "priority": <sort_order/priority>, "available": true },
  { "id": "local", "type": "LOCAL", "displayName": "로컬 계정", "priority": ..., "available": true }
]}
```
> 기존 `ProviderEntry`(id/type/displayName/priority/available) 스키마 재사용. SAML/OIDC는 본 엔드포인트에서 제외(별도 `/saml/idps`·`/oidc/providers`가 담당) — 드롭다운은 username/password 계열만.

## 데이터 모델 변경

- **컬럼 변경 없음** — `enabled`/`sort_order` 이미 존재(V002).
- **신규 마이그레이션 V020 (선택적, 결정 필요 → brainstorming)** — LOCAL/LDAP authn_providers seed row. 단 LDAP config(serverUrl)는 **환경 의존**이라 production 마이그레이션 하드코딩 부적절. → 기본 설계는 **seed 불요**(FR-06-06의 "DB row 없으면 기본 활성" 경로). 운영자가 LDAP을 끄려면 authn_providers에 LDAP row를 등록(관리 UI는 후속 FR). 본 PR은 **마이그레이션 없이** 코드 Bean ∩ DB 오버레이로 처리.

## 엣지 케이스

- **EC-06-01** 같은 username이 LDAP·Local 양쪽 존재 + 자동 모드. → sort_order 우선순위(LDAP 먼저)로 첫 성공 반환. **명시 선택 시에는 그 Provider만** → 사용자가 의도한 계정 보장. (자동 모드의 동명이인 위험은 NFR/문서에 명시, FR-AU-07 도메인 라우팅이 후속 개선)
- **EC-06-02** provider="ldap"인데 LDAP 비활성. → 401(존재 누출 금지).
- **EC-06-03** authn_providers에 username/password 계열 활성 Provider 0개. → providers 응답에 "자동"만 또는 빈 + 로그인 시 401. (운영 사고 방지 — 최소 LOCAL은 항상 코드 Bean 존재)
- **EC-06-04** LDAP unavailable(503) + 자동 모드 + Local 성공 가능. → LDAP 건너뛰고 Local 성공 → 200. (FR-06-04)
- **EC-06-05** provider 값이 알 수 없는 문자열(예 "saml"). → username/password 계열 아님 → 401(또는 400). SSO는 이 엔드포인트로 인증하지 않음.
- **EC-06-06** 프론트 providers fetch 실패. → 드롭다운 "자동" 단일 fallback 렌더(로그인 자체는 가능하게).

## 제약 조건

- BC 격리 — identity-access 단일 PR. 타 BC 호출 없음.
- DEVELOPMENT.md §1 — 평문 비밀번호 금지, 토큰 sessionStorage, CSRF 유지, 인증 없는 엔드포인트 추가 금지(providers/login은 기존 permitAll 유지).
- SSO @Order 체인(SamlSecurityConfig/OidcSecurityConfig) 무변경.
- 같은 type 여러 인스턴스(LdapTemplate 동적 wiring) 스코프 제외.

## 측정 가능한 완료 기준

1. `CompositeAuthenticationManager` 단위 테스트 — 명시 선택(local/ldap) + 자동 fallback(LDAP실패→Local성공) + 전부실패 401 + LDAP unavailable 건너뜀.
2. LDAP username/password 로그인 통합 테스트 — `{provider:"ldap"}`로 200 (G1 해소 증명, Testcontainers OpenLDAP 재사용).
3. `GET /api/v1/auth/providers` — DB enabled=false Provider 제외 + sort_order 정렬 + auto 포함 (MVC 통합 테스트).
4. 프론트 — LoginForm 드롭다운 동적 렌더 단위 테스트(MSW), 하드코딩 enum 제거(grep 0).
5. E2E — S1/S2/S3/S4 Playwright.
6. 기존 식별자 회귀 0 — `./gradlew test`(identity-access) + `pnpm test`/`test:e2e` + ktlint/detekt/typecheck/lint 그린.
