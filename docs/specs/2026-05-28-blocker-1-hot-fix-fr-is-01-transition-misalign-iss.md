# BLOCKER 1 hot-fix — FR-IS-01 transition 매핑 영구 misalign

> slug: blocker-1-hot-fix-fr-is-01-transition-misalign-iss
> type: api / agent: backend-engineer
> primary_bc: project-workflow (정책 결정) + issue-tracking (어댑터)
> 생성: 2026-05-28
> 도메인 분석. `docs/plans/2026-05-28-<slug>.md` §도메인 정리

## 결정 (Maxi 게이트1 확정 2026-05-28)

**옵션 (b) — (from, to) 2 튜플 채택**.

`WorkflowEngine.resolveTransition` 의 매칭 조건을 `(fromStateKey, toStateKey, name)` 3 튜플 → `(fromStateKey, toStateKey)` 2 튜플로 변경. `WorkflowTransition.name` 은 사람 친화 표시 라벨로 강등. `TransitionRequest.transitionName` 파라미터 제거. `WorkflowTransition.kt` 의 KDoc `(fromStateKey, toStateKey, name)` 유일성 보장키 명세를 `(fromStateKey, toStateKey)` 로 정정.

신규 ADR. `docs/adr/2026-05-28-workflow-transition-identity-policy.md`.

## 사용자 시나리오 (Given-When-Then)

### S1 — happy path (옵션 b 적용 후)

```
Given 표준 워크플로우 software-default 가 시드된 프로젝트 ATLAS
  And 이슈 ATLAS-1 의 currentStateKey = "open"
When POST /api/v1/issues/ATLAS-1/transition
     body = {"toStatusKey": "in_progress", "expectedVersion": 1}
Then 200 OK
  And response.data.currentStateKey == "in_progress"
  And response.data.version == 2
  And IssueTransitioned 이벤트가 pgmq 에 발행됨
```

### S2 — production 시나리오 회귀 (BLOCKER 가 fix 됐는지 검증)

```
Given software-default.yaml 의 transition { from: open, to: in_progress, name: "Start Work" }
  And IssueControllerTransitionIntegrationTest 가 우회 seed 제거 후 정상 software-default 시드 사용
When POST /api/v1/issues/{key}/transition body = {"toStatusKey": "in_progress", ...}
Then 200 OK (사람 친화 라벨 "Start Work" 와 클라이언트 입력 "in_progress" 라벨 mismatch 가 더 이상 발생 안 함)
```

### S3 — invalid transition (정의 안 된 (from, to))

```
Given software-default 시드, 이슈 currentStateKey = "open"
When body = {"toStatusKey": "in_review", "expectedVersion": 1}
     (software-default 에 from=open, to=in_review transition 미정의)
Then 409 IssueTransitionNotAllowed (RFC 7807 ProblemDetail, errorCode = "ISSUE_TRANSITION_NOT_ALLOWED")
```

### S4 — version conflict (낙관락)

```
Given 이슈 ATLAS-1, version = 2 (DB)
When body = {"toStatusKey": "done", "expectedVersion": 1}
Then 409 IssueVersionConflict (errorCode = "ISSUE_VERSION_CONFLICT")
```

### S5 — yaml seed 검증 — 같은 (from, to) 중복

```
Given yaml seed 단계 (ApplicationReadyEvent)
  And 가상의 yaml 에 { from: open, to: in_progress, name: A } 와 { from: open, to: in_progress, name: B } 동시 존재
When YamlSeedService 가 해당 yaml 적재 시도
Then fail-fast — IllegalStateException("Workflow '<key>' has duplicate (from, to)=(open,in_progress)") 로 부팅 차단
```

## 기능 요구사항 (FR)

- **FR-1**. `WorkflowEngine.resolveTransition` 은 `(fromStateKey, toStateKey)` 만으로 transition 매칭. 매칭 실패 시 `WorkflowNotFoundException` 발생 (기존 메시지 형식 유지하되 transitionName 부분 제거).
- **FR-2**. `shared-kernel` `TransitionRequest.transitionName` 필드 제거. Konform 검증 규칙에서도 `transitionName { minLength(1) }` 제거.
- **FR-3**. issue-tracking `AppTransitionIssueRequest.transitionName` 필드 제거. `IssueApplicationService.transitionIssue` 가 `TransitionRequest` 생성 시 transitionName 안 넘김.
- **FR-4**. `IssueController.transition` 의 `AppTransitionIssueRequest` 생성에서 `transitionName = request.toStatusKey` 라인 제거 (BLOCKER 본질 부위).
- **FR-5**. `WorkflowTransition.kt` KDoc 정정. L10 §유일성 보장키 = `(fromStateKey, toStateKey)`. `name` 의 역할은 "사람 친화 표시 라벨" 로 재정의. `key` 필드의 "라우팅/API 호출용" 표현 보존 (이번 결정으로 진짜 라우팅 키가 됨).
- **FR-6**. `YamlSeedService` 의 yaml 적재 검증에 같은 워크플로우 내 `(from, to)` 유일성 검증 추가. 위반 시 부팅 fail-fast (ADR `yaml-vs-db-storage` 의 fail-fast 정신과 일치).
- **FR-7**. `IssueControllerTransitionIntegrationTest` 의 우회 seed (`transitionName=toStateKey`) 제거 + 표준 `software-default.yaml` 시드로 교체. 통합 테스트가 production 시나리오를 정확히 반영하도록 한다.
- **FR-8**. 신규 ADR `docs/adr/2026-05-28-workflow-transition-identity-policy.md` 작성. 결정 근거 + 옵션 a/c 기각 사유 + 향후 "같은 (from, to) 에 여러 transition" 필요 시 별 ADR 탈출구 명시.

## 비기능 요구사항 (NFR)

- **NFR-1 (성능)**. transition 매칭은 워크플로우 메모리 캐시 `workflow.transitions.find { ... }` 라 O(N) 선형 탐색 — N=10 미만 (표준 워크플로우 transition 수). 옵션 b 채택으로 비교 조건 1개 줄어들어 미미한 개선.
- **NFR-2 (기존 회귀)**. 변경 후 `:modules:issue-tracking:test :modules:project-workflow:test :modules:shared-kernel:test` 3 모듈 전체 BUILD SUCCESSFUL 유지. transitionName 제거로 영향 받는 모든 단위/통합 테스트 정합 갱신.
- **NFR-3 (yaml 호환성)**. 표준 4종 yaml (`software-default`, `bug-tracking`, `simple`, `kanban-basic`) 의 transition.name 필드 보존 (사람 친화 라벨로 유지). yaml 스키마 변경 0.
- **NFR-4 (API 하위 호환)**. REST 외부 contract (`POST /api/v1/issues/{key}/transition` 의 request body) 변경 0 — `TransitionIssueRequest { toStatusKey, expectedVersion }` 그대로. frontend 영향 0.

## API 인터페이스 (REST)

**변경 없음**. 외부 contract 보존.

```
POST /api/v1/issues/{key}/transition
Content-Type: application/json
Body: { "toStatusKey": "in_progress", "expectedVersion": 1 }
```

내부 application/shared-kernel 계층의 `transitionName` 만 제거.

## 데이터 모델 변경

**없음**. yaml 스키마 변경 0, DB 마이그레이션 0.

## 엣지 케이스

- **EC-1**. `WorkflowEngine.resolveTransition` 의 예외 메시지. 기존 `"${workflowKey}::${transitionName}(${from}→${to})"` → `"${workflowKey}::${from}→${to}"` (transitionName 부분 제거). 메시지 로깅 회귀 영향 0 (production 에러 메시지 패턴 사용처 0 추정 — 검증 필요).
- **EC-2**. shared-kernel `TransitionRequest` 의 Konform `transitionRequestValidation` 에서 `transitionName { minLength(1) }` 라인 제거. 다른 BC (FR-WF-02 의 WorkflowEngine 호출자) 에 영향 0 확인 — `grep -RIn "TransitionRequest(" backend/modules`.
- **EC-3**. yaml 적재 시 같은 워크플로우 내 같은 `(from, to)` 가 다른 `name` 으로 중복 정의된 경우 — fail-fast (FR-6). 표준 4종 yaml 모두 사전 검증 통과 (수동 검사 결과 0 충돌).
- **EC-4**. 신규 detekt / ktlint issue. `WorkflowTransition.name` 필드 자체는 유지 (사람 친화 라벨로 strict 1 사용처 — yaml 응답 DTO `TransitionResponseDto`). detekt UnusedPrivateProperty 등 발현 0.
- **EC-5**. WorkflowController.kt 의 transition API endpoint (있다면) — 검토 필요. 만약 transitionName 받는 endpoint 가 있다면 deprecated 또는 무시.
- **EC-6**. FR-WF-02 (커스텀 워크플로우) 시 (from, to) 유일성 강제는 이미 FR-6 으로 확립. 미래 호환.

## 제약 조건

- 본 PR scope = BLOCKER 1 hot-fix slice. BLOCKER 2 (V004 Flyway path) 는 별 PR.
- BC 격리 유지. issue-tracking → project-workflow 직접 import 0 유지. shared-kernel 의 `TransitionRequest` SPI 만 사용.
- TDD red→green→refactor 정통. 본 PR 의 각 task 는 RED commit (`test:`) 후 GREEN commit (`feat:` / `refactor:`) 순서로 분리.
- 절대 규칙 19개 위반 0. learnings.md 회귀 위험 0.

## 측정 가능한 완료 기준

- **C-1**. `WorkflowEngine.resolveTransition` 매칭 조건 코드 grep — `transitionName` / `req.transitionName` / `it.name` 모두 0 hit. (단 `WorkflowTransition.name` 필드 자체는 응답 DTO 에서 사용 유지.)
- **C-2**. shared-kernel `TransitionRequest.kt` — `transitionName` 필드 0 grep.
- **C-3**. issue-tracking — `AppTransitionIssueRequest.transitionName` 0 grep + `IssueController.kt:191-195` 의 `transitionName = request.toStatusKey` 라인 제거.
- **C-4**. `IssueControllerTransitionIntegrationTest` 우회 seed (`transitionName=toStateKey`) 제거 + 표준 software-default.yaml 시드 사용 + 모든 시나리오 PASS.
- **C-5**. 3 모듈 (`:modules:issue-tracking :modules:project-workflow :modules:shared-kernel`) `./gradlew clean test ktlintCheck detekt` 전부 BUILD SUCCESSFUL.
- **C-6**. ADR `docs/adr/2026-05-28-workflow-transition-identity-policy.md` 작성 완료 + 결정 근거 + 기각 사유 + 향후 탈출구 명시.
- **C-7**. `WorkflowTransition.kt` KDoc — L10 §유일성 보장키 = `(fromStateKey, toStateKey)` 로 정정 + `name` 의 역할 재정의 코멘트.
- **C-8**. `YamlSeedService` 의 yaml 적재 검증에 `(from, to)` 유일성 검증 추가 + 단위 테스트 RED→GREEN.
- **C-9**. PR diff 가 다음 모듈만 손댐 — `backend/modules/shared-kernel/`, `backend/modules/project-workflow/`, `backend/modules/issue-tracking/`, `docs/adr/`, `docs/plans/`, `docs/specs/`.

## Brainstorming Check (sanity check inline)

PR #21/#22/#23/#24 fast-track 패턴 따라 inline 진행. brainstorming 의 핵심 — 누락/모호/엣지 케이스 미커버 점검.

### 점검 결과

- **누락 가능 항목 검토 ✅**.
  - `WorkflowController.kt` 의 transition endpoint (있다면) — EC-5 에 명시.
  - FR-WF-02 미래 호환 — EC-6 에 명시 + FR-6 의 (from, to) 유일성 강제로 차단.
  - 다른 BC (예: automation) 의 WorkflowEngine 호출 — EC-2 에 grep 검증 명시.
- **모호 표현 검토 ✅**.
  - "사람 친화 표시 라벨" 의 구체 사용처 — yaml 응답 DTO `TransitionResponseDto` (EC-4) 로 박힘.
  - "ADR 신규 후보" → "ADR 신규 필수 (FR-8)" 로 명확화.
- **엣지 케이스 추가 발견 ✅**.
  - EC-1 (예외 메시지 로깅 회귀) — 추가됨.
  - EC-3 (yaml 중복 fail-fast) — 추가됨.
  - EC-5 (WorkflowController 영향) — 추가됨.
- **가정 누락 ✅**.
  - "외부 contract 변경 0" 가정 — REST DTO `TransitionIssueRequest` 가 이미 `toStatusKey, expectedVersion` 만 받는 것 확인 (`TransitionIssueRequest.kt` Read 완료). 가정 유효.
  - "표준 4종 yaml (from, to) 유일성" 가정 — 수동 검사로 사전 검증 완료. 가정 유효.

### 결론

✅ 통과 — gap 0건. (option a 채택 시 frontend transitionName 라우팅 책임 검토가 추가 필요했으나, option b 채택으로 면제.)

