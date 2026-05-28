# BLOCKER 1 hot-fix — FR-IS-01 transition 매핑 영구 misalign

> slug: blocker-1-hot-fix-fr-is-01-transition-misalign-iss
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-05-28

## Brief

PR #27 (FR-IS-01 transition wiring) 머지 직전 `/review` adversarial subagent 가 발견한 BLOCKER 2건 중 BLOCKER 1 의 hot-fix slice.

**증상**. `IssueController.kt:191-193` 의 `transitionName = request.toStatusKey` 하드코딩 (예. `"in_progress"`) 이 `software-default.yaml` 의 `transition.name: "Start Work"` 같은 라벨과 매칭 실패 → production `POST /api/v1/issues/{key}/transition` 전건 409.

**pre-existing**. PR #17/#26 도입, PR #27 diff 0 변경. 본 PR 통합 테스트 (`IssueControllerTransitionIntegrationTest`) 가 seed 에서 `transitionName=toStateKey` 우회로 production 함정을 가림.

**옵션 (bts-spec 에서 결정)**.
- (a) controller request body 에 `transitionName` 필드 추가 (client 가 전달)
- (b) `WorkflowEngine.resolveTransition` 매칭 방식을 `name` 에서 `(from, to)` 합성키로 변경
- (c) yaml seed 에 `name: ${toStateKey}` 추가

통합 테스트 우회 seed 해제 필수.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
