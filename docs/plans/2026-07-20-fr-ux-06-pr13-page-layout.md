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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
