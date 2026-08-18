<!-- ADR: 워크플로우 전이 identity 정책 — (from, to) 2 튜플 채택 -->

# ADR — 워크플로우 전이 identity 정책: (from, to) 2 튜플 채택

**일자**. 2026-05-28
**상태**. ⚠️ **대체됨 (Superseded, 2026-08-18)** — `docs/adr/2026-08-18-workflow-transition-id-identity.md`
**관련 FR**. FR-IS-01 BLOCKER 1 hot-fix (본 PR #28)

> **읽기 전 주의.** 이 ADR 의 결정(전이 identity = `(from, to)` 2튜플)은 **더 이상 유효하지 않다.**
> 아래 §대안 채택 조건이 예비한 탈출구가 FR-WF-05 에서 발동해, identity 가 `workflow_transitions.id`
> (UUID)로 옮겨졌고 `(from, to)` 유일성 제약도 해제됐다. 같은 상태쌍에 이름이 다른 전환을 여럿 둘 수
> 있고, `kind` 로 전역 전환(GLOBAL)·최초 전환(INITIAL)을 표현한다.
>
> **다만 이 ADR 의 다음 조항은 살아 있다** — 「`name` 은 사람 친화 표시 라벨이며 엔진 매칭에 쓰지
> 않는다」. 새 ADR 은 매칭 기준을 `name` 이 아니라 `id` 로 두어 이 분리를 더 강하게 지킨다.
>
> 이 문서는 **당시 판단의 근거를 남기기 위해** 보존한다. 현행 계약은 새 ADR 을 본다.
**관련 PR**. #28
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

PR #27 (FR-IS-01 transition wiring) 머지 시점에 BLOCKER 1이 발견됐다.

`WorkflowTransition.kt` 도메인 모델이 두 개의 identity를 동시에 선언하는 모순을 포함하고 있었다.

- L10 `@property name` KDoc — "(fromStateKey, toStateKey, name) 조합이 [Workflow] 내에서 고유해야 한다" → **3 튜플 선언**.
- L11 `@property key` KDoc — "[fromStateKey]__[toStateKey] 합성 … 라우팅/API 호출용" → **2 튜플 사용**.

이 모순이 런타임 전건 409로 이어졌다.

- `WorkflowEngine.resolveTransition`이 3 튜플 매칭(`from + to + name`)을 채택.
- `IssueController`가 `transitionName=toStatusKey`를 하드코딩 — `name` 필드와 `toStateKey`가 다른 값이면 전이 미발견.
- 결과. production 전건 409 (Conflict — 전이 미발견).

또한 `WorkflowController`에 `transitionName` 기반 endpoint가 존재하나 frontend caller가 0이었다 — dead code.

## 후보 비교

| 항목 | 옵션 (a) 3 튜플 채택 | 옵션 (b) 2 튜플 채택 ✓ | 옵션 (c) yaml name 강제 |
|---|---|---|---|
| API contract 변경 | 확장 (transitionName 필수화) | 단순화 (toStatusKey만) | 없음 |
| yaml UX 자유도 | 손상 (name 매칭 강제) | 보존 | 영구 상실 |
| 도메인 모델 정합 | key KDoc stale 정정 필요 | 완전 일치 | 불일치 잔존 |
| 본질 해결 | 부분 (모순 잔존) | 완전 | 회피 |
| frontend 영향 | 높음 | 낮음 (0) | 낮음 |

## 결정

**옵션 (b1) 채택 — `(from, to)` 2 튜플을 전이 identity로 확정.**

### Source of truth

`WorkflowTransition.key` (`fromStateKey__toStateKey` 합성) 가 전이 1급 식별자.

### name 필드 역할 재정의

`name` 필드는 사람 친화 표시 라벨 (UI 표시 / yaml 가독성) 이다. 엔진 매칭에 사용하지 않는다. yaml 작성자가 자유롭게 지정할 수 있다.

### yaml seed 유일성 검증

yaml 파싱 시 같은 워크플로우 내 `(fromStateKey, toStateKey)` 중복을 fail-fast로 차단한다. `name` 중복은 허용 (표시용).

### dead code 제거

`WorkflowController`의 `transitionName` 기반 endpoint — frontend caller 0 확인 후 제거. `IssueController` transition body는 `{toStatusKey, expectedVersion}` 2 필드로 충분.

## 근거

1. **도메인 모델 자체가 2 튜플을 지시** — `key` 필드 KDoc "라우팅용 합성 = from__to"가 원래부터 2 튜플 의도를 담고 있었다. 3 튜플 선언(`name` KDoc)이 나중에 잘못 추가된 것이다.
2. **표준 4종 yaml 사전 검증 완료** — `software-default`, `bug-tracking`, `simple`, `kanban-basic` 모두 같은 워크플로우 내 `(from, to)` 유일성 만족. 변경 영향 0.
3. **REST 외부 contract 단순화** — client가 `transitionName`을 알 필요 없다. `toStatusKey`만으로 전이 lookup이 가능하다.
4. **yaml UX 자유도 보존** — `name`을 매칭에 사용하지 않으므로 "Start Work", "시작", "begin" 어느 표현도 허용된다. FR-WF-02 커스텀 워크플로우 도입 시 사용자 UX에 제약이 없다.
5. **frontend dead code 정리 영향 0** — `planTransition()` caller 0 확인. 제거 시 production 영향 없음.

## 기각 사유

**옵션 (a) — 3 튜플 채택 기각.**

- API contract 확장으로 frontend가 `transitionName`을 알아야 한다 — coupling 증가.
- `WorkflowTransition.key` 필드 KDoc "라우팅용" 표현이 stale로 남는다 — 도메인 모델 모순 잔존.
- yaml의 `name` 값이 API 라우팅에 영향을 미치게 된다 — 사람 친화 라벨과 라우팅 키가 혼합되는 설계 냄새.

**옵션 (c) — yaml name 강제 기각.**

- `name=${toStateKey}` 강제는 yaml의 사람 친화 라벨 자유도를 영구 상실시킨다.
- FR-WF-02 커스텀 워크플로우 도입 시 사용자가 전이 이름을 자유롭게 지정할 수 없다.
- 모순의 본질을 해결하지 않고 회피한다 — 도메인 모델 stale 잔존.

## 영향

### 긍정

- (i) 도메인 모델 정합 — `WorkflowTransition.key` KDoc과 `WorkflowEngine` 매칭 로직이 일치.
- (ii) REST API 단순화 — `IssueController` transition endpoint body 변경 없음 (`toStatusKey` 기존 사용).
- (iii) `name` 사람 친화 라벨 자유도 보존 — yaml 작성자 UX 제약 없음.
- (iv) yaml seed fail-fast `(from, to)` 유일성 검증 — 미래 중복 정의 회귀 차단.

### 부정 / 위험

- (i) **같은 `(from, to)`에 여러 전이 정의 영구 차단** — 예. "Cancel"과 "Reject"가 같은 `(from, to)`를 가지는 경우 불가. 탈출구는 아래 §대안 채택 조건 참조.
- (ii) `WorkflowController` `transitionName` endpoint 제거 — 향후 역방향 lookup 필요 시 재구현 필요.

## 대안 채택 조건 (향후 탈출구)

- 같은 `(from, to)`에 여러 전이가 필요한 비즈니스 시나리오 출현 시 → 새 ADR 발행 + transition identity를 `(from, to, label)` 또는 `(from, to, guard)` 분기 메커니즘으로 확장.
- FR-WF-02 커스텀 워크플로우 CRUD 도입 시 사용자 정의 워크플로우의 `(from, to)` 유일성 요구를 사용자 UX 가이드에 명시.

## 관련

- `docs/adr/2026-05-21-workflow-yaml-vs-db-storage.md` — yaml seed 정책 (본 ADR의 fail-fast 정신과 일치).
- `docs/adr/2026-05-26-workflow-transition-port-result-sealed.md` — sealed Result port (identity 결정과 무관, 본 ADR 보완).
- `docs/decisions/2026-05-27-shared-kernel-extraction.md` — TransitionRequest SPI.
- `docs/sdd/07-workflow-engine.md` — 워크플로우 엔진 전체 설계.
- PR #27 (`079dfdb`) — BLOCKER 1 발견 시점. PR #28 (본 PR) — fix.
