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

## 도메인 정리

> Fast-track inline 모드 (PR #21/#22/#23/#24 패턴 따름). grill-with-docs 우회.

### BC

`project-workflow` (transition identity 정책 결정) + `issue-tracking` (어댑터 호출 부위) 양쪽 영향. 주된 BC = `project-workflow` (변경 본질).

### 영향 엔티티

- `WorkflowTransition` (project-workflow 도메인 모델, `domain/WorkflowTransition.kt`)
- `TransitionRequest` (shared-kernel SPI, `shared/workflow/TransitionRequest.kt`) — `transitionName: String` 파라미터
- `IssueController.transition` (issue-tracking 어댑터, `web/IssueController.kt:191-193`) — `WorkflowTransitionPort` 호출 부위

### 본질 — transition identity 모순

`WorkflowTransition.kt` 가 두 개의 identity 를 동시에 선언.

| 필드 | KDoc | 본질 |
|---|---|---|
| L10 §유일성 보장키 | "(fromStateKey, toStateKey, name) 조합이 [Workflow] 내에서 고유" | 3 튜플 |
| L11 §key 필드 | "fromStateKey__toStateKey 합성 — 라우팅/API 호출용" | 2 튜플 |

`WorkflowEngine.resolveTransition` (`engine/WorkflowEngine.kt:163-173`) 은 **3 튜플 매칭** 채택. `key` 필드는 정의만 있고 매칭에 미사용 (현재 코드에서 호출 0). 즉 도메인 모델의 "라우팅용 key" 의도와 엔진 동작이 정렬 안 됨.

`IssueController.transition` 은 client 가 전달한 `toStatusKey` 를 `transitionName` 자리에 넘김 → yaml seed 의 `transition.name` ("Start Work", "Resolve" 등 사람 친화 라벨) 과 매칭 실패 → 전건 409. **본 PR scope 의 BLOCKER 1 의 근본**.

### 결정 본질 — 옵션 a/b/c 의 도메인 의미

- (a) `transitionName` 필드 추가 → identity = (from, to, name) **3 튜플 채택**. §유일성 보장키 KDoc 정합. `WorkflowTransition.key` 필드 KDoc 의 "라우팅" 표현 stale 정정 필요. client (frontend/API consumer) 에 transitionName 노출 필요 (워크플로우 조회 응답).
- (b) `(from, to)` 합성키 매칭 → identity = (from, to) **2 튜플 채택**. §key 필드 KDoc 정합. name 은 사람 친화 표시 라벨로 강등. yaml seed 정책에서 (from, to) 유일성 강제 명세 필요 (현재 명세 안 됨). controller 시그니처 단순화.
- (c) yaml seed 에 `name: ${toStateKey}` 추가 → 본질 회피, 1줄 fix. name 의 사람 친화 라벨 자유도 상실 (yaml UX). 도메인 모델 stale 가속.

### ubiquitous language 영향

- 현재 glossary `전이 (Transition. 상태 → 상태로 가는 액션)` 정의는 identity 명시 안 함.
- 옵션 b 채택 시 "transition key" / "transition label" 분리 후보 (key = 라우팅 식별자, label = 사람 친화 표시).
- 옵션 a 채택 시 "transition name" 이 1급 시민, glossary 갱신 가능.

### 관련 ADR / Learnings

- `docs/adr/2026-05-21-workflow-yaml-vs-db-storage.md` — yaml seed 정책 (transition identity 명시 0)
- `docs/adr/2026-05-26-workflow-transition-port-result-sealed.md` — sealed Result port (identity 결정 0)
- `docs/decisions/2026-05-27-shared-kernel-extraction.md` — TransitionRequest SPI (transitionName 파라미터 보유, identity 결정 0)
- **identity 결정 ADR 부재** — 본 PR 의 ADR 후보 (옵션 a/b 채택 시).

### plan §도메인 정리 요약

- BC. project-workflow (주), issue-tracking (어댑터)
- 새 용어. 옵션에 따라 "transition key" vs "transition name" 분리 가능
- 기존 결정 충돌. 없음 (identity 명시 ADR 부재)
- 관련 ADR. 신규 후보 1건 (옵션 a/b 채택 시) — `docs/adr/2026-05-28-workflow-transition-identity-policy.md`



## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
