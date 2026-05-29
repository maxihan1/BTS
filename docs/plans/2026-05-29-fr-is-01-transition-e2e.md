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

## 도메인 정리

- **BC**: issue-tracking (주). project-workflow는 `WorkflowKeyResolver` SPI로 소비 (PR #25 공통 SPI 모듈, PR #27 wiring). 새 용어/엔티티 없음 — 기존 모델의 전이 UI + 테스트만 추가.
- **scope 확장** (Maxi 결정 2026-05-29): classify=qa였으나 실제 = **frontend feature(전이 UI) + qa(프론트 E2E + 백엔드 통합 테스트)**. agent: frontend-engineer(UI) + qa-engineer(E2E·통합테스트).
- **전이 실행 계약**: `POST /api/v1/issues/{key}/transition`, body `{ toStatusKey, expectedVersion }` → 200 + 갱신 `IssueResponse`. workflowKey는 서버가 `WorkflowKeyResolver.resolveStart`로 자동 결정. 에러: 404(이슈 없음)·422(워크플로우 미설정)·409(전이 거부/낙관락 충돌).
- **혼동 주의**: `POST /api/v1/workflows/{key}/transitions`(`planTransition`)는 전이 "계획 계산"용 별개 엔드포인트. 이슈 전이 실행이 아님.
- **열린 설계 질문 (→ spec)**: 프론트 전이 UI가 "가용 전이 목록"을 어떻게 얻는가. 현재 `IssueResponse`엔 workflowKey/available transitions 없음. (a) 이슈의 프로젝트 워크플로우 정의를 별도 fetch 후 클라이언트가 currentStateKey 기준 필터 (b) 백엔드가 이슈별 가용 전이를 응답에 포함. spec office-hours에서 결정.
- **현재 UI 상태**: `issues.$key.tsx` + `IssueMetaPanel.tsx`는 상태를 읽기전용 배지(`data-testid="issue-state-badge"`)로만 표시. D6에서 전이 UI 의도적 제외. 이번 작업이 그 후속 slice.
- **새 용어**: 없음. **기존 결정 충돌**: 없음.
- **관련 ADR**: `docs/decisions/2026-05-28-workflow-transition-identity-policy` (transition identity = (fromStateKey, toStateKey), transitionName 불필요).
- **관련 메모**: `issue-transition-backend-gap`(백엔드 갭 PR #27/#28 해결, 본 E2E·통합테스트가 런타임 최종검증), `bts-cross-bc-test-migration`(백엔드 통합테스트는 issue-tracking↔project-workflow 마이그레이션 둘 다 testRuntimeOnly 의존 — 워크플로우 시드 필요), `frontend-zod-backend-dto-contract-gap`(전이 UI Zod 스키마는 실제 backend DTO와 정합 grep 검증 필수).

## 스펙

전체 스펙. [docs/specs/2026-05-29-fr-is-01-transition.md](../specs/2026-05-29-fr-is-01-transition.md)

핵심 결정 (Maxi 2026-05-29).
- 가용전이 = **옵션 A 풀버전** (지라식). 서버가 이슈별로 validator/조건/권한까지 평가해 실행 가능한 전이만 반환.
- scope = shared-kernel SPI 확장 + project-workflow 구현 + issue-tracking 엔드포인트 + 전이 UI(frontend) + 프론트 E2E + 백엔드 Testcontainers 통합 테스트. **cross-BC 3모듈 (Maxi 승인 BC 격리 예외).**
- 전이 실행 엔드포인트(`POST /issues/{key}/transition`)는 기존 — 통합 테스트로 런타임 검증.

핵심 시나리오 3줄.
- 이슈 상세에서 가용 전이를 골라 상태 변경(open→in_progress 등), 배지·가용전이 갱신.
- 가용 전이만 노출 — 서버가 validator 평가 후 권위 있게 큐레이션.
- 잘못된 전이/충돌/미설정은 409·422로 안전 처리.

## Brainstorming Check

✅ 통과. Phase B에서 gap 3건(가용전이 SPI 부재 / 이슈 workflow_key 미영속 / 조건부 전이 미고려) 발견 → Maxi 결정(옵션 A 풀버전)으로 spec 보강. 상세는 spec 파일 ## Brainstorming Check.

## Plan

> **이 plan은 PR 1/2 (백엔드 기반)만 분해한다.** PR 2/2 (전이 UI + Playwright E2E)는 PR1 머지 후 별도 worktree에서 진행 — 하단 ## PR 2/2 (후속) 참조.
> 공통 검증: `./gradlew ktlintCheck detekt`, 모듈별 `./gradlew :backend:<module>:test`.

### Task 1. shared-kernel — 가용전이 SPI 타입 + 메서드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/AvailableTransitionsRequest.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/AvailableTransitionsResult.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/WorkflowTransitionPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/workflow/AvailableTransitionsRequestTest.kt`]
- depends-on: []

**RED**: `AvailableTransitionsRequestTest` — `AvailableTransitionsRequest(workflowKey, fromStateKey, actorId, actorRoles, issueFields)` 의 `validate()` 가 빈 workflowKey/fromStateKey 거부. 타입 미존재로 컴파일 실패.

**GREEN**:
- `AvailableTransitionsRequest` data class + `validate()` (TransitionRequest 패턴 답습).
- `AvailableTransitionsResult` sealed — `Success(transitions: List<AvailableTransitionView>)` / `WorkflowNotFound(key)`. `AvailableTransitionView(fromStateKey, toStateKey, name)`.
- `WorkflowTransitionPort` 에 `@Transactional(readOnly=true, propagation=MANDATORY) fun availableTransitions(req): AvailableTransitionsResult` 추가.

**REFACTOR**: KDoc — 반환 계약 명시 (Success/WorkflowNotFound 2-case, `else` 금지). transition identity=(from,to) ADR 인용.

**검증**: `./gradlew :backend:shared-kernel:test`

### Task 2. project-workflow — availableTransitions 구현 (enumerate + validator 평가)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/adapter/inbound/WorkflowTransitionAdapter.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/adapter/inbound/WorkflowTransitionAdapterAvailableTest.kt`]
- depends-on: [1]

**RED**: `WorkflowTransitionAdapterAvailableTest` — software-default 로드, `fromStateKey=open` → `Success([open→in_progress, open→closed])` 기대. 가드 있는 워크플로우에서 미충족 actor → 해당 전이 제외 기대. 메서드 미구현으로 실패.

**GREEN**: `availableTransitions` 구현 — 워크플로우 로드(없으면 `WorkflowNotFound`), `transitions.filter { fromStateKey == req.fromStateKey }`, 각 후보를 기존 `WorkflowEngine`/validator 경로로 평가(`plan` 과 동일 검증 재사용, 로직 중복 금지) → 통과 전이만 `Success`.

**REFACTOR**: enumerate+평가 로직을 private helper로. `plan` 과 공유 가능한 검증 부분 추출.

**검증**: `./gradlew :backend:project-workflow:test`

### Task 3. issue-tracking — IssueApplicationService.availableTransitions

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceAvailableTransitionsTest.kt`]
- depends-on: [1]

**RED**: 서비스 단위 테스트(port mock) — 이슈 조회 → `resolveStart` → `port.availableTransitions` 호출 → 결과 매핑. 이슈 없음 → `IssueNotFoundException`. `WorkflowSchemeNoDefaultException` → `IssueWorkflowNotConfiguredException`. 메서드 미존재 실패.

**GREEN**: `availableTransitions(actor, key): List<AvailableTransitionView>` (또는 응답 모델) — 기존 `transitionIssue` 의 resolve/예외매핑 패턴 재사용. 읽기 전용 `@Transactional(readOnly=true)`.

**REFACTOR**: resolve+예외매핑 공통부를 private helper로 (transitionIssue 와 공유).

**검증**: `./gradlew :backend:issue-tracking:test --tests *AvailableTransitions*`

### Task 4. issue-tracking — GET /issues/{key}/transitions 엔드포인트 + 응답 DTO

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/AvailableTransitionsResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerTransitionsTest.kt`]
- depends-on: [3]

**RED**: 컨트롤러 web 테스트(MockMvc 또는 기존 패턴) — `GET /api/v1/issues/{key}/transitions` 200 + `{ data: { transitions: [...] } }`. 404(이슈 없음)·422(미설정) 매핑. 엔드포인트 미존재 실패.

**GREEN**: `@GetMapping("/{key}/transitions")` → service.availableTransitions → `AvailableTransitionsResponse` 래핑. `IssueExceptionHandler` 가 404/422 이미 매핑하는지 확인, 없으면 추가.

**REFACTOR**: 응답 DTO KDoc + transition.key computed(`${from}__${to}`) 명시.

**검증**: `./gradlew :backend:issue-tracking:test --tests *ControllerTransitions*`

### Task 5. 통합 테스트 — 전이 런타임 (Testcontainers, mock 없음)

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueTransitionRuntimeIntegrationTest.kt`]
- depends-on: [2, 4]

**RED**: 실제 Postgres + 실제 워크플로우 시드(software-default). 이슈 생성 → `GET /transitions` 가 현재상태 출발 전이 반환 → `POST /transition` 실행 → 재조회 시 상태 갱신 + 새 가용전이. **mock 없이** 전 경로 검증. 미구현/wiring 누락 시 실패. cross-BC 마이그레이션 의존(`bts-cross-bc-test-migration`) — issue-tracking + project-workflow 시드 둘 다 testRuntimeOnly 확인.

**GREEN**: (구현은 Task 1~4가 제공) 테스트 그린.

**REFACTOR**: Testcontainers singleton 패턴(`learnings` 2026-05-21 stale port 함정 회피).

**검증**: `./gradlew :backend:issue-tracking:test --tests *RuntimeIntegration*`

### Task 6. 통합 테스트 — validator 가드 가용전이 필터링 실증 (FR-T-Q3)

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueTransitionGuardFilterIntegrationTest.kt`]
- depends-on: [2, 4]

**RED**: 가드(예: `PermissionValidator`) 적용된 전이가 actor 권한 미충족 시 `GET /transitions` 결과에서 **제외**, 충족 시 포함. 풀버전(조건 평가)의 실증. 가드 워크플로우 시드 방법은 RED 작성 시 확정(기존 검증 자원 vs 테스트 전용 시드).

**GREEN**: (Task 2의 validator 평가가 제공) 테스트 그린.

**REFACTOR**: 시드/컨텍스트 빌더 헬퍼 정리.

**검증**: `./gradlew :backend:issue-tracking:test --tests *GuardFilter*`

## Plan 메타

- task 수: 6 (PR 1/2 백엔드 기반)
- 예상 wave: 4 (W1: T1 / W2: T2·T3 / W3: T4 / W4: T5·T6)
- TDD 강제: yes (test 커밋이 feat 커밋 선행)
- 추가 검증: ktlintCheck, detekt, Testcontainers 통합
- cross-BC: shared-kernel + project-workflow + issue-tracking (Maxi 승인 예외, SPI 확장 채널)

## PR 2/2 (후속, 별도 worktree)

PR1 머지 후 진행 — 전이 UI(frontend). 대략 task.
- api 클라이언트(`fetchIssueTransitions`/`transitionIssue`) + Zod (backend DTO grep 정합, `frontend-zod-backend-dto-contract-gap`)
- MSW stateful 핸들러(GET transitions / POST transition)
- 전이 UI(`IssueMetaPanel.tsx`/`issues.$key.tsx`, shadcn select) + i18n + 에러(409/422)
- Playwright E2E(`issue-transition.spec.ts`) — happy + 가용전이 필터 + 에러


## 리뷰 결과

> classify type=qa(스테일)였으나 실제 scope=backend+api(신규 공개 엔드포인트 + SPI 계약). fast-track skip 부적절 판단 → eng + devex 관점 리뷰 적용.

### plan-eng-review (2026-05-29)
- ✅ TDD 순서 — 각 task RED→GREEN→REFACTOR, test 커밋 feat 선행 가능.
- ✅ BC 격리 — cross-BC지만 SPI 확장(sanctioned 채널) + Maxi 승인. T3가 port mock로 단위 격리 유지(depends-on [1]만).
- ✅ 트랜잭션 — SPI `availableTransitions`가 `MANDATORY`, 서비스 T3가 `readOnly=true` tx로 호출. `resolveStart`(MANDATORY)+port 호출이 **동일 트랜잭션**임을 impl에서 보장.
- ⚠️ 주의 1 (impl 필수 해소) — **enumeration은 validator만 평가, `WorkflowPostAction`은 절대 실행 금지.** GET(읽기)에서 post-action(상태변경/부수효과) 트리거되면 안 됨. T2 GREEN에서 `plan` 경로 재사용 시 post-action 분기 제외 명시.
- ⚠️ 주의 2 (impl 필수 해소) — **`WorkflowNotFound` HTTP 매핑 미정.** workflowKey resolve됐으나 워크플로우 row 부재 시. 422(미설정)로 통일 권장. T4에서 `IssueExceptionHandler` 매핑 확정.
- ⚠️ 주의 3 — T5/T6 통합테스트는 impl(T2,T4) 후 작성이라 엄밀한 red-first 아님(검증 지향). BTS E2E 관례상 허용이나, wiring 끊어 fail 재현 1회로 "진짜 검증함" 확인 권장.
- BLOCKER: 없음.

### plan-devex-review (2026-05-29)
- ✅ 응답 형태 — `{ data: { transitions: [{fromStateKey,toStateKey,name,key}] } }`. 기존 `DataResponse<T>` + frontend `workflowTransitionViewSchema`와 정합(PR2 Zod 재사용 가능).
- ✅ 에러 코드 — GET은 404/422만(409 비해당). /api/v1 버전 일관.
- ⚠️ 경미 — `GET .../transitions`(복수, 목록) vs 기존 `POST .../transition`(단수, 실행) 명명 혼재. 기존 POST 명명 유지가 제약이라 수용. impl에서 KDoc로 의도 명시.
- BLOCKER: 없음.
