# PR #16 후속 — workflows.$key 의 404 fallback E2E 1건 추가

> slug. workflow-detail-404-e2e
> type. qa (qa-engineer)
> primary BC. project-workflow (frontend view layer)
> 생성. 2026-05-23
> 베이스. main `dc6f46e` (PR #16 머지 이후)

## Brief

PR #16 (FR-WF-01 후속 — WorkflowDiagram C-2/C-3 정리) `/bts-codereview` 의 informational CONCERN-1 후속. PR #16 의 D5 옵션 C (workflows.$key.test.tsx T5-1/T5-2 `it.skip` + E2E 위임) 위임 약속 완전 충족.

### 사용자 원문
> PR #16 후속 — CONCERN-1 해소. workflows.$key.tsx 의 404 fallback 시나리오를 Playwright E2E 로 추가. apps/web/e2e/workflow.spec.ts 에 1건 신규 — 사용자가 존재하지 않는 워크플로우 key (예. /workflows/non-existent) 로 진입 → MSW 가 404 응답 → 페이지가 "워크플로우를 찾을 수 없습니다" 텍스트 표시 검증. PR #16 의 D5 옵션 C (it.skip + E2E 위임) 약속 완전 충족.

### 사실 검증 (plan/spec 작성 전 확인)

- `apps/web/src/routes/workflows.$key.tsx:62-68` — `error !== null || data === undefined` 시 `<div role="alert" class="text-destructive">워크플로우를 찾을 수 없습니다</div>` 표시. `useQuery` `retry: false` 라 첫 fail 즉시 alert 진입.
- `apps/web/src/mocks/workflow-handlers.ts:21-27` — unknown key 시 `{ message: '워크플로우를 찾을 수 없습니다' }` + status 404 응답. 본 PR 의 E2E 가 MSW worker 통해 404 트리거 가능.
- 기존 4 happy path E2E (T6-1~T6-4) 가 `navigateAndWaitForDiagram` helper 로 진입 — 본 PR 은 unknown key 진입이라 helper 직접 사용 안 함. `page.goto` + alert 텍스트 검증으로 단순 구현.

## 도메인 정리

- **BC**. project-workflow (frontend view layer).
- **영향 엔티티**. 0 — 도메인 모델 / 행동 / 책임 변경 없음. E2E 시나리오 추가만.
- **새 용어**. 0.
- **기존 ADR 충돌**. 0.
- **grill-with-docs skip**. type=qa + E2E 시나리오 1건 추가 = 도메인 영향 0. PR #12 / #16 의 동일 패턴.

## 스펙

### 사용자 시나리오 (Given-When-Then)

**S1. unknown workflow key 진입 시 fallback UI 표시**.
- **Given**. 사용자가 로그인된 상태 (alice/password Local).
- **When**. 브라우저가 `/workflows/non-existent` 같이 backend / fixture 에 존재하지 않는 key 로 진입한다.
- **Then**. 페이지가 `role="alert"` 텍스트 "워크플로우를 찾을 수 없습니다" 표시. mermaid 다이어그램 렌더 0. 콘솔 에러 없음 (예상된 404).

### FR

**FR-1**. `apps/web/e2e/workflow.spec.ts` 에 신규 시나리오 T6-5 추가 (T6-1~T6-4 happy path 뒤).

```ts
test('T6-5 unknown-key — 404 응답 → fallback UI ("워크플로우를 찾을 수 없습니다") 표시', async ({ page }) => {
  // 인증 우회 — 기존 E2E helper 와 같은 패턴 (이미 login E2E 가 alice/password Local 로 진입한 후 상태 공유 가능)
  // 단순 구현. page.goto(/workflows/non-existent) → MSW 가 404 → alert 텍스트 검증.
  await page.goto('/workflows/non-existent')

  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  await expect(alert).toHaveText('워크플로우를 찾을 수 없습니다')

  // mermaid 다이어그램 렌더 0 검증 — 일반 상태 노드 0건
  const stateNodes = page.locator('.statediagram-state')
  await expect(stateNodes).toHaveCount(0)
})
```

### NFR

- **NFR-1 회귀 0**. 기존 4 happy path E2E (T6-1~T6-4) 변경 0. login E2E + smoke + workflows 4 = 총 8 시나리오 유지 + T6-5 추가 = 9 시나리오.
- **NFR-2 timing**. workflow.spec.ts 의 helper (`navigateAndWaitForDiagram`) 우회 — `page.goto` 직접 호출. alert 텍스트 검증 < 1초 예상.
- **NFR-3 인증 전제**. 본 시나리오는 인증 후 진입. workflow.spec.ts 의 기존 helper 가 이미 인증 처리 — beforeAll 또는 fixtures 패턴 확인 필요. 만약 미처리 시 본 시나리오에서 인증 단계 추가.

### 엣지 케이스

**EC-1**. WorkflowDetailPage 의 `isLoading` 분기 — page.goto 직후 로딩 상태 가능. Playwright 의 `expect.toBeVisible()` 은 기본 waitFor 적용 — 로딩 후 alert 까지 자동 대기. 추가 처리 불필요.

**EC-2**. MSW worker 가 dev 모드에서 활성화 — `import.meta.env.DEV` 가드. Playwright 가 vite dev 서버 자동 실행 (`playwright.config.ts` 의 `webServer`) 로 dev 모드 = MSW worker 활성. 가정 검증 — 기존 4 happy path E2E 가 같은 가정 하에 동작.

**EC-3**. unknown key 의 정확한 문자열 — `'non-existent'` 권장. `workflow-handlers.ts` 의 `allWorkflowFixtures.find(w => w.key === params['key'])` 는 4 fixture key (software-default / bug-tracking / simple / kanban-basic) 외 모두 404. 'non-existent' 가 의미 명확.

### Out of scope

- backend 변경 0. MSW handler 변경 0 (기존 404 응답 그대로).
- WorkflowDetailPage 의 fallback UI 디자인 변경 0 (PR #13 의 EC-3 구현 그대로).
- 404 외 다른 에러 (500 / 네트워크 timeout) 시나리오 — 후속 PR 후보.
- axe 접근성 자동 검사 (D4) — 별 PR.

## Plan

### Task 1. workflow.spec.ts 에 T6-5 unknown-key 404 fallback 시나리오 추가

**메타**.
- agent. `qa-engineer`
- files. [`apps/web/e2e/workflow.spec.ts`]
- depends-on. []

**TDD (E2E 변형 — PR #12 / #16 패턴)**. RED = 시나리오 작성 시점에 backend / fixture 에 'non-existent' key 부재 = 자연스러운 RED 상태 (테스트 작성 후 첫 실행 시 alert 검증 결과로 RED→GREEN 즉시 전환).

**RED + GREEN**. `apps/web/e2e/workflow.spec.ts` 에 T6-5 시나리오 추가 (T6-4 뒤 append).

작성 후 `pnpm --filter web test:e2e -- workflow.spec.ts` 실행 → 9/9 pass 확인.

커밋. `test: workflow-detail-404-e2e — T6-5 unknown-key 404 fallback 시나리오 추가 (PR #16 D5 옵션 C 위임 충족)`.

**REFACTOR**. 없음 (시나리오 1건 추가만).

**검증**. `pnpm --filter web test:e2e` 전체 9/9 pass. typecheck/lint/build 0 issue 변경 없음 (E2E 코드만 추가, src/ 변경 0).

## Plan 메타

- task 수. 1
- wave. 1 (단일 task).
- 예상 시간. 약 5분 (qa-engineer dispatch + E2E 검증).
- TDD 강제. E2E 변형 (test 작성 = RED → 실행 = GREEN, 별도 commit 분리 불필요).
- 추가 검증. `pnpm --filter web test:e2e` 전체 9/9 pass + 기존 unit test 97 + skipped 2 회귀 0.

## 리뷰 결과 (← /bts-review-plan 채움)

type=qa 의 fast-track skip 조건 적용 — review-plan 스킵. /bts-codereview 단계에서 code-reviewer agent + /review 가 검증.
