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

## 도메인 정리

- **BC**: personalization (논리) / `apps/web` + issue-tracking API 소비 (물리). FR-UX-06 ADR D5 "논리 ≠ 물리" 승계.
- **영향 엔티티**: 없음(신규 도메인 엔티티 0). 사이드바·앱 셸·전역 네비는 **UI/IA 어휘**라 glossary(DDD 유비쿼터스 언어) 대상 아님.
- **새 용어**: 없음.
- **기존 결정 충돌**: 없음. PR11은 FR-UX-06 ADR을 **구현**한다 — D2(사이드바 IA)·D3(_shell)·D4(nav vs Tabs + aria-label 계약)·D7(`--sidebar-*` 8종을 이 PR에서 덮어씀).
- **관련 ADR**: `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md` (D1~D8). 신규 ADR 불필요.

### 스코프 확정 (허브 plan PR11/12/13 슬라이싱 실측)

| PR | 범위 | 파일 |
|---|---|---|
| **PR11 (본 작업)** | 전역 사이드바 신설 + `Header` 축소 | +4파일 ≈450, 🔴 실질 최대 |
| PR12 | **프로젝트 사이드바 확장 + `ProjectNavTabs` 통합** — 프로젝트 트리(섹션 2)가 여기서 `GET /api/v1/projects` 소비 | +2파일 |
| PR13 | `PageLayout`/`PageHeader`/`Breadcrumb` + `/settings`·`/admin` 인덱스 | +3파일 |

**★ 프로젝트 트리(디자인 스펙 §3.1 섹션 2)는 PR11이 아니라 PR12.** 따라서 **PR11은 프로젝트 목록 API를 소비하지 않는다.** PR11 사이드바 = 상단바 축소 + 섹션 1(내 작업/최근/즐겨찾기)·섹션 3(이슈/대시보드/캘린더/필터)·섹션 4(관리, isSystemAdmin 게이팅+기본펼침).

### 실측으로 갱신된 사실 (메모리 스냅샷과 상이)

1. **`GET /api/v1/projects` 이제 존재·fail-closed 안전** ([[no-project-list-api-blocks-sidebar]] 백엔드 차단 주장은 이제 stale). `ProjectQueryController.kt:64`(issue-tracking, #277 PR-3 산출) — `@PreAuthorize("isAuthenticated()")` + `listAccessible(actor, archived)`가 **멤버십 없으면 빈 배열**(fail-closed). 메모리가 경고한 cross-project fail-open 위험은 백엔드에서 해소됨. **단 이건 PR12 소비 대상이고 PR11 무관.**
2. **관리 nav 링크 6개**(디자인 스펙 §3.1은 5개만 나열 — `전역 권한`(/admin/global-permissions) 누락, FR-PM-10 #284로 추가됨). `Header.tsx ADMIN_LINKS` 정본 = 워크플로우 스킴·감사 로그·**전역 권한**·알림 정책·Webhook·Slack 연결. **6개 전부 보존.**
3. **관리 게이팅 술어** = `user?.isSystemAdmin === true` (명시 `=== true` 비교, `routeGuard.requireSystemAdmin` 일관). navigation-contract 테스트가 이 술어로 어서션.

### 현 Header aria-label 소유자 (PR11이 사이드바로 이관하며 보존)

| aria-label | 현재 위치 | PR11 처리 |
|---|---|---|
| `메인 메뉴` | `Header.tsx` nav (대시보드·캘린더 2링크) | 사이드바로 이관, 라벨 보존 |
| `관리 메뉴` | `Header.tsx` nav (isAdmin 게이팅, 6링크) | 사이드바로 이관, 라벨+게이팅+기본펼침 보존 |
| `프로젝트 뷰 전환` | `board.tsx:477`·`backlog.tsx:59` | **PR11 미접촉**(PR12 ProjectNavTabs 몫) → 그대로 존재 |
| workflow-scheme `sidebar.nav` | `i18n/workflow-scheme-labels.ts` | 미접촉 |

`검색`(Header 버튼)·즐겨찾기·알림·계정 드롭다운은 상단바에 잔류.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
