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

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
