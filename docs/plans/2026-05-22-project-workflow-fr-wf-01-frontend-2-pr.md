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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
