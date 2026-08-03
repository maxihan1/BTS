# FR-UX-11 F9 — 이슈 목록 셀 인라인 편집

> slug: fr-ux-11-f9-list-cell-inline-edit
> type: ui
> agent: frontend-engineer
> 생성: 2026-08-04

## Brief

**사용자 원문.** `fr-ux-11 f9 진행해줘`

**정본 근거.**
- `docs/plan/product/personalization.md:325` — **F9 — 이슈 목록 셀 인라인 편집**(담당자·우선순위·상태).
  `IssueTable.tsx` · `issue-columns.ts` · `components/issue/meta/*` 재사용 ·
  `components/ui/popover.tsx`(소비처 0→1).
- `docs/plan/product/personalization.md:327` **아키텍처** — 프론트 전용 예상. 기존 이슈 PATCH API 를
  소비한다. 목록 셀은 낙관적 동시성(OCC) 409 를 만나므로 `setQueryData` 부분 갱신 대신
  **invalidate 로 정합**을 맞춘다.
- `docs/design/jira-parity-roadmap.md:61` — F9 행. 선행 **F8**.
- FR-UX-11 의 **D6/D7 마감분**. F8(PR #337)이 D1~D5 를 닫았고 D6 본문의 「F9 목록 셀 3종」·
  D7 의 F9 잔여가 이번 PR 로 충족되면 `[x]` 로 전환된다.

**classify 결과 (override 기록).**
- 스크립트 출력. `type=backend` · `agent=backend-engineer` · `slug=fr-ux-11-f9`
- **정정.** `type=ui` · `agent=frontend-engineer` · `slug=fr-ux-11-f9-list-cell-inline-edit`
- 사유. classify 키워드 신호 0 → `backend` 기본값. 정본 3건이 프론트 전용을 명시하고
  F8(#337)이 백엔드 0줄(`git diff --exit-code` EXIT 0)로 선례를 남겼다.
- `primary_bc` = `issue-tracking` (유지)

**선행 읽기에서 고른 learnings (이 작업 관련).**
- `learnings.md:616` 메타 mutation `setQueryData`(부분응답)가 본문을 placeholder 로 덮는 플리커 —
  메타 mutation 6종은 **invalidate-only 로 통일**돼 있다. F9 가 같은 계열이다.
- `learnings.md:631` UI PR 이 E2E 를 후속 PR 로 미루면 기존 E2E 회귀가 머지 시점에 잠복 —
  목록 화면 셀렉터 충돌 위험. 같은 화면 기존 E2E 동반 실행 필요.
- `learnings.md:600` Zod 응답 스키마와 산재 인라인 mock.
- 메모리 [[fr-ux-11-f8-inline-edit-done]] 의 미해결 4건(E2E 셀렉터 `exact` 판별식 부재 ·
  `disabled:opacity-50` 전역 미적용 · 룰 E 가 D 마커 미검사 · M-3 저장 후 편집창 미닫힘).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
