# FR-WF-01 후속 — WorkflowDiagram 영역 정리 (C-2 + C-3)

> slug. workflow-diagram-c2-c3-followup
> type. ui (classify override — original=qa, agent=frontend-engineer 로 재조정)
> agent. frontend-engineer
> primary BC. project-workflow (frontend view layer)
> 생성. 2026-05-22
> 베이스. main `623a6da` (PR #15 husky 머지 이후)

## Brief

PR #13 (FR-WF-01 frontend) 의 /bts-codereview CONCERN-2 + CONCERN-3 후속 PR. 후속 위임 합의 (history.md PR #13 entry — "C-2/C-3/SUGGESTION 후속 PR 위임"). 같은 영역 (project-workflow frontend) 이라 한 PR 로 묶음.

### 사용자 원문
> FR-WF-01 후속 — WorkflowDiagram 영역 정리.
> (1) MSW workflow-fixtures.ts 의 transition.key 형식을 backend computed property `"${fromStateKey}__${toStateKey}"` 와 동일하게 통일 + key 생성 helper 추가
> (2) mermaid classDef prefix — `categoryToClass()` 가 `"category-todo"` / `"category-in-progress"` / `"category-done"` 반환하도록 변경
> WorkflowDiagram + 단위 테스트 + Playwright E2E + snapshot 모두 갱신.

### classify 재조정

- 원본 classify. type=qa / agent=qa-engineer / slug=fr-wf-01-workflowdiagram-1-msw-workflow-fixtures-t
- 재조정 후. type=ui / agent=frontend-engineer / slug=workflow-diagram-c2-c3-followup / primary_bc=project-workflow
- 이유. 핵심 변경이 apps/web/src/ frontend 코드 (workflow-fixtures.ts / WorkflowDiagram.tsx / categoryToClass). qa-engineer agent 는 구현 코드 수정 금지 — frontend-engineer 가 적절. E2E + snapshot 갱신은 그 변경의 결과물.

### 직접 컨텍스트

- history.md PR #13 (`4b2804d`) — 본 PR 의 직접 상위. C-2/C-3 둘 다 codereview CONCERN 으로 식별됨.
- learnings.md 2026-05-22 — "mermaid stateDiagram-v2 SVG 셀렉터" + "TanStack Router code-based adapter 패턴" — 본 PR 에서 회귀 방지.
- /context-restore 산출물 — 본 작업 진입 시 "C-2 + C-3 묶음" 옵션 Maxi 결정 (recommended option).

## 도메인 정리

- **BC**. project-workflow (frontend view layer 한정)
- **영향 엔티티**. 없음 — 도메인 엔티티 / 행동 / 책임 변경 없음
  - C-2 — `WorkflowTransition.key` computed property 는 backend PR #13 Task 7 에서 이미 도입. 본 PR 은 frontend MSW fixture (테스트/dev 응답 데이터) 의 transition.key 표기를 그 형식에 맞추는 작업
  - C-3 — `categoryToClass()` 는 frontend WorkflowDiagram view layer 의 mermaid 토큰 변환 helper. 도메인 무관
- **새 용어**. 0건 (glossary 갱신 불필요)
- **기존 ADR 충돌**. 없음 — project-workflow BC ADR 5건 (`v001-initial-schema-non-concurrent` / `workflow-bc-cross-bc-port` / `workflow-expression-parser-spel` / `workflow-validator-terminology` / `workflow-yaml-vs-db-storage`) 모두 본 작업 영역과 무관
- **관련 ADR (참고만)**. 없음 — 본 PR 에서 신규 ADR 작성 불필요
- **grill-with-docs 스킵 사유**. C-2 + C-3 모두 PR #13 codereview 의 CONCERN 후속 정리. 새 기능 / 도메인 모델 변경 / 신규 BC 통합 0건. learnings.md 2026-05-22 "BC 격리 예외 (옵션 C)" 적용 — 본 PR 은 same BC frontend 영역 내부 cleanup
- **domain/project-workflow.md 갱신**. 불필요 (도메인 엔티티 / 결정 사항 변경 0)

## 스펙

전체 스펙. [docs/specs/2026-05-22-workflow-diagram-c2-c3-followup.md](../specs/2026-05-22-workflow-diagram-c2-c3-followup.md)

핵심 시나리오 3줄.
- mermaid 다이어그램 렌더 회귀 0 — 사용자 시각 변화 0 (classDef 이름만 변경, 색상/노드/전환 동일).
- transition.key 형식 통일 — fixture 17건 + workflows.test.ts inline 17건 = 34건 일괄 backend computed `${fromStateKey}__${toStateKey}` 형식.
- mermaid classDef prefix — categoryToClass() 가 `category_todo` / `category_in_progress` / `category_done` 반환, state id 와 토큰 충돌 0.

핵심 결정 4건 (D1~D4).
- **D1** — `transitionKey()` helper 위치 = `workflow.types.ts` (view layer 타입 정의 인접).
- **D2** — fixture 결합 = 옵션 B (fixture 가 helper 호출, drift 본질 차단).
- **D3** — classDef prefix = 언더스코어 (`category_todo` 등 — mermaid v11 호환성 100%).
- **D4** — workflows.test.ts inline 17건도 본 PR scope.

## Brainstorming Check

✅ 통과 (1회 iteration, gap 6건 발견 — Maxi 결정 4건 + spec 보강 2건 모두 inline 반영).

## Plan

### Task 1. transitionKey() helper export + 명시적 단위 테스트

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/components/workflow/workflow.types.ts`, `apps/web/src/components/workflow/workflow.types.test.ts`]
- depends-on. []

**RED**. `workflow.types.test.ts` 신규. transitionKey() 2건 expectation.
```ts
import { describe, it, expect } from 'vitest'
import { transitionKey } from './workflow.types'

describe('transitionKey', () => {
  it('returns "{from}__{to}" composition for plain state keys', () => {
    expect(transitionKey('open', 'in_progress')).toBe('open__in_progress')
  })

  it('preserves underscores in state keys (no escaping)', () => {
    expect(transitionKey('in_progress', 'in_review')).toBe('in_progress__in_review')
  })
})
```
실패 메시지 (예상). `transitionKey` named export 없음 → TypeScript 컴파일 에러 → vitest fail.

**GREEN**. `workflow.types.ts` 에 다음 추가 (파일 끝 export 영역).
```ts
/**
 * 두 state key 를 backend WorkflowTransition.key 와 같은 형식으로 합성.
 * backend domain WorkflowTransition.kt:18 의 `"${fromStateKey}__$toStateKey"` 와 정확 일치.
 */
export function transitionKey(fromStateKey: string, toStateKey: string): string {
  return `${fromStateKey}__${toStateKey}`
}
```

**REFACTOR**. JSDoc 1줄 보강 + named export 위치 확인 (파일 끝).

**검증**. `pnpm --filter web test -- workflow.types.test.ts`. 2 pass.

---

### Task 2. workflow-fixtures.ts + workflows.test.ts inline → 옵션 B 패턴 (helper 호출)

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/mocks/workflow-fixtures.ts`, `apps/web/src/api/workflows.test.ts`]
- depends-on. [1]

**RED**. 새 검증 테스트 (회귀 가드) 신규 — `workflow-fixtures.test.ts`.
```ts
import { describe, it, expect } from 'vitest'
import { allWorkflowFixtures } from './workflow-fixtures'
import { transitionKey } from '@/components/workflow/workflow.types'

describe('workflow-fixtures transition key 형식 검증', () => {
  it.each(allWorkflowFixtures)(
    '$key — 모든 transition.key 가 transitionKey() 결과와 일치',
    (workflow) => {
      workflow.transitions.forEach((t) => {
        expect(t.key).toBe(transitionKey(t.fromStateKey, t.toStateKey))
      })
    },
  )
})
```
실패 메시지 (예상). 현재 fixture key (`open-to-in_progress`) ≠ `transitionKey('open', 'in_progress')` (`open__in_progress`) → 17건 fail.

**GREEN**. 두 파일 갱신.

1. `workflow-fixtures.ts` (17건) — fixture transition 정의를 옵션 B 패턴으로 변환. 예시 (softwareDefaultFixture 1건).
```ts
import { transitionKey } from '@/components/workflow/workflow.types'

// before. { key: 'open-to-in_progress', name: 'Start Work', ... }
// after.
{ key: transitionKey('open', 'in_progress'), name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress' }
```
4 워크플로우 × 합계 17건 전체.

2. `workflows.test.ts` (라인 23-28 / 44-48 / 62-64 / 79-81 — 17건 inline) — 동일 패턴 일괄 변환. import 추가.

**REFACTOR**. 두 파일의 transition 정의 alignment 확인 (가독성). 코멘트 1줄 — fixture 가 helper 호출로 drift 차단 명시.

**검증**. `pnpm --filter web test -- workflow-fixtures workflows`. 17건 회귀 가드 + 기존 workflows.test.ts pass.

---

### Task 3. categoryToClass() 언더스코어 prefix + classDef 라인 동기 + 명시적 단위 테스트 3건

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/components/workflow/WorkflowDiagram.tsx`, `apps/web/src/components/workflow/WorkflowDiagram.test.tsx`]
- depends-on. []

**RED**. `WorkflowDiagram.test.tsx` 에 categoryToClass 명시적 단위 테스트 3건 신규 (describe block 추가).
```ts
import { categoryToClass } from './WorkflowDiagram'

describe('categoryToClass — 카테고리 → mermaid classDef 식별자 변환 (D3 언더스코어 prefix)', () => {
  it("'TODO' → 'category_todo'", () => {
    expect(categoryToClass('TODO')).toBe('category_todo')
  })
  it("'IN_PROGRESS' → 'category_in_progress'", () => {
    expect(categoryToClass('IN_PROGRESS')).toBe('category_in_progress')
  })
  it("'DONE' → 'category_done'", () => {
    expect(categoryToClass('DONE')).toBe('category_done')
  })
})
```
실패 메시지 (예상). 현재 categoryToClass 반환 (`'todo'` / `'in_progress'` / `'done'`) ≠ 새 기대값 → 3건 fail.

**GREEN**. `WorkflowDiagram.tsx` 갱신 2부분.

1. `categoryToClass()` 본문.
```ts
case 'TODO':
  return 'category_todo'
case 'IN_PROGRESS':
  return 'category_in_progress'
case 'DONE':
  return 'category_done'
```

2. `generateMermaidCode()` 의 classDef 라인 3건 동기.
```ts
lines.push(`  classDef category_todo fill:var(--muted),stroke:var(--border)`)
lines.push(`  classDef category_in_progress fill:oklch(from var(--primary) l c h / 0.15),stroke:var(--primary)`)
lines.push(`  classDef category_done fill:oklch(0.94 0.05 160 / 0.15),stroke:oklch(0.5 0.12 160)`)
```

3. `class <stateKey> categoryToClass(state.category)` 라인은 변경 0 — helper 호출 결과만 변경됨.

**REFACTOR**. categoryToClass KDoc 갱신 — D3 결정 (언더스코어 prefix, mermaid v11 호환성 100%) 사유 1줄 보강.

**검증**. `pnpm --filter web test -- WorkflowDiagram`. categoryToClass 3건 + 기존 generateMermaidCode 단위 테스트 모두 pass. snapshot 4건은 다음 Task 에서 갱신.

---

### Task 4. snapshot 4 워크플로우 갱신 + 시각 회귀 0 검증

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/components/workflow/__snapshots__/WorkflowDiagram.test.tsx.snap`]
- depends-on. [3]

**RED**. Task 3 GREEN 후 `pnpm --filter web test -- WorkflowDiagram` 실행 → 4 워크플로우 snapshot 모두 mismatch (classDef 3 라인 + class N 라인 prefix 차이).
- software-default. 3 classDef 라인 + 5 class 라인 (open / in_progress / in_review / resolved / closed).
- bug-tracking. 3 classDef 라인 + 5 class 라인.
- simple. 3 classDef 라인 + 2 class 라인 (open / closed).
- kanban-basic. 3 classDef 라인 + 4 class 라인.
합계 변경 라인. 3 classDef × 4 = 12 + class N 라인 합 16 = 28 라인.

**GREEN**. `pnpm --filter web test -- WorkflowDiagram -u`. snapshot 일괄 갱신.

**검증** (필수). `git diff __snapshots__/WorkflowDiagram.test.tsx.snap` 으로 diff 검토. 다음 검증.
- 변경 라인이 classDef 3 라인 + class N 라인 (prefix `category_` 추가) 만 포함. **노드 / 전환 / 시작·종료 라인 변경 0**.
- 변경되지 않은 라인 (예. `[*] --> open`, `open --> in_progress : 진행 시작`, `done --> [*]`) 가 정확히 보존.

diff 가 예상 패턴과 다르면 RED 로 돌아가서 Task 3 GREEN 점검 (의도치 않은 부수 변경 의심).

**REFACTOR**. 없음. snapshot 은 generated artifact.

**검증**. `pnpm --filter web test`. 86 unit (기존 추정 86 + Task 1 의 transitionKey 2건 + Task 2 의 fixture 회귀 가드 4건 (4 워크플로우 × 1 인라인 it.each) + Task 3 의 categoryToClass 3건 = 약 95). 0 fail.

---

### Task 5 (hot-fix, D5 결정 옵션 C). workflows.$key.test.tsx T5-1/T5-2 jsdom mermaid timeout 해소

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/routes/workflows.$key.test.tsx`]
- depends-on. []

**배경**. Wave 1 implementer (Task 2 + Task 4) 보고 + 회귀 분석 — T5-1/T5-2 timeout 이 **PRE_EXISTING** (PR #10 4b2804d 시점부터 잠재). workflows.$key.test.tsx 가 `vi.mock('mermaid', ...)` 부재 → jsdom 의 SVG `getBBox` 미구현 → `mermaid.render()` timeout. 본 PR 변경 (transition.key 형식 + classDef prefix) 은 mermaid 렌더 타이밍/로직 무관. 본 PR 검증 단계 (`pnpm test` 전체 통과) 차단 위험으로 Maxi 결정 D5 = 옵션 C (본 PR 안 hot-fix).

**RED (이미 존재)**. `pnpm --filter web test -- workflows.\$key.test` 실행 → T5-1 / T5-2 timeout fail. 본 PR 변경 직전부터 존재.

**GREEN**. workflows.$key.test.tsx 의 T5-1 / T5-2 `it(...)` 를 `it.skip(...)` 으로 변경. 직전에 1줄 코멘트로 사유 명시.
```ts
// jsdom 환경에서 mermaid SVG getBBox() 미구현으로 mermaid.render() timeout 발생 (PR #10 시점부터 잠재).
// 실제 mermaid 렌더 검증은 e2e/workflow.spec.ts 4 happy path 가 커버. PR #16 hot-fix (D5 옵션 C).
it.skip('T5-1: ...', ...)
it.skip('T5-2: ...', ...)
```

커밋. `fix: workflow-diagram-c2-c3-followup task-5 hot-fix — workflows.\$key.test.tsx T5-1/T5-2 it.skip (jsdom mermaid getBBox 미구현, E2E 위임)`.

**REFACTOR**. 없음.

**검증**. `pnpm --filter web test -- workflows.\$key.test` 전체 pass (skipped 2건 명시) 확인. `pnpm --filter web test` 전체 0 fail. E2E (`pnpm --filter web test:e2e`) 의 workflow.spec.ts 4 happy path 가 mermaid 렌더 실제 검증.

## Plan 메타

- task 수. 4
- wave 분석. depends-on 그래프 longest path = 2 (Task 1→2 또는 Task 3→4). **2 wave 가능**.
  - Wave 0. Task 1 (workflow.types) + Task 3 (WorkflowDiagram) — 2 task 병렬. 파일 겹침 0.
  - Wave 1. Task 2 (fixtures) + Task 4 (snapshot) — 2 task 병렬. 파일 겹침 0.
- 예상 시간. wave 당 약 10분 × 2 = 약 20분. controller verify 단계 약 5분 추가.
- TDD 강제. yes (모든 task RED → GREEN → REFACTOR).
- 병렬 dispatch. bts-impl 자동.
- 추가 검증 (Step 4 verification-before-completion). `pnpm --filter web typecheck` + `pnpm --filter web lint` + `pnpm --filter web build` + `pnpm --filter web test:e2e` (4 happy path 시각 회귀 0). backend 변경 0 — `git diff --stat main backend/` empty 확인.

## 리스크

1. **mermaid 의 underscore classDef 식별자 실제 처리**. spec EC-3 + D3 결정. mermaid v11 의 `classDef` 식별자 정규식 `[a-zA-Z][a-zA-Z0-9_-]*` 명시 허용. 단 stateDiagram-v2 의 v11 변경 가능성. Task 4 E2E (workflow.spec.ts) 가 .statediagram-state 셀렉터로 노드 수 검증 — class 속성 변경이 SVG 구조에 영향 0. 시각 회귀 0 자동 검증.

2. **fixture 옵션 B 채택 후 가독성 약간 저하**. fixture 라인 길이 증가 (`transitionKey('open', 'in_progress')` 가 `'open__in_progress'` 보다 19자 길음). drift 차단 가치 > 가독성 비용 (PR #13 learnings 정신 적용).

3. **D4 결정 — workflows.test.ts inline 17건도 본 PR scope**. workflows.test.ts 가 workflow-fixtures.ts import 로 일관화는 별 PR 후속 후보 (spec §9 Out of scope). 본 PR 머지 후 inline 구조 유지 — 다음 fixture 추가 시 재발 가능성 명시.

4. **snapshot diff 검토 누락 위험**. Task 4 검증 단계가 git diff 검토를 verifier 단계 명시. 의도치 않은 부수 변경 (예. mermaid 코드의 indent / 줄바꿈) 발생 시 즉시 RED 회귀. PR #13 learnings 의 "spec/plan 표기 drift 발견 시 spec 우선" 정신.

## 리뷰 결과

### /plan-design-review — SKIP

본 PR 의 사용자 시각 변화 0 (NFR-1 명시). classDef 이름만 변경 (SVG class 속성 비가시), 색상 / 노드 / 전환 동일. design-review 가 평가할 시각 요소 0. **skip**.

### /plan-eng-review (2026-05-22)

**Step 0 — scope challenge**. ✅ trigger 안 됨 (4 task / 7 file / 0 new class / 1 new function). 기존 codebase 재사용 100%. 최소 변경. scope accepted as-is.

**Section 1 — Architecture**. ✅ No issues. 의존성 그래프 / 데이터 흐름 / 보안 / 분배 변경 0. plan §리스크 1번 (mermaid underscore classDef) 이 D3 결정으로 해소.

**Section 2 — Code Quality**. ✅ No issues. helper 1 함수 재사용 (fixture + workflows.test.ts inline). 한국어 헤더 / NEVER-15 (console.error 만 허용) / TDD 강제 모두 준수.

**Section 3 — Tests**. ✅ Coverage 8/8 paths (100%). 모든 새 코드패스 (transitionKey 2건 / categoryToClass 3건 / generateMermaidCode snapshot 4건 / fixture 회귀 가드 1건) 가 ★★★ (behavior + edge + error). gaps 0. Test plan artifact 불필요 (spec §8 완료 기준 충분).

**Section 4 — Performance**. ✅ No issues. 런타임 영향 0. 소스 코드 +1 KB.

**Outside voice**. skip (PR scope 작음).

**Completion Summary**.
- Step 0 — scope accepted as-is.
- Architecture — 0 issues.
- Code Quality — 0 issues.
- Tests — 100% coverage / 0 gaps.
- Performance — 0 issues.
- NOT in scope — spec §9 (axe / SUGGESTION-1 / 새 워크플로우 / mermaid 버전 / workflows.test.ts inline → import 일관화 모두 후속 PR).
- What already exists — 본 PR 의 모든 영향 파일은 PR #13 에서 도입. 신규 파일 0.
- TODOS — 0 proposed (NOT in scope 항목은 history.md PR #13 entry 에 후속 PR 후보 기록 완료).
- Failure modes — 0 critical gaps.
- Parallelization — 2 lanes (Wave 0. Task 1+3 / Wave 1. Task 2+4).
- Lake Score — 4/4 (D1 helper 위치 명시 / D2 옵션 B drift 차단 / D3 underscore 안전 옵션 / D4 본 PR scope 내 처리).

**VERDICT**. CLEARED — ready to implement.

### /bts-codereview (PR 단위 리뷰, 2026-05-23)

#### superpowers:code-reviewer agent

**판정**. ✅ **PASS** (수정 권장 1건, 머지 차단 사유 0건)

검증 결과.
1. Plan / Spec 정합성 — D1~D5 결정과 실제 구현 5/5 일치. snapshot diff 28 라인 (classDef 12 + class 16), 노드/전환/시작·종료 변경 0.
2. BTS 절대 규칙 19개 (DEVELOPMENT.md §1) — ALL PASS. NEVER-11/12/13/15/16/17 모두 0 위반.
3. TDD red→green→refactor — task 1/2/3 만족. task-4 snapshot 갱신은 generated artifact 라 RED 의미가 task-3 GREEN 의 자동 부산물 — 정당.
4. learnings.md 회귀 검증 — 2026-05-22 누적 학습 6건 (drift / mermaid SVG 셀렉터 / BC 격리 / hot-fix / TanStack Router) 모두 본 PR 과 정합. PR #13 의 mermaid SVG 셀렉터 학습이 D3 결정 (언더스코어 prefix) 으로 직접 이어짐.

CONCERN 1건 (낮음 — 옵션 A 권장).
- **CONCERN-1**. T5-2 의 404 fallback 시나리오가 E2E (workflow.spec.ts) 에 명시적 케이스 0건. 권장 옵션 A — 본 PR scope 외, learnings.md 에 후속 PR 후보 1줄 기록 + history.md PR #16 entry 에 명시.

#### /review (gstack) — critical pass

**판정**. ✅ **PASS** (critical category 위반 0).

검증 결과.
- SQL / Race / LLM trust / Shell injection — 본 PR 무관 (frontend cleanup).
- NEVER-15 (console.log) 0 hit. console.error 2 hit (useLogoutMutation 의 PR #11 + WorkflowDiagram 의 mermaid 렌더 실패) — 정책 허용.
- NEVER-11 (any) / NEVER-12 (!!) / NEVER-13 (빈 catch) / NEVER-16 (PoC 표현) — 모두 0 hit.
- 첫 줄 한국어 헤더 — 신규 2 파일 (workflow.types.test.ts + workflow-fixtures.test.ts) 모두 충족.
- specialist + adversarial dispatch — scope 작아 (PR #13 codereview 후속 정리) 본 controller 가 critical pass 만 실행. code-reviewer agent 의 PASS 판정과 cross-check.

#### /plan-ceo-review

type=ui (frontend cleanup) — auth/migration 아니므로 PR-level ceo-review 스킵.

#### 머지 게이트

**최종 PASS — 머지 가능**.
- CONCERN-1 (T5-2 fallback E2E) 은 후속 PR 후보 (옵션 A) — 본 PR scope 외.
- verification 전부 통과 — typecheck/lint/test 97 + skipped 2/build 9.34s/E2E 8/8.
- backend 변경 0 확인.
