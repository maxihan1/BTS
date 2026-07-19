# FR-UX-06 Phase 3 PR11 — 전역 사이드바 신설 + Header 축소 — 스펙

> slug: fr-ux-06-pr11-sidebar | type: ui | agent: frontend-engineer
> BC: personalization(논리)/apps/web(물리) | 정본: 디자인 스펙 §3.1 · ADR D2/D3/D4/D7
> 작성: 2026-07-20

## 요약

PR10에서 도입한 pathless `_shell`(현재 passthrough)의 컴포넌트 `ShellLayout.tsx`를 **Jira Cloud 2025 통합 사이드바 + 상단바 + 콘텐츠 레이아웃**으로 확장한다. 크롬(현재 `RootLayout`이 렌더하는 `<Header/>`)을 `_shell`로 이관하고 `Header.tsx`를 상단바로 축소한다. **프로젝트 트리(디자인 스펙 §3.1 섹션 2)는 PR12로 이연** — 본 PR은 `GET /api/v1/projects`를 소비하지 않는다.

### Maxi 확정 결정 (2026-07-20, bts-spec 게이트)

| # | 결정 | 근거 |
|---|---|---|
| S1 | **반응형 = 데스크탑 우선(≥1024px)** + 접기 토글(전 폭 동작). <900px sheet 오버레이는 후속 PR | 사내 데스크탑 도구·PR11 추정(~450 LOC)·sheet primitive 부재 |
| S2 | **접기 상태 = localStorage 지속** | 프론트 전용·백엔드 변경 0·기기별 기억으로 Jira parity 충분 |
| S3 | **백킹 없는 항목(내 작업·최근·필터 인덱스) 미포함** — 실 라우트 있는 것만 | 죽은 링크 금지(완제품 원칙)·해당 항목은 각 기능 FR에서 추가 |

## 사용자 시나리오 (Given-When-Then)

**S-1. 인증 사용자가 앱에 진입한다.**
- Given 로그인한 사용자가 임의의 앱 라우트(`/issues` 등)에 있고
- When 페이지가 렌더되면
- Then 좌측에 264px 고정 사이드바(내비 항목 + 관리)와 상단 48px 바(로고·검색·만들기·알림·설정·아바타)가 보이고, 콘텐츠가 우측 영역에 렌더된다.

**S-2. 사이드바를 접는다.**
- Given 사이드바가 펼쳐져 있고
- When 상단바의 사이드바 토글(또는 접기 단축키)을 누르면
- Then 사이드바가 접히고(아이콘만 또는 폭 0), 상태가 localStorage에 저장돼 새로고침·재접속 후에도 유지된다.

**S-3. 시스템 관리자가 관리 메뉴를 본다.**
- Given `isSystemAdmin === true`인 사용자가
- When 사이드바를 보면
- Then `관리 메뉴` nav가 **기본 펼침** 상태로 6개 링크(워크플로우 스킴·감사 로그·전역 권한·알림 정책·Webhook·Slack 연결)를 노출한다. 비관리자에겐 관리 nav가 아예 렌더되지 않는다.

**S-4. 비인증 사용자가 공개 공유 대시보드를 본다.**
- Given 로그인하지 않은 사용자가 `dashboards.shared.$token` 공유 링크로 진입하고
- When 페이지가 렌더되면
- Then 사이드바·상단바(크롬)가 렌더되지 않고 콘텐츠만 보인다(공개 공유 뷰는 앱 셸 억제).

**S-5. 로그인 화면.**
- Given 비인증 사용자가 `/login`에 있고
- When 페이지가 렌더되면
- Then 크롬 없이 로그인 폼만 보인다(`loginRoute`는 `_shell` 밖 — PR10에서 확정, 회귀 없음).

## 기능 요구사항 (FR)

- **FR1. ShellLayout 크롬 렌더.** `ShellLayout`(`_shell` 컴포넌트)이 `isAuthenticated === true`일 때 상단바 + 사이드바 + 콘텐츠(`<Outlet/>`)를 렌더한다. `false`일 때는 bare `<Outlet/>`(크롬 억제, S-4).
- **FR2. 상단바(TopBar).** 좌→우: 사이드바 토글 · 로고(Atlas, 클릭 → 홈 `/dashboards`) · 검색(Header 잔류, `aria-label="검색"` 단일) · 만들기 버튼(→ `/issues/new`, 기존 이슈 생성 진입점 재사용·확인) · 알림(`InboxBell` 잔류) · 도움말(→ 기존 `ShortcutsHelpDialog` 오픈, FR-UX-05 재사용) · 설정(gear) · 계정 아바타 드롭다운(잔류, 로그아웃 포함). 높이 48px.
  - 🔴 **설정 기어 대상 주의(Phase B 갭).** `/settings`·`/admin` **인덱스 라우트가 없다**(서브라우트만 존재 — settings.account-links/calendar/keymap). 전용 인덱스 페이지는 PR13(PageLayout) 몫. PR11 설정 기어는 **죽은 `/settings`로 보내지 말 것** → 기존 settings 서브라우트 랜딩(예 `/settings/account-links`)으로 이동하거나 계정 드롭다운의 설정 항목으로 흡수. frontend-engineer가 실측 진입점 결정.
- **FR3. 사이드바 메인 nav.** `<nav aria-label="메인 메뉴">`에 실 라우트 항목만: 이슈(`/issues`) · 대시보드(`/dashboards`) · 캘린더(`/calendar`) · 즐겨찾기. 활성 라우트 시각 강조. 264px. **즐겨찾기 = 기존 `FavoritesMenu`(드롭다운, FR-UX-02) 재사용** — 사이드바 내 트리거로 재배치, 재빌드 금지(타입별 그룹·SPA Link 로직 보존).
- **FR4. 사이드바 관리 nav.** `isSystemAdmin === true`일 때만 `<nav aria-label="관리 메뉴">`를 **기본 펼침**으로 렌더. 링크 6종(`ADMIN_LINKS` 정본 그대로: 워크플로우 스킴·감사 로그·전역 권한·알림 정책·Webhook·Slack 연결). 게이팅 술어 `user?.isSystemAdmin === true`.
- **FR5. 사이드바 접기.** 상단바 토글로 접기/펼치기. 상태를 localStorage(`bts.sidebar.collapsed` 등)에 저장, 부트 시 복원. 토글 버튼이 키보드 포커스 가능 = WCAG 키보드 접근 충족. **전용 접기 단축키는 선택적**(넣는다면 하드코딩 단순키) — **FR-PF-03 keymap 커스터마이즈 시스템에 통합하지 말 것**(스코프 크립·keymap 화이트리스트 변경 회귀). 토글 버튼 `aria-label`은 상태별(`사이드바 접기`/`사이드바 펼치기`).
- **FR6. Header 축소 + RootLayout 이관.** `Header.tsx`에서 `메인 메뉴`·`관리 메뉴` nav를 제거(사이드바로 이관). 남는 상단바 요소는 TopBar로 재구성. `RootLayout`(`__root.tsx`)은 `<Header/>` 직접 렌더를 중단(크롬은 `_shell`이 소유). **★ `RootLayout`의 전역 오버레이·훅은 그대로 유지**(Phase B 갭): `CommandPalette`·`ShortcutsHelpDialog`·`useNotificationStream`·`useKeyboardShortcuts(isAuthenticated)`는 크롬이 아니라 앱 전역 관심사라 RootLayout에 잔류(이동 시 login 배제·테스트 폭발 반경 → 최소 변경 원칙). 단 도움말 트리거(FR2)가 `ShortcutsHelpDialog`를 여는 배선은 유지.
- **FR7. i18n nav-labels 신설.** `i18n/nav-labels.ts`에 `navLabels` 상수(디자인 스펙 §11). 🔒 e2e 계약 문자열(`메인 메뉴`·`관리 메뉴`·`프로젝트 뷰 전환`·`검색`)은 글자 변경 금지.
- **FR8. `--sidebar-*` 토큰 실값화(ADR D7).** `index.css`의 `--sidebar-*` 8종(현재 무채색 oklch, 소비자 0)을 사이드바가 실제 소비하는 ADS 값(라이트/다크 both)으로 덮어쓴다. 사이드바 배경은 디자인 스펙 §3.1대로 sunken 계열.

## 비기능 요구사항 (NFR)

- **NFR1. E2E 계약 무위반.** `getByRole('navigation')` 18건, `role="dialog"` 147건, `<h1>` 34건이 무수정 통과. aria-label 4종·검색 단일·관리 기본펼침·h1 사이드바 금지.
- **NFR2. 접근성(WCAG 2.1 AA).** 대화형 요소 `focus-visible` 링. Tab 순서 = 시각 순서. 아이콘 전용 버튼 `aria-label`. `prefers-reduced-motion` 존중. **body 가로 스크롤 금지 — 전 폭.** <1024px에서도 사이드바 264px 유지(수동 접기로 대응)·auto-sheet 없음(S1); 콘텐츠·테이블·보드는 자기 컨테이너가 `overflow-x:auto`를 소유해 body가 아닌 컨테이너가 스크롤(Phase B 갭 — 좁은 폭에서 사이드바가 콘텐츠를 밀어 body h-scroll 나지 않게).
- **NFR3. 라이트/다크 both.** 다크 모드 완전 동작 중 → 모든 신규 토큰·컴포넌트 both 값.
- **NFR4. 시각 회귀 최소.** 인증 라우트의 콘텐츠 영역 렌더는 사이드바 추가 외 변화 없음(PR10의 "픽셀 동일" 위에 사이드바만 얹음).

## 컴포넌트 인터페이스 (프론트)

```
components/layout/
  ShellLayout.tsx      # (기존, 확장) _shell 컴포넌트 — isAuthenticated 게이팅 + TopBar+Sidebar+content
  TopBar.tsx           # (신규) 상단바 48px
  Sidebar.tsx          # (신규) 사이드바 264px, 메인/관리 nav, 접기
  __tests__/navigation-contract.test.tsx  # (신규, 先작성) aria-label 4종 + 관리 게이팅 — 인증 셸 합성을 렌더(Header/Sidebar 무관)해 이관 전 green→이관 후 green, 라벨 깨지면 red
components/Header.tsx   # (축소) 메인/관리 nav 제거
routes/__root.tsx       # (수정) <Header/> 렌더 중단
i18n/nav-labels.ts       # (신규) navLabels
hooks/use-sidebar-collapsed.ts  # (신규) localStorage 접기 상태
index.css                # (수정) --sidebar-* 8종 실값
```

추정 +4~6파일 ≈450 LOC(허브 plan 부합).

## 데이터/상태 변경

- **localStorage 키** `bts.sidebar.collapsed`(boolean). 백엔드 변경 0.
- **`--sidebar-*` 8종** 값 교체(신규 토큰 아님, 기존 무채색 → ADS).
- REST API 변경 0. `GET /api/v1/projects` 미소비(PR12).

## 엣지 케이스

- **E1. 비인증 + `_shell` 내부 라우트(`dashboards.shared`).** ShellLayout이 `isAuthenticated=false` → bare Outlet. 사이드바 누출 금지(S-4).
- **E2. `이슈` nav 링크 추가 시 strict-mode 충돌.** 현 `메인 메뉴`엔 이슈 없음(대시보드·캘린더만). 이슈 추가 시 `이슈` 텍스트가 기존 e2e와 충돌 안 하는지 확인(memory: 대시보드 4·타임라인 3 확인 대상, 이슈는 확인 필요).
- **E3. 관리 nav 접힘 회귀.** 기본 펼침을 어기면 `webhook.spec.ts:73`·`audit-logs.spec.ts:50`·`notification-policies.spec.ts:83` 클릭 실패. 접기 토글이 관리 nav를 숨기면 안 됨(사이드바 전체 접힘과 관리 아코디언은 별개 — 사이드바 접힘 시 e2e는 인증 후 펼친 상태를 기대).
- **E4. localStorage 부재/파싱 실패.** 기본값 = 펼침(fail-safe). JSON 파싱 예외 시 펼침.
- **E5. `프로젝트 뷰 전환` nav 미접촉 확인.** board/backlog의 `프로젝트 뷰 전환`은 PR11 미접촉(PR12 몫) → 그대로 존재해야 계약 유지.
- **E6. 검색 이중화 금지.** 사이드바에 검색 항목 절대 추가 금지(Header 단일).
- **E7. `<main>` 랜드마크 중복.** RootLayout `<main>`과 ShellLayout content가 겹치지 않게(main 하나만).

## 제약 조건 (🔴 위반 시 E2E 즉사)

1. aria-label 4종 글자 보존 — `메인 메뉴`·`관리 메뉴`·`프로젝트 뷰 전환`·workflow-scheme `sidebar.nav`.
2. `검색` aria-label은 상단바 단일.
3. 관리 메뉴 기본 펼침.
4. 뷰 전환을 Radix Tabs로 바꾸지 말 것(PR11은 board/backlog 미접촉이라 자동 준수).
5. 사이드바에 `<h1>` 금지.
6. 관리 nav 6링크 전부 보존.
7. 개별 `@radix-ui/react-*` 추가 설치 금지(통합 `radix-ui`).

## 측정 가능한 완료 기준

- [ ] `navigation-contract.test.tsx` 先작성 → 현 Header 기준 green → 이관 후에도 green.
- [ ] `pnpm typecheck` 0 · `pnpm lint` 0.
- [ ] 유닛 전량 green(신규 사이드바/접기 테스트 포함).
- [ ] Playwright 전수 green(특히 webhook·audit-logs·notification-policies·calendar·dashboard·board·backlog·already-authed·dashboards.shared).
- [ ] 라이트/다크 both 시각 확인.
- [ ] 접기 상태 localStorage 지속 확인(새로고침 후 유지).
- [ ] FR 총수 불변 129(마킹은 소비 화면 PR 몫).

## Out of scope (PR11)

- 프로젝트 트리·프로젝트 전환기 → **PR12**(`GET /api/v1/projects` 소비 + ProjectNavTabs).
- `PageLayout`/`PageHeader`/`Breadcrumb`·`/settings`·`/admin` 인덱스 → **PR13**.
- <900px sheet 오버레이 반응형 → 후속(S1).
- 내 작업·최근·필터 인덱스 → 각 기능 FR(S3).
- DESIGN.md 패치 → PR3 몫(이미 완료).

## Brainstorming Check

✅ 통과 (1회 iteration). 적대적 gap 헌팅으로 6개 갭 발견 → 전부 자체 해소(Maxi 결정 불필요, 실측 기반).

| # | 갭 | 해소 |
|---|---|---|
| G1 | RootLayout의 CommandPalette·ShortcutsHelpDialog·useNotificationStream·useKeyboardShortcuts 거취 미명시 | FR6 — RootLayout 잔류(크롬 아닌 전역 관심사, 최소 변경) |
| G2 | 설정 기어가 `/settings`로 가면 404(인덱스 라우트 부재 실측) | FR2 — 기존 서브라우트 랜딩/계정 드롭다운 흡수, 전용 인덱스는 PR13 |
| G3 | 디자인 §3.1 상단바 도움말을 스펙이 누락 | FR2 — 도움말 버튼 → 기존 ShortcutsHelpDialog(FR-UX-05) |
| G4 | navigation-contract 테스트가 Header→Sidebar 이관에 살아남는지 모호 | 컴포넌트 — 인증 셸 합성 렌더(Header/Sidebar 무관)로 마이그레이션 무관성 확보 |
| G5 | 접기 단축키가 keymap 시스템(FR-PF-03) 침범 위험 | FR5 — 토글 버튼이 WCAG 충족, 전용 단축키 선택·keymap 미통합 |
| G6 | 좁은 폭(<1024px) 사이드바 거동 미정(S1 데스크탑 우선) | NFR2 — 사이드바 유지·auto-sheet 없음·컨테이너 자체 overflow(body h-scroll 금지) |

부수 확정: 로고→홈(/dashboards)·만들기→/issues/new·즐겨찾기=FavoritesMenu 드롭다운 재배치.
