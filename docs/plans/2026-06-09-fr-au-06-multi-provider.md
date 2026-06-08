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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
