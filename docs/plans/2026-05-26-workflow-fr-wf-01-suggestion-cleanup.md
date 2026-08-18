<!-- PR #13 FR-WF-01 codereview SUGGESTION 2건 정리 plan — workflow-fixtures description 1:1 + workflows.$key.tsx 빈 description 패턴 정리 -->
# PR #13 FR-WF-01 codereview SUGGESTION 2건 정리

> slug. workflow-fr-wf-01-suggestion-cleanup
> type. ui
> agent. frontend-engineer
> primary_bc. project-workflow
> 생성. 2026-05-26

## Brief

PR #13 (FR-WF-01 frontend) 의 codereview 결과 SUGGESTION 2건 후속 정리. PR #16 (C-2/C-3) + PR #19 (D5 위임) 머지 후 잔여 SUGGESTION 2건만 남음.

**SUGGESTION 1** — `workflow-fixtures.ts` 의 transition `description` 문자열이 backend `*.yaml` seed 의 description 과 다름. fixture mirror data 가 production 코드와 정렬되어야 함.

**SUGGESTION 2** — `workflows.$key.tsx` 의 빈 description 분기 (`data.description !== ''`) 가 의도 코멘트 없이 명확성 부족. backend 가 description 미제공 시 빈 문자열 반환 (PR #13 Task 9 fallback) 인데 null/undefined 처리도 분기에 섞임.

**Maxi 사전 결정 (2026-05-26)**.
- SUGGESTION 1 = 옵션 A — fixture description 을 backend yaml seed 와 1:1 정적 일치 + 회귀 가드 it.each 신규.
- SUGGESTION 2 = 패턴 정리 — `data.description` truthy check 로 통일 + KDoc 1줄.

## 도메인 정리

- **BC**. project-workflow (frontend view layer)
- **영향 엔티티**. 없음. fixture (MSW mirror data) + UI 컴포넌트 분기 정리만. 도메인 모델 (Workflow / State / Transition) 변경 0.
- **새 용어**. 0건. glossary.md 의 "전환" / "워크플로우" / "게이트" 모두 기존 등재.
- **기존 결정 충돌**. 없음.
- **본 작업의 위치 — `workflow-yaml-vs-db-storage` ADR 강화**. PR #10 시점 ADR 5건 (`workflow-validator-terminology` / `workflow-expression-parser-spel` / `workflow-yaml-vs-db-storage` / `workflow-bc-cross-bc-port` / `v001-initial-schema-non-concurrent`) 모두 따름. 옵션 A 채택은 YAML = production source of truth 결정의 frontend 일관성 강화 — fixture mirror 가 YAML 과 1:1 정렬.
- **관련 ADR 신규**. 0건. cleanup + 기존 결정 일관 → ADR 작성 불요.
- **BC 격리**. 안전. backend 변경 0 (workflow-fixtures.ts 는 yaml seed 의 description 을 정적 복사 → backend `*.yaml` 파일 변경 불요).
- **grill-with-docs**. skip — fixture mirror + UI 분기 정리는 chore 에 가까운 ui cleanup. 도메인 모델 영향 0 / 신규 용어 0 / 기존 결정 충돌 0 → grill 비용 > 효익.

### 위 학습 사용 (learnings.md 참조)

- **2026-05-23 fixture 옵션 B 패턴** — mirror data 영역 (fixture / seed / mock / stub) 의 형식이 production 코드와 정렬되어야 하는 경우 → helper 호출 패턴이 본질 차단. **본 작업은 description (문자열 본문) 영역이라 옵션 B (yaml import) cross-stack 비용이 크고, 옵션 A (1:1 복사 + 회귀 가드) 가 현실적.** PR #16 D2 의 transition.key (식별자) 와 description (본문) 영역 차이 명확.
- **2026-05-22 BC 격리 예외** — 본 작업은 frontend only 라 예외 적용 안 됨 (참조만).


## 스펙

본 PR scope 작음 — PR #19 패턴 (plan §inline 통합 fast-track) 적용. 별도 `docs/specs/` 파일 미생성.

### 사용자 시나리오 (Given-When-Then)

- **S1**. dev 환경에서 워크플로우 상세 페이지 본 사용자 (Maxi 본인) 가 MSW 응답을 통해 표시되는 description 이 backend yaml seed 의 description 과 일치. 잘못된 dev 미리보기 회피.
  - Given. workflows.$key.tsx 에서 `software-default` workflow 조회.
  - When. MSW workflow-handlers 가 softwareDefaultFixture 반환.
  - Then. 사용자가 본 description = `"Open → In Progress → In Review → Done → Closed 흐름의 표준 소프트웨어 개발 워크플로우"` (backend yaml 과 일치).

- **S2**. backend yaml seed description 이 향후 변경될 때 fixture description 미동기화 = `workflow-fixtures.test.ts` 회귀 가드 fail → CI 차단.
  - Given. yaml seed description 변경 (예. typo fix).
  - When. fixture description 미수정.
  - Then. `workflow-fixtures.test.ts` 의 description 회귀 가드가 expected map 불일치로 fail. 사람 의존 1회는 발생 (옵션 A 한계 명시).

- **S3**. 빈 description workflow (`data.description === ''`) 상세 페이지 본 사용자 — description `<p>` 미렌더링. 다이어그램만 표시.
  - Given. workflow detail 페이지가 description 이 빈 문자열인 workflow 받음.
  - When. WorkflowDetailPage 렌더링.
  - Then. `<header>` 안 `<h1>` (name) 만 표시, `<p>` (description) 미렌더링. 기존 동작 보존.

### 기능 요구사항 (FR)

- **FR-1**. `workflow-fixtures.ts` 의 4 workflow description 을 backend yaml seed 의 description 과 1:1 정적 일치 (옵션 A 채택, Maxi 사전 결정 2026-05-26).
  - software-default. `"Open → In Progress → In Review → Done → Closed 흐름의 표준 소프트웨어 개발 워크플로우"`
  - bug-tracking. `"Reported → Triaged → In Progress → Resolved → Closed 흐름의 버그 추적 워크플로우"`
  - simple. `"To Do → Doing → Done 3단계 단순 워크플로우"`
  - kanban-basic. `"Backlog → Ready → In Progress → Done 흐름의 칸반 기본 워크플로우"`

- **FR-2**. `workflow-fixtures.test.ts` 에 description 회귀 가드 it.each 신규. 4 fixture × expected description map 검증. yaml 변경 시 fixture+map 둘 다 갱신 필요 — 사람 의존 1회는 발생 (옵션 A 한계 학습으로 명시).

- **FR-3**. `workflows.$key.tsx:75` 빈 description 분기 패턴 정리. `data.description !== ''` → `data.description.length > 0` (옵션 c — 의도 명시 + PR #16 식별자 보수적 선택 정신 일관). KDoc 1줄 — "backend toDto 가 description 미제공 시 빈 문자열 반환 (PR #13 Task 9 fallback) — `.length > 0` 으로 빈 문자열 분기 명시" inline 코멘트.

- **FR-4 (Brainstorming 발견 G-1)**. `workflows.test.ts` (api/) 의 4 workflow inline description 4건도 본 PR scope 에 포함 (PR #16 D4 패턴 — workflows.test.ts inline 17건 일괄). 별도 PR 분리 시 description 영역 drift 잠재.

### 비기능 요구사항 (NFR)

- **NFR-1**. 회귀 0 — `pnpm test` 전체 통과 (97 + skipped 2). description 변경이 snapshot test (`WorkflowDiagram.test.tsx.snap`) 에 영향 0 (WorkflowDiagram 은 `description` 사용 안 함, name + states + transitions 만 렌더링).

- **NFR-2**. typecheck/lint/build 0 issue. ESLint NEVER-15 (console.error) 본 PR scope 영역 0 위반.

- **NFR-3**. E2E 회귀 0 — `pnpm test:e2e` 9/9 통과. workflow.spec.ts T6-1 (software-default detail) 의 description 검증 셀렉터 변경 시 함께 업데이트 (현재 셀렉터 검토 필요).

### 엣지 케이스

- **EC-1**. yaml seed description 에 큰따옴표 escape 필요한 경우 — 본 작업 시점 4건 모두 큰따옴표 없음 (확인 완료). 향후 yaml 에 escape 추가 시 fixture 의 JS 문자열에도 동일하게 escape 필요. 회귀 가드 catch.

- **EC-2**. backend yaml description 이 빈 문자열인 workflow (현재 0건). 향후 도입 시 fixture description 도 빈 문자열 → S3 시나리오 적용 (UI `<p>` 미렌더링).

- **EC-3**. workflows.test.ts 의 description 검증 — fixture import 로 통일 vs inline 4건 유지. **inline 4건 명시 유지가 PR #16 D4 패턴 일관 + test 가독성 우위.**

### 제약 조건

- backend yaml 파일 변경 0 (frontend only — BC 격리 안전).
- src/generated/* 변경 0 (jOOQ codegen 영향 없음).
- backend `*.kt` / `*.java` 변경 0 (DTO + Repository + YamlSeedService 모두 무관).
- ESLint flat config (PR #12) NEVER-15 (no-console) 위반 0.

### 측정 가능한 완료 기준

1. `workflow-fixtures.ts` 의 4 description 이 backend yaml seed 와 1:1 일치 (grep 비교 0 diff).
2. `workflow-fixtures.test.ts` 의 description 회귀 가드 it.each 4건 통과.
3. `workflows.test.ts` 의 4 inline description 도 yaml 과 1:1 일치 + 기존 테스트 통과.
4. `workflows.$key.tsx:75` 의 분기 `data.description.length > 0` + KDoc 1줄 + 기존 동작 보존 (S3 시나리오).
5. `pnpm test` 97 + skipped 2 통과, `pnpm test:e2e` 9/9 통과, `pnpm verify` (lint + typecheck + test + build) 0 exit code.

## Brainstorming Check

self-brainstorming Phase B sanity check 1회 (PR #19 fast-track 패턴) — gap 3건 발견 + 모두 spec inline 반영.

- **G-1** (수정 가능, inline 반영). `workflows.test.ts` (api/) 의 4 inline description 도 잘못된 값. PR #16 D4 (inline 17건 transition.key 일괄) 패턴 일관 — 본 PR scope 에 포함. **FR-4 신설**.
- **G-2** (수정 가능, inline 반영). SUGGESTION 2 의 truthy check 옵션 3안 (`&&` / `Boolean()` / `.length > 0`) 중 선택 필요. PR #16 #식별자 보수적 선택 정신 일관 — 의도 명시 `.length > 0` 채택. **FR-3 명시**.
- **G-3** (수정 가능, inline 반영). 회귀 가드의 한계 (옵션 A 라 drift 본질 차단 안 됨, expected map 도 사람 수정 의존) 를 plan + 본 회귀 가드 코멘트에 명시. **FR-2 명시** + 옵션 A 한계 학습 후보.

✅ 통과 (1회 iteration, gap 3건 모두 inline 반영). gap 도 모두 cleanup 영역 — Maxi 결정 필요 없음 (FR-4 본 PR scope 확장은 명시적 권장).

## Plan

### Task 1. workflow-fixtures.ts description 1:1 일치 + 회귀 가드 (FR-1 + FR-2)

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/mocks/workflow-fixtures.ts`, `apps/web/src/mocks/workflow-fixtures.test.ts`]
- depends-on. []

**RED**.
- 파일. `apps/web/src/mocks/workflow-fixtures.test.ts`
- 테스트.
  ```ts
  describe('workflow-fixtures description 형식 검증 (FR-1 회귀 가드)', () => {
    const expectedDescriptions: Record<string, string> = {
      'software-default': 'Open → In Progress → In Review → Done → Closed 흐름의 표준 소프트웨어 개발 워크플로우',
      'bug-tracking': 'Reported → Triaged → In Progress → Resolved → Closed 흐름의 버그 추적 워크플로우',
      simple: 'To Do → Doing → Done 3단계 단순 워크플로우',
      'kanban-basic': 'Backlog → Ready → In Progress → Done 흐름의 칸반 기본 워크플로우',
    }
    it.each(allWorkflowFixtures)(
      '$key — fixture description 이 backend yaml seed 와 일치',
      (workflow) => {
        expect(workflow.description).toBe(expectedDescriptions[workflow.key])
      },
    )
  })
  ```
- 실패 메시지 (예상). 4 fixture 의 description 이 expectedDescriptions 값과 다름 → 4건 fail.

**GREEN**.
- 파일. `apps/web/src/mocks/workflow-fixtures.ts`
- 4 fixture 의 description 을 backend yaml seed 와 일치하는 값으로 변경.
  - softwareDefaultFixture. `description: 'Open → In Progress → In Review → Done → Closed 흐름의 표준 소프트웨어 개발 워크플로우'`
  - bugTrackingFixture. `description: 'Reported → Triaged → In Progress → Resolved → Closed 흐름의 버그 추적 워크플로우'`
  - simpleFixture. `description: 'To Do → Doing → Done 3단계 단순 워크플로우'`
  - kanbanBasicFixture. `description: 'Backlog → Ready → In Progress → Done 흐름의 칸반 기본 워크플로우'`

**REFACTOR**.
- `workflow-fixtures.ts` 상단 코멘트 (line 5) 갱신. "backend WorkflowDto 의 description / transition.key 누락 보완" → "description 도 backend yaml seed 와 1:1 일치 (PR #20 회귀 가드 + 옵션 A 한계 — yaml 변경 시 fixture + expected map 둘 다 갱신 필요)" 명시.
- `workflow-fixtures.test.ts` 의 description 회귀 가드 위에 옵션 A 한계 코멘트 1줄 — "옵션 A (1:1 정적 복사) 채택. yaml seed 변경 시 본 expectedDescriptions 도 갱신 필요. drift 본질 차단은 옵션 B (yaml import) 후속 후보."

**검증**. `pnpm --filter web test workflow-fixtures` (workflow-fixtures.test.ts 의 transition.key + description 두 회귀 가드 모두 통과).

---

### Task 2. workflows.test.ts inline 4건 description 갱신 (FR-4)

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/api/workflows.test.ts`]
- depends-on. []

**RED**. TDD 변형 — 별도 RED 없음 (PR #16 Task 4 패턴 — workflows.test.ts inline 17건 transition.key 일괄 갱신 시 별도 RED 없이 진행). 본질 회귀 가드는 Task 1 의 description 회귀 가드 (workflow-fixtures.test.ts) 가 fixture 영역에서 담당. workflows.test.ts 의 inline mock 은 Zod parse 동작 검증이라 description 값 자체보다 형식 검증이 본질.

**GREEN**.
- 파일. `apps/web/src/api/workflows.test.ts`
- 4 inline mock 의 description 값을 backend yaml seed 와 일치하는 값으로 변경 (Task 1 GREEN 의 4 description 동일).
  - line 15 `description: '소프트웨어 개발 팀을 위한 기본 워크플로우'` → `description: 'Open → In Progress → In Review → Done → Closed 흐름의 표준 소프트웨어 개발 워크플로우'`
  - line 36 `'버그 수명 주기를 추적하는 워크플로우'` → `'Reported → Triaged → In Progress → Resolved → Closed 흐름의 버그 추적 워크플로우'`
  - line 56 `'3단계 단순 워크플로우'` → `'To Do → Doing → Done 3단계 단순 워크플로우'`
  - line 72 `'칸반 방식의 기본 워크플로우'` → `'Backlog → Ready → In Progress → Done 흐름의 칸반 기본 워크플로우'`
- 기존 test (Zod parse + description 타입 검증) 통과 확인.

**REFACTOR**. workflows.test.ts 상단 코멘트 갱신 (있다면) — "fixture mirror data 와 backend yaml seed 일치 (PR #20 FR-4)" 한 줄 명시. 없으면 생략 (test 가독성 영역).

**검증**. `pnpm --filter web test workflows.test` (api/workflows.test.ts 모든 test 통과).

---

### Task 3. workflows.$key.tsx 빈 description 패턴 정리 + KDoc (FR-3)

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/routes/workflows.$key.tsx`, `apps/web/src/routes/workflows.$key.test.tsx`]
- depends-on. []

**RED**. behavior preservation refactor — 새 test 2건 추가. `vi.mock('mermaid', ...)` 신규 추가 (WorkflowDiagram.test.tsx 패턴 복사 — mermaid render() 우회로 jsdom getBBox timeout 회피).
- 파일. `apps/web/src/routes/workflows.$key.test.tsx`
- 테스트 신규.
  ```ts
  vi.mock('mermaid', () => ({
    default: {
      initialize: vi.fn(),
      render: vi.fn().mockResolvedValue({ svg: '<svg></svg>' }),
    },
  }))

  // 새 describe 블록 'description rendering (FR-3)' 안에 it 2건.
  it('description 이 빈 문자열인 workflow 의 경우 description <p> 를 미렌더링한다', async () => {
    // MSW handler override — description = '' 인 mock workflow 응답.
    // queryByText 로 '' 또는 placeholder 미존재 확인.
  })

  it('description 이 있는 workflow 의 경우 description <p> 를 렌더링한다', async () => {
    // workflowHandlers (정상 fixture, description 채워짐).
    // findByText 로 description 텍스트 노드 존재 확인.
  })
  ```
- 실패 메시지 (예상). 본 새 test 는 현재 패턴 `!== ''` + 후속 패턴 `.length > 0` 모두 통과 (behavior 동일) → RED 가 fail 안 함. **이것이 본 task 의 본질 — behavior preservation refactor 회귀 가드.** plan 본문 명시.

**GREEN**.
- 파일. `apps/web/src/routes/workflows.$key.tsx:75`
- 변경 1줄. `{data.description !== '' && (...)}` → `{data.description.length > 0 && (...)}`.
- 새 test 2건 통과 확인 (behavior 동일이므로 자동 통과).

**REFACTOR**.
- 파일. `apps/web/src/routes/workflows.$key.tsx:75` 의 분기 위에 KDoc 1줄 (또는 JSX inline 코멘트) 추가.
  ```tsx
  {/* backend toDto 가 description 미제공 시 빈 문자열 반환 (PR #13 Task 9 fallback) — .length > 0 으로 빈 문자열 분기 명시 */}
  {data.description.length > 0 && (
    <p className="text-muted-foreground text-sm">{data.description}</p>
  )}
  ```

**검증**. `pnpm --filter web test workflows.\$key` (T5-1/T5-2 it.skip 유지 + 새 description rendering 2건 통과).

---

### Task 4. 통합 검증 (controller wave 종료 후)

**메타**.
- agent. controller (메인 agent — bts-impl 의 마지막 단계)
- files. 없음 (실행만)
- depends-on. [1, 2, 3]

**검증 단계**.
1. `pnpm --filter web typecheck` 0 issue.
2. `pnpm --filter web lint` 0 issue (ESLint flat config NEVER-15 본 PR 영역 0 hit).
3. `pnpm --filter web test` 97 + skipped 2 통과 (description 회귀 가드 4건 + behavior preservation 2건 추가 → 99 + skipped 2 예상).
4. `pnpm --filter web build` 0 error.
5. `pnpm --filter web exec playwright test` 9/9 통과 (workflow.spec.ts 5 + login 3 + smoke 1).
6. `git diff --stat backend/` empty (backend 변경 0).
7. `git diff --stat src/generated/` empty (jOOQ 변경 0).

## Plan 메타

- task 수. 4 (Task 1~3 = TDD 단위, Task 4 = 통합 검증).
- 예상 wave 수. **1 wave** (PR #7 패턴, 가장 빠른 케이스). Task 1, 2, 3 모두 `depends-on: []` + files 겹침 0 → 단일 wave 3-병렬. Task 4 = wave 종료 후 controller 일괄 검증.
- 예상 시간. 직렬 기준 약 6~8분, 병렬 wave 적용 시 약 3~5분.
- TDD 강제. Task 1 = 정규 RED→GREEN→REFACTOR. Task 2 = TDD 변형 (PR #16 패턴, 별도 RED 없이 mock data 갱신). Task 3 = behavior preservation refactor (RED 단계는 새 test 추가 후 두 패턴 모두 통과 — 본질 = 회귀 가드).
- 병렬 dispatch. 1 wave / 3-병렬. learnings PR #7 "wave 1 task 수 == 전체 task 수" 케이스 (의존성 0 + 파일 겹침 0 = 가장 빠른 케이스).
- 추가 검증. typecheck / lint / test / build / E2E / backend·generated diff empty 확인.



## 리뷰 결과

### plan-design-review (skip, 2026-05-26)

**결정 — skip**. type=ui 라도 사용자 시각 변화 0. 색상/타이포/여백/레이아웃 결정 0, DESIGN.md 변경 0. 변경 본질 = MSW dev 응답의 description 텍스트 교정 (잘못 → 정확) + JSX truthy check 패턴 정리 (behavior 동일). design 결정 영역 아님.

PR #16 의 plan-design-review skip 결정 (type=ui 라도 사용자 시각 변화 0 시 비용 > 효익) 패턴 정확 일치. PR scope 더 작음 (PR #16 = WorkflowDiagram 의 classDef 식별자 + fixture 옵션 B 본질 변화, 본 PR = description 텍스트 + JSX 패턴 정리).

### plan-eng-review (self, 2026-05-26)

self-eng-review 1회 (PR #19 patten — 작은 후속 PR plan §inline 통합). 검토 영역 6항목.

1. **회귀 가드 본질 (FR-2) 의 옵션 A 한계 명시 충분한지**. ✅ plan §스펙 FR-2 + Task 1 REFACTOR 모두 명시 — "yaml seed 변경 시 fixture + expectedDescriptions 둘 다 갱신 필요. drift 본질 차단은 옵션 B (yaml import) 후속 후보."

2. **behavior preservation refactor (Task 3) 의 RED 단계 명시 적절한지**. ✅ plan §Task 3 RED 본문에 "본 새 test 는 현재 패턴 `!== ''` + 후속 패턴 `.length > 0` 모두 통과 (behavior 동일) → RED 가 fail 안 함. **이것이 본 task 의 본질 — behavior preservation refactor 회귀 가드.**" 명시. TDD 변형 정당화 충분.

3. **wave 1 단일 wave 가 PR #7 패턴 일관한지**. ✅ Task 1 files = `workflow-fixtures.{ts, test.ts}` / Task 2 files = `workflows.test.ts` / Task 3 files = `workflows.$key.{tsx, test.tsx}`. 파일 겹침 0 + depends-on 0. PR #7 "wave 1 task 수 == 전체 task 수" 케이스 정확 일치 (가장 빠른 케이스).

4. **backend 변경 0 확인 (BC 격리)**. ✅ plan §도메인 정리 + §스펙 제약 조건 모두 backend 변경 0 명시. workflow-fixtures 는 backend yaml seed 의 description 을 정적 복사라 backend `*.yaml` 변경 불요. learnings #BC 격리 예외 패턴 적용 안 됨 (frontend only).

5. **mermaid mock 신규 추가 (Task 3) 의 영향 검토**. ⚠️ **주의 1건**. WorkflowDiagram.test.tsx 의 `vi.mock('mermaid', ...)` 패턴 복사. workflows.$key.test.tsx 의 T5-1/T5-2 는 it.skip 유지 (PR #16 D5 옵션 C — `aria-label` 셀렉터 사용이라 mermaid mock 와 무관). **잠재 — mermaid mock 추가 후 T5-1/T5-2 가 jsdom timeout 회피 가능해질 수 있음**. 본 PR 에서 unskip 까지 scope 확장하지 않음 — 별도 후속 PR 후보로 기록.

6. **회귀 가드 추가가 기존 test 97 카운트에 영향**. ✅ Task 1 의 description 회귀 가드 4건 + Task 3 의 behavior preservation 2건 = +6건 → 97 → 99 + skipped 2 예상. plan §Task 4 통합 검증 단계에 명시.

**결정 — ✅ PASS w/ 주의 1건**.
- BLOCKER. 없음.
- 주의 1건. mermaid mock 추가의 잠재 영향 (T5-1/T5-2 unskip 가능성) — 별도 후속 PR 후보로 후보 기록.

### 후속 후보 (게이트 2 통과 후 검토)

- **T5-1/T5-2 unskip + WorkflowDiagram mock 일관화** — 본 PR Task 3 의 fix 단계에서 `vi.mock('@/components/workflow/WorkflowDiagram')` 으로 좁힘. T5-1 의 `getByLabelText(/소프트웨어 개발 기본 워크플로우 다이어그램/)` 셀렉터는 WorkflowDiagram 의 aria-label 이라 mock 가 null 반환 시 부재 → unskip 시 fail. 본 PR 시점은 it.skip 유지. 별도 후속 PR 후보로 (a) WorkflowDiagram mock 가 aria-label wrapper 만 stub 반환 + 내부 mermaid 부재 패턴 또는 (b) WorkflowDetailPage 의 header 영역만 추출한 컴포넌트 신규.

## 통합 검증 + 본 PR Learnings 후보

### 통합 검증 결과 (Task 4)

| 항목 | 결과 |
|---|---|
| typecheck | ✅ 0 issue |
| lint (ESLint flat config, NEVER-15 본 PR 영역 0 hit) | ✅ 0 issue, EXIT 0 |
| test (vitest) | ✅ 17 file / 103 passed + 2 skipped (T5-1/T5-2 it.skip 유지). 본 PR 신규 +6건 (description 회귀 가드 4건 + behavior preservation 2건) |
| build | ✅ EXIT 0 |
| E2E (playwright) | ✅ 9/9 통과 (workflow 5 + login 3 + smoke 1, 44.4s) |
| backend diff | ✅ empty (git diff --stat origin/main -- backend/ empty) |
| generated diff | ✅ empty (jOOQ 무관) |

### 본 PR 의 두 learnings 후보 (머지 후 learnings.md 등재)

1. **병렬 dispatch race — Task 1 GREEN commit 에 Task 3 RED 산출물 흡수 (옵션 A 재커밋 정리)**.
   - **사고**. wave 1 의 3-병렬 dispatch (Task 1, 2, 3) 중 Task 1 implementer 의 commit 시 lint-staged 의 자동 stage / 광범위 staging 으로 Task 3 implementer 가 동시 작업 중인 `workflows.$key.test.tsx` 의 staged 변경분 (vi.mock + 새 describe + T-NEW-1/2) 까지 Task 1 GREEN commit (4fcde1f) 에 흡수. Task 3 의 RED commit (`test: ... task-3 red`) 부재. verifier 가 Task 1 DRIFT + Task 3 TDD_VIOLATION 보고.
   - **근본 원인**. worktree 공유 + 병렬 dispatch race. lint-staged 도구의 자동 stage 가 다른 implementer 의 변경분 흡수.
   - **해결**. 옵션 A 재커밋. `git reset --hard 715819f` (Task 1 RED commit 직후) + 각 commit 의 file snapshot 으로 분리 재 commit 6 step + force-push (Draft PR 이라 안전).
   - **예방**. (1) implementer prompt 에 "git commit 시 git add <file> 단위로 명시 stage. `git add -A` / `git add .` 금지" 명시 강화. (2) bts-impl SKILL.md §실패 케이스에 "lint-staged 가 자동 stage 시 다른 implementer 의 staged 변경분 흡수 가능 — 해당 commit 의 변경 파일을 `git show <commit> --stat` 으로 검증 후 분리" 추가. (3) verifier 의 검증 항목에 "허용 files 외 commit 포함 검출" 강조 — 본 PR 의 Task 1 verifier 가 정확 catch.

2. **vi.mock 의 광범위 module mock 은 vitest worker scope leak 잠재 → 컴포넌트 단위 mock 우선**.
   - **사고**. Task 3 implementer 가 `vi.mock('mermaid', ...)` 패턴 채택 (WorkflowDiagram.test.tsx 의 동일 패턴 복사). 단독 실행 시 `workflows.$key.test.tsx` 6/6 통과 + LoginForm.test.tsx 단독 6/6 통과. 그러나 `pnpm test` 전체 실행 시 LoginForm.test.tsx 의 `userEvent.type` 5000ms timeout 1건 fail (다른 test file 영향).
   - **근본 원인**. vitest worker thread 공유 + mermaid 같은 무거운 module 의 mock 등록 overhead 가 같은 worker 안 다른 test file 의 timing 에 간섭. vitest `isolate: true` default 도 module cache 만 격리, worker thread 자체는 공유.
   - **해결**. `vi.mock('mermaid', ...)` → `vi.mock('@/components/workflow/WorkflowDiagram', () => ({ WorkflowDiagram: () => null }))` 로 영역 좁힘. WorkflowDiagram 컴포넌트 자체를 stub → 내부의 mermaid import 자체 안 발생. 전체 test duration 89s → 23s (4x 단축).
   - **예방**. (1) frontend-engineer agent prompt 에 "vi.mock 사용 시 가장 좁은 영역 (컴포넌트 또는 함수 단위) 우선. 광범위 module (mermaid, react, lodash 등) 직접 mock 은 worker scope leak 잠재 — 다른 test file timing 영향 검증 필수" 추가. (2) vitest config 에 `isolate: true` (default) + 추후 worker fork 분리 옵션 (`pool: 'forks'`) 도입 검토. (3) WorkflowDiagram.test.tsx 의 기존 `vi.mock('mermaid')` 도 후속 PR 에서 같은 패턴 (컴포넌트 단위 mock) 으로 마이그레이션 검토.
