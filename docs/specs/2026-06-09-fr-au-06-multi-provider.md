<!-- FR-AU-06 다중 Provider 동시 활성화 기술 스펙 — username/password 계열 Provider 명시 선택 인증 + 로그인 화면 동적 목록 -->

# FR-AU-06 다중 Provider 동시 활성화 — 스펙

> BC. identity-access | type. auth | 담당. security-engineer
> 스코프 확정(Maxi 2026-06-09).
> - 옵션 1 — "서버는 하나, 로그인 방법 종류(LDAP·Local·SAML·OIDC)는 동시에 켜고 화면이 동적으로 보여준다." 같은 type 여러 인스턴스(LdapTemplate 동적 wiring) **제외 → 후속 FR**.
> - 인증 동작 = **명시 선택만**(brainstorming 보안 검토 후 자동 fallback 폐기). 자동 순차 시도의 위험(동명이인 타계정 로그인 · 비밀번호 오(誤)전달 · lockout 2배)을 회피. "똑똑한 자동 선택"은 **FR-AU-07 도메인 기반 라우팅**이 담당.
> 선행. FR-AU-01(SPI/ProviderRegistry) · FR-AU-02(LDAP) · FR-AU-03(SAML) · FR-AU-04(OIDC) · FR-AU-05(Local) · FR-AU-09(세션/JWT) 모두 완료.

## 배경 — 현재 동작의 실제 갭

| # | 현행 | 위치 | 문제 |
|---|---|---|---|
| **G1** | `AuthController.login`이 **항상 `Credential.UsernamePassword`만 생성** | `web/AuthController.kt:103` | `findFor(UsernamePassword)` → `LocalProvider.supports`(UsernamePassword)만 매칭. **LdapProvider는 `Credential.LdapBind`만 supports**(LdapProvider.kt:75) → username/password 로그인으로 LDAP 진입 불가. **LDAP 사용자는 사실상 로그인 불가** (1순위 버그) |
| **G2** | `LoginRequest.provider` 필드 **무시** | `web/AuthController.kt:103-108` | 사용자가 Provider를 골라도 백엔드가 안 씀 |
| **G3** | 로그인 드롭다운 **하드코딩** `z.enum(['local','ldap-corp'])` | `apps/web/src/auth/LoginForm.tsx:46` | DB·백엔드 목록 미반영. SAML/OIDC는 이미 동적(`fetchSamlIdps`/`fetchOidcProviders`) |
| **G4** | `/api/v1/auth/providers`가 코드 Bean(`ProviderRegistry.all()`)만 사용 | `web/ProvidersController.kt:38` | 운영자가 DB `authn_providers.enabled=false`로 꺼도 목록에 노출. `sort_order` 미반영 |

> **핵심**. G1은 단순 미배선이 아니라 LDAP 로그인을 막는 실질 버그다. FR-AU-06의 1순위.

## 인프라 현황 (이미 존재 — 재사용)

- `authn_providers(enabled BOOLEAN, sort_order INTEGER)` 컬럼 + 부분 인덱스 `idx_authn_providers_type_enabled (type, enabled) WHERE enabled=true` — `V002:8-22`. 최신 마이그레이션 V019.
- SAML/OIDC 동적 목록 선례 — `SamlIdpController.listIdps()`(`findAllEnabled()` → `{idps:[{registrationId,displayName}]}`, permitAll), `OidcProviderController`(동형). 프론트 `api/saml.ts`·`api/oidc.ts`가 Zod 스키마 1:1 정합으로 소비.
- `ProviderRegistry`(코드 Bean 수집, `findByType` 보유) · `ProviderType(priority)`(LDAP 80 / LOCAL 70 / PAT 60 / OIDC 50 / SAML 40 / OAUTH 30) · `Credential` sealed(UsernamePassword / LdapBind / ...).
- `AuthnResult` sealed = Success / Failure / RequiresMfa.

## 사용자 시나리오 (Given-When-Then)

### S1 — Local 사용자 명시 선택 로그인 (외부 협력사)
- **Given** authn_providers에 LOCAL·LDAP 모두 활성. 외부 협력사 bob은 Local 계정만 보유(FR-AU-05).
- **When** 로그인 화면에서 "로컬 계정" 선택 + bob/비밀번호 → `POST /api/v1/auth/login {provider:"local", username, password}`.
- **Then** provider 디스패처가 LOCAL용 `UsernamePassword` 생성 → `LocalProvider.authenticate` → 200 + 세션/JWT. **LDAP은 시도조차 안 함**(동명이인·비번 오전달 위험 0).

### S2 — LDAP 사용자 명시 선택 로그인 (정직원)
- **Given** 정직원 alice는 LDAP 디렉토리 계정 보유.
- **When** "회사 계정(LDAP)" 선택 + alice/비밀번호 → `{provider:"ldap", ...}`.
- **Then** LDAP용 `LdapBind` 생성 → `LdapProvider.authenticate`(bind 검증 + JIT 프로비저닝) → 200. **(G1 해소)**

### S3 — 로그인 화면 동적 목록 (비활성 제외)
- **Given** 운영자가 authn_providers에 LDAP row를 `enabled=false`로 등록.
- **When** 로그인 화면 진입 → `GET /api/v1/auth/providers`.
- **Then** 응답·드롭다운에 LDAP 미포함(LOCAL만). 하드코딩 enum 제거. **(G3·G4 해소)**

### S4 — SAML/OIDC 공존 (회귀 없음)
- **Given** SAML/OIDC IdP 활성.
- **When** 로그인 화면 진입.
- **Then** username/password 드롭다운(LOCAL/LDAP) + 기존 SAML/OIDC 버튼이 함께 표시. SSO는 리다이렉트 흐름 그대로(본 작업 미변경).

## 기능 요구사항 (FR)

- **FR-06-01** `POST /api/v1/auth/login`은 `provider`를 **필수**로 받아 해석한다. 값은 `GET /api/v1/auth/providers`가 돌려준 `id`(예 `"local"`, `"ldap"`)와 동일 어휘. provider 누락/빈값 → 400 `provider_required`(또는 검증 오류).
- **FR-06-02** provider 디스패치. 선택된 provider의 `ProviderType`에 맞는 Credential을 생성한다 — LOCAL→`Credential.UsernamePassword`, LDAP→`Credential.LdapBind`. `ProviderRegistry.findByType`으로 Provider를 특정해 `authenticate` 호출(우선순위 순회·fallback 없음).
- **FR-06-03** 선택 provider가 **비활성/미등록/미지원**(예 `"saml"` 같은 비 username/password 계열, 또는 enabled=false)이면 **401 `invalid_credentials`** — 존재·활성 여부를 응답으로 구분 노출하지 않는다(계정/구성 열거 방지).
- **FR-06-04** `AuthnResult` 처리 — Success→200 세션/JWT(기존 흐름), Failure→401 `invalid_credentials`, RequiresMfa→401 `mfa_required`(기존 계약 유지, MFA 구현은 FR-MF). `LdapProvider`가 던지는 `ProviderUnavailableException`(LDAP 503)은 디스패처가 잡아 **503**으로 반환(fallback 없음 → 다른 Provider 시도 안 함).
- **FR-06-05** provider 디스패치 책임은 단일 컴포넌트(product §2.6 D4 `CompositeAuthenticationManager`)로 분리. `@Service`(ArchUnit `@Transactional`+`@Service` 가드 준수), `AuthController.login`은 이 컴포넌트에 위임(컨트롤러 @Transactional 0건, learning #91). 명명은 책임("여러 활성 Provider 중 요청된 것으로 디스패치")에 맞게 impl 단계에서 확정 가능하나 product D4 의도 유지.
- **FR-06-06** `GET /api/v1/auth/providers`는 **username/password 계열**(supports가 `UsernamePassword` 또는 `LdapBind`인 = LOCAL/LDAP) Provider만 반환한다. **코드 Bean(`ProviderRegistry`) ∩ DB(`authn_providers`)** 기준 — 해당 type DB row가 있으면 `enabled`/`sort_order` 반영(enabled=false 제외), DB row 없으면 기본(활성, sort_order=priority 파생). `sort_order` 오름차순(동률 시 priority 내림차순, EC-25 관례) 정렬. **"auto" 항목 없음**(명시 선택). SAML/OIDC는 본 엔드포인트 제외(별도 `/saml/idps`·`/oidc/providers` 담당).
- **FR-06-07** 프론트 `LoginForm`의 provider 드롭다운을 `GET /api/v1/auth/providers` 기반 동적 렌더. 하드코딩 `z.enum` 제거. **기본 선택 = 응답 첫 항목(sort_order 우선)**. value = 응답 `id`(login에 그대로 전달). SAML/OIDC 버튼은 기존 유지.
- **FR-06-08** E2E — S1(Local 선택) / S2(LDAP 선택) / S3(비활성 제외) 시나리오.

## 비기능 요구사항 (NFR)

- **NFR-06-01 보안 — 계정/구성 열거 방지**. provider 비활성·미등록·인증 실패 모두 동일한 **401 `invalid_credentials`**(provider 형식 오류만 400). 타이밍으로 provider 존재 여부 추론 불가하도록, 디스패치 실패도 정상 인증 실패와 동급 처리.
- **NFR-06-02 보안 — password CharArray 수명**. 디스패처는 단일 Provider만 호출하므로 CharArray 사본 1회. `authenticate`가 호출 내에서 wipe(§1.1). 평문 비밀번호를 의도와 다른 Provider 백엔드로 전송하지 않는다(명시 선택의 직접 효과 — 자동 fallback 폐기 근거).
- **NFR-06-03 로깅**. password 절대 미기록(§1.1). 선택 provider type 로깅 가능(PII 무관).
- **NFR-06-04 회귀 0**. 기존 FR-AU-02/03/04/05/09 단위·통합 테스트 + login/login-ldap E2E 그대로 통과. SSO `@Order` 체인(SamlSecurityConfig/OidcSecurityConfig) 무변경.
- **NFR-06-05 lockout 일관성**. 명시 선택이므로 한 로그인 시도 = 한 Provider 실패 카운트만 증가(자동 모드의 2배 누적 위험 없음). 기존 LDAP `user_external_accounts.failed_attempts` lockout 동작 무변경.

## API 인터페이스 (REST)

### `POST /api/v1/auth/login` (변경 — provider 필수 해석)
```jsonc
// 요청
{ "provider": "local" | "ldap", "username": "...", "password": "..." }
//   provider = GET /providers 응답의 id. 누락/빈값 → 400.
// 응답 — 기존과 동일: 200 TokenResponse{accessToken} + Set-Cookie refresh
//   / 400 {error:"provider_required"} (형식 오류만)
//   / 401 {error:"invalid_credentials"} (인증 실패·비활성·미지원 provider)
//   / 401 {error:"mfa_required"}
//   / 503 (선택한 LDAP unavailable)
```
> `LoginRequest.provider`는 현재 `String`(필수, AuthController.kt:434-438). 본 작업에서 의미 부여. 하위호환 — 기존 클라이언트는 이미 provider를 보냄(LoginForm).

### `GET /api/v1/auth/providers` (변경 — username/password 계열만 + DB enabled/sort_order)
```jsonc
// 응답 (기존 ProvidersResponse / ProviderEntry 구조 유지, 내용만 변경)
{ "providers": [
  { "id": "ldap",  "type": "LDAP",  "displayName": "회사 계정",  "priority": <sort_order/priority>, "available": true },
  { "id": "local", "type": "LOCAL", "displayName": "로컬 계정", "priority": ..., "available": true }
]}
```
> 기존 `ProviderEntry`(id/type/displayName/priority/available) 스키마 재사용. **변경점**: (1) PAT 외에 SAML/OIDC도 제외(username/password 계열만), (2) 정렬·필터를 DB `authn_providers` enabled/sort_order로 오버레이.
> `displayName`은 현재 type명 capitalize(`"Local"`). 한국어 라벨("로컬 계정"/"회사 계정")은 프론트 i18n(`loginStrings`)에서 id→라벨 매핑하거나 백엔드 displayName 보강 — impl 단계에서 기존 `loginStrings.providerLocal/providerLdapCorp` 재사용 방향으로 결정.

## 데이터 모델 변경

- **컬럼 변경 없음** — `enabled`/`sort_order` 이미 존재(V002).
- **신규 마이그레이션 불요**. LOCAL/LDAP authn_providers seed row는 만들지 않는다(LDAP config serverUrl은 환경 의존 → production 마이그레이션 하드코딩 부적절). FR-06-06의 "DB row 없으면 기본 활성" 경로로 처리. 운영자가 특정 type을 끄려면 authn_providers에 해당 row를 등록(enabled=false) — 관리 UI는 후속 FR.

## 엣지 케이스

- **EC-06-01** 같은 username이 LDAP·Local 양쪽 존재. → **명시 선택이므로 각자 자기 Provider로만 인증** → 타계정 로그인 위험 0. (자동 fallback 폐기로 본질 해소)
- **EC-06-02** provider="ldap"인데 LDAP DB row가 enabled=false. → 401 `invalid_credentials`(구성 누출 금지).
- **EC-06-03** username/password 계열 활성 Provider 0개(이론상). → providers 응답 빈 배열. 최소 LOCAL은 코드 Bean 항상 존재 + DB row 없으면 기본 활성이므로 실무상 LOCAL은 항상 노출.
- **EC-06-04** provider 값이 비 username/password 계열(예 "saml"/"oidc"/"pat") 또는 알 수 없는 문자열. → 401 `invalid_credentials`(SSO/PAT는 이 엔드포인트로 인증하지 않음).
- **EC-06-05** 선택한 LDAP unavailable. → 503(fallback 없음).
- **EC-06-06** 프론트 providers fetch 실패. → 드롭다운을 안전 기본값으로 렌더(예: LOCAL 단일) 또는 에러 표시. 로그인 화면이 완전히 깨지지 않게.

## 제약 조건

- BC 격리 — identity-access 단일 PR. 타 BC 호출 없음.
- DEVELOPMENT.md §1 — 평문 비밀번호 금지, 토큰 sessionStorage, CSRF 유지, 인증 없는 엔드포인트 추가 금지(providers/login은 기존 permitAll 유지).
- SSO `@Order` 체인 무변경. PAT는 providers 목록·login 디스패치 대상 아님.
- 같은 type 여러 인스턴스(LdapTemplate 동적 wiring) 스코프 제외.

## 측정 가능한 완료 기준

1. provider 디스패처(D4 컴포넌트) 단위 테스트 — local 선택→UsernamePassword/LocalProvider, ldap 선택→LdapBind/LdapProvider, 미지원/비활성 provider→401, RequiresMfa→401 mfa_required, ProviderUnavailableException→503.
2. **LDAP username/password 로그인 통합 테스트** — `{provider:"ldap"}`로 200 (G1 해소 증명, Testcontainers OpenLDAP 재사용).
3. `GET /api/v1/auth/providers` MVC 통합 — DB enabled=false Provider 제외 + sort_order 정렬 + SAML/OIDC·PAT 제외 + auto 미포함.
4. 프론트 — LoginForm 드롭다운 동적 렌더 단위 테스트(MSW), 하드코딩 `z.enum` 제거(grep 0), value가 응답 id와 일치.
5. E2E — S1/S2/S3 Playwright.
6. 기존 식별자 회귀 0 — `./gradlew test`(identity-access) + `pnpm test`/`test:e2e` + ktlint/detekt/typecheck/lint 그린. login-ldap E2E 기존 통과 유지.

## Brainstorming Check

✅ 통과 (1회 iteration). 자동 fallback 보안 위험 3종(동명이인 타계정 로그인 · 비밀번호 오전달 · lockout 2배) 발견 → Maxi 결정으로 **명시 선택만** 채택, 자동 fallback 폐기. 경미 gap 4건(provider 명명 통일·AUTO 항목 제거·RequiresMfa 처리·ProviderUnavailable try/catch 계약) 스펙 보강 완료.
