# FR-UX-06 Phase 3 PR13 — PageLayout/PageHeader/Breadcrumb + /settings·/admin 인덱스

> slug: fr-ux-06-pr13-page-layout
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-20

## Brief

FR-UX-06 Jira 재개편 Phase 3(Shell)의 PR13. 전역 사이드바(PR11·PR12 완료) 위에 페이지 레벨 공통 레이아웃을 신설한다.

**본 작업**
- `PageLayout` / `PageHeader` / `Breadcrumb` 공통 레이아웃 컴포넌트 신설 (`components/layout/`).
- `/settings` · `/admin` 인덱스 페이지 추가 (현재 라우트 부재로 죽은링크였던 그룹 헤더에 실제 인덱스 제공).

**PR12 후속 2건 (Maxi 지시로 본 PR13에 포함)**
- ① 자체 `<main>`을 가진 3페이지의 landmark 강등(`<section>`/`<div>`): `issues.$key.tsx`, `admin.workflow-schemes.tsx`, `admin.workflow-schemes.$schemeKey.tsx`. 문서당 `<main>` 1개(WCAG 1.3.1). PR12에서 PRE_EXISTING로 분류된 중첩 main 해소.
- ② 프로젝트 설정 라우트별 authz 게이팅 확인. PR12에서 설정 그룹 11링크를 전 인증자에게 노출(GAP-1)한 정책이 관리 nav 정책과 어긋나는지 점검.

**classify 결과** — type=ui, agent=frontend-engineer, primary_bc=issue-tracking(물리, 실제는 personalization 논리). 순수 프론트(apps/web) 예상.

**착수 전 필독 (계약)**
- [[frontend-nav-aria-label-e2e-contract]] — aria-label 4종 e2e 계약·뷰전환 Tabs 금지·검색 Header 단일.
- [[playwright-getbyrole-exact-strict-mode]] — 신규 라벨 substring 함정, exact 필수.
- CI에 e2e 잡 없음 → UI PR 로컬 e2e 필수 ([[frontend-ci-10min-timeout-nonrequired]]).

## 도메인 정리

- **BC**: personalization (논리) / identity-access (물리). 실질은 cross-cutting 프론트 IA 작업 — 순수 apps/web.
- **새 도메인 용어/엔티티**: 없음 (프레젠테이션 레이어. PageLayout/PageHeader/Breadcrumb는 UI 컴포넌트 계약이지 도메인 개념 아님).
- **기존 결정 충돌**: 없음. FR-UX-06 ADR D1~D8(Jira 재개편) 노선 계승.
- **관련 ADR**: `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md` (D1~D8). 신규 ADR 후보 = Breadcrumb 데이터 소스 결정(아래 D2).

### 실측 결과 (Explore 조사, worktree 기준)

1. **layout 컴포넌트 5종** — ShellLayout(auth/unauth 양분기 모두 `<main>` 소유, `ShellLayout.tsx:64`)·TopBar(`<header>` banner, 설정아이콘→`/settings/account-links` 임시랜딩 `TopBar.tsx:87~88`)·Sidebar(`<aside>` complementary + nav 3종)·AccountMenu·ProjectTree(`<nav>`). **PageLayout/PageHeader/Breadcrumb 전무**.
2. **`/settings`·`/admin` 인덱스 라우트 부재** — 하위 라우트만 존재(개인 settings 11 + admin 11). `routes/settings.tsx`·`admin.tsx`·`*.index.tsx` 없음.
3. **ProjectTree 18링크 전부 실재 라우트 매칭(죽은링크 0)** — 단 이건 **프로젝트 스코프**(`/projects/$projectKey/settings/...`). 개인 `/settings/*`·`/admin/*`와 별개.
4. **중첩 main 3파일** — issues.$key.tsx:585(좌 본문컬럼), admin.workflow-schemes.tsx:46(중앙), admin.workflow-schemes.$schemeKey.tsx:112(중앙 매핑테이블). 전부 ShellLayout main 안 중첩 → 문서당 main 2개(WCAG 1.3.1 위반, PR12서 PRE_EXISTING 판정).
5. **authz 실측** — 가드 정의 `apps/web/src/auth/routeGuard.ts`. 개인 settings=인증만(정상). admin 8개=`requireSystemAdmin` 포함(정상). **⚠️ `/admin/workflow-schemes`·`/new`·`/$schemeKey` 3개만 `requireAuthAndPasswordChanged`만 걸려 SYSTEM_ADMIN 미요구 — 라우트 가드 권한 갭**(router.ts:178·187·196). 사이드바 admin nav는 isSystemAdmin 게이팅하나 라우트 직접도달은 일반 인증자도 통과.
6. **Breadcrumb 데이터 소스 부재** — staticData는 `{ requireAuth }` 전용(`StaticDataRouteOption`, router.ts:798~806). breadcrumb/title 신호 없음. 신규 도입 필요. 현재 페이지 헤더는 `mx-auto max-w-* px-4 py-8` + `<h1 text-xl font-semibold> + <p text-muted-foreground>` 관용구가 다수 설정/관리 페이지에 복붙 반복(PageHeader 추출 후보).

### 후속 ②의 정정된 결론

- **원래 질문(프로젝트 설정 11링크 전 인증자 노출)** = PR12 GAP-1로 이미 결정된 항목(백엔드 fail-closed 신뢰). 프론트 추가 게이팅 불요 — 재확인 완료.
- **신규 발견 = `/admin/workflow-schemes` 3라우트 SYSTEM_ADMIN 미요구 갭**. 보안 민감 → security-engineer 검토 + Maxi 게이트 결정 필요(PR13 내 hot-fix vs 별도 보안 PR).

## 스펙

전체 스펙. [docs/specs/2026-07-20-fr-ux-06-pr13-page-layout.md](../specs/2026-07-20-fr-ux-06-pr13-page-layout.md)

핵심 요약 (9 작업 항목 PL-1~9)
- **컴포넌트 3종**: PageLayout(`<div>` 컨테이너, main 미렌더)·PageHeader(title/description/breadcrumbs/actions)·Breadcrumb(`nav[aria-label='탐색 경로']`, per-page props).
- **인덱스 2종**: /settings(requireAuth)·/admin(SYSTEM_ADMIN) 카드 허브. TopBar 설정아이콘 /settings 재랜딩.
- **landmark 강등 3파일**: issues.$key:585·admin.workflow-schemes:46·.$schemeKey:112 `<main>`→`<section>`/`<div>`(문서당 main 1개). 커스텀 레이아웃이라 PageLayout 강제 안 함.
- **authz hot-fix**: /admin/workflow-schemes 3라우트 SYSTEM_ADMIN 가드(security-engineer, 음성 mutation 테스트).
- **nav 라벨**: navLabels.breadcrumb 추가.
- FR 총수 불변 129, 백엔드/DB 0변경.

## Brainstorming Check

✅ 통과 (결정 확정 후 자체 sanity, gap 5건 모두 스펙에 반영: 중첩 main 재발 가드 E3·라우트 충돌 E4·authz vacuous 방지 PL-8·카드 drift E5·aria substring 비충돌 PL-9).

## Plan

> 전역 규칙. 각 task RED→GREEN→REFACTOR. 컴포넌트/라우트 테스트 co-located(layout은 `__tests__/`). ADS 토큰만·하드코딩 색 금지. **router.ts는 T4·T5·T8이 공유 → 파일겹침 자동 직렬화**(같은 wave 금지). PR11/PR12 선례대로 controller가 각 task git diff 실물 검증.

### Task 1. Breadcrumb 컴포넌트 + navLabels.breadcrumb (PL-3·PL-9)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/nav-labels.ts`, `apps/web/src/i18n/nav-labels.test.ts`, `apps/web/src/components/layout/Breadcrumb.tsx`, `apps/web/src/components/layout/__tests__/Breadcrumb.test.tsx`]
- depends-on: []

**RED**:
- `nav-labels.test.ts`: `navLabels.breadcrumb === '탐색 경로'` + 계약 4종('메인 메뉴'·'관리 메뉴'·'프로젝트 뷰 전환'·'검색')·'프로젝트' 어느 것의 substring도 아님 단언.
- `Breadcrumb.test.tsx`: items 3개 → `nav[aria-label='탐색 경로']` 1개·`<ol>`·마지막 `aria-current="page"` 비링크·앞 2개 `<a href>`. items 1개 → 링크 0. items 빈배열 → nav 미렌더.
- 실패(예상): `Breadcrumb`·`navLabels.breadcrumb` 없음.

**GREEN**:
- `nav-labels.ts`에 `breadcrumb: '탐색 경로'` 추가(주석: 신규, 계약 substring 비충돌).
- `Breadcrumb.tsx`: `{ items: BreadcrumbItem[] }` → `<nav aria-label={navLabels.breadcrumb}><ol>` + `<Link to params>` / 마지막 `<span aria-current="page">` + 구분자 `aria-hidden`.

**REFACTOR**: `BreadcrumbItem` 타입 export, KDoc(ARIA authoring practices 근거).

**검증**: `node_modules/.bin/vitest run src/components/layout/__tests__/Breadcrumb.test.tsx src/i18n/nav-labels.test.ts`

### Task 2. PageLayout 컴포넌트 (PL-1)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/PageLayout.tsx`, `apps/web/src/components/layout/__tests__/PageLayout.test.tsx`]
- depends-on: []

**RED**:
- children 렌더·`maxWidth` 기본 '4xl'/'2xl'/'7xl' 클래스 매핑·`className` 병합.
- ★구조 회귀 가드: `container.querySelector('main')` **null**(PageLayout은 `<div>`만, ShellLayout이 문서 main 소유 — 중첩 main 재발 봉인 E3).
- 실패(예상): `PageLayout` 없음.

**GREEN**: `PageLayout.tsx` = `<div className={cn('mx-auto w-full px-4 py-8', maxWidthClass, className)}>{children}</div>`. main 태그 금지.

**REFACTOR**: maxWidth 매핑 상수 추출, props 타입 export.

**검증**: `node_modules/.bin/vitest run src/components/layout/__tests__/PageLayout.test.tsx`

### Task 3. PageHeader 컴포넌트 (PL-2)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/PageHeader.tsx`, `apps/web/src/components/layout/__tests__/PageHeader.test.tsx`]
- depends-on: [1]

**RED**:
- `title` → `<h1 text-xl font-semibold>` 정확 1개. `description` → `<p text-muted-foreground>`. `breadcrumbs` prop → Breadcrumb(nav) 렌더, 없으면 nav 0. `actions` → 우측 슬롯 렌더.
- 실패(예상): `PageHeader` 없음.

**GREEN**: `PageHeader.tsx` — breadcrumbs 있으면 `<Breadcrumb items>` → `<h1>` + description + actions(flex 우측).

**REFACTOR**: props 타입 export, KDoc(page당 h1 1개 계약).

**검증**: `node_modules/.bin/vitest run src/components/layout/__tests__/PageHeader.test.tsx`

### Task 4. /settings 인덱스 허브 라우트 (PL-4)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/settings.index.tsx`, `apps/web/src/routes/settings.index.test.tsx`, `apps/web/src/router.ts`]
- depends-on: [2, 3]

**RED**:
- `settings.index.test.tsx`: 페이지 컴포넌트 렌더 → PageHeader title '설정' 1개 + 개인설정 하위 링크 카드 N개(각 `Card` + `<Link to>`), 각 `to`가 실재 하위 라우트(profile·preferences·keymap·calendar·slack·mfa·pats·notifications·sessions·password·account-links).
- 실패(예상): 라우트/페이지 없음.

**GREEN**:
- `settings.index.tsx`: PageLayout + PageHeader('설정','개인 설정을 관리합니다') + Card 그리드. 링크 목록은 단일 출처 상수(E5).
- `router.ts`: `settingsIndexRoute`(path `/settings`, beforeLoad `requireAuthAndPasswordChanged`, staticData requireAuth:true) 등록 + routeTree 추가. **정확매칭 확인**(`/settings`≠`/settings/*`, E4).

**REFACTOR**: 카드 링크 배열 상수 파일 분리(재사용), 아이콘 lucide.

**검증**: `node_modules/.bin/vitest run src/routes/settings.index.test.tsx` + `node_modules/.bin/tsc -p tsconfig.app.json --noEmit`

### Task 5. /admin 인덱스 허브 라우트 (PL-5)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/admin.index.tsx`, `apps/web/src/routes/admin.index.test.tsx`, `apps/web/src/router.ts`]
- depends-on: [2, 3, 4]   # router.ts 겹침으로 T4 뒤 직렬

**RED**:
- `admin.index.test.tsx`: 페이지 렌더 → PageHeader title '관리' + 관리 하위 링크 카드(workflow-schemes·audit-logs·global-permissions·notification-policies·webhooks·slack·users/new).
- 실패(예상): 없음.

**GREEN**:
- `admin.index.tsx`: PageLayout + PageHeader('관리') + Card 그리드.
- `router.ts`: `adminIndexRoute`(path `/admin`, beforeLoad = `composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled)` — 다른 admin 라우트 동일) 등록 + routeTree.

**REFACTOR**: 관리 링크 상수 분리.

**검증**: `node_modules/.bin/vitest run src/routes/admin.index.test.tsx` + typecheck.

### Task 6. TopBar 설정아이콘 /settings 재랜딩 (PL-6)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/TopBar.tsx`, `apps/web/src/components/layout/__tests__/TopBar.test.tsx`]
- depends-on: [4]   # /settings 라우트 존재해야 typed link 통과

**RED**: TopBar 테스트 — 설정 링크 `to === '/settings'`(현재 `/settings/account-links`).

**GREEN**: TopBar.tsx 설정 `Link to` 변경 + 87행 임시 주석 제거.

**REFACTOR**: 없음(최소 변경).

**검증**: `node_modules/.bin/vitest run src/components/layout/__tests__/TopBar.test.tsx`

### Task 7. landmark 강등 3파일 (PL-7)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/admin.workflow-schemes.tsx`, `apps/web/src/routes/admin.workflow-schemes.$schemeKey.tsx`, (기존 관련 테스트 갱신)]
- depends-on: []

**RED**: 각 페이지 컴포넌트 렌더 시 `container.querySelector('main')` **null**(자체 main 제거, ShellLayout이 문서 main 소유). 기존 페이지 테스트에 구조 단언 추가 or 신규 구조 테스트.

**GREEN**: issues.$key.tsx:585 `<main>`→`<section aria-label="이슈 상세">`(좌 컬럼). admin.workflow-schemes.tsx:46·.$schemeKey.tsx:112 `<main>`→`<section>`/`<div>`. **커스텀 레이아웃 보존**(PageLayout 강제 안 함).

**REFACTOR**: 없음.

**검증**: `node_modules/.bin/vitest run src/routes/issues.$key.test.tsx` (+ workflow-schemes 관련) · typecheck.

### Task 8. authz hot-fix — /admin/workflow-schemes SYSTEM_ADMIN 가드 (PL-8)

**메타**.
- agent: `security-engineer`
- files: [`apps/web/src/router.ts`, `apps/web/src/router.admin-guards.test.tsx`]
- depends-on: [4, 5]   # router.ts 겹침으로 T4·T5 뒤 직렬

**RED (음성 가드 — mutation 검증 필수)**:
- `router.admin-guards.test.tsx`: 비-admin(`isSystemAdmin:false`) actor로 `adminWorkflowSchemesRoute`·`New`·`Detail`의 beforeLoad 실행 → `/dashboard` redirect throw. admin actor는 통과.
- ★기준선 확인: 가드 교체 **전** 이 테스트가 비-admin에서 **통과(=갭 실재)** 확인 → 교체 후 redirect. 가드 제거하면 테스트 fail(vacuous 아님, [[verify-logic-vs-verify-guard]]·[[negative-guard-needs-body-discriminator]]).

**GREEN**: router.ts:178·187·196 3라우트 beforeLoad `requireAuthAndPasswordChanged` → `composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled)`.

**REFACTOR**: 4-가드 조합을 지역 상수 `requireSystemAdminFull`로 추출(다른 admin 라우트도 참조 검토 — 스코프 넘으면 보류).

**검증**: `node_modules/.bin/vitest run src/router.admin-guards.test.tsx` + typecheck.

### Task 9. E2E — 허브·리다이렉트·랜드마크·탐색경로 (PL-4~8 통합)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/settings-admin-hub.spec.ts`, (기존 landmark spec 있으면 확장)]
- depends-on: [4, 5, 6, 7, 8]

**시나리오**(happy + 가드):
- 설정 허브: `/settings` 로드 → 카드 목록 보임 → 카드 클릭 시 하위 라우트 이동(`exact:true` 조회).
- 관리 허브: admin 세션 `/admin` → 카드 보임. **비-admin `/admin`·`/admin/workflow-schemes` 직접 → /dashboard 리다이렉트**(S6·E1).
- TopBar 설정아이콘 클릭 → `/settings` 랜딩.
- 랜드마크: `issues.$key`·`admin.workflow-schemes` 페이지에서 `main` 정확히 1개(`page.locator('main')` count 1).
- 탐색경로: breadcrumb 넘긴 페이지에서 `nav[aria-label="탐색 경로"]` 표기·링크 이동.

**검증**: `node_modules/.bin/playwright test e2e/settings-admin-hub.spec.ts`(바이너리 직접호출 — [[e2e-playwright-filter-arg-drop]]).

## Plan 메타

- task 수: 9 (PL-1~9 매핑, E2E 포함)
- 예상 wave: ~6 (router.ts 공유로 T4→T5→T8 직렬 강제). Wave1: T1·T2·T7(독립 병렬 가능하나 공유 worktree 안전상 controller 판단으로 직렬화 가능) / Wave2: T3 / Wave3: T4 / Wave4: T5 / Wave5: T6·T8 / Wave6: T9.
- ★안전: PR11/PR12 병렬 커밋 레이스 선례 → **직렬 dispatch 우선**, controller가 각 task git diff 실물 검증(sub-agent 보고 불신).
- TDD 강제: yes. PL-8 음성 mutation 가드 필수.
- 추가 검증: typecheck(tsconfig.app) · lint · vitest 전체 · playwright(로컬, CI e2e 잡 없음).
- FR 총수 불변 129 · 백엔드/DB 0변경 → fr-index/verify-master-plan 대상 아님.

## 리뷰 결과 (← /bts-review-plan 채움)
