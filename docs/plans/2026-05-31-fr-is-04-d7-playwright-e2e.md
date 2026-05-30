# FR-IS-04 D7 — Playwright E2E (PR 3/3)

> slug: fr-is-04-d7-playwright-e2e
> type: qa
> agent: qa-engineer
> 생성: 2026-05-31

## Brief

FR-IS-04(이슈 본문 Markdown + 우선순위/라벨/환경/영향도)의 마지막 조각(D7). FR 분할 3PR 중 3/3.
백엔드(PR #43)·프론트 D6(PR #46) 머지 완료. 이번 PR은 D6에서 구현한 본문/메타필드 UI를 Playwright E2E로 검증.

**시나리오 후보**: 본문 Write/Preview 작성→저장→렌더, 우선순위/영향도 셀렉터 변경(즉시 PATCH), 환경/라벨 저장.
**MSW stateful**: issue-handlers.ts에 5필드 PATCH 영속 이미 구현(D6). E2E는 그 위에서 동작.
**선례**: e2e/issue-type-change.spec.ts(D6 셀렉터), issue-transition.spec.ts(D7), issue-edit-conflict.spec.ts(OCC).

**주의 (메모리)**:
- worktree-node-modules-partial-install: E2E 전 worktree node_modules 정상성 확인 필수(Vite dev 부팅). 깨졌으면 main에서 dist cp 복구.
- e2e-orphan-vite-after-worktree-remove: worktree remove 후 5173 orphan Vite 가능.
- e2e-msw-serviceworker-block: serviceWorkers:'block' 금지(MSW 부팅 깨짐).
- playwright-getbyrole-exact-strict-mode: 같은 텍스트 버튼 여러 곳 → exact/컨테이너 한정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
