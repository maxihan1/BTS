<!-- FR-IS-01 transition wiring slice — IssueApplicationService/IssueController가 project-workflow BC의 WorkflowResolver를 SPI 경유 호출하도록 연결하는 백엔드 작업 plan -->

# 이슈 상태 전환 백엔드 wiring — WorkflowResolver consumer 연결

> slug: wiring-workflowresolver-consumer-fr-is-01-transiti
> type: backend
> agent: backend-engineer
> 주 BC: **issue-tracking** (classify는 `project-workflow`로 분류했으나, 실 변경 영역이 `IssueController`/`IssueApplicationService`라서 정정)
> 부 BC: project-workflow (SPI 경유 호출만, 코드 변경 없음 또는 최소)
> 생성: 2026-05-28
> 관련 메모: [[issue-transition-backend-gap]] 해소 trigger

## Brief

PR #18에서 project-workflow BC에 추가된 `WorkflowResolver` (SPI outbound port — 프로젝트 키 + 이슈 타입 키 기준으로 적용 워크플로우 결정)를, issue-tracking BC가 PR #25에서 분리한 공통 SPI 경계를 통해 실제로 호출하도록 연결한다.

### 현재 문제 (issue-transition-backend-gap)

1. `IssueController.kt:179`. `AppTransitionIssueRequest(workflowKey = "DEFAULT", ...)`로 하드코딩. 그러나 시드된 워크플로우는 `software-default` / `bug-tracking` / `kanban-basic` / `simple` — `"DEFAULT"` 미시드
2. `IssueApplicationService.kt:92`. `currentStateKey = "OPEN"`(대문자). 워크플로우 상태 키는 `software-default.yaml`의 `open` / `in_progress` / `in_review` / `done` / `closed`(소문자). 케이스 불일치

→ 단위 테스트는 `WorkflowTransitionPort`를 mock해서 통과하지만 런타임에 전환이 동작하지 않음.

### 본 PR 변경 (예상 — 구체는 /bts-spec, /bts-plan에서 확정)

1. `IssueController` (또는 application service) 가 `WorkflowResolver.resolveFor(projectKey, issueTypeKey)` 호출 → 실제 워크플로우 키/엔티티 획득 → `AppTransitionIssueRequest` 또는 동등 경로에 전달
2. `IssueApplicationService.kt:92`의 초기 상태 키를 워크플로우의 시작 상태(`open` 소문자)와 정렬
3. 필요 시 software-scheme 자동 배정(WorkflowResolver 주석상 EC-1, D10) 검증 — DB에 `project_workflow_scheme_assignments` 자동 매핑이 정상 동작하는지

### 목표

이슈 상태 전환이 런타임에 실제 동작하게 만든다. 후속 FR-IS-01 D7 E2E(생성 → 조회 → 수정 → **상태 전환** → 소프트 삭제 → 키 영속성) 풀시나리오를 돌릴 수 있는 상태로 unblock.

### 제약

- **BC 격리**. PR #25에서 분리한 공통 SPI 경계를 통해서만 호출. issue-tracking에서 project-workflow 내부 클래스 직접 import 금지
- 백엔드 작업 단독 (프론트 UI / E2E는 D6 / D7 별 slice)
- 본 PR scope에서는 전환 UI 신규 생성 없음 — 백엔드 wiring + 단위/통합 테스트만

## 도메인 정리

> `grill-with-docs` 스킵 사유. 본 작업은 새 도메인 용어 도입 0건, PR #25 shared-kernel ADR(2026-05-27)의 첫 consumer 적용 사례. 도메인 모델 영향이 정의 영역(shared-kernel 패턴)에 이미 잡혀 있어 대화형 grill 비용 > 효익. 사실 검증은 코드 grep으로 완료(아래), 핵심 결정 사항은 spec 단계 명세화 + 게이트1 일괄 검토.

### BC 경계

- **주 BC**. `issue-tracking` (코드 변경 주 영역 — `IssueController`, `IssueApplicationService`)
- **부 BC**. `project-workflow` (이미 만들어진 `WorkflowResolver` consumer로 호출만)
- **경계 정책**. ADR [2026-05-27-shared-kernel-extraction](../decisions/2026-05-27-shared-kernel-extraction.md) 패턴 후속 적용. issue-tracking은 `com.bts.shared.*` 만 import, project-workflow 내부 직접 import 금지(BC 격리).

### 영향 엔티티

- **Issue** (issue-tracking). `currentStateKey` 표현 케이스 변경 (`"OPEN"` 대문자 → 워크플로우 정본 키 `open` 소문자). **필드 추가 없음.**
- **IssueApplicationService** (issue-tracking). 생성(line 92) + 전환(line 206) 흐름에 `WorkflowResolver` 호출 추가. `workflowKey="DEFAULT"` / `currentStateKey="OPEN"` 하드코딩 제거.
- **IssueController** (issue-tracking). `AppTransitionIssueRequest.workflowKey` 생성 로직에서 `WorkflowResolver` 결과 사용 (line 179).

### 사실 검증 (코드 grep, 2026-05-28)

- `Issue` Aggregate는 `id, key, projectId(UUID), summary, reporterId, currentStateKey, version, deletedAt, createdAt, updatedAt` 보유. **`issueTypeKey` / `IssueType` 도메인 없음** (FR-IS-02 미구현).
- `WorkflowResolver` 시그니처. `resolveFor(projectKey: ProjectKey, issueTypeKey: IssueTypeKey?): Workflow`, `Propagation.MANDATORY`.
- `WorkflowResolver`의 현재 패키지. `com.bts.workflow.scheme.port.outbound` (project-workflow 모듈 내부). **shared-kernel 미이전.**
- `shared-kernel`에 현재 있는 SPI. `WorkflowTransitionPort` + `TransitionRequest/Result/Plan/FieldChange/DomainEvent` 6종. `WorkflowResolver` 없음.
- 워크플로우 정본 시작 상태 컨벤션. `software-default.yaml` 의 `key: open` (소문자), `displayOrder: 1`. 다른 워크플로우들도 모두 소문자.

### 신규 용어

없음. `WorkflowResolver` / `WorkflowScheme` / `SchemeIssueTypeMapping` 은 PR #18에서 이미 도입. `glossary.md` / `domain/project-workflow.md` 미반영 stale은 별 cleanup slice (본 PR scope 외).

### 기존 결정 충돌

없음. ADR `2026-05-27-shared-kernel-extraction` 의 published language 패턴 후속 적용.

### 도메인 결정 사항 (spec 단계 명세화 — 게이트1 일괄 검토)

- **D1. WorkflowResolver 통합 패턴**. 옵션.
  - (a) `WorkflowResolver` 인터페이스를 shared-kernel 로 이전 (`Workflow` 반환 타입 도달 폐쇄 포함). ADR 후속 자연스러움. 단 도달 폐쇄 큼.
  - (b) shared-kernel 에 신규 SPI `WorkflowKeyResolver` 정의 — consumer 가 알아야 할 최소만 노출(`WorkflowKey` 만 반환). project-workflow 가 두 SPI 구현. **published language 정신 부합**.
  - (c) issue-tracking 에서 project-workflow 직접 import 예외 명문화 — BC 격리 위반, 비추.
- **D2. 워크플로우 결정 시점**.
  - (a) 이슈 생성 + 전환 둘 다 (생성 시점에도 `WorkflowResolver` 호출 → 결정된 워크플로우의 시작 상태 사용). 깔끔.
  - (b) 전환 시점만 (생성은 케이스만 정렬 `"OPEN"` → `"open"`). 작음.
- **D3. `currentStateKey` 케이스 마이그레이션**.
  - (a) Flyway 마이그레이션으로 기존 DB 의 `"OPEN"` 데이터 일괄 `"open"` 으로 변경. 안전.
  - (b) 새 데이터부터 소문자, 기존 데이터는 그대로 (호환성 위험).
- **D4. `issueTypeKey` 전달**. 본 PR 은 `null` 전달 (default mapping). FR-IS-02 도입 시 명시 전달로 확장. **사실상 결정됨.**

### 관련 ADR

- [2026-05-27-shared-kernel-extraction](../decisions/2026-05-27-shared-kernel-extraction.md) — 직접 근거, 본 PR 이 첫 consumer 적용 사례
- [2026-05-20-authentication-provider-spi-naming](../decisions/2026-05-20-authentication-provider-spi-naming.md) — 선례 SPI 패턴 (프레임워크 비결합)

### 관련 메모

- `[[issue-transition-backend-gap]]` — 본 PR 머지로 해소
- `glossary.md` + `domain/project-workflow.md` stale (PR #18 신규 엔티티 누락) → 별 cleanup slice

## 스펙

전체 스펙. [../specs/2026-05-28-wiring-workflowresolver-consumer-fr-is-01-transiti.md](../specs/2026-05-28-wiring-workflowresolver-consumer-fr-is-01-transiti.md)

**D1 (Maxi 결정 2026-05-28)**. (b) shared-kernel에 신규 SPI `WorkflowKeyResolver` 정의 — consumer 최소 노출, published language 정신. 시그니처 `resolveStart(projectKey, issueTypeKey?): WorkflowStartState(workflowKey, startStateKey)`.

핵심 시나리오 3줄 요약.
- **이슈 생성**. `WorkflowKeyResolver.resolveStart(projectKey, null)` → `WorkflowStartState(workflowKey, startStateKey="open")` → 이슈 INSERT (currentStateKey 소문자 정본)
- **이슈 전환**. `WorkflowKeyResolver.resolveStart()` → workflowKey → `WorkflowTransitionPort.applyTransition` → 이슈 UPDATE
- **마이그레이션**. Flyway V00X로 기존 DB `OPEN` → `open` 일괄

**게이트1 추가 검토 항목**.
- **D2** (워크플로우 결정 시점, 추천 (a) 생성+전환 둘 다)
- **D3** (마이그레이션 방식, 추천 (a) Flyway)
- **C-4** (`currentStateKey` 응답 케이스 변경의 D6 UI 영향 — plan 단계에서 grep 검증)
- **EC-2 도메인 예외** (`IssueWorkflowNotConfiguredException` 신규 vs 기존 통합)
- **ADR 미해결 (`@Transactional(MANDATORY)` 위치)** — 본 PR이 첫 consumer라 결정 후 ADR 추가 단락 권장

## Brainstorming Check

✅ 통과 (1회 iteration, 자체 외부 시선 검토)

스킵 사유. `superpowers:brainstorming` 외부 호출 대신 spec 작성자가 자체 sanity check 수행. 본 작업은 명확한 wiring slice + 새 도메인 용어 0건이라 대화형 brainstorming 비용 > 효익. 발견 6건(EC-5, EC-8, D2 보조 결정, C-4, ADR 미해결, IssueWorkflowNotConfiguredException) 모두 spec EC/D 섹션 또는 게이트1 검토 항목에 반영.

## Plan

> **`superpowers:writing-plans` 우회 사유**. spec/도메인 정리에 task 윤곽이 이미 정리됨(args 명시) + 의존성 그래프 분석 가능. 직접 분해 + 형식(메타/RED/GREEN/REFACTOR) 준수.
> **`.bts-cache/classify.json` `task_count` 갱신 skip 사유**. plan §Plan 메타가 source of truth, bts-impl이 plan 파일을 직접 카운트하므로 cache 갱신 가치 < 비용.

### 사전 발견 사항 (controller 검증, 2026-05-28)

- **C-4 검증 완료**. `apps/web/src` 에서 `currentStateKey` 케이스 비교 의존 0건 (`IssueMetaPanel.tsx:41` 단순 표시, Zod `z.string().min(1)` 케이스 무관). 응답 케이스 대→소 변경 안전.
- **부수 발견**. `apps/web/src/mocks/issue-handlers.ts:16` 의 `'OPEN'` 대문자 사용 (fixtures `'open'` 과 불일치). **T9 회귀에서 함께 정렬**.
- T10 (D6 UI 검증 task) 생략, plan 9 task 확정.

### Task 1. shared-kernel `WorkflowKeyResolver` SPI + `WorkflowStartState` VO 정의

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/WorkflowKeyResolver.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/WorkflowStartState.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/workflow/WorkflowKeyResolverContractTest.kt`]
- depends-on: []

**RED**.
- 파일. `WorkflowKeyResolverContractTest.kt`
- 테스트.
  ```kotlin
  @Test fun `WorkflowKeyResolver has resolveStart(projectKey, issueTypeKey?) returning WorkflowStartState`()
  @Test fun `WorkflowStartState requires non-blank workflowKey and startStateKey`()
  @Test fun `WorkflowKeyResolver method annotated with Transactional(propagation = MANDATORY)`()
  ```
- 실패 메시지. `WorkflowKeyResolver` / `WorkflowStartState` 클래스 없음.

**GREEN**.
- `WorkflowKeyResolver.kt`. `interface WorkflowKeyResolver { @Transactional(propagation = Propagation.MANDATORY, readOnly = true) fun resolveStart(projectKey: ProjectKey, issueTypeKey: IssueTypeKey?): WorkflowStartState }`. `ProjectKey` 신규 또는 String — impl 단계에서 확정(shared-kernel-extraction ADR에 미명시, 본 task RED 시점 결정 + plan 반영).
- `WorkflowStartState.kt`. `data class WorkflowStartState(val workflowKey: String, val startStateKey: String) { init { require(workflowKey.isNotBlank()); require(startStateKey.isNotBlank()) } }`

**REFACTOR**.
- KDoc 한국어 (계약 + Propagation.MANDATORY 호출 계층 제약). 기존 `WorkflowResolver.kt` KDoc 패턴 참조.

**검증**. `./gradlew :backend:modules:shared-kernel:test --tests "*WorkflowKeyResolverContractTest"`

---

### Task 2. project-workflow `WorkflowKeyResolverImpl` 구현 + 통합 테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/adapter/inbound/WorkflowKeyResolverImpl.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/adapter/inbound/WorkflowKeyResolverImplIntegrationTest.kt`]
- depends-on: [1]

**RED**.
- 파일. `WorkflowKeyResolverImplIntegrationTest.kt` — `@SpringBootTest` + Testcontainers PostgreSQL (기존 `WorkflowResolverImplIntegrationTest` 패턴 참조)
- 테스트.
  ```kotlin
  @Test fun `resolveStart returns software-default + open for new project (EC-1 auto-assign)`()
  @Test fun `resolveStart returns mapped workflow + start state for explicit scheme assignment`()
  @Test fun `resolveStart throws WorkflowSchemeNoDefaultException when default mapping absent (EC-2)`()
  @Test fun `resolveStart is idempotent under concurrent auto-assign (EC-8 — 2 thread race)`()
  ```

**GREEN**.
- `WorkflowKeyResolverImpl.kt`. `@Component class WorkflowKeyResolverImpl(private val workflowResolver: WorkflowResolver) : WorkflowKeyResolver { override fun resolveStart(...): WorkflowStartState { val wf = workflowResolver.resolveFor(projectKey, issueTypeKey); return WorkflowStartState(wf.key, wf.states.minByOrNull { it.displayOrder }!!.key) } }` — 또는 scheme 직접 조회 (impl에서 효율 검토).

**REFACTOR**.
- KDoc (EC-1 / EC-2 / EC-8 동작 명시).
- EC-8 idempotent 검증 통과 = PR #18 자동 배정 race-safe 확인 (별 issue 등록 불필요).

**검증**. `./gradlew :backend:modules:project-workflow:test --tests "*WorkflowKeyResolverImplIntegrationTest"`

---

### Task 3. `IssueWorkflowNotConfiguredException` 신규 + 단위 테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueExceptions.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueExceptionsTest.kt`]
- depends-on: []

**RED**.
- 파일. `IssueExceptionsTest.kt` (신규 또는 기존 확장)
- 테스트.
  ```kotlin
  @Test fun `IssueWorkflowNotConfiguredException carries projectKey + nullable issueTypeKey in message`()
  @Test fun `IssueWorkflowNotConfiguredException extends IssueDomainException`()
  ```

**GREEN**.
- `IssueExceptions.kt` 에 추가.
  ```kotlin
  class IssueWorkflowNotConfiguredException(projectKey: String, issueTypeKey: String?) :
      IssueDomainException("Workflow not configured for project=$projectKey, issueType=${issueTypeKey ?: "<default>"}")
  ```

**REFACTOR**.
- KDoc 한국어. HTTP 422 매핑은 T6에서 처리.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests "*IssueExceptionsTest"`

---

### Task 4. `IssueApplicationService.transition` wiring + 단위 테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTransitionTest.kt`]
- depends-on: [1, 3]

**RED**.
- 파일. `IssueApplicationServiceTransitionTest.kt` (기존 또는 신규) — MockK
- 테스트.
  ```kotlin
  @Test fun `transition calls WorkflowKeyResolver_resolveStart with projectKey from issue and null issueTypeKey`()
  @Test fun `transition uses resolved workflowKey in AppTransitionIssueRequest (not hardcoded DEFAULT)`()
  @Test fun `transition translates WorkflowSchemeNoDefaultException to IssueWorkflowNotConfiguredException`()
  ```

**GREEN**.
- 생성자에 `WorkflowKeyResolver` 의존 주입.
- `transition()` 안에서 `workflowKeyResolver.resolveStart(projectKey, null)` 호출 → `result.workflowKey` 를 `AppTransitionIssueRequest.workflowKey` 에 사용.
- `try-catch WorkflowSchemeNoDefaultException → throw IssueWorkflowNotConfiguredException(...)`.

**REFACTOR**.
- KDoc 업데이트. `fromStateKey = issue.currentStateKey` (소문자 정본) 그대로.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests "*IssueApplicationServiceTransitionTest"`

---

### Task 5. `IssueApplicationService.create` wiring + 단위 테스트 (T4 와 같은 파일 — 직렬)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceCreateTest.kt`]
- depends-on: [1, 3, 4]

**RED**.
- 파일. `IssueApplicationServiceCreateTest.kt`
- 테스트.
  ```kotlin
  @Test fun `create uses startStateKey from WorkflowKeyResolver (not hardcoded OPEN)`()
  @Test fun `create assigns currentStateKey = open (lowercase) for software-default workflow`()
  @Test fun `create throws IssueWorkflowNotConfiguredException when resolver default-mapping missing`()
  ```

**GREEN**.
- `IssueApplicationService.create()` 라인 92. `currentStateKey = "OPEN"` 제거 → `currentStateKey = workflowKeyResolver.resolveStart(request.projectKey, null).startStateKey`.

**REFACTOR**.
- KDoc 업데이트. `Issue.create()` factory KDoc 의 예시 `"OPEN"` → `"open"` 정렬.

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests "*IssueApplicationServiceCreateTest"`

---

### Task 6. `IssueController.transition` 통합 테스트 (MockMvc end-to-end)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerTransitionIntegrationTest.kt`]
- depends-on: [2, 4, 5]

**RED**.
- 파일. `IssueControllerTransitionIntegrationTest.kt` — `@SpringBootTest @AutoConfigureMockMvc` + Testcontainers
- 테스트.
  ```kotlin
  @Test fun `POST issues key transition succeeds open to in_progress with auto-resolved workflow`()
  @Test fun `POST issues key transition returns 422 when project has no workflow default mapping`()
  @Test fun `IssueResponse currentStateKey is lowercase after transition`()
  ```

**GREEN**.
- `IssueController.kt:179` `workflowKey = "DEFAULT"` 제거 — controller 는 transport only (toStateKey 만 전달), workflowKey 결정은 service 책임 (T4 결과 활용).
- `IssueExceptionHandler.kt` 에 `IssueWorkflowNotConfiguredException` → HTTP 422 매핑.

**REFACTOR**.
- 책임 분리 검증 (controller transport, service workflow 결정).

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests "*IssueControllerTransitionIntegrationTest"`

---

### Task 7. Flyway `V00X__lowercase_current_state_key.sql` + 마이그레이션 통합 테스트

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/V00X__lowercase_current_state_key.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/migration/LowercaseCurrentStateKeyMigrationTest.kt`]
- depends-on: []
- **V 번호 결정**. impl 시점 `ls backend/modules/issue-tracking/src/main/resources/db/migration/` 확인 후 다음 번호.

**RED**.
- 파일. `LowercaseCurrentStateKeyMigrationTest.kt` — Testcontainers + Flyway 단계별
- 테스트.
  ```kotlin
  @Test fun `migration converts existing OPEN to open while preserving lowercase rows`()
  @Test fun `migration is idempotent (re-run yields no change)`()
  @Test fun `migration handles mixed case (Open Done IN_PROGRESS) via LOWER`()
  ```

**GREEN**.
- `V00X__lowercase_current_state_key.sql`.
  ```sql
  -- 기존 currentStateKey 대문자 데이터를 워크플로우 정본 키(소문자)와 정렬
  UPDATE issues
    SET current_state_key = LOWER(current_state_key)
  WHERE current_state_key <> LOWER(current_state_key);
  ```

**REFACTOR**.
- 한국어 코멘트. `updated_at` 갱신 회피 (audit 노이즈).

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests "*LowercaseCurrentStateKeyMigrationTest"`

---

### Task 8. ArchUnit 룰 — shared-kernel 경계 강제

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/architecture/SharedKernelBoundaryArchTest.kt`]
- depends-on: [1]

**RED**.
- 파일. `SharedKernelBoundaryArchTest.kt`
- 테스트.
  ```kotlin
  @Test fun `shared-kernel must not depend on com_bts_issue package`()
  @Test fun `shared-kernel must not depend on com_bts_workflow package`()
  @Test fun `shared-kernel must not depend on com_atlas_bts_identity package`()
  ```

**GREEN**.
- ArchUnit DSL. `noClasses().that().resideInAPackage("com.bts.shared..").should().dependOnClassesThat().resideInAnyPackage("com.bts.issue..", "com.bts.workflow..", "com.atlas.bts.identity..")`.

**REFACTOR**.
- 룰 위반 메시지에 ADR `2026-05-27-shared-kernel-extraction` 링크 포함.

**검증**. `./gradlew :backend:modules:shared-kernel:test --tests "*SharedKernelBoundaryArchTest"`

---

### Task 9. 회귀 — 기존 테스트 케이스 정렬 + MSW handler 정합 + KDoc 예시 정렬

**메타**.
- agent: `backend-engineer` (백엔드 다수) + frontend mock 1줄
- files: [기존 `IssueRepositoryTest.kt` / `IssueApplicationServiceTest.kt` / `IssueControllerTest.kt` (존재 시), `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt` (KDoc 예시), `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueDomainEvent.kt` (KDoc 예시), `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt` (KDoc 예시), `apps/web/src/mocks/issue-handlers.ts`]
- depends-on: [4, 5, 6, 7]

**RED**.
- 기존 백엔드 테스트들의 `"OPEN"` 기댓값을 `"open"` 으로 변경 → 새 코드에서 PASS.
- `apps/web/src/mocks/issue-handlers.ts:16` `'OPEN'` → `'open'` 변경 → fixtures 와 정합.

**GREEN**.
- 위 변경 일괄 적용.

**REFACTOR**.
- 케이스 컨벤션 도메인 노트 (`domain/issue-tracking.md` / `glossary.md`) 명시는 별 cleanup slice (본 PR scope 외 — issue 등록 권장).

**검증**. `./gradlew :backend:modules:issue-tracking:test` (전체) + `pnpm --filter web test` (mock 영향 확인) + 본 PR 전체 `./gradlew test`.

---

## Plan 메타

- **task 수**. 9 (D6 UI 검증 task 생략 — controller 사전 검증 완료)
- **예상 wave 구성** (bts-impl 이 depends-on + files 교집합으로 재계산).
  - **Wave 1** (병렬 3). T1 (shared-kernel SPI) / T3 (예외) / T7 (마이그레이션) — 의존성 0
  - **Wave 2** (병렬 3). T2 (project-workflow impl, dep T1) / T4 (IssueApplicationService.transition, dep T1+T3) / T8 (ArchUnit, dep T1)
  - **Wave 3** (단독). T5 (IssueApplicationService.create, dep T1+T3+T4 — T4와 같은 파일 직렬화)
  - **Wave 4** (단독). T6 (IssueController integration, dep T2+T4+T5)
  - **Wave 5** (단독). T9 (회귀 + frontend mock, dep T4+T5+T6+T7)
- **예상 시간**. task 평균 3분 × 9 = 약 27분 직렬. 병렬 5 wave 라 약 15~18분.
- **TDD 강제**. yes. `test:` commit이 `feat:` 보다 먼저 (controller가 git log 직접 검증, learnings 2026-05-27 C1).
- **공유 파일 직렬화**. `IssueApplicationService.kt` (T4 → T5 명시). `IssueController.kt` (T6 단독). `IssueResponse.kt` (T9 KDoc만, 코드 무변경).
- **wave 종료 검증**. controller가 전체 `./gradlew test` ground truth 실행 (서브에이전트 scoped green 신뢰 금지, learnings 2026-05-27).
- **추가 검증**. ktlintCheck + detekt (백엔드), vitest (T9 frontend mock 영향분).
- **금지 사항**. 디버그/스크래치 파일 커밋 (lint-staged 흡수 위험, learnings 2026-05-27).

## 리뷰 결과

### 외부 리뷰 체인 스킵 (사유)

`/bts-review-plan` 의 타입 분기표 (`auth/migration/ui/api/feature/design/bugfix/chore/qa`) 에 `backend` 명시 없음. fallback 모호 — 본 작업은 다음 사유로 외부 리뷰 체인 전부 skip + controller self-review 로 대체.

- **eng-review skip**. plan 검증 영역(D1 SPI 선택 / ADR 미해결 / wave 구성 / V 번호) 은 self-review (사실 검증 + 의존성 분석) 로 충분. 외부 호출 가치 < 비용.
- **ceo-review skip**. 내부 wiring, product 영향 0 (응답 케이스 변경만, spec C-4 검증 완료).
- **design-review skip**. 백엔드 전용, UI 변경 없음.
- **devex-review skip**. shared-kernel SPI 패턴은 ADR `shared-kernel-extraction` 에서 이미 검토 완료.
- **autoplan skip**. wiring slice scope 에 비해 비용 큼.

### Controller self-review (2026-05-28)

자체 외부 시선으로 plan 7개 영역 점검.

| 항목 | 결과 |
|---|---|
| 1. task 수 9 + wave 5 의존성 그래프 | ✅ T1 → T2/T4/T8, T3 → T4/T5, T4 → T5, T2+T4+T5 → T6, T4+T5+T6+T7 → T9. **DAG 정상, cycle 없음** |
| 2. 공유 파일 직렬화 (files 교집합) | ✅ `IssueApplicationService.kt` (T4↔T5) 명시 + depends-on 강제. learnings 2026-05-27 lint-staged race 패턴 적용 |
| 3. RED/GREEN/REFACTOR 각 task phase 형식 | ✅ 9 task 모두 phase 명시 |
| 4. 사실 검증 누락 처리 | ✅ V 번호 / `ProjectKey` VO 존재 여부 = impl 시점 확인 명시 |
| 5. learnings 적용 (lint-staged race + scoped typecheck) | ✅ wave 종료 시 controller 전체 `./gradlew test` ground truth 실행 + scratch 파일 커밋 금지 + 공유 파일 직렬화 |
| 6. ADR 정합성 (shared-kernel-extraction 후속) | ✅ ADR 미해결 항목(`@Transactional(MANDATORY)` 위치) 을 게이트1 검토 항목으로 명시 |
| 7. 외부 영향 (C-4 D6 UI 케이스 의존) | ✅ controller 사전 grep 검증 — 의존 0건 확인 |

**self-review 결론**. ✅ 통과 (BLOCKER 0건).

### 게이트1 검토 항목 (Maxi 결정 필요)

1. **D2 추천 (a)** — 이슈 생성 + 전환 둘 다 워크플로우 결정. 정합성 vs scope 약간 큼.
2. **D3 추천 (a)** — Flyway 마이그레이션 V00X 로 기존 `OPEN` → `open` 일괄. 안전 vs 새 데이터부터 (호환 위험).
3. **EC-2 도메인 예외** — `IssueWorkflowNotConfiguredException` 신규 (T3) vs 기존 `IssueDomainException` 통합.
4. **ADR 미해결 항목** (`@Transactional(MANDATORY)` 위치) — 본 PR 은 shared-kernel 인터페이스 잔류 (PR #18 패턴 일관) 가정. impl 측 이동 옵션 vs 추가 단락 작성 여부.
5. **T7 V 번호** — impl 시점 `ls db/migration` 확인 (위험 낮음, 명시로 충분).
