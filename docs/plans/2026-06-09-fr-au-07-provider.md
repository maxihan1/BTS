# FR-AU-07 — 도메인 기반 자동 라우팅

> slug: fr-au-07-provider
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-09

## Brief

**사용자 원문**. "fr-au-07 진행하자" — FR-AU-07 도메인 기반 자동 라우팅, 이메일 도메인으로 인증 Provider 자동 선택.

**명세 위치**. `docs/plan/product/identity-access.md §2.7`

**핵심**. 사용자가 이메일을 입력하면 그 이메일의 도메인(`@partner.com`)을 키로 매핑된 인증 Provider로 자동 진입시킨다. FR-AU-06(#101, 다중 Provider 명시 선택)이 의도적으로 미룬 "똑똑한 자동 선택"을 담당.

**명세 D 단계**.
- D1. 도메인 (security-engineer)
- D2. 명세 — 이메일 도메인 → Provider 매핑 (security-engineer)
- D3. 데이터 모델 — `domain_provider_routes(domain, provider_id)` (db-engineer)
- D4. 백엔드 — 이메일 입력 → Provider 자동 선택 (security-engineer)
- D5. 백엔드 테스트 (security-engineer)
- D6. 프론트 UI — 이메일 입력 후 Provider 자동 진입 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

**분류 결과**. type=auth, agent=security-engineer, slug=fr-au-07-provider, primary_bc=identity-access. 최신 마이그레이션 V019 → 신규 V020 예정.

## 도메인 정리

- **BC**: identity-access
- **작업 본질**: Home Realm Discovery — 이메일 도메인으로 인증 Provider 자동 안내.
- **영향 엔티티**:
  - `DomainProviderRoute` (신규) — `domain_provider_routes(domain UNIQUE, provider_id → authn_providers(id))`. V020.
  - `authn_providers` (기존) — 라우팅 대상 레지스트리. SAML/OIDC만 유효(항상 행 존재).
  - `saml_idp_configs` / `oidc_provider_configs` (기존) — authn_provider_id로 registration_id·displayName 조회.
- **새 용어**: "도메인 라우팅" / "Home Realm Discovery" (이메일 도메인으로 신원 영역 판별 → glossary 추가 후보, Maxi 승인 대기).
- **핵심 설계 결정 (Maxi 2026-06-09)**:
  - D1. 라우팅 대상 **SSO 전용(SAML/OIDC)**. LOCAL/LDAP 제외(authn_providers 행 부재 + 비번 오전달 위험).
  - D2. 매칭 시 **자동 리다이렉트**, 미매칭 시 기존 전체 목록 fallback.
  - D3. 도메인 **exact match + UNIQUE + lowercase 정규화**. 서브도메인 매칭 후속.
  - D4. 관리 **DB/시드만**(라우트 CRUD API/UI 후속 FR). V020 시드 행 없음.
- **기존 결정 충돌**: 없음. FR-AU-06 ADR이 명시적으로 FR-AU-07에 자동 선택 위임.
- **관련 ADR**: [docs/decisions/2026-06-09-domain-based-provider-routing.md](../decisions/2026-06-09-domain-based-provider-routing.md) (생성됨), 선행 [2026-06-09-multi-provider-explicit-selection.md](../decisions/2026-06-09-multi-provider-explicit-selection.md)
- **회귀 주의(learnings)**: jOOQ init_codegen 미러(V020), 마이그레이션 V번호 머지 직전 재확인, PG NULL UNIQUE 멱등성, 계정 열거 방지(도메인만 판단), cross-BC 아님(identity-access 단일).

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
