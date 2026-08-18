# FR-WF-01 frontend 스펙 — WorkflowDiagram mermaid + Playwright E2E

> slug. project-workflow-fr-wf-01-frontend-2-pr
> type. ui
> 생성. 2026-05-22

## 1. 작업 의도

PR #10 의 backend FR-WF-01 (FSM 워크플로우) 의 시각화 + E2E 검증. 본 PR 로 FR-WF-01 **완결**.

- 사용자가 표준 4 워크플로우 (`software-default` / `bug-tracking` / `simple` / `kanban-basic`) 를 **시각적으로 이해**.
- 워크플로우 전환 호출 결과가 UI 에 반영됨을 **end-to-end 검증**.

## 2. 사용자 시나리오 (Given-When-Then)

### S1. 표준 워크플로우 다이어그램 표시 (Task 37 핵심)

```
Given. 사용자가 워크플로우 상세 화면 진입 (예. /workflows/software-default).
When. WorkflowDiagram 컴포넌트가 mount 되고 workflow prop (5 상태 + 4 전환) 받음.
Then. mermaid stateDiagram-v2 다이어그램 렌더 — 상태 5 노드 + 전환 4 엣지, 카테고리별 색상 구분 (todo=회색 / in_progress=파랑 / done=초록).
```

### S2. 작은 워크플로우 (`simple`) — 2 상태

```
Given. simple 워크플로우 (open / closed 2 상태 + 2 전환).
When. WorkflowDiagram mount.
Then. 노드 2 + 엣지 2, 다이어그램 컨테이너 height 자동 조정.
```

### S3. mermaid 코드 직접 노출 (개발자 디버깅용)

```
Given. WorkflowDiagram 의 debug prop 가 true.
When. 컴포넌트 렌더.
Then. mermaid 텍스트 코드 (`stateDiagram-v2 ... ` 블록) 가 `<details>` 태그 안에 노출.
```

### S4. Playwright E2E — 표준 4 워크플로우 happy path (Task 38)

```
Given. backend dev 서버 (PR #10 의 4 표준 YAML 시드 적재 완료) + frontend dev 서버 가동.
When. Playwright 가 4 워크플로우 각각의 상세 페이지 진입.
Then. (1) GET /api/v1/workflows/{key} 200 응답, (2) 다이어그램 노드 수 + 엣지 수 검증, (3) 첫 전환 buttons 클릭 → POST /api/v1/workflows/{key}/transitions 200 + UI 업데이트.
```

## 3. 기능 요구사항 (FR)

### Task 37 — WorkflowDiagram 컴포넌트

| FR | 내용 |
|---|---|
| FR-1 | `WorkflowDiagram` named export. props. `workflow: WorkflowView` (모듈 type) + `debug?: boolean` |
| FR-2 | mermaid `stateDiagram-v2` 코드 자동 생성. 노드 = `WorkflowState.key` (display = `WorkflowState.name` 한글), 엣지 = `WorkflowTransition` (label = `transition.name`) |
| FR-3 | 카테고리별 색상. `StateCategory.TODO`=`bg-muted text-muted-foreground` / `IN_PROGRESS`=`bg-primary/10 text-primary` / `DONE`=`bg-emerald-500/10 text-emerald-700` (DESIGN.md 토큰 활용) |
| FR-4 | mermaid 초기화 1회 (`useEffect` + ref). 다이어그램 컨테이너에 inject. 컴포넌트 unmount 시 정리 |
| FR-5 | workflow prop 변경 시 다이어그램 재렌더 (mermaid `render` 재호출) |
| FR-6 | debug=true 면 `<details><summary>mermaid source</summary><pre>{code}</pre></details>` 노출 |

### Task 38 — Playwright E2E

| FR | 내용 |
|---|---|
| FR-7 | 4 표준 워크플로우 각 1 시나리오 — `software-default` (5 상태), `bug-tracking` (5 상태), `simple` (2 상태), `kanban-basic` (4 상태) |
| FR-8 | 각 시나리오. (1) 페이지 진입 + 200 응답 검증, (2) 다이어그램 SVG 렌더 검증 (`svg.mermaid` 존재 + `g[class*="node"]` 수), (3) 첫 전환 트리거 + 200 응답 검증 |
| FR-9 | backend dev seed (`data-dev.sql`) 의 알리스 사용자 (PR #11 도입) 로 로그인 → 워크플로우 페이지 진입. 또는 인증 우회 dev profile |

## 4. 비기능 요구사항 (NFR)

| NFR | 내용 | 측정 |
|---|---|---|
| NFR-1 | 다이어그램 초기 렌더 200ms 이내 (cold load) | Vitest 의 `performance.now()` 또는 Playwright trace |
| NFR-2 | 번들 크기 증가 60KB gzip 이내 (mermaid 라이브러리 추가분) | `pnpm build` 후 `du -h` 또는 `vite-bundle-visualizer` |
| NFR-3 | 접근성 — 다이어그램 컨테이너에 `aria-label="<workflow.name> 다이어그램"` 부착. mermaid SVG 자체는 정적 이미지로 처리 (screen reader 가 SVG 내부 탐색 안 함, summary text 별도 제공) | axe DevTools 또는 `@axe-core/playwright` |
| NFR-4 | 다크 모드. 본 PR 미지원 (DESIGN.md 와 일관, 라이트 모드 전용) | 시각 확인 |

## 5. API 인터페이스 (활용만)

본 PR 신규 API 0건. PR #10 의 기존 API 활용.

- `GET /api/v1/workflows` — 4 워크플로우 목록.
- `GET /api/v1/workflows/{key}` — 단건 + 상세 (계층 구조 — states + transitions).
- `POST /api/v1/workflows/{key}/transitions` — 전환 호출.

API client 코드. `apps/web/src/api/workflows.ts` 신규 (또는 기존 `client.ts` 확장).

## 6. 데이터 모델 (frontend type)

`apps/web/src/components/workflow/workflow.types.ts` 신규.

```ts
export type StateCategory = 'TODO' | 'IN_PROGRESS' | 'DONE';

export interface WorkflowStateView {
  key: string;
  name: string;
  category: StateCategory;
  displayOrder: number;
}

export interface WorkflowTransitionView {
  key: string;
  name: string;
  fromStateKey: string;
  toStateKey: string;
}

export interface WorkflowView {
  key: string;
  name: string;
  description: string;
  states: WorkflowStateView[];
  transitions: WorkflowTransitionView[];
}
```

backend `WorkflowDto` (`web/dto/`) 직렬화와 1:1 대응. Zod schema 로 런타임 validation 권장 (`apps/web/src/api/client.ts` 패턴 따름).

## 7. 엣지 케이스

| EC | 내용 | 대응 |
|---|---|---|
| EC-1 | 빈 워크플로우 (states 0건) | `<div>워크플로우 정의 비어있음</div>` placeholder |
| EC-2 | 사이클 그래프 (A → B → A) | mermaid 가 자동 처리 — 별도 대응 없음 (FR-WF-01 의 표준 4 워크플로우는 사이클 없음, 그러나 미래 사용자 정의 워크플로우 대비) |
| EC-3 | mermaid render 실패 (잘못된 코드 등 — 거의 발생 안 하지만) | `try/catch` 후 `<div>다이어그램 렌더 실패</div>` + 에러 콘솔 로그 |
| EC-4 | 매우 큰 워크플로우 (20+ 상태) | mermaid 자체 한계 — 본 PR 의 4 표준 (최대 5 상태) 에서는 영향 X. 후속 대응은 별도 PR |
| EC-5 | 사용자 정의 한글 상태 이름에 mermaid 특수 문자 (`[`, `]`, `:` 등) 포함 | 노드 ID 에는 영문 `key` 만 사용. display 라벨에는 한글 그대로 (mermaid 의 quote 처리 활용) |
| EC-6 | E2E 에서 backend 미가동 | Playwright 의 `webServer` 옵션으로 자동 기동, 실패 시 명시적 에러 |

## 8. 제약 조건

- **mermaid 라이브러리 추가**. Maxi 사전 승인 (PR #10 plan §909 CONCERN-4). 번들 크기 NFR-2 준수.
- **read-only 다이어그램**. 노드 드래그 / 줌 / 편집 0. 후속 PR 에서 인터랙티브 검토.
- **카테고리 매핑**. backend `StateCategory` enum 3종 (TODO/IN_PROGRESS/DONE) 만 매핑. 미래 enum 확장 시 별도 PR.
- **다크 모드 미지원**. DESIGN.md 일관.

## 9. 측정 가능한 완료 기준

- [ ] `WorkflowDiagram.tsx` + `workflow.types.ts` + `WorkflowDiagram.test.tsx` 작성.
- [ ] Vitest 단위 테스트 3건 모두 통과 (FR-1~6 의 핵심 시나리오).
- [ ] Playwright E2E 4건 (FR-7~9) 모두 통과. backend dev seed 적재 + 4 워크플로우 라우팅 동작.
- [ ] `pnpm typecheck lint test build` 모두 통과 (모노레포 verify).
- [ ] 번들 크기 증가 60KB gzip 이내.
- [ ] axe 접근성 검사 통과 (다이어그램 영역 + `aria-label` 부착).
- [ ] DESIGN.md 토큰 활용 (별도 색상 추가 0).

## 10. 본 PR 미포함 (후속 PR 후보)

- 워크플로우 다이어그램 인터랙티브 (노드 클릭 → 상세 / 전환 클릭 → confirm).
- 워크플로우 편집 UI (생성 / 수정 / 삭제).
- 다크 모드.
- 워크플로우 변경 이력 (changelog 시각화).
- 모바일 viewport 최적화 (PR #11 의 mobile-first 패턴 준수하되 다이어그램은 desktop-first).

## 11. Brainstorming Check

✅ manually 압축 진행 (본 세션 컨텍스트 부담).

**검토한 gap 후보**.

- **테스트 환경에서 mermaid SVG 렌더 검증 어려움?** — Vitest 의 jsdom 환경에서는 mermaid 가 fallback 으로 텍스트 코드만 생성하고 SVG 렌더 안 됨. Vitest 단위 테스트는 "mermaid 코드 생성 정확성" 검증 (mermaid 라이브러리 mock 으로 입력 → 출력 정확성). 실제 SVG 렌더는 Playwright (실제 브라우저) 가 담당.
- **mermaid v11 의 ESM 호환성 — Vite 빌드 시 dynamic import 필요?** — mermaid v11 은 dynamic import 강제. `await mermaid.run()` 또는 `mermaid.render()` 비동기. 컴포넌트의 `useEffect` 안에서 `async` 처리.
- **카테고리 색상 — mermaid theme 변수 vs CSS 클래스?** — mermaid 의 `classDef` 로 stateDiagram 노드에 클래스 부착 → CSS 변수 활용. DESIGN.md 의 OKLCH 토큰 그대로 활용 가능.
- **E2E 의 backend 의존성 — Testcontainers vs MSW?** — PR #10 의 backend Testcontainers 패턴 활용 가능하지만, frontend Playwright 에서는 over-engineering. MSW (Mock Service Worker) 로 REST API mock 권장. `apps/web/src/mocks/` 의 PR #11 도입 패턴 따름.

→ **gap 0건. 1 iteration 통과**. 단위 테스트 + E2E 의 책임 분리 명확. mermaid 비동기 처리 + MSW mock 패턴 plan 단계에서 task 분해.
