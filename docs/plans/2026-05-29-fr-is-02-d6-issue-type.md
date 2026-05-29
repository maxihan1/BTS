# FR-IS-02 D6 — 이슈 타입 표시 + 변경

> slug: fr-is-02-d6-issue-type
> type: feature (backend + frontend)
> agent: backend-engineer (primary) + frontend-engineer
> 생성: 2026-05-29

## Brief

FR-IS-02 D6 — 이슈 상세 화면에 이슈 타입 표시 + 변경 UI.

- **backend (issue-tracking)** — 개별 이슈의 타입(typeId) 변경 지원. 현재 PATCH `/issues/{key}` 는 summary + expectedVersion 만 받음 → typeId 변경 경로 신규. 계층/유효성 검증 동반.
- **frontend** — 이슈 상세 화면(`IssueMetaPanel.tsx` / `issues.$key.tsx`)에 타입별 아이콘 표시 + 타입 셀렉터로 변경.

backend `IssueResponse` 는 이미 `typeId: Long` / `typeKey: String` / `typeName: String` (non-null) 노출 확인 완료(grep 검증).
backend PATCH 는 typeId 변경 미지원 확인 완료 → 본 PR 에서 추가.

Maxi 결정: D6 스코프 = 표시 + 변경 (backend 동반). 경량 경로 (office-hours/autoplan skip).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
