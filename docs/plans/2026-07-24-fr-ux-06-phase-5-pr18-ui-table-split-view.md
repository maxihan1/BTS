# FR-UX-06 Phase 5 PR18 — 이슈 목록 카드 → ui/table 네비게이터 (정렬·컬럼 선택·split view)

> slug: fr-ux-06-phase-5-pr18-ui-table-split-view
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (프론트 apps/web 단일)
> 생성: 2026-07-24

## Brief

**사용자 원문**. FR-UX-06 Phase 5 PR18 — 이슈 목록 카드를 `ui/table` 네비게이터로 전환 (정렬·컬럼 선택·split view). 허브 `fr-ux-06-jira-redesign-plan` 22 PR 체인 중 Phase 5 두 번째 화면 PR (PR17=공유 FilterBar 다음).

**classify 결과**. type=ui · agent=frontend-engineer · primary_bc=issue-tracking · slug=fr-ux-06-phase-5-pr18-ui-table-split-view.

**컨텍스트**. `ui/table` 프리미티브는 PR2(#286)에서 이미 도입됨. Phase 5 화면 개편의 일부로, 이슈 목록(issues.index 라우트)의 카드 레이아웃을 Jira Cloud 방식 테이블 네비게이터로 전환한다.

**착수 전 필독 (메모리)**.
- [[frontend-nav-aria-label-e2e-contract]] — h1 단일(e2e 34건)·role 셀렉터·nav 라벨 4종 계약
- [[playwright-getbyrole-exact-strict-mode]] — 이슈 행/셀 텍스트 셀렉터 strict 위반 주의
- [[e2e-playwright-filter-arg-drop]] — vitest·playwright 둘 다 `pnpm test -- <파일>` 인자 삼킴 → 바이너리 직접 호출
- [[frontend-ci-10min-timeout-nonrequired]] — CI에 e2e 잡 없음 → 로컬 e2e 필수

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
