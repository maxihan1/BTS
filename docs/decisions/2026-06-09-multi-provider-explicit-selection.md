<!-- ADR — FR-AU-06 다중 Provider 동시 활성화: 명시 선택 채택(자동 fallback 폐기) + providers 목록 DB 동적화 -->

# ADR: 다중 Provider 명시 선택 (자동 fallback 폐기) + providers 목록 DB 동적화

> 결정일. 2026-06-09
> 상태. Accepted
> 컨텍스트. identity-access BC §2.6 FR-AU-06 (slug `fr-au-06-multi-provider`, PR #101)
> 선행. ADR `2026-05-20-authentication-provider-spi-naming`(SPI/ProviderRegistry), `2026-06-04-saml-sso-provider`/`2026-06-04-oidc-sso-provider`(SSO 전용 @Order 체인).

## 컨텍스트

FR-AU-06은 여러 인증 방식(LOCAL·LDAP·SAML·OIDC)을 동시에 활성화하고 로그인 화면이 동적으로 목록을 보여주게 한다. 구현 직전 코드 조사에서 두 사실이 드러났다.

1. **G1 — LDAP username/password 로그인이 사실상 불가**. `AuthController.login`이 항상 `Credential.UsernamePassword`만 생성(`AuthController.kt:103`)하는데, `LdapProvider.supports`는 `Credential.LdapBind`만 받는다(`LdapProvider.kt:75`). 따라서 `ProviderRegistry.findFor(UsernamePassword)`는 `LocalProvider`만 매칭하고 LDAP은 진입점 자체가 없었다.
2. 인프라는 거의 완비. `authn_providers(enabled, sort_order)` 컬럼(V002, "FR-AU-06" 주석)·`GET /api/v1/auth/providers`(코드 Bean 기반)·SAML/OIDC 동적 버튼이 이미 존재. 갭은 username/password 계열의 디스패치·동적 목록·프론트 드롭다운 하드코딩.

product 문서 §2.6 D2는 "Provider 우선순위 + **fallback** 규칙"을 명세했으나, brainstorming 보안 검토에서 자동 fallback의 함정 3종이 드러났다.

## 선택지 — 인증 동작

### A. 자동 fallback (우선순위 순으로 여러 Provider 순차 시도)
- 장점. 사용자가 방식을 안 골라도 로그인 가능.
- 단점(보안). ① **동명이인** — LDAP `alice`(직원)와 Local `alice`(외부 협력사)가 공존하면, LDAP 인증 실패 후 Local로 재시도하다 우연히 타인 계정에 로그인. ② **비밀번호 오(誤)전달** — 같은 평문 비밀번호가 의도와 다른 백엔드(LDAP 서버 로그 등)로 전송. ③ **lockout 2배** — 한 번의 오타가 LDAP·Local 양쪽 실패 카운트를 증가.

### B. 명시 선택만 (사용자가 방식을 고르고, 그 Provider로만 인증)
- 장점. 위 3종 위험 0. 비밀번호가 의도한 백엔드로만 전송.
- 단점. 사용자가 로그인 시 방식을 한 번 선택해야 함(드롭다운 기본값 제공으로 완화).

## 결정

### D1. **B 채택 — 명시 선택만, 자동 fallback 폐기** (Maxi 2026-06-09)
`CompositeAuthenticationManager`(product D4 명명 유지)가 요청의 `provider`(예 `"local"`/`"ldap"`)를 `ProviderType`으로 파싱(`runCatching valueOf` — unknown은 Failure), username/password 계열(LOCAL/LDAP)만 허용, 해당 type에 맞는 Credential(UsernamePassword/LdapBind)을 만들어 단일 Provider로 인증한다. **우선순위 순회·fallback 없음.** "똑똑한 자동 선택"은 후속 FR-AU-07(도메인 기반 라우팅 — 이메일 도메인→Provider)이 안전하게 담당한다.

### D2. 인증 결과 → HTTP 매핑 (계정/구성 열거 방지, NFR-06-01)
`AuthnResult.Failure`는 **reason 무관 401 `invalid_credentials`** — 비활성·미등록·인증 실패를 응답으로 구분하지 않는다. provider 누락/빈값은 400 `provider_required`. LDAP 통신 불가 시 `LdapProvider`가 throw하는 `ProviderUnavailableException`만 `AuthController.login` 내부 try/catch로 **503**(catch-all `@ExceptionHandler` 부재라 500 변질 없음).

### D3. providers 목록 — 코드 Bean ∩ DB 오버레이 (마이그레이션 0)
`GET /api/v1/auth/providers`는 username/password 계열(LOCAL/LDAP)만 반환(SAML/OIDC는 별도 `/saml/idps`·`/oidc/providers` 담당, PAT 제외). `authn_providers.enabled`/`sort_order`를 오버레이. **LOCAL/LDAP seed row는 만들지 않는다** — LDAP config(serverUrl)는 환경 의존이라 production 마이그레이션 하드코딩이 부적절. 운영자가 끄려면 authn_providers에 enabled=false row를 등록(관리 UI는 후속).

### D4. `isEnabled` fail-safe 결정성 (코드리뷰 C1)
`AuthnProviderConfigRepository.isEnabled(type)`는 `type`에 UNIQUE가 없어 다중 row가 가능하므로, **`enabled=false` row가 하나라도 있으면 false**, 그 외(row 없음 포함)는 true. `LIMIT 1`의 비결정성을 제거하고 "끄려는 의도가 있으면 끈다"는 fail-safe. row 미등록 시 true는 LOCAL/LDAP이 코드 Bean으로 상시 존재하기 때문(SAML/OIDC는 V010/V011 seed라 이 경로 미진입).

### D5. 같은 type 여러 인스턴스 — 스코프 제외 (후속 FR)
LDAP 서버 2대 등 같은 type 다중 인스턴스(DB serverUrl 기반 LdapTemplate 동적 wiring)는 본 PR 제외. 사내 1000명 규모에 디렉토리 1개로 충분, 복잡도/위험 대비 효익 낮음.

## 결과

- 신규 컴포넌트 `CompositeAuthenticationManager`(@Service) + `AuthnProviderConfigRepository`(jdbc). 마이그레이션 0(컬럼 기존재, seed 안 함).
- `AuthController.login`이 `providerRegistry.findFor` → 디스패처 위임으로 변경(providerRegistry 주입 제거).
- `ProvidersController` username/password 계열만 + DB enabled/sort_order. 프론트 `LoginForm` 하드코딩 enum 제거 → 동적 드롭다운(+fetch 실패 시 LOCAL fallback).
- SSO @Order 체인 무변경. LDAP username/password 로그인(G1) 통합테스트로 증명(provider="local" 음성 대조 포함).

## 보안 (DEVELOPMENT.md §1)
- 평문 비밀번호 미저장(Argon2id, 무변경) · password CharArray는 단일 Provider 호출 뒤 wipe(LdapProvider finally) · 명시 선택으로 비밀번호 오전달 0 · 계정/구성 열거 방지(401 통일) · SQL prepared(NamedParameterJdbcTemplate) · providers/login 기존 permitAll 재사용(신규 미인증 엔드포인트 0) · 토큰 sessionStorage.

## 관련
- 마스터플랜 §2.6 (`docs/plan/product/identity-access.md`)
- plan `docs/plans/2026-06-09-fr-au-06-multi-provider.md` · spec `docs/specs/2026-06-09-fr-au-06-multi-provider.md`
- 후속 FR-AU-07(도메인 라우팅) — "똑똑한 자동 선택" 담당
- SDD 19장(인증)
