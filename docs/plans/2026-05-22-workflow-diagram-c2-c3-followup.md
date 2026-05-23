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
- mermaid 다이어그램 렌더 회귀 0 — 사용자 시각 변화 0 (classDef 이름만 변경, 색상/노드/전이 동일).
- transition.key 형식 통일 — fixture 17건 + workflows.test.ts inline 17건 = 34건 일괄 backend computed `${fromStateKey}__${toStateKey}` 형식.
- mermaid classDef prefix — categoryToClass() 가 `category_todo` / `category_in_progress` / `category_done` 반환, state id 와 토큰 충돌 0.

핵심 결정 4건 (D1~D4).
- **D1** — `transitionKey()` helper 위치 = `workflow.types.ts` (view layer 타입 정의 인접).
- **D2** — fixture 결합 = 옵션 B (fixture 가 helper 호출, drift 본질 차단).
- **D3** — classDef prefix = 언더스코어 (`category_todo` 등 — mermaid v11 호환성 100%).
- **D4** — workflows.test.ts inline 17건도 본 PR scope.

## Brainstorming Check

✅ 통과 (1회 iteration, gap 6건 발견 — Maxi 결정 4건 + spec 보강 2건 모두 inline 반영).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
