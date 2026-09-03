<!-- FR-AU-07 도메인 기반 자동 라우팅 기술 스펙 — 이메일 도메인 → SSO Provider 자동 진입 (Home Realm Discovery) -->

# FR-AU-07 도메인 기반 자동 라우팅 — 스펙

> BC. identity-access | type. auth | 담당. security-engineer
> 스코프 확정(Maxi 2026-06-09).
> - 라우팅 대상 = **SSO 전용(SAML/OIDC)**. LOCAL/LDAP 제외(authn_providers 시드 행 부재 + 비밀번호 오전달 위험).
> - 매칭 동작 = **자동 리다이렉트**, 미매칭 시 기존 전체 Provider 목록 fallback.
> - 도메인 매칭 = **exact + UNIQUE + lowercase 정규화**. 서브도메인 매칭 후속 FR.
> - 관리 = **DB/시드만**. 라우트 CRUD API/UI 후속 FR(현 Provider 관리 관례와 동일).
> - ~~로그인 UX = **identifier-first 2단계**(Maxi 2026-06-09, brainstorming gap 해소). 1단계 이메일만 입력 → "계속" → 매칭 시 SSO 자동 진입 / 미매칭 시 2단계(기존 provider 드롭다운+username+password) 노출. Google/Microsoft 방식.~~
>   **2026-09-03 폐기.** 로그인 UX = **단일 화면**. 식별자·비밀번호를 함께 받고 도메인 조회는 blur ‖ 디바운스 배경 조회로,
>   매칭 시 자동 이동이 아니라 SSO 버튼 노출로 바뀌었다. 백엔드 계약은 불변.
>   정본 `docs/specs/2026-09-03-login-modal-ux.md` · ADR `docs/decisions/2026-09-03-login-modal-and-single-screen-form.md`.
> 선행. FR-AU-03(SAML) · FR-AU-04(OIDC) · FR-AU-06(다중 Provider 명시 선택, #101) 완료.
> ADR. `docs/decisions/2026-06-09-domain-based-provider-routing.md`

## 배경 — 무엇을 더하는가

FR-AU-06 이후 로그인 화면은 **모든** 활성 Provider를 동시에 보여주고(LOCAL/LDAP 드롭다운 + SAML/OIDC 버튼) 사용자가 명시 선택한다. FR-AU-07은 그 위에 "이메일 도메인을 보고 알맞은 SSO로 자동 안내"하는 디스커버리 계층을 얹는다. 사용자가 `alice@partner.com`을 입력하면 `partner.com`에 매핑된 SAML/OIDC로 즉시 진입한다.

## 인프라 현황 (이미 존재 — 재사용)

- `authn_providers(id, type, enabled, ...)` — 중앙 Provider 레지스트리(V002). SAML/OIDC는 항상 행 보유.
- `saml_idp_configs(registration_id UNIQUE, display_name, authn_provider_id → authn_providers, enabled)` — V010.
- `oidc_provider_configs(registration_id UNIQUE, display_name, authn_provider_id → authn_providers, enabled)` — V011.
- SSO 진입 표준 엔드포인트(Spring Security) — SAML `/saml2/authenticate/{registrationId}`, OIDC `/oauth2/authorization/{registrationId}`. 프론트는 `window.location.assign`로 풀 네비게이션(SamlIdpButtons/OidcIdpButtons 선례).
- permitAll 로그인전 조회 선례 — `/api/v1/auth/providers`·`/saml/idps`·`/oidc/providers`. 프론트 Zod 스키마 1:1 정합 소비.
- 최신 마이그레이션 V019 → 신규 V020.

## 사용자 시나리오 (Given-When-Then)

> ⚠️ **2026-09-03 deviation.** S1~S4 의 UI 흐름은 `docs/specs/2026-09-03-login-modal-ux.md` 가 갱신했다.
> **백엔드 계약(`GET /api/v1/auth/route`)과 매칭 규칙은 변경 없다.** 프론트만 바뀌었다.
> - 1단계 이메일 입력 + "계속" 폐기 → **단일 화면**에서 식별자 blur ‖ 500ms 디바운스로 배경 조회
> - 매칭 시 **자동 리다이렉트 폐기** → SSO 버튼을 비밀번호 위에 노출, 이동은 사용자 클릭
> - "2단계 노출"·"username 프리필" 개념 소멸 — 폼이 처음부터 하나다
> 근거. 단일 화면에서 조회 트리거가 blur/디바운스로 **수동적**이라, 자동 풀 네비게이션은
> 타이핑 중이던 비밀번호와 함께 화면을 없앤다. ADR `2026-09-03-login-modal-and-single-screen-form` D5·D6.

### S1 — 도메인 매칭 → SAML 진입 (정상)
- **Given** `domain_provider_routes`에 `partner.com → (SAML registration "partnerSaml")` 등록, 해당 SAML config enabled=true.
- **When** 로그인 화면에서 식별자 `alice@partner.com` 입력 후 blur.
- **Then** `GET /api/v1/auth/route?domain=partner.com` → `{matched:true, type:"SAML", registrationId:"partnerSaml", displayName:"Partner SSO"}`. 프론트가 SSO 버튼을 노출하고, 사용자가 클릭하면 `/saml2/authenticate/partnerSaml`로 이동한다. 비밀번호 입력 없음(IdP에서 인증).

### S2 — 도메인 매칭 → OIDC 진입 (정상)
- **Given** `acme.com → (OIDC registration "acmeOidc")`, enabled=true.
- **When** `bob@acme.com` 입력 후 blur.
- **Then** `{matched:true, type:"OIDC", registrationId:"acmeOidc", ...}` → SSO 버튼 노출 → 클릭 시 `/oauth2/authorization/acmeOidc` 이동.

### S3 — 도메인 미매칭 → 로컬 폼만 (정상)
- **Given** `freelancer@gmail.com`의 `gmail.com`은 라우트 미등록.
- **When** 식별자 입력 후 blur.
- **Then** `{matched:false}`. SSO 버튼이 나타나지 않고 기존 로그인 폼(LOCAL/LDAP 드롭다운 + username + password + SAML/OIDC 버튼 전체)이 그대로 남는다. 사용자가 직접 방식 선택(FR-AU-06 동작 보존).

### S4 — 도메인은 등록됐으나 대상 SSO 비활성/삭제 → 미매칭 취급 (fail-safe)
- **Given** `partner.com → providerX` 라우트는 있으나 그 SAML/OIDC config가 enabled=false(또는 LOCAL/LDAP provider를 가리킴).
- **When** `alice@partner.com` 입력 후 blur.
- **Then** `{matched:false}` → 기존 폼 fallback. 끊긴 라우트가 사용자를 막지 않는다.
  매칭된 경우에도 로컬 제출 버튼은 강등되되 enabled 로 남아 같은 원칙을 지킨다.

### S5 — 대소문자/공백 정규화 (정상)
- **When** `Alice@Partner.COM ` 입력.
- **Then** 도메인 `partner.com`으로 정규화 후 조회 → S1과 동일 매칭.

### S6 — `@` 없는 식별자 → 조회 없음 (2026-09-03 추가)
- **Given** LDAP 사용자명 `alice`.
- **When** 입력 후 blur.
- **Then** 도메인이 비므로 `GET /auth/route` 를 **호출하지 않는다**. 불필요한 왕복을 만들지 않는다.

## 기능 요구사항 (FR)

- **FR-07-01** `domain_provider_routes(domain UNIQUE, provider_id → authn_providers(id))` 테이블 신규(V020). 시드 없음.
- **FR-07-02** 도메인 라우팅 조회 엔드포인트 `GET /api/v1/auth/route?domain={domain}`(permitAll). 도메인을 lowercase 정규화 후 조회.
- **FR-07-03** 매칭 판정 — 라우트의 provider_id가 가리키는 **enabled=true인 SAML 또는 OIDC config**가 있을 때만 매칭. 그 외(미등록 도메인·비활성·LOCAL/LDAP 지시·삭제)는 모두 미매칭.
- **FR-07-04** 매칭 응답 `{matched:true, type, registrationId, displayName}`. 미매칭 응답 `{matched:false}`. (둘 다 200 — 도메인 라우트 유무를 상태코드로 구분하지 않음.)
- **FR-07-05** 프론트 로그인 — **identifier-first 2단계**. 1단계: 이메일 입력 + "계속" → 라우트 조회. 매칭이면 type에 맞는 SSO 엔드포인트(`/saml2/authenticate/{id}` 또는 `/oauth2/authorization/{id}`)로 자동 리다이렉트(registrationId는 `encodeURIComponent` 적용). 미매칭이면 2단계(기존 provider 드롭다운+username+password+SAML/OIDC 버튼) 노출, 입력 이메일을 username에 프리필(수정 가능).

## 비기능 요구사항 (NFR)

- **NFR-07-01 (계정 열거 방지)** 라우팅은 이메일의 **도메인만** 본다. 특정 계정 존재 여부를 응답으로 구분하지 않는다. 응답은 도메인 라우트 유무만 드러내며(설정값, 비밀 아님), 사용자/비밀번호는 일절 받지 않는다.
- **NFR-07-02 (PII 최소화)** 프론트가 이메일에서 **도메인만 추출**해 전송한다(전체 이메일은 GET URL/로그에 남기지 않음).
- **NFR-07-03 (신규 미인증 엔드포인트 관례 준수)** `/api/v1/auth/route`는 기존 `/api/v1/auth/providers` permitAll 관례를 따른다. 신규 인증 우회 경로 0.
- **NFR-07-04 (fail-safe)** 라우팅 조회 실패·끊긴 라우트는 사용자를 막지 않고 기존 폼으로 떨어진다.

## API 인터페이스 (REST)

```
GET /api/v1/auth/route?domain={domain}        (permitAll)

200 매칭:
  { "matched": true, "type": "SAML", "registrationId": "partnerSaml", "displayName": "Partner SSO" }
200 미매칭:
  { "matched": false }
400:
  domain 누락 또는 형식 오류(빈 문자열 등)
```

- `type` ∈ {"SAML", "OIDC"}. registrationId는 프론트가 `/saml2/authenticate/{id}` 또는 `/oauth2/authorization/{id}` 조립에 사용.
- 민감·내부 정보(authn_provider_id, 인증서, client_secret 등) 절대 미노출(saml/oidc 목록 엔드포인트 관례 동일).

## 데이터 모델 변경 (V020)

```sql
CREATE TABLE domain_provider_routes (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    domain      VARCHAR(253) NOT NULL UNIQUE,                              -- 이메일 도메인(lowercase 정규화, RFC 1035 max 253)
    provider_id UUID         NOT NULL REFERENCES authn_providers (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_domain_provider_routes_provider_id ON domain_provider_routes (provider_id);
```

- `domain` UNIQUE — 한 도메인은 정확히 한 Provider로만(모호성 0).
- `provider_id` ON DELETE CASCADE — 라우트는 Provider에 종속된 메타데이터. Provider 삭제 시 무의미한 라우트 자동 제거.
- init_codegen 미러 **불필요** — identity-access는 jOOQ codegen 미사용(전부 jdbc `NamedParameterJdbcTemplate`). `jooq-init-codegen-mirror` 학습은 issue-tracking 등 jOOQ 모듈 한정.
- 시드 행 없음(라우트는 환경 의존, FR-AU-06 D3 정신과 동일).

## 엣지 케이스

- **EC1** domain 누락/빈값 → 400. (프론트는 이메일에 `@`가 없으면 조회 자체를 안 함.)
- **EC2** 미등록 도메인 → `{matched:false}` (S3).
- **EC3** 라우트는 있으나 대상 SSO config 비활성/삭제/LOCAL·LDAP 지시 → `{matched:false}` (S4, fail-safe).
- **EC4** 대소문자·앞뒤 공백 → lowercase + trim 정규화 후 조회 (S5).
- **EC5** 도메인 UNIQUE 위반(동일 도메인 중복 등록 시도) → DB 제약으로 차단(관리 경로는 후속 FR이나 제약은 본 FR에서 강제).
- **EC6** 동일 provider_id를 여러 도메인이 가리키는 것은 허용(한 SSO가 여러 도메인 담당). provider_id에 UNIQUE 없음.

## 제약 조건

- BC 격리 — identity-access 단일. cross-BC 호출 없음.
- 평문 비밀번호·토큰 비취급(라우팅은 인증 전 단계, 자격증명 0).
- SQL prepared(NamedParameterJdbcTemplate, 현 repository 관례).
- 라우트 CRUD 관리 API/UI는 본 FR 제외(후속).

## 측정 가능한 완료 기준

- [ ] V020 Flyway 마이그레이션 통과(Testcontainers). (init_codegen 미러 불필요 — jdbc-only 모듈)
- [ ] `GET /api/v1/auth/route` — S1~S5 통합테스트 그린(매칭 SAML/OIDC, 미매칭, fail-safe, 정규화).
- [ ] 계정 열거 방지 — 자격증명 미취급 + 도메인만 판단 검증.
- [ ] 프론트 — identifier-first 2단계. 1단계 이메일 입력 → 매칭 SSO 자동 리다이렉트 / 미매칭 2단계 폼 노출 (단위테스트 + E2E).
- [ ] ktlint/detekt/ArchUnit/typecheck 그린. 기존 로그인 E2E(FR-AU-05/06)를 2단계 흐름으로 재조정, 회귀 0.

## Brainstorming Check

✅ 통과 (직접 adversarial sanity check, 1회 iteration). 발견 gap 1건(프론트 로그인 UX 흐름 모호) → Maxi 결정으로 identifier-first 2단계 채택 후 보강. 그 외 점검 통과 — GET이라 CSRF 무관, prepared statement로 SQL injection 0, registrationId는 기존 SSO 목록이 이미 공개하는 값(추가 노출 0), open redirect는 registrationId가 DB 신뢰값+encodeURIComponent로 차단, 도메인 열거는 NFR-07-01에서 수용된 낮은 위험(자격증명 미취급).
