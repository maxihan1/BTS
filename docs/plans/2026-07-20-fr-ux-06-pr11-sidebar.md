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

**RED/GREEN(특수 — 회귀 가드)**: 이 테스트는 **기존 계약을 잠그는 characterization 가드**라 작성 즉시 green(현 Header가 라벨을 이미 소유). **★셋업(리뷰 refinement)**: 인증 상태는 `authStore` mock(`useIsAuthenticated`→true) + `isSystemAdmin` 사용자 mock 필요. `router.test.tsx`의 기존 하네스(`createMemoryHistory`+`routeTree`+QueryClientProvider mock) 패턴 재사용. 전 앱 렌더가 무겁다면 크롬을 렌더하는 최상위 레이아웃 합성만 렌더하되 **Header 직접 렌더는 피함**(마이그레이션 무관성 유지 — 라우터/레이아웃 합성 레벨). 어서션:
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
- 🔴 **non-vacuous 어서션(리뷰 refinement, [[archunit-vacuous-rule-silent-pass]])**: 새 사이드바 어서션은 공허하면 안 됨 — 별칭이면 `declarationOf(--sidebar)`가 특정 surface var를 참조함을, hex면 정확 hex를 라이트/다크 각각 어서션. 룰 추가 후 일부러 위반(oklch 잔존) 넣어 red 확인 → 값 넣어 green.

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
- `Header.tsx`: `메인 메뉴`·`관리 메뉴` nav 제거. **★죽은 Header 방지(리뷰 refinement)**: TopBar(T6)가 검색·InboxBell·계정 드롭다운·계정라벨 로직을 완전 흡수하므로, 축소 후 Header가 top-bar 요소를 이중 소유하지 않게 한다 — Header 소비처는 `__root.tsx` **단일**이라 TopBar로 치환 후 `Header.tsx`+`Header.test.tsx` 삭제가 깔끔(또는 Header=TopBar로 리네임/재작성). 축소 잔재·TopBar와의 중복 금지.
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

## 구현 결과 (bts-impl, 2026-07-20)

전 8 task 완료 (계획 7 + T8 fix). frontend-engineer sub-agent wave dispatch, TDD red→green 강제, controller가 각 wave git log + 전량 테스트 실물 검증.

| task | 커밋 (test→feat/fix) | 결과 |
|---|---|---|
| T1 navigation-contract 가드 | 6b1004d5e(test, characterization green baseline) | 5 테스트 |
| T2 nav-labels | ced506bfc→4dd5299e3 | 16 |
| T3 use-sidebar-collapsed | 2e521b9eb→dc947745d | (T8서 스토어 전환) |
| T4 --sidebar 토큰+동결 | d1e7828c4→78223e3fd→f6e66c203 | 동결 해제·ADS hex·129 어서션 |
| T5 Sidebar | 174d73e7f→01d7309a0 | 7 |
| T6 TopBar(+AccountMenu) | 1b951b80c→fb3a89520 | 9 |
| T7 크롬 스왑 | 0e0023f9a→48e6bc817 | ShellLayout 배선·Header 삭제 |
| **T8 fix 공유 스토어** | 82dc53f30→b2a8e685d | 접기 상태 버그 수정 |

### ★ T7이 발견한 통합 버그 → T8 fix

T5(Sidebar)·T6(TopBar)를 격리 빌드 후 T7이 처음 형제로 합성하자, `useSidebarCollapsed`가 컴포넌트-로컬 `useState`라 **두 인스턴스가 상태 미공유** → TopBar 토글이 Sidebar를 안 접는 FR5 기능 결함이 드러났다. T7 agent가 정확히 적발하고 스코프 밖이라 미수정 보고(옳은 판단). **T8**에서 `useSidebarCollapsed`를 zustand 공유 스토어(authStore 동형)로 전환, RED(T-SC-5 크로스 인스턴스 미공유 재현)→GREEN. Sidebar/TopBar는 API(`{collapsed,toggle}`) 불변이라 무수정. **교훈: 격리 빌드 컴포넌트의 공유 상태 결함은 합성 시점에만 드러남 — 통합 검증 필수.**

### ★ 공유 worktree 병렬 커밋 레이스 (W2)

T5·T6 병렬 dispatch서 `--no-verify`로 lint-staged 공유stash는 피했으나, 같은 git 인덱스 커밋 레이스로 T6 green이 orphan 커밋에 섞임. **두 agent 보고가 충돌** → controller가 `git show`/`git log` 실물 대조로 진상 규명(T6 green이 미커밋 staged 잔류) → pathspec 정리 커밋(fb3a89520). 데이터 손실 0. ([[parallel-review-mutation-contaminates-peers]]·[[worktree-lint-staged-shared-git-stash-collision]])

### 설계 결정 (구현 중)

- **T4 토큰**: `--surface-*` ADS 별칭 var가 실제로 없어(PR3가 shadcn명 `--background`/`--card`/`--muted`로 구현) `--sidebar-*` 8종을 **ADS hex 리터럴**로(§5.3 매핑, 기존 파일 관례). state-tokens 동결 리스트서 --sidebar 제거·`--chart/--syntax` 동결 유지.
- **T7 도움말**: ShortcutsHelpDialog가 __root 소유라 ShellLayout서 접근 불가 → TopBar 도움말 버튼을 `onHelpClick` 있을 때만 조건부 렌더, 미전달로 **버튼 미노출**(dead 버튼 방지). `?` 단축키 도움말은 __root useKeyboardShortcuts로 무회귀. 버튼 배선은 후속(help 상태 lift 필요).
- **T7 main 단일**: __root `<main>` 유지, ShellLayout 콘텐츠는 `<div>`(E7 이중 main 방지, login 랜드마크 회귀 방지).
- **T7 router.test**: Sidebar 이슈 nav 신설로 `/issues` 링크 1→2(스펙 E2 예견), 둘 다 href 검증으로 갱신(비약화 아님).

### 검증 (controller 실측)

- 전량 vitest: **468 파일 / 7379 테스트 green** (baseline 7353 → +26 순증). TDD 순서 전 task 정합.
- typecheck: EXIT 0. eslint src: **0 errors**(warning 8건은 사전존재 SlackResultBanner/WeekGrid, 무관).
- e2e 계약-크리티컬 8 spec: **39 passed/1 skipped** — 메인 메뉴(calendar·dashboard)·관리 메뉴+게이팅(notification-policies·webhook·audit-logs)·login/shell(already-authed·login-happy)·공개공유 bare shell(dashboard-share) 전부 green. aria-label 이관 무위반 실증.
- e2e 전수 124 spec/530 테스트: 529 passed + **1 회귀 발견·수정**(아래) → **530 passed**.

### ★ 전수 e2e가 잡은 회귀 → 타임라인 클릭 e2e 견고화 (fca4d3d13, test-only)

전수 e2e에서 `timeline.spec:505 S-DEPS-REALCLICK`(FR-TL-02 의존 라인 실클릭) 1건 실패. **flaky로 단정 않고 baseline 대조**([[e2e-flaky-timeout-masks-transient-url-race]]): main 통과(1.4s)↔PR11 실패(30s) = **PR11 회귀 확정**. 원인 격리(z-index·overflow-y-auto·h-screen 아님 → `<Sidebar/>` 제거 시 통과). **DOM 정밀 진단**: 의존 라인 hit-path가 폭 1608px, bbox 중심 x=1580이 사이드바로 좁아진 뷰포트(Desktop Chrome 1280) **밖** → `elementFromPoint`=null, force-click도 실패. **스크린샷 확인: 라인 시각적으로 정상 연결 = 앱 정렬 버그 아님**. 즉 **넓은 라인의 기하 중심을 클릭하는 e2e가 좁아진 뷰포트에 취약**한 것(사용자는 보이는 라인 클릭 가능). **fix = e2e test-only**: qa-engineer가 `getPointAtLength`/`getScreenCTM`로 stroke 위 **화면 안 지점**을 런타임 계산해 `click({position})`(하드코딩 아님), 어서션(data-selected/data-dimmed) 유지=**비약화**. 화면 안 지점 클릭이 실제 선택됨을 확인=**앱 정상 확증**. 인접 6클릭 안전 점검. timeline.spec 10 passed ×3 안정. **교훈: 격리 통과 e2e도 전수서 레이아웃 회귀를 잡는다 — 좌표-정밀 SVG 클릭 테스트는 뷰포트 폭에 취약, getScreenCTM 런타임 계산이 견고.**

## 리뷰 결과

### plan 리뷰 (design+eng 렌즈, 2026-07-20)

TYPE=ui → 라우팅상 plan-design-review이나 **디자인이 잠겨(ADR 확정 6결정·디자인 스펙 §3.1·프로토타입 시안)** 진짜 리스크가 엔지니어링 계약이라, 대화형 design-review 대신 plan에 대한 **적대적 리뷰(design 충실도 + eng 리스크)**로 통합. 디자인 충실도(264px·4섹션·토큰·라이트/다크)는 §3.1 정본을 T2/T4/T5/T6가 그대로 반영 — deviation 0.

- ✅ **통과**: E2E 계약(aria-label 4종·검색단일·관리기본펼침·h1금지)을 T1 회귀가드 + T5/T7 어서션이 다층 방어. `메인 메뉴` 내용 계약(대시보드·캘린더 링크)·`관리` 게이팅 실측 반영.
- ✅ **통과**: T7 크롬 스왑 원자성(이중 nav strict-mode 충돌 방지) 설계 확인. Sidebar/TopBar는 컴포넌트만 선생성(트리 미배선).
- ⚠️ **refinement 4건 반영(BLOCKER 아님)**:
  1. T1 셋업 — authStore/isSystemAdmin mock + router.test 하네스 재사용 명시.
  2. T4 — 동결 제거 후 새 어서션 non-vacuous 강제([[archunit-vacuous-rule-silent-pass]]).
  3. T7 — TopBar가 Header top-bar 완전 흡수, 소비처 __root 단일이라 Header 삭제/치환(죽은 축소 잔재·중복 금지).
  4. wave — layout/__tests__/ 동일 디렉토리지만 파일별 직렬화라 무충돌, 자기 파일만 stage([[parallel-dispatch-precommit-hook-race]]).
- **BLOCKER: 없음.**

ceo(제품 가치)는 ADR 확정 결정 3(FR-UX-06 신설)·4(개편 전체)에서 이미 결정 — 재리뷰 불요. devex(API)는 REST 변경 0이라 해당 없음.

### PR 단위 리뷰 (bts-codereview, 2026-07-20)

- **security-engineer: PASS.** 인증 로직 diff-0 — 로그아웃 byte-identical(AccountMenu↔삭제된 Header)·`isSystemAdmin===true` 게이팅 보존·PII/토큰 누출 0·localStorage boolean만(§1.18 준수)·공개공유 크롬 억제. PRE_EXISTING(PR11 무관): `/admin/workflow-schemes`가 requireSystemAdmin 미적용(router.ts diff-0, 백엔드 @PreAuthorize authoritative) → 별건.
- **code-reviewer: CONCERNS (BLOCKER 0).** T7 원자 스왑 완전·관리 6링크 정합·--sidebar 토큰 정합·navigation-contract non-vacuous·zustand 정확 확인. 권장 수정 3:
  - **C1 AccountMenu 무테스트** (양 리뷰어 공통) — Header.test 439줄 삭제로 계정 로직(로그아웃 성공/500 navigate·avatar cacheBust·상태/OOO 배지 FR-PR-02/03) 유닛 커버 소실. 동작 byte-identical·e2e(start-page) 로그아웃 커버라 BLOCKER 아니나 안전망 축소.
  - **C2 접힘=잘린 텍스트** — Sidebar 아이콘 미렌더(실측 확증), JSDoc은 "아이콘 레일"로 서술=불일치. 접힘 시 "이"/"워..." UX. 기능 정상(접근가능 이름 보존).
  - **C3 랜드마크 회귀** — `<main>`이 TopBar/Sidebar까지 포괄 → banner role 소실·main 과포괄. 테스트 의존 0(a11y 품질).
  - SUGGESTION: `navLabels.starred` 죽은 상수(소비처 0 실측).
- **/review (gstack): 통합.** 스코프 CLEAN(크립 0)·Header dangling import 0. 백엔드 지향 army/codex(SQL·migration·race·LLM)는 순수 프론트라 N/A → 두 리뷰로 대체. 신규 구조 이슈 0.

**종합: BLOCKER 0.** C1(커버리지)·C2(아이콘/UX)·C3(a11y)·starred(데드) = 완제품 품질 개선 4건.

### 게이트2 concerns 수정 (Maxi "C1·C2·starred 수정 후 머지", C3는 PR12 이연)

- **C1** `AccountMenu.test.tsx` 신설(11 케이스) — 라벨 폴백·avatar cacheBust·상태/부재중 배지·Status/Ooo 모달·로그아웃 성공/500 navigate. 커버리지 복구(26f5c5f5f).
- **C2** Sidebar lucide 아이콘 — 이슈`CircleDot`·대시보드`LayoutDashboard`·캘린더`Calendar` + 관리 6종. **접힘=아이콘+`sr-only` 텍스트**(잘린 텍스트 해소, 접근가능 이름 보존). JSDoc 정정. ★부수: zustand 싱글턴 테스트 격리 누출 발견→`beforeEach` 리셋(01fdcd7de).
- **starred** `navLabels.starred` 죽은 상수 제거(소비처 0 실측, c126bed78).
- **C3**(랜드마크 banner) → PR12 이연(main을 ShellLayout으로 이관, 리뷰어 제안).
- 검증: 전량 vitest **7391 passed**(+12) · typecheck 0 · eslint 0 · navigation-contract 5/5 · 계약-크리티컬 e2e 33 passed(아이콘+sr-only 후 aria-label 계약 무회귀).
