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

## 스펙

전체 스펙. [docs/specs/2026-07-20-fr-ux-06-pr11-sidebar.md](../specs/2026-07-20-fr-ux-06-pr11-sidebar.md)

핵심 3줄 요약.
- `ShellLayout`(_shell)이 `isAuthenticated` 게이팅으로 상단바(축소 Header)+사이드바(264px)+콘텐츠를 렌더. 공개 공유(dashboards.shared)는 bare Outlet.
- 사이드바 = `메인 메뉴` nav(이슈·대시보드·캘린더·즐겨찾기 — 실 라우트만) + `관리 메뉴` nav(isSystemAdmin 게이팅·기본펼침·6링크). 프로젝트 트리는 PR12.
- 접기 상태 localStorage 지속. aria-label 4종·검색 단일·관리 기본펼침·h1 금지 계약 무위반. navigation-contract.test 先작성.

Maxi 확정 3결정: S1 데스크탑 우선(반응형)·S2 localStorage(접기)·S3 실재 항목만(백킹 없는 항목 미포함).

## Brainstorming Check

✅ 통과 (1회 iteration). 적대적 gap 헌팅 6건 발견 → 전부 자체 해소(실측 기반, Maxi 결정 불필요). G1 RootLayout 오버레이 잔류·G2 설정 기어 404 회피·G3 도움말 버튼·G4 테스트 마이그레이션 무관성·G5 접기 단축키 keymap 미통합·G6 좁은 폭 body h-scroll 금지. 상세는 스펙 §Brainstorming Check.

office-hours/design-shotgun는 스킵 — 디자인 잠김(ADR 확정 6결정·디자인 스펙 §3.1·프로토타입)·FR 정의 완료라 직접 기술 스펙 작성이 적합([[bts-spec-office-hours-mismatch]] 선례, Maxi 기존 선택).

## Plan

> 전 task agent = `frontend-engineer`. 경로는 repo 루트 기준(worktree 내부). TDD red→green→refactor 강제.
> **★ 크롬 스왑 원자성**: Sidebar(T5)·TopBar(T6)는 컴포넌트만 생성(트리 미배선). T7이 ShellLayout 배선 + Header 축소를 **한 커밋에** 처리 → 이중 nav strict-mode 충돌 방지.
> **★ navigation-contract(T1)**: 앱 라우터 트리를 렌더하는 마이그레이션 무관 가드. W1서 현 Header 기준 green → T7 후 Sidebar 기준 green. 라벨 깨지면 red. **반드시 첫 test 커밋.**

### Task 1. navigation-contract 계약 가드 (先작성·회귀 가드)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/__tests__/navigation-contract.test.tsx`]
- depends-on: []

**RED/GREEN(특수 — 회귀 가드)**: 이 테스트는 **기존 계약을 잠그는 characterization 가드**라 작성 즉시 green(현 Header가 라벨을 이미 소유). `router.test.tsx` 방식으로 `createMemoryHistory` + `routeTree`를 인증 사용자 mock으로 렌더하고 어서션:
- `getByRole('navigation', { name: '메인 메뉴' })` 존재 + 그 안에 `대시보드`·`캘린더` 링크(계약: `dashboard.spec:440`·`calendar.spec:88`).
- `isSystemAdmin=true` mock → `getByRole('navigation', { name: '관리 메뉴' })` 존재 + 6링크. `isSystemAdmin=false` → 관리 nav 부재.
- `검색`(`aria-label`)은 정확히 1개(strict 단일).
- 사이드바 영역에 `<h1>` 없음(현재 trivially true).

**REFACTOR**: mock 헬퍼(인증/admin 사용자) 추출.

**검증**: `(cd apps/web && node_modules/.bin/vitest run src/components/layout/__tests__/navigation-contract.test.tsx)` → green.

### Task 2. i18n/nav-labels.ts — nav 라벨 상수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/nav-labels.ts`, `apps/web/src/i18n/__tests__/nav-labels.test.ts`]
- depends-on: []

**RED**: `nav-labels.test.ts` — `navLabels`가 🔒 e2e 계약 문자열(`메인 메뉴`·`관리 메뉴`·`프로젝트 뷰 전환`·`검색`) 정확 일치 + 사이드바 라벨(myWork 제외 — S3, `이슈`/`대시보드`/`캘린더`/`즐겨찾기`/`만들기`/`관리`/`사이드바 접기`/`사이드바 펼치기`)을 가진다. 실패: 파일 없음.

**GREEN**: `nav-labels.ts` 상수 생성(디자인 스펙 §11 기준, 단 백킹 없는 myWork/recent/filters/projects 제외 — S3).

**REFACTOR**: `as const` + 타입 export.

**검증**: `(cd apps/web && node_modules/.bin/vitest run src/i18n/__tests__/nav-labels.test.ts)`.

### Task 3. hooks/use-sidebar-collapsed.ts — localStorage 접기 상태

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-sidebar-collapsed.ts`, `apps/web/src/hooks/__tests__/use-sidebar-collapsed.test.ts`]
- depends-on: []

**RED**: hook 테스트(`renderHook`) — 기본 펼침(localStorage 없음), toggle→localStorage `bts.sidebar.collapsed` 저장, mount 시 복원, JSON 파싱 실패 시 펼침(fail-safe, E4). 실패: 파일 없음.

**GREEN**: `use-sidebar-collapsed.ts` — `useState` + `localStorage` get/set, try/catch fail-safe.

**REFACTOR**: 스토리지 키 상수화, SSR-safe(`typeof window`).

**검증**: `(cd apps/web && node_modules/.bin/vitest run src/hooks/__tests__/use-sidebar-collapsed.test.ts)`.

### Task 4. --sidebar-* 토큰 ADS화 + state-tokens 동결 계약 갱신

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/index.css`, `apps/web/src/components/ui/__tests__/state-tokens.test.ts`]
- depends-on: []

**RED**: `state-tokens.test.ts` **동결 리스트(L202-205)에서 `--sidebar-*` 8종 제거** + 사이드바 토큰이 ADS 값(라이트/다크 both, hex 또는 ADS surface 별칭)임을 어서션하는 케이스 추가. 현재 index.css는 oklch라 RED.
- ★ 주의: `--chart-*`·`--syntax-*`는 동결 유지(건드리지 않음).

**GREEN**: `index.css`의 `--sidebar-*` 8종(`:root`+`.dark`)을 ADS 값으로 교체. **설계 결정(frontend-engineer)**: 디자인 스펙 §3.1이 사이드바 배경을 `--surface-sunken`으로 규정하므로, `--sidebar` 계열을 ADS surface/border/brand 토큰에 **별칭**(`var(--surface-sunken)` 등)하거나 동등 hex로. D7 "소비되는 PR에서 정의" 준수 — 죽은 값 금지.

**REFACTOR**: 주석으로 별칭 근거 명시.

**검증**: `(cd apps/web && node_modules/.bin/vitest run src/components/ui/__tests__/state-tokens.test.ts)`.

### Task 5. Sidebar.tsx — 사이드바 컴포넌트 (트리 미배선)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/Sidebar.tsx`, `apps/web/src/components/layout/__tests__/Sidebar.test.tsx`]
- depends-on: [2, 3]

**RED**: `Sidebar.test.tsx` — 렌더 시 `<nav aria-label="메인 메뉴">`에 이슈/대시보드/캘린더 링크 + 즐겨찾기(FavoritesMenu 트리거); `isSystemAdmin=true`면 `<nav aria-label="관리 메뉴">` **기본 펼침** + 6링크(ADMIN_LINKS 정본), `false`면 관리 nav 부재; 사이드바에 `<h1>` 없음; `검색` 항목 없음(Header 단일); 접힘 상태 반영(use-sidebar-collapsed 소비). 실패: 파일 없음.

**GREEN**: `Sidebar.tsx` — 264px, `--surface-sunken` 배경(§3.1), 메인/관리 nav, `user?.isSystemAdmin === true` 게이팅, `FavoritesMenu` 재사용(import·미수정), `useSidebarCollapsed` 소비. h1 금지. **이 task는 컴포넌트만 생성 — 어디에도 렌더 안 함**(T7이 배선).

**REFACTOR**: 링크 배열 상수화, 활성 스타일 공통화.

**검증**: `(cd apps/web && node_modules/.bin/vitest run src/components/layout/__tests__/Sidebar.test.tsx)`.

### Task 6. TopBar.tsx — 상단바 컴포넌트 (트리 미배선)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/TopBar.tsx`, `apps/web/src/components/layout/__tests__/TopBar.test.tsx`]
- depends-on: [2, 3]

**RED**: `TopBar.test.tsx` — 사이드바 토글(`aria-label` 상태별) + 로고(→`/dashboards`) + 검색(`aria-label="검색"` 정확 1개) + 만들기(→`/issues/new`) + 알림(InboxBell) + 도움말(트리거) + 설정(기존 서브라우트/계정, `/settings` 죽은링크 금지) + 계정 드롭다운. 실패: 파일 없음.

**GREEN**: `TopBar.tsx` — 48px, 기존 Header의 검색·InboxBell·계정 드롭다운 로직 재사용, 사이드바 토글(useSidebarCollapsed), 도움말→ShortcutsHelpDialog 오픈 콜백 prop. **컴포넌트만 생성 — T7이 배선.**

**REFACTOR**: 아이콘 버튼 공통화.

**검증**: `(cd apps/web && node_modules/.bin/vitest run src/components/layout/__tests__/TopBar.test.tsx)`.

### Task 7. 크롬 스왑 — ShellLayout 확장 + Header 축소 + __root 이관 (원자적)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/ShellLayout.tsx`, `apps/web/src/components/layout/__tests__/ShellLayout.test.tsx`, `apps/web/src/components/Header.tsx`, `apps/web/src/components/Header.test.tsx`, `apps/web/src/routes/__root.tsx`, `apps/web/src/routes/__tests__/__root.test.tsx`]
- depends-on: [5, 6]

**RED**: `ShellLayout.test.tsx` — `isAuthenticated=true` → TopBar + Sidebar + 콘텐츠 Outlet 렌더; `false` → bare Outlet(사이드바 부재, E1 공개공유). `Header.test.tsx` 갱신 — 축소 후 Header에 `메인 메뉴`/`관리 메뉴` nav 부재(어서션을 Sidebar/contract로 이관). 실패: ShellLayout 미확장·Header 여전히 nav 보유.

**GREEN(원자)**: 한 커밋에 —
- `ShellLayout.tsx`: `useIsAuthenticated` 게이팅 → true면 `<TopBar/>`+`<Sidebar/>`+`<main><Outlet/></main>`, false면 `<Outlet/>`. 도움말 오픈 상태를 TopBar↔ShortcutsHelpDialog로 연결(또는 RootLayout 유지 배선).
- `Header.tsx`: `메인 메뉴`·`관리 메뉴` nav 제거(TopBar/Sidebar가 흡수). ★ 남는 로직 중복 제거 후 Header가 빈 껍데기면 파일 삭제까지 고려(단 import 정리).
- `__root.tsx`: `<Header/>` 렌더 중단. CommandPalette·ShortcutsHelpDialog·useNotificationStream·useKeyboardShortcuts는 **잔류**(G1). `<main>` 중복 방지(ShellLayout이 main 소유 시 __root는 wrapper만).
- `__root.test.tsx`·`Header.test.tsx`: 이관 반영.

**REFACTOR**: 중복 import 정리, 데드코드 제거(내 변경이 만든 것만).

**검증**: `(cd apps/web && node_modules/.bin/vitest run)` 전량 green + `navigation-contract`(T1) green 유지 + `pnpm --dir apps/web typecheck && pnpm --dir apps/web lint`.

## Plan 메타

- task 수: 7
- wave 예상: W1[T1·T2·T3·T4 병렬] → W2[T5·T6 병렬] → W3[T7] = **3 wave**
- 예상 시간: 직렬 ~30분 / 3-wave 병렬 ~12분
- TDD 강제: yes (T1은 회귀 가드 특수 — green baseline, 첫 test 커밋)
- 추가 검증(qa-engineer): Playwright 전수 — 특히 calendar·dashboard·notification-policies·webhook·audit-logs·board·backlog·already-authed·dashboards.shared. CI에 e2e 잡 없음([[frontend-ci-10min-timeout-nonrequired]]) → 로컬 전수 필수.
- 🔴 hard-won 커플링: (a) T4가 state-tokens 동결 리스트 갱신 안 하면 test 깨짐, (b) T7 원자성 — Sidebar/TopBar 배선과 Header 축소 동시(이중 nav 방지), (c) navigation-contract 마이그레이션 무관 렌더.

## 리뷰 결과 (← /bts-review-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
