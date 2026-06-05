# FR-PM-09 — 사용자 그룹 (전역 그룹 인프라)

> slug: fr-pm-09-user-groups
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-05

## Brief

FR-PM-09 사용자 그룹 백엔드 인프라. 전역(시스템 단위) `UserGroup` + `group_memberships`(사용자↔그룹 N:M),
SYSTEM_ADMIN(FR-PM-08)만 관리하는 그룹 CRUD + 멤버 추가/제거 API. 백엔드 인프라만(D1~D5).

BTS에 그룹 개념 전무(`user_external_accounts.groups` LDAP 문자열 목록만 존재, 로컬 가입자는 그룹 0)해
FR-PM-06(이슈 보안 수준, 그룹 기반 멤버)이 막혀 있음 → 이 인프라가 선행 해소.

- SDD 정본: docs/sdd/12-permissions.md §12.6.1
- plan 추적: docs/plan/product/identity-access.md §4.9
- 범위: 전역 그룹 / 네이티브 먼저(LDAP 동기화 후속) / 백엔드 인프라만(관리 UI/E2E 후속)
- 관리 주체: SYSTEM_ADMIN (FR-PM-08 SystemPermissionResolver 재사용)
- 단계: D1 도메인 / D2 명세 / D3 데이터모델(user_groups, group_memberships, V015) /
  D4 백엔드(Repository + 관리 API + SYSTEM_ADMIN 가드) / D5 백엔드 테스트

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
