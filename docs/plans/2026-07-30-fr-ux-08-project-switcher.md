# FR-UX-08 — 프로젝트 스위처 · 최근 항목 · 내 작업

> slug: fr-ux-08-project-switcher
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-30

## Brief

**사용자 원문.** FR-UX-08 프로젝트 스위처 + 사이드바 "내 작업"·"최근 항목" 구현 (F12 + F17).

**classify 결과.** `type=ui` · `agent=frontend-engineer` (원 출력의 `slug=fr-ux-08-ui-apps-web-react` · `primary_bc=issue-tracking` 은 아래대로 정정).

**정정 2건.**
1. **slug** — 정본 `docs/plan/product/personalization.md:213` 이 `**Plan slug**. fr-ux-08-project-switcher` 로 이미 지정. classify 자동 생성 slug 를 기각하고 정본을 따른다.
2. **primary_bc** — `issue-tracking` 이 아니라 **personalization**(논리 BC, 물리는 identity-access). 다만 본 작업은 `apps/web/**` 단일 SPA 전용이라 백엔드 모듈 경계와 무관하다.

**범위.** 프론트 전용. 신규 API 0 · 마이그레이션 0 · 백엔드 Kotlin 0줄 · **FR 카운트 불변 139**(FR-UX-08 은 `docs/plan/fr-index.md:186` 에 이미 등록됨 — 신설 아님).

**승계 PR 2건** (로드맵 정본 `~/.claude/plans/ui-ux-sorted-kay.md` §PR 체인 Tier 2).

- **F12 — 프로젝트 스위처 + 트리 펼침 영속.** §4.5(FR-UX-07, PR #320)가 "활성 프로젝트"라는 컨텍스트를 만들었지만 **그것을 손으로 바꿀 UI 가 없다.** 신규 `components/project/ProjectSwitcher.tsx` · `TopBar.tsx` · `ProjectTree.tsx:368-370` · 신규 `hooks/use-recent-projects.ts`.
- **F17 — 사이드바 "내 작업"(프로젝트 스코프) + "최근 항목".** `i18n/nav-labels.ts:9` 의 제외 주석을 해제하고 `Sidebar.tsx:47-51` 에 배선.

**착수 시점에 확정된 제약 3종.**

- 🛑 **스위처를 `<nav>` 로 만들면 안 된다.** `프로젝트` 가 기존 `aria-label="프로젝트 뷰 전환"` 의 substring 이라 `getByRole('navigation')` 계약(E2E 18건)과 충돌한다. `components/ui/popover.tsx`(현재 소비처 0) + `role="listbox"` 가 정답.
- 🛑 **cross-project "내 작업"은 범위 밖**(로드맵 B3). `IssueApplicationService.kt:1021` 이 `assertPermission(actor, BROWSE, IssueScope.Project(projectKey))` 로 프로젝트 스코프를 강제하고, `UserCalendarLookupAdapter.kt:25-38` 이 모든 visibility 술어가 `PROJECTS.KEY.eq(projectKey)` 단일 축으로 하드코딩됨을 명시한다. 합집합 조립은 fail-open 사고. v1 은 **활성 프로젝트 스코프**(`?assignee=me`).
- **localStorage 영속 템플릿.** `hooks/use-sidebar-collapsed.ts:15-45`(zustand + fail-safe 3중 폴백)를 §4.5 와 같은 방식으로 복제.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
