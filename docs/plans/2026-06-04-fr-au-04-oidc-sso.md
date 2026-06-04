# FR-AU-04 — OIDC SSO

> slug: fr-au-04-oidc-sso
> type: auth
> agent: security-engineer
> primary BC: identity-access
> 생성: 2026-06-04

## Brief

FR-AU-04 OIDC SSO (OpenID Connect 기반 Single Sign-On). identity-access BC.

- Authorization Code + PKCE 흐름, JWT(ID Token) 검증
- 기술 스택: `spring-boot-starter-oauth2-client` + `-resource-server`
- 데이터 모델: `oidc_provider_configs` (authn_provider_id FK, SAML `saml_idp_configs` 선례)
- 테스트: Keycloak Testcontainers OIDC 모드
- 구조 재사용: FR-AU-03 SAML SSO (PR #76) — 별도 @Order SecurityFilterChain 분리, JIT 프로비저닝(AutoProvisionService) 재사용, STATELESS↔oauth2Login 세션 충돌 회피

product plan: docs/plan/product/identity-access.md §2.4 (D1~D7)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
