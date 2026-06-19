# FR-PL-01 — 일정 필드 (Start/Due/Target Date)

> slug: fr-pl-01-issue-dates
> type: feature
> agent: backend-engineer (D1/D4/D5) + db-engineer (D3) + frontend-engineer (D6) + qa-engineer (D7)
> primary_bc: issue-tracking
> 생성: 2026-06-19

## Brief

FR-PL-01 (agile-planning §6.1). 이슈에 일정 필드 3종(start_date, due_date, target_date) 추가.
- 데이터: `issues.start_date, due_date, target_date` (DATE)
- 백엔드: 이슈 PATCH 엔드포인트 확장
- 프론트: date-fns + 데이트픽커 UI
- 선행: issue-tracking §2.1.1 (FR-IS-01 이슈 CRUD) — 완료됨
- BC 경계: FR은 agile-planning 분류이나 구현은 issue-tracking BC (issues 테이블 + 이슈 PATCH)

classify-task 오판정(auth/security) → feature/backend-engineer override.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
