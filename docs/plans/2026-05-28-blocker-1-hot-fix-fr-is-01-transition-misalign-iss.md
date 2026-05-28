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



## 스펙

전체 스펙. [docs/specs/2026-05-28-blocker-1-hot-fix-fr-is-01-transition-misalign-iss.md](../specs/2026-05-28-blocker-1-hot-fix-fr-is-01-transition-misalign-iss.md)

### 결정 (Maxi 게이트1 확정 2026-05-28)

**옵션 (b) — (from, to) 2 튜플 채택**. `WorkflowEngine.resolveTransition` 매칭 = `(fromStateKey, toStateKey)`. `WorkflowTransition.name` = 사람 친화 표시 라벨로 강등. `TransitionRequest.transitionName` 필드 제거. `WorkflowTransition.kt` KDoc 정정.

### 핵심 시나리오 3줄 요약

- happy path. `POST /api/v1/issues/{key}/transition body={toStatusKey, expectedVersion}` 200 OK + IssueTransitioned 이벤트 발행 (외부 contract 변경 0).
- production 회귀 검증. `IssueControllerTransitionIntegrationTest` 우회 seed 제거 + 표준 software-default.yaml 시드 사용 → 사람 친화 라벨 ("Start Work") 과 클라이언트 입력 ("in_progress") mismatch 더 이상 발생 안 함.
- yaml fail-fast. 같은 워크플로우 내 같은 `(from, to)` 중복 시 부팅 차단 (FR-6).

### 변경 모듈

- `backend/modules/shared-kernel/` — `TransitionRequest.kt` (필드 + Konform 제거)
- `backend/modules/project-workflow/` — `WorkflowEngine.kt` (매칭 단순화), `WorkflowTransition.kt` (KDoc 정정), `YamlSeedService.kt` (검증 추가)
- `backend/modules/issue-tracking/` — `IssueController.kt`, `IssueApplicationRequests.kt`, `IssueApplicationService.kt`, `IssueControllerTransitionIntegrationTest.kt` (우회 seed 제거)
- `docs/adr/` — 신규 ADR 1건
- `docs/plans/`, `docs/specs/` — 본 PR 산출물

### ADR 신규

`docs/adr/2026-05-28-workflow-transition-identity-policy.md`.

## Brainstorming Check

✅ 통과 — gap 0건 (1 iteration, fast-track inline 모드).

상세. `docs/specs/2026-05-28-<slug>.md §Brainstorming Check`.

## Plan

> Fast-track inline 모드 (PR #21/#22/#23/#24 패턴). writing-plans 스킬 우회.
> TDD red→green→refactor 정통. 각 task 메타 블록 (agent / files / depends-on) bts-impl wave 계산 입력.

### Task 1. WorkflowEngine.resolveTransition (from, to) 매칭 + 예외 메시지 정리

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/WorkflowEngine.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/engine/WorkflowEngineUnitTest.kt`]
- depends-on. `[]`

**RED**.
- 파일. `WorkflowEngineUnitTest.kt`
- 테스트 (Kotlin). yaml seed 에 `{ from: open, to: in_progress, name: "Start Work" }` 가 정의된 워크플로우에서, `TransitionRequest` 가 `(fromStateKey="open", toStateKey="in_progress")` 만 전달했을 때 (transitionName 누락/임의값/공백 모두) 정확한 transition 매칭. 현재 코드에서 fail (name 매칭 조건 위반).
- 실패 메시지 예상. `WorkflowNotFoundException("DEFAULT::?(open→in_progress)")` 또는 매칭 실패.

**GREEN**.
- 파일. `WorkflowEngine.kt:163-173`
- `resolveTransition` 의 `it.name == req.transitionName` 조건 삭제. 매칭 = `it.fromStateKey == req.fromStateKey && it.toStateKey == req.toStateKey`.
- 예외 메시지에서 `${req.transitionName}` 토큰 제거 — `"${req.workflowKey}::${req.fromStateKey}→${req.toStateKey}"`.

**REFACTOR**.
- `resolveTransition` 함수 KDoc 추가 (1줄, "transition identity = (from, to) — ADR 2026-05-28 참조").

**검증**. `./gradlew :modules:project-workflow:test --tests WorkflowEngineUnitTest`.

---

### Task 2. YamlSeedService (from, to) 유일성 검증 + fail-fast

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedServiceTest.kt`]
- depends-on. `[]`

**RED**.
- 파일. `YamlSeedServiceTest.kt`
- 테스트. 같은 워크플로우 안에 `{ from: open, to: done, name: A }` + `{ from: open, to: done, name: B }` 중복 정의된 fixture yaml 로 `loadAndSeed()` 호출 시 `IllegalStateException` 발생 + 메시지에 `"duplicate (from, to)=(open,done)"` 포함 기대. 현재 코드에서 검증 누락이라 fail (예외 미발생).

**GREEN**.
- 파일. `YamlSeedService.kt` (validation 단계)
- yaml 적재 시 `transitions` 의 `(from, to)` pair 가 모두 distinct 인지 검증. `groupBy { it.from to it.to }.filter { it.value.size > 1 }` 결과 0 이 아니면 `IllegalStateException("Workflow '${workflowKey}' has duplicate (from, to)=${dup}")` throw.

**REFACTOR**.
- 검증 로직을 private fun extractor (`validateTransitionUniqueness`) 로 추출.

**검증**. `./gradlew :modules:project-workflow:test --tests YamlSeedServiceTest`.

---

### Task 3. shared-kernel TransitionRequest.transitionName 제거 + 모든 caller 갱신 (project-workflow web/dto 포함)

**메타**.
- agent. `backend-engineer`
- files. [
    `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/TransitionRequest.kt`,
    `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/workflow/TransitionRequestTest.kt` (신규),
    `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`,
    `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/WorkflowController.kt`,
    `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/dto/TransitionRequestDto.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/CustomExpressionValidatorTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/PermissionValidatorTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/NotStatusCategoryValidatorTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/RequiredFieldValidatorTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/RunAutomationPostActionTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/NotifyPostActionTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/CallWebhookPostActionTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/SetFieldPostActionTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/property/WorkflowPropertyTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowControllerMvcTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/dto/WebDtoSerializationTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/integration/WorkflowIntegrationTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/adapter/inbound/WorkflowTransitionAdapterTest.kt`,
    `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/repository/WorkflowRepositoryTest.kt`
  ]
- depends-on. `[1]`  (WorkflowEngine 이 transitionName 안 봐야 안전)

**RED**.
- 파일. `TransitionRequestTest.kt` (신규)
- 테스트. `TransitionRequest(workflowKey, issueKey, fromStateKey, toStateKey, actorId, issueFields, actorRoles, version)` — 9 → 8 파라미터 시그니처 컴파일 + `validate()` 통과 기대. 현재 컴파일 fail.

**GREEN**.
- `TransitionRequest.kt`. `transitionName: String` 필드 제거 + Konform `transitionRequestValidation` 에서 `TransitionRequest::transitionName { minLength(1) }` 라인 제거 + KDoc `@param transitionName ...` 줄 제거.
- `WorkflowController.kt:84-115`. `TransitionPlanRequestBody.transitionName` 필드 제거 + `TransitionRequestDto` 생성 + `TransitionRequest` 생성에서 transitionName 인자 제거.
- `TransitionRequestDto.kt`. `transitionName` 필드 제거 + Konform 갱신.
- caller (production + test 17 파일) 의 `TransitionRequest(...)` 생성에서 `transitionName = "..."` 명명 인자 제거.

**REFACTOR**.
- `TransitionRequest.kt` KDoc 의 `@param transitionName` 자리에 "L11 `WorkflowTransition.key` (`from__to`) 합성 기반 매칭 — ADR 2026-05-28 참조" 1줄 추가.

**검증**. `./gradlew :modules:shared-kernel:test :modules:project-workflow:test :modules:issue-tracking:compileKotlin`.

---

### Task 4. issue-tracking AppTransitionIssueRequest + IssueController 의 transitionName 라인 제거

**메타**.
- agent. `backend-engineer`
- files. [
    `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`,
    `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`,
    `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTransitionTest.kt`
  ]
- depends-on. `[3]`  (shared-kernel TransitionRequest 시그니처 + IssueApplicationService 의 호출 갱신 선행)

**RED**.
- 파일. `IssueApplicationServiceTransitionTest.kt`
- 테스트. `AppTransitionIssueRequest(toStateKey, expectedVersion)` 2 파라미터 시그니처로 `transitionIssue()` 호출. 현재 3 파라미터라 컴파일 fail.

**GREEN**.
- `IssueApplicationRequests.kt`. `AppTransitionIssueRequest.transitionName` 필드 제거 (3 → 2 필드).
- `IssueController.kt:190-195`. `AppTransitionIssueRequest` 생성에서 `transitionName = request.toStatusKey` 라인 제거 (BLOCKER 본질 부위).
- `IssueApplicationService.kt` 의 `transitionIssue()` 메서드에서 `TransitionRequest` 생성 시 `transitionName` 안 넘김 (Task 3 에서 이미 처리됐을 수 있음 — 검증 후 정리).

**REFACTOR**.
- `AppTransitionIssueRequest` KDoc 갱신 — `transitionName` 제거 사유 (ADR 2026-05-28 참조) 1줄.

**검증**. `./gradlew :modules:issue-tracking:test --tests IssueApplicationServiceTransitionTest`.

---

### Task 5. IssueControllerTransitionIntegrationTest 우회 seed 해제 + production-aligned 시드

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerTransitionIntegrationTest.kt`]
- depends-on. `[4]`  (IssueController + AppTransitionIssueRequest 변경 선행)

**RED 변형**. 본 task 는 우회 seed 제거 단계라 정통 RED commit 분리 불가능 — PR #12, #16, #19 패턴 적용. commit message 에 변형 사유 명시 + plan §Plan 변형 단락 inline (verifier prompt 첨부 대상).

- 변형 본질. PR #27 머지 시점의 통합 테스트가 `transitionName=toStateKey` 우회 seed 로 production 함정을 가렸음 — 본 task 가 그 우회 제거. 우회 제거 후 시드를 표준 `software-default.yaml` 로 정렬하면, Task 1~4 의 GREEN 이 이미 적용된 상태에서는 PASS 가 정상.
- 회귀 가드. 같은 (from, to) 전이가 정상 매칭되는 시나리오 (S1) + 정의 안 된 (from, to) 가 409 (S3) + version conflict 409 (S4) 추가.

**GREEN**.
- `IssueControllerTransitionIntegrationTest.kt` 의 setup 단계에서 hand-crafted `transitionName=toStateKey` 우회 yaml 제거.
- 표준 `software-default.yaml` 시드로 교체 (이미 `YamlSeedService` 가 부팅 시 적재하므로 추가 호출 0 가능 — 검증 후 정리).
- 시나리오 S1 (happy path), S3 (invalid transition 409), S4 (version conflict 409) 통합 테스트 추가 또는 갱신.

**REFACTOR**.
- 테스트 base class 활용 (PR #23 의 `IssueTestcontainersBase` 패턴) — 이미 적용 중인지 검증.

**검증**. `./gradlew :modules:issue-tracking:test --tests IssueControllerTransitionIntegrationTest`.

---

### Task 6. frontend planTransition + MSW handler 정리 (옵션 b1 dead code cleanup)

**메타**.
- agent. `frontend-engineer`
- files. [
    `apps/web/src/api/workflows.ts`,
    `apps/web/src/api/workflows.test.ts`,
    `apps/web/src/mocks/workflow-handlers.ts` (영향 0 — transitionName 인용 0, 검증만)
  ]
- depends-on. `[3]`  (WorkflowController endpoint body schema 변경 후)

**RED**.
- 파일. `workflows.test.ts`
- 테스트 (vitest). `planTransition()` 함수 시그니처에서 `transitionName` 인자 제거된 형태로 호출 + MSW handler 가 `transitionName` 없이 처리 통과 기대. 현재 컴파일 fail (TS 시그니처).

**GREEN**.
- `apps/web/src/api/workflows.ts:112-139`. `planTransition()` 함수 시그니처에서 `transitionName: string` 제거 (L119) + body 직렬화에서 `transitionName: request.transitionName` 라인 제거 (L131).
- 직렬화 후 body 가 backend `TransitionPlanRequestBody` 와 정합.

**REFACTOR**.
- `planTransition()` KDoc 갱신 — transitionName 제거 사유 (ADR 2026-05-28 참조) 1줄.

**검증**. `pnpm -F web typecheck && pnpm -F web test workflows.test.ts`.

---

### Task 7. ADR + WorkflowTransition.kt KDoc 정정

**메타**.
- agent. `backend-engineer`
- files. [
    `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/WorkflowTransition.kt`,
    `docs/adr/2026-05-28-workflow-transition-identity-policy.md` (신규)
  ]
- depends-on. `[5]`  (backend 전체 결정 박힌 후 ADR 정정 — Task 6 frontend 와는 무관)

**RED 변형**. docs/KDoc 정정 task — TDD RED 의 본질은 "ADR 부재 = 결정 미박힘". commit message 에 명시 + verifier prompt 첨부.

**GREEN**.
- `WorkflowTransition.kt` L10 KDoc 정정 — `(fromStateKey, toStateKey)` 가 §유일성 보장키, `name` 의 역할 = 사람 친화 표시 라벨 (1급 시민 아님).
- `WorkflowTransition.kt` L11 KDoc — `key` 의 "라우팅/API 호출용" 표현 보존 (이제 진짜 라우팅 키).
- 신규 ADR 작성. 결정 본문 (옵션 a/c 기각 사유, 옵션 b 채택 근거, 향후 "같은 (from, to) 에 여러 transition" 탈출구).

**REFACTOR**.
- `learnings.md` 후보 정리 (본 PR codereview 단계에서 진행).

**검증**. `./gradlew :modules:project-workflow:test :modules:project-workflow:ktlintCheck :modules:project-workflow:detekt`.

---

## Plan 메타

- task 수. 7 (BLOCKER 1 해소 후 b1 채택으로 frontend Task 6 신규)
- wave 수. 5
  - Wave 1. T1, T2 (병렬, project-workflow 모듈, file 겹침 0)
  - Wave 2. T3 (depends-on [1], shared-kernel + WorkflowController + 17 caller 갱신)
  - Wave 3. T4 (depends-on [3], issue-tracking application/adapter)
  - Wave 4. T5 (depends-on [4], 통합테스트 우회 seed 해제)
  - Wave 5. T6 + T7 병렬 (T6 frontend depends-on [3], T7 ADR depends-on [5]). 단 T6 depends-on=[3] 만족 시 Wave 3 직후 진입 가능 — 실제 dispatch 는 T7 의 [5] 제약이 더 늦으므로 T6+T7 모두 Wave 5 통합 안전.
    - 최적화 옵션. T6 (frontend) 를 Wave 3 종료 직후 별 wave 로 띄우면 1 wave 단축 가능. bts-impl 의 wave 계산이 자동 처리 (depends-on 만 보고).
- 예상 시간. task × 5분 = 약 35분 (직렬 기준), wave 병렬 적용 시 약 25분
- TDD 강제. yes (T5 / T7 변형 + commit message 본질 명시)
- 병렬 dispatch. bts-impl wave 계산 입력
- 추가 검증. backend 3 모듈 (`:modules:shared-kernel :modules:project-workflow :modules:issue-tracking`) clean test ktlintCheck detekt 전부 BUILD SUCCESSFUL + frontend `pnpm -F web verify` PASS
- ADR 신규. 1건

## 리뷰 결과

### self-eng-review (2026-05-28, fast-track inline)

- ✅ **절대 규칙 19개 (DEVELOPMENT.md §1) 위반 0** — REST 외부 contract 변경 = `/api/v1/issues/{key}/transition` 0건 + `/api/v1/workflows/{key}/transitions` 1건 (dead code 정리, NFR-4 갱신 명시). DB 마이그레이션 0. yaml 스키마 변경 0. 트랜잭션 경계 (shared-kernel SPI MANDATORY) 유지. BC 격리 유지. ADR 작성 (FR-8). TDD red→green→refactor 정통 (T1~T4, T6) + 변형 (T5 통합 / T7 ADR) commit message 본질 명시. learnings PR #12/#16/#19/#23 변형 패턴 일관.
- ✅ **learnings 회귀 위험 0** — "PoC 표현" 0건. "본질 회피 vs 해소" → 옵션 b 본질 해소. "lint-staged race" → wave 내 file 겹침 0 검증. "서브에이전트 scoped typecheck 맹점" → bts-impl 의 wave 종료 시 controller 전체 ground truth 검증 명시. "BC 격리 wrapper exception" → 본 PR 이 BC 격리 강화 (shared-kernel SPI 정합).
- ✅ **Plan 메타 블록 완정성** — agent / files / depends-on 모두 명시. depends-on cycle 0. 7 task wave 계산 가능. file 겹침 0 검증 — T1 (WorkflowEngine.kt) / T2 (YamlSeedService.kt) / T3 (TransitionRequest.kt + 17 file) / T4 (IssueController.kt + IssueApplicationRequests.kt) / T5 (IssueControllerTransitionIntegrationTest.kt) / T6 (apps/web/src/api/workflows.ts) / T7 (WorkflowTransition.kt + ADR) 모두 별 파일 (T1 의 WorkflowEngine.kt vs T7 의 WorkflowTransition.kt 도 별 파일).
- ✅ **TDD 분해 정합성** — Task 1~4, T6 정통 RED→GREEN→REFACTOR. T5 변형 (우회 seed 제거 단계 RED 분리 불가). T7 변형 (docs/KDoc 정정 RED 본질 = ADR 부재). 변형 사유 commit message + plan inline 명시 (PR #12/#16/#19/#23 패턴).
- ✅ **BC 격리** — shared-kernel SPI (`TransitionRequest`) 만 사용. issue-tracking → project-workflow 직접 import 0 유지. cross-BC port 패턴 유지.
- ⚠️ **주의 (정보성)**. T3 의 file 수 17 — wave 종료 시 controller 전체 `./gradlew :modules:shared-kernel:test :modules:project-workflow:test :modules:issue-tracking:compileKotlin` ground truth 검증 필수 (서브에이전트 scoped typecheck 맹점 learnings 적용).

### BLOCKER 처리 이력

- **🛑 BLOCKER 1 (해소됨, 2026-05-28)**. `WorkflowController.kt:84-115` 의 `POST /api/v1/workflows/{key}/transitions` endpoint 가 `transitionName` 을 외부 contract 로 받는 것 발견 + plan §Task 3 files 9건 누락 + spec NFR-4 가정 위반. 해소 — Maxi 옵션 b1 채택 (endpoint 도 transitionName 제거 + frontend `planTransition()` dead code 정리). spec NFR-4 갱신 (body 변경 1건 dead code 정리 명시) + EC-5 갱신. plan §Task 3 files 9건 추가 + Task 6 (frontend) 신규 + Plan 메타 7 task / 5 wave 갱신.

### plan-ceo-review / plan-design-review / plan-devex-review

- ⏭️ **skip**. type=api hot-fix slice + fast-track 일관성. ceo / design / devex 영역 영향 0 (외부 contract 변경 1건이지만 dead code 정리). PR #21/#22/#23/#24 fast-track 패턴 따름.

### PR 단위 code-reviewer agent (superpowers, 2026-05-28)

- ✅ **PASS w/ CONCERNS** (BLOCKER 0, CONCERN 3 — 본 PR scope 외 cleanup 후보)
- **검증 통과**. DEVELOPMENT.md §1 절대 규칙 19개 위반 0 + DATA.md §1 5원칙 위반 0 + learnings 회귀 0 + Plan 명세 일치.
- **CONCERN-1 (Important, 별 PR)**. `Workflow.of()` aggregate factory invariant 5번이 여전히 `(from, to, name)` 3 튜플 — yaml seed `validateTransitionUniqueness` (2 튜플) 와 부정합. yaml seed 안 거치는 다른 경로 (`WorkflowRepository.toAggregate()`, FR-WF-02 CRUD) 로 같은 (from, to) 가 들어와도 통과. **별 PR cleanup**.
- **CONCERN-2 (Suggestion, 별 PR)**. dead parameter/helper — frontend `planTransition()` 의 `transitionKey: string` 미사용 + backend `TransitionRequestDto.toDomain()` caller 0. **별 PR cleanup**.
- **CONCERN-3 (Info, 수용)**. Plan §Task 3 files 19개 외 4 file 추가 수정 (컴파일 의존성 정당). **learnings 후보**.

### PR 단위 /review (gstack) adversarial subagent

- ✅ **PASS** (BLOCKER 0, INVESTIGATE 1건 informational)
- **cross-branch 충돌**. 0 (미머지 PR 0).
- **잔여 transitionName 사용처**. 모두 의도된 컨텍스트 (회귀 가드 + DB seed helper + JSDoc).
- **표준 4종 yaml fail-fast 안전**. (from, to) 유일성 사전 검증 통과.
- **INVESTIGATE 1건**. Spring Boot Jackson `FAIL_ON_UNKNOWN_PROPERTIES` 정책 미명시 — 외부 consumer 가 transitionName 보낼 때 silent ignore vs 400 reject 결정. 본 PR scope 외, 향후 외부 API 통합 시 명시 권장.
- **추가 BLOCKER/HIGH severity 발견 0**. code-reviewer 의 CONCERN-1/2/3 모두 confirm.

### 권장 액션 — Ship as-is

본 PR scope 의 BLOCKER 1 본질 fix 완료. CONCERN-1/2/3 모두 본 PR scope 외 cleanup, 별 PR 후보로 분리.

### 후속 cleanup PR 후보 (별 작업)

1. **Workflow aggregate invariant 정렬** — `Workflow.of()` 의 invariant 5번을 `(from, to)` 2 튜플로 정정 (CONCERN-1).
2. **Dead parameter/helper cleanup** — `planTransition()` 의 `transitionKey: string` + `TransitionRequestDto.toDomain()` 제거 (CONCERN-2).
3. **Pre-existing ktlint test + detekt 64 weighted issues** — learnings PR #25 C1 본질 동일, multi-BC chore cleanup PR (BLOCKER 2 와 묶어도 가능).
4. **BLOCKER 2 hot-fix** — V003+V004 Flyway 경로 정리 (저장 컨텍스트 2026-05-28 #28 priority 3).
5. **learnings 후보 추가**. (a) shared-kernel SPI 시그니처 변경 task 의 plan §files 메타는 전수 grep 후 작성 (CONCERN-3). (b) ADR 결정 시 도메인 aggregate invariant 와의 정합 검토 필수 (CONCERN-1 회귀 차단).


## 리뷰 결과 (← /bts-review-plan 채움)
