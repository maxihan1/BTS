# CONCERN-1 cleanup — Workflow.of() aggregate invariant 2-tuple 정합 정렬

> slug: workflow-aggregate-invariant-2tuple
> type: bugfix
> agent: backend-engineer
> primary_bc: project-workflow
> 생성: 2026-05-28

## Brief

PR #29 (BLOCKER 2 hot-fix) 머지 후 잔존 CONCERN-1 (Important). `Workflow.of()` aggregate factory invariant 5번이 transition uniqueness 를 `(from, to, name)` 3 튜플로 검사 중. ADR `docs/adr/2026-05-28-workflow-transition-identity-policy.md` 결정 (transition identity = `(from, to)` 2 튜플) + yaml seed fail-fast 정책과 불일치.

위협. 다른 진입 경로 (예. `WorkflowRepository.toAggregate()` 의 DB load, FR-WF-02 CRUD, 향후 도메인 호출) 가 같은 `(from, to)` 가 `name` 만 다른 두 transition 을 보유한 채 aggregate 를 통과시킬 수 있음 → policy enforcement holes.

본 PR scope.
1. `Workflow.of()` invariant 5번을 `(from, to)` 2 튜플 기준으로 변경
2. 회귀 가드 단위 테스트 1건 추가 (`name` 만 달라도 invariant 위반으로 reject)
3. 영향 범위 검증. `WorkflowRepository.toAggregate()` / FR-WF-02 CRUD / 기타 진입 경로의 정책 일치 확인 (회귀 0)

비-범위 (out of scope).
- ADR 본문 갱신 (이미 결정 명시)
- 다른 CONCERN (#2 dead code, ktlint cleanup) — 별 PR 위임
- 새 진입 경로 추가

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
