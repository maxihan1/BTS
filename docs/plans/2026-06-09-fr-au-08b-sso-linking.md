# FR-AU-08b — SSO(SAML/OIDC) 리다이렉트 방식 계정 연결

> slug: fr-au-08b-sso-linking
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-09

## Brief

FR-AU-08 1차(PR #103)에서 LDAP 동기 연결만 완료. SAML/OIDC 리다이렉트 방식 계정 연결은 후속(FR-AU-08b)으로 분리됨.

로그인된 사용자가 자신의 SSO 외부 신원(SAML/OIDC)을 자기 계정에 **명시적 수동 연결**. 이메일 자동 연결 미도입(계정 탈취 차단, FR-AU-06/07/08 1차 보안 기조 일관). 보안 핵심 SSO 성공 핸들러를 "연결 모드"로 분기 필요.

classify 결과 — type=auth, agent=security-engineer, primary_bc=identity-access.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
