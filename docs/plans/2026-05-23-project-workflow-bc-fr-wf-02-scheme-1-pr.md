<!-- FR-WF-02 project-workflow BC WorkflowScheme — D1~D5 backend plan stub (워크플로우 단계별 채워짐) -->

# FR-WF-02 project-workflow BC — WorkflowScheme + 프로젝트별 스킴 + 타입별 워크플로우 매핑 (D1~D5, 1-PR)

> slug. `project-workflow-bc-fr-wf-02-scheme-1-pr`
> type. backend (classify-task 원본 `qa` override — D1~D5 token 오분류, learnings L1 누적 패턴)
> primary agent. backend-engineer (+ D4 security-engineer 검토)
> primary BC. project-workflow
> 의존 PR. #10 (FR-WF-01 backend, 머지됨), #13 (FR-WF-01 frontend, 머지됨)
> 생성. 2026-05-23

## Brief

**사용자 원문**. "FR-WF-02 backend 구현 진행하자. WorkflowScheme 도메인 + workflow_schemes / project_workflow_scheme_map / scheme_issue_type_workflow 테이블 3건 + Scheme 관리 API. D1~D5 한 PR, frontend (D6) 와 E2E (D7) 는 후속 PR. Plan slug `workflow/scheme`. FR-WF-01 PR #10 패턴 참고."

**SDD/plan 출처**.
- `docs/plan/product/project-workflow.md §2.2 FR-WF-02` — D1~D7 7단계 정의
- `docs/sdd/` — 워크플로우 도메인 챕터 (도메인 단계에서 정확 발췌)

**scope (본 PR)**.
- D1 도메인. WorkflowScheme 책임 (backend-engineer)
- D2 명세 (backend-engineer)
- D3 데이터 모델. `workflow_schemes` + `project_workflow_scheme_map` + `scheme_issue_type_workflow` 3 테이블 (db-engineer)
- D4 backend. Scheme 관리 API (backend-engineer + security-engineer 검토)
- D5 backend 테스트 (backend-engineer)

**scope 외 (후속 PR)**.
- D6 frontend UI. 프로젝트 설정 → 워크플로우 (designer → frontend-engineer)
- D7 E2E (qa-engineer)

## 충돌 분석 (worktree 진입 시점)

| 후보 worktree | 충돌 영역 | 영향 |
|---|---|---|
| PR #16 (ui/workflow-diagram-c2-c3-followup) | frontend cosmetic (MSW fixture + classDef) | 0 — 본 PR backend 도메인, 영역 분리 |
| PR #17 (backend/issue-tracking-bc-fr-is-01-business-logic) | issue-tracking BC | 0 — 본 PR project-workflow BC, BC 격리 |

본 worktree 진입 안전.

## 도메인 정리 (← `/bts-domain` 채움)

## 스펙 (← `/bts-spec` Phase A 채움)

## Brainstorming Check (← `/bts-spec` Phase B 채움)

## Plan (← `/bts-plan` 채움)

## 리뷰 결과 (← `/bts-review-plan` 채움)
