# FR-AU-06 다중 Provider 동시 활성화

> slug: fr-au-06-multi-provider
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-09

## Brief

FR-AU-06 다중 Provider 동시 활성화 — CompositeAuthenticationManager + Provider 우선순위/fallback + authn_providers.priority,enabled + 다중 Provider 선택 화면.

분류 결과. type=auth · agent=security-engineer · BC=identity-access.

product 문서 §2.6 (docs/plan/product/identity-access.md:106) 기준 D1~D7.
- D1. 도메인 (security-engineer)
- D2. 명세 — Provider 우선순위 + fallback 규칙 (security-engineer)
- D3. 데이터 모델 — `authn_providers.priority, enabled` (db-engineer)
- D4. 백엔드 — `CompositeAuthenticationManager` (security-engineer)
- D5. 백엔드 테스트 — 다중 Provider 시나리오 (security-engineer)
- D6. 프론트 UI — 다중 Provider 선택 화면 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리

- **BC**: identity-access (담당 security-engineer)
- **새 용어**: 없음 (기존 유비쿼터스 언어 재사용 — `AuthenticationProvider`, `ProviderRegistry`, `Credential`, `sort_order`)

### 스코프 확정 (Maxi 2026-06-09)

> **옵션 1 채택** — "인증 서버는 하나여도, 로그인 방법 종류(LDAP·Local·SAML·OIDC)는 동시에 켜고 화면이 동적으로 보여준다." 같은 type 여러 인스턴스(LDAP 서버 2대 등 LdapTemplate 동적 wiring)는 **제외 → 후속 FR**. 사내 1000명 규모에 디렉토리 1개로 충분, 복잡도/위험 대비 효익 낮음.
> **인증 동작**: 선택우선 + 자동 fallback. 사용자가 드롭다운에서 고른 Provider 우선 인증, 미선택('자동') 시 `sort_order` 우선순위 순으로 시도(예: LDAP 실패→Local).

### 현행 인프라 (이미 구현됨 — Explore 조사 결과)

| 구성요소 | 위치 | 상태 |
|---|---|---|
| `authn_providers.enabled` / `sort_order` | `V002__authn_providers_and_user_external_accounts.sql:8-17` | **이미 존재** (sort_order에 "FR-AU-06" 주석). 새 마이그레이션 거의 불요. 최신 V019 → 필요 시 V020 |
| `AuthenticationProvider` SPI + `ProviderRegistry` | `identity/spi/` | 존재. `findFor(credential)` = priority 내림차순 **첫 매칭 1개만** 반환 (fallback 없음) |
| `ProviderType(priority)` enum | `identity/spi/ProviderType.kt:30-37` | LOCAL 70 / LDAP 80 / SAML 40 / OIDC 50 / PAT 60 / OAUTH 30 — **코드 하드코딩 우선순위** (DB sort_order와 별개) |
| `GET /api/v1/auth/providers` | `web/ProvidersController.kt:36-51` | 존재. 단 `ProviderRegistry.all()`(코드 등록 Provider) 기반 — **DB enabled/sort_order 미반영** |
| SAML/OIDC 동적 IdP 버튼 | `apps/web` `SamlIdpButtons`/`OidcIdpButtons` | 존재. SSO는 리다이렉트 기반(thin Provider, supports=false) |
| LoginForm Provider 드롭다운 | `apps/web/src/auth/LoginForm.tsx:46` | **하드코딩** `z.enum(['local','ldap-corp'])` |
| `LoginRequest.provider` 필드 | `web/AuthController.kt:434-438` | 정의돼 있으나 **백엔드가 무시** |
| `CompositeAuthenticationManager` | — | **없음** |

### 실제 갭 (FR-AU-06이 메꿀 것)

1. **백엔드 — username/password 인증 fallback**: `AuthController`가 `findFor` 첫 매칭만 시도. `LoginRequest.provider` 무시. → 선택된 Provider 우선 + sort_order 순 fallback chain (D2 fallback 규칙 / D4 CompositeAuthenticationManager).
2. **백엔드 — `/api/v1/auth/providers` DB 동적화**: 코드 enum 기반 정적 목록 → `authn_providers`(enabled=true) + sort_order 반영 (D3 데이터 모델 활용).
3. **프론트 — 로그인 화면 동적 목록**: 하드코딩 enum 제거 → `/api/v1/auth/providers` 응답 기반 동적 렌더 (D6 다중 Provider 선택 화면).
4. **E2E**: 다중 Provider 시나리오(선택 인증 + fallback) (D7).

### 기존 결정 충돌 / 관련 ADR

- **충돌 없음**. 기존 ADR(`authentication-provider-spi-naming`, `saml-sso-provider`, `oidc-sso-provider`) 위에 얹음. SSO 전용 @Order 체인은 그대로, 본 작업은 username/password Provider 흐름 + providers 목록 동적화에 집중.
- **신규 ADR 후보**: "다중 Provider fallback chain (선택우선+자동) + /api/v1/auth/providers DB 동적화" — spec/plan 단계에서 결정 구체화 후 `docs/decisions/2026-06-09-multi-provider-fallback.md` 작성.
- **관련 learning**: [[learnings#2026-05-21 — prod 단일 LdapTemplate vs test dead URL 가정]] — 같은 type 다중 인스턴스는 본 PR 스코프 제외이므로 dead URL 동적 wiring 재도입도 **제외**(후속 FR로 유지).

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
