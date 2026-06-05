# FR-PM-06 — 이슈 보안 수준 (Issue Security Level)

> slug: fr-pm-06-issue-security-level
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-05

## Brief

FR-PM-06 이슈 보안 수준. 이슈마다 보안 등급(IssueSecurityLevel: 이름·설명·허용 역할 목록)을
지정하고, VIEW_ISSUE 권한을 통과해도 그 이슈의 보안 수준을 통과하지 못하면 못 보게 하는
추가 차단 계층. FR-PM-05(Browse/View 분리)의 직속 후속.

- SDD 정본: docs/sdd/12-permissions.md §12.4
- plan 추적: docs/plan/product/identity-access.md §4.6
- 단계: D1 도메인 / D2 명세 / D3 데이터모델(security_levels, issues.security_level_id) /
  D4 백엔드 가드 / D5 백엔드 테스트 / D6 프론트 UI / D7 E2E

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
