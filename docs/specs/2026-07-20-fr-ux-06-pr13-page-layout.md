# FR-UX-06 Phase 3 PR13 — PageLayout/PageHeader/Breadcrumb + /settings·/admin 인덱스 — 스펙

> slug: fr-ux-06-pr13-page-layout · type: ui · agent: frontend-engineer · 2026-07-20
> 순수 프론트(apps/web). FR 총수 불변 129 (FR-UX-06 완료 마킹은 후속 소비 화면 PR 몫).

## 배경

FR-UX-06 Jira 재개편 Phase 3(Shell). PR11(사이드바)·PR12(프로젝트 트리) 위에 **페이지 레벨 공통 레이아웃**을 올린다. 현재 `/settings`·`/admin` 인덱스 라우트가 없어 TopBar 설정아이콘이 `/settings/account-links`로 임시 랜딩 중이고(TopBar.tsx:87 주석에 PR13 예고), 다수 설정/관리 페이지가 헤더 관용구(`mx-auto max-w-* px-4 py-8` + `<h1>` + `<p>`)를 복붙한다. PR12에서 PRE_EXISTING로 남긴 중첩 `<main>` 3파일(WCAG 1.3.1 위반)과, 실측 중 발견한 `/admin/workflow-schemes` 권한 갭도 함께 봉합한다.

## 사용자 시나리오 (Given-When-Then)

- **S1 (설정 허브)**: Given 인증 사용자가 상단바 설정 아이콘 클릭, When `/settings`로 이동, Then 개인 설정 하위 항목(프로필·환경설정·키맵·캘린더·Slack·MFA·PAT·알림·세션·비밀번호·계정 연결)이 카드 그리드로 보이고 각 카드 클릭 시 해당 하위 라우트로 이동한다.
- **S2 (관리 허브)**: Given SYSTEM_ADMIN 사용자가 사이드바 관리 메뉴 또는 `/admin` 진입, When `/admin` 로드, Then 관리 항목(워크플로우 스킴·감사 로그·전역 권한·알림 정책·웹훅·Slack·사용자 추가)이 카드로 보인다. Given 비-admin이 `/admin` URL 직접 도달, Then `/dashboard`로 리다이렉트된다.
- **S3 (페이지 헤더 일관화)**: Given 신규 인덱스 페이지, When 렌더, Then PageHeader가 제목(`<h1>`)·설명·(선택)탐색 경로(Breadcrumb)·(선택)우측 액션을 일관 표기하고, PageLayout이 컨테이너 폭/여백을 통일한다.
- **S4 (탐색 경로)**: Given Breadcrumb items를 넘긴 페이지, When 렌더, Then `<nav aria-label="탐색 경로">` 안에 경로가 순서대로 표기되고 마지막 항목은 현재 페이지(`aria-current="page"`, 링크 아님), 앞 항목들은 링크다.
- **S5 (랜드마크 정정)**: Given issues.$key·admin.workflow-schemes·admin.workflow-schemes.$schemeKey 페이지, When 렌더, Then 문서 전체에 `<main>`이 정확히 1개(ShellLayout 소유)이며 페이지 자체는 `<section>`/`<div>`로 콘텐츠를 감싼다.
- **S6 (권한 갭 봉합)**: Given 비-admin 인증 사용자가 `/admin/workflow-schemes`·`/new`·`/$schemeKey` URL 직접 도달, When beforeLoad 실행, Then `/dashboard`로 리다이렉트된다(현재는 통과하는 갭).

## 기능 요구사항 (FR — 로컬 작업 항목, FR-ID 신설 아님)

**컴포넌트 3종 (`apps/web/src/components/layout/`)**
- **PL-1 PageLayout** — 페이지 콘텐츠 컨테이너. `<div>` 렌더(**`<main>` 렌더 금지** — ShellLayout이 문서 main 소유). props: `children`, `maxWidth?`('2xl'|'4xl'|'7xl', 기본 '4xl'), `className?`. `mx-auto max-w-{maxWidth} px-4 py-8` 관용구 캡슐화.
- **PL-2 PageHeader** — 페이지 헤더. props: `title: string`, `description?: string`, `breadcrumbs?: BreadcrumbItem[]`, `actions?: ReactNode`. 렌더: (breadcrumbs 있으면) Breadcrumb → `<h1 text-xl font-semibold>` → (description 있으면) `<p text-muted-foreground>` → (actions 있으면) 우측 정렬 슬롯. 제목 h1은 페이지당 1개.
- **PL-3 Breadcrumb** — 탐색 경로. props: `items: BreadcrumbItem[]` (`{ label: string; to?: string; params?: Record<string,string> }`). 렌더: `<nav aria-label={navLabels.breadcrumb}><ol>...`. 마지막 항목 `aria-current="page"`(비링크), 앞 항목은 `<Link>`. 구분자는 장식(aria-hidden). **per-page props만** — staticData/route-id 결합 금지([[tanstack-pathless-layout-router-test-blind]]·PR9 학습).

**신규 인덱스 라우트 2종**
- **PL-4 /settings 인덱스** — `settingsIndexRoute` path `/settings`, beforeLoad `requireAuth`. `routes/settings.index.tsx`. PageLayout + PageHeader(title '설정') + Card 그리드(개인 설정 11 하위 링크). AccountMenu/기존 하위 라우트 목록을 단일 출처로 참조.
- **PL-5 /admin 인덱스** — `adminIndexRoute` path `/admin`, beforeLoad = 다른 admin 라우트와 동일 4-가드(`composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled)`). `routes/admin.index.tsx`. PageLayout + PageHeader(title '관리') + Card 그리드(관리 하위 링크). **SYSTEM_ADMIN 게이팅**.

**TopBar 재랜딩**
- **PL-6** — TopBar.tsx 설정아이콘 `to`를 `/settings/account-links` → `/settings`로 변경. 임시 주석(87) 제거.

**landmark 강등 3파일**
- **PL-7** — issues.$key.tsx:585 `<main>` → `<section aria-label>`/`<div>`(좌 본문 컬럼). admin.workflow-schemes.tsx:46 → `<section>`/`<div>`(중앙). admin.workflow-schemes.$schemeKey.tsx:112 → `<section>`/`<div>`(중앙 매핑테이블). **이 3파일은 커스텀 다중컬럼 레이아웃이라 PageLayout 강제 래핑 안 함** — 랜드마크 태그 정정만(강제 시 grid 컬럼/사이드바 구조 훼손). PageHeader는 자연스럽게 맞는 경우만.

**authz hot-fix (security-engineer)**
- **PL-8** — router.ts:178·187·196 `/admin/workflow-schemes`·`/new`·`/$schemeKey` beforeLoad를 `requireAuthAndPasswordChanged` → `composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled)`(다른 admin 라우트 패턴 일치)로 교체. **음성 가드 회귀테스트 필수** — 비-admin → `/dashboard` 리다이렉트 검증, 가드 제거 시 fail하는지 mutation으로 확인([[verify-logic-vs-verify-guard]]·[[negative-guard-needs-body-discriminator]]).

**nav 라벨**
- **PL-9** — `navLabels`에 `breadcrumb: '탐색 경로'` 추가. 기존 🔒 계약 문자열과 substring 충돌 없음(검증필: '메인 메뉴'·'관리 메뉴'·'프로젝트'·'프로젝트 뷰 전환'·'검색' 어느 것의 부분문자열도 아님).

## 비기능 요구사항 (NFR)

- **접근성** — 문서당 `<main>` 1개(WCAG 1.3.1). Breadcrumb은 ARIA authoring practices(`nav[aria-label]` + `ol/li` + `aria-current="page"`). h1은 페이지당 1개.
- **디자인** — ADS v2 토큰만 사용(하드코딩 색 금지). Card 프리미티브(`components/ui/card.tsx`) 재사용. DESIGN.md 컨벤션 준수.
- **계약 보존** — [[frontend-nav-aria-label-e2e-contract]]: 뷰전환 Tabs 금지(Breadcrumb/헤더는 nav+Link). 검색은 Header 단일 유지. 기존 aria-label 4종 문자열 불변.
- **회귀 격리** — router.test·routeGuard.test 기존 케이스 무수정 통과가 목표(신규 라우트 2개 추가분만 증가). 라우트 삭제 0.

## 엣지 케이스

- **E1** — 비-admin이 `/admin` 또는 `/admin/workflow-schemes` 직접 URL → 리다이렉트(가드). 사이드바 링크는 애초에 비노출.
- **E2** — Breadcrumb items 1개(현재 페이지만) → 링크 없이 현재 페이지만 표기. items 빈 배열 → nav 미렌더(방어).
- **E3** — PageLayout이 실수로 `<main>` 렌더하면 중첩 main 재발 → **구조 회귀 가드**(PageLayout 유닛테스트가 `container.querySelector('main')` null 단언).
- **E4** — 신규 라우트 path `/settings`·`/admin`가 기존 `/settings/*`·`/admin/*`와 매칭 충돌? TanStack 정확매칭이라 `/settings`는 인덱스만, `/settings/profile`은 기존 라우트 매칭(형제, 충돌 없음). 라우트 등록 후 실측 확인.
- **E5** — 설정/관리 카드 목록의 단일 출처 — 하위 링크가 향후 추가/삭제될 때 drift 방지. 링크 배열을 상수로 추출(nav-labels 인접 또는 라우트 상수 참조).

## 제약 조건

- 백엔드 0변경, DB 0변경, FR 총수 불변 129 → `verify-master-plan.sh`/fr-index 동기화 대상 아님.
- CI에 e2e 잡 없음([[frontend-ci-10min-timeout-nonrequired]]) → 로컬 e2e 필수. `pnpm exec playwright test <file>` positional 삼킴 → 바이너리 직접호출([[e2e-playwright-filter-arg-drop]]).
- Playwright `getByRole`은 substring 매칭 → 신규 라벨 조회 시 `exact: true`([[playwright-getbyrole-exact-strict-mode]]).

## 측정 가능한 완료 기준

- [ ] PageLayout/PageHeader/Breadcrumb 3종 신설 + 유닛테스트(구조 회귀 가드 포함: PageLayout `<main>` 미렌더).
- [ ] `/settings`·`/admin` 인덱스 라우트 등록 + 카드 그리드 렌더 + 가드(settings=requireAuth, admin=SYSTEM_ADMIN) + 유닛테스트.
- [ ] TopBar 설정아이콘 `/settings` 재랜딩 + 관련 테스트 갱신.
- [ ] 3파일 landmark 강등 + 문서당 main 1개 구조 검증(유닛 or 구조테스트).
- [ ] `/admin/workflow-schemes` 3라우트 SYSTEM_ADMIN 가드 + 음성 회귀테스트(mutation 검증).
- [ ] navLabels.breadcrumb 추가.
- [ ] E2E: 설정 허브 로드·카드 네비, 관리 허브(admin)·비-admin 리다이렉트, TopBar→/settings, 랜드마크 단일 main.
- [ ] typecheck 0 · lint 0 · 유닛 전체 green · 관련 e2e 로컬 green.

## Brainstorming Check

자체 sanity 점검(gap 발견 시 반영 완료).
- ✅ 중첩 main 재발 방지 = PageLayout 구조 가드로 봉인(E3).
- ✅ 라우트 매칭 충돌 = 정확매칭 근거 + 등록 후 실측(E4).
- ✅ authz 갭 = 음성 mutation 가드로 vacuous 방지(PL-8).
- ✅ 카드 목록 drift = 단일 출처 상수(E5).
- ✅ aria-label 계약 = breadcrumb 신규 라벨 substring 비충돌 검증(PL-9).
