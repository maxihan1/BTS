# [backend] project-workflow — 전환 ID + 전역 전환 + 다중 전환

> 티어: T3
> slug: backend-workflow-transition-id-multi-global
> type: backend
> agent: backend-engineer
> 생성: 2026-08-20

## Brief

FR-WF-05. 워크플로우 편집기 로드맵(`~/.claude/plans/cozy-hatching-otter.md`) **PR 4**.
선행은 PR 3(#393 `00949bb1f`) · PR 2(#392 `213e8a317`).

전환(transition)의 identity 를 `(from, to)` 2튜플에서 **전환 ID(UUID)** 로 옮긴다.
그래야 ① 같은 두 상태 사이에 이름이 다른 전환을 둘 이상 만들 수 있고(다중 전환)
② 시작 상태가 없는 전환(전역 전환)을 표현할 수 있다.
설계 근거는 이미 승인된 ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` (D1~D4) 이고,
이 PR 은 그 ADR 의 **구현**이다 — 새 ADR 을 만들지 않는다.

### 범위 (로드맵 PR 4 절)

- `V207__transitions_multi_and_global.sql` — `UNIQUE(workflow_id, from_state_id, to_state_id)` DROP ·
  `from_status_id` NULL 허용 · `workflow_statuses` 참조로 재지정
- `Workflow.of()` invariant — 「(from,to) 중복 금지」 제거 · 「전역 전환은 from 이 NULL」 규칙 추가
- `WorkflowTransition` 에 `id: UUID` 를 1급 식별자로 (기존 `key` 계산 프로퍼티는 하위호환용 유지)
- `WorkflowEngine.availableTransitions` 가 전역 전환을 포함
- shared-kernel `TransitionRequest`·`AvailableTransitionsResult` 에 `transitionId` 추가 (nullable)
- `POST/PUT/DELETE /api/v1/workflows/{key}/transitions` CRUD

### red-first 3종 (로드맵이 지정)

1. 같은 (from, to) 에 이름이 다른 전환 2개를 만들 수 있는가
2. 전역 전환이 모든 상태에서 후보로 나오는가
3. `toStatusKey` 만으로 호출했을 때 후보가 2개면 409 인가

### classify 결과

`type=backend · agent=backend-engineer · tier=T3(지정) · primary_bc=project-workflow`.
**classify 의 자동 tier 는 T1** 이었다 — `classify-task.ts:517` 은 `--tier` 미지정 시 `DEFAULT_TIER`
를 쓰고 실측 티어는 `detect-tier.ts` 가 변경 경로에서 따로 낸다(판정 규칙 ②). 마이그레이션·
shared-kernel 표면이 확실하므로 **T3 를 지정 선언**했다.

### 선행 PR 이 물려준 제약 (메모리 `fr-wf-04-workflow-crud-backend-done`)

- 워크플로우 상태를 **원시 SQL 로 심지 마라** — 픽스처 헬퍼 사용.
  `RawWorkflowStateInsertGuardTest` 가 재유입을 막고 `WorkflowStatusFixtureParityTest` 가
  두 BC 헬퍼의 본문을 대조한다.
- 쓰기 경로 끝에 **`WorkflowCache.invalidate(workflowKey)`** 필수.
  `CacheInvalidationCoverageTest` 가 차집합으로 누락을 잡는다.
- jOOQ 레코드 접근은 `Record.required(field)` (`!!` 금지). 반환 타입은 `T & Any`.
- 권한 축은 `isSystemAdmin` 하나. `WorkflowDefinitionPermission` 4종은 감사·에러 메시지용 구분이다.

### learnings 발췌 (체인 전체 주입)

- **「백엔드 완비」 판정은 컨트롤러의 HTTP 매핑을 세어서 한다** — 파일 존재 ≠ 기능 존재 (2026-07-17).
- **Zod 응답 스키마 강화가 산재 인라인 mock 을 깬다** (2026-05-30). 응답에 `transitionId`·`kind` 를
  더할 때 PR 2 가 세운 「읽기 API 응답 형태 불변 · 새 필드는 추가만」 계약을 지킨다.
  `apps/web` 의 `api/workflows.ts` Zod · `mocks/workflow-fixtures.ts` · e2e 영향 확인.
- `[[jooq-init-codegen-mirror]]` 마이그레이션으로 컬럼을 더하면 `init_codegen.sql` 에도 미러해야
  jOOQ 상수가 생성된다.
- `[[bts-cross-bc-test-migration]]` project-workflow 통합테스트는 issue-tracking 마이그레이션을
  `testRuntimeOnly` 로 의존한다.
- `[[permission-assert-before-existence-makes-403-lie]]` 권한 단언을 존재 확인보다 먼저 두면
  403/404 의미가 뒤집힌다.

## 도메인 정리

**BC.** project-workflow (단일). shared-kernel 은 계약 **추가만** — `TransitionRequest.transitionId: UUID?`
nullable 추가라 기존 호출부 컴파일이 깨지지 않는다.

**영향 엔티티.**

| 엔티티 | 변경 |
|---|---|
| `WorkflowTransition` | `id: UUID` 1급 식별자 추가 · `kind` 추가 · `fromStateKey` 가 nullable 이 된다 |
| `Workflow` | `of()` invariant 5개 중 5번(전환 (from,to) 중복 금지)을 새 규칙 2개로 교체 |
| `workflow_transitions` (테이블) | V207 — UNIQUE 해제 · `kind` · `from_status_id`/`to_status_id`/`display_order` 추가 |
| `WorkflowKeyResolverImpl` | 시작 상태 해석이 `minByOrNull(displayOrder)` → INITIAL 전환 |
| `PostActionTransitionResolver` | (from,to) 해석이 전환 ID 기준으로 바뀐다. 구 경로는 유지 |

**새 용어 0건.** 「전환」·「전역 전환」·「최초 전환」은 이미 ADR `2026-08-18-workflow-transition-id-identity.md`
와 로드맵이 쓰는 말이고, `glossary.md` 의 「전환」 항목이 정본이다. `glossary.md` 갱신 불필요.

**기존 결정 충돌 — 없음.** 구 ADR `2026-05-28-workflow-transition-identity-policy.md`(2튜플 identity)는
`2026-08-18-workflow-transition-id-identity.md` 가 **이미 supersede** 했고, 그 구 ADR 이 §대안 채택 조건에
적어 둔 탈출구를 발동하는 것이다. 이 PR 은 새 ADR 을 만들지 않는다.

**관련 ADR.**

- `docs/adr/2026-08-18-workflow-transition-id-identity.md` — 이 PR 의 설계 정본 (D1~D4)
- `docs/adr/2026-08-18-workflow-global-status-catalog.md` — 전환이 참조할 `workflow_statuses`
- `docs/adr/2026-05-26-workflow-transition-port-result-sealed.md` — sealed Result port (**유효** ·
  결정 D-2 가 이 ADR 을 지키려고 예외 경로를 택했다)
- `docs/adr/2026-05-28-workflow-transition-identity-policy.md` — supersede 됨

## 스펙

정본. **`docs/specs/2026-08-20-backend-workflow-transition-id-multi-global.md`** (9섹션)

핵심 시나리오 3줄.

1. 같은 상태쌍에 이름이 다른 전환을 여럿 두고, 각각을 `transitionId` 로 지목해 실행한다.
2. `kind=GLOBAL` 전환은 현재 상태와 무관하게 후보에 오른다(자기 자신 제외). `kind=INITIAL` 은
   이슈 생성 진입 상태를 명시해, 관리자가 상태 순서를 바꿔도 생성 동작이 흔들리지 않게 한다.
3. `transitionId` 없이 `toStatusKey` 만으로 호출했을 때 후보가 둘이면 **409 `AMBIGUOUS_TRANSITION`**
   과 후보 목록을 돌려준다 — 조용히 아무거나 고르지 않는다.

### ★ 게이트 1 확인 대상 결정 2건

| 결정 | 내용 | 왜 확인이 필요한가 |
|---|---|---|
| **D-1** | 기존 `POST /{key}/transitions`(전환 계획)를 `POST /{key}/transitions/plan` 으로 옮기고 `/transitions` 를 전환 정의 CRUD 에 준다 | **API 계약 변경**이다. 지금 실호출부가 0(프론트 `src/api/*.ts` 에 없음 · MSW 목 1건 · MVC 테스트 1건)이라 비용은 작지만, 계약을 옮기는 판단은 사람이 한다 |
| **D-2** | 모호 전환 409 를 `TransitionResult` sealed 확장이 아니라 **`AmbiguousTransitionException` + 핸들러**로 낸다 | sealed 에 케이스를 더하면 `IssueApplicationService.kt:1175` 의 exhaustive `when` 이 깨져 **issue-tracking 이 컴파일 실패**한다 = cross-BC 변경. 로드맵은 issue-tracking 대응을 PR 7 에 배정했다 |

## Sanity Check

**gap 4건 발견 — 2건 흡수 · 2건 게이트 1 확인 대상.** 남은 미확정 0건.

- **G1 ❓** 로드맵 PR 4 절 요약에 **INITIAL(최초 전환)이 빠져 있었다**. ADR §D2 와
  `docs/plan/product/project-workflow.md §2.5` 제목은 포함한다 → **범위에 포함**. 로드맵 요약이
  축약이고 ADR 이 정본이다.
- **G2 ❓** `POST /{key}/transitions` **경로 충돌** → 결정 D-1. 게이트 1 확인 대상.
- **G3 ❓** 모호 전환 409 의 sealed 확장이 **cross-BC 컴파일 파괴** → 결정 D-2 (예외 경로).
  게이트 1 확인 대상 + 통합테스트로 실측(예외가 issue 경로에서도 409 로 나오는지).
- **G4 ❓** `fromStateKey` nullable 화가 프론트 Zod 를 깰 수 있다 → 이 PR 은 `apps/web` 0파일이므로
  **PR 8 이 물려받을 제약**으로 남긴다.

## Plan

> 경로는 모두 repo 루트 기준. `PW = backend/modules/project-workflow/src`,
> `SK = backend/modules/shared-kernel/src` 로 줄여 적는다.

### Task 1. 모호 전환 409 계약 — 예외 신설 + 두 경로 실측 (★결정 D-2 판정)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/exception/WorkflowExceptions.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/WorkflowExceptionHandler.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowExceptionHandlerAmbiguousTest.kt`,
  `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/AmbiguousTransitionStatusCodeIntegrationTest.kt`]
- depends-on: []

**RED**:
- 파일 ①: `.../workflow/web/WorkflowExceptionHandlerAmbiguousTest.kt`
- 파일 ②: `.../issue/integration/AmbiguousTransitionStatusCodeIntegrationTest.kt`
- 테스트:
  ```kotlin
  // ① project-workflow 자기 경로
  @Test fun `모호 전환 예외는 409 AMBIGUOUS_TRANSITION 과 후보 목록을 낸다`()
  // ② ★판정 — issue-tracking 컨트롤러 경로에서도 409 인가
  @Test fun `issue 전환 경로에서 던져진 AmbiguousTransitionException 이 500 이 아니라 409 로 나온다`()
  ```
- 실패 메시지 (예상): `AmbiguousTransitionException` 클래스 없음

**GREEN**:
- `WorkflowExceptions.kt` 에 `AmbiguousTransitionException(val workflowKey: String, val candidates: List<TransitionCandidate>)` 추가
- `WorkflowExceptionHandler` 에 `@ExceptionHandler` 1개 — 409 + `errorCode="AMBIGUOUS_TRANSITION"` + `candidates` 배열.
  **선례를 그대로 따른다** — `WorkflowSchemeExceptionHandler` 의 `SchemeInUseException(usedByProjects) → 409`

**REFACTOR**: 후보 DTO 를 `web/dto` 로 승격할지 판단. 핸들러 KDoc 에 409 목록 1행 추가

**검증**: `./gradlew :modules:project-workflow:test --tests '*AmbiguousTest' :modules:issue-tracking:test --tests '*AmbiguousTransitionStatusCode*'`

**★ 이 task 가 BLOCKER 판정 지점이다.** ②가 500 으로 나오면 `@RestControllerAdvice` 스캔 범위가
BC 경계를 넘지 못한다는 뜻이고, 그 경우 **결정 D-2 를 폐기하고 게이트 1 로 되돌아간다**
(대안 — `TransitionResult` sealed 확장 + issue-tracking `when` 분기 추가, 단 N2 예외 승인 필요).
구현을 계속 진행하지 않는다.

### Task 2. V207 마이그레이션 + jOOQ 코드젠 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V207__transitions_multi_and_global.sql`,
  `backend/modules/project-workflow/src/main/resources/db/codegen/init_codegen.sql`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V207MigrationTest.kt`]
- depends-on: []

**RED**:
- 파일: `.../workflow/db/V207MigrationTest.kt` (`V200MigrationTest.kt` 패턴 · 이미지 `quay.io/tembo/pg16-pgmq:latest`)
- 테스트:
  ```kotlin
  @Test fun `UNIQUE(workflow_id, from_state_id, to_state_id) 가 사라져 같은 상태쌍 2행이 들어간다`()
  @Test fun `kind CHECK 가 NORMAL GLOBAL INITIAL 만 허용한다`()
  @Test fun `INITIAL 은 워크플로우당 1개 — 2건째 INSERT 가 유니크 위반`()
  @Test fun `kind=NORMAL 인데 from_status_id 가 NULL 이면 CHECK 위반`()
  @Test fun `INITIAL 백필 도착지가 백필 전 display_order 최소 상태와 같다`()
  @Test fun `from_state_id·to_state_id 와 workflow_states 는 살아 있다`()   // N4
  ```
- 실패 메시지 (예상): `column "kind" does not exist`

**GREEN**: `V207__transitions_multi_and_global.sql` — spec §데이터 모델 변경의 ①~⑧.
백필은 대응 `workflow_statuses` 행이 없으면 `RAISE EXCEPTION` (V204 가 세운 유일성 가드 관례).

**REFACTOR**: SQL 각 블록에 한국어 주석 1줄 — 왜 DROP 하지 않는지(N4)를 명시

**검증**: `./gradlew :modules:project-workflow:test --tests V207MigrationTest` ·
`:modules:project-workflow:generateJooq` 후 **생성물 커밋**

**함정**. `init_codegen.sql` 미러를 빠뜨리면 jOOQ 상수가 안 생겨 Task 4 의 리포지토리가
**컴파일되지 않는다** (`[[jooq-init-codegen-mirror]]`).

### Task 3. `WorkflowTransition` 확장 + `Workflow.of()` invariant 교체

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/WorkflowTransition.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/Workflow.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/WorkflowTest.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/WorkflowTransitionTest.kt`]
- depends-on: []

**RED**:
- 테스트:
  ```kotlin
  @Test fun `같은 (from,to) 에 이름이 다른 전환 2개를 of() 가 받는다`()          // C1 도메인 층
  @Test fun `GLOBAL 전환은 fromStateKey 가 null 이어야 한다 — 값이 있으면 거부`()
  @Test fun `INITIAL 전환은 워크플로우당 1개 — 2개면 거부`()
  @Test fun `NORMAL 전환의 fromStateKey 가 null 이면 거부`()
  @Test fun `key 계산 프로퍼티는 그대로 from__to 를 낸다`()                     // 하위호환
  ```
- 실패 메시지 (예상): `duplicate transition (from, to) combinations found`

**GREEN**:
- `WorkflowTransition` 에 `id: UUID`·`kind: TransitionKind` 추가, `fromStateKey: String?` 로 완화.
  `key` 는 `"${fromStateKey ?: "*"}__$toStateKey"` 로 **계속 계산**한다 (하위호환)
- `Workflow.of()` invariant 5번을 삭제하고 GLOBAL/INITIAL 규칙 2개로 **교체**.
  3·4번(from/to 가 states 안) 은 `fromStateKey != null` 일 때만 검사

**REFACTOR**: `TransitionKind` enum 을 별도 파일로. `Workflow.of()` KDoc 의 invariant 목록 갱신 (5개 → 6개)

**검증**: `./gradlew :modules:project-workflow:test --tests 'com.bts.workflow.domain.*'`

**근거**. ADR §D4 — 「모호하면 런타임이 아니라 정의 시점에 막는다」는 유지된다.

### Task 4. 리포지토리 — `workflow_statuses` 참조 + `id`·`kind` 매핑

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowRepository.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowWriteRepository.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/DefaultWorkflowDefinitionRepository.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/repository/WorkflowRepositoryTransitionTest.kt`]
- depends-on: [2, 3]

**RED**:
- 테스트:
  ```kotlin
  @Test fun `읽기가 transition 의 id 와 kind 를 채운다`()
  @Test fun `GLOBAL 전환은 fromStateKey 가 null 로 읽힌다`()
  @Test fun `같은 상태쌍 2행이 각각 다른 id 로 읽힌다`()
  ```
- 실패 메시지 (예상): `Unresolved reference: KIND`

**GREEN**: 읽기 쿼리를 `workflow_statuses` join 으로 재지정하고 `id`·`kind`·`display_order` 를 매핑.
**`Record.required(field)` 를 쓴다 — `!!` 금지, 반환 타입 `T & Any`** (`RecordAccess.kt`).

**REFACTOR**: 전환 매핑 함수 1개로 추출 (읽기 3곳이 같은 매핑을 반복하지 않게)

**검증**: `./gradlew :modules:project-workflow:test --tests '*RepositoryTransition*'`

**함정**. 픽스처는 `testsupport/WorkflowStatusFixture.kt` 헬퍼만 쓴다 — 원시 SQL 로 심으면
`RawWorkflowStateInsertGuardTest` 가 red 를 낸다 (N6).

### Task 5. `availableTransitions` 가 GLOBAL 전환을 후보에 넣는다 (C2)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/WorkflowEngine.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/engine/WorkflowEngineGlobalTransitionTest.kt`]
- depends-on: [3, 4]

**RED**:
- 테스트:
  ```kotlin
  @Test fun `GLOBAL 전환이 어느 상태에서도 후보에 나온다`()
  @Test fun `GLOBAL 전환의 도착지가 현재 상태와 같으면 후보에서 빠진다`()   // E1
  @Test fun `INITIAL 전환은 availableTransitions 후보에 절대 안 나온다`()
  ```
- 실패 메시지 (예상): 후보 목록에 GLOBAL 전환 없음 (`expected 2 but was 1`)

**GREEN**: `WorkflowEngine.kt:208` 의 `filter { it.fromStateKey == req.fromStateKey }` 를
`NORMAL(from 일치) + GLOBAL(to != 현재 상태)` 합집합으로. **INITIAL 은 항상 제외**한다.

**REFACTOR**: 후보 산출을 `private fun candidatesFor(...)` 로 추출

**검증**: `./gradlew :modules:project-workflow:test --tests '*GlobalTransition*'`

### Task 6. `transitionId` 우선 실행 + 모호 감지 + shared-kernel 계약 추가 (C3)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/TransitionRequest.kt`,
  `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/AvailableTransitionsResult.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/WorkflowEngine.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/engine/WorkflowEngineAmbiguousTest.kt`,
  `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/workflow/TransitionRequestTest.kt`]
- depends-on: [1, 3, 4]

**RED**:
- 테스트:
  ```kotlin
  @Test fun `후보가 2개인데 transitionId 가 없으면 AmbiguousTransitionException`()   // C3
  @Test fun `transitionId 를 주면 그 전환으로 실행된다`()
  @Test fun `후보가 1개면 transitionId 없이도 종전대로 실행된다`()                  // S5 하위호환
  @Test fun `transitionId 를 안 넣은 기존 생성자 호출이 그대로 컴파일된다`()        // N2
  ```
- 실패 메시지 (예상): 후보 2개인데 예외 없이 첫 번째가 선택됨

**GREEN**:
- `TransitionRequest` 에 `val transitionId: UUID? = null` **기본값과 함께** 추가 (기존 호출부 무변경)
- `AvailableTransitionView` 에 `transitionId: UUID?`·`kind: String?` 추가 (같은 이유로 기본값)
- `WorkflowEngine.resolveTransition` — `transitionId` 우선 → 없으면 `(from,to)` 후보 산출 →
  0개면 종전 `WorkflowNotFoundException`(E11) · 2개 이상이면 `AmbiguousTransitionException`

**REFACTOR**: `resolveTransition` KDoc 을 새 identity 정책으로 교체하고 구 ADR 링크를 새 ADR 로 갱신

**검증**: `./gradlew :modules:shared-kernel:test :modules:project-workflow:test --tests '*Ambiguous*'`

**함정**. 새 필드는 **반드시 기본값**을 준다. 기본값이 없으면 `TransitionRequest(...)` 호출부
(issue-tracking·automation)가 컴파일 실패해 N2 가 깨진다.

### Task 7. 시작 상태 해석을 INITIAL 전환으로 (C4)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/adapter/inbound/WorkflowKeyResolverImpl.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/WorkflowStartStateInitialTest.kt`]
- depends-on: [3, 4]

**RED**:
- 테스트:
  ```kotlin
  @Test fun `상태 display_order 를 바꿔도 시작 상태가 안 바뀐다`()               // C4
  @Test fun `INITIAL 전환이 가리키는 상태가 시작 상태다`()
  @Test fun `INITIAL 전환이 없으면 종전 minByOrNull 폴백`()                     // 백필 누락 방어
  ```
- 실패 메시지 (예상): 순서를 바꾸자 시작 상태가 따라 바뀜

**GREEN**: `WorkflowKeyResolverImpl.kt:83`·`:123` 의 `states.minByOrNull { it.displayOrder }` 를
`transitions.firstOrNull { it.kind == INITIAL }?.toStateKey` 우선, 없으면 종전 폴백

**REFACTOR**: 두 곳의 중복 해석을 `private fun resolveStartState(workflow)` 로 통합

**검증**: `./gradlew :modules:project-workflow:test --tests '*StartStateInitial*'`

**근거**. ADR §맥락 — 「관리자가 순서를 바꾸는 순간 이슈 생성 상태가 조용히 바뀐다」가 이 task 가 닫는 결함이다.

### Task 8. 전환 정의 CRUD + 경로 이동 (C1 · ★결정 D-1)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/WorkflowController.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/dto/WorkflowCommandDtos.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/WorkflowCommandService.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/command/WorkflowCommands.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowWriteRepository.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowControllerMvcTest.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/TransitionCrudMvcTest.kt`,
  `apps/web/src/mocks/workflow-handlers.ts`]
- depends-on: [2, 3, 4]

**RED**:
- 테스트:
  ```kotlin
  @Test fun `POST 로 같은 상태쌍에 이름이 다른 전환을 하나 더 만든다`()          // C1
  @Test fun `kind=GLOBAL 인데 fromStatusKey 를 보내면 400`()                    // E3
  @Test fun `kind=NORMAL 인데 fromStatusKey 가 없으면 400`()                    // E4
  @Test fun `INITIAL 전환 DELETE 는 409`()                                      // E5
  @Test fun `다른 워크플로우의 transitionId 로 PUT 하면 404`()                  // E9
  @Test fun `권한 없는 액터는 403 — 존재하지 않는 워크플로우는 404`()           // 검사 순서
  @Test fun `쓰기 3종 모두 WorkflowCache.invalidate 를 부른다`()                // N5
  @Test fun `전환 계획은 POST /{key}/transitions/plan 으로 옮겨졌다`()          // D-1
  ```
- 실패 메시지 (예상): `POST /transitions` 가 `TransitionPlan` 을 돌려줌 (CRUD 아님)

**GREEN**:
- 기존 `plan()` 을 `@PostMapping("/{key}/transitions/plan")` 으로 이동
- `POST /{key}/transitions`(201) · `PUT /{key}/transitions/{transitionId}`(200) ·
  `DELETE /{key}/transitions/{transitionId}`(204) 신설
- **권한 단언을 존재 확인 앞에 두지 않는다** (`[[permission-assert-before-existence-makes-403-lie]]`)
- 쓰기 3종 끝에 `WorkflowCache.invalidate(workflowKey)`
- `apps/web/src/mocks/workflow-handlers.ts:30` 의 목 경로를 `/transitions/plan` 으로 (**이 1줄만** — N3 예외)

**REFACTOR**: CRUD 3종의 권한·존재 확인 전처리를 `private fun requireEditable(key)` 로 통합

**검증**: `./gradlew :modules:project-workflow:test --tests '*MvcTest'` ·
`pnpm --filter web test -- workflow` (목 경로 변경이 프론트 테스트를 깨지 않는지)

### Task 9. 읽기 응답 계약 스냅샷 — 형태 불변 + 새 필드 추가만 (C5)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/dto/WorkflowDto.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowReadContractTest.kt`]
- depends-on: [4, 8]

**RED**:
- 테스트:
  ```kotlin
  @Test fun `GET workflows-key 응답 최상위 키가 정확히 key name description states transitions 5개다`()
  @Test fun `transitions 원소에 id 와 kind 가 추가됐고 기존 필드는 그대로다`()
  @Test fun `GLOBAL 전환의 fromStateKey 는 null 로 직렬화된다`()               // G4 — PR 8 이 물려받을 제약
  ```
- 실패 메시지 (예상): `id` 필드 없음

**GREEN**: `WorkflowDto` 의 전환 DTO 에 `id`·`kind` 추가. **필드 삭제·이름 변경 0**

**REFACTOR**: 계약 테스트에 「이 테스트가 깨지면 프론트가 깨진다」 주석 1줄

**검증**: `./gradlew :modules:project-workflow:test --tests '*ReadContract*'`

## Plan 메타

- **task 수** 9 · **예상 wave** 4 (`w1` 1·2·3 → `w2` 4 → `w3` 5·6·7·8 → `w4` 9)
- **구현 규율** TDD red-first. T3 이므로 `test:` 커밋이 `feat:` 보다 **먼저** 대조된다
- **추가 검증** `./gradlew :modules:project-workflow:test :modules:shared-kernel:test ktlintCheck detekt` ·
  `pnpm test:workflow`(판별식 406) · `node scripts/build-doc-index.mjs --check` ·
  `bash scripts/verify-master-plan.sh`
- **선행 판정** Task 1 ②가 red 로 남으면 **구현을 멈추고 게이트 1 로 되돌아간다**. 결정 D-2 가 무효다
- **FR 동기화** `docs/plan/product/project-workflow.md §2.5` 의 D1~D5 체크박스를 이 PR 에서 `[x]` 로
  (`docs/rules/fr-sync-checklist.md` 9항목)

## 리뷰 결과 (← /bts-review-plan 채움)
