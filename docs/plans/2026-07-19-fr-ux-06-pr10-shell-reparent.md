# FR-UX-06 Phase 3 PR10 — _shell pathless layout 재부모화

> slug: fr-ux-06-pr10-shell-reparent
> type: refactor (fast-track 아님 — 위험 구조 리팩터)
> agent: frontend-engineer
> primary_bc: personalization (물리 apps/web)
> 생성: 2026-07-19

## Brief

FR-UX-06 Jira 재개편 Phase 3의 핵심. `apps/web/src/router.ts`에서 loginRoute를 제외한 라우트들을
새 pathless route `_shell`(id 시작 `_` = URL 기여 0) 밑으로 재부모화한다. **`_shell` 컴포넌트는 이번
PR에선 passthrough(`<Outlet/>`, DOM 추가 0)** — 전역 사이드바는 PR11.

**목적.** 전역 사이드바(Jira 신형 통합 사이드바)를 붙일 **구조적 앵커** 확보.

**무해 증거.** 렌더 결과 동일 → 기존 E2E + `router.test.tsx` 무수정 전원 통과가 무해함의 순수한 증거.
PR10(재부모화)과 PR11(사이드바)을 반드시 분리해야 원인 추적이 가능([[tanstack-pathless-layout-router-test-blind]]).

### 실측 (2026-07-19, main=2a6d02a41)
- 라우트 **53개** 전부 `getParentRoute: () => rootRoute` (flat). `createRoute` 53건.
- routeTree 조립부 `router.ts:681` `rootRoute.addChildren([...])`, `createRouter` `:783`.
- `rootRoute`(`:62`)는 `component: RootLayout`, **beforeLoad 없음**.
- beforeLoad 가드 52개(거의 전 라우트 자체 보유). loginRoute(`:73`) = `redirectIfAuth`+`requireAuth:false`.
- `RootLayout`(`__root.tsx`)이 **`isAuthenticated`로 크롬(Header·CommandPalette·단축키) 분기** — 경로 무관.
- PR9(#294)로 route-id 결합 제거 완료 → prep 스텝 불필요.

### 설계 결정 (게이트1 확인 대상)
- **passthrough `_shell`**: 컴포넌트 = 바 `<Outlet/>`(wrapper div 없음). 크롬은 RootLayout에 그대로 → 완벽 렌더 동일. 크롬→사이드바 이관은 PR11.
- **_shell 멤버십**: loginRoute만 rootRoute 직속 유지, 나머지 52개 `_shell` 밑. (passthrough라 렌더엔 무영향, PR11 사이드바 게이팅은 isAuthenticated로 처리.)

### 🔴 절대 실패 경로 2개 (금지)
1. **loginRoute를 `_shell` 안에 넣기 금지** — `already-authed.spec.ts`가 `redirectIfAuth` 검증. loginRoute는 rootRoute 직속.
2. **가드 hoist 금지** — 52개 beforeLoad를 `_shell` 하나로 올리지 말 것(평가 순서 바뀌어 `routeGuard.test.tsx` 깨짐). `_shell`엔 beforeLoad 없음, 각 라우트 가드 그대로.

## 도메인 정리 (← /bts-domain)

## 스펙 (← /bts-spec)

**SKIP 예정 (신규 기능 아님, 재개편 스펙 `docs/design/fr-ux-06-jira-redesign.md` + 메모리가 정본).**

## Brainstorming Check (← /bts-spec Phase B)

**SKIP 예정.**

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan)
