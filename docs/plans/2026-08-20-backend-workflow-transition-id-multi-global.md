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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
