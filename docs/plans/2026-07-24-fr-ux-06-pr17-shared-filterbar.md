# FR-UX-06 Phase 5 PR17 — IssueFilterBar + BoardFilterBar → 공유 FilterBar 통합

> slug: fr-ux-06-pr17-shared-filterbar
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-24
> 마스터 플랜: docs/plans/2026-07-17-fr-ux-06-jira-redesign/plan.md (PR17)
> 허브 메모리: fr-ux-06-jira-redesign-plan

## Brief

FR-UX-06 Jira 재개편 Phase 5(화면)의 첫 PR. 거의 클론된 두 필터바 컴포넌트
`components/issues/IssueFilterBar.tsx` · `components/board/BoardFilterBar.tsx`
(+ 각 test)를 공유 `FilterBar` 하나로 통합한다. i18n 라벨 이원화 제거,
약 -350 LOC 순감 목표.

- 소비처: `routes/issues.index.tsx`(IssueFilterBar) · `routes/projects.$projectKey.board.tsx`(BoardFilterBar)
- 순수 프론트(apps/web). 백엔드/마이그레이션 0. FR 총수 불변 129.
- FR-UX-06 D3~D7 진척 마킹은 소비 화면 PR에서 (허브 메모리 규칙).

**classify 정정**. classify-task가 backend/backend-engineer/issue-tracking으로 오판
→ controller가 ui/frontend-engineer로 정정(순수 apps/web 컴포넌트 리팩터). #295·#298·#300 선례 동형.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
