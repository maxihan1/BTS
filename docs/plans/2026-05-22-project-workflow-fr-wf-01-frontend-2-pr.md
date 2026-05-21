# FR-WF-01 frontend — WorkflowDiagram mermaid + Playwright E2E (PR #10 후속)

> slug: project-workflow-fr-wf-01-frontend-2-pr
> type: ui
> agent: frontend-engineer
> primary_bc: project-workflow
> 생성: 2026-05-22

## Brief

PR #10 (project-workflow BC FR-WF-01 FSM 워크플로우 완제품, 머지 완료 — main `b33f5aa`) 에서 옵션 B 결정으로 분리된 frontend Task 37/38 진행. 이번 PR 로 FR-WF-01 완결.

### 작업 분류

- type. `ui` (Maxi 명시. classify 의 자동 분류 `qa` 는 "Playwright E2E" 키워드 때문이지만 메인 작업은 UI 컴포넌트)
- agent. `frontend-engineer` (Task 37). Task 38 는 frontend-engineer 가 E2E 까지 같이 작성하거나 qa-engineer 보조
- primary_bc. `project-workflow`

### 의존성

- main 의 `apps/web/` scaffold (PR #11 머지 완료 — Vite + React 19 + TypeScript + Tailwind v4 + TanStack Router/Query + shadcn/ui + msw + Playwright).
- main 의 backend REST API (PR #10 머지 완료 — `GET /api/v1/workflows`, `GET /api/v1/workflows/{key}`, `POST /api/v1/workflows/{key}/transitions`, `POST /api/v1/workflows/cache/invalidate`).
- mermaid 라이브러리 (Maxi 사전 승인 — PR #10 plan §909 CONCERN-4).

### 원본 plan 참조

`docs/plans/2026-05-21-project-workflow-bc-fr-wf-01-fsm-1-pr.md` §897~933.

- **Task 37**. `apps/web/src/components/workflow/WorkflowDiagram.tsx` + types + 테스트. mermaid stateDiagram-v2. 카테고리별 색상.
- **Task 38**. `apps/web/tests/e2e/workflow.spec.ts`. Playwright 4 시나리오 (software-default / bug-tracking / simple / kanban-basic happy path).

## 도메인 정리

### BC

`project-workflow` (frontend view layer 만 — backend domain 모델 변경 0).

### 영향 엔티티

- **신규 0건**. backend 의 `Workflow` / `WorkflowState` / `WorkflowTransition` / `StateCategory` (PR #10 도입) 을 view layer 로 변환만.
- frontend 의 표현 데이터 구조 (`WorkflowDto` / `TransitionDto`) 는 backend `web/dto/*` 의 직렬화 형태와 1:1 대응. 별도 도메인 모델 X.

### 신규 용어 (glossary 등록 여부 검토)

| 후보 | 성격 | glossary 등록? |
|---|---|---|
| `WorkflowDiagram` | React 컴포넌트 이름 (시각화) | ❌ 구현 디테일 |
| `StateNode` | mermaid stateDiagram 의 상태 표현 | ❌ 라이브러리 용어 |
| `TransitionEdge` | mermaid stateDiagram 의 전이 표현 | ❌ 라이브러리 용어 |
| `CategoryColor` | `StateCategory` 별 색상 매핑 (CSS 클래스 또는 mermaid theme override) | ❌ 시각 디테일 |

**결론**. glossary 갱신 0건. 모든 후보가 frontend 구현 디테일로 BTS 의 ubiquitous language (도메인 모델 용어) 가치 낮음. 후속 frontend 작업에서 자연스럽게 재사용될 단어들이라 컴포넌트 이름 / type 정의 / 색상 상수로 표현되면 충분.

### 기존 결정 충돌

없음. PR #10 의 ADR 5건 (v001-initial-schema-non-concurrent / workflow-validator-terminology / workflow-expression-parser-spel / workflow-yaml-vs-db-storage / workflow-bc-cross-bc-port) 모두 backend domain 결정 — 본 PR 의 view layer 와 무관.

### 신규 ADR 후보

**ADR-1. mermaid stateDiagram-v2 채택 근거** (선택 사항).

- 결정. FSM 시각화 라이브러리로 `mermaid` 의 `stateDiagram-v2` 사용.
- 사전 승인. PR #10 plan §909 CONCERN-4 (Maxi 2026-05-21 승인).
- 대안 평가. `react-flow` (인터랙티브 강력, 번들 크기 큼 100KB+) / `cytoscape` (그래프 일반화, 학습 곡선 가파름) / 커스텀 SVG (자유도 최대, 구현 비용 큼). `mermaid` 의 stateDiagram-v2 는 텍스트 → 다이어그램 변환이 가장 직관적 + FSM 시각화에 최적화. 번들 크기 약 50KB gzip.
- trade-off. mermaid 는 인터랙티브 (노드 드래그 / 줌) 제한적. 본 PR 의 read-only 다이어그램 목적에 충분. 추후 인터랙티브 요구가 생기면 별도 PR 에서 react-flow 로 마이그레이션 검토.

이 ADR 은 `/bts-plan` 단계에서 Task 화 (신규 `docs/adr/2026-05-22-mermaid-stateDiagram-v2-for-fsm-viz.md` 작성) 또는 plan 본문 인용으로 처리 결정.

### 관련 ADR / 도메인 노트

- 기존. [domain/project-workflow.md](/Users/maxi.moff/Maxi_wiki/BTS/domain/project-workflow.md) — Phase 0 진입 후 갱신 필요한 영역 (PR #10 의 5 엔티티 + ADR 5건 미반영). 본 PR 의 frontend 작업과 별개의 cleanup PR 후보.
- 본 PR 신규 ADR. ADR-1 (mermaid 채택) — plan 단계에서 작성 여부 결정.

## 스펙

전체 스펙. [docs/specs/2026-05-22-project-workflow-fr-wf-01-frontend-2-pr.md](../specs/2026-05-22-project-workflow-fr-wf-01-frontend-2-pr.md)

핵심 시나리오 3줄.
- **S1 (Task 37 핵심)**. 사용자가 워크플로우 상세 진입 → `WorkflowDiagram` 이 5 상태 + 4 전이 mermaid stateDiagram-v2 다이어그램으로 카테고리별 색상 (TODO/IN_PROGRESS/DONE) 구분해서 렌더.
- **S2~S3**. 작은 워크플로우 (`simple` 2 상태) 도 정상 + debug prop 시 mermaid source 노출.
- **S4 (Task 38 핵심)**. Playwright 가 표준 4 워크플로우 happy path — 페이지 진입 + 다이어그램 렌더 + 첫 전이 호출 + 200 응답 검증.

핵심 FR 9건 + NFR 4건 + EC 6건. 측정 가능한 완료 기준 7항목.

## Brainstorming Check

✅ 1 iteration 통과 (manually 압축 진행, 본 세션 컨텍스트 부담).

검토한 gap 후보 4건.
1. Vitest 의 jsdom 에서 mermaid SVG 렌더 어려움 → 단위 테스트 = 코드 생성 정확성, Playwright = 실제 SVG 렌더 책임 분리.
2. mermaid v11 의 ESM dynamic import → `useEffect` 안 `async` 처리.
3. 카테고리 색상 = mermaid `classDef` + DESIGN.md OKLCH 토큰 활용.
4. E2E backend 의존성 = MSW mock (Testcontainers over-engineering).

→ 모두 plan 단계에서 task 분해 가능. gap 0 확정.

## Plan

### Task 1. workflow.types.ts — frontend type 정의

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/components/workflow/workflow.types.ts`]
- depends-on. []

**RED**. 별도 테스트 X (순수 type 정의). 단, `WorkflowDiagram.test.tsx` 가 type 을 import 해서 사용 → 의존성 통한 검증.

**GREEN**. spec §6 의 4 type (`StateCategory` / `WorkflowStateView` / `WorkflowTransitionView` / `WorkflowView`) named export. JSDoc 일관.

**REFACTOR**. 첫 줄 한국어 헤더 (`// 워크플로우 다이어그램 view 모델 type 정의`).

**검증**. `pnpm --filter @bts/web typecheck`.

---

### Task 2. mermaid 의존성 추가

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/package.json`, `pnpm-lock.yaml`]
- depends-on. []

**RED**. 의존성 추가 자체는 테스트 X. Task 3 의 import 가 컴파일 검증.

**GREEN**. `apps/web/package.json` 의 `dependencies` 에 `mermaid` 추가 (최신 stable, v11.x). `pnpm install --filter @bts/web` → lockfile 갱신.

**REFACTOR**. n/a (config 변경).

**검증**. `pnpm --filter @bts/web build` 통과.

---

### Task 3. WorkflowDiagram.tsx + Vitest 단위 테스트

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/components/workflow/WorkflowDiagram.tsx`, `apps/web/src/components/workflow/WorkflowDiagram.test.tsx`]
- depends-on. [1, 2]

**RED**. 테스트 3건.
- T3-1. workflow prop 주면 mermaid 코드 생성 — `code` 상태에 `stateDiagram-v2` + 5 노드 + 4 엣지.
- T3-2. 4 표준 워크플로우 각각 (software-default / bug-tracking / simple / kanban-basic) 의 generator 출력 snapshot 검증.
- T3-3. `debug=true` 시 `<details>` + `<pre>` 안에 mermaid source 노출.

실패 예상. `Cannot find module './WorkflowDiagram'`.

**GREEN**. WorkflowDiagram 컴포넌트 (spec FR-1~6).
- `// 워크플로우 FSM 다이어그램 컴포넌트 (mermaid stateDiagram-v2 + 카테고리별 색상)` 첫 줄 헤더.
- named export. `interface WorkflowDiagramProps { workflow: WorkflowView; debug?: boolean }`.
- mermaid 동적 import (`useEffect` 안 `await import('mermaid')` + `mermaid.run()` 또는 `mermaid.render()`).
- mermaid 코드 generator. helper function `generateMermaidCode(workflow: WorkflowView): string` 추출 (테스트 가능성).
- 카테고리별 색상 — `classDef` 활용. `classDef todo fill:var(--muted) ...` 등. DESIGN.md OKLCH 토큰 활용.
- `<div ref={ref} aria-label="${workflow.name} 다이어그램">` + (debug) `<details>` + `<pre>`.
- mermaid render 실패 시 try/catch + fallback `<div>다이어그램 렌더 실패</div>` + 콘솔 에러.

**REFACTOR**. helper 함수 분리 (`generateMermaidCode`, `categoryToClass`) + JSDoc + spec EC-1 (빈 워크플로우 placeholder) / EC-5 (mermaid 특수 문자 — 영문 key 만 노드 ID) 처리.

**검증**. `pnpm --filter @bts/web test WorkflowDiagram`.

---

### Task 4. workflows API client + MSW handlers

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/api/workflows.ts`, `apps/web/src/api/workflows.test.ts`, `apps/web/src/mocks/workflow-handlers.ts`, `apps/web/src/mocks/handlers.ts` (msw handlers index — 기존 패턴 확장)]
- depends-on. [1]

**RED**. 테스트 2건 (`workflows.test.ts`).
- T4-1. `fetchWorkflows()` MSW mock 의 4 워크플로우 목록 반환 검증.
- T4-2. `fetchWorkflow('software-default')` MSW mock 의 단건 + Zod 파싱 검증.

실패 예상. `Cannot find module './workflows'`.

**GREEN**.
- `apps/web/src/api/workflows.ts`. `fetchWorkflows()` + `fetchWorkflow(key)` + `planTransition(key, request)` + Zod schema (`apps/web/src/api/client.ts` 패턴 따름).
- `apps/web/src/mocks/workflow-handlers.ts`. MSW handlers — `GET /api/v1/workflows` (4 워크플로우 fixture) + `GET /api/v1/workflows/:key` (4 워크플로우 매칭) + `POST /api/v1/workflows/:key/transitions` (mock TransitionPlan).
- `apps/web/src/mocks/handlers.ts` 에 workflow-handlers spread (또는 별도 export pattern — PR #11 패턴 확인 후 결정).

**REFACTOR**. fixture 데이터 분리 (`apps/web/src/mocks/workflow-fixtures.ts` — 4 표준 워크플로우 JSON).

**검증**. `pnpm --filter @bts/web test workflows`.

---

### Task 5. Playwright E2E — 표준 4 워크플로우 happy path

**메타**.
- agent. `frontend-engineer` (또는 qa-engineer 보조 — controller 결정)
- files. [`apps/web/e2e/workflow.spec.ts`]
- depends-on. [3, 4]

**RED**. 테스트 4건 (4 표준 워크플로우).
- T5-1. `software-default` happy path — 페이지 진입 + 다이어그램 SVG 렌더 (`svg.mermaid` + node count) + 첫 전이 호출 + 200 검증.
- T5-2. `bug-tracking` 동일 패턴.
- T5-3. `simple` 동일 패턴 (2 상태 / 2 전이 검증).
- T5-4. `kanban-basic` 동일 패턴 (4 상태 / 3 전이 검증).

실패 예상. `workflow.spec.ts` 신규라 첫 실행 시 즉시 RED 또는 라우트 미존재 시 navigation fail.

**GREEN**.
- `// FR-WF-01 E2E — 표준 4 워크플로우 happy path` 첫 줄 헤더.
- Playwright `test.describe('workflow diagram', ...)` + 4 `test()` 또는 `test.describe.parallel` 활용.
- 라우트. PR #11 의 dashboard 안에 임시 `/workflows/:key` 라우트 추가 (TanStack Router) — 또는 dev only `?workflow=software-default` query param 활용.
- backend mock — MSW 의 service worker 가 Playwright 환경에서 자동 활성 (PR #11 의 패턴 확인 후 결정. MSW 가 Playwright 와 호환 안 되면 backend dev 서버 + Playwright `webServer` 옵션).
- 공통 fixture 추출 — `apps/web/e2e/fixtures/workflow-helpers.ts` (선택, REFACTOR 단계).

**REFACTOR**. fixture helper 추출 + 한국어 헤더 + axe 접근성 검사 (`@axe-core/playwright` 활용 — NFR-3).

**검증**. `pnpm --filter @bts/web test:e2e workflow`.

---

## Plan 메타

- **task 총 수**. 5
- **예상 wave 수**. 3
  - Wave 0 (depends-on `[]`). Task 1 (types) + Task 2 (mermaid dep) — **2 task 병렬** (파일 충돌 0).
  - Wave 1 (depends-on ⊂ wave 0). Task 3 (WorkflowDiagram) + Task 4 (API client + MSW) — **2 task 병렬** (파일 충돌 0).
  - Wave 2 (depends-on ⊂ wave 0~1). Task 5 (E2E) — 1 task.
- **agent 분포**. 5/5 `frontend-engineer` (Task 5 는 controller 가 qa-engineer 보조 dispatch 도 결정 가능).
- **신규 의존성**. mermaid v11.x (Maxi 사전 승인 — PR #10 plan §909).
- **CONCERN**.
  - CONCERN-1. Vitest jsdom 환경에서 mermaid 비동기 dynamic import 처리 — Task 3 의 GREEN 단계에서 `vi.mock('mermaid')` 패턴 필요 가능성. spec §11 의 Brainstorming gap 4 의 결정 (단위 = 코드 생성 검증, E2E = 실제 SVG 검증).
  - CONCERN-2. mermaid v11 의 ESM dynamic import + Vite 빌드 호환성 — package.json 의 `dependencies` 추가 시 자동 처리 예상 (Vite v5+ 의 ESM 지원). 실패 시 Task 2 의 GREEN 단계에서 `optimizeDeps.include` 추가 또는 mermaid 의 sync import 대체.
  - CONCERN-3. MSW 가 Playwright 와 호환 — PR #11 의 도입 패턴 확인 후 Task 4 의 GREEN 결정. 호환 안 되면 backend dev 서버 + Playwright `webServer` 활용.
  - CONCERN-4. 라우트 통합 — `/workflows/:key` 페이지 미존재. 옵션 A. PR #11 dashboard 안에 임시 라우트 + 컴포넌트 mount. 옵션 B. dev only query param. 옵션 C. 별도 페이지 라우트 신규 추가 (TanStack Router 패턴 따름). Task 5 의 GREEN 단계에서 controller 결정 or Maxi 결정.

## 리뷰 결과 (← /bts-review-plan 채움)
