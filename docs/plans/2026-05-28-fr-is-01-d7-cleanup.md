# FR-IS-01 D7 Cleanup — PR #32 codereview 위임 4건

> slug: fr-is-01-d7-cleanup
> type: qa
> agent: qa-engineer
> 생성: 2026-05-28
> 브랜치: claude/context-restore-uP6lK (remote execution 환경, worktree 미사용)

## Brief

사용자 원문. "pr32-merged-fr-is-01-d7-e2e-4-scenarios-shipped-next-cleanup 작업 진행해줘".

직전 컨텍스트. PR #32 (FR-IS-01 D7 E2E 4 시나리오) 머지 완료 (`ee5412f`, 2026-05-28). `docs/plan/product/issue-tracking.md §2.1.1` D7 "부분 통과" 메모 반영. `docs/plans/2026-05-28-fr-is-01-d7-e2e-issue-tracking-playwright-happy-ed.md` §리뷰 결과 §Advisory CONCERN 종합 (BLOCKER 0) 에서 "별 cleanup PR" 위임된 4건이 본 PR scope.

본 PR scope (4건, Maxi 명시 선택).

| # | severity | 위치 | 내용 |
|---|---|---|---|
| L1 | LOW | `apps/web/e2e/fixtures/issue-fixtures.ts:53-54` | `createIssueViaUI` 가 hardcoded `'ATLAS-42'` 키 — mock `createdIssueFixture.key` 변경 시 silent break |
| L2 | LOW | `apps/web/src/mocks/issue-handlers.ts:38-41` | `resetIssueState()` export 호출처 0 — plan §C1 advisory 약속 미충족, dead code 우려 |
| PE1 | PRE_EXISTING | `apps/web/src/mocks/handlers.test.ts` PATCH/POST | MSW unhandled exception `Body is unusable: Body has already been read` (body consumption race) |
| E2E-5 | gap-I | `apps/web/e2e/issue-edit-conflict.spec.ts` (신규) | 동시 편집 409 회귀 가드 — PR #26 EC-1 이미 구현, 회귀 가드만 추가 |

classify 결과.
- type: qa
- agent: qa-engineer
- primary_bc: issue-tracking (수동 보강, classify 자동 추론 null)
- slug: fr-is-01-d7-cleanup (자동 생성 slug `fr-is-01-d7-cleanup-pr-pr-32-codereview-4-l1-hardc` 단축)

## 도메인 정리

**처리 방식**. fast-track inline (Maxi 선택, PR #32 D2 옵션 A 선례 동일). type=qa, 본 작업 본질 = 테스트/mock 인프라 cleanup → 프로덕션 도메인 모델 변경 0 → grill-with-docs 가치 ≈ 0.

**BC**. issue-tracking (주, 4건 중 L1/L2/PE1/E2E-5 모두 이슈 mock 인프라). identity-access 일부 (auth-handlers.ts 도 PE1 동일 race, 같은 fix 적용 — scope 1줄 확장).

**영향 엔티티**. 신규 0, 변경 0. 기존 mock fixture (`createdIssueFixture`) 와 상태 (`deletedKeys` / `createdIssues`) 활용만.

**새 용어**. 0. 기존 용어 (이슈, 이슈 키, 버전 충돌) 인용만.

**기존 결정 충돌**. 0. 관련 결정.
- `docs/plans/2026-05-28-fr-is-01-d7-e2e-issue-tracking-playwright-happy-ed.md` §리뷰 결과 §Advisory CONCERN 위임 (본 PR scope 출처)
- `docs/plans/2026-05-22-issue-tracking-bc-fr-is-01-crud.md` PR #26 EC-1 409 처리 (E2E-5 검증 대상)
- `docs/decisions/2026-05-22-frontend-logging-policy.md` E2E 콘솔 로깅 정책 (E2E-5 일관)

**glossary 갱신**. 없음. **domain/issue-tracking.md 갱신**. 없음. **ADR 신규**. 없음.

## 스펙

**처리 방식**. Phase A fast-track inline (별 `docs/specs/<date>-<slug>.md` 분리 안 함, plan §스펙 직접 작성). PR #32 D3 옵션 1 선례.

### L1 — e2e fixtures hardcoded ATLAS-42 제거

**현상**. `apps/web/e2e/fixtures/issue-fixtures.ts:53-54` 의 `createIssueViaUI` 헬퍼가 `waitForURL(/\/issues\/ATLAS-42$/)` 와 `return 'ATLAS-42'` 두 곳에서 키를 하드코딩. `apps/web/e2e/issue-ui-regression.spec.ts:22` 의 `expect(key).toBe('ATLAS-42')` 도 동일 하드코딩.

**변경 후 동작**. mock fixture (`apps/web/src/mocks/issue-handlers.ts:13-23` `createdIssueFixture`) 의 `key` 필드가 단일 진실 원천. fixture 변경 시 모든 e2e 자동 동기화.

### L2 — global afterEach resetIssueState 추가

**현상**. `apps/web/src/mocks/issue-handlers.ts:38-41` 의 `resetIssueState()` export 가 코드베이스에서 호출 0회 (`grep -rn resetIssueState` 확인). plan §C1 advisory 약속 미충족 dead code 우려.

**변경 후 동작**. `apps/web/src/test/setup.ts` 의 global afterEach 에서 `server.resetHandlers()` 와 함께 `resetIssueState()` 호출. issue-handlers 모듈-스코프 state (`deletedKeys` Set, `createdIssues` Map) 가 test 간 leak 되지 않도록 격리. resetIssueState export 가 실제 사용처를 가지면서 dead code 우려 해소.

### PE1 — MSW v2.14 body consumption race fix

**현상**. `pnpm vitest run src/mocks/handlers.test.ts --reporter=verbose` 실행 시 stderr 에 `TypeError: Body is unusable: Body has already been read` (undici `consumeBody` 5854) + `[MSW] Encountered an unhandled exception during the handler lookup for "POST/PATCH ..."` 노이즈. 테스트 자체는 PASS (status 만 assert) 였으나 stderr 가 다른 진짜 에러 가려 maintainability 저해.

**원인 분석**. MSW v2.14.6 의 experimental define-network 아키텍처에서 request 가 두 번 clone 됨 — (1) `core/experimental/frames/http-frame.mjs:70` 로그용 + (2) `core/handlers/RequestHandler.mjs:111` `cloneRequestOrGetFromCache` 캐시용. 이후 resolver 안 `await request.json()` 호출 시 undici 가 원본 stream 을 "already read" 로 판정 (Node 22.22.2 + undici).

**변경 후 동작**. 각 핸들러의 body 읽기를 `await request.clone().json()` 으로 변경 — 새 clone 의 fresh body 가 정상 소비됨. 적용 위치.
- `apps/web/src/mocks/issue-handlers.ts:89` (`createIssueHandler`)
- `apps/web/src/mocks/issue-handlers.ts:120` (`updateIssueHandler`)
- `apps/web/src/mocks/auth-handlers.ts:17` (`loginHandler` — scope 1줄 확장, 같은 race 패턴)

stderr 노이즈 0건. 테스트 status 영향 0 (이미 PASS).

### E2E-5 — 동시 편집 409 회귀 가드 spec

**현상**. PR #26 EC-1 (낙관적 업데이트 + 409 VERSION_CONFLICT 롤백 + sonner toast) 가 `useUpdateIssueSummary.ts:71-75` + 단위 테스트 `useUpdateIssueSummary.test.ts:127-235` 로 구현되어 있으나 E2E 가드 부재 — UI 회귀 (예: toast 위치 변경 / 편집 모드 종료 시 의도 외 save 트리거 / 헤딩 셀렉터 변경) 감지 못 함.

**변경 후 동작**. `apps/web/e2e/issue-edit-conflict.spec.ts` (신규, 1 test) 추가.

#### 시나리오 (Given-When-Then)

```
Given 로그인된 사용자 alice (LOCAL provider, dev seed)
  And 새 이슈 (key=createdIssueFixture.key, summary='E2E-5 원본 제목 (롤백 대상)') 생성됨

When  편집 모드 진입 (editTitleButton 클릭)
  And titleEditLabel 입력 필드에 MOCK_CONFLICT_TRIGGER ('__TRIGGER_409__') 입력
  And saveButton 클릭

Then  sonner toast '다른 사용자가 이미 이 이슈를 수정했습니다. 새로고침 후 다시 시도해 주세요.' 노출
  And 헤딩이 원본 summary 로 복원됨 (onError snapshot 롤백 + onSettled invalidate 재조회)
```

**mock 변경**. `apps/web/src/mocks/issue-handlers.ts` 에 `MOCK_CONFLICT_TRIGGER` 상수 + `updateIssueHandler` 분기 추가 — summary 가 트리거 값이면 409 VERSION_CONFLICT 응답. 트리거 값은 production 에서 발생 가능성 ≈ 0 (`__` prefix + suffix).

## Brainstorming Check

**처리 방식**. 본 PR scope 자체가 PR #32 brainstorming 결과 (gap-I + Advisory L1/L2/PE1) 라 별도 brainstorm 1라운드 ≈ 가치 0. Controller 자동 결론 항목.

**Cross-check (skip 정당화)**.
- gap-A NFR — 본 PR scope 외 (PR #32 와 동일 Deferred trigger 유지).
- gap-B 권한 가드 — 본 PR scope 외 (실 RBAC 구현 후 별 FR).
- gap-D~H — PR #32 본 PR 에 이미 처리.
- gap-I — **본 PR 에서 처리 (E2E-5)**.

**새 식별 gap**. 1건.
- **gap-J (controller 자동 적용)**. auth-handlers.ts 도 동일 PE1 race 패턴 → scope 1줄 확장으로 같은 PR 에서 동시 처리 (root cause 동일, 분리 시 다음 PR 에 같은 진단 반복). 메시지 commit `af0bbdd` 에 명시.

## Plan

**처리 방식**. Controller inline (Maxi D5 옵션 A 선례). writing-plans 스킬 우회 누적 +2 (PR #32 +1).

**TDD 변형 본질** (verifier prompt 사전 명시).
- PE1 = bugfix (test commit 0, fix commit 1 — 기존 테스트가 이미 PASS 상태였고 stderr 노이즈만 제거하는 변경. RED phase 부재 정당).
- L1 = chore (e2e fixture 동기화, behavior 변경 0).
- L2 = chore (test setup infra).
- E2E-5 = qa (test commit + mock 트리거 chore commit — TDD red→green→refactor 의 변형. RED = spec 작성 시 트리거 미존재로 spec 실행 시 toast 못 만남. GREEN = mock 트리거 추가로 spec 통과. 실 실행은 원격 환경 브라우저 미지원으로 보류, local 검증 책임).

**Task 분해 (실 commit 단위)**.

### Task 1. PE1 fix — request.clone().json()

**메타**. agent: `frontend-engineer` (mock 인프라 — DEVELOPMENT.md mock = frontend 영역). files. `apps/web/src/mocks/issue-handlers.ts`, `apps/web/src/mocks/auth-handlers.ts`.

**검증**. `pnpm vitest run src/mocks/handlers.test.ts src/mocks/auth-handlers.test.ts --reporter=verbose` 실행 → stderr "Body is unusable" / "unhandled exception" 0건 + 14 tests PASS. **commit `af0bbdd`** (E2E-5 트리거도 같은 commit 안 포함 — gap-J 처리).

### Task 2. L1 fix — fixture key 동기화

**메타**. files. `apps/web/e2e/fixtures/issue-fixtures.ts`, `apps/web/e2e/issue-crud-happy.spec.ts`, `apps/web/e2e/issue-ui-regression.spec.ts`.

**검증**. typecheck / lint clean. playwright --list 에서 spec 인식 (E2E-1/4 와 동일 import 패턴 일관). **commit `8341781`**.

### Task 3. L2 fix — global resetIssueState

**메타**. files. `apps/web/src/test/setup.ts`.

**검증**. vitest 175 tests 유지 (회귀 0). resetIssueState export 호출처 ≥1. **commit `8d8f7b3`**.

### Task 4. E2E-5 spec — 동시 편집 409 회귀 가드

**메타**. agent: `qa-engineer`. files. `apps/web/e2e/issue-edit-conflict.spec.ts` (신규).

**검증**. typecheck / lint clean. playwright --list 에서 신규 spec 인식 (총 20 tests). 실 실행은 원격 환경 보류 (CI frontend-ci.yml playwright 미실행 + cdn.playwright.dev allowlist 차단, PR #32 와 동일 패턴). **commit `33e2093`**.

## 리뷰 결과 (← /bts-codereview 가 채움)

