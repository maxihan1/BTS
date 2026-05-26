<!-- PR #20 후속 — workflows.$key.test.tsx T5-1/T5-2 unskip + WorkflowDiagram mock aria-label stub 패턴 + plan §FR-WF-01 체크박스 진척 갱신 -->
# PR #20 후속 — workflows.$key.test.tsx T5-1/T5-2 unskip + plan §FR-WF-01 진척 갱신

> slug. workflow-detail-test-mock-cleanup
> type. ui (frontend test cleanup + docs)
> agent. frontend-engineer
> primary_bc. project-workflow
> 생성. 2026-05-26

## Brief

PR #20 의 후속 후보 (plan §리뷰 결과 §후속 후보 등재 영역) 작업. 두 트랙.

**Track 1 — workflows.$key.test.tsx 의 T5-1/T5-2 unskip + WorkflowDiagram mock 패턴 조정**.
- PR #20 의 fix 단계에서 `vi.mock('@/components/workflow/WorkflowDiagram', () => ({ WorkflowDiagram: () => null }))` 패턴 도입 — vitest worker scope leak 회피.
- 그러나 mock 가 `null` 반환 → T5-1 의 `getByLabelText(/소프트웨어 개발 기본 워크플로우 다이어그램/)` 셀렉터 부재로 unskip 시 fail.
- **본 PR 해결책**. WorkflowDiagram mock 가 aria-label wrapper 만 stub 반환하도록 조정 → T5-1 의 셀렉터 충족 + 내부 mermaid 부재 유지.

**Track 2 — `docs/plan/product/project-workflow.md` 체크박스 진척 갱신**.
- §1.1 워크플로우 FSM PoC. PR #10 시점 완료 → `[x]` 마킹.
- §1.2 pgmq 트랜잭션 일관성 PoC. PR #10 + PR #14 (`pgmq-postgres-image` ADR) → `[x]` 마킹.
- §2.1 FR-WF-01 D1~D7. PR #10 (D1~D5) + PR #13 (D6) + PR #19 (D7 E2E) → 모두 `[x]` 마킹.
- §2.2 FR-WF-02. active worktree 진행 중 → `[ ]` 유지.
- §NFR 측정값. 미실측 → `___` 유지 (후속 후보로 등재).

본 PR scope 매우 작음 — PR #19 패턴 (plan §inline 통합 fast-track) 적용.

## 도메인 정리

- **BC**. project-workflow (frontend view layer test + plan 추적).
- **영향 엔티티**. 없음. test mock 패턴 + 추적 문서 갱신.
- **새 용어**. 0건.
- **기존 결정 충돌**. 없음.
- **본 작업의 위치**. PR #20 후속 (cleanup) + 추적성 회복. FR-WF-01 의 완료 마무리 (plan 체크박스).
- **grill-with-docs**. skip — test cleanup + docs 갱신, chore 가까운 ui cleanup.

## 스펙

### 사용자 시나리오

- **S1 (개발자, dev 환경)**. workflows.$key.test.tsx 단위 테스트 실행 시 T5-1 (다이어그램 마운트) + T5-2 (404 fallback) 가 정상 실행 → 회귀 즉시 감지. 기존 it.skip 의 silent skip 위험 해소.
- **S2 (Maxi, 추적성)**. `docs/plan/product/project-workflow.md` 의 §1 PoC + §2.1 FR-WF-01 D1~D7 체크박스 확인 시 실제 진척 (PR #10/#13/#19 머지) 과 동기화 → BC 완료 게이트 (§NFR) 까지 남은 작업이 명확.

### 기능 요구사항 (FR)

- **FR-1 (Track 1)**. `apps/web/src/routes/workflows.$key.test.tsx` 의 mock 변경. `WorkflowDiagram: () => null` → `WorkflowDiagram: ({ workflow }: { workflow: { name: string } }) => <div aria-label={\`\${workflow.name} 다이어그램\`} />`. aria-label wrapper stub.
- **FR-2 (Track 1)**. T5-1 `it.skip` 해제 → `it`. T5-1 의 `getByLabelText(/소프트웨어 개발 기본 워크플로우 다이어그램/)` 셀렉터가 mock wrapper 의 aria-label 매칭 → 통과.
- **FR-3 (Track 1)**. T5-2 `it.skip` 해제 → `it`. T5-2 의 `getByText(/워크플로우를 찾을 수 없습니다/)` 는 fallback alert UI 검증이라 mock 와 무관 (`workflows.$key.tsx:64-67`) → 통과.
- **FR-4 (Track 1)**. T5-1/T5-2 의 기존 KDoc (jsdom mermaid getBBox 한계 명시) 갱신 → "PR #21 시점 mock 패턴 조정으로 unskip 가능. mermaid 실제 렌더는 e2e/workflow.spec.ts 4 happy path + T6-5 fallback 가 본질 검증 담당." (PR 번호 자리표시자, 머지 후 정확 번호 확인).
- **FR-5 (Track 2)**. `docs/plan/product/project-workflow.md` 의 §1 + §2.1 체크박스 `[x]` 갱신 + 각 항목 옆에 완료 PR 번호 + 일자 명시.
- **FR-6 (Track 2)**. §NFR 측정값 `___` 옆에 "후속 후보 (k6 + axe 도입 별도 결정)" 1줄 명시 — BC 완료 게이트 미충족 상태 명확.

### 비기능 요구사항 (NFR)

- **NFR-1**. 회귀 0 — `pnpm test` 17 file / **105 passed + 0 skipped** (이전 103 + skipped 2 → T5-1/T5-2 unskip 으로 +2 passed, 0 skipped).
- **NFR-2**. typecheck / lint / build 0 issue. ESLint NEVER-15 본 PR 영역 0 hit.
- **NFR-3**. E2E playwright 9/9 통과 (회귀 0).
- **NFR-4**. test duration 추가 단축 확인 — PR #20 시점 23s. 본 PR mock 패턴 조정 후 동일 또는 단축 (mock wrapper 가 null 보다 약간 무거우나 영향 작음).

### 엣지 케이스

- **EC-1**. T5-1 의 셀렉터가 `/소프트웨어 개발 기본 워크플로우 다이어그램/` regex 라 mock wrapper 의 aria-label 이 정확히 일치해야 함. workflow.name = '소프트웨어 개발 기본 워크플로우' (workflow-fixtures.ts) → aria-label = '소프트웨어 개발 기본 워크플로우 다이어그램'. ✅
- **EC-2**. T5-2 의 셀렉터가 mock 와 무관 (fallback UI 만 검증). ✅
- **EC-3**. WorkflowDiagram.test.tsx 의 기존 `vi.mock('mermaid')` 는 본 PR scope 외. WorkflowDiagram 자체 검증이라 mermaid mock 필수 — 마이그레이션 대상 아님. plan §리뷰 결과 §후속 후보로 명시 제거 (불가능 작업).

### 제약 조건

- backend 변경 0 / generated 변경 0.
- WorkflowDiagram.test.tsx (workflow-diagram 컴포넌트 자체 단위 테스트) 변경 0.
- workflow-fixtures.ts / workflow-fixtures.test.ts 변경 0.

### 측정 가능한 완료 기준

1. workflows.$key.test.tsx 의 T5-1/T5-2 가 `it` (skip 아님) + 통과.
2. mock 가 aria-label wrapper stub 반환 패턴 + KDoc 1줄 갱신.
3. plan §1 + §2.1 체크박스 모두 `[x]` + 완료 PR 번호 + 일자 명시.
4. §NFR 측정값 `___` 옆에 "k6 + axe 도입 후속" 명시.
5. pnpm test 17 file / 105 passed / 0 skipped + typecheck/lint/build 0 issue + E2E 9/9.

## Brainstorming Check

self-brainstorming Phase B sanity check 1회 (PR #19/#20 fast-track 패턴) — gap 2건 발견 + 모두 spec inline 반영.

- **G-1**. WorkflowDiagram mock 의 wrapper 패턴이 다른 test file 에 worker scope leak 줄 가능성? — mock 가 매우 작은 JSX (단순 `<div>` wrapper) 라 worker overhead 무시. PR #20 의 `null` 반환 vs 본 PR 의 wrapper 반환 차이는 < 1ms. test duration 영향 0. **반영 — NFR-4 명시**.
- **G-2**. WorkflowDiagram.test.tsx 의 mermaid mock 도 같이 정리 가능? — 불가. WorkflowDiagram.test.tsx 가 WorkflowDiagram 자체 검증이라 mermaid 의 동작 (render output) 검증이 본질. 컴포넌트 단위 mock 불가 (자기 자신 검증). **반영 — EC-3 + plan §리뷰 결과 §후속 후보 갱신**.

✅ 통과 (1회 iteration, gap 2건 모두 inline 반영).

## Plan

### Task 1. workflows.$key.test.tsx mock 패턴 조정 + T5-1/T5-2 unskip (FR-1~4)

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/routes/workflows.$key.test.tsx`]
- depends-on. []

**RED**. behavior preservation refactor 변형. 기존 T5-1/T5-2 가 it.skip 상태라 RED 부재 — 본 task 자체가 unskip + mock 변경 = behavior 동일 보장 회귀 가드 (PR #20 Task 3 패턴).
- **결정**. 별도 RED 커밋 없음. mock 변경 + unskip 을 한 GREEN commit 으로. behavior preservation 회귀 가드 = 본 task 의 통합 검증 (pnpm test) 단계가 T5-1/T5-2 통과 확인.

**GREEN**.
- 파일. `apps/web/src/routes/workflows.$key.test.tsx`
- 변경 1. `vi.mock('@/components/workflow/WorkflowDiagram', () => ({ WorkflowDiagram: () => null }))` → mock 가 aria-label wrapper stub 반환.
  ```ts
  vi.mock('@/components/workflow/WorkflowDiagram', () => ({
    WorkflowDiagram: ({ workflow }: { workflow: { name: string } }) => (
      <div aria-label={`${workflow.name} 다이어그램`} />
    ),
  }))
  ```
- 변경 2. T5-1 `it.skip(...)` → `it(...)`. KDoc 갱신 — "PR #21 mock 패턴 조정으로 unskip. mermaid 실제 렌더는 e2e/workflow.spec.ts 4 happy path 가 본질 검증 담당."
- 변경 3. T5-2 `it.skip(...)` → `it(...)`. KDoc 갱신 동일.
- 커밋. `feat: workflow-detail-test-mock-cleanup task-1 — WorkflowDiagram mock aria-label wrapper stub + T5-1/T5-2 unskip`.

**REFACTOR**. KDoc 정리 + mock wrapper 의 의도 명시 코멘트 1줄 추가.
- 커밋. `refactor: workflow-detail-test-mock-cleanup task-1 — mock wrapper 의도 KDoc 1줄 추가`.

**검증**. `pnpm --filter web test src/routes/workflows.\$key.test.tsx` — T5-1 + T5-2 + T-NEW-1 + T-NEW-2 모두 4건 통과 + skipped 0.

---

### Task 2. plan §FR-WF-01 체크박스 진척 갱신 (FR-5, FR-6)

**메타**.
- agent. `frontend-engineer` (docs 갱신, agent 영역 무관)
- files. [`docs/plan/product/project-workflow.md`]
- depends-on. []

**TDD 변형**. docs 만 갱신 — RED/GREEN/REFACTOR 단계 부재. 단일 commit.

**작업**.
- `docs/plan/product/project-workflow.md` 의 다음 체크박스 `[ ]` → `[x]` + 완료 PR/일자 명시.
  - §1.1 워크플로우 FSM PoC. 5건 체크박스 모두 `[x]` (PR #10, 2026-05-22).
  - §1.2 pgmq 트랜잭션 일관성 PoC. 5건 체크박스 모두 `[x]` (PR #10 backend + PR #14 ADR pgmq-postgres-image, 2026-05-22).
  - §2.1 FR-WF-01 D1~D7. 7건 체크박스 모두 `[x]` (D1~D5 backend = PR #10, D6 frontend = PR #13, D7 E2E = PR #13 + PR #19, 2026-05-22 ~ 2026-05-23). 후속 cleanup = PR #16/#20/#21.
- §NFR 측정값 표 — 5건 `___` 옆에 비고 컬럼에 "k6 + axe 도입 후속" 1줄 명시. BC 완료 게이트 미충족 상태 명확.
- §BC 완료 조건 — §2 (FR-WF 2개) 의 진척 = FR-WF-01 ✅ / FR-WF-02 진행 중 명시.

**커밋**. `docs: workflow-detail-test-mock-cleanup task-2 — plan §FR-WF-01 진척 갱신 (체크박스 [x] + 완료 PR 번호)`.

**검증**. `cat docs/plan/product/project-workflow.md | grep -E "^\- \[" | grep -c "\[x\]"` 가 §1 (10건) + §2.1 (7건) = 17건 `[x]` 마킹.

---

### Task 3. 통합 검증 (controller wave 종료 후)

**메타**.
- agent. controller (메인 agent)
- files. 없음 (실행만)
- depends-on. [1, 2]

**검증 단계**.
1. `pnpm --filter web typecheck` 0 issue.
2. `pnpm --filter web lint` 0 issue.
3. `pnpm --filter web test` 17 file / **105 passed / 0 skipped** (이전 103 + skipped 2 → unskip 으로 +2 passed, -2 skipped).
4. `pnpm --filter web build` 0 error.
5. `pnpm --filter web exec playwright test` 9/9 통과.
6. `git diff --stat origin/main -- backend/ apps/web/src/generated/` empty.
7. test duration 비교 — PR #20 시점 23s 대비 동일 또는 단축.

## Plan 메타

- task 수. 3 (Task 1 = mock 패턴 + unskip / Task 2 = plan 갱신 / Task 3 = 통합 검증).
- 예상 wave 수. **1 wave** (Task 1, 2 files 겹침 0 + depends-on 0 → 2-병렬). Task 3 = 통합 검증.
- TDD 강제. Task 1 = TDD 변형 (behavior preservation, RED 부재). Task 2 = docs 단일 commit. Task 3 = 통합 검증.
- 병렬 dispatch. 1 wave / 2-병렬. **Task 1 implementer 의 git commit 시 `git add <file>` 단위 명시 stage 강제** (PR #20 learnings #1 병렬 dispatch race 예방).

## 리뷰 결과

### plan-design-review (skip, 2026-05-26)

**결정 — skip**. type=ui 라도 사용자 시각 변화 0 (단위 테스트 mock 패턴 + docs 갱신). PR #16/#20 의 skip 결정 정신 일관.

### plan-eng-review (self, 2026-05-26)

self-eng-review 1회 (PR #19/#20 fast-track). 검토 영역.

1. **mock wrapper 의 aria-label 셀렉터 정확 일치**. ✅ workflow.name = '소프트웨어 개발 기본 워크플로우' + wrapper = `\`${workflow.name} 다이어그램\`` = '소프트웨어 개발 기본 워크플로우 다이어그램' → T5-1 의 regex `/소프트웨어 개발 기본 워크플로우 다이어그램/` 정확 매칭.
2. **behavior preservation refactor 의 RED 부재 정당화**. ✅ T5-1/T5-2 가 기존 it.skip 라 RED 부재. unskip + mock 변경 한 GREEN commit = 통합 검증 단계가 회귀 가드.
3. **plan 체크박스 갱신의 정확성**. ✅ history.md 의 PR #10/#13/#19 entry 와 plan §FR-WF-01 D1~D7 매핑 명확. §NFR `___` 유지 + 비고에 후속 명시 = BC 완료 게이트 미충족 상태 명확.
4. **병렬 dispatch race 예방** (PR #20 learnings #1). ✅ implementer prompt 에 "git add <file> 단위 명시 stage, -A/. 금지" 명시.
5. **PR #20 learnings #2 (vi.mock worker leak) 회귀 위험**. ✅ 본 PR 의 mock wrapper 패턴은 매우 작은 JSX (단순 div) — worker overhead 무시. PR #20 의 mermaid mock 영역 좁힘 패턴 정신 유지.

**결정 — ✅ PASS**. BLOCKER 0, 주의 0.

