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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
