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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
