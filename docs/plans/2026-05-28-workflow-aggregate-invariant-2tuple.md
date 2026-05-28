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

## 스펙

- **fast-track 사유**. `type=bugfix` (정책 정합 정정). office-hours / brainstorming 비용 > 효익. PR #28/#29 inline 패턴 일관.

### 사용자 시나리오 (Given-When-Then)

#### 시나리오 1 — 정상 transitions 통과 (회귀 0)

- Given. yaml 표준 4종 (`software-default`, `bug-tracking`, `simple`, `kanban-basic`) 의 transition 정의 (`(from, to)` 모두 유일)
- When. `Workflow.of(...)` 호출
- Then. invariant 5번 통과 → Workflow 인스턴스 정상 생성

#### 시나리오 2 — 같은 (from, to) 가 name 만 다른 두 transition (회귀 가드 핵심)

- Given. transitions = `[ T1(from=open, to=in_progress, name="Start Work"), T2(from=open, to=in_progress, name="시작") ]`
- When. `Workflow.of(...)` 호출
- Then. invariant 5번 위반 → `IllegalArgumentException` throw + 메시지에 duplicate `(from, to)` 표현 포함

#### 시나리오 3 — 같은 (from, to) 동일 name 중복 (회귀 0)

- Given. transitions = `[ T1(from=open, to=in_progress, name="Start"), T2(from=open, to=in_progress, name="Start") ]`
- When. `Workflow.of(...)` 호출
- Then. invariant 5번 위반 → throw (변경 전후 동일 동작)

#### 시나리오 4 — 다른 (from, to) 가 같은 name (회귀 0)

- Given. transitions = `[ T1(from=open, to=in_progress, name="Move"), T2(from=in_progress, to=done, name="Move") ]`
- When. `Workflow.of(...)` 호출
- Then. invariant 5번 통과 (name 은 사람 친화 라벨, identity 아님)

### 기능 요구사항 (FR)

- **FR-1**. `Workflow.of()` 의 invariant 5번이 `(fromStateKey, toStateKey)` 2 튜플 기준 중복 검증
- **FR-2**. invariant 5번 위반 오류 메시지가 duplicate `(from, to)` 표현 + workflow key 포함
- **FR-3**. KDoc L33 의 invariant 5번 명세가 코드 동작과 일치 (`(fromStateKey, toStateKey)` 명시)
- **FR-4**. 회귀 가드 단위 테스트가 시나리오 1~4 모두 검증

### 비기능 요구사항 (NFR)

- **NFR-1**. 변경 후 기존 테스트 전체 (단위 + 통합) 회귀 0
- **NFR-2**. 변경 영역 = `Workflow.kt` 단일 파일 + 회귀 가드 테스트 1 파일

### API 인터페이스 (REST)

- 변경 없음 (내부 도메인 invariant 정정).

### 데이터 모델 변경

- 변경 없음 (Workflow.kt 의 검증 로직만 정정).

### 엣지 케이스

- **EC-1**. transitions 빈 리스트 → invariant 5번 무관 통과 (states 검증만 적용)
- **EC-2**. 단일 transition → 중복 검증 무관 통과
- **EC-3**. 다른 workflow key 의 같은 `(from, to)` → invariant 5번은 workflow 내부만 검증 (집계 영역 한정)

### 제약 조건

- **C-1**. ADR `docs/adr/2026-05-28-workflow-transition-identity-policy.md` 의 §결정 (`(from, to)` 2 튜플 채택) 과 정합
- **C-2**. WorkflowRepository.toAggregate() 와 WorkflowKeyResolverImpl 의 호출 흐름 회귀 0 (모두 `Workflow.of(...)` 경로 통과 → 자동 적용)

### 측정 가능한 완료 기준

1. `Workflow.kt` 의 L33 KDoc + L66 `Triple` → `Pair` + L69 오류 메시지 모두 `(fromStateKey, toStateKey)` 표현으로 정렬
2. 신규 회귀 가드 단위 테스트 1 파일 (`WorkflowInvariantTest.kt`) 시나리오 1~4 모두 GREEN
3. `./gradlew :modules:project-workflow:test` 전체 통과 (회귀 0)
4. `./gradlew :modules:issue-tracking:test` 전체 통과 (cross-BC test 무영향)

## Brainstorming Check

✅ 통과 (fast-track inline, 1회). office-hours / brainstorming sub-agent 호출 없음 — PR #28/#29 일관 패턴.

- ✅ scope 명확 (Workflow.of() 단일 메서드)
- ✅ 회귀 가드 핵심 시나리오 (시나리오 2) 명시
- ✅ ADR 정합 기준 명시
- ✅ 다른 진입 경로 (toAggregate, FR-WF-02 CRUD) 자동 적용 확인
- ⚠️ 잠재 우려. yaml seed 단의 fail-fast 가 이미 적용 중인지 plan 단계에서 grep 확인 필요 (이미 적용되어 있으면 본 PR 와 정합. 미적용이면 별 PR 위임 또는 scope 확장 판단).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
