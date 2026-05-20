<!-- BTS plan — identity-access FR-AU-02 LDAP/AD Provider 정식 구현 -->

# FR-AU-02 — LDAP/AD Provider 정식 구현

> slug: identity-ldap-provider
> type: auth
> agent: security-engineer (primary) + db-engineer + designer + frontend-engineer + qa-engineer
> primary_bc: identity-access
> 생성: 2026-05-20
> 마스터플랜: `docs/plan/product/identity-access.md §2.2`
> 선행 PR: #3 (FR-AU-01 SPI 인프라)

## Brief

PR #3 가 도입한 `AuthenticationProvider` SPI 의 **첫 실제 구현**. 사내 LDAP/AD 서버를 통한 인증 + 사용자/그룹 매핑 + 계정 잠금 정책. 마스터플랜 §2.2 D1~D7.

본 PR 에 동반되는 첫 DB 마이그레이션 V002 — `authn_providers` (LDAP/SAML/OIDC config) + `user_external_accounts` (provider, externalId 매핑). FR-AU-01 가 보류했던 D3 도 함께 도입.

또한 PR #3 머지 후 잔여 작업: `scripts/verify/bootjar-no-fakes.sh` 실행 권한 부여 (Maxi 가 머지 후 `! chmod +x` 직접 실행, stash 보관 후 worktree 에서 pop) — chore 커밋 1건으로 묶음.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
