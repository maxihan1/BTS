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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
