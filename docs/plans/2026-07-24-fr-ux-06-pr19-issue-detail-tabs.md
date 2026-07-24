# FR-UX-06 Phase 5 PR19 — 이슈 상세 탭화 + IssueMetaPanel 분해

> slug: fr-ux-06-pr19-issue-detail-tabs
> type: ui (classify가 backend로 오분류 → 실측 정정, FR-UX-06 UI 시리즈 선례 PR9/PR10/PR12 동일 함정)
> agent: frontend-engineer
> primary_bc: issue-tracking (프론트)
> 생성: 2026-07-24

## Brief

FR-UX-06(BTS UI/UX를 Jira Cloud 2025 방식으로 전면 개편) Phase 5(화면) 세 번째 PR.
이슈 상세 화면을 Radix Tabs로 탭화하고, 거대해진 `IssueMetaPanel`(~1204줄)을 분해한다.
split view(목록+상세 2분할)도 이 PR과 함께 검토 (PR18에서 의도적으로 이연).

### 착수 전 필독 (메모리)
- `frontend-nav-aria-label-e2e-contract` — **규칙: 라우트 변경=nav+Link, 같은 라우트 패널 전환=Radix Tabs.**
  이슈 상세 활동 탭은 탭 0개·라우팅 아님 → **여기선 Tabs가 정답**.
- `playwright-getbyrole-exact-strict-mode` — 단일단어 라벨(저장/삭제/취소/확인/추가) substring 매칭 위험 → `exact:true`.
- `e2e-playwright-filter-arg-drop` — 특정 spec만 돌리려면 `apps/web/node_modules/.bin/playwright` 직접 호출. CI에 e2e 잡 없음 → 로컬 e2e 필수.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
