# FR-IS-05 D7 — 이슈 일괄 작업 E2E + bulk MSW 핸들러 정본

> slug: fr-is-05-d7-e2e-bulk-msw
> type: qa
> agent: qa-engineer
> 생성: 2026-06-02

## Brief

FR-IS-05 D7 — 이슈 일괄 작업(편집/전이) E2E 테스트 추가. 일괄 편집/전이 + 진행률 폴링
(PENDING→RUNNING→COMPLETED) 끝-to-끝 시나리오를 Playwright로 검증하고, mocks/에 bulk
작업 공용 MSW 핸들러 정본을 stateful하게 추가. 백엔드 D1~D5(PR #54/#56), 프론트
D6(PR #58) 이미 머지됨. 이번은 D7 E2E만.

- classify: type=qa, agent=qa-engineer, slug=fr-is-05-d7-e2e-bulk-msw

## 도메인 정리

- **BC**: issue-tracking
- **영향 엔티티**: 신규 없음. 기존 BulkOperation / BulkOperationItem(D1~D5 백엔드 도메인, 이미 머지)을 E2E로 검증만.
- **새 용어**: 없음 (테스트 인프라 작업). 글로서리 "일괄 작업(Bulk Operation)/일괄 작업 항목" 추가는 별도 수동 영역 — Maxi 승인 대기(체크포인트 잔여 #3). 본 PR 범위 밖.
- **기존 결정 충돌**: 없음. 비동기 아키텍처 결정은 [docs/adr/2026-06-02-bulk-operation-async-architecture.md] 에 이미 확정. D7은 그 동작(접수 202 → 폴링 → 종단)을 E2E로 재현·검증.
- **관련 ADR**: docs/adr/2026-06-02-bulk-operation-async-architecture.md (기존, 신규 발행 없음)
- **grill-with-docs 스킵 사유**: D1~D6 머지로 도메인·계약 확정 상태. 새 개념 0건 → 무거운 대화형 grill 불필요(메모리 bts-spec-office-hours-mismatch). 직접 점검으로 충돌 0 확인.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
