# FR-PM-06 PR-B — 이슈 보안 수준 판정 결선

> slug: fr-pm-06-pr-b-security-decision
> type: api
> agent: backend-engineer (+ security-engineer 검토: 권한 판정/resolver)
> primary_bc: issue-tracking (+ identity-access resolver 확장 — cross-BC 포트)
> 생성: 2026-06-06
> 선행: PR-A(#86, 관리 인프라) 머지 완료. 최신 main(FR-CM-04 #89) 기준.

## Brief

FR-PM-06(이슈 보안 수준)의 후속 PR-B. PR-A에서 Jira Cloud 방식 스킴→등급→멤버(5타입) **관리 인프라**까지 머지됐고, 이번 PR-B는 실제 **이슈 차단 판정 결선**을 구현한다.

범위:
- `issues.security_level_id` 컬럼 추가 (issue-tracking 마이그레이션 + init_codegen.sql 미러 필수 — jOOQ 상수 생성, jooq-init-codegen-mirror 교훈).
- 이슈 생성/편집 시 등급 지정 API (SET_ISSUE_SECURITY 가드). 적용 스킴 미소속 등급이면 422.
- cross-BC `IssueSecurityLookup` 포트 (identity-access가 issues의 security_level_id/reporter_id/assignee_id read, ProjectDirectory 패턴).
- `IdentityAccessIssuePermissionResolver` 판정 확장: VIEW 매트릭스 통과 AND 등급 멤버(5타입) 충족, 미통과 시 404 (FR-PM-05 assertViewIssueOrNotFound 일관).
- listIssues 목록 필터 (등급 멤버 아닌 이슈 제외, N+1 회피).
- prod Testcontainers 통합으로 판정 ground-truth (S10~S13, non-prod AlwaysAllow 마스킹 주의).

(선택 후속) D6/D7 — 이슈 생성/편집 등급 선택 UI + E2E.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
