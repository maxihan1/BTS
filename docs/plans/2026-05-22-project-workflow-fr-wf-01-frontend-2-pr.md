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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
