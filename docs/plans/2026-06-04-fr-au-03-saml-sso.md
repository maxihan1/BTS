# FR-AU-03 — SAML 2.0 SSO

> slug: fr-au-03-saml-sso
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-04

## Brief

FR-AU-03 SAML 2.0 SSO 구현 — identity-access BC.

선행 FR-AU-01(플러그형 AuthenticationProvider 구조, 완료) 위에:
- `spring-security-saml2-service-provider` 기반 SP-initiated + IdP-initiated 흐름
- `saml_idp_configs` 데이터 모델
- `/sso/saml2/...` 엔드포인트
- Keycloak SAML 모드 Testcontainers 통합 테스트
- IdP 선택 프론트 UI (D6) + E2E (D7)

plan 슬롯: `docs/plan/product/identity-access.md §2.3 (D1~D7)`.

**병행 주의** — 같은 identity-access BC인 `system-admin-role`(#75, 전역 권한 인프라)·`fr-pm-04`(#73)와
SAML Provider 등록(`ProviderRegistry`/`authn_providers`)·`SecurityContext`/SecurityFilterChain 충돌 가능성.
또 FR-IS-08 프론트(#74, issue-tracking BC)와 프론트 공유 인프라(라우터/MSW 핸들러 인덱스/api 공통)
충돌 선점 확인 필요. → spec 단계에서 grep 검증.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
