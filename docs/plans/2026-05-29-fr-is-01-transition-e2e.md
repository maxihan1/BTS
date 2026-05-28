# FR-IS-01 상태전이 E2E

> slug: fr-is-01-transition-e2e
> type: qa
> agent: qa-engineer
> 생성: 2026-05-29

## Brief

이슈 상태 전이(transition) 플로우의 Playwright E2E 테스트 추가.

- 백엔드 전이 wiring은 PR #27(`WorkflowResolver consumer`)/#28(`transition 매핑 misalign hot-fix`)에서 완료됨. 2026-05-29 코드 검증 완료.
- 기존 E2E는 `issue-crud-happy / issue-not-found / issue-auth-guard / issue-ui-regression` 4개뿐 — transition 시나리오 부재.
- 이번 작업은 메모 `issue-transition-backend-gap`가 말한 "백엔드 wiring 수정 후 후속 slice"에 해당. E2E가 전이의 런타임 end-to-end 동작을 최초로 검증.
- classify: type=qa, agent=qa-engineer.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
