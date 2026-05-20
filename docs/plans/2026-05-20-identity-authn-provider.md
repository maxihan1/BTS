<!-- BTS plan — identity-access FR-AU-01 플러그형 AuthenticationProvider 구조 -->

# FR-AU-01 — 플러그형 AuthenticationProvider 구조

> slug: identity-authn-provider
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-20
> 마스터플랜: `docs/plan/product/identity-access.md §2.1`

## Brief

identity-access BC 정식 진입의 첫 PR. SDD 04장 (인증/계정) §1 + 마스터플랜 §2.1 기준 — 향후 LDAP/SAML/OIDC/Local 등 모든 Provider가 등록될 **확장 지점**을 먼저 구축.

§1 PoC (PR #2)는 Spring Security + Keycloak 한 묶음으로 통합 검증한 PoC였다. 이 PR은 그 위에 정식 도메인 모델 (`Principal`/`Credential`/`AuthnResult`) + 인터페이스 (`AuthenticationProvider`) + 등록 메커니즘 (`ProviderRegistry`) 을 얹는다. 이후 FR-AU-02~05 (LDAP/SAML/OIDC/Local) 가 모두 이 인터페이스 구현체로 들어온다.

**이 PR 스코프** = §2.1 D1~D5 (백엔드 + 백엔드 테스트) 까지. D6 (UI) + D7 (E2E) 는 별도 PR.

## 도메인 정리 (← /bts-domain 채움)

_TBD_

## 스펙 (← /bts-spec Phase A 채움)

_TBD_

## Brainstorming Check (← /bts-spec Phase B 채움)

_TBD_

## Plan (← /bts-plan 채움)

_TBD_

## 리뷰 결과 (← /bts-review-plan 채움)

_TBD_
