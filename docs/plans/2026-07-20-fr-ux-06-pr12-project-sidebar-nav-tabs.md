# FR-UX-06 Phase 3 PR12 — 프로젝트 사이드바 확장 + ProjectNavTabs + C3 랜드마크 복원

> slug: fr-ux-06-pr12-project-sidebar-nav-tabs
> type: ui
> agent: frontend-engineer
> primary_bc: personalization (물리 identity-access)
> 생성: 2026-07-20

## Brief

FR-UX-06 Jira 재개편 Phase 3(Shell)의 PR12. PR11에서 전역 사이드바 뼈대
(ShellLayout·TopBar·Sidebar·AccountMenu)가 이미 렌더된다. PR12는 세 가지를 얹는다.

1. **프로젝트 트리** (디자인 스펙 §3.1 섹션2) — 사이드바에 프로젝트 목록 표시.
   `GET /api/v1/projects` 소비 (PR11에서 존재·fail-closed 확인됨, #277 PR-3 ProjectQueryController).
2. **ProjectNavTabs (프로젝트 뷰 전환)** — 보드/백로그/타임라인 등 뷰 전환 통합.
   🔴 **Radix Tabs 금지** — `role="navigation"` 소멸 시 e2e 5 + 유닛 5 즉사.
   정답 = `<nav aria-label="프로젝트 뷰 전환">` + `<Link>` 를 탭처럼 스타일링 (라우트 이동).
3. **C3 랜드마크 복원** — PR11 게이트2 이연분. `<main>` 을 `__root` 에서 ShellLayout으로
   이관해 `banner` role 회복.

순수 프론트엔드 (React 19 / TS strict). 백엔드/Kotlin 미변경. GET /api/v1/projects는 기존 API 소비만.
FR 총수 불변 129 (마킹은 #277 PR-5 몫).

**분류 정정 기록**: classifier가 backend/backend-engineer로 오분류 → ui/frontend-engineer로 실측 정정
(PR9 route→api·PR10 router→api 동일 반복 오분류, gstack-diff-scope-blind-to-kotlin 계열).

**착수 전 필독 계약**:
- `frontend-nav-aria-label-e2e-contract` — aria-label 4종 e2e 계약, 검색 Header 단일,
  관리메뉴 기본펼침, **뷰전환 Tabs 금지**. 규칙: 라우트 바뀌면 nav+Link, 같은 라우트 패널만 바뀌면 Tabs.
  ⚠️ 이 메모리는 PR11 전 관측 — Header.tsx 삭제됐으니 라벨 소유 위치는 Sidebar 계열로 실측 재확인 필요.
- `fr-ux-06-jira-redesign-plan` (허브), `fr-ux-06-pr11-sidebar-done` (PR11 세부·교훈).
- `no-project-list-api-blocks-sidebar` (GET /api/v1/projects 존재로 갱신됨).

**검증 baseline (PR11 시점)**: 유닛 7391, e2e 전수 530 passed, typecheck 0, lint 0.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
