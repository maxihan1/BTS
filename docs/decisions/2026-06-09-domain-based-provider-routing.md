<!-- ADR — FR-AU-07 도메인 기반 Provider 라우팅(Home Realm Discovery): SSO 전용 + 자동 리다이렉트 + DB/시드 관리 -->

# ADR: 도메인 기반 Provider 라우팅 (Home Realm Discovery)

> 결정일. 2026-06-09
> 상태. Accepted
> 컨텍스트. identity-access BC §2.7 FR-AU-07 (slug `fr-au-07-provider`)
> 선행. ADR `2026-06-09-multi-provider-explicit-selection`(FR-AU-06 — 명시 선택, 자동 fallback 폐기), `2026-06-04-saml-sso-provider`, `2026-06-04-oidc-sso-provider`.

## 컨텍스트

FR-AU-06은 여러 인증 방식(LOCAL·LDAP·SAML·OIDC)을 동시 활성화하고 로그인 화면이 모든 활성 Provider를 보여준 뒤 사용자가 **명시 선택**하게 했다. 자동 fallback은 보안 위험 3종(동명이인 타계정 로그인·비밀번호 오전달·lockout 2배)으로 폐기했고, "똑똑한 자동 선택"을 FR-AU-07에 위임했다.

FR-AU-07은 사용자가 입력한 이메일의 **도메인**(`alice@partner.com` → `partner.com`)을 키로 알맞은 Provider를 자동으로 안내한다(업계 용어 Home Realm Discovery — 어느 신원 영역에 속하는지 도메인으로 판별).

설계 직전 스키마 조사에서 Provider 종류별 저장 위치의 비대칭이 드러났다.
- **SAML/OIDC** — 각자 설정 테이블(`saml_idp_configs`·`oidc_provider_configs`)을 두고 중앙 레지스트리 `authn_providers(id)`를 `authn_provider_id` FK로 참조한다. **항상 `authn_providers` 행이 존재**한다.
- **LOCAL/LDAP** — 코드 Bean으로만 존재하고 `authn_providers`에 시드 행이 **없다**(FR-AU-06 ADR D3).

이 비대칭이 라우팅 대상 모델·매칭 동작·관리 범위를 가른다.

## 결정

### D1. 라우팅 대상 — SSO 전용 (SAML/OIDC) (Maxi 2026-06-09)
`domain_provider_routes.provider_id`는 `authn_providers(id)`를 FK로 참조하되, **유효 대상은 SAML/OIDC type 행뿐**이다. LOCAL/LDAP는 라우팅 대상에서 제외한다.
- 근거. (1) 업계 Home Realm Discovery는 본질적으로 SSO 디스커버리다("이 도메인은 X사 IdP를 쓴다"). (2) SAML/OIDC는 항상 `authn_providers` 행을 보유해 FK가 깨끗하다. (3) LOCAL/LDAP는 username/password 계열이라 자동 라우팅이 FR-AU-06이 막은 비밀번호 오전달 위험을 재도입한다. LOCAL/LDAP는 기존대로 로그인 폼에서 수동 선택한다.

### D2. 매칭 동작 — 자동 리다이렉트 + 미매칭 fallback (Maxi 2026-06-09)
이메일 도메인이 라우트와 일치하면 해당 SSO 진입점으로 **자동 리다이렉트**한다.
- 안전성. SSO는 비밀번호가 BTS를 거치지 않고 IdP에서 직접 인증되므로, 자동 진입이 FR-AU-06의 비밀번호 오전달 위험을 만들지 않는다. FR-AU-06 ADR이 FR-AU-07에 "안전하게 위임"한 자동 선택이 바로 이 경우다.
- 미매칭. 라우트가 없는 도메인이면 자동 진입 없이 **기존 전체 Provider 목록**(LOCAL/LDAP 드롭다운 + SAML/OIDC 버튼)을 그대로 보여준다(현행 동작 보존, fail-safe).

### D3. 도메인 매칭 정책 — exact + UNIQUE + lowercase 정규화
- **exact match only**. `partner.com` 라우트는 `partner.com`에만 매칭. 서브도메인(`eng.partner.com`) 매칭은 본 FR 제외 → 후속 FR(필요 시).
- **domain UNIQUE**. 한 도메인은 정확히 하나의 Provider로만 라우팅(모호성 0). DB UNIQUE 제약으로 강제.
- **lowercase 정규화**. 저장·조회 모두 도메인을 소문자로 정규화(`Partner.COM` = `partner.com`). 이메일 도메인은 대소문자 구분 없음(RFC).

### D4. 관리 범위 — DB/시드만, 관리 API/UI는 후속 FR (Maxi 2026-06-09)
본 FR은 **라우팅 조회 로직 + 로그인 UX**만 구현한다. `domain_provider_routes` 행은 DB로 직접 등록(현 Provider 설정 관리 관례와 동일 — authn_providers/saml/oidc 모두 관리 UI 없이 DB/시드). SYSTEM_ADMIN 관리 API/UI는 후속 FR.
- 마이그레이션. 신규 테이블 V020 생성만, **시드 행 없음**(라우트는 환경 의존이라 production 하드코딩 부적절. FR-AU-06 D3 LOCAL/LDAP seed 안 함과 동일 정신).

## 결과

- 신규 테이블 `domain_provider_routes(domain UNIQUE, provider_id → authn_providers(id))` — V020 + `init_codegen.sql` 미러.
- 신규 조회 엔드포인트(permitAll, 로그인 전 호출) — 이메일/도메인 → 라우팅 결과(SSO type + registrationId + displayName) 또는 미매칭. 계정 존재 여부는 절대 노출 안 함(도메인만 판단).
- 프론트 로그인 폼 — 이메일 입력 → 라우트 조회 → 매칭 시 해당 SSO 자동 진입, 미매칭 시 기존 폼 유지.

## 보안 (DEVELOPMENT.md §1)
- **계정 열거 방지**. 라우팅은 이메일의 **도메인**만 보고 판단한다. 특정 계정의 존재 여부를 응답으로 구분하지 않는다(도메인 라우트 유무만 드러남 — 도메인 설정은 비밀이 아니며 위험 낮음).
- **비밀번호 오전달 0**. 라우팅 대상이 SSO 전용이라 BTS가 평문 비밀번호를 받지 않는다(IdP 직접 인증).
- **신규 미인증 엔드포인트 최소화**. 라우팅 조회는 로그인 전 단계라 permitAll이어야 하며, 기존 `/api/v1/auth/providers`·`/saml/idps`·`/oidc/providers` permitAll 관례를 따른다.
- **SQL prepared**. NamedParameterJdbcTemplate(현 repository 관례).
- **provider_id 유효성**. 라우트가 SAML/OIDC 행을 가리키는지 조회 시 검증(LOCAL/LDAP·비활성·삭제 행으로의 잘못된 라우팅 차단).

## 관련
- 마스터플랜 §2.7 (`docs/plan/product/identity-access.md`)
- 선행 ADR `docs/decisions/2026-06-09-multi-provider-explicit-selection.md` (FR-AU-06)
- SDD 19장(인증)
