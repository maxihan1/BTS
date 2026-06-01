# FR-PM-01 프로젝트 행정 (멤버 관리) — D6 프론트 UI + D7 E2E

> slug: fr-pm-01-members-ui
> type: ui (classifier qa 오분류 교정)
> agent: frontend-engineer (+ qa-engineer for E2E)
> primary BC: identity-access (프론트는 apps/web)
> 생성: 2026-06-01

## Brief

FR-PM-01 남은 작업. 백엔드 D1~D5는 PR #48 완료(ProjectMembership/ProjectRole + CRUD API).
이번 PR 범위는 **D6(프론트 UI: 프로젝트 설정 → 멤버 관리 화면) + D7(E2E)**.

백엔드 API. `/api/v1/projects/{projectId}/members` — POST(추가)/GET(목록)/PATCH(역할)/DELETE(제거).
규칙. 부트스트랩(멤버0명 첫멤버 자동 ADMIN, JWT+자기자신), CRUD PAT 허용, 비멤버 404 존재숨김, 마지막 admin 보호.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
