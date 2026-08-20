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
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/AmbiguousTransitionExceptionHandler.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowExceptionHandlerAmbiguousTest.kt`,
  `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/AmbiguousTransitionStatusCodeIntegrationTest.kt`]
- depends-on: []

**★ 1차 시도 실측 결과 (2026-08-20) — 판정 ②가 500. 그러나 D-2 는 유효하다.**
plan 이 적은 폐기 조건은 「`@RestControllerAdvice` 스캔 범위가 BC 경계를 넘지 못한다」였는데
**그 가설이 거짓으로 실측됐다.** 스캔은 정상이고 원인은 **advice 등록 순서**다.

```
IssueExceptionHandler.kt:98   @RestControllerAdvice(basePackages = ["com.bts.issue.adapter.inbound.rest"])
IssueExceptionHandler.kt:763  @ExceptionHandler(Exception::class)   ← catch-all, @Order 없음
```
Spring 의 `ExceptionHandlerExceptionResolver` 는 advice 를 등록 순서대로 훑어 **처음 매칭되는 것**을
쓴다. 둘 다 `@Order` 가 없어 클래스패스 순서가 승자를 정했다. 진단 실험에서
`@Order(HIGHEST_PRECEDENCE)` 를 주자 ②·③ 모두 409 로 PASS 했다.

**채택 — 단일 예외 전용 advice 신설.** `WorkflowExceptionHandler` 에 `@Order` 를 붙이는 안은
그 advice 가 예외 **8종**을 잡는 다중 advice 라 `WorkflowNotFoundException` 까지
`WorkflowSchemeExceptionHandler`(`basePackages = ["com.bts.workflow.scheme"]`)에서 빼앗고,
스킴 404 응답 형태가 `ProblemDetail` 에서 `{error:{code,message}}` 로 **조용히 바뀐다.**
그 회귀는 슬라이스 테스트가 잡지 못한다.

**선례를 그대로 따른다** — `issue-tracking/.../project/archive/web/ProjectArchivedExceptionHandler.kt:40-41`
이 정확히 같은 문제(catch-all 삼킴)를 **단일 예외 전용 advice + `@Order(Ordered.HIGHEST_PRECEDENCE)`**
로 이미 풀었고, 그 KDoc 이 위험 원천을 명시한다.

**RED**:
- 파일 ①: `.../workflow/web/WorkflowExceptionHandlerAmbiguousTest.kt`
- 파일 ②: `.../issue/integration/AmbiguousTransitionStatusCodeIntegrationTest.kt`
- 테스트:
  ```kotlin
  // ① project-workflow 자기 경로
  @Test fun `모호 전환 예외는 409 AMBIGUOUS_TRANSITION 과 후보 목록을 낸다`()
  // ② ★판정 — issue-tracking 컨트롤러 경로에서도 409 인가
  @Test fun `issue 전환 경로에서 던져진 AmbiguousTransitionException 이 500 이 아니라 409 로 나온다`()
  // ③ ★판정 보강 (리뷰 T5) — 트랜잭션이 rollback-only 로 마킹되는가
  @Test fun `모호 전환 예외 뒤 호출자 트랜잭션이 rollback-only 로 마킹된다`()
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

**리뷰 T5 반영.** 판정 기준을 「409 가 나오는가」가 아니라 **「409 가 나오고 호출자 트랜잭션이
rollback-only 로 마킹되는가」**로 넓혔다. `WorkflowTransitionPort.plan` 이
`@Transactional(propagation = MANDATORY)` 라 예외가 공유 트랜잭션을 오염시킨다
(`[[workflowstatecatalog-mandatory-rollback-poison]]`). 지금은 `IssueApplicationService.kt:1174`
가 catch 폴백 없이 전부 throw 하므로 안전하지만, ③을 남겨 두어야 **미래에 폴백을 넣는 사람이
red 로 걸린다.**

### Task 2. V207 마이그레이션 + jOOQ 코드젠 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V207__transitions_multi_and_global.sql`,
  `backend/modules/project-workflow/src/main/resources/db/codegen/init_codegen.sql`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V207MigrationTest.kt`]
- depends-on: []

**RED**:
- 파일: `.../workflow/db/V207MigrationTest.kt` (**선례는 `V203ToV206MigrationTest.kt`** — 같은 대역·같은
  카탈로그 스키마를 다룬다. 리뷰 T6 반영 · 이미지 `quay.io/tembo/pg16-pgmq:latest`)
- 테스트:
  ```kotlin
  @Test fun `UNIQUE(workflow_id, from_state_id, to_state_id) 가 사라져 같은 상태쌍 2행이 들어간다`()
  @Test fun `kind CHECK 가 NORMAL GLOBAL INITIAL 만 허용한다`()
  @Test fun `INITIAL 은 워크플로우당 1개 — 2건째 INSERT 가 유니크 위반`()
  @Test fun `kind=NORMAL 인데 from_status_id 가 NULL 이면 CHECK 위반`()
  @Test fun `INITIAL 백필 도착지가 백필 전 display_order 최소 상태와 같다`()
  @Test fun `from_state_id·to_state_id 와 workflow_states 는 살아 있다`()   // N4
  @Test fun `display_order 가 워크플로우 안에서 1..n 로 중복 없이 채워진다`()  // 리뷰 T2
  ```
- 실패 메시지 (예상): `column "kind" does not exist`

**GREEN**: `V207__transitions_multi_and_global.sql` — spec §데이터 모델 변경의 ①~⑧.
백필은 대응 `workflow_statuses` 행이 없으면 `RAISE EXCEPTION` (V204 가 세운 유일성 가드 관례).

**리뷰 T2 반영 — `display_order` 를 백필한다.** `NOT NULL DEFAULT 0` 만 두면 기존 전환이 전부 0 이 되어
PR 8 의 편집기가 **임의 순서로 그린다**(화면이 없는 지금은 안 보이는 조용한 실패).
```sql
UPDATE workflow_transitions t SET display_order = s.rn
FROM (SELECT id, row_number() OVER (PARTITION BY workflow_id ORDER BY created_at, name) AS rn
      FROM workflow_transitions) s
WHERE t.id = s.id;
```

**REFACTOR**: SQL 각 블록에 한국어 주석 1줄 — 왜 DROP 하지 않는지(N4)를 명시

**검증**: `./gradlew :modules:project-workflow:test --tests V207MigrationTest` ·
`:modules:project-workflow:generateJooq` EXIT=0 확인

**★ 정정 (2026-08-20 실측).** 이 절은 원래 「생성물 **커밋**」이라 적었으나 **이 저장소는 jOOQ
생성물을 추적하지 않는다** — `.gitignore:21` 이 `**/src/generated/jooq/` 를 제외하고
`git ls-files | grep -c generated/jooq` 는 **0** 이다. `DATA.md §5` 의 「생성물은 git 에 커밋」과
`.gitignore` 가 **서로를 검사하지 않는 두 정본**이라 어긋나 있다(문서 부채 후보).
확인은 커밋이 아니라 **생성 성공 + 생성물에 새 컬럼 상수가 실제로 나오는지**로 한다.

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
  `key` 는 **계속 계산**한다 (하위호환). **리뷰 T4 반영 — `*` 를 쓰지 않는다.**
  `key` 는 URL 경로 세그먼트로 소비된다(`.../transitions/{transitionKey}/post-actions` ·
  MSW 목 `apps/web/src/mocks/post-action-handlers.ts:60`). `*` 는 인코딩·매칭이 갈리므로
  `NORMAL` 은 종전대로 `"$fromStateKey__$toStateKey"`, `GLOBAL`·`INITIAL` 은
  `"${kind.name}__$toStateKey"` 로 **문자 클래스가 안전한 토큰**을 쓴다
- `Workflow.of()` invariant 5번을 삭제하고 GLOBAL/INITIAL 규칙 2개로 **교체**.
  3·4번(from/to 가 states 안) 은 `fromStateKey != null` 일 때만 검사

**REFACTOR**: `TransitionKind` enum 을 별도 파일로. `Workflow.of()` KDoc 의 invariant 목록 갱신 (5개 → 6개)

**검증**: `./gradlew :modules:project-workflow:test --tests 'com.bts.workflow.domain.*'`

**근거**. ADR §D4 — 「모호하면 런타임이 아니라 정의 시점에 막는다」는 유지된다.

### Task 10. 도메인 정책 정합 보정 — 갈라진 두 번째 정본을 닫는다

> **wave 1 실측이 만든 신규 task.** plan 작성 시점에 보이지 않던 것 3건을 닫는다.
> Task 4 의 선행이다 — 이것이 없으면 wave 2·3 의 검증 명령이 컴파일 단계에서 성립하지 않는다.

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/WorkflowTransition.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/dto/WorkflowDto.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/domain/WorkflowAggregateTest.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedServiceTest.kt`]
- depends-on: [3]

**RED**:
- 테스트:
  ```kotlin
  // YamlSeedServiceTest — 시나리오 5 를 뒤집는다
  @Test fun `같은 워크플로우 안에 from-to 가 같고 이름이 다른 전환 2개가 시드된다`()
  // WorkflowAggregateTest — 기존 호출부가 id 없이 그대로 컴파일된다 (회귀 가드)
  @Test fun `WorkflowTransition 을 id 없이 만들면 새 UUID 가 자동 부여된다`()
  ```
- 실패 메시지 (예상): `IllegalStateException: Workflow '...' has duplicate (from, to)=(TODO,DONE)`

**GREEN — 3건**

1. **★ 갈라진 두 번째 정본을 닫는다.** `YamlSeedService.validateTransitionUniqueness`(`:399-411`)가
   `error("Workflow '$key' has duplicate (from, to)=$dup")` 로 **여전히 (from,to) 중복을 막는다.**
   Task 3 이 `Workflow.of()` 에서 지운 바로 그 규칙의 **복제본**이다. 한쪽만 고치면 도메인 규칙이
   두 곳에 갈라진 채 하나만 바뀐 상태로 남는다 — 이 저장소가 반복해서 물린
   `[[two-lists-never-check-each-other]]` 양식이다. 함수와 호출부를 제거하고
   `YamlSeedServiceTest` 시나리오 5(`:376`)를 **수용 단언으로 뒤집는다.**
2. **`WorkflowTransition.id` 에 기본값을 준다** — `val id: UUID = UUID.randomUUID()`.
   현재 `id` 가 필수라 기존 호출부 **27곳**(test 25파일 · main 2파일)이 전부 컴파일되지 않는다.
   DB 도 `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`(V200:66) 이므로 도메인 기본값이 정합이다.
   **이 한 줄이 25개 파일의 일괄 수정을 없앤다** — 새 코드만 `id` 를 명시한다.
3. **`WorkflowDto.kt:92` 의 `fromStateKey` 를 `String?` 로** — Task 9 소유 파일이지만 이 1줄이
   없으면 project-workflow **main 이 wave 4 전까지 컴파일되지 않아** wave 2·3 의 검증 명령이
   전부 성립하지 않는다. 1줄만 앞당기고 나머지 DTO 작업은 Task 9 가 그대로 한다.

**REFACTOR**: `WorkflowAggregateTest.kt:152`·`:172` 의 죽은 테스트 2개를 **삭제**한다 —
삭제된 invariant(「(from,to) 조합 중복 금지」)를 단언하므로 컴파일을 고쳐도 의미상 반드시 실패한다.
대체 커버리지는 Task 3 이 만든 `WorkflowTest.kt` 가 이미 제공한다(같은 커밋에서 그 사실을 주석으로 남긴다).

**검증**: `./gradlew :modules:project-workflow:compileKotlin` EXIT=0 (이것이 이 task 의 1차 성공 기준) ·
`./gradlew :modules:project-workflow:test --tests '*YamlSeedServiceTest*' --tests '*WorkflowAggregateTest*'`

**범위 근거.** spec F2 는 「같은 상태쌍에 전환을 여럿 둔다」이고 ①은 그 F2 가 시드 경로에서
성립하도록 만드는 **누락 보정**이다. 범위 확대가 아니다 — 고치지 않으면 YAML 로 시드되는 표준
워크플로우에서만 F2 가 거짓이 되고, 로드맵 PR 6·10 의 「기본값 복원」이 YAML 을 소스로 쓰므로
그 지점에서 다시 터진다.

### Task 4. 리포지토리 — `workflow_statuses` 참조 + `id`·`kind` 매핑

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowRepository.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowWriteRepository.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/DefaultWorkflowDefinitionRepository.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/WorkflowEngine.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/repository/WorkflowRepositoryTransitionTest.kt`]
- depends-on: [2, 3, 10]

**★ `WorkflowEngine.kt` 는 `:214` 타입 보정 1줄만.** 후보 산출 로직은 Task 5·6 의 몫이다.
Task 10 이 `WorkflowDto.kt` 1줄을 앞당긴 것과 같은 이유 — 이 1줄이 없으면 모듈 main 이
wave 3 전까지 컴파일되지 않아 **이 task 자신의 검증 명령이 성립하지 않는다.**

**★ 이 task 가 컴파일을 EXIT=0 으로 되돌리는 지점이다.** 복구 직후 **가장 먼저** 아래를 돌려라 —
wave 1 의 Task 10 이 만든 테스트가 **한 번도 실행된 적이 없다**(컴파일 차단). 그 미확인 상태를
닫는 것이 이 task 의 인수 조건에 포함된다.
```
./gradlew :modules:project-workflow:test --tests '*YamlSeedServiceTest*' --tests '*WorkflowAggregateTest*'
./gradlew :modules:project-workflow:test --tests V207MigrationTest
./gradlew :modules:project-workflow:test --tests '*WorkflowExceptionHandlerAmbiguousTest*' --tests '*WorkflowSchemeExceptionHandlerTest*'
```

**★ 프로덕션에서만 터지는 함정 (Task 2 가 남긴 인수인계).** INITIAL 백필 행은 구 컬럼
`from_state_id`·`to_state_id` 가 NULL 이다. `WorkflowRepository.kt:224` 의
`this[WORKFLOW_TRANSITIONS.FROM_STATE_ID] as UUID` 가 여기서 터진다.
**테스트에서는 드러나지 않는다** — 빈 DB 에 마이그레이션이 돌면 백필 대상이 0행이기 때문이다.
읽기를 `workflow_statuses` 로 재지정하며 이 캐스팅을 함께 닫아라.

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

**REFACTOR**: 후보 산출을 `private fun candidatesFor(workflow, fromStateKey)` 로 추출.
**리뷰 T3 반영 — 이 함수는 Task 6 의 `resolveTransition` 도 함께 쓴다.** 두 벌로 두면 갈라진다(DRY).

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
- `WorkflowEngine.resolveTransition` — `transitionId` 우선 → 없으면 **Task 5 가 추출한
  `candidatesFor()` 로** 후보 산출 → 0개면 종전 `WorkflowNotFoundException`(E11) ·
  2개 이상이면 `AmbiguousTransitionException`
- **리뷰 T3 반영 — `INITIAL` 은 여기서도 후보에서 제외한다.** 안 빼면 `toStatusKey` 가 INITIAL 의
  도착지와 같을 때 **있지도 않은 모호성**으로 409 가 난다. 추가 테스트 —
  `@Test fun \`INITIAL 도착지와 같은 toStatusKey 로 호출해도 모호가 아니다\`()`

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

**검증**: `./gradlew :modules:project-workflow:test --tests '*MvcTest'`
**리뷰 T6 반영 — worktree 에서 `pnpm` 을 부르지 않는다.** 심볼릭 `node_modules` 때문에
`ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY` 로 죽는다(`[[worktree-pnpm-blocked-use-node-test-directly]]`).
MSW 목 1줄 변경의 검증은 **경로 문자열 grep** 으로 갈음한다 —
`grep -n "transitions/plan" apps/web/src/mocks/workflow-handlers.ts`

### Task 9. 읽기 응답 계약 스냅샷 — 형태 불변 + 새 필드 추가만 (C5)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/dto/WorkflowDto.kt`,
  `backend/modules/app/src/test/kotlin/com/bts/app/WorkflowReadContractProdBootTest.kt`]
- depends-on: [4, 8]

**★ 리뷰 T1 반영 — 계약 스냅샷은 `:app` 조립에서 만든다. MVC 슬라이스에 두지 않는다.**
실측 근거 — `WorkflowControllerMvcTest.kt:82` 가 `@EnableWebMvc` 로 MVC 를 직접 구성하고 `:128` 이
`ObjectMapper().registerKotlinModule()`(**`JavaTimeModule` 없음**)을 쓴다. 그 옆에 계약 테스트를 두면
조립 앱과 **다른 직렬화**를 계약으로 박제하고, PR 8 이 그 틀린 형식에 맞춰 Zod 를 고치면 프로덕션이
깨진다(`[[contract-snapshot-must-be-generated-in-assembly-not-slice]]` · 같은 사고가 `LocalDate` 배열로 1회).

조립 테스트의 두 함정도 함께 지킨다(`[[bts-assembly-test-pat-bearer-and-httpclient]]`).
① **PAT Bearer** 를 쓴다 — JWT 면 `MfaEnrollmentGateFilter` 에서 관리자 endpoint 가 전부 403 이 되어
기능 파손으로 오진한다. ② 베이스의 `TestRestTemplate` 대신 **JDK `java.net.http.HttpClient`** 로 원 응답을
관측한다(`:modules:app` 에 HttpComponents5 가 없어 401/403 본문을 못 읽는다).
③ `ProdAssemblyHttpTestBase` 를 **상속만** 하고 `@SpringBootTest`·`@ActiveProfiles`·`@DynamicPropertySource`
를 자체 선언하지 않는다(컨텍스트 캐시 키가 갈리면 9-BC 컨텍스트가 2회 부팅된다).

**RED**:
- 테스트:
  ```kotlin
  @Test fun `GET workflows-key 응답 최상위 키가 정확히 key name description states transitions 5개다`()
  @Test fun `transitions 원소에 id 와 kind 가 추가됐고 기존 필드는 그대로다`()
  @Test fun `GLOBAL 전환의 fromStateKey 는 null 로 직렬화된다`()               // G4 — PR 8 이 물려받을 제약
  @Test fun `timestamp 계열 필드가 배열이 아니라 ISO 문자열이다`()              // 리뷰 T1 회귀 가드
  ```
- 실패 메시지 (예상): `id` 필드 없음

**GREEN**: `WorkflowDto` 의 전환 DTO 에 `id`·`kind` 추가. **필드 삭제·이름 변경 0**

**REFACTOR**: 계약 테스트에 「이 테스트가 깨지면 프론트가 깨진다」 주석 1줄

**검증**: `./gradlew :modules:app:test --tests '*WorkflowReadContract*'`
**선행** — `:modules:app:test` 는 Testcontainers 가 아니라 실제 Postgres(5433)를 요구한다.
`docker-compose -f infra/docker-compose.dev.yml up -d postgres` 를 먼저 띄운다
(`[[worktree-pnpm-blocked-use-node-test-directly]]` 의 후반부).

## Plan 메타

- **task 수** 11 · **예상 wave** 6 (`w1` 1·2·3 → `w1.5` **10**(신규) → `w2` 4 → `w3` 5·7·8 → `w4` 6 → `w5` 9)
  Task 5·6 은 `WorkflowEngine.kt` 를 공유해 `files` 교집합으로 자동 직렬화된다.
- **구현 규율** TDD red-first. T3 이므로 `test:` 커밋이 `feat:` 보다 **먼저** 대조된다
- **추가 검증** `./gradlew :modules:project-workflow:test :modules:shared-kernel:test ktlintCheck detekt` ·
  `pnpm test:workflow`(판별식 406) · `node scripts/build-doc-index.mjs --check` ·
  `bash scripts/verify-master-plan.sh`
- **선행 판정** Task 1 ②가 red 로 남으면 **구현을 멈추고 게이트 1 로 되돌아간다**. 결정 D-2 가 무효다
- **FR 동기화** `docs/plan/product/project-workflow.md §2.5` 의 D1~D5 체크박스를 이 PR 에서 `[x]` 로
  (`docs/rules/fr-sync-checklist.md` 9항목)
- **리뷰 반영** 게이트 1 승인(2026-08-20). `/plan-eng-review` 의 T1~T6 을 위 task 본문에 흡수했다 —
  T1 Task 9 를 `:app` 조립으로 · T2 `display_order` 백필 · T3 `candidatesFor()` 공유 + INITIAL 양쪽 제외 ·
  T4 `key` 의 URL 안전 토큰 · T5 rollback-only 관측 · T6 선례 교체 + worktree `pnpm` 제거

## 리뷰 결과

**렌즈 1종 — `/plan-eng-review`** (`type=backend` · UI 미포함 → 분기표대로 1종).
아웃사이드 보이스(codex)는 `codex_reviews=disabled` 라 미실행 — 0종으로 조용히 통과시키지 않고 여기 명시한다.

**판정. ✅ 통과 · BLOCKER 0 · P1 1건 · P2 5건 · P3 1건.**
개별 발견마다 `AskUserQuestion` 을 쏘지 않고 **게이트 1 에서 합산 판정**한다 —
`/bts-review-plan` §Step 3 이 「BLOCKER 는 합산한 뒤 한 번에」로 정한 절차이고 게이트 1 이 바로 다음이다.

### Step 0 — 스코프 도전

**복잡도 체크 트리거됨** — files 합계 약 25개(임계 8) · 새 타입 3종 이상
(`AmbiguousTransitionException`·`TransitionKind`·전환 CRUD DTO). 다만 이 PR 은 **이미 분할의 산물**이다
(로드맵이 10 PR 로 쪼갠 4번). 추가 분할안은 「(마이그레이션+도메인) / (엔진+API)」 2개인데,
**권장하지 않는다** — Task 4(리포지토리)가 두 덩어리를 잇는 이음매라 어디서 잘라도 한쪽이 컴파일되지 않는
중간 상태가 main 에 남는다. 로드맵의 「각 PR 이 독립적으로 main 초록」 원칙과 충돌한다.
**게이트 1 에서 Maxi 가 확인할 항목**으로 올린다.

검색 체크는 생략했다 — 이 설계는 저장소 고유 스키마·포트 계약에 매인 것이라 외부 사례 검색의 산출이 없다.
`TODOS.md` 교차 확인 — 이 PR 을 막는 항목 **0건**(워크플로우 관련 부채 4건은 전부 하네스/스킬 쪽이지
도메인 워크플로우가 아니다).

### 1. 아키텍처 리뷰 — 1건

**[P1] (confidence: 9/10) Task 9 — 계약 스냅샷을 MVC 슬라이스에 두면 틀린 계약을 박제한다.**

Prior learning applied: **`contract-snapshot-must-be-generated-in-assembly-not-slice`** (confidence 9/10, 2026-07-27)

실측 인용.
```
backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowControllerMvcTest.kt:82
    @EnableWebMvc
backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/WorkflowControllerMvcTest.kt:128
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
```
`JavaTimeModule` 이 없다. Task 9 의 `WorkflowReadContractTest` 를 이 슬라이스 옆에 두면 조립 앱
(Spring Boot 자동설정)과 **다른 직렬화**를 계약으로 고정하게 된다. 그 스냅샷에 맞춰 PR 8 의 프론트 Zod 를
고치면 프로덕션이 깨진다. 같은 사고가 이 저장소에서 `LocalDate` 배열 직렬화로 이미 한 번 났다.

**처방.** Task 9 의 계약 테스트를 `:app` 조립 컨텍스트(`ProdAssemblyHttpTestBase`)로 옮긴다.
그러면 `[[bts-assembly-test-pat-bearer-and-httpclient]]` 의 두 함정이 함께 걸린다 —
① PAT Bearer 를 쓴다(JWT 면 `MfaEnrollmentGateFilter` 에서 관리자 endpoint 가 전부 403)
② 베이스의 `TestRestTemplate` 대신 JDK `java.net.http.HttpClient` 로 원 응답을 관측한다.
`ProdAssemblyHttpTestBase` 를 **상속만** 하고 `@SpringBootTest`·`@ActiveProfiles` 를 자체 선언하지 않는다.

**비용.** human ~1.5h / CC ~10min. 슬라이스에 두는 것보다 느리지만 계약의 진위가 걸린 자리다.

### 2. 코드 품질 리뷰 — 2건

**[P2] (confidence: 7/10) Task 3 — `key` 계산 프로퍼티의 `*` 가 URL 세그먼트로 샌다.**

plan Task 3 GREEN 이 `key` 를 `"${fromStateKey ?: "*"}__$toStateKey"` 로 적었다. 그런데 `key` 는
**URL 경로 세그먼트로 쓰인다** — `PostActionController` 의 `.../transitions/{transitionKey}/post-actions`
(MSW 목 `apps/web/src/mocks/post-action-handlers.ts:60` 이 같은 모양을 안다). `*` 는 경로 문자로
인코딩·매칭이 갈린다.

**처방.** GLOBAL·INITIAL 의 `key` 는 `null` 을 돌려주거나 `"GLOBAL__$toStateKey"` 처럼 **문자 클래스가
안전한 토큰**을 쓴다. 구 경로는 「단일 NORMAL 전환일 때만 유효한 축약」이라는 ADR 의 규정과도 맞는다.

**[P2] (confidence: 8/10) Task 5·6 — `INITIAL` 제외가 한쪽에만 적혀 있다.**

Task 5 는 `availableTransitions` 에서 INITIAL 을 빼도록 명시했지만, Task 6 의 `resolveTransition`
후보 산출에는 그 문구가 없다. 그대로 두면 `toStatusKey` 가 INITIAL 의 도착지와 같을 때
**있지도 않은 모호성**으로 409 가 난다(E12 와 다른 경로다).

**처방.** Task 6 의 후보 산출에도 「INITIAL 은 항상 제외」를 명시하고, 후보 산출을 **Task 5 가 추출하는
`candidatesFor()` 하나로 공유**한다. 두 벌로 두면 갈라진다 — DRY.

### 3. 테스트 리뷰 — 다이어그램 + gap 2건

```
CODE PATHS                                                 검증 수단
[+] V207 마이그레이션
  ├── UNIQUE DROP ────────────────── [★★★ 계획됨] V207MigrationTest
  ├── kind CHECK ─────────────────── [★★★ 계획됨] V207MigrationTest
  ├── INITIAL 부분 유니크 ─────────── [★★★ 계획됨] V207MigrationTest
  ├── INITIAL 백필(=구 minByOrNull) ─ [★★★ 계획됨] V207MigrationTest + C7
  └── display_order 백필 ──────────── [GAP-1] 계획 없음 · 전부 0 이 된다
[+] Workflow.of() invariant
  ├── (from,to) 중복 허용 ─────────── [★★★ 계획됨] WorkflowTest
  ├── GLOBAL/INITIAL from=NULL ────── [★★★ 계획됨] WorkflowTest
  ├── INITIAL 1개 ────────────────── [★★★ 계획됨] WorkflowTest
  └── key 하위호환 ───────────────── [★★  계획됨] · `*` 문자 케이스 미커버 → 코드품질 P2
[+] WorkflowEngine
  ├── GLOBAL 후보 포함 ───────────── [★★★ 계획됨] C2
  ├── 자기 자신 제외 ─────────────── [★★★ 계획됨] E1
  ├── 모호 감지 → 예외 ───────────── [★★★ 계획됨] C3
  └── INITIAL 이 resolve 후보에서 제외 [GAP-2] 계획 없음 → 코드품질 P2 와 같은 뿌리
[+] 시작 상태 해석
  └── 순서 변경 무영향 ───────────── [★★★ 계획됨] C4
[+] 전환 CRUD
  ├── 생성/수정/삭제 ─────────────── [★★★ 계획됨] TransitionCrudMvcTest
  ├── 400 4종 (E3·E4) ────────────── [★★★ 계획됨]
  ├── 409 (E5) · 404 (E8·E9) ─────── [★★★ 계획됨]
  ├── 403/404 검사 순서 ──────────── [★★★ 계획됨]
  └── 캐시 무효화 ────────────────── [★★★ 계획됨] N5
[+] 읽기 계약
  └── 응답 형태 불변 ─────────────── [★★  계획됨] → 아키텍처 P1 (슬라이스→조립 이전 필요)

COVERAGE: 19/21 경로 계획됨 (90%)  |  GAPS: 2
```

**[P2] (confidence: 9/10) GAP-1 — `display_order NOT NULL DEFAULT 0` 이 기존 전환 순서를 뭉갠다.**

spec §데이터 모델 ③이 `display_order INT NOT NULL DEFAULT 0` 을 추가하는데 백필이 없다. 기존 전환이
전부 `0` 이 되어 **PR 8 의 편집기가 전환을 임의 순서로 그린다.** 지금은 화면이 없어 안 보이고,
화면이 생기는 순간 「순서가 뒤죽박죽」으로 드러난다.

**처방.** 백필에 `row_number() OVER (PARTITION BY workflow_id ORDER BY created_at, name)` 을 쓰고
V207MigrationTest 에 「같은 워크플로우 안에서 display_order 가 중복 없이 1..n」 단언을 추가한다.

**GAP-2** 는 코드품질 §2 의 두 번째 발견과 같은 뿌리다 — 거기서 함께 닫힌다.

**[P2] (confidence: 9/10) Task 8 의 검증 명령이 worktree 에서 죽는다.**

Prior learning applied: **`worktree-pnpm-blocked-use-node-test-directly`** (confidence 9/10, 2026-08-18)

Task 8 검증이 `pnpm --filter web test -- workflow` 인데, worktree 의 `node_modules` 는 심볼릭이라
`ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY` 로 죽는다. **처방** — MSW 목 1줄 변경의 검증은
main 워크스페이스에서 돌리거나, 프론트 테스트 대신 **목 경로 문자열 grep** 으로 갈음한다
(이 PR 의 프론트 변경은 1줄이고 `apps/web` 0파일 원칙의 예외다).

### 4. 성능 리뷰 — 0건

`availableTransitions` 가 GLOBAL 을 매번 훑지만 워크플로우당 전환은 10~20건이고 `WorkflowCache` 가
정의를 메모리에 들고 있어 DB 왕복이 늘지 않는다. 리포지토리의 `workflow_statuses` join 은 단건 조회
경로라 N+1 이 생기지 않는다. 쓰기 3종의 전체 캐시 무효화는 편집 빈도가 낮아 정상.
**No issues found.**

### 5. 트랜잭션 위험 — 1건 (잠복)

**[P2] (confidence: 8/10) 결정 D-2 는 지금은 안전하지만, catch 폴백이 들어오면 500 으로 뒤집힌다.**

Prior learning applied: **`[[workflowstatecatalog-mandatory-rollback-poison]]`**

실측 인용.
```
backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/workflow/WorkflowTransitionPort.kt:12
    @Transactional(propagation = Propagation.MANDATORY)
    fun plan(req: TransitionRequest): TransitionResult
backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt:1174-1190
    when (result) { is TransitionResult.Success -> ... }   // catch 폴백 없음 · 전부 throw
```
`MANDATORY` 안에서 예외를 던지면 Spring 이 **호출자의 공유 트랜잭션을 rollback-only 로 마킹**한다.
지금은 `IssueApplicationService` 가 예외를 흡수하지 않고 그대로 던지므로 롤백이 정상 동작이고
**D-2 는 유효하다.** 위험은 미래에 있다 — 누가 「모호하면 후보를 보여주고 계속 진행」 같은 폴백을 넣는
순간 `UnexpectedRollbackException` 으로 500 이 된다. 같은 양식의 잠복 버그가 이 저장소에
이미 2건 기록돼 있다(`IssueEpicService.resolveStateCategories` 등).

**처방.** ① Task 1 ②의 판정 기준을 「409 가 나오는가」에서 **「409 가 나오고 트랜잭션이 rollback-only
로 마킹되는가」**로 넓힌다 ② 「`AmbiguousTransitionException` 을 catch 해 폴백하려면 `REQUIRES_NEW`
격리 빈이 필요하다」를 아래 §다음 PR 이 물려받는 제약에 등재한다.

### NOT in scope — 고려했으나 뺀 것

| 항목 | 왜 뺐나 |
|---|---|
| 보드 드래그앤드롭의 「어떤 전환인가요」 선택 다이얼로그 | 프론트 표면 · ADR §영향-부정이 후속으로 명시. 이 PR 은 409 로 정직하게 막는 데까지 |
| `POST /api/v1/issues/{key}/transition` 의 `transitionId` 수용 | issue-tracking BC · 로드맵 PR 7 |
| 전환 규칙(validator) CRUD 경로의 `transitionId` 정렬 | 로드맵 PR 5 (FR-WF-06) |
| `workflow_states`·`from_state_id`·`to_state_id` DROP | `DATA.md` §4 3단 분할의 3단계 — 로드맵이 마지막 PR 로 못박음 |
| `/admin/workflows` 화면 | 로드맵 PR 8 |
| `TransitionResult` sealed 확장 | 결정 D-2 로 기각 · cross-BC 컴파일 파괴 |

### What already exists — 재사용 대 재구축

| 이미 있는 것 | 이 plan 의 처리 |
|---|---|
| `workflow_transitions.id UUID PRIMARY KEY` (V200:66) | **재사용** — id 는 이미 있다. V207 은 노출과 제약만 바꾼다 |
| `WorkflowSchemeExceptionHandler` 의 `SchemeInUseException → 409 + 목록` | **재사용** — D-2 가 그대로 본뜬다 |
| `RecordAccess.kt` 의 `Record.required()` | **재사용** — Task 4 가 `!!` 대신 씀 |
| `testsupport/WorkflowStatusFixture.kt` | **재사용** — 원시 SQL 금지 관례 |
| `V203ToV206MigrationTest.kt` | **더 가까운 선례** — plan 은 `V200MigrationTest.kt` 를 지목했으나 같은 대역·같은 카탈로그를 다루는 쪽은 이것이다 [P2 · confidence 9/10] |
| `workflow_validators`/`workflow_post_actions` 의 `ON DELETE CASCADE` (V200:74·95) | **확인 완료** — E6 의 「CASCADE 로 함께 삭제」가 실제와 일치 |
| `WorkflowCache` | **재사용** — 무효화 결선만 |

### 실패 모드 — 프로덕션에서 어떻게 깨지나

| 경로 | 실패 시나리오 | 테스트 | 에러 처리 | 사용자가 보는 것 |
|---|---|---|---|---|
| 모호 전환 | 보드 DnD 가 후보 2개인 상태쌍으로 이동 | C3 ✓ | 409 ✓ | 명시적 409 — 조용한 실패 아님 ✓ |
| INITIAL 백필 누락 | 마이그레이션이 일부 워크플로우를 못 채움 | C7 ✓ | Task 7 의 `minByOrNull` 폴백 ✓ | 종전과 동일 동작 ✓ |
| display_order 뭉개짐 | PR 8 편집기가 전환을 임의 순서로 그림 | **GAP-1** ✗ | 없음 ✗ | **조용한 실패** ⚠ |
| 계약 스냅샷 오박제 | 조립과 다른 직렬화를 고정 → PR 8 Zod 가 틀린 형식에 맞춰짐 | P1 ✗ | 없음 ✗ | **조용한 실패** ⚠ |
| rollback poison | 미래의 catch 폴백이 500 을 만듦 | §5 ✗ | 없음 ✗ | 500 ⚠ (잠복) |

**critical gap 2건** — `display_order`(GAP-1) 과 계약 스냅샷 위치(P1). 둘 다 테스트도 에러 처리도 없고
증상이 조용하다. 위 처방으로 닫는다.

### 병렬화 전략

| 단계 | 건드리는 모듈 | 의존 |
|---|---|---|
| Task 1 예외·핸들러 | project-workflow/web · issue-tracking/test | — |
| Task 2 마이그레이션 | project-workflow/resources | — |
| Task 3 도메인 | project-workflow/domain | — |
| Task 4 리포지토리 | project-workflow/repository | 2·3 |
| Task 5·6 엔진 | project-workflow/engine · shared-kernel | 3·4 (6 은 1 도) |
| Task 7 리졸버 | project-workflow/scheme | 3·4 |
| Task 8 CRUD | project-workflow/web · application · apps/web | 2·3·4 |
| Task 9 계약 | project-workflow/web/dto (P1 반영 시 app/test) | 4·8 |

`Lane A: 1 (독립)` / `Lane B: 2 → 4 → {5,6,7} → 9` / `Lane C: 3 (B 에 합류)`.
**worktree 분리는 권장하지 않는다** — Task 5·6·8 이 전부 `project-workflow` 한 모듈을 건드려
병렬 worktree 가 곧 머지 충돌이다. 순차 wave 로 충분하다.

### Implementation Tasks — 이 리뷰가 만든 작업

- [ ] **T1 (P1, human: ~1.5h / CC: ~10min)** — Task 9 — 계약 스냅샷을 `:app` 조립으로 옮긴다
  - Surfaced by: 아키텍처 리뷰 — `WorkflowControllerMvcTest.kt:82` `@EnableWebMvc` · `:128` JavaTimeModule 부재
  - Files: `backend/modules/app/src/test/kotlin/com/bts/app/WorkflowReadContractProdBootTest.kt`
  - Verify: `./gradlew :modules:app:test --tests '*WorkflowReadContract*'` (Postgres 5433 선행 기동 필요)
- [ ] **T2 (P2, human: ~30min / CC: ~5min)** — Task 2 — `display_order` 백필 + 중복 없음 단언
  - Surfaced by: 테스트 리뷰 GAP-1
  - Files: `V207__transitions_multi_and_global.sql` · `V207MigrationTest.kt`
  - Verify: `./gradlew :modules:project-workflow:test --tests V207MigrationTest`
- [ ] **T3 (P2, human: ~20min / CC: ~5min)** — Task 5·6 — 후보 산출을 `candidatesFor()` 하나로 공유하고 INITIAL 을 양쪽에서 제외
  - Surfaced by: 코드 품질 리뷰 · 테스트 리뷰 GAP-2
  - Files: `WorkflowEngine.kt` · `WorkflowEngineGlobalTransitionTest.kt` · `WorkflowEngineAmbiguousTest.kt`
  - Verify: `./gradlew :modules:project-workflow:test --tests '*Transition*'`
- [ ] **T4 (P2, human: ~15min / CC: ~3min)** — Task 3 — GLOBAL/INITIAL 의 `key` 에 URL 안전 토큰
  - Surfaced by: 코드 품질 리뷰 — `*` 가 `.../transitions/{transitionKey}/post-actions` 세그먼트로 샌다
  - Files: `WorkflowTransition.kt` · `WorkflowTransitionTest.kt`
  - Verify: `./gradlew :modules:project-workflow:test --tests '*WorkflowTransitionTest*'`
- [ ] **T5 (P2, human: ~5min / CC: ~1min)** — Task 1 — 판정 기준에 rollback-only 마킹 관측 추가
  - Surfaced by: 트랜잭션 위험 §5
  - Files: `AmbiguousTransitionStatusCodeIntegrationTest.kt`
  - Verify: 같은 테스트
- [ ] **T6 (P2, human: ~5min / CC: ~1min)** — Task 2 선례를 `V203ToV206MigrationTest.kt` 로 · Task 8 검증 명령에서 worktree `pnpm` 제거
  - Surfaced by: What already exists · 테스트 리뷰
  - Files: plan 문서만
  - Verify: 문서 확인

### ★ 다음 PR 이 물려받는 제약 (이 리뷰가 추가)

1. **`AmbiguousTransitionException` 을 catch 해 폴백하지 마라.** `WorkflowTransitionPort.plan` 이
   `MANDATORY` 라 공유 트랜잭션이 rollback-only 로 마킹된다 — 폴백해도 커밋 시점에
   `UnexpectedRollbackException` 으로 500 이 된다. 굳이 하려면 `REQUIRES_NEW` 격리 빈이 필요하다
   (`IsolatedWorkflowStateLookup` 선례).
2. **`GET /api/v1/workflows/{key}` 의 `transitions[].fromStateKey` 가 nullable 이 됐다.** PR 8 이
   프론트 Zod 를 완화해야 한다 (`apps/web/src/api/workflows.ts`).
3. **전환 `display_order` 는 V207 백필이 정한 순서다.** 편집기가 순서를 바꾸면 이 컬럼을 쓴다.

## GSTACK REVIEW REPORT

| Runs | Status | Findings |
|---|---|---|
| plan-eng-review ×1 | issues_open | P1 1 · P2 5 · P3 1 · BLOCKER 0 |
| 아웃사이드 보이스 (codex) | skipped | `codex_reviews=disabled` — 0종 통과가 아니라 미실행임을 명시 |

- Step 0 스코프 도전 — 복잡도 임계 초과(파일 ~25 · 임계 8). **분할 권장하지 않음** (Task 4 가 이음매라 어디서 잘라도 중간 상태가 main 에 남는다). 게이트 1 확인 항목.
- 아키텍처 — 1건 (P1 계약 스냅샷 위치)
- 코드 품질 — 2건 (P2 `key` 의 `*` · P2 INITIAL 제외 누락)
- 테스트 — 다이어그램 산출 · 21경로 중 19 계획됨(90%) · GAP 2
- 성능 — 0건
- 트랜잭션 위험 — 1건 (P2 rollback poison 잠복)
- NOT in scope — 작성 · What already exists — 작성
- 실패 모드 — **critical gap 2건** (display_order · 계약 스냅샷 위치)
- 병렬화 — Lane 3개이나 한 모듈 집중이라 **순차 권장**
- Prior learnings applied — 3건 (`contract-snapshot-must-be-generated-in-assembly-not-slice` · `worktree-pnpm-blocked-use-node-test-directly` · `workflowstatecatalog-mandatory-rollback-poison`)
- Lake Score — 6/6 권고가 완전판을 택함

VERDICT: **PASS WITH FIXES** — BLOCKER 0. T1~T6 을 구현 전에 plan 에 반영한다. CODEX: skipped (disabled). CROSS-MODEL: n/a.

**UNRESOLVED DECISIONS:**
- 게이트 1 — 결정 D-1 (전환 계획 경로 이동) 승인 여부
- 게이트 1 — 결정 D-2 (모호 전환을 예외로) 승인 여부 · Task 1 ② 판정 전까지 잠정
- 게이트 1 — Step 0 복잡도 임계 초과를 그대로 진행할지

## wave 1 결과 (2026-08-20)

| task | implementer | verifier | 비고 |
|---|---|---|---|
| Task 1 | DONE_WITH_CONCERNS | **PASS** | 판정 ② **409 확정** — 결정 D-2 유효. 역-뮤테이션(`@Order` 제거 → 500)으로 비-공허 확인 |
| Task 2 | DONE_WITH_CONCERNS | **PASS** | 가짜 그린 1건 자체 적발 → `pg_get_constraintdef` 스키마 축 추가. N4 위반 0 |
| Task 3 | DONE_WITH_CONCERNS | **PASS** | 수용 단언이 거부 단언과 함께 있어 「전부 거부」 구현이 통과 못 한다 |
| Task 10 | DONE_WITH_CONCERNS | DRIFT → **PASS** | DRIFT 는 **controller 의 diff 수집 실수**였다. `WorkflowTransition.kt` 를 Task 10 수집 목록에서 빠뜨렸고, 실제로는 `:30` 에 `val id: UUID = UUID.randomUUID(),` 가 커밋 `dc4318013` 로 실재한다. 컴파일 오류에서 `No value passed for parameter 'id'` 가 사라진 것이 기계적 증거다 |

**커밋 16개 · TDD 순서 전수 통과 · 선언 외 파일 0건 · `[skip ci]` 0건.**

### wave 1 이 남긴 미확인 2건 — Task 4 가 닫는다

1. **Task 10 의 테스트가 한 번도 실행된 적이 없다.** `compileTestKotlin` 이 `compileKotlin` 에 걸려
   단 1건도 돌지 못했다. 뒤집은 시드 시나리오는 「중복 거부 red → 규칙 제거 green」이라는
   **의미적 대조를 확보하지 못했고**, 지금 있는 red 는 컴파일 실패뿐이다.
2. **Task 1 의 검증은 격리 트리 하네스에서 이뤄졌다.** worktree 에서 3개 테스트 재실행이 필요하다.

현재 컴파일 잔여 오류 2건 — `WorkflowEngine.kt:214`(Task 4 가 1줄 보정) ·
`DefaultWorkflowDefinitionRepository.kt:135`(Task 4 본업).

### 부수 발견 (이 PR 밖 · 장부 후보)

- **`DATA.md §5`(「jOOQ 생성물은 git 에 커밋」)와 `.gitignore:21`(`**/src/generated/jooq/` 제외)이
  서로를 검사하지 않는 두 정본이다.** 실측 `git ls-files | grep -c generated/jooq` = 0.
  `[[two-lists-never-check-each-other]]` 양식.
- `detekt` 위반 1건 — `seed/YamlSeedServiceTest.kt:385 NestedBlockDepth`(Task 10 이 들여옴).
  게이트 2 전에 해소한다.

### Task 11. 3단 분할 2단계 정합 복구 — 프로덕션 시드 파손 차단 + 호출부 회생

> **wave 2 실측이 만든 신규 task.** Task 4 가 `BLOCKED` 로 보고한 근본 원인을 닫는다.
> spec `## 데이터 모델 변경` 의 **★ 정정** 절이 이 task 의 근거다.

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/resources/db/migration/project-workflow/V207__transitions_multi_and_global.sql`,
  `backend/modules/project-workflow/src/main/resources/db/codegen/init_codegen.sql`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/seed/YamlSeedService.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/WorkflowTransition.kt`,
  `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowRepository.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/db/V207MigrationTest.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/property/WorkflowPropertyTest.kt`,
  `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/repository/DefaultWorkflowDefinitionRepositoryTest.kt`]
- depends-on: [2, 3, 4, 10]

**RED**:
- 테스트:
  ```kotlin
  // V207MigrationTest — 3단 분할 2단계 계약을 못박는다
  @Test fun `to_status_id 는 아직 NULL 을 허용한다`()          // 3단계로 이연됐음을 고정
  @Test fun `구 컬럼만 채운 INSERT 가 성공한다`()               // 기존 21파일이 사는 근거
  // YamlSeedServiceTest 계열 — 프로덕션 부팅 회귀 가드
  @Test fun `시드가 심은 전환은 from_status_id·to_status_id·kind 를 채운다`()
  ```
- 실패 메시지 (예상): `null value in column "to_status_id" violates not-null constraint`

**GREEN — 5건**

1. **★ V207 에서 `:153` `ALTER COLUMN to_status_id SET NOT NULL` 과 `:194` `ck_transition_kind_from`
   CHECK 를 제거한다.** 근거 주석을 그 자리에 남겨라 — 「3단 분할 2단계는 구·신 공존 구간이다.
   NOT NULL·CHECK 는 `workflow_states` DROP 과 같은 PR(3단계)로 이연. 정의 시점 강제는
   `Workflow.of()` 가 진다」. `init_codegen.sql` 미러도 같이 맞춘다.
2. **★ `YamlSeedService.kt:451-456` 의 INSERT 가 신 컬럼도 채운다** — `FROM_STATUS_ID`·`TO_STATUS_ID`·
   `KIND`. **이것이 프로덕션 부팅 파손을 막는 핵심**이다(현재 `grep -c "TO_STATUS_ID" YamlSeedService.kt`
   = 0). `workflow_statuses` 에서 `status_id` 를 찾아 넣는다.
3. **읽기가 구·신 양쪽을 견딘다** — `WorkflowRepository` 의 전환 매핑에서 신 컬럼이 NULL 이면 구 컬럼
   경유로 폴백한다. 3단계에서 이 폴백을 지운다는 주석을 남겨라.
4. **★ `WorkflowTransition` 의 파라미터 순서를 바꾼다** — `id` 와 `kind` 를 **뒤로** 보내
   `(fromStateKey, toStateKey, name, id = …, kind = …)` 로. 지금은 `id` 가 첫 자리라
   위치 인자 호출 `WorkflowTransition("open", "done", "완료")` 가 `id: UUID` 에 묶여
   `String vs UUID` 로 죽는다 — **테스트 컴파일 오류 62건 중 55건이 이 하나 때문**이다.
   기본값은 **이름 붙인 호출만** 구한다는 것이 실측된 교훈이다.
5. `V207MigrationTest.kt:502` 의 백틱 함수명에서 `..` 을 없앤다 — JVM 이 허용하지 않는 문자라
   **이 파일은 지금껏 한 번도 컴파일된 적이 없다**(`Name contains illegal characters: ..`).

**REFACTOR**: 남은 nullable 타입 불일치 6건 보정 — `WorkflowPropertyTest.kt:270`(`Triple<…, String?, …>`) ·
`DefaultWorkflowDefinitionRepositoryTest.kt:253`. 후자는 Task 4 가 validator 조회를 `transition.id`
기준으로 바꿨으므로 **임의 UUID 로 만든 전환이 조회되기를 기대하는 픽스처**(`:108-109`)를
DB 에서 읽어 온 전환을 쓰도록 고쳐야 한다.

**검증** (순서대로, 각각 종료 코드를 보고에 적을 것):
```
./gradlew :modules:project-workflow:compileTestKotlin        # 0 이어야 한다 — 이 task 의 1차 성공 기준
./gradlew :modules:project-workflow:test
./gradlew :modules:issue-tracking:test --tests '*Transition*'
```

**★ 이 task 가 닫는 미확인** — wave 1·2 를 통틀어 이 모듈의 테스트가 **한 번도 실행된 적이 없다.**
Task 1·2·3·4·10 의 테스트 전부가 이 task 이후에 처음 돌아간다. 하나라도 red 면 원문을 인용해
보고하라 — controller 가 담당 task 로 되돌린다.

## wave 2 결과 (2026-08-20)

| task | 결과 | 핵심 |
|---|---|---|
| Task 4 | BLOCKED (구현 3커밋 완료 · 검증만 막힘) | `compileKotlin` EXIT=0 복구 · `as UUID` 함정 제거 · **프로덕션 시드 파손 발견** |
| Task 11 | DONE_WITH_CONCERNS | 3단 분할 2단계 정합 복구 · 컴파일 오류 **62 → 1** · 시드 파손 차단 + 회귀 가드 · 역-뮤테이션 2회 |

**Task 11 담당 파일 전부 초록** — `V207MigrationTest` 9/9 · `YamlSeedServiceTest` 12/12 ·
`WorkflowRepositoryTransitionTest` 5/5 · `DefaultWorkflowDefinitionRepositoryTest` 9/9 ·
`WorkflowPropertyTest` 3/3 · `V203ToV206MigrationTest` 31/31. detekt·ktlint EXIT=0.
임시 패치 적용 시 모듈 전체 **668 tests / 14 failed**(전부 아래 잔여).

### 규약 이탈 1건 — **승인함**

Task 11 이 `files` 밖의 `seed/YamlSeedServiceTest.kt` 를 수정했다. **plan 작성자(controller)의 실수가
원인이다** — Task 11 의 RED 절이 「`YamlSeedServiceTest` 계열 회귀 가드」를 명시로 요구하면서
메타 `files` 에는 그 파일을 넣지 않았다(GREEN 대상만 모았다). 그 파일을 안 건드리면 이 task 의 핵심인
GREEN 2(시드 신컬럼 충전)가 **테스트 없는 변경**이 되어 TDD 가 성립하지 않는다. 이탈이 아니라
메타 누락이므로 승인하고 여기 기록한다.

### Task 12. 잔여 회귀 정리 — project-workflow

**메타**. agent `backend-engineer` ·
files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/property/WorkflowGraphClosedTest.kt`,
`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/DoneResolutionValidatorSeedTest.kt`,
`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/seed/YamlSeedValidatorPostActionTest.kt`,
`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/engine/WorkflowEngineWiringIntegrationTest.kt`,
`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/guard/RawWorkflowStateInsertGuardTest.kt`] ·
depends-on: [4, 11]

- **(A) 컴파일 1건.** `WorkflowGraphClosedTest.kt:174` 의 `groupBy { it.fromStateKey }` 가
  `Map<String?, …>` 를 낸다. **기계적 수정이 아니다** — 그래프 도달성의 의미가 확장됐다.
  **채택할 의미** — 시작점은 `INITIAL` 전환의 `toStateKey`(없으면 종전 `minByOrNull` 폴백) ·
  `NORMAL` 은 `from → to` 간선 · `GLOBAL` 은 **모든 상태 → to** 간선 · `INITIAL` 은 시작점 결정에만
  쓰고 간선으로 세지 않는다.
- **(B) 13건.** `DefaultWorkflowDefinitionRepository.findValidators/findPostActions` 가 `transition.id`
  로 찾는데 3파일이 임의 UUID 로 만든 전환을 넘겨 항상 빈 리스트를 받는다.
  **처방은 확립돼 있다** — Task 11 이 `DefaultWorkflowDefinitionRepositoryTest` 에서 쓴
  `SeededTransitions` 패턴(시드가 DB 에서 받은 전환 id 를 픽스처에 넣는다)을 그대로 재사용한다.
- **(C) 1건.** `RawWorkflowStateInsertGuardTest` 가 `V207MigrationTest` 를 원시 SQL 위반으로 잡는다.
  그 픽스처는 **V204 적용 전** 시점에 심어야 해서 `statuses` 가 아직 없어 헬퍼를 쓸 수 없다.
  **가드에 예외를 등재하고 사유를 그 자리에 적는다**(가드를 무력화하지 말 것 — 파일 1개 화이트리스트).

### Task 13. 잔여 회귀 정리 — issue-tracking 픽스처 15파일

**메타**. agent `backend-engineer` · files: `backend/modules/issue-tracking/src/test/**` 중
아래 15파일 · depends-on: [2, 11]

V207 ①이 `UNIQUE(workflow_id, from_state_id, to_state_id)` 를 DROP 했는데 픽스처들이 그 제약을
`ON CONFLICT` 대상으로 쓴다 → `PSQLException: there is no unique or exclusion constraint matching
the ON CONFLICT specification`. **15파일이 전부 동일한 한 패턴**이다.

```sql
INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name)
VALUES (?, ?, ?, ?) ON CONFLICT (workflow_id, from_state_id, to_state_id) DO NOTHING
```

**처방** — `DO NOTHING` 의 멱등성을 `WHERE NOT EXISTS` 로 옮긴다(단순 삭제 금지 — 픽스처가 같은
전환을 두 번 심는 경로가 있으면 깨진다. 파일별로 호출 횟수를 먼저 확인할 것).
**N2 유지** — `issue-tracking/src/main` 은 0줄이다. 테스트 픽스처만 고친다.

### 부수 발견 — 장부 등재 후보 (이 PR 밖)

- **시드가 심는 전환의 `display_order` 가 전부 0.** V207 ⑥이 **기존 행**에 대해 정확히 이 문제를
  막았는데(`row_number()` 백필), **시드가 새로 심는 행**에는 같은 보호가 없다. `WorkflowRepository`
  가 `ORDER BY display_order, id` 로 읽으므로 시드 워크플로우의 전환 순서가 사실상 UUID 순이다.
  편집기 UI(로드맵 PR 8)가 생기는 순간 드러나는 조용한 실패다.

## wave 3 결과 (2026-08-20)

| task | 결과 | 핵심 |
|---|---|---|
| Task 5 | DONE_WITH_CONCERNS | `candidatesFor()` 추출(enum 전수 `when` — 종류 추가 시 컴파일 파손) · **뮤테이션 3회 전부 red** · GLOBAL 2 + INITIAL 1 인라인 픽스처로 엔진 쪽 dead branch 해소 |
| Task 7 | DONE_WITH_CONCERNS | INITIAL 우선 + `minByOrNull` 폴백 · **`displayOrder` 를 정반대로 뒤집은 픽스처 2개**로 대조(폴백 구현이 통과 못 하게) · 뮤테이션 red · issue-tracking 이슈 생성 64건 무회귀 |
| Task 8 | DONE_WITH_CONCERNS | CRUD 3종 + 경로 이동 · `requireEditable`(권한 → 존재 → 잠금) · **뮤테이션 2회 red** · MSW 목 1줄 |

**`:modules:project-workflow:cleanTest test` EXIT=0 · 683 tests / 0 failures** (XML 신선도 확인) ·
detekt·ktlint EXIT=0 · `issue-tracking` 무회귀.

### Task 8 의 판단 2건 — 승인

- **Jakarta Validation 어노테이션을 새 요청 DTO 에 달지 않았다.** 이 모듈은 `jakarta.validation-api` 만
  있고 구현체(hibernate-validator)가 없어 컨트롤러도 `@Valid` 를 쓰지 않는다 — 달면 **장식이 된다**
  (`[[decorative-annotation-copied-from-sibling]]` 을 정확히 적용한 판단이다). 실제 판정은 서비스가
  지고 `WORKFLOW_INVALID_REQUEST`(400)로 나가며 그 400 이 도메인 판정인지까지 테스트가 대조한다.
- **응답 필드 이름을 읽기 API 와 맞췄다**(`fromStateKey`/`toStateKey` + `id`·`key`·`kind`).
  요청 바디만 spec 이 못박은 `fromStatusKey`/`toStatusKey` 다. Task 9 가 읽기 DTO 에 `id`·`kind` 를
  더하면 두 모양이 같아져 프론트 스키마가 하나로 유지된다.

### Task 14. 409 응답 형식 통일 + 목 주석 정합

> **wave 3 실측이 만든 신규 task.** Task 8 의 concern 1·3 을 닫는다. Task 9 와 파일이 겹치지 않아
> wave 5 에서 병렬로 돈다.

**메타**. agent `backend-engineer` ·
files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/exception/WorkflowExceptions.kt`,
`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/TransitionConflictExceptionHandler.kt`(신규),
`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/application/WorkflowCommandService.kt`,
`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/TransitionCrudMvcTest.kt`,
`apps/web/src/mocks/workflow-handlers.ts`] · depends-on: [8]

- **문제.** Task 8 이 E5(INITIAL 삭제 409)·E2(INITIAL 중복 409)를 `ResponseStatusException` 으로 냈다.
  허용 파일에 도메인 예외와 advice 가 없었기 때문이다(controller 의 `files` 설계 실수). 그 결과
  **이 두 409 만 `ProblemDetail` 로 나가고 나머지 워크플로우 오류는 `{error:{code,message}}` 다.**
  프론트가 한 화면에서 두 형식을 다뤄야 한다.
- **처방.** `TransitionConflictException` 을 도메인 예외로 옮기고 **전용 advice** 로 409 +
  `{error:{code,message}}` 를 낸다. `WorkflowExceptionHandler` 에 얹지 **않는다** — 그 advice 는 이미
  detekt `TooManyFunctions` 한도에 닿아 있고, `WorkflowStatusCompositionExceptionHandler` 가 정확히
  그 이유로 갈라져 나온 선례다. Task 1 이 만든 `AmbiguousTransitionExceptionHandler` 와 같은 모양.
- **MSW 목 주석 2줄**(`workflow-handlers.ts` 10행·29행)이 옛 경로를 가리킨 채 남았다 — N3 「경로 문자열
  1줄만」을 문자 그대로 지킨 결과다. 주석까지 갱신한다.

### 부수 발견 — 장부 후보 추가

- **파일 300줄 초과 3건** — `WorkflowCommandService.kt` 421 · `WorkflowWriteRepository.kt` 391 ·
  `WorkflowController.kt` 334. 정본 처방은 `WorkflowTransitionController`/`TransitionCommandService`/
  `TransitionWriteRepository` 분리인데 전부 신규 파일이라 이 PR 범위 밖이다. Task 8 이 `@Suppress` 3곳에
  사유 KDoc 을 달았고 **전역 임계값은 건드리지 않았다**.
- **`WorkflowGraphClosedTest` 의 그래프 도달성 쪽 GLOBAL·INITIAL 간선 규칙은 여전히 미도달.**
  Task 5 가 엔진 쪽 구멍은 닫았으나 도달성 속성은 별건이다 — 표준 4개 워크플로우에 해당 전환이 없어
  **그 규칙을 지워도 초록**이다.
- **병렬 wave 가 Gradle `build/` 를 공유해 서로의 test XML 을 덮고 지운다.** 실측 3종 —
  `NoSuchFileException: output.bin.idx` · `BUILD SUCCESSFUL` 인데 결과 디렉터리 소실 · 낡은 XML 을
  읽을 뻔함. `--rerun-tasks` 를 test 와 함께 쓰면 특히 심하다.

## wave 4 결과 (2026-08-20) — Task 6

**중단·재개.** Task 6 은 「전체 스위트 검증」 직전에 Maxi 가 중단시켰다. 구현 3파일이 미커밋으로
워킹트리에 남아 있었고 유실은 0 이었다. controller 가 이어받아 커밋·검증을 마쳤다.

| 커밋 | 내용 |
|---|---|
| `ea1cd09ae` | test red — transitionId 우선 실행과 모호 감지 |
| `cfc0bbf4e` | feat green — `transitionId` 우선 + `AmbiguousTransitionException` |
| `01ce66da1` | refactor — `TooManyFunctions` 근거 명시 |

**검증 전량**

| 대상 | 결과 |
|---|---|
| `shared-kernel` (`cleanTest`) | **314 tests / 0 failures** |
| `project-workflow` (`cleanTest`) | **690 tests / 0 failures** (683 → 690) |
| `issue-tracking` (`cleanTest`) | **3,286 tests / 0 failures** — ★하위호환 계약 판정 |
| detekt · ktlint (양 모듈) | EXIT=0 |

**뮤테이션 3회 — 전부 red (비-공허 확인)**

| # | 훼손 | 결과 |
|---|---|---|
| 1 | 모호 감지 분기 → `candidates.first()` | 2 red — 「후보 2개면 예외」·「NORMAL 과 GLOBAL 이 같은 도착지면 모호」 |
| 2 | `transitionId` 우선 분기 제거 | 2 red — 「지목 실행」·「타 워크플로우 transitionId 는 404」 |
| 3 | `candidatesFor` 재사용 → 자체 필터 | 1 red — GLOBAL 이 후보에서 빠져 모호 판정이 무너진다 |

3회 모두 `git checkout -- <파일>` 로 원복하고 `git status` 와 `grep` 으로 눈 확인했다.

### ★ controller 의 시행착오 1건 — 기록

`WorkflowEngine` 이 detekt `TooManyFunctions`(임계 11)에 닿아, `@Suppress` 를 쌓기보다
2줄 헬퍼 `resolveById` 를 인라인하려 했다. **실패했다** — 인라인하면 `resolveTransition` 의 `throw` 가
3개가 되어 `ThrowsCount`(상한 2)를 대신 위반하고 `UUID` import 도 미사용이 된다.
**규칙이 요구하는 모양이 원래 구조였다.** 원복 후 `@Suppress` + 사유를 달았고, KDoc 에
「인라인은 실측으로 기각했다」와 그 근거를 남겨 **다음 사람이 같은 시도를 반복하지 않게** 했다.
정본 처방은 전환 해석을 `TransitionResolver` 로 떼는 것이고 다음 PR 몫이다.

## 리뷰 결과 (PR 단위 · 2026-08-20)

**렌즈 7종** — `code-reviewer`(절대 규칙) · `/review` 스페셜리스트 6(data-migration · security ·
api-contract · testing · performance · maintainability). T3 이므로 `/bts` 표의 「2종 + ceo」를 넘겨
가짜 그린 사냥까지 붙였다.

**판정. 🛑 BLOCKER — 수정 후 재리뷰.**

| 렌즈 | 판정 | 요지 |
|---|---|---|
| code-reviewer | **BLOCKER 1 · CONCERNS 8** | V207 INITIAL 백필이 프론트 화면 2개를 **확정적으로** 깬다 |
| api-contract | **CRITICAL 4** · INFO 6 | D-1 근거 거짓 · Zod 미완화 · 목이 자가 초록 · post-action 404 |
| data-migration | **CRITICAL 4** · INFO 3 | 구 컬럼 잔존 → 조회 500 · 신규 설치에 INITIAL 없음 · 롤백 주장 과장 |
| testing | **CRITICAL 4** · INFO 6 | 복제 전환 복사가 0행으로만 돎 · B2 판정 공허화 · 도달 불가 분기 |
| maintainability | **CRITICAL 2** · INFO 11 | 존재하지 않는 제약을 안전망으로 인용 · 300줄 장부 자기 누락 |
| security | CRITICAL 0 · INFO 7 | 4개 판정 항목 전부 통과. `resolveById` 무방비는 미래 위험 |
| performance | CRITICAL 0 · INFO 5 | 곱집합 축소·N+1 부재·캐시 무효화 3종 전부 **확인** |

### ★ 교차 확인된 결함 (2개 이상 렌즈가 독립 지목)

| # | 결함 | 렌즈 수 | 등급 |
|---|---|---|---|
| 1 | **프론트 Zod 미완화 → 배포 즉시 워크플로우 화면 2곳 실패** | 2 | **P0** |
| 2 | `PostActionTransitionResolver` 미이전 → 신규 전환의 규칙 편집 404 | **4** | P1 |
| 3 | `copyTransitions` KDoc 이 **존재하지 않는 CHECK** 를 안전망으로 인용 | **4** | P1 |
| 4 | D-1 「실호출부 0」 근거가 **거짓** — `api/workflows.ts:128` | 2 | P1 |
| 5 | `WorkflowGraphClosedTest` GLOBAL·INITIAL 분기 **지워도 초록** | 3 | P2 |
| 6 | `AvailableTransitionView.transitionId` 항상 null → **409 재요청 왕복이 성립 안 함** | 3 | P1 |
| 7 | 시드가 INITIAL 을 안 심음 → **신규 설치에서 C4/F10 이 거짓** | 2 | P1 |
| 8 | `resolveById` 가 현재 상태를 대조하지 않음 | 2 | P2(미래 P0) |

### 단독 CRITICAL 7건

`updateTransition` 이 구 컬럼을 안 지워 조회 500 · 워크플로우 복제의 전환 복사가 **0행으로만 실행**
(함수를 비워도 초록) · B2 cross-workflow 판정이 id 전환으로 **공허해짐** ·
`hasInitialTransition(excludingId)` 미도달(최초 전환 수정이 영구 409) · 프론트 테스트가 인라인 목으로
**자가 초록** · 롤백 주석이 forward-only 를 「데이터 온전」이라 서술 ·
`WorkflowRepository.kt` 241→354줄인데 300줄 장부에서 **자기 누락**.

### ★ controller 가 낸 오류 3건 — 기록

1. **D-1 근거 「실호출부 0」이 거짓이었다.** 초기 확인에서 `grep ... | head -15` 로 결과가 잘려
   `api/workflows.ts` 를 못 보고, 그 잘린 출력을 근거로 삼았다. **개수 제한을 걸고 「없다」로 결론 낸 것**이 오류다.
2. **회귀 가드를 잘못 지목했다.** `@Order` 제거를 잡는 것은 `GlobalControllerAdviceSealTest` 가 아니라
   `AmbiguousTransitionStatusCodeIntegrationTest` 다. 전자는 `@Order` 를 떼도 초록이다.
3. **`@Suppress` 4곳은 전부 `TooManyFunctions`** 다. 「300줄 초과 3 + 1」이라 적었으나 300줄은 억제된 적이 없다.

### 통과 확인된 것

- **N2 cross-BC 프로덕션 0줄** — issue-tracking 16파일 **전부 `src/test`**, shared-kernel 은 기본값 있는
  nullable 추가만. agile-planning·slack-integration·automation **0파일** (code-reviewer 전수 확인)
- **403/404 의미** — 미보유자는 존재 여부와 무관히 403, 보유자만 404. 존재 probe 불가 (security)
- **인증 경계** — SecurityConfig·permitAll·CSRF 무변경. 오히려 기존 원시 SQL 1건 제거 (security)
- **광역 advice 응답 유출 없음** — 둘 다 `ProblemDetail` 이 아니라 URI 자동 채움 경로에 안 닿는다 (security)
- **성능** — 곱집합 한 겹 축소 · 폴백은 N+1 아님(1회 배치, 백필 완료 시 0회) · 캐시 무효화 3종 전부 (performance)
- **하네스 고정 2줄** — `verify-master-plan.sh` · `classify-task.ts --cache` 호출문 그대로. `.claude/**` 무변경
- **CI** — 실패 0건 (SUCCESS 9 · 진행 중 5)
