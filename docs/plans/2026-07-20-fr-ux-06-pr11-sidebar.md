# FR-UX-06 Phase 3 PR11 — 사이드바 신설

> slug: fr-ux-06-pr11-sidebar
> type: ui
> agent: frontend-engineer
> primary_bc: personalization (물리 identity-access)
> 생성: 2026-07-20

## Brief

`_shell`(현재 passthrough `<Outlet/>` — PR10 #295 산출)을 Jira Cloud 2025 통합 사이드바 + 콘텐츠 레이아웃으로 확장한다. 크롬(Header 등)을 `RootLayout`에서 `_shell`로 이관하고, login/공개 라우트(`dashboards.shared`)의 사이드바 억제는 `isAuthenticated` 게이팅으로 처리한다.

**classify 실측 정정** — classify-task.ts가 `route/apps/web` 키워드를 `backend`로 오분류(PR9·PR10 동일 패턴). 실제는 순수 frontend refactor + UI 신설이라 type=ui, agent=frontend-engineer로 덮음.

**FR 총수 불변 129.** FR-UX-06 마킹은 소비 화면 PR 몫(현 단계 숫자 불변).

### 🔴 착수 전 필독 계약 ([[frontend-nav-aria-label-e2e-contract]])

E2E의 진짜 계약은 DOM 구조가 아니라 **`aria-label` 문자열 4종**. `getByRole('navigation')` 18건이 전부 이 라벨로 스코프됨. 헤더 nav를 사이드바로 옮겨도 라벨만 그대로 달면 spec 무수정 통과. 깨면 폭발 반경 큼.

| aria-label | 현재 소유자 | 의존 |
|---|---|---|
| `메인 메뉴` | `Header.tsx:91` | calendar · dashboard spec |
| `관리 메뉴` | `Header.tsx:107` | notification-policies · audit-logs · webhook (각 2) |
| `프로젝트 뷰 전환` | `board.tsx:477` · `backlog.tsx:59` | e2e 5 + 유닛 5 |
| workflow-scheme `sidebar.nav` | `i18n/workflow-scheme-labels.ts` | workflow-scheme-crud |

**즉사 4대 금지사항.**
1. 🔴 `검색`은 Header에만(`Header.tsx:124`, 5 spec 의존). 사이드바에 검색 항목 추가 = strict mode 위반.
2. 🔴 관리 메뉴 **기본 펼침** 필수. 접으면 webhook/audit-logs/notification-policies spec 클릭 실패(접으려면 3 spec 수정 같은 PR).
3. 🔴 `프로젝트 뷰 전환`을 Radix Tabs로 바꾸지 말 것(role=navigation 소멸 → e2e 5 + 유닛 5 즉사). nav+Link를 탭처럼 스타일링.
4. 🔴 사이드바에 `<h1>` 절대 금지(e2e 34건이 h1 level 1 의존).

★ 규칙. 라우트 이동 = nav+Link, 같은 라우트 패널 전환 = Radix Tabs.

**완화책(TDD red 준비).** 착수 전 `components/layout/__tests__/navigation-contract.test.tsx`로 라벨 4종 + 관리 nav admin 게이팅 어서션. 현 Header 기준 = 즉시 green → 이관 중 라벨 깨면 vitest가 Playwright보다 빨리 잡음.

### 관련 메모리

- [[frontend-nav-aria-label-e2e-contract]] — 필독 계약
- [[tanstack-pathless-layout-router-test-blind]] — PR10 재부모화 근거, _shell 구조
- [[fr-ux-06-jira-redesign-plan]] — 허브(22 PR 체인·확정 6결정·시안)
- [[no-project-list-api-blocks-sidebar]] — DEFAULT_PROJECT_KEY='ATLAS'는 프로젝트 목록 API 부재의 결과
- [[avatar-auth-image-cachebust]] — 아바타 인증 이미지

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
