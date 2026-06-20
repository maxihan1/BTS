# FR-TT-01 D6/D7 — Worklog 프론트 UI + E2E

> slug: fr-tt-01-d6-d7-worklog-ui-e2e
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (백엔드), 프론트 apps/web
> 생성: 2026-06-20

## Brief

FR-TT-01 — Worklog (추정/실제/잔여 시간)의 **D6(프론트 UI) + D7(E2E)**. 백엔드 D1~D5는 #163으로 머지 완료.

- 원문: "fr-tt-01 d6, d7 진행해줘"
- classify: type=qa 오판정 → **ui 교정**(D6 프론트가 주작업, D7 E2E는 qa-engineer 단일 task). 선례 #155/#158/#164.
- product: docs/plan/product/agile-planning.md §5.1
  - [ ] D6. 프론트 UI — Worklog 입력 폼 + 잔여 시간 자동 계산 (designer → frontend-engineer)
  - [ ] D7. E2E (qa-engineer)
- 백엔드 계약(#163 산출): `POST/GET/PATCH/DELETE /api/v1/issues/{key}/worklogs`, 추정 PATCH(`PATCH /issues/{key}` — originalEstimateSeconds/remainingEstimateSeconds), IssueResponse 3필드(originalEstimateSeconds/timeSpentSeconds/remainingEstimateSeconds, `@JsonInclude(NON_NULL)`), 잔여 자동차감 max(0, remaining−timeSpent) 또는 newRemaining override

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
