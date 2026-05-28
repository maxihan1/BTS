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

## 도메인 정리

- **BC**. project-workflow
- **영향 Aggregate**. `Workflow` (Aggregate Root)
- **영향 메서드**. `Workflow.of()` companion factory invariant 5번
- **신규 용어**. 없음 (ADR 에서 이미 정의 완료)
- **fast-track 사유**. `type=bugfix` (정책 정합 정정). grill-with-docs 비용 > 효익.

### ADR 인용 (정합 기준)

`docs/adr/2026-05-28-workflow-transition-identity-policy.md` §결정.

> 옵션 (b1) 채택 — `(from, to)` 2 튜플을 전이 identity로 확정.
> `name` 필드는 사람 친화 표시 라벨 (UI 표시 / yaml 가독성). 엔진 매칭에 사용하지 않는다.
> yaml seed 단계에서 같은 워크플로우 내 `(fromStateKey, toStateKey)` 중복을 fail-fast로 차단. `name` 중복은 허용.

### 현재 코드 (Workflow.kt L33, L66-70) vs ADR 의 drift

```kotlin
// L33 KDoc
// 5. [transitions] 내 (fromStateKey, toStateKey, name) 조합 중복이 0건이어야 한다.

// L66-67 검증 로직
val transitionKeys = transitions.map { Triple(it.fromStateKey, it.toStateKey, it.name) }
val duplicateTransitions = transitionKeys.groupBy { it }.filter { it.value.size > 1 }.keys

// L69 오류 메시지
"Workflow '$key': duplicate transition (from, to, name) combinations found — $duplicateTransitions"
```

= ADR `(from, to)` 2 튜플 정책 위반. `name` 만 다른 두 transition (예. "Start Work" + "시작") 이 같은 `(from, to)` 를 가져도 invariant 5번 통과 — 다른 진입 경로 (WorkflowRepository.toAggregate(), 향후 FR-WF-02 CRUD) 가 policy 우회.

### 다른 진입 경로 (Workflow.of() 호출자)

```
backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowRepository.kt
backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/adapter/inbound/WorkflowKeyResolverImpl.kt
```

= 모두 `Workflow.of(...)` companion factory 통과. invariant 정정 시 자동 적용 (회귀 0).

### 기존 결정 충돌

- 없음. ADR `2026-05-28-workflow-transition-identity-policy.md` 와 정합 정렬이 본 PR 본질.

### 관련 ADR

- `docs/adr/2026-05-28-workflow-transition-identity-policy.md` (기준)
- `docs/adr/2026-05-21-workflow-yaml-vs-db-storage.md` (yaml seed 정책 정합)
- `docs/adr/2026-05-26-workflow-transition-port-result-sealed.md` (보완)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
