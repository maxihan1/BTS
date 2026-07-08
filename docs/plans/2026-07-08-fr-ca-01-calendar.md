# FR-CA-01 개인 캘린더 (할당/마감일/Worklog 통합)

> slug: fr-ca-01-calendar
> type: feature
> agent: backend-engineer (+ frontend-engineer for UI, db-engineer for query, qa-engineer for E2E)
> 생성: 2026-07-08

## Brief

FR-CA-01 개인 캘린더 — 로그인 사용자의 (1) 할당 이슈, (2) 이슈 마감일(due date), (3) Worklog 일정을
월/주 캘린더로 통합 조회. `GET /api/v1/users/me/calendar?from=&to=`.
별도 저장 테이블 없이 조회(read-only)만. personalization BC(논리) / identity-access 모듈(물리).
cross-BC 조회(issue-tracking, agile-planning)가 핵심 설계 포인트.

- 우선순위: 높음
- 선행: issue-tracking §6.1 FR-PL-01 (계획/날짜, 완료됨)
- product: docs/plan/product/personalization.md §5.1
- D1~D7 (도메인 CalendarEvent / 명세 / 데이터모델(조회만) / 백엔드 API / 백엔드 테스트 / 월·주 UI / E2E)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
